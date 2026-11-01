---
machine: build-host / TIZEN_TV
subsystem: tizen-tv
last-verified: 2026-11-01
status: developer mode, signing, app port and 1080p playback VERIFIED
---
# Samsung Tizen TV target

Real values (IP, DUID) live outside the repo in `~/.config/anidroid/tizen-tv.env`. This page uses `TIZEN_TV` and `TIZEN_TV_DUID` placeholders.

## Device (observed over sdb, 2026-11-01)

| Field | Value |
| --- | --- |
| Model | UN55TU8300FXZA (55" TU8300, 2020) |
| Platform | Tizen 5.5 (`platform_version:5.5`, `product_version:4.0`) |
| Profile | `tv`, vendor Samsung, `can_launch:tv-samsung` |
| CPU | armv7 |
| sdbd | 2.2.31, plugin 3.8.0_TV_REL |
| Root | `sdbd_rootperm:disabled`; no root shell |
| Shell | `intershell_support:disabled`; only `sdb shell 0 <cmd>` vendor commands (e.g. `getduid`, `vd_applist`) |
| File sync | push/pull supported |
| USB install | not supported (Samsung docs) |

## Enabling developer mode (VERIFIED on this TV)

1. Open the Apps screen. There is no input field; just enter `12345` while it is open.
2. This remote has no number keys. What worked: SmartThings phone app, open the TV's remote, tap the `123` icon in the right-hand corner, enter 1 2 3 4 5.
3. Popup: Developer mode On, Host PC IP = the build host's LAN IP, OK, restart TV.
4. After restart, "Develop Mode" shows at the top of the Apps screen.

Developer mode only accepts sdb from the Host PC IP entered on the TV. If the build host's IP changes, re-enter it on the TV.

## Connecting

```bash
source ~/.config/anidroid/tizen-tv.env
~/tizen-studio/tools/sdb connect "$TIZEN_TV_SDB"
~/tizen-studio/tools/sdb devices
~/tizen-studio/tools/sdb -s "$TIZEN_TV_SDB" shell 0 getduid
```

Port 26101 is the device's sdb port. The sdb server runs locally on 26099.

## Tooling on the build host

- Tizen Studio 10.0 at `~/tizen-studio` (sdb, `tools/ide/bin/tizen` CLI, Certificate Manager, Device Manager).
- TV extensions present: `platforms/*/tv`, `tv-samsung`, `tv-samsung-public`.
- Certificate Manager is unreadable under the Breeze Dark desktop theme (2017 Eclipse/SWT 3.106). VERIFIED fix 2026-11-01: force a light GTK theme and XWayland:
  `GTK_THEME=Adwaita:light GDK_BACKEND=x11 WEBKIT_DISABLE_COMPOSITING_MODE=1 ~/tizen-studio/tools/certificate-manager/certificate-manager`
  The same env likely applies to Device Manager and the Tizen Studio IDE.

## Signing (VERIFIED 2026-11-01)

Samsung TV installs need a **Samsung** certificate profile (type Samsung, device TV), not a plain Tizen one. It needs a Samsung Developers account sign-in for the author certificate, and the distributor certificate must list `TIZEN_TV_DUID`. Back up `author.p12`; app updates must use the same author certificate. Source: developer.samsung.com Creating Certificates and TV Device guides (read 2026-11-01).

Profile `anidroid-tv` (Samsung/TV) exists; keys in `~/SamsungCertificate/anidroid-tv/` (author.p12, distributor.p12). Never commit them.

End-to-end check (user saw the test screen on the TV): a minimal `tv-samsung` web app (`required_version="5.5"`) packaged, installed and launched on the TV:

```bash
T=~/tizen-studio/tools/ide/bin/tizen
$T package -t wgt -s anidroid-tv -- <app-dir>        # output name = <name> from config.xml
$T install -n <file>.wgt -t UN55TU8300FXZA            # -t is the sdb device name
$T run -p <PackageId>.<appname> -t UN55TU8300FXZA
```

Test packages were uninstalled afterwards (`$T uninstall -p <PackageId>.<appname> -t UN55TU8300FXZA`).

## Background web service (VERIFIED 2026-11-01)

A Tizen web service bundled in the app package runs on this TV with an unprivileged developer certificate. It can make a pinned HTTPS request to a self-signed server, which the UI's browser cannot. Probe results:

- UI: Chromium 69.0.3497.106 (`SMART-TV; LINUX; Tizen 5.5`). XHR to a self-signed HTTPS server fails (as expected).
- Service runtime: **Node v4.4.3** (V8 4.5, OpenSSL 1.0.2zl). `http`, `https`, `tls`, `crypto`, `fs`, `net`, `os`, `path` all load.
- `https.request({rejectUnauthorized:false})` plus a SHA-256 check of `res.socket.getPeerCertificate().raw` worked; the fingerprint matched.
- Start from UI: `tizen.application.launchAppControl(new tizen.ApplicationControl('http://tizen.org/appcontrol/operation/service'), '<PackageId>.service', ...)` succeeded. The service got `onStart` then `onRequest`.
- Service to UI: UI `tizen.messageport.requestLocalMessagePort('Name')`, service `tizen.messageport.requestRemoteMessagePort('<PackageId>.<uiapp>', 'Name').sendMessage([{key, value}])`. Delivered.
- Privileges used: `internet`, `application.launch`.

config.xml declaration that worked (no `type` attribute):

```xml
<tizen:service id="PackageId.service">
  <tizen:content src="service/service.js"/>
  <tizen:name>Name</tizen:name>
  <tizen:description>...</tizen:description>
</tizen:service>
```

Service entry: `module.exports.onStart / onRequest / onExit`.

Not yet tested: a persistent writable path for the private connection file (webOS used `/media/internal`), and UI-to-service requests (needs a second message port in the other direction).

## App port (VERIFIED 2026-11-01)

See [tizen-tv/README.md](../tizen-tv/README.md). The shared webOS app/service run unchanged apart from Tizen key codes (Back 10009, PlayPause 10252), a D-pad route into the playback buttons, and a configurable data dir. Verified on the TV: catalog, search, details, Luffy stream, playback, 600 KB message round trip, HUD navigation.

- Trusted message ports (`requestTrustedLocalMessagePort`) so only apps signed with our certificate can call the service, matching webOS Luna scoping.
- Message ports throw `QuotaExceededError` between 8 and 16 KB per message; both sides chunk JSON at 2000 chars.
- Service data dir: `/opt/usr/home/owner/apps_rw/<PackageId>/data` (survives reinstall/update). `/media/internal` does not exist.
- sdb can only push to `/home/owner/share/tmp/sdk_tools` (world-readable). `provision.sh` pushes the connection there; the service imports it on start and deletes it.
- Debugging without screenshots: `sdb shell 0 debug AniDroidTV.app` prints an inspector port; `sdb forward tcp:9222 tcp:<port>` then use the Chrome DevTools protocol (`Runtime.evaluate`, `Input.dispatchKeyEvent`).

## Video quality on Tizen (VERIFIED 2026-11-01)

- The HTML5 player starts at the lowest HLS variant and did not climb in 40 s, regardless of variant order.
- A master with a single variant fails after ~2.5 s with `MEDIA_ELEMENT_ERROR: Format error`. The same variant listed twice works only if the copy has a different BANDWIDTH.
- So the catalog relay (`MediaRelay.limitQuality`) keeps only the best variant at or below the chosen cap (Auto = highest) and adds a 98%-bandwidth copy when one is left. Better Call Saul S1E1 then played at 1920x1080 on Auto.
