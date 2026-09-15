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
