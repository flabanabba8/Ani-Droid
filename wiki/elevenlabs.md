---
machine: build-host / Android
subsystem: elevenlabs-tts
last-verified: 2026-09-15
status: verified development build
---
# ElevenLabs speech and voice selection

Settings → Speech engine → ElevenLabs. Flash v2.5 is the initial model; Multilingual v2 and v3 are also selectable. The API key is separate from Groq and Google credentials.

Voice selection uses a short dropdown: George, Brian, Adam, Liam, Sarah, Jessica, Matilda and Lily. These IDs and metadata were checked against the account's public premade voice list on September 15, 2026. Select **Custom / other voice ID** to paste an accessible voice ID from ElevenLabs. IDs are validated as path-safe identifiers; URLs and display names are rejected. The account must have permission to use the voice. The same picker is available for narrator, characters and series profiles; character/profile fields also allow Automatic.

The eight names are a curated convenience list, not a live popularity ranking. ElevenLabs has announced retirement of these current default voices on December 31, 2026. Refresh this catalog before that date or public release; do not silently map to different-sounding voices. Custom voice IDs provide access beyond the shortlist. [Default voice transition](https://help.elevenlabs.io/hc/en-us/articles/26942950589969-What-are-Default-voices).

## Speech and character analysis

Narrator mode requires only the ElevenLabs key. Distinct mode uses the selected text provider (Groq by default; Vertex/Gemini also available). Existing Groq GPT-OSS selection/key are shared. Analysis recommends voices from the shortlist; an explicit custom voice overrides the recommendation.

The client requests 24 kHz 16-bit mono raw PCM over HTTPS. Reader passages split at sentence boundaries with a 1000-character target; the API client accepts up to 3000 characters including pronunciation replacements. Custom voice and model participate in cache/offline identity. Credential changes do not invalidate audio. Uses existing buffering, offline chapter preparation, previews and cancellation. Only transient HTTP 429/5xx responses retry automatically. No Google performance instructions are appended to speech text; full v3 performance-tag support remains future work.

## Spending and privacy

Speech text and selected voice ID go to ElevenLabs. Successful responses count by model and book, but are marked **unpriced** because USD rates depend on the account's subscription and voice pricing. No flat dollar amount is invented. Groq/Google analysis continues to use existing price estimates.

No proprietary SDK was added. Existing OkHttp handles requests and does not follow redirects. API keys live in private app settings. Include ElevenLabs in F-Droid's optional non-free network-service disclosure. [Speech API](https://elevenlabs.io/docs/api-reference/text-to-speech/convert).

## Development tests

Three desktop API probes passed before integration; see [evaluation](elevenlabs-evaluation.md).
The Android live test is opt-in: class com.geminireader.tts.ElevenOnDeviceTest with instrumentation argument elevenLive=true. It synthesizes a short passage with Laura, an accessible voice outside the dropdown, to verify custom character voice routing.

ElevenLabs verification: 96 JVM tests and lint passed. Real custom character-voice synthesis passed in the Android emulator (Laura, outside the dropdown); emulator UI selected Custom, entered the ID and persisted it correctly. Installed on ANDROID_DEVICE with install -r; key import acknowledged and key presence verified without displaying it. Existing Vertex speech selection preserved. Host temporary testing key file removed after provisioning.
