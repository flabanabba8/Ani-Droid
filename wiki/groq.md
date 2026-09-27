---
machine: build-host / Android
subsystem: groq-tts
last-verified: 2026-09-15
status: implemented; live English synthesis verified
---
# Groq speech

Settings → Speech engine → Groq uses `canopylabs/orpheus-v1-english` with Autumn, Diana, Hannah, Austin, Daniel and Troy. Troy is the initial Groq voice. The app supports Narrator and Distinct modes; Groq GPT-OSS 20B is the default Groq text provider for character analysis and requested rewrites; GPT-OSS 120B, Vertex and Gemini are also selectable. Narrator needs only a Groq key.

The user requested English only. Groq also hosts Saudi Arabic, but it is not exposed in this app. PlayAI/PlayHT was a different provider; Groq retired its PlayAI models and replaced them with Canopy Labs Orpheus. This integration cannot recover PlayHT custom voice clones.

## Requests and playback

- HTTPS POST to `https://api.groq.com/openai/v1/audio/speech`, separate bearer key, fixed English model and selected voice, WAV output.
- Sentence-aware passage planning caps segments at 200 UTF-16 units. The client also splits defensively after pronunciation replacements or for previews, preserves Unicode pairs and prefers word boundaries.
- Streaming WAV responses have unknown RIFF/data length markers. Normalize these markers, preserve metadata, then validate mono 16-bit PCM. Concatenate defensive chunks at the returned sample rate.
- Uses existing playback, buffering, cancellation, pronunciation, offline preparation and cache. Voice/model identify cached audio; rotating credentials does not invalidate it.
- Retries HTTP 429 and 5xx up to four attempts, with bounded backoff. Authentication, invalid requests and terms errors are shown without exposing raw provider response bodies. Redirects do not forward bearer credentials.
- Free-form Google performance instructions are not sent to Orpheus. English supports inline directions at the API level, but this initial UI offers fixed voices only.

## Spending and privacy

Settings records successful responses at the published English paid rate of $22 per million input Unicode characters, attributed to the active book. Previews appear under other usage. Cached playback adds no charge. Estimates exclude credits, taxes, requests without received responses and unknown custom endpoints; they are not invoices.

Text sent for Groq speech leaves the device. Google credentials are separate and retained across engine changes. No new proprietary SDK was added; HTTP uses the existing OkHttp dependency. F-Droid's optional non-free network service disclosure must include Groq.

## Development

`tools/pair-groq.sh ADB_SERIAL SSH_HOST [REMOTE_ENV_PATH]` imports GROQ_API_KEY from an environment file on an authorized SSH host into a debuggable app. It transfers the key through a pipe and app-private temporary file, deletes the file and waits for an acknowledgement after settings are saved. It preserves the current speech engine. Never print keys or commit environment files.

Live Android test is opt-in because it bills a short request:
```sh
adb -s emulator-5554 shell am instrument -w \
  -e class com.geminireader.tts.GroqOnDeviceTest -e groqLive true \
  com.geminireader.test/androidx.test.runner.AndroidJUnitRunner
```

Verified real English API output through Android: 24 kHz, nonempty PCM. A Saudi Arabic probe was refused with `model_terms_required`; no terms were accepted. JVM coverage exercises streamed WAV metadata/sentinels, malformed audio, Unicode limits, bearer/model requests, transient retries, authentication failure, per-book costs and cache identity.

Sources: [Orpheus models, voices, limits and pricing](https://console.groq.com/docs/text-to-speech/orpheus), [Groq changelog](https://console.groq.com/docs/changelog).

Verification: 90 JVM tests, debug app/test builds and Android lint passed. Live English Groq synthesis passed on the API 36 emulator. Settings UI verified English model and Troy voice. Installed the English-only APK on ANDROID_DEVICE (ANDROID_MODEL) over wireless ADB with install -r; private key import acknowledged after saving, selected engine preserved. User confirms Groq speech is fast and sounds good enough.

Emulator UI Save & test speech also passed: MediaSession reported PLAYING and the preview recorded one Groq response costing $0.001078 (49 characters).


## Groq character analysis

Select Groq as the text analysis provider and Distinct as the character mode. The same Groq key handles speech and text; no Google credentials are needed on this path. Model choices are `openai/gpt-oss-20b` (default) and `openai/gpt-oss-120b`. Requests use low reasoning effort and strict JSON schema through /openai/v1/chat/completions.

The existing chapter batching, saved checkpoints, speaker reassignment, editable cast and series profiles remain in use. Analysis recommends a Groq voice and explicit manual voice choices take priority. Model/provider and voice-catalog changes invalidate analysis caches; credentials affect in-flight sharing, not durable cached analysis. Incomplete/refused results do not become completed analysis. Existing narration fallback remains if analysis fails.

This provides speaker detection and distinct fixed voices. It does not implement Gemini-style free-form performance direction in Orpheus. Whole-chapter analysis retains the existing spoiler limitations.

Text usage is included in per-book spending, using reported prompt/completion tokens and reported cached-token discounts. Per million tokens: 20B input $0.075, cached input $0.037, output $0.30; 120B input $0.15, cached input $0.075, output $0.60. Missing usage remains unpriced. [Model pricing](https://console.groq.com/docs/models), [strict structured output](https://console.groq.com/docs/structured-outputs).

Live emulator test correctly attributed two short quotations to Alice and Bob, recommended supported voices, built the dialogue playback plan and reused saved analysis with the key removed. Google credentials were blank during this test. This is a small functional fixture, not an accuracy benchmark on novels.

Validation: 93 JVM tests and Android lint passed. Final APK passed live Groq character-analysis instrumentation in 1.996 seconds for the short fixture. Emulator Settings confirmed text provider Groq, Distinct mode and GPT-OSS 20B. This is functional evidence, not a long-chapter benchmark.

Installed the character-analysis update on ANDROID_DEVICE (ANDROID_MODEL) with wireless adb install -r. Enabled Groq text analysis. Post-install settings showed Vertex/Narrator selected, so those selections were preserved; choose Groq speech and Distinct mode to use Groq character voices. Groq key remained present.
