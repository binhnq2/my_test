# Known limitations

Everything this build does not do, cannot do on this hardware, or does only
partially. Each entry says what the driver sees as a result, so a limitation can
be recognised in the field rather than rediscovered.

This document exists because the specification forbids claiming success for
anything that was not verified. Where a claim could not be tested, it is here
and it is labelled as unverified, not as working.

## A. Blocks the acceptance gate

These are the items that keep the overall status short of a clean PASS.

### A1. No physical device and no emulator on this machine

`adb devices` is empty — the head unit is not connected. The host reports
`Virtualization Enabled In Firmware: No`, so no API 23 emulator can start
either.

Consequences, all of them real:

| acceptance item | status |
|-----------------|--------|
| APK installs on API 23 | **not run on a device**; install path and recovery documented, build verified |
| map renders offline on the unit | **not run on a device**; style generation, pack validation and the 748-tile pack verified offline |
| search / route / GPS with network off | search and routing verified against the real pack bytes; **not run on a device** |
| music in background, MediaSession, no crash on USB removal | **not run on a device**; logic covered by JVM tests (`PlaybackCoreTest`, `PlaybackQueueTest`, `Checkpoint*`, `ScanDiff`, `TrackIds`) |
| TTS duck/restore, no regression to Phone/Radio/Camera/CANBUS | **not run on a device**; the radio cannot be paused at all (see B1) |
| app drawer opens real apps | **not run on a device**; `PackageManager` query path only |
| 2-hour soak, navigation + music | **not run** — no device, no emulator |

Nothing in the source pretends otherwise, and `TEST_REPORT.md` marks each row
with where it was actually verified.

**For Batch 1 the same gap, in its own terms.** Build and the full unit suite
pass (58,686,466-byte APK, SHA-256 `B0A26B62…`, 376 tests across 30 suites,
0 failures); **not one pixel of it was seen on the C2**. §1 of the Batch 1 brief
forbids reporting `DEVICE_PASS`, and the status stays
`SANDBOX_PASS_DEVICE_PENDING` until `BATCH1_DEVICE_TEST_CHECKLIST.md` comes back
with seven photographs and a diagnostics export.

### A2. The 2-hour soak cannot be performed here

The specification says to run it "nếu có thiết bị" and to state the on-car
portion explicitly otherwise. There is no device, so: not performed. What the
soak would have caught — a `MediaPlayer` released twice, a checkpoint ticker
that outlives the service, a `MapView` surface leaked across an Activity
recreate — is argued for in `ARCHITECTURE.md` §2 and covered where JVM tests can
reach it, but that is not the same as two hours of running.

## B. Hardware and platform limits

### B1. The FM/AM radio cannot be paused by this app

The tuner is owned by a vendor application with no public API and no broadcast
this app may call. `AndroidVehicleAudioBridge.pauseExternalSource()` returns
`AudioResult.Unsupported`, and the UI shows "chuyển nguồn bằng tay" instead of a
disabled-looking button that lies. Bluetooth playback is the only external
source the launcher can suspend, and only where the vendor stack honours AVRCP.

### B2. Cannot disable or take over the stock launcher automatically

Deliberate, and required by the spec. `HomeActivity` declares `CATEGORY_HOME` +
`CATEGORY_DEFAULT`, which makes the app a **candidate** in Android's own "choose
Home app" dialog — it does not make it Home. A person has to pick it, and the
stock launcher is never disabled, so returning to it is always possible.

The candidate filter was added late in this campaign: without it the app was only
a normal launchable app and could not be assigned the Home role at all, which
would have made the spec's manual opt-in impossible. That change is in
`app/src/main/AndroidManifest.xml`; it was not verified on a unit (see A1), so what
is claimed is that the filter is present and correct, not that the chooser has
been seen to list the app.

Recovery instructions are in `ADB_INSTALL_AND_RECOVERY.md` §5, with three
independent ways back.

### B3. Screen-off and restart are not power operations

`PowerManager.goToSleep()` and `reboot()` are signature-protected. "Screen off"
is a black full-screen Activity and "restart" restarts the launcher task. The
driver-visible behaviour matches; the mechanism does not reach the MCU.

### B4. No MCU / CANBUS / source switch integration

Switching the head unit's audio source at the MCU level, reading CANBUS frames,
and the vendor's deeper audio routing all need privileges this app does not have
and are explicitly out of scope for this campaign. `VehicleAudioBridge` is the
seam where a future `SyuAudioBridge` would plug in; nothing here calls a vendor
SDK.

### B5. Quick Settings on API 23

| control | limitation |
|---------|------------|
| mobile data | no public API to toggle it; the tile opens the system data panel |
| Wi-Fi / Bluetooth | the toggle can be refused by firmware; the boolean return is checked and the tile stays off, never claims success |
| brightness | requires `WRITE_SETTINGS`; the row offers the grant and reports it honestly until granted |
| screen off | a black Activity, not a real panel-off (B3) |

## C. Map data limits

Measured on the pack built for this campaign. These are properties of the data,
not defects in the code.

### C1. Speed limits exist on a minority of ways, and the roundel says so

OSM in Đà Nẵng tags `maxspeed` on only a minority of highway ways. The routing
graph carries the posted value where it exists (`build_routing_graph.py`
`parse_maxspeed`, and a posted tag beats the road-class average), so the roundel
is **visible and honest** rather than hidden:

| what the edge has | what the roundel draws |
|---|---|
| a posted `maxspeed` | that number, in the limit-ring colour |
| only a class average | `--`, in the tertiary text colour |
| nothing usable | `--` |

It turns `danger` red while `SpeedUiState.alerting` is true, so the overspeed
state is visible without reading the number. Printing a class average as if it
were a posted limit is the fabrication the spec forbids, which is why `--` is a
first-class state and not a placeholder for zero.

A pack with fuller `maxspeed` tagging starts showing real values with no code
change.

### C2. Addresses exist on POIs, not on streets

Of 11,268 places, 2,062 POIs carry an `addr:*` address but only **2 of 4,874**
street rows do. OSM in Đà Nẵng tags addresses on buildings and shops; a street's
own nodes almost never carry one. Search shows the address line on POI rows and
leaves it blank on street rows rather than guessing a ward.

### C3. No turn restrictions

`graph.bin` header `flags = 0`. The format has bit 0 for turn restrictions and
the builder does not yet emit them, so a banned left turn into a one-way street
is routable and may be offered. This is the most likely source of a route that
a local driver would call wrong.

### C4. No live traffic, no live closures, no live speed

There is no network path and no traffic provider. ETAs come from the graph's own
per-edge speed model, so a jammed road is still routed as if it were clear, and
`remainingSeconds` does not react to real traffic — only to distance covered.

### C5. Ba Na Hills and similar private-access destinations are unreachable

Ways tagged `access=private` are flagged car-forbidden, so routing to them
reports `UNREACHABLE` ("Không có lộ trình lái xe tới điểm này.") even though the
place exists in search. Correct behaviour, surprising result for a driver aiming
at a resort with a gated service road.

### C6. Detour off the extract boundary

The pack covers `107.85,15.85,108.45,16.25`. A route that would leave that box
rounds its way around the edge or fails, because the clipped graph stops there.
Search, snapping and the camera are all bounded to the pack, so the driver sees
"ngoài vùng dữ liệu" rather than a route that ends in the sea.

### C7. One city, one pack

Only Đà Nẵng is built. A second city needs a second pack and a UI to switch
between them; this build loads exactly one pack at a time, chosen by directory
priority (`MAP_DATA_PIPELINE.md` §3).

### C8. Labels need the glyph ranges the pack carries

The pack ships 7 Unicode ranges (Latin through Latin Extended Additional). A
label containing a character outside them renders as a blank box. For Vietnamese
this is complete; for a pack with CJK or Arabic labels it would not be.

### C9. A software-GL emulator renders no labels at all

On an emulator running the SwiftShader software renderer (`GPU Identifier:
Android Emulator OpenGL ES Translator (Google SwiftShader)`), MapLibre draws
fills, lines and circles but **no symbol layer** — so the map has no place
names, no street names and no vehicle arrow. Nothing is logged: glyphs are
requested and return 200, the symbol layers are present in the loaded style, and
the style is valid. The same APK on a real GPU (`-gpu host`) renders every
label.

Diagnose it by reading the GPU line in logcat before believing a label bug:

```
adb logcat -d | grep "GPU Identifier"
```

If it says SwiftShader, launch the AVD with `-gpu host`. This cost one full
debugging session that rewrote the pack's glyph files before the GPU was
checked; the glyph files were never at fault. Do not "fix" labels against a
software-GL emulator.

### C10. No speed-camera warnings — the pack has no camera features

`highway=speed_camera` (and `enforcement=*`) is **not** extracted by
`build_map_pack.py` and is not in `graph.bin`. There is therefore no camera
feature to warn about, and no proximity trigger exists in the routing or
navigation path.

This was investigated and then left unimplemented on purpose:

- `grep -rn "speed_camera|speedCamera|camera|traffic_sign" tools/` returns only
  `parse_maxspeed` and unrelated matches — no camera handling exists.
- An Overpass query for `node["highway"="speed_camera"]` over the pack bounds
  returned `http_code 000` with zero bytes: the build host had no network, so
  even a one-off dataset could not be fetched.
- Fabricating camera positions, or shipping a "camera ahead" alert with no
  source, is exactly the mock data the spec forbids.

What the driver gets instead is the honest subset that *is* backed by data: the
speed-limit roundel with the posted limit, and the overspeed alert. What is
missing is the camera **position** warning.

Closing this needs, in order: a `speed_camera` layer in the pack extractor, a
`FLAG_CAMERA` (or a separate camera table) in `graph.bin`, and a proximity
trigger in `NavigationController`. None of that exists, and none of it can be
built from the data on this machine.

### C11. No no-overtaking / no-parking sign warnings

Same cause as `C10`. OSM carries these as `overtaking=no` / `parking:*` on ways,
and neither is read by the pack builder, so there is no per-edge sign attribute
to warn from. The `FLAG_*` bit space in `graph.bin` has room; the data pipeline
does not fill it.

The road-class speed model and the posted `maxspeed` are the only traffic rules
this build knows about. A driver expecting "cấm vượt" or "cấm đỗ" callouts will
not get them.


## D. Feature scope

### D1. Online music: one provider ships configured, two need the driver

`core:diagnostics` is still an empty shell — it contains only an
`AndroidManifest.xml`, and nothing navigates to it, because a button that opens
nothing is worse than no button.

`core:music-provider-online` is **no longer** a shell. It carries three real
providers behind `OnlineMusicGateway` (see `ONLINE_MUSIC.md`):

| provider | needs | what the driver sees without it |
|---|---|---|
| Internet Radio (Radio Browser) | **nothing** | works — this is the one that ships production-capable |
| Podcast RSS | a feed URL in Settings | `BLOCKED_EXTERNAL_CONFIG`: *"Podcast: chưa cấu hình địa chỉ RSS trong Cài đặt"* |
| Navidrome / Jellyfin | server address, user, password | `BLOCKED_EXTERNAL_CONFIG`: *"Navidrome/Jellyfin: chưa cấu hình địa chỉ máy chủ, tên đăng nhập hoặc mật khẩu"* |

That is a **PARTIAL** by §21's definition, reported as such rather than padded.
No credential exists in this repository, so no provider can be demonstrated
against a real server from here.

### D2. The search screen has no map preview

The design shows a map image of the selected place. This implementation shows
name, address, category, coordinates and distance, and hands the point to the
real map on "Lộ trình". A second `MapView` would mean a second GL surface and a
second style load on hardware that can barely afford one. Recorded in
`UI_IMPLEMENTATION.md` §4.

### D3. Voice Instant works only where the unit ships a recogniser

Voice is **wired** — `VoiceTrigger`, `VoiceInstantController`,
`SpeechRecognizerAdapter`, `VoiceRouter`, `IntentParser`, `VoiceFeedback` and the
overlay all exist and are covered by JVM scenarios (see `VOICE_INSTANT.md`). The
microphone is drawn and functional.

What cannot be promised is the platform half. On the TS8:

| case | what happens |
|---|---|
| ROM has no `SpeechRecognizer` service | overlay shows `Voice service unavailable` |
| ROM's recogniser is English-only | Vietnamese folding (`IntentParser.fold`) recovers common phrases; the rest reaches `Ambiguous` or `Unrecognized` |
| no Vietnamese model and no network | `EXTRA_PREFER_OFFLINE` is advisory on API 23; the session fails as `SERVICE_UNAVAILABLE` and does **not** retry |
| ROM stripped `TextToSpeech` | Voice Instant still runs — the overlay shows every caption; only the spoken audio is missing |

**No wake word.** There is no "Hey TS8". Background listening is forbidden by
the campaign's own constraint, so the microphone opens only on a tap.

### D4. No weather provider — the chip says so

The design shows `Đà Nẵng 28°C`. Weather widgets are explicitly out of scope, and
a fabricated temperature is the mock data the spec forbids. Since Batch 1 the
chip is **visible and honest** rather than hidden: `CompactTopBar.setWeather`
takes `LOADING / READY / UNAVAILABLE`, is constructed with
`setWeather(UNAVAILABLE, null)`, and prints `Chưa có thời tiết`. A `READY` call
with blank text is downgraded to `UNAVAILABLE`, so a future provider cannot
half-wire it into a plausible degree.

On the map screen the chip is absent entirely (`compactOnly`), because it would
compete with the maneuver card for the same corner.

### D5. No album art in the media notification

`Track` has no embedded-artwork path in this build, and a placeholder image in
the car's media widget would be a fake asset. The notification shows title,
artist and album text.

### D6. Saved places are device-local

`SavedPlaces` is a local store. There is no account and no sync, so a favourite
does not survive a factory reset. That is a deliberate consequence of "no
network", not an omission.

### D7. The `housenumber` label layer may render nothing

`disp_label_house_v1` is stored, read by `mapDisplayConfig()`, and used by
`StyleBuilder` to emit a label layer. Most packs in the current format do not
carry a `housenumber` source layer inside `basemap.mbtiles`, so turning
"Số nhà" on can produce **no visible change**. The setting is not broken — the
data is absent, and the layer is empty rather than fabricated.

### D8. Parking has no vehicle-signal source

`ParkingSignals` declares `vehicleGearPark`, `vehicleAccOn`,
`vehicleParkingBrake` and `vehicleIgnitionOff`, and `ParkingStateResolver` has a
branch for each — but `ParkingRuntime` never sets any of them. Publishing a
value there would require a bridge whose contract this campaign does not have
(`B4`), and spec §18 forbids reversing the MCU API without one.

What the driver gets instead is the fallback path, exactly as §18 words it:
**manual entry plus the GPS/time heuristic**. Auto-enter needs 20 s of
stationary, stable-location evidence to become a candidate and 120 s to reach
HIGH confidence.

Two consequences worth knowing:

- **The `REVERSE_ACTIVE` blocker never triggers.** `reverseCameraActive` is
  hard-coded `false` because the launcher has no readable signal for the
  reverse camera on this unit. Reversing into a space for more than two minutes
  could still reach PARKED.
- **A noisy receiver degrades to `STOPPED_TEMPORARILY`,** not PARKED, because
  `LocationStability` never sees a stable window. That is the safe direction —
  no clock appears over a moving car — but the driver has to tap the manual
  button.

### D9. No screenshots could be taken

Spec §22 asks for seven post-repair screenshots. `adb devices` is empty and the
host cannot start an emulator (`A1`), so **no screenshot was produced**. The
comparison in `UI_GAP_REPORT.md` is built from pixel measurements of
`docs/UI_design.png` against the layout XML and dimens, not from a captured
frame. Claiming otherwise would be the exact dishonesty the spec forbids.

Batch 1 keeps the same position: `BATCH1_SANDBOX_TEST_REPORT.md` records a build
and 376 unit tests, never a rendered frame, and
`BATCH1_DEVICE_TEST_CHECKLIST.md` asks for the seven photographs instead.

### D10. The map renderer is wired, not repaired

§6 of the Batch 1 brief forbids claiming a rendering repair, and this build does
not claim one. What Batch 1 added is the *shell*: the six phases
(`INITIALIZING`, `VALIDATING_PACK`, `LOADING_STYLE`, `WAITING_FIRST_FRAME`,
`READY`, `FAILED`), the error surface with an error code, the active pack, and
three actions, and every entry point into Map Management.

`WAITING_FIRST_FRAME` is now entered from the `MapView`'s own
`addOnDidFinishLoadingMap` callback, and left either by that callback or by a
12-second `MAP_FIRST_FRAME_TIMEOUT`. Both paths exist because
`MapLibreMapController` registers only a success lambda on `setStyle`
(`MapLibreMapController.kt:150`): a style the native layer rejects can invoke
neither callback, and before this batch the screen claimed to be loading
forever with no code, no timeout and no action.

What is *not* established: that the C2's GL driver fires that callback at all.
If it does not, the driver sees `MAP_FIRST_FRAME_TIMEOUT` after 12 s. That is a
truthful failure, not a fix. `BATCH1_DEVICE_TEST_CHECKLIST.md` step 4 asks which
happened.

### D11. Volume honesty depends on what the audio HAL does

`VolumeCapabilityTracker` reports what it observed, not what it wished for: two
consecutive valid write/read-back pairs that did not move the stream declare
`NO_EFFECT`, and `isVolumeFixed()` declares `UNAVAILABLE` without a write.

Which of the three states the TS8 actually reaches is a device question. The
tracker covers all three; no SYU/MCU protocol was reversed, and
`VehicleAudioBridge` remains the adapter boundary an MCU implementation would
later go behind (§8 scope).

### D12. Auto Parking is still not available, by design

Batch 1 wired **manual** Parking (Quick Settings → `ParkingModeActivity`, with
Back and a tap both leaving it) and left Auto Parking exactly where it was:
default OFF, labelled `Chưa khả dụng`, no switch, no signal source.

The GPS/time heuristic in `ParkingResolver` can move the *screen* — §9 permits
that — but it is never reported as Auto Parking being complete, and it is never
the sole evidence of anything: a stationary receiver at a red light and a lost
signal both look the same, which is why 20 s of stability plus 120 s to HIGH
confidence are required before a candidate exists at all (`D8`).

A consequence worth repeating from `D8`: `REVERSE_ACTIVE` can never block, since
`reverseCameraActive` is hard-coded `false` with no readable signal.

### D13. Some Batch-1 composition is proven only at the source level

Three of the acceptance items cannot be exercised on this host, because they
need either an inflated view hierarchy or a device:

| item | how it is proven | what is missing |
|---|---|---|
| Map Management resolves from the manifest | `AndroidManifest.xml` parsed from disk + the merged manifest inside the built APK | an actual `startActivity` on API 23 |
| Music opens without the IME | `windowSoftInputMode` on that one activity, plus `musicRoot` focusability | the ROM honouring `stateAlwaysHidden` |
| Navigation controls not under the music card | `236 + 12 = 248` recomputed from `dimens.xml` against `guidanceControls`'s margin | the panel actually clearing the 248 dp band |

Each is the strongest honest statement available without a device. None is a
substitute for `BATCH1_DEVICE_TEST_CHECKLIST.md`.

### D13a. Parking's music card is a deliberate departure from the design sheet

`docs/UI_design.png` state 7 draws the Parking mode with a large clock, a date,
one line, a photograph and the bottom bar — **no player card**. Spec §9's bullet
list asks for "music compact" on the same screen. The two cannot both be drawn
literally, so the card exists but is `gone` until playback already has a track:
with nothing playing, Parking matches the design exactly; with something
playing, the card is information about a state the driver created elsewhere.

The passivity rule is asserted, not just documented — `ParkingMusicContractTest`
requires `visibility="gone"` in the layout, `musicCompact="true"`, and the
*absence* of `play(`, `startActivity` and `startService` in
`ParkingModeActivity`. What it cannot assert is placement: that the card, pinned
to the pane's trailing edge, leaves the tap-to-exit area reachable on the real
1024×600 panel. That is step 7 of `BATCH1_DEVICE_TEST_CHECKLIST.md`.

### D14. GPS speed correction is one constant, not a calibration curve

`Bù tốc độ GPS` (`speed_gps_offset_v1`, −20…+20 km/h) adds a single constant to
**every** GPS-derived sample, at the one boundary where a fix becomes a sample
(`SpeedMonitor.onFix`). The readout, the limit comparison and the overspeed alert
all move together by construction, because they all read the same
`SpeedUiState`.

What it does not model: the error is not constant in reality. Tyre wear, wheel
size and cluster optimism produce a roughly proportional error, so a +4 km/h
correction tuned at 60 km/h overshoots at 20 km/h and undershoots at 100 km/h.
A proportional (percentage) term would fit better; it is not implemented because
the driver asked for "cộng trừ" and one number is what was asked for.

A vehicle-bus sample is never offset — that number is already the car's own.

**How it was verified.** The emulator's `geo fix` does not honour its velocity
argument and drops the speed field of an NMEA `RMC` sentence, so a real GPS speed
cannot be produced on this host (`adb emu geo nmea` with `29.2` knots still reads
`0 km/h`). The offset was therefore verified against a **known-zero** baseline,
which isolates the offset term exactly:

| `Bù tốc độ GPS` | fix speed | speed chip |
|---|---|---|
| `0` (before) | 0 m/s | `0 km/h` |
| `+10` | 0 m/s | **`10 km/h`** |
| `−5` | 0 m/s | **`0 km/h`** (clamped, never negative) |
| `0` (restored) | 0 m/s | `0 km/h` |

That proves the setting reaches `SpeedMonitor.Config.gpsOffsetKph`, that the
arithmetic is applied, that the clamp holds, and that it survives leaving and
re-entering the screen. It does **not** prove behaviour at 60 km/h — for that the
unit has to be driven, which is `E`.

### D15. Alternative routes are a penalty heuristic, not a k-shortest proof

`AStarRouter.routeAlternatives` re-runs A* with the used edges' cost multiplied
by `ALT_PENALTY = 2.4`, then filters candidates that share ≥ 75 % of their nodes
with an accepted route (`MAX_SHARED_NODE_FRACTION`) or that are more than 1.7×
slower (`MAX_ALTERNATIVE_SLOWDOWN`). It stops after `MAX_ALTERNATIVE_ATTEMPTS = 6`
or three accepted routes.

Consequences worth knowing:

- **It can return fewer than three, including one.** On a corridor with a single
  bridge or a single one-way pair there is genuinely no second road, and the
  card correctly shows one option instead of a fake detour.
- **"Nhanh nhất" is the fastest of the routes found,** not a proof that no faster
  route exists. With `C3` (no turn restrictions) the fastest route can still
  contain a turn a local driver would call illegal.
- **The penalty is a global scalar per edge,** so it pushes the search away from
  the whole first route rather than off one segment. On a long route with one
  congested middle the alternative may be a wide loop rather than a short
  bypass.

### D16. Zoom belongs to the driver — the rule every camera path must obey

Not a limitation but a contract, written down because it was broken in three
places at once and the symptom looked like a broken button rather than a camera
policy:

> **Only two things may choose a zoom: the driver, and fitting a newly
> calculated route.** A follow camera moves the target and keeps the zoom.

Why it matters: the follow path runs about once a second. A follow that writes
its own zoom therefore undoes a `+` tap within a second, and the driver reads
that as "the zoom button does not work". That was the bug — `centreOnVehicle`
re-imposed `navigationZoom` on every fix, `onNavigationFix` re-imposed
`NAV_ZOOM`, and Home's `applyAutoFollow` re-imposed `FOLLOW_ZOOM`.

Where the rule now lives:

| path | zoom behaviour |
|---|---|
| `MapActivity.followVehicle` (browse, per fix) | reads the camera, replaces only the target |
| `MapActivity.centreOnVehicle` | `navigationZoom` — **first placement only** |
| `MapLibreMapController.onNavigationFix` (guidance) | `driverFollowZoom ?: NAV_ZOOM` |
| `MapLibreMapController.fitRoute` | fits the route and **clears** the driver's zoom |
| `HomeActivity.applyAutoFollow` | target only, `FOLLOW_ZOOM` on first placement |
| re-centre buttons (both screens) | target only — position, not scale |

The driver's zoom is detected by observation, not by wiring every control into
the controller: if the live camera is not where the policy left it, something
else moved it and that value wins. It is sticky until `fitRoute`, because a
one-shot comparison would revert on the next fix.

**Pan and pinch are not the same gesture.** MapLibre reports both as
`REASON_API_GESTURE`. Treating them alike meant a pinch while navigating killed
the follow camera permanently — the guidance layout has no re-centre button, so
the only way back was ending the route. The decision is now deferred to
`onCameraIdle` and made by target travel: under 60 m is a pinch (keep following,
adopt the zoom), over it is a pan (hand the camera to the driver).

## E. Not verified on the vehicle

These are implemented and reasoned about, but **no evidence from the unit
exists**. They are listed separately so the distinction survives in writing.

| behaviour | why it could not be verified |
|-----------|------------------------------|
| AVRCP reads the `MediaSessionCompat` metadata | needs the paired phone and the vendor stack |
| steering-wheel keys reach `MediaButtonReceiver` | needs the CANBUS injection on the real unit |
| `MEDIA_EJECT` vs `MEDIA_UNMOUNTED` per stick | the doc comment describes the observed firmware variation; it cannot be reproduced without the unit |
| Vietnamese TTS voice present | depends on what the ROM ships; the code handles absence but cannot report which case the unit is in |
| hardware decode for every listed codec | `AndroidCodecSupport` reports at runtime; no device was available to run it |
| GL surface survives an Activity recreate | argued in `ARCHITECTURE.md` §2; not observed |
| a two-finger pinch keeps the follow camera armed | `adb input` cannot produce a real multi-touch pinch, so only the **pan** branch of the pan-vs-pinch split was exercised; the pinch branch is reasoned from the target-distance threshold (`PAN_GESTURE_METRES = 60`) |

## F. Known-and-accepted rough edges

Recorded so they are not mistakes later:

- **Neither transfer set carries the unit tests.**
  `transfer/` is the older five-file set and `transfer_full/` the current
  seven-file one; both omit `src/test` and `src/androidTest` by design, so
  `gradlew test` on a transferred tree reports **0 tests**. 0 tests is not 376
  passing — the 30 suites / 376 tests in `BATCH1_SANDBOX_TEST_REPORT.md` §2 are
  the repository's, and only the repository can run them. The APK is unaffected:
  nothing under `src/test` is an input to `assembleDebug`, which is why a tree
  without the tests still builds to the same **58,686,466-byte /
  `B0A26B62…`** APK. A few of those tests are structural — they walk up to
  `settings.gradle.kts` and assert the *main* sources still match what the
  reports claim — so that guarantee does not travel with the tree.
  `TRANSFER.md` documents the set; the tests are off by `INCLUDE_TESTS = False`
  in `tools/transfer/gen_transfer_full.py`.
- **A transfer set must be regenerated after every source change.** Its payload
  and its hash check both go stale the moment anything under `app/`, `core/`,
  `docs/` or `tools/` changes. For this round `transfer_full/` was regenerated
  and verified from an empty directory: **315/315 files byte-identical to the
  repository** under an independent, source-derived check (not the driver's own
  hash table), and a build of the written tree produced the same
  **58,686,466-byte / `B0A26B62…`** APK. Re-run
  `python -I tools/transfer/gen_transfer_full.py` after any further source
  change, or the driver's `5/5 result` step will report a mismatch on a correct
  tree. That failure is the check working, not a broken build.
- **The 10.7 MB map fixture does not travel either.** `tools/testdata/basemap.mbtiles`
  is omitted by `INCLUDE_TESTDATA = False`. That is safe because nothing reads
  it — no Kotlin, Kotlin-DSL or Gradle file mentions `tools/testdata`, an APK
  built without it contains no matching entry, and `AStarRouterPackTest` (the
  only suite that opens a pack) reads `D:/Tools/_data/pack-test` outside the
  repository and skips itself when absent. It is also a byte-identical duplicate
  of `pack-test/basemap.mbtiles` and rebuildable with
  `tools/mapdata/build_map_pack.py`. Carrying it cost 14.3 MB of base64 — 64% of
  the payload — which is why the set went from 22.4 MB to 7.8 MB when it left.
- **Script 2 is seven files, not one.** The payload was split into a driver
  (`2-Create-Sources.ps1`) and six data parts (`2a`–`2f`, ~1.3 MB each) so no
  single file dominates. They share one scope through
  dot-sourcing, so the parts are not runnable on their own and all seven must
  travel together — the driver stops before writing anything if a part is
  missing.
- **The generator ships inside its own payload.** `tools/transfer/gen_transfer_full.py`
  is one of the 315 files, so the delivered set can rebuild itself and be
  re-checked later. The cost is that changing the generator changes the payload:
  it must be regenerated once more after any edit to itself, and the delivered
  copy is only current if the last regeneration happened after the last edit.
- **Two APK sizes appear in the record.** An incremental build of the repaired
  tree currently reports 58,756,310 bytes; a clean build reports **58,599,327**
  and is reproducible (three clean runs, same sha256 `5400FDA4…`).
  `TEST_REPORT.md` §1.0 explains why the incremental gap moves with the edit that
  caused it, and 58,599,327 is the number to compare against.
- **The same gap appears in Batch 1, with a number to quote.** Batch 1's clean
  build is **58,686,466 bytes / `B0A26B62…`**, reproducible across two
  `rm -rf app/build/outputs` + `assembleDebug` runs. An earlier draft of the same
  batch — before Parking's compact music card — clean-built to 58,686,344 bytes /
  `B1889A6C…`; that number is superseded. An incremental rebuild of the same
  source produced 59,813,007 bytes / `0077A8DF…` while this document was being
  written. **Compare clean builds only**; the incremental figure is an artefact
  of incremental packaging, not a different program.
- **Three separate "Cầu Rồng" POI rows** in search plus one street row. OSM has
  the bridge tagged more than once; results are not merged, because merging
  would have to pick one of four coordinate pairs and route the driver to a pin
  they did not choose.
- **The first search after a cold start is slower** than later ones — the FTS4
  index pages are being read from storage for the first time.
- **Lint is not clean of warnings.** Errors and fatals: zero. Warnings remain
  (`UnusedResources` ~40, `LogNotTimber` 16, `GradleDependency` 12,
  `DiscouragedApi` 9, `UseCompoundDrawables` 9, `HardcodedText` 9, `Overdraw` 8,
  `SetTextI18n` 6). `HardcodedText` is mostly `tools:text` previews and the
  Vietnamese strings that are deliberately inline in the parking clock format.
  They are not suppressed wholesale — an unused-resource warning is a real
  signal that a string drifted.
