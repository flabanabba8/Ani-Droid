# Luffy bridge and desktop builds

A bounded JSON adapter around the pinned Luffy engine, used by the catalog server and by the Luffy skills on the PCs. It never starts a player, invokes fzf, or writes watch history.

Upstream: https://github.com/DemonKingSwarn/luffy at revision `0e0abc6bb1c10a02c2d0db2711a04ca9bb836deb`. GPL-3.0; see the upstream license bundled with Ani-Droid. The build uses current Cinejoy support from that revision, rather than the older release binary. Upstream request encoding is WASM with its original integrity pin retained.

## Build

With Go 1.26 or newer on PATH:

```sh
./luffy-bridge/build.sh
```

The script checks out/verifies the pinned source under `.reference/luffy`, applies the checked fallback patch, copies the adapter and VixSrc overlay into the checkout, formats them, runs the core/provider and adapter tests, and produces Linux and Windows Luffy executables plus bridge executables under ignored `luffy-bridge/build`.

Install the Linux bridge as `~/.local/bin/ani-luffy-bridge` for the catalog server. Alternatively set `luffyBridge` in the server's private configuration. The optional desktop executable is `luffy` (`luffy.exe` on Windows); standard dependencies are mpv, fzf and yt-dlp.

## Protocol

One JSON request on stdin, one JSON response on stdout. Inputs are limited to 64 KiB; failures return an error object and nonzero exit. The JVM caller bounds runtime, output size, and concurrent processes. No shell executes user queries.

Actions: `search` (query/page), `browse` (movie or tv/page), `details` (canonical movie-ID or tv-ID), `genres` (ID), and `streams` (ID/title/year/season/episode). Search/catalog metadata uses the same TMDB integration as Luffy; streams are resolved by `providers.Cinejoy.GetLink`, with an independent VixSrc fallback adapted from PandaFlix revision `f6484787ba5a`. The reproducible overlay is in `overlay/`; it retains the Cinejoy WASM integrity pin and preserves VixSrc headers and English captions. The bridge keeps HLS master playlists intact so separate audio renditions are preserved.

Stream responses include required Referer/Origin/User-Agent headers. Do not put signed stream URLs in public logs. English is the discovery default, not a restriction on live searches. Discovery results have not all been playback-verified.

## Verification

The upstream Go tests passed. Native Linux/Windows `--version` and headless movie search checks passed on this machine, Omarchy and the Legion Go. Cinejoy's upstream encoder/server catalog returned HTTP 502 during live stream verification on 2026-10-04; the independent VixSrc fallback added on 2026-10-05 resolved and decoded a movie and TV episode successfully.

The three-source chain now tries Cinejoy, VixSrc, then LookMovie. Independent LookMovie movie resolution and headless decoding passed. Stream results include sanitized source-attempt reports and provider-specific headers/captions. This does not change local watch-history behavior or add synchronization.
