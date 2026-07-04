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
- **Messages** (send hotkey, word/sentence mode) scroll across the matrix
  once, then hold the tail.
- **Live typing** (character mode) shows the end of the buffer, updated in
  place.
- **Attention** beeps and flashes the matrix white, twice.
- The display **clears on disconnect** and after **10 minutes** without
  input — old text never lingers on the table.
- The **middle button replays** the last message.
- Non-ASCII characters render as `?` (Phase 1).

## Flashing

1. Install [PlatformIO](https://platformio.org/) (VS Code extension or CLI).
2. Connect the TC001 over USB-C (onboard CH340 serial — no extra hardware).
3. From this directory: `pio run -t upload`

Reflashing back to stock/AWTRIX later works the same way; this firmware does
not touch the bootloader.

## Tuning

Constants at the top of `src/main.cpp`:

- `BRIGHTNESS` (0–255, default 40)
- `IDLE_CLEAR_MS` (default 10 min)
- `SCROLL_STEP_MS` (scroll speed, default 40 ms/pixel)
- Text color (default the app's accent blue)
- If text renders mirrored/interleaved on your unit, adjust the
  `NEO_MATRIX_*` orientation flags.

## Hardware map

Per [rroels/ulanzi_tc001_hardware](https://github.com/rroels/ulanzi_tc001_hardware):
matrix on GPIO32 (8×32 WS2812B, serpentine), buzzer GPIO15, middle button
GPIO27. Temperature/light/RTC peripherals are unused by this firmware.
