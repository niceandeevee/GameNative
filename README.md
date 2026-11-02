# GameNative (Steam-only fork)

A lean fork of [GameNative](https://github.com/utkarshdalal/GameNative) for running Steam games on
Android through Wine, FEX/Box64, DXVK and Turnip. Other storefronts, ads, analytics, membership,
Play Store and Discord features, VR mode and the in-app updater have been removed.

## Building

Run the **Build Modern APK** workflow from the Actions tab. It builds `libgndownload.so` (the Rust
Steam downloader in `app/src/main/cpp/gn-download/rust`) when its sources change, then the APK,
and publishes it as the `modern-release` prerelease.

Locally: `./gradlew :app:assembleModernRelease` after building the Rust library with
`tools/build-gn-download.sh`.

## License

GPL-3.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).
