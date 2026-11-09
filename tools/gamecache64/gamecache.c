/*
 * gamecache: a pass-through psapi.dll for 64-bit games that load slowly under Wine because of
 * many tiny file operations (Star Wars: Empire at War: Gold Pack, StarWarsG.exe).
 *
 * psapi.dll is in the game's static imports (StarWarsG.exe pulls it in while it starts, before any
 * file is read), and all its functions are kernel32's K32* ones, so gamecache.def forwards every
 * export there. (It was a version.dll first; the game never loads that one itself.) On load, the
 * game's own import table (not kernel32 itself: in arm64ec Wine those functions are ARM64 code
 * that x86 jumps can't patch) is pointed at the hooks below:
 *
 * - Read-ahead for archives: .meg files opened read-only are served from 128 KB blocks through
 *   the self-checking cache shared with the 32-bit steamcache (../steamcache/iwdcache.c). Empire
 *   at War reads its archive indexes 64 bytes at a time with a seek before each read.
 * - Missing directories: opening a file in a directory under the game folder that doesn't exist
 *   fails at once with ERROR_PATH_NOT_FOUND (dircache.c). The game probes every model as
 *   Data\Art\Models\<name> under two roots that don't exist on a Steam install before reading
 *   it from the archives.
 *
 * The exe and every DLL loaded from the game folder are patched. The read cache is only switched on
 * when every call that reads or moves a file position through those import tables can be hooked;
 * otherwise only the directory cache runs. A status line goes to
 * stderr (the Wine log) when the hooks are installed and when the process exits.
 */
#define PSAPI_VERSION 2
#include <windows.h>
#include <psapi.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../steamcache/iwdcache.h"
#include "dircache.h"

extern IMAGE_DOS_HEADER __ImageBase;

typedef HANDLE(WINAPI *CreateFileWFn)(LPCWSTR, DWORD, DWORD, LPSECURITY_ATTRIBUTES, DWORD, DWORD, HANDLE);
typedef HANDLE(WINAPI *CreateFileAFn)(LPCSTR, DWORD, DWORD, LPSECURITY_ATTRIBUTES, DWORD, DWORD, HANDLE);
typedef BOOL(WINAPI *ReadFileFn)(HANDLE, LPVOID, DWORD, LPDWORD, LPOVERLAPPED);
typedef DWORD(WINAPI *SetFilePointerFn)(HANDLE, LONG, PLONG, DWORD);
typedef BOOL(WINAPI *SetFilePointerExFn)(HANDLE, LARGE_INTEGER, PLARGE_INTEGER, DWORD);
typedef BOOL(WINAPI *CloseHandleFn)(HANDLE);
typedef BOOL(WINAPI *CreateDirectoryWFn)(LPCWSTR, LPSECURITY_ATTRIBUTES);
typedef BOOL(WINAPI *CreateDirectoryAFn)(LPCSTR, LPSECURITY_ATTRIBUTES);

static CreateFileWFn real_CreateFileW;
static CreateFileAFn real_CreateFileA;
static ReadFileFn real_ReadFile;
static SetFilePointerFn real_SetFilePointer;
static SetFilePointerExFn real_SetFilePointerEx;
static CloseHandleFn real_CloseHandle;
static CreateDirectoryWFn real_CreateDirectoryW;
static CreateDirectoryAFn real_CreateDirectoryA;

/* What the kernel functions resolve to, for calls the cache makes itself. */
static ReadFileFn k_ReadFile;
static SetFilePointerExFn k_SetFilePointerEx;

static iwd_cache cache;
static dc_cache dirs;
static CRITICAL_SECTION lock;
static int read_cache_on;
static int dir_cache_on;
static volatile LONG served_reads, failed_probes, tracked_files;

static void log_line(const char *msg)
{
    HANDLE err = GetStdHandle(STD_ERROR_HANDLE);
    DWORD written;
    if (err && err != INVALID_HANDLE_VALUE) WriteFile(err, msg, (DWORD)strlen(msg), &written, NULL);
}

/* ---- file system access for the caches (always the real kernel functions) ---- */

static int64_t os_pread(void *h, int64_t offset, void *buf, uint32_t count)
{
    LARGE_INTEGER at;
    at.QuadPart = offset;
    DWORD got = 0;
    if (!k_SetFilePointerEx(h, at, NULL, FILE_BEGIN)) return -1;
    if (!k_ReadFile(h, buf, count, &got, NULL)) return -1;
    return got;
}

static int os_seek(void *h, int64_t offset)
{
    LARGE_INTEGER at;
    at.QuadPart = offset;
    return k_SetFilePointerEx(h, at, NULL, FILE_BEGIN) ? 0 : -1;
}

static uint64_t file_id(HANDLE h)
{
    BY_HANDLE_FILE_INFORMATION info;
    if (!GetFileInformationByHandle(h, &info)) return 0;
    uint64_t index = ((uint64_t)info.nFileIndexHigh << 32) | info.nFileIndexLow;
    return index ^ ((uint64_t)info.dwVolumeSerialNumber << 21) ^ 0x9e3779b97f4a7c15ull;
}

static int os_same_file(void *h, uint64_t id) { return file_id(h) == id; }
static void *os_alloc(uint32_t size) { return HeapAlloc(GetProcessHeap(), 0, size); }
static void os_release(void *p) { HeapFree(GetProcessHeap(), 0, p); }

static int os_dir_state(const wchar_t *dir)
{
    DWORD attr = GetFileAttributesW(dir);
    if (attr == INVALID_FILE_ATTRIBUTES) {
        DWORD e = GetLastError();
        return (e == ERROR_FILE_NOT_FOUND || e == ERROR_PATH_NOT_FOUND) ? DC_MISSING : DC_UNKNOWN;
    }
    return (attr & FILE_ATTRIBUTE_DIRECTORY) ? DC_EXISTS : DC_MISSING;
}

static uint64_t os_now_ms(void) { return GetTickCount64(); }

/* ---- hooks ---- */

static int is_cached_archive(LPCWSTR name)
{
    if (!name) return 0;
    size_t n = wcslen(name);
    if (n < 4) return 0;
    const WCHAR *e = name + n - 4;
    return e[0] == L'.' && (e[1] | 0x20) == L'm' && (e[2] | 0x20) == L'e' && (e[3] | 0x20) == L'g';
}

static int read_only_open(DWORD access, DWORD creation)
{
    const DWORD writes = GENERIC_WRITE | GENERIC_ALL | FILE_WRITE_DATA | FILE_APPEND_DATA;
    return !(access & writes) && creation == OPEN_EXISTING;
}

/* 1 when the open can fail right away because its directory is known to be missing. */
static int probe_misses(LPCWSTR name, DWORD access, DWORD creation)
{
    if (!dir_cache_on || !name || !read_only_open(access, creation)) return 0;
    WCHAR full[DC_MAX_PATH];
    DWORD n = GetFullPathNameW(name, DC_MAX_PATH, full, NULL);
    if (!n || n >= DC_MAX_PATH) return 0;
    EnterCriticalSection(&lock);
    int missing = dc_parent_missing(&dirs, full);
    LeaveCriticalSection(&lock);
    if (missing) InterlockedIncrement(&failed_probes);
    return missing;
}

static void maybe_track(HANDLE h, LPCWSTR name, DWORD access, DWORD creation, DWORD flags)
{
    if (!read_cache_on || h == INVALID_HANDLE_VALUE || !is_cached_archive(name)) return;
    if (!(access & (GENERIC_READ | FILE_READ_DATA)) || !read_only_open(access, creation) ||
        (flags & (FILE_FLAG_OVERLAPPED | FILE_FLAG_NO_BUFFERING)))
        return;
    DWORD err = GetLastError();
    LARGE_INTEGER size;
    uint64_t id = file_id(h);
    if (id && GetFileSizeEx(h, &size)) {
        EnterCriticalSection(&lock);
        if (iwd_track(&cache, h, id, size.QuadPart) == 0) InterlockedIncrement(&tracked_files);
        LeaveCriticalSection(&lock);
    }
    SetLastError(err);
}

/*
 * Intro movies. Before playing a movie the game looks for it as a loose file in
 * <exe dir>\DATA\ART\MOVIES\BINKED\ and only then reads it from Movies.meg, so handing it an empty
 * file there (a zero-byte file in the temp folder) leaves nothing to play. Every movie
 * the game opens is logged, so the names of the others can be added here.
 */
/* The LucasArts logo is confirmed by a file trace; the Petroglyph logo that follows isn't in a trace
 * yet, so the likely spellings are listed (a name that doesn't occur does nothing). */
static const WCHAR *const skipped_movies[] = {
    L"lucasartslogo.bik", L"petroglyphlogo.bik", L"petroglyph_logo.bik", L"petroglyph.bik", L"pglogo.bik",
};
static volatile LONG movies_logged;

/* 0: not a movie; 1: a movie to play normally; 2: an intro to skip. */
static int movie_kind(LPCWSTR name, DWORD access, DWORD creation)
{
    if (!name || !read_only_open(access, creation)) return 0;
    size_t n = wcslen(name);
    if (n < 4 || name[n - 4] != L'.' || (name[n - 3] | 0x20) != L'b' || (name[n - 2] | 0x20) != L'i' ||
        (name[n - 1] | 0x20) != L'k')
        return 0;
    const WCHAR *base = name + n;
    while (base > name && base[-1] != L'\\' && base[-1] != L'/') base--;
    char msg[300];
    if (InterlockedIncrement(&movies_logged) <= 64) {
        snprintf(msg, sizeof(msg), "gamecache: movie %ls\n", base);
        log_line(msg);
    }
    for (size_t i = 0; i < sizeof(skipped_movies) / sizeof(skipped_movies[0]); i++)
        if (!_wcsicmp(base, skipped_movies[i])) return 2;
    return 1;
}

/* A zero-byte file in the temp folder, opened read-only like the game asked for. */
static HANDLE open_empty(void)
{
    static WCHAR path[MAX_PATH];
    if (!path[0]) {
        WCHAR dir[MAX_PATH];
        DWORD n = GetTempPathW(MAX_PATH, dir);
        if (!n || n + 32 >= MAX_PATH) return INVALID_HANDLE_VALUE;
        swprintf(path, MAX_PATH, L"%lsgamecache_empty.bik", dir);
        HANDLE w = CreateFileW(path, GENERIC_WRITE, FILE_SHARE_READ, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (w == INVALID_HANDLE_VALUE) {
            path[0] = 0;
            return INVALID_HANDLE_VALUE;
        }
        CloseHandle(w);
    }
    return CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
}

static HANDLE WINAPI hook_CreateFileW(LPCWSTR name, DWORD access, DWORD share, LPSECURITY_ATTRIBUTES sa,
                                      DWORD creation, DWORD flags, HANDLE templ)
{
    if (movie_kind(name, access, creation) == 2) {
        HANDLE h = open_empty();
        if (h != INVALID_HANDLE_VALUE) return h;
    }
    if (probe_misses(name, access, creation)) {
        SetLastError(ERROR_PATH_NOT_FOUND);
        return INVALID_HANDLE_VALUE;
    }
    HANDLE h = real_CreateFileW(name, access, share, sa, creation, flags, templ);
    maybe_track(h, name, access, creation, flags);
    return h;
}

static HANDLE WINAPI hook_CreateFileA(LPCSTR name, DWORD access, DWORD share, LPSECURITY_ATTRIBUTES sa,
                                      DWORD creation, DWORD flags, HANDLE templ)
{
    WCHAR wide[DC_MAX_PATH];
    int ok = name && MultiByteToWideChar(CP_ACP, 0, name, -1, wide, DC_MAX_PATH) > 0;
    if (ok && movie_kind(wide, access, creation) == 2) {
        HANDLE h = open_empty();
        if (h != INVALID_HANDLE_VALUE) return h;
    }
    if (ok && probe_misses(wide, access, creation)) {
        SetLastError(ERROR_PATH_NOT_FOUND);
        return INVALID_HANDLE_VALUE;
    }
    HANDLE h = real_CreateFileA(name, access, share, sa, creation, flags, templ);
    if (ok) maybe_track(h, wide, access, creation, flags);
    return h;
}

static BOOL WINAPI hook_ReadFile(HANDLE h, LPVOID buf, DWORD count, LPDWORD read, LPOVERLAPPED ov)
{
    EnterCriticalSection(&lock);
    iwd_file *f = iwd_find(&cache, h);
    if (f && ov) {
        iwd_forget(&cache, f, 1); /* an offset read moves the real position itself */
    } else if (f) {
        uint32_t done = 0;
        int served = iwd_read(&cache, f, buf, count, &done);
        LeaveCriticalSection(&lock);
        if (served) {
            if (read) *read = done;
            InterlockedIncrement(&served_reads);
            return TRUE;
        }
        return real_ReadFile(h, buf, count, read, ov); /* given back at the game's position */
    }
    LeaveCriticalSection(&lock);
    return real_ReadFile(h, buf, count, read, ov);
}

static BOOL WINAPI hook_SetFilePointerEx(HANDLE h, LARGE_INTEGER distance, PLARGE_INTEGER newpos, DWORD method)
{
    EnterCriticalSection(&lock);
    iwd_file *f = iwd_find(&cache, h);
    if (f) {
        int64_t pos = 0;
        int ok = method <= FILE_END && iwd_seek(&cache, f, distance.QuadPart, (int)method, &pos);
        LeaveCriticalSection(&lock);
        if (!ok) {
            SetLastError(method <= FILE_END ? ERROR_NEGATIVE_SEEK : ERROR_INVALID_PARAMETER);
            return FALSE;
        }
        if (newpos) newpos->QuadPart = pos;
        return TRUE;
    }
    LeaveCriticalSection(&lock);
    return real_SetFilePointerEx(h, distance, newpos, method);
}

static DWORD WINAPI hook_SetFilePointer(HANDLE h, LONG low, PLONG high, DWORD method)
{
    EnterCriticalSection(&lock);
    iwd_file *f = iwd_find(&cache, h);
    if (f) {
        int64_t distance = high ? (int64_t)(((uint64_t)(uint32_t)*high << 32) | (uint32_t)low) : (int64_t)low;
        int64_t pos = 0;
        int ok = method <= FILE_END && iwd_seek(&cache, f, distance, (int)method, &pos);
        LeaveCriticalSection(&lock);
        if (!ok) {
            SetLastError(method <= FILE_END ? ERROR_NEGATIVE_SEEK : ERROR_INVALID_PARAMETER);
            return INVALID_SET_FILE_POINTER;
        }
        if (high) *high = (LONG)(pos >> 32);
        SetLastError(NO_ERROR); /* INVALID_SET_FILE_POINTER can be a valid low half */
        return (DWORD)pos;
    }
    LeaveCriticalSection(&lock);
    return real_SetFilePointer(h, low, high, method);
}

static BOOL WINAPI hook_CloseHandle(HANDLE h)
{
    EnterCriticalSection(&lock);
    iwd_file *f = iwd_find(&cache, h);
    if (f) iwd_forget(&cache, f, 0);
    LeaveCriticalSection(&lock);
    return real_CloseHandle(h);
}

static BOOL WINAPI hook_CreateDirectoryW(LPCWSTR name, LPSECURITY_ATTRIBUTES sa)
{
    BOOL ok = real_CreateDirectoryW(name, sa);
    if (ok) {
        EnterCriticalSection(&lock);
        dc_clear(&dirs);
        LeaveCriticalSection(&lock);
    }
    return ok;
}

static BOOL WINAPI hook_CreateDirectoryA(LPCSTR name, LPSECURITY_ATTRIBUTES sa)
{
    BOOL ok = real_CreateDirectoryA(name, sa);
    if (ok) {
        EnterCriticalSection(&lock);
        dc_clear(&dirs);
        LeaveCriticalSection(&lock);
    }
    return ok;
}

/* ---- import table patching ---- */

typedef struct {
    const char *name;
    void *hook;
    void **real;
    int found;
} hook_def;

static hook_def hooks[] = {
    {"CreateFileW", (void *)hook_CreateFileW, (void **)&real_CreateFileW, 0},
    {"CreateFileA", (void *)hook_CreateFileA, (void **)&real_CreateFileA, 0},
    {"ReadFile", (void *)hook_ReadFile, (void **)&real_ReadFile, 0},
    {"SetFilePointer", (void *)hook_SetFilePointer, (void **)&real_SetFilePointer, 0},
    {"SetFilePointerEx", (void *)hook_SetFilePointerEx, (void **)&real_SetFilePointerEx, 0},
    {"CloseHandle", (void *)hook_CloseHandle, (void **)&real_CloseHandle, 0},
    {"CreateDirectoryW", (void *)hook_CreateDirectoryW, (void **)&real_CreateDirectoryW, 0},
    {"CreateDirectoryA", (void *)hook_CreateDirectoryA, (void **)&real_CreateDirectoryA, 0},
};
#define N_HOOKS (sizeof(hooks) / sizeof(hooks[0]))

/* Calls that would move or read a file position without passing through the hooks above. */
static const char *const unhookable[] = {"DuplicateHandle", "ReadFileEx", "ReadFileScatter", "ReOpenFile"};

static int kernel_dll(const char *name)
{
    return !_stricmp(name, "kernel32.dll") || !_stricmp(name, "kernelbase.dll") ||
           !_strnicmp(name, "api-ms-win-core-file-", 21) || !_strnicmp(name, "api-ms-win-core-handle-", 23);
}

static hook_def *find_hook(const char *name)
{
    for (size_t i = 0; i < N_HOOKS; i++)
        if (!strcmp(hooks[i].name, name)) return &hooks[i];
    return NULL;
}

/* Patches the module's import address table. Returns 0 if a call in `unhookable` is imported. */
static int patch_imports(HMODULE mod)
{
    BYTE *base = (BYTE *)mod;
    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *)base;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return 0;
    IMAGE_NT_HEADERS *nt = (IMAGE_NT_HEADERS *)(base + dos->e_lfanew);
    IMAGE_DATA_DIRECTORY dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (!dir.VirtualAddress) return 0;
    int safe = 1;
    for (IMAGE_IMPORT_DESCRIPTOR *d = (IMAGE_IMPORT_DESCRIPTOR *)(base + dir.VirtualAddress); d->Name; d++) {
        const char *dll = (const char *)(base + d->Name);
        if (!kernel_dll(dll) || !d->OriginalFirstThunk) continue;
        IMAGE_THUNK_DATA *names = (IMAGE_THUNK_DATA *)(base + d->OriginalFirstThunk);
        IMAGE_THUNK_DATA *iat = (IMAGE_THUNK_DATA *)(base + d->FirstThunk);
        for (; names->u1.AddressOfData; names++, iat++) {
            if (IMAGE_SNAP_BY_ORDINAL(names->u1.Ordinal)) continue;
            const char *fn = (const char *)((IMAGE_IMPORT_BY_NAME *)(base + names->u1.AddressOfData))->Name;
            for (size_t i = 0; i < sizeof(unhookable) / sizeof(unhookable[0]); i++)
                if (!strcmp(fn, unhookable[i])) safe = 0;
            hook_def *hk = find_hook(fn);
            if (!hk) continue;
            if (!*hk->real) *hk->real = (void *)iat->u1.Function;
            DWORD old;
            if (!VirtualProtect(&iat->u1.Function, sizeof(void *), PAGE_READWRITE, &old)) continue;
            iat->u1.Function = (ULONG_PTR)hk->hook;
            VirtualProtect(&iat->u1.Function, sizeof(void *), old, &old);
            hk->found = 1;
        }
    }
    return safe;
}

static void install(void)
{
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    k_ReadFile = (ReadFileFn)(void *)GetProcAddress(k32, "ReadFile");
    k_SetFilePointerEx = (SetFilePointerExFn)(void *)GetProcAddress(k32, "SetFilePointerEx");
    if (!k_ReadFile || !k_SetFilePointerEx) return;

    WCHAR exe[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, exe, MAX_PATH);
    if (!n || n >= MAX_PATH) return;
    /* Root = the folder above the exe's folder (Empire at War: <install>\corruption\StarWarsG.exe). */
    for (int strip = 0; strip < 2; strip++) {
        WCHAR *slash = wcsrchr(exe, L'\\');
        if (!slash) return;
        *slash = 0;
    }

    InitializeCriticalSection(&lock);
    iwd_ops iops = {os_pread, os_seek, os_same_file, os_alloc, os_release};
    iwd_init(&cache, &iops);
    dc_ops dops = {os_dir_state, os_now_ms, os_alloc, os_release};
    dc_init(&dirs, &dops, exe);

    /*
     * Patch the exe and every DLL loaded from the game's folder (Miles audio, Bink and friends may be
     * handed a file the game opened), so a cached file can't be read past the hooks.
     */
    int safe = 1;
    HMODULE mods[512];
    DWORD needed = 0;
    if (K32EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed)) {
        size_t count = needed / sizeof(HMODULE);
        if (count > 512) count = 512;
        size_t root_len = wcslen(exe);
        for (size_t i = 0; i < count; i++) {
            WCHAR path[MAX_PATH];
            DWORD len = GetModuleFileNameW(mods[i], path, MAX_PATH);
            if (!len || len >= MAX_PATH || mods[i] == (HMODULE)&__ImageBase) continue;
            if (_wcsnicmp(path, exe, root_len) || path[root_len] != L'\\') continue;
            if (!patch_imports(mods[i])) safe = 0;
        }
    } else {
        safe = patch_imports(GetModuleHandleW(NULL));
    }
    hook_def *cw = find_hook("CreateFileW"), *ca = find_hook("CreateFileA");
    dir_cache_on = cw->found || ca->found;
    /* Every way the game reads or positions a file must go through the hooks. */
    read_cache_on = safe && dir_cache_on && find_hook("ReadFile")->found && find_hook("CloseHandle")->found &&
                    (find_hook("SetFilePointer")->found || find_hook("SetFilePointerEx")->found);

    char msg[200];
    snprintf(msg, sizeof(msg), "gamecache: hooks installed (archive read cache %s, missing-folder cache %s)\n",
             read_cache_on ? "on" : "off", dir_cache_on ? "on" : "off");
    log_line(msg);
}

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID reserved)
{
    (void)inst;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(inst);
        install();
    } else if (reason == DLL_PROCESS_DETACH) {
        (void)reserved;
        char msg[200];
        snprintf(msg, sizeof(msg),
                 "gamecache: %ld archive reads served from memory across %ld files, %ld missing-folder probes "
                 "answered%s\n",
                 served_reads, tracked_files, failed_probes, cache.disabled ? " (read cache disabled after a mismatch)" : "");
        log_line(msg);
    }
    return TRUE;
}
