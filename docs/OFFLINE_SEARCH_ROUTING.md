# Offline search and routing

Two independent engines, both plain Kotlin over `java.io` / `android.database`,
both with no UI types and no Android rendering — which is why their tests run
on the JVM rather than needing a device.

- `core:place-search` — FTS4 over the pack's `places.db`
- `core:routing-engine-offline` — A* over the pack's `graph.bin`

## Part 1 — Search

### 1.1 FTS4, not FTS5

Android 6 / API 23 ships SQLite with FTS4 and **not** FTS5. That single fact
decides the entire search design: FTS4 has no `bm25()`, so it cannot rank. The
virtual table is therefore used as a *candidate generator only*, and scoring
happens in SQL-adjacent Kotlin after the fetch.

```sql
CREATE VIRTUAL TABLE places_fts USING fts4(
  name_norm, address_norm, tokenize=simple, content=''
);
```

`content=''` makes it an external-content table: the index stores positions but
not the text, so a rebuild is `INSERT INTO places_fts(places_fts) VALUES('rebuild')`
rather than a copy of every row.

### 1.2 Vietnamese normalisation — the one thing that must not drift

The index is built on the PC by `build_search_db.py`; the app cannot rebuild it.
So `VietnameseText.normalize` and the Python `normalize()` must produce
identical bytes, or one spelling silently returns nothing:

| step | why |
|------|-----|
| NFKD | decomposes "ồ" into "o" + combining marks so the marks can be dropped |
| strip `\p{Mn}` | removes every tone mark in one pass, not a table of 134 cases |
| `đ`/`Đ` → `d` | `đ` is a distinct letter, not `d` + a mark, so NFKD leaves it alone |
| lowercase (`Locale.ROOT`) | `Locale.ROOT` because a Turkish locale lowercases `I` to `ı` and would break English tokens |
| collapse whitespace, trim | a paste with a trailing newline must match |

The cultural requirement behind it: search must work whether or not the
driver's keyboard emits diacritics. `cau rong`, `cầu rồng` and `CẦU RỒNG` all
fold to `cau rong`, and all three find the same place.

### 1.3 Query to MATCH expression

```kotlin
fun toFtsMatchExpression(query: String): String? {
    val toks = tokens(query)
    if (toks.isEmpty()) return null
    return toks.joinToString(" ") { "$it*" }
}
```

Every token becomes a **prefix** term — that is what makes the acceptance case
`cau r` match `Cầu Rồng`. FTS4 requires the `*` to follow the term with no
space; a space there turns the query into a syntax error, which is caught and
turned into "no results" rather than a crash in the driver's search box.

### 1.4 Ranking

`offsets()` rather than `bm25` — FTS4's function for term positions. The score
ladder, in descending weight:

| signal | points | reason |
|--------|--------|--------|
| exact whole-name match | +5,000,000 | typing the full name must win regardless of category |
| prefix at a token start | +1,000,000 | `cau r` landing on the `r` of "Cầu Rồng" |
| name starts with the query | +400,000 | "Cầu" before "Ngã ba Cầu…" |
| `importance × 10,000` | 20k–95k | a city outranks a café; see the importance tables |
| `−min(distance, 60 km) / 6` | 0 … −10,000 | proximity |

The distance term is **capped at 60 km** on purpose. Uncapped, a nearby café
would beat the exact match the driver typed; capped, proximity is a tie-breaker
and never a reason to hide the thing asked for.

`isTokenStartMatch` parses `offsets()` — `"<column> <byteOffset> <byteLength>"`
quadruples — and looks for any term at position 0 of column 0 (the name). That
distinguishes "Cầu Rồng" (the query begins the name) from "Bến xe Cầu Rồng"
(the query is merely in the name).

### 1.5 Bounded, debounced, never stale

`PlaceSearchService` is the only thing the UI talks to. Three rules:

1. **Debounce** (`DEFAULT_DEBOUNCE_MS`, 250 ms). Typing `cầu rồng` must not run
   nine FTS queries and stall the map behind them.
2. **Bounded** — `limit` is clamped to `MAX_RESULTS = 20`, and the SQL fetch is
   `limit × 12` with a floor of 200 candidates, so ranking has something to rank.
3. **Never a stale answer.** Every dispatch takes a ticket
   (`AtomicInteger`); a result whose ticket is no longer current is dropped.
   This is what stops the answer for `cau` from overwriting the answer for
   `cau rong` when the first query happened to be slower. `reverse()` has its
   own ticket so a map tap and a search do not cancel each other.

An empty query **clears the callback** instead of dispatching, because "nothing
typed" and "nothing found" are different states and the screen renders them
differently (the former lists saved places).

### 1.6 What was measured on the real pack

Against `.work/pack/danang/places.db` (11,268 rows):

| query | result |
|-------|--------|
| `cau r*` | id 1693 "Cầu Rồng" (poi, bridge, importance 58) first |
| `cau rong` | 1693, then two more "Cầu Rồng" POIs, then street 6547 |
| `cau` | genuinely mixed — "Azura", "Bãi đỗ xe Cầu Trần Thị Lý", … |

The `cau r*` case is the spec's acceptance example and it is satisfiable on the
shipped pack. Note the three separate "Cầu Rồng" POI rows plus one street row:
OSM has the bridge tagged more than once, and search does not merge them,
because merging would have to pick one of four coordinate pairs and the driver
would be routed to a pin they did not choose.

`cau` returning "Azura" looks wrong and is not. Row 791 is
`name_norm = "azura"`, `address = "Cầu Sông Hàn"` — it is a hotel on the Hàn
river, and FTS4 matches across *both* indexed columns. The candidate set is
right (somewhere with "Cầu" in its record); the score is what fixes the
ordering. "Azura" has no `cau` token in its name and no importance to speak of,
so `prefixMatch` fails and the +1,000,000 term-start bonus never applies, while
row 1693 "Cầu Rồng" claims both the exact-name bonus and importance 58. The
visible list is the sorted list; the raw FTS row order is never displayed.

This is also why the address column is in the index at all: a driver searching
a street name should find the business standing on it.

### 1.7 Failure behaviour

| condition | behaviour |
|-----------|-----------|
| `places.db` absent | `SqlitePlaceSearch.open` returns null; the screen says so once; no fake results |
| malformed MATCH | `SQLiteException` caught → empty list, never a crash |
| database closed mid-query | `isReady()` returns false; `PlaceSearchService` reports `onError` |

`SqlitePlaceSearch.open` uses `OPEN_READONLY`, so a pack on a USB stick is never
modified — a search must not be able to corrupt a driver's data.

## Part 2 — Routing

### 2.1 Cost is time, not distance

The objective a driver has is "arrive soonest", and the graph's per-edge speed
limits are what distinguish a motorway from a service lane. A distance-optimal
router would route through the shortest alley.

```
tentative = dist[current] + e.baseSeconds
```

`baseSeconds` comes from the edge's `speedKph` (the OSM `maxspeed` where tagged,
a per-class default otherwise — see `ROAD_CLASS` in `build_routing_graph.py`).

### 2.2 The heuristic is admissible

```kotlin
private fun heuristic(from: GeoPoint, to: GeoPoint): Double =
    haversine(from, to) / (MAX_GRAPH_SPEED_KPH * 1000.0 / 3600.0)
```

Straight-line distance divided by the pack's highest speed (120 km/h). That can
never overestimate the cheapest remaining cost, so A* returns the **optimal**
route rather than a greedy approximation. Using the current edge's speed would
make it inadmissible and quietly produce routes that are 5 % too long and
unexplainable.

### 2.3 Working memory

Four flat arrays (`DoubleArray(nodeCount)` plus three `IntArray`/`BooleanArray`)
allocated per query and freed with it. Nothing is cached between routes: a
reroute runs on a background thread, and a shared mutable scratch pad would need
locking that costs more than the allocation at this graph size (67,859 nodes →
~900 KB per query).

`MAX_EXPLORED = 400_000` is a safety valve: a pathological query fails fast
instead of pinning the CPU for the rest of the drive. On this pack a city-crossing
route explores a small fraction of that.

The open set is a binary `MinHeap` with lazy deletion — a node can be pushed
several times as its cost improves, and the `closed` check on pop discards the
stale entries. That is cheaper than a decrease-key on a heap that needs an
index map.

### 2.4 Snapping — and the mask that prevents a wrong answer

Both endpoints are snapped before routing, through
`GraphIndex.nearestNodeExact`, which searches an expanding ring of grid cells:

```
if (best >= 0 && bestDist < ring * cellDeg * 110_000.0) break
```

That stop condition is the correctness argument: once the searched area already
covers at least the best distance found, no further ring can contain anything
closer.

`nearestNodeExact` takes an `accept` predicate, and routing passes the graph's
`routableNode` mask — any incident edge with the car-forbidden bit clear.
Without it, a GPS fix on a beach snaps to the nearest vertex, which is a
footway, and the route reports `UNREACHABLE`. That reads like corrupt map data
when the actual cause is a 4 m snap error, and it was worth the mask.

### 2.5 Degenerate cases, decided rather than thrown

| case | behaviour |
|------|-----------|
| start and end snap to the same node | a two-point route with `DEPART` + `ARRIVE` — "route to the shop next door" must work |
| no start node | `NO_START_NODE` → "Không tìm thấy đường gần vị trí hiện tại." |
| no end node | `NO_END_NODE` → "Không tìm thấy đường gần điểm đến." |
| unreachable | `UNREACHABLE` → the caller retries once with both endpoints snapped (see `NAVIGATION_STATE_MACHINE.md` §7) |
| same point | `SAME_POINT` |
| any exception | `INTERNAL` with the message, never a crash |

### 2.6 Route geometry and maneuvers

The route polyline is built from the node points **plus each edge's geometry
blob**, so the drawn line follows the real road curve rather than a chord
between junctions. The blob is written once per undirected segment, and the
reverse edge walks it backwards when `FLAG_GEOM_REVERSED` is set — inferred
ordering would break for `oneway=-1` ways where only one edge exists.

Maneuvers are derived from the **bearing change** between consecutive segments:

- `TURN_THRESHOLD_DEG = 25.0` — below that it is not a turn
- a street-name change with no meaningful turn becomes a "continue" ("Tiếp tục
  đi thẳng trên <đường>"), which is what a driver actually hears
- the first maneuver is always `DEPART`, the last always `ARRIVE`

No turn restrictions (`graph.bin` `flags = 0` in this pack), so a banned left is
routable. Recorded in `KNOWN_LIMITATIONS.md`.

## Part 3 — Why the two implementations exist

`tools/mapdata/verify_graph.py` is a **second** reader and a second A*, in
Python. If it disagrees with the Kotlin, one of the two is wrong. The distance
windows in `AStarRouterPackTest` (e.g. airport → Dragon Bridge must be
5.0–6.1 km) are exactly the values that verifier reports for the same pairs on
the same bytes, so reader/writer format drift shows up as a number moving
outside its window instead of as a wrong route nobody notices.

That test suite runs against the real pack and is skipped, with a clear message,
when the pack is absent — never silently green.

## Part 4 — Does the drawn line actually sit on the drawn roads?

The complaint that started this line of work was that the route looked like a
straight line between two points rather than a road. It was fixed, but "it looks
right now" is not a measurement, so here is the measurement and how to repeat it.

### What was measured, and against what

Two independent references, because they answer different questions:

| reference | question it answers | tool |
|---|---|---|
| `road` layer of `basemap_water.mbtiles`, z14 | does the line the driver sees lie on the line the driver sees? | `measure_route.py` |
| `graph.bin` directed edges | is every segment on a *permitted* direction — the correct carriageway, not the opposite one? | `check_direction.py` |

The polyline under test is the one the app actually handed to MapLibre
(`MapLibreMapController.setRoute`), captured from logcat in 1,500-character
chunks because a single log line is capped near 4 KB. Nothing is re-derived from
the routing graph, so this measures the shipped artefact rather than a
re-implementation of it.

### Results — 6.6 km route across Đà Nẵng, 217 vertices

| metric | value |
|---|---|
| vertex → nearest drawn road, median | **0.11 m** |
| vertex → nearest drawn road, p99 | 0.32 m |
| vertex → nearest drawn road, max | **0.33 m** |
| vertices more than 5 m off any road | **0 of 217** |
| segments whose midpoint is off any road | **none** |
| segments on a permitted directed edge | **216 of 216** |
| segments drawn against the permitted direction | **0** |
| segments with no matching graph edge | **0** |

The rerouted line, after the driver left the route, measures identically:
median 0.11 m, max 0.33 m, 177 of 177 segments with the permitted direction.

Two notes on how to read that 0.33 m ceiling. It is not a tolerance we chose: it
is the distance between the routing graph's centreline (built from OSM with
`--simplify-m 0`) and the tile pipeline's simplified copy of the same road at
z14. The pack's `maxZoom` is 14 and the renderer overzooms above it, so z14 *is*
the geometry on screen — which is why this is the right comparison and why
comparing against z16 finds no tiles at all.

### Why the direction check matters more than the distance check

A line can sit perfectly on a road and still be on the wrong side of a dual
carriageway. That is invisible to a distance test. `graph.bin` stores each
traversable direction as its own edge, with its geometry pre-oriented by
`FLAG_GEOM_REVERSED` (bit 12), so "the route drives with traffic" is a statement
that can be checked: the route segment's bearing must match some edge's bearing
within 60°, and no edge near it may only match at 180°.

Two-way roads make this subtle: both directions exist as separate edges with
identical geometry, one reversed, so "the nearest edge" is a coin toss between
them. The check therefore asks whether *any* permitted direction near the
segment is aligned, not whether the nearest one is.

### Re-running it

Both tools live in `tools/diagnostics/` and take the pack and graph paths from the
environment, so nothing is hard-coded to the machine they were written on:

```
export TS8_PACK=/path/to/basemap_water.mbtiles
export TS8_GRAPH=/path/to/routing-graph/graph.bin

adb logcat -c && adb logcat -d -s TS8RouteDump > route.log   # with the temp dump in place
python tools/diagnostics/measure_route.py route.log A
python tools/diagnostics/check_direction.py route.log
```

The dump is a temporary diagnostic and is **not** in the shipped source; it has
to be re-added to `setRoute` (chunked, see the change that removed it) before
either script has anything to read.

## Part 5 — Leaving the route on purpose

The other half of "routing must follow the road the driver actually took": what
happens when the driver ignores an instruction.

### Policy, in one place

`NavigationController.onLocation` is the only place that decides this, and the
numbers are in `Config`:

| knob | value | why |
|---|---|---|
| `offRouteDistanceMeters` | 60 m | wider than a dual carriageway and a car park entrance, narrower than a wrong turn |
| `rerouteConfirmSamples` | 3 | one GPS spike must never trigger a reroute |
| `rerouteCooldownMs` | 20 s | a failed or immediately-repeated departure must not loop |
| `arrivalRadiusMeters` | — | checked before the off-route test, so arriving is never "a departure" |

A reroute recomputes from the **current** position to the **same** destination,
with alternatives suppressed (`isReroute = true`) because three options under a
moving car is a question the driver cannot answer. On success it returns straight
to `NAVIGATING`; on failure it keeps the old line and says so
(`"Không thể tính lại lộ trình. Đang tiếp tục dùng lộ trình cũ."`) rather than
stranding the driver with no line at all.

### Verified on the emulator

Drove 55 fixes along a 6.6 km route, then stepped onto a real parallel road 103 m
away and held there. Measured from the two polylines the app drew:

| | planned | after departure |
|---|---|---|
| points | 217 | 178 |
| length | 6 598 m | 6 341 m |
| starts at | the GPS start point | **18.7 m from the car** |
| ends at | the destination | the same destination |
| instruction | `Rẽ phải / Sau đó Đường Phan Châu Trinh` | `Rẽ phải vào Đường Hoàng Diệu / Sau đó Đường Lê Đình Dương` |

The 18.7 m start offset is the snap from the tile's road centreline to the
graph's, not a positioning error — the fixes were taken from road geometry.

Two behaviours worth recording:

- **The camera must not re-frame mid-reroute.** `applyRoute` used to call
  `fitRoute` unconditionally, so a reroute zoomed the panel out to the whole 6 km
  for the one second until the next fix re-armed the follow camera — right when
  the driver needs the next junction. It now fits only when not guiding, or when
  the driver has explicitly asked for the overview.
- **A first test that veered perpendicular into a city block** produced a route
  starting 146 m away, snapped to the nearest road. That is correct behaviour for
  a position with no road under it, but it is not the scenario a driver produces,
  which is why the recorded run uses a real road.
