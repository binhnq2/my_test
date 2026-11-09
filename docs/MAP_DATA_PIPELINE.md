# Map data pipeline

The launcher ships no map data. A **map pack** is a directory built on a PC by
`tools/mapdata/` and copied to the head unit's internal storage or a USB stick.
This document records how a pack is made, what is in it, how the device reads
it, and what was measured on the pack built for this campaign.

The binary contracts live in `docs/internal/FORMAT_SPECS.md`. That file is
normative for byte layout; this one is the operator's guide.

## 1. The five stages

```
VN OSM extract (vietnam-latest.osm.pbf, ~400 MB)
        │
        │ clip_osm.py          keep complete ways + relations inside the box
        ▼
danang.osm.pbf
        │
        ├── build_map_pack.py   → basemap.mbtiles  (gzipped MVT, SIMPLE scheme)
        ├── build_search_db.py  → places.db       (SQLite + FTS4)
        └── build_routing_graph.py → routing-graph/{graph.bin,graph.idx}
                │
                │ write_manifest.py   reads tile bounds, writes:
                ▼
        metadata.json + checksums.sha256
                │
                │ copy to /storage/emulated/0/ts8/map-pack/danang  (or a USB root)
                ▼
        device
```

Each builder is a plain Python program run directly. That is deliberate: the
build host cannot execute third-party JARs, so Planetiler and the OpenMapTiles
toolchain are unavailable, and the pack format had to be produced by code that
could actually run here.

### 1.1 `clip_osm.py`

Two passes over the source PBF. Pass 1 collects every node inside the bounding
box and every way that references one; pass 2 emits the surviving nodes, the
ways with their full node lists, and the relations whose members survived.
**Complete ways** matters: clipping a way in half leaves a road that ends in
nowhere, which then becomes a routing dead end and a search result with a
truncated address.

Da Nẵng box used for this pack: `107.85,15.85,108.45,16.25`
(~55 km × 44 km, the whole city plus Sơn Trà peninsula and a margin).

### 1.2 `build_map_pack.py`

Streams the PBF and writes MBTiles tiles: a SQLite `tiles` table with gzipped
Mapbox Vector Tiles, standard OpenMapTiles semantics — `tileScheme: "simple"`,
no Y-flip.

Layers emitted, which is exactly the set `StyleBuilder` consumes:

| layer | geometry | source tags | first zoom |
|-------|----------|-------------|------------|
| `water` | polygon | `natural=water`, `water=*`, `landuse=reservoir` | 6 |
| `waterway` | line | `waterway=river\|stream\|canal` | 9 |
| `landuse` | polygon | `landuse=*`, `leisure=park\|garden\|pitch`, `natural=wood\|scrub` | 8 |
| `landcover` | polygon | `natural=bare_rock\|sand\|scree`, `landuse=forest` | 9 |
| `building` | polygon | `building=*` + derived height | 12 |
| `road` | line | `highway=*` with `class`, `oneway`, `name`, `layer` | 6 |
| `railway` | line | `railway=rail\|light_rail\|subway\|tram` | 8 |
| `boundary` | line | `admin_level` 2..8 | 6 |
| `place` | point | `place=*` with `name`, `rank` | 6 |
| `poi` | point | `amenity`, `shop`, `tourism`, `leisure`, `historic`, `office` | 12 |
| `aeroway` | line | `aeroway=runway\|taxiway` | 9 |
| `transportation` | point | `public_transport`, `highway=bus_stop` | 13 |

Tiles are buffered by `TILE_BUFFER = 256` MVT units so a line does not visibly
stop at a tile seam, and the extent is 4096.

Buildings carry a `render_height` derived from `height` or `levels` (3 m per
level when only `levels` is present) so `fill-extrusion` has something to
extrude. Without it every building would render at one height and the 3D view
would read as a field of identical cards.

### 1.3 `build_search_db.py`

Produces `places.db` (`user_version = 1`) with a normal `places` table and an
`places_fts` FTS4 virtual table. Ranking values come from four importance
tables: cities outrank bridges, which outrank cafés. `MAN_MADE_IMPORTANCE =
{"bridge": 58}` exists because named bridges are highly searchable in Vietnam —
"Cầu Rồng" is one of the most-typed destinations in Đà Nẵng.

**The normaliser is duplicated on purpose and must stay byte-identical.**
`normalize()` in Python and `VietnameseText.normalize` in Kotlin both implement:

1. Unicode NFKD
2. strip combining marks (`\p{Mn}`)
3. `đ`/`Đ` → `d`
4. lowercase (`Locale.ROOT`)
5. collapse whitespace, trim

The app cannot rebuild the index at runtime, so a drift between the two does
not throw — it silently returns no results for one of the spellings. The
Python side says so in its own docstring; the Kotlin side is pinned by a test.

### 1.4 `build_routing_graph.py`

Car-drivable highways only. A way becomes a routing *segment*; graph nodes sit
where segments meet (OSM nodes with ≥ 3 incident ways) plus both endpoints of
every way, so no segment is ever split in a way the graph cannot express.

Each way is simplified in place: endpoints plus any point needed to stay within
`--simplify-m` of the original line. The points that were removed go into the
geometry blob, so the route still draws along the real road shape while the
graph stays small.

Then directed edges (one per direction unless one-way), CSR-ordered, plus the
name table and the spatial grid index.

Two classification tables that need to be read together:

```python
CAR_FORBIDDEN = {"footway", "path", "cycleway", "steps", "pedestrian", "track"}
```

Those ways stay in the graph — the map draws them and a future pedestrian
profile needs them — but their edges get flag bit 8. Both readers build a
`routableNode` mask from that flag before snapping, because a GPS fix on a
beach is often closest to a footway vertex, and routing from there reports
`UNREACHABLE`, which reads like corrupt data rather than a bad snap.

### 1.5 `write_manifest.py`

Generates `metadata.json` and `checksums.sha256`, and is generated rather than
hand-written for one reason: the bounds written into the manifest are **read
back out of the tile store** rather than retyped. A pack whose manifest claims a
larger area than its tiles is the classic "blank map at the edge" bug, and it is
invisible until a driver pans there.

`checksums.sha256` is written last (so it can never list itself) in the
`sha256sum -c` format, relative to the pack root.

Note what the manifest does **not** contain: a `style` block. The style document
is generated at runtime by `core:map-engine-maplibre/StyleBuilder`, because it
has to name the absolute tile and glyph paths of *this* install, and an absolute
path cannot live in a file that is copied between machines.

## 2. What is actually in the shipped pack

Measured on the pack built for this campaign (`.work/pack/danang`, mirrored to
`D:/Tools/_data/pack-test` for the tests).

| artifact | size | contents |
|----------|------|----------|
| `basemap.mbtiles` | 11 MB | 748 tiles, zoom 6–14 |
| `fonts/` | 1.4 MB | 7 ranges × 2 stacks |
| `places.db` | 2.2 MB | 11,268 places |
| `routing-graph/` | 6.3 MB | `graph.bin` 6,047,219 B, `graph.idx` 513,492 B |
| **total** | **~21 MB** | |

Tile distribution is worth recording because it explains the size:

| zoom | tiles |
|------|-------|
| 6 | 1 |
| 7 | 1 |
| 8 | 2 |
| 9 | 4 |
| 10 | 10 |
| 11 | 21 |
| 12 | 55 |
| 13 | 160 |
| 14 | 494 |

Two thirds of the file is zoom 14. That is where streets, shops and buildings
actually appear, and it is why the pack renders the city rather than a coloured
blob.

### 2.1 `places.db`

| category | rows |
|----------|------|
| `poi` | 6,313 |
| `street` | 4,874 |
| `place` | 81 |
| **total** | **11,268** |

Address coverage differs sharply by category and that difference is a real
limitation, not a bug: **2,062 POIs carry an address, only 2 of 4,874 street
rows do.** OSM in Đà Nẵng has `addr:*` tags on buildings and shops, while a
street's own nodes almost never carry one. The search UI therefore shows an
address line on POI rows and leaves it blank on street rows rather than
inventing one. See `KNOWN_LIMITATIONS.md`.

### 2.2 `graph.bin`

```
magic           TS8G
formatVersion   1
flags           0        (no turn restrictions in this build)
nodeCount       67,859
edgeCount      167,859
bbox            107.85, 15.85, 108.45, 16.25
nameTable       offset 5,935,172   size 112,047 B
geometryBlob    offset 5,114,424
file size       6,047,219 B
```

67,859 nodes / 167,859 edges is ~2.5 edges per node — the signature of a graph
whose nodes are junctions and way endpoints rather than every OSM vertex. Every
unremarkable vertex on a road went into the geometry blob instead.

`flags = 0` means this pack carries no turn restrictions. A banned left turn
into a one-way street is therefore routable and will be offered. Recorded in
`KNOWN_LIMITATIONS.md`; the format has bit 0 for it, the builder does not yet
emit it.

### 2.3 Glyphs

Labels need font PBFs. The pack carries 7 ranges per stack instead of the full
256:

| range | what it covers |
|-------|----------------|
| 0–255 | Basic Latin, road numbers, punctuation |
| 256–511 | Latin-1 Supplement |
| 512–767 | Latin Extended-A |
| 768–1023 | Latin Extended-B |
| 1024–1279 | IPA extensions |
| **7680–7935** | **Latin Extended Additional — U+1EA0–1EFF, nearly every Vietnamese tone mark** |
| 8192–8447 | General punctuation and currency, for road shields |

Full coverage would be ~40 MB of fonts for scripts this pack has no labels in.
The 7680 block is the one that makes "Đường Nguyễn Văn Linh" render instead of
"Du?ng Nguy?n V?n Linh".

## 3. Installing a pack on the unit

`MapPack.defaultRoots` is the priority list, in order:

1. `filesDir/map-pack` — app-private, always readable, survives a USB pull
2. `getExternalFilesDir(null)/map-pack`
3. `/storage/emulated/0/ts8/map-pack`
4. `/storage/emulated/0/TS8/map-pack`
5. `<usb root>/ts8/map-pack` and `<usb root>/TS8/map-pack`, for six vendor mount
   points (`/storage/usb0`, `/storage/usb1`, `/storage/usbdisk`, `/storage/usb_disk`,
   `/mnt/usb_storage`, `/storage/UsbDriveA`)

`MapPack.discover` tries each root as a pack first, then each of its
subdirectories. That is what lets a driver copy the whole `map-pack/` folder
with `danang/` inside it, or just copy `danang/` itself, and have both work.

App-private storage is first because it is the only location that survives the
driver pulling the stick mid-drive.

## 4. Validation, and why it is a class

`MapPack.load(directory)` never throws. A missing directory, unreadable JSON, an
unsupported `formatVersion`, a missing `basemap.mbtiles` or `maxZoom < minZoom`
each come back as `LoadResult.Failure` with a sentence aimed at the driver.
Each of those conditions, unchecked, fails inside MapLibre as a native crash or
a silently blank map.

The version check is a hard reject rather than best-effort: a pack built by a
newer tool than this APK understands would mis-render, and mis-rendering a map
is worse than saying "cập nhật ứng dụng".

## 5. Rebuilding a pack

```sh
python -I tools/mapdata/clip_osm.py \
    --input vn.osm.pbf --output danang.osm.pbf \
    --bbox 107.85,15.85,108.45,16.25

python -I tools/mapdata/build_map_pack.py \
    --input danang.osm.pbf --out-dir pack/danang --min-zoom 6 --max-zoom 14

python -I tools/mapdata/build_search_db.py \
    --input danang.osm.pbf --out pack/danang/places.db

python -I tools/mapdata/build_routing_graph.py \
    --input danang.osm.pbf --out-dir pack/danang/routing-graph

python -I tools/mapdata/write_manifest.py \
    --pack pack/danang --pack-id danang-2026.10 --name "Đà Nẵng" \
    --generated-at 2026-10-28T00:00:00Z
```

(`-I` runs Python isolated: no current-directory imports, which matters when the
inputs are downloaded files.)

Then, independently of the Kotlin reader:

```sh
python -I tools/mapdata/verify_graph.py \
    --pack pack/danang --from 16.0616,108.2270 --to 16.0472,108.2015
```

`verify_graph.py` is a **second implementation** of the reader and of A*, in a
different language. If it disagrees with `core:routing-engine-offline`, one of
the two is wrong — which is the point: a bug in the interpretation of the format
cannot hide behind the same bug in the writer. The distance windows in
`AStarRouterPackTest` are the values this verifier reports for the same pairs on
the same bytes.

Changing any format requires bumping `formatVersion` in
`docs/internal/FORMAT_SPECS.md`, both readers and `MapPack.SUPPORTED_FORMAT_VERSION`.
