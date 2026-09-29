"""The emulator helper must never boot-poll or unlock an attached phone."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class EmulatorTargetTest(unittest.TestCase):
    def test_targets_emulator_with_phone_connected(self):
        with tempfile.TemporaryDirectory() as folder:
            sdk = Path(folder)
            (sdk / 'platform-tools').mkdir()
            adb = sdk / 'platform-tools/adb'
            adb.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$ADB_CALLS"
case "$*" in
  devices) printf 'List of devices attached\\nphone device\\nemulator-5554 device\\n' ;;
  '-s emulator-5554 wait-for-device') ;;
  '-s emulator-5554 shell getprop sys.boot_completed') echo 1 ;;
  '-s emulator-5554 shell input keyevent 82') ;;
  *) exit 42 ;;
esac
''')
            adb.chmod(0o700)
            log = sdk / 'calls'
            env = dict(os.environ, ANDROID_HOME=folder, ADB_CALLS=str(log))
            env.pop('ANDROID_SERIAL', None)
            subprocess.run(['bash', 'tools/emulator.sh', '--headless'], cwd=ROOT,
                           env=env, check=True, capture_output=True, timeout=10)
            self.assertEqual(log.read_text().splitlines(), [
                'devices', '-s emulator-5554 wait-for-device',
                '-s emulator-5554 shell getprop sys.boot_completed',
                '-s emulator-5554 shell input keyevent 82'])

    def test_rejects_phone_serial(self):
        result = subprocess.run(['bash', 'tools/emulator.sh', '--headless'], cwd=ROOT,
                                env=dict(os.environ, ANDROID_SERIAL='phone'),
                                capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('must select an emulator', result.stderr)


if __name__ == '__main__':
    unittest.main()
