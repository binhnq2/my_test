# Batch 1 — UI gap closure

Each of §3–§9's requirements, what the source now does about it, and where it can
be checked. Three columns matter here: **closed**, **partial**, and **not
closed**, because §13's acceptance gate depends on the difference being visible
rather than averaged away.

The reference for every visual judgement is `docs/UI_design.png`. No screenshot
of a running screen was taken — `adb devices` is empty and this host cannot start
an emulator — so every "matches the design" below means *the declared dp values
match*, never *the rendered frame was compared*. `KNOWN_LIMITATIONS.md` §D9
records this, and `BATCH1_SANDBOX_TEST_REPORT.md` repeats it.

---

## §3. Design system

| requirement | state | where |
|---|---|---|
| consistent navy background tokens | closed | `colors.xml`: `bg_root`, `bg_window`, `surface_card`, `surface_card_raised`, `surface_nav`, `surface_chip` |
| blue-tinted surfaces, not grey | closed | the six surface tokens are all blue-shifted; `bg_screen` draws a graded wash rather than a flat fill |
| elevated cards | closed | `bg_card` / `bg_card_raised` are gradients with a 1 dp `surface_card_border` stroke |
| borders | closed | the border colour is a token; the 1 dp stroke is repeated in the layered drawables |
| active accent | closed | `accent`, `accent_pressed`, `accent_soft`, `accent_text`, `accent_hairline` |
| text hierarchy | closed | `text_primary` / `text_secondary` / `text_tertiary` / `text_on_accent` |
| light gradients | closed | `bg_screen`, `bg_card`, `bg_card_raised`, `bg_chip`, `bg_bottom_nav`, `bg_top_overlay`, `bg_parking` |
| no runtime blur / shaders / video | closed | every background is a static `GradientDrawable` XML; there is no `RenderEffect`, no blur, no shader, no animation on any panel |
| remove the dark-flat look | **closed, unverified on device** | the primary surfaces are layered; `bg_eta_strip`, `bg_speed_card`, `bg_list_item` and `bg_button_primary` were given gradient + stroke in this batch |
| clear layering on cards, bottom bar, map chrome, music surface | **closed, unverified on device** | cards and the bar carry gradients + strokes; the map chrome is an overlay over a live map; the music card uses `bg_card` with a `bg_card_raised` artwork tile |
| 1024×600 optimisation | closed | `screen_pad` 16, `gap` 12, `gap_small` 8, `card_pad` 16, `top_overlay_height` 40, `vendor_bar_inset` 32 — the whole vertical budget is accounted for by tokens, not literals |

**What "unverified on device" means here.** Whether a gradient reads as depth on
the C2's panel at 1024×600, in daylight, is a question only the panel can answer.
The tokens are consistent and the drawables are layered; nothing here claims more.

---

## §4. Status bar and Home

| requirement | state | evidence |
|---|---|---|
| do not repeat clock/date/battery/Wi-Fi/Bluetooth/mobile | closed | no layout inflates `StatusBarView`; `StatusBarAbsenceTest` scans all layouts |
| remove/disable the full `StatusBarView` on the six normal screens | closed | Home and Map carry `CompactTopBar`; the other nine screens draw nothing at the top and inset by `vendor_bar_inset` |
| do not force fullscreen over the vendor bar | closed | `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` lays out *under* the bar without hiding it; every screen insets 32 dp |
| Home keeps only Weather, Voice, Quick Settings | closed | `view_compact_top_bar.xml`: `weatherChip`, `btnVoice`, `btnQuickSettings` |
| Weather has `LOADING / READY / UNAVAILABLE` | closed | `CompactTopBar.WeatherState` |
| no hard-coded fake temperature | closed | `setWeather(UNAVAILABLE, null)` on construction; `READY` requires a non-blank string |
| large clock only in Parking Mode | closed | `activity_parking_mode.xml` `parkingClock` at 96 sp is the only large clock; `CompactTopBar.refreshClock()` is a no-op |

**Not done, deliberately.** The weather chip shows `UNAVAILABLE` because no
provider exists in this build. Wiring one would be a Phase-2 feature, and showing
a plausible degree would violate §4 directly.

---

## §5. Composition per screen

| screen / mode | requirement | state |
|---|---|---|
| Home | map panel + normal music card on the right, side by side | closed — `mapHost` and `musicCard` are siblings with `End_toStartOf` |
| Map Browse | full-width map, **no floating music card** | closed — `MapChrome.isBrowse` ⇒ `showsMusicCard == false`, for every browse state |
| Navigation | map and compact card as two **non-overlapping** regions | closed — the card is an overlay; `guidanceControls` is inset by `map_controls_reserve` (248 dp = card 236 + gap 12) so it ends where the card begins |
| Navigation | maneuver / overview / mute / end-route always visible and tappable | closed — the three controls are inside `guidanceControls`, which is visible in `NAVIGATING`, `REROUTING` and `ARRIVED` and inset out from under the card |
| Music | fullscreen per the design hierarchy | closed — `activity_music_fullscreen.xml`, now-playing left, library right |
| Search | its own composition | closed — `activity_search.xml`, vendor-bar inset, no status row |
| App Drawer | its own composition | closed — `activity_app_drawer.xml`, grid, own back affordance |
| Quick Settings | its own composition | closed — `activity_quick_settings.xml`, four tiles, two sliders, five wide actions |
| Parking | its own composition | closed — `activity_parking_mode.xml`, `bg_parking`, large clock, date, bottom nav, tap to exit; the compact music card is present but `gone` until a track exists |
| bottom nav unique | `Home \| Map \| Music \| Phone \| Apps`, no duplicate action | closed — `view_bottom_nav.xml` declares exactly those five plus the volume stepper; `navPhone` launches the dialer rather than being a sixth tab |
| no left menu | closed | no layout declares a drawer, no `DrawerLayout` in the tree |

**One honest caveat on the Navigation row.** The card still floats *over the
map*; it does not float over the *controls*. That is the reading of §5's
Chinese/Vietnamese phrasing that keeps the map from re-centring its camera the
moment guidance starts. `MapCompositionTest` asserts the controls' geometry, and
`BATCH1_DEVICE_TEST_CHECKLIST.md` asks the driver to confirm it on the panel.

---

## §6. Map empty/error shell and Map Management

| requirement | state | evidence |
|---|---|---|
| `MapManagementActivity` declared in the manifest | closed | `AndroidManifest.xml`; `ManifestResolutionTest`; present in the merged debug manifest |
| every launch Intent checked | closed | `MapManagementActivity.start()` returns `false` rather than throwing; all four callers handle it |
| entry from the map error/empty state | closed | `MapPhaseSurface` `btnMapManage` |
| entry from Settings > Bản đồ | closed | `SettingsActivity.btnManagePack` |
| entry from Quick Settings | **closed in this batch** | new `btnMapManagement` in `activity_quick_settings.xml` |
| entry from the App Drawer | **not applicable, by design** | the drawer lists installed `MAIN/LAUNCHER` apps from `PackageManager`; an internal Activity with no intent filter cannot appear, and adding one would put the launcher's own settings into the app list. Quick Settings and Settings both carry the entry |
| no white screen when the map does not render | closed | `MapPhaseSurface` covers the renderer in every non-`READY` phase |
| six phases `INITIALIZING … FAILED` | closed | `runtime/MapPhaseStore.kt`; `MapPhaseStore.Snapshot` |
| `FAILED` shows error code, active pack, `Thử lại`, `Cài đặt bản đồ`, `Chi tiết` | closed | `view_map_phase.xml`: `mapPhaseErrorCode`, `mapPhaseErrorPack`, `btnMapRetry`, `btnMapManage`, `btnMapDetails` |
| do not claim to have fixed map rendering | honoured | `BATCH1_ROOT_CAUSE_AND_CHANGES.md` RC-2 says so explicitly |

**Where the phases come from, honestly.** `INITIALIZING` and `VALIDATING_PACK`
are driven by `AppGraph.loadPack`/`packValidating`. `LOADING_STYLE` and
`WAITING_FIRST_FRAME` are driven by `MapHost`: the controller's `ReadyListener`
means "the style parsed", and the `MapView`'s own
`addOnDidFinishLoadingMapListener` means "a frame was drawn". Both are needed —
before this batch the two events were collapsed into one `READY`, which made
`WAITING_FIRST_FRAME` unreachable. The controller registers only a success
lambda on `setStyle` (`MapLibreMapController.kt:150`), so a native rejection
that calls back neither success nor failure would leave the shell claiming
progress forever; `MapHost` therefore arms `FIRST_FRAME_TIMEOUT_MS` (12 s) and
turns that silence into `FAILED` with `MAP_FIRST_FRAME_TIMEOUT`. A frame that
arrives late still clears it through the same listener.

---

## §7. Music UX

| requirement | state | evidence |
|---|---|---|
| root takes initial focus | closed | `musicRoot` is `focusable` + `focusableInTouchMode`; `holdInitialFocus()` |
| search field does not auto-focus | closed | no focus attributes on `musicSearch`; `MusicFocusContractTest` |
| keyboard hidden by default, opens only on Search | closed | `stateAlwaysHidden\|adjustPan` in the manifest, declared on the music activity only |
| source selector `Trên thiết bị \| USB \| Trực tuyến` | closed | `sourceDevice` / `sourceUsb` / `sourceOnline`, wired by `wireSources()` |
| under Trực tuyến: Radio, Podcast, Navidrome/Jellyfin | closed | `OnlineMusicRuntime.providers()`, fixed order |
| providers missing config still show `Thiết lập`, never disappear | closed | `renderProviders()` draws every provider unconditionally; `OnlineSourceDiscoverabilityTest` |
| keep `MusicPlaybackService`, no second playback state | closed | `MusicPlaybackService` owns `LocalMusicBackend`/`LocalMusicPlayer`, the only `MediaPlayer`; every screen reads `MusicRuntime` |

**Not claimed.** Radio is playable (a public directory, no configuration). Podcast
and Subsonic need an endpoint the build does not ship, so they report
`BLOCKED_EXTERNAL_CONFIG` and offer setup. That is the standing campaign rule,
not a defect.

---

## §8. Volume honesty

| requirement | state | evidence |
|---|---|---|
| keep the `AudioManager` bridge | closed | `AndroidVehicleAudioBridge` unchanged in role |
| read-before / write / read-after | closed | `QuickSettingsActivity.writeVolume`, `VoiceCommandExecutor.applyVolume` |
| states `SUPPORTED / NO_EFFECT / UNAVAILABLE` | closed | `VolumeCapabilityTracker.Capability` (plus `UNKNOWN` before evidence) |
| do not change the fake volume UI when the write fails or read-back is unchanged | closed | the slider is re-bound to the **observed** level after every write; an inert stream snaps back and prints `Không có tác dụng · N/M` |
| show `Không hỗ trợ trên thiết bị này` or hide by capability | closed | `isVolumeFixed()` ⇒ slider disabled, label `Không hỗ trợ`, tracker `UNAVAILABLE` |
| no SYU/MCU reverse engineering; keep `VehicleAudioBridge` as the adapter boundary | honoured | no firmware, no MCU contract, no new bridge implementation |

**The threshold, as the driver specified it.** `NO_EFFECT` requires at least two
consecutive valid write/read-back cycles that did not change, and explicitly does
not count "increasing at max" or "decreasing at min".
`VolumeCapabilityTrackerTest` covers each sentence separately.

---

## §9. Manual Parking

| requirement | state | evidence |
|---|---|---|
| manual action switches clearly to Parking | closed | `QS.btnParking` → `ParkingRuntime.requestEnter()` → `ParkingModeActivity` with `REORDER_TO_FRONT or SINGLE_TOP` |
| can exit | closed | tap anywhere on `parkingPane` / `parkingExitHint`, or Back; both go through `requestExit()` + `finish()` |
| Parking screen per design: light landscape/car background | **partial** | `bg_parking` is a vertical gradient standing in for the design photograph — the launcher ships no photograph |
| large clock, date | closed | `parkingClock` 96 sp, `parkingDate` |
| compact music | closed, conditional | `parkingMusicCard` (`MusicCardView`, `musicCompact`, `visibility="gone"`); `ParkingModeActivity` reveals it only when `PlaybackState.hasTrack`, so the design's player-less panel is preserved when nothing is playing. Asserted by `ParkingMusicContractTest` |
| bottom nav | closed | `BottomNavView` in `activity_parking_mode.xml` |
| do not auto-play music or auto-open apps | closed | the card's visibility is a *consequence* of playback state, never a trigger — no `play(`, `startActivity` or `startService` in the Activity; `ParkingMusicContractTest` asserts all three are absent |
| Auto Parking default OFF, labelled `Chưa khả dụng` | closed | Settings parking row renders `parking_auto_unavailable`, no switch |
| do not report Auto Parking complete from a GPS heuristic | honoured | `BATCH1_DEVICE_TEST_CHECKLIST.md` and `KNOWN_LIMITATIONS.md` D12 state it |

---

## What §5 and §4 required and this batch did **not** do

- **No redesign.** No screen was re-composed beyond the constraints the spec
  names. No left menu, no new navigation model, no new tab.
- **No change to the bottom navigation's five items**, per the decision that
  Quick Settings does not become a sixth.
- **No landscape-specific resource qualifiers added.** The panel is fixed
  landscape, and adding `layout-land` would double every layout for a device
  that never rotates.

---

## Summary

| §  | closed | partial | not closed |
|---|---|---|---|
| 3 | 11 | 0 | 0 (one row awaits the panel) |
| 4 | 7 | 0 | 0 |
| 5 | 12 | 0 | 0 |
| 6 | 8 | 0 | 1 by design (drawer entry) |
| 7 | 7 | 0 | 0 |
| 8 | 6 | 0 | 0 |
| 9 | 8 | 1 (background stand-in) | 0 |

The one entry marked "by design" is the app drawer, and the reason is that the
drawer is a list of *other* apps. The one partial is Parking's background
photograph, which the build does not ship.
