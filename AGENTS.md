# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## What this is

KiepKeyboard is a fullscreen Android typing app for a **hardware USB keyboard**, used as a portable communication aid for disabled people. The phone is locked to one giant text field; physical keys (including punctuation and function keys) are repurposed as commands. The repo also holds the physical build (laser-cut grid frame DXF, 3D-printed charge dock STL) — see `README.md`. Code work lives in `AndroidApp/`.

## Build & run

All Gradle commands run from the `AndroidApp/` directory (that's where `gradlew` lives):

```bash
cd AndroidApp
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew bundleRelease      # release AAB (CI signs it afterwards)
./gradlew lint               # Android lint
```

- **Java 17** source/target (`compileOptions`), but **CI builds with JDK 21** (Temurin). Toolchain pins language version 17.
- `compileSdk`/`targetSdk` = 37, `minSdk` = 24. Bump `versionCode` in `app/build.gradle.kts` for every Play release.
- Firebase/Analytics: `Analytics.java` uses `FirebaseAnalytics`, but that class comes transitively from the `play-services-measurement-api` dependency — there is **no** `com.google.gms.google-services` Gradle plugin applied and no Firebase BOM/dependency. Because the plugin isn't applied, `app/google-services.json` is **not** consumed at build time, so local debug builds compile and run without it. CI still writes the file from the `FIREBASE_CONFIG` secret (vestigial), but you don't need it locally.
- No test sources exist yet (`src/test` and `src/androidTest` are empty), though JUnit/Espresso/Compose-test deps are wired up. There is no test command to run.
- CI: `android.yml` builds a debug APK on every push/PR to `master`; `publish.yml` builds + signs a release AAB on push to `master`.

## Architecture

**Single source file per feature, Java, no DI framework.** Everything is wired up imperatively in `FullscreenActivity.onCreate()`.

The keyboard-as-command-bus is the core abstraction. `GlobalKeyPressListener` (constructed once per activity around the single `EditText`) is the dispatcher every feature registers against:

- **Character commands** (e.g. `[`, `]`, `\`): registered by the literal character. The listener watches `EditText` text changes via a `TextWatcher`; when a registered sequence appears it fires the callback and *silently strips the character from the text* (see `containsCharSequence`) so the command key never shows up as typed text.
- **Function keys** (F1–F12, Backspace, Scroll Lock, Break): registered via the `FunctionKey` enum and handled through `setOnKeyListener` on key-down.
- `addListener(CharSequence, ...)` auto-detects whether the string names a function key (`FunctionKey.fromString`) and routes accordingly — so settings can map any command to either a punctuation char or a function key uniformly.

Feature classes each take `(Context, GlobalKeyPressListener, EditText)` and register their own listeners in their constructor:

- `YesNo` — plays yes/no `.wav` (from `res/raw`) and flashes the background green/red. Animation is done manually with a `Handler` loop (`animateBackgroundColorManually`) so it runs even when system animations are disabled.
- `TTS` — speaks the *last sentence* of the buffer (splits on `.`/`!`/`?`, picks shortest trailing segment).
- `Abbreviations` — expands `::abbr::expansion` snippets when an end-char is typed; Backspace undoes the last expansion (`UndoInfo`).
- `FontSize` — increment/decrement keys resize text; size persisted to prefs in `onPause`/`saveCurrentFontSize`.
- `BatteryStatus` — registers an `ACTION_BATTERY_CHANGED` receiver, drives the `ProgressBar`, tints/darkens on low battery.
- `EditTxtFileActivity` — separate activity launched by a configurable key; opens a `text/plain` file picked via the Storage Access Framework (persisted `content://` URI), edits it in the fullscreen buffer, and autosaves back (debounced + flush on pause). Read-only files fall back to an `InputFilter` that still lets the yes/no, TTS, and font keys through. Best-effort external-edit/conflict detection via `DocumentsContract` last-modified/size.
- `UpdateCheck` — Play in-app flexible updates.
- `Analytics` — thin Firebase wrapper (logs yes/no/tts events).

**Settings.** `SettingsActivity` declares **all preference keys and their defaults as `public static final` constants** — feature classes read prefs using these constants, so this class is the single registry of every setting. The preference screen is built **programmatically** in `SettingsFragment.onCreatePreferences` (no XML preference resource). Any command key is chosen from the shared `functionKeys` array.

**Reactivity.** `FullscreenActivity` implements `OnSharedPreferenceChangeListener` and calls `recreate()` on any settings change — features re-read prefs and re-register from scratch rather than updating live. The typed text survives via `SharedPreferences` ("KiepKeyboard"/"Text") saved in `onPause`.

When adding a new keyboard command: add its key + default constant in `SettingsActivity`, add the preference UI in the relevant `add*Category` method, create/extend a feature class that registers via `keyPressListener.addListener(...)`, and instantiate it in `FullscreenActivity.onCreate` (and `EditTxtFileActivity` if it should work there too).
