# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## What this is

KiepKeyboard is a fullscreen Android typing app for a **hardware USB keyboard**, used as a portable communication aid for disabled people. The phone is locked to one giant text field; physical keys (including punctuation and function keys) are repurposed as commands. The repo also holds the physical build (laser-cut grid frame DXF, 3D-printed charge dock STL) — see `README.md`. Code work lives in `AndroidApp/`.

## Build & run

All Gradle commands run from the `AndroidApp/` directory (that's where `gradlew` lives):

```bash
cd AndroidApp
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew bundleRelease      # release AAB (CI signs it afterwards; needs real google-services.json)
./gradlew test               # JVM unit tests (src/test)
./gradlew lint               # Android lint
```

- **Java 17** source/target (`compileOptions`), but **CI builds with JDK 21** (Temurin). Toolchain pins language version 17.
- `compileSdk`/`targetSdk` = 37, `minSdk` = 24. **Versioning is automatic in CI**: `versionCode` = git commit count (`-PversionCode`), `versionName` = `1.<count>`; local builds use the fallback in `app/build.gradle.kts`. No manual bump commits.
- Firebase/Analytics: the `google-services` plugin **is applied**. Debug builds use the committed **dummy** `app/src/debug/google-services.json` (analytics intentionally dead in debug); release builds get the real config from the `FIREBASE_CONFIG` secret in `publish.yml`. Local `bundleRelease` fails without a real `app/google-services.json` — that's expected; releases come from CI.
- Unit tests live in `src/test` (pure-JVM: sentence picker, abbreviation parsing/expansion, FunctionKey mapping). No instrumentation tests; the on-device smoke test before a Play release is the gate.
- CI: `android.yml` runs tests + lint, then builds a debug APK on every push/PR to `master`; `publish.yml` builds + signs a release AAB on push to `master` (manual upload to Play Console).

## Architecture

**Single source file per feature, Java, no DI framework.** Everything is wired up imperatively in `FullscreenActivity.onCreate()`.

The keyboard-as-command-bus is the core abstraction. `GlobalKeyPressListener` (constructed once per activity around the single `EditText`) is the dispatcher every feature registers against:

- **Character commands** (e.g. `[`, `]`, `\`): registered by the literal character. The listener watches `EditText` changes via a `TextWatcher` and reacts **only to single-character insertions**: the typed command character is deleted in place (cursor preserved) and the callback fired. Programmatic `setText`/`replace` (restore, abbreviation expansions, downloaded documents) are deliberately inert.
- **Function keys** (F1–F12, Backspace, Scroll Lock, Break): registered via the `FunctionKey` enum and handled through `setOnKeyListener` on key-down.
- `addListener(CharSequence, ...)` auto-detects whether the string names a function key (`FunctionKey.fromString`) and routes accordingly — so settings can map any command to either a punctuation char or a function key uniformly.

Feature classes each take `(Context, GlobalKeyPressListener, EditText)` and register their own listeners in their constructor:

- `YesNo` — plays yes/no `.wav` (from `res/raw`) and flashes the background green/red. Animation is done manually with a `Handler` loop (`animateBackgroundColorManually`) so it runs even when system animations are disabled.
- `TTS` — speaks the *sentence under the cursor* (`sentenceAt`; sentences end at `.`/`!`/`?`/newline, cursor at the end yields the last sentence); a second optional hotkey speaks the whole buffer.
- `Abbreviations` — expands `::abbr::expansion` snippets when an end-char is typed; Backspace undoes the last expansion (`UndoInfo`).
- `FontSize` — increment/decrement keys resize text; size persisted to prefs in `onPause`/`saveCurrentFontSize`.
- `BatteryStatus` — registers an `ACTION_BATTERY_CHANGED` receiver, drives the `ProgressBar`, tints/darkens on low battery.
- `FileEditorActivity` — separate activity launched by a configurable key (default F2); edits one SAF-picked text file (the **File**) with all Notepad features active. File writes are deliberately sparse (`onPause` + a 5-minute safety timer) to avoid Dropbox "conflicted copy" files; durability between writes comes from a local SharedPreferences mirror. Read-only files get an `InputFilter` that still lets the command keys through. See `docs/proposals/editable-files.md` and `CONTEXT.md` for terminology (Notepad / File / File editor).
- `ExternalDisplay` — sends typed text to paired BLE displays (never the internet, by design). Stateless frames: 'L' live buffer (character mode) or 'M' message (word/sentence mode, send hotkey — `TTS.sentenceAt` reused); one attention command. The send key may deliberately equal the TTS speak key — `GlobalKeyPressListener` fires **all** listeners registered on a key, in registration order. Receiver firmware for the Ulanzi TC001 lives in `UlanziReceiver/` (PlatformIO); the Ulanzi TC002 receiver (Linux/FlyThings, C++ in Docker, BlueZ GATT) lives in `UlanziTC002Receiver/` with its dev loop and laptop-driven verification in `UlanziTC002Receiver/docs/SETUP.md`; protocol in `docs/proposals/external-display.md`.
- `UpdateCheck` — Play in-app flexible updates.
- `Analytics` — thin Firebase wrapper (logs yes/no/tts events).

**Settings.** `SettingsActivity` declares **all preference keys and their defaults as `public static final` constants** — feature classes read prefs using these constants, so this class is the single registry of every setting. The preference screen is built **programmatically** in `SettingsFragment.onCreatePreferences` (no XML preference resource). Any command key is chosen from the shared `functionKeys` array; picking a key already owned by another command is rejected with a toast (`preventDuplicateKeys`). `SettingsActivity` **intentionally has its own launcher icon** (second `MAIN`/`LAUNCHER` entry in the manifest): it's the recovery path to settings when the in-app settings button is disabled — don't "fix" it.

**Reactivity.** `FullscreenActivity` implements `OnSharedPreferenceChangeListener`, sets a dirty flag on changes, and recreates **once in `onStart()`** when returning from Settings (`FONT_SIZE` is exempt — the hotkeys apply it live) — features re-read prefs and re-register from scratch rather than updating live. The typed text survives via `SharedPreferences` ("KiepKeyboard"/"Text") saved in `onPause`.

When adding a new keyboard command: add its key + default constant in `SettingsActivity`, add the preference UI in the relevant `add*Category` method, create/extend a feature class that registers via `keyPressListener.addListener(...)`, and instantiate it in `FullscreenActivity.onCreate` (and `FileEditorActivity` if it should work there too).
