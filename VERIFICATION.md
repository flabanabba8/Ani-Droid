# Verification — 2026-09-15

Branch: `gardeneel`. Tests ran on this Ubuntu machine with user-space Temurin JDK 21, Gradle 9.7.1 and the official Android Emulator. No sudo, system-package installation, Waydroid, or git push was used. Initial checks used mocks; subsequent live Vertex checks used an authorized short-lived token from the BROKER_HOST.

## Build and automated tests

Timeout/chunking follow-up: **39 JVM tests pass**, with `testDebugUnitTest assembleDebug lintDebug`. New coverage includes a response delayed 11 seconds (beyond the old 10-second read timeout), bounded quote batches and ordering, canceled playback waiters sharing analysis, failure cooldown, checkpoint reuse after failure/process restart, analysis-model dropdown filtering and the dark default. Log: `tools/artifacts/chunking-build.log`.

Live follow-up used the user's standalone Chapter 1 EPUB (282 paragraphs, 34,837 characters). Nine batch checkpoints and a final analysis file were saved; the result contains 198 quote assignments. Real Vertex TTS reached `PLAYING`. A paragraph jump during analysis did not cancel the shared analysis; a subsequent jump from paragraph 5 to prepared paragraph 6 retained the existing media queue (active item advanced from 4 to 5 rather than starting a new queue). Inspected `analysis-dropdown-dark.png` showing the model dropdown and dark theme. This confirms functional completion, not an audit of all speaker assignments. The updated APK was installed on ANDROID_DEVICE with `adb install -r`.

`source tools/env.sh && ./gradlew testDebugUnitTest assembleDebug lintDebug` passes.

32 JVM tests pass: 8 importer tests, 7 segmentation/direction tests, 8 audio/API/cache tests, 7 voice-catalog tests, and 2 HTTP integration tests. Coverage includes EPUB spine ordering and intra-document TOC anchors; all text formats; PDF reflow; quote styles, apostrophes and unclosed quotes; unknown-speaker merging; gender-based performance; WAV validation/padding; PCM parsing; cache identity; Vertex project/region routing, explicit user role and bearer/project headers; retrying 429 and not retrying 401. Catalog tests exercise all 30 voices against male/female/unknown characters, stale gender settings, distinct manual overrides, schema choices, cache invalidation and old saved records.

`python3 tools/mock-server/test_mock.py` passes. All shell scripts pass `bash -n`; Python helpers compile. `git diff --check` passes. Android lint has no errors; remaining warnings include the deliberate API 36 target and third-party PDFBox/Bouncy Castle code. The app's HTTP clients use OkHttp's default certificate verification.

The handoff's dependency versions require compile SDK 37 or later. The app therefore compiles with SDK 37.2 while keeping target 36, min 26 and emulator API 36. The Gradle distribution was downloaded and its SHA-256 verified against the handoff; the wrapper pins that checksum.

## Emulator checks

- `reader` AVD, API 36 Google APIs x86_64, KVM, `-gpu host -no-window -no-audio`. Cold boot through `tools/emulator.sh --headless` completed and adb remained connected after the script returned.
- Installed the debug APK and imported Gutenberg Alice EPUB/TXT and Pride and Prejudice EPUB using `tools/push-book.sh`. The updated Pride and Prejudice import has 71 TOC entries, including front matter and chapters.
- Imported generated HTML, Markdown, FB2, DOCX and PDF fixtures. All seven supported formats have saved book records. Inspected PDF text and the EPUB/library screenshots.
- Played local mock audio through Cloud TTS, Gemini generateContent, and Vertex AI endpoints. MediaSession reported `PLAYING` for each.
- Forced Gemini generateContent to return 404 on the fallback mock. The app sent `/v1beta/interactions`, decoded its audio and played it.
- Vertex mock logged both analysis and TTS paths under `/v1beta1/projects/mock-project/locations/us-central1/publishers/google/models/...:generateContent`, with `bearer_auth: true` and `billing_project: mock-project`.
- Inspected a screenshot showing just Alice's quoted passage highlighted and `Alice` as the current speaker. Mock logs show voice `Charon` plus the character-specific direction: “As a male narrator portraying this character, use a lighter, higher voice with gentle resonance.”
- Inspected the characters screen, Vertex settings and fetched-models dialog. Narrator preview works through the mock; settings and books survive app reinstall/restart and emulator cold boot.
- Replayed the short HTML fixture: **zero additional mock requests**. Reading position was saved to `position.json`.
- While the app was backgrounded, Android reported `isForeground=true`, notification ID 1001 and media-playback service type. System media pause/play commands changed playback state. Inspected the notification screenshot with the book title, Alice attribution and controls.
- No AndroidRuntime crash was recorded in the final checks.

The headless emulator mutes host audio. These checks verify generated PCM/WAV, Android decoding, advancing playback, highlighting and controls; they do not claim a listening evaluation.

## Evidence on this machine

### Live Vertex follow-up

Voice-catalog update: researched all 30 voice gender labels and traits from official Google sources (see `docs/voices.md`). `testDebugUnitTest assembleDebug lintDebug` passed; mock tests also passed. Real Vertex analysis on the short test story returned Autonoe (female, bright) for Alice and Algieba (male, smooth) for Captain Reed, with all four quote assignments. Performance-mode playback retained Charon and Android reported `PLAYING` with Alice metadata. Inspected `voice-catalog-live.png` for highlighting and `voice-catalog-characters.png` for recommendations versus the active narrator. The catalog is research-based: individual listening tests of all 30 voices were not performed. Build log: `voice-catalog-build.log`.

- Connected over SSH to `USER@BROKER_HOST` (BROKER_HOST). Used its user Application Default Credentials, not its unrelated active CLI service account. Long-lived refresh credentials remain on the BROKER_HOST; only a short-lived access token entered the emulator's private settings.
- Project `YOUR_PROJECT_ID`, region `us-central1`: real `gemini-2.5-flash` text request returned HTTP 200; real `gemini-3.1-flash-tts-preview` with Charon returned HTTP 200 and 178,560 bytes of 24 kHz mono PCM for a short connection test.
- The first real TTS request rejected the absent content role. Added explicit `role: user` to speech and analysis requests and a regression test.
- Real analysis initially produced no usable quote assignments. Strengthened instructions with an explicit list of required quote IDs and reject incomplete assignments. The revised live analysis correctly assigned `2.0` and `2.1` to Alice and `3.0` and `3.1` to Captain Reed in a new four-paragraph test story.
- Real speech played through Media3. Android reported `PLAYING` with Captain Reed metadata; inspected `vertex-live-reader.png` showing his quote highlighted and speaker label. Inspected `vertex-live-alice.png` showing Alice's quote highlighted with her label. The app retained Charon and used its character direction builder. This is a small functional sample, not a general accuracy or listening evaluation.
- Fixed cold-start debug settings ordering and propagation of failed audio-prefetch coroutines, exposed during this run. `tools/use-vertex-ssh.sh` renews the debug app's token without putting it in adb intent extras or terminal output.
- Rechecked an unavailable mock endpoint: the app stayed running and displayed a connection/analysis error. Restored real Vertex credentials afterward. Removed the host's temporary token file; the original refresh credentials were not copied or changed.
- `testDebugUnitTest installDebug lintDebug` passed; build output is in `vertex-live-build.log`.

Generated files are intentionally gitignored under `tools/artifacts/`:

- `build-final.log` and the HTML/XML reports under `app/build/`.
- `library-imports.png`, `reader-import-alice.png`, `pdf-reader.png`.
- `vertex-character-highlight.png`, `characters-screen.png`, `settings-vertex.png`, `vertex-models.png`.
- `background-notification.png`, `playback-cloud.png`, `playback-interactions.png`.
- `mock.log`, `mock-vertex.log`, `mock-fallback.log`, `emulator.log`.

Screenshots listed above were opened and inspected, not merely captured. Early library screenshots precede the improved EPUB TOC parser; their Pride and Prejudice entry shows the original eight spine documents.

## Not verified

Live Vertex authentication and the two default models are verified for the project/region above, but billing-credit application, sustained quota, broad LLM attribution accuracy, and subjective character voice quality remain unverified. Optional Cloud TTS API-key support remains uncertain; the optional Cloud engine provides OAuth token/project settings. Gemini's Interactions fallback is contract-tested against the mock, not a live service. Vertex mode stays on Vertex.

Physical-phone wireless adb pairing, installation and the voice-catalog update succeeded on ANDROID_DEVICE (`ANDROID_MODEL`, `LOCAL_DEVICE_ADDRESS`). Its existing books/settings were preserved through `adb install -r`; the app was launched afterward. Phone listening quality has not been independently evaluated. OAuth refresh on Android is not implemented; replace expired access tokens in Settings. Highlight timing is estimated per sentence. Scanned PDF OCR, DRM and MOBI/AZW3 are outside scope.

See [README.md](README.md) for exact emulator, mock, build/install, Vertex configuration and wireless-adb commands.
