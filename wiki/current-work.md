---
machine: build-host / Android
subsystem: implementation
last-verified: 2026-09-15
status: implemented and smoke tested
---
# Current work

Baseline commit: `7588fdf`, functioning MVP, 39 JVM tests. User clarified scope: **automatic Vertex renewal, smarter buffering, reliable resume, plus audio export**. Local Wi-Fi through BROKER_HOST is acceptable. Offline chapter preparation and series casts remain backlog.

## Implemented

- Pinned-HTTPS BROKER_HOST token broker, pairing via SSH/adb, automatic expiry refresh, concurrent refresh deduplication and one retry on 401. Google ADC never leaves the BROKER_HOST. See [authentication](authentication.md).
- Configurable 15–600 seconds of ready audio, default 120, measured from WAV duration and adjusted for playback speed; two concurrent TTS requests maximum.
- Separate audio and scroll bookmarks, serialized atomic writes, offset/speed persistence, last-book reopening without automatic playback.
- Reader → Export audio → Save WAV → Android document picker. Streams generated current-queue audio, not an ungenerated full chapter; validates PCM and sample rates. Export itself requests no synthesis.

## Evidence

- Build, lint and 46 JVM tests pass, including expiry/concurrency/401 refresh, duration/speed, bookmark isolation, WAV concatenation and malformed/mixed-rate rejection.
- BROKER_HOST HTTPS service is enabled/running with user lingering enabled. Unauthenticated POST returns 401; paired requests return a short-lived token (never logged).
- Emulator played broker-backed Vertex audio for a short original-text fixture, showing sentence highlighting and `0m 42s ready`.
- Paused at 7,835 ms, force-stopped/reopened, tapped Play then paused during loading: Media3 restored exactly 7,835 ms against the same cached audio. Initial loading did not overwrite the bookmark.
- Inspected screenshots: `tools/artifacts/milestone-buffer.png`, `export-confirm.png`, `export-picker.png`.
- Android document picker saved a valid 50.596583-second mono 24 kHz PCM WAV; `ffprobe` verified it. Inspected `export-success.png`.
- Alice mock playback displayed `2m 10s ready`; paused filling bounded by duration. Mock log confirmed Alice-specific higher/lighter voice directions. Emulator reboot preserved the saved 18,750 ms bookmark; subsequent Play resumed cached audio without new mock requests. Inspected `two-minute-buffer.png` and `reboot-resume.png`.
- Two Python broker tests pass: unauthorized/path rejection and cache/forced/expired refresh. Live forced Google ADC refresh returned HTTP 200 and a fresh ~3,598-second lifetime, token suppressed.
- Final APK installed on emulator and Samsung ANDROID_DEVICE (`ANDROID_MODEL`, `LOCAL_DEVICE_ADDRESS`); both paired with BROKER_HOST. Fresh emulator `broker-smoke` audio generated and played with persisted manual token cleared. Phone pairing import consumed successfully; no phone reboot or headphone hardware test performed.

## Limits

BROKER_HOST must be reachable for renewal and needs Internet to Google. No away-from-home connectivity yet. Buffer target is best effort; in-flight segments may overshoot, and paused playback still fills the target. Current chapter queue stays pinned until stop and can exceed the nominal cache budget. Bookmarks save every two seconds plus transitions/pause/background; abrupt death can lose that interval. Exact milliseconds require unchanged cached audio; regenerated speech restarts the segment safely. Emulator reboot is tested; physical headphone reconnection and phone reboot are not. No overnight endurance run was performed. Export is original-speed WAV, potentially a partial chapter/paragraph, and needs temporary disk space. Full offline chapter preparation is not implemented.

## Verification discipline

Update this page with completed code, tests, emulator results, and remaining limitations before handoff. Do not call a design or UI stub a working feature. Do not copy long-lived Google credentials into this repo, APK, wiki, or phone.
