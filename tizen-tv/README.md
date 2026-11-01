# Ani-Droid for Samsung Tizen TV

The webOS app and service from `../webos-tv`, packaged for Samsung TVs with two small shims:

- `tizen-shim.js` gives the app `webOS.service.request` (over trusted message ports, chunked: ports reject messages over ~8 KB) and `webOS.platformBack`.
- `service/main.js` runs the shared `service.js` under Tizen's Node 4.4 web service, stores data in the app's private data directory (kept across updates), and imports a pushed connection.

Tested on a 2020 UN55TU8300 (Tizen 5.5, Chromium 69).

## Build, install, connect

Needs Tizen Studio with the TV extensions and a Samsung certificate profile for the TV (see `wiki/samsung-tizen-tv.md`).

```sh
tizen-tv/build.sh                                  # -> tizen-tv/build/Ani-Droid.wgt (profile anidroid-tv)
~/tizen-studio/tools/ide/bin/tizen install -n tizen-tv/build/Ani-Droid.wgt -t <device-name>
tizen-tv/provision.sh <tv-ip>:26101                # once: pushes the private catalog connection
```

Then open Ani-Droid on the TV. The service imports the connection and deletes the pushed copy.

## Remote

Samsung remotes have no pointer. During playback: OK pause/play, Left/Right seek, Down playback options, Up the button bar (Left/Right to move, OK to press, Down to leave), Back returns to episodes.
