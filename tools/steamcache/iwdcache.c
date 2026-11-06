#include "iwdcache.h"

#include <string.h>

void iwd_init(iwd_cache *c, const iwd_ops *ops)
{
    memset(c, 0, sizeof(*c));
    c->ops = *ops;
}

int iwd_track(iwd_cache *c, void *handle, uint64_t id, int64_t size)
{
    if (c->disabled || size < 0) return -1;
    for (int i = 0; i < IWD_MAX_FILES; i++) {
        iwd_file *f = &c->files[i];
        if (f->handle) continue;
        memset(f, 0, sizeof(*f));
        f->handle = handle;
        f->id = id;
        f->size = size;
        return 0;
    }
    return -1;
}

iwd_file *iwd_find(iwd_cache *c, void *handle)
{
    if (!handle) return 0;
    for (int i = 0; i < IWD_MAX_FILES; i++)
        if (c->files[i].handle == handle) return &c->files[i];
    return 0;
}

void iwd_forget(iwd_cache *c, iwd_file *f, int keep_position)
{
    if (!f || !f->handle) return;
    if (keep_position) c->ops.seek(f->handle, f->pos);
    if (f->buf) c->ops.release(f->buf);
    memset(f, 0, sizeof(*f));
}

static void mismatch(iwd_cache *c, iwd_file *f)
{
    iwd_forget(c, f, 1);
    if (++c->mismatches >= 2) {
        c->disabled = 1;
        for (int i = 0; i < IWD_MAX_FILES; i++)
            if (c->files[i].handle) iwd_forget(c, &c->files[i], 1);
    }
}

/* Loads the block holding f->pos. Returns 1 if there is data, 0 at end of file, -1 on failure. */
static int refill(iwd_cache *c, iwd_file *f)
{
    if (f->pos >= f->size) return 0;
    if (!c->ops.same_file(f->handle, f->id)) return -1;
    if (!f->buf && !(f->buf = c->ops.alloc(IWD_BLOCK))) return -1;
    int64_t got = c->ops.pread(f->handle, f->pos, f->buf, IWD_BLOCK);
    if (got < 0) return -1;
    f->buf_off = f->pos;
    f->buf_len = (uint32_t)got;
    return got > 0;
}

int iwd_read(iwd_cache *c, iwd_file *f, void *out, uint32_t count, uint32_t *done)
{
    *done = 0;
    if (c->disabled) return 0;
    if (count >= IWD_BLOCK) {
        /* Large reads gain nothing from the buffer: read straight from the tracked position. */
        if (!c->ops.same_file(f->handle, f->id)) {
            iwd_forget(c, f, 0); /* not our file any more: leave its position alone */
            return 0;
        }
        int64_t got = c->ops.pread(f->handle, f->pos, out, count);
        if (got < 0) {
            iwd_forget(c, f, 1);
            return 0;
        }
        f->pos += got;
        *done = (uint32_t)got;
        return 1;
    }
    int64_t start = f->pos;
    unsigned char *dst = out;
    uint32_t left = count;
    int from_buffer = 0;
    while (left) {
        if (!(f->buf_len && f->pos >= f->buf_off && f->pos < f->buf_off + f->buf_len)) {
            int r = refill(c, f);
            if (r < 0) {
                /* Give the file back to the real calls at the game's position; the caller redoes the read. */
                f->pos = start;
                *done = 0;
                iwd_forget(c, f, 1);
                return 0;
            }
            if (r == 0) break;
        } else {
            from_buffer = 1;
        }
        uint32_t at = (uint32_t)(f->pos - f->buf_off);
        uint32_t n = f->buf_len - at;
        if (n > left) n = left;
        memcpy(dst, f->buf + at, n);
        dst += n;
        left -= n;
        f->pos += n;
    }
    *done = count - left;

    if (from_buffer && *done && ++f->hits % IWD_VERIFY_EVERY == 0) {
        /* Spot check against the file itself. Compare in pieces so no large stack buffer is needed. */
        unsigned char check[4096];
        uint32_t off = 0;
        int ok = 1;
        while (off < *done && ok) {
            uint32_t n = *done - off;
            if (n > sizeof(check)) n = sizeof(check);
            int64_t got = c->ops.pread(f->handle, start + off, check, n);
            ok = got == (int64_t)n && memcmp(check, (unsigned char *)out + off, n) == 0;
            off += n;
        }
        if (!ok) {
            f->pos = start;
            *done = 0;
            mismatch(c, f); /* restores the real position to start; the caller redoes the read */
            return 0;
        }
    }
    return 1;
}

int iwd_seek(iwd_cache *c, iwd_file *f, int64_t distance, int method, int64_t *newpos)
{
    (void)c;
    int64_t base = method == 0 ? 0 : method == 1 ? f->pos : f->size;
    int64_t pos = base + distance;
    if (pos < 0) return 0;
    f->pos = pos;
    if (newpos) *newpos = pos;
    return 1;
}
