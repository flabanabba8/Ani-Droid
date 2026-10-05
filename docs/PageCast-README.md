# PageCast

Free software under **GPL-3.0-or-later**. See [LICENSE](LICENSE), [COPYRIGHT](COPYRIGHT),
and the [0.2.0 release notes](CHANGELOG.md). Public source: https://github.com/flabanabba8/PageCast.

Previously Gemini Reader. The Android package remains `com.geminireader` so upgrades retain books, settings, and audio. Existing workspace paths and token-broker service names remain unchanged for compatibility.

Android reader built with Compose and Media3. Package: `com.geminireader`. Android 8+ (API 26); target API 36, compile API 37.2. The handoff's pinned Compose, core, lifecycle and OkHttp versions require compile SDK 37 or later according to their AAR metadata. The emulator remains API 36.

## Current capabilities and release plans

Read and listen with optionally downloaded on-device Kokoro (28 English voices) or optional Google speech services. Includes passage editing, explicit rejected-passage retry/rewrite, per-book spending estimates, pronunciation rules, series voices and offline chapter preparation. Kokoro Narrator works offline after installation; character analysis and requested rewrites still use Google.

See [current implementation](wiki/current-work.md), [passage recovery](wiki/passages.md), [spending](wiki/spending.md), [privacy](PRIVACY.md), and [verification](VERIFICATION.md).

**F-Droid is the intended release channel.** Release 0.2.0 includes source-built
Kokoro bindings, listing assets and proposed build metadata. Inclusion and the
optional model/runtime download design remain subject to F-Droid review. See the
[F-Droid release plan](wiki/fdroid.md) and [Kokoro provenance](docs/kokoro-source.md).

## Development additions

The current development build adds ten reading and listening features, including
voice locks, a sleep timer, background preparation, credential-free library
backup, spoiler-safe references, audiobook export and notes. See
[feature locations and limits](docs/reading-features.md). These additions passed unit tests, emulator checks and release lint; see the
feature guide for the tested scope and remaining real-world coverage.

## Import from Google Drive

Choose **Import book → From Google Drive → Browse Drive files**. In Android's
file picker, open the navigation menu, choose Drive and your account, then select
the book. Install/open the Google Drive app and sign in if Drive is not listed.
PageCast imports a local copy for offline reading; it does not edit the Drive
file or sync reading progress. Cloud files may need an internet connection.
This uses Android's document provider support without a Google SDK or Google
account credentials in PageCast.

## Local development

Project knowledge base: [LLM wiki](wiki/README.md), [complete feature backlog](wiki/features.md), [current milestone](wiki/current-work.md).

Normal APK builds compile the vendored Kotlin Kokoro bindings directly and use
the checked-in model download catalog and conversion recipe. They do not download
a Sherpa AAR or model weights. Python 3.11+ and `uv` are only needed by maintainers
regenerating those model resources; see [provenance](docs/kokoro-source.md).

No system packages or sudo are needed. The setup script installs Temurin JDK 21 and the official Android SDK in your home directory. System Java 25 is not used.

```bash
./tools/setup-android-sdk.sh
source tools/env.sh
./tools/emulator.sh --headless
```

Omit `--headless` for an emulator window. The default GPU is `host`; use `EMULATOR_GPU=swiftshader ./tools/emulator.sh --headless` if the host driver fails. `/dev/kvm` must be accessible. With multiple devices connected, set `ANDROID_SERIAL` for adb operations.

```bash
source tools/env.sh
./gradlew testDebugUnitTest assembleDebug
./gradlew installDebug
adb shell am start -n com.geminireader/.MainActivity
./tools/samples.sh
./tools/push-book.sh "$PWD/tools/samples/alice.epub"
./tools/screenshot.sh
```

`tools/dev.sh` builds, installs, launches, then follows the app's logcat. Screenshots and emulator logs live in ignored `tools/artifacts/`. Gutenberg downloads live in ignored `tools/samples/`.

## Offline mock

The mock uses the installed `flite`, `ffmpeg`, and Python 3. It performs simple regex attribution and produces audible local speech. It does not simulate Gemini's voice quality or intelligence. Logs contain book text and style prompts; they exclude authentication headers.

```bash
python3 -u tools/mock-server/mock_gemini.py 2>&1 | tee /tmp/gemini-reader-mock.log
```

In a second terminal, after installing the debug APK:

```bash
source tools/env.sh
./tools/use-mock.sh vertex
./tools/push-book.sh "$PWD/tools/samples/alice.epub"
```

Open **Contents**, select Chapter I, and tap a paragraph or Play. The mock setup helper fills the debug token, project, and all base URLs without real credentials. Use `./tools/use-mock.sh cloud` or `./tools/use-mock.sh gemini` to exercise the optional engines. For the Interactions fallback, start the server with `--force-interactions` and select `gemini`.

The Android emulator reaches the host through `http://10.0.2.2:8765`. The server binds loopback by default. Add `--force-interactions` to test the Gemini endpoint fallback. HTTP is enabled only in debug builds.

`python3 tools/create-fixtures.py` generates small PDF, DOCX, FB2, HTML, Markdown and TXT samples under `tools/samples/generated/`. `python3 tools/mock-server/test_mock.py` checks the mock's attribution and audio. `python3 tools/ui-tap.py 'Settings'` taps a visible UI label through uiautomator.

## Vertex AI and your Google Cloud credits project (default)

**Vertex AI is the default for both TTS and character analysis.** Both calls use the project and region in Settings, with `Authorization: Bearer` and `x-goog-user-project`. There is no AI Studio dependency in Vertex mode. Model access and whether your particular credits cover its usage must be confirmed in your billing account.

1. Select the Google Cloud project attached to your credits/billing account.
2. Enable the Vertex AI API (`aiplatform.googleapis.com`). Your signed-in account needs Vertex AI User (`roles/aiplatform.user`, including `aiplatform.endpoints.predict`) and permission to use that project's services.
3. In a machine with the Google Cloud CLI configured, run:

   ```bash
   gcloud auth login
   gcloud config set project YOUR_CREDITS_PROJECT_ID
   gcloud auth print-access-token
   ```

4. In the app's Settings choose `vertex`, enter the **project ID**, region `us-central1`, and the resulting **OAuth access token**. Leave the advanced Vertex URL blank for Google's regional endpoint. Save and test speech.
5. Default speech model: `gemini-3.1-flash-tts-preview`; default analysis model: `gemini-2.5-flash`. Both names are editable. Fetch models queries the selected provider's catalog; availability can vary by region and account.

Access tokens expire, typically after about an hour. **Automatic renewal is available through the BROKER_HOST's private LAN broker**: install the current debug APK and run `ANDROID_SERIAL=PHONE_IP:DEBUGGING_PORT tools/pair-token-broker.sh`. The phone receives short-lived tokens over pinned HTTPS; Google refresh credentials stay on BROKER_HOST. BROKER_HOST must be awake/reachable for renewal. See [authentication setup, security and recovery](wiki/authentication.md). Manual tokens remain available, but require replacement when they expire. No service-account private key belongs in the APK. App backup/device transfer are disabled; interactive phone Google sign-in is not implemented.

For this debug emulator, refresh directly from the BROKER_HOST's existing Application Default Credentials without printing or copying its long-lived credentials:

```bash
tools/use-vertex-ssh.sh USER@BROKER_HOST YOUR_PROJECT_ID
```

This stops the app, transfers a short-lived access token over SSH/adb into app-private storage, and selects the real Vertex endpoint. The temporary import file is deleted after reading. The token remains in private settings until replaced; rerun the command when it expires. This helper requires an installed debug APK, authorized adb, and working SSH authentication. The BROKER_HOST's active gcloud CLI account differs from its user ADC, so this helper deliberately uses `gcloud auth application-default print-access-token`.

The Vertex endpoint is `/v1beta1/projects/PROJECT/locations/REGION/publishers/google/models/MODEL:generateContent`. The app parses Gemini's raw PCM audio, wraps it as WAV, and uses the same schema-based text-analysis request on Vertex. See Google's [Vertex TTS request examples](https://docs.cloud.google.com/text-to-speech/docs/gemini-tts) and [Vertex authentication quickstart](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/start/quickstart).

## Optional Cloud TTS and Gemini API engines

Select your Google Cloud project that has the credits and a billing account. Enable Cloud Text-to-Speech API (`texttospeech.googleapis.com`) and Generative Language API (`generativelanguage.googleapis.com`). Create an API key restricted to these APIs in **APIs & Services → Credentials**. Confirm that the key/AI Studio project is the intended billing project; eligibility for credits and model access depends on your account and offer.

For the optional Cloud Text-to-Speech engine, the app tries `x-goog-api-key`. **API-key authentication for Cloud Gemini TTS has not been verified with a real key.** Google's [Cloud Gemini TTS documentation](https://docs.cloud.google.com/text-to-speech/docs/gemini-tts) shows OAuth bearer authentication and requires `aiplatform.endpoints.predict` (for example, the Vertex AI User role). If your key gets 401/403, use the advanced Cloud OAuth token/project settings. Obtain a short-lived token with `gcloud auth application-default print-access-token` after configuring gcloud for your project, and enter the project ID for `x-goog-user-project`. Character analysis in Cloud/Gemini modes uses the Gemini API key. In Vertex mode it uses the Vertex token and project instead.

The optional Gemini API engine first tries `models/{model}:generateContent`, then the newer `v1beta/interactions` audio endpoint for unsupported requests or missing audio. Google's [speech generation documentation](https://ai.google.dev/gemini-api/docs/speech-generation) now demonstrates Interactions. Neither live endpoint has been verified here. Mock verification covers request shapes, parsing, playback, and the fallback; it cannot establish production authentication, billing, quota, or model availability. Vertex mode retries Vertex failures and does not switch to another billing provider.

## Install to a phone over Wi-Fi

Enable Developer options and **Wireless debugging** on an Android 11+ phone. Put the phone and computer on the same network. Select **Pair device with pairing code**; pairing and connection ports are usually different.

```bash
source tools/env.sh
adb pair PHONE_IP:PAIRING_PORT
# Enter the code displayed by the phone.
adb connect PHONE_IP:DEBUGGING_PORT
adb devices
./gradlew assembleDebug
adb -s PHONE_IP:DEBUGGING_PORT install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PHONE_IP:DEBUGGING_PORT shell am start -n com.geminireader/.MainActivity
```

To use the host mock from the phone, select that device with `export ANDROID_SERIAL=PHONE_IP:DEBUGGING_PORT`, run `adb reverse tcp:8765 tcp:8765`, then `./tools/use-mock.sh vertex http://127.0.0.1:8765`. Keep the adb connection active.

## Reading and character performance

- Import with **Import book**, Android's Open/Share actions, or the debug push script. The app stores its own copy of extracted text; the original document can be moved afterward.
- Tap a paragraph to play from there. Play/Pause, speed, chapter navigation, and follow controls are below the text. Android media controls remain available during background playback. Yellow highlighting estimates sentence timing from audio duration; it is not word-level forced alignment.
- Each chapter's quoted passages get stable IDs. A Gemini text model returns schema-constrained JSON assigning speakers and delivery; ordinary Kotlin code builds the TTS prompts. This uses structured output, not LLM function calling.
- **Performance** keeps one narrator voice and directs character-specific changes in pitch, pace, timbre and delivery. **Distinct** picks a gender-matched voice, with per-character overrides. **Narrator** skips analysis. Failed analysis falls back to narration and shows the reason.
- All 30 voices have researched gender/trait profiles shown in the voice selectors. Analysis receives this catalog and recommends a voice for distinct mode; manual overrides take precedence. Directions adapt pitch relative to the actual selected voice, not a stale narrator-gender setting. See [voice research and direction](docs/voices.md) for the complete catalog, sources and limitations.
- Open **Characters** to edit a performance or voice, preview it, or analyze the whole book. Long-press reader text to inspect quote assignments and prompts, then choose a speaker. Manual assignments are stored separately and survive reanalysis.
- Long-press a paragraph and choose **Edit passage** to change its text, then **Save** or **Cancel**. Edits persist in the imported copy and are used for speech. Blank passages cannot be saved. Saving stops playback and offline preparation, resets chapter analysis, and keeps manual speaker assignments only for unchanged quotes. Prepare the chapter again to update offline audio.
- Analysis caches, the cast, metadata and reading position live under `files/books/{id}`. Settings use DataStore. Audio is an LRU cache keyed by engine, endpoint/project, model, voice, language, prompt, text and pause. Currently used/prefetched files are protected from eviction; they can temporarily exceed a very small cache limit. Clearing cache stops playback.

### Analysis, chunking and paragraph jumps

- Character analysis runs for the current chapter, not the whole book (unless you choose **Analyze whole book**). It sends batches of roughly 6,000 tagged characters with at most 24 quote spans, plus the voice catalog, roster and a small preceding-context excerpt. Successful batches are saved immediately and reused after errors or restarts. The reader shows batch progress. **Characters → Analyze this chapter / retry** prepares just this chapter and resumes saved batches.
- Playback waits for the chapter's analysis before preparing character-directed audio. Gemini 2.5 Flash/Flash-Lite analysis disables optional thinking to reduce latency. Model selection is a dropdown; fetched text-model names extend the built-in choices. Availability still depends on project/region.
- TTS is incremental: each narration/dialogue span is split at sentence boundaries where possible, up to about 1,200 characters per audio request. Two requests may run concurrently. **Audio buffer** sets a duration target (default 120 seconds; 15–600 configurable). The reader shows actual queued time remaining, adjusted for speed. It is best effort, can overshoot by pending segments, and continues filling while paused. Audio is cached as each request completes, not rendered for the whole book at once.
- Tapping a paragraph already in the prepared queue seeks directly without restarting analysis or discarding the queue. Other jumps reuse completed audio and shared in-progress analysis, but may still need to generate uncached speech. Unfinished TTS requests outside the retained queue can be canceled by a restart. A recently failed analysis is held for 30 seconds to avoid repeated requests on every tap; the chapter retry button bypasses that cooldown while preserving checkpoints.
- Analysis now survives cancellation of a playback waiter during a jump and finishes saving the requested chapter. Deleting the book cancels its analysis. Changing voice/model/backend creates separate cache identities.
- The HTTP client explicitly allows 120 seconds without response data and a 150-second total call deadline; timeouts get at most one retry. The old client inadvertently retained a 10-second socket read timeout despite a longer overall deadline.
- New installs default to dark mode. Existing theme choices remain saved. **Settings → Appearance** is the first setting; dark/light/system changes apply and save immediately without stopping playback or needing the Save button.

## Limits and verification

**Resume:** reopening the app returns to the last book's listening location without automatically playing. Audio offset and speed are saved separately from scrolling, every two seconds and on pause/background transitions. Unchanged cached audio restores the millisecond offset; after eviction/regeneration or voice changes, playback safely restarts the saved segment. Sentence highlighting remains estimated. Sudden process death may lose up to the periodic-save interval.

**Export:** choose **Export audio → Save WAV**, then save with Android's document picker. A matching fully prepared chapter exports in full; otherwise export includes only the generated current queue, potentially a partial chapter. It includes entire segments, not just audio remaining after the playhead. WAV is mono 16-bit PCM at original 1× speed with generated pauses. Export itself makes no synthesis requests; normal buffering can continue. Temporary disk space is required. Compressed M4A and chapter markers are not implemented.

### Kokoro speech

Choose **Settings → Speech engine → kokoro** for speech generated directly on your Android phone. After tapping Download Kokoro, the installed model supports 28 English voices, narrator/distinct modes, audio caching, offline preparation and WAV export. Narrator mode works offline without a server or API key. See [Kokoro setup and verification](wiki/kokoro.md).

### Spending estimates

**Settings → Spending estimate** shows recorded total, this month, and per-book costs for speech and character analysis. Tracking starts with this version and persists on this device. Previews without book context are listed separately. Estimates use published paid rates before credits/free tiers/taxes; unknown rates and missing usage are flagged. Cached playback adds no cost. Open Google Cloud billing from the card to check actual charges. See [accounting details and pricing sources](wiki/spending.md).

### Offline chapters, pronunciation and series voices

Reader tools are grouped under **Book tools** beside the chapter selector. Open that menu for Characters, Pronunciation, Series voices, Export audio, and preparation/cancellation. The title and chapter selector now occupy two compact lines instead of a stack of action rows.

- **Prepare chapter offline** explicitly generates and retains the current chapter. Confirm the potentially billed work; keep the app open. You can cancel and retry, reusing completed segments. A fully prepared matching chapter plays without network and exports in full. Voice/dictionary changes require preparing again, but unchanged speech is reused. Temporary plus retained disk space is needed. Use **Prepare chapter offline → Remove saved chapter audio…** to reclaim storage; exported files are unaffected.
- **Pronunciation** has manual written-word/phrase and “Speak as” fields, Add/Edit/Delete, narrator Preview, and book/global/series scopes. Series scope requires a linked series. Whole-word/phrase replacements affect only TTS; no source text is modified. A phonetic spelling is a hint, not a guaranteed phoneme sequence. Preview may incur synthesis charges.
- **Series voices** lets you create/join a series, create/edit voice profiles and explicitly link a detected speaker in each book. Only user-authored performance/voice data is reused; no model-generated future-volume aliases, descriptions or relationships are imported. This avoids automatic identity merging, **not all spoilers**: current analysis still reads the whole chapter. Strict reveal-gated analysis is deferred. See [design and verification](wiki/next-milestone.md).
- Failed generations (text instead of audio, missing audio data, or empty PCM/WAV) automatically retry with unchanged text and prompts: up to three generation attempts for Vertex and Cloud TTS, or two on Gemini generateContent followed by up to three on the Interactions fallback. Backoff starts at 0.5 seconds and doubles; stopping playback cancels backoff. HTTP retries remain separately bounded (up to four attempts for rate limits/server errors, two for timeouts). Explicit content blocks are not automatically retried or sent to the fallback endpoint. Exhausted attempts retain **Retry failed speech**. Malformed audio and configuration errors are surfaced instead of blindly retried. The reported jacket paragraph succeeded unchanged in a live smoke test, so its original failure was not conclusively diagnosed.
- **Retry generation** retries a rejected paragraph unchanged. It appears beside **Rewrite rejected passage** and directly in the long-press menu. Asterisk scene breaks such as `***` are skipped during speech.
- **Rewrite rejected passage** opens the passage editor from a red rejection marker. Choose **Suggest milder wording** to use the selected text analysis model, review the suggestion (or **Show original**), and **Save** to apply it. Cancel keeps the book unchanged. Rewrite requests may incur costs and are included under the book in spending estimates. Rewrites do not guarantee speech acceptance; a refusal or incomplete rewrite keeps your draft unchanged.
- Provider-blocked book passages are highlighted red with the provider's reason, including failures during prefetch or offline preparation. **Show rejected passage** jumps to the first marked passage in the current chapter. Markers persist across app restarts and clear after successful generation of the marked span. They identify the requested passage, not a specific offending word or proof that the text is explicit. Older failures cannot be reconstructed retroactively. Voice/settings previews without a book location are not marked. Test using the mock server's `--block-audio` flag (Gemini/Vertex audio requests).

Live Vertex OAuth, text generation (`gemini-2.5-flash`), and speech generation (`gemini-3.1-flash-tts-preview`, Charon, 24 kHz PCM) succeeded in `us-central1` using the BROKER_HOST's user ADC and project `YOUR_PROJECT_ID`. Real audio also played on the emulator through broker-backed auth. This does not establish whether promotional credits cover the charges, broad attribution accuracy, or subjective voice quality. The headless emulator is muted. See `VERIFICATION.md` for results.

EPUB spine/TOC and covers, text PDFs, TXT, HTML, Markdown, FB2 and DOCX are supported. This is a text-first reader: complex document layout, embedded illustrations (except covers), footnote navigation, and advanced Markdown formatting are simplified. PDF extraction quality depends on the document's text layer. Scanned PDFs need external OCR; MOBI/AZW3 and DRM are not supported.

The development checks include `./gradlew testDebugUnitTest assembleDebug lintDebug`, Gutenberg EPUB/TXT imports, generated fixtures for the other formats, mock Cloud/Vertex/Gemini playback, forced Gemini Interactions fallback, and screenshot inspection. Cache replay was checked with zero additional mock requests. See `VERIFICATION.md` for the final run and evidence paths.

## Toolchain

AGP 9.4.0 with built-in Kotlin, Kotlin Compose/serialization plugins 2.4.20, Gradle 9.7.1, JDK 21. The wrapper pins SHA-256 `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`; see [Gradle release checksums](https://gradle.org/release-checksums/). Kotlin serialization 1.11.0 is the stable release used rather than 1.12.0-RC.

## Groq TTS

Groq Orpheus English is available in Settings, with six voices, a separate API key, Narrator/Distinct modes, and per-book spending estimates. Groq-hosted GPT-OSS 20B (default) or 120B handles character analysis, voice recommendations and requested rewrites with the same key. See [Groq setup and limitations](wiki/groq.md).

## ElevenLabs

Eight preset voices plus custom voice ID entry for narrator, characters and series profiles. Three speech models and optional Groq character analysis. See [ElevenLabs setup](wiki/elevenlabs.md).

## Cartesia

Sonic 3.6 with six presets, custom voice IDs and optional Groq character analysis. See [Cartesia setup and development budget](wiki/cartesia.md).

## Deepgram

English Flux TTS, six preset voices plus other voice models, optional Groq character analysis and per-book spending estimates. See [Deepgram setup](wiki/deepgram.md).

## Inworld

TTS-2 speech with six presets, custom voice IDs, optional Groq character analysis and per-book spending estimates. See [Inworld setup](wiki/inworld.md).

## Speechify

English Simba 3.2, six presets plus custom voice IDs, Groq character analysis and per-book usage estimates. See [Speechify setup](wiki/speechify.md).

## Fish Audio

S2.1 Pro Free with six official English presets, custom IDs, Groq character analysis and per-book request tracking. See [Fish Audio setup](wiki/fish.md).

## Android speech engines

Use an installed Android TTS engine and English narrator voice. Offline voices are selected by default; no speech API key is needed. See [Android TTS setup](wiki/android-tts.md).

Kokoro is now an optional post-install download: its model and native runtime are excluded from the APK. Settings provides Download, Cancel and Delete controls. Only the matching CPU architecture is fetched from public sherpa-onnx GitHub releases; the exact selective-8 model is prepared locally using a small bundled recipe. No custom hosting or speech API key is required. Downloads occur only after explicit consent; installed Narrator speech works offline. See [Kokoro](wiki/kokoro.md).
