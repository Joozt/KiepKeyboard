#!/usr/bin/env python3
"""update.img packer round trip against the vendor image pulled by `install.py backup`:
pack(unpack(vendor), template=vendor) must be byte-identical, and the header checks must pass.
Run: python tests/test_update_img.py"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tools"))
import install  # noqa: E402

backups = sorted(p for p in (install.ROOT / "out/backup").glob("*") if p.is_dir())
assert backups, "no backup: run tools/install.py backup"
vendor = None
for b in reversed(backups):
    if (b / "vendor-update.img").exists():
        vendor = (b / "vendor-update.img").read_bytes()
        break
assert vendor, "no vendor-update.img in any backup"

info = install.inspect_update_img(vendor)
assert info["crc_ok"] and info["md5_ok"] and info["squashfs"], info
assert info["device_code"] == hex(install.DEVICE_CODE), info
assert info["file_size"] == info["expected_file_size"], info

sqsh = install.unpack_update_img(vendor)
assert sqsh[:4] == b"hsqs"
assert install.squashfs_used(sqsh) <= len(sqsh)

repacked = install.pack_update_img(sqsh, vendor)
assert repacked == vendor, "round trip differs at byte %d" % next(i for i in range(min(len(repacked), len(vendor))) if repacked[i] != vendor[i])

synth = install.pack_update_img(sqsh, None)
si = install.inspect_update_img(synth)
assert si["crc_ok"] and si["md5_ok"] and si["squashfs"] and si["device_code"] == hex(install.DEVICE_CODE), si

# uninstall must never "restore" a backup that already contains our firmware: pick the newest STOCK backup
import tempfile  # noqa: E402
with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    (root / "20260101-000000").mkdir(); (root / "20260101-000000/mtd3.bin").write_bytes(b"hsqs" + b"\0" * 64)
    (root / "20260102-000000").mkdir(); (root / "20260102-000000/mtd3.bin").write_bytes(b"hsqs" + b"lib/libkiep.so" + b"\0" * 64)
    assert install.latest_stock_backup(root).name == "20260101-000000"
    (root / "20260103-000000").mkdir(); (root / "20260103-000000/mtd3.bin").write_bytes(b"hsqs" + b"\0" * 64)
    assert install.latest_stock_backup(root).name == "20260103-000000"

print("test_update_img: all passed", info)
