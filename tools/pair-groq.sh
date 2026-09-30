#!/usr/bin/env bash
# Usage: tools/pair-groq.sh ADB_SERIAL SSH_HOST REMOTE_ENV_PATH
# Key travels through a pipe directly into private app storage, never argv/logs.
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
serial="${1:?ADB serial required}"
remote="${2:?SSH host required}"
env_path="${3:?Remote environment file path required}"
# Restrict the path before embedding it in the remote shell command.
[[ "$env_path" =~ ^/[a-zA-Z0-9_./-]+$ ]] || { echo "Unsupported path" >&2; exit 1; }
adb -s "$serial" shell run-as com.geminireader rm -f files/debug-groq-paired
ssh -o BatchMode=yes "$remote" "python3 - '$env_path'" <<'PY' |
import re, sys
from pathlib import Path
match = re.search(r"""(?m)^\s*(?:export\s+)?GROQ_API_KEY\s*=\s*["']?([^\s"']+)""", Path(sys.argv[1]).read_text())
if not match:
    raise SystemExit("Groq key not found")
sys.stdout.write(match.group(1))
PY
adb -s "$serial" shell run-as com.geminireader sh -c "'umask 077; cat > files/debug-groq-key'"
adb -s "$serial" shell am start -W -n com.geminireader/.MainActivity --ez debug_groq_key true
for attempt in {1..50}; do
    if adb -s "$serial" shell run-as com.geminireader test -f files/debug-groq-paired; then
        adb -s "$serial" shell run-as com.geminireader rm files/debug-groq-paired
        echo "Groq key saved; speech engine preserved."
        exit 0
    fi
    sleep 0.2
done
echo "App did not confirm key import." >&2
exit 1
