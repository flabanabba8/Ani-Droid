# Kokoro: optional on-device speech

Kokoro's model and native runtime are **not bundled in the APK**. The base universal debug APK is approximately **28.0 MB**.

## Install and use

1. Select **Settings → Speech engine → Kokoro**.
2. Tap **Download Kokoro** and read the opt-in notice. Native runtime code is downloaded outside F-Droid's checks.
3. Confirm **Download**. The app fetches pinned public sherpa-onnx GitHub release files over HTTPS, verifies their hashes, and prepares Kokoro on the phone.
4. After installation, select a voice and use **Save & test speech**. Narrator mode works offline without credentials.

No download occurs merely by selecting Kokoro or trying to play a passage. Cancel stops the download and removes partial files; retry starts again. Deleting Kokoro stops playback/preparation and removes its model/runtime files. Cloud engines remain usable without Kokoro installed. Changing away from Kokoro cancels an active download before releasing the speech runtime.

Existing verified model installations are reused, so an upgrade may need only the small runtime download. A fresh install downloads the original upstream model archive, then recreates the exact selective-8-bit model locally. The initial download is larger than the final prepared model. An older full-precision installation can also be verified and converted locally without downloading the archive again. Allow roughly 1.2 GB of temporary free space for archive extraction/conversion; temporary files are removed afterward.

## No custom hosting

Everything is obtained from public upstream releases:

- Model archive: `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2`
- Runtime source: `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar`

The app fetches only the two compressed ZIP entries for the device's process architecture using verified HTTP byte ranges. It does not download all four architecture variants. Supported architectures are ARM64, ARMv7, x86 and x86_64. HTTPS redirects are permitted; non-HTTPS requests are rejected.

A **111 KB recipe** included in the APK copies unchanged tensor data, transposes weights where required, and quantizes only the tensors changed by our established selective-8 conversion. The prepared ONNX file must match SHA-256 `84514ba144a99ac1e00d2ab60ebf9207ba7794b3b86c96ecb2ed9fb983f54f51`. This preserves the benchmarked model rather than substituting a different quantization. The recipe carries model metadata and transformation instructions, not the large trained weight tensors.

## Verification and loading

Download URLs, archive/range sizes, ZIP offsets, file hashes and manifests are embedded at build time. Downloads are bounded and verified before extraction. Paths are restricted to expected manifest entries; installation publishes only complete bundles. Native files are made read-only and their hashes are checked again before loading. Model hashes are checked before first generation in a runtime session.

The APK retains sherpa's Java/Kotlin wrapper classes, with `OfflineTts`'s native-library initializer redirected to `KokoroNativeLoader`. The loader loads verified ONNX Runtime and sherpa JNI libraries from private storage before constructing the TTS runtime. No native library is loaded from an arbitrary user-provided URL or path.

`tools/prepare-kokoro-android.py` prepares pinned upstream inputs, `tools/package-kokoro-downloads.py` emits lightweight APK metadata/wrappers, and `tools/kokoro-recipe.py` generates the reproducible conversion recipe. Development builds still need the original source model to produce/check the recipe. Fresh APK packaging remains mandatory on every rebuild.

See [benchmarks](kokoro-benchmarks.md) and [F-Droid status](fdroid.md). Download consent is one F-Droid requirement; the existing licensing and reproducible-build requirements still apply.
