#!/usr/bin/env python3
"""End-to-end verification: deploy, drive the TC002 from the laptop over BLE, capture the frame tap, assert.

  python tools/verify.py                 # deploy out/device, run every fixture in tests/fixtures.json
  python tools/verify.py --no-deploy     # the build on the device is already the one to test
  python tools/verify.py --update        # (re)write the golden PNGs in tests/golden/ -- review the diffs!
  python tools/verify.py --only m_fits   # a single fixture

Fixture fields: name, send (frames as `send.py` lines; "M <300 chars>" expands to a 300-byte message),
gap_ms (pause between frames), wait_ms (before capturing), idle_ms (debug idle override, restarts the app),
disconnect (capture after the connection dropped), expect:
  lit_cols_min/lit_cols_max [lo, hi]  leftmost/rightmost lit column of the last frame (heartbeat excluded)
  color white|green|red|dark          dominant color of the last frame
  motion true|false                   >= 5 distinct frames in the last second, or <= 2
  interval [mean_lo, mean_hi, max]    frame intervals (ms) over the last second
  golden true                         last frame equals tests/golden/<name>.png (lit mask, heartbeat cleared)
Adding a feature = adding a fixture (and a golden with --update, reviewed by eye).
"""
import argparse
import asyncio
import json
import pathlib
import subprocess
import sys
import time

from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import screen  # noqa: E402
import send  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[1]
GOLD = ROOT / "tests/golden"
W, H = 52, 16
SERIAL = None


def adb(cmd):
    args = [screen.adb_path()] + (["-s", SERIAL] if SERIAL else []) + ["shell", cmd]
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def restart_app():
    adb("setprop ctl.stop zkswe")
    time.sleep(1)
    adb("setprop ctl.start zkswe")
    time.sleep(12)  # panel + BLE bring-up


def mask(rgb):
    return bytes(1 if (x != 51 or y != 0) and max(rgb[(y * W + x) * 3:(y * W + x) * 3 + 3]) > 0 else 0
                 for y in range(H) for x in range(W))


def lit_cols(rgb):
    m = mask(rgb)
    cols = [x for x in range(W) if any(m[y * W + x] for y in range(H))]
    return (cols[0], cols[-1]) if cols else (-1, -1)


def color(rgb):
    m = mask(rgb)
    lit = [rgb[i * 3:i * 3 + 3] for i in range(W * H) if m[i]]
    if len(lit) < W * H // 2:
        return "dark" if not lit else "text"
    r, g, b = (sum(p[c] for p in lit) // len(lit) for c in range(3))
    if min(r, g, b) > 100:
        return "white"
    return "green" if g > r and g > b else "red" if r > g and r > b else "other"


def golden_check(name, rgb, update):
    img = Image.frombytes("L", (W, H), bytes(255 * v for v in mask(rgb)))
    path = GOLD / f"{name}.png"
    if update or not path.exists():
        GOLD.mkdir(exist_ok=True)
        img.save(path)
        return True, "golden written"
    return list(img.getdata()) == list(Image.open(path).convert("L").getdata()), "golden mismatch"


def expand(line):
    if "<300 chars>" in line:
        return "M " + ("Lange zin " * 30)
    return line.replace("\\n", "\n")


async def run_fixture(fx, update):
    if fx.get("idle_ms"):
        adb(f"mkdir -p /tmp/kiep; echo {fx['idle_ms']} > /tmp/kiep/idle_ms")
        restart_app()
    lines = [expand(l) for l in fx["send"]]
    dev = await send.find()
    frames = None
    async with send.BleakClient(dev) as client:
        for i, line in enumerate(lines):
            if i:
                await asyncio.sleep(fx.get("gap_ms", 50) / 1000)
            cmd, _, arg = line.partition(" ")
            uuid, data = send.frame(cmd, arg or None)
            await client.write_gatt_char(uuid, data, response=True)
        if not fx.get("disconnect"):
            await asyncio.sleep(fx["wait_ms"] / 1000)
            frames = screen.pull(None, SERIAL)
    if fx.get("disconnect"):
        await asyncio.sleep(fx["wait_ms"] / 1000)
        frames = screen.pull(None, SERIAL)
    if fx.get("idle_ms"):
        adb("rm -f /tmp/kiep/idle_ms")
        restart_app()
    ts, rgb = frames[-1]
    # "the last second" is measured against the device clock, not the newest frame: a held tail writes no
    # frames, so the ring's newest entries would otherwise always be the end of the scroll
    now_ms = float(adb("cat /proc/uptime").split()[0]) * 1000
    last_s = [f for f in frames if f[0] >= now_ms - 1000]
    e = fx["expect"]
    fails = []
    lo, hi = lit_cols(rgb)
    if "lit_cols_min" in e and not (e["lit_cols_min"][0] <= lo <= e["lit_cols_min"][1]):
        fails.append(f"lit_cols_min={lo}")
    if "lit_cols_max" in e and not (e["lit_cols_max"][0] <= hi <= e["lit_cols_max"][1]):
        fails.append(f"lit_cols_max={hi}")
    if "color" in e and color(rgb) != e["color"]:
        fails.append(f"color={color(rgb)}")
    if "motion" in e:
        distinct = len({f[1] for f in last_s})
        moving = distinct >= 5
        if moving != e["motion"]:
            fails.append(f"motion distinct={distinct}")
    if "interval" in e and len(last_s) > 2:
        d = [last_s[i + 1][0] - last_s[i][0] for i in range(len(last_s) - 1)]
        mean = sum(d) / len(d)
        if not (e["interval"][0] <= mean <= e["interval"][1]) or max(d) > e["interval"][2]:
            fails.append(f"interval mean={mean:.1f} max={max(d)}")
    if e.get("golden"):
        ok, msg = golden_check(fx["name"], rgb, update)
        if not ok:
            fails.append(msg)
            (ROOT / "out").mkdir(exist_ok=True)
            screen.png(rgb).save(ROOT / f"out/fail_{fx['name']}.png")
    return fails


def main():
    global SERIAL
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-deploy", action="store_true")
    ap.add_argument("--update", action="store_true")
    ap.add_argument("--only")
    ap.add_argument("--serial")
    a = ap.parse_args()
    SERIAL = a.serial
    if not a.no_deploy:
        cmd = [sys.executable, str(ROOT / "tools/deploy.py")] + (["--serial", a.serial] if a.serial else [])
        subprocess.run(cmd, check=True)
        time.sleep(12)
    fixtures = json.loads((ROOT / "tests/fixtures.json").read_text(encoding="utf-8"))
    failed = 0
    for fx in fixtures:
        if a.only and fx["name"] != a.only:
            continue
        try:
            fails = asyncio.run(run_fixture(fx, a.update))
        except Exception as ex:  # a fixture must never abort the run
            fails = [f"{type(ex).__name__}: {ex}"]
        failed += bool(fails)
        print(f"{'PASS' if not fails else 'FAIL'} {fx['name']}" + (f": {', '.join(fails)}" if fails else ""), flush=True)
    total = sum(1 for fx in fixtures if not a.only or fx["name"] == a.only)
    print(f"{total - failed}/{total} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
