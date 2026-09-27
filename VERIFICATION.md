# Verification — 2026-09-15

## Latest — on-device Kokoro, September 15, 2026

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: successful, **83 JVM tests**, zero failures/errors.
- `KokoroOnDeviceTest.realLocalGenerationCancellationAndReuse`: passed on API 36 x86_64 emulator (12.13 seconds total) and Samsung ANDROID_MODEL (25.44 seconds total). Generates real local PCM, checks sample rate/nonzero output, cancels generation and generates again with a different voice.
- Phone first generation: 3.528 seconds of audio in 14.662 seconds including model preparation/loading. No sustained speed, battery or thermal claim follows from this measurement.
- Fixed JNI callback compatibility using a kept concrete callback class exposing `invoke(float[]): Integer`; reflection regression test verifies the signature. Verified native dependencies before excluding unused C/C++ API wrapper libraries.
- Updated debug APK installed over wireless ADB with `install -r`; Kokoro/Narrator enabled and configuration message observed. Test package removed. No Kokoro server or ADB reverse tunnel required.
- Includes passage editing/recovery and per-book spending changes. The unit count covers the full current suite, not only Kokoro.

**Release limits:** no F-Droid build, release-signature migration, native source rebuild, complete license audit or submission verified. See [release plan](wiki/fdroid.md).

## Earlier verification history


Earlier milestone verification: [offline chapters, manual pronunciation, series voice profiles and no-audio recovery](wiki/next-milestone.md). This includes 52 passing JVM tests, mock no-audio/retry UI testing, prepared playback with the mock server stopped and disposable cache absent, and a successful live retry of the reported jacket paragraph. User reports physical headphone disconnect pauses and manual Play resumes successfully.

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

Physical-phone wireless adb pairing, installation and the voice-catalog update succeeded on ANDROID_DEVICE (`ANDROID_MODEL`, `LOCAL_DEVICE_ADDRESS`). Its existing books/settings were preserved through `adb install -r`; the app was launched afterward. Phone listening quality has not been independently evaluated. Automatic LAN-broker renewal was subsequently implemented and tested below. Highlight timing is estimated per sentence. Scanned PDF OCR, DRM and MOBI/AZW3 are outside scope.

See [README.md](README.md) for exact emulator, mock, build/install, Vertex configuration and wireless-adb commands.

## Post-MVP milestone — 2026-09-15

User-confirmed scope: automatic Vertex renewal via local Wi-Fi/BROKER_HOST, duration-based buffering, reliable resume, and audio export. The full backlog and future-agent runbooks are in [wiki/README.md](wiki/README.md).

- Final `./gradlew testDebugUnitTest assembleDebug lintDebug`: successful, 46 JVM tests, zero failures. `python3 -m unittest discover -s tools/token-broker -p 'test_*.py'`: two passing broker tests. Build log: `tools/artifacts/milestone-build.log`.
- Deployed authenticated pinned-HTTPS broker as a BROKER_HOST user service, enabled with lingering. Unauthenticated POST returns 401. Live forced Google ADC refresh returns HTTP 200, correct project, ~3,598-second lifetime; credentials/tokens suppressed. Android expiry/concurrency/401 behavior is covered with a simulated clock, not an hour-long wait.
- Emulator paired over SSH/adb; fresh original-text `broker-smoke` fixture generated and played on real Vertex with persisted manual token cleared. Media3 completed 5.9 seconds of speech. Broker logs confirmed issuance. Final APK also installed on ANDROID_DEVICE and broker pairing import consumed successfully; existing books preserved.
- Longer Alice mock playback displayed **2m 10s ready**, with actual duration accounting and bounded generation. Mock prompts contained `Perform Alice (female)` and Charon-relative higher/lighter register guidance. No whole book was synthesized. Log: `tools/artifacts/milestone-mock.log`.
- Paused original-text fixture at **7,835 ms**, force-stopped/reopened, requested Play and paused during loading: Media3 restored exactly **7,835 ms** against the same cached WAV. Audio bookmark did not get reset by loading or scrolling.
- Emulator reboot preserved Alice chapter 1, paragraph 3, **18,750 ms** bookmark. Reopened last book without autoplay; subsequent Play resumed cached audio. Mock log count remained 39 lines across reboot/resume until additional uncached buffering became necessary.
- Android document picker exported `reader-chapter-1.wav` to Downloads. Pulled result: 2,428,680 bytes; `ffprobe` reports PCM signed 16-bit little-endian, mono, 24,000 Hz, **50.596583 seconds**. Export does not synthesize speech; it includes the generated queue, not necessarily the whole chapter. Staging copy streams without loading an entire chapter into memory.
- Screenshots captured **and opened**: `milestone-buffer.png`, `two-minute-buffer.png`, `export-confirm.png`, `export-picker.png`, `export-success.png`, `reboot-resume.png` in `tools/artifacts/`.

Remaining limits: no physical headphone reconnect/phone reboot or overnight endurance test; no away-from-home broker connectivity; credit eligibility still unverified. Sudden death can lose up to the two-second bookmark interval. Uncached/regenerated audio restarts the saved segment because new synthesis timing may differ. WAV export can be a partial chapter and needs staging disk space. Offline chapter preparation and series casts remain backlog.

## Subsequent user report and recovery — September 15, 2026

User reports crunchy, lo-fi Kokoro audio with continuous ringing. Nonzero PCM/native lifecycle tests did not validate perceived quality. Investigating the int8 model before calling the implementation release-ready. An [upstream report](https://github.com/k2-fsa/sherpa-onnx/issues/3754) describes int8 ringing and ARM corruption that disappeared with fp32; this is a lead, not yet a confirmed local diagnosis.

The user then needed cloud listening restored. Saved Vertex project/broker/pin/secret were present, while the selected engine was the separate `gemini` API-key option. BROKER_HOST broker reported active. Reapplied its pairing through the existing helper and verified saved engine `vertex` with automatic-renewal configuration present. No credentials were printed. Preserve this selection during further Kokoro work. User resumed-playback success has not yet been verified.

## Full-precision quality fix — development build

Switched bundled speech from int8 to fp32 (`kokoro-1.0-fp32-en-sherpa-1.13.8-v2`). The model archive SHA-256 is `c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298`, verified against the GitHub release asset digest. Audio cache and prepared-chapter identities change with the model, so previous int8 audio will not be reused as fp32 speech. Existing Google caches/settings remain compatible.

Compared the same text/voice through the app's native engine on the emulator, before playback. Welch spectra used 100 ms Hann windows, 50% overlap and adjacent 40–190 Hz bands as a reference. Int8 tone prominence was 14.63 dB at 4.8 kHz and 19.36 dB at 9.6 kHz; fp32 measured 1.51 and 1.91 dB. Absolute tone power fell by 13.53 and 17.99 dB. Neither sample clipped. This confirms a model-output ringing artifact in this fixture rather than a WAV-header/sample-rate mismatch. A native regression assertion now limits both tone prominences to 8 dB for this fixture.

The fp32 model passed emulator generation/cancellation/reuse. These measurements do not establish subjective quality, sustained performance or memory behavior on the phone. The larger build has about 366 MiB of model assets and a 560 MiB universal debug APK, with another model copy in private storage after first use. Phone still has the previous int8 APK and is set to Vertex: the candidate was not installed over ongoing listening. Test the fp32 candidate on the phone when that will not interrupt the user, preserving Vertex and credentials.

Engine labels now explicitly distinguish Google Vertex AI (OAuth / automatic renewal) from Google Gemini API (API key). Recovery independently verified the broker returned HTTP 200 and a valid short-lived token; credentials were not displayed. Audio-quality acceptance remains pending user listening feedback.

Final fp32 regression run: 83 JVM tests and lint passed; emulator instrumentation passed in 6.252 seconds. Native tone-prominence assertions measured 1.61 dB at 4.8 kHz and 1.71 dB at 9.6 kHz (limit 8 dB). These are narrow-tone regression checks on one fixture, not a general speech-quality score.

## Phone installation completed — September 15, 2026, 10:59

At the user's request, installed the full-precision APK on Samsung ANDROID_MODEL over wireless ADB (`install -r`: Success) and launched PageCast. Saved engine remained Vertex and broker configuration was retained. Earlier notes that the candidate was not installed are now superseded. The user will test switching between Kokoro and Vertex; phone fp32 RAM/subjective quality remain unverified. `tools/monitor-memory.py` now provides live sampled PSS/RSS in decimal MB and session peak PSS. Groq TTS is the next requested provider after this test.

## Selective8 bundled build — September 15, 2026

Rebuilt with the exact selective8 model from the comparison: SHA-256 `84514ba144a99ac1e00d2ab60ebf9207ba7794b3b86c96ecb2ed9fb983f54f51`. Isolated pinned conversion reproduced it. 83 JVM tests, assembleDebug/assembleDebugAndroidTest and lint passed. Emulator native test passed in 8.018 s, including generation, cancellation/reuse and narrow-tone checks (2.25 dB at 4.8 kHz, 2.06 dB at 9.6 kHz). This validates the new bundle/cache version, not phone speed or perceived quality.

Selective8 APK installed on ANDROID_DEVICE at 11:20:57 with `install -r` (Success); app launched, engine verified Vertex and broker configuration retained. User will select Kokoro for testing. A new bounded resource log is running at `tools/artifacts/poo-selective8-resources.jsonl`. No phone speed/quality result is claimed yet.

## Kokoro sentence queue

86 JVM tests pass. Added coverage for separate short sentences, long sentences/word boundaries, UTF-16 surrogate pairs, quote IDs/source offsets, scene breaks and paragraph pause placement. Emulator `KokoroOnDeviceTest` passed both tests in 6.392 s: actual chapter plan emits sentence-sized items with correct pauses, and native speech generation/cancellation/reuse passes ringing checks. Phone latency improvement still needs user confirmation.

Verification: 90 JVM tests, debug app/test builds and Android lint passed. Live English Groq synthesis passed on the API 36 emulator. Settings UI verified English model and Troy voice. Installed the English-only APK on ANDROID_DEVICE (ANDROID_MODEL) over wireless ADB with install -r; private key import acknowledged after saving, selected engine preserved. Phone listening quality remains for user testing.

Emulator UI Save & test speech also passed: MediaSession reported PLAYING and the preview recorded one Groq response costing $0.001078 (49 characters).

## Groq character analysis — September 15, 2026

Implemented Groq GPT-OSS 20B (default) / 120B text analysis, strict JSON schema, Groq voice recommendations, preserved cast overrides and saved batches, per-book token spending and selected-provider passage rewrites. Live emulator fixture passed with Google credentials blank: two speakers attributed, valid voices selected, playback plan constructed and durable analysis reused with Groq key removed. See the project wiki/groq.md for scope and limits.

Validation: 93 JVM tests and Android lint passed. Final APK passed live Groq character-analysis instrumentation in 1.996 seconds for the short fixture. Emulator Settings confirmed text provider Groq, Distinct mode and GPT-OSS 20B. This is functional evidence, not a long-chapter benchmark.

Installed the character-analysis update on ANDROID_DEVICE (ANDROID_MODEL) with wireless adb install -r. Enabled Groq text analysis. Post-install settings showed Vertex/Narrator selected, so those selections were preserved; choose Groq speech and Distinct mode to use Groq character voices. Groq key remained present.

ElevenLabs verification: 96 JVM tests and lint passed. Real custom character-voice synthesis passed in the Android emulator (Laura, outside the dropdown); emulator UI selected Custom, entered the ID and persisted it correctly. Installed on ANDROID_DEVICE with install -r; key import acknowledged and key presence verified without displaying it. Existing Vertex speech selection preserved. Host temporary testing key file removed after provisioning.

Verification: 99 JVM tests and Android lint passed. API 36 emulator mock test verified current request transport, custom character voice, WAV decoding and cached replay with the server stopped. Emulator Settings selected Cartesia, entered a custom UUID and saved it successfully. Current-version live voice-list GET returned HTTP 200; no live TTS POST was made.

Installed on ANDROID_DEVICE (ANDROID_MODEL) over wireless ADB with install -r. Cartesia key import acknowledged and private settings verified key presence; existing speech selection preserved. Host temporary key file removed. Real Cartesia sound quality and generation latency remain for user testing within the free allowance.

## Streamed WAV fix — September 15, 2026

User reported "Truncated WAV chunk" on the phone. The initial mock used a finalized WAV header and missed streaming markers. Reproduced the exact strict-decoder error with Cartesia's published training_en.wav sample, without making any generation request.

The published file is 599838 bytes, with PCM mono 16-bit/44100 Hz, LIST metadata at offset 36, and a data chunk at offset 70. RIFF and data sizes are 0xffffffff (unknown), not actual lengths. SHA-256: 5e91780d3cbbbb691eede2ec1b07e05f497fe0a8d5b4f1f51effd737ed0dc6cf.

Moved Groq's existing sentinel-only normalization to Wav.decodeStreamed and use it for both cloud providers. Standard cache-file decode stays strict. Regression tests cover streamed metadata, preserved samples, ordinary truncated chunks, odd PCM data and ordinary WAV compatibility. Cartesia HTTP mock now sends streamed lengths.

[Published provider sample](https://github.com/cartesia-ai/cartesia-enterprise-usecases-demos/blob/main/examples/02_multilingual_training_practice/training_en.wav) is downloaded only to ignored tools/artifacts/cartesia-reference.wav, not bundled with the app. No Cartesia generation credits consumed.

Fix validation: 102 JVM tests, debug app/test builds and Android lint passed. Two emulator instrumentation tests passed: streamed HTTP-to-cache round trip and decoding the published Cartesia WAV that reproduced the reported error.

Installed the streamed-WAV fix on ANDROID_DEVICE with data preserved. Both Cartesia instrumentation tests also passed on the phone (published provider sample plus streamed HTTP/cache fixture, 0.27 seconds). Removed the test-only package afterward and reopened PageCast. No live Cartesia generation request was made.

## Deepgram integration — September 15, 2026

English Flux TTS is implemented with six presets and custom voice models for narrator, character and series selections. Groq text analysis is the default, with Vertex/Gemini alternatives. Per-book speech estimates use $45/million characters before account credits. Provider credentials are separate and adding the key preserves the selected engine. See [Deepgram](wiki/deepgram.md).

Validation: 106 JVM tests, debug APK/test APK builds and lint pass. Live emulator synthesis with the non-preset Haley voice passes. The first live response exposed Deepgram's 0x7fff0000 WAV size placeholders; the final decoder normalizes only the exact paired header and retains rejection of ordinary truncated audio. Regression coverage includes the header, Token auth, query/body contract, cache reuse, custom voices and Unicode per-book pricing. No new proprietary SDK is used.

Final Deepgram build installed on ANDROID_DEVICE over wireless ADB. Live on-phone synthesis passed (2.15-second test); key presence verified and prior engine preserved. Test-only package removed. Emulator settings UI confirms Deepgram selection, Hannah preset and Groq analysis. Temporary host key deleted.

## Inworld — September 15, 2026

Added TTS-2 with balanced delivery, 48 kHz mono WAV, six presets plus custom voice IDs, narrator/distinct modes, Groq/Google character analysis, provider-specific cache/offline signatures and per-book spending estimates. The app uses synchronous JSON/base64 synthesis in the existing segment buffer. Reported character usage is priced at $25/million before credits; Unicode counts are the fallback. Credentials remain separate and provisioning preserves the engine.

Validation: 110 JVM tests and lint pass; authenticated voice catalog and a short live Sarah request succeeded. No new SDK dependencies. See [Inworld runbook](wiki/inworld.md).

Inworld final build installed on ANDROID_DEVICE; live Ashley synthesis passed (1.22-second smoke test). Key stored privately, existing engine preserved, test package removed and temporary host key deleted.

## Speechify — September 15, 2026

Simba 3.2 integrated with six catalog-verified presets, other voice IDs, Groq character analysis, per-book spending estimates and provider-specific cache/offline signatures. Speech uses JSON/base64 WAV from `/v1/audio/speech` in the existing segment buffer. Reported billable characters are valued at the $10/million Starter reference rate before included usage/discounts; missing counts remain unpriced.

114 JVM tests, APK builds and lint pass. Voice catalog authentication and a six-character live Geffen request succeeded. Regression tests cover Bearer auth, voice/model/input/format fields, audio decoding, truncation rejection, custom voice selection, cache reuse and provider-count accounting. No new SDK dependencies. See [Speechify](wiki/speechify.md).

Speechify final build installed on ANDROID_DEVICE. Live Alicia speech test passed (1.97-second smoke test). Key presence verified and prior engine preserved. Test package removed and temporary host credential deleted.

## Fish Audio — September 15, 2026

S2.1 Pro Free integrated with six official English presets, custom voice IDs, Groq character analysis and per-book free-request accounting. The model header is fixed to `s2.1-pro-free`; there is no paid-model selector or client fallback. Existing cache, offline signatures, cast overrides and pronunciation handling apply. Free access currently ends November 30, 2026; later usage is unpriced until terms are rechecked.

118 JVM tests, APK builds and lint pass. Read-only official catalog lookup and a short live free-model Sarah request succeeded. Fish's exact WAV size placeholders are normalized before shared decoding, with regression coverage for malformed chunks, auth/model header, request fields, custom voices and cache reuse. See [Fish Audio](wiki/fish.md).

Fish Audio final APK installed on ANDROID_DEVICE. Live free-model Sophia synthesis passed (2.15-second smoke test). UI confirmed presets/custom ID support. Key saved privately and existing engine preserved; test package removed and temporary host key deleted.

## Installed Android TTS — September 15, 2026

Added installed-engine discovery and English narrator voice selection with offline-only filtering, refresh and Android voice-settings shortcut. Narration requires no API key and uses no character analysis. Optional passage rewrites retain Groq. Native synthesizeToFile output feeds the existing cache/playback/offline preparation pipeline; engine/voice/policy identify cached audio. One engine operation runs at a time. Cancellation stops synthesis and removes temporary audio; switching away releases the engine.

119 JVM tests, builds and lint passed. Native emulator test passed offline synthesis, cached replay without generation, cancellation, recovery and temporary-file cleanup. See [Android TTS](wiki/android-tts.md).
Removed experimental local-engine integrations and bundles. 121 JVM tests and lint passed, including migration from unsupported engine selections to offline Kokoro Narrator while preserving credentials and supported selections. A clean APK repack eliminated stale incremental ZIP space. APK measured 327,128,099 bytes; no removed model assets present. Kokoro assets plus four-ABI native runtime account for approximately 300.4 MB. Build daemons stopped after packaging.

Removal APK installed on ANDROID_DEVICE over wireless ADB; extracted retired model folders deleted from app-private storage. PageCast reopened. Fresh packaging is now enforced in Gradle for every debug/release/test APK build, with old outputs and incremental package state deleted before packaging. Two consecutive assemble runs verified that packaging executes again even with unchanged sources.

Optional Kokoro build: 122 JVM tests and lint passed. Recipe test reconstructs the 176.4 MB selective-8 model from the pinned upstream FP32 model and confirms the exact benchmark SHA-256. APK is 28.0 MB and contains no Kokoro model or sherpa/ONNX native libraries. Public upstream runtime byte-range support verified; ARM64 compressed runtime transfer totals 9,926,127 bytes. No custom hosting endpoint is used.

Phone validation: installed final 27,989,384-byte APK on ANDROID_DEVICE. APK recipe retains gzip magic under a `.bin` filename (Android stripped/decompressed the earlier `.gz` asset). Initial cold test successfully downloaded/verified the public runtime and model archive and extracted the source model, then exposed that asset lookup bug. After the fix, the final test reused a hash-verified legacy FP32 installation and passed both instrumentation tests in 37.963 seconds, including selective-8 reconstruction, dynamic native loading, synthesis, cancellation/recovery and the known-tone regression checks. Phone model SHA-256 is `84514ba144a99ac1e00d2ab60ebf9207ba7794b3b86c96ecb2ed9fb983f54f51`. First 3.527 seconds of speech took 12.737 seconds including initialization; 4.8/9.6 kHz prominence was 1.82/2.10 dB. The entire cold path was verified in stages, rather than a second full archive download after the asset fix.
