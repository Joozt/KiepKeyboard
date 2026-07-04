# Proposal: Editable text files

Status: Phase 1 implemented (2026-07-04)
Author: (Joost)
Date: 2026-05-31

> **Implementation notes (Phase 1, 2026-07-04).** Built as designed, with these
> resolutions from the implementation review:
>
> - **Autosave revised**: debounced-per-keystroke file writes caused Dropbox
>   "conflicted copy" files (every write is an upload; overlaps with remote
>   edits conflict). File writes now happen only on leaving the editor plus a
>   5-minute safety timer while dirty; a local SharedPreferences mirror
>   (2 s debounce, no sync traffic) covers crashes between writes.
> - **Conflict UX**: local edits win; the save proceeds with a snackbar notice.
>   The remote version stays recoverable via Dropbox version history.
> - **Navigation**: switching files is a caregiver action in Settings
>   ("Choose file"); the F2 hotkey opens the configured file directly and only
>   shows the picker when no file is configured yet.
> - **Encoding**: UTF-8; BOM stripped on read and never written back;
>   `\r\n`/`\n` detected at load and preserved on save.
> - **Editor features**: all five Notepad features, including abbreviations.
> - Terminology (Notepad / File / File editor) recorded in `CONTEXT.md`.

## Background

Today the app can pull a `.txt` file from a public URL and show it **read-only**
(`ReadTxtFileActivity`, triggered by a configurable hotkey, default `F2`). It fetches
the file over plain HTTP with OkHttp. There is no way to edit a file or save changes
back anywhere.

A user has asked to open text files from Dropbox and **edit** them, eventually several
files at once in tabs (like QuickEdit), with as few on-screen distractions as possible.
That user only cares about Dropbox. We want to keep the app provider-agnostic rather
than tie it to one cloud service.

This document records the design decisions that resolve that tension and the plan to
build the feature in phases.

## Goals

- Open an existing text file, edit it, and have changes saved back to where it came
  from (Dropbox in practice, but not only Dropbox).
- Keep the editing surface as plain and distraction-free as the rest of the app.
- Keep the app generic: no hard dependency on Dropbox.

## Non-goals (for now)

- Rich text or any formatting. Plain UTF-8 text only.
- Real-time / multi-device collaborative editing.
- A file browser inside the app beyond what the OS picker already provides.

## Decisions

| Question | Decision | Why |
| --- | --- | --- |
| How to read **and write** files | **Storage Access Framework (SAF)** now; a Dropbox-API backend can come later if needed | SAF is generic (Dropbox, Drive, OneDrive, local) with no SDK, OAuth, or API key. The user reaches "Dropbox only" by picking Dropbox once in the OS picker. |
| Scope split | **Phase 1: one editable file. Phase 2: multiple files in tabs.** | Tabs add real UI and state complexity; a single file delivers the core value first. |
| When to save | **Debounced autosave** (write a few seconds after the last keystroke), plus a flush on `onPause`/`onStop` | Gives an "always saved" feel with no save button to think about. The pause flush covers the case where the OS kills the app before the debounce timer fires. |
| Where editing lives | **A separate mode** (an editable sibling of the current viewer), entered by a hotkey, that **auto-reopens the last file**. The scratch-pad home screen is unchanged. | One-key access for the Dropbox user, no picker on every launch, while the app's default behavior stays the same for everyone else. |
| The existing read-only URL viewer | **Replaced by SAF.** | One way to open files. SAF opens read-only when a file is not writable, which covers the "view a shared document" case the URL viewer served. |

## How SAF works here, and its one weak spot

SAF hands the app a `content://` URI that it can persist (`takePersistableUriPermission`)
and read/write through `ContentResolver`:

- **Read:** `ContentResolver.openInputStream(uri)`.
- **Write:** `ContentResolver.openOutputStream(uri, "wt")` (truncate, then write the
  whole buffer). The Dropbox app then uploads on its own schedule — there is no
  upload-completion callback, so the app controls *when it hands over the bytes*, not
  when the cloud copy lands.
- **Remember the file:** store the persisted URI in `SharedPreferences` so the editor
  can reopen it on the next launch without a picker.

**The weak spot — detecting external edits.** SAF reads go through the Dropbox app's
local cache, and the keyboard app cannot force Dropbox to pull a remote change. So if
the same file is edited on another device, this app only notices after the Dropbox app
syncs it locally. There is no reliable push signal: `registerContentObserver` only fires
if the provider chooses to call `notifyChange`, which Dropbox does not guarantee.

Because we chose debounced autosave, this matters: a stale-then-save sequence could
overwrite a remote edit. Mitigations, in order of effort:

1. On open and on `onResume`, re-read `DocumentsContract.Document.COLUMN_LAST_MODIFIED`
   and `COLUMN_SIZE`; if they changed since load, reload the file.
2. Before each autosave, compare `last_modified` against the value captured at load
   time. If it moved, treat it as a conflict and warn rather than silently overwrite.

This is best-effort and bounded by Dropbox's own sync freshness. If reliable remote-edit
detection ever becomes a hard requirement, that is the trigger to add the Dropbox-API
backend (Phase 3 below), which can check server revisions directly.

## Prior art: QuickEdit

QuickEdit (Rhythm Software, `com.rhmsoft.edit`) is the editor the request was modelled
on, so it is worth recording how it handles Dropbox. It offers two ways in:

- **Its own cloud integration.** QuickEdit signs in to the service from inside the app —
  "Access files from FTP, Google Drive, Dropbox, and OneDrive." A changelog entry adds
  "support for Dropbox short-lived access token", which is the giveaway that it talks to
  the **Dropbox API over OAuth** and manages its own tokens. The Android file picker never
  exposes an access token to an app, so this cannot be SAF — it is the Dropbox-API path we
  parked as Phase 3.
- **The Android system file picker (SAF).** It also works through the OS picker, which is
  the generic path that needs no Dropbox-specific code.

The useful part for us: **even QuickEdit, with a full native Dropbox API integration, does
not edit the cloud file in place.** Its own description notes it "doesn't support editing
remote files directly." It opens a local copy and saves back, with sync left to the
provider — the same open-local / save-back model proposed here. That confirms the
external-edit / conflict limitation is inherent to mobile cloud editing, not a SAF-specific
weakness. A Dropbox-API backend buys *better conflict detection* (server revisions), not
live remote editing.

Concrete note for Phase 3: Dropbox now mandates **short-lived OAuth tokens** (refresh-token
flow); QuickEdit had to add this, and we would inherit the same requirement.

Confidence: this is from QuickEdit's store/marketing pages, an XDA review, and a changelog
summary — not its source. The short-lived-token line is strong evidence of an OAuth
integration, but the implementation itself was not inspected.

Sources: [XDA review](https://www.xda-developers.com/quickedit-text-editor/),
[QuickEdit Pro site](https://edit-pro.andro.io/),
[Google Play listing](https://play.google.com/store/apps/details?id=com.rhmsoft.edit).

## Phased plan

### Phase 1 — single editable file (this proposal)

- New editable mode, modelled on `ReadTxtFileActivity` but with an editable buffer and
  no `InputFilter` lock. Reuses the existing fullscreen layout, font-size hotkeys,
  battery bar, yes/no, and TTS.
- **Open an existing file only.** Open via the SAF picker (`ACTION_OPEN_DOCUMENT`, MIME
  `text/plain`), persist the URI, auto-reopen on the next entry into the mode. Creating
  a new file is deferred to Phase 2.
- Debounced autosave + pause flush. Conflict check on save (mitigation 2 above).
- Read-only fallback when the picked file is not writable.
- Remove `ReadTxtFileActivity` and its `DOWNLOAD_TXT_URL` / `DOWNLOAD_TXT_KEY` settings.
  Repurpose the freed `F2` default as the "open file" hotkey.

### Phase 2 — multiple files in tabs

- Track a list of open files (their persisted URIs) instead of one.
- **Create a new file** via SAF `ACTION_CREATE_DOCUMENT`, behind a deliberate action (its
  own hotkey or settings entry) so it never intrudes on the open-and-edit flow.
- Switch between them. Given the hardware-keyboard, grid-overlay setup, switching should
  be **key-driven** (e.g. cycle with a hotkey) rather than relying on small tap targets.
  A minimal tab strip can be shown, but tapping is a poor fit for the grid frame — to be
  decided in a Phase 2 design.
- Per-file autosave state.

### Phase 3 — optional Dropbox-API backend (only if needed)

- Add a Dropbox-API path behind the same editor for reliable revision checks and safe
  conflict handling. Kept optional so the app stays generic.

## What this replaces / removes

- `ReadTxtFileActivity` (the read-only URL viewer).
- The `DOWNLOAD_TXT_URL` and `DOWNLOAD_TXT_KEY` settings.
- The **OkHttp** dependency. Confirmed: `okhttp3` is imported only by the URL viewer,
  so removing that activity lets us drop the dependency.
- Existing users who set up the `F2` URL download will lose that setup. Accepted.

## Open questions

- **Conflict UX.** When mitigation 2 detects a conflict, what does the user see — a
  snackbar with "reload / overwrite", or just an automatic reload? The grid-overlay
  setup makes dialogs awkward; a snackbar with hardware-key actions may fit better.
- **Encoding / line endings.** Assume UTF-8 and preserve existing line endings.
  Confirm no BOM handling is needed.
- **Debounce interval.** Proposed 2–3 seconds after the last keystroke. Tune during
  testing.
