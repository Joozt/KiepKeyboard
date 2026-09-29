#!/usr/bin/env python3
"""See what the TC002 shows: pull the frame ring (Debug builds) and render it.

  python tools/screen.py                 # ASCII art of the newest frame + cadence stats
  python tools/screen.py --png out/screen.png
  python tools/screen.py --gif out/motion.gif   # the last ~2 s of frames, real timing
  python tools/screen.py --stats         # frame intervals: mean / stdev / max / count > 45 ms
  python tools/screen.py --raw frames.bin        # offline, from a saved ring
"""
import argparse
import os
import pathlib
import shutil
import statistics
import struct
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw

W, H = 52, 16
SLOT = 8 + W * H * 3


def adb_path():
    found = shutil.which("adb")
    if found:
        return found
    sdk = pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"
    if sdk.exists():
        return str(sdk)
    sys.exit("adb not found")


def pull(raw=None, serial=None):
    """Return [(timestamp_ms, rgb_bytes)] sorted by time."""
    if raw:
        data = open(raw, "rb").read()
    else:  # this adbd has no exec-out and `shell cat` mangles binaries: pull to a temp file
        tmp = pathlib.Path(tempfile.gettempdir()) / "kiep-frames.bin"
        cmd = [adb_path()] + (["-s", serial] if serial else []) + ["pull", "/tmp/kiep/frames.bin", str(tmp)]
        subprocess.run(cmd, check=True, capture_output=True)
        data = tmp.read_bytes()
    if data[:8] != b"KIEPTAP1":
        sys.exit("no frame tap on the device (Debug build deployed?)")
    slots, _next = struct.unpack_from("<II", data, 8)
    frames = []
    for i in range(slots):
        off = 16 + i * SLOT
        (ts,) = struct.unpack_from("<q", data, off)
        if ts:
            frames.append((ts, data[off + 8: off + SLOT]))
    return sorted(frames)


def ascii_art(rgb):
    ramp = " .:-=+*#%@"
    rows = []
    for y in range(H):
        row = ""
        for x in range(W):
            i = (y * W + x) * 3
            row += ramp[min(9, max(rgb[i:i + 3]) * 10 // 256)]
        rows.append(row)
    return "\n".join(rows)


def png(rgb, scale=10):
    img = Image.new("RGB", (W * scale, H * scale), (12, 12, 12))
    d = ImageDraw.Draw(img)
    for y in range(H):
        for x in range(W):
            i = (y * W + x) * 3
            r, g, b = rgb[i], rgb[i + 1], rgb[i + 2]
            d.ellipse([x * scale + 1, y * scale + 1, x * scale + scale - 2, y * scale + scale - 2],
                      fill=(max(r, 20), max(g, 20), max(b, 20)))
    return img


def intervals(frames):
    return [frames[i + 1][0] - frames[i][0] for i in range(len(frames) - 1)]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--png")
    ap.add_argument("--gif")
    ap.add_argument("--ascii", action="store_true")
    ap.add_argument("--stats", action="store_true")
    ap.add_argument("--raw")
    ap.add_argument("--serial")
    ap.add_argument("--window", type=int, default=0, help="only frames within the last N ms (stats, gif)")
    a = ap.parse_args()
    frames = pull(a.raw, a.serial)
    if not frames:
        sys.exit("ring empty: nothing was drawn yet")
    ts, rgb = frames[-1]
    if a.window:
        frames = [f for f in frames if f[0] >= ts - a.window]
    default = not (a.png or a.gif or a.ascii or a.stats)
    if a.ascii or default:
        print(ascii_art(rgb))
    if a.png:
        png(rgb).save(a.png)
        print("wrote", a.png)
    if a.gif:
        imgs = [png(f[1]) for f in frames]
        durs = [max(20, d) for d in intervals(frames)] + [500]
        imgs[0].save(a.gif, save_all=True, append_images=imgs[1:], duration=durs, loop=0)
        print("wrote", a.gif, len(imgs), "frames")
    if a.stats or default:
        d = intervals(frames)
        if d:
            print(f"frames={len(frames)} interval mean={statistics.mean(d):.1f}ms "
                  f"stdev={statistics.pstdev(d):.1f}ms max={max(d)}ms >45ms={sum(x > 45 for x in d)}")
        else:
            print(f"frames={len(frames)} (single frame, no intervals)")


if __name__ == "__main__":
    main()
