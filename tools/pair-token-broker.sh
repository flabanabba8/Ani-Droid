#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
remote=${1:?Usage: tools/pair-token-broker.sh USER@BROKER_HOST}
[[ "$remote" != -* && "$remote" =~ ^[a-zA-Z0-9_.@:-]+$ ]] || exit 1
# No token, certificate key or pairing secret in command-line arguments or stdout.
ssh -o BatchMode=yes "$remote" 'python3 .local/share/gemini-reader-broker/broker.py --pairing-json' |
  adb shell run-as com.geminireader sh -c '"umask 077; cat > files/debug-broker.json"'
adb shell am start -n com.geminireader/.MainActivity --ez debug_broker true
echo 'Automatic renewal configured using an SSH-authenticated certificate pin and pairing secret.'
