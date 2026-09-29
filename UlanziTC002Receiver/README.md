# KiepDisplay receiver — Ulanzi TC002

Turns the Ulanzi TC002 pixel clock (52×16 LEDs, Linux + FlyThings, not Android) into a BLE display for
the KiepKeyboard app: one row of large text, same protocol as the TC001 firmware in `../UlanziReceiver/`
and the Android "KiepKeyboard Display". No Wi-Fi, no cloud, no server: the radio range is the privacy
boundary.

Design: [docs/proposals/tc002-receiver.md](../docs/proposals/tc002-receiver.md); protocol:
[docs/proposals/external-display.md](../docs/proposals/external-display.md).
**Setup, build, deploy, verify, install: [docs/SETUP.md](docs/SETUP.md).**

## Behavior

- Advertises as **KiepDisplay** (service `b1e60001-…`); pair it in the app via Settings → External
  display → Add display. Public static BLE address.
- **Heartbeat pixel** top-right (app accent blue): blinking = advertising, solid = app connected,
  red = battery below 15 %.
- **Messages** (`M`, send hotkey / sentence mode): centered when they fit (~6 characters), otherwise
  scroll in from the right once and hold the tail. **Word mode** (`W`) extensions continue a running
  scroll. **Live typing** (`L`) shows the tail of the buffer, updated in place, no motion.
- Font: 12-px caps generated from DejaVu Sans Bold, proportional, Latin-1 accents included. Scroll:
  1 px per 30 ms on a dedicated render thread (measured jitter 0 ms).
- **Attention**: white flash + `res/ui/beep.mp3`, repeated per the app's count. **Yes/no**: green / red
  flash, silent.
- **Knob**: rotate = notification volume (0–6, one preview sound when the knob rests); press = replay the
  last message. **Buttons**: left / right = brightness (5 levels), middle = replay. Levels persist in
  `/data/kiep/`.
- Clears on disconnect and after 10 minutes without input.
- USB-C adb is enabled at boot (debug/update path). Wi-Fi is never brought up (`persist.wifi.on=0`).

## Layout

`src/Presenter.*` pure logic (host tests in `tests/`), `src/Device.*` wiring + render thread,
`src/Panel.*` SPI panel, `src/KiepGatt.c` BlueZ GATT server, `src/BtBringup.*` radio bring-up,
`src/Mcu.*` battery UART, `src/Input.*` knob/buttons, `src/Sound.*` beep, `src/FrameTap.*` debug ring.
`tools/`: `fetch-sdk.py`, `build.sh`, `gen-font.py`, `deploy.py`, `send.py`, `screen.py`, `verify.py`,
`install.py`. Everything compiles in Docker (`docker compose run --rm build`); the host runs only
`adb` and the Python tools.

## Tuning

Constants at the top of `src/Presenter.h` (tick, flash timings, idle clear, brightness levels),
`src/Device.cpp` (settle window, volume preview delay, battery threshold), `src/Sound.cpp` (volume map).

## Licenses

Our code: MIT (repository license). Third-party notices (BlueZ LGPL, DejaVu, FlyThings SDK, Arm toolchain):
[LICENSE-THIRD-PARTY.md](LICENSE-THIRD-PARTY.md).
