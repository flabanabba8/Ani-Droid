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

17. **Audio export.** IMPLEMENTED: export the generated current chapter queue through Android's document picker, potentially a partial chapter. Lossless WAV; streamed concatenation; export itself makes no synthesis requests. Full prepared-chapter export is implemented. Future: AAC/M4A, chapter markers, metadata and full-book export with explicit confirmation.

## Additional TTS providers (future)

18. **More cloud TTS engines.** IMPLEMENTED: Groq Orpheus English, [ElevenLabs](elevenlabs.md) and [Cartesia](cartesia.md), including curated/custom voice pickers. See [Groq](groq.md). Other planned providers: OpenAI, and evaluate other established providers (for example Azure, Amazon Polly, other providers). Prioritize legitimate free tiers and low-cost options. Compare current model availability, monthly allowances, expiry/trial limits, billing requirements, export rights, voice quality, supported languages, latency and direction controls before implementing. Do not label a service permanently free based on promotional credits or assume a provider's free LLM tier includes speech. Provider adapters must preserve pronunciation, cache identity, audio export and accurate capability reporting; never silently switch providers or billing accounts.
19. **Local TTS.** IMPLEMENTED: on-device Kokoro, 28 English voices, Narrator/Distinct modes, shared caching/preparation/export. Speech runs on Android without a server. Google text analysis remains optional for Distinct/rewrite. **QUALITY ISSUE:** user reports crunchy/lo-fi audio and persistent ringing with the int8 model; fp32 fix now built and measured on the emulator; phone quality acceptance remains pending. Do not label subjective audio quality verified. See [Kokoro](kokoro.md).
20. **Passage editing and recovery.** IMPLEMENTED: long-press editor, explicit unchanged-text Retry generation and reviewed Suggest milder wording for recorded rejections; scene breaks skipped. See [passages](passages.md).
21. **Spending estimates.** IMPLEMENTED: local total/month/provider/model and per-book estimates, with missing-rate limitations. See [spending](spending.md).
22. **F-Droid release.** PLANNED: source publication, app license, native source-build/model provenance, signing, offline setup and metadata. See [release plan](fdroid.md).

Research starting points: [Android voice network requirement](https://developer.android.com/reference/android/speech/tts/Voice#isNetworkConnectionRequired()), [sherpa Android engines](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html), [Kokoro Android example](https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/kokoro-multi-lang-v1_1.html), [Picovoice Orca](https://picovoice.ai/docs/orca/), [Groq changelog](https://console.groq.com/docs/changelog). Kokoro is implemented; other engines in this research list remain unimplemented. Groq's changelog indicates migration from PlayAI to Orpheus; old API examples should not be treated as current model availability.

## Original first implementation milestone

User clarified **three priorities: automatic Vertex renewal, time-based buffering, reliable resume**, plus audio export. Local Wi-Fi via the BROKER_HOST is acceptable. Offline preparation and manually linked series casts were subsequently implemented.

Pricing estimates must use current verified provider rates or user-supplied rates, never guessed prices. The current local estimate uses supported provider rates; keep unpriced usage visible and do not imply a provider invoice.

## Deepgram — September 15, 2026

Deepgram English Flux TTS supports narrator and distinct character voices, six presets plus other voice models, Groq/Google text analysis and per-book PAYG spending estimates. See [Deepgram](deepgram.md).

## Inworld — September 15, 2026

Inworld TTS-2 adds six voice presets, custom narrator/character/series voices, Groq analysis and per-book usage estimates. See [Inworld](inworld.md).

## Speechify — September 15, 2026

Speechify Simba 3.2 offers six presets, custom narrator/character/series voices and Groq character analysis. See [Speechify](speechify.md).

## Fish Audio — September 15, 2026

Fish Audio S2.1 Pro Free supports six official English presets, custom narrator/character/series voice IDs and Groq analysis. See [Fish Audio](fish.md).

## Android TTS — September 15, 2026

Installed Android TTS engines now support English narrator voices, offline-only filtering, voice refresh and an OS voice-settings shortcut. Existing playback/cache/offline preparation apply. See [Android TTS](android-tts.md).

Kokoro is now an optional post-install download: its model and native runtime are excluded from the APK. Settings provides Download, Cancel and Delete controls. Only the matching CPU architecture is fetched from public sherpa-onnx GitHub releases; the exact selective-8 model is prepared locally using a small bundled recipe. No custom hosting or speech API key is required. Downloads occur only after explicit consent; installed Narrator speech works offline. See [Kokoro](kokoro.md).
