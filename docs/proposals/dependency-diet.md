# Proposal: Dependency diet

Status: Draft for review
Author: Claude (full-app review)
Date: 2026-07-04

## Background

The app is 11 Java files using classic Views, yet the build ships a full
Kotlin + Jetpack Compose stack and a Firebase Analytics dependency that is
provably disabled. With `isMinifyEnabled = false` (`app/build.gradle.kts:25`),
**all of it ends up in the release AAB**. For a communication aid running on a
modest dedicated phone, that's multiple MB of dead weight, extra dex, slower
cold start, and an unearned `AD_ID` permission in the Play listing.

## Findings

### 1. Entire Compose/Kotlin stack is unused (high)

There are **zero `.kt` files** in the project. Nevertheless:

- `app/build.gradle.kts:3` applies the Compose compiler plugin.
- `app/build.gradle.kts:36-38` sets `buildFeatures { compose = true }`.
- Dependencies (lines 51-57, 66-69): compose BOM, `ui`, `ui-graphics`,
  `ui-tooling-preview`, `material3`, `activity-compose`, `ui-tooling`,
  `ui-test-junit4`, `ui-test-manifest`, plus `lifecycle-runtime-ktx` — none
  referenced by any source file.
- `kotlin` / `composeBom` / `activityCompose` / `lifecycleRuntimeKtx` versions in
  `gradle/libs.versions.toml`.
- The `kotlin { compilerOptions { ... } }` block (`app/build.gradle.kts:76-80`)
  configures a compiler that compiles nothing (`compileDebugKotlin NO-SOURCE`).

**Decision (Joost, 2026-07-04): remove all of it** — plugin, buildFeature, deps,
versions, kotlin block. The view-based UI is a good fit for this app (one EditText, one
FAB, one progress bar). If a future feature (e.g. editable-files Phase 2 tabs)
wants Compose, add it back *with* the first `.kt` file — adding is one commit.

Keep: `material` (FAB + Snackbar), `appcompat`, `preference` (see #3), `okhttp`
(until editable-files removes it), `app-update`.

`core-ktx` can also go: the only androidx.core usage is `ColorUtils`
(`YesNo.java:14`), which comes from `androidx.core:core`, already transitive via
appcompat.

### 2. Firebase Analytics is dead code in every build (high — decision needed)

`Analytics.java` calls `FirebaseAnalytics`, provided by
`play-services-measurement-api`. But the `com.google.gms.google-services` plugin
is applied **nowhere**, so `google-services.json` is never consumed and no
`google_app_id` resource is generated. Firebase Analytics logs
"Missing google_app_id, Firebase Analytics disabled" and drops every event — in
debug, in release, in the Play build. The yes/no/TTS counters have been going
nowhere.

**Decision (Joost, 2026-07-04): wire it properly.** The usage data is wanted.
Plan:

1. Apply the `com.google.gms.google-services` plugin (root + app), so
   `google-services.json` is consumed and `google_app_id` is generated.
2. Keep the existing `play-services-measurement-api` dependency (it already
   provides `FirebaseAnalytics`; no need to switch to the Firebase BOM for one
   API).
3. CI already writes `google-services.json` from the `FIREBASE_CONFIG` secret —
   that step stops being vestigial and starts being load-bearing.
4. Verify end-to-end with `adb shell setprop debug.firebase.analytics.app
   nl.joozt.kiep.keyboard` + Firebase DebugView before calling it done — the
   whole point is confirming events actually arrive this time.

**File handling (decided): secret + debug fallback.** A dummy
`google-services.json` is committed at `app/src/debug/google-services.json`
(plugin resolves per-buildtype first), the real one keeps coming from the CI
`FIREBASE_CONFIG` secret at `app/google-services.json` for release builds. Local
debug always builds; no real config in the public repo. Caveats to remember:

- Debug builds log against the dummy app id → analytics stays dead in debug by
  design; production data is what's wanted.
- The DebugView verification step therefore needs a sideloaded internal-test /
  release build (or temporarily dropping the real json locally).
- `android.yml`'s FIREBASE_CONFIG step becomes unnecessary (debug uses the
  committed dummy); only `publish.yml` needs the secret.

### 3. Duplicate preference dependency (low)

Both `androidx-preference` and `androidx-preference-ktx` (same version 1.2.1) are
declared (`app/build.gradle.kts:49,59`). The Java code needs only the non-ktx
one. Drop `preference-ktx`.

### 4. R8/minify disabled (medium)

`isMinifyEnabled = false`. After the diet above this matters less, but enabling
R8 + `shrinkResources` on release is still nearly free for an app this small and
without reflection tricks. The only candidates needing keep rules are Play
in-app-update and GMS, which ship consumer rules of their own. Test the release
build once, keep the mapping file from CI.

### 5. Misc build cleanup (low)

- `buildToolsVersion = "37.0.0"` (`app/build.gradle.kts:44`) — unnecessary since
  AGP picks its own; remove.
- `versionName = "Animation tweak"` (`app/build.gradle.kts:15`) — **superseded
  by the CI auto-versioning decision**: versionCode/versionName get stamped from
  git commit count in CI (see [testing-and-ci](testing-and-ci.md) Part D).
- The Gradle 9.6 deprecation warning ("Project object as a dependency notation")
  comes from **inside AGP**, not from this project's scripts (all three checked).
  Nothing to fix here; just don't move to Gradle 10 until AGP has.

## Expected result

Release artifact several MB smaller, fewer methods/dex, no AD_ID permission, a
`libs.versions.toml` where every line is actually used, and Dependabot/renovate
noise reduced by ~8 version streams.

## Open questions

- ~~Compose: leftovers from a template, or an intended migration path?~~
  **Answered: leftovers — drop everything (#1).**
- Analytics: was anyone ever reading those events? (Decides #2 a/b.)
- Is the Play listing's version display ("Animation tweak") intentional branding?
