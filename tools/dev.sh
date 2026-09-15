#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
./gradlew installDebug
adb shell am start -n com.geminireader/.MainActivity
pid=$(adb shell pidof com.geminireader | tr -d '\r')
adb logcat --pid="$pid"
