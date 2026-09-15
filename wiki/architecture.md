---
machine: build-host / Android
subsystem: architecture
last-verified: 2026-09-15
status: verified MVP; extensions in progress
---
# Architecture and decisions

- Native Kotlin + Jetpack Compose; Media3/ExoPlayer for playback. No ReAct/agent loop. Gemini text returns schema-constrained JSON, Kotlin builds prompts, Gemini TTS returns audio.
- Vertex is the default for both analysis and speech because the user wants usage in their Cloud credits project. Successful API requests do not prove promotional-credit eligibility. Long-lived user ADC remains on the BROKER_HOST; a pinned-HTTPS authenticated LAN broker supplies short-lived tokens automatically. See [authentication](authentication.md).
- Imports: EPUB/PDF/TXT/HTML/Markdown/FB2/DOCX. Private JSON stores source text, positions, analysis, cast and overrides. No DRM or scanned-PDF OCR.
- Chapter analysis: roughly 6,000 tagged characters / at most 24 quote spans per request, prior-context tail and roster, checkpoints after each successful batch. Shared in-progress work survives paragraph jumps. User overrides remain separate. Explicit read timeout 120 seconds, total call 150 seconds; timeouts retry once.
- TTS: narration/dialogue segments up to approximately 1,200 characters; WAV/PCM validation, silence trimming and pauses. Disk cache keys include voice/prompt/model/provider/text/pauses. Buffer is duration-based (default 120 seconds), measured from queued WAVs and adjusted for speed; two concurrent requests, best-effort target.
- Listening bookmarks contain chapter, paragraph, segment start, audio filename key, offset and speed. Scroll writes a different file. Last-book reopening restores listening location; backgrounding and media pauses persist. Periodic persistence is every two seconds.
- WAV export snapshots file references from the generated current queue, validates each PCM file and sample rate, concatenates with bounded memory into temporary app cache, then copies through the document picker. It does not initiate synthesis or imply a whole chapter is prepared.
- Voice profiles are sourced from Google's 30 voice gender/trait descriptions. Do not invent measured pitch ranges. Direction uses the actual selected voice, not the obsolete saved narratorGender field.
- Appearance is the first Settings control, applied/saved immediately. Fresh installs default to dark; explicit existing choices remain intact.

## Extension boundaries

Future offline artifacts must be separate from evictable audio cache. Current export includes only the pinned current queue. Token renewal uses authenticated encryption and origin validation. Future series membership must be explicit and opt-in; imported books remain independent by default.
