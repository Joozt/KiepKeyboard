# Proposal: Ulanzi TC002 receiver

Status: Implemented and installed on the device (2026-09-29) — hardware verified
Author: (Joost)
Date: 2026-09-29

> **Implementation notes (2026-09-29).** Built as designed in
> `UlanziTC002Receiver/`; setup and verification in its `docs/SETUP.md`.
> Deviations found on the hardware:
>
> - **Controls (user request during implementation):** knob rotate = notification
>   volume 0–6 with one preview sound when the knob rests; left/right buttons =
>   brightness; knob press *and* middle button = replay. The spec's "knob =
>   brightness" is superseded.
> - **Bluetooth bring-up:** `aic8800_bsp` alone gives no `hci0`; `bsp` + `fdrv`
>   is needed (`wlan0` appears and is forced down). The EasyUI framework starts
>   `wpa_supplicant` when the stock `persist.wifi.on=1` is set, so the app sets
>   it to 0 and stops the service. `ifconfig wlan0 down` does not hurt BLE.
>   Right after `hciattach` the controller sometimes answers LE Set Advertising
>   Data with a Hardware Error event: commands are status-checked and retried.
> - **No UI resource:** the loader logs "open main.ftu fail!" and "not found
>   mainActivity Activity" and runs on; no `.ftu` is shipped.
> - **Install path:** the loader consults only `/tmp/EasyUI.cfg` and
>   `/res/etc/EasyUI.cfg`, so the permanent install rebuilds the `res` squashfs
>   (stock app kept, our `libkiep.so` + `ui/kiep/` added, config edited) and
>   flashes it through the vendor `sys.zkupgrade` path; `install.py` packs the
>   `update.img` container byte-identically to the vendor image.
> - **Sound:** `res/ui/beep.mp3` (user-chosen clip) is the only sound; plays are
>   queued and sequential. Battery: the MCU reports percent but no charging flag.
> - **Measured:** scroll cadence 30.0 ms ± 0.0 ms, no dropped ticks; `verify.py`
>   14/14 on the deployed build and on the installed firmware (with no other
>   BLE central nearby: a connected phone re-sends its buffer after a
>   disconnect and breaks the disconnect-clear fixture).

Companion to [external-display.md](external-display.md) (owns the BLE
protocol) and [ulanzi-receiver.md](ulanzi-receiver.md) (the TC001
firmware). This document covers the second Ulanzi device, the TC002, and the
development environment built for it.

## Background

The TC001's 8×32 matrix shows about five characters in a 7-pixel font. The
Ulanzi TC002 ("Pixbar Smart Pixel Clock II") has a 52×16 matrix in a housing
of the same width, a speaker, a knob, and a 3600 mAh battery. The goal is a
KiepDisplay receiver on the TC002 whose text is physically easier to read than
on the TC001, speaking the **same BLE protocol** so the KiepKeyboard app needs
no changes.

## Research findings

**The TC002 is not an Android device.** It runs Linux 4.9.84 on a SigmaStar
SSD21x (2× Cortex-A7 at 1 GHz, 64 MB RAM, 32 MB SPI NOR flash) with the
vendor's "FlyThings" C++ application framework (`zkgui` loader, EasyUI). The
stock firmware exposes a root `adbd` on TCP port 5555 and, after one sysfs
write, adb over USB-C. There is no app store and no APK sideloading; as with
the TC001, the stock application is replaced by custom software — a Linux
shared library instead of ESP32 firmware.

| Component | Detail |
| --- | --- |
| Display | 52×16 RGB LEDs (832), driven by a separate pixel MCU. The SoC sends 3072-byte frames over `/dev/spidev0.0` (10 MHz, mode 0) with a GPIO35 latch pulse; the panel double-buffers and shows a frame when the next one arrives. ~66 fps maximum (≥15 ms between frames). |
| Bluetooth | AIC8800DC Wi-Fi + BLE 5.2 combo chip, HCI over UART (`hci0`). The Linux kernel has HCI and L2CAP sockets; no RFCOMM. Userspace GATT is available via BlueZ's shared GATT library, shipped prebuilt in the SDK demo (`libgatt-server.a` + BlueZ headers). |
| MCU link | `/dev/ttyS1` at 1.5 Mbaud; must be initialised before the panel accepts frames. Also reports buttons, knob, battery, microphone. |
| Audio | Speaker, MP3 playback through the SDK `AudioManager`, volume 0–6. |
| Input | Rotary knob (clockwise / counter-clockwise / press) and three buttons. |
| Power | 3.7 V 3600 mAh battery, ~2 h at full brightness, USB-C or dock charging. |
| Flash | 8 partitions; `res` (mtd3, 8 MiB squashfs) holds the stock app; `/data` (8 MiB jffs2) is the only writable persistent space. |
| Toolchain | Vendor: FlyThings IDE (Eclipse, Windows) with Linaro GCC 8.3.0 (`arm-pc-linux-gnueabihf`), SDK packages from `package.flythings.cn/packages/Z21/`. Device libc: glibc 2.30. Headless builds are proven by the Owlanzi project (CMake + the same toolchain and packages). |
| Watchdog | The app must set the system property `sys.zkapp.state=running` within 15 s of start or the loader falls back to recovery. Three consecutive crashes revert to the stock configuration. |

**Existing custom firmwares** (Stipple, atomicstack runtime, AWTRIX NG port,
Owlanzi) are all Wi-Fi/HTTP/MQTT based; none does BLE. They are reference
material for panel driving and bring-up quirks, not a base: they carry web
servers and MQTT clients that contradict the privacy model, and their
licenses (GPL-3, PolyForm-NC, undeclared) do not fit the MIT repository.

**Official SDK demo** (`UlanziTechnology/Ulanzi-U-Clock-TC002`, GPL-3.0)
contains a working BLE GATT server built on BlueZ shared code plus a raw
L2CAP ATT socket and HCI advertising commands, with service and
characteristic UUIDs defined in an `on_start` callback. It also documents
"BLE depends on Wi-Fi being enabled": the combo chip's Bluetooth firmware is
loaded through the same vendor kernel modules as Wi-Fi (`aic8800_bsp`,
`aic_btusb`). That is a driver dependency, not a network one.

## Goals

- TC002 boots straight into KiepDisplay and behaves like the TC001 receiver:
  advertises `KiepDisplay`, renders `M`/`W`/`L` frames, expresses attention
  and yes/no, clears on disconnect and idle.
- **Bigger, more legible text**: one row, glyphs physically taller than the
  TC001's, accented Dutch characters rendered.
- **Smooth scrolling**: no flicker, no judder (a known TC001 problem, see
  [ulanzi-text-presentation.md](ulanzi-text-presentation.md)).
- **BLE only.** No Wi-Fi network, ever: no supplicant, no SSID, no AP mode,
  no HTTP/MQTT. The radio never associates.
- **Reproducible headless build** in Docker; no IDE. Documented so that an AI
  agent or a new human can set up, build, deploy and verify from the
  document alone.
- **Reusable verification loop** from the developer's laptop: the laptop acts
  as the keyboard over its own Bluetooth, and the frames the TC002 shows can
  be captured and inspected.

## Non-goals

- Any change to the KiepKeyboard app or the BLE protocol.
- Keeping stock features (clock, weather, Ulanzi cloud, Ulanzi Studio).
- Firmware OTA. Updates go over USB adb.
- Two-row layouts or presentation modes (pager, sub-pixel scrolling). The
  renderer keeps a single presenter function for `M`/`W` frames so a pager
  mode could be added later behind a constant, as proposed for the TC001.

## Decisions (from the design review)

| Question | Decision |
| --- | --- |
| Platform | TC002 is Linux + FlyThings, not Android. Custom app replaces the stock one. |
| Approach | Native FlyThings SDK app (`libzkgui.so`), C++. Own BLE GATT code against the SDK's prebuilt BlueZ shared library. Rejected: standalone daemon bypassing the SDK (more reverse-engineering), forking Stipple/atomicstack (license, attack surface). |
| Text layout | One row, tall font. Readability beats amount of text. |
| Font | Generated bitmap font, caps 12 px, from a permissive bold sans TTF (DejaVu Sans Bold); proportional; ASCII + Latin-1. Alternative (5×7 stretched 1×2) rejected. |
| Wi-Fi | Never. Load the radio modules for Bluetooth firmware only; no supplicant, no association. Hard go/no-go spike. |
| Build | Docker image with the toolchain and SDK packages; CMake + Ninja. Host side (Windows) only runs adb and Bluetooth scripts. |
| Repository layout | `UlanziTC002Receiver/` next to `UlanziReceiver/`. TC001 firmware untouched. |

## Design

### 1. BLE peripheral

- **Stack.** BlueZ shared GATT library (`gatt-db`, `gatt-server`, `att`,
  `l2cap`, `mainloop`, `hci_lib`), linked from the SDK demo's
  `libgatt-server.a` and headers (BlueZ shared code is LGPL). The demo's own
  `gatt-impl.c` is GPL-3 and is **not copied**; we write our own
  `KiepGatt.c` (~250 lines) against the same headers, using the demo only as
  a reference for the bring-up sequence.
- **Bring-up** (own minimal sequence, mirrors what the demo shells out to):
  load `aic8800_bsp` and `aic_btusb` modules, start the `hciattach` service
  via the `ctl.start` system property, `hciconfig hci0 up`, HCI reset. No
  `wpa_supplicant`, no DHCP, no SSID. The spike additionally tries
  `ifconfig wlan0 down` after Bluetooth is up; if BLE survives, the Wi-Fi
  interface is taken down permanently.
- **Advertising.** Public static address, connectable undirected
  advertising. Advertising data: flags + complete list of 128-bit service
  UUIDs (`b1e60001-8f2a-4d0c-9e4b-5c3d2a1f0e9d`), which the app's
  `ScanFilter` requires. Scan response: complete local name `KiepDisplay`
  (name and 128-bit UUID do not fit one 31-byte payload together).
  Advertising restarts on disconnect. Because the address is static, the
  app's stored-address direct connect with `autoConnect` applies, exactly as
  for the TC001.
- **GATT database.** Primary service `b1e60001-…`; characteristic
  `b1e60002-…` Text and `b1e60003-…` Attention, properties WRITE and
  WRITE_WITHOUT_RESPONSE. `BT_SECURITY_LOW`, no bonding — identical in
  practice to the TC001, whose characteristics are unencrypted and which the
  app never bonds with. Server MTU 517, so the app's 247-byte negotiation
  succeeds and long writes are reassembled by the library; frames arrive
  whole.
- **Handoff.** The BLE mainloop runs on its own thread. Write callbacks copy
  the value (`[type][UTF-8 text]` or `[effect][count]`) into a mutex-guarded
  pending slot; the render thread consumes it. Single writer, single reader,
  lock held only for the copy.
- **Frames.** Byte-identical protocol: `M` message, `W` word-append, `L`
  live buffer; attention effects 1 (attention), 2 (yes), 3 (no) with repeat
  count. Zero app changes.
- **Failure mode.** If any bring-up step fails, the app logs and continues
  with the heartbeat blinking; it never exits (three crashes trigger stock
  recovery).

### 2. Rendering

**Sizing.** Both clocks are about 20 cm wide: TC001 32 columns ≈ 6 mm pitch,
TC002 52 columns ≈ 3.6 mm. TC001 caps are 7 px ≈ 42 mm. To be physically
taller on the TC002, caps need ≥ 12 px; standard 8×16 terminal fonts reserve
line gap and have 10-px caps, so they would render *smaller* than the TC001.

- **Font.** `tools/gen-font.py` renders a permissive bold sans TTF (DejaVu
  Sans Bold) to 1-bit bitmaps at a size giving 12-px caps: baseline on row
  12, descenders on rows 13–15, one blank row on top. Proportional widths,
  average advance 7–9 px, so 6–7 characters visible. Glyph set: printable
  ASCII plus Latin-1 letters (é ë ï ö ü etc. render instead of `?`).
  Output is committed as `src/font.h` (arrays + advance table); the
  generator runs only when the font changes. Caps ≈ 43 mm, x-height ≈ 32 mm,
  2-px strokes.
- **Frame buffer.** 52×16×3 bytes RGB, white text on black, brightness
  applied in software before `sendLedData` (the panel's own level curve is
  left alone). Never less than 15 ms between frames.
- **Presenter (TC001 semantics, one row).**
  - `M`: centered when it fits; otherwise scroll in from the right once,
    then hold the tail.
  - `W` extension of the currently shown text: a running scroll continues
    into the appended words; a held tail resumes scrolling from its position
    when the text stops fitting. Non-extensions behave as `M`.
  - `L`: static tail view of the buffer — as many trailing characters as
    fit, updated in place, no motion. Newlines render as spaces.
  - Scroll speed: 1 px per tick, tick ≈ 30 ms, matching the TC001's mm/s
    (40 ms/px on 6-mm pixels). `SCROLL_TICK_MS` is a constant at the top of
    the file.
- **Heartbeat pixel** at (51, 0), KiepKeyboard accent blue, blinking while
  advertising, solid while connected, drawn over text. Turns red below ~15 %
  battery if the MCU protocol exposes charge (section 3).
- **Flashes.** Attention: white full-panel flash + beep, repeated `count`
  times. Yes: green; no: red; ~0.8 s fade, silent. Same fade pattern as the
  TC001 and the app.
- **Privacy clears.** Panel cleared on disconnect and after 10 minutes
  without frames (`IDLE_CLEAR_MS`).

**Smoothness (flicker/judder).** The TC001's flicker came from BLE task
jitter corrupting the timing-critical WS2812 data stream; the TC002's panel
is driven by its own MCU with double buffering, so that failure class is
gone by hardware design. What remains is cadence, handled as follows:

- A **dedicated render thread** with nothing else on it. Fixed cadence via
  `clock_nanosleep(TIMER_ABSTIME)` on a monotonic deadline: no drift, no
  accumulated jitter. One pixel per tick.
- **Whole-frame swaps only.** Every `sendLedData` call carries a complete
  frame; the panel shows it atomically on the next latch.
- **No blocking work on the render thread.** BLE writes, audio, buttons and
  knob events land in mutex-guarded slots; no allocation or logging per
  frame, logging only on state changes.
- **Static content sends nothing.** Frames go out only when the buffer
  changed or the heartbeat toggled.
- **Thread priority** raised (`SCHED_FIFO` at a low priority, falling back
  to `nice`) so SDK background threads cannot steal a tick. Tuning knob, not
  a dependency.

### 3. Device behavior

- **Boot.** The stock `zkgui` loader dlopens our `libzkgui.so` (configured in
  `EasyUI.cfg`). The first statement of the entry point sets
  `sys.zkapp.state=running`. Then: MCU initialisation on `/dev/ttyS1`
  (`McuManager`, `queryMcuVersion`), panel clear, heartbeat blinking within
  ~2 s of power-on. Bluetooth bring-up runs on a background thread; the
  heartbeat goes solid on connect.
- **Knob.** Rotate = brightness, five levels, persisted via
  `StoragePreferences`. Press = replay the last message. The three buttons
  are unused. Nothing else is configurable on the device, as on the TC001.
- **Audio.** Attention beep = one short generated MP3 tone in the app
  resources, played through `AudioManager` at a fixed volume constant tuned
  on hardware. Yes/no are silent.
- **Battery.** If the MCU protocol reports charge (the stock app shows it, so
  likely), the heartbeat pixel turns red below ~15 %. If it is not exposed,
  skip; not worth reverse-engineering.
- **USB adb.** At boot the app writes `usb_device` to
  `/sys/bus/platform/devices/soc:usbotg/otg_role`, so `adb` over USB-C always
  works. This is the debug and update path; it requires physical access.
- **Removed.** Clock, Ulanzi cloud, HTTP API on port 80, MQTT, Wi-Fi. The
  stock `adbd` on TCP 5555 is unreachable without a network.
- **Recovery** (stock, unchanged). Reset button held at power-on restores
  the factory app; three consecutive boot crashes fall back to the stock
  configuration. Every bring-up failure in our app degrades (log, keep the
  heartbeat) rather than exits.
- **Idle.** Clear after 10 min and on disconnect. No auto power-off; the
  hardware power button per the Ulanzi manual is unaffected.

### 4. Build & deploy

Folder `UlanziTC002Receiver/`:

```
Dockerfile, compose.yaml           # build image: toolchain + SDK packages
CMakeLists.txt, CMakePresets.json
cmake/toolchain-z21.cmake          # cross compiler, SDK include/lib paths, flags from the demo .cproject
tools/fetch-sdk.py                 # Z21 SDK packages from package.flythings.cn, pinned SHA-256 → .cache/ (gitignored)
tools/gen-font.py                  # TTF → src/font.h (run once; output committed)
tools/deploy.py                    # host: adb push + restart (dev loop)
tools/install.py                   # host: permanent install / uninstall
tools/send.py                      # host: laptop as keyboard over BLE (bleak)
tools/screen.py                    # host: pull frame ring, render PNG/ASCII/GIF, cadence stats
tools/verify.py                    # host: end-to-end regression cycle
tools/requirements.txt             # bleak, Pillow
src/Main.cpp                       # entry: watchdog property, MCU init, threads
src/KiepDisplay.cpp/.h             # protocol parsing, presenter, render thread, flashes, idle
src/KiepGatt.c/.h                  # BlueZ shared GATT server + HCI advertising
src/BtBringup.cpp/.h               # module load, hciattach, hci0 up (no Wi-Fi)
src/FrameTap.cpp/.h                # debug frame ring (see section 5)
src/font.h                         # generated
res/EasyUI.cfg, beep.mp3, ui/main.ftu
docs/SETUP.md                      # environment, first contact, build, deploy, verify, restore
README.md                          # behavior + pointers, like UlanziReceiver/README.md
```

- **Docker image.** Based on a current Debian. Contains the Arm GNU/Linaro
  GCC 8.3 Linux x86_64 cross toolchain (`gcc-arm-8.3-2019.03-x86_64-arm-
  linux-gnueabihf`, the same compiler generation as the vendor's Windows
  toolchain, so libstdc++ symbol versions match the device), CMake, Ninja,
  Python 3 with Pillow, `squashfs-tools`. `fetch-sdk.py` runs at image build
  and pulls only the Z21 SDK packages (headers and ARM stub `.so` files,
  which are toolchain-independent). Spike 0 verifies ABI compatibility;
  fallbacks in order: the vendor's Linux Z21 toolchain in the same image,
  then the Windows toolchain on the host.
- **Build.** `docker compose run build` → `build/device/{EasyUI.cfg,
  lib/libzkgui.so, ui/main.ftu}`. Debug and Release presets; Debug enables
  the frame tap and verbose logging.
- **`main.ftu`.** The EasyUI loader expects a compiled UI resource even
  though the LED panel bypasses the LCD pipeline. Owlanzi ships a minimal
  one from a CMake build, so it is obtainable without the IDE; spike 2
  settles whether it is generated or extracted once.
- **Host side** (Windows). adb over USB and Bluetooth cannot cross into the
  container, so `deploy.py`, `install.py`, `send.py`, `screen.py` and
  `verify.py` run on the host with Python + `bleak` + platform-tools `adb`.
- **First contact (one time, dev only).** Stock firmware exposes adb only
  over Wi-Fi. Join the TC002 to the development network once via Ulanzi
  Studio, `adb connect <ip>:5555`, deploy our app (which enables USB adb),
  then factory-forget the network. If the unit has a TF slot, `update.img`
  on a FAT32 card is the zero-Wi-Fi alternative. The product never uses
  Wi-Fi.
- **Dev loop.** `deploy.py` pushes the bundle to `/tmp/kiep/` and `EasyUI.cfg`
  to `/tmp/EasyUI.cfg` (the loader checks `/tmp` first), then
  `setprop ctl.restart zkswe`. A power cycle returns to stock. Logs are read
  over `adb shell`; the exact sink is found in spike 0.
- **Permanent install.** Two candidates; spike 2 decides:
  1. *Preferred:* the loader honours a persistent `EasyUI.cfg` override on
     `/data`. Install = `adb push` to `/data/kiep/`, uninstall = delete. No
     flashing, stock app untouched.
  2. *Fallback:* rebuild the `res` squashfs with our app in place of the
     stock one and flash with the documented recipe (stage `update.img` on
     `/data`, set `sys.zkupgrade.dir`, then `sys.zkupgrade.flag`, restart the
     `zkswe` service). `install.py` performs it; `mksquashfs` runs in the
     Docker image.
- **Backup first.** `install.py backup` dumps every mtd partition to the PC
  before any permanent step.

### 5. Development environment and verification loop

- **`docs/SETUP.md`** is written for an agent or person starting cold:
  prerequisites (Docker Desktop, Python, platform-tools, Bluetooth on the
  laptop), one-time first contact, build, deploy, verify, restore, and a
  troubleshooting table (device not found, `hci0` missing, watchdog
  fallback, ABI mismatch). Every step is a copy-paste command with the
  expected output, so success and failure are recognisable without prior
  knowledge.
- **Laptop as the keyboard — `tools/send.py`.** Scans for `KiepDisplay`,
  connects, writes frames exactly as the app does:
  `send.py M "Hallo allemaal"`, `send.py W "…"`, `send.py L "typing"`,
  `send.py attention 2`, `send.py yes`, `send.py no`. It doubles as
  executable protocol documentation.
- **Screen tap — `src/FrameTap.cpp` + `tools/screen.py`.** In Debug builds
  the app appends every frame it hands to `sendLedData` — the last software
  point before SPI; the panel cannot be read back — to a 64-frame ring in
  `/tmp/kiep/frames.bin` (3072 bytes + monotonic timestamp each, ~200 KB).
  `screen.py` pulls the ring over adb and renders: PNG with an LED look
  (viewable by an agent through its file reader), ASCII art in the
  terminal, `--gif` for the last ~2 s of motion, and frame-interval
  statistics (mean, jitter, dropped ticks) for the smoothness check. Off in
  Release builds.
- **`tools/verify.py` — the reusable cycle.** Deploy → wait for the
  heartbeat → run fixtures through `send.py` → capture through `screen.py` →
  compare against a host-side reference render made with the same
  `font.h` glyphs → pass/fail per case. Fixtures: `M` that fits, `M` that
  scrolls (final held tail), `W` extension continuing a scroll, `L` tail,
  newline as space, accented characters, attention (flash frames present +
  count), yes/no colors, idle clear (timeout shortened through a debug file
  `/tmp/kiep/idle_ms`), disconnect clear. Run after every change; a future
  feature adds a fixture and nothing else.

### 6. Spikes (in order, each a go/no-go)

0. **Toolchain.** A hello `libzkgui.so` from the Docker image loads on the
   device, sets `sys.zkapp.state`, initialises the MCU and lights one pixel.
   Finds the log sink.
1. **BLE without a network.** Modules + `hci0` up, advertising with our
   UUID, `bleak` writes from the laptop arrive. Then `wlan0` down. **Hard
   gate:** if BLE only works while associated to a network, the TC002 cannot
   meet the requirement and the project stops here.
2. **Loader.** Minimal `main.ftu`; persistent `EasyUI.cfg` override on
   `/data`. Decides the install path.
3. **Cadence.** 30-ms tick, frame-tap statistics show no jitter or dropped
   ticks while a long message scrolls and BLE writes arrive.

Feature work starts only after spikes 0–2 pass.

## Open questions

- Exact scroll tick and beep volume: tune on hardware.
- Whether the knob press should replay (TC001 parity) or clear; parity
  chosen, revisit after table use.
- Battery exposure in the MCU protocol (decides the red heartbeat).

## Sources

- [atomicstack/tc002-customisation](https://github.com/atomicstack/tc002-customisation) — hardware, kernel, LED SPI, firmware container, flashing recipe, adb
- [UlanziTechnology/Ulanzi-U-Clock-TC002](https://github.com/UlanziTechnology/Ulanzi-U-Clock-TC002) (English fork: [klein0r](https://github.com/klein0r/Ulanzi-U-Clock-TC002)) — official SDK demo: BLE GATT server, MCU init, LED API, audio, buttons, IDE guide
- [Surio89/owlanzi-tc002](https://github.com/Surio89/owlanzi-tc002) — headless CMake build with the official toolchain and SDK packages, adb deploy recipe
- [galadril/Stipple](https://github.com/galadril/Stipple), [sanderdw/awtrix-ng-tc002](https://github.com/sanderdw/awtrix-ng-tc002) — surveyed custom firmwares (Wi-Fi based)
- [Ulanzi TC002 product page](https://www.ulanzi.com/collections/new-arrivals/products/tc002-pixbar-smart-pixel-clock-ii), [Ulanzi Studio TC002 docs](https://docs.ulanzistudio.com/tc002/en/software/ulanzi-studio.html) — specifications
