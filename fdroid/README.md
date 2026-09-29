# PageCast F-Droid submission

Proposed release: **0.2.0**, version code **2**, tag `v0.2.0`.
Public source: https://github.com/flabanabba8/PageCast
License: **GPL-3.0-or-later**; see `LICENSE` and `COPYRIGHT`.

## Submission files

Copy `metadata/com.geminireader.yml` into the `metadata/` directory of an
fdroiddata checkout. The upstream source supplies the English listing, icon,
original-content screenshots and changelog through `fastlane/metadata/android`.
The application name is PageCast; its existing Android ID stays `com.geminireader`.

From fdroiddata, with the F-Droid Android build environment configured:

```sh
fdroid lint com.geminireader
fdroid build --test --scan-binary com.geminireader
```

Then submit the metadata in a merge request to
https://gitlab.com/fdroid/fdroiddata. Inclusion requires reviewer approval.

## Review notes

- Optional proprietary cloud services are declared with `NonFreeNet`.
- Normal builds compile the Sherpa wrapper from published Kotlin source and do
  not download its binary AAR or pretrained model.
- The single `scanignore` entry covers a compressed ONNX transformation resource,
  not executable JVM/DEX/native code. Its generator and decoder are published.
  See [source provenance](../docs/kokoro-source.md) for review details.
- Optional Kokoro native/model downloads require explicit consent explaining
  that they bypass F-Droid checks. Reading needs neither cloud credentials nor
  these downloads. Android TTS can use an installed speech engine.
- No personal credentials or developer broker configuration are included.

## Validation recorded September 29, 2026

Passed: release/debug Gradle builds, unit tests, release lint, a separate clean
source export built offline using cached Maven dependencies, F-Droid 2.4.5
metadata lint, source scan with the documented data exception, and APK scan.
The source-built wrapper also passed two local speech instrumentation tests on
a dedicated emulator. Screenshots contain only original sample books.

These are local checks. The full F-Droid build-server recipe and upstream CI
have not run, and the model resource exception has not been approved.

## Signing and publication

Use F-Droid-managed signing for its distribution. No signing private key is
committed. Existing debug-signed installations cannot update directly to an
F-Droid-signed APK. Preserve their books and settings before any migration;
do not uninstall an existing installation just to try this release.

The GitHub tag is a source release for review, not an F-Droid approval or a
signed installable APK. At preparation time the GitLab submission is pending
account sign-in; no merge request has been filed.
