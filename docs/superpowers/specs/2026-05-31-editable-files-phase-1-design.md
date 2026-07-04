# Design: Editable text files — Phase 1 (single editable file)

Status: Approved (design)
Author: Joost
Date: 2026-05-31
Parent proposal: `docs/proposals/editable-files.md`

## Summary

Add an editable text-file mode to KiepKeyboard. The user picks a `text/plain`
file through the Android system file picker (SAF) from within KiepKeyboard's
Settings — Dropbox appears there as a location — edits it in a distraction-free
fullscreen buffer, and changes are autosaved back through the same SAF URI. A
configurable hotkey opens the configured file; if no file is configured yet, the
hotkey shows the picker.

This phase delivers **one** editable file. Tabs/multiple files (Phase 2) and a
Dropbox-API backend (Phase 3) are out of scope.

## Goals

- Open an existing `text/plain` file, edit it, and autosave back to its origin.
- Reach the file from within KiepKeyboard (Settings-driven pick), with the file
  remembered across launches.
- Keep the editing surface as plain as the rest of the app; keep the app generic
  (SAF, no Dropbox SDK).

## Non-goals (Phase 1)

- Creating new files (SAF `ACTION_CREATE_DOCUMENT`) — deferred to Phase 2.
- Multiple files / tabs — Phase 2.
- Reliable remote-revision conflict detection — Phase 3 (Dropbox API).
- Rich text, formatting, encodings other than UTF-8.

## Entry model (decided)

The integration is **KiepKeyboard-initiated**, not Dropbox-initiated. Rationale:
only SAF-picker URIs (`ACTION_OPEN_DOCUMENT`) can be persisted
(`takePersistableUriPermission`) and are reliably writable. URIs handed over by
the Dropbox app's "Open with"/Export are temporary, typically read-only cached
copies, and cannot be persisted — so a Dropbox-app-launched flow cannot satisfy
"save back" + "remember the file". The user therefore configures the file from
KiepKeyboard's Settings (the system picker shows Dropbox as a location).

## Components

### `EditTxtFileActivity` (new)

Modelled on `ReadTxtFileActivity`. Reuses `R.layout.activity_fullscreen`, hides
the FAB, and wires up the existing helpers: `BatteryStatus`, `FontSize`,
`YesNo`, `TTS`, and `Abbreviations`.

- **All special keys stay active** in edit mode. Consequence (accepted): the
  Yes/No keys (default `[` `]`), the TTS speak-char, and abbreviation triggers
  are consumed by their `TextWatcher` handlers and cannot appear in the file's
  text. Function-key features (font size F9/F10) are unaffected.
- The buffer is editable: no `InputFilter` lock, **unless** the file is
  read-only.
- Provides `static registerKeyPressListener(Context, GlobalKeyPressListener)`,
  called from `FullscreenActivity` in place of the old
  `ReadTxtFileActivity.registerKeyPressListener`. Registration is gated on the
  enable switch. The listener launches `EditTxtFileActivity` on the configured
  hotkey.
- Uses a single-thread `ExecutorService` for all `ContentResolver` IO, with UI
  updates marshalled via `runOnUiThread`. The executor is created in `onCreate`
  and shut down in `onDestroy`.

### Settings (in `SettingsActivity`)

A new `PreferenceCategory` ("Edit file"), built programmatically like the
existing categories, added in `onCreatePreferences`. Contents:

- `SwitchPreferenceCompat` — enable the feature. **Default: on.**
- `ListPreference` — the hotkey, using the shared `functionKeys` entries/values.
  **Default: `F2`.**
- A clickable `Preference` ("Open file…") — launches the SAF picker. Its summary
  shows the current filename, or a "none selected" string when empty.

The hotkey and "Open file…" preferences `setDependency(EDIT_TXT_ENABLE)`, mirroring
how `NO_KEY`/`YES_KEY` depend on `ENABLE_YES_NO`.

New constants (names provisional, follow existing style):

- `EDIT_TXT_ENABLE` / `EDIT_TXT_ENABLE_DEFAULT = true`
- `EDIT_TXT_KEY` / `EDIT_TXT_KEY_DEFAULT = "F2"`
- `EDIT_TXT_URI` (persisted `content://` string, no default)
- `EDIT_TXT_DISPLAY_NAME` (cached filename for the summary)

### `FullscreenActivity`

Replace the `ReadTxtFileActivity.registerKeyPressListener(this, keyPressListener)`
call with `EditTxtFileActivity.registerKeyPressListener(this, keyPressListener)`.

## File picking & persistence

- The "Open file…" preference launches `ACTION_OPEN_DOCUMENT` (MIME `text/plain`)
  with `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION`, via
  `registerForActivityResult` on `SettingsActivity`.
- On result: call `takePersistableUriPermission(uri, READ|WRITE)`. **This grant is
  the "attempt write" probe** — success ⇒ treat the file as writable; if the write
  grant is unavailable, keep read permission only and treat the file as read-only.
- Persist the URI string and the resolved `DISPLAY_NAME` (`OpenableColumns`) in
  default `SharedPreferences`.
- Changing the file = picking again; the new URI overwrites the stored one. (No
  need to release the old permission for Phase 1, but doing so is harmless.)

## Hotkey flow

On the configured hotkey (feature enabled): start `EditTxtFileActivity`.

- A URI is configured → open it.
- No URI configured → launch the picker from the activity; if the user cancels
  (no file selected), `finish()` and return to the scratchpad.

## Read / edit / write

- **Read:** `ContentResolver.openInputStream(uri)` on the executor; decode UTF-8;
  **strip a leading UTF-8 BOM** if present (do not re-add on write). `setText`,
  set selection to **end of file**, request focus. Show the progress bar while
  loading, as the old viewer does.
- **Edit:** editable buffer; new Enter keystrokes insert `\n`. Existing line
  endings are preserved where untouched (a CRLF file may end up with mixed
  endings — acceptable for plain text).
- **Autosave:** a `TextWatcher` marks the buffer dirty and posts a debounced
  `Handler` runnable at **2.5s**, removing the previous callback on each change.
  The runnable writes only when `dirty && writable && !loading && !conflictPending`.
  Write via `ContentResolver.openOutputStream(uri, "wt")` (truncate + write whole
  buffer) on the executor. On success: clear dirty, refresh the conflict baseline.
- **Flush on `onPause`/`onStop`:** remove the pending debounce callback, then
  perform a best-effort write so an OS kill after a recent edit doesn't lose work.
  Also call `fontSize.saveCurrentFontSize()` like the other activities.

## Conflict handling

The buffer is an open-local / save-back copy; the app cannot force Dropbox to
pull remote changes, so detection is best-effort (same limitation QuickEdit has).

- **Baseline:** at load, capture `DocumentsContract.Document.COLUMN_LAST_MODIFIED`
  and `COLUMN_SIZE`. After each successful own-write, refresh the baseline so our
  own write is not later mistaken for an external edit.
- **Checks:** on `onResume` and before each autosave, re-query the columns. If
  they differ from the baseline:
  - **Buffer clean** → silently reload the remote copy and update the baseline.
  - **Buffer dirty** → show an indefinite snackbar; **Yes key = reload (discard my
    edits)**, **No key = overwrite remote (keep mine)**. Set `conflictPending` to
    pause autosave until the user resolves it.
- **Provider gaps:** if `LAST_MODIFIED`/`SIZE` are unavailable, conflict detection
  is skipped and we save best-effort.
- **Overwrite risk is bounded:** choosing "keep mine" can overwrite a remote edit,
  but Dropbox retains file version history, so an overwritten version can be
  restored — data is not permanently lost. This is the accepted Phase 1 limit;
  Phase 3 (Dropbox API, server revisions) is the real fix.

## Read-only fallback & feedback

- Not writable → apply the old viewer's `InputFilter` allowlist (pass only Yes/No,
  font-size, TTS chars, and newline; block other input) and show a **persistent
  read-only snackbar**.
- On open, show a brief snackbar with the filename.
- **Write backstop:** if a write fails despite holding write permission, flip to
  read-only and warn (the "catch" half of attempt-write).

## Removals (per proposal)

- Delete `ReadTxtFileActivity.java` and its `<activity>` entry in the manifest.
- Remove `DOWNLOAD_TXT_URL` / `DOWNLOAD_TXT_KEY` constants, the `download_txt`
  preference category, and their string resources.
- Drop the **OkHttp** dependency (`implementation(libs.okhttp)`); it is used only
  by the viewer. Leaving the unused version-catalog alias is harmless; remove it
  if convenient.
- Keep `INTERNET` / `ACCESS_NETWORK_STATE` permissions — still used by update-check
  and analytics.
- Existing users who configured the F2 URL download lose that setup. Accepted.

## Manifest

- Add `<activity android:name=".EditTxtFileActivity">` mirroring the old
  `ReadTxtFileActivity` entry (`singleTask`, `FullscreenTheme`, same
  `configChanges`). No new permissions are required for SAF.

## Testing

Manual, on-device only (decided). No new automated tests in Phase 1. Pure logic
(BOM stripping, the conflict baseline comparison) is kept in small, focused
methods so it can be verified by inspection. Manual test checklist to cover:
pick a writable Dropbox file; edit + observe autosave; kill app and reopen
(persistence + reopen); pick a read-only file (lock + note); external edit while
clean (auto-reload) and while dirty (snackbar Yes/No); cancel picker on empty
config; toggle the enable switch; change the hotkey.

## Open items deferred (not Phase 1)

- Creating new files, tabs, key-driven file switching (Phase 2).
- Dropbox-API backend for server-revision conflict checks (Phase 3).
- Persisting cursor/scroll position across reopens.