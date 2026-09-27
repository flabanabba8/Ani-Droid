---
machine: build-host / Android
subsystem: deepgram-tts
last-verified: 2026-09-15
status: live synthesis verified on emulator and ANDROID_DEVICE
---
# Deepgram speech

Settings → Speech engine → Deepgram · Flux. English Flux TTS uses six presets: Hannah (default), Kit, Sienna, Cliff, Gemma and Colin. Other English voice models can be entered directly, for example `flux-haley-en`. The same picker supports narrator, character and series voices.

Narrator mode needs only the Deepgram key. Distinct mode uses Groq character analysis by default, with Vertex and Gemini alternatives. Existing saved cast, manual overrides, quote attribution, pronunciation replacement and reviewed passage rewrites remain available. Analysis selects Deepgram voice models. Speech requests do not include free-form Gemini performance instructions.

## API and accounting

The app posts `{"text":"…"}` to `https://api.deepgram.com/v2/speak` using Token authorization. Query parameters select the voice model and 24 kHz mono PCM16 WAV output. Sentence-aware segments target at most 1,000 characters; the client enforces a conservative 2,000-character cap. The client normalizes Deepgram’s exact paired RIFF/data placeholders (0x7fff0024 / 0x7fff0000) before shared WAV decoding. Other malformed chunks remain rejected. Audio is cached by voice, endpoint version, text and pause, independent of credentials. Offline chapter signatures include Deepgram voice and text-analysis settings.

Successful requests are recorded per book with a **$45/million Unicode characters** PAYG estimate before credits, promotions or account discounts. These are estimates of usage value, not the account balance or money actually charged. Groq analysis is recorded separately. Account credit balance was not verified. Cached replay makes no synthesis request.

Only HTTP 429 and 5xx responses are retried, up to four total attempts, with bounded Retry-After/backoff. Authentication errors are not retried. Coroutine cancellation cancels the network call. Redirects are disabled and raw provider error bodies are not displayed.

The app uses existing OkHttp, with no proprietary SDK or new dependency. Speech text leaves the device for Deepgram; provider retention and model-improvement terms apply. The request does not override the provider's model-improvement default. Credentials remain in app-private settings. Debug provisioning saves only the new key and preserves the selected engine and other credentials.

## Sources

- [Flux REST API](https://developers.deepgram.com/docs/flux-tts/batch)
- [English voice catalog](https://developers.deepgram.com/docs/flux-tts/voices)
- [Pricing](https://deepgram.com/pricing), checked September 15, 2026

## Verification

106 JVM tests, debug builds and lint passed. Live Android synthesis using the other-voice Haley model passed on emulator (2.58 seconds for the test) and ANDROID_DEVICE (2.15 seconds). These short tests are not an audiobook latency benchmark. Settings UI confirmed Deepgram, default Hannah voice and Groq analysis. Final APK installed over wireless ADB; key provisioned into private app storage without changing the selected engine. Test-only package removed from ANDROID_DEVICE.
