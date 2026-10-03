# PageCast privacy and data use

Updated September 30, 2026.

## On your device

PageCast stores imported book copies, reading/listening positions, character analysis, passage edits, pronunciation rules, series voice profiles, generated audio, settings and spending estimates in app-private storage. The source document selected for import is not edited. Kokoro speech is generated locally after an explicit model/runtime download. Once installed, Narrator mode needs no network or API key.

Credentials are stored in private app settings. This is Android app isolation, not a claim of separate encrypted credential storage. The manifest disables Android backup and its extraction rules exclude cloud backup and device transfer. The current app has no advertising, analytics or crash-reporting SDK configured.

## When text leaves the device

Google Drive imports use Android's document picker and the installed Drive app.
Drive handles Google sign-in and downloading the selected file; PageCast does
not receive your Google password or Drive account token. PageCast reads the
selected document into its local library. It does not upload books or reading
progress to Drive. The Drive app's network activity follows Google's policies.

Selecting Fish Audio sends speech text and voice IDs to Fish; the free tier may use requests for model improvement. Selecting Speechify sends speech text and voice IDs to Speechify. Selecting Inworld sends speech text and the selected voice ID to Inworld. Selecting Deepgram sends speech text and the selected English voice model to Deepgram; its account and model-improvement terms apply. Selecting Cartesia sends speech text and the selected voice ID to Cartesia. Selecting ElevenLabs sends speech text and the selected voice ID to ElevenLabs. Selecting Groq sends speech text and the selected voice to Groq. Selecting a Google speech engine sends the requested text (including spoken pronunciation replacements) and voice instructions to the configured provider. Character analysis sends chapter excerpts and relevant character context to the selected Google or Groq text model. Requested passage rewrites send text to that model even when speech uses Kokoro or Groq. Generation may continue ahead of playback to fill the buffer; chapter preparation generates the selected chapter. Provider processing and retention follow that provider's terms.

The optional user-configured token broker receives authentication requests and returns short-lived Google credentials; it does not synthesize speech or receive book text through its token endpoint. Its deployment is optional for local Kokoro narration. Custom endpoints receive requests made to them.

## Spending, deletion and export

Spending history stores month, provider/model, book ID/title, response counts and calculated estimates. It does not store full prompts, audio or credentials. Deleting a book retains its spending history, including its title. Estimates are not provider invoices.

Book deletion removes the app's imported book data; exports saved with Android's document picker remain in the chosen destination. Clearing Android app storage removes local settings/library/history and requires reconfiguration. Uninstalling also removes app-private data. Original documents and exported files outside app storage are unaffected.

## Permissions and development logs

Internet access supports configured cloud providers and token authentication. Foreground media playback and notification permissions support continued audio playback and media controls. Import/export use Android's document picker.

Development mocks can log book text and voice prompts. Do not publish those logs, private reading material, screenshots of credentials, or personal pairing files. Kokoro downloads occur only after explicit consent. The public GitHub download service receives the normal network request metadata, but no book text, speech audio or speech-provider credentials.

Android TTS delegates narration to the installed engine chosen in Settings. Offline-only mode excludes voices reported as requiring network access; network voices can send text to that engine provider. Voice downloads and provider behavior are controlled by the installed engine. Narrator mode sends no character-analysis requests. Optional passage rewrites still use the configured text provider.

Kokoro is now an optional post-install download: its model and native runtime are excluded from the APK. Settings provides Download, Cancel and Delete controls. Only the matching CPU architecture is fetched from public sherpa-onnx GitHub releases; the exact selective-8 model is prepared locally using a small bundled recipe. No custom hosting or speech API key is required. Downloads occur only after explicit consent; installed Narrator speech works offline. See the Kokoro wiki for details.

## Library tools and background preparation

Explicit library backups include imported book text, reading positions, saved
quotes/notes, character casts, series voice profiles and pronunciation rules.
They exclude application settings, credentials, broker configuration, generated
audio and models. Backups are not encrypted: the destination you choose through
Android's document picker (including a cloud provider) receives the archive.
Restoration adds new book copies and keeps existing books and credentials.
Selected quote export similarly writes only the quotes and notes you select.

The spoiler-safe reference searches only source paragraphs before the selected
reading position on this device. It does not use cloud models or future cast
analysis. Import cleanup and AAC audiobook encoding also run on this device.

Scheduled preparation can send selected book text to your configured speech and
analysis providers while PageCast is in the background. You explicitly choose
the chapter range and charging/network constraints before scheduling. Saved
queue metadata contains book/chapter IDs and a configuration hash, not API keys.
You can pause the queue from Book tools. A settings change requires requeueing.
