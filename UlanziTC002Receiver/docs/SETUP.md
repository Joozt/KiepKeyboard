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

## 5. Bluetooth check (laptop as the keyboard)

The app brings Bluetooth up itself, with **no Wi-Fi network**: it sets `persist.wifi.on=0`, stops
`wpa_supplicant`, loads `aic8800_bsp.ko` + `aic8800_fdrv.ko` (the combo chip's Bluetooth firmware rides
on these; `bsp` alone gives no `hci0`), starts the stock `hciattach` service (`/res/bin/hciattach -n
ttyS3 aic`), brings `hci0` up and forces `wlan0` down. Expected logcat lines:
```
bt: hci0 up
bt: `ifconfig wlan0 down` -> 0
gatt: advertising on (ok)
```
Verify there is no network: `adb shell ifconfig wlan0` shows no `UP` flag and no `inet addr`;
`adb shell ps` lists no `wpa_supplicant`; `adb shell getprop persist.wifi.on` prints `0`.

Send frames from the laptop (Bluetooth on, `pip install -r tools/requirements.txt` done):
```bash
python tools/send.py M "Hallo allemaal"     # message
python tools/send.py L "typing"             # live buffer
python tools/send.py W "Hallo allemaal"     # word-append
python tools/send.py attention 2            # white flash + beep, twice
python tools/send.py yes                    # green flash;  `no` = red
python tools/send.py shell                  # several frames on one connection, one per line
```
Expected: `found CC:C4:B2:xx:xx:xx KiepDisplay`, `sent M 15 bytes`; logcat shows `gatt: connected`,
the frame, `gatt: disconnected`, `gatt: advertising on (ok)`. The BLE address is the device's public
(static) address, so the KiepKeyboard app's stored-address connect path applies.

Known controller quirk: right after `hciattach`, the AIC controller sometimes answers LE Set
Advertising Data with a Hardware Error event; the app checks the status and retries (`gatt: LE cmd …
attempt N failed`). Persistent failures mean advertising without our payload: power cycle.

## 6. Seeing the screen (frame tap)

Debug builds (`docker compose run --rm build`, the default) write every frame handed to the panel into
a 64-frame ring at `/tmp/kiep/frames.bin` — the last software point before SPI; the panel cannot be
read back. Release builds have no tap.

```bash
python tools/screen.py                       # ASCII art of the newest frame + cadence stats
python tools/screen.py --png out/screen.png  # LED-look PNG (open it, or let an agent read it)
python tools/screen.py --gif out/motion.gif --window 2500   # the last 2.5 s as an animation
python tools/screen.py --stats --window 1500 # frame intervals over the last 1.5 s
```
Keep `out/*.png` / `out/*.gif` for screenshots; `out/` is gitignored.

**The receiver clears on disconnect** (protocol rule), and `send.py` disconnects right after its last
frame. To look at a result, hold the connection: `python tools/send.py M "Hallo" --hold 10 &` then
run `screen.py` while it holds. In `shell` mode `--gap` spaces the frames.

**Smoothness check.** While a long message scrolls with the connection held:
`python tools/screen.py --stats --window 1500` → `interval mean=30.0ms stdev=0.0ms max=30ms >45ms=0`
(measured 2026-09-29). Anything else means the render thread lost its cadence: check
`adb logcat -s kiep` for `render: could not raise priority`.

**Debug knobs** (Debug builds, read at app start, restart with `deploy.py` after changing):
`adb shell "echo 3000 > /tmp/kiep/idle_ms"` shortens the 10-minute idle clear to 3 s; delete the file
to restore.

## 7. Knob, buttons, battery

- Knob rotate: notification volume 0–6 (0 = silent), evdev `/dev/input/event68`, ABS pairs 8→1 clockwise,
  13→11 counter-clockwise. The sound plays once at the new level ~300 ms after the knob rests. Persists in
  `/data/kiep/volume`.
- Left / right buttons (`/dev/input/event67`, keys 0x6C / 0x6A): brightness down / up in 5 levels, persisted
  in `/data/kiep/brightness`.
- Knob press (key 0x67) or middle button (0x69): replays the last message from the start (`replay` in logcat).
- Battery: polled every 30 s over the MCU UART (`/dev/ttyS1`, 1.5 Mbaud); logcat shows `mcu version V1.0.17`
  at start. Below 15 % the heartbeat pixel turns red. The MCU reports no charging flag.

## 8. Attention sound

`res/ui/beep.mp3` plays once per attention flash at the knob-selected volume. Replace the file with any
short clip the SDK player decodes (MP3/WAV); it is picked up on the next deploy (`sound: ready
(…/beep.mp3)` in logcat). Sounds play back to back, one per flash; a long sound trails the flashes, so
keep it under ~0.5 s.

## 9. Verification cycle

One command, after every change:
```bash
python tools/verify.py            # deploys out/device, runs tests/fixtures.json, prints PASS/FAIL per fixture
python tools/verify.py --no-deploy
python tools/verify.py --only accents
python tools/verify.py --update   # rewrite goldens after a deliberate rendering change; review tests/golden/*.png
```
It drives the display from the laptop over BLE (one connection per fixture, held while capturing), pulls
the frame tap, and checks lit columns, dominant color, motion, cadence and golden frames. Failed goldens
land in `out/fail_<name>.png`. A new feature gets a new fixture (schema in the script's docstring);
`idle_ms` fixtures restart the app twice and take ~30 s.

## 10. Permanent install

Decided on hardware (2026-09-29): the loader (`/bin/zkgui` + `/lib/libzkupgrade.so`) reads only
`/tmp/EasyUI.cfg` (RAM, written by `zkdaemon` as `{}` after boot failures) or `/res/etc/EasyUI.cfg`
(read-only squashfs). Nothing under `/data` or `/mnt/storage` is consulted, so a permanent install
rebuilds the `res` partition image and flashes it with the vendor upgrade path
(`sys.zkupgrade.dir` + `sys.zkupgrade.flag`). Tooling: `tools/install.py` (backup, build-image,
flash, uninstall); procedure in section 8 once implemented. The vendor's own `update.img` sits in
`/mnt/storage` and serves as the header template.

## 7. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `adb: no devices` over USB | Cable plugged before/during a reboot: replug. Or the app did not start: use Wi-Fi adb once more. |
| `panel: open /dev/spidev0.0 failed` | Stock app still holds the panel: `adb shell ps` shows two `zkgui`? Power cycle, deploy again. |
| Stock clock comes back after deploy | Our library crashed before/at start (three crashes revert to stock config). Read `adb logcat -d \| grep -i -E "kiep\|zkgui\|dlopen"`. |
| `dlopen … undefined symbol` | SDK ABI mismatch between the Docker toolchain and the device libraries; see the fallbacks in the design proposal §4. |
| Panel dark, heartbeat absent, `getprop sys.zkapp.state` ≠ `running` | Watchdog fallback; same as above. |
