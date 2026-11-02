# gn-download

GameNative's Steam download engine (Rust, JNI, `libgndownload.so`): CDN depot downloads and a
parallel tree delete. Login and CM traffic stay in JavaSteam on the Kotlin side; this crate only
moves bytes.

## Layout

| Path | Role |
|---|---|
| `store_dl/steam/depot_downloader.rs` | Orchestration: manifests, depot keys, DLC/redist depots, cache validation |
| `store_dl/steam/depot_writer.rs` | Depot pipeline: verify/resume, chunk dispatch, ordered writes, stall watchdog |
| `store_dl/steam/cdn_client.rs` / `cdn_probe.rs` | CDN client (manifest/chunk GETs) and throughput-based server ranking |
| `store_dl/steam/depot_chunk.rs` / `crypto.rs` | Chunk decrypt (AES-256) + VZip/LZMA/zstd decompress |
| `store_dl/steam/content_manifest.rs`, `proto_wire.rs`, `pb/` | Manifest and content-server protobuf parsing |
| `store_dl/steam/depot_config.rs` | Manifest store (`.DepotDownloader/`) |
| `store_dl/steam/jni.rs` | JNI entry points called from `NativeSteamDownload.kt` |
| `queue_delay.rs` | LEDBAT (RFC 6817) queueing-delay estimator for the writer's window controller |
| `progress_gate.rs` | Rate-limits progress callbacks into the JVM |
| `tree_delete.rs`, `jni_tree_delete.rs` | Parallel directory deletion (`NativeTreeDelete.kt`) |

Every byte is written directly to its final file and verified in place; there are no temp files.

## Building

The Build Modern APK workflow builds the library from source whenever these sources change and
caches it otherwise; the `.so` is not checked in. Locally:

```bash
rustup target add aarch64-linux-android
ANDROID_NDK_HOME=/path/to/ndk tools/build-gn-download.sh
```

Host tests: `cargo test --release` in this directory.
