# Batch 1 — sandbox test report

What was built, what was run, and what each result does and does not prove.

**This is not a device test.** `adb devices` is empty and the host reports
`Virtualization Enabled In Firmware: No`, so no head unit and no API 23 emulator
were available. §1 forbids reporting `DEVICE_PASS`, and this document does not.
Every "PASS" below means *this command exited 0 on this machine*.

---

## 1. Build

| item | value |
|---|---|
| command | `gradlew.bat :app:assembleDebug --offline` (PowerShell, JDK 17 pinned by `org.gradle.java.home`) |
| result | **BUILD SUCCESSFUL** (exit 0) |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| size | **58,686,466 bytes** |
| SHA-256 | **`b0a26b623c22ad5db8226f0edca9f64b8e731f61275cf020e5d005cd04a97561`** |
| baseline APK (pre-batch) | 58,599,327 bytes / `5400fda4e17edfeb424571bd8397af06328e394f23bf7806fb24ca802b4cc164` |

**Reproducibility, stated plainly.** `app/build/outputs` was deleted and
`assembleDebug` run twice. Both runs produced **58,686,466 bytes** and the same
SHA-256 above — 122 bytes more than the earlier draft of this report, which is
the Parking music card and its test-only strings. So the number above is the
clean-build number, not an incremental artefact.

⚠️ **An incremental build after a source edit does not reproduce this hash.**
While this document was being written, a stale incrementally built APK hashed
`0077a8df…` at 59,813,007 bytes — 1.1 MB larger than a clean build, with no
source difference. `KNOWN_LIMITATIONS.md` §F records the same phenomenon for the
previous round. Compare clean builds only.

| build input | value |
|---|---|
| `applicationId` / `namespace` | `com.ts8.launcher` |
| `compileSdk` / `targetSdk` | 34 / 30 |
| `minSdk` | **23** (Android 6.0) |
| `versionName` / `versionCode` | 1.0.0 / 1 |
| `buildFeatures` | `viewBinding` only — **no `BuildConfig`** |
| core library desugaring | `desugar_jdk_libs:2.0.4` |
| AGP / Kotlin / Gradle | 8.7.3 / 1.9.25 / 8.11.1 |
| JDK | 17 (`D:/Tools/_toolchain/jdk-17.0.20.1+1`) |
| modules | `:app` + 11 under `:core:` (all `com.android.library`) |

**Why `--offline`.** Every dependency was already in the Gradle cache. A
network build was not attempted and is not claimed.

**Merged manifest check.** The APK's merged debug manifest
(`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`)
was read back and contains `HomeActivity`, `MusicFullscreenActivity`,
`MapActivity`, `SearchActivity`, `AppDrawerActivity`, `QuickSettingsActivity`,
`SettingsActivity`, `DiagnosticsActivity`, `MapManagementActivity`,
`ParkingModeActivity` and `ScreenOffActivity`. `MapManagementActivity` in
particular was absent before this batch (RC-2).

---

## 2. Tests

| item | value |
|---|---|
| command | `gradlew.bat test --offline --max-workers=1` |
| result | **BUILD SUCCESSFUL** (exit 0) |
| suites | **30** |
| tests | **376** |
| failures / errors / skipped | **0 / 0 / 0** |

**Why `--max-workers=1`.** A parallel `test` run on this host died in
`core:routing-engine-offline` with
`insufficient memory for the Java Runtime Environment … paging file is too
small`. That is a host resource limit, not a test result: no test assertion ran
and failed. Every module was then re-run single-threaded and passed. The
parallel failure is recorded here rather than omitted, because a green number
produced by a different command is a different claim.

### Per module

| module | suites | tests |
|---|---:|---:|
| `:app` | 10 | **79** |
| `:core:location-engine` | 4 | 77 |
| `:core:music-engine-local` | 7 | 97 |
| `:core:music-provider-online` | 2 | 30 |
| `:core:navigation-core` | 3 | 47 |
| `:core:map-api` | 1 | 11 |
| `:core:map-engine-maplibre` | 1 | 10 |
| `:core:routing-engine-offline` | 1 | 12 |
| `:core:vehicle-audio-bridge` | 1 | **13** |
| **total** | **30** | **376** |

### The §12 regression tests

All seven required by §12, plus two this batch added — one for the phase machine
and one for Parking's conditional music card. Every one is pure JVM — Robolectric
cannot be used here because its `android-all` jar is not in the offline cache
(`:app` sets `isReturnDefaultValues = true`).

| §12 | requirement | file | n | result |
|---|---|---|---:|---|
| (1) | MAP_BROWSE has no music card | `MapCompositionTest` | 8 | PASS |
| (2) | NAVIGATING controls not obscured | `MapCompositionTest` | ↑ | PASS |
| (3) | Music search does not initial-focus | `MusicFocusContractTest` | 4 | PASS |
| (4) | Map Management resolves from the manifest | `ManifestResolutionTest` | 5 | PASS |
| (5) | Manual Parking switches mode and can exit | `LauncherModeParkingTest` | 7 | PASS |
| (6) | Status view not duplicated | `StatusBarAbsenceTest` | 5 | PASS |
| (7) | Online source entry always discoverable | `OnlineSourceDiscoverabilityTest` | 7 | PASS |
| — | all six map phases reachable (RC-10) | `MapPhaseFrontTest` | 8 | PASS |
| — | Parking's music card is conditional and passive (§9) | `ParkingMusicContractTest` | 3 | PASS |

Two of those are worth naming because of *how* they are asserted:

- `MapCompositionTest` proves §5 twice — behaviourally by enumerating every
  `NavigationState`, and structurally by recomputing `236 + 12 = 248` from
  `dimens.xml` and comparing it to the margin on `guidanceControls`. Widening
  the music card past the reserve fails the test with the arithmetic in the
  message.
- `MapPhaseFrontTest` is split the same way: five behavioural tests drive
  `MapPhaseStore` through all six phases, and three structural tests read
  `MapHost.kt` / the two Activity sources to prove the `MapView` listeners are
  registered and that neither screen drives the phase store any more. There is
  no way to build a `MapView` on this host, so the structural half is labelled
  as structural rather than dressed up as a runtime result.
- `ParkingMusicContractTest` exists because §9's "music compact" and
  `UI_design.png` state 7 disagree: the design draws no player at all. The test
  pins the reconciliation — a compact card in the layout, `gone` by default,
  revealed only by `hasTrack`, and no `play(` / `startActivity` / `startService`
  anywhere in the Activity. It cannot show that the card is placed well on a
  1024×600 panel; step 7 of the checklist does.

---

## 3. What the tests prove, and what they do not

**Proven here.** The source compiles against API 23 with the real MapLibre AAR;
the manifest declares every screen the batch added or repaired; the four §5
composition rules hold for every `NavigationState`; the §8 threshold behaves as
the decision specified it, for every case including the three exclusions; the
seven files the §12 tests name read the files that are compiled, not copies.

**Not proven here — and required before `DEVICE_PASS`.**

- that the map renders on the C2's GL driver at all;
- that `onDidFinishLoadingMap` actually fires on that firmware, or that
  `WAITING_FIRST_FRAME` instead times out into `MAP_FIRST_FRAME_TIMEOUT`;
- that the vendor status bar is where `vendor_bar_inset` assumes it is, so 32 dp
  is the right inset and not merely a plausible one;
- that a gradient reads as depth on a 1024×600 panel in daylight;
- that the TS8's audio stream accepts or ignores volume writes (the tracker
  reports which, the device decides);
- that the IME stays down on Music until Search is tapped;
- that Manual Parking visibly switches the panel and Back leaves it;
- that the Parking music card sits clear of the exit tap and only appears with a
  track already playing;
- that the diagnostics export lands in a directory the driver can find with a
  file manager.

`BATCH1_DEVICE_TEST_CHECKLIST.md` asks each of those as one concrete step.

---

## 4. Honest limits of this report

- **No screenshot exists.** §12 forbids a layout preview as evidence, and §1
  forbids `DEVICE_PASS`. There is no captured frame anywhere in this document.
- **No UI automation.** All 376 tests are JVM-level. None inflates a layout,
  measures a view, or synthesises a touch.
- **No soak.** No two-hour navigation-plus-music run, for the same reason as
  `KNOWN_LIMITATIONS.md` §A2.
- **No lint gate.** Lint warnings are unchanged in kind from the previous round
  (`KNOWN_LIMITATIONS.md` §F); errors and fatals are zero.

---

## 5. Changed files

Measured against `docs/batch1/BASELINE_SHA256.tsv` (335 tracked files at the
start of the batch), not from memory.

| | count |
|---|---:|
| modified | **41** |
| deleted | **0** |
| unchanged | 294 |
| added | **30** |

### Modified (41)

Manifest and composition root: `AndroidManifest.xml`, `di/AppGraph.kt`.

Runtime: `runtime/MapHost.kt`, `runtime/OnlineMusicRuntime.kt`,
`runtime/VoiceCommandExecutor.kt`.

Screens: `ui/BaseScreenActivity.kt`, `ui/BottomNavView.kt`,
`ui/HomeActivity.kt`, `ui/MapActivity.kt`, `ui/MapManagementActivity.kt`,
`ui/MusicFullscreenActivity.kt`, `ui/ParkingModeActivity.kt`,
`ui/QuickSettingsActivity.kt`, `ui/SettingsActivity.kt`.

Drawables: `bg_button_primary`, `bg_card`, `bg_card_raised`, `bg_chip`,
`bg_eta_strip`, `bg_list_item`, `bg_speed_card`.

Layouts: `activity_app_drawer`, `activity_home`, `activity_map`,
`activity_map_management`, `activity_music_fullscreen`,
`activity_parking_mode`, `activity_quick_settings`, `activity_search`,
`activity_settings`, `view_bottom_nav`.

Values: `colors.xml`, `dimens.xml`, `strings.xml`.

Core: `music-api/…/MusicApi.kt`, `music-engine-local/…/LocalMusicPlayer.kt`,
`music-engine-local/…/MusicDatabase.kt`, `navigation-core/…/VoiceGuidance.kt`,
`vehicle-audio-bridge/…/AndroidVehicleAudioBridge.kt`,
`vehicle-audio-bridge/…/VehicleAudioBridge.kt`.

Docs: `KNOWN_LIMITATIONS.md`.

### Added (30)

Runtime and UI: `runtime/BuildFingerprint.kt`, `runtime/DiagnosticsReport.kt`,
`runtime/LauncherModeStore.kt`, `runtime/MapPhaseStore.kt`,
`ui/CompactTopBar.kt`, `ui/DiagnosticsActivity.kt`, `ui/MapChrome.kt`,
`ui/MapPhaseSurface.kt`.

Resources: `drawable/bg_bottom_nav.xml`, `drawable/bg_screen.xml`,
`drawable/bg_top_overlay.xml`, `layout/activity_diagnostics.xml`,
`layout/view_compact_top_bar.xml`, `layout/view_map_phase.xml`.

Tests (9 files): `runtime/LauncherModeParkingTest`,
`runtime/MapPhaseFrontTest`, `runtime/OnlineSourceDiscoverabilityTest`,
`ui/ManifestResolutionTest`, `ui/MapCompositionTest`,
`ui/MusicFocusContractTest`, `ui/ParkingMusicContractTest`, `ui/RepoFiles`
(shared helper), `ui/StatusBarAbsenceTest`.

Core: `vehicle-audio-bridge/…/VolumeCapabilityTracker.kt` and its 13-test suite
`VolumeCapabilityTrackerTest.kt`.

Docs (5): `BATCH1_ROOT_CAUSE_AND_CHANGES.md`, `BATCH1_UI_GAP_CLOSURE.md`,
`BATCH1_RUNTIME_WIRING.md`, `BATCH1_SANDBOX_TEST_REPORT.md`,
`BATCH1_DEVICE_TEST_CHECKLIST.md`.
