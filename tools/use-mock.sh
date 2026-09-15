#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
engine=${1:-vertex}
url=${2:-http://10.0.2.2:8765}
[[ "$engine" =~ ^(vertex|cloud|gemini)$ ]] || { echo 'Engine must be vertex, cloud or gemini' >&2; exit 1; }
[[ "$url" =~ ^http://(10\.0\.2\.2|127\.0\.0\.1|localhost):[0-9]+$ ]] || { echo 'Use a loopback mock URL' >&2; exit 1; }
adb shell am start -n com.geminireader/.MainActivity --es debug_mock "$url" --es debug_engine "$engine"
