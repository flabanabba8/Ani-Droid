#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
book=${1:?Usage: tools/push-book.sh /path/to/book}
ext=${book##*.}
[[ "$ext" =~ ^[a-zA-Z0-9]+$ ]] || { echo 'Invalid extension' >&2; exit 1; }
name=$(basename "$book" | sed 's/[^a-zA-Z0-9_.-]/_/g')
adb shell run-as com.geminireader mkdir -p files/debug-import
adb push "$book" "/data/local/tmp/$name"
adb shell "cat /data/local/tmp/$name | run-as com.geminireader sh -c 'cat > files/debug-import/$name'"
adb shell rm "/data/local/tmp/$name"
adb shell am start -n com.geminireader/.MainActivity --es debug_import "$name"
