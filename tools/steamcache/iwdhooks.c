/*
 * Hooks Wine's kernelbase file functions to put iwdcache.c in front of the game's .iwd reads.
 *
 * Wine's kernelbase routes CreateFileA through CreateFileW and SetFilePointer through
 * SetFilePointerEx, and kernel32 forwards all of them to kernelbase, so four hooks cover every path:
 * CreateFileW, ReadFile, SetFilePointerEx and CloseHandle. They are installed only when all four
 * start with the exact hotpatch prologue Wine builds them with (mov edi,edi; push ebp; mov ebp,esp);
 * otherwise none is, and nothing changes. Only plain read-only, non-overlapped opens of *.iwd are
 * cached; every other call goes straight to Wine.
 */
#include <windows.h>
#include <stdint.h>
#include <string.h>

#include "iwdcache.h"

typedef HANDLE(WINAPI *CreateFileWFn)(LPCWSTR, DWORD, DWORD, LPSECURITY_ATTRIBUTES, DWORD, DWORD, HANDLE);
typedef BOOL(WINAPI *ReadFileFn)(HANDLE, LPVOID, DWORD, LPDWORD, LPOVERLAPPED);
typedef BOOL(WINAPI *SetFilePointerExFn)(HANDLE, LARGE_INTEGER, PLARGE_INTEGER, DWORD);
typedef BOOL(WINAPI *CloseHandleFn)(HANDLE);

static CreateFileWFn real_CreateFileW;
static ReadFileFn real_ReadFile;
static SetFilePointerExFn real_SetFilePointerEx;
static CloseHandleFn real_CloseHandle;

static iwd_cache cache;
static CRITICAL_SECTION cache_lock;
static volatile LONG hooks_on;

static int64_t os_pread(void *h, int64_t offset, void *buf, uint32_t count)
{
    LARGE_INTEGER at;
    at.QuadPart = offset;
    DWORD got = 0;
    if (!real_SetFilePointerEx(h, at, NULL, FILE_BEGIN)) return -1;
    if (!real_ReadFile(h, buf, count, &got, NULL)) return -1;
    return got;
}

static int os_seek(void *h, int64_t offset)
{
    LARGE_INTEGER at;
    at.QuadPart = offset;
    return real_SetFilePointerEx(h, at, NULL, FILE_BEGIN) ? 0 : -1;
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

static int is_iwd(LPCWSTR name)
{
    if (!name) return 0;
    int n = lstrlenW(name);
    if (n < 4) return 0;
    const WCHAR *e = name + n - 4;
    return e[0] == L'.' && (e[1] | 0x20) == L'i' && (e[2] | 0x20) == L'w' && (e[3] | 0x20) == L'd';
}

static HANDLE WINAPI hook_CreateFileW(LPCWSTR name, DWORD access, DWORD share, LPSECURITY_ATTRIBUTES sa,
                                      DWORD creation, DWORD flags, HANDLE templ)
{
    HANDLE h = real_CreateFileW(name, access, share, sa, creation, flags, templ);
    if (h == INVALID_HANDLE_VALUE || !hooks_on || !is_iwd(name)) return h;
    const DWORD writes = GENERIC_WRITE | GENERIC_ALL | FILE_WRITE_DATA | FILE_APPEND_DATA;
    if ((access & writes) || !(access & (GENERIC_READ | FILE_READ_DATA)) || creation != OPEN_EXISTING ||
        (flags & (FILE_FLAG_OVERLAPPED | FILE_FLAG_NO_BUFFERING)))
        return h;
    DWORD err = GetLastError();
    LARGE_INTEGER size;
    uint64_t id = file_id(h);
    if (id && GetFileSizeEx(h, &size)) {
        EnterCriticalSection(&cache_lock);
        iwd_track(&cache, h, id, size.QuadPart);
        LeaveCriticalSection(&cache_lock);
    }
    SetLastError(err);
    return h;
}

static BOOL WINAPI hook_ReadFile(HANDLE h, LPVOID buf, DWORD count, LPDWORD read, LPOVERLAPPED ov)
{
    if (hooks_on) {
        EnterCriticalSection(&cache_lock);
        iwd_file *f = iwd_find(&cache, h);
        if (f && ov) {
            /* An offset read moves the real position itself: stop tracking this file. */
            iwd_forget(&cache, f, 1);
        } else if (f) {
            uint32_t done = 0;
            int served = iwd_read(&cache, f, buf, count, &done);
            LeaveCriticalSection(&cache_lock);
            if (served) {
                if (read) *read = done;
                return TRUE;
            }
            /* The cache gave the file back at the game's position: do the read for real. */
            return real_ReadFile(h, buf, count, read, ov);
        }
        LeaveCriticalSection(&cache_lock);
    }
    return real_ReadFile(h, buf, count, read, ov);
}

static BOOL WINAPI hook_SetFilePointerEx(HANDLE h, LARGE_INTEGER distance, PLARGE_INTEGER newpos, DWORD method)
{
    if (hooks_on) {
        EnterCriticalSection(&cache_lock);
        iwd_file *f = iwd_find(&cache, h);
        if (f) {
            int64_t pos = 0;
            int ok = method <= FILE_END && iwd_seek(&cache, f, distance.QuadPart, (int)method, &pos);
            LeaveCriticalSection(&cache_lock);
            if (!ok) {
                SetLastError(method <= FILE_END ? ERROR_NEGATIVE_SEEK : ERROR_INVALID_PARAMETER);
                return FALSE;
            }
            if (newpos) newpos->QuadPart = pos;
            return TRUE;
        }
        LeaveCriticalSection(&cache_lock);
    }
    return real_SetFilePointerEx(h, distance, newpos, method);
}

static BOOL WINAPI hook_CloseHandle(HANDLE h)
{
    if (hooks_on) {
        EnterCriticalSection(&cache_lock);
        iwd_file *f = iwd_find(&cache, h);
        if (f) iwd_forget(&cache, f, 0);
        LeaveCriticalSection(&cache_lock);
    }
    return real_CloseHandle(h);
}

/* mov edi,edi; push ebp; mov ebp,esp: complete instructions, so they can run from a trampoline. */
static const unsigned char prologue[5] = {0x8B, 0xFF, 0x55, 0x8B, 0xEC};

static int write_jump(unsigned char *at, void *target)
{
    DWORD old;
    if (!VirtualProtect(at, 5, PAGE_EXECUTE_READWRITE, &old)) return 0;
    int32_t rel = (int32_t)((unsigned char *)target - (at + 5));
    at[0] = 0xE9;
    memcpy(at + 1, &rel, 4);
    VirtualProtect(at, 5, old, &old);
    FlushInstructionCache(GetCurrentProcess(), at, 5);
    return 1;
}

/* Must run before any game thread exists (from DllMain), like the other patches in this DLL. */
void install_iwd_cache(void)
{
    HMODULE kb = GetModuleHandleA("kernelbase.dll");
    if (!kb) return;
    const char *names[4] = {"CreateFileW", "ReadFile", "SetFilePointerEx", "CloseHandle"};
    void *hooks[4] = {(void *)hook_CreateFileW, (void *)hook_ReadFile, (void *)hook_SetFilePointerEx,
                      (void *)hook_CloseHandle};
    unsigned char *fns[4];
    for (int i = 0; i < 4; i++) {
        fns[i] = (unsigned char *)(void *)GetProcAddress(kb, names[i]);
        if (!fns[i] || memcmp(fns[i], prologue, sizeof(prologue)) != 0) return; /* not the expected Wine: leave all alone */
    }
    unsigned char *tramp = VirtualAlloc(NULL, 4096, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (!tramp) return;
    void **reals[4] = {(void **)&real_CreateFileW, (void **)&real_ReadFile, (void **)&real_SetFilePointerEx,
                       (void **)&real_CloseHandle};
    for (int i = 0; i < 4; i++) {
        unsigned char *t = tramp + i * 16;
        memcpy(t, prologue, sizeof(prologue));
        int32_t rel = (int32_t)((fns[i] + 5) - (t + 10));
        t[5] = 0xE9;
        memcpy(t + 6, &rel, 4);
        *reals[i] = t;
    }
    FlushInstructionCache(GetCurrentProcess(), tramp, 64);

    iwd_ops ops = {os_pread, os_seek, os_same_file, os_alloc, os_release};
    iwd_init(&cache, &ops);
    InitializeCriticalSection(&cache_lock);
    for (int i = 0; i < 4; i++) {
        if (!write_jump(fns[i], hooks[i])) {
            /* Undo the ones already written so all four stay in step. */
            for (int j = 0; j < i; j++) {
                DWORD old;
                VirtualProtect(fns[j], 5, PAGE_EXECUTE_READWRITE, &old);
                memcpy(fns[j], prologue, sizeof(prologue));
                VirtualProtect(fns[j], 5, old, &old);
            }
            return;
        }
    }
    hooks_on = 1;
}
