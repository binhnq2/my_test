# Runtime wiring

What the code does at run time, traced through the actual call chain. Not the
intended architecture: every claim below names a file, a symbol and a line, and
those lines were read from the tree that this bundle carries.

Conventions: paths are relative to the project root; lines are 1-indexed.
"Production-wired" means a real user action on the device reaches the code
without a test double in that path.

Summary of conclusions:

| flow | conclusion |
|---|---|
| 1. App launch → Home | PRODUCTION_WIRED |
| 2. Bottom tab Map → Map screen | PRODUCTION_WIRED |
| 3. Map pack → validation → activation → style → first frame | PARTIALLY_WIRED |
| 4. White map → empty/error state → Map Management | PARTIALLY_WIRED |
| 5. Map Settings / Map Management entry point | PARTIALLY_WIRED |
| 6. Bottom tab Music → Music screen | PRODUCTION_WIRED |
| 7. Music screen opening → search focus → keyboard | NOT_WIRED (the behaviour does not exist) |
| 8. Online Music entry → provider → playback service | PARTIALLY_WIRED |
| 9. Music panel visibility on Home / Map / Navigation | PRODUCTION_WIRED |
| 10. Volume button → AudioManager → system volume | PRODUCTION_WIRED |
| 11. Manual Parking action → resolver → Parking screen | PRODUCTION_WIRED |
| 12. Auto Parking signals → resolver → mode transition | BLOCKED |
| 13. Voice button → recogniser → parser → confirmation → execution | PARTIALLY_WIRED |
| 14. Back button and bottom navigation → screen restoration | PRODUCTION_WIRED |
| 15. Process recreation → Map / Music / Parking restoration | PARTIALLY_WIRED |

---

## FLOW 1 — App launch → Home screen

**Conclusion: PRODUCTION_WIRED**

**Entry point.** Android's launcher intent. `app/src/main/AndroidManifest.xml:106-122`
declares `HomeActivity` exported with two intent filters: `MAIN`+`LAUNCHER`
(lines 114-116) and `MAIN`+`HOME`+`DEFAULT` (lines 117-121). The second is what
makes the app a *candidate* in the system's "choose Home" dialog; the manifest
comment says the stock launcher is never removed and the driver opts in.

**Steps.**

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/Ts8LauncherApp.kt:16-20` | `onCreate` | constructs `AppGraph(this)` and calls `AppGraph.install(graph)` — the whole dependency graph, nothing else |
| 2 | `app/src/main/java/com/ts8/launcher/di/AppGraph.kt:91-92` | `audioBridge` | first lazy member reached; every engine is lazy so cold start does not pay for map, routing or a media scan |
| 3 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:91` | `onCreate` | `AppGraph.require().uiState.restoreAfterProcessDeath()` |
| 4 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:94-97` | `onCreate` | sets `SYSTEM_UI_FLAG_FULLSCREEN` on the decor view |
| 5 | `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt:166-168` | `onCreate` | `setContentView(R.layout.activity_home)`, then `bindNavigation()` |
| 6 | `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt:327-331` | `wireMap` | `MapHost(this, graph, container)` then `host.create(savedInstanceState)` |
| 7 | `app/src/main/java/com/ts8/launcher/runtime/MapHost.kt:67-72` | `create` | `graph.loadPack()`; on failure stores a message and returns — the map panel becomes a placeholder, not a crash |

**State updated.** `AppStateMachine` (`AppGraph.kt:680`) is the single state
holder; `restoreAfterProcessDeath` (`AppStateMachine.kt:223-229`) runs exactly
once per process and adopts the persisted state. `MapHost` holds the pack and
the `MapView` for this screen instance.

**Screen/component receiving state.** `HomeActivity` binds: speed cluster
(`HomeActivity.kt:188-195`), music card (line 181, bound at 153), nav strip
(253-288), map placeholder (336-361).

**Dependencies called.** `AppGraph.loadPack` → `MapPack.discover`
(`AppGraph.kt:181-186`); `MusicRuntime.get(this)` (`HomeActivity.kt:172`);
`SpeedViews.bind` with `SpeedUiState.EMPTY` (188-195) so both readouts start as
`--`.

**Error path.** Pack missing → `MapHost.create` returns early with
`failure` set (`MapHost.kt:69-72`) → `HomeActivity` shows the placeholder
(`HomeActivity.kt:336-337`) with an Import button that starts Map Management
(`HomeActivity.kt:214-218`). `MapView` construction throwing on a broken GL
driver is caught separately (`MapHost.kt:80-86`).

**Production-wired.** Yes — reachable through both the LAUNCHER and HOME
intent filters.

**Mock/demo/fallback.** No mock. The placeholder is a real degraded state, and
its Import button performs a real navigation.

---

## FLOW 2 — Bottom tab Map → Map screen

**Conclusion: PRODUCTION_WIRED**

**Entry point.** A tap on the Map item in the shared bottom bar.

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt:75-77` | constructor | `items.forEach { item.setOnClickListener { select(screen) } }` |
| 2 | `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt:98-101` | `select` | `setSelectedScreen(screen)` then `onNavigate?.invoke(screen)` |
| 3 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:123` | `bindNavigation` | `bar.onNavigate = { target -> navigateTo(target) }` |
| 4 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:183-191` | `navigateTo` | maps `Screen.MAP` → `MapActivity::class.java` |
| 5 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:192-203` | `navigateTo` | `startActivity(Intent(...).addFlags(REORDER_TO_FRONT or SINGLE_TOP))` then `ScreenTransition.apply(this)` |
| 6 | `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt:266-267` | `onCreate` | `super.onCreate` → base restores state and hides system bars |
| 7 | `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt:412-420` | `wireMap` | `MapHost(...)`, `host.create(saved)`, then reads `host.failure` |

**State updated.** The bar's `selected` (`BottomNavView.kt:50`, set at 84-94);
the map camera through `MapLibreMapController.moveCamera`
(`MapActivity.kt:434-438`).

**Screen/component receiving state.** `MapActivity` and its `FrameLayout
R.id.mapHost` (`MapActivity.kt:414`); the same `BottomNavView` inside
`activity_map.xml`, re-selected at `BaseScreenActivity.kt:122`.

**Dependencies called.** `AppGraph.loadPack` via `MapHost.create`;
`MapLibreRuntime.configure` (`MapLibreMapController.kt:127`).

**Error path.** Identical to FLOW 1's pack failure, plus a second one from the
renderer: `MapController.ReadyListener.onFailed` shows the same placeholder
(`MapActivity.kt:450-452`).

**Production-wired.** Yes — the bottom bar is on every tab screen and the
listener is set in the base class.

**Mock/demo/fallback.** No mock. `REORDER_TO_FRONT` is a deliberate performance
choice, not a stub: it keeps an existing `MapActivity` instance and therefore
its GL surface.

---

## FLOW 3 — Map pack → validation → activation → style → first frame

**Conclusion: PARTIALLY_WIRED**

The discovery, validation, activation and style pipeline all exist and are
reachable. What is **not** wired is the outcome of validation: a pack that
validation rejects is still activated, because activation never consults the
report.

**Entry point.** `MapHost.create` → `AppGraph.loadPack`.

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/di/AppGraph.kt:175-198` | `loadPack` | memoised; `MapPack.discover(roots, diagnostics, storageReadable, preferred = settings.activePackPath)` |
| 2 | `app/src/main/java/com/ts8/launcher/di/AppGraph.kt:189-195` | `loadPack` | on `Failure` logs code and reason; on `Ok` calls `scheduleValidation(pack, generation)` |
| 3 | `app/src/main/java/com/ts8/launcher/di/AppGraph.kt:292-298` | `scheduleValidation` | `PackValidator().validate(pack, PackDiagnostics())` off the main thread |
| 4 | `app/src/main/java/com/ts8/launcher/runtime/MapHost.kt:78-79` | `create` | `MapLibre.getInstance(context)`; `MapView(context, ...)` |
| 5 | `app/src/main/java/com/ts8/launcher/runtime/MapHost.kt:100-104` | `create` | `MapLibreMapController(context, pack, view)`, then `applyDisplayConfig` **before** the style loads |
| 6 | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt:121` | `attach` | `mapView.getMapAsync { onMapReady(it) }` |
| 7 | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt:127` | `onMapReady` | `MapLibreRuntime.configure(readyMap)` |
| 8 | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt:149-152` | `onMapReady` | `StyleBuilder.build(pack, resolved, density(), display)` → `readyMap.setStyle(Style.Builder().fromJson(json)) { onStyleLoaded(it) }` |
| 9 | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt:160-170` | `onStyleLoaded` | `addOverlayLayers(loaded)`; sets `ready = true` — this is the first-frame boundary |

**State updated.** `AppGraph.packResult` (memo, `AppGraph.kt:176`),
`AppGraph.packReport` (`AppGraph.kt:271`), `MapLibreMapController.ready`
(`MapLibreMapController.kt:170`).

**Screen/component receiving state.** The `MapView` inside `R.id.mapHost`.

**Dependencies called.** `PackDiagnostics`, `PackValidator`, `StyleBuilder`,
`MapLibreRuntime`, the native `MBTilesFileSource` named by
`StyleBuilder`'s absolute `mbtiles://` source
(`MapLibreMapController.kt:33-38`).

**Error path.** Style build throws → `fail("Không dựng được style bản đồ: ${e.message}")`
(`MapLibreMapController.kt:153-157`) → `ReadyListener.onFailed` →
placeholder. Overlay failure is swallowed separately so a broken route layer
does not take the basemap down (`MapLibreMapController.kt:163-169`).

**Production-wired.** Discovery and rendering: yes. Validation: **no**. The
report is produced and published, but `pack()` (`AppGraph.kt:261`) returns the
pack from `loadPack()` without reading `packReport`, and `MapHost.create` uses
`MapPack.LoadResult.Ok` directly (`MapHost.kt:68`).

**Mock/demo/fallback.** No mock. `RecordingOnlineProvider` is not involved
here.

**Gap.** `AppGraph.kt:261`:

```kotlin
fun pack(): MapPack? = (loadPack() as? MapPack.LoadResult.Ok)?.pack
```

`packReport` is never consulted by any activation path.

---

## FLOW 4 — White map → empty/error state → Map Management

**Conclusion: PARTIALLY_WIRED**

The error state exists on the Map screen, but it is text only: it has no
control that reaches Map Management.

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt:418-423` | `wireMap` | on `host.failure`: `mapPlaceholder.visibility = VISIBLE`, `mapPlaceholderText.text = failure`, **`return`** |
| 2 | `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt:450-452` | `onFailed` | renderer failure shows the same placeholder |
| 3 | `app/src/main/res/layout/activity_map.xml:49-74` | `mapPlaceholder` | contains one `ImageView` and one `TextView`; **no button** |

On Home the same situation offers two actions
(`app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt:214-222`):
`btnPlaceholderImport` → `MapManagementActivity.intentTo(this)`, and
`btnPlaceholderRetry` → `graph.invalidatePack(); rebuildMap()`.

**State updated.** `mapPlaceholder` visibility only.

**Consumer.** `R.id.mapPlaceholder` / `R.id.mapPlaceholderText`.

**Dependencies.** `AppGraph.loadPack`, `MapHost.failure`, `MapController.ReadyListener`.

**Error path.** The failure *is* the error path. When it fires, `wireMap` returns
before `host.withController { controller ->` (line 426) runs, so no camera, route
or vehicle marker is ever applied — the screen is a centred warning icon and a
sentence.

**Production-wired.** The error state is wired. The escalation to Map Management
is **not present on this screen**, and the one that does exist — Home's Import
button — cannot succeed (FLOW 5).

**Mock/demo/fallback.** No mock. The white surface itself is
`@color/surface_card` on the container (`activity_map.xml:43`) showing through
when no `MapView` was added.

**What produces the blank screen, and which of them this flow covers.** Four
distinct causes, one handled:

| cause | handled? | where |
|---|---|---|
| no pack at all | **yes** — placeholder text | `MapHost.kt:67-71` → `MapActivity.kt:419-421` |
| pack present but fails validation | **no** | `AppGraph` stores the report at `AppGraph.kt:306`; neither `MapHost` nor `MapActivity` reads `packReport()` |
| MapLibre rejects the style | **no** | `MapLibreMapController.kt:150` registers only the success callback; no `OnDidFailLoadingMapListener` exists; `PackFailureCode.STYLE_LOAD_FAILED` (`PackDiagnostics.kt:56`) is declared and never used |
| firmware GL failure constructing `MapView` | **yes** — same placeholder | `MapHost.kt:75-85` |

The second and third rows are the white screen with no message: the `MapView`
exists and drew nothing, or was never styled, and nothing in the app notices.

**Gap.** On a unit with no pack, the driver is told the map is unavailable and
given no way forward from the screen they are on. Home's placeholder does offer
two actions (`HomeActivity.kt:214-222`: `btnPlaceholderImport` and
`btnPlaceholderRetry`), but Import throws `ActivityNotFoundException` (FLOW 5),
so the only working action anywhere is Retry — which re-reads the same missing
pack.

---

## FLOW 5 — Map Settings / Map Management entry point

**Conclusion: NOT_WIRED**

There is exactly one production call site, it is conditional, and it cannot
succeed: `MapManagementActivity` is **not declared in the manifest**, so the
`Intent` does not resolve.

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt:214-218` | `wireMapPlaceholderActions` | `btnPlaceholderImport` → `startActivity(MapManagementActivity.intentTo(this))` + `ScreenTransition.apply(this)` |
| 2 | `app/src/main/java/com/ts8/launcher/ui/MapManagementActivity.kt:993-998` | `intentTo` | builds the Intent |
| 3 | `app/src/main/java/com/ts8/launcher/ui/MapManagementActivity.kt:57` | class | `MapManagementActivity : BaseScreenActivity()` |
| 4 | `app/src/main/AndroidManifest.xml:107-179` | — | nine `<activity>` declarations; **this is not one of them** |

`wireMapPlaceholderActions()` is called unconditionally from `onCreate`
(`HomeActivity.kt:201`), but the button it wires lives inside the placeholder,
which is `GONE` whenever a pack loads (`HomeActivity.kt:340`).

**State updated.** Nothing. The activity never runs, so the SharedPreferences
writes it would perform (`activePackPath` at `MapManagementActivity.kt:282`,
`:342`, `:457`) never happen.

**Consumer.** None — the screen cannot be created.

**Dependencies.** `MapManagementActivity.intentTo`; inside it, `AppGraph.listPacks`
(`AppGraph.kt:221-229`), `packDiagnostics` (`AppGraph.kt:211`). None of these are
reached.

**Error path.** `ActivityNotFoundException` at `HomeActivity.kt:216`. The call is
**not** wrapped in `try/catch` and does not call `resolveActivity`, so the
exception propagates out of the click listener and crashes Home. Verified absent
from three independent manifests: the source manifest, the merged manifest
(`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`)
and the packaged APK manifest
(`app/build/intermediates/packaged_manifests/release/processReleaseManifestForPackage/AndroidManifest.xml`,
where `grep -c MapManagementActivity` returns `0`).

**Production-wired.** No. Compiling is not wiring: the class exists, its layout
exists, and the button exists, but Android has no record of the component.

**Mock/demo/fallback.** No mock and no fallback screen. Settings' pack section is
read-only status plus a rescan button (`SettingsActivity.kt:141-183`), and the
Map tab's placeholder is text-only (`activity_map.xml:50/66/75`).

**What is reachable instead.** None of the map-management functions. A driver
with no pack sees the placeholder and a button that crashes Home; a driver with a
**wrong** pack — the case the screen exists to fix — sees no entry at all, because
the placeholder is gone and nothing else links there. The KDoc at
`MapManagementActivity.kt:59` ("opened from the map placeholder, Settings or Quick
Settings") is false on all three: Quick Settings opens Settings
(`QuickSettingsActivity.kt:363`), and Settings has only `rescanPack`
(`SettingsActivity.kt:93`, `:173`), which re-validates the *current* pack and
cannot import one.

**Related unused asset.** `app/src/main/res/values/strings.xml:189` defines
`map_mgmt_entry` = "Quản lý bản đồ &amp; cài đặt". No Kotlin or XML file
references it. A label for a persistent entry was written and never placed — it
is the intended entry point that this flow is missing.

---

## FLOW 6 — Bottom tab Music → Music screen

**Conclusion: PRODUCTION_WIRED**

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt:76-78` | constructor | tap → `select(screen)` |
| 2 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:188` | `navigateTo` | `Screen.MUSIC` → `MusicFullscreenActivity::class.java` |
| 3 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt:180-181` | `bindViews` | `libraryFooter`, `searchField` (`R.id.musicSearch`) |
| 4 | `app/src/main/java/com/ts8/launcher/runtime/MusicRuntime.kt:198-207` | `attach` | `MusicPlaybackService.start(appContext)`, then `bindService(intent, connection, BIND_AUTO_CREATE)` |
| 5 | `app/src/main/java/com/ts8/launcher/runtime/MusicRuntime.kt:249-253` | `play/pause/toggle/next/previous` | each is `MusicPlaybackService.send(appContext, ACTION_*)` |
| 6 | `app/src/main/java/com/ts8/launcher/service/MusicPlaybackService.kt:157-170` | `handleIntent` | dispatches the action to `backend().player` |
| 7 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt:187-190` | `onStart`/`onResume` | `music.attach(observer)`; `renderPlayback(music.state())` |

**State updated.** `LocalMusicBackend` / `PlaybackCore` state, published through
`MusicRuntime.Observer` (`MusicRuntime.kt:51`).

**Consumer.** `MusicFullscreenActivity`'s player columns and `libraryList`.

**Dependencies.** `MusicPlaybackService` (declared
`AndroidManifest.xml:182-186` with `foregroundServiceType="mediaPlayback"`);
`MediaSession` built at `MusicPlaybackService.kt:228-254`.

**Error path.** `bindService` refused → `Log.w(TAG, "bindService refused; music
UI will stay empty")` (`MusicRuntime.kt:203-207`). Playback errors are
published at `MusicPlaybackService.kt:274`.

**Production-wired.** Yes.

**Mock/demo/fallback.** No mock in this path.

---

## FLOW 7 — Music screen opening → search focus → keyboard

**Conclusion: NOT_WIRED — the behaviour does not exist**

The claim under review is that the Music screen auto-focuses its search field
and opens the keyboard. In this source it does not.

| # | file:line | evidence |
|---|---|---|
| 1 | `app/src/main/res/layout/activity_music_fullscreen.xml:301-315` | the `EditText R.id.musicSearch` declares **no** `android:focusable`, no `android:focusableInTouchMode`, no `android:requestFocus` |
| 2 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt:181` | the only reference to the field in `onCreate`: `searchField = findViewById(R.id.musicSearch)` |
| 3 | `app/src/main/AndroidManifest.xml:124-129` | `MusicFullscreenActivity` has **no** `windowSoftInputMode` |
| 4 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt` | no `requestFocus()`, no `InputMethodManager`, no `showSoftInput` anywhere in the file |

By contrast, the *search* screen does exactly this, deliberately:

| # | file:line | evidence |
|---|---|---|
| 5 | `app/src/main/AndroidManifest.xml:141-146` | `SearchActivity` `android:windowSoftInputMode="stateAlwaysVisible"` |
| 6 | `app/src/main/java/com/ts8/launcher/ui/SearchActivity.kt:262-264` | `input.requestFocus()` with the comment "search is the only thing this screen can do" |

**State updated.** None.

**Consumer.** N/A.

**Dependencies.** N/A.

**Error path.** N/A.

**Production-wired.** No — there is nothing to wire.

**Mock/demo/fallback.** N/A.

**If the symptom is real on a unit**, it does not come from this source: an
`EditText` that is the first focusable view in a newly shown window receives
focus from the platform itself, and `stateAlwaysVisible` on another activity
does not leak into this one. That is a platform default to look at on the
device, not a line in this tree.

---

## FLOW 8 — Online Music entry → provider → playback service

**Conclusion: PARTIALLY_WIRED**

Providers, gateway and playback all exist and are constructed in production. The
entry point is a **search field**, not a browse screen; and the online tab label
that the UI would need is defined but unused.

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/runtime/OnlineMusicRuntime.kt:199` | `gateway` | `OnlineMusicGateway(providers, clock = { SystemClock.elapsedRealtime() })` |
| 2 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt:495-502` | `runOnlineSearch` | `graph.onlineMusic.search(query) { snapshot ->` / `if (!searching \|\| generation != loadGeneration) return@search` — a stale-snapshot guard, so a late result cannot overwrite a newer one |
| 3 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt:547-560` | `renderSearch` | online rows appended under a group header |
| 4 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt:793-797` | row click | `onlineRows[row.key]` → `playOnline(online)` |
| 5 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt:826-843` | `playOnline` | `runtime.toTrack(online)` on the IO pool, then `music.setQueue(listOf(track), 0)` |
| 6 | `app/src/main/java/com/ts8/launcher/service/MusicPlaybackService.kt:157-170` | `handleIntent` | the queue lands in the same player as local playback |

**State updated.** `onlineRows` (`MusicFullscreenActivity.kt:98`); the playback
queue via `MusicRuntime.setQueue`.

**Consumer.** `renderSearch` and `libraryList`.

**Dependencies.** `OnlineMusicGateway`, `NavidromeProvider`,
`RadioBrowserProvider`, `PodcastRssProvider`, `CircuitBreaker`, `HttpSupport`.

**Error path.** `toTrack` returns `null` → `libraryFooter` shows
`music_stream_unavailable` (`MusicFullscreenActivity.kt:833-841`). A provider
with no configuration reports `BLOCKED_EXTERNAL_CONFIG`; that string exists
(`strings.xml:375`, `music_online_blocked`) and is **not referenced** by any
screen.

**Production-wired.** Partially: search + play is wired. Browsing a provider's
catalogue without typing a query is not.

**Mock/demo/fallback.** `RecordingOnlineProvider`
(`core/music-provider-online/src/main/java/com/ts8/core/music/online/RecordingOnlineProvider.kt:35`)
is a test double. It is referenced only from
`core/music-provider-online/src/test/java/com/ts8/core/music/online/OnlineMusicGatewayTest.kt`.
It is not in the production graph.

**Unused UI.** `strings.xml:369` defines `music_tab_online` = "Trực tuyến".
Nothing references it; the library tabs are playlist/songs/albums/artists
(`activity_music_fullscreen.xml:340-397`). Online content appears only as
search results.

---

## FLOW 9 — Music panel visibility on Home, Navigation and Map

**Conclusion: PRODUCTION_WIRED (with an overlap finding)**

| # | file:line | evidence |
|---|---|---|
| 1 | `app/src/main/res/layout/view_music_card.xml:15` | the card layout is a `<merge>`, inflated in code — no activity uses `<include>` |
| 2 | `app/src/main/java/com/ts8/launcher/ui/MusicCardView.kt:85` | `LayoutInflater.from(context).inflate(R.layout.view_music_card, this, true)` |
| 3 | `app/src/main/java/com/ts8/launcher/ui/MusicCardView.kt:103-104` | reads `R.styleable.MusicCardView_musicCompact` |
| 4 | `app/src/main/res/layout/activity_home.xml:356-366` | full card, `music_card_width` (300dp), **no** `android:visibility` — always visible |
| 5 | `app/src/main/res/layout/activity_home.xml:49` | `app:layout_constraintEnd_toStartOf="@id/musicCard"` — **the map is constrained to stop where the card starts** |
| 6 | `app/src/main/res/layout/activity_map.xml:480-493` | compact card, `music_card_width_compact` (236dp), `app:musicCompact="true"`, `android:visibility="gone"` in XML |
| 7 | `app/src/main/res/layout/activity_map.xml:46` | `app:layout_constraintEnd_toEndOf="parent"` — the map is **full-bleed** and runs underneath the card |
| 8 | `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt:549-550` | the toggle: `VISIBLE` iff `guiding \|\| ui.state == NavigationState.IDLE`, else `GONE` |
| 9 | `app/src/main/res/layout/activity_map.xml:275` | `guidanceControls` sits at `center_vertical\|end` **inside** `mapHost` — geometrically beneath the card |
| 10 | `app/src/main/res/layout/activity_music_fullscreen.xml:50` | the Music screen uses its own `nowPlayingPanel`; there is no `MusicCardView` there |

**State updated.** `musicCard.visibility`; the card's bind from `PlaybackState`
(`MapActivity.kt:253-254`).

**Consumer.** `R.id.musicCard` in `activity_home.xml` and `activity_map.xml`.

**Dependencies.** `MusicRuntime` observer, `MusicCardView.bind`.

**Error path.** None; the card renders without a track.

**Production-wired.** Yes on Home (unconditional) and on Map (conditional).

**Mock/demo/fallback.** No mock.

**Overlap, per screen.**

- **Home** — no overlap. `mapHost` ends at `musicCard`'s start
  (`activity_home.xml:49`), so the card occupies a 300dp column the map never
  enters.
- **Map** — real overlap. `mapHost` spans the full width
  (`activity_map.xml:42-46`) and the card is a later sibling pinned to the
  parent's end (`activity_map.xml:486-491`), so it paints over the map's right
  ~248dp (236dp width + 12dp margin) for the full height between the status bar
  and the bottom bar. In exactly the states where it is `VISIBLE`
  (`MapActivity.kt:550`), that band also contains `guidanceControls`
  (`activity_map.xml:275`) — the mute / overview / end-guidance buttons — which
  therefore sit beneath the card and cannot be tapped while guiding.
- **Music** — no card at all; the screen has its own full-size panel.

---

## FLOW 10 — Volume button → Android AudioManager → system volume

**Conclusion: PRODUCTION_WIRED**

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt:80-81` | constructor | `volDown` → `stepVolume(-1)`, `volUp` → `stepVolume(+1)` |
| 2 | `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt:147-158` | `stepVolume` | `AppGraph.current?.audioBridge`; `bridge.mediaVolume()`; `bridge.setMediaVolume(target)` |
| 3 | `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt:161-164` | `refreshVolume` | `volumeLabel.text = bridge.mediaVolume().toString()` |
| 4 | `app/src/main/java/com/ts8/launcher/di/AppGraph.kt:91-92` | `audioBridge` | `AndroidVehicleAudioBridge(appContext)` |
| 5 | `core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/AndroidVehicleAudioBridge.kt:234-246` | `setMediaVolume` | `audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, max), 0)` |
| 6 | `core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/AndroidVehicleAudioBridge.kt:248` | `mediaVolume` | `audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)` |

**State updated.** The Android media stream itself. Nothing is cached: the label
is re-read from the stream after every change (line 154).

**Consumer.** `R.id.volLabel` in `view_bottom_nav.xml`, present on every tab
screen.

**Dependencies.** `AudioManager` (`STREAM_MUSIC`).

**Error path.** `SecurityException` → `AudioResult.Unsupported`; any other throw →
`AudioResult.Failed` (`AndroidVehicleAudioBridge.kt:242-246`). Both still call
`refreshVolume()` (`BottomNavView.kt:154`), so a refused change is visible as the
label not moving.

**Production-wired.** Yes.

**Mock/demo/fallback.** **There is no mock bridge.** `VehicleAudioBridge` has
exactly one implementation in the tree —
`AndroidVehicleAudioBridge` (`grep` over all `.kt` finds no other `: VehicleAudioBridge`).
`AppGraph.audioBridge` is the only production source.

---

## FLOW 11 — Manual Parking action → resolver → Parking screen

**Conclusion: PRODUCTION_WIRED**

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/QuickSettingsActivity.kt:395-396` | button handler | `parking.requestEnter()`, then `startActivity(Intent(this, ParkingModeActivity::class.java))` |
| 2 | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt:130-135` | `requestEnter` | `pendingManual = true`; `autoOpened = false`; evaluates on the next tick |
| 3 | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt:183-209` | `evaluate` | builds `ParkingSignals(` (line 191) with `explicitManualRequest = manual` at line 203, then calls `graph.parkingResolver.resolve(signals, settings.parkingAutoEnter)` at line 209 |
| 4 | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt:252-269` | `maybeOpenParkingScreen` | starts `ParkingModeActivity` when the decision says so |
| 5 | `app/src/main/AndroidManifest.xml:165-171` | manifest | `ParkingModeActivity` `exported="false"`, landscape |

**State updated.** `ParkingDecision` published through
`ParkingRuntime.Listener` (`ParkingRuntime.kt:225`) and persisted via
`graph.parkingStore.save(decision)` (238).

**Consumer.** `ParkingModeActivity`; any registered listener (Quick Settings
refreshes its button at `QuickSettingsActivity.kt:400`).

**Dependencies.** `ParkingStateResolver`, `ParkingStateStore`, `location.lastFix`.

**Error path.** `startActivity` failure is logged
(`ParkingRuntime.kt:266-268`). A resolver throw degrades to
`ParkingDecision.UNKNOWN` (`ParkingRuntime.kt:210-215`) rather than a stale
screen.

**Production-wired.** Yes. Note the manual path starts the activity **directly**
from the button, so it does not depend on the resolver agreeing; the resolver
call is what keeps the persisted state and the auto-open latch consistent.

**Mock/demo/fallback.** No mock. `ParkingScenarios` (the `ignitionOffParks` and
similar functions) is a JVM scenario suite under `src/main`, referenced only
from `core/location-engine/src/test/.../ParkingScenariosTest.kt`.

---

## FLOW 12 — Auto Parking signals → resolver → mode transition

**Conclusion: BLOCKED**

The chain is complete and correct, and it cannot fire on a stock unit, for two
independent reasons that both have to be removed.

**Chain.**

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt:181-206` | `evaluate` | builds `ParkingSignals` from `graph.location.lastFix`, `stability.verdict(now)`, `navigationActive`, `callActive` |
| 2 | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt:191-206` | `evaluate` | `reverseCameraActive = false` — a constant |
| 3 | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingSignals.kt:34` | `vehicleGearPark` | declared `Boolean? = null` |
| 4 | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingSignals.kt:67` | `vehicleIgnitionOff` | declared `Boolean? = null` |
| 4a | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingSignals.kt:46` | `vehicleAccOn` | declared `Boolean? = null`, never supplied either |
| 4b | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingSignals.kt:57` | `vehicleParkingBrake` | declared `Boolean? = null`, never supplied either |
| 5 | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt:209` | `evaluate` | `graph.parkingResolver.resolve(signals, settings.parkingAutoEnter)` |
| 6 | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateResolver.kt:99-100` | `showsParkingScreen` | `state == PARKED && confidence == HIGH` |
| 7 | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt:252-259` | `maybeOpenParkingScreen` | requires `PARKED`, `showsParkingScreen`, `reason != MANUAL`, latch clear |

**Blocker 1 — the vehicle signals are never populated.**
`ParkingRuntime.evaluate` constructs `ParkingSignals` at
`ParkingRuntime.kt:191-206` with seven named arguments, none of which is a
vehicle bus signal. **All four** vehicle fields stay at their `null` default:

| field | line in `ParkingSignals.kt` | supplied by `ParkingRuntime`? |
|---|---|---|
| `vehicleGearPark` | 34 | no |
| `vehicleAccOn` | 46 | no |
| `vehicleParkingBrake` | 57 | no |
| `vehicleIgnitionOff` | 67 | no |

In this resolver's vocabulary `null` means *not measured*, not *false* — the
field's own KDoc draws that distinction explicitly (lines 28-32). A tree-wide
grep finds no production assignment to any of the four; the only writes are in
`ParkingScenariosTest` and `ParkingResolverTest`. There is no CAN adapter, no
`CarPropertyManager`, and no vendor signal receiver anywhere in the project, so
`vehicleEvidence()` (`ParkingStateResolver.kt:381-384`) always returns `null` and
control falls through to the GPS heuristic alone.

**Blocker 2 — auto-entry is off, and nothing can turn it on.**
`app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt:385`:

```kotlin
    get() = boolOr(KEY_PARKING_AUTO, false)
```

and `ParkingRuntime.evaluate` passes it straight to the resolver
(`ParkingRuntime.kt:209`), which uses it to decide whether `PARKED` may be
reported at all. With the default, the best the launcher can reach from GPS
alone is `PARKED` at lower confidence, which `showsParkingScreen`
(`ParkingStateResolver.kt:100`) then refuses.

This is stronger than "off by default": `grep -rn "parkingAutoEnter *="` over
`app/src` and `core` returns **zero** assignments, and the label/hint strings
that would back a switch (`parking_auto`, `parking_auto_hint`,
`strings.xml:392-393`) are referenced by nothing. There is no preference screen,
no developer toggle and no code path that writes the value, so the automatic
branch of `ParkingStateResolver` is dead code for the lifetime of every install.

**State updated.** `ParkingDecision`, `parkingStore`.

**Consumer.** `maybeOpenParkingScreen`, listeners.

**Error path.** See FLOW 11.

**Production-wired.** The plumbing is wired; the **input** is not. On the
hardware this campaign targets, the driver must press the Quick Settings button.

**Mock/demo/fallback.** `reverseCameraActive = false`
(`ParkingRuntime.kt:203`) is a hard-coded absence, honest but not a measurement.

---

## FLOW 13 — Voice button → recogniser → parser → confirmation → execution

**Conclusion: PARTIALLY_WIRED**

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:133` | `startVoice` | the single entry point every screen's microphone uses |
| 2 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:112-115` | `onContentChanged` | `voice.install()` — the bar is added to every screen without each screen remembering |
| 3 | `app/src/main/java/com/ts8/launcher/runtime/VoiceTrigger.kt:97-98` | permission callback | `grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED \|\| hasPermission()` |
| 4 | `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt:102-127` | `start` | `SpeechRecognizer.isRecognitionAvailable(context)`; if false → `VoiceError.SERVICE_UNAVAILABLE` with the detail string |
| 5 | `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt:141-150` | `start` | `createSpeechRecognizer` returning null, or throwing, is the same error |
| 6 | `app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt:82-85` | field | `private val router = VoiceRouter(` / `executor = CommandExecutor { command -> executor.execute(command) },` / `clock = { SystemClock.elapsedRealtime() }` / `)` — no `config`, so `requireConfirmation` takes its default |
| 7 | `core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt:233-238` | `VoiceRouter.onTranscript` | `if (command.needsConfirmation && config.requireConfirmation) {` / `pending = command` / `enter(VoiceState.CONFIRMING)` / `speak(VietnameseVoicePrompts.confirm(command))` / `} else {` / `execute(command, alreadyConfirmed = false)` |
| 8 | `core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt:268-281` | `confirm` | the driver's yes/no; `execute(command, alreadyConfirmed = true)` |
| 9 | `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceCommand.kt:65, 84` | `NavigateTo`, `PlayMusic` | `needsConfirmation = true` |
| 10 | `app/src/main/java/com/ts8/launcher/runtime/VoiceCommandExecutor.kt` | executor | the app-side `CommandExecutor` |

**State updated.** `VoiceStateStore` / `VoiceState` through `VoiceRouter`.

**Consumer.** `VoiceOverlayView` installed by `VoiceOverlayHost`.

**Dependencies.** `android.speech.SpeechRecognizer` (behind
`SpeechRecognizerAdapter`), `IntentParser`, `VoiceRouter`,
`VietnameseVoicePrompts`, `VoiceFeedback` (`android.speech.tts.TextToSpeech`,
`VoiceFeedback.kt:6, 77`), `VehicleAudioBridge.duck` for music ducking
(`VoiceFeedback.kt:19, 43`).

**Error path.** `SERVICE_UNAVAILABLE` on: recogniser absent (`isRecognitionAvailable`
false), `createSpeechRecognizer` null, a throw during start, and the mapped
platform errors `ERROR_CLIENT`, `ERROR_SERVER`, `ERROR_SERVER_DISCONNECTED`,
`ERROR_RECOGNIZER_BUSY`, `ERROR_NETWORK`, `ERROR_NETWORK_TIMEOUT`, and codes
12/13 (`SpeechRecognizerAdapter.kt:323-343`). The overlay shows
"voice unavailable" (`VoiceInstantController.kt:136-138`).

**Production-wired.** The voice path is wired end to end. Confirmation is wired
for exactly the destructive commands.

**Mock/demo/fallback.** **There is no fallback recogniser.** Every recognition
path goes through `android.speech.SpeechRecognizer`. There is no text-entry
fallback, no bundled offline engine, and no alternative backend. Toggling TTS
off does not disable voice — `VoiceFeedback` degrades — but toggling the
recogniser off disables it entirely. `VoiceScenarios` and its `RecordingExecutor`
/ `RecordingListener` / `FakeClock` are JVM test scaffolding.

---

## FLOW 14 — Back button and bottom navigation → screen state restoration

**Conclusion: PARTIALLY_WIRED**

Back handling and tab navigation are wired end to end. What is *not* wired is
the restoration half of the flow name: the one persisted value cannot select a
screen, and per-screen state is not saved.

| # | file:line | symbol | what it does |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:225-242` | `onBackPressed` | `if (voice.handleBack()) return`; then `uiState?.popTopOverlay()`; then `onBack()` |
| 2 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:286-297` | `onBack` | Home returns without doing anything; every other tab screen `startActivity(Intent(this, HomeActivity::class.java).addFlags(REORDER_TO_FRONT or SINGLE_TOP))` |
| 3 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:183-205` | `navigateTo` | tabs use the same flags, so returning to a screen reuses its instance |
| 4 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:209-222` | doc | `super.onBackPressed()` is deliberately not called: it would finish a launcher screen and leave an empty display |
| 5 | `app/src/main/java/com/ts8/launcher/ui/SearchActivity.kt:200` | `onBack` | the only override: search finishes itself and returns to whichever screen opened it |
| 6 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:91` | `onCreate` | `runCatching { AppGraph.require().uiState.restoreAfterProcessDeath() }` — **the return value is discarded** |
| 7 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:169` | `onResume` | `uiState?.let { machine -> primaryState?.let { machine.moveTo(it) } }` — the resumed screen asserts its own state |
| 8 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:62-69` | `primaryState` | derived from `screen`, so *whichever* Activity resumes sets the machine's state |

**State updated.** The overlay stack (popped); the front Activity; the machine's
primary state on every resume.

**Consumer.** `BottomNavView` selection, `AppStateMachine`, each screen's own
`onResume` rendering.

**Dependencies.** `AppStateMachine` over `PrefsKeyValueStore`
(SharedPreferences, synchronous `commit`); manifest launch modes —
`HomeActivity` `singleTask` (`AndroidManifest.xml:109`), `MusicFullscreenActivity`
`singleTop` (line 129), `MapActivity` `singleTop` (line 136).

**Error path.** An illegal transition is logged and refused, not thrown
(`AppStateMachine.kt:121-124`); a preference write failure is swallowed
(`AppStateMachine.kt:242`), degrading to "no restore".

**Production-wired.** Back and tab navigation: yes. State restoration: **not in
effect**.

**Why restoration does not take effect.** `restoreAfterProcessDeath`
(`AppStateMachine.kt:223-229`) genuinely sets `current` to the restored state
(`AppStateMachine.kt:270-274`). But nothing consumes it: a tree-wide search finds
no production caller of `AppStateMachine.current`/`state()`, and the first
`onResume` immediately overwrites it with the resumed screen's `primaryState`
(`BaseScreenActivity.kt:169`). After a launcher restart that screen is Home, so
the stored `MAP_BROWSE` or `MUSIC_EXPANDED` is lost within a frame. This is not
a bug in `restore`; it is a missing consumer.

**Mock/demo/fallback.** No mock.

**Also true, and not visible in launcher source.** `HomeActivity` is
`singleTask` and is the task root. Bringing it forward clears the activities
above it in the task — so Map and Music *are* destroyed on a Home round trip by
platform semantics, even though `navigateTo` never calls `finish()`. Each
recreated screen therefore re-runs `onCreate` with a fresh `savedInstanceState`
from the platform, and per-screen state that the screen does not save itself is
lost (e.g. `MusicFullscreenActivity.tab`, `MusicFullscreenActivity.kt:82`, which
has no `onSaveInstanceState`).

---

## FLOW 15 — Process recreation → Map / Music / Parking restoration

**Conclusion: PARTIALLY_WIRED**

| # | file:line | symbol | survives? |
|---|---|---|---|
| 1 | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt:91` | `restoreAfterProcessDeath` | the `AppStateMachine` primary state, once per process |
| 2 | `app/src/main/java/com/ts8/launcher/runtime/AppStateMachine.kt:241-243` | `save` | the primary state, synchronously on screen change |
| 3 | `app/src/main/java/com/ts8/launcher/runtime/AppStateMachine.kt:254-266` | `restore` | degrades `SEARCH`, `DESTINATION_PREVIEW` etc. back to MAP because their data died with the process |
| 4 | `app/src/main/java/com/ts8/launcher/runtime/MapHost.kt:149-151` | `onSaveInstanceState` | MapLibre camera, via `mapView.onSaveInstanceState` |
| 5 | `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt:654-659` | `onSaveInstanceState` | forwards `mapHost?.onSaveInstanceState(outState)` |
| 6 | `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt:393-396` | `onSaveInstanceState` | forwards `mapHost?.onSaveInstanceState(outState)` — the same camera save |
| 7 | `app/src/main/java/com/ts8/launcher/service/MusicPlaybackService.kt:310` | `restoreCheckpoint` | playback position and queue, from `CheckpointRestore` |
| 8 | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt:194-197` | `onPause` | `music.backend?.player?.saveCheckpoint()` |
| 9 | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt:237-239` | `persist` | `graph.parkingStore.save(decision)` on change |
| 10 | `app/src/main/java/com/ts8/launcher/di/AppGraph.kt:185` | `loadPack` | active pack path from `settings.activePackPath` |

**State updated.** Each of the stores above.

**Consumer.** `HomeActivity` / `MapActivity` (camera), `MusicPlaybackService`
(queue and position), `ParkingRuntime` listeners (decision).

**Dependencies.** `PrefsKeyValueStore` (synchronous `commit`,
`PrefsKeyValueStore.kt:25`), `SavedPlaces`, `MusicDatabase`, `ParkingStateStore`.

**Error path.** Every restore is wrapped: `runCatching` around the preference
read (`AppStateMachine.kt:255`), `runCatching` around every checkpoint save
(`MusicPlaybackService.kt:135, 167, 191`).

**Production-wired.** Mostly.

**What does not survive.** The map camera survives only a *configuration*
change or a save the platform asks for — `MapActivity.kt:393-396` and
`HomeActivity.kt:654-659` both forward MapLibre's own bundle, and that is all
that is saved. A cold process start passes `savedInstanceState = null`, so both
screens begin from the pack centre (`MapActivity.kt:434-438`,
`HomeActivity.kt:331`). Track position and queue survive through
`CheckpointRestore`; camera, zoom, overview and the Music screen's selected
library tab do not. The machine's own primary state is restored by
`restoreAfterProcessDeath` and then overwritten on the first `onResume`
(`BaseScreenActivity.kt:91`, `169`) — see FLOW 14.

**Mock/demo/fallback.** No mock.

---

## Cross-flow observations

1. **One wiring point.** `AppGraph` is the only place services are constructed;
   `AppGraph.current` / `AppGraph.require()` is how views reach them
   (`BottomNavView.kt:148`, `BaseScreenActivity.kt:91`).
2. **There is no navigation graph, no Fragment and no ViewModel in the tree.**
   Navigation is `startActivity` with `REORDER_TO_FRONT or SINGLE_TOP`
   (`BaseScreenActivity.kt:194-197`). A reviewer looking for
   `nav_graph.xml` or a `ViewModel` store will not find one, and that absence is
   the design, not an omission.
3. **Two label resources exist for entry points that were never built**:
   `map_mgmt_entry` (`strings.xml:189`) and `music_tab_online`
   (`strings.xml:369`). Both are unreferenced. They are the clearest evidence
   that the Map Management and online-music entry points were planned as
   persistent UI and shipped as conditional search features.
