# Proposal: Testing and CI improvements

Status: Draft for review
Author: Claude (full-app review)
Date: 2026-07-04

## Background

There are zero tests (`src/test` and `src/androidTest` don't even exist), while
JUnit, Espresso, and Compose-test dependencies are all wired up. Meanwhile the
app's riskiest code is exactly the kind that unit tests catch: string
manipulation with index math (`TTS.getTextToSpeak` has a live crash bug,
`Abbreviations` does substring surgery, `GlobalKeyPressListener` rewrites the
buffer). CI builds but never verifies anything.

This proposal deliberately stays small: pure-JVM JUnit tests for extracted logic.
No Espresso, no emulator jobs, no coverage gates.

## Part A — Make the logic testable, then test it

The three logic hotspots are trapped inside classes that require an `EditText`.
Extract the *pure string logic* (not the Android wiring) and test only that:

### A1. Sentence picker (also fixes the crash)

Extract `TTS.getTextToSpeak`'s body into a static
`String lastSentence(String buffer)` (package-private, same file is fine).
Tests: empty buffer, whitespace-only (both currently **crash** — see
[crash-and-correctness-fixes](crash-and-correctness-fixes.md) #1), single
sentence, trailing `.`/`!`/`?`, multiple sentences, no terminator.

### A2. Abbreviation parsing + expansion decision

Extract two statics from `Abbreviations`:
`Map<String,String> parse(String prefText)` and a
`Expansion findExpansion(String text)` (returns replace-range + replacement or
null). Tests: the default `::kkb::KiepKeyboard`, multiple lines, end-char set
(space/newline/`.,?!:;`), case-insensitivity, mid-text words, the `\w`-only
abbreviation-name limitation (document it: accented abbreviation names don't
match — decide if that's fine for Dutch users).

### A3. FunctionKey mapping

`FunctionKey.fromString` round-trip for every entry in
`SettingsActivity.functionKeys` — a one-test guarantee that every option the
settings UI offers actually resolves (would catch someone adding "Page Up" to
the array without an enum entry).

That's ~3 test files, `./gradlew test`, no new dependencies (JUnit 4 already
declared). Skip testing the Android-glue classes; instrumentation tests are not
worth the maintenance for a one-person project — the physical-device smoke test
before release covers those.

Also: drop the unused Espresso/Compose-test deps (overlaps
[dependency-diet](dependency-diet.md) #1).

## Part B — CI improvements

Current state: `android.yml` (debug APK on push/PR), `publish.yml` (signed AAB on
master push). Both work but:

| Finding | Fix |
| --- | --- |
| No verification step — a PR that breaks `lint` or (future) tests still goes green | Add `./gradlew lint test` to `android.yml`; upload lint report as artifact |
| No Gradle caching — every run re-downloads the world | `cache: gradle` on `setup-java`, or `gradle/actions/setup-gradle` |
| `fetch-depth: 0` on both workflows | Keep — becomes load-bearing for commit-count versioning (Part D) |
| No wrapper integrity check | `gradle/actions/wrapper-validation` step (supply-chain hygiene, one line) |
| `r0adkll/sign-android-release@v1` is unmaintained/archived | Replace with plain `apksigner`/`jarsigner` steps or a maintained fork; revisit when it first breaks — it still works today (`ponytail:` known ceiling) |
| `FIREBASE_CONFIG` steps write a file nothing reads | Remove once the analytics decision lands ([dependency-diet](dependency-diet.md) #2) |
| Play deploy step is commented out | **Decided: manual upload stays** — delete the dead commented block |

## Part D — Automated versioning (decided 2026-07-04)

Requirement (Joost): CI must stamp `versionCode`/`versionName` automatically so
the signed AAB from `publish.yml` is immediately uploadable to Play — no manual
bump-commit per release.

**Scheme: versionCode = git commit count on master.** Currently 82 (> the
shipped 58, so no offset needed), strictly increasing with every push, needs no
state, no tags, no API calls. `versionName = "1.<versionCode>"`.

- `app/build.gradle.kts`:

  ```kotlin
  val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
  defaultConfig {
      versionCode = ciVersionCode ?: 59          // local fallback
      versionName = ciVersionCode?.let { "1.$it" } ?: "1.59-dev"
      ...
  }
  ```

- `publish.yml` (and optionally `android.yml`):

  ```yaml
  - name: Compute version
    run: echo "VERSION_CODE=$(git rev-list --count HEAD)" >> $GITHUB_ENV
  - name: Build release AAB
    run: bash ./gradlew bundleRelease -PversionCode=$VERSION_CODE
  ```

- The existing `fetch-depth: 0` makes `rev-list --count` correct — keep it.
- This supersedes the "versionName = Animation tweak" nit in
  [dependency-diet](dependency-diet.md) #5: release descriptions move to Play
  Console release notes.
- `ponytail:` commit count shrinks if master is ever force-pushed/rebased below
  the last release's count — accepted; this repo doesn't rewrite master. If it
  ever bites, switch to `github.run_number` with an offset.
- Deploy stays manual (decided): CI produces the signed, versioned AAB artifact;
  upload to Play Console by hand. Delete the commented-out deploy block.

## Part C — Keep AGENTS.md honest

AGENTS.md is good and current. After this and the sibling proposals land, update:
the "no test sources exist" paragraph, the Firebase/vestigial-secret paragraph,
and the build commands (add `./gradlew test`).

## Open questions

- Any appetite for a pre-release emulator smoke test (boot, type, speak) in CI,
  or does the manual on-device test before each Play release stay the gate?
  (Recommend: manual stays; emulator jobs are flaky and slow for a project this
  size.)
- ~~The commented-out Play deploy: revive or delete?~~ **Answered: delete;
  upload stays manual.**
