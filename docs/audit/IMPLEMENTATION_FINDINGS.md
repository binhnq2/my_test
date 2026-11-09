# Implementation findings

Scope: a read-only audit of the production source. Every finding below is
grounded in a file/class/method/line that was opened and read, and every
citation was re-checked against the line on disk after the report was written.
Where the task's premise does not match the source, the finding says so and
the premise is refuted rather than "confirmed" to fit the question.

**No production source was modified. No code is repaired in this task.**

Severity is about runtime consequence, not about how wrong the design is:

| severity | meaning in this report |
|---|---|
| **P0** | the driver cannot complete the task at all, or the app crashes |
| **P1** | a headline feature is unreachable or silently does nothing |
| **P2** | a feature works but a control is obscured, mis-hit, or contradicts its docs |
| **P3** | cosmetic, dead code, stale comment, or a non-blocking inconsistency |
| **NONE** | the item was checked and the premise is false; recorded so the reviewer can see it was checked |

---

## Summary

| id | checklist item | verdict | severity |
|---|---|---|---|
| F-01 | dark flat theme vs a light-blue design | PARTIALLY_TRUE | P3 |
| F-02 | assets/gradients/tokens defined but unused | CONFIRMED | P3 |
| F-03 | Home repeats time/status over a system bar | REFUTED | NONE |
| F-04 | weather missing from its Home position | PARTIALLY_TRUE | P3 |
| F-05 | Music panel covers the Map | PARTIALLY_TRUE | **P2** |
| F-06 | Music panel in root layout and always visible | PARTIALLY_TRUE | P3 |
| F-07 | volume changes only demo state | **REFUTED** | NONE |
| F-08 | Music search auto-focuses and opens the keyboard | PARTIALLY_TRUE | P3 |
| F-09 | Online Music has providers but no UI entry | PARTIALLY_TRUE | P3 |
| F-10 | why the Map screen is white | PARTIALLY_TRUE | **P1** |
| F-11 | Map Management implemented but no navigation entry | **CONFIRMED** | **P0** |
| F-12 | Parking Mode does not change screen | PARTIALLY_TRUE | **P2** |
| F-13 | Voice depends entirely on Android SpeechRecognizer | CONFIRMED | **P2** |
| F-14 | confirmation applies to all voice commands | REFUTED | NONE |
| F-15 | unit tests PASS but no production wiring | CONFIRMED | P3 |

Three of the fifteen premises are false, and the report says so rather than
fitting the answer to the question: F-03 (the system status bar is hidden, not
duplicated), F-07 (the volume button really drives `AudioManager`), and F-14
(confirmation is by design restricted to the two destructive commands). Four
are confirmed: F-02 (design tokens defined and unused), F-11 (below), F-13
(voice has no fallback recognizer), and F-15 (tests cover unwired paths). The
other eight — F-01, F-04, F-05, F-06, F-08, F-09, F-10, F-12 — are partially
true: the symptom is real but the stated mechanism is not. One finding is the
most serious defect in the audit: F-11 — `MapManagementActivity` is not
declared in the manifest at all, so the only production entry point to map
management throws `ActivityNotFoundException`.

---

## F-01 — The UI is flat dark, but "light blue" is not the target design

**Severity: P3**

**Symptom.** Every launcher surface is a flat, solid-fill dark navy. Cards have
no gradient, no stroke and no shadow; there are no raster images anywhere in
`app/src` (0 png/jpg/webp). That part of the premise is true. What is false is
the idea that the design was light blue: `docs/UI_design.png` is a dark navy
mockup sheet, and the palette was sampled from it.

**Root cause.** The dark palette is deliberate and documented. `colors.xml`
states it in a comment, and the mockup's own background samples to near-black
navy. The only light colour family in the project is the *map tile* palette
(`StyleBuilder.Palette.DAY`, background `#e8e4dc`), which styles map geometry
and is reachable only through the map day/night setting — not app chrome. The
"flat" part is real and has three concrete causes: every page background is the
plain `@color/bg_root`; exactly one drawable in the whole tree contains a
`<gradient>`; and only two layouts set `android:elevation`.

**Evidence.**

| file | line | what it shows |
|---|---|---|
| `app/src/main/res/values/colors.xml` | 5 | `Dark theme is the product default (the head unit is always dark);` |
| `app/src/main/res/values/colors.xml` | 10 | `<color name="bg_root">#FF070B14</color>` |
| `app/src/main/res/values/themes.xml` | 9 | `<item name="android:windowBackground">@color/bg_root</item>` |
| `app/src/main/res/drawable/bg_card.xml` | 4 | `<solid android:color="@color/surface_card" />` — flat fill, no gradient |
| `app/src/main/res/drawable/bg_parking.xml` | 13 | `<gradient` — the only gradient in `res/` |
| `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/StyleBuilder.kt` | 517 | `background = "#e8e4dc",` — the DAY *map-tile* palette |

Verified counts: `grep -rc "<gradient" app/src/main/res` → one file, one hit.
`grep -rE "android:elevation=" app/src/main/res/layout` → 2. `find app/src
-name '*.png' -o -name '*.jpg' -o -name '*.webp'` → 0.

**Runtime impact.** Cosmetic. Nothing crashes and nothing misbehaves; the
launcher renders the flat dark design it was built to. The visible gap against
the mockup is the absence of album-art and parking photography and of depth on
cards — not a wrong colour family.

**Recommended repair boundary.** The drawable/token layer only, and only if
visual fidelity is wanted: add depth or a subtle gradient to `bg_card.xml` and
the page backgrounds, and supply real album-art and parking imagery (or
document the placeholders). Do not change the core palette in `colors.xml` or
`themes.xml`; it already matches the sampled sheet.

---

## F-02 — Ten resource declarations have no consumer

**Severity: P3**

**Symptom.** Resources are declared and referenced by nothing. Verified
repo-wide (excluding `build/`, `docs/audit/`, `transfer/`, `.work/`, and
comments):

| kind | unused | count |
|---|---|---|
| drawable | `ic_folder`, `ic_pin`, `ic_refresh`, `ic_route` | 4 of 68 |
| colour | `route_line`, `route_line_casing`, `nav_muted` | 3 of 29 |
| dimen | `scorebar_height`, `app_grid_spacing`, `text_huge` | 3 of 33 |
| style | `Text.Body`, `Card.Surface`, `QsTileIcon` | 3 of 13 |

**Root cause.** Icons come from a generator table (`tools/ui/gen_icons.py`) that
emits a fixed glyph set regardless of whether a screen consumes each glyph.
The route colour tokens were templated for map route drawing but never adopted —
the MapLibre style builds its route colours from `StyleBuilder.Palette` instead.
The dimens are leftovers from an earlier layout pass, and the three styles were
superseded by inline attributes (`view_qs_tile.xml` hard-codes the same 26dp
values that `QsTileIcon` declares).

**Evidence.**

| file | line | declaration |
|---|---|---|
| `app/src/main/res/values/colors.xml` | 33 | `<color name="route_line">#FF3B82F6</color>` |
| `app/src/main/res/values/colors.xml` | 34 | `<color name="route_line_casing">#FF0B3A66</color>` |
| `app/src/main/res/values/colors.xml` | 35 | `<color name="nav_muted">#FF9AA6B8</color>` |
| `app/src/main/res/values/dimens.xml` | 16 | `<dimen name="scorebar_height">44dp</dimen>` |
| `app/src/main/res/values/dimens.xml` | 40 | `<dimen name="app_grid_spacing">12dp</dimen>` |
| `app/src/main/res/values/dimens.xml` | 55 | `<dimen name="text_huge">64sp</dimen>` |
| `app/src/main/res/values/themes.xml` | 35 | `<style name="Text.Body" parent="">` |
| `app/src/main/res/values/themes.xml` | 45 | `<style name="Card.Surface" parent="">` |
| `app/src/main/res/values/themes.xml` | 131 | `<style name="QsTileIcon">` |

Counter-checks, run so the list is not a guess: `bg_circle_accent` **is** used
(`view_music_row.xml:96`), `ic_brightness` **is** used
(`activity_quick_settings.xml:124`), and `bg_parking` **is** used
(`activity_parking_mode.xml:19`). `colorPrimary`, `colorPrimaryDark` and
`colorAccent` also show zero `@color/` references, but they **are** consumed —
`themes.xml:5-7` maps them, which is the normal way to define framework theme
colours. They are excluded from the unused count above.

**Runtime impact.** None at runtime. The resources are compiled in and never
loaded. The risk is maintenance: a future change may assume `route_line` drives
the map route, and `docs/audit/FILE_INDEX.md` classifies the four unused icons
as "drawable used by a screen", which is what let them survive review.

**Recommended repair boundary.** Resource layer only: the ten declarations have
no consumers, so deletion cannot affect behaviour. Alternatively wire
`route_line`/`route_line_casing` into `StyleBuilder` if the map is meant to use
the palette tokens. Do not delete `bg_circle_accent`, `ic_brightness` or
`bg_parking`.

---

## F-03 — Home does not duplicate a system status bar; it replaces one

**Severity: NONE (premise refuted)**

**Symptom.** The premise says Home repeats time and status that the system bar
already shows. There is no system status bar to duplicate: every launcher screen
hides it and then draws its own status row, which is what the mockup sheet
shows.

**Root cause.** `BaseScreenActivity.onCreate` sets `SYSTEM_UI_FLAG_FULLSCREEN`
(plus the two LAYOUT flags) on the decor view, so Android's own bar is not
drawn. The app then draws a 44dp status row through `StatusBarView` — clock,
date, and four connectivity icons. This is replacement, and it is required:
without hiding the platform bar the panel would show two clocks. One genuine
redundancy does exist, but it is not a visual duplicate: the same clock TextViews
are written twice per resume.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt` | 94 | `window.decorView.systemUiVisibility =` |
| `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt` | 97 | `View.SYSTEM_UI_FLAG_FULLSCREEN` |
| `app/src/main/res/values/themes.xml` | 14 | `<item name="android:statusBarColor">@android:color/transparent</item>` |
| `app/src/main/res/layout/activity_home.xml` | 26 | `<com.ts8.launcher.ui.StatusBarView` |
| `app/src/main/java/com/ts8/launcher/ui/StatusBarView.kt` | 114 | `clock.text = ClockFormat.time()` |
| `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt` | 172 | `findViewById<TextView?>(R.id.clockText)?.text = ClockFormat.time()` |

**Runtime impact.** Home shows exactly one clock, one date and one icon
cluster. Two latent issues, neither a duplicate element: the immersive flags are
asserted once in `onCreate` and never re-asserted in `onWindowFocusChanged`, so
on API 23 a swipe can transiently reveal the system bar; and the clock/date
write at `BaseScreenActivity.kt:172-173` repeats what `StatusBarView.refreshClock()`
already does (same value, same view, harmless).

**Recommended repair boundary.** No fix for "duplication" — the premise is
false. If tightening is wanted it is out of this item's scope: re-assert the
flags on focus change, and drop the redundant write at 172-173.

---

## F-04 — The weather chip exists but is hard-hidden and never bound

**Severity: P3**

**Symptom.** No weather or temperature chip (the design shows `Đà Nẵng 28°C`)
is ever visible on Home or any other screen that uses `StatusBarView`.

**Root cause.** The chip is in the layout but is `visibility="gone"`, has
`android:text=""`, and is never resolved by Kotlin — `StatusBarView.init` binds
`clockText`, `dateText` and the four icons, and nothing else. There is no weather
provider in any of the 13 modules; a repo-wide search for weather/forecast/
temperature returns only layout comments and docs. Placement is *not* the
problem: the chip sits between the weighted `Space` and the icon row, so if it
were ever shown it would land on the right, immediately before the radio icons —
the intended position. The element is hidden by design, not misplaced.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/res/layout/view_status_bar.xml` | 55 | `android:id="@+id/weatherChip"` |
| `app/src/main/res/layout/view_status_bar.xml` | 61 | `android:visibility="gone"` |
| `app/src/main/res/layout/view_status_bar.xml` | 63 | `android:text="" />` |
| `app/src/main/java/com/ts8/launcher/ui/StatusBarView.kt` | 63 | `LayoutInflater.from(context).inflate(R.layout.view_status_bar, this, true)` |
| `app/src/main/java/com/ts8/launcher/ui/StatusBarView.kt` | 69 | `iconBattery = findViewById(R.id.iconBattery)` — the last binding in the init block |

**Runtime impact.** Zero. The chip is an empty, gone TextView consuming no
pixels and no bindings. Showing a temperature without a real source would have
fabricated data, which the campaign brief forbids.

**Recommended repair boundary.** Fabricating a value is not acceptable. Making
the chip appear requires a real weather source, a binding in `StatusBarView`, and
a refresh path; that is a feature, not a repair. Do not set `visibility` to
`visible` or seed a static string.

---

## F-05 — The Music card overlays the Map and covers the guidance controls

**Severity: P2**

**Symptom.** On the Map screen a 236dp music strip is painted over the right
edge of the map, and it is declared after `mapHost` so it draws above the map and
above the map's own right-edge controls. In exactly the states where it is
visible it obscures the mute / overview / end-guidance buttons.

**Root cause.** The overlap is real. `mapHost` is deliberately full-bleed — it
is constrained `start`→parent and `end`→parent with no end constraint to the
card — and the `MusicCardView` is a later sibling pinned `end`→parent. It
therefore covers the map's rightmost ~248dp (236dp + 12dp margin) for the full
height between status bar and bottom nav. `guidanceControls` is
`center_vertical|end` *inside* `mapHost`, so it sits geometrically beneath the
card. Home does not have this problem because there `mapHost`'s end is
constrained to the card's start.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/res/layout/activity_map.xml` | 39 | `android:id="@+id/mapHost"` |
| `app/src/main/res/layout/activity_map.xml` | 46 | `app:layout_constraintEnd_toEndOf="parent">` |
| `app/src/main/res/layout/activity_map.xml` | 481 | `<com.ts8.launcher.ui.MusicCardView` |
| `app/src/main/res/layout/activity_map.xml` | 483 | `android:layout_width="@dimen/music_card_width_compact"` |
| `app/src/main/res/layout/activity_map.xml` | 486 | `android:layout_marginEnd="@dimen/gap"` |
| `app/src/main/res/layout/activity_map.xml` | 491 | `app:layout_constraintEnd_toEndOf="parent"` |
| `app/src/main/res/layout/activity_map.xml` | 492 | `android:visibility="gone"` |
| `app/src/main/res/layout/activity_map.xml` | 275 | `android:layout_gravity="center_vertical\|end"` |
| `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt` | 549-550 | `musicCard.visibility = if (guiding \|\| ui.state == NavigationState.IDLE) View.VISIBLE else View.GONE` |
| `app/src/main/res/layout/activity_home.xml` | 49 | `app:layout_constraintEnd_toStartOf="@id/musicCard">` — Home's contrast |

**Runtime impact.** A visual and hit-test conflict with a safety-adjacent
control. While guiding, the card is drawn over the mute/overview/end buttons in
the right-centre band, so taps there hit the card instead of the controls. In
`IDLE` it covers the right end of the search field and the bottom-right browse
controls. No crash; the map still renders full-bleed beneath.

**Recommended repair boundary.** `activity_map.xml` only: constrain `mapHost`'s
end to the card's start (as Home does) or add an end margin matching the compact
width, and/or move the card out of the right-edge band. Do not change the shared
`MusicCardView` or `view_music_card.xml` — that would alter Home as well — and do
not remove the visibility logic in `MapActivity.render`.

---

## F-06 — The card is a root child on Home and Map, absent on Music

**Severity: P3**

**Symptom.** A `MusicCardView` is a root `ConstraintLayout` child on Home and
Map, but it is not always visible, and the Music screen does not use the
component at all. `view_music_card.xml` is never `<include>`d by any activity.

**Root cause.** `view_music_card.xml` is a bare `<merge>` inflated in code inside
`MusicCardView.init`; no activity layout includes it. Per screen:

- **Home** — declared with **no** `android:visibility` attribute and
  `HomeActivity` never writes `musicCard.visibility` (only `MapActivity` does),
  so it is present **and always visible**. It is load-bearing: the map's end
  constraint depends on it.
- **Map** — declared `android:visibility="gone"` and toggled in
  `MapActivity.render`, so present but conditionally visible.
- **Music** — `MusicFullscreenActivity` does not reference `MusicCardView` or
  `R.id.musicCard`; the screen builds its own full-size `nowPlayingPanel`.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/res/layout/view_music_card.xml` | 15 | `<merge xmlns:android="http://schemas.android.com/apk/res/android"` |
| `app/src/main/java/com/ts8/launcher/ui/MusicCardView.kt` | 85 | `LayoutInflater.from(context).inflate(R.layout.view_music_card, this, true)` |
| `app/src/main/res/layout/activity_home.xml` | 357 | `<com.ts8.launcher.ui.MusicCardView` |
| `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt` | 181 | `musicCard = findViewById(R.id.musicCard)` |
| `app/src/main/res/layout/activity_map.xml` | 492 | `android:visibility="gone"` |
| `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt` | 549 | `musicCard.visibility =` |
| `app/src/main/res/layout/activity_music_fullscreen.xml` | 50 | `android:id="@+id/nowPlayingPanel"` |

**Runtime impact.** No crash and no state-correctness issue. The consequence is
screen inconsistency: Home always reserves the right 300dp column, Map overlays
a 236dp card only while guiding or idle, Music shows no card. Any assumption
that one always-present card exists is wrong.

**Recommended repair boundary.** Per-screen and local. Do not centralise the
change in `MusicCardView` or `view_music_card.xml`. If Home's card is ever hidden
dynamically, its end constraint at `activity_home.xml:49` must change too or the
map will leave a 300dp void.

---

## F-07 — Volume is NOT demo state; it drives AudioManager directly

**Severity: NONE (premise refuted)**

**Symptom.** The premise says the volume buttons update only an in-app demo
variable and leave the device volume untouched.

**Root cause.** There is no in-memory or demo volume state anywhere in the
repository. `BottomNavView.stepVolume` reads the current level through the real
bridge, computes a target, and calls `setMediaVolume`. The only implementation of
`VehicleAudioBridge` in the tree is `AndroidVehicleAudioBridge`, which performs
the terminal call `audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
index.coerceIn(0, max), 0)`. `refreshVolume`
re-reads `getStreamVolume` rather than a cached value. `AppGraph` constructs the
concrete implementation directly, with no mock, no build flavour and no
substitution seam.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt` | 147 | `private fun stepVolume(delta: Int) {` |
| `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt` | 153 | `when (val r = bridge.setMediaVolume(target)) {` |
| `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt` | 163 | `volumeLabel.text = bridge.mediaVolume().toString()` |
| `app/src/main/java/com/ts8/launcher/di/AppGraph.kt` | 92 | `AndroidVehicleAudioBridge(appContext).also { bridge ->` |
| `core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/AndroidVehicleAudioBridge.kt` | 236 | `audioManager.setStreamVolume(` |
| `core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/AndroidVehicleAudioBridge.kt` | 248 | `override fun mediaVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)` |
| `app/src/main/AndroidManifest.xml` | 48 | `<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />` |

**Runtime impact.** The buttons change the real `STREAM_MUSIC` level and the
label shows the level read back from the OS. The caveat is **scope, not
fakery**: `setStreamVolume` targets `STREAM_MUSIC` only. If the head unit's
firmware owns master volume and ignores Android's stream level, the control will
look ineffective on that hardware — an OS/firmware boundary, not demo state.
Related honest limit: `pauseExternalSource` can never pause the radio because
`radioControllable` is hard-coded `false` (`AndroidVehicleAudioBridge.kt:50`),
which returns `Unsupported` rather than pretending.

**Recommended repair boundary.** No repair for the "demo-only" claim. If the
on-device symptom is real, the fix is a vendor/MCU bridge behind the existing
`VehicleAudioBridge` seam — one constructor change at `AppGraph.kt:91-95`, not
a UI change.

---

## F-08 — Nothing in the app focuses the Music search field

**Severity: P3**

**Symptom.** Opening Music fullscreen can leave the search `EditText` focused
with the soft keyboard up, as if the screen deliberately opened search.

**Root cause.** No app code requests focus and no attribute suppresses it. The
`musicSearch` EditText declares only layout, background, hint, colours,
`singleLine`, `imeOptions` and `inputType`. `MusicFullscreenActivity.onCreate`
never calls `requestFocus()` or `showSoftInput()`. The activity's manifest entry
has no `android:windowSoftInputMode` at all. Because the EditText is the only
`focusableInTouchMode` text field on the screen and the window uses the platform
default soft-input mode, Android's initial-focus rule can land on it and raise
the IME. The keyboard is a platform default that nothing suppresses, not an
explicit auto-focus. The one explicit `requestFocus` in the app is in
`SearchActivity` (the map-search screen), which pairs it with
`windowSoftInputMode="stateAlwaysVisible"` deliberately.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/res/layout/activity_music_fullscreen.xml` | 297-298 | `<EditText` / `android:id="@+id/musicSearch"` |
| `app/src/main/res/layout/activity_music_fullscreen.xml` | 311 | `android:inputType="text"` |
| `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt` | 140 | `setContentView(R.layout.activity_music_fullscreen)` |
| `app/src/main/AndroidManifest.xml` | 124-129 | the Music entry — four attributes, and **no** `android:windowSoftInputMode` among them |
| `app/src/main/AndroidManifest.xml` | 144 | `android:windowSoftInputMode="stateAlwaysVisible"` — Search's, by design |
| `app/src/main/java/com/ts8/launcher/ui/SearchActivity.kt` | 264 | `input.requestFocus()` |

Verified: `grep -n "requestFocus\|focusableInTouchMode\|focusable=" activity_music_fullscreen.xml`
→ 0 hits; the same grep over `MusicFullscreenActivity.kt` → 0 hits.

**Runtime impact.** If the keyboard appears it is the platform's initial-focus
rule, not a code path. The cost is a keyboard covering the library on a
1024x600 panel until dismissed. Search itself works either way, because
`wireSearch` listens for text changes regardless of focus. **Manual
confirmation on the device is still required** to know whether the keyboard
actually appears on this firmware — the source can only prove that nothing in the
app asks for it.

**Recommended repair boundary.** Declarative and local to this screen: add
`android:windowSoftInputMode="stateAlwaysHidden"` to the Music activity entry,
or `android:focusableInTouchMode="false"` on `musicSearch`. Do not copy
SearchActivity's `requestFocus` pattern — that screen wants the keyboard and
Music does not. Do not touch `BaseScreenActivity`, which never handles focus.

---

## F-09 — Online playback is reachable by search only; browsing is dead

**Severity: P3**

**Symptom.** The app ships three online providers but is said to have no UI
entry, so the driver cannot reach online playback.

**Root cause.** There **is** a reachable online playback path, but it is
search-only. `OnlineMusicRuntime` builds `RadioBrowserProvider`,
`PodcastRssProvider` and `NavidromeProvider` and exposes `search()`, `browse()`,
`status()` and a stream resolver. `SettingsActivity` only *configures* and
*reports* providers; it has no result list. The content entry is the single
search field on Music fullscreen: typing a query triggers `onlineMusic.search()`,
results are appended under the "Trực tuyến" heading, and tapping an online row
resolves the stream and calls `music.setQueue()`. The browsing half is dead:
`browse(category, limit, onDone)` has **zero** call sites in the app, and
`hasAnyProvider()` is never called either. There is therefore no radio-station
list, no trending/genre shelf, and no way to reach online content without
already knowing what to search for.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/java/com/ts8/launcher/runtime/OnlineMusicRuntime.kt` | 186 | `providers.add(RadioBrowserProvider())` |
| `app/src/main/java/com/ts8/launcher/runtime/OnlineMusicRuntime.kt` | 121 | `if (!settings.onlineEnabled) return null` |
| `app/src/main/java/com/ts8/launcher/runtime/OnlineMusicRuntime.kt` | 240 | `fun browse(` — definition only |
| `app/src/main/java/com/ts8/launcher/runtime/OnlineMusicRuntime.kt` | 498 | `fun hasAnyProvider(): Boolean = gateway() != null` — never called |
| `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt` | 500 | `graph.onlineMusic.search(query) { snapshot ->` |
| `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt` | 559 | `rows.add(MusicRowAdapter.headerRow(getString(R.string.music_search_online_group)))` |
| `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt` | 841 | `music.setQueue(listOf(track), 0)` |
| `app/src/main/java/com/ts8/launcher/ui/SettingsActivity.kt` | 203 | `private fun renderOnline() {` — configuration only |

Verified call-site search: `grep -rn "\.browse(" app/src core --include=*.kt`
returns only the definition's own dispatch (`OnlineMusicRuntime.kt:245`), the
gateway's internal loop and one test — no UI caller.

**Runtime impact.** A driver can reach online playback today: open Music, type a
song/podcast/station name, tap a row under "Trực tuyến". What is missing is
*discovery*: no station list, no shelves, no per-provider status on the music
screen. With online switched off, search returns an empty snapshot carrying the
"nhạc trực tuyến đang tắt trong Cài đặt" failure message — honest, not fake.

**Recommended repair boundary.** A UI addition, not a runtime rewrite: call
`OnlineMusicGateway.browse(BrowseCategory, limit)` (line 140) from an online
section or chips on the Music column and render its `SearchAggregate` the way
search results are rendered (`MusicRowAdapter.onlineRow` and `playOnline`
already handle playback). The providers, gateway, resolver and settings path
already work; only
the screen that turns `browse()` into rows is absent. Note also that
`music_tab_online` (`strings.xml:369`) is declared and referenced by nothing —
evidence that a persistent Online tab was planned and shipped as a search-only
feature. The same is true of `map_mgmt_entry` (`strings.xml:189`).

---

## F-10 — The Map can be blank with no message and no way out

**Severity: P1**

**Symptom.** The Map screen can render white: no map, no error text, no retry.
The no-pack case is handled; every other failure is silent.

**Root cause.** Four distinct failure modes, only one of which produces UI:

1. **No pack at all** — handled. `MapHost.create` sets `failure` from the
   load result and `MapActivity.wireMap` writes it into the placeholder.
2. **Invalid or corrupt pack** — silent. `AppGraph` validates asynchronously
   and stores a `Report` in `packReport`, but neither `MapHost` nor `MapActivity`
   ever reads `packReport()` or `isPackValidating()`. An invalid pack still
   attempts to render.
3. **MapLibre rejects the style** — silent. `setStyle` registers only the
   success callback; no `OnDidFailLoadingMapListener` is registered anywhere.
   `fail()` is reached only when `StyleBuilder.build` *throws*, which is a
   different case. `PackFailureCode.STYLE_LOAD_FAILED` exists and is **never
   used**.
4. **The placeholder is text-only** — the Map screen has no button at all,
   unlike Home, so even the handled case offers the driver no next step.

**Why the result is *white* and not black or grey.** This is the part of the
question the source answers directly. `StyleBuilder` always emits a background
layer (`add(background(p.background))`, line 161) before any tile source, and
the DAY palette's background is `#e8e4dc` (line 517) — near-white. `MapStyleMode`
defaults to `AUTO`, which resolves to DAY between 06:00 and 19:00
(`MapLibreMapController.kt:379`). So a style that loads successfully but whose
tile source yields nothing still paints a flat near-white canvas: the renderer
is working, the *data* is not. `PackValidator` is not a guard against this — by
the time a pack reaches rendering, discovery has only checked that
`basemap.mbtiles` exists (`MapPack.kt:180`), and validation runs asynchronously
afterwards into a report nobody reads.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/java/com/ts8/launcher/runtime/MapHost.kt` | 67-71 | `val result = graph.loadPack()` / `val pack = (result as? MapPack.LoadResult.Ok)?.pack` / `if (pack == null) {` / `failure = (result as? MapPack.LoadResult.Failure)?.reason` / `?: context.getString(R.string.home_map_unavailable)` |
| `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt` | 419-420 | `if (failure != null) {` / `mapPlaceholder.visibility = View.VISIBLE` |
| `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt` | 424 | `mapPlaceholder.visibility = View.GONE` — the else branch |
| `app/src/main/res/layout/activity_map.xml` | 50, 66, 75 | the placeholder is a `LinearLayout` + one `TextView`; no clickable child |
| `app/src/main/java/com/ts8/launcher/runtime/MapHost.kt` | 75-86 | the try/catch covers *constructing* the `MapView`, not style rejection |
| `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt` | 150 | `readyMap.setStyle(Style.Builder().fromJson(json)) { loaded ->` — success callback only |
| `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt` | 156 | `fail("Không dựng được style bản đồ: ${e.message}")` — only on a build exception |
| `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackDiagnostics.kt` | 56 | `STYLE_LOAD_FAILED,` — dead |
| `app/src/main/java/com/ts8/launcher/di/AppGraph.kt` | 271 | `fun packReport(): PackValidator.Report? = packReport` — read only by `MapManagementActivity.kt:153` |
| `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/StyleBuilder.kt` | 161 | `add(background(p.background))` — emitted unconditionally, before any tile source |
| `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/StyleBuilder.kt` | 517 | `background = "#e8e4dc",` — the DAY palette, near-white |
| `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt` | 379 | `return if (hour >= 19 \|\| hour < 6) MapStyleMode.NIGHT else MapStyleMode.DAY` — AUTO resolves to DAY by day |
| `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapPack.kt` | 180 | `private val REQUIRED = listOf("basemap.mbtiles")` — discovery checks existence only |
| `app/src/main/java/com/ts8/launcher/di/AppGraph.kt` | 298 | `PackValidator().validate(pack, PackDiagnostics())` — result lands in `packReport` |

Verified: `grep -rn "STYLE_LOAD_FAILED"` → one hit, the declaration.
`grep -rn "OnDidFailLoadingMapListener"` → 0 hits.

**Runtime impact.** A driver whose pack fails validation, or whose style is
rejected by the renderer, sees an empty map with no explanation and no control —
and the one screen that could fix it is unreachable (see F-11). On a head unit
whose pack is present but damaged (truncated `.mbtiles`, tiles outside the
declared bounds), the visible result is a near-white Navigation screen with no
roads and no message, indistinguishable from "this region has no data" without
`adb logcat`. Home shows the same blank canvas, because `MapHost` is shared.

**Recommended repair boundary.** Three small, separate changes: register a
style-failure listener in `MapLibreMapController` and route it through the
existing `fail()`/`onFailed()` seam; have `MapHost`/`MapActivity` read
`graph.packReport()` and show the validation reason; and give the Map
placeholder a CTA, as Home already has. Do not add a fallback map or a
fabricated style — the honest state is "no map, here is why".

---

## F-11 — Map Management has no working entry point and would crash if tapped

**Severity: P0**

**Symptom.** There is exactly one production `startActivity` to
`MapManagementActivity`, and it cannot succeed: the activity is **not declared
in the manifest**, so tapping the only button that reaches it throws
`ActivityNotFoundException` and takes Home down with it.

**Root cause.** `MapManagementActivity.kt` exists, is fully implemented (import,
validation, activation, removal, diagnostics), and is called from one place:
`HomeActivity.kt:216`, bound to the no-pack placeholder's import button. The
call is **not** wrapped in `try/catch` and does not check `resolveActivity`. The
manifest declares nine activities and this is not one of them — verified in the
source manifest, the merged manifest and the packaged APK manifest. The KDoc at
`MapManagementActivity.kt:59` claiming "opened from the map placeholder, Settings
or Quick Settings" is false: Settings only has a rescan button, Quick Settings
opens Settings, and the Map placeholder has no button at all.

The entry is doubly unreachable, which is the part worth recording precisely.
`btnPlaceholderImport` lives inside the `mapPlaceholder` container, which is
`android:visibility="gone"` in `activity_home.xml:67` and is only made
`View.VISIBLE` at `HomeActivity.kt:336` when `host.failure != null`. So the
single path to map management appears **only in the already-broken state** and
ends in a crash: a driver with no map gets a button that kills Home, and a driver
with a map never sees the button at all.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt` | 216 | `startActivity(MapManagementActivity.intentTo(this))` — unguarded |
| `app/src/main/java/com/ts8/launcher/ui/MapManagementActivity.kt` | 57 | `class MapManagementActivity : BaseScreenActivity() {` |
| `app/src/main/java/com/ts8/launcher/ui/MapManagementActivity.kt` | 59 | `/** Not a tab: opened from the map placeholder, Settings or Quick Settings. */` — false |
| `app/src/main/AndroidManifest.xml` | 107-179 | nine `<activity>` entries; `MapManagementActivity` absent |
| `app/build/intermediates/packaged_manifests/release/processReleaseManifestForPackage/AndroidManifest.xml` | — | `grep -c MapManagementActivity` → `0` |
| `app/src/main/java/com/ts8/launcher/ui/SettingsActivity.kt` | 93 | `findViewById<View>(R.id.btnRescanPack).setOnClickListener { rescanPack() }` — no navigation |
| `app/src/main/java/com/ts8/launcher/ui/QuickSettingsActivity.kt` | 363 | `Intent(this, SettingsActivity::class.java)` — Settings, not Map Management |
| `app/src/main/res/layout/activity_home.xml` | 67 | `android:visibility="gone"` — the `mapPlaceholder` container holding the only entry |
| `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt` | 336 | `mapPlaceholder.visibility = View.VISIBLE` — reached only when the pack already failed |

Manifest activity names actually declared: `HomeActivity`,
`MusicFullscreenActivity`, `MapActivity`, `SearchActivity`, `AppDrawerActivity`,
`QuickSettingsActivity`, `SettingsActivity`, `ParkingModeActivity`,
`ScreenOffActivity`.

**Runtime impact.** The entire pack-management feature — the only way to import
a map pack from a USB stick, validate it, activate it, or remove it — is
unreachable. Worse than unreachable: the one button that appears exactly when the
driver has no map kills the launcher. A driver with no map gets a button that
crashes Home, and a driver with a map never sees the button at all. This is the
root cause of F-10's "no way out".

**Recommended repair boundary.** One manifest entry is the blocking fix:

```xml
<activity
    android:name=".ui.MapManagementActivity"
    android:exported="false"
    android:screenOrientation="landscape"
    android:launchMode="singleTop" />
```

and, independently, guard the call at `HomeActivity.kt:216` so a future
misconfiguration degrades to a message instead of a crash. Persisting a real
entry point (Settings section or Map placeholder CTA, matching the dead
`map_mgmt_entry` label) is the follow-on, not part of the blocking repair.

---

## F-12 — Auto Parking cannot fire: the setting is off, has no setter, and two vehicle signals are never populated

**Severity: P2 — raised from P3 during the citation sweep.** Setter `PARKED`,
`showsParkingScreen` and the launch all exist, so nothing is *unreachable in
principle*; but on every shipping unit the automatic path is permanently off and
no screen in the app can turn it on. That is "a feature works but contradicts
its docs" — `docs/KNOWN_LIMITATIONS` describes auto-enter as a working fallback —
which is the P2 line, not the P3 "dead code / stale comment" line.

**Symptom.** Parking Mode is said not to change screen. Manual entry works;
automatic entry does not.

**Root cause.** Three independent blockers, and one refutation of the premise:

1. **The setting defaults to off and has no setter.** `parkingAutoEnter` reads
   `boolOr(KEY_PARKING_AUTO, false)` (`AppSettings.kt:385`), and
   `grep -rn "parkingAutoEnter *="` over `app/src` and `core` returns **zero**
   assignments — no preference screen, no code path. The strings
   `parking_auto` / `parking_auto_hint` (`strings.xml:392-393`) are declared and
   referenced by nothing. So on every device the value is `false` for the
   process lifetime.
2. **The vehicle signals are never populated.** `ParkingRuntime.evaluate()`
   constructs `ParkingSignals` without `vehicleGearPark`, `vehicleAccOn`,
   `vehicleParkingBrake` or `vehicleIgnitionOff`, so all four stay at their
   `null` default. The resolver's strongest automatic candidates can never be
   satisfied on real hardware — only the GPS heuristic remains.
3. **The premise is partly false.** When the setting *is* on and the GPS
   heuristic reaches `HIGH`, the screen *does* switch:
   `maybeOpenParkingScreen` starts `ParkingModeActivity` (line 261).

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt` | 385 | `get() = boolOr(KEY_PARKING_AUTO, false)` — the default; `grep -rn "parkingAutoEnter *="` → 0 assignments |
| `app/src/main/res/values/strings.xml` | 392-393 | `parking_auto` / `parking_auto_hint` — declared, referenced by nothing |
| `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt` | 191 | `val signals = ParkingSignals(` — the seven named arguments that follow (192-205) are `speedMps`, `speedAccuracyMeters`, `locationStable`, `navigationActive`, `callActive`, `reverseCameraActive`, `explicitManualRequest`, `elapsedRealtimeMs`; `vehicleGearPark`, `vehicleAccOn`, `vehicleParkingBrake` and `vehicleIgnitionOff` are absent |
| `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt` | 209 | `graph.parkingResolver.resolve(signals, settings.parkingAutoEnter)` |
| `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt` | 252 | `private fun maybeOpenParkingScreen(decision: ParkingDecision) {` |
| `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt` | 257 | `if (!decision.showsParkingScreen \|\| decision.reason == ParkingReason.MANUAL) return` — the gate above the launch |
| `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt` | 261 | `context.startActivity(` — line 263 adds `NEW_TASK or SINGLE_TOP`, the whole block wrapped in `runCatching` at 260 |
| `core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateResolver.kt` | 544 | `if (!allowAutoEnter) {` |
| `core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateResolver.kt` | 546 | `state = ParkingState.PARKING_CANDIDATE,` |
| `core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateResolver.kt` | 502, 507 | the ignition and gear branches, both unreachable from the runtime |
| `core/location-engine/src/main/java/com/ts8/core/parking/ParkingSignals.kt` | 34, 67 | both signals `= null` by default |
| `core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateResolver.kt` | 99-100 | `val showsParkingScreen: Boolean` / `get() = state == ParkingState.PARKED && confidence == ParkingConfidence.HIGH` |

Also verified: `grep -rn "vehicleGearPark\|vehicleIgnitionOff" app/src/main`
returns **nothing** — the only non-test references are inside the resolver and the
signals definition itself. Manual entry is wired and works:
`QuickSettingsActivity.kt:396` starts `ParkingModeActivity` directly.

**Runtime impact.** On real hardware, automatic parking entry cannot occur — not
even with the setting enabled, because no setter exists, and because the four
signals that would justify it are never supplied so the GPS heuristic alone must
reach `HIGH`. Manual entry via Quick Settings works. This is consistent with the
campaign rule that GPS speed alone is not sufficient evidence — but it means the
automatic branch of `ParkingStateResolver` is dead code in production, silently,
with no log line and no hint in Settings. The project's own
`docs/KNOWN_LIMITATIONS` (D8) describes auto-enter as a working fallback needing
20 s / 120 s of evidence, a claim that cannot hold while the setting is
permanently `false`.

**Recommended repair boundary.** Do not invent a signal source. The honest
options, in order: (a) leave auto-entry off and document that it requires a
vehicle bus; (b) if a real MCU/gear/ignition source exists in a later phase, feed
it into `ParkingSignals` at `ParkingRuntime.kt:191-205` — one construction, no
resolver change; (c) surface `parkingAutoEnter` in Settings, since the label and
hint strings already exist, so the switch stops being unreachable; (d) never make
`speed == 0` the sole AUTO condition, which the resolver currently already
respects by requiring dwell, stability and accuracy.

---

## F-13 — Voice Instant has no fallback; without Android speech it is unavailable, honestly

**Severity: P2 — raised from P3 during the citation sweep.** Under the scale
above, P2 is "a feature works but contradicts its docs". Voice Instant does not
merely *depend* on an external component: the class's own KDoc names the three
firmware variants on which it will not work, and states that the rest of Voice
Instant was written so as not to know which unit it is on. On those units the
headline feature is absent for the process lifetime with no degraded mode — the
P1 test ("a headline feature is unreachable") is not met because voice *is*
reachable on a unit that ships a recogniser, so P2 is the honest level.

**Symptom.** They ask whether Voice Instant depends entirely on Android
`SpeechRecognizer`. It does — and there is no alternative recognizer, no
fallback, and no text-input escape hatch.

**Root cause.** `SpeechRecognizerAdapter` is the one class in the app that
touches `android.speech.SpeechRecognizer`, and it gates on
`SpeechRecognizer.isRecognitionAvailable(context)`. If that returns `false`, or
`createSpeechRecognizer` returns `null`, it reports `VoiceError.SERVICE_UNAVAILABLE`
with a real reason string. There is no second engine: a repo-wide search for
Vosk, Whisper, PocketSphinx, on-device recognition or any bundled model returns
nothing. `EXTRA_PREFER_OFFLINE` is set, which asks the platform engine for its
offline mode — that is a preference to the same recognizer, not a different one.

The fallbacks that *could* exist do not:

| possible fallback | present? | evidence |
|---|---|---|
| a typed-command surface into `IntentParser` | no | `SearchActivity` has a text box, but it feeds place search, not `IntentParser` |
| a hardware voice key | no | `grep -rn "dispatchKeyEvent\|onKeyDown\|onKeyUp" app/src/main` → 0 hits |
| a bundled `android.speech.RecognitionService` | no | manifest line 81 declares `android.speech.RecognitionService` inside `<queries>` (visibility only) |
| an app-owned recognition service | no | the sole `<service>` is `MusicPlaybackService` (line 182) |
| retry after `SERVICE_UNAVAILABLE` | no, by design | `VoiceOverlayView.kt:139` retryable set excludes it |

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` | 14 | `* The one class in the app that touches \`android.speech.SpeechRecognizer\`.` — the leading `*` and backticks are source, not markup |
| `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` | 17-19 | `* and the reason is concrete on this unit: the TS8's vendor firmware may ship` / `* no recogniser at all, may ship one that only understands English, or may ship` / `* a Google app that is disabled by the ROM. All three are invisible from the` |
| `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` | 102 | `val probe = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }` |
| `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` | 127 | `"SpeechRecognizer.isRecognitionAvailable = false"` |
| `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` | 141 | `callback.onError(VoiceError.SERVICE_UNAVAILABLE, "createSpeechRecognizer = null")` |
| `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` | 214 | `Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {` |
| `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` | 234 | `putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)` |
| `app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt` | 80 | `private val adapter = SpeechRecognizerAdapter(appContext)` — the only adapter |
| `app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt` | 183 | `router.onRecognitionError(error)` |
| `app/src/main/java/com/ts8/launcher/ui/VoiceOverlayView.kt` | 139-143 | `retryable = state.state == VoiceState.ERROR && (NO_SPEECH \|\| NO_MATCH \|\| NOT_UNDERSTOOD \|\| COMMAND_FAILED)` — `SERVICE_UNAVAILABLE` excluded |
| `app/src/main/AndroidManifest.xml` | 81 | `<action android:name="android.speech.RecognitionService" />` — inside `<queries>`, not a `<service>` |
| `app/src/main/res/values/strings.xml` | 331 | `Voice service unavailable — thiết bị không có dịch vụ nhận dạng giọng nói` |

Verified: `grep -rn "Vosk\|whisper\|PocketSphinx\|createOnDevice"` → 0 hits;
`grep -rn "dispatchKeyEvent\|onKeyDown\|onKeyUp" app/src/main` → 0 hits;
`grep -n "<service" app/src/main/AndroidManifest.xml` → one hit, `MusicPlaybackService`.

**Runtime impact.** On a unit whose firmware ships no speech service, Voice
Instant reports `SERVICE_UNAVAILABLE` and does nothing else — which is the
correct, non-fabricating behaviour the brief requires, but it means the feature
is entirely dependent on a component the project does not ship. The parser,
router, executor and confirmation logic are all real and tested
(`IntentParserTest`, 27 tests); only the acoustic front end is external.

**Recommended repair boundary.** If a bundled recognizer is ever wanted it
belongs behind the existing `SpeechRecognizerAdapter` interface, not in the
router or executor. For this campaign, the honest deliverable is a settings line
stating the dependency, not a new engine.

---

## F-14 — Confirmation applies to two destructive commands, by design

**Severity: NONE (premise refuted)**

**Symptom.** They ask whether confirmation applies to all voice commands. It
does not, and the code says so explicitly.

**Root cause.** `needsConfirmation` is true exactly when `destructive` is true,
and `destructive` is true for exactly two of the fifteen commands: `NavigateTo`
and `PlayMusic` — the two that change what the vehicle or the cabin is doing.
`CommandRouter.process` confirms only when
`command.needsConfirmation && config.requireConfirmation` (default `true`).
Ambiguous input is *not* a command property; it fails into
`VoiceError.NOT_UNDERSTOOD` rather than prompting, because there is no command
object to confirm.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceCommand.kt` | 26-27 | `* **Confirmation policy.** [needsConfirmation] is true exactly when` / `* [destructive] is true. Genuinely ambiguous input is *not* a command property:` |
| `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceCommand.kt` | 64-65 | `override val destructive: Boolean = true` / `override val needsConfirmation: Boolean = true` — inside `NavigateTo` (`id = "navigate_to"`, line 63) |
| `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceCommand.kt` | 83-84 | `override val destructive: Boolean = true` / `override val needsConfirmation: Boolean = true` — inside `PlayMusic` (`id = "play_music"`, line 82) |
| `core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt` | 233 | `if (command.needsConfirmation && config.requireConfirmation) {` |
| `core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt` | 114 | `val requireConfirmation: Boolean = true` |

All fifteen commands and their confirmation flag: `NavigateTo` **true**,
`SearchPlace` false, `PlayMusic` **true**, `PauseMusic` false, `NextTrack` false,
`PreviousTrack` false, `VolumeUp` false, `VolumeDown` false, `VolumeTo` false,
`GoHome` false, `OpenMap` false, `OpenMusic` false, `OpenPhone` false, `OpenApp`
false, `Exit` false.

**Runtime impact.** None — it is the intended policy. The only consequence is
that a driver who says "mở nhạc" is asked to confirm, while "tắt nhạc" executes
immediately. Whether that is desirable product behaviour is a decision, not a
defect.

**Recommended repair boundary.** None. If the policy should change it is a data
change in `VoiceCommand.kt` (the flag per command) plus a test update, not a
router change.

---

## F-15 — 284 unit tests pass; several of them cover paths production never calls

**Severity: P3**

**Symptom.** They ask whether there are unit tests that pass while the
production wiring does not exist. Yes.

**Root cause.** The test suites are real and green, but a test proving a function
behaves does not prove a screen calls it. Five gaps of exactly this shape were
found, ordered by how much they mislead a reader:

1. **`restoreAfterProcessDeath()` is tested in detail and its return value is
   discarded in production.** `BaseScreenActivity.onCreate` calls it inside
   `runCatching {}` and ignores the result, then `onResume` overwrites the state
   with the resumed screen's `primaryState`. `AppStateMachine.state()` has no
   production caller at all.
2. **`ParkingScenarios` and `VoiceScenarios` are test-support objects** held in
   `src/main` and exercised by tests, referenced from production source only in
   KDoc.
3. **`RecordingOnlineProvider`** lives in `src/main` but is a test double; the
   production graph constructs the three real providers instead.
4. **Auto-parking is exercised end-to-end in `ParkingResolverTest`** with
   `vehicleGearPark`/`vehicleIgnitionOff` populated — inputs the production
   runtime never supplies (F-12).
5. **Four green XML reports have no test source in the tree.**
   `core/music-engine-local/build/test-results/testDebugUnitTest/` holds seven
   `TEST-*.xml` files, but `src/test/java/com/ts8/core/music/local/` holds three
   `.kt` files. `CheckpointCodecTest` (9), `CheckpointRestoreTest` (8),
   `ScanDiffTest` (11) and `TrackIdsTest` (4) all report `failures="0"` with the
   identical timestamp `2026-10-30T12:49:48` and no source anywhere in the tree.
   They are stale build artifacts. Their 32 tests are excluded from the totals
   below; a reader who summed the XML directory would report 316 and overstate
   coverage by 32.

**Evidence.**

| file | line | snippet |
|---|---|---|
| `app/src/test/java/com/ts8/launcher/runtime/AppStateMachineTest.kt` | 294-363 | several `restoreAfterProcessDeath()` assertions |
| `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt` | 91 | `runCatching { AppGraph.require().uiState.restoreAfterProcessDeath() }` — result discarded |
| `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt` | 169 | `uiState?.let { machine -> primaryState?.let { machine.moveTo(it) } }` — overwrites it |
| `core/location-engine/src/test/java/com/ts8/core/parking/ParkingResolverTest.kt` | 40-43 | `vehicleGearPark = gearPark,` / `vehicleAccOn = null,` / `vehicleParkingBrake = brake,` / `vehicleIgnitionOff = ignitionOff,` |
| `core/music-provider-online/src/main/java/com/ts8/core/music/online/RecordingOnlineProvider.kt` | 35 | `class RecordingOnlineProvider(` — in `src/main`, used by tests only |
| `core/music-engine-local/build/test-results/testDebugUnitTest/TEST-com.ts8.core.music.local.CheckpointCodecTest.xml` | 2 | `tests="9" skipped="0" failures="0" errors="0" timestamp="2026-10-30T12:49:48"` — no `CheckpointCodecTest.kt` exists |
| `core/music-engine-local/build/test-results/testDebugUnitTest/TEST-com.ts8.core.music.local.TrackIdsTest.xml` | 2 | `tests="4" skipped="0" failures="0" errors="0" timestamp="2026-10-30T12:49:48"` — no `TrackIdsTest.kt` exists |
| `app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt` | 263 | `fun restoredSnapshot(): VoiceStateSnapshot {` — no caller |
| `app/src/main/java/com/ts8/launcher/ui/VoiceOverlayView.kt` | 204 | `const val STATE_KEY: String = VoiceStateStore.KEY` — no reader |
| `app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt` | 292 | `enabled = true,` — `SpeedMonitor.Config.enabled` hardcoded, no preference |

Test totals, from `build/test-results/testDebugUnitTest/TEST-*.xml`,
de-duplicated across the debug/release variants and **restricted to suites whose
`src/test` source exists**:

| module | classes | tests |
|---|---|---|
| `app` | 2 | 32 |
| `core/location-engine` | 4 | 77 |
| `core/map-api` | 1 | 11 |
| `core/map-engine-maplibre` | 1 | 10 |
| `core/music-engine-local` | 3 | 65 |
| `core/music-provider-online` | 2 | 30 |
| `core/navigation-core` | 3 | 47 |
| `core/routing-engine-offline` | 1 | 12 |
| **total** | **17** | **284** |

284 tests, 0 failures, 0 errors, 0 skipped. The four stale reports in gap 5 would
add 4 classes and 32 tests if counted, which is why they are named here instead.

Two further tested-but-unreached paths, smaller than the five above:
`VoiceInstantController.restoredSnapshot()` and `VoiceOverlayView.STATE_KEY` make
`VoiceStateStore`'s save/restore round-trip dead on the read side (the overlay
always renders `controller.snapshot()`), so the CONFIRMING-degradation cases
`VoiceScenariosTest` proves never execute; and `SpeedPipelineTest`'s
master-switch case constructs `SpeedMonitor.Config(enabled = false)`, a value
production cannot produce because `AppSettings.speedConfig()` hardcodes `true`
at `AppSettings.kt:292` with no preference or UI behind it.

**Runtime impact.** None directly — green tests are not a defect. The impact is
on *review*: a reader who sees
`assertEquals(AppStateMachine.State.NAVIGATING, running.restoreAfterProcessDeath())`
reasonably assumes the app restores to `NAVIGATING`, and it does not. The tests
describe the library, not the launcher.

**Recommended repair boundary.** Either wire the behaviour or mark the gap. For
item 1 the cheap, honest fix is to *use* the returned state in `onCreate` (or
stop overwriting it in `onResume`) — but that changes navigation behaviour and is
out of scope here. For items 2 and 3, moving the doubles out of `src/main` would
make the distinction visible at compile time. For item 5, delete the four stale
reports or regenerate them; a `clean` build would resolve it on its own. Do not
delete tests to make the report cleaner.

---

## Cross-cutting: what this audit could not verify from source

Recorded so the reviewer knows the boundary of the evidence, not to excuse it:

| claim | why source cannot settle it |
|---|---|
| whether the soft keyboard actually appears on Music | platform focus rule + firmware IME behaviour; F-08 proves only that no app code asks for it |
| whether `setStreamVolume` moves the cabin volume on a TS8 | firmware/MCU ownership of master volume; F-07 proves the call is real, not that the firmware honours it |
| whether the map draws a first frame on the unit | depends on a valid pack and the GL driver; the source path is complete but the pack is a runtime asset |
| whether voice works on the unit | depends on a system speech service the project does not ship (F-13) |
| auto-parking on real hardware | requires vehicle signals the runtime never supplies (F-12) |

**No code was modified in this task.**
