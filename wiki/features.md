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
3. **Prepare chapter offline.** IMPLEMENTED FIRST VERSION: explicit current-chapter confirmation, source character count, progress/cancellation, reusable completed segment files, durable WAVs outside the disposable LRU and full-chapter export. Tested with mock server stopped and disposable cache removed. Background job resumption and precise storage/cost estimation remain future work. See [implementation](next-milestone.md).
4. **Reliable resume.** IMPLEMENTED: separate audio/scroll bookmarks, saved offset and speed, last-book reopening. See [current verification and limits](current-work.md).
5. **Sleep timer.** PLANNED: elapsed time, end of paragraph, end of chapter.

## Better performances

6. **Persistent series cast.** PARTIAL: explicit series membership and manually linked reusable voice profiles implemented. No automatic cross-book name/alias matching or plot-description transfer. Strict progressive/reveal-gated analysis remains planned; current whole-chapter analysis is not spoiler-free.
7. **Pronunciation dictionary.** IMPLEMENTED: manual entry/edit/delete, narrator preview, book/series/global scope, whole-word/phrase phonetic substitutions used only in spoken text. Original analysis and displayed text remain unchanged.
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
