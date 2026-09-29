#!/usr/bin/env python3
"""Push out/device to the TC002 over adb and restart the loader. A power cycle returns to stock.

  python tools/deploy.py [--serial IP:5555|USBSERIAL] [--logs]
  python tools/deploy.py --restore        # remove the override, restart the stock app
"""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEV = ROOT / "out/device"
TARGET = "/tmp/kiep"


def adb_path():
    found = shutil.which("adb")
    if found:
        return found
    sdk = pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"
    if sdk.exists():
        return str(sdk)
    sys.exit("adb not found: install Android platform-tools or put adb on PATH")


ADB = adb_path()
SERIAL = []


def adb(*args, check=True):
    return subprocess.run([ADB, *SERIAL, *args], check=check, text=True)


def restart():
    # ctl.restart is silently ignored by this init; stop + start works
    adb("shell", "setprop ctl.stop zkswe; sleep 1; setprop ctl.start zkswe")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", help="ip:5555 (Wi-Fi adb on stock firmware) or a USB serial")
    ap.add_argument("--logs", action="store_true", help="tail `logcat -s kiep` after deploying")
    ap.add_argument("--restore", action="store_true", help="remove the override and restart the stock app")
    a = ap.parse_args()
    if a.serial:
        if ":" in a.serial:
            adb("connect", a.serial, check=False)
        SERIAL[:] = ["-s", a.serial]
    if a.restore:
        adb("shell", f"rm -rf /tmp/EasyUI.cfg {TARGET}")
        restart()
        print("stock app restarted")
        return
    if not (DEV / "lib/libzkgui.so").exists():
        sys.exit("no bundle: run `docker compose run --rm build` first")
    cfg = json.loads((DEV / "EasyUI.cfg.json").read_text().replace("@ROOT@", TARGET))
    (ROOT / "out/EasyUI.cfg").write_text(json.dumps(cfg))
    adb("shell", f"mkdir -p {TARGET}/lib {TARGET}/ui {TARGET}/tr")
    adb("push", str(DEV / "lib/libzkgui.so"), f"{TARGET}/lib/libzkgui.so")
    for f in (DEV / "ui").iterdir():
        if f.name != ".gitkeep":
            adb("push", str(f), f"{TARGET}/ui/{f.name}")
    adb("push", str(ROOT / "out/EasyUI.cfg"), "/tmp/EasyUI.cfg")
    restart()
    print("deployed to", TARGET)
    if a.logs:
        subprocess.run([ADB, *SERIAL, "logcat", "-v", "time", "-s", "kiep"])


if __name__ == "__main__":
    main()
