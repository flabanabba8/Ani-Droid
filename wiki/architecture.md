---
machine: build-host / Android
subsystem: architecture
last-verified: 2026-09-15
status: implemented; Kokoro audio-quality investigation active
---
# Architecture and decisions

- Native Kotlin + Jetpack Compose; Media3/ExoPlayer for playback. No ReAct/agent loop. Gemini text returns schema-constrained JSON, Kotlin builds prompts, Cloud TTS returns audio; Kokoro generates PCM locally.
- Fresh installs still default to Vertex/Performance. Vertex is the default for both analysis and speech because the user wants usage in their Cloud credits project. Successful API requests do not prove promotional-credit eligibility. Long-lived user ADC remains on the BROKER_HOST; a pinned-HTTPS authenticated LAN broker supplies short-lived tokens automatically. See [authentication](authentication.md).
- Imports: EPUB/PDF/TXT/HTML/Markdown/FB2/DOCX. Private JSON stores source text, positions, analysis, cast and overrides. No DRM or scanned-PDF OCR.
- Chapter analysis: roughly 6,000 tagged characters / at most 24 quote spans per request, prior-context tail and roster, checkpoints after each successful batch. Shared in-progress work survives paragraph jumps. User overrides remain separate. Explicit read timeout 120 seconds, total call 150 seconds; timeouts retry once.
- TTS: narration/dialogue segments up to approximately 1,200 characters; WAV/PCM validation, silence trimming and pauses. Disk cache keys include voice/prompt/model/provider/text/pauses. Buffer is duration-based (default 120 seconds), measured from queued WAVs and adjusted for speed; two concurrent cloud requests, best-effort target. Kokoro queues one sentence per chunk, splits unusually long sentences at up to 350 characters, and schedules one local request at a time in reading order; native synthesis is serialized.
- Listening bookmarks contain chapter, paragraph, segment start, audio filename key, offset and speed. Scroll writes a different file. Last-book reopening restores listening location; backgrounding and media pauses persist. Periodic persistence is every two seconds.
- WAV export snapshots file references from the generated current queue, validates each PCM file and sample rate, concatenates with bounded memory into temporary app cache, then copies through the document picker. It does not initiate synthesis or imply a whole chapter is prepared.
- Voice profiles are sourced from Google's 30 voice gender/trait descriptions. Do not invent measured pitch ranges. Direction uses the actual selected voice, not the obsolete saved narratorGender field.
- Appearance is the first Settings control, applied/saved immediately. Fresh installs default to dark; explicit existing choices remain intact.

## Extension boundaries

Offline artifacts are stored separately from the evictable audio cache. Export prefers a complete valid prepared chapter, otherwise the pinned generated queue. Token renewal uses authenticated encryption and origin validation. Series membership and reusable voice links are explicit and opt-in; imported books remain independent by default.

## Current extensions and release constraints

Kokoro uses one process-scoped native model with serialized generation/release, CPU inference and cancellable callbacks. A concrete kept Kotlin callback is required because JNI looks up `invoke(float[]): Integer`; an invokedynamic lambda is incompatible. Model files are verified and installed atomically in no-backup storage. Model identity participates in disposable and prepared cache signatures. The int8 version had measurable 4.8/9.6 kHz ringing. The fp32 replacement passes emulator comparison; phone listening acceptance remains pending.

Passage edits affect the imported copy and invalidate dependent analysis/audio signatures. Rewrites require a recorded rejection plus an explicit user request and review; retries preserve text. Spending persistence is independent of book deletion. See [passages](passages.md), [spending](spending.md), [privacy](../PRIVACY.md).

F-Droid is the intended release channel. Dependency choices must include a source-build and licensing path; the current prebuilt Kokoro AAR download needs release work. See [release plan](fdroid.md).

## Groq text analysis

GroqAnalysis adapts the shared character/rewrite JSON requests to Groq chat completions with strict schemas and low reasoning effort. It converts successful complete responses to the existing validated analysis format. Selected text provider and Groq text model are separate from speech provider/model and Google model settings. Voice suggestions use the actual Groq catalog for Groq speech. Chapter caches include provider, text model and voice-catalog identity; manual cast/quote overrides are retained. Groq chat usage flows through the existing SpendingBook coroutine context.

## Deepgram — September 15, 2026

Deepgram follows the fixed-voice provider path: `DeepgramTtsClient`, Token-authenticated `/v2/speak`, shared streamed WAV decoder, voice-specific cache and chapter signatures, and independent text-analysis credentials. See [Deepgram](deepgram.md).

## Inworld — September 15, 2026

Inworld uses Basic-authenticated `/tts/v1/voice` JSON/base64 WAV responses, the existing playback buffer, provider-specific cache/offline identities and independent text credentials. See [Inworld](inworld.md).

## Speechify — September 15, 2026

Speechify uses Bearer-authenticated `/v1/audio/speech`, JSON/base64 WAV responses, provider-specific cache/offline identities and independent text analysis credentials.

## Fish Audio — September 15, 2026

Fish Audio uses a fixed free-model header and binary WAV `/v1/tts` responses. Its exact paired WAV size placeholders are normalized before shared decoding. Cache/offline identities are provider-specific.

## Android TTS — September 15, 2026

AndroidTtsClient serializes TextToSpeech connections and synthesizeToFile requests, then decodes the private WAV into the common cache/playback pipeline. Cancellation stops synthesis and cleans up. Engine/voice/offline policy are included in cache and chapter signatures.

Kokoro is now an optional post-install download: its model and native runtime are excluded from the APK. Settings provides Download, Cancel and Delete controls. Only the matching CPU architecture is fetched from public sherpa-onnx GitHub releases; the exact selective-8 model is prepared locally using a small bundled recipe. No custom hosting or speech API key is required. Downloads occur only after explicit consent; installed Narrator speech works offline. See [Kokoro](kokoro.md).
