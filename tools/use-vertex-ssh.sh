#!/usr/bin/env bash
# Transfer only a short-lived ADC access token; refresh credentials stay on the SSH host.
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
remote=${1:?Usage: tools/use-vertex-ssh.sh USER@HOST PROJECT [REGION]}
project=${2:?Supply the Google Cloud billing project ID}
region=${3:-us-central1}
[[ "$remote" != -* && "$remote" =~ ^[a-zA-Z0-9_.@:-]+$ ]] || exit 1
[[ "$project" =~ ^[a-zA-Z0-9-]+$ && "$region" =~ ^[a-z0-9-]+$ ]] || exit 1
adb shell am force-stop com.geminireader
ssh -o BatchMode=yes "$remote" 'gcloud auth application-default print-access-token' |
    adb shell run-as com.geminireader sh -c '"umask 077; cat > files/debug-vertex-token"'
adb shell am start -n com.geminireader/.MainActivity \
    --es debug_vertex_project "$project" --es debug_vertex_location "$region"
echo 'Short-lived Vertex token transferred to the debug app. Refresh credentials stayed on the SSH host.'
