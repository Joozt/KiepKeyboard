# Proposal: Ulanzi text presentation modes

Status: Draft for review
Author: (Joost)
Date: 2026-07-05

Companion to [ulanzi-receiver.md](ulanzi-receiver.md) and
[external-display.md](external-display.md). No implementation yet.

## Problem

Scrolling text on the Ulanzi flickers, and following a long message across the
~5-character window is exhausting ("you get a bit crazy from looking at the
screen"). Live mode — static text updated letter by letter — is calm. Two
separate causes:

1. **Flicker.** Each scroll step rewrites all 256 WS2812 LEDs. On the ESP32
   the NeoPixel data stream is timing-critical; the NimBLE stack's radio task
   can preempt or delay it, occasionally corrupting a frame — visible as
   random flicker, worst when frames are sent 25× per second (scrolling).
   Static frames (live mode) are written once and hold perfectly, which
   matches the observation.
2. **Reading fatigue.** A 1-pixel jump every 40 ms is sample-and-hold motion:
   the eye continuously tracks a strobing 5-character window at a forced pace
   of ~4 characters/second, with no resting point, for the whole message.
   Fatigue is inherent to continuous scrolling on a window this small — tuning
   the speed only trades strain for duration.

## Proposal: three presentation modes behind a firmware constant

```cpp
enum TextPresentation { SCROLL, PAGER, SCROLL_SMOOTH };
static const TextPresentation TEXT_PRESENTATION = PAGER; // proposed default
```

All timings become named constants next to it. The mode applies to `M` and
`W` frames; live (`L`) frames stay as they are — their static tail view is
the baseline everything is judged against.

### Mode A — SCROLL (current behavior)

1 px per `SCROLL_STEP_MS` (40 ms), once through, hold the tail; `W`
extensions append without restart. Kept as-is for comparison and as
fallback. Knobs: step time (larger = calmer but slower), brightness.
Fundamental limits stay: strobing motion + BLE-induced flicker at 25 fps.

### Mode B — PAGER (word pager) — proposed default

No motion at all: the message is shown one word at a time (RSVP-style —
like the app's own live typing, but word-wise).

- Tokenize the message on spaces; show each word centered, hold for
  `PAGE_BASE_MS` (≈400) + `PAGE_PER_CHAR_MS` (≈120) per character; a short
  blank (`PAGE_GAP_MS` ≈80) between words marks the transition.
- A word wider than the screen (>5 chars) scrolls *only itself*, once,
  slowly (~70 ms/px) — the only motion left, seconds at most.
- `W` frames (word mode) enqueue the new words at the end of the queue; the
  pager simply never runs dry while the user keeps typing. `M` frames
  (hotkey/sentence) replace the queue and start from the first word.
- Middle button replays the whole queue. After the last word: hold the last
  word (mirrors today's tail-hold).
- Frame rate drops from 25 fps to ~1 frame per word — the BLE flicker
  surface all but disappears as a side effect.

Effort: small-medium (tokenizer + queue + timer states; render stays the
existing static path). Risk: pacing taste — the three timing constants will
need on-device tuning.

### Mode C — SCROLL_SMOOTH (anti-aliased scrolling)

Keep continuous scrolling but make the motion look fluid instead of
strobing:

- Render the full message once per frame-set to an offscreen 1-bit canvas
  (`GFXcanvas1`), then emit columns with a **fractional offset**: at scroll
  position x = 10.4, each LED column blends 60%/40% of canvas columns 10/11
  (linear brightness interpolation). Sub-pixel rendering removes the harsh
  1-px jump perception.
- Slower default (`SCROLL_SMOOTH_STEP_MS` ≈70 per full pixel, interpolated in
  quarter-pixel frames) — calmer pace, still fluid.
- Effort: medium (custom column renderer bypassing `matrix.print` per frame;
  glyph rendering itself stays GFX). Fatigue improves but continuous motion
  remains; frame rate *rises* (interpolation frames), so the BLE flicker
  mitigation below becomes more relevant, not less.

## Flicker mitigations independent of mode

- Already in place: static frames are only written when content changes.
- **FastLED with the I2S parallel driver** instead of Adafruit NeoPixel:
  I2S DMA output is immune to BLE task jitter. Library swap for the render
  path (moderate churn) — hold in reserve, apply only if PAGER still shows
  flicker in practice.
- Relaxed BLE connection interval (peripheral-requested) reduces radio
  contention windows; cheap to try alongside either mode.

## Recommendation

Implement **PAGER as default** — it removes both the fatigue and (as a side
effect) nearly all the flicker surface, and it matches the reading rhythm
the user already finds comfortable in live mode. Keep SCROLL selectable via
the constant for comparison. Defer SCROLL_SMOOTH: it is the most effort and
attacks only half the problem; build it only if paging turns out not to suit
conversation partners. Defer the FastLED/I2S swap until proven necessary.

## Open questions

- Pager pacing (base/per-char/gap) — tune on the device with the actual
  conversation partners.
- Should the pager briefly show the *whole* message tail at the end (context
  after the last word), or hold the last word only?
- Long-word handling: scroll-once (proposed) vs splitting into chunks
  ("keybo"/"ard").
- Is a per-message repeat (message shown twice) wanted for the table
  audience, or does the middle-button replay cover it?
