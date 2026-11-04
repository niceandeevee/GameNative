#!/bin/sh
# Builds the steamcache proxy xinput1_3.dll (32-bit) into the app's assets.
# Needs gcc-mingw-w64-i686 (apt install gcc-mingw-w64-i686).
set -e
cd "$(dirname "$0")"
out=../../app/src/main/assets/gamefixes/steamcache/xinput1_3.dll
mkdir -p "$(dirname "$out")"
i686-w64-mingw32-gcc -O2 -s -shared -static-libgcc -Wall -Wextra -Werror \
    -Wl,--enable-stdcall-fixup -o "$out" steamcache.c steamcache.def
i686-w64-mingw32-objdump -p "$out" | grep -E "DLL Name|^\s+\[|Forwarder|Ordinal Base|Name:" || true
