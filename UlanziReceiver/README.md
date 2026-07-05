# KiepDisplay receiver — Ulanzi TC001 firmware

Custom firmware that turns the Ulanzi TC001 pixel clock into a BLE display
for the KiepKeyboard app. No WiFi, no MQTT, no server: the app connects
directly over Bluetooth Low Energy (the radio range is the privacy boundary).

Design: [docs/proposals/ulanzi-receiver.md](../docs/proposals/ulanzi-receiver.md)
and [docs/proposals/external-display.md](../docs/proposals/external-display.md)
(protocol).

## Behavior

- Advertises as **KiepDisplay**; pair it in the app via
  Settings → External display → Add display.
- **Heartbeat pixel** (top-right, the app's accent blue): **blinking** =
  powered and advertising, **solid** = the app is connected.
- **Messages** (send hotkey, word/sentence mode) scroll across the matrix
  once, then hold the tail.
- **Live typing** (character mode) shows the end of the buffer, updated in
  place.
- **Attention** beeps and flashes the matrix white; the app's
  "Attention beeps/flashes" setting controls how many times (default once).
- **Yes/no answers** (app setting "Show yes/no on displays", default off)
  flash the matrix green/red for ~0.8 s, silently.
- Word mode extensions of the current message continue an ongoing scroll
  instead of restarting it; when the held text stops fitting, scrolling
  resumes from the current position. Hotkey/sentence messages always scroll
  from the start.
- Newlines in live text render as spaces (single-line matrix).
- **Buttons**: left/right arrows step the matrix brightness through five
  levels (persisted across power cycles); the middle button replays the
  last message.
- The display **clears on disconnect** and after **10 minutes** without
  input — old text never lingers on the table.
- Non-ASCII characters render as `?` (Phase 1).

## Flashing

1. Install [PlatformIO](https://platformio.org/) (VS Code extension or CLI).
2. Connect the TC001 over USB-C (onboard CH340 serial — no extra hardware).
3. From this directory: `pio run -t upload`
4. Debug logging: `pio device monitor` (115200) — logs advertising,
   connect/disconnect, every received frame, attention, idle clears.

Reflashing back to stock/AWTRIX later works the same way; this firmware does
not touch the bootloader.

## Tuning

Constants at the top of `src/main.cpp`:

- `BRIGHTNESS_LEVELS` (button-stepped levels, default max)
- `IDLE_CLEAR_MS` (default 10 min)
- `SCROLL_STEP_MS` (scroll speed, default 40 ms/pixel)
- Text color (default white; the heartbeat pixel stays accent blue)
- If text renders mirrored/interleaved on your unit, adjust the
  `NEO_MATRIX_*` orientation flags.

## Hardware map

Per [rroels/ulanzi_tc001_hardware](https://github.com/rroels/ulanzi_tc001_hardware):
matrix on GPIO32 (8×32 WS2812B, serpentine), buzzer GPIO15, middle button
GPIO27. Temperature/light/RTC peripherals are unused by this firmware.
