/*
 * powrprof.dll pass-through for gamecache. StarWarsG.exe imports POWRPROF.dll itself, so Wine looks
 * for it in the game folder first and loads this copy (with the per-exe DllOverrides
 * powrprof=native,builtin) before the game runs any code. (psapi.dll and version.dll, the earlier
 * attempts, are only pulled in by Steam's tier0_s64.dll, and Wine resolves those imports next to
 * tier0 and in system32, never in the game folder.)
 *
 * Every export is a one-instruction jump through proxy_slots (proxy_stubs.S); proxy_init() fills the
 * slots from Wine's own powrprof.dll, loaded by its system32 path. A function that copy doesn't
 * have answers ERROR_CALL_NOT_IMPLEMENTED, which is what an import of a missing function would have
 * done without the proxy (the game would not have started).
 */
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

extern void *proxy_slots[];

static const char *const proxy_names[] = {
#include "proxy_names.h"
};

static DWORD WINAPI not_implemented(void)
{
    return ERROR_CALL_NOT_IMPLEMENTED;
}

/* Returns FALSE when Wine's powrprof.dll couldn't be loaded (every call then fails cleanly). */
BOOL proxy_init(HINSTANCE self)
{
    WCHAR path[MAX_PATH];
    UINT len = GetSystemDirectoryW(path, MAX_PATH);
    HMODULE real = NULL;
    if (len && len < MAX_PATH - 16) {
        wcscpy(path + len, L"\\powrprof.dll");
        real = LoadLibraryW(path);
        if (real == self) real = NULL; /* never forward to ourselves */
    }
    for (size_t i = 0; i < sizeof(proxy_names) / sizeof(proxy_names[0]); i++) {
        FARPROC p = real ? GetProcAddress(real, proxy_names[i]) : NULL;
        proxy_slots[i] = p ? (void *)p : (void *)not_implemented;
    }
    return real != NULL;
}
