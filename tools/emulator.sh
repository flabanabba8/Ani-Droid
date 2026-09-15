#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
mkdir -p tools/artifacts
args=(-avd reader -gpu "${EMULATOR_GPU:-host}" -no-snapshot-save)
if [[ "${1:-}" == --headless ]]; then args+=(-no-window -no-audio); fi
if ! adb devices | grep -q '^emulator-.*device'; then
  nohup emulator "${args[@]}" >tools/artifacts/emulator.log 2>&1 &
fi
adb wait-for-device
for ((attempt=0; attempt<180; attempt++)); do
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then
    adb shell input keyevent 82
    echo 'Emulator ready.'
    exit 0
  fi
  sleep 2
done
echo 'Emulator did not boot; see tools/artifacts/emulator.log' >&2
exit 1
