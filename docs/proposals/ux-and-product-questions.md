# Proposal: UX and product-level observations

Status: Draft for review — mostly questions, not prescriptions
Author: Claude (full-app review)
Date: 2026-07-04

These are findings where the code is *consistent* but the product intent is
unclear, plus small UX rough edges. Answers will fold into the other proposals.

## 1. Two launcher icons — deliberate?

`SettingsActivity` has its own `MAIN`/`LAUNCHER` intent filter
(`AndroidManifest.xml:44-47`), so the device shows **two** icons: "KiepKeyboard"
and "KiepKeyboard Settings". Guess: intentional — it's the recovery path when
the in-app settings button is hidden (`enable_settings_button = false`), so a
caregiver can still reach settings from the launcher.

**Confirmed intentional (Joost, 2026-07-04) — keep.** Action: document the why
in AGENTS.md (it looks like a manifest bug to any passer-by).

## 2. Hardware Back/Esc key exits the "kiosk"

The app is described as "locked to one fullscreen text field", but a hardware
keyboard's Esc/Back (and Home) leave the app instantly — one stray keypress and
the user is stranded on the Android launcher they can't operate.

**Decision (Joost, 2026-07-04): device is unprotected today; fix by
documentation, not code.** Add a short "Lock the phone to the app" section to
the README hardware/setup recipe: enable Android screen pinning
(Settings → Security → App pinning) and pin KiepKeyboard once per device. No
in-app `startLockTask()` — pinning is the supported, code-free mechanism and
the caregiver controls the exit (pin/pattern).

## 3. Yes/no sounds can be silently missed right after launch

`SoundPool.load` is async (`YesNo.java:34-35`); a yes/no keypress in the first
~second after (re)start plays nothing. Given `recreate()` fires on every settings
change, this window recurs. Cheap fix: ignore until loaded is fine, but a
`setOnLoadCompleteListener` + queue-one-press would make the device feel
reliable. Low priority; worth knowing it's a race, not flakiness.

## 4. FAB dim timer races

`configureSettingsFab` (`FullscreenActivity.java:65-73`): every tap schedules
another 3 s dim timer and none are cancelled — tap the FAB twice quickly and the
first timer dims it while the user is deciding, and the second tap then *opens
settings* on what looks like a dim button. Cancel the pending dim
(`removeCallbacks`) on each tap. Two lines.

## 5. TTS failure is silent

If the TTS engine fails to init or the language pack is missing
(`TTS.java:33-40`), the speak key just does nothing — logged, invisible. For a
communication device, a one-time Snackbar ("Text-to-speech unavailable — check
TTS settings") would turn a mystery into a fixable configuration step.

## 6. Positive observations (no action)

- The `GlobalKeyPressListener` command-bus is a genuinely good core abstraction —
  one dispatcher, features self-register, settings map keys uniformly.
- `SettingsActivity` as the single constants registry keeps every pref
  discoverable.
- Manual color animation with a documented reason (system animations disabled on
  the device) is the right call, just needs frame pacing
  ([platform-modernization](platform-modernization.md) #3).
- README + AGENTS.md quality is far above typical hobby-project level.

## Open questions (the grill list)

1. Two launcher icons: intentional recovery path or accident?
2. Is screen pinning already part of the device setup?
3. Has anyone confirmed Firebase events were *ever* received? (Decides
   dependency-diet #2.)
4. Compose deps: template leftovers or migration intent? (Decides
   dependency-diet #1.)
5. How soon is editable-files Phase 1? (Decides whether ReadTxtFileActivity
   bugs are worth fixing.)
6. Is the app tested on Android 15/16 hardware? (Platform-modernization #1.)
