#include "dircache.h"

#include <string.h>

uint32_t dc_normalize(const wchar_t *in, wchar_t *out, uint32_t cap)
{
    uint32_t n = 0;
    for (; *in && n + 1 < cap; in++) {
        wchar_t ch = *in == L'/' ? L'\\' : *in;
        if (ch >= L'A' && ch <= L'Z') ch = (wchar_t)(ch - L'A' + L'a');
        /* Keep a leading "\\" (UNC / \\?\ prefixes) but collapse repeats elsewhere. */
        if (ch == L'\\' && n > 1 && out[n - 1] == L'\\') continue;
        out[n++] = ch;
    }
    if (*in) return 0; /* too long: don't cache */
    out[n] = 0;
    return n;
}

static uint32_t hash_of(const wchar_t *s, uint32_t len)
{
    uint32_t h = 2166136261u;
    for (uint32_t i = 0; i < len; i++) {
        h ^= (uint32_t)s[i];
        h *= 16777619u;
    }
    return h ? h : 1;
}

void dc_init(dc_cache *c, const dc_ops *ops, const wchar_t *root)
{
    memset(c, 0, sizeof(*c));
    c->ops = *ops;
    uint32_t n = dc_normalize(root, c->root, DC_MAX_PATH - 1);
    if (n && c->root[n - 1] != L'\\') {
        c->root[n++] = L'\\';
        c->root[n] = 0;
    }
    c->root_len = n;
}

void dc_clear(dc_cache *c)
{
    for (uint32_t i = 0; i < DC_SLOTS; i++) {
        if (c->slots[i].path) c->ops.release(c->slots[i].path);
    }
    memset(c->slots, 0, sizeof(c->slots));
    c->used = 0;
}

static dc_slot *find_slot(dc_cache *c, const wchar_t *dir, uint32_t len, uint32_t h, int *found)
{
    for (uint32_t i = 0; i < DC_SLOTS; i++) {
        dc_slot *s = &c->slots[(h + i) & (DC_SLOTS - 1)];
        if (!s->path) {
            *found = 0;
            return s;
        }
        if (s->hash == h && wcsncmp(s->path, dir, len) == 0 && s->path[len] == 0) {
            *found = 1;
            return s;
        }
    }
    *found = 0;
    return 0;
}

int dc_parent_missing(dc_cache *c, const wchar_t *full_path)
{
    if (!c->root_len) return 0;
    wchar_t dir[DC_MAX_PATH];
    uint32_t len = dc_normalize(full_path, dir, DC_MAX_PATH);
    if (!len) return 0;
    while (len && dir[len - 1] != L'\\') len--; /* strip the file name */
    if (len <= c->root_len) return 0;          /* the root itself, or outside it */
    if (wcsncmp(dir, c->root, c->root_len) != 0) return 0;
    len--; /* drop the trailing separator */
    dir[len] = 0;
    for (uint32_t i = 0; i < len; i++) {
        if (dir[i] != L'.' || (i && dir[i - 1] != L'\\')) continue;
        uint32_t j = i + 1;
        if (dir[j] == L'.') j++;
        if (dir[j] == L'\\' || dir[j] == 0) return 0; /* "." or ".." left in: not canonical, don't guess */
    }

    uint32_t h = hash_of(dir, len);
    uint64_t now = c->ops.now_ms();
    int found;
    dc_slot *s = find_slot(c, dir, len, h, &found);
    if (found) {
        if (s->state == DC_EXISTS) return 0;
        if (now - s->checked_ms < DC_RECHECK_MS) return 1;
    }
    int state = c->ops.dir_state(dir);
    if (state == DC_UNKNOWN) return 0;
    if (found) {
        s->state = (uint8_t)state;
        s->checked_ms = now;
    } else if (s && c->used < DC_SLOTS * 3 / 4) {
        wchar_t *copy = c->ops.alloc((len + 1) * sizeof(wchar_t));
        if (copy) {
            memcpy(copy, dir, (len + 1) * sizeof(wchar_t));
            s->path = copy;
            s->hash = h;
            s->state = (uint8_t)state;
            s->checked_ms = now;
            c->used++;
        }
    }
    return state == DC_MISSING;
}
