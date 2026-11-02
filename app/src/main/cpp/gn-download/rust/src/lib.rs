//! GameNative unified game-download engine (`libgndownload.so`).
//!
//! Steam CDN depot downloads (`store_dl::steam`: `depot_downloader` / `depot_writer` /
//! `cdn_client`), fed by JavaSteam-resolved depot keys, manifest request codes and CDN server
//! lists from Kotlin (`store_dl::steam::jni`), plus a parallel tree delete.
//!
//! Login / CM traffic stays in JavaSteam on the Kotlin side; this crate only moves bytes.

pub mod queue_delay;
pub mod jni_tree_delete;
pub(crate) mod progress_gate;
pub mod store_dl;
pub mod tree_delete;
