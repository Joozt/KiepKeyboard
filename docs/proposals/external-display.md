# Proposal: External display

Status: Phase 1 implemented (2026-07-05) — pending hardware testing
Author: (Joost)
Date: 2026-07-05

> **Implementation notes.** Built as designed. Indicator = small accent dot,
> top-right, visible while ≥1 display is connected. `GlobalKeyPressListener`
> now fires all listeners per key (registration order), enabling the
> send-key = speak-key default; Settings exempts exactly that pair from
> duplicate-key validation. Receiver firmware: `UlanziReceiver/` (PlatformIO).
> Untested against real hardware at the time of writing.
>
> **Phase 2 notes (2026-07-24).** Android receive mode implemented as
> `DisplayActivity` — its own launcher entry ("Kiep Display", accent-blue
> icon variant). Foreground-only: advertises the KiepDisplay service while
> visible, keeps the screen on at full brightness, clears on disconnect and
> after the 10-minute idle timeout (Ulanzi parity). Deviations from the
> Ulanzi rendering: no scrolling (text autosizes to fit the screen, so the
> `M`/`W`/`L` types render identically) and attention plays the system
> notification sound instead of a beep. Yes/no reuses the in-app yes/no
> samples and green/red highlight. `BLUETOOTH_ADVERTISE` is requested when
> the receiver is first opened; the keyboard side is unchanged.

## Background

In a group setting (a conversation around a table), the user wants what they type
to be readable by others on one or more external displays: an Ulanzi TC001 pixel
clock on the table, and later a phone/tablet running this app in a receive mode —
possibly several at once so everyone can see it.

Hard requirement: **local wireless only (BLE), never the internet.** Typed text
is intimate; it must be physically impossible for it to leave the room. BLE's
~10 m range *is* the privacy model: walk away and the link drops. An
internet/cloud transport will not be added.

## Goals

- Send typed text to 1–3 paired receivers over BLE, no server, no internet.
- Modes: continuous **character** (live typing), **word**, **sentence** streams,
  plus an always-available **send hotkey** (sentence under the cursor, like TTS).
- **Attention** signal (receiver flashes/beeps), by hotkey and optionally
  automatically after sends.
- First receiver: Ulanzi TC001 with custom firmware
  (see [ulanzi-receiver.md](ulanzi-receiver.md)); the protocol is
  receiver-agnostic so an Android receive mode can follow.

## Non-goals (for now)

- Internet/cloud transport (never), receiver-to-sender replies, per-receiver
  content differences, Android receive mode (Phase 2), firmware OTA.

## Decisions (from the design review)

| Question | Decision |
| --- | --- |
| Wire semantics | **Replace, with a rendering hint.** Every frame carries the complete text to display. Two kinds: `MSG` (a message to read — receiver scrolls it when wider than its screen) and `LIVE` (current typing buffer — receiver shows the tail, updated in place, no scroll restarts). Character mode sends `LIVE`; word/sentence/hotkey send `MSG`. Receivers are stateless; backspace/corrections just work. |
| Privacy gate | BLE-only by design; master enable switch in Settings; receivers **clear their display on disconnect** and after an idle timeout. Bonded (encrypted) links. |
| Mode model | The send hotkey and attention hotkey are always active while the feature is on. One Settings choice selects the *continuous* stream on top: **Off / Character / Word / Sentence** ("hotkey only" = Off). Mode changes are caregiver actions in Settings; no mode-cycle key. |
| Attention | One protocol command; each receiver expresses it its own way (Ulanzi: beep + flash; tablet: flash + sound). One global **auto-attention** toggle covering word/sentence/hotkey sends (character mode excluded). Note: in word mode that means one attention per word — caregiver's choice. |
| Send key vs TTS key | Configurable, **may be the same key** — and defaults to the TTS speak key (`\`): one press speaks *and* displays. Requires multi-listener dispatch (below) and a validation exception for this pair. |

## Protocol (shared interface — the Ulanzi proposal builds against this)

- **Roles**: the app is BLE **central**; every receiver is a **peripheral**
  advertising the KiepDisplay service.
- **Service**: one custom 128-bit service UUID (generated at implementation)
  with two characteristics:
  - **Text** (write): value = 1 type byte followed by UTF-8 text; the full
    value replaces the receiver's display content. Types: `M` = message
    (always presented from the start — scrolled when it doesn't fit), `W` =
    word-append message (word mode; see extension rule), `L` = live typing
    buffer (tail view). Values longer than the negotiated MTU use standard
    GATT long writes; receivers render on write completion. Newlines are
    legal; constrained receivers may render them as spaces.
  - **Attention** (write): payload `[effect, count]`. Effect 1 = attention
    (white flash + beep, repeated `count` times, 1–5); effect 2 = yes (green
    flash); effect 3 = no (red flash) — the yes/no flash is longer and
    silent, similar to the app's own highlight animation.
- **Extension rule**: a `W` frame whose text extends the currently shown text
  never restarts an ongoing scroll — the appended words follow at the end; a
  held tail resumes scrolling from its current position when the text no
  longer fits.
- **Security**: BLE bonding (Just Works — receivers have no input surface);
  link encrypted; the app only connects to receivers it bonded via the in-app
  "Add display" flow.
- **Receiver duties**: render `MSG` (scroll once, then hold or repeat —
  receiver's choice), render `LIVE` (tail, in-place), attention expression,
  clear on disconnect and after idle timeout (default ~10 min).
- **Multi-receiver**: the app holds up to 3 concurrent GATT connections and
  writes identical frames to all.

## App design

Follows the house pattern (feature class, constructor takes
`(Context, GlobalKeyPressListener, EditText)`, no cross-feature dependencies,
wired in both `FullscreenActivity` and `FileEditorActivity`):

- **`ExternalDisplay`** feature class: owns BLE connections, the mode engine,
  and both hotkeys.
  - Character mode: `TextWatcher` → debounced (~100 ms) `LIVE` frame with the
    current text.
  - Word mode: on end-char (same set as Abbreviations), append the completed
    word to the current message and send it as `MSG`; a sentence terminator
    completes the message, the next word starts a new one.
  - Sentence mode: on terminator, send the finished sentence (`TTS.sentenceAt`
    reused) as `MSG`.
  - Send hotkey: `MSG` with the sentence under the cursor (`TTS.sentenceAt`).
- **`GlobalKeyPressListener`**: upgraded to allow **multiple listeners per
  key** (registration order, all fire) — needed for send-key == speak-key.
  Duplicate-key validation in Settings keeps rejecting conflicts *except* the
  send↔speak pair, which is a supported combination.
- **Settings — "External display" section**: enable switch; continuous-mode
  list (Off/Character/Word/Sentence, default Off); send hotkey (default `\`,
  = TTS default); attention hotkey (default None, speak-all convention);
  auto-attention switch (default off); "Add display" (BLE scan + bond) and a
  paired-displays list with remove.
- **Permissions**: `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN`
  (`neverForLocation`), Android 12+.
- **Indicator**: while ≥1 receiver is connected, a subtle on-screen indicator
  (shape TBD — candidate: a small dot near the battery bar).

## Phases

1. **Prototype**: app feature + protocol + Ulanzi TC001 receiver
   ([ulanzi-receiver.md](ulanzi-receiver.md)).
2. **Android receive mode**: this app advertising as a peripheral, rendering
   `MSG`/`LIVE` fullscreen — same protocol, zero app-side sender changes.
3. Polish: per-receiver management UI, connection robustness, tuning.

## Open questions

- Indicator design on the main screen (dot vs battery-bar accent).
- Word-mode message reset rule: terminator-completes-message is the proposal —
  confirm it feels right on the device.
- Receiver idle-clear timeout default (10 min?).
- Ulanzi hardware buttons: unused in Phase 1, or map middle button to
  "clear display"?
