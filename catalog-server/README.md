# Ani-Droid catalog server

A private HTTPS catalog for Ani-Droid. Uses the ani-cli adapter and the pinned Luffy bridge, SQLite for durable listings and episode metadata, and the JDK HTTP server. Android normally fetches video and posters directly from providers. An optional private-LAN TV media relay streams video through this computer without storing it.

## Build and run

Requires JDK 21 and the repository's Gradle wrapper. From the repository root:

```sh
source tools/env.sh
./gradlew :catalog-server:test :catalog-server:installDist
# Default setup binds only to localhost. Use 0.0.0.0 for access on your home network.
python3 catalog-server/setup.py --bind 0.0.0.0 --install-service
```

The setup creates a private state directory at `~/.local/share/ani-droid-catalog` (mode 0700), a self-signed TLS certificate, a random access key, and a systemd user service. Configuration and keys have mode 0600; the service uses umask 0077. No keys or addresses are built into the APK or source.

Existing configuration is preserved when rerunning setup. To change its bind address or port, edit the private `config.json`, rerun setup to update the connection file, then restart the service. Never commit the state directory.

```sh
systemctl --user status ani-droid-catalog.service
systemctl --user restart ani-droid-catalog.service
journalctl --user -u ani-droid-catalog.service -n 30
# Stop and disable:
systemctl --user disable --now ani-droid-catalog.service
```

The service starts with the user's systemd session. The computer must be awake and reachable. Access away from home needs an existing VPN or separately configured secure hosting; setup does not open router ports.

Without systemd:

```sh
python3 catalog-server/setup.py
catalog-server/build/install/catalog-server/bin/catalog-server ~/.local/share/ani-droid-catalog/config.json
```

## Connect the app

Use **Server** in Ani-Droid. Enter the HTTPS address of the server, the access key (`token`), and SHA-256 certificate fingerprint from the private `connection.json`. Its initial address is localhost; substitute the computer's reachable address when connecting a phone. The app validates the connection before saving it. The private certificate is pinned, redirects are disabled, and the access key is sent only to this catalog client. A publicly trusted certificate may be used without a pin.

For development installs, `catalog-connection.json` in the app's private files directory has the same schema as `connection.json`. Configure it through authorized ADB without embedding it in the APK. Never pass credentials in command-line arguments, logs, screenshots, or source files.

## Indexing and freshness

- Anime: paginated A–Z directory and genre listing pages discovered from its navigation. The page counts and categories come from the live site; common genres are seeded first.
- Luffy: English-language movie/TV discovery through the same TMDB metadata source as Luffy, with Cinejoy resolution on playback. Install the [Luffy bridge](../luffy-bridge/README.md) before starting the server. MovieBox rows/feeds were explicitly retired; a private database backup was taken before migration.
- One browse-page operation at a time, with at least ten seconds between operations. Metadata uses two workers per source, each waiting at least one second after a completed request; transient failures back off and are retried later. Separate bounded metadata workers backfill full genre lists from title pages for both sources; missing tags and the oldest backlog take priority. Provider failures postpone that feed for fifteen minutes while others continue.
- Page results and the next-page checkpoint commit together. Restarting resumes incomplete passes.
- The anime pass finishes only at the directory's declared end. Unexpected repeated anime pages are retried instead of being marked complete. Empty/end-of-feed, repeated feed pages, or three Luffy pages with no new titles end a Luffy pass. This bounds rotating recommendation feeds that otherwise repeat indefinitely. Each completed feed is revisited after 24 hours. Luffy feed completion does **not** imply a complete global inventory.
- Old records are retained when a feed fails or no longer lists a title. Each title has a last-seen timestamp. Listed does not guarantee currently playable; playback resolves a fresh stream directly from the provider.
- Episode/season metadata is fetched on first opening a catalog title, cached for 24 hours, and served stale with an explicit app notice if refreshing fails. The metadata workers fetch genres without downloading episode lists or media.
- Genre tags are merged across listing pages and title metadata. Unknown genres are not guessed; those titles remain visible under All genres. Genre counts follow the active source/type/search filters. Coverage is shown explicitly and Uncategorized remains browsable. A–Z discovery continues independently of genre enrichment, so even permanently missing metadata cannot block the full directory scan.
- The phone requests 40 titles per page, with text, source, genre and movie/series filters and A–Z/newly indexed sorting. It does not copy the whole database.

Private API endpoints (all require `Authorization: Bearer …`):

- `GET /v1/status`: source counts and per-feed progress, errors and timestamps.
- `GET /v1/catalog?q=&provider=all&kind=all&page=1&sort=name&genre=`: indexed listings.
- `GET /v1/details?provider=ani&id=...`: cached/fresh episode metadata for an indexed title.
- `GET /v1/search?provider=ani&q=...&page=1`: live ani-cli or Luffy search.
- `GET /v1/streams?provider=ani&id=...&season=1&episode=1&audio=sub`: fresh stream resolution. `tv=true` adds private relay URLs when `tvRelayPort` is configured.

Inputs are bounded, SQL values are parameterized, and errors/logs omit tokens and signed URLs. There is no remote shell, arbitrary URL fetch endpoint or public administrative API. The optional TV relay accepts only resources registered from resolved provider streams and their HLS manifests; private upstream destinations are refused.

The server shares the Ani-Droid module's GPL-3.0-or-later license and bundled ani-cli/Luffy notices. Private deployment is separate from any public publication; follow the workspace's full publication audit before distributing repository history or artifacts publicly.


## Overnight full anime scan

Leave the catalog service running and the computer awake. No LLM or AI API is involved: pages and title metadata are parsed directly into SQLite. The full A–Z directory continues while the separate workers categorize titles, with persisted checkpoints and automatic retries. On the phone, tap **Refresh**, then expand the source counts: **Anime A–Z** shows the directory page progress and completion time. The categorized percentage covers stored titles; it does not by itself mean the entire provider directory has been collected.

At the default ten-second browse interval, several hundred directory pages plus the discovered genre pages can take several hours. Upstream failures may extend that. Titles without source genre metadata remain explicitly Uncategorized rather than receiving invented labels.

Luffy now falls back from Cinejoy to VixSrc through the shared bridge (2026-10-05). This server update applies to both Android and webOS without reinstalling clients. The fallback preserves master HLS audio, required headers and English captions.
