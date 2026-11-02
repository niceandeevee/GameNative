#!/usr/bin/env bash
# Builds libgndownload.so (the Rust Steam download engine behind GameDownloadService) for
# arm64-v8a and installs it into app/src/main/jniLibs. The Build Modern APK workflow runs this
# on every build whose Rust sources changed; the .so is not checked in.
#
# Prerequisites: rustup target add aarch64-linux-android, and an Android NDK
# (ANDROID_NDK_HOME, else ANDROID_NDK_LATEST_HOME, else $HOME/Android/Sdk/ndk/27.3.13750724).
set -euo pipefail

RUST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../app/src/main/cpp/gn-download/rust" && pwd)"
JNILIBS="$(cd "$RUST_DIR/../../.." && pwd)/jniLibs"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-$HOME/Android/Sdk/ndk/27.3.13750724}}"
HOST_TAG="linux-x86_64" # darwin-x86_64 on macOS

export PATH="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"
export CC_aarch64_linux_android=aarch64-linux-android26-clang
export AR_aarch64_linux_android=llvm-ar

cd "$RUST_DIR"
cargo build --release --locked --target aarch64-linux-android
mkdir -p "$JNILIBS/arm64-v8a"
cp target/aarch64-linux-android/release/libgndownload.so "$JNILIBS/arm64-v8a/libgndownload.so"
echo "Installed $JNILIBS/arm64-v8a/libgndownload.so ($(du -h "$JNILIBS/arm64-v8a/libgndownload.so" | cut -f1))"
