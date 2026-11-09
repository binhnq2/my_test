# Batch 1 — root cause and changes

Every defect this batch repairs, why it existed, and what changed. Written so
that a reader who did not do the work can check each claim against the source.

Scope discipline (§2): this batch touches the **UI shell and its production
wiring only**. The MapLibre/MBTiles renderer, the routing graph, the backend
STT, the AI voice engine, the firmware, the MCU and Auto Parking were not
modified. Where a screen could not be made to work without touching one of
those, the screen says what is missing instead of pretending.

---

## RC-1. Map Browse drew a floating music card (partially repaired before this batch, structural gap remained)

**Symptom.** §5 says Map Browse is a full-width map with *absolutely no* floating
music card. The card appeared over the browse screen.

**Root cause, two layers.**

1. *Visibility.* `MapActivity.render` decided the card's visibility with
   `if (guiding && ui.state != ARRIVED)` — but the condition that preceded this
   batch included `IDLE`, so the browse state drew the card. That half was
   already corrected.
2. *Layout.* The card was a **sibling of `mapHost` pinned to the parent's end**,
   while `mapHost` was **also pinned to the parent's end** (`End_toEndOf="parent"`).
   So whenever the card was visible it floated over the map's right 248 dp —
   which is exactly the band `guidanceControls` occupies
   (`layout_gravity="center_vertical|end"`). During `NAVIGATING` the mute,
   overview and end-route buttons sat underneath the card. §5's "maneuver /
   overview / mute / end route luôn hiển thị và bấm được" was false, and the
   layout's own comment claimed otherwise.

**Why the comment did not prevent it.** Nothing checked the geometry. A
comment is not a constraint.

**Change.**

- New [app/src/main/java/com/ts8/launcher/ui/MapChrome.kt](app/src/main/java/com/ts8/launcher/ui/MapChrome.kt):
  `showsGuidanceChrome`, `showsMusicCard`, `isBrowse` as pure functions over
  `NavigationState`. `MapActivity.render` now reads them instead of repeating
  the conditions inline.
- `activity_map.xml`: `guidanceControls` gains
  `android:layout_marginEnd="@dimen/map_controls_reserve"`.
- `dimens.xml`: new token `map_controls_reserve` = 248 dp =
  `music_card_width_compact` (236) + `gap` (12), i.e. the card plus its margin.
  The control column's right edge now lands exactly where the card's left edge
  begins.

**Why the map itself stays full-bleed.** Shrinking `mapHost` when guidance
starts would make the renderer re-lay-out and re-centre the camera at the moment
the driver begins steering by it. §5's rule is about the *controls* remaining
visible and tappable, so the card remains an overlay and the one column it
covered is inset out from under it.

**Proof.** `MapCompositionTest` — behavioural (every `NavigationState`
enumerated) and structural (the margin attribute, the card's width attribute,
the controls being children of the column, and the arithmetic
`236 + 12 = 248` recomputed from `dimens.xml`). A future edit that widens the
card past the reserve fails with the arithmetic in the message.

---

## RC-2. `MapManagementActivity` was not declared — every §6 entry point crashed

**Symptom.** Four entry points were wired to a screen that could not open.

**Root cause.** The Activity, its layout and its logic were complete, but no
`<activity>` element existed in `AndroidManifest.xml`. `startActivity` on an
undeclared component throws `ActivityNotFoundException`, which propagates out of
the click listener and takes the calling screen down. The two callers that
existed were the map error surface (`MapPhaseSurface`) and Settings > Bản đồ.
Nothing in the test suite parsed the manifest, so nothing noticed.

**Change.**

- `AndroidManifest.xml`: declared `.ui.MapManagementActivity`,
  `exported="false"`, `landscape`, `launchMode="singleTop"`, with a comment
  stating why.
- `MapManagementActivity.start(context): Boolean` — launches through
  `REORDER_TO_FRONT or SINGLE_TOP` and returns `false` instead of throwing, so a
  future rename degrades to a message rather than a crash.
- Settings continues to call `MapManagementActivity.start(this)`.
- **New:** Quick Settings gains a `Quản lý bản đồ` action (`btnMapManagement`,
  §6's fourth entry point), through the same helper, with a toast when it cannot
  open.

**Proof.** `ManifestResolutionTest` (declaration, orientation, launch mode, no
unexpected `exported="true"`, and the two source call sites) plus the merged
manifest of the built APK, verified by grepping
`app/build/intermediates/merged_manifest/debug/.../AndroidManifest.xml`.

**Not claimed.** §6 is explicit: this batch does **not** claim to have fixed map
*rendering*. Only the shell, the error surface and the entry wiring.

---

## RC-3. The launcher drew a second copy of the vendor status bar

**Symptom.** §4: the TS8 vendor bar already shows clock, date, battery, Wi-Fi,
Bluetooth and mobile data. The launcher drew all six again, on every normal
screen.

**Root cause.** `StatusBarView` was the launcher's own bar and was inflated by
Home. It duplicated a bar the driver was already looking at, and on a 600 dp
panel it cost vertical space every other element needed.

**Change.**

- `CompactTopBar` replaces it: weather chip (left), Voice Instant microphone,
  Quick Settings (right). `compactOnly` strips the weather chip for the map,
  where it would compete with the maneuver card for the same corner.
- `HomeActivity` and `activity_home.xml` use it. `activity_map.xml` uses the
  `compactOnly` variant.
- `StatusBarView.kt` remains in the tree — Parking Mode's large clock is the one
  place §4 allows a real time display, and the class still carries the
  connectivity plumbing — but **no layout inflates it**.
- Every other screen reserves the vendor bar with `@dimen/vendor_bar_inset`
  (32 dp) instead of drawing over it.

**Weather honesty (§4).** `CompactTopBar.setWeather` takes
`LOADING / READY / UNAVAILABLE`. There is no provider in this build, so the chip
reports `UNAVAILABLE`; there is no default degree to show. `READY` requires a
non-blank string, so a future provider cannot half-wire it.

**Proof.** `StatusBarAbsenceTest` — scans every layout in `res/layout` for a
`StatusBarView` element tag, asserts only `activity_home` and `activity_map`
carry a `CompactTopBar`, asserts Home's `statusBar` id resolves to the compact
class, asserts the map declares no weather chip of its own, and asserts the seven
non-map screens inset by `vendor_bar_inset`.

---

## RC-4. Volume controls reported success that the device did not honour

**Symptom.** §8: dragging the Quick Settings volume slider, or speaking "tăng âm
lượng", changed the number on screen whether or not the audio stream moved.

**Root cause, two paths, same lie.**

1. *Quick Settings.* The `SeekBar` listener wrote the finger's value straight
   into the label: `volumeValue.text = progress.toString()`. No read-back, so a
   stream that ignores `setStreamVolume` still showed the requested level.
2. *Voice.* `VoiceCommandExecutor.nudgeVolume` returned
   `CommandOutcome.Done("Âm lượng N%")` whenever the write's `AudioResult` was
   not an explicit failure. A vendor stream that accepts the write and does not
   move counted as success, so the driver heard a level nobody could hear.

**Change.**

- New `core:vehicle-audio-bridge` `VolumeCapabilityTracker` with
  `Capability { UNKNOWN, SUPPORTED, NO_EFFECT, UNAVAILABLE }`. `record()`
  requires **at least two consecutive valid write/read-back pairs that did not
  change** before declaring `NO_EFFECT`, and ignores the "already at max when
  pressing up" / "already at min when pressing down" cases, per the decision the
  driver made.
- `QuickSettingsActivity`: every write is read-before → `setMediaVolume` →
  read-after → `record()` → **re-bind the bar to what was actually read**. An
  inert stream snaps the slider back and the label reads
  `Không có tác dụng · N/M`; a fixed stream (`isVolumeFixed`) disables the slider
  and prints `Không hỗ trợ trên thiết bị này`.
- `VoiceCommandExecutor`: `nudgeVolume` and `setVolumePercent` both route through
  one `applyVolume()` that speaks the **observed** level, or a failure sentence —
  never a requested one.
- The `AudioManager` bridge is kept. `VehicleAudioBridge` remains the boundary an
  MCU adapter would implement later (§8 forbids reversing SYU/MCU in this
  batch).

**Proof.** `VolumeCapabilityTrackerTest` in `core:vehicle-audio-bridge` — 13
tests covering the threshold (one unchanged write is not evidence, two are),
the three exclusions the decision names (pressing up at max, pressing down at
min, a rejected write), a move clearing accumulated doubt, a transient failure
leaving existing evidence intact, and a route change demoting `NO_EFFECT` back
to `UNKNOWN`. Both call paths record into the same tracker instance, so there is
one place that can decide.

---

## RC-5. Parking Mode never claimed its mode

**Symptom.** §10: Bottom navigation switches mode without recreating the map or
music engines, and Back returns to a sensible mode. From Parking, "thoát chế độ
đỗ xe" and Back consulted a store that still said the *previous* screen.

**Root cause.** `ParkingModeActivity` inherits `BottomNavView.Screen.NONE`, which
`BaseScreenActivity` maps to a `null` mode. The shared `LauncherModeStore` was
therefore never told the driver had entered Parking, so `backTarget()` answered
for whatever screen was there before.

**Change.**

- `ParkingModeActivity` overrides
  `launcherMode = LauncherModeStore.Mode.PARKING`.
- The Quick Settings parking action launches through
  `REORDER_TO_FRONT or SINGLE_TOP`, so a second tap returns to the existing
  instance instead of stacking another.

**Proof.** `LauncherModeParkingTest` — enters Parking from Quick Settings,
asserts `effectiveMode()` is `PARKING` even while a route is running, asserts
`backTarget()` is `HOME`, drives Back and asserts the destination, asserts
`navActive` survives parking (the map and music engines are not recreated), and
asserts a restored `PARKING` survives Home's `onResume` reasserting `HOME`.

---

## RC-6. Music had no source selector and the online entry was not discoverable

**Symptom.** §7 requires `Trên thiết bị | USB | Trực tuyến`, and requires every
online provider — configured or not — to stay visible with a `Thiết lập` CTA.

**Root cause.** Two separate things:

1. Online music was reachable only by typing into the search field. There was no
   browse/discovery path, and the shipped `music_source_*` strings were
   unreferenced.
2. The provider list was the *result* of a search, so a provider with no
   configuration produced no rows at all — the silent disappearance §7 forbids.

**Change.**

- `activity_music_fullscreen.xml` gains the three-pill source row
  (`sourceDevice` / `sourceUsb` / `sourceOnline`); `wireSources()` drives it.
- `MusicFullscreenActivity.renderProviders()` draws one card per provider from
  `OnlineMusicRuntime.providers()`, unconditionally, from settings alone — no
  network call, so it is always populated. An unconfigured provider shows
  `Chưa cấu hình — thiếu <setting>` and a `Thiết lập` chip that opens the setup
  section.
- `OnlineMusicRuntime.buildProviders(feedConfigured, serverConfigured, jellyfin)`
  extracted to the companion so the list is testable without a `Context`.
- `providerLabel` fixed to be Jellyfin-aware and no longer hard-codes
  `"subsonic" → "Navidrome"`.

**Proof.** `OnlineSourceDiscoverabilityTest` — all three providers present with
nothing configured; radio needs no configuration; an unconfigured provider names
the setting it lacks; configuring everything leaves all three present; the order
is Radio / Podcast / Subsonic; the Subsonic row follows the driver's server kind;
the `Trực tuyến` and `Thiết lập` strings exist.

**Not claimed.** Two of the three providers have no endpoint on this build. They
report `BLOCKED_EXTERNAL_CONFIG` and offer setup — the standing campaign rule is
to implement the provider, the settings and the test double, then report
`BLOCKED_EXTERNAL_CONFIG` rather than pretend online production playback works.

---

## RC-7. Music opened with the search field focused

**Symptom.** §7: the root container takes initial focus, the search field does
not, and the keyboard is hidden until the driver taps Search.

**Root cause.** The search field is the only `focusableInTouchMode` view in the
layout, so the platform's initial-focus pass gives it focus and draws the caret;
any later window change can bring the IME up behind it.

**Change.**

- `activity_music_fullscreen.xml`: root `musicRoot` is `focusable` **and**
  `focusableInTouchMode`; the search field carries neither.
- Manifest: `windowSoftInputMode="stateAlwaysHidden|adjustPan"` on the music
  activity only.
- `MusicFullscreenActivity.holdInitialFocus()` takes focus on the root, clears it
  from the field and dismisses the IME; `restoreFocusUnlessSearching()` only
  re-takes it when the driver was not already searching.

**Proof.** `MusicFocusContractTest` — root focusable in both modes, field carries
no focus attributes, the manifest declares `stateAlwaysHidden` and not
`stateAlwaysVisible`, and exactly one activity in the whole manifest may raise
the IME on open (Search, which the driver taps).

---

## RC-8. There was no diagnostics surface

**Symptom.** §11 asks for a screen showing version, build hash, mode, active
pack, map phase, music service/provider, voice availability, parking state and
volume capability, plus an export to a user-accessible file.

**Root cause.** No such screen existed.

**Change.**

- `DiagnosticsReport` — a typed data class with **no field that could hold a
  credential**. Provider lines report *configuration state*, never the value.
  `render()` produces the exported text.
- `BuildFingerprint` — SHA-256 of the installed APK (`applicationInfo.sourceDir`),
  truncated to 12 hex characters, cached, computed on the IO pool. `:app` does
  not generate `BuildConfig` (`buildFeatures` declares only `viewBinding`), so
  the hash is derived from what is actually installed and cannot disagree with
  it.
- `activity_diagnostics.xml` + `DiagnosticsActivity`, declared in the manifest,
  reachable from Settings.
- `export()` prefers the Download directory, falls back to
  `getExternalFilesDir(null)`, then `filesDir`, and reports the absolute path or
  the failure reason.

**Proof.** The screen compiles and the wiring is exercised by the assembled APK;
the report's shape is enforced by its type. No credential can be written because
there is nowhere to put one.

---

## RC-9. No regression coverage for any of the above

§12 lists seven required regression tests. Eight new test files implement them
(plus one for RC-10 and one for Parking's conditional music card), all pure JVM —
Robolectric cannot be used here because its `android-all` jar is not in the
offline cache.

| §12 requirement | file |
|---|---|
| (1) MAP_BROWSE has no music card | `MapCompositionTest` |
| (2) NAVIGATING controls not obscured | `MapCompositionTest` |
| (3) Music search does not initial-focus | `MusicFocusContractTest` |
| (4) Map Management resolves from the manifest | `ManifestResolutionTest` |
| (5) Manual Parking switches mode and can be exited | `LauncherModeParkingTest` |
| (6) Status view not duplicated | `StatusBarAbsenceTest` |
| (7) Online source entry always discoverable | `OnlineSourceDiscoverabilityTest` |
| (RC-10) all six map phases reachable | `MapPhaseFrontTest` |
| (§9) Parking's music card is conditional and passive | `ParkingMusicContractTest` |

`RepoFiles` and `Xml` are the shared helpers: they walk up from `user.dir` to
find the repository root and parse the manifest and layouts from disk, so the
assertions read the files that are compiled rather than a copy of their
contents.

**Not claimed.** §12 is explicit that a layout preview or screenshot is not
evidence of correct behaviour on the TS8. None of these tests proves anything
about the device; they prove that the source says what this document says it
says.

---

## RC-10. `WAITING_FIRST_FRAME` was declared and could never be entered

**Symptom.** §6 names six phases. Five of them were reachable; the fourth was
not. A grep for it in `app/src/main` returned exactly one hit — the enum
declaration in `MapPhaseStore.kt`. Both map screens went
`VALIDATING_PACK → LOADING_STYLE → READY`.

**Root cause, two layers.**

1. *Where the phase was driven.* `MapLibreMapController.setReadyListener`
   reports only two things: the style parsed, or building the style document
   threw. Both screens treated "style parsed" as "map is on screen" and moved
   the store to `READY` from inside `onReady`. The renderer's actual first
   frame — a third, later event — had no path into the phase machine at all.
2. *Why nobody noticed.* `MapLibreMapController.onMapReady` registers **only** a
   success lambda on `readyMap.setStyle(...)` (`MapLibreMapController.kt:150`).
   A native style rejection that invokes neither callback leaves the screen in
   `LOADING_STYLE` indefinitely, saying "đang tải kiểu hiển thị…" with no
   timeout, no error code and no actions. That is the white-screen problem §6
   exists to prevent, wearing a progress label.

**Change.**

- `MapHost` now registers both `MapView` listeners, verified in the 12.0.0 AAR
  bytecode rather than assumed:
  `addOnDidFinishLoadingMapListener { onFirstFrameDrawn() }` and
  `addOnDidFailLoadingMapListener { reason -> onStyleFailed(reason) }`.
  They are registered **before** `c.attach()`, so a rejection that fires while
  the controller is still parsing cannot slip between the two.
- `MapHost` gains a `RenderListener` interface. The host drives the whole phase
  ladder (`VALIDATING_PACK` is still the screen's, because it precedes the
  host); `HomeActivity` and `MapActivity` now receive `onStyleReady(controller)`
  and do only what is theirs — camera default and route replay.
- `onStyleLoaded()` moves to `WAITING_FIRST_FRAME` and arms
  `FIRST_FRAME_TIMEOUT_MS` (12 s). `onFirstFrameDrawn()` cancels it and moves to
  `READY`. `onFirstFrameTimedOut()` fails with the new
  `MAP_FIRST_FRAME_TIMEOUT` code and a sentence that says what a timeout does
  and does not prove.
- A style *rebuild* (palette flip, display-config change) replays `onReady`
  through the same listener. Once a frame has been seen the store goes straight
  to `READY`, so a later style change cannot turn a working map into a
  12-second "chờ khung hình" window.
- `onDestroy` cancels the pending timeout and clears `firstFrameSeen`. A post
  that outlived its Activity would write a failure into the process-wide store
  that no screen is showing.
- The pack name is captured once in `create()` instead of being re-read through
  `graph.pack()` on every failure. `graph.pack()` is a filesystem walk, and
  calling it inside the error handler for a directory that just failed is the
  one call that must not be able to hang.

**Proof.** `MapPhaseFrontTest` — behavioural: all five working phases are
reachable through the store's own API, `FAILED` is reachable only through
`fail()`, `WAITING_FIRST_FRAME ≠ READY` as observations, the pack name survives
a phase change and a failure, and `reset()` clears a stale error. Structural:
the source registers both `MapView` listeners, and neither map screen moves the
store to `READY` or `WAITING_FIRST_FRAME` itself any more — so a third screen
copying the old ladder fails a test instead of silently restoring the bug.

**Not claimed.** Whether the C2's GL driver actually fires
`onDidFinishLoadingMap` is a question only the unit can answer. The phase
machine now *listens* for it and has a truthful failure if it never comes;
`BATCH1_DEVICE_TEST_CHECKLIST.md` asks the driver to confirm which one happened.
