# Proposal: Repo hygiene

Status: Draft for review
Author: Claude (full-app review)
Date: 2026-07-04

## Findings

### 1. Dropbox is fighting the toolchain (the real one)

The working copy lives in Dropbox, and it shows:

- Today's build failed with `Unable to delete directory ...\intermediates\javac\...`
  — Dropbox holding file handles on `build/` output. Recoverable (stop daemon,
  delete dir) but it will recur.
- `KiepTest/` contains `KiepTest (Joost van den Ouweland's conflicted copy).txt`
  ×2 plus `desktop.ini` — Dropbox conflict artifacts sitting in the repo tree.

The repo already has a proper remote (GitHub, CI on push).

**Decision (Joost, 2026-07-04): stay in Dropbox, fence the build dirs.**

- Mark as Dropbox-ignored (NTFS stream, survives re-creation of the folder by
  marking the parent dirs once they exist):

  ```powershell
  Set-Content -Path AndroidApp\app\build -Stream com.dropbox.ignored -Value 1
  Set-Content -Path AndroidApp\build     -Stream com.dropbox.ignored -Value 1
  Set-Content -Path AndroidApp\.gradle   -Stream com.dropbox.ignored -Value 1
  ```

  Note: the stream lives on the folder — after a `clean` that deletes the
  folder, re-mark it (or keep the folders and only clean contents).
- Gitignore `desktop.ini` and `* (conflicted copy*` patterns.

**Decision (Joost, 2026-07-04):** `KiepTest/` is leftover test junk, not the
live download target — delete the whole folder.

### 2. Stray launcher XMLs at repo root (trivial)

`ic_launcher_background.xml` and `ic_launcher_foreground.xml` are **tracked** at
the repo root — duplicates of the real resources under
`AndroidApp/app/src/main/res/`. Almost certainly a misplaced paste. Delete.

### 3. README nits (trivial)

- Badge label says "Build release APK"; `publish.yml` builds an **AAB**.
- Otherwise the README is genuinely good (rare) — the hardware section and
  key-mapping table are exactly what this project needs.

### 4. `.idea` tracking (fine as-is)

Only four `.idea` files are tracked (`.gitignore`, `.name`, `compiler.xml`,
`misc.xml`) — the noisy ones (`workspace.xml`, `caches/`, `deviceStreaming.xml`)
are already ignored. No action needed; noted so nobody "fixes" it.

## Open questions

None — all decided 2026-07-04: stay in Dropbox with fenced build dirs; delete
`KiepTest/` entirely; delete stray root launcher XMLs; fix README badge label.
