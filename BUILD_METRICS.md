# Build Metrics

Last updated: 2026-05-27

## APK size

Current measured artifact:

- Variant: `afatRelease`
- ABI: `arm64-v8a`
- File: `TMessagesProj_App/build/outputs/apk/afat/release/app-arm64-v8a.apk`
- APK size: `101.3 MB`
- Download size: `63.2 MB`

Universal build size for the same codebase:

- Variant: `afatRelease`
- ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64`
- APK size: `145-160 MB`
- Download size: `78-85 MB`

Notes:

- The universal build values are calculated from the current `arm64-v8a` APK Analyzer data and the expected extra native payload for `armeabi-v7a` and `x86_64`.
- The largest contributors are native libraries in `lib/` and heavy assets such as `assets/model.glb`.
- The current `arm64-v8a` build is the smallest practical APK artifact for direct distribution.

## ML Kit performance

- Target on-device latency P50: `<= 2.0s` on `Pixel 6+`
- Target on-device latency P50: `<= 3.0s` on `mid-tier 2022`
- Samsung M31 (`mid-tier`, `2020`): total pipeline P50 `4.22s` (`capture + mlkit`, `n=42`)
- Samsung M31 (`2448x3264`): capture P50 `1.24s`
- Samsung M31 (`mlkit_v161`): ML Kit P50 `2.98s`
