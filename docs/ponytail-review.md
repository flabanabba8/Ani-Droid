# PageCast Ponytail review

Date: 2026-09-29. Branch: `pagecast`. Base: `54b04ab`.

This is a file-by-file review of all **138 tracked source, test, script, and configuration files** at the base commit. Production callers and existing tests were traced before choosing changes. A reviewed file need not require an edit.

| Area | Files at base | Review outcome |
| --- | ---: | --- |
| Production Kotlin | 59 | Changes in app routing, voice direction, pronunciation lookup, imports, playback, HTTP retry handling, and UI |
| JVM tests | 28 | All read; four regression tests added to existing files |
| Android instrumentation / benchmarks | 10 | All read and compiled; provider opt-in gates retained |
| Python tools and tests | 13 | Existing standard-library server/tool flows and pinned model conversion retained |
| Shell tools | 12 | Existing short scripts retained; all passed syntax checks |
| Android manifests and resources | 6 | Existing permissions, backup exclusions, network restrictions, and resources retained |
| Build, launchers, service, and ignore configuration | 10 | Existing build/checksum/packaging behavior retained |
| **Total** | **138** | **Complete source/configuration review** |

The 32 other tracked files are prose documentation/license notices and the generated Gradle wrapper JAR. Reference documentation informed the review; the JAR was not decompiled or rewritten. Downloaded dependencies, model binaries, ignored build products, and secrets are outside the authored code review.

## Applied changes

- **HTTP requests:** seven copies of the same HTTP-status retry loop now use one private helper. Request construction and response work stay on the IO dispatcher. Provider-specific authentication, error text, parsing, and spending callbacks remain explicit. The Google request loop remains separate because it also handles transport failures and timeout-specific retries. Four app-level attempts and the existing OkHttp behavior are preserved; `503` with `Retry-After: 0` can produce eight wire requests because OkHttp retries once inside each call.
- **Voice pickers:** six identical Compose implementations now share one helper in `ui/VoicePickers.kt`. Existing provider entry points, validation, labels, optional automatic selection, and explicit custom-entry state are preserved. Six files become one, without new dependencies.
- **Voice direction:** fixed-voice providers reuse the existing `distinctVoice` dispatcher and `Settings.speechVoice`; paragraph pause selection is computed once. Android keeps its narrator-only behavior. Removed two assertions the Kotlin compiler proved unnecessary.
- **Chapter routing:** the seven identical 1,000-character sentence-chunk branches in `ReaderApp` are one `when` branch.
- **Imports:** HTML title and body extraction share one parsed document. Reflow and chapter-heading regexes are compiled once rather than once per paragraph. Archive size limits and import validation remain in place.
- **Pronunciations:** series membership is loaded at most once per applicable-rule lookup, and only if a series rule needs it. This uses a local lazy value, with no persistent cache or invalidation mechanism.
- **Reader rendering:** sentence boundaries are remembered for the active text, and rejection records are grouped when they change. Playback progress updates reuse those results.
- **Playback:** the buffer calculation sums a `subList` view instead of copying the queue tail every 100 ms. Kokoro's single-value engine check uses equality.

Production Kotlin shrinks from **4,375 to 4,228 lines** (147 fewer). This measures code size; no end-to-end timing or battery improvement is claimed.

## Reviewed and retained

- Book writes, stale-edit checks, analysis checkpoints, manual overrides, cache signatures, and rejected-passage persistence protect saved state. Their validation and recovery paths remain.
- Quote segmentation preserves source offsets, Unicode pairs, scene breaks, and punctuation. Sentence planning and cache-version identities were not changed.
- Native model preparation, quantization recipes, and WAV placeholder normalization encode exact binary contracts. Their checksum and format checks remain rather than adding a general conversion layer.
- Android and Kokoro runtime locks protect stateful engines. Shared analysis outlives canceled playback waiters deliberately. These lifetime boundaries remain.
- Provider catalogs, settings fields, and provider-specific validation remain explicit. Consolidating them into a registry would introduce a broader migration and framework beyond the proven repeated control flow removed here.
- The token broker and its pairing tools keep certificate pinning, token caching, constant-time authorization checks, and private credential transfer. These are necessary behavior, not removable boilerplate.
- Build packaging deliberately recreates APK archives to avoid retained ZIP holes. Runtime/model download pins and dependency versions are unchanged.
- Tests retain independent fixtures and provider assertions; shortening them by deriving expected values from the implementation would reduce their value.

## Validation

Final Gradle validation passed: **129 JVM tests, zero failures/errors/skips; debug app and instrumentation APKs built; lint completed with zero errors (13 warnings and four hints)**. The build also reports Gradle deprecation notices; no dependency upgrades were made in this pass.

Commands:

```sh
source tools/env.sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --console=plain
python3 tools/token-broker/test_broker.py
python3 tools/mock-server/test_mock.py
python3 -m compileall -q tools
for script in tools/*.sh; do bash -n "$script" || exit; done
sh -n gradlew
git diff --check
```

The broker and mock suites pass (four Python tests total). Python compilation, shell syntax, and whitespace checks pass. New JVM checks cover all fixed-voice routes and modes, HTML metadata/fallback text, retry limits across all eight affected HTTP entry points, redirect/permanent-error rejection, malformed JSON, and cancellation during backoff. Existing tests cover cache identity, spending, imported formats, bookmarks, local model reconstruction, and audio handling.

No Android device was connected. Instrumentation sources and the test APK are built, but device execution, live paid providers, and interactive picker behavior are not claimed as tested.

## Complete reviewed-file inventory

Paths below are from the base commit, including the six picker files consolidated by this change. The added `app/src/main/java/com/geminireader/ui/VoicePickers.kt` was also reviewed.

### Build and repository configuration

```text
.gitignore
app/build.gradle.kts
build.gradle.kts
gradle.properties
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
settings.gradle.kts
```

### Android tests and benchmarks

```text
app/src/androidTest/java/com/geminireader/tts/AndroidTtsOnDeviceTest.kt
app/src/androidTest/java/com/geminireader/tts/CartesiaOnDeviceTest.kt
app/src/androidTest/java/com/geminireader/tts/DeepgramOnDeviceTest.kt
app/src/androidTest/java/com/geminireader/tts/ElevenOnDeviceTest.kt
app/src/androidTest/java/com/geminireader/tts/FishOnDeviceTest.kt
app/src/androidTest/java/com/geminireader/tts/GroqOnDeviceTest.kt
app/src/androidTest/java/com/geminireader/tts/InworldOnDeviceTest.kt
app/src/androidTest/java/com/geminireader/tts/KokoroBenchmark.kt
app/src/androidTest/java/com/geminireader/tts/KokoroOnDeviceTest.kt
app/src/androidTest/java/com/geminireader/tts/SpeechifyOnDeviceTest.kt
```

### Android configuration and resources

```text
app/src/debug/AndroidManifest.xml
app/src/debug/res/xml/debug_network_security.xml
app/src/main/AndroidManifest.xml
app/src/main/res/drawable/ic_reader.xml
app/src/main/res/values/strings.xml
app/src/main/res/xml/data_extraction_rules.xml
```

### Production Kotlin

```text
app/src/main/java/com/geminireader/MainActivity.kt
app/src/main/java/com/geminireader/ReaderApp.kt
app/src/main/java/com/geminireader/analysis/AnalysisModels.kt
app/src/main/java/com/geminireader/analysis/CartesiaVoices.kt
app/src/main/java/com/geminireader/analysis/Characters.kt
app/src/main/java/com/geminireader/analysis/DeepgramVoices.kt
app/src/main/java/com/geminireader/analysis/ElevenVoices.kt
app/src/main/java/com/geminireader/analysis/FishVoices.kt
app/src/main/java/com/geminireader/analysis/GroqAnalysis.kt
app/src/main/java/com/geminireader/analysis/GroqVoices.kt
app/src/main/java/com/geminireader/analysis/InworldVoices.kt
app/src/main/java/com/geminireader/analysis/KokoroVoices.kt
app/src/main/java/com/geminireader/analysis/PassageRewriter.kt
app/src/main/java/com/geminireader/analysis/SharedAnalysis.kt
app/src/main/java/com/geminireader/analysis/SpeechifyVoices.kt
app/src/main/java/com/geminireader/analysis/VoiceCatalog.kt
app/src/main/java/com/geminireader/data/Books.kt
app/src/main/java/com/geminireader/data/OfflineChapters.kt
app/src/main/java/com/geminireader/data/Pronunciations.kt
app/src/main/java/com/geminireader/data/RejectedPassages.kt
app/src/main/java/com/geminireader/data/Settings.kt
app/src/main/java/com/geminireader/data/Spending.kt
app/src/main/java/com/geminireader/importer/BookImporter.kt
app/src/main/java/com/geminireader/importer/TextImporters.kt
app/src/main/java/com/geminireader/playback/BufferPolicy.kt
app/src/main/java/com/geminireader/playback/PlaybackEngine.kt
app/src/main/java/com/geminireader/playback/PlaybackService.kt
app/src/main/java/com/geminireader/text/Segmenter.kt
app/src/main/java/com/geminireader/tts/AndroidTtsClient.kt
app/src/main/java/com/geminireader/tts/AudioCache.kt
app/src/main/java/com/geminireader/tts/CartesiaTtsClient.kt
app/src/main/java/com/geminireader/tts/DeepgramTtsClient.kt
app/src/main/java/com/geminireader/tts/ElevenTtsClient.kt
app/src/main/java/com/geminireader/tts/FishTtsClient.kt
app/src/main/java/com/geminireader/tts/GroqTtsClient.kt
app/src/main/java/com/geminireader/tts/InworldTtsClient.kt
app/src/main/java/com/geminireader/tts/KokoroDownloads.kt
app/src/main/java/com/geminireader/tts/KokoroModelFiles.kt
app/src/main/java/com/geminireader/tts/KokoroNativeLoader.kt
app/src/main/java/com/geminireader/tts/KokoroRecipe.kt
app/src/main/java/com/geminireader/tts/KokoroTtsClient.kt
app/src/main/java/com/geminireader/tts/SpeechifyTtsClient.kt
app/src/main/java/com/geminireader/tts/TtsClients.kt
app/src/main/java/com/geminireader/tts/VertexAuth.kt
app/src/main/java/com/geminireader/tts/Wav.kt
app/src/main/java/com/geminireader/tts/WavExport.kt
app/src/main/java/com/geminireader/ui/AndroidVoiceSettings.kt
app/src/main/java/com/geminireader/ui/CartesiaVoicePicker.kt
app/src/main/java/com/geminireader/ui/CharactersScreen.kt
app/src/main/java/com/geminireader/ui/DeepgramVoicePicker.kt
app/src/main/java/com/geminireader/ui/ElevenVoicePicker.kt
app/src/main/java/com/geminireader/ui/FishVoicePicker.kt
app/src/main/java/com/geminireader/ui/InworldVoicePicker.kt
app/src/main/java/com/geminireader/ui/KokoroDownloadSettings.kt
app/src/main/java/com/geminireader/ui/PronunciationScreen.kt
app/src/main/java/com/geminireader/ui/ReaderUi.kt
app/src/main/java/com/geminireader/ui/SeriesScreen.kt
app/src/main/java/com/geminireader/ui/SettingsScreen.kt
app/src/main/java/com/geminireader/ui/SpeechifyVoicePicker.kt
```

### JVM tests

```text
app/src/test/java/com/geminireader/analysis/AnalysisBatchTest.kt
app/src/test/java/com/geminireader/analysis/GroqAnalysisTest.kt
app/src/test/java/com/geminireader/analysis/PassageRewriterTest.kt
app/src/test/java/com/geminireader/analysis/VoiceCatalogTest.kt
app/src/test/java/com/geminireader/data/PassageEditingTest.kt
app/src/test/java/com/geminireader/data/SettingsMigrationTest.kt
app/src/test/java/com/geminireader/data/SpendingTest.kt
app/src/test/java/com/geminireader/importer/ImporterTest.kt
app/src/test/java/com/geminireader/text/SegmenterTest.kt
app/src/test/java/com/geminireader/tts/AndroidTtsTest.kt
app/src/test/java/com/geminireader/tts/AudioTest.kt
app/src/test/java/com/geminireader/tts/CartesiaTest.kt
app/src/test/java/com/geminireader/tts/DeepgramTest.kt
app/src/test/java/com/geminireader/tts/ElevenTest.kt
app/src/test/java/com/geminireader/tts/FishTest.kt
app/src/test/java/com/geminireader/tts/GenerationRetryTest.kt
app/src/test/java/com/geminireader/tts/GroqTest.kt
app/src/test/java/com/geminireader/tts/HttpApiTest.kt
app/src/test/java/com/geminireader/tts/InworldTest.kt
app/src/test/java/com/geminireader/tts/KokoroModelFilesTest.kt
app/src/test/java/com/geminireader/tts/KokoroRecipeTest.kt
app/src/test/java/com/geminireader/tts/KokoroSentencesTest.kt
app/src/test/java/com/geminireader/tts/KokoroTest.kt
app/src/test/java/com/geminireader/tts/MilestoneTest.kt
app/src/test/java/com/geminireader/tts/PerformanceFeaturesTest.kt
app/src/test/java/com/geminireader/tts/RejectedPassagesTest.kt
app/src/test/java/com/geminireader/tts/SpeechifyTest.kt
app/src/test/java/com/geminireader/tts/StreamedWavTest.kt
```

### Tools and tool configuration

```text
tools/create-fixtures.py
tools/dev.sh
tools/emulator.sh
tools/env.sh
tools/kokoro-benchmark/analyze.py
tools/kokoro-benchmark/prepare.py
tools/kokoro-benchmark/requirements.txt
tools/kokoro-recipe.py
tools/mock-server/mock_gemini.py
tools/mock-server/test_mock.py
tools/monitor-memory.py
tools/package-kokoro-downloads.py
tools/pair-groq.sh
tools/pair-token-broker.sh
tools/prepare-kokoro-android.py
tools/push-book.sh
tools/quantize-kokoro.py
tools/samples.sh
tools/screenshot.sh
tools/setup-android-sdk.sh
tools/token-broker/broker.py
tools/token-broker/gemini-reader-broker.service
tools/token-broker/test_broker.py
tools/ui-tap.py
tools/use-kokoro.sh
tools/use-mock.sh
tools/use-vertex-ssh.sh
```
