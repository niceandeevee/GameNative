/* Host test for dircache.c: cc -O2 -o t test_dircache.c dircache.c && ./t */
#include "dircache.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

static const wchar_t *existing[16];
static int n_existing;
static int lookups;
static uint64_t clock_ms = 1000;
static int deny;

static int fail(const char *what) { printf("FAIL: %s\n", what); exit(1); }

static int t_dir_state(const wchar_t *dir)
{
    lookups++;
    if (deny) return DC_UNKNOWN;
    for (int i = 0; i < n_existing; i++)
        if (wcscmp(existing[i], dir) == 0) return DC_EXISTS;
    return DC_MISSING;
}
static uint64_t t_now(void) { return clock_ms; }
static void *t_alloc(uint32_t n) { return malloc(n); }
static void t_release(void *p) { free(p); }

int main(void)
{
    static dc_cache c;
    dc_ops ops = {t_dir_state, t_now, t_alloc, t_release};
    existing[n_existing++] = L"c:\\game\\corruption";
    existing[n_existing++] = L"c:\\game\\corruption\\data";
    dc_init(&c, &ops, L"C:\\Game");

    /* Missing directory: looked up once, then answered from the cache. */
    if (!dc_parent_missing(&c, L"C:\\Game\\corruption\\Data\\Art\\Models\\A.ALA")) fail("missing dir not reported");
    if (!dc_parent_missing(&c, L"c:/game/CORRUPTION//data\\art\\models\\B.ala")) fail("normalized path not matched");
    if (lookups != 1) fail("missing dir looked up more than once");

    /* Existing directory: never reported missing, and cached too. */
    if (dc_parent_missing(&c, L"C:\\Game\\corruption\\Data\\64Patch.meg")) fail("existing dir reported missing");
    if (dc_parent_missing(&c, L"C:\\Game\\corruption\\Data\\Textures.meg")) fail("existing dir reported missing (2)");
    if (lookups != 2) fail("existing dir looked up more than once");

    /* Outside the root, or the root itself: never answered. */
    if (dc_parent_missing(&c, L"C:\\Users\\x\\Saved Games\\Save\\a.sav")) fail("outside root answered");
    if (dc_parent_missing(&c, L"C:\\Game\\file.txt")) fail("root itself answered");
    if (dc_parent_missing(&c, L"C:\\GameData\\x\\y.txt")) fail("sibling with root as prefix answered");
    if (dc_parent_missing(&c, L"C:\\Game\\a\\..\\b\\y.txt")) fail("non-canonical path answered");
    if (lookups != 2) fail("unanswered paths looked up");

    /* A missing directory that appears is seen after the recheck interval. */
    existing[n_existing++] = L"c:\\game\\corruption\\data\\art\\models";
    clock_ms += DC_RECHECK_MS - 1;
    if (!dc_parent_missing(&c, L"C:\\Game\\corruption\\Data\\Art\\Models\\C.ala")) fail("rechecked too early");
    clock_ms += 2;
    if (dc_parent_missing(&c, L"C:\\Game\\corruption\\Data\\Art\\Models\\C.ala")) fail("new dir not seen after recheck");

    /* Clearing forgets everything. */
    dc_clear(&c);
    int before = lookups;
    dc_parent_missing(&c, L"C:\\Game\\corruption\\Data\\64Patch.meg");
    if (lookups != before + 1) fail("clear did not forget");

    /* Unknown answers (access errors) are never cached or reported. */
    deny = 1;
    if (dc_parent_missing(&c, L"C:\\Game\\locked\\x.txt")) fail("unknown reported missing");
    if (dc_parent_missing(&c, L"C:\\Game\\locked\\x.txt")) fail("unknown reported missing (2)");
    deny = 0;

    /* Many directories: the table stops growing at 3/4 full and keeps working. */
    for (int i = 0; i < DC_SLOTS; i++) {
        wchar_t p[64];
        swprintf(p, 64, L"C:\\Game\\m%d\\f.txt", i);
        if (!dc_parent_missing(&c, p)) fail("missing dir in big table");
    }
    if (c.used > DC_SLOTS * 3 / 4) fail("table overfilled");

    dc_clear(&c);
    printf("ALL OK\n");
    return 0;
}
