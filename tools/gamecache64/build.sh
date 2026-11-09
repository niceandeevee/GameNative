#!/bin/sh
# Builds the gamecache proxy psapi.dll (64-bit) into the app's assets.
# Needs gcc-mingw-w64-x86-64 (apt install gcc-mingw-w64-x86-64).
set -e
cd "$(dirname "$0")"
out=../../app/src/main/assets/gamefixes/gamecache64/psapi.dll
mkdir -p "$(dirname "$out")"
x86_64-w64-mingw32-gcc -O2 -s -shared -static-libgcc -Wall -Wextra -Werror \
    -o "$out" gamecache.c dircache.c ../steamcache/iwdcache.c gamecache.def
# The cache logic is also tested on the build host.
cc -O2 -Wall -Wextra -Werror -o /tmp/test_dircache.$$ test_dircache.c dircache.c && /tmp/test_dircache.$$ && rm -f /tmp/test_dircache.$$
(cd ../steamcache && cc -O2 -Wall -Wextra -Werror -o /tmp/test_iwdcache.$$ test_iwdcache.c iwdcache.c && /tmp/test_iwdcache.$$ && rm -f /tmp/test_iwdcache.$$)
x86_64-w64-mingw32-objdump -p "$out" | grep -E "DLL Name|Forwarder|\[ *[0-9]+\]" || true
