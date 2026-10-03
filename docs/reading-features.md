# Reading and listening additions

Implementation order follows the requested feature list. These changes are in
the working development version; the F-Droid submission still targets 0.2.1.

1. **Characters:** lock a voice and delivery, adjust expression and edit the shared
   audition line. Save pins an automatic voice choice. A locked character voice overrides Performance mode; Narrator mode remains a single voice. Series voice profiles carry
   stable delivery and expression across manually linked books. Direction controls
   apply to engines that accept prompts; they cannot guarantee identical acting
   from a generative provider. Voice selection still works for other engines.
2. **Book tools → Sleep timer:** duration (1–240 minutes), paragraph or chapter,
   optional last-15-second fade. The timer lives in the running playback process.
3. **Book tools → Playback recovery:** optionally skip after available retries;
   the local repair list retains missing text for later retry. Default is off.
4. **Book tools → Prepare chapters overnight:** queue a chapter range or whole
   book. Android schedules work and retains progress across interruption/reboot.
   Saved segments are reused. Only one queue exists at a time. API costs are unknown
   unless you supply a character-based rate; that estimate excludes analysis and
   retries. Android 8 requires unmetered Wi-Fi for the Wi-Fi option. Settings changes
   pause resumption until you explicitly requeue. Android may defer jobs.
5. **Settings → Library backup:** save to any document provider, including Drive;
   restore into new book copies. Explicit typed fields exclude settings, all API
   credentials, broker configuration and generated audio. Backups are unencrypted,
   limited to 256 MB uncompressed and 1,000 books. Restore preserves existing data.
6. **Book tools → Spoiler-safe character reference:** double-tap a word in the reader, tap a suggested name, or type
   a phrase to inspect up to 30 earlier source excerpts. Suggestions are text
   matches, not model-generated identities. The current paragraph and later text
   are excluded. Whole-chapter analysis elsewhere is still not spoiler-safe.
7. **Reader transport:** previous/next sentence and ±15 seconds within buffered
   audio. Sentence timing is estimated from text length. Resume after five minutes
   rewinds five seconds when the same cached audio is available.
8. **Book tools → Export audio:** chapter WAV or AAC M4A/M4B of fully prepared
   chapters/the whole book. No new synthesis occurs. AAC uses the platform encoder,
   64 kbps mono, original playback speed, title/author/cover metadata and Nero
   chapter markers (player support varies). One export supports up to 255 chapters;
   all included WAVs must have the same sample rate. Current prepared signatures
   must match; voice changes may require preparation again.
9. **Import review:** opt-in removal of standalone page numbers, numeric footnote
   markers, a selected repeated header, and joining wrapped lines within existing
   paragraphs. Shows the first 30 changes and the total changed count. Keep the
   original text, accept cleanup, or cancel. Source files are untouched.
10. **Book tools → Bookmark current sentence / Bookmarks and quotes:** bookmarks
    use the current estimated spoken sentence. Long-press text to save a passage.
    Add/edit notes, return to the source, and export selected quotes with chapter
    references. Saved quotes survive later source edits and show a change warning.

Debug/release builds and release lint pass. 141 JVM tests and six emulator
instrumentation tests pass, covering voice modes/locks, source boundaries,
cleanup, notes, backup/restore, AAC metadata, sleep boundaries, skipped playback
and queued preparation. Long overnight/reboot scheduling and other audiobook
players still need real-world coverage.

Canary follow-up: the Google TTS model selector is a dropdown with provider-specific
model IDs and compatible fetched catalog choices. Playback shows the requested
voice ID so it can be distinguished from the perceived vocal performance.

Distinct is the default character mode for new settings: narration uses the
selected narrator and attributed dialogue uses character voices. Existing saved
mode choices are preserved unless explicitly changed.
