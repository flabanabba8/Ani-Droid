# Verification — 2026-09-15

Branch: `gardeneel`. Tests ran on this Ubuntu machine with user-space Temurin JDK 21, Gradle 9.7.1 and the official Android Emulator. No sudo, system-package installation, Waydroid, real API credentials, or git push was used.

## Build and automated tests

`source tools/env.sh && ./gradlew testDebugUnitTest assembleDebug lintDebug` passes.

24 JVM tests pass: 8 importer tests, 7 segmentation/direction tests, 7 audio/API/cache tests, and 2 HTTP integration tests. Coverage includes EPUB spine ordering and intra-document TOC anchors; all text formats; PDF reflow; quote styles, apostrophes and unclosed quotes; unknown-speaker merging; gender-based performance; WAV validation/padding; PCM parsing; cache identity; Vertex project/region routing and bearer/project headers; retrying 429 and not retrying 401.

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

Generated files are intentionally gitignored under `tools/artifacts/`:

- `build-final.log` and the HTML/XML reports under `app/build/`.
- `library-imports.png`, `reader-import-alice.png`, `pdf-reader.png`.
- `vertex-character-highlight.png`, `characters-screen.png`, `settings-vertex.png`, `vertex-models.png`.
- `background-notification.png`, `playback-cloud.png`, `playback-interactions.png`.
- `mock.log`, `mock-vertex.log`, `mock-fallback.log`, `emulator.log`.

Screenshots listed above were opened and inspected, not merely captured. Early library screenshots precede the improved EPUB TOC parser; their Pride and Prejudice entry shows the original eight spine documents.

## Not verified

Live Vertex/Gemini/Cloud authentication, IAM and quota, real model availability, application of Google Cloud credits, real LLM attribution accuracy, and Gemini character voice quality require your credentials. Cloud TTS API-key support remains uncertain; the optional Cloud engine provides OAuth token/project settings. Gemini's Interactions fallback is contract-tested against the mock, not a live service. Vertex mode stays on Vertex.

Physical-phone wireless adb installation is documented but no phone was available. OAuth refresh on Android is not implemented; replace expired access tokens in Settings. Highlight timing is estimated per sentence. Scanned PDF OCR, DRM and MOBI/AZW3 are outside scope.

See [README.md](README.md) for exact emulator, mock, build/install, Vertex configuration and wireless-adb commands.
