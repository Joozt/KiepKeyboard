# KiepDisplay TC002 receiver — setup, build, deploy, verify

Written for someone (or some agent) starting cold on a Windows laptop with the TC002 on the desk.
Every step is a command with its expected output. Design: [`docs/proposals/tc002-receiver.md`](../../docs/proposals/tc002-receiver.md).

## 1. Prerequisites (Windows host)

| Need | Check | Expected |
| --- | --- | --- |
| Docker Desktop, running | `docker ps` | a (possibly empty) container table, no error |
| Python 3.11+ | `python --version` | `Python 3.1x` |
| Host tool deps | `pip install -r tools/requirements.txt` | `bleak`, `Pillow` installed |
| Android platform-tools | `adb version` | `Android Debug Bridge version …` (the tools also find `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` when `adb` is not on PATH) |
| Bluetooth on the laptop | Windows Settings → Bluetooth on | needed only for `send.py` / `verify.py` |

All commands run from `UlanziTC002Receiver/`.

## 2. Build (Docker only; nothing compiles on the host)

```bash
docker compose build build          # once; downloads the Arm GNU 8.3 toolchain (~256 MB)
docker compose run --rm build       # Debug bundle -> out/device  (frame tap on)
docker compose run --rm build --release
docker compose run --rm test        # host presenter tests
```
Expected tail of a build: `bundle: out/device`. Expected test output: `presenter_test: all passed`.
The SDK packages are fetched into a Docker volume on the first build (`sdk ok: /work/.cache/sdk`);
hashes are pinned in `tools/sdk-lock.json`.

Regenerate the font after changing `tools/gen-font.py`: `docker compose run --rm shell -c "python3 tools/gen-font.py"`.

## 3. First contact (once, stock firmware)

Stock firmware exposes adb only over Wi-Fi. This is a development step; the product never uses Wi-Fi.

1. In the Ulanzi Studio app: add the TC002 as a Wi-Fi device on your development network; note its IP.
2. `adb connect <ip>:5555` → `connected to <ip>:5555`
3. `adb shell getprop ro.product.model` → the TC002 model string.
4. `python tools/deploy.py --serial <ip>:5555 --logs` → our app runs and enables adb over USB-C.
5. Plug in USB-C. `adb devices` → a device with USB id `18d1:d002`. From now on use USB.
6. In Ulanzi Studio: remove the device / forget the network. Our firmware never brings Wi-Fi up.

**USB gadget note:** after every *reboot* the port flips to host mode ~3.5 s into boot and a plugged
cable does not re-enumerate. Replug the cable after a reboot. A `ctl.stop/ctl.start zkswe` restart
(what `deploy.py` does) is not a reboot.

## 4. Dev loop

```bash
docker compose run --rm build && python tools/deploy.py --logs
```
Expected logcat lines (`adb logcat -v time -s kiep`):
```
KiepDisplay TC002 starting
usb: otg_role <- usb_device (10)
panel: open
spike0: pixel sent
```
The loader itself logs `load /tmp/EasyUI.cfg ok!` and `registerActivity name: mainActivity OK!` (tag `zkgui`).

The device shell is a stripped busybox: no `sleep`, `grep`, `head`, `uname`. Filter on the host
(`adb shell cat … | findstr`), never on the device.
and one heartbeat-blue pixel top-right on the panel. `adb shell getprop sys.zkapp.state` → `running`.

`python tools/deploy.py --restore` returns to the stock app; so does a power cycle
(the override lives in `/tmp`, which is RAM).

## 5. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `adb: no devices` over USB | Cable plugged before/during a reboot: replug. Or the app did not start: use Wi-Fi adb once more. |
| `panel: open /dev/spidev0.0 failed` | Stock app still holds the panel: `adb shell ps` shows two `zkgui`? Power cycle, deploy again. |
| Stock clock comes back after deploy | Our library crashed before/at start (three crashes revert to stock config). Read `adb logcat -d \| grep -i -E "kiep\|zkgui\|dlopen"`. |
| `dlopen … undefined symbol` | SDK ABI mismatch between the Docker toolchain and the device libraries; see the fallbacks in the design proposal §4. |
| Panel dark, heartbeat absent, `getprop sys.zkapp.state` ≠ `running` | Watchdog fallback; same as above. |
