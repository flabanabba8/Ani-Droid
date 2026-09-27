---
machine: build-host / Android
subsystem: fish-audio-tts
last-verified: 2026-09-15
status: live phone synthesis verified
---
# Fish Audio speech

Settings → Speech engine → Fish Audio · S2.1 Pro Free. Sarah is the default. Presets Sarah, Adrian, Selene, Jordan, Laura and Ethan were verified in the authenticated Fish Official English catalog. Other accessible voice models can be entered using their 32-character hexadecimal ID. Narrator, character and series profiles support the same picker.

Narrator mode uses only the Fish key. Distinct mode uses Groq character analysis by default, with Vertex/Gemini alternatives. The analysis schema recommends Fish voice IDs. Existing manual cast overrides, pronunciations, reviewed rewrites, retry, audio caching and offline preparation are retained. This integration does not create voice clones or expose Fish's multi-speaker request format/performance instructions.

## Free model and accounting

Every speech request explicitly sets the `model` header to **s2.1-pro-free**. The app has no paid-model selector or automatic paid fallback. HTTP 402 surfaces an account/free-model availability error. The provider currently advertises free access through November 30, 2026 under fair use, without latency guarantees. Requests may be used to improve its models.

Successful free-model requests are recorded per book at $0 through that published date. After the date, the tracker marks them unpriced until terms are checked again; it does not assume the promotion continues. Groq/Google character analysis retains its separate usage estimate. The integration does not retrieve account balances or remaining allowance.

## Audio and API

Bearer-authenticated JSON requests go to `https://api.fish.audio/v1/tts`. Body fields select text, reference ID, WAV at 44.1 kHz and `latency: normal` for quality. The reader groups sentences into at most 1,000 characters; the client applies a conservative 2,000-character cap. It consumes complete HTTP audio responses into the existing playback buffer.

Fish's live WAV response uses paired RIFF/data size placeholders `0xffffff24` / `0xffffff00`. The client normalizes only that exact 44-byte header shape before shared WAV decoding; ordinary truncation remains rejected. Cache keys include endpoint, model, voice, text and pause, excluding credentials. Offline signatures include voice and analysis settings.

Only 429/5xx responses receive bounded retries. Cancellation cancels the request, redirects are disabled, and audio decode failure does not automatically retry synthesis. Credentials stay in private settings; debug provisioning changes only the new key and preserves the selected engine.

## Privacy and F-Droid

Text and voice reference IDs go to Fish Audio; analysis/rewrites go to the selected text provider. Free-tier model-improvement terms apply. The integration uses existing OkHttp, without a proprietary SDK or bundled Fish model weights. Include this optional service in NonFreeNet disclosure; existing release blockers remain.

## Sources

- [Speech API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)
- [Free API terms](https://fish.audio/blog/s2-1-pro-free-api/)
- [Voice catalog API](https://docs.fish.audio/api-reference/endpoint/model/list-models)

## Verification

118 JVM tests, debug builds and lint pass. Emulator settings UI verified the free model, Sarah default, Groq analysis and custom voice field. Final APK installed on ANDROID_DEVICE via wireless ADB; live free-model synthesis with Sophia (outside the preset list) passed in a 2.15-second smoke test. This is not a latency or listening-quality benchmark. Key presence verified and prior engine preserved. Test package removed; temporary host key deleted.
