# PageCast releases

## 0.2.1

- Add guided Google Drive importing through Android’s document picker.
- Remove personal deployment identifiers from documentation and helper defaults.
- Ignore local credentials and signing files.

## 0.2.0

- Prepare the first public release for F-Droid review under GPL-3.0-or-later.
- Compile Sherpa's Kotlin wrapper from pinned Apache-2.0 source. Normal APK
  builds no longer download a precompiled Sherpa AAR or convert Kokoro models.
- Document optional native/model downloads and proprietary cloud services.
- Retry temporary playback generation failures without cancelling earlier audio.
- Keep sentence punctuation with speech and allow dismissal of rejection markers.
- Offer Gemini 3.1 Flash-Lite analysis with independent Vertex endpoint routing.

The Android ID remains `com.geminireader`. Canary is a separate development app.

## Unreleased

1. Character voice/delivery locks, expression control, editable audition line and reusable series delivery profiles.
2. Sleep timer by duration, paragraph or chapter, with optional fade.
3. Opt-in skipping after speech retries and a persistent repair list.
4. Persistent multi-chapter preparation queue with charging/Wi-Fi conditions and user-supplied cost estimates.
5. Library backup and restore through the document picker; credentials, broker configuration and generated audio are excluded.
6. Spoiler-safe reference using earlier source excerpts only.
7. Sentence and 15-second seeking, plus a five-second rewind after a five-minute pause.
8. AAC M4A/M4B export of prepared chapters/books, including chapter markers and cover metadata.
9. Optional import cleanup with a before/after preview.
10. Sentence bookmarks, saved quotes, editable notes and selected quote export.
