/*
 * Read-ahead cache for read-only files the game reads in small pieces (Black Ops' .iwd archives:
 * 512-byte reads with a seek before each, ~270,000 calls at startup). The game's position is tracked
 * here and reads are answered from a block buffer, so only block refills reach the file system.
 *
 * Self-correcting: every IWD_VERIFY_EVERY-th answer from a buffer is re-read from the file and
 * compared. A mismatch drops that file from the cache (restoring the real file position first) and
 * the read is redone from the file; a second mismatch turns the cache off for the session.
 *
 * Portable core: the file system is reached only through iwd_ops, so the logic is unit-tested on the
 * build host (test_iwdcache.c) and wired to Wine's kernelbase in steamcache.c.
 */
#ifndef IWDCACHE_H
#define IWDCACHE_H

#include <stdint.h>

#define IWD_BLOCK (128 * 1024)
#define IWD_MAX_FILES 128
#define IWD_VERIFY_EVERY 64

typedef struct {
    /* Read count bytes at offset into buf; returns bytes read (0 at end), or -1 on error. */
    int64_t (*pread)(void *handle, int64_t offset, void *buf, uint32_t count);
    /* Move the real file position (used when a file leaves the cache). Returns 0 on success. */
    int (*seek)(void *handle, int64_t offset);
    /* Still the same file the cache opened? (catches a handle number reused after a close) */
    int (*same_file)(void *handle, uint64_t id);
    void *(*alloc)(uint32_t size);
    void (*release)(void *p);
} iwd_ops;

typedef struct {
    void *handle;
    uint64_t id;
    int64_t size;
    int64_t pos; /* the game's file position */
    int64_t buf_off;
    uint32_t buf_len;
    uint32_t hits;
    unsigned char *buf;
} iwd_file;

typedef struct {
    iwd_ops ops;
    iwd_file files[IWD_MAX_FILES];
    int mismatches;
    int disabled;
} iwd_cache;

void iwd_init(iwd_cache *c, const iwd_ops *ops);
/* Start tracking a freshly opened handle (position 0). Returns 0 if tracked. */
int iwd_track(iwd_cache *c, void *handle, uint64_t id, int64_t size);
iwd_file *iwd_find(iwd_cache *c, void *handle);
/* Stop tracking; when keep_position, the real file position is set to the game's first. */
void iwd_forget(iwd_cache *c, iwd_file *f, int keep_position);
/* Returns 1 on success (bytes in *done), 0 to tell the caller to fall back to the real call. */
int iwd_read(iwd_cache *c, iwd_file *f, void *out, uint32_t count, uint32_t *done);
/* method: 0 begin, 1 current, 2 end. Returns 1 and the new position, or 0 for a negative seek. */
int iwd_seek(iwd_cache *c, iwd_file *f, int64_t distance, int method, int64_t *newpos);

#endif
