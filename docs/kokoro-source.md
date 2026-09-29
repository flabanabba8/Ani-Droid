# Kokoro source and release provenance

## Code compiled into PageCast

The Kotlin JNI wrapper is vendored as source at
`app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` under Apache-2.0.
Upstream: https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/sherpa-onnx/kotlin-api/Tts.kt
(release v1.13.8). The sole modification replaces `System.loadLibrary` with
PageCast's verified optional-runtime loader. No precompiled AAR/JAR or native
library from sherpa-onnx is used to build the APK. Gradle compiles the Kotlin
source alongside PageCast. No model conversion or Python tooling is required
for a normal app build.

## Model resources in the APK

`app/src/main/assets/kokoro-downloads.json` contains URLs, sizes, SHA-256 hashes,
and ZIP byte ranges for optional upstream downloads. `kokoro-recipe.bin` is a
gzip-compressed KQR1 model transformation resource, not a JVM/DEX/native binary.
It contains ONNX graph fragments and operations to copy, transpose and quantize
tensors from the original model. Its decoder is `KokoroRecipe.kt`; its complete
generator source is `tools/kokoro-recipe.py`. Model resource treatment remains
subject to F-Droid review. F-Droid's extension-based source scanner flags this
`.bin` resource, so the proposed recipe declares one explicit `scanignore` for
this model resource only. No native/JVM/DEX binary is excluded from scanning.

The maintained resource generation path is:

1. Run `python3 tools/prepare-kokoro-android.py` (requires `uv` and Python 3.11+).
2. The script checks the original archive and runtime AAR hashes, uses the pinned
   ONNX conversion tools in `tools/quantize-kokoro.py`, and checks the converted
   model's hash. `tools/package-kokoro-downloads.py` emits the download catalog
   and calls the recipe generator.
3. Compare the two generated bootstrap assets with `app/src/main/assets` before
   updating them. The original and converted model hashes and per-file hashes
   are recorded in the catalog. Generation tools are maintainer tools, not APK
   build dependencies.

Original model SHA-256: b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11

Converted model SHA-256: 84514ba144a99ac1e00d2ab60ebf9207ba7794b3b86c96ecb2ed9fb983f54f51

## Optional downloads, outside the APK

Kokoro 1.0 weights/voices: https://huggingface.co/hexgrad/Kokoro-82M (Apache-2.0).
ONNX distribution: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models.
The archive hash is pinned in the catalog because this release tag is mutable.

Native runtime: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8.
Build source: https://github.com/k2-fsa/sherpa-onnx/tree/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf.
Its build files identify the transitive ONNX Runtime and eSpeak NG sources.
The runtime includes ONNX Runtime (MIT) and eSpeak NG (GPL-3.0-or-later);
license copies ship under `assets/kokoro-licenses`.

These executable downloads happen only after a separate opt-in dialog clearly
states that they bypass F-Droid's checks. Cancel and Download are equally
available. Reading works without them; Android TTS is another narration route.
Do not describe the downloaded native runtime as built or verified by F-Droid.
