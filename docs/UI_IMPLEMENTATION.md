# UI implementation

`docs/UI_design.png` is the source of truth. This document maps each of its
seven states to the Activity, layout and strings that implement it, and records
the places where the implementation deliberately differs from the picture.

## 0. The rules the whole UI obeys

| rule | where it is enforced |
|------|---------------------|
| Bottom navigation is the only navigation | `BaseScreenActivity.bindNavigation`; `BottomNavView` is part of every layout |
| No left menu, ever | nothing in `res/layout/` declares a drawer, `NavigationView`, or a left-anchored panel |
| Every screen shows the same status row and the same bottom bar | `BaseScreenActivity` sets both; a screen that forgot one cannot compile without noticing |
| Landscape, immersive | `android:screenOrientation="landscape"` on every Activity; `SYSTEM_UI_FLAG_FULLSCREEN` in the base class |
| Minimum 1024×600 | all widths are `0dp` with constraints or weights; no fixed full-screen dimension |

### Font and colour tokens

`res/values/colors.xml` holds the palette from the design (dark surfaces
`#070B14` – `#16223A`, accent `#3B82F6`, four text weights). `res/values/dimens.xml`
holds every spacing and type size. No layout hardcodes a colour and only the
two `tools:text` previews hardcode text.

## 1. Home — `HomeActivity` / `activity_home.xml`

Design: large map left/centre, music card right, status row top, bottom nav.

- `mapHost` is a `FrameLayout`; `MapHost` inserts the real `MapView` at index 0,
  so every overlay declared in XML stays above the map.
- Floating speed chip (bottom-left of the map) reads
  `ClockFormat.speed(fix.speedMps)` from the filtered GPS fix.
- Zoom / recentre / layers round buttons, 44 dp each, bottom-right of the map.
- `musicCard` is a `MusicCardView` bound to the same `PlaybackState` the music
  screen renders — one state object, two views.
- The map pane shows the pack's failure text instead of a drawn placeholder when
  there is no pack.

**Deviations from the design, and why**

| design | implementation | reason |
|--------|----------------|--------|
| `60` in the speed-limit roundel | roundel present but hidden | this pack tags `maxspeed` on ~6 % of highway ways; there is no per-road value to read. Printing 60 would fabricate a safety number. |
| `Đà Nẵng 28°C` weather chip | chip present, `visibility="gone"` | no weather provider in this campaign (the spec lists weather widgets as out of scope), and a fabricated temperature is exactly the mock data the spec forbids. |
| "Cầu Rồng" / "Đ. Nguyễn Văn Linh" labels baked into the picture | real labels from the pack's own tile data | the design's labels are illustrative; the map draws whatever the vector tiles contain. |

## 2 & 3. Navigation — `MapActivity` / `activity_map.xml`

Design states 2 (guiding) and 3 (music fullscreen) are two Activities, not one
screen with a mode. `MapActivity` is the navigating state; the design's
"full-screen music over the map" is simply the Music tab, which is a real
Activity on top of the map task.

- Maneuver card: icon, distance, instruction, "sau đó" arrow and next street —
  every field is a `NavigationProgress` property.
- ETA strip: `ClockFormat.duration`, `ClockFormat.distance`, arrival clock.
- Guidance controls (overview / mute / end) on the right; browse controls
  (recentre / zoom in / zoom out) replace them when not navigating.
- Destination card appears once a route is previewed, with "Đi" and "Huỷ".
- The speed-limit roundel is hidden for the same reason as Home.

## 4. Search — `SearchActivity` / `activity_search.xml`

Design: query field, result list left, map preview + destination card right.

- Query field with clear button and a microphone affordance the base has no
  backend for — it is hidden rather than drawn as a dead control.
- Debounced search via `PlaceSearchService` (250 ms), max 20 results, scored in
  SQL and Kotlin, never inside FTS.
- Each row: name, address, straight-line distance from the current fix.
- Right pane: name, address, category, coordinates, distance, and the two
  actions "Lộ trình" and "Lưu"/"Đã lưu".
- An empty query lists saved places (`SavedPlaces`), so the pane is never a dead
  half-screen.

**Deviation:** the design's right pane is a map image of the place. This
implementation shows text plus coordinates and hands the point to the real map
on "Lộ trình". A second `MapView` would mean a second GL surface and a second
style load on hardware that can barely afford one; the handoff gives the driver
the actual map one tap later, on the screen that already pays for it.

## 5. App drawer — `AppDrawerActivity` / `activity_app_drawer.xml`

- `AppCatalog` reads `PackageManager.queryIntentActivities` for `MAIN`/`LAUNCHER`
  (with the manifest's `<queries>` block so the same code works if `targetSdk`
  is ever raised).
- Grid columns adapt to width; icons come from each app's own `loadIcon`.
- Search field filters by label using `VietnameseText.normalize`, so `cai dat`
  finds "Cài đặt".
- Launch failure (missing activity, `SecurityException`, revoked permission)
  shows a message; it never crashes and never claims success.

## 6. Quick settings — `QuickSettingsActivity` / `activity_quick_settings.xml`

Design: Wi-Fi / Bluetooth / data / theme tiles, two sliders, four action
buttons.

| control | API used | works on this unit |
|---------|----------|-------------------|
| Wi-Fi | `WifiManager.setWifiEnabled` | yes — the boolean return is checked |
| Bluetooth | `BluetoothAdapter.enable/disable` | yes — the boolean return is checked |
| Mobile data | none available | tile shows "Mở cài đặt"; tap opens the system data panel |
| Day/night | `AppSettings.mapStyleMode` | yes — read by Home and Map on resume |
| Brightness | `Settings.System.SCREEN_BRIGHTNESS` + `WRITE_SETTINGS` | yes, after the grant, which the row offers |
| Volume | `VehicleAudioBridge.setMediaVolume` | yes |
| Screen off | black full-screen Activity | yes — **not** `PowerManager.goToSleep` |
| Restart | launcher task restart | yes — **not** `PowerManager.reboot` |
| Settings | `SettingsActivity` | yes |

`QsTileView` has four states: `ON`, `OFF`, `UNAVAILABLE` (dimmed, not
clickable) and `NEEDS_PERMISSION` (clickable, offers the grant path). There is no
state that looks active and does nothing.

## 7. Parking mode — `ParkingModeActivity` / `activity_parking_mode.xml`

- 96 sp clock and the date, updated from `ACTION_TIME_TICK` (once a minute, which
  is the display's resolution) rather than a `Handler` posting every second.
- Gradient backdrop `bg_parking.xml`, not a bundled photograph: substituting a
  drawn "mountain" for the design's licensed image would be a fake asset.
- The whole pane and the "Chạm để quay lại" chip both finish the screen.

## Settings — `SettingsActivity` / `activity_settings.xml`

Not one of the seven states. Reachable from Quick Settings; holds the map pack
in use (name, absolute path, attribution), the three permissions the launcher
actually uses, the version, and a "Tìm lại map pack" action that drops the cached
lookup so a newly inserted USB stick is found without a relaunch.

## The Phone item

The design's bottom bar has five items. Only four are screens. "Điện thoại"
resolves `Intent.ACTION_DIAL` — a real dialer — and when the unit has no dialer it
opens the app drawer rather than failing silently. There is no fifth Activity
because there is no fifth screen to show.

## Reaching Quick Settings

The design has no settings affordance on any of the seven screens. The status
row's radio icons (signal, Wi-Fi, Bluetooth, battery) are the entry point: they
are visible on every screen already, and tapping them opens Quick Settings. One
tap target, no new control the design does not draw.
