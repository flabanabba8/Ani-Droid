---
machine: build-host / BROKER_HOST / ANDROID_DEVICE
subsystem: development
last-verified: 2026-09-15
status: verified MVP workflow
---
# Development runbook

Worktree: `/home/USER/orca/workspaces/gemini-reader/gardeneel`, branch `gardeneel`. Commit logical steps; do not push. Preserve user changes. No sudo or system-package installs.

```bash
source tools/env.sh
./gradlew testDebugUnitTest assembleDebug lintDebug
python3 -m unittest discover -s tools/token-broker -p 'test_*.py'
tools/emulator.sh --headless
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
ANDROID_SERIAL=emulator-5554 tools/use-mock.sh vertex
```

Start the mock with `python3 tools/mock-server/mock_gemini.py` (see `--help` for options). Keep mock logs and screenshots in gitignored `tools/artifacts/`; inspect screenshots, don't only capture them. Avoid outputting DataStore settings, tokens or remote credential files.

ANDROID_DEVICE was paired via wireless adb; last endpoint `LOCAL_DEVICE_ADDRESS`, model `ANDROID_MODEL`. Discover fresh endpoints with `adb mdns services`; never target an unrelated advertised Android device. `adb install -r` preserves data. Do not repeatedly drive the phone UI while the user is interacting with it; prefer emulator tests.

BROKER_HOST: `USER@BROKER_HOST`. Its **user ADC** works for Vertex; its active gcloud CLI account is a different service account. Automatic renewal pairing:

```bash
ANDROID_SERIAL=emulator-5554 tools/pair-token-broker.sh
```

See [authentication](authentication.md) for service operations, security and rotation. `tools/use-vertex-ssh.sh` remains the manual fallback and disables broker mode. Prefer the mock for repeated tests; use short original-text fixtures for live smoke tests and avoid accidentally rendering an entire book.

## On-device Kokoro and release work

Python 3.11+ prepares checksum-pinned runtime/model inputs during Gradle builds. First builds need network access and substantial disk space; cached inputs live in `.cache/kokoro-android`. See [Kokoro setup/testing](kokoro.md). `assembleDebugAndroidTest` builds real native instrumentation tests; run them on the emulator first. These tests can stop/restart the app: avoid interrupting a user who is listening on the phone.

**Current phone preference:** Vertex with the saved BROKER_HOST automatic-renewal configuration, restored after the Kokoro quality report. Preserve credentials and listening position. `gemini` is the separate API-key engine; the user's existing OAuth/broker setup belongs to `vertex`. Do not select Kokoro on the phone simply because a new build contains it. Local defaults remain Vertex/Performance.

Use [F-Droid release preparation](fdroid.md) for public build/signing/source requirements. The present debug build process is not a validated F-Droid recipe. Do not publish private infrastructure notes, pairing files, logs or book excerpts without review.

## Track phone RAM while listening

```bash
source tools/env.sh
python3 tools/monitor-memory.py --serial YOUR_PHONE_SERIAL
```

Samples every two seconds and prints decimal MB plus the highest sampled PSS in the monitoring session. Use `--once` for a snapshot or Ctrl+C to stop. PSS apportions shared pages and is useful for app-attributed memory; RSS includes every resident shared page. Watch before model load, during generation, and after switching back to Vertex. This samples process memory, not a guaranteed instantaneous peak. Requires wireless/USB ADB, not root. [Android memory diagnostics](https://developer.android.com/tools/dumpsys#meminfo).

## PageCast Canary

Build with `./gradlew assembleCanary`. Install `app/build/outputs/apk/canary/app-canary.apk` with ADB. This debug-signed variant appears as **PageCast Canary**, uses package `com.geminireader.canary`, and installs alongside PageCast with independent storage. Launch it with `adb shell am start -n com.geminireader.canary/com.geminireader.MainActivity`.

Canary has its own JVM and instrumentation tasks: `testCanaryUnitTest` and `assembleCanaryAndroidTest`. For emulator debugging:

```bash
source tools/env.sh
ANDROID_SERIAL=emulator-5554 tools/emulator.sh --headless
./gradlew testCanaryUnitTest assembleCanary assembleCanaryAndroidTest lintCanary
adb -s emulator-5554 install -r app/build/outputs/apk/canary/app-canary.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/canary/app-canary-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.geminireader.data.LibraryOnDeviceTest,com.geminireader.tts.HttpApiOnDeviceTest,com.geminireader.tts.CartesiaOnDeviceTest \
  com.geminireader.canary.test/androidx.test.runner.AndroidJUnitRunner
```

The emulator helper defaults to `emulator-5554` and rejects phone serials. Native Kokoro tests require its downloaded model/runtime; Android TTS tests require an installed offline English voice. Paid provider tests are opt-in. Inspect instrumentation status codes: the runner's `OK` total can include skipped tests. See [Canary review](../docs/canary-debug-review.md) for the September 29 results and limits.

To retain settings on an initial Canary install, stop Canary and copy `files/datastore/settings.preferences_pb` from PageCast using each package's `run-as` access before launching Canary. Keep the destination file private (mode 600), compare the copied bytes, and never print or commit its contents. This includes Gemini credentials and Vertex automatic-renewal settings; books and playback state are separate. Subsequent `adb install -r` updates preserve Canary's own settings.

### Fresh APK packaging

Every debug/release/canary APK packaging task (including Android test APKs) deletes its previous output and incremental packaging state before writing a new archive. APK packaging is never up-to-date or restored from the build cache. Compilation and other tasks remain incremental. This is intentional: incremental APK ZIP edits retained large unused regions after model removal. Do not remove this behavior; the user requested fresh packaging on every rebuild.
