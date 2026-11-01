#!/usr/bin/env bash
# Push the private catalog connection to the TV once; the app's service imports it on its next
# start and deletes the pushed copy. Nothing secret goes on the command line or into the .wgt.
# Usage: tizen-tv/provision.sh <tv-ip>:26101
set -euo pipefail
sdb=${SDB:-$HOME/tizen-studio/tools/sdb}; state=${ANI_CATALOG_STATE:-$HOME/.local/share/ani-droid-catalog}
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT; chmod 700 "$tmp"
python3 -I - "$state" "$tmp/c.json" <<'PY'
import base64, json, os, sys, textwrap
state, dest = sys.argv[1:]
c = json.load(open(os.path.join(state, 'connection.json')))
der = open(os.path.join(state, 'certificate.der'), 'rb').read()
pem = '-----BEGIN CERTIFICATE-----\n' + '\n'.join(textwrap.wrap(base64.b64encode(der).decode(), 64)) + '\n-----END CERTIFICATE-----\n'
fd = os.open(dest, os.O_WRONLY | os.O_CREAT, 0o600)
os.write(fd, json.dumps({'url': c['url'], 'token': c['token'], 'fingerprint': c['fingerprint'], 'certificate': pem}).encode())
PY
"$sdb" -s "$1" push "$tmp/c.json" /home/owner/share/tmp/sdk_tools/anidroid-connection.json >/dev/null
echo "Pushed. Launch Ani-Droid on the TV to import it."
