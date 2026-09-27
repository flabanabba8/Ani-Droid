---
machine: build-host / Android
subsystem: speechify-tts
last-verified: 2026-09-15
status: live phone synthesis verified
---
# Speechify speech

Settings → Speech engine → Speechify · Simba 3.2. English speech defaults to Geffen (`geffen_32`). Six presets (Geffen, Alec, Carly, Archie, Beatrice and Benjamin) were selected from the authenticated catalog with Simba 3.2 support. Other accessible English voice IDs can be entered for narrator, character and series-profile voices.

Narrator mode needs only the Speechify key. Distinct mode uses Groq character analysis by default, with Vertex/Gemini alternatives. Analysis recommends Speechify voice IDs. Manual cast overrides, pronunciations, reviewed rewrites, retry, audio caching and offline chapters use the existing reader pipeline. Full performance/SSML direction and voice cloning creation are not exposed in this integration.

## API and usage

Bearer-authenticated `POST https://api.speechify.ai/v1/audio/speech` sends `input`, `voice_id`, explicit `model: simba-3.2` and `audio_format: wav`. The response contains base64 `audio_data`; shared WAV decoding handles streamed size markers and metadata chunks. A live six-character request returned 48 kHz mono PCM16 WAV. The reader groups sentences into at most 1,000 characters; the client enforces the API's 2,000-character limit. The complete response feeds the existing playback buffer; this integration does not play HTTP audio incrementally.

Costs are grouped by book and model. Provider-reported `billable_characters_count` is valued at the Starter reference overage rate of **$10/million characters**, marked estimated. This does not mean free-tier requests incurred a charge. Included allowance and plan discounts are not deducted; missing billable counts are recorded as unpriced rather than guessed because whitespace/SSML are excluded from billing. Cached replay makes no synthesis request.

The pricing page currently advertises 500,000 free characters per month with no credit card and a pause once exhausted. Account-specific remaining allowance was not verified. HTTP 402 displays an allowance message; 401/403 are not retried. Only 429/5xx receive bounded retries. Cancellation cancels the request, redirects are disabled, and decode failures do not trigger a paid retry.

Credentials remain in private settings and are excluded from cache identities. Debug provisioning saves the key while preserving the selected engine and other credentials.

## Privacy and F-Droid

Speech text and voice selection go to Speechify. Analysis/rewrites use the selected Groq or Google provider. Provider retention terms apply. This uses existing OkHttp without a new proprietary SDK. Include Speechify in optional NonFreeNet disclosure; existing F-Droid release blockers remain.

## Sources

- [Speech API](https://docs.speechify.ai/tts/api-reference/text-to-speech/audio/speech)
- [Quickstart and voices](https://docs.speechify.ai/docs/get-started/quickstart)
- [Pricing](https://speechify.ai/pricing), checked September 15, 2026

## Verification

114 JVM tests, debug builds and lint passed. Emulator settings UI verified Speechify selection, Geffen default, Groq text provider and custom voice field. Unit coverage includes Bearer auth, explicit model/voice/input/format, base64 WAV, malformed audio rejection, custom character selection, cached replay and reported-character accounting.

Final APK installed on ANDROID_DEVICE via wireless ADB. Live Alicia synthesis (a voice outside the preset list) passed in a 1.97-second smoke test. Key presence verified, previous engine preserved, test package removed and temporary host credential deleted.
