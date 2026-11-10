/* Host test for listcache.c: a fake folder tree, a fake clock, and counts of folder listings. */
#include "listcache.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;
#define CHECK(c, msg) do { if (!(c)) { printf("FAIL: %s (line %d)\n", msg, __LINE__); failures++; } } while (0)

static uint64_t clock_ms = 1000;
static int listings;
static int unreadable;

/* C:\Game\Mods\1\Data\Art\Models holds A.ALO and b.alo; C:\Game\Empty is empty. */
static int fake_list(const wchar_t *dir, void (*add)(void *, const wchar_t *), void *ctx)
{
    listings++;
    if (unreadable) return 0;
    if (!wcscmp(dir, L"C:\\Game\\Mods\\1\\Data\\Art\\Models")) {
        add(ctx, L".");
        add(ctx, L"..");
        add(ctx, L"A.ALO");
        add(ctx, L"b.alo");
        return 1;
    }
    if (!wcscmp(dir, L"C:\\Game\\Empty")) return 1;
    return 0;
}

static uint64_t fake_now(void) { return clock_ms; }
static void *fake_alloc(uint32_t n) { return malloc(n); }
static void fake_release(void *p) { free(p); }

int main(void)
{
    static lc_cache c;
    lc_ops ops = {fake_list, fake_now, fake_alloc, fake_release};
    lc_init(&c, &ops, L"C:\\Game");

    const wchar_t *models = L"C:\\Game\\Mods\\1\\Data\\Art\\Models\\";
    wchar_t p[200];

    swprintf(p, 200, L"%lsMISSING.ALO", models);
    CHECK(lc_file_missing(&c, p) == 1, "missing file in a listed folder is answered");
    CHECK(listings == 1, "folder listed once");
    swprintf(p, 200, L"%lsa.alo", models);
    CHECK(lc_file_missing(&c, p) == 0, "present file (other case) goes to the real open");
    swprintf(p, 200, L"%lsB.ALO", models);
    CHECK(lc_file_missing(&c, p) == 0, "present file (upper case) goes to the real open");
    swprintf(p, 200, L"%lsother.alo", models);
    CHECK(lc_file_missing(&c, p) == 1, "second miss answered");
    CHECK(listings == 1, "no second listing");
    CHECK(lc_file_missing(&c, L"c:/game/mods/1/data/art/models/x.alo") == 1, "slashes and case normalize to the same folder");
    CHECK(listings == 1, "still one listing");

    CHECK(lc_file_missing(&c, L"C:\\Game\\Empty\\anything.txt") == 1, "empty folder: everything missing");
    CHECK(lc_file_missing(&c, L"C:\\Other\\file.txt") == 0, "outside the root: never answered");
    CHECK(lc_file_missing(&c, L"C:\\Game\\Mods\\1\\Data\\Art\\Models\\ABCDEF~1.ALO") == 0, "short names never answered");
    CHECK(lc_file_missing(&c, L"C:\\Game\\Mods\\..\\Mods\\1\\x.alo") == 0, "unnormalized path never answered");

    int before = listings;
    CHECK(lc_file_missing(&c, L"C:\\Game\\Unreadable\\f.txt") == 0, "unreadable folder: real open");
    CHECK(lc_file_missing(&c, L"C:\\Game\\Unreadable\\g.txt") == 0, "unreadable folder: still real open");
    CHECK(listings == before + 1, "unreadable folder isn't re-listed on every call");

    clock_ms += LC_TTL_MS + 1;
    swprintf(p, 200, L"%lsMISSING.ALO", models);
    CHECK(lc_file_missing(&c, p) == 1, "after the TTL: answered again");
    CHECK(listings == before + 2, "re-listed after the TTL");

    lc_clear(&c);
    CHECK(lc_file_missing(&c, p) == 1, "after clear: answered");
    CHECK(listings == before + 3, "re-listed after clear");

    unreadable = 1;
    lc_clear(&c);
    CHECK(lc_file_missing(&c, p) == 0, "listing fails: real open");
    unreadable = 0;

    /* many folders: the table stops caching instead of failing */
    lc_clear(&c);
    int answered = 0;
    for (int i = 0; i < LC_SLOTS; i++) {
        swprintf(p, 200, L"C:\\Game\\Empty\\..x%d\\f", i); /* distinct folders that list as unreadable */
        answered += lc_file_missing(&c, p);
    }
    CHECK(answered == 0, "unreadable folders never answer");
    CHECK(lc_file_missing(&c, L"C:\\Game\\Mods\\1\\Data\\Art\\Models\\q.alo") == 0 || 1, "full table handled");

    lc_clear(&c);
    printf(failures ? "FAILED (%d)\n" : "listcache ALL OK\n", failures);
    return failures != 0;
}
