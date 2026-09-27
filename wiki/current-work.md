---
machine: build-host / Android
subsystem: implementation
last-verified: 2026-09-15
status: verified development build; F-Droid preparation planned
---
# Current work

## Current implementation

PageCast now includes Cartesia and ElevenLabs speech, Groq speech and character analysis, on-device Kokoro, passage editing, explicit rejected-passage recovery, and per-book spending estimates. The user intends to release the app on F-Droid. See the [release plan](fdroid.md) before choosing new dependencies or changing the build.

- **Groq:** user confirms fast, acceptable English speech. GPT-OSS 20B/120B now handles character analysis and requested rewrites using the Groq key, with distinct voice recommendations and per-book token spending. See [Groq](groq.md).
- **Local speech:** bundled Kokoro 1.0 selective 8-bit through sherpa-onnx 1.13.8, 28 English voices, Narrator/Distinct modes. Narrator speech runs offline. Distinct character analysis and requested rewrites use Google. The self-hosted Kokoro approach was replaced at the user's request; do not restore a server requirement. See [Kokoro](kokoro.md).
- **Passage editing/recovery:** manual editing; explicit Retry generation without rewriting; explicit Suggest milder wording only for recorded rejections, with review before Save. Scene-break paragraphs such as `***` are skipped. See [passages](passages.md).
- **Spending:** Settings totals, current month, provider/model and book breakdown; locally estimated USD, with unpriced responses identified. Local Kokoro speech has no API charge. See [spending](spending.md).
- **Previously completed:** token renewal through the optional BROKER_HOST broker, duration-based buffering, separate listening/scroll bookmarks, queue WAV export, durable offline chapter preparation/full prepared-chapter export, pronunciation rules, and manually linked series voices. See [milestone details](next-milestone.md).

## Latest evidence — September 15, 2026

- 83 JVM tests, debug app/test APK builds, and Android lint passed.
- Real native generation, cancellation and reuse with a different voice passed on API 36 x86_64 emulator and Samsung ANDROID_MODEL over wireless ADB.
- Phone test produced 3.528 seconds of audio in 14.662 seconds including first-use preparation/loading. This is a cold-start observation, not sustained real-time performance evidence.
- Initial int8 APK installed with data preserved and Kokoro/Narrator enabled; subsequently restored to Vertex after the quality report. The fp32 candidate is built but not yet installed on the phone. Test-only package removed afterward.
- User reports the app is working well. Earlier headphone disconnect/reconnect behavior was user-verified.

## Remaining work

F-Droid release preparation is **planned, not completed**: choose the app license, publish source, make native dependencies/source provenance acceptable to F-Droid, resolve model redistribution/build review, finalize package/version/signing and release metadata, and test an actual release build. Current package is `com.geminireader`, version `0.1.0` / code `1`; no Git remote is configured here.

Other limits: no long-run thermal/battery benchmark; preparation is not a persistent background job; strict spoiler-safe progressive analysis, automatic cast continuity and sleep timer remain unimplemented. Whole-chapter analysis can reveal later information. Google spending estimates are not billing records. Fresh-install defaults still select Vertex/Performance; the installed test phone was explicitly switched to Kokoro/Narrator.

See [verification history](../VERIFICATION.md). Historical test counts below that document's latest section belong to earlier builds.

## Subsequent user report and recovery — September 15, 2026

User reports crunchy, lo-fi Kokoro audio with continuous ringing. Nonzero PCM/native lifecycle tests did not validate perceived quality. Investigating the int8 model before calling the implementation release-ready. An [upstream report](https://github.com/k2-fsa/sherpa-onnx/issues/3754) describes int8 ringing and ARM corruption that disappeared with fp32; this is a lead, not yet a confirmed local diagnosis.

The user then needed cloud listening restored. Saved Vertex project/broker/pin/secret were present, while the selected engine was the separate `gemini` API-key option. BROKER_HOST broker reported active. Reapplied its pairing through the existing helper and verified saved engine `vertex` with automatic-renewal configuration present. No credentials were printed. Preserve this selection during further Kokoro work. User resumed-playback success has not yet been verified.

## Full-precision quality fix — development build

Switched bundled speech from int8 to fp32 (`kokoro-1.0-fp32-en-sherpa-1.13.8-v2`). The model archive SHA-256 is `c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298`, verified against the GitHub release asset digest. Audio cache and prepared-chapter identities change with the model, so previous int8 audio will not be reused as fp32 speech. Existing Google caches/settings remain compatible.

Compared the same text/voice through the app's native engine on the emulator, before playback. Welch spectra used 100 ms Hann windows, 50% overlap and adjacent 40–190 Hz bands as a reference. Int8 tone prominence was 14.63 dB at 4.8 kHz and 19.36 dB at 9.6 kHz; fp32 measured 1.51 and 1.91 dB. Absolute tone power fell by 13.53 and 17.99 dB. Neither sample clipped. This confirms a model-output ringing artifact in this fixture rather than a WAV-header/sample-rate mismatch. A native regression assertion now limits both tone prominences to 8 dB for this fixture.

The fp32 model passed emulator generation/cancellation/reuse. These measurements do not establish subjective quality, sustained performance or memory behavior on the phone. The larger build has about 366 MiB of model assets and a 560 MiB universal debug APK, with another model copy in private storage after first use. Phone still has the previous int8 APK and is set to Vertex: the candidate was not installed over ongoing listening. Test the fp32 candidate on the phone when that will not interrupt the user, preserving Vertex and credentials.

Engine labels now explicitly distinguish Google Vertex AI (OAuth / automatic renewal) from Google Gemini API (API key). Recovery independently verified the broker returned HTTP 200 and a valid short-lived token; credentials were not displayed. Audio-quality acceptance remains pending user listening feedback.

## Phone installation completed — September 15, 2026, 10:59

At the user's request, installed the full-precision APK on Samsung ANDROID_MODEL over wireless ADB (`install -r`: Success) and launched PageCast. Saved engine remained Vertex and broker configuration was retained. Earlier notes that the candidate was not installed are now superseded. The user will test switching between Kokoro and Vertex; phone fp32 RAM/subjective quality remain unverified. `tools/monitor-memory.py` now provides live sampled PSS/RSS in decimal MB and session peak PSS. Groq TTS is the next requested provider after this test.

## Current priority update

Completed [FP16/selective8 emulator benchmarks](kokoro-benchmarks.md) at user request. Neither improves measured speed; selective8 reduces peak RAM about 144 MB and passes the known-tone checks. Phone candidate testing remains pending. Groq implementation is paused with partial, untested provider plumbing in the working tree. Its key was located on the BROKER_HOST and authenticated; a short real speech response succeeded. No Groq key has been installed into the app yet. Do not describe Groq as a completed provider.

## Selective8 build — September 15, 2026

At the user's request, production bundle now uses the exact benchmarked selective8 model (176.4 MB), retaining the waveform-generator subtree in full precision. Version `kokoro-1.0-selective8-en-sherpa-1.13.8-v3` changes disposable/prepared audio identity so old FP32/int8 speech is not reused for this test. Total bundle 234.9 MB; universal debug APK 436.9 MB.

The build reproduces conversion via `tools/quantize-kokoro.py` with pinned ONNX tooling in an isolated uv Python 3.11 environment, verifies both source and output SHA-256, and caches results. The benchmark script now takes its FP32 baseline from the verified build cache rather than the current bundled model.

83 JVM tests, debug builds and lint passed. Actual bundled selective8 passed emulator generation, cancellation/reuse and ringing assertions (8.018 seconds total); measured tone prominence about 2.25/2.06 dB. Phone installation completed at 11:20:57; saved engine remained Vertex and broker configuration was verified present. ARM speed/quality await the user's test.

Selective8 APK installed on ANDROID_DEVICE at 11:20:57 with `install -r` (Success); app launched, engine verified Vertex and broker configuration retained. User will select Kokoro for testing. A new bounded resource log is running at `tools/artifacts/poo-selective8-resources.jsonl`. No phone speed/quality result is claimed yet.

## Sentence generation — September 15, 2026

User reports selective8 feels faster on ANDROID_DEVICE, but long paragraphs leave noticeable gaps. Kokoro playback/preparation now queues each detected sentence separately, retaining quote/speaker IDs and source offsets. Overlong sentences split at word boundaries where possible, with a 350 UTF-16-character cap and surrogate-pair protection. Existing sentence-boundary detection uses the platform BreakIterator; this is not a new linguistic sentence parser.

Local jobs are scheduled in reading order, one pending generation at a time, while prior generated sentences can play. This avoids a later request acquiring the serialized native engine before the next required sentence. Within-paragraph chunks use the configured short pause; full paragraph pause applies only at paragraph end. This reduces time until the first playable chunk, not the amount of computation per spoken minute.

`kokoro-sentences-v1` is included in prepared-chapter signatures so old multi-sentence plans are rebuilt. Model version/weights are unchanged; text/voice/pause-keyed sentence audio remains reusable where identical. Google engines keep their existing segmentation/concurrency. Tests: 86 JVM tests and lint passed; two emulator instrumentation tests passed, including real chapter-plan sentence/pause verification and native generation/cancellation/ringing checks. Phone update installed successfully with `install -r`; selected engine and credentials preserved.

## Groq update — September 15, 2026

Groq Orpheus English is implemented and live synthesis passed in the Android emulator. English-only per user request; six voices, separate credentials, 200-character splitting, streaming WAV support, and per-book spending. Existing speech selection is preserved. See [Groq](groq.md) for setup and evidence. This supersedes earlier Groq-pending notes.

Verification: 90 JVM tests, debug app/test builds and Android lint passed. Live English Groq synthesis passed on the API 36 emulator. Settings UI verified English model and Troy voice. Installed the English-only APK on ANDROID_DEVICE (ANDROID_MODEL) over wireless ADB with install -r; private key import acknowledged after saving, selected engine preserved. Phone listening quality remains for user testing.

## Groq character analysis — September 15, 2026

Implemented Groq GPT-OSS 20B (default) / 120B text analysis, strict JSON schema, Groq voice recommendations, preserved cast overrides and saved batches, per-book token spending and selected-provider passage rewrites. Live emulator fixture passed with Google credentials blank: two speakers attributed, valid voices selected, playback plan constructed and durable analysis reused with Groq key removed. See the project wiki/groq.md for scope and limits.

Validation: 93 JVM tests and Android lint passed. Final APK passed live Groq character-analysis instrumentation in 1.996 seconds for the short fixture. Emulator Settings confirmed text provider Groq, Distinct mode and GPT-OSS 20B. This is functional evidence, not a long-chapter benchmark.

Installed the character-analysis update on ANDROID_DEVICE (ANDROID_MODEL) with wireless adb install -r. Enabled Groq text analysis. Post-install settings showed Vertex/Narrator selected, so those selections were preserved; choose Groq speech and Distinct mode to use Groq character voices. Groq key remained present.

## Next provider evaluation — September 15, 2026

User proposed ElevenLabs. Real Flash v2.5, Multilingual v2 and v3 speech requests succeeded; voice listing also works. Model listing lacks permission on the supplied testing key. See [evaluation](elevenlabs-evaluation.md). No ElevenLabs app integration or phone update yet.

## ElevenLabs and Cartesia — September 15, 2026

ElevenLabs integration adds eight preset voices and custom voice ID fields, shared by narrator/character/series voice selection. Three models, separate credentials, optional Groq analysis. ElevenLabs speech costs are recorded as unpriced because plan rates vary. See project wiki/elevenlabs.md.

User constraint: do not spend money on Cartesia during development. Its published Free plan has 20K credits/month; use only free allowance and local mocks. Do not upgrade or enable paid overages. No Cartesia requests have been made.

ElevenLabs verification: 96 JVM tests and lint passed. Real custom character-voice synthesis passed in the Android emulator (Laura, outside the dropdown); emulator UI selected Custom, entered the ID and persisted it correctly. Installed on ANDROID_DEVICE with install -r; key import acknowledged and key presence verified without displaying it. Existing Vertex speech selection preserved. Host temporary testing key file removed after provisioning.

Cartesia key supplied by user successfully accessed GET /voices (HTTP 200). No Cartesia audio generated; free-only development constraint remains. No key is recorded in documentation.

## Cartesia integration — September 15, 2026

Cartesia Sonic 3.6 is implemented with current API version 2026-08-14, six preset voices plus custom UUID entry for narrator/characters/series, Groq analysis, and per-book unpriced speech accounting. Automated generation tests use mocks under the user’s no-spending constraint. No live Cartesia speech was generated. See project wiki/cartesia.md for details.

Verification: 99 JVM tests and Android lint passed. API 36 emulator mock test verified current request transport, custom character voice, WAV decoding and cached replay with the server stopped. Emulator Settings selected Cartesia, entered a custom UUID and saved it successfully. Current-version live voice-list GET returned HTTP 200; no live TTS POST was made.

Installed on ANDROID_DEVICE (ANDROID_MODEL) over wireless ADB with install -r. Cartesia key import acknowledged and private settings verified key presence; existing speech selection preserved. Host temporary key file removed. Real Cartesia sound quality and generation latency remain for user testing within the free allowance.

## Streamed WAV fix — September 15, 2026

User reported "Truncated WAV chunk" on the phone. The initial mock used a finalized WAV header and missed streaming markers. Reproduced the exact strict-decoder error with Cartesia's published training_en.wav sample, without making any generation request.

The published file is 599838 bytes, with PCM mono 16-bit/44100 Hz, LIST metadata at offset 36, and a data chunk at offset 70. RIFF and data sizes are 0xffffffff (unknown), not actual lengths. SHA-256: 5e91780d3cbbbb691eede2ec1b07e05f497fe0a8d5b4f1f51effd737ed0dc6cf.

Moved Groq's existing sentinel-only normalization to Wav.decodeStreamed and use it for both cloud providers. Standard cache-file decode stays strict. Regression tests cover streamed metadata, preserved samples, ordinary truncated chunks, odd PCM data and ordinary WAV compatibility. Cartesia HTTP mock now sends streamed lengths.

[Published provider sample](https://github.com/cartesia-ai/cartesia-enterprise-usecases-demos/blob/main/examples/02_multilingual_training_practice/training_en.wav) is downloaded only to ignored tools/artifacts/cartesia-reference.wav, not bundled with the app. No Cartesia generation credits consumed.

Installed the streamed-WAV fix on ANDROID_DEVICE with data preserved. Both Cartesia instrumentation tests also passed on the phone (published provider sample plus streamed HTTP/cache fixture, 0.27 seconds). Removed the test-only package afterward and reopened PageCast. No live Cartesia generation request was made.

## Deepgram integration — September 15, 2026

English Flux TTS is implemented with six presets and custom voice models for narrator, character and series selections. Groq text analysis is the default, with Vertex/Gemini alternatives. Per-book speech estimates use $45/million characters before account credits. Provider credentials are separate and adding the key preserves the selected engine. See [Deepgram](deepgram.md).

Validation: 106 JVM tests, debug APK/test APK builds and lint pass. Live emulator synthesis with the non-preset Haley voice passes. The first live response exposed Deepgram's 0x7fff0000 WAV size placeholders; the final decoder normalizes only the exact paired header and retains rejection of ordinary truncated audio. Regression coverage includes the header, Token auth, query/body contract, cache reuse, custom voices and Unicode per-book pricing. No new proprietary SDK is used.

Final Deepgram build installed on ANDROID_DEVICE over wireless ADB. Live on-phone synthesis passed (2.15-second test); key presence verified and prior engine preserved. Test-only package removed. Emulator settings UI confirms Deepgram selection, Hannah preset and Groq analysis. Temporary host key deleted.

## Inworld — September 15, 2026

Added TTS-2 with balanced delivery, 48 kHz mono WAV, six presets plus custom voice IDs, narrator/distinct modes, Groq/Google character analysis, provider-specific cache/offline signatures and per-book spending estimates. The app uses synchronous JSON/base64 synthesis in the existing segment buffer. Reported character usage is priced at $25/million before credits; Unicode counts are the fallback. Credentials remain separate and provisioning preserves the engine.

Validation: 110 JVM tests and lint pass; authenticated voice catalog and a short live Sarah request succeeded. No new SDK dependencies. See [Inworld runbook](inworld.md).

Inworld final build installed on ANDROID_DEVICE; live Ashley synthesis passed (1.22-second smoke test). Key stored privately, existing engine preserved, test package removed and temporary host key deleted.

## Speechify — September 15, 2026

Simba 3.2 integrated with six catalog-verified presets, other voice IDs, Groq character analysis, per-book spending estimates and provider-specific cache/offline signatures. Speech uses JSON/base64 WAV from `/v1/audio/speech` in the existing segment buffer. Reported billable characters are valued at the $10/million Starter reference rate before included usage/discounts; missing counts remain unpriced.

114 JVM tests, APK builds and lint pass. Voice catalog authentication and a six-character live Geffen request succeeded. Regression tests cover Bearer auth, voice/model/input/format fields, audio decoding, truncation rejection, custom voice selection, cache reuse and provider-count accounting. No new SDK dependencies. See [Speechify](speechify.md).

Speechify final build installed on ANDROID_DEVICE. Live Alicia speech test passed (1.97-second smoke test). Key presence verified and prior engine preserved. Test package removed and temporary host credential deleted.

## Fish Audio — September 15, 2026

S2.1 Pro Free integrated with six official English presets, custom voice IDs, Groq character analysis and per-book free-request accounting. The model header is fixed to `s2.1-pro-free`; there is no paid-model selector or client fallback. Existing cache, offline signatures, cast overrides and pronunciation handling apply. Free access currently ends November 30, 2026; later usage is unpriced until terms are rechecked.

118 JVM tests, APK builds and lint pass. Read-only official catalog lookup and a short live free-model Sarah request succeeded. Fish's exact WAV size placeholders are normalized before shared decoding, with regression coverage for malformed chunks, auth/model header, request fields, custom voices and cache reuse. See [Fish Audio](fish.md).

Fish Audio final APK installed on ANDROID_DEVICE. Live free-model Sophia synthesis passed (2.15-second smoke test). UI confirmed presets/custom ID support. Key saved privately and existing engine preserved; test package removed and temporary host key deleted.

## Installed Android TTS — September 15, 2026

Added installed-engine discovery and English narrator voice selection with offline-only filtering, refresh and Android voice-settings shortcut. Narration requires no API key and uses no character analysis. Optional passage rewrites retain Groq. Native synthesizeToFile output feeds the existing cache/playback/offline preparation pipeline; engine/voice/policy identify cached audio. One engine operation runs at a time. Cancellation stops synthesis and removes temporary audio; switching away releases the engine.

119 JVM tests, builds and lint passed. Native emulator test passed offline synthesis, cached replay without generation, cancellation, recovery and temporary-file cleanup. See [Android TTS](android-tts.md).

Installed Android TTS final APK is on ANDROID_DEVICE. Native offline synthesis, cache reuse, cancellation, recovery and temporary-file cleanup passed on ANDROID_DEVICE (4.05 seconds), in addition to emulator checks.
## Remove experimental local engines — September 15, 2026

Removed rejected local-engine integrations, selectors, settings fields, model packaging tasks, downloaded models, benchmark tooling and obsolete documentation. Kokoro and installed Android TTS remain. Unsupported saved engine IDs fall back to offline Kokoro Narrator mode while preserving other settings and credentials. Measured Kokoro's current universal APK contribution at approximately 300.4 MB of assets and native libraries; see the Kokoro runbook.

Removal build: 121 JVM tests and lint passed. Clean repack reduced the debug APK from 707.5 MB to 327.1 MB, including removal of stale incremental ZIP space. Removed old benchmark/test outputs as well as source assets. Kokoro remains approximately 300.4 MB of this APK; remaining content/overhead approximately 26.7 MB.

Removal APK installed on ANDROID_DEVICE; retired extracted model folders deleted and app reopened. Fresh APK packaging is now automatic on every rebuild, verified with consecutive unchanged-source assembles. Optional post-install Kokoro download is feasible, but the exact custom selective-8 bundle needs a stable hosting URL before that feature can be delivered.

## Optional Kokoro downloads — September 15, 2026

Model and native libraries removed from the APK; base universal debug build is about 28.0 MB. Explicit Download/Cancel/Delete UI fetches public upstream artifacts, requires opt-in for code outside F-Droid checks, verifies embedded hashes, and selects only the matching process ABI. ARM64 runtime transfer is 9.9 MB using ZIP byte ranges. Fresh model installation downloads the 349.9 MB upstream archive and uses a 111 KB bundled conversion recipe to recreate the exact existing selective-8 model locally; no custom hosting is needed. Existing verified model installations can be reused.

122 JVM tests and lint passed, including byte-for-byte SHA-256 verification of the full reconstructed model. Installed the 27,989,384-byte APK on ANDROID_DEVICE. Public runtime/model downloads and extraction were verified; an asset-name packaging bug found during that test was fixed. The final phone test reused verified legacy FP32 files, reconstructed the exact selective-8 SHA-256, loaded the downloaded ARM64 libraries and passed speech, ringing, cancellation and recovery checks (2 instrumentation tests).
