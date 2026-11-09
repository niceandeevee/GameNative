/*
 * Smoke test under Wine: a stand-in "game" in <root>\corruption that imports version.dll (so the
 * gamecache proxy loads like it does for StarWarsG.exe), reads a .meg file the way Empire at War
 * does (64-byte reads, each after a seek), probes missing folders, and checks every result.
 * Run it from <root>\corruption with WINEDLLOVERRIDES=version=n,b (cache) or version=b (plain Wine).
 */
#include <windows.h>
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
    /* version.dll forwards work. */
    DWORD dummy;
    DWORD vsize = GetFileVersionInfoSizeW(L"C:\\windows\\system32\\kernel32.dll", &dummy);
    CHECK(vsize > 0, "GetFileVersionInfoSizeW returned 0 (err %lu)", GetLastError());
    if (vsize) {
        void *vi = malloc(vsize);
        CHECK(GetFileVersionInfoW(L"C:\\windows\\system32\\kernel32.dll", 0, vsize, vi), "GetFileVersionInfoW");
        VS_FIXEDFILEINFO *ffi;
        UINT len;
        CHECK(VerQueryValueW(vi, L"\\", (void **)&ffi, &len) && len, "VerQueryValueW");
        free(vi);
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

    printf("archive reads: %.3f s, 20000 missing-folder probes: %.3f s\n", t_reads, t_probes);
    printf(failures ? "FAILED (%d)\n" : "ALL OK\n", failures);
    free(data);
    return failures != 0;
}
