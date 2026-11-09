/*
 * Missing-directory cache for games that probe for loose files before falling back to their
 * archives. Star Wars: Empire at War checks every model as Data\Art\Models\<name> under two roots
 * that don't exist on a Steam install; each probe walks Wine's path lookup only to fail with "path
 * not found". This remembers which directories under the game folder are missing, so a probe
 * there can fail at once with the same error.
 *
 * Only directory existence is cached (never files), only under one root (the game's install
 * folder), and a missing entry is re-checked after DC_RECHECK_MS, so a directory created later
 * is seen within that time. Creating a directory through the hooked calls clears the cache.
 *
 * Portable core: the file system is reached through dc_ops, so it is unit-tested on the host
 * (test_dircache.c) and wired to Wine in gamecache.c.
 */
#ifndef DIRCACHE_H
#define DIRCACHE_H

#include <stdint.h>
#include <wchar.h>

#define DC_SLOTS 8192
#define DC_MAX_PATH 520
#define DC_RECHECK_MS 10000

enum { DC_UNKNOWN = 0, DC_EXISTS = 1, DC_MISSING = 2 };

typedef struct {
    /* DC_EXISTS, DC_MISSING, or DC_UNKNOWN when the answer must not be cached (e.g. access denied). */
    int (*dir_state)(const wchar_t *dir);
    uint64_t (*now_ms)(void);
    void *(*alloc)(uint32_t size);
    void (*release)(void *p);
} dc_ops;

typedef struct {
    uint32_t hash;
    uint8_t state;
    uint64_t checked_ms;
    wchar_t *path;
} dc_slot;

typedef struct {
    dc_ops ops;
    wchar_t root[DC_MAX_PATH]; /* normalized, ends with '\' */
    uint32_t root_len;
    dc_slot slots[DC_SLOTS];
    uint32_t used;
} dc_cache;

/* root: a full path; only paths under it are ever answered from the cache. */
void dc_init(dc_cache *c, const dc_ops *ops, const wchar_t *root);
/*
 * full_path: an absolute path as returned by GetFullPathName. Returns 1 when the file's directory is
 * known to be missing (the caller fails the open with ERROR_PATH_NOT_FOUND), 0 to do the real call.
 */
int dc_parent_missing(dc_cache *c, const wchar_t *full_path);
/* Forget everything (a directory was created). */
void dc_clear(dc_cache *c);
/* Lowercases, turns '/' into '\' and collapses repeated separators. Returns the length. */
uint32_t dc_normalize(const wchar_t *in, wchar_t *out, uint32_t cap);

#endif
