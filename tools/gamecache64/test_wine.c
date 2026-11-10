/*
 * Smoke test under Wine: a stand-in "game" in <root>\corruption that imports powrprof.dll (so the
 * gamecache proxy loads like it does for StarWarsG.exe), reads a .meg file the way Empire at War
 * does (64-byte reads, each after a seek), probes missing folders, and checks every result.
 * Run it from <root>\corruption with WINEDLLOVERRIDES=powrprof=n,b (cache) or powrprof=b (plain Wine).
 * Build: x86_64-w64-mingw32-gcc -O2 -o test_wine.exe test_wine.c -lpowrprof.
 */
#include <windows.h>
#include <powrprof.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { printf("FAIL: " __VA_ARGS__); printf("\n"); failures++; } } while (0)

/* Probe a name (listed missing), move a file in under that name, then it must open. */
static int lc_probe_then_move(void)
{
    HANDLE m = CreateFileW(L"Loose\\moved.alo", GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (m != INVALID_HANDLE_VALUE) return 0;
    if (!MoveFileW(L"moved_src.tmp", L"Loose\\moved.alo")) return 0;
    m = CreateFileW(L"Loose\\moved.alo", GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (m == INVALID_HANDLE_VALUE) return 0;
    CloseHandle(m);
    return 1;
}

static double now_s(void)
{
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart / (double)f.QuadPart;
}

int main(int argc, char **argv)
{
    /* GAMECACHE_ADD_ARGS reaches the command line and the C runtime's argv. */
    {
        WCHAR want[256];
        if (GetEnvironmentVariableW(L"GAMECACHE_ADD_ARGS", want, 256) && GetEnvironmentVariableW(L"EXPECT_SKIP", NULL, 0)) {
            CHECK(wcsstr(GetCommandLineW(), want) != NULL, "GetCommandLineW lacks the added args: %ls", GetCommandLineW());
            CHECK(strstr(GetCommandLineA(), "MODPATH=") != NULL, "GetCommandLineA lacks the added args");
            CHECK(argc >= 2 && !strncmp(argv[argc - 1], "MODPATH=", 8), "argv lacks the added args (argc %d)", argc);
            printf("argv[%d] = %s\n", argc - 1, argv[argc - 1]);
        }
    }

    /* powrprof.dll calls reach Wine's own (the game calls PowerGetActiveScheme). */
    {
        GUID *scheme = NULL;
        DWORD r = PowerGetActiveScheme(NULL, &scheme);
        printf("PowerGetActiveScheme -> %lu\n", r);
        if (scheme) LocalFree(scheme);
        SYSTEM_POWER_CAPABILITIES caps;
        BOOLEAN ok = GetPwrCapabilities(&caps);
        printf("GetPwrCapabilities -> %d\n", ok);
        HMODULE pp = GetModuleHandleW(L"powrprof.dll");
        WCHAR path[MAX_PATH] = L"";
        if (pp) GetModuleFileNameW(pp, path, MAX_PATH);
        printf("powrprof.dll loaded from %ls\n", path);
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

    /* Missing files in a folder that exists: answered from its listing, with Windows' error. */
    double t_files;
    {
        CreateDirectoryW(L"Loose", NULL);
        HANDLE w2 = CreateFileW(L"Loose\\present.xml", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
        static char text[70000];
        for (int i = 0; i < (int)sizeof(text); i++) text[i] = (char)('a' + i % 26);
        DWORD wr2;
        WriteFile(w2, text, sizeof(text), &wr2, NULL);
        CloseHandle(w2);
        double t1 = now_s();
        for (int i = 0; i < 20000; i++) {
            WCHAR p[100];
            swprintf(p, 100, L"Loose\\Missing_%d.ALO", i);
            HANDLE m = CreateFileW(p, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
            DWORD e = GetLastError();
            CHECK(m == INVALID_HANDLE_VALUE && e == ERROR_FILE_NOT_FOUND, "missing file %d err %lu", i, e);
            if (failures > 5) break;
        }
        t_files = now_s() - t1;
        /* present files (any case) still open */
        HANDLE r = CreateFileW(L"loose\\PRESENT.XML", GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
        CHECK(r != INVALID_HANDLE_VALUE, "present file in a listed folder (err %lu)", GetLastError());
        /* small reads from a loose file come back right (read-ahead cache) */
        char small[61];
        DWORD got2 = 0, total = 0;
        int bad = 0;
        while (ReadFile(r, small, sizeof(small), &got2, NULL) && got2) {
            for (DWORD i = 0; i < got2; i++) if (small[i] != text[total + i]) bad = 1;
            total += got2;
        }
        CHECK(!bad && total == sizeof(text), "loose file small reads (%lu bytes, bad %d)", total, bad);
        CloseHandle(r);
        /* a file created after the folder was listed is seen at once */
        w2 = CreateFileW(L"Loose\\new.alo", GENERIC_WRITE, 0, NULL, CREATE_NEW, 0, NULL);
        CHECK(w2 != INVALID_HANDLE_VALUE, "create new.alo");
        CloseHandle(w2);
        r = CreateFileW(L"Loose\\NEW.ALO", GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
        CHECK(r != INVALID_HANDLE_VALUE, "file created in a listed folder (err %lu)", GetLastError());
        CloseHandle(r);
        /* and one moved in */
        w2 = CreateFileW(L"moved_src.tmp", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
        CloseHandle(w2);
        CHECK(lc_probe_then_move(), "file moved into a listed folder");
        DeleteFileW(L"Loose\\present.xml");
        DeleteFileW(L"Loose\\new.alo");
        DeleteFileW(L"Loose\\moved.alo");
        RemoveDirectoryW(L"Loose");
    }

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

    if (GetEnvironmentVariableW(L"GAMECACHE_STATS_MS", NULL, 0)) Sleep(1500); /* let a stats line through */
    printf("archive reads: %.3f s, 20000 missing-folder probes: %.3f s, 20000 missing files: %.3f s\n", t_reads, t_probes, t_files);
    printf(failures ? "FAILED (%d)\n" : "ALL OK\n", failures);
    free(data);
    return failures != 0;
}
