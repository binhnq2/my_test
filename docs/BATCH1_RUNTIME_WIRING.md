# Batch 1 — runtime wiring

How Batch 1's screens, services and state stores are actually connected, in the
order a boot goes through them. Written so a reader can trace one path end to
end rather than take a diagram's word for it.

Every claim below names the file and symbol it comes from. Nothing here is
verified on a device: `adb devices` is empty and this host cannot start an
emulator, so this document describes what the source wires, not what the panel
did. `KNOWN_LIMITATIONS.md` §A1 and §D9 record that, and
`BATCH1_SANDBOX_TEST_REPORT.md` repeats it.

---

## 1. One composition root

`com.ts8.launcher.di.AppGraph` is the only place anything is constructed. There
is no second graph, no service locator per screen, no `new` of a runtime from an
Activity.

| graph member | type | lifetime |
|---|---|---|
| `settings` | `AppSettings` (SharedPreferences) | process |
| `graph.mapPhase` | `MapPhaseStore` | **process**, shared by Home and Map |
| `graph.launcherMode` | `LauncherModeStore` | process |
| `graph.volumeCapability` | `VolumeCapabilityTracker` | process, shared by QS and Voice |
| `graph.navigation` | `NavigationController` | process |
| `graph.music` | `MusicRuntime` | process, backed by `MusicPlaybackService` |
| `graph.parking` | `ParkingRuntime` | process |
| `graph.voice` | `VoiceRuntime` | process |
| `graph.location` / `graph.speed` | referenced-counted listeners | process, acquired per screen |

**Why the two stores are process-wide and not per screen.** §6 says a driver who
watches Home fail and then opens Map must see the *same* failure, and §10 says
switching tabs must not recreate the map or music engines. A per-Activity store
would answer `INITIALIZING` on the second screen and hide the first screen's
error behind another twelve seconds of progress.

---

## 2. Boot path

```
Launcher icon
  └─ HomeActivity  (exported="true", singleTask, landscape)
       ├─ attachBaseContext → LocaleHelper
       ├─ onCreate
       │    ├─ CompactTopBar        (weather UNAVAILABLE, voice, quick settings)
       │    ├─ MapPhaseSurface      (§6 shell, bound to graph.mapPhase)
       │    ├─ wireMap()            → MapHost.create()
       │    ├─ MapChrome-driven card visibility
       │    ├─ BottomNavView        (Home | Map | Music | Phone | Apps)
       │    └─ LauncherModeStore.onSurfaceResumed(HOME, fromRestore=…)
       ├─ onStart → mapHost.onStart(), location.acquire, speed.acquire
       └─ onResume → mapHost.onResume(), refreshDisplayConfig()
```

**`onResume` ordering is load-bearing.** `BaseScreenActivity.onResume` calls
`onSurfaceResumed(launcherMode, justRestored)`. When the process was restored
with a non-Home mode, the store's `restorePending` guard makes Home's claim a
no-op (§10: "không để Home `onResume()` ghi đè state vừa restore"). Only the
first claim after a restore can win, and only if it is not Home.

---

## 3. Map shell — the six phases (§6)

```
HomeActivity.wireMap / MapActivity.wireMap
  graph.mapPhase.moveTo(VALIDATING_PACK)
  MapHost(this, graph, container)
    ├─ setRenderListener(RenderListener)        ← screen's camera + route work
    └─ create(savedInstanceState)
         ├─ graph.loadPack()  ── null ──▶ MapHost.failure  (screen calls fail())
         ├─ packName captured ONCE from pack.metadata.name
         ├─ MapLibre.getInstance(context) + MapView(...)   ── throws ──▶ failure
         ├─ container.addView(view, 0, MATCH_PARENT)
         ├─ view.addOnDidFinishLoadingMapListener { onFirstFrameDrawn() }
         ├─ view.addOnDidFailLoadingMapListener { onStyleFailed(reason) }
         ├─ MapLibreMapController(...)  → applyDisplayConfig(settings…)
         ├─ controller.setReadyListener { onReady → onStyleLoaded()
         │                                 onFailed → onStyleFailed(reason) }
         ├─ view.onCreate(savedInstanceState)
         └─ controller.attach()
```

| phase | who moves the store | what it means |
|---|---|---|
| `INITIALIZING` | `MapPhaseStore` initial value, and `reset()` on `Thử lại` | nothing has been looked for |
| `VALIDATING_PACK` | the screen, immediately before `host.create` | the pack directory is being found |
| `LOADING_STYLE` | the screen, after `create` reports no failure | style document is being generated |
| `WAITING_FIRST_FRAME` | `MapHost.onStyleLoaded()`, from the controller's `onReady` | style is live, no pixels yet |
| `READY` | `MapHost.onFirstFrameDrawn()`, from the `MapView` listener | a frame was drawn |
| `FAILED` | `MapHost.onStyleFailed` / `onFirstFrameTimedOut`, or the screen's pack failure | error code + detail + pack |

**Why the host, not the screen.** The `MapView` is the only object that reports
"a frame was drawn", and the host owns it. Before this batch both screens drove
`LOADING_STYLE → READY` from the controller's `ReadyListener`, which made
`WAITING_FIRST_FRAME` unreachable and gave a native style rejection no timeout.
`MapPhaseFrontTest` asserts neither screen does that any more.

**The timeout.** `MapLibreMapController` registers only a success lambda on
`setStyle` (`MapLibreMapController.kt:150`), so a rejection that invokes neither
callback would leave the shell busy forever. `MapHost` arms
`FIRST_FRAME_TIMEOUT_MS` = 12 s when the style comes up, cancels it in
`onFirstFrameDrawn`, and on expiry fails with `MAP_FIRST_FRAME_TIMEOUT`. A late
frame still promotes to `READY` through the same listener.

**Retry.** `Thử lại` calls `store.reset()` and the screen's `rebuildMap()`, which
destroys the host, keeps the `mapPhase` include alive (it is a child of
`mapHost`, so `removeAllViews` is done *around* it), and re-runs `wireMap(null)`.
`onStart` is re-forwarded because the Activity already received it.

---

## 4. Screen surface — what each of the six normal screens draws at the top

| screen | top of screen | why |
|---|---|---|
| Home | `CompactTopBar` (weather chip, voice, quick settings) | §4: the only screen with room for all three |
| Map | `CompactTopBar compactOnly` (voice, quick settings) | weather would fight the maneuver card for the corner |
| Music | nothing; content inset by `@dimen/vendor_bar_inset` (32 dp) | the vendor bar is already showing clock and radio state |
| Search | nothing; same inset | opens the IME on purpose, so it needs the space |
| App Drawer | nothing; same inset | grid |
| Quick Settings | own title only; same inset | §6 entry into Map Management lives here |

`StatusBarView` is still compiled — Parking's large clock reuses part of its
plumbing — but **no layout inflates it**. `StatusBarAbsenceTest` scans every
layout for the tag.

---

## 5. Bottom navigation and mode (§10)

`BottomNavView.Screen { HOME, MAP, MUSIC, APPS, NONE }` — five visible items, of
which `navPhone` is an **action** (dialer intent), not a sixth tab.

```
navHome → HomeActivity      navMap → MapActivity     navMusic → MusicFullscreenActivity
navApps → AppDrawerActivity  navPhone → ACTION_DIAL (no Screen value)
```

`LauncherModeStore.Mode` has eight values; `NAVIGATING` is never claimed by a
screen — `effectiveMode()` derives it from `navActive` while the front mode is
`HOME` or `MAP_BROWSE`. `ParkingModeActivity` overrides
`launcherMode = PARKING` explicitly, which is what makes `backTarget()` answer
`HOME` from Parking rather than for whatever screen was there before.

| from | Back goes to |
|---|---|
| `HOME` | null (a launcher that exits leaves a black panel) |
| `PARKING` | `HOME` |
| `SEARCH`, `QUICK_SETTINGS` | the mode underneath (history, capped at 3), else `HOME` |
| `MUSIC`, `APP_DRAWER`, `MAP_BROWSE`, `NAVIGATING` | `HOME` |

Overlays close first: `BaseScreenActivity.onBackPressed` handles an open overlay
before any screen's own Back runs.

---

## 6. Music (§7)

```
MusicFullscreenActivity
  ├─ musicRoot takes focus (holdInitialFocus), search field carries none
  ├─ manifest: windowSoftInputMode="stateAlwaysHidden|adjustPan" — this activity only
  ├─ source row: Trên thiết bị | USB | Trực tuyến
  └─ renderProviders() ← OnlineMusicRuntime.providers()
                              ← buildProviders(feedConfigured, serverConfigured, jellyfin)

MusicPlaybackService
  └─ LocalMusicBackend / LocalMusicPlayer   ← the only MediaPlayer in the process
```

`OnlineMusicRuntime.buildProviders` is a pure function of three booleans, which
is why `OnlineSourceDiscoverabilityTest` can assert all three providers stay
visible with nothing configured. Every provider card is drawn unconditionally
from settings — no network call, so an unconfigured provider shows
`Chưa cấu hình — thiếu …` and a `Thiết lập` chip instead of disappearing.

`OnlineSourceDiscoverabilityTest` is also the guard on §7's "provider thiếu
config vẫn hiện `Thiết lập`".

---

## 7. Volume (§8)

```
QuickSettingsActivity.writeVolume      VoiceCommandExecutor.applyVolume
              │                                    │
              └──────────► graph.volumeCapability ◄┘
                        VolumeCapabilityTracker.record(requested, before, after, result)

before = bridge.readMediaVolume()      after = bridge.readMediaVolume()
write  = bridge.setMediaVolume(requested)
```

Both paths re-bind their UI to **`after`**, never to `requested`. Two consecutive
valid write/read-back pairs that did not move declare `NO_EFFECT`; pressing up at
max and pressing down at min are excluded by construction. `isVolumeFixed()`
short-circuits to `UNAVAILABLE` with no write attempted.

`VehicleAudioBridge` remains the adapter boundary. No SYU/MCU protocol was
reversed in this batch.

---

## 8. Parking (§9)

```
QS btnParking → QuickSettingsActivity.toggleParking()
                  ├─ graph.parking.requestEnter()  → ParkingRuntime
                  └─ startActivity(ParkingModeActivity, REORDER_TO_FRONT or SINGLE_TOP)

ParkingModeActivity
  ├─ screen = NONE, launcherMode = PARKING  (override)
  ├─ ACTION_TIME_TICK receiver  → parkingClock / parkingDate
  ├─ MusicRuntime.attach(musicObserver) → parkingMusicCard
  │     visibility = hasTrack ? VISIBLE : GONE, then bind(state)
  ├─ tap parkingPane / Back     → requestExit() + finish()
  └─ parkingListener → finishes on DRIVING (auto exit, never auto enter)
```

The compact card is a **mirror, not a player**. It shares `MusicRuntime` with
every other screen — same service, same state — and it can only appear because
playback already had a track; nothing on this screen creates one. That is how
§9's "music compact" and `UI_design.png` state 7, which draws no player at all,
are both true: with nothing playing, Parking looks exactly like the design.

Auto Parking stays default OFF and labelled `Chưa khả dụng` in Settings; there
is no switch, because there is no verified vehicle signal source. The GPS
heuristic in `ParkingResolver` drives the *screen*, never a claim of completion.

---

## 9. Diagnostics (§11)

```
SettingsActivity.btnDiagnostics → DiagnosticsActivity
   ├─ DiagnosticsReport (typed; no field can hold a credential)
   ├─ BuildFingerprint → SHA-256 of applicationInfo.sourceDir, first 12 hex
   └─ export() → Download/  →  getExternalFilesDir(null)/  →  filesDir
```

`:app` does not generate `BuildConfig` (`buildFeatures` declares only
`viewBinding`), so the "build hash" is derived from what is actually installed
and cannot disagree with it. The report prints provider *configuration state*,
never the value, and carries no personal location.

---

## What is wired and what is not

| wiring | state |
|---|---|
| Home → Map, Music, Apps, Phone | wired |
| Map → Map Management (error surface) | wired, `start()` returns false rather than throwing |
| Settings → Map Management | wired |
| Quick Settings → Map Management | wired in this batch |
| App Drawer → Map Management | **not wired, by design** — the drawer lists other apps |
| Quick Settings → Parking | wired, `REORDER_TO_FRONT or SINGLE_TOP` |
| Parking → Home | wired through `backTarget()` |
| Parking → compact music card | wired to `MusicRuntime`; visible only when a track already exists |
| Settings → Diagnostics | wired |
| Diagnostics → exported file | wired, three fallback locations |
| Music → OS settings for a provider | wired through the setup chip |
