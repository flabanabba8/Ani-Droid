#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
adb shell am start -n com.geminireader/.MainActivity --ez debug_kokoro true
