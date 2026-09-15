# Gemini Reader

Personal Android reader built with Compose and Media3. Package: `com.geminireader`. Android 8+ (API 26); target API 36, compile API 37.2. The handoff's pinned Compose, core, lifecycle and OkHttp versions require compile SDK 37 or later according to their AAR metadata. The emulator remains API 36.

## Local development

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

Access tokens expire, typically after about an hour. Renew the token and replace it in Settings. This version does not implement interactive Google sign-in or automatic token refresh on Android. Do not put a service-account private key in the APK. App-private settings hold the token; cloud backup and device transfer are disabled. This is a personal development app, not a credential distribution service.

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
- Analysis caches, the cast, metadata and reading position live under `files/books/{id}`. Settings use DataStore. Audio is an LRU cache keyed by engine, endpoint/project, model, voice, language, prompt, text and pause. Currently used/prefetched files are protected from eviction; they can temporarily exceed a very small cache limit. Clearing cache stops playback.

## Limits and verification

Live Vertex OAuth, text generation (`gemini-2.5-flash`), and speech generation (`gemini-3.1-flash-tts-preview`, Charon, 24 kHz PCM) succeeded in `us-central1` using the BROKER_HOST's user ADC and project `YOUR_PROJECT_ID`. Real audio also played on the emulator. This does not establish whether promotional credits cover the charges, broad attribution accuracy, or subjective voice quality. The headless emulator is muted. Vertex OAuth renewal requires a fresh token, entered manually or through the SSH helper above. See `VERIFICATION.md` for live character-attribution results.

EPUB spine/TOC and covers, text PDFs, TXT, HTML, Markdown, FB2 and DOCX are supported. This is a text-first reader: complex document layout, embedded illustrations (except covers), footnote navigation, and advanced Markdown formatting are simplified. PDF extraction quality depends on the document's text layer. Scanned PDFs need external OCR; MOBI/AZW3 and DRM are not supported.

The development checks include `./gradlew testDebugUnitTest assembleDebug lintDebug`, Gutenberg EPUB/TXT imports, generated fixtures for the other formats, mock Cloud/Vertex/Gemini playback, forced Gemini Interactions fallback, and screenshot inspection. Cache replay was checked with zero additional mock requests. See `VERIFICATION.md` for the final run and evidence paths.

## Toolchain

AGP 9.4.0 with built-in Kotlin, Kotlin Compose/serialization plugins 2.4.20, Gradle 9.7.1, JDK 21. The wrapper pins SHA-256 `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`; see [Gradle release checksums](https://gradle.org/release-checksums/). Kotlin serialization 1.11.0 is the stable release used rather than 1.12.0-RC.
