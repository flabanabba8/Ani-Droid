---
machine: build-host / Android
subsystem: F-Droid release
last-verified: 2026-09-29
status: prepared; GitLab submission pending
---
# PageCast F-Droid release

PageCast 0.2.0 (version code 2) is prepared under GPL-3.0-or-later at
https://github.com/flabanabba8/PageCast. Its Android ID remains `com.geminireader`
for continuity. The source tag is `v0.2.0`.

The submission recipe, recorded checks, signing strategy and remaining review
work are maintained in [fdroid/README.md](../fdroid/README.md). The listing is in
`fastlane/metadata/android/en-US`; screenshots use original sample books only.

Gradle now compiles the Apache-2.0 Sherpa wrapper from pinned Kotlin source.
Normal APK builds no longer fetch its precompiled AAR or convert model weights.
Optional native/model downloads still require explicit consent explaining that
they bypass F-Droid checks. The small checked-in model transformation resource
has one documented scanner exception, subject to reviewer acceptance. See
[provenance](../docs/kokoro-source.md).

The metadata declares `NonFreeNet` for optional proprietary cloud services.
Reading requires no account. Android TTS and optional downloaded Kokoro provide
local narration routes; cloud providers use the user's own credentials.

Local builds, unit tests, Android lint, F-Droid metadata/source/APK scans and
local speech instrumentation passed. The complete F-Droid build-server run,
GitLab merge request and reviewer approval remain outstanding. GitLab account
sign-in is required to file the prepared submission.

F-Droid-managed signing is proposed. Existing debug-signed installs cannot
update directly to that signature; preserve user data before migration.

References: [inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/),
[submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[anti-features](https://f-droid.org/en/docs/Anti-Features/).
