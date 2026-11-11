/*
 * steamcache: a pass-through xinput1_3.dll that also answers two hot Steam calls from memory.
 *
 * Why: under Wine on Android every Steam API call a game makes is a synchronous pipe round trip to
 * the real Steam client in another process (~3 ms each). Call of Duty: Black Ops polls
 * ISteamUser::BLoggedOn() and ISteamUser::GetSteamID() for every UI element it draws; on the
 * Create-a-Class screen that is ~50 calls per frame, which held the screen at ~5 fps while the GPU
 * sat idle. Neither answer changes during a session, so they are cached here and refreshed at most
 * a few times a second.
 *
 * How: the game loads xinput1_3.dll at startup. Every XInput export is forwarded to Wine's
 * xinput1_4 / xinput9_1_0 (see steamcache.def), so input is untouched. A background thread waits
 * for steam_api.dll to hand out its ISteamUser and patches vtable slots 1 (BLoggedOn) and
 * 2 (GetSteamID) to the cached versions below. Nothing in the game's own files is modified;
 * deleting this DLL restores the original behaviour.
 *
 * Also, when the app launched the game with Wine debug logging off (WINEDEBUG=-all), the game's debug
 * prints are dropped: Wine turns every OutputDebugString into an exception (for debuggers), which the
 * 32-bit emulation makes expensive, and with logging off nothing reads them. Black Ops prints a
 * stream of them while loading. With any debug channel on, they are left alone.
 *
 * And the game's .iwd archives get a self-checking read-ahead cache (iwdcache.c, iwdhooks.c): Black
 * Ops reads them in 512-byte pieces with a seek before each, ~270,000 calls at startup.
 *
 * Finally, a clean exit after quitting: under Wine, Black Ops crashes at the very end of its own
 * shutdown. It has already shut Steam down and unloaded every fastfile ("PMem_Free( ..., High )" is
 * the last console line, cut off mid-word), and then its crash handler opens a "Fatal Error" console
 * the player has to close. Once the game has shut Steam down, a crash (access violation, illegal
 * instruction and the like) in the game process ends it quietly with exit code 0 instead. Faults
 * raised inside kernel32/kernelbase are left alone, because Wine's IsBadReadPtr-style probes there
 * raise and handle access violations themselves. While Steam is up, nothing changes.
 */
#include <windows.h>
#include <stdint.h>
#include <string.h>

void install_iwd_cache(void); /* iwdhooks.c: read-ahead cache for the game's .iwd archives */

typedef _Bool(__attribute__((thiscall)) * BLoggedOnFn)(void *self);
/* MSVC returns a class by value from a member function through a hidden pointer passed on the stack
 * after `this`; the callee pops it and returns the pointer. */
typedef uint64_t *(__attribute__((thiscall)) * GetSteamIdFn)(void *self, uint64_t *ret);

static BLoggedOnFn real_logged_on;
static GetSteamIdFn real_get_steam_id;
static CRITICAL_SECTION lock;
/* steam_api.dll's SteamUser(): an in-process global lookup that returns NULL once SteamAPI_Shutdown
 * has run, so it tells us whether Steam is still up without a call to the Steam client. */
static void *(__cdecl *steam_user_fn)(void);

static int steam_alive(void)
{
    return steam_user_fn && steam_user_fn();
}

#define REFRESH_MS 250

static DWORD logged_on_at;
static _Bool logged_on;
static DWORD steam_id_at;
static uint64_t steam_id;

static _Bool __attribute__((thiscall)) cached_logged_on(void *self)
{
    /* After the game shuts Steam down (on exit), never answer "signed in" from the cache, and don't
     * call into a Steam client that is going away. */
    if (!steam_alive()) {
        EnterCriticalSection(&lock);
        logged_on = 0;
        steam_id = 0;
        LeaveCriticalSection(&lock);
        return 0;
    }
    DWORD now = GetTickCount();
    EnterCriticalSection(&lock);
    _Bool have = logged_on && now - logged_on_at < REFRESH_MS;
    LeaveCriticalSection(&lock);
    if (have) return 1;

    /* Only "signed in" is cached: a signed-out answer is asked again every time so the game sees a
     * reconnect as soon as Steam reports it. */
    _Bool value = real_logged_on(self);
    EnterCriticalSection(&lock);
    logged_on = value;
    logged_on_at = now;
    LeaveCriticalSection(&lock);
    return value;
}

static uint64_t *__attribute__((thiscall)) cached_get_steam_id(void *self, uint64_t *ret)
{
    if (!steam_alive()) {
        *ret = 0;
        return ret;
    }
    DWORD now = GetTickCount();
    EnterCriticalSection(&lock);
    uint64_t value = steam_id;
    _Bool have = value != 0 && now - steam_id_at < REFRESH_MS;
    LeaveCriticalSection(&lock);
    if (have) {
        *ret = value;
        return ret;
    }

    uint64_t fresh = 0;
    real_get_steam_id(self, &fresh);
    EnterCriticalSection(&lock);
    steam_id = fresh;
    steam_id_at = now;
    LeaveCriticalSection(&lock);
    *ret = fresh;
    return ret;
}

static int patch_slot(void **slot, void *replacement, void **original)
{
    DWORD old;
    if (!VirtualProtect(slot, sizeof(void *), PAGE_READWRITE, &old)) return 0;
    *original = *slot;
    *slot = replacement; /* aligned pointer store: atomic for other threads calling through it */
    VirtualProtect(slot, sizeof(void *), old, &old);
    FlushInstructionCache(GetCurrentProcess(), slot, sizeof(void *));
    return 1;
}

/* Turns a one-argument stdcall function into an immediate return ("ret 4"). */
static void make_return(void *fn)
{
    static const unsigned char ret4[] = {0xC2, 0x04, 0x00};
    DWORD old;
    if (!fn || !VirtualProtect(fn, sizeof(ret4), PAGE_EXECUTE_READWRITE, &old)) return;
    memcpy(fn, ret4, sizeof(ret4));
    VirtualProtect(fn, sizeof(ret4), old, &old);
    FlushInstructionCache(GetCurrentProcess(), fn, sizeof(ret4));
}

static void drop_debug_prints_when_logging_off(void)
{
    char value[16];
    DWORD n = GetEnvironmentVariableA("WINEDEBUG", value, sizeof(value));
    if (n == 0 || n >= sizeof(value) || lstrcmpiA(value, "-all") != 0) return;
    /* Wine has its own copies in kernel32 and kernelbase; the game may reach either. */
    const char *modules[] = {"kernel32.dll", "kernelbase.dll"};
    const char *names[] = {"OutputDebugStringA", "OutputDebugStringW"};
    for (int m = 0; m < 2; m++) {
        HMODULE module = GetModuleHandleA(modules[m]);
        if (!module) continue;
        for (int i = 0; i < 2; i++) make_return((void *)GetProcAddress(module, names[i]));
    }
}

/* --- Clean exit when the game crashes after it has shut Steam down ------------------------------ */

typedef struct {
    uintptr_t base, end;
} module_range;

static module_range probe_modules[2]; /* kernelbase, kernel32 */

static module_range range_of(const char *name)
{
    module_range r = {0, 0};
    unsigned char *base = (unsigned char *)GetModuleHandleA(name);
    if (!base) return r;
    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *)base;
    IMAGE_NT_HEADERS *nt = (IMAGE_NT_HEADERS *)(base + dos->e_lfanew);
    r.base = (uintptr_t)base;
    r.end = r.base + nt->OptionalHeader.SizeOfImage;
    return r;
}

static int fatal_code(DWORD code)
{
    switch (code) {
    case EXCEPTION_ACCESS_VIOLATION:
    case EXCEPTION_IN_PAGE_ERROR:
    case EXCEPTION_ILLEGAL_INSTRUCTION:
    case EXCEPTION_PRIV_INSTRUCTION:
    case EXCEPTION_INT_DIVIDE_BY_ZERO:
    case EXCEPTION_STACK_OVERFLOW:
    case EXCEPTION_ARRAY_BOUNDS_EXCEEDED:
        return 1;
    default:
        return 0;
    }
}

/* Straight to Wine's log (the app's log): OutputDebugString may have been turned into a no-op above. */
static int(__cdecl *dbg_output)(const char *);

/* Names where a crash happened (module and offset, thread, and for an access violation what was
 * touched), so a crash during play can be traced from an ordinary app log. At most 64 per session. */
static volatile LONG crashes_logged;

static void log_crash(const EXCEPTION_RECORD *rec, const char *what)
{
    if (!dbg_output || InterlockedIncrement(&crashes_logged) > 64) return;
    char module[MAX_PATH] = "?";
    HMODULE m = NULL;
    uintptr_t at = (uintptr_t)rec->ExceptionAddress;
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCSTR)rec->ExceptionAddress, &m) &&
        m) {
        char path[MAX_PATH];
        DWORD n = GetModuleFileNameA(m, path, sizeof(path));
        if (n && n < sizeof(path)) {
            const char *name = path + n;
            while (name > path && name[-1] != '\\' && name[-1] != '/') name--;
            lstrcpynA(module, name, sizeof(module));
        }
    }
    char access[64] = "";
    if (rec->ExceptionCode == EXCEPTION_ACCESS_VIOLATION && rec->NumberParameters >= 2)
        wsprintfA(access, ", %s %08lx", rec->ExceptionInformation[0] == 1 ? "writing" : rec->ExceptionInformation[0] == 8 ? "executing" : "reading",
                  (unsigned long)rec->ExceptionInformation[1]);
    char line[400];
    wsprintfA(line, "steamcache: %s: exception %08lx at %s+0x%lx (%p), thread %04lx%s\n", what,
              (unsigned long)rec->ExceptionCode, module, (unsigned long)(m ? at - (uintptr_t)m : at),
              rec->ExceptionAddress, (unsigned long)GetCurrentThreadId(), access);
    dbg_output(line);
}

static LONG CALLBACK quit_crash_handler(EXCEPTION_POINTERS *info)
{
    const EXCEPTION_RECORD *rec = info->ExceptionRecord;
    if (!fatal_code(rec->ExceptionCode)) {
        /* Not ended here, only named: debug breaks, failed security checks and C++ throws. */
        DWORD code = rec->ExceptionCode;
        if (code == EXCEPTION_BREAKPOINT || code == 0xC0000409 /* STATUS_STACK_BUFFER_OVERRUN */ ||
            code == 0xE06D7363 /* MSVC C++ throw */)
            log_crash(rec, "exception during play (left to the game)");
        return EXCEPTION_CONTINUE_SEARCH;
    }
    uintptr_t at = (uintptr_t)rec->ExceptionAddress;
    for (int i = 0; i < 2; i++)
        if (at >= probe_modules[i].base && at < probe_modules[i].end) return EXCEPTION_CONTINUE_SEARCH;
    /* Only once Steam was up and the game has shut it down: that happens only when it quits. */
    if (!steam_user_fn || steam_alive()) {
        log_crash(rec, "crash during play (left to the game)");
        return EXCEPTION_CONTINUE_SEARCH;
    }
    log_crash(rec, "game crashed after quitting; ending it cleanly");
    TerminateProcess(GetCurrentProcess(), 0);
    return EXCEPTION_CONTINUE_SEARCH;
}

static DWORD WINAPI patch_thread(void *unused)
{
    (void)unused;
    /* steam_api.dll is imported by the game, but SteamUser() only returns the interface once
     * SteamAPI_Init has run. Poll for up to ten minutes. */
    for (int i = 0; i < 3000; i++) {
        HMODULE steam_api = GetModuleHandleA("steam_api.dll");
        if (steam_api) {
            void *(__cdecl * steam_user)(void) = (void *(__cdecl *)(void))(void (*)(void))GetProcAddress(steam_api, "SteamUser");
            void *user = steam_user ? steam_user() : NULL;
            if (user) {
                steam_user_fn = steam_user;
                void **vtable = *(void ***)user;
                if (vtable[1] == (void *)cached_logged_on) return 0;
                patch_slot(&vtable[1], (void *)cached_logged_on, (void **)&real_logged_on);
                patch_slot(&vtable[2], (void *)cached_get_steam_id, (void **)&real_get_steam_id);
                OutputDebugStringA("steamcache: ISteamUser BLoggedOn/GetSteamID cached\n");
                return 0;
            }
        }
        Sleep(200);
    }
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, void *reserved)
{
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(instance);
        InitializeCriticalSection(&lock);
        /* Here, not in the thread: the game imports this DLL, so DllMain runs before any game thread
         * exists and none can be inside the functions while their first bytes are rewritten. */
        drop_debug_prints_when_logging_off();
        install_iwd_cache();
        probe_modules[0] = range_of("kernelbase.dll");
        probe_modules[1] = range_of("kernel32.dll");
        dbg_output = (int(__cdecl *)(const char *))(void (*)(void))GetProcAddress(GetModuleHandleA("ntdll.dll"), "__wine_dbg_output");
        /* First in line, so it runs before the game's own handlers turn the crash into a dialog. */
        AddVectoredExceptionHandler(1, quit_crash_handler);
        HANDLE thread = CreateThread(NULL, 0, patch_thread, NULL, 0, NULL);
        if (thread) CloseHandle(thread);
    }
    return TRUE;
}
