---
machine: build-host / Android
subsystem: pagecast
last-verified: 2026-09-15
status: active
---
# PageCast LLM wiki

Start here in future sessions. This is the project's durable knowledge base, following the conventions in the user's infrastructure llm-wiki. Keep observed facts separate from plans; update verification dates after testing, not merely editing. Never store credentials or copyrighted book content here.

- [F-Droid release plan](fdroid.md) — intended distribution, observed gaps and release decisions.
- [Kokoro precision benchmarks](kokoro-benchmarks.md) — FP32, FP16 and selective 8-bit comparison.
- [On-device Kokoro](kokoro.md) — local model, build inputs, voices and testing.
- [Passage editing and recovery](passages.md) — explicit retry and reviewed rewrite workflow.
- [Spending estimates](spending.md) — totals, book accounting and limitations.
- [Privacy and data use](../PRIVACY.md) — local storage and optional cloud requests.
- [Feature backlog](features.md) — all brainstormed features, priorities and acceptance criteria.
- [Architecture and decisions](architecture.md) — how the app works and why.
- [Development runbook](development.md) — build, emulator and phone workflow.
- [Authentication](authentication.md) — private BROKER_HOST broker, pairing, recovery and security limits.
- [Current work](current-work.md) — implementation status and next steps.
- [Offline chapters and reusable performances](next-milestone.md) — pronunciation, series profiles, preparation and remaining spoiler work.
- [Verification evidence](../VERIFICATION.md) — actual test results, not aspirations.
- [Voice research](../docs/voices.md) — official voice metadata and prompt policy.

Conventions: relative links; front matter on each wiki page; explicit `PLANNED`, `IN PROGRESS`, `IMPLEMENTED`, or `VERIFIED` status. Record the reason for decisions and unresolved limitations. The original root HANDOFF is historical bootstrap guidance; use this wiki and current source for ongoing state.

## Groq TTS

Groq Orpheus English is available in Settings, with six voices, a separate API key, Narrator/Distinct modes, and per-book spending estimates. See [Groq setup and limitations](groq.md).

- [ElevenLabs evaluation](elevenlabs-evaluation.md) — three successful API tests; next provider candidate.

## ElevenLabs

Eight preset voices plus custom voice ID entry for narrator, characters and series profiles. Three speech models and optional Groq character analysis. See [ElevenLabs setup](elevenlabs.md).

## Cartesia

Sonic 3.6 with six presets, custom voice IDs and optional Groq character analysis. See [Cartesia setup and development budget](cartesia.md).

## Deepgram

English Flux TTS, six preset voices plus other voice models, optional Groq character analysis and per-book spending estimates. See [Deepgram setup](deepgram.md).

## Inworld

TTS-2 speech with six presets, custom voice IDs, optional Groq character analysis and per-book spending estimates. See [Inworld setup](inworld.md).

## Speechify

English Simba 3.2, six presets plus custom voice IDs, Groq character analysis and per-book usage estimates. See [Speechify setup](speechify.md).

## Fish Audio

S2.1 Pro Free with six official English presets, custom IDs, Groq character analysis and per-book request tracking. See [Fish Audio setup](fish.md).

## Android speech engines

Use an installed Android TTS engine and English narrator voice. Offline voices are selected by default; no speech API key is needed. See [Android TTS setup](android-tts.md).