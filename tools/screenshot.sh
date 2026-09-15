#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
mkdir -p tools/artifacts
adb exec-out screencap -p > "${1:-tools/artifacts/screenshot.png}"
