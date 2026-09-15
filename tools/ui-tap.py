#!/usr/bin/env python3
"""Tap an exact visible text label using adb/uiautomator; respects ANDROID_SERIAL."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

label = sys.argv[1]
subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/reader-window.xml'], check=True, stdout=subprocess.DEVNULL)
tree = ET.fromstring(subprocess.check_output(['adb', 'shell', 'cat', '/sdcard/reader-window.xml']))
for node in tree.iter('node'):
    if node.get('text') == label or node.get('content-desc') == label:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
        break
else:
    sys.exit(f'Visible label not found: {label}')
