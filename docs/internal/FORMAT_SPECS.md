# Map pack / data format specifications

This document is the contract between the PC-side pack builders
(`tools/mapdata/`) and the on-device readers (`core:place-search`,
`core:routing-engine-offline`). Every format below is versioned and
little-endian. Changing a reader requires bumping the format version and
rebuilding the pack.

---

## 1. Map pack layout

A map pack is a directory on device storage (internal or USB):

```
map-pack/
  metadata.json          # manifest, see 1.1
  basemap.mbtiles        # MVT vector tiles, SIMPLE scheme
  places.db              # SQLite with FTS4, see 2
  routing-graph/
    graph.bin            # routing graph, see 3
    graph.idx            # spatial grid index, see 3.4
  checksums.sha256       # sha256 of every file above, "<hex>  <relative path>"
```

### 1.1 `metadata.json`

```json
{
  "formatVersion": 1,
  "packId": "danang-2026.10",
  "name": "Đà Nẵng",
  "generatedAt": "2026-10-28T00:00:00Z",
  "bounds": [107.85, 15.85, 108.45, 16.25],
  "center": [108.2215, 16.0678],
  "defaultZoom": 12.0,
  "minZoom": 5,
  "maxZoom": 14,
  "tileExtent": 4096,
  "tileScheme": "simple",
  "attribution": "© OpenStreetMap contributors",
  "attributionUrl": "https://www.openstreetmap.org/copyright",
  "style": {
    "day": "style-day.json",
    "night": "style-night.json"
  },
  "glyphs": "fonts/{fontstack}/{range}.pbf",
  "sprite": "sprites/sprite"
}
```

`tileScheme: "simple"` means the MBTiles `tile_data` column holds a
**gzipped Mapbox Vector Tile** (standard OpenMapTiles behaviour). Do not use
the "tms" Y-flip scheme.

---

## 2. `places.db` — offline place search

SQLite database, `user_version = 1`. FTS4 is used because Android 6 (API 23)
ships FTS4 but **not** FTS5.

```sql
CREATE TABLE places (
  id        INTEGER PRIMARY KEY,
  osm_type  TEXT    NOT NULL,        -- 'node' | 'way' | 'relation'
  osm_id    INTEGER NOT NULL,
  name      TEXT    NOT NULL,        -- display name, original diacritics
  name_norm TEXT    NOT NULL,        -- lowercase, no diacritics, folded
  address   TEXT,                    -- "Số nhà, Đường, Phường, Thành phố"
  category  TEXT    NOT NULL,        -- 'poi' | 'street' | 'place' | 'address'
  subclass  TEXT,                    -- 'restaurant' | 'bridge' | 'residential' | ...
  lat       REAL    NOT NULL,
  lon       REAL    NOT NULL,
  bbox_w    REAL, bbox_s REAL, bbox_e REAL, bbox_n REAL,
  importance INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_places_importance ON places(importance DESC, id);

CREATE VIRTUAL TABLE places_fts USING fts4(
  name_norm,
  address_norm,
  tokenize=simple,
  content=''
);
```

`name_norm` / `address_norm` are produced by the same normalisation function
the app implements (`core:place-search`, `VietnameseText.normalize`):

1. Unicode NFKD, strip combining marks (`\p{Mn}`).
2. `đ`/`Đ` → `d`.
3. Lowercase.
4. Collapse whitespace, trim.

Because FTS4 `simple` tokenizer splits on non-alphanumerics, the query side
must produce the same tokens. Search ranking is applied in SQL/Kotlin, never
inside FTS: FTS supplies the candidate set, then the app scores

```
score = ftsRankMatch * 1000000
      + prefixLengthBonus * 10000        // query matched at token start
      + importance * 100
      - min(distanceMeters / 50, 20000)  // proximity to current position
```

`places.id` values are the FTS `docid` (rowid).

---

## 3. `graph.bin` — routing graph

### 3.1 Header (64 bytes)

| offset | type   | field |
|--------|--------|-------|
| 0      | char[4]| magic `"TS8G"` |
| 4      | u16    | formatVersion = 1 |
| 6      | u16    | flags (bit0: has turn restrictions) |
| 8      | u32    | nodeCount |
| 12     | u32    | edgeCount |
| 16     | f64×4  | bbox west, south, east, north |
| 48     | u32    | nameTableOffset |
| 52     | u32    | nameTableBytes |
| 56     | u32    | geometryBlobOffset |
| 60     | u32    | headerCRC32 |

`geometryBlobOffset` is stored rather than derived: the blob sits between the
edge table and the name table, so a reader would otherwise have to know the
blob's total size, duplicating the writer's arithmetic on both sides. A
version-1 file written before this field was populated has `0` here; readers
treat `0` as "blob starts at `64 + 16×nodeCount + 24×edgeCount`".

### 3.2 Nodes

`nodeCount` records of 16 bytes starting at offset 64:

| type | field |
|------|-------|
| i32  | latE7 (lat × 1e7) |
| i32  | lonE7 (lon × 1e7) |
| u32  | firstEdge (index into edge array) |
| u32  | edgeCount |

Edges of a node are contiguous, so neighbours are
`edge[firstEdge .. firstEdge+edgeCount)`.

### 3.3 Edges

`edgeCount` records of 24 bytes starting at `64 + 16 × nodeCount`:

| type | field |
|------|-------|
| u32  | targetNode |
| u32  | distanceCm |
| u16  | flags |
| u16  | speedKph |
| u32  | nameIdx (into name table; 0xFFFFFFFF = unnamed) |
| u32  | reverseEdge (paired edge index; 0xFFFFFFFF = none) |
| u32  | geometryOffset (into the geometry blob; 0xFFFFFFFF = straight line) |

Edge flags:

| bit | meaning |
|-----|---------|
| 0   | street is one-way — **informational only** (see below) |
| 1   | toll |
| 2   | ferry |
| 3   | step / unsuitable for cars |
| 4-7 | road class (0 motorway … 7 service) |
| 8   | access prohibited for car |
| 9   | roundabout |
| 10  | tunnel |
| 11  | bridge |
| 12  | geometry blob is stored against travel direction; reverse it when drawing |

**Bit 0 does not block traversal.** The edge table stores *directed* edges, so
a one-way street is modelled by simply not emitting the opposite edge. A router
that skips every edge with bit 0 set makes every motorway unroutable. Bit 0 is
kept because the renderer uses it to draw one-way arrows and because a future
pedestrian profile needs to know.

**Bit 12** exists because the geometry blob is written once per undirected
segment. For a bidirectional street the two edges share one blob, and the
reverse edge must walk it backwards. This cannot be inferred from index
ordering: a `oneway=-1` way emits a single edge whose blob is already reversed,
so the flag is the only reliable signal.

Edges are laid out in CSR order: all edges of node `i` are contiguous at
`firstEdge(i) .. firstEdge(i)+edgeCount(i)`. The writer builds edges in segment
order and permutes them, remapping `reverseEdge` accordingly — a graph whose
edge table is not CSR-ordered will route from the wrong vertices without any
integrity check failing.

### 3.4 Geometry blob and `graph.idx`

The geometry blob stores intermediate polyline points for curved roads so
the on-device renderer can draw the route along the real road shape:

```
u32 count, then count × (i32 latE7, i32 lonE7)
```

`graph.idx` is the spatial grid used for snapping GPS fixes and for
origin/destination lookup:

```
u32 magic "TS8I"
u16 formatVersion = 1
u16 cellSizeE7        # cell edge length in 1e-7 degrees (e.g. 20000 = 0.002°)
u32 cols, u32 rows
f64 west, south, east, north
u32 cellStart[(cols*rows)+1]   # CSR offsets into cellNodes
u32 cellNodes[total]           # node ids, sorted, no duplicates
```

Lookup: `col = floor((lon - west) / cellDegrees)`, `row = floor((lat - south) / cellDegrees)`.

Snapping must ignore vertices that touch no car-accessible edge. A GPS fix on
a beach or in a park is often closest to a footway vertex; routing from there
reports `UNREACHABLE`, which reads like corrupt data rather than a bad snap.
Both readers build a `routableNode` mask (any incident edge with bit 8 clear,
in either direction) and pass it as a filter to the nearest-node search.

---

## 4. Compatibility rules

- All integers are little-endian, unaligned-safe (normal `ByteBuffer.order(LITTLE_ENDIAN)`).
- Readers must reject files whose magic or `formatVersion` is unknown and
  fall back to a visible error, never to demo data.
- Coordinates are `1e-7` degrees, giving ~1.1 cm resolution — well below GPS accuracy.
- Packs are read-only on device; the readers use `RandomAccessFile` /
  `FileChannel.map` so a 100 MB graph never has to be fully loaded into heap.
