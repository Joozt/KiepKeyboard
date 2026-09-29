#!/usr/bin/env python3
"""Laptop as the keyboard: write KiepDisplay frames over BLE, byte-identical to the Android app.

  python tools/send.py M "Hallo allemaal"     message: centered, or scrolls once and holds the tail
  python tools/send.py W "Hallo allemaal"     word-append message (extends the current one)
  python tools/send.py L "typing..."          live buffer: static tail view
  python tools/send.py attention [N]          white flash + beep, N times (1-5)
  python tools/send.py yes | no               green / red flash
  python tools/send.py shell                  one frame per stdin line on a single connection
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

SERVICE = "b1e60001-8f2a-4d0c-9e4b-5c3d2a1f0e9d"
TEXT = "b1e60002-8f2a-4d0c-9e4b-5c3d2a1f0e9d"
ATTENTION = "b1e60003-8f2a-4d0c-9e4b-5c3d2a1f0e9d"
NAME = "KiepDisplay"


def frame(cmd, arg=None):
    """Return (characteristic uuid, bytes) for a command line."""
    if cmd in ("M", "W", "L"):
        return TEXT, cmd.encode() + (arg or "").encode("utf-8")
    if cmd == "attention":
        return ATTENTION, bytes([1, int(arg or 1)])
    if cmd == "yes":
        return ATTENTION, bytes([2, 1])
    if cmd == "no":
        return ATTENTION, bytes([3, 1])
    raise SystemExit(f"unknown command {cmd!r}")


async def find(timeout=15):
    dev = await BleakScanner.find_device_by_filter(
        lambda d, ad: SERVICE in (ad.service_uuids or []) or d.name == NAME, timeout=timeout)
    if not dev:
        raise SystemExit("KiepDisplay not found (is the heartbeat blinking?)")
    print("found", dev.address, dev.name)
    return dev


async def send_all(lines, hold=0.0, gap=0.3):
    """Send frames on one connection; `hold` seconds afterwards keep it open (the receiver clears on disconnect)."""
    async with BleakClient(await find()) as client:
        for i, line in enumerate(lines):
            if i:
                await asyncio.sleep(gap)
            cmd, _, arg = line.partition(" ")
            uuid, data = frame(cmd, arg or None)
            await client.write_gatt_char(uuid, data, response=True)
            print("sent", cmd, len(data), "bytes", flush=True)
        if hold:
            await asyncio.sleep(hold)


def main():
    args = sys.argv[1:]
    hold, gap = 0.0, 0.3
    if "--hold" in args:
        i = args.index("--hold"); hold = float(args[i + 1]); del args[i:i + 2]
    if "--gap" in args:
        i = args.index("--gap"); gap = float(args[i + 1]); del args[i:i + 2]
    if not args:
        raise SystemExit(__doc__ + "\n  --hold SECONDS  keep the connection open after the last frame\n  --gap SECONDS   pause between frames in shell mode (default 0.3)")
    if args[0] == "shell":
        print("one frame per line, e.g. `M Hallo`; ctrl-Z then Enter (Windows) / ctrl-D to quit")
        lines = [l.rstrip("\n") for l in sys.stdin if l.strip()]
    else:
        lines = [" ".join(args)]
    asyncio.run(send_all(lines, hold, gap))


if __name__ == "__main__":
    main()
