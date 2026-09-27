#!/usr/bin/env python3
"""Sample PageCast RAM over ADB; no root or third-party Python packages required."""
import argparse
import datetime
import os
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'), help='ADB device serial')
parser.add_argument('--interval', type=float, default=2, help='Seconds between samples (minimum 1)')
parser.add_argument('--once', action='store_true')
args = parser.parse_args()
if args.interval < 1:
    parser.error('--interval must be at least 1 second')
command = ['adb'] + (['-s', args.serial] if args.serial else [])
peak = 0.0
print('Time      PSS MB   RSS MB   Peak PSS MB (this monitoring session)', flush=True)
try:
    while True:
        result = subprocess.run(command + ['shell', 'dumpsys', 'meminfo', '-s', 'com.geminireader'], capture_output=True, text=True, timeout=15)
        if result.returncode:
            parser.exit(1, result.stderr)
        pss = re.search(r'TOTAL PSS:\s*(\d+)', result.stdout)
        rss = re.search(r'TOTAL RSS:\s*(\d+)', result.stdout)
        now = datetime.datetime.now().strftime('%H:%M:%S')
        if pss:
            value = int(pss[1]) * 1024 / 1_000_000
            peak = max(peak, value)
            resident = f'{int(rss[1]) * 1024 / 1_000_000:8.1f}' if rss else '     n/a'
            print(f'{now} {value:8.1f} {resident} {peak:13.1f}', flush=True)
        else:
            print(f'{now} PageCast is not running or no memory summary is available.', flush=True)
        if args.once:
            break
        time.sleep(args.interval)
except KeyboardInterrupt:
    pass
except (FileNotFoundError, subprocess.TimeoutExpired) as error:
    parser.exit(1, f'{error}\nSource tools/env.sh and check the ADB connection.\n')
