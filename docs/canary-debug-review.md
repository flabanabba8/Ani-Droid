# Canary code review and emulator debugging

Date: 2026-09-29. Branch: `pagecast`.

Reviewed the production architecture and the complete changes from `54b04ab` through `2935f00`, then debugged `com.geminireader.canary` on the `reader` Android 16/API 36 x86_64 emulator. The earlier whole-codebase simplification inventory is in [ponytail-review.md](ponytail-review.md). This follow-up adds execution evidence and regression fixes, rather than treating that earlier review as proof of runtime correctness.

## Findings and fixes

### Reading bookmarks were written but never restored

`ReaderScreen` saves scrolling to `reading-position.json`. Both library opening and launcher restoration read only `position.json`, which belongs to audio playback. A reader could reopen at an older listening position or the start of the book.

The new emulator regression reproduced this: opening a book with its reading bookmark at chapter 2 returned chapter 1 (`expected:<1> but was:<0>` for the zero-based chapter). It now passes for both library opening and launcher restoration.

`BookRepository.readingPosition()` selects the newer reading/audio bookmark and falls back to the audio bookmark if the reading file is absent or corrupt. Both opening paths use it. Audio resume still retains its segment, offset, cache identity and speed. Existing file modification times determine recency; copies should preserve those timestamps. This defect predates the Ponytail changes.

### Canary had no test variants enabled

`assembleCanary` worked, but `testCanaryUnitTest` was missing. AGP's default test configuration did not automatically cover the newly added build type. Canary now explicitly enables its `UnitTest` and `AndroidTest` components, preserving the debug test tasks.

Tests were built and installed for `com.geminireader.canary.test`, targeting `com.geminireader.canary`. This verifies Canary's package, resources, debug network configuration and actual Android transport.

### Emulator helper could target the connected phone

`tools/emulator.sh` used unqualified `adb wait-for-device`, `adb shell getprop` and `adb shell input`. With a wireless phone connected, startup could poll/unlock that device or fail as ambiguous when the emulator appeared.

The helper now uses an explicit emulator serial and port, defaulting to `emulator-5554`, and rejects phone serials. Two runnable Python regressions cover a phone and emulator being connected together, and rejection of a phone selected through `ANDROID_SERIAL`.

## Codebase and change analysis

| Area | Review conclusion |
| --- | --- |
| App and storage | `ReaderApp` coordinates lifecycle, settings, imports, analysis and playback. Books, cast, overrides, rejection records and prepared audio live together under each book directory; credentials remain in DataStore. Atomic writes and stale-edit checks protect saved content. Bookmark restoration was the confirmed functional defect. |
| HTTP refactor | Seven duplicated provider retry implementations now share a helper; Groq speech/chat produce eight entry points. Authentication, response parsing, error mapping and spending callbacks remain provider-specific. JVM coverage checks permanent failures, redirects, bounded retries, malformed JSON and cancellation. A new Android test exercises all eight routes with HTTP 429 followed by success, checking authentication on both attempts. |
| Google speech and authentication | Vertex/Gemini use their existing transport and missing-audio retry rules; the token broker remains separate and pinned. Emulator mocks exercised Vertex analysis/speech, Gemini's interactions fallback, explicit rejection and subsequent recovery. |
| Voices and UI | Six picker implementations share one Compose helper. Static comparison preserves labels, validators, automatic selection and explicit custom entry. Fixed-voice routing uses existing dispatch and settings properties. Cartesia's standard-to-custom transition and UUID entry were exercised on screen. |
| Import and text | HTML now parses once for title/body, and repeated regex construction was removed. Size limits and source-offset handling remain. JVM tests cover supported import formats and segmentation; a fresh two-chapter TXT fixture exercised the installed app's import path. |
| Playback and rendering | Queue duration summing uses a list view instead of copying the tail. Mutations and buffer reads remain on the player/main thread. Active sentence boundaries and rejection grouping are remembered by their inputs. Emulator playback exercised highlighting, automatic chapter advance and rejection scrolling. |
| Native speech | Runtime serialization, cancellation and model verification remain. Real Android TTS and Kokoro generation, cancellation and reuse passed. Kokoro reused an existing emulator model, fetched the required runtime and verified its hashes before loading. |
| Offline preparation | A complete chapter was prepared through the UI; its manifest reports seven segments and `complete=true`. Prepared audio remains distinct from disposable cache entries. |

The simplifications reduce duplication and allocations without introducing dependencies or changing serialized settings. No end-to-end speed, battery or memory improvement is claimed. `ReaderApp` still has broad responsibilities and some synchronous file operations; a larger restructuring was not needed to address the reproduced failures.

## Validation results

- **129 Canary JVM tests passed**, zero failures/errors/skips. The initial debug JVM suite also passed all 129 tests.
- **6 emulator instrumentation tests passed**, zero failures; **8 skipped** for absent paid-provider opt-ins/credentials or the optional published Cartesia audio fixture. The opt-in benchmark was excluded rather than counted as a substantive pass.
- Executed device tests: bookmark restoration; all eight HTTP retry/auth routes; Cartesia transport/cache; Android offline TTS/cancellation/cache; Kokoro sentence planning; real Kokoro generation/cancellation/reuse.
- **6 Python tests passed**: broker (2), mock (2), emulator targeting (2).
- Canary app and instrumentation APK builds passed. Canary lint: **zero errors, 13 warnings, 4 hints**; existing Gradle deprecation notices remain.
- Interactive emulator checks: launch/label, TXT import, character analysis, Vertex playback through both chapters, sentence highlighting, Gemini interactions fallback, custom voice entry, rejected-passage display/scroll, recovery clearing saved rejection records, and full-chapter offline preparation.
- Emulator crash buffer contained no Java fatal exception or native fatal signal after these checks.

Paid providers and live Vertex/Gemini credentials were not exercised. Mock success verifies local contracts and control flow, not current service availability or voice quality. Only Cartesia's picker received interactive testing; the other shared picker wrappers were reviewed and their routing is covered by JVM tests. Long-running playback, every device/API combination, and power/performance measurements remain outside this run.

## Reproduce

```bash
source tools/env.sh
ANDROID_SERIAL=emulator-5554 tools/emulator.sh --headless
./gradlew testCanaryUnitTest assembleCanary assembleCanaryAndroidTest lintCanary
adb -s emulator-5554 install -r app/build/outputs/apk/canary/app-canary.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/canary/app-canary-androidTest.apk
# Requires Kokoro installed and an Android offline English voice for the native tests.
adb -s emulator-5554 shell am instrument -w -r \
  -e notClass com.geminireader.tts.KokoroBenchmark \
  com.geminireader.canary.test/androidx.test.runner.AndroidJUnitRunner
python3 tools/test_emulator.py
python3 tools/token-broker/test_broker.py
python3 tools/mock-server/test_mock.py
```

Local evidence is in gitignored `tools/artifacts/`: `canary-final-instrumentation.log`, `canary-bookmark-before.log`, `canary-kokoro.log`, `canary-crashes.log`, `canary-prepared.json`, and screenshots `canary-start.png`, `canary-playback.png`, `canary-custom-voice.png`, `canary-rejected.png`, `canary-final.png`. Screenshots were inspected. Build output is in `/tmp/pagecast-canary-fixed-build.log`; JVM and lint reports are under `app/build/`.
