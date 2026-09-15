# Gemini Reader: handoff plan

Goal: an Android app like 11reader. It imports PDF, EPUB and other ebooks, shows the text, and reads it aloud
with Gemini TTS while highlighting along. It also detects which character is speaking and sends a per-line
style prompt, so the narrator "performs" each character. It also needs a local test/debug loop on this machine.

## State so far (nothing app-side written yet)
- `tools/setup-android-sdk.sh` exists. It installs a user-space JDK 21 (~/.jdks/temurin-21), Android SDK
  (~/android-sdk: platform-tools, platforms;android-36, build-tools;36.0.0, emulator,
  system-images;android-36;google_apis;x86_64) and creates AVD `reader`. No root needed. It was started in the
  background and may or may not have finished. Re-run it; it is idempotent.
- The Gradle 9.7.1 download was interrupted. Get it from https://services.gradle.org/distributions/gradle-9.7.1-bin.zip
  (sha256 acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a) and run `gradle wrapper`.
- Sample books: Project Gutenberg `https://www.gutenberg.org/ebooks/1342.epub3.images` (Pride and Prejudice, lots of
  dialogue), `/ebooks/11.epub3.images` and `/ebooks/11.txt.utf-8` (Alice). Put them in tools/samples (gitignore it).

## Machine facts
- Ubuntu 26.04, 16 cores, 28 GB RAM, RTX 4090 + AMD iGPU, Wayland session.
- /dev/kvm is readable/writable by user `curator` (ACL). sudo requires a password, so the agent cannot use root.
- binder_linux module exists but is not loaded. Waydroid is not installed.
- System java is 25. Use JDK 21 for Gradle. flite, ffmpeg and libreoffice are installed.

## Test environment decision: Android Emulator, not Waydroid
- The emulator needs no root and uses KVM. Waydroid needs root to install and to load binder.
- The emulator supports `-gpu host` on NVIDIA. Waydroid does not hardware-render on NVIDIA proprietary drivers.
- The emulator gives stock API 36, adb, snapshots and headless mode (`-no-window`) for automated checks.
- Also document wireless debugging to the real phone: `adb pair` / `adb connect`, then `./gradlew installDebug`.
  That removes APK shuffling on the real device too.

## Verified toolchain versions (Sept 2026)
AGP 9.4.0 (needs Gradle >= 9.6.0, JDK 17+), Gradle 9.7.1, Kotlin 2.4.20, Compose BOM 2026.09.00,
media3 1.11.1, activity-compose 1.13.0, navigation-compose 2.10.1, lifecycle 2.11.0, datastore-preferences 1.2.1,
core-ktx 1.19.0, okhttp 5.5.0, jsoup 1.23.2, pdfbox-android 2.0.27.0 (`com.tom-roush:pdfbox-android`),
kotlinx-coroutines 1.11.0, material-icons-extended 1.7.8 (explicit version, not in BOM), compileSdk/targetSdk 36, minSdk 26.
- AGP 9 has built-in Kotlin. Do NOT apply `org.jetbrains.kotlin.android`. Still apply
  `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` (2.4.20).
  Use `kotlin { compilerOptions { } }`, not `kotlinOptions`. If there is a KGP version mismatch, add
  `classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")` in the root buildscript.
- Check the latest stable kotlinx-serialization-json (1.12.0-RC was the newest tag; prefer the latest non-RC).

## Gemini APIs (verified from docs)
TTS voices (30). Female: Achernar, Aoede, Autonoe, Callirrhoe, Despina, Erinome, Gacrux, Kore, Laomedeia, Leda,
Pulcherrima, Sulafat, Vindemiatrix, Zephyr. Male: Achird, Algenib, Algieba, Alnilam, Charon, Enceladus, Fenrir,
Iapetus, Orus, Puck, Rasalgethi, Sadachbia, Sadaltager, Schedar, Umbriel. (Zubenelgenubi also exists.)

1. Cloud Text-to-Speech (bills Google Cloud credits; default engine)
   POST https://texttospeech.googleapis.com/v1/text:synthesize , header `x-goog-api-key: KEY`
   (docs show OAuth; API keys generally work. Verify, and fall back to documenting OAuth if not.)
   ```json
   {"input":{"text":"line","prompt":"style instructions"},
    "voice":{"languageCode":"en-US","name":"Charon","modelName":"gemini-3.1-flash-tts-preview"},
    "audioConfig":{"audioEncoding":"LINEAR16","sampleRateHertz":24000}}
   ```
   Response `audioContent` is base64 WAV. Limits: text <= 4000 bytes, prompt <= 4000 bytes.
   Models: gemini-3.1-flash-tts-preview, gemini-2.5-flash-tts, gemini-2.5-pro-tts, gemini-2.5-flash-lite-preview-tts.
2. Gemini API (AI Studio key; second engine)
   POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent , header x-goog-api-key
   ```json
   {"contents":[{"parts":[{"text":"<director's notes>\n#### TRANSCRIPT\n<line>"}]}],
    "generationConfig":{"responseModalities":["AUDIO"],
      "speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Charon"}}}}}
   ```
   Audio is at candidates[0].content.parts[].inlineData.data. It is raw s16le mono PCM, and the rate is in the mimeType (24000).
   Newer docs show `/v1beta/interactions` with `response_format:{type:"audio"}` and `output_audio.data`.
   Support either one if generateContent fails.
   Prompt structure from docs: `# AUDIO PROFILE`, `## THE SCENE`, `### DIRECTOR'S NOTES`, `#### TRANSCRIPT`.
   Inline tags like [whispers] and [laughs] are allowed. Vague prompts can get read aloud.
   Occasional 500s happen when the model returns text, so retry.
3. Character analysis uses a text model over generateContent with `responseMimeType: application/json` + `responseSchema`.
   The models page lists gemini-3.5-flash, gemini-3.5-flash-lite, gemini-2.5-flash, among others.
   Make the model a free-text setting with a "fetch models" button (GET v1beta/models).

## Architecture to build (package com.geminireader, single :app module, Compose + Media3)
- data/: kotlinx-serialization JSON files in filesDir/books/{id}/: meta.json, content.json (chapters -> paragraphs),
  position.json, cast.json (characters), analysis/{chapter}.json. Settings go in DataStore.
- importer/: EPUB (zip -> container.xml -> OPF spine; titles from nav.xhtml/NCX; jsoup for block text; cover image),
  PDF (pdfbox-android PDFTextStripper; reflow lines into paragraphs; de-hyphenate; chapters from outline, else
  20-page groups; cover via android PdfRenderer; call PDFBoxResourceLoader.init), TXT/MD, HTML, FB2 (XML), DOCX
  (word/document.xml). MOBI/AZW3 are out of scope; say so in the UI.
  Add ACTION_VIEW/ACTION_SEND intent filters and the SAF picker.
- text/Segmenter: split paragraphs into narration/dialogue spans. Handle curly “ ”, straight ", ‘ ’ (not
  apostrophes), « », „ “. Pick the dominant quote style per chapter. Unclosed quotes run to the end of the paragraph.
  Split sentences for highlighting. Split long chunks at sentence boundaries (~1200 chars).
- analysis/CharacterAnalyzer: per chapter (chunk at ~40k chars), send paragraphs with `<q id="p.n">…</q>` tags plus
  the known roster and the narrator voice gender. Get JSON back: characters[{id,name,aliases,gender,age,description,voiceStyle}],
  lines[{q,speaker|unknown,delivery}]. Merge into the roster. voiceStyle = how THIS narrator should shift voice
  (pitch/pace/timbre/accent). Run automatically before a chapter plays, with "Analyzing…" status and fallback to narrator-only
  on failure. Add an "analyze whole book" button too.
- analysis/VoiceDirector: build voice + prompt per segment.
  Mode A (default) keeps the narrator voice and adds a performance prompt, e.g. a male narrator doing a higher, lighter voice for a female character.
  Mode B gives each character a distinct voice from the gender-matched pool.
  Narrator-only mode uses paragraph-level segments. Unattributed dialogue is merged into narration.
- tts/: TtsEngine interface, CloudTtsClient, GeminiApiTtsClient, WAV parse/write, retries on 429/5xx with backoff,
  and readable errors (bad key, API not enabled, quota). Trim leading/trailing silence, then add controlled pauses
  (~80 ms within a paragraph, ~350 ms between paragraphs). Disk cache keyed by sha256(engine|model|voice|lang|prompt|text|pad)
  with an LRU size limit, so replay costs nothing.
- playback/: PlaybackEngine (app-scoped ExoPlayer, speech audio attributes, audio focus, noisy handling).
  Keep a per-chapter segment list and prefetch the next N segments (concurrency 2).
  Append ready WAVs to the playlist in order. If the player ENDED while waiting, seekTo the new item and play.
  mediaId = "generation:index" to ignore stale items. Auto-advance chapters, save position, set speed via PlaybackParameters.
  PlaybackService extends MediaSessionService and wraps the engine player (notification + lock-screen controls).
  Permissions: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS.
- ui/: Library (import FAB, covers, progress, delete).
  Reader: LazyColumn of paragraphs; highlight the current segment plus the estimated sentence
  (position/duration mapped to char offset); auto-follow with a "follow" button; tap a paragraph to play from it;
  long-press to see speaker/prompt and reassign the speaker; bottom player bar with speed and a TOC drawer.
  Characters: edit voiceStyle/voice, preview button.
  Settings: API key + Test, engine, TTS model, language, narrator voice + preview, narrator prompt,
  character mode, analysis model, prefetch, pauses, font size/theme, cache size/clear, and advanced base-URL overrides.

## Local dev loop to build (tools/)
- env.sh: exports JAVA_HOME, ANDROID_HOME, PATH.
- emulator.sh: `emulator -avd reader -gpu host` (windowed) or `--headless` (`-no-window -no-audio`). Wait for `sys.boot_completed`.
- dev.sh: `./gradlew installDebug`, launch the activity, `adb logcat --pid`.
- push-book.sh: `adb push book /data/local/tmp/`, then
  `adb shell 'cat /data/local/tmp/x | run-as com.geminireader sh -c "cat > files/debug-import/x"'`, then
  `am start ... --es debug_import x`. The debug build only honors this extra. Also add a debug intent that sets settings (base URL, key).
- mock-server/mock_gemini.py: implements text:synthesize and generateContent (audio via flite -> 24 kHz PCM,
  JSON attribution via a "said Name" regex). It logs every prompt received. Point the app at http://10.0.2.2:8765.
  Allow cleartext only in the debug network security config. This tests end to end without spending credits.
- screenshot.sh: `adb exec-out screencap -p`. Use `uiautomator dump` + `input tap` for scripted UI checks.
- JVM unit tests: Segmenter, PDF reflow, EPUB parser (build a zip in the test), WAV, API JSON parsing, prompt building.

## Order of work
1. Finish SDK + Gradle wrapper, then scaffold and get an empty Compose app building (`./gradlew assembleDebug`).
2. Importers + library + reader text display (test with the samples on the emulator).
3. TTS clients + cache + playback engine/service + highlighting (test against the mock server).
4. Segmenter + analyzer + voice director + characters screen.
5. Settings polish, README (API key setup: enable Cloud Text-to-Speech API + Generative Language API on the
   credits project, create a key restricted to those two APIs), wireless-adb instructions, commit.
