# Proposal: Platform modernization

Status: Draft for review
Author: Claude (full-app review)
Date: 2026-07-04

## Background

The app targets SDK 37 but its fullscreen, font-scaling, and theming code dates
from the API-19 era. Most of it still works, but the immersive-mode path is the
one most at risk of actually breaking on current Android, and the theme/resource
files carry visible template fossils.

## Findings

### 1. Deprecated immersive mode — real breakage risk (high)

`FullscreenActivity.onWindowFocusChanged` (`FullscreenActivity.java:113-121`) and
the identical copy in `ReadTxtFileActivity.java:83-91` use
`setSystemUiVisibility` with `SYSTEM_UI_FLAG_*` — deprecated since API 30. With
`targetSdk = 37`, devices on Android 15+ **force edge-to-edge** and the legacy
flags are on their way out; hidden system bars are the core UX of this app
("no system bars, nothing else to get lost in").

**Fix:** one small shared helper used by both activities:

```java
WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
WindowInsetsControllerCompat c =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
c.hide(WindowInsetsCompat.Type.systemBars());
c.setSystemBarsBehavior(
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
```

Call it from `onCreate` (the insets controller re-applies on focus changes, so
the `onWindowFocusChanged` override can go).

**Context (Joost, 2026-07-04):** the primary device is a Samsung Galaxy
Note 10+ on its final update (Android 12) — the legacy flags still work there,
so the *own* device is safe. The exposure is Play Store users on Android 15/16.
Priority: medium — do the migration, verify on the Note 10+ (must not regress
the real device) plus an Android 15/16 emulator for the enforcement path.

### 2. Deprecated `scaledDensity` in FontSize (medium)

`FontSize.java:27,36,54` back-computes sp from pixels via
`DisplayMetrics.scaledDensity`, deprecated in API 34 (non-linear font scaling
breaks the linear px→sp assumption — and this app runs at 100-400 sp, exactly
the range where non-linear scaling bites).

**Fix:** stop round-tripping through pixels. Keep the current size in sp as an
`int` field: initialize from prefs, `+=`/`-=` 10 on the hotkeys, apply with
`setTextSize(COMPLEX_UNIT_SP, ...)`, save the field in `saveCurrentFontSize()`.
Deletes the deprecated API *and* the lossy float math.

### 3. Small deprecated/discouraged APIs (low)

| Where | Issue | Fix |
| --- | --- | --- |
| `FullscreenActivity.java:68` | `new Handler()` (deprecated no-arg) | `view.postDelayed(...)` |
| `FullscreenActivity.java:136` | `commit()` on main thread in `onPause` | `apply()` (safe: `QueuedWork` flushes before process death); drop the `@SuppressLint` |
| `YesNo.java:84` | `getIdentifier` for `raw/yes`, `raw/no` | `R.raw.yes` / `R.raw.no` directly; deletes the `@SuppressLint("DiscouragedApi")` |
| `YesNo.java:109-124` | manual animation loop uses bare `handler.post(this)` — a busy-loop at max callback rate for 250 ms | `view.postOnAnimation(this)` — still bypasses the animator-scale=0 problem (the documented reason it's manual), but paces at vsync |

### 4. Theme and resource fossils (medium, cosmetic + consistency)

Leftovers from the old Android Studio "FullscreenActivity" template:

- `values/attrs.xml` — `ButtonBarContainerTheme` / `metaButtonBar*` attrs, unused
  except by `FullscreenTheme` items that reference nothing visible.
- `values/styles.xml` — `FullscreenActionBarStyle` + action-bar overlay config
  for an action bar that never shows; `black_overlay` color only used there.
- Theme inconsistency: app theme `Theme.KiepKeyboard` parents **framework**
  `android:Theme.Material.Light.NoActionBar` (`values/themes.xml:4`) while
  activities use AppCompat themes; `SettingsActivity` hardcodes
  `Theme.AppCompat.Light` in the manifest (`AndroidManifest.xml:43`) — the one
  screen a caregiver interacts with looks like 2014 and ignores dark mode.
- `tools:targetApi="35"` in the manifest is stale vs compileSdk 37.

**Fix (decided 2026-07-04):** collapse to one theme family in `themes.xml` —
`Theme.Material3.DayNight.NoActionBar` (ships with the already-present
`material` dependency) with a fullscreen child; delete `attrs.xml`,
`FullscreenActionBarStyle`, `black_overlay`, the `metaButtonBar*` items;
SettingsActivity gets the **DayNight** settings theme (follows system dark
mode — confirmed).

### 5. Hardcoded colors scattered in Java (low)

`#be0000`, `#009800` (`YesNo.java:50,62`), `#FFC107`, `#33B5E5`, `#493700`
(`BatteryStatus.java:38,41,44`), plus `#000000`/`#FFFFFF` in
`activity_fullscreen.xml`. Note `#33B5E5` duplicates `colorAccent`
(`colors.xml:5`). Move to `colors.xml` (`yes_green`, `no_red`, `battery_low`,
`battery_normal`, `background_lowbat`) and reference them — one source of truth,
and the yes/no flash colors become themable/tweakable in one place.

### 6. Layout nits (low)

`activity_fullscreen.xml`: `android:ems="10"` is meaningless with
`match_parent`; `android:textSize="100sp"` is always overridden by `FontSize`;
`android:text="@string/app_name"` doubles as the *magic sentinel* that
`FullscreenActivity.onResume` (`:98`) compares against to decide select-all
behavior — if the user ever types exactly "KiepKeyboard", their text gets
select-all'd on next resume. Replace the sentinel with an explicit boolean pref
(`"has_saved_text"`) and set the initial text in code.

### 7. String fixes (trivial)

- `strings.xml:45`: "Use the %s key show a downloaded .txt file" → "…key **to**
  show…".
- `no_data` string is unused in both locales — delete.

## Suggested order

1 (breakage risk, needs device test) → 2 → 4 → 5+6+7 in one cosmetic sweep → 3.

## Open questions

- ~~Has the app been run on a physical Android 15/16 device recently?~~
  **Answered: primary device is a Note 10+ (Android 12, final). Own device safe;
  migrate for Play users, test both Note 10+ and 15/16 emulator.**
- ~~Dark-mode settings screen?~~ **Answered: DayNight — follow system.**
