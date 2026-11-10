#include "listcache.h"

#include <stdlib.h>
#include <string.h>
#include <wctype.h>

static wchar_t lower(wchar_t ch)
{
    if (ch == L'/') return L'\\';
    return (wchar_t)towlower(ch);
}

static uint64_t name_hash(const wchar_t *s, uint32_t n)
{
    uint64_t h = 1469598103934665603ULL;
    for (uint32_t i = 0; i < n; i++) {
        h ^= (uint64_t)lower(s[i]);
        h *= 1099511628211ULL;
    }
    return h;
}

static uint32_t path_hash(const wchar_t *s, uint32_t n)
{
    uint32_t h = 2166136261u;
    for (uint32_t i = 0; i < n; i++) {
        h ^= (uint32_t)s[i];
        h *= 16777619u;
    }
    return h ? h : 1;
}

void lc_init(lc_cache *c, const lc_ops *ops, const wchar_t *root)
{
    memset(c, 0, sizeof(*c));
    c->ops = *ops;
    uint32_t n = 0;
    for (; root[n] && n < LC_MAX_PATH - 2; n++) c->root[n] = lower(root[n]);
    if (n && c->root[n - 1] != L'\\') c->root[n++] = L'\\';
    c->root[n] = 0;
    c->root_len = n;
}

static void free_slot(lc_cache *c, lc_slot *s)
{
    if (s->path) c->ops.release(s->path);
    if (s->names) c->ops.release(s->names);
    memset(s, 0, sizeof(*s));
}

void lc_clear(lc_cache *c)
{
    for (uint32_t i = 0; i < LC_SLOTS; i++)
        if (c->slots[i].hash) free_slot(c, &c->slots[i]);
    c->used = 0;
}

typedef struct {
    lc_cache *c;
    uint64_t *names;
    uint32_t count, cap;
    int overflow;
} collect;

static void add_name(void *ctx, const wchar_t *name)
{
    collect *k = ctx;
    if (k->overflow) return;
    if (k->count == k->cap) {
        uint32_t cap = k->cap ? k->cap * 2 : 256;
        if (cap > LC_MAX_NAMES) {
            k->overflow = 1;
            return;
        }
        uint64_t *n = k->c->ops.alloc(cap * sizeof(uint64_t));
        if (!n) {
            k->overflow = 1;
            return;
        }
        if (k->names) {
            memcpy(n, k->names, k->count * sizeof(uint64_t));
            k->c->ops.release(k->names);
        }
        k->names = n;
        k->cap = cap;
    }
    k->names[k->count++] = name_hash(name, (uint32_t)wcslen(name));
}

static int cmp_u64(const void *a, const void *b)
{
    uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b;
    return x < y ? -1 : x > y;
}



static int has_name(const lc_slot *s, uint64_t h)
{
    uint32_t lo = 0, hi = s->count;
    while (lo < hi) {
        uint32_t mid = (lo + hi) / 2;
        if (s->names[mid] < h) lo = mid + 1;
        else if (s->names[mid] > h) hi = mid;
        else return 1;
    }
    return 0;
}

int lc_file_missing(lc_cache *c, const wchar_t *full_path)
{
    wchar_t dir[LC_MAX_PATH];
    uint32_t n = 0, last_sep = 0;
    for (; full_path[n]; n++) {
        if (n >= LC_MAX_PATH - 1) return 0;
        dir[n] = lower(full_path[n]);
        if (dir[n] == L'\\') last_sep = n;
    }
    dir[n] = 0;
    if (!last_sep || last_sep + 1 >= n) return 0;
    if (n < c->root_len || wcsncmp(dir, c->root, c->root_len)) return 0;
    const wchar_t *name = full_path + last_sep + 1;
    uint32_t name_len = n - last_sep - 1;
    for (uint32_t i = 0; i < name_len; i++)
        if (name[i] == L'~' || name[i] == L'*' || name[i] == L'?' || name[i] == L':') return 0;
    /* a "..\" or ".\" left in the path means it wasn't normalized: don't guess */
    for (uint32_t i = 0; i + 1 < last_sep; i++)
        if (dir[i] == L'\\' && dir[i + 1] == L'.') return 0;
    dir[last_sep] = 0;

    uint32_t h = path_hash(dir, last_sep);
    uint64_t now = c->ops.now_ms();
    lc_slot *s = NULL;
    uint32_t i = h % LC_SLOTS;
    for (uint32_t probe = 0; probe < LC_SLOTS; probe++, i = (i + 1) % LC_SLOTS) {
        lc_slot *t = &c->slots[i];
        if (!t->hash) break;
        if (t->hash == h && !wcscmp(t->path, dir)) {
            s = t;
            break;
        }
    }
    if (s && now - s->listed_ms > LC_TTL_MS) {
        /* stale: list again into the same slot */
        if (s->names) c->ops.release(s->names);
        s->names = NULL;
        s->count = 0;
    } else if (!s) {
        if (c->used >= LC_SLOTS * 3 / 4) return 0; /* full: stop caching new folders */
        i = h % LC_SLOTS;
        while (c->slots[i].hash) i = (i + 1) % LC_SLOTS;
        s = &c->slots[i];
        s->path = c->ops.alloc((last_sep + 1) * sizeof(wchar_t));
        if (!s->path) return 0;
        memcpy(s->path, dir, (last_sep + 1) * sizeof(wchar_t));
        s->hash = h;
        c->used++;
    }
    if (!s->names) {
        collect k = {c, NULL, 0, 0, 0};
        wchar_t real_dir[LC_MAX_PATH];
        memcpy(real_dir, full_path, last_sep * sizeof(wchar_t));
        real_dir[last_sep] = 0;
        int ok = c->ops.list_dir(real_dir, add_name, &k);
        if (!ok || k.overflow) {
            if (k.names) c->ops.release(k.names);
            /* keep the slot (so it isn't re-listed on every call) but answer nothing for a while */
            s->names = c->ops.alloc(sizeof(uint64_t));
            s->count = 0;
            s->listed_ms = now;
            if (s->names) s->names[0] = 0;
            s->count = (uint32_t)-1; /* marks "can't say" */
            return 0;
        }
        if (!k.names) {
            k.names = c->ops.alloc(sizeof(uint64_t)); /* empty folder */
            if (!k.names) return 0;
        }
        qsort(k.names, k.count, sizeof(uint64_t), cmp_u64);
        s->names = k.names;
        s->count = k.count;
        s->listed_ms = now;
    }
    if (s->count == (uint32_t)-1) return 0;
    return !has_name(s, name_hash(name, name_len));
}
