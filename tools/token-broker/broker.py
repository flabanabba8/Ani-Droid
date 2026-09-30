#!/usr/bin/env python3
"""Private LAN HTTPS broker. Logs neither access tokens nor authorization headers."""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import threading
import time
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Tokens:
    def __init__(self, adc):
        self.adc = adc
        self.lock = threading.Lock()
        self.value = ""
        self.expiry = 0

    def get(self, force=False):
        with self.lock:
            if force or time.time() + 90 >= self.expiry:
                credentials = json.loads(self.adc.read_text())
                if credentials.get("type") != "authorized_user":
                    raise ValueError("This broker requires user ADC")
                body = urllib.parse.urlencode({"grant_type": "refresh_token", **{
                    key: credentials[key] for key in ("client_id", "client_secret", "refresh_token")
                }}).encode()
                # Fixed Google origin; never honor a token_uri supplied by an incoming request.
                request = urllib.request.Request("https://oauth2.googleapis.com/token", body,
                                                 {"Content-Type": "application/x-www-form-urlencoded"})
                with urllib.request.urlopen(request, timeout=20) as response:
                    result = json.load(response)
                self.value = result["access_token"]
                self.expiry = time.time() + int(result["expires_in"])
            return {"access_token": self.value, "expires_in": max(1, int(self.expiry - time.time()))}


def handler(config, tokens):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, status, body):
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            if self.path != "/token":
                self.reply(404, {"error": "not found"})
                return
            if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + config["secret"]):
                self.reply(401, {"error": "pairing required"})
                return
            try:
                result = tokens.get(self.headers.get("X-Reader-Refresh") == "1")
                self.reply(200, {**result, "project": config["project"]})
                print("token issued", flush=True)
            except Exception:
                self.reply(503, {"error": "ADC refresh unavailable on broker"})
                print("ADC refresh failed (details suppressed)", flush=True)
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, default=Path.home() / ".local/share/gemini-reader-broker")
    parser.add_argument("--init", action="store_true")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--project", default="", help="Google Cloud project ID (required with --init)")
    parser.add_argument("--pairing-json", action="store_true", help="Sensitive: pipe directly to app-private storage, never logs")
    args = parser.parse_args()
    os.umask(0o077)
    folder = args.directory
    folder.mkdir(parents=True, exist_ok=True)
    config_path = folder / "pairing.json"
    if args.init:
        if not args.project:
            parser.error("--project is required with --init")
        if config_path.exists():
            print("Existing broker configuration retained")
            return
        # Host is an IP address; used for certificate SAN and listening address only.
        import ipaddress
        ipaddress.ip_address(args.host)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "365",
                        "-keyout", str(folder / "key.pem"), "-out", str(folder / "cert.pem"),
                        "-subj", "/CN=Gemini Reader private token broker", "-addext", f"subjectAltName=IP:{args.host}"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        der = ssl.PEM_cert_to_DER_cert((folder / "cert.pem").read_text())
        config = {"url": f"https://{args.host}:{args.port}/token", "pin": hashlib.sha256(der).hexdigest(),
                  "secret": secrets.token_urlsafe(32), "project": args.project, "host": args.host, "port": args.port}
        with config_path.open("x") as output:
            json.dump(config, output)
        print("Private broker configuration created")
        return
    config = json.loads(config_path.read_text())
    if args.pairing_json:
        print(json.dumps({key: config[key] for key in ("url", "pin", "secret", "project")}))
        return
    server = ThreadingHTTPServer((config["host"], config["port"]), handler(config, Tokens(Path.home() / ".config/gcloud/application_default_credentials.json")))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(folder / "cert.pem", folder / "key.pem")
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print("Private HTTPS token broker ready", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
