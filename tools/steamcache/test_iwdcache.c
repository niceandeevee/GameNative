/* Host test for iwdcache.c: cc -O2 -o test_iwdcache test_iwdcache.c iwdcache.c && ./test_iwdcache */
#define _GNU_SOURCE
#include "iwdcache.h"

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int fd;
static int64_t real_pos;
static int same = 1;
static int fail(const char *what) { printf("FAIL: %s\n", what); exit(1); }

static int64_t t_pread(void *h, int64_t off, void *buf, uint32_t n)
{
    (void)h;
    ssize_t r = pread(fd, buf, n, off);
    if (r >= 0) real_pos = off + r; /* like SetFilePointerEx + ReadFile */
    return r;
}
static int t_seek(void *h, int64_t off) { (void)h; real_pos = off; return 0; }
static int t_same(void *h, uint64_t id) { (void)h; (void)id; return same; }
static void *t_alloc(uint32_t n) { return malloc(n); }
static void t_release(void *p) { free(p); }

int main(void)
{
    char path[] = "/tmp/iwdtestXXXXXX";
    fd = mkstemp(path);
    const int64_t size = 3 * 1024 * 1024 + 12345;
    unsigned char *data = malloc(size);
    srand(1);
    for (int64_t i = 0; i < size; i++) data[i] = (unsigned char)(rand() >> 7);
    if (write(fd, data, size) != size) fail("write");

    iwd_ops ops = {t_pread, t_seek, t_same, t_alloc, t_release};
    static iwd_cache c;
    iwd_init(&c, &ops);
    void *h = (void *)0x148;
    if (iwd_track(&c, h, 7, size)) fail("track");
    iwd_file *f = iwd_find(&c, h);

    /* 1. Random reads and seeks, every byte checked against the file. */
    int64_t ref = 0;
    unsigned char *out = malloc(IWD_BLOCK * 2 + 10);
    for (int i = 0; i < 400000; i++) {
        int op = rand() % 10;
        if (op < 3) {
            int method = rand() % 3;
            int64_t dist = (rand() % 2 ? 1 : -1) * (int64_t)(rand() % (size / 2));
            if (rand() % 4 == 0) dist = rand() % 1024; /* small forward skips like a zip scan */
            int64_t base = method == 0 ? 0 : method == 1 ? ref : size;
            int64_t want = base + dist;
            int64_t np = -1;
            int ok = iwd_seek(&c, f, dist, method, &np);
            if (want < 0) { if (ok) fail("negative seek accepted"); continue; }
            if (!ok || np != want) fail("seek position");
            ref = want;
        } else {
            uint32_t n = op < 8 ? 512 : (uint32_t)(rand() % (IWD_BLOCK + 5000)) + 1;
            uint32_t done = 0;
            if (!iwd_read(&c, f, out, n, &done)) fail("read fell back without a fault");
            int64_t expect = ref >= size ? 0 : (ref + n > size ? size - ref : n);
            if (done != expect) fail("read length");
            if (done && memcmp(out, data + ref, done)) fail("read data");
            ref += done;
            if (f->pos != ref) fail("tracked position");
        }
    }
    printf("random reads/seeks: ok (%u buffer hits)\n", f->hits);

    /* 2. Corrupted buffer: the spot check must catch it, restore the real position and drop the file. */
    iwd_seek(&c, f, 1000, 0, 0);
    uint32_t done;
    iwd_read(&c, f, out, 512, &done);
    memset(f->buf, 0xAA, f->buf_len);
    int caught = 0;
    for (int i = 0; i < IWD_VERIFY_EVERY * 2 && !caught; i++) {
        int64_t at = f->pos;
        if (!iwd_read(&c, f, out, 16, &done)) {
            caught = 1;
            if (iwd_find(&c, h)) fail("file still tracked after mismatch");
            if (real_pos != at) fail("real position not restored after mismatch");
        }
    }
    if (!caught) fail("corruption not detected");
    if (c.disabled) fail("disabled after one mismatch");
    printf("corruption caught, file dropped at the right position: ok\n");

    /* 3. A second mismatch turns the whole cache off. */
    iwd_track(&c, h, 7, size);
    f = iwd_find(&c, h);
    iwd_read(&c, f, out, 512, &done);
    memset(f->buf, 0x55, f->buf_len);
    for (int i = 0; i < IWD_VERIFY_EVERY * 2 && iwd_find(&c, h); i++) iwd_read(&c, f, out, 16, &done);
    if (!c.disabled) fail("not disabled after second mismatch");
    if (!iwd_track(&c, h, 7, size)) fail("tracking allowed while disabled");
    printf("second mismatch disables the cache: ok\n");

    /* 4. Handle reused for another file: dropped on the next refill, game gets the real call. */
    iwd_init(&c, &ops);
    iwd_track(&c, h, 7, size);
    f = iwd_find(&c, h);
    iwd_seek(&c, f, 5000, 0, 0);
    same = 0;
    if (iwd_read(&c, f, out, 512, &done)) fail("read served for a different file");
    if (iwd_find(&c, h)) fail("reused handle still tracked");
    if (real_pos != 5000) fail("position not handed back for reused handle");
    printf("reused handle dropped: ok\n");

    unlink(path);
    free(out);
    free(data);
    printf("ALL OK\n");
    return 0;
}
