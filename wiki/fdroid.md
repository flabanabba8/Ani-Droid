---
machine: build-host / Android
subsystem: F-Droid release
last-verified: 2026-09-15
status: planned; documentation audit only
---
# F-Droid release plan

The user intends to release PageCast on F-Droid. This is a development build, not a submitted or approved F-Droid release. Policy links were checked September 15, 2026; recheck them during submission.

## Observed gaps and next actions

| Area | Current evidence | Before submission |
|---|---|---|
| App license | No root app LICENSE or copyright declaration. Dependency license files do not license PageCast. | Owner chooses a FLOSS license and copyright attribution; add the license and review compatibility with bundled components. |
| Public source | This checkout has no Git remote. | Publish complete reviewed source and a release tag; provide SourceCode and IssueTracker URLs. A public repository elsewhere has not been verified. |
| Native runtime | `prepare-kokoro-android.py` downloads a checksum-pinned GitHub release AAR containing JNI and ONNX Runtime. | Provide a pinned source-build recipe, including native transitive sources, NDK/CMake/toolchain versions, or a reviewer-accepted dependency route. A checksum alone does not establish source buildability. |
| Model/data | Bundled selectively quantized model, voices, lexicons and eSpeak data are extracted from a pinned release archive. | Record exact upstream revisions, conversion/export/quantization provenance and licenses for every component; resolve model treatment with F-Droid reviewers. Do not assume weights qualify for a source exemption. |
| License inventory | Initial Kokoro notices are included in APK assets. eSpeak NG has GPL terms. | Audit the actual linked runtime and all transitive components, retain required notices and provide corresponding source where required. Current generic upstream links are not a complete source/compliance inventory. |
| Build access | Gradle invokes Python downloads and a pinned uv/ONNX conversion on a cold cache. Existing successful builds were developer debug builds. | Stage pinned inputs through the F-Droid recipe, support its restricted build environment, and validate a clean release build with no credentials or private LAN dependencies. |
| Identity/version | `com.geminireader`, versionName `0.1.0`, versionCode `1`; public name PageCast. | Confirm a unique permanent application ID before release. Renaming creates a separate installation and needs a migration plan. Increment literal version codes for releases. |
| Signing | Current installed APK is debug-signed. | Decide distribution signing and update strategy. Test migration/backup before changing signers; do not instruct existing users to uninstall without preserving their library. |
| Listing | No submitted fdroiddata entry. | Supply license, public links, build recipe, release tag, description, screenshots, changelog and privacy URL; run fdroid lint/build. Use original or clearly licensed screenshot content, with credentials absent. |

F-Droid requires publicly available FLOSS source and reviews how binary dependencies are obtained. The native GitHub AAR is a release gap requiring investigation, not a claim that Kokoro is ineligible. See [Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/) and [developer FAQ](https://f-droid.org/docs/FAQ_-_App_Developers/).

## Product and privacy decisions

Kokoro Narrator provides an offline path without Google accounts or Play Services. The current fresh-install default remains Vertex/Performance; plan a clear offline-first setup for the public release. Keep cloud synthesis, character analysis and rewrites explicit and document what text leaves the device. Do not bundle personal credentials or require the developer's BROKER_HOST broker.

The app offers proprietary Google services, so plan to disclose `NonFreeNet` for review; having an offline engine does not automatically remove the label for promoted proprietary services. Classification belongs to F-Droid review. See [Anti-Features](https://f-droid.org/en/docs/Anti-Features/). There is no advertising or analytics SDK declared in the current app dependencies; a complete dependency scan is still pending.

The model adds about 235 MB of assets and a private extracted copy on first use. The tested universal debug APK is approximately 437 MB (selective8; FP32 was 587 MB). Review distribution size and ABI packaging; benchmark sustained playback, memory, battery and thermal behavior on modest phones. Do not claim real-time synthesis from a short cold-start smoke test.

## Submission sequence

1. Resolve owner license, public repository and package/signing decisions.
2. Complete native source-build and model/license provenance work; test the pinned toolchain in an F-Droid build environment.
3. Finalize offline setup, public-facing help, privacy information and license notices. Keep developer/debug intents and loopback exceptions out of release behavior.
4. Build/test release, verify upgrade behavior and metadata, tag source, then submit to fdroiddata.

References: [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [build metadata](https://f-droid.org/docs/Build_Metadata_Reference/), [descriptions and screenshots](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/).

Do not bypass scanning with blanket exclusions or call a debug installation an F-Droid-ready build. No submission, publication, app relicensing or signing change has been made by this documentation update.

Groq integration uses existing OkHttp with no additional proprietary SDK. Include Groq alongside Google in optional non-free network-service disclosure. Local Kokoro narration remains available.

ElevenLabs also uses existing OkHttp; include it in non-free network-service disclosure. Refresh its time-limited default voice catalog before release.

Cartesia uses existing OkHttp with no new proprietary SDK. Include its optional network dependency in NonFreeNet disclosure.

## Deepgram — September 15, 2026

Deepgram also uses existing OkHttp without a proprietary SDK. Include its optional network service in NonFreeNet disclosure. This does not resolve the existing release/license/build blockers.

## Inworld — September 15, 2026

Inworld uses existing OkHttp with no proprietary SDK. Include the optional Inworld service in NonFreeNet disclosure; existing release blockers remain.

## Speechify — September 15, 2026

Speechify uses existing OkHttp with no new proprietary SDK. Include the optional service in NonFreeNet disclosure; existing release blockers remain.

## Fish Audio — September 15, 2026

Fish Audio uses existing OkHttp without a proprietary SDK or bundled model weights. Include the optional hosted service in NonFreeNet disclosure. Free-tier model-improvement terms are disclosed in privacy notes.

## Android TTS — September 15, 2026

Android TTS uses platform APIs with no new SDK dependency. Users can choose an open-source installed engine. This provides another offline narration route; existing app licensing and build reproducibility blockers remain.
## Optional runtime downloads

The APK now excludes Kokoro's trained weights and native libraries. The optional download dialog explicitly explains that it downloads and runs native code outside F-Droid's checks; Download and Cancel are equally available. No automatic download occurs when selecting the engine or starting playback. Public upstream HTTPS sources and exact hashes are embedded in the app. The runtime fetches only the matching architecture's ZIP ranges; the custom model is reconstructed locally from the upstream original and checked against the pinned selective-8 hash. There is no developer-hosted custom binary endpoint.

This follows the explicit-consent requirement in [F-Droid's inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/), but does not by itself establish eligibility. The wrapper AAR's provenance, source build, licensing and reproducibility still need release review. Model conversion tooling and generated metadata/recipe are reproducible build inputs.
