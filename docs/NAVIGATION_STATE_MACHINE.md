# Navigation state machine

`core:navigation-core` owns guidance. This document is the contract between
the map screen, the search screen and that controller: every state, every
legal transition, and the policy decisions that make the machine behave on a
head unit instead of on a desk.

The implementation is `NavigationController` (610 lines) plus the enums and
value types in `NavigationState.kt`. There is no second copy of this logic in
`:app` — the Activities only render `NavigationUiState` and forward taps.

## 1. The eight states

| state | meaning | what the driver sees |
|-------|---------|----------------------|
| `IDLE` | no destination | the map follows the car; no guidance panel |
| `SEARCHING` | a lookup is running | the search screen's result list |
| `DESTINATION_PREVIEW` | a point is chosen, no route yet | the pin plus either a spinner or an error |
| `ROUTE_CALCULATING` | A* is running | "Đang tính lộ trình…" |
| `ROUTE_PREVIEW` | a route is drawn and offered | the line plus "Đi" and "Huỷ" |
| `NAVIGATING` | guidance is active | maneuver card, ETA strip, voice |
| `REROUTING` | left the line, recomputing | the old line dimmed, no announcements |
| `ARRIVED` | within `arrivalRadiusMeters` | the arrival card |

`ARRIVED` is terminal for guidance but not for the session: the line stays on
the map, and the driver's dismissal calls `end(NavigationEndReason.ARRIVED)`.

## 2. Transition table

| from | event | to | note |
|------|-------|----|------|
| `IDLE` | `beginSearch()` | `SEARCHING` | |
| `SEARCHING` | `selectDestination(point, name, from)` | `DESTINATION_PREVIEW` → `ROUTE_CALCULATING` | both fire in one call |
| `DESTINATION_PREVIEW` | route success | `ROUTE_PREVIEW` | |
| `DESTINATION_PREVIEW` | route failure | `DESTINATION_PREVIEW` | stays, with `message` set |
| `ROUTE_PREVIEW` | `startNavigation()` | `NAVIGATING` | resets the guidance counters |
| `ROUTE_PREVIEW` | `recalculate(from)` | `ROUTE_CALCULATING` | the "Lộ trình" button |
| `NAVIGATING` | off route × 3, past the cooldown | `REROUTING` | |
| `REROUTING` | route success | `NAVIGATING` | resumed **without** a confirm step |
| `REROUTING` | route failure | `NAVIGATING` | keeps the old route, sets `message` |
| `NAVIGATING` | `remainingMeters <= 30` | `ARRIVED` | announces "Đã đến nơi." |
| any | `end(reason)` | `IDLE` | clears route, destination, progress |
| any | `onPackLost()` | `IDLE` | `reason = PACK_LOST` |
| `NAVIGATING` / `REROUTING` | `beginSearch()` | — | **ignored**, by design; end first |

Two rejections are deliberate rather than defensive. `beginSearch()` from a
guiding state returns silently: on the road, a stray tap on the search field
must not abandon an active route. `startNavigation()` outside `ROUTE_PREVIEW`
returns silently for the same reason.

`setState` is a no-op transition when the state is unchanged — it still emits
`notifyState()`, so a listener that attached late receives the current state
without any extra bookkeeping.

## 3. Reroute policy

This is the part the spec calls out explicitly, so it is written as three
rules that must all hold:

```kotlin
private fun shouldRerouteNow(): Boolean {
    if (offRouteStreak < config.rerouteConfirmSamples) return false
    // Cooldown measured from the previous reroute, not from route start,
    // so an immediate second departure is still possible.
    if (lastRerouteAtMs != 0L && clock() - lastRerouteAtMs < config.rerouteCooldownMs) {
        return false
    }
    return true
}
```

1. **Three consecutive off-route fixes** (`rerouteConfirmSamples = 3`). A
   single GPS bounce must never trigger a recomputation. The location engine
   already rejects outliers; this is the second, independent gate, because the
   two defences fail differently — the engine rejects impossible motion, this
   one rejects a plausible position that is simply on the wrong road.
2. **A 60 m lateral threshold** (`offRouteDistanceMeters = 60f`). Only a
   measured distance counts. Fixes that simply could not be snapped are not
   treated as departures, because "not snapped" also happens when the car sits
   still at a junction and the engine refuses to snap backwards.
3. **A 20 s cooldown measured from the previous reroute**
   (`rerouteCooldownMs = 20_000`). Measured from the *reroute*, not from route
   start — a car that leaves the line twice in a minute is genuinely lost
   twice, and the second departure must still be able to recover. Without any
   cooldown, a driver parked 30 m off the line spends the whole stop
   recomputing.

Any on-route fix resets `offRouteStreak` to zero, so the counter is a streak,
not a running total.

**A failed reroute is not a dead end.** `onRouteComputed` keeps the previous
route and returns to `NAVIGATING` with the message "Không thể tính lại lộ
trình. Đang tiếp tục dùng lộ trình cũ." The driver still has a line to follow,
and the next fix starts the streak over.

## 4. Arrival

Two separate thresholds, because the driver needs notice as well as the event:

| constant | value | effect |
|----------|-------|--------|
| `arrivalAnnounceMeters` | 300 | speaks "300 m nữa là đến nơi", once |
| `arrivalRadiusMeters` | 30 | speaks "Đã đến nơi." and enters `ARRIVED` |

The ARRIVE maneuver is deliberately **not** announced by the maneuver
announcer (`emitNextManeuver` returns early for `ManeuverType.ARRIVE`).
Otherwise the car would hear "300 m, đã đến nơi" while still 300 m away,
immediately after "300 m nữa là đến nơi" — two prompts saying the same wrong
thing.

## 5. Voice announcements

`announceDistancesMeters = [1000, 500, 300, 150, 50]`, largest first.

For each maneuver, the controller picks the largest threshold the car has just
crossed and speaks once per threshold per maneuver:

```kotlin
val threshold = config.announceDistancesMeters.filter { it >= dist }.minOrNull()
if (threshold != null && announcedForManeuver.add(threshold)) { ... }
```

`announcedForManeuver` is cleared whenever the next maneuver changes, so each
turn gets its own set. It is also cleared on unmute, because a driver who
muted for a phone call wants the next instruction again rather than silence
until the turn after.

`urgent = dist <= 50` selects `QUEUE_FLUSH` over `QUEUE_ADD`: within 50 m the
instruction must interrupt whatever is being said, not wait behind it.

`formatDistance` has three cases worth recording, all of them Vietnamese
conventions rather than obvious defaults:

- under 20 m returns `""`, so the prompt is "Rẽ phải" not "10 m, Rẽ phải" —
  a distance the driver can already see.
- under 1 km rounds to the nearest 10 m ("150 m", not "147 m").
- 1 km and above uses a **comma** as the decimal separator, and drops a
  trailing `,0` — "15 km" reads, "15,0 km" does not.

## 6. Ducking, and what happens without a Vietnamese voice

`VoiceGuidance` pairs every duck with an unduck through
`UtteranceProgressListener` — not a timer, which a long street name would
outlive.

Three failure paths, each handled where it happens:

| condition | behaviour |
|-----------|-----------|
| no TTS engine / engine init fails | `vietnameseAvailable = false`; `speak` returns false; visual guidance continues; the UI says so once |
| engine present but `setLanguage` says missing data | `availableLanguages` is probed for any `vi` tag before giving up — some firmware ships the voice without advertising it |
| still nothing | engine stays on `Locale.US`; guidance is spoken in English and the UI reports the voice is not Vietnamese |
| `speak` fails to queue | `endUtterance()` runs immediately, because `onDone` will never fire and the music would stay ducked |
| duck denied by the focus owner | speech proceeds anyway: the instruction is safety-relevant, the music is merely too loud |

A phone call stops guidance immediately (`onCallStarted` → `stop()`), which
also restores the music level. Guidance is short; finishing the sentence over
a call is worse than cutting it.

## 7. Route computation

| property | value | why |
|----------|-------|-----|
| executor | one thread, owned by the controller | a 300 ms A* must not block a frame; one route at a time is all a head unit needs |
| in-flight policy | a new request **cancels** the pending one | queueing would leave the driver waiting for a route to a place they already left |
| `routeTimeoutMs` | 15 s | a computation this slow is a bug in the graph, not a slow device |
| `retryOnUnreachable` | true | a first `UNREACHABLE` is usually a snap that landed on a dead-end vertex; snapping both endpoints and retrying costs one query |

Every failure is mapped to a sentence the driver can act on, not an error
code — `NO_START_NODE`, `NO_END_NODE`, `UNREACHABLE`, `SAME_POINT`,
`PACK_NOT_READY`, `INTERNAL`. See `describeError` in the controller.

## 8. Lifetime and the one rule about threading

`NavigationController` lives in `AppGraph` (process-wide), owned by
`Ts8LauncherApp`. That is why tapping "Âm nhạc" 200 m from a turn does not
cancel the instruction the driver is about to reach: the Activity is destroyed,
the controller is not.

`onLocation(fix)` is the only entry point for driving input, and the only
place where reroute and arrival are decided. It may be called from any thread —
`state` and the route are `@Volatile` — but listeners are installed by an
Activity and must receive their callbacks on the main thread. That hop happens
exactly once, in the screen's `Listener` implementation, so everything past it
is ordinary main-thread code (see `ARCHITECTURE.md` §3).

`shutdown()` cancels the in-flight computation, drops the listener and stops
the executor. `AppGraph.invalidatePack()` calls it before releasing the graph
the router was memory-mapping, which is the ordering that prevents a
use-after-close.
