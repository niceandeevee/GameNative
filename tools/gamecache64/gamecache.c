/*
 * gamecache: a pass-through powrprof.dll for 64-bit games that load slowly under Wine because of
 * many tiny file operations (Star Wars: Empire at War: Gold Pack, StarWarsG.exe).
 *
 * The game imports powrprof.dll itself, so this copy in its folder loads before any game code runs;
 * every export is passed to Wine's own powrprof.dll (proxy.c, proxy_stubs.S). On load, the
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
#include <shellapi.h>
#include <winternl.h>
#include <wchar.h>
#include <stdint.h>
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../steamcache/iwdcache.h"
#include "dircache.h"
#include "listcache.h"

BOOL proxy_init(HINSTANCE self);

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
static lc_cache files;
static volatile LONG64 missing_files;
static CRITICAL_SECTION lock;
static int read_cache_on;
static int dir_cache_on;
static volatile LONG served_reads, failed_probes, tracked_files;

static void log_line(const char *msg)
{
    /*
     * Wine's own debug output goes straight to the Unix stderr, which the app logs. A game started by
     * a launcher (Empire at War: steam -> runme2 -> swfoc -> StarWarsG) has no Windows stderr handle.
     */
    typedef int(__cdecl * dbg_output_fn)(const char *);
    static dbg_output_fn dbg_output;
    static int looked_up;
    if (!looked_up) {
        HMODULE ntdll = GetModuleHandleA("ntdll.dll");
        dbg_output = ntdll ? (dbg_output_fn)(void *)GetProcAddress(ntdll, "__wine_dbg_output") : NULL;
        looked_up = 1;
    }
    if (dbg_output) {
        dbg_output(msg);
        return;
    }
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

static int os_list_dir(const wchar_t *dir, void (*add)(void *, const wchar_t *), void *ctx)
{
    WCHAR pattern[LC_MAX_PATH + 4];
    if (swprintf(pattern, LC_MAX_PATH + 4, L"%ls\\*", dir) < 0) return 0;
    WIN32_FIND_DATAW fd;
    HANDLE f = FindFirstFileExW(pattern, FindExInfoBasic, &fd, FindExSearchNameMatch, NULL, FIND_FIRST_EX_LARGE_FETCH);
    if (f == INVALID_HANDLE_VALUE) return 0;
    do add(ctx, fd.cFileName);
    while (FindNextFileW(f, &fd));
    DWORD err = GetLastError();
    FindClose(f);
    return err == ERROR_NO_MORE_FILES;
}

/* ---- hooks ---- */

/* Files the read-ahead cache serves: any file under the game folder (archives and loose files). */
static int under_root(LPCWSTR name)
{
    if (!name) return 0;
    WCHAR full[DC_MAX_PATH], norm[DC_MAX_PATH];
    DWORD n = GetFullPathNameW(name, DC_MAX_PATH, full, NULL);
    if (!n || n >= DC_MAX_PATH) return 0;
    uint32_t len = dc_normalize(full, norm, DC_MAX_PATH);
    return len > dirs.root_len && !wcsncmp(norm, dirs.root, dirs.root_len);
}

static int is_cached_archive(LPCWSTR name)
{
    return under_root(name);
}

static int read_only_open(DWORD access, DWORD creation)
{
    const DWORD writes = GENERIC_WRITE | GENERIC_ALL | FILE_WRITE_DATA | FILE_APPEND_DATA;
    return !(access & writes) && creation == OPEN_EXISTING;
}

/* ---- load statistics ----
 * Every 10 s with file activity, one line: opens (by type, failed ones, missing-folder answers), reads
 * (from the cache vs. through Wine) and the time spent in the real file calls. Shows whether a slow
 * load waits on files or on the game's own processing.
 */
/* Opens counted by extension (the first EXT_SLOTS distinct ones; the rest go to "other"). */
#define EXT_SLOTS 31
static char ext_names[EXT_SLOTS + 1][8];
static volatile LONG64 st_opens[EXT_SLOTS + 1];
static volatile LONG ext_used;
static volatile LONG64 st_open_fail, st_reads, st_read_bytes, st_cached_reads, st_cached_bytes;
static volatile LONG64 st_open_us, st_read_us, st_seek_us;
static LARGE_INTEGER qpf;

static int64_t now_us(void)
{
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return qpf.QuadPart ? (int64_t)(c.QuadPart * 1000000 / qpf.QuadPart) : 0;
}

static int ext_slot(LPCWSTR name)
{
    char ext[8] = "none";
    const WCHAR *dot = name ? wcsrchr(name, L'.') : NULL;
    if (dot && !wcschr(dot, L'\\') && !wcschr(dot, L'/') && dot[1]) {
        int k = 0;
        for (const WCHAR *c = dot + 1; *c && k < 7; c++) ext[k++] = (char)(*c < 128 ? tolower(*c) : '?');
        ext[k] = 0;
    }
    EnterCriticalSection(&lock);
    int slot = EXT_SLOTS; /* "other" */
    for (int i = 0; i < ext_used; i++)
        if (!strcmp(ext_names[i], ext)) {
            slot = i;
            break;
        }
    if (slot == EXT_SLOTS && ext_used < EXT_SLOTS) {
        slot = ext_used;
        strcpy(ext_names[slot], ext);
        ext_used++;
    }
    LeaveCriticalSection(&lock);
    return slot;
}

static void count_open(LPCWSTR name, HANDLE h, int64_t t0)
{
    InterlockedAdd64(&st_open_us, now_us() - t0);
    if (h == INVALID_HANDLE_VALUE) InterlockedIncrement64(&st_open_fail);
    else InterlockedIncrement64(&st_opens[ext_slot(name)]);
}

static DWORD WINAPI stats_thread(LPVOID arg)
{
    (void)arg;
    strcpy(ext_names[EXT_SLOTS], "other");
    enum { FAIL, PROBES, MISSF, READS, RBYTES, CREADS, CBYTES, OPUS, RDUS, SKUS, N_TOTALS };
    LONG64 last_opens[EXT_SLOTS + 1] = {0}, last[N_TOTALS] = {0};
    char env[16];
    DWORD interval_ms = 10000;
    if (GetEnvironmentVariableA("GAMECACHE_STATS_MS", env, sizeof(env)) && atoi(env) >= 100) interval_ms = (DWORD)atoi(env);
    for (;;) {
        Sleep(interval_ms);
        LONG64 cur[N_TOTALS] = {st_open_fail, failed_probes, missing_files, st_reads, st_read_bytes, st_cached_reads,
                                st_cached_bytes, st_open_us, st_read_us, st_seek_us};
        LONG64 d[N_TOTALS], dopen[EXT_SLOTS + 1], opens = 0;
        for (int i = 0; i < N_TOTALS; i++) d[i] = cur[i] - last[i];
        for (int i = 0; i <= EXT_SLOTS; i++) {
            LONG64 v = st_opens[i];
            dopen[i] = v - last_opens[i];
            last_opens[i] = v;
            opens += dopen[i];
        }
        memcpy(last, cur, sizeof(last));
        if (!opens && !d[FAIL] && !d[PROBES] && !d[MISSF] && !d[READS] && !d[CREADS]) continue;
        /* the 6 busiest extensions */
        char by_type[240] = "";
        size_t len = 0;
        int shown[EXT_SLOTS + 1] = {0};
        for (int round = 0; round < 6; round++) {
            int best = -1;
            for (int i = 0; i <= EXT_SLOTS; i++)
                if (dopen[i] && !shown[i] && (best < 0 || dopen[i] > dopen[best])) best = i;
            if (best < 0) break;
            shown[best] = 1;
            len += snprintf(by_type + len, sizeof(by_type) - len, "%s%lld %s", len ? ", " : "", (long long)dopen[best],
                            ext_names[best]);
        }
        char msg[700];
        snprintf(msg, sizeof(msg),
                 "gamecache: last %.1f s: %lld opens (%s), %lld failed, %lld missing-folder answers, "
                 "%lld missing-file answers; %lld reads %.1f MB through Wine, %lld reads %.1f MB from cache; "
                 "time in file calls: opens %.2f s, reads %.2f s, seeks %.2f s\n",
                 interval_ms / 1000.0, (long long)opens, len ? by_type : "none", (long long)d[FAIL], (long long)d[PROBES],
                 (long long)d[MISSF], (long long)d[READS], d[RBYTES] / 1048576.0, (long long)d[CREADS],
                 d[CBYTES] / 1048576.0, d[OPUS] / 1e6, d[RDUS] / 1e6, d[SKUS] / 1e6);
        log_line(msg);
    }
    return 0;
}

/*
 * When the open can fail right away: ERROR_PATH_NOT_FOUND (its folder is known to be missing),
 * ERROR_FILE_NOT_FOUND (its folder's listing doesn't have it), or 0 for the real call.
 */
static DWORD probe_misses(LPCWSTR name, DWORD access, DWORD creation)
{
    if (!dir_cache_on || !name || !read_only_open(access, creation)) return 0;
    WCHAR full[DC_MAX_PATH];
    DWORD n = GetFullPathNameW(name, DC_MAX_PATH, full, NULL);
    if (!n || n >= DC_MAX_PATH) return 0;
    EnterCriticalSection(&lock);
    DWORD result = dc_parent_missing(&dirs, full) ? ERROR_PATH_NOT_FOUND
                   : lc_file_missing(&files, full)  ? ERROR_FILE_NOT_FOUND
                                                    : 0;
    LeaveCriticalSection(&lock);
    if (result == ERROR_PATH_NOT_FOUND) InterlockedIncrement(&failed_probes);
    if (result == ERROR_FILE_NOT_FOUND) InterlockedIncrement64(&missing_files);
    return result;
}

/* A write, create, move or copy under the game folder: forget the folder listings. */
static void files_changed(LPCWSTR name)
{
    if (!dir_cache_on || (name && !under_root(name))) return;
    EnterCriticalSection(&lock);
    lc_clear(&files);
    LeaveCriticalSection(&lock);
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
    DWORD miss = probe_misses(name, access, creation);
    if (miss) {
        SetLastError(miss);
        return INVALID_HANDLE_VALUE;
    }
    if (!read_only_open(access, creation)) files_changed(name);
    int64_t t0 = now_us();
    HANDLE h = real_CreateFileW(name, access, share, sa, creation, flags, templ);
    DWORD err = GetLastError();
    count_open(name, h, t0);
    SetLastError(err);
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
    DWORD miss = ok ? probe_misses(wide, access, creation) : 0;
    if (miss) {
        SetLastError(miss);
        return INVALID_HANDLE_VALUE;
    }
    if (!read_only_open(access, creation)) files_changed(ok ? wide : NULL);
    int64_t t0 = now_us();
    HANDLE h = real_CreateFileA(name, access, share, sa, creation, flags, templ);
    DWORD err = GetLastError();
    count_open(ok ? wide : NULL, h, t0);
    SetLastError(err);
    if (ok) maybe_track(h, wide, access, creation, flags);
    return h;
}

static BOOL timed_read(HANDLE h, LPVOID buf, DWORD count, LPDWORD read, LPOVERLAPPED ov)
{
    int64_t t0 = now_us();
    DWORD got = 0;
    BOOL ok = real_ReadFile(h, buf, count, read ? read : &got, ov);
    DWORD err = GetLastError();
    InterlockedAdd64(&st_read_us, now_us() - t0);
    InterlockedIncrement64(&st_reads);
    if (ok) InterlockedAdd64(&st_read_bytes, read ? *read : got);
    SetLastError(err);
    return ok;
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
            InterlockedIncrement64(&st_cached_reads);
            InterlockedAdd64(&st_cached_bytes, done);
            return TRUE;
        }
        return timed_read(h, buf, count, read, ov); /* given back at the game's position */
    }
    LeaveCriticalSection(&lock);
    return timed_read(h, buf, count, read, ov);
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
    int64_t t0 = now_us();
    BOOL r = real_SetFilePointerEx(h, distance, newpos, method);
    DWORD err = GetLastError();
    InterlockedAdd64(&st_seek_us, now_us() - t0);
    SetLastError(err);
    return r;
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
    int64_t t0 = now_us();
    DWORD r = real_SetFilePointer(h, low, high, method);
    DWORD err = GetLastError();
    InterlockedAdd64(&st_seek_us, now_us() - t0);
    SetLastError(err);
    return r;
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
        lc_clear(&files);
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
        lc_clear(&files);
        LeaveCriticalSection(&lock);
    }
    return ok;
}

/* Moves and copies can put a file into a listed folder. */
typedef BOOL(WINAPI *MoveFileWFn)(LPCWSTR, LPCWSTR);
typedef BOOL(WINAPI *MoveFileAFn)(LPCSTR, LPCSTR);
typedef BOOL(WINAPI *MoveFileExWFn)(LPCWSTR, LPCWSTR, DWORD);
typedef BOOL(WINAPI *MoveFileExAFn)(LPCSTR, LPCSTR, DWORD);
typedef BOOL(WINAPI *CopyFileWFn)(LPCWSTR, LPCWSTR, BOOL);
typedef BOOL(WINAPI *CopyFileAFn)(LPCSTR, LPCSTR, BOOL);
static MoveFileWFn real_MoveFileW;
static MoveFileAFn real_MoveFileA;
static MoveFileExWFn real_MoveFileExW;
static MoveFileExAFn real_MoveFileExA;
static CopyFileWFn real_CopyFileW;
static CopyFileAFn real_CopyFileA;

static void files_changed_a(LPCSTR name)
{
    WCHAR wide[DC_MAX_PATH];
    files_changed(name && MultiByteToWideChar(CP_ACP, 0, name, -1, wide, DC_MAX_PATH) > 0 ? wide : NULL);
}

static BOOL WINAPI hook_MoveFileW(LPCWSTR from, LPCWSTR to)
{
    BOOL ok = real_MoveFileW(from, to);
    files_changed(to);
    return ok;
}
static BOOL WINAPI hook_MoveFileA(LPCSTR from, LPCSTR to)
{
    BOOL ok = real_MoveFileA(from, to);
    files_changed_a(to);
    return ok;
}
static BOOL WINAPI hook_MoveFileExW(LPCWSTR from, LPCWSTR to, DWORD flags)
{
    BOOL ok = real_MoveFileExW(from, to, flags);
    files_changed(to);
    return ok;
}
static BOOL WINAPI hook_MoveFileExA(LPCSTR from, LPCSTR to, DWORD flags)
{
    BOOL ok = real_MoveFileExA(from, to, flags);
    files_changed_a(to);
    return ok;
}
static BOOL WINAPI hook_CopyFileW(LPCWSTR from, LPCWSTR to, BOOL fail_if_exists)
{
    BOOL ok = real_CopyFileW(from, to, fail_if_exists);
    files_changed(to);
    return ok;
}
static BOOL WINAPI hook_CopyFileA(LPCSTR from, LPCSTR to, BOOL fail_if_exists)
{
    BOOL ok = real_CopyFileA(from, to, fail_if_exists);
    files_changed_a(to);
    return ok;
}

/* ---- extra command-line arguments ----
 *
 * GAMECACHE_ADD_ARGS (set by the app, inherited through the launchers) is appended to the game's
 * command line when it isn't already there: Empire at War reads a Workshop mod from MODPATH=<folder>,
 * and its launcher chain doesn't reliably pass arguments on. The C runtime has already built its own
 * copies by the time this DLL runs, so those are replaced too.
 */
static WCHAR *cmd_w;
static char *cmd_a;
typedef LPWSTR(WINAPI *GetCommandLineWFn)(void);
typedef LPSTR(WINAPI *GetCommandLineAFn)(void);
static GetCommandLineWFn real_GetCommandLineW;
static GetCommandLineAFn real_GetCommandLineA;

static LPWSTR WINAPI hook_GetCommandLineW(void)
{
    return cmd_w ? cmd_w : real_GetCommandLineW();
}

static LPSTR WINAPI hook_GetCommandLineA(void)
{
    return cmd_a ? cmd_a : real_GetCommandLineA();
}

/* The C runtime's startup arguments (what main()/WinMain() get), hooked in the game's imports. */
static int my_argc;
static char **my_argv;
static WCHAR **my_wargv;
typedef int(__cdecl *getmainargs_fn)(int *, char ***, char ***, int, void *);
typedef int(__cdecl *wgetmainargs_fn)(int *, WCHAR ***, WCHAR ***, int, void *);
typedef void *(__cdecl *crt_getter)(void);
static getmainargs_fn real_getmainargs;
static wgetmainargs_fn real_wgetmainargs;
static crt_getter real_p_argc, real_p_argv, real_p_wargv, real_narrow_winmain, real_wide_winmain;

static int __cdecl hook_getmainargs(int *argc, char ***argv, char ***envp, int wildcards, void *si)
{
    int r = real_getmainargs(argc, argv, envp, wildcards, si);
    if (my_argv) {
        *argc = my_argc;
        *argv = my_argv;
    }
    return r;
}

static int __cdecl hook_wgetmainargs(int *argc, WCHAR ***argv, WCHAR ***envp, int wildcards, void *si)
{
    int r = real_wgetmainargs(argc, argv, envp, wildcards, si);
    if (my_wargv) {
        *argc = my_argc;
        *argv = my_wargv;
    }
    return r;
}

static void *__cdecl hook_p_argc(void) { return my_argv ? (void *)&my_argc : real_p_argc(); }
static void *__cdecl hook_p_argv(void) { return my_argv ? (void *)&my_argv : real_p_argv(); }
static void *__cdecl hook_p_wargv(void) { return my_wargv ? (void *)&my_wargv : real_p_wargv(); }

/* WinMain's lpCmdLine: the command line after the program name (Microsoft's rules). */
static const WCHAR *skip_program_w(const WCHAR *c)
{
    if (*c == L'"') {
        for (c++; *c && *c != L'"'; c++) {}
        if (*c) c++;
    } else {
        while (*c > L' ') c++;
    }
    while (*c == L' ' || *c == L'\t') c++;
    return c;
}

static const char *skip_program_a(const char *c)
{
    if (*c == '"') {
        for (c++; *c && *c != '"'; c++) {}
        if (*c) c++;
    } else {
        while ((unsigned char)*c > ' ') c++;
    }
    while (*c == ' ' || *c == '\t') c++;
    return c;
}

static void *__cdecl hook_narrow_winmain(void) { return cmd_a ? (void *)skip_program_a(cmd_a) : real_narrow_winmain(); }
static void *__cdecl hook_wide_winmain(void) { return cmd_w ? (void *)skip_program_w(cmd_w) : real_wide_winmain(); }

static char *to_ansi(const WCHAR *w)
{
    int n = WideCharToMultiByte(CP_ACP, 0, w, -1, NULL, 0, NULL, NULL);
    char *a = n > 0 ? HeapAlloc(GetProcessHeap(), 0, n) : NULL;
    if (a) WideCharToMultiByte(CP_ACP, 0, w, -1, a, n, NULL, NULL);
    return a;
}

/* Points one C runtime's command-line globals (ucrtbase or msvcrt) at the new command line. */
static void update_crt(const WCHAR *crt, WCHAR **argv_w, int argc)
{
    HMODULE m = GetModuleHandleW(crt);
    if (!m) return;
    char ***p_argv = NULL;
    WCHAR ***p_wargv = NULL;
    int *p_argc = NULL;
    char **p_acmd = NULL;
    WCHAR **p_wcmd = NULL;
    typedef void *(__cdecl * getter)(void);
    getter g;
    if ((g = (getter)(void *)GetProcAddress(m, "__p___argc"))) p_argc = g();
    if ((g = (getter)(void *)GetProcAddress(m, "__p___argv"))) p_argv = g();
    if ((g = (getter)(void *)GetProcAddress(m, "__p___wargv"))) p_wargv = g();
    if ((g = (getter)(void *)GetProcAddress(m, "__p__acmdln"))) p_acmd = g();
    if ((g = (getter)(void *)GetProcAddress(m, "__p__wcmdln"))) p_wcmd = g();
    if (p_acmd) *p_acmd = cmd_a;
    if (p_wcmd) *p_wcmd = cmd_w;
    if (argv_w && p_argc && (p_argv || p_wargv)) {
        char **argv_a = HeapAlloc(GetProcessHeap(), 0, (argc + 1) * sizeof(char *));
        if (!argv_a) return;
        for (int i = 0; i < argc; i++) argv_a[i] = to_ansi(argv_w[i]);
        argv_a[argc] = NULL;
        *p_argc = argc;
        if (p_argv) *p_argv = argv_a;
        if (p_wargv) *p_wargv = argv_w;
    }
}

static void add_args(void)
{
    WCHAR extra[1024];
    DWORD n = GetEnvironmentVariableW(L"GAMECACHE_ADD_ARGS", extra, 1024);
    if (!n || n >= 1024) return;
    const WCHAR *cur = GetCommandLineW();
    if (wcsstr(cur, extra)) return; /* the launchers passed it on */
    size_t len = wcslen(cur) + 1 + n + 1;
    cmd_w = HeapAlloc(GetProcessHeap(), 0, len * sizeof(WCHAR));
    if (!cmd_w) return;
    swprintf(cmd_w, len, L"%ls %ls", cur, extra);
    cmd_a = to_ansi(cmd_w);
    if (!cmd_a) {
        cmd_w = NULL;
        return;
    }
    /* The process parameters (GetCommandLineW reads them; so may anything else in the process). */
    typedef PEB *(WINAPI * get_peb_fn)(void);
    get_peb_fn get_peb = (get_peb_fn)(void *)GetProcAddress(GetModuleHandleA("ntdll.dll"), "RtlGetCurrentPeb");
    PRTL_USER_PROCESS_PARAMETERS pp = get_peb ? get_peb()->ProcessParameters : NULL;
    if (pp) {
        pp->CommandLine.Buffer = cmd_w;
        pp->CommandLine.Length = (USHORT)(wcslen(cmd_w) * sizeof(WCHAR));
        pp->CommandLine.MaximumLength = (USHORT)(len * sizeof(WCHAR));
    }
    int argc = 0;
    WCHAR **argv_w = CommandLineToArgvW(cmd_w, &argc);
    if (argv_w) {
        char **argv_a = HeapAlloc(GetProcessHeap(), 0, (argc + 1) * sizeof(char *));
        if (argv_a) {
            for (int i = 0; i < argc; i++) argv_a[i] = to_ansi(argv_w[i]);
            argv_a[argc] = NULL;
            my_argc = argc;
            my_wargv = argv_w;
            my_argv = argv_a;
        }
    }
    update_crt(L"ucrtbase.dll", argv_w, argc);
    update_crt(L"msvcrt.dll", argv_w, argc);
    char msg[1200];
    snprintf(msg, sizeof(msg), "gamecache: added to the command line: %.1000ls\n", extra);
    log_line(msg);
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
    {"MoveFileW", (void *)hook_MoveFileW, (void **)&real_MoveFileW, 0},
    {"MoveFileA", (void *)hook_MoveFileA, (void **)&real_MoveFileA, 0},
    {"MoveFileExW", (void *)hook_MoveFileExW, (void **)&real_MoveFileExW, 0},
    {"MoveFileExA", (void *)hook_MoveFileExA, (void **)&real_MoveFileExA, 0},
    {"CopyFileW", (void *)hook_CopyFileW, (void **)&real_CopyFileW, 0},
    {"CopyFileA", (void *)hook_CopyFileA, (void **)&real_CopyFileA, 0},
    {"GetCommandLineW", (void *)hook_GetCommandLineW, (void **)&real_GetCommandLineW, 0},
    {"GetCommandLineA", (void *)hook_GetCommandLineA, (void **)&real_GetCommandLineA, 0},
    /* C runtime (ucrtbase / api-ms-win-crt-runtime / msvcrt imports): */
    {"__getmainargs", (void *)hook_getmainargs, (void **)&real_getmainargs, 0},
    {"__wgetmainargs", (void *)hook_wgetmainargs, (void **)&real_wgetmainargs, 0},
    {"__p___argc", (void *)hook_p_argc, (void **)&real_p_argc, 0},
    {"__p___argv", (void *)hook_p_argv, (void **)&real_p_argv, 0},
    {"__p___wargv", (void *)hook_p_wargv, (void **)&real_p_wargv, 0},
    {"_get_narrow_winmain_command_line", (void *)hook_narrow_winmain, (void **)&real_narrow_winmain, 0},
    {"_get_wide_winmain_command_line", (void *)hook_wide_winmain, (void **)&real_wide_winmain, 0},
};
#define N_HOOKS (sizeof(hooks) / sizeof(hooks[0]))

/* Calls that would move or read a file position without passing through the hooks above. */
static const char *const unhookable[] = {"DuplicateHandle", "ReadFileEx", "ReadFileScatter", "ReOpenFile"};

static int kernel_dll(const char *name)
{
    return !_stricmp(name, "kernel32.dll") || !_stricmp(name, "kernelbase.dll") ||
           !_strnicmp(name, "api-ms-win-core-", 16) || !_strnicmp(name, "api-ms-win-crt-", 15) ||
           !_stricmp(name, "ucrtbase.dll") || !_stricmp(name, "msvcrt.dll") || !_strnicmp(name, "msvcr1", 6);
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

    QueryPerformanceFrequency(&qpf);
    InitializeCriticalSection(&lock);
    iwd_ops iops = {os_pread, os_seek, os_same_file, os_alloc, os_release};
    iwd_init(&cache, &iops);
    dc_ops dops = {os_dir_state, os_now_ms, os_alloc, os_release};
    dc_init(&dirs, &dops, exe);
    lc_ops lops = {os_list_dir, os_now_ms, os_alloc, os_release};
    lc_init(&files, &lops, exe);

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
    if (dir_cache_on) {
        HANDLE t = CreateThread(NULL, 0, stats_thread, NULL, 0, NULL);
        if (t) CloseHandle(t);
    }
}

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID reserved)
{
    (void)inst;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(inst);
        if (!proxy_init(inst)) log_line("gamecache: Wine's powrprof.dll not found; power calls will fail\n");
        add_args();
        {
            /* Shows which arguments (MODPATH=... for a Workshop mod) reached the game. */
            char cmd[600];
            snprintf(cmd, sizeof(cmd), "gamecache: command line from the launcher: %.500ls\n", GetCommandLineW());
            log_line(cmd);
        }
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
