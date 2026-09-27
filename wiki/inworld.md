---
machine: build-host / Android
subsystem: inworld-tts
last-verified: 2026-09-15
status: live phone synthesis verified
---
# Inworld speech

Settings → Speech engine → Inworld · TTS-2. The default is Sarah, with Dennis, Chloe, Clive, Deborah and Simon presets plus custom voice ID entry. All six presets were verified against the authenticated voice catalog. Narrator, character and series-profile pickers support other accessible voice IDs.

Narrator mode needs only the Inworld key. Distinct mode uses Groq character analysis by default, with Vertex/Gemini alternatives. Analysis recommends Inworld voices and preserves manual character overrides. Existing pronunciations, reviewed rewrites, passage retry, cache and offline preparation work with the provider. This integration supports narrator/distinct modes; it does not yet expose Inworld's free-form delivery instructions or voice cloning operations.

## API

The client sends Basic authorization with the already encoded API credential; users enter the value after `Basic`, without encoding it again. Keys remain in private settings and are not included in source, logs or cache identities. Debug provisioning changes only the new key, preserving the selected engine and other credentials.

For existing segment-based playback, the client uses the synchronous `/tts/v1/voice` endpoint. Each response is JSON containing base64 `audioContent` and usage. TTS-2, BALANCED delivery and LINEAR16 at 48 kHz return mono PCM16 with a WAV header. The app decodes the WAV and uses its existing playback buffer. This is not incremental playback of the playground's `voice:stream` response. Sentences are grouped into segments of at most 1,000 characters, with the API's 2,000-character cap enforced by the client.

Successful requests are recorded by book at the published on-demand **$25/million characters**, before credits, plan discounts and taxes. Inworld's `processedCharactersCount` takes precedence; missing usage falls back to an explicitly estimated Unicode code-point count. Cached replay makes no synthesis request. Credits remaining were not queried or verified.

HTTP 429/5xx responses get bounded retries; authentication errors do not. Cancellation cancels the request. Redirects are disabled. Audio parsing errors do not cause an automatic paid retry.

## Verification

110 JVM tests and lint pass. Coverage includes Basic auth, JSON/base64 audio decoding, cached replay without a second request, separate voice/cache identities, custom character selection, malformed audio rejection and reported-character per-book accounting. A six-character live request returned valid 48 kHz PCM16 WAV. Voice catalog access was read-only.

## Privacy and distribution

Speech text and voice IDs go to Inworld; character analysis/rewrites go to the selected text provider. Provider retention terms apply. Integration uses existing OkHttp, adding no proprietary SDK. Inworld is another optional NonFreeNet service for F-Droid disclosure; existing release blockers still apply.

## Sources

- [Synthesis API](https://docs.inworld.ai/api-reference/ttsAPI/texttospeech/synthesize-speech)
- [Pricing](https://inworld.ai/pricing), checked September 15, 2026

Final APK installed on ANDROID_DEVICE via wireless ADB. Live Android synthesis with Ashley (outside the preset list) passed in a 1.22-second test. This is a short smoke test, not a latency benchmark. Key presence verified, selected engine preserved, test package removed and temporary host credential deleted.
