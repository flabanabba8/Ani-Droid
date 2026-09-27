---
machine: build-host / Android
subsystem: roadmap implementation
last-verified: 2026-09-15
status: first implementation tested; strict spoiler-safe analysis deferred
---
# Offline chapters and reusable performances

User requests implementation, including manual pronunciation entry. Scope: offline chapter preparation/full prepared-chapter export, pronunciation dictionary, and spoiler-aware persistent voice profiles. Sleep timer remains separate backlog.

1. Dictionary: manual written/spoken fields, book/series/global scope, preview, edit/delete; whole-word/phrase matching, longest match first, specific scope wins, non-cascading replacements. Apply only to synthesized text; preserve original analysis/display text. Audio identity follows the actual transformed speech so only changed speech misses cache.
2. Series cast: explicitly create/join a series and manually link a detected speaker to a reusable voice profile. Profile contains only a user label, voice and performance instructions, never model-generated plot descriptions, aliases or relationships. No automatic name matching across volumes. Linking an unrevealed identity is an explicit user choice.
3. Offline chapter: explicit confirmation of scope and potential billed generation, cancellation, resumable segment files and manifest in durable app files, not evictable cache. Playback can load prepared plan/audio without network; export the complete prepared chapter. Voice/dictionary/settings changes must not silently play a stale preparation.

## Spoiler boundary

Existing analysis sees the entire chapter and can infer identities from later text or model background knowledge. Manual voice profiles do not fix this. Never label existing analysis as spoiler-free. Strict progressive, evidence-positioned analysis and reveal-gated facts require a separate redesign; the first persistent-cast version avoids *new cross-volume* information transfer and warns about chapter lookahead. It does not store or surface a future-volume encyclopedia.

## Acceptance checks

Unit tests for dictionary boundaries, scope priority, Unicode, possessives, non-cascading replacements and profile isolation. Mock emulator test for manual entry/preview, transformed TTS with unchanged source text, offline preparation and export. Document exactly what is implemented versus deferred.

User verified physical headphones: connecting did not autoplay, disconnecting paused, manually pressing Play resumed seamlessly. This is user-reported device verification, not a new automated hardware test.

## Implemented

Saved series profiles now have **Delete profile** with confirmation. Deletion removes links to that profile across its series, preserving other profiles/series and returning affected characters to book-specific voices. Edited form state is cleared if its profile is deleted. Source text and exported audio are retained; prepared manifests become stale as appropriate. Deletion is not undoable.

Reader layout follow-up: secondary actions now live under **Book tools** instead of three permanent action rows. Book title and chapter selector are single-line/ellipsized; chapter selection remains directly accessible. User reports one successful real-phone pronunciation test changing Eris to their preferred phonetic spelling; exact replacement was not supplied. This is encouraging user evidence, not broad pronunciation-quality validation.

Layout verification: 52 JVM tests plus assemble/lint passed (`compact-reader-build.log`). Opened and inspected `compact-reader.png` and `book-tools-menu.png`; verified the menu opens Pronunciation. At that historical layout update, additional engines were future work; on-device Kokoro has since been implemented (see [current work](current-work.md)).

- Reader → Pronunciation: manual Add/Edit/Delete, written phrase and spoken replacement, book/series/global scope, narrator preview. Series scope appears after assigning the book to a series. Boundaries are Unicode-letter/digit-aware, case insensitive; possessives remain intact. Replacements are one-pass, never cascading. Maximum entry lengths and final speech byte limits fail explicitly, never truncate source text.
- Reader → Series voices: create/join a series, create/edit voice profiles and manually link each book's detected speakers. Linked style/voice applies only when building speech; no series roster, alias or plot description is sent to analysis. Blank profile voice preserves the book's existing choice. Profile labels/style are user-authored and can themselves contain spoilers; use neutral labels. Existing model attribution is not reveal-safe.
- Reader → Prepare chapter offline: confirmed chapter-only generation, progress and cancellation, durable per-segment WAVs plus manifest in `files/books/{id}/offline/{chapter}`. Repeating preparation reuses matching WAVs. Incomplete manifests are never treated as fully prepared. Book deletion cancels preparation first. Remove saved audio is available inside the preparation dialog with a separate confirmation.
- Playback reads matching prepared manifests and durable audio without analysis/TTS calls. Content, relevant settings, book cast/overrides, applicable dictionary and linked profiles determine validity; token renewal, theme and buffer changes do not invalidate preparation. Changes may invalidate a manifest, but unchanged speech keys retain audio. Old versions remain available for reuse until explicit removal/book deletion.
- Export prefers the entire valid prepared chapter, even before playback, otherwise exports the current generated queue. WAV remains original-speed PCM. Keep the app open during preparation; no foreground worker/reboot job resumption yet. Interrupted runs resume completed segment files when requested again. Canceled shared character analysis may finish its in-flight work.

## TTS text-only incident

User reported paragraph starting “I slipped off my jacket”. Located chapter index 0, paragraph index 38 (223 characters). Its unchanged narration successfully generated/played about 14.7 seconds on real Vertex. Original failure cause cannot be recovered because old code discarded response metadata; success suggests intermittent output, not proof of a particular cause.

Vertex now retries non-blocked no-audio responses up to three total calls, without changing text/prompts. Errors distinguish provider block/finish reason. Explicit block reasons are not automatically retried or routed to another endpoint. Reader offers Retry failed speech, preserving current cached playback position. [Google response fields](https://cloud.google.com/vertex-ai/generative-ai/docs/reference/rest/v1/GenerateContentResponse) are the reference for diagnostic metadata. Mock `--text-only-count 3` recreates HTTP-success/no-audio behavior.

## Verification

- JVM suite: 52 tests, including boundary/scope/non-cascading pronunciation, series isolation, offline completeness/settings invalidation, no-audio metadata and identical-request retry. Assemble/lint pass.
- Emulator: manually entered Alice → Al-iss, edited/previewed; source still reads Alice and mock speech contains Al-iss. Created TestSeries/AliceProfile, manually linked Alice; mock TTS contains the custom `Measured and reflective` direction.
- Prepared eight-segment original-text dialogue chapter; stopped mock server, moved disposable cache aside, restarted app: all eight durable segments played with Alice/Captain Reed attribution. Disposable cache was restored afterward. Complete-chapter export confirmation showed eight segments.
- Complete prepared-chapter export saved through Android's document picker: 1,628,538 bytes, mono 24 kHz PCM, 33.926958 seconds, verified with ffprobe. Manifest contains eight segments, two with pronunciation substitutions. Screenshot `offline-export-saved.png` inspected.
- Canceled Alice chapter preparation from the UI: manifest remained incomplete with 54 planned segments, 18 completed WAVs retained. `offline-canceled.png` inspected. This checks normal cancellation, not abrupt process death.
- Requested preparation again; Alice completed all 54 segments with a complete manifest, retaining/reusing the earlier files. Latest tested APK installed successfully on ANDROID_DEVICE (`LOCAL_DEVICE_ADDRESS`) with `adb install -r`, then launched normally. Phone settings/books were not cleared or pointed at mocks.
- Forced three text-only responses on mock port 8767: app displayed STOP diagnostic and Retry failed speech; tapping retry generated/played the original passage to completion.
- Screenshots captured and inspected: `pronunciation-manual.png`, `series-create.png`, `offline-ready.png`, `offline-export-confirm.png`, `text-only-error.png`, `text-only-recovered.png`, `jacket-tts-test.png`, under gitignored `tools/artifacts/`.

Remaining: strict progressive/reveal-gated analysis, automatic cast continuity, explicit per-fact evidence, phoneme-level pronunciation guarantees, forced alignment after phonetic substitutions, and long-running/background preparation endurance. Abrupt mid-generation process-death stress testing is not yet complete. Sleep timer remains backlog.
