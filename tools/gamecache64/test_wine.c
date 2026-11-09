/*
 * Smoke test under Wine: a stand-in "game" in <root>\corruption that imports psapi.dll (so the
 * gamecache proxy loads like it does for StarWarsG.exe), reads a .meg file the way Empire at War
 * does (64-byte reads, each after a seek), probes missing folders, and checks every result.
 * Run it from <root>\corruption with WINEDLLOVERRIDES=psapi=n,b (cache) or psapi=b (plain Wine).
 * Build: x86_64-w64-mingw32-gcc -O2 -o test_wine.exe test_wine.c -lpsapi.
 */
#define PSAPI_VERSION 1 /* import psapi.dll itself, like the game */
#include <windows.h>
#include <psapi.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { printf("FAIL: " __VA_ARGS__); printf("\n"); failures++; } } while (0)

static double now_s(void)
{
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart / (double)f.QuadPart;
}

int main(void)
{
    /* psapi.dll forwards work. */
    {
        DWORD pids[1024], got = 0;
        CHECK(EnumProcesses(pids, sizeof(pids), &got) && got > 0, "EnumProcesses (err %lu)", GetLastError());
        HMODULE mods[256];
        DWORD needed = 0;
        CHECK(EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed) && needed > 0, "EnumProcessModules");
        WCHAR name[MAX_PATH];
        CHECK(GetModuleBaseNameW(GetCurrentProcess(), NULL, name, MAX_PATH) > 0, "GetModuleBaseNameW");
        HMODULE ps = GetModuleHandleW(L"psapi.dll");
        WCHAR path[MAX_PATH] = L"";
        if (ps) GetModuleFileNameW(ps, path, MAX_PATH);
        printf("psapi.dll loaded from %ls\n", path);
    }

    /* Build a test archive. */
    CreateDirectoryW(L"Data", NULL);
    const DWORD size = 3 * 1024 * 1024 + 4321;
    unsigned char *data = malloc(size);
    srand(7);
    for (DWORD i = 0; i < size; i++) data[i] = (unsigned char)(rand() >> 5);
    HANDLE w = CreateFileW(L"Data\\Test.MEG", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    DWORD wr;
    CHECK(w != INVALID_HANDLE_VALUE && WriteFile(w, data, size, &wr, NULL) && wr == size, "write archive");
    CloseHandle(w);

    /* Read it like the game: seek relative/absolute, read 64 bytes, compare. */
    double t0 = now_s();
    for (int pass = 0; pass < 3; pass++) {
        HANDLE h = CreateFileA("Data\\Test.MEG", GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
        CHECK(h != INVALID_HANDLE_VALUE, "open archive");
        int64_t ref = 0;
        unsigned char buf[20000];
        for (int i = 0; i < 60000; i++) {
            int op = rand() % 10;
            if (op < 4) {
                LONG d = (LONG)(rand() % 70000) - 35000;
                DWORD method = (op == 0) ? FILE_BEGIN : FILE_CURRENT;
                int64_t want = (method == FILE_BEGIN ? 0 : ref) + d;
                if (want < 0) d = -d, want = (method == FILE_BEGIN ? 0 : ref) + d;
                DWORD r;
                if (op == 3) {
                    LARGE_INTEGER dist, np;
                    dist.QuadPart = d;
                    BOOL ok = SetFilePointerEx(h, dist, &np, method);
                    CHECK(ok && np.QuadPart == want, "SetFilePointerEx");
                } else {
                    r = SetFilePointer(h, d, NULL, method);
                    CHECK(r == (DWORD)want, "SetFilePointer %lu vs %lld", r, (long long)want);
                }
                ref = want;
            } else {
                DWORD n = op < 9 ? 64 : (DWORD)(rand() % 20000) + 1;
                DWORD got = 0;
                CHECK(ReadFile(h, buf, n, &got, NULL), "ReadFile");
                DWORD expect = ref >= size ? 0 : (DWORD)((ref + n > size) ? size - ref : n);
                CHECK(got == expect, "read length %lu vs %lu", got, expect);
                if (got && memcmp(buf, data + ref, got)) { CHECK(0, "read data at %lld", (long long)ref); break; }
                ref += got;
            }
            if (failures > 5) break;
        }
        CloseHandle(h);
    }
    double t_reads = now_s() - t0;

    /* Missing folders fail with ERROR_PATH_NOT_FOUND; missing files in real folders with FILE_NOT_FOUND. */
    t0 = now_s();
    for (int i = 0; i < 20000; i++) {
        WCHAR p[200];
        swprintf(p, 200, i & 1 ? L"..\\GameData\\\\Data\\Art\\Models\\RB_%d.ALA" : L"Data\\Art\\Models\\RB_%d.ALA", i);
        HANDLE h = CreateFileW(p, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
        DWORD e = GetLastError();
        CHECK(h == INVALID_HANDLE_VALUE && e == ERROR_PATH_NOT_FOUND, "probe %d err %lu", i, e);
        if (failures > 5) break;
    }
    double t_probes = now_s() - t0;
    HANDLE h = CreateFileW(L"Data\\NoSuch.ALA", GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    CHECK(h == INVALID_HANDLE_VALUE && GetLastError() == ERROR_FILE_NOT_FOUND, "missing file in real folder err %lu", GetLastError());

    /* A folder created after it was found missing is seen at once. */
    CHECK(CreateDirectoryW(L"Data\\Art", NULL) || GetLastError() == ERROR_ALREADY_EXISTS, "mkdir Art");
    CHECK(CreateDirectoryW(L"Data\\Art\\Models", NULL), "mkdir Models");
    w = CreateFileW(L"Data\\Art\\Models\\RB_0.ALA", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    CHECK(w != INVALID_HANDLE_VALUE, "create file in new folder");
    CloseHandle(w);
    h = CreateFileW(L"Data\\Art\\Models\\RB_0.ALA", GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    CHECK(h != INVALID_HANDLE_VALUE, "open file in new folder (err %lu)", GetLastError());
    CloseHandle(h);
    DeleteFileW(L"Data\\Art\\Models\\RB_0.ALA");
    RemoveDirectoryW(L"Data\\Art\\Models");
    RemoveDirectoryW(L"Data\\Art");
    DeleteFileW(L"Data\\Test.MEG");

    /* Intro movies get an empty file; other movies are left alone (here: not found). */
    {
        HANDLE m = CreateFileW(L"DATA\\ART\\MOVIES\\BINKED\\LUCASARTSLOGO.BIK", GENERIC_READ, FILE_SHARE_READ, NULL,
                               OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
        int skipping = GetEnvironmentVariableW(L"EXPECT_SKIP", NULL, 0) > 0;
        if (skipping) {
            CHECK(m != INVALID_HANDLE_VALUE, "intro movie got no handle (err %lu)", GetLastError());
            if (m != INVALID_HANDLE_VALUE) {
                LARGE_INTEGER sz = {0};
                BOOL szok = GetFileSizeEx(m, &sz);
                CHECK(szok && sz.QuadPart == 0 && GetFileType(m) == FILE_TYPE_DISK, "intro stand-in not an empty disk file");
                char b[64];
                DWORD got = 1;
                CHECK(ReadFile(m, b, sizeof(b), &got, NULL) && got == 0, "intro stand-in read %lu bytes", got);
                CloseHandle(m);
            }
        } else {
            CHECK(m == INVALID_HANDLE_VALUE, "intro movie opened without the cache");
        }
        HANDLE o = CreateFileW(L"DATA\\ART\\MOVIES\\BINKED\\LIGHT_LOOP.BIK", GENERIC_READ, FILE_SHARE_READ, NULL,
                               OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
        CHECK(o == INVALID_HANDLE_VALUE, "menu movie was replaced");
    }

    printf("archive reads: %.3f s, 20000 missing-folder probes: %.3f s\n", t_reads, t_probes);
    printf(failures ? "FAILED (%d)\n" : "ALL OK\n", failures);
    free(data);
    return failures != 0;
}
