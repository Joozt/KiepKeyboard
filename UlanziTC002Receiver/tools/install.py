#!/usr/bin/env python3
"""Permanent install of the KiepDisplay receiver on the TC002 (rebuilds the `res` partition image).

  python tools/install.py backup                     # dump all 8 mtd partitions + vendor update.img -> out/backup/<stamp>/
  python tools/install.py build-image [--release]    # out/update.img with our app in place of the stock one (runs in Docker)
  python tools/install.py inspect out/update.img     # header fields, CRC and MD5 check
  python tools/install.py flash out/update.img       # stage on /data, trigger the vendor flasher, reboot (~1 min)
  python tools/install.py uninstall                  # flash the backed-up stock `res` image back
  python tools/install.py unpack IMG OUT.sqsh        # squashfs out of an update.img

The `res` squashfs keeps the stock app files; only /res/etc/EasyUI.cfg is changed (startupLibPath ->
/res/lib/libkiep.so, resPath -> /res/ui/kiep/) and our files are added. Container format and flashing
recipe: atomicstack/tc002-customisation FIRMWARE.md (verified header layout on the vendor image).
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import time
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
BACKUPS = ROOT / "out/backup"
DEVICE_CODE = 0xAA550606  # Zkswe_SSD21X_SPINOR
HDR = 0x24C               # header incl. CRC (0x238) and payload MD5 (0x23C..0x24C); payload bytes 16.. follow
ENTRY = 0x14              # first (only) partition entry, 28 bytes
RES_INDEX = 3
SERIAL = None

# --- adb -----------------------------------------------------------------------------------------------

def adb_path():
    found = shutil.which("adb")
    if found:
        return found
    sdk = pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"
    if sdk.exists():
        return str(sdk)
    sys.exit("adb not found")


def adb(*args, check=True, capture=False):
    cmd = [adb_path()] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, check=check, capture_output=capture, text=capture)


def sh(cmd):
    return adb("shell", cmd, capture=True).stdout


# --- update.img container ------------------------------------------------------------------------------

def unpack_update_img(data: bytes) -> bytes:
    """Squashfs image (4 KiB padded) out of an update.img."""
    assert data[:9] == b"ZKSWEV1.0", "not a ZKSWE update image"
    off, size = struct.unpack_from("<II", data, ENTRY + 4)
    first16 = data[ENTRY + 0x0C:ENTRY + 0x1C]
    return first16 + data[off + 16:off + size]


def pack_update_img(sqsh: bytes, template: bytes | None) -> bytes:
    """update.img around a squashfs; a vendor image as template reproduces the vendor header byte for byte."""
    img = sqsh + b"\0" * (-len(sqsh) % 4096)
    md5 = hashlib.md5(img).digest()
    hdr = bytearray(template[:HDR]) if template else bytearray(HDR)
    if not template:
        hdr[0:16] = b"ZKSWEV1.0-180127"
        hdr[0x10] = 0x30; hdr[0x11] = 1; hdr[0x12] = 0x30; hdr[0x13] = 0x23
        struct.pack_into("<I", hdr, 0x30, 0x20C)
        hdr[0x34] = 0x02
        struct.pack_into("<I", hdr, 0x35, DEVICE_CODE)
        hdr[0x39] = 0
        hdr[ENTRY] = RES_INDEX
        hdr[ENTRY + 1:ENTRY + 4] = bytes.fromhex("10606c")
    struct.pack_into("<II", hdr, ENTRY + 4, 0x23C, len(img))
    hdr[ENTRY + 0x0C:ENTRY + 0x1C] = img[:16]
    hdr[0x23C:0x24C] = md5
    struct.pack_into("<I", hdr, 0x238, zlib.crc32(bytes(hdr[:0x238])) & 0xFFFFFFFF)
    return bytes(hdr) + img[16:]


def inspect_update_img(data: bytes) -> dict:
    off, size = struct.unpack_from("<II", data, ENTRY + 4)
    (code,) = struct.unpack_from("<I", data, 0x35)
    (crc,) = struct.unpack_from("<I", data, 0x238)
    img = unpack_update_img(data)
    info = {
        "magic": data[:16].decode(errors="replace"), "entries": data[0x11], "partition": data[ENTRY],
        "payload_offset": hex(off), "image_size": size, "device_code": hex(code), "device_flag": data[0x39],
        "crc_ok": crc == (zlib.crc32(data[:0x238]) & 0xFFFFFFFF),
        "md5_ok": data[0x23C:0x24C] == hashlib.md5(img).digest(),
        "squashfs": img[:4] == b"hsqs", "file_size": len(data), "expected_file_size": 0x23C + size,
    }
    return info


def squashfs_used(sqsh: bytes) -> int:
    """bytes_used from the squashfs superblock, 4 KiB padded (a raw mtd dump is padded with 0xFF)."""
    (used,) = struct.unpack_from("<Q", sqsh, 40)
    return used + (-used % 4096)


# --- commands -----------------------------------------------------------------------------------------

def latest_backup() -> pathlib.Path:
    dirs = sorted(p for p in BACKUPS.iterdir() if p.is_dir()) if BACKUPS.exists() else []
    if not dirs:
        sys.exit("no backup in out/backup: run `install.py backup` first")
    return dirs[-1]


def cmd_backup(a):
    out = BACKUPS / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True)
    for i in range(8):
        sh(f"[ -e /dev/block/mtdblock{i} ] || mknod /dev/block/mtdblock{i} b 31 {i}; cat /dev/block/mtdblock{i} > /tmp/mtd{i}.bin")
        adb("pull", f"/tmp/mtd{i}.bin", str(out / f"mtd{i}.bin"), capture=True)
        sh(f"rm /tmp/mtd{i}.bin")
        print(f"mtd{i}: {(out / f'mtd{i}.bin').stat().st_size} bytes")
    if "update.img" in sh("ls /mnt/storage"):
        adb("pull", "/mnt/storage/update.img", str(out / "vendor-update.img"), capture=True)
        print("vendor-update.img:", (out / "vendor-update.img").stat().st_size, "bytes")
    print("backup in", out)


def in_container():
    return pathlib.Path("/work/tools/install.py").exists()


def cmd_build_image(a):
    if not in_container():  # squashfs-tools live in the build image
        args = ["build-image"] + (["--release"] if a.release else [])
        subprocess.run(["docker", "compose", "run", "--rm", "shell", "-c",
                        "python3 tools/install.py " + " ".join(args)], cwd=ROOT, check=True)
        return
    backup = latest_backup()
    bundle = ROOT / "out/device"
    if not (bundle / "lib/libzkgui.so").exists():
        sys.exit("no bundle in out/device: run tools/build.sh first")
    work = pathlib.Path("/tmp/kiep-res")
    shutil.rmtree(work, ignore_errors=True)
    subprocess.run(["unsquashfs", "-q", "-d", str(work), str(backup / "mtd3.bin")], check=True)
    shutil.copy(bundle / "lib/libzkgui.so", work / "lib/libkiep.so")
    os.chmod(work / "lib/libkiep.so", 0o755)
    (work / "ui/kiep").mkdir(exist_ok=True)
    for f in (bundle / "ui").iterdir():
        shutil.copy(f, work / "ui/kiep" / f.name)
    cfg_path = work / "etc/EasyUI.cfg"
    cfg = json.loads(cfg_path.read_text())
    cfg["startupLibPath"] = "/res/lib/libkiep.so"
    cfg["resPath"] = "/res/ui/kiep/"
    cfg_path.write_text(json.dumps(cfg, indent=1))
    sqsh = ROOT / "out/res-kiep.sqsh"
    sqsh.unlink(missing_ok=True)
    subprocess.run(["mksquashfs", str(work), str(sqsh), "-comp", "xz", "-b", "128K", "-noappend", "-quiet"], check=True)
    template_path = backup / "vendor-update.img"
    template = template_path.read_bytes() if template_path.exists() else None
    img = pack_update_img(sqsh.read_bytes(), template)
    (ROOT / "out/update.img").write_bytes(img)
    print(json.dumps(inspect_update_img(img), indent=1))
    print("wrote out/update.img", len(img), "bytes", "(template: vendor image)" if template else "(synthesized header)")


def cmd_inspect(a):
    print(json.dumps(inspect_update_img(pathlib.Path(a.image).read_bytes()), indent=1))


def cmd_unpack(a):
    pathlib.Path(a.out).write_bytes(unpack_update_img(pathlib.Path(a.image).read_bytes()))
    print("wrote", a.out)


def flash(image: pathlib.Path):
    info = inspect_update_img(image.read_bytes())
    if not (info["crc_ok"] and info["md5_ok"] and info["squashfs"] and info["device_code"] == hex(DEVICE_CODE)):
        sys.exit(f"refusing to flash: {info}")
    print("staging", image, "on /data ...")
    adb("push", str(image), "/data/update.img", capture=True)
    sh("setprop persist.zkupgrade.dir /data")
    sh("setprop sys.zkupgrade.dir /data")
    sh("setprop sys.zkupgrade.flag 255")
    sh("setprop ctl.stop zkswe")
    time.sleep(1)
    sh("setprop ctl.start zkswe")
    print("flasher triggered; the device shows the upgrade screen and reboots. Replug USB-C afterwards.")
    print("Then check: adb shell ls /res/lib   (libkiep.so present = installed; absent = stock)")
    print("            adb shell rm /data/update.img")


def cmd_flash(a):
    flash(pathlib.Path(a.image))


def cmd_uninstall(a):
    backup = latest_backup()
    raw = (backup / "mtd3.bin").read_bytes()
    sqsh = raw[:squashfs_used(raw)]
    template_path = backup / "vendor-update.img"
    img = pack_update_img(sqsh, template_path.read_bytes() if template_path.exists() else None)
    out = ROOT / "out/stock-restore.img"
    out.write_bytes(img)
    print("stock image from", backup, "->", out, json.dumps(inspect_update_img(img)))
    flash(out)


def main():
    global SERIAL
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("backup").set_defaults(fn=cmd_backup)
    p = sub.add_parser("build-image"); p.add_argument("--release", action="store_true"); p.set_defaults(fn=cmd_build_image)
    p = sub.add_parser("inspect"); p.add_argument("image"); p.set_defaults(fn=cmd_inspect)
    p = sub.add_parser("unpack"); p.add_argument("image"); p.add_argument("out"); p.set_defaults(fn=cmd_unpack)
    p = sub.add_parser("flash"); p.add_argument("image"); p.set_defaults(fn=cmd_flash)
    sub.add_parser("uninstall").set_defaults(fn=cmd_uninstall)
    a = ap.parse_args()
    SERIAL = a.serial
    a.fn(a)


if __name__ == "__main__":
    main()
