#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
mkdir -p tools/artifacts
serial=${ANDROID_SERIAL:-emulator-5554}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'ANDROID_SERIAL must select an emulator' >&2; exit 1; }
args=(-avd reader -port "${serial#emulator-}" -gpu "${EMULATOR_GPU:-host}" -no-snapshot-save)
if [[ "${1:-}" == --headless ]]; then args+=(-no-window -no-audio); fi
if ! adb devices | awk '{print $1}' | grep -qx "$serial"; then
  setsid -f emulator "${args[@]}" </dev/null >tools/artifacts/emulator.log 2>&1
fi
adb -s "$serial" wait-for-device
for ((attempt=0; attempt<180; attempt++)); do
  if [[ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then
    adb -s "$serial" shell input keyevent 82
    echo 'Emulator ready.'
    exit 0
  fi
  sleep 2
done
echo 'Emulator did not boot; see tools/artifacts/emulator.log' >&2
exit 1
