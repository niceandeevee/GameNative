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
 */
#include <windows.h>
#include <stdint.h>

typedef _Bool(__attribute__((thiscall)) * BLoggedOnFn)(void *self);
/* MSVC returns a class by value from a member function through a hidden pointer passed on the stack
 * after `this`; the callee pops it and returns the pointer. */
typedef uint64_t *(__attribute__((thiscall)) * GetSteamIdFn)(void *self, uint64_t *ret);

static BLoggedOnFn real_logged_on;
static GetSteamIdFn real_get_steam_id;
static CRITICAL_SECTION lock;

#define REFRESH_MS 250

static DWORD logged_on_at;
static _Bool logged_on;
static DWORD steam_id_at;
static uint64_t steam_id;

static _Bool __attribute__((thiscall)) cached_logged_on(void *self)
{
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
        HANDLE thread = CreateThread(NULL, 0, patch_thread, NULL, 0, NULL);
        if (thread) CloseHandle(thread);
    }
    return TRUE;
}
