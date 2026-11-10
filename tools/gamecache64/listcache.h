/*
 * Folder-listing cache for games that probe for loose files that mostly don't exist. Star Wars:
 * Empire at War with a mod looks for every model and texture in the mod's own Data folders before the
 * game's: the folders exist, so the missing-folder cache (dircache) can't answer, and each miss costs
 * Wine a case-insensitive scan of the whole folder (about 1.4 ms under FEX; a battle load spent
 * 9.8 s of every 10 s on 6,000-7,000 of them). This reads a folder's listing once and answers "not
 * there" from it.
 *
 * Per folder only 64-bit hashes of the lowercased names are kept: a hash collision can only make a
 * missing file look present (the real open then runs), never hide one that exists. Only folders under
 * the game's install folder are cached, a listing is re-read after LC_TTL_MS, and any write, create,
 * move or copy through the hooked calls clears everything. Names with '~' (8.3 short names) are never
 * answered.
 *
 * Portable core (file system through lc_ops), unit-tested on the host in test_listcache.c.
 */
#ifndef LISTCACHE_H
#define LISTCACHE_H

#include <stdint.h>
#include <wchar.h>

#define LC_SLOTS 4096
#define LC_MAX_PATH 520
#define LC_TTL_MS 30000
#define LC_MAX_NAMES 200000 /* per folder; a bigger one isn't cached */

typedef struct {
    /*
     * Calls add(ctx, name) for every entry of dir (a full path without a trailing '\'). Returns 1 when
     * the whole listing was delivered, 0 when it couldn't be read (nothing is cached then).
     */
    int (*list_dir)(const wchar_t *dir, void (*add)(void *ctx, const wchar_t *name), void *ctx);
    uint64_t (*now_ms)(void);
    void *(*alloc)(uint32_t size);
    void (*release)(void *p);
} lc_ops;

typedef struct {
    uint32_t hash;
    uint64_t listed_ms;
    wchar_t *path;     /* lowercase, no trailing '\' */
    uint64_t *names;   /* sorted name hashes */
    uint32_t count;
} lc_slot;

typedef struct {
    lc_ops ops;
    wchar_t root[LC_MAX_PATH]; /* lowercase, ends with '\' */
    uint32_t root_len;
    lc_slot slots[LC_SLOTS];
    uint32_t used;
} lc_cache;

void lc_init(lc_cache *c, const lc_ops *ops, const wchar_t *root);
/* full_path: absolute (GetFullPathName). 1 = the folder is listed and the file isn't in it. */
int lc_file_missing(lc_cache *c, const wchar_t *full_path);
void lc_clear(lc_cache *c);

#endif
