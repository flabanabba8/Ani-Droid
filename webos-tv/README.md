# Ani-Droid for LG webOS TV

A remote-friendly webOS app with catalog browsing, source/genre/type filters, live search, episode selection, favorites, fullscreen video and resume positions. The default UI uses large couch-distance text and four poster columns; **Larger text** increases it further and switches to three columns. Subtitle styling is independent of UI text size.

The target tested here is an LG C1 running webOS SDK 6.5.3 (Chromium 79, Node 8.12). The app avoids unsupported flex-gap/inset layout features. UI controls work with directional keys, OK, Back and the Magic Remote pointer. During playback, left/right seek, OK pauses/plays, and Back returns to episodes.

## Build

Install the official `@webos-tools/cli` and select its TV profile, then:

```sh
ares-package webos-tv/app webos-tv/service -o webos-tv/build
ares-install -d YOUR_TV webos-tv/build/dev.anidroid.tv_0.2.0_all.ipk
ares-launch -d YOUR_TV dev.anidroid.tv
```

The generated IPK contains the app and a small Node service, not personal connection settings or developer keys. LG's webOSTV.js library and Apache-2.0 license are bundled in the app. Ani-Droid code is GPL-3.0-or-later.

## Runtime connection

The TV service uses a private catalog connection containing `url`, `token`, `fingerprint` and the PEM `certificate`. The `configure` Luna method validates and writes this to a mode-0600 file owned by the service in `/media/internal`; it never returns the key. Provision through an authorized developer connection without echoing credentials or embedding them in the IPK. The `status` method reports configuration presence only.

The service pins the exact catalog certificate. Browser code receives catalog data, not the access key. Its API requests are restricted to the catalog/search/details/streams endpoints.

Enable `tvRelayPort` in the catalog server's private config for TV video. The TV cannot attach the same provider headers as the Android player, so media passes through a private-LAN HTTP relay on that port. The catalog API remains HTTPS. The relay uses temporary capability URLs, keeps upstream provider traffic HTTPS, rejects private upstream addresses, forwards range requests, and rewrites HLS audio/key/segment/subtitle URLs. External SRT subtitles are converted to WebVTT. It does not save video files. This LAN relay setup is not intended for direct public exposure; public hosting should use properly trusted HTTPS for media too.

The computer running the catalog must remain awake and reachable. Downloads are currently an Android feature; the TV app streams video.

## Checks

- JavaScript syntax checks, catalog/relay unit tests, and a local Playwright UI test passed.
- The local UI test uses the real private catalog through a test-only bridge and checks D-pad focus, search/details, favorites, genre filtering and the text-size toggle.
- On the actual TV, developer authentication, private HTTPS catalog requests, episode/stream resolution, and fetching relayed HLS media bytes passed.
- The relay decoded video/audio successfully in a headless player. A first automated on-TV UI timing check failed; subsequent inspection confirmed the catalog rendered, and couch-distance UI changes were applied live during user testing. Native on-TV video/subtitle rendering still needs confirmation during normal viewing.
- Cinejoy returned HTTP 502/Cloudflare 1033 during installation checks. The VixSrc fallback now resolves successfully; the actual LG TV fetched its master HLS and English WebVTT, and headless playback through the TV relay decoded successfully. Native on-screen video rendering remains a user verification step.

Luffy now falls back from Cinejoy to VixSrc through the shared bridge (2026-10-05). This server update applies to both Android and webOS without reinstalling clients. The fallback preserves master HLS audio, required headers and English captions.

Subtitle options are available from title details and the playback HUD (Down on the remote). Six sizes (32–72 px) and White/Yellow/Mint/Cyan colors apply immediately and persist on the TV. Default captions are 48 px with a dark background. Back closes the panel without stopping playback.

Version 0.2 adds device-local Continue Watching and a separate Watchlist, Recently Added sorting, automatic next episodes with a cancelable countdown, subtitle timing/track and native audio-track selection where advertised, and fresh-stream retry with source reports. Watch progress and lists never leave the TV. TV downloads remain unsupported; Android queues provide offline downloads.
