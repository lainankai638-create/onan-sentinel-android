#!/usr/bin/env python3
"""UI screenshots on a disposable Android emulator; never clears app data.
Install app + androidTest APK first. ADB may name the local SDK adb executable.
Only UiAcceptanceTest runs: historical StoreInstrumentedTest is destructive.
"""
import argparse
import os
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--widths', type=int, nargs='+', choices=(320,360,393), default=(320,360,393))
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Disposable emulator serial required; real phones are not supported')
adb = [os.environ.get('ADB', 'adb'), '-s', args.serial]
def call(*cmd):
    return subprocess.run(adb + list(cmd), check=True, capture_output=True, text=True, timeout=240).stdout
if call('shell', 'getprop', 'ro.hardware').strip() not in ('ranchu', 'goldfish'):
    parser.error('Hardware is not a supported emulator')
args.output.mkdir(parents=True, exist_ok=True)
try:
    for width in args.widths:
        for scale, mode in ((1, 'no'), (2, 'yes')):
            name = f'{width}dp-font{scale}-' + ('light' if mode == 'no' else 'dark')
            call('shell', 'wm', 'size', f'{width*2}x1400')
            call('shell', 'wm', 'density', '320')
            call('shell', 'settings', 'put', 'system', 'font_scale', str(scale))
            call('shell', 'cmd', 'uimode', 'night', mode)
            # Configuration changes and the previous instrumentation teardown are asynchronous.
            # This is a disposable emulator: stop only the test target, never clear its data.
            call('shell', 'am', 'force-stop', 'cn.onan.sentinel.preview')
            time.sleep(3)
            result = call('shell', 'am', 'instrument', '-w', '-e', 'class',
                          'cn.onan.sentinel.UiAcceptanceTest',
                          'cn.onan.sentinel.preview.test/androidx.test.runner.AndroidJUnitRunner')
            (args.output / (name + '.log')).write_text(result)
            if 'OK (1 test)' not in result:
                raise RuntimeError(f'{name} failed; see saved log')
            call('pull', '/sdcard/Android/data/cn.onan.sentinel.preview/files/ui-evidence', str(args.output / name))
            print(name + ': passed', flush=True)
finally:
    call('shell', 'settings', 'put', 'system', 'font_scale', '1')
    call('shell', 'cmd', 'uimode', 'night', 'auto')
    call('shell', 'wm', 'size', 'reset')
    call('shell', 'wm', 'density', 'reset')
