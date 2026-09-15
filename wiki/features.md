---
machine: build-host / Android
subsystem: roadmap
last-verified: 2026-09-15
status: active backlog
---
# Feature backlog

The complete post-MVP brainstorm, with the requested audio export addition. Original discussion groups and ordering are retained.

## Make it feel finished

1. **Automatic Vertex authentication renewal.** IMPLEMENTED: authenticated, certificate-pinned HTTPS broker on BROKER_HOST, automatic short-lived token refresh and concurrent refresh sharing. No Google refresh credentials on phone. Local Wi-Fi required; see [security and runbook](authentication.md).
2. **Time-based buffering.** IMPLEMENTED: configurable playable-duration target, visible seconds/minutes ready, two concurrent synthesis calls. Duration uses actual generated WAVs and playback speed; jumps reuse prepared audio. Best-effort target, not a spending cap.
3. **Prepare chapter offline.** PLANNED: explicit chapter selection, preflight work/storage estimate, progress, cancellation, resumable generation, and durable audio outside the disposable LRU. Acceptance: the prepared chapter plays in airplane mode without analysis or TTS requests. Never prepare an entire book implicitly.
4. **Reliable resume.** IMPLEMENTED: separate audio/scroll bookmarks, saved offset and speed, last-book reopening. See [current verification and limits](current-work.md).
5. **Sleep timer.** PLANNED: elapsed time, end of paragraph, end of chapter.

## Better performances

6. **Persistent series cast.** PLANNED: explicit series membership; reuse character identity, voices, pronunciation and user edits across volumes; no accidental merging by name alone. Acceptance: a second volume adopts its linked series' cast and manual edits remain authoritative.
7. **Pronunciation dictionary.** PLANNED: name corrections reused across books/series without changing displayed source text.
8. **Character auditions.** PLANNED: compare the same quote across voices and directions before saving a choice.
9. **Performance intensity.** PLANNED: restrained narration through dramatic acting, with character overrides.
10. **Correct a wrong speaker.** PARTIAL MVP: long-press and reassign exists. Add a streamlined correction flow and regenerate only affected audio; invalidate offline/export manifests deliberately.
11. **Inner monologue and document delivery.** PLANNED: differentiate thoughts, letters, dialogue and narration with stable subtle directions.

## Reading and listening together

12. **Sentence seeking and rewind/forward.** PLANNED: sentence jumps plus 15-second transport controls; timing remains estimated unless alignment is added.
13. **Bookmarks, notes and saved quotes.** PLANNED: local, source-position-based and exportable.
14. **Reading/listening history.** PLANNED: progress and estimated time remaining, with explicit privacy controls.
15. **Spoiler-safe character reference.** PLANNED: restrict evidence to text before the current position; do not expose future-volume cast notes.
16. **Return-to-book recap.** PLANNED: short recap limited to already-read material; opt-in model request.

## Added by user

17. **Audio export.** IMPLEMENTED: export the generated current chapter queue through Android's document picker, potentially a partial chapter. Lossless WAV; streamed concatenation; export itself makes no synthesis requests. Future: offline chapter preparation, AAC/M4A, chapter markers, metadata and full-book export with explicit confirmation.

## First implementation milestone

User clarified **three priorities: automatic Vertex renewal, time-based buffering, reliable resume**, plus audio export. Local Wi-Fi via the BROKER_HOST is acceptable. Offline preparation and series casts remain backlog, outside this milestone.

Pricing estimates must use current verified provider rates or user-supplied rates, never guessed prices. Until billing-rate integration exists, show uncached text size, segment count and PCM storage estimates rather than a misleading dollar figure.
