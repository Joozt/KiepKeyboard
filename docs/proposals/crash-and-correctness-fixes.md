# Proposal: Crash and correctness fixes

Status: Draft for review
Author: Claude (full-app review)
Date: 2026-07-04

## Background

Full review of the app (~1250 lines, all files read). This document collects the
findings that are plain bugs: crashes, resource leaks, and behavior that does the
wrong thing. Fixes are ordered by severity. Some findings touch
`ReadTxtFileActivity`, which the [editable-files proposal](editable-files.md)
plans to remove — those are marked so we don't invest in code that is about to die.

## Findings

### 1. Crash: TTS on an empty buffer (high)

`TTS.getTextToSpeak` (`TTS.java:91`) calls `charAt(length - 1)` after trimming.
If the buffer is empty or whitespace-only when the speak key is pressed, this
throws `StringIndexOutOfBoundsException` and crashes the app.

Reproduction: clear all text (or fresh screen where the "KiepKeyboard" default is
selected and replaced), press `\`. The typed `\` is stripped by
`GlobalKeyPressListener` *before* the listener fires, so the buffer really is
empty when `getTextToSpeak` runs.

For the target user this is the worst possible failure: the communication device
dies mid-conversation.

**Fix:** guard at the top of `getTextToSpeak`:

```java
String complete_text = editText.getText().toString().trim();
if (complete_text.isEmpty()) {
    return "";
}
```

`speak()` already handles `""` correctly. One line, plus a unit test once the
sentence-picker is extracted (see [testing-and-ci](testing-and-ci.md)).

### 2. Programmatic text changes trigger command keys (high)

`GlobalKeyPressListener`'s `TextWatcher` (`GlobalKeyPressListener.java:56-63`)
fires on *every* text change, not just typed keys. Any text that arrives
programmatically and contains a registered command character (`[`, `]`, `\` by
default) triggers the command *and silently strips the character*:

- **Abbreviation expansions** (`Abbreviations.java:59`): an expansion text
  containing `[` plays the "no" sound and loses the bracket.
- **Downloaded .txt files** (`ReadTxtFileActivity.java:145`, `showText` calls
  `setText` with filters removed): every `[`, `]`, `\` in the document plays a
  sound on load and is deleted from the displayed text.

### 3. Cursor jump and O(n) rebuild on every strip (medium)

`containsCharSequence` (`GlobalKeyPressListener.java:83-93`) rebuilds the whole
editable via `toString().replace(...)` to strip one character. Consequences:

- The cursor/selection is thrown to the end of the text — if the user typed a
  command key with the cursor mid-document, they lose their place.
- Every keystroke scans the full text for every registered char listener; on a
  large downloaded file this gets laggy.
- It strips **all** occurrences, not the one that was typed.

**Fix for 2 + 3 (same root cause):** react to the *insertion*, not the buffer
content. In `onTextChanged(s, start, before, count)` check whether exactly one
character was inserted (`count == 1 && before == 0`) and whether `s.charAt(start)`
is a registered command char; if so, delete just that character at `start` and
fire the listener. This makes programmatic `setText`/`replace` inert, preserves
the cursor, and is O(1) per keystroke. One guard in the shared dispatcher fixes
both call sites — no per-feature workarounds.

### 4. BatteryStatus receiver leaks on every settings change (medium)

`BatteryStatus.java:50` registers an `ACTION_BATTERY_CHANGED` receiver and never
unregisters it. `FullscreenActivity.onSharedPreferenceChanged` calls `recreate()`
on **every** settings change, so each change stacks another receiver holding
references to the dead activity's views. Long-lived kiosk device + occasional
settings tweaks = slow leak plus duplicate work per battery tick.

**Fix:** keep the receiver in a field, add `destroy()` that unregisters, call it
from `onDestroy()` in both activities — same pattern `TTS.destroy()` already uses.

### 5. SoundPool never released (low)

`YesNo.java:33` creates a `SoundPool` per construction (so per `recreate()`),
never calls `release()`. Same fix pattern: `destroy()` called from `onDestroy()`.

### 6. Conflicting key assignments silently break features (medium)

Settings let every command pick from the same `functionKeys` list
(`SettingsActivity.java:21-22`) with no uniqueness check. Both listener maps in
`GlobalKeyPressListener` are plain `Map.put`, so the **last registrant wins**
(registration order in `FullscreenActivity.onCreate:41-57`: FontSize,
Abbreviations, YesNo, TTS, download). Set the "no" key to `F10` and font-increment
silently stops working — no error, no hint.

**Fix (decided 2026-07-04, both layers):**
1. `GlobalKeyPressListener.addListener` logs on duplicate registration — turns
   silent breakage into a visible one (safety net for legacy saved conflicts).
2. Settings validation — **reject with toast**: an `OnPreferenceChangeListener`
   on each key preference refuses a value already used by another command and
   shows a toast naming the current owner ("F10 is already used by Font size").
   No invalid state can be saved.

### 7. recreate() storms (low)

`FullscreenActivity.onSharedPreferenceChanged` (`FullscreenActivity.java:83-85`)
recreates on any default-prefs change:

- Changing N settings in one visit recreates the (paused) activity N times —
  each recreate re-initializes the TTS engine, SoundPool, and update check.
- `FontSize.saveCurrentFontSize()` runs in `onPause` and writes to default prefs;
  if the size changed via hotkeys, backgrounding the app triggers a pointless
  recreate.

**Fix (decided 2026-07-04):** set a dirty flag in the listener, recreate once in
`onStart()` when flagged; ignore the `FONT_SIZE` key (it's already applied live
by the hotkeys). Confirmed no setting needs to apply live behind the settings
screen.

### 8. Battery level ignores scale (low)

`BatteryStatus.java:33` reads `EXTRA_LEVEL` and assumes the scale is 100. Correct
form: `level * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)`. Wrong
on the rare device where scale isn't 100.

### 9. Download callback touches UI after destroy (low) — *dies with editable-files*

`ReadTxtFileActivity` never cancels the OkHttp call; `onResponse` →
`runOnUiThread` → Snackbar on a possibly-destroyed activity. Guard with
`isDestroyed()`/`isFinishing()` or cancel the call in `onDestroy()`.

**Decision (Joost, 2026-07-04): skip.** editable-files Phase 1 is next up and
removes this activity; only the shared `GlobalKeyPressListener` fix (#2/#3)
is worth doing, since the new editable mode inherits that dispatcher.

### 10. UpdateCheck listener not unregistered on destroy (low)

`UpdateCheck` unregisters its `InstallStateUpdatedListener` only when a download
completes (`UpdateCheck.java:75`). If the activity is destroyed mid-download the
listener (holding the activity) stays registered. Add an `unregister()` called
from `onDestroy()`.

## Suggested order

1 (crash) → 2+3 (one fix) → 4 → 6 → 7 → 5 → 8 → 10. Item 9 skipped
(editable-files Phase 1 is next up and deletes the activity).

## Open questions

- ~~Item 6: validation UX?~~ **Answered: reject with toast.**
- ~~Item 7: any setting that must apply instantly?~~ **Answered: no —
  recreate-once-on-return.**
