#!/usr/bin/env bash
# Assemble the shared webOS app + service with the Tizen shims, then sign with a Samsung profile.
# Usage: tizen-tv/build.sh [profile]   (default: anidroid-tv)  -> tizen-tv/build/Ani-Droid.wgt
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); web=$here/../webos-tv; out=$here/build; stage=$out/wgt
tizen=${TIZEN:-$HOME/tizen-studio/tools/ide/bin/tizen}
rm -rf "$out"; mkdir -p "$stage/service"
cp -r "$web/app/." "$stage/"; rm -rf "$stage/webOSTVjs-1.2.13" "$stage/appinfo.json"
sed -i 's#<script src="webOSTVjs-1.2.13/webOSTV.js"></script>#<script src="tizen-shim.js"></script>#' "$stage/index.html"
grep -q tizen-shim.js "$stage/index.html" || { echo "index.html script tag changed; update build.sh" >&2; exit 1; }
cp "$here/config.xml" "$here/tizen-shim.js" "$stage/"; cp "$here/service/main.js" "$web/service/service.js" "$stage/service/"
"$tizen" package -t wgt -s "${1:-anidroid-tv}" -- "$stage" >/dev/null
mv "$stage/Ani-Droid.wgt" "$out/"; echo "$out/Ani-Droid.wgt"
