# Ani-Droid 0.7

Android front end for **ani-cli** anime and **Luffy / Cinejoy** movies and TV. The app has a shared catalog, live search, episodes, genre filters, favorites, history, resume, fullscreen playback and offline downloads.

Anime search/playback works directly on the phone. Luffy search and stream resolution use the private catalog server and its `ani-luffy-bridge` helper; video normally streams directly to the phone. MovieBox was retired from the active UI, provider code, and catalog. Existing phone history/favorites from that provider are retained under private legacy preference keys.

## Build and verify

```sh
source tools/env.sh
./gradlew :anidroid:assembleDebug :anidroid:testDebugUnitTest :anidroid:lintDebug
./gradlew :anidroid:assembleDebugAndroidTest
```

APK: `anidroid/build/outputs/apk/debug/anidroid-debug.apk`.

Use an emulator first. `OfflineTest` serves a generated six-second HLS fixture with required HTTP headers, downloads it for both provider identities, stops the media server, verifies decoded offline video in fullscreen landscape, checks persisted completion, and removes the downloads. `CatalogAppTest` requires a private catalog connection in app storage; it covers browsing, search, filters, details and TLS/access-key rejection. `PlaybackTest` is opt-in with instrumentation arguments `live=true` and `sources=ani` or `sources=ani,luffy`.

## Playback and downloads

Playback starts in landscape with system bars hidden. The player has a fullscreen toggle and Fit/Fill screen control. Back restores the previous orientation and saves position.

Choose a stream and tap **Download**. Downloads are stored in app-owned storage, appear in **Downloads**, and support pause, resume, cancellation, deletion, and offline playback. An Auto stream downloads up to 720p; choosing an explicit anime quality retains that quality. A default external subtitle is saved locally, and selected HLS audio/subtitle renditions are downloaded with the video. Files are managed inside Ani-Droid, not exported to the public Downloads folder. Uninstalling the app removes its downloads.

Transfers use the current network and a foreground download service. If Android kills the process, the persistent download index/cache survives and work can resume when the app restarts. Expired source links may require opening the title and starting a fresh download. Online playback does not permanently fill the offline cache.

## Server and TV

See [catalog server](../catalog-server/README.md), [Luffy bridge](../luffy-bridge/README.md), and [LG webOS TV app](../webos-tv/README.md). Configuration is runtime data: no personal server address, access key or device credentials are embedded in APK/IPK assets.

Luffy discovery uses its TMDB metadata source with English-language discovery defaults; stream availability is checked on Play/Download. Catalog availability is not a guarantee that a resolver is online. At verification on 2026-10-04, Luffy search/details worked on all three PCs, but Cinejoy's `api.shegu.st` resolver returned HTTP 502, preventing live Luffy playback verification. The Android download/offline engine passed both-provider fixture tests; ani-cli live stream/relay decoding also passed.

## Artwork and licenses

[Generated launcher artwork](artwork/icon-v2.png) and its [prompt](artwork/icon-v2-prompt.txt) are retained in the workspace. The icon was generated with the built-in image tool and packaged as an Android adaptive icon.

ani-cli provider revision: `21ed4a0a6354688622b5a967d93ba96d851d0ef2`, GPL-3.0-or-later. Luffy revision: `0e0abc6bb1c10a02c2d0db2711a04ca9bb836deb`, GPL-3.0. Notices are bundled in `src/main/assets/licenses` and shown in About. This module is GPL-3.0-or-later. Full upstream research copies are in ignored `.reference/` folders. Follow the workspace publication audit before any public distribution.

Luffy now falls back from Cinejoy to VixSrc through the shared bridge (2026-10-05). This server update applies to both Android and webOS without reinstalling clients. The fallback preserves master HLS audio, required headers and English captions.

Version 0.5.0 adds saved subtitle size (16–36 sp) and White/Yellow/Mint/Cyan colors from the player’s Subtitles button, with a preview. These apply to streaming and offline playback, overriding embedded caption styling for consistent readability.

Version 0.6 adds device-local Continue Watching and Watchlist, a cancelable next-episode countdown, subtitle timing and audio/text track selection, fresh-stream recovery with source reports, download quality limits and Wi-Fi/unmetered scheduling with queue pause/resume. Recently Added sorts by catalog discovery time. Playback history is never synced. No public release or F-Droid submission has been made.

Caption timing is applied during text parsing, including after seeks; changing it prepares the current media again at the same position. Next-episode autoplay currently applies to streaming sessions. Wi-Fi scheduling uses Android’s unmetered-network requirement and resumes through its job scheduler.

## Version 0.7: library tools and download recovery

- **Batch downloads**: Title > Batch downloads. Tick episodes or use Select season, see an estimated size (a rough duration x bitrate figure for the chosen quality; real sizes vary) and queue them. Each episode gets its own download at the selected quality limit; the closest available stream at or below the limit is used, and an adaptive stream is capped to it. Episodes that cannot be resolved are reported and can be retried from the title.
- **Backup / import** (top bar > Backup): a JSON file of favorites, watchlist, history titles, watched markers, per-show preferences, and subtitle size/color/timing, auto-next and download quality. It never contains the catalog address or key, signed stream URLs, poster links, resume positions, Wi-Fi/queue state or downloaded media. Import checks every field first and either replaces the saved lists and settings or changes nothing. It replaces rather than merges.
- **Watched markers**: toggle per episode in the title list or player. Playback past 95% marks an episode watched automatically; a manual "unwatched" is respected until you play that episode again.
- **Per-show preferences**: source, audio, quality, subtitle track/language/off are saved per title. If the saved audio or quality is not offered, the app falls back (first audio track; exact height, then adaptive, then the best at or below, then the lowest above). If a saved Luffy source returns nothing, Auto is used for that request without erasing the saved choice. The preferred stream is labeled in the list.
- **Source switching and expired links**: Luffy titles offer a manual source choice (Auto, Cinejoy, VixSrc, LookMovie) on the title and in the player; switching keeps the position, episode, quality and selected tracks. A queued download whose signed link is old, or is rejected mid-transfer (HTTP 401/403/404/410), is re-resolved once for the same episode and download id, with the same quality limit and track languages; cached parts are kept when the new link still matches, otherwise the stale partial is discarded. If the source cannot be re-resolved, the download fails and can be retried from the title. This recovery needs the catalog server for Luffy and the provider site for anime.
- Long subtitle cues are cut into one-second pieces so a seek into the middle of a cue shows it again within a second.

Verification (Android emulator, local fixtures only): unit tests, lint, debug builds, and instrumentation tests for backup round-trip/rejection (no partial changes), batch queueing, watched markers, expired-link renewal against a fixture server that returns 403, offline download/playback, and subtitle timing. The catalog-connected and live-provider tests were skipped on this run; live Luffy/anime recovery against real providers was not exercised.

