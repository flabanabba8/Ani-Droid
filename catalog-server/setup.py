#!/usr/bin/env python3
"""Create private TLS configuration and optionally install the user's systemd service."""
import argparse, base64, hashlib, json, os, pathlib, secrets, subprocess

p = argparse.ArgumentParser()
p.add_argument('--state', type=pathlib.Path, default=pathlib.Path.home()/'.local/share/ani-droid-catalog')
p.add_argument('--bind', default='127.0.0.1')
p.add_argument('--port', type=int, default=8765)
p.add_argument('--install-service', action='store_true')
args = p.parse_args()
args.state.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(args.state,0o700)
config_file=args.state/'config.json'
java_home=pathlib.Path(os.environ['JAVA_HOME'])
if not config_file.exists():
    config={'bind':args.bind,'port':args.port,'token':secrets.token_urlsafe(32),'keystorePassword':secrets.token_urlsafe(32),'requestIntervalSeconds':10}
    env=dict(os.environ, ANI_CATALOG_KEYSTORE_PASSWORD=config['keystorePassword'])
    subprocess.run([str(java_home/'bin/keytool'),'-genkeypair','-alias','catalog','-keyalg','RSA','-keysize','2048','-validity','3650',
                    '-dname','CN=Ani-Droid Catalog','-ext','SAN=dns:localhost,ip:127.0.0.1','-storetype','PKCS12',
                    '-keystore',str(args.state/'server.p12'),'-storepass:env','ANI_CATALOG_KEYSTORE_PASSWORD','-noprompt'],env=env,check=True,capture_output=True)
    config_file.write_text(json.dumps(config));os.chmod(config_file,0o600);os.chmod(args.state/'server.p12',0o600)
else:
    config=json.loads(config_file.read_text())
env=dict(os.environ, ANI_CATALOG_KEYSTORE_PASSWORD=config['keystorePassword'])
cert=subprocess.run([str(java_home/'bin/keytool'),'-exportcert','-alias','catalog','-keystore',str(args.state/'server.p12'),
                     '-storepass:env','ANI_CATALOG_KEYSTORE_PASSWORD'],env=env,check=True,capture_output=True).stdout
(args.state/'certificate.der').write_bytes(cert)
pairing={'url':f"https://localhost:{config['port']}",'token':config['token'],'fingerprint':hashlib.sha256(cert).hexdigest()}
(args.state/'connection.json').write_text(json.dumps(pairing));os.chmod(args.state/'connection.json',0o600)
if args.install_service:
    root=pathlib.Path(__file__).resolve().parent
    launcher=root/'build/install/catalog-server/bin/catalog-server'
    if not launcher.exists(): raise SystemExit('Run ./gradlew :catalog-server:installDist first.')
    service_dir=pathlib.Path.home()/'.config/systemd/user';service_dir.mkdir(parents=True,exist_ok=True)
    # Runtime paths belong only in the local service, never in versioned defaults.
    def quote(value): return '"'+str(value).replace('\\','\\\\').replace('"','\\"').replace('%','%%')+'"'
    unit='\n'.join(['[Unit]','Description=Ani-Droid private catalog','After=network-online.target','', '[Service]',
        'Type=simple','Environment='+quote('JAVA_HOME='+str(java_home)), 'ExecStart='+quote(launcher)+' '+quote(config_file),
        'Restart=on-failure','RestartSec=10','SuccessExitStatus=143','UMask=0077','NoNewPrivileges=true','PrivateTmp=true','', '[Install]','WantedBy=default.target',''])
    (service_dir/'ani-droid-catalog.service').write_text(unit)
    subprocess.run(['systemctl','--user','daemon-reload'],check=True)
    subprocess.run(['systemctl','--user','enable','--now','ani-droid-catalog.service'],check=True,capture_output=True)
print('Catalog configuration ready. Connection details are in the private state directory; no credentials printed.')
