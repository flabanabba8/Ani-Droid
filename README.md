# Ani-Droid

An Android client for watching anime and movies/TV, with offline downloads and a small private catalog server.

- **Anime** is searched and played directly on the phone using provider logic from [ani-cli](https://github.com/pystardust/ani-cli).
- **Movies and TV** are resolved through a private catalog server and a bridge around [Luffy](https://github.com/DemonKingSwarn/luffy). Video still plays straight from the provider to the phone.
- An experimental **LG webOS TV** app uses the same catalog server.

Version 0.7.0. Android 8+ (API 26). No public release or F-Droid submission has been made; build it yourself.

## Features

- Shared catalog with live search, source/genre/type filters, favorites, watchlist, history and Continue Watching
- Fullscreen playback with subtitle size, color and timing controls, audio/subtitle track selection, next-episode autoplay and fresh-stream retry
- Offline downloads with a quality limit, Wi-Fi-only scheduling and pause/resume
- Batch downloads: pick episodes or a whole season, see an estimated size, queue at your quality limit
- Watched/unwatched markers, set automatically near the end of playback or by hand
- Saved per-show source, quality, audio and subtitle choices, with fallback when an option is missing
- Manual source switching for Luffy titles and automatic renewal of expired download links
- Backup/import of your lists, markers and preferences. It never includes server credentials, signed stream URLs or downloaded videos, and an import is fully validated before it changes anything

## Repository layout

| Path | What it is |
| --- | --- |
| `anidroid/` | The Android app ([details](anidroid/README.md)) |
| `catalog-server/` | Private HTTPS catalog and optional TV media relay ([details](catalog-server/README.md)) |
| `luffy-bridge/` | JSON adapter around the pinned Luffy engine ([details](luffy-bridge/README.md)) |
| `webos-tv/` | LG webOS TV app ([details](webos-tv/README.md)) |

## Build

Needs JDK 21 and the Android SDK; `tools/env.sh` sets both up for this workspace.

```sh
source tools/env.sh
./gradlew :anidroid:assembleDebug :anidroid:testDebugUnitTest :anidroid:lintDebug
```

The APK is `anidroid/build/outputs/apk/debug/anidroid-debug.apk`. Instrumentation tests run on an emulator and use local fixture servers; some tests need a private catalog connection or live providers and skip without them.

## Catalog server

Luffy search and playback need the catalog server and bridge running on a computer you control. See the [catalog server](catalog-server/README.md) and [bridge](luffy-bridge/README.md) guides. In the app, open **Server** and enter its address, access key and certificate fingerprint. These are stored on the device only; nothing is built into the APK.

## Privacy

Favorites, history, watch progress and downloads stay on your device and are never synced. The app talks only to the providers and to the catalog server you configure.

## Limitations

- Provider availability changes. Catalog listings do not guarantee a stream is currently playable, and live-provider behavior has been checked only occasionally.
- Downloads are Android-only; the TV app streams.
- Backup import replaces your lists and settings; it does not merge.

## License and notices

GPL-3.0-or-later, see [LICENSE](LICENSE). It builds on ani-cli (GPL-3.0-or-later) and Luffy (GPL-3.0); their notices ship in the app's About screen. This is an independent project, not affiliated with those projects or with any content provider. You are responsible for how you use it and for complying with the laws and terms that apply to you.
