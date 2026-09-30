# PageCast F-Droid submission

Proposed release: **0.2.1**, version code **3**, tag `v0.2.1`.
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

On September 30, the full local `fdroid build --test --scan-binary` recipe also
passed for 0.2.1/code 3. Upstream CI has not run: GitLab blocked the fork pipeline
because the submitting account is not verified. The model resource exception
still requires reviewer approval.

## Signing and publication

Use F-Droid-managed signing for its distribution. No signing private key is
committed. Existing debug-signed installations cannot update directly to an
F-Droid-signed APK. Preserve their books and settings before any migration;
do not uninstall an existing installation just to try this release.

The GitHub tag is a source release for review, not an F-Droid approval or a
signed installable APK. Submission: [draft merge request !50694](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50694)
and [packaging request #4484](https://gitlab.com/fdroid/rfp/-/work_items/4484).
Account verification is required before GitLab will run the fork CI. F-Droid
inclusion and publication remain pending.
