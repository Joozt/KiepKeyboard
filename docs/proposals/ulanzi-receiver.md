# Proposal: Ulanzi TC001 receiver firmware

Status: Draft for review
Author: (Joost)
Date: 2026-07-05

Companion to [external-display.md](external-display.md) — that document owns
the BLE protocol; this one covers the device.

## Background

The Ulanzi TC001 desk clock is the first external display: it sits on the
table, scrolls what the user typed, and beeps/flashes for attention. It must
talk **directly to the KiepKeyboard app over BLE** — no WiFi, no MQTT broker,
no server.

## Research: existing firmware does not fit

All established TC001 firmwares are WiFi-based and need infrastructure,
which violates the no-server/no-internet requirement:

- **AWTRIX3** — MQTT/HTTP over WiFi; designed around a broker (typically Home
  Assistant). No BLE.
- **Pixelix, PixelIt, EspHoMaTriXv2** — WiFi/ESPHome/Home Assistant variants
  of the same idea. No BLE.

Conclusion: **custom firmware**, and it is small — the device side is a BLE
peripheral that renders text and beeps. AWTRIX code brings little to reuse
(its value is the MQTT/app ecosystem); the rendering stack comes from standard
libraries instead.

## Hardware (per rroels/ulanzi_tc001_hardware)

| Component | Detail |
| --- | --- |
| MCU | ESP32-WROOM-32D — BLE + Bluetooth Classic capable |
| Matrix | 8×32 WS2812B-Mini (256 LEDs), GPIO32, serpentine layout (even rows L→R, odd rows R→L) |
| Buzzer | GPIO15 (needs `INPUT_PULLDOWN` when idle to avoid noise) |
| Buttons | GPIO26 / GPIO27 / GPIO14, active low, internal pullups |
| Extras | SHT3x temp/humidity + DS1307 RTC on I2C (21/22), light sensor GPIO35, battery voltage GPIO34 — unused in Phase 1 |
| Flashing | Onboard CH340 USB-serial; Arduino IDE / PlatformIO supported |

## Firmware design

- **Stack**: PlatformIO (Arduino framework), **NimBLE-Arduino** (BLE
  peripheral), **FastLED + Adafruit-GFX-compatible matrix mapping** for
  serpentine 8×32 text rendering (5×7 font ≈ 5 visible characters).
- **BLE**: advertises the KiepDisplay service (UUIDs from
  external-display.md); bonding (Just Works), encrypted link; single central
  (the app).
- **Rendering**:
  - `MSG` frames: scroll the text across the matrix once, then hold the tail
    (repeat scroll on a long-press of the middle button — optional).
  - `LIVE` frames: static tail view of the buffer (last ~5 chars), updated in
    place — no scroll animation, per the wire-semantics decision.
  - UTF-8 in, rendered as Latin-1 subset; unsupported glyphs as `?` (Dutch
    needs no more).
- **Attention**: short buzzer beep (GPIO15 PWM) + one full-matrix white flash.
- **Idle/privacy**: clear the matrix on BLE disconnect and after ~10 min
  without frames (timeout value shared with the protocol doc's open question).
- **Brightness**: fixed sane default in Phase 1; light-sensor auto-dim later.
- **Buttons**: unused in Phase 1 (open question: middle = clear display).

## Development plan

1. PlatformIO skeleton: matrix "hello" scroll + beep (validates GPIO map).
2. NimBLE peripheral with the two characteristics; test writes from a BLE
   scanner app (nRF Connect).
3. Wire protocol rendering (`MSG` scroll / `LIVE` tail), disconnect/idle clear.
4. Pair against the KiepKeyboard app prototype; latency and range testing at
   a real table.

Repo layout: firmware lives in this repository under `UlanziReceiver/`
(PlatformIO project), keeping app and receiver in lockstep with the shared
protocol.

## Sources

- [AWTRIX3](https://github.com/Blueforcer/awtrix3) — MQTT/WiFi architecture (not reusable here)
- [Ulanzi TC001 hardware reference](https://github.com/rroels/ulanzi_tc001_hardware) — GPIO map, serpentine layout, flashing, code examples
- [Pixelix](https://github.com/BlueAndi/Pixelix), [PixelIt_Ulanzi](https://github.com/aptonline/PixelIt_Ulanzi), [EspHoMaTriXv2](https://github.com/lubeda/EspHoMaTriXv2) — surveyed, all WiFi-based
