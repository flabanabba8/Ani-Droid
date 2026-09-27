---
machine: build-host / Android
subsystem: android-system-tts
last-verified: 2026-09-15
status: installed and native phone tests passed
---
# Installed Android speech engines

Settings → Speech engine → Android · installed speech engines. Choose an installed engine and an English narrator voice, then Save. Offline voices only is enabled initially. Disable it to list voices that report requiring a network connection. Refresh voices after installing voice data; Android voice settings opens the OS configuration screen.

This initial integration supports Narrator mode. It requires no API key and performs no character analysis. Existing credentials are preserved. Optional requested passage rewrites use Groq; those still require its key. The selected engine is never changed automatically during installation.

## Implementation

`AndroidTtsClient` wraps Android TextToSpeech with an app-scoped, serialized connection. Discovery uses the TTS service intent and a manifest package-visibility query. Voice choices come from the selected engine; only English voices without the not-installed feature are shown. The default Android engine is resolved to an explicit package and voice before saving. Missing engines/voices produce setup errors.

Synthesis uses `synthesizeToFile`, an utterance-ID-specific completion listener, a private temporary WAV and the existing PCM/cache/playback pipeline. Initialization and Android API operations run on the main dispatcher; PCM file decoding runs on IO. One synthesis runs at a time to avoid voice/stop interference. Cancellation stops synthesis, detaches the listener and deletes the temporary file. Initialization and synthesis have timeouts. Switching away from Android TTS stops playback/preparation and releases the engine connection.

Sentence-aware segments target 1,000 characters and respect Android's maximum input size. Engine package, voice and offline-only policy participate in cache and offline chapter identities. Cached replay does not call the installed engine. Playback speed remains controlled by the existing player.

## Privacy and distribution

PageCast adds no speech API charge. Offline status is reported by the installed engine; PageCast rejects network-required voices while Offline voices only is enabled. Network voices may send text to their engine provider. Installing/downloading voices is managed by Android and the selected engine. Native narration does not call PageCast's cloud speech clients.

No new library or proprietary SDK was added. Users can supply an open-source Android TTS engine. Existing F-Droid licensing/build/release blockers remain.

## Verification

119 JVM tests, APK builds and lint passed. Native emulator test covers an installed offline voice, PCM output, cached replay, cancelling a long passage, successful subsequent synthesis and temporary-file cleanup. No paid speech API requests are used.

## Sources

- [Android TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [Sherpa Android TTS engines](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html)

Final Android TTS build installed on ANDROID_DEVICE. Native Google offline voice synthesis, cached replay, cancellation, recovery and temporary-file cleanup all passed (4.05-second combined test). Emulator UI confirmed engine selection and offline voice discovery. Existing speech engine selection preserved.
