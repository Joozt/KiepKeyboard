#!/usr/bin/env python3
"""Fetch the FlyThings Z21 SDK packages and the BlueZ artifacts into .cache/sdk, pinned by SHA-256.

A package with "sha256": null in tools/sdk-lock.json is pinned on its first successful download
(the hash is written back into the lock file); afterwards a mismatch aborts the build.
"""
import hashlib
import json
import pathlib
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "tools/sdk-lock.json"
CACHE = ROOT / ".cache/sdk"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch(url, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if not dest.exists():
        print("fetch", url)
        urllib.request.urlretrieve(url, dest)
    return dest


def main():
    lock = json.loads(LOCK.read_text())
    changed = False
    for p in lock["packages"]:
        z = fetch(f'{lock["sdk_base"]}/{p["name"]}/{p["version"]}.zip', CACHE / f'{p["name"]}-{p["version"]}.zip')
        h = sha256(z)
        if p["sha256"] is None:
            p["sha256"] = h
            changed = True
            print("pinned", p["name"], h)
        elif p["sha256"] != h:
            sys.exit(f"sha256 mismatch for {z}: {h}")
        out = CACHE / p["name"]
        if not out.exists():
            zipfile.ZipFile(z).extractall(out)
    b = lock["bluez"]
    base = b["base"].format(commit=b["commit"])
    for h in b["headers"]:
        fetch(f"{base}/include/ble/{h}", CACHE / "bluez/include/ble" / h)
    lib = fetch(f"{base}/lib/libgatt-server.a", CACHE / "bluez/lib/libgatt-server.a")
    if sha256(lib) != b["lib_sha256"]:
        sys.exit("libgatt-server.a sha256 mismatch")
    # Spike stand-in for the loader's UI resource (the demo repo is GPL-3: kept in the cache, never committed).
    # Task 5 replaces it with our own res/ui/main.ftu.
    fetch(f'https://raw.githubusercontent.com/UlanziTechnology/Ulanzi-U-Clock-TC002/{b["commit"]}/Z21_TC002_Demo/ui/main.ftu',
          CACHE / "demo-ui/main.ftu")
    if changed:
        LOCK.write_text(json.dumps(lock, indent=2) + "\n")
    print("sdk ok:", CACHE)


if __name__ == "__main__":
    main()
