---
machine: BROKER_HOST / Android
subsystem: authentication
last-verified: 2026-09-15
status: deployed; live smoke tested
---
# Automatic Vertex renewal over local Wi-Fi

The BROKER_HOST (`USER@BROKER_HOST`) runs a private HTTPS token broker on port 8766. Google user ADC remains in its original `~/.config/gcloud/application_default_credentials.json`; it is never copied into the app. The broker refreshes against Google's fixed OAuth endpoint and returns short-lived bearer tokens. Phone requests to Vertex still go directly to Google, using project `YOUR_PROJECT_ID`.

Phone trust is established by SSH/adb provisioning of a random pairing secret and SHA-256 certificate fingerprint. Android verifies the exact certificate, its expiry and hostname, requires HTTPS, and refuses redirects. Tokens remain in memory; pairing settings live in app-private DataStore with backups disabled. Google access tokens and authorization headers are never logged. A compromised paired phone can obtain tokens while pairing remains valid: these are **user-scoped credentials**, not restricted to this one project. Do not expose the broker publicly or share the pairing secret.

The phone refreshes within 60 seconds of expiry and retries a rejected 401 once. Concurrent requests share refresh. Broker caches tokens until 90 seconds before expiry. The existing manual SSH token helper remains a fallback and explicitly disables broker mode.

## Current deployment

`~/.local/share/gemini-reader-broker/` on BROKER_HOST contains `broker.py`, `pairing.json`, `cert.pem`, `key.pem`, with private permissions. User systemd service is enabled, running, and lingering is enabled. No sudo, firewall or router changes were made. Service binds only `LOCAL_DEVICE_ADDRESS`.

```bash
ssh USER@BROKER_HOST 'systemctl --user status gemini-reader-broker --no-pager'
ssh USER@BROKER_HOST 'journalctl --user -u gemini-reader-broker -n 20 --no-pager'
ANDROID_SERIAL=LOCAL_DEVICE_ADDRESS tools/pair-token-broker.sh
```

Pairing requires the debug APK, authorized adb and SSH. It stops playback to apply settings, but does not clear books or analysis. Never run `--pairing-json` directly into a terminal/log; the pairing helper pipes it straight into app-private storage and deletes the temporary import afterward.

## Rebuild deployment on a new host

Copy `tools/token-broker/broker.py` into `~/.local/share/gemini-reader-broker/` and the supplied service into `~/.config/systemd/user/`. Configure user ADC interactively on that host first. Then run there:

```bash
python3 ~/.local/share/gemini-reader-broker/broker.py --init --host HOST_LAN_IP --project PROJECT_ID
systemctl --user daemon-reload
systemctl --user enable --now gemini-reader-broker
```

Initialization retains existing pairing. Use a stable LAN IP/DHCP reservation. The certificate lasts 365 days; before expiry, stop the service, move its private configuration directory to a protected backup, recreate/init it, restart, and re-pair every device. The same rotation revokes old pairing secrets. Stop immediately with `systemctl --user stop gemini-reader-broker` if a paired device is lost. Revoke the underlying Google ADC separately if those credentials are compromised.

BROKER_HOST must be awake and reachable on Wi-Fi for renewal and online to Google. If user ADC is revoked, Google may require another interactive login; this removes hourly token replacement, not all possible reauthentication. Broker errors do not silently switch billing projects. Promotional credit eligibility is still unverified.

## Sources

- [Google local ADC setup](https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment): protect the local refresh credentials.
- [Application Default Credentials](https://docs.cloud.google.com/docs/authentication/application-default-credentials): user ADC defaults to cloud-platform scope.
- [Google token types](https://docs.cloud.google.com/docs/authentication/token-types): bearer-token security properties.
