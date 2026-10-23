#!/usr/bin/env python3
"""Move existing One UI Home Tweaks settings to the renamed, signed release app.

Requires ADB root access. No restart or authentication setting is changed. The old
app stays installed until all copied preferences pass byte-for-byte verification.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

OLD_PACKAGE = 'net.ryuya_dev.oneuilivecolors'
NEW_PACKAGE = 'dev.ryuya.oneuihometweaks'
CERTIFICATE = '7938e1850451b95c45c8f138a768c9da7c5d9db7fb61a883ac8dc724077ddb03'
PREFERENCE_FILES = ('modes.xml', 'app_ui.xml', 'grid_save.xml')
SCOPES = ('com.sec.android.app.launcher/0', 'com.android.systemui/0',
          'com.samsung.android.app.dressroom/0', 'system/0')


class Migration:
    def __init__(self, arguments):
        self.arguments = arguments
        self.adb_prefix = [str(arguments.adb), '-P', str(arguments.adb_port), '-s', arguments.serial]
        self.tools = arguments.sdk / 'build-tools/37.0.0'

    @staticmethod
    def run(command):
        result = subprocess.run(command, check=True, text=True, capture_output=True, timeout=120)
        return result.stdout.strip()

    def adb(self, *arguments):
        return self.run(self.adb_prefix + list(arguments))

    def root(self, command):
        return self.adb('shell', 'su -c ' + shlex.quote(command))

    def verify_release(self, apk, package):
        signature = self.run([str(self.tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)])
        if CERTIFICATE not in signature:
            raise RuntimeError('Release signing certificate does not match the existing app')
        badging = self.run([str(self.tools / 'aapt2'), 'dump', 'badging', str(apk)])
        if f"package: name='{package}'" not in badging or 'application-debuggable' in badging:
            raise RuntimeError('Unexpected package or debuggable APK')

    def migrate(self):
        if self.root('id -u') != '0':
            raise RuntimeError('ADB root access is required')
        self.verify_release(self.arguments.apk, NEW_PACKAGE)
        original_apk = self.adb('shell', f'pm path {OLD_PACKAGE}').removeprefix('package:')
        if not original_apk.startswith('/data/app/') or '\n' in original_apk:
            raise RuntimeError('Expected one installed legacy base APK')
        with tempfile.TemporaryDirectory(prefix='oneui-migration-') as temporary:
            local_apk = Path(temporary) / 'legacy-release.apk'
            self.adb('pull', original_apk, str(local_apk))
            self.verify_release(local_apk, OLD_PACKAGE)

        self.adb('shell', f'am force-stop {OLD_PACKAGE}')
        timestamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        backup = '/data/local/tmp/oneuihometweaks-migration-' + timestamp
        self.root(f'mkdir {backup}')
        self.root(f'chmod 700 {backup}')
        copied = []
        for filename in PREFERENCE_FILES:
            source = f'/data/user/0/{OLD_PACKAGE}/shared_prefs/{filename}'
            if self.root(f'if test -f {source}; then echo present; fi') == 'present':
                self.root(f'cp {source} {backup}/{filename}')
                self.root(f'chmod 600 {backup}/{filename}')
                copied.append(filename)
        if 'modes.xml' not in copied:
            raise RuntimeError('Legacy settings are missing; the old app has been retained')
        print('Private device backup:', backup, flush=True)

        self.adb('install', '--no-incremental', '-r', str(self.arguments.apk.resolve()))
        self.adb('shell', f'am force-stop {NEW_PACKAGE}')
        package_info = self.adb('shell', f'dumpsys package {NEW_PACKAGE}')
        user = re.search(r'\buserId=(\d+)\b', package_info)
        if not user:
            raise RuntimeError('Cannot resolve the new app UID; the old app has been retained')
        uid = int(user.group(1))
        if not 10000 <= uid < 100000:
            raise RuntimeError('Unexpected app UID')
        destination = f'/data/user/0/{NEW_PACKAGE}/shared_prefs'
        self.root(f'mkdir -p {destination}')
        self.root(f'chown {uid}:{uid} {destination}')
        self.root(f'chmod 700 {destination}')
        for filename in copied:
            self.root(f'cp {backup}/{filename} {destination}/{filename}')
            self.root(f'chown {uid}:{uid} {destination}/{filename}')
            self.root(f'chmod 600 {destination}/{filename}')
            before = self.root(f'sha256sum {backup}/{filename}').split()[0]
            after = self.root(f'sha256sum {destination}/{filename}').split()[0]
            if before != after:
                raise RuntimeError('Preference verification failed; the old app has been retained')
        self.root(f'restorecon -RF {destination}')
        print('Verified preference files:', len(copied), flush=True)
        # Let the framework discover the new package before calling this script's
        # final activation step. No framework database is edited directly.
        print('Settings copied. Open the new app and verify them before retiring the legacy package.')
        print('Activate the new module with scopes:', ', '.join(SCOPES))
        print('Then disable the old module and uninstall:', OLD_PACKAGE)
        print('Reload the system last to refresh its resident module. This script does not restart it.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--adb-port', type=int, default=5037)
    parser.add_argument('--adb', type=Path, default=Path.home() / 'Library/Android/sdk/platform-tools/adb')
    parser.add_argument('--sdk', type=Path, default=Path.home() / 'Library/Android/sdk')
    Migration(parser.parse_args()).migrate()


if __name__ == '__main__':
    main()
