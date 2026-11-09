# Multi-Region Map Strategy

## Problem

The TS8 head unit targets drivers across all of Vietnam. A driver in Da Nang needs
Da Nang's roads; a driver in Hanoi needs Hanoi's. A driver planning a trip from
Hanoi to Saigon needs sign coverage and routing data for the entire corridor.

The device has:
- 8-16 GB internal storage (after system)
- No Play Services, no reliable internet
- Map packs loaded from USB or app-private storage

## Architecture

### Layer 1: Nationwide Sign Data (in APK)

Traffic sign data is **already nationwide** in the APK assets directory `vnsigns/`.
It covers all 178 grid cells of Vietnam (~6.8 MB total). This means:

- Speed camera alerts work everywhere, with no pack installed
- Speed limit signs are available on any road, not just mapped regions
- No per-region sign download is needed

The sign data is loaded by `TrafficSignStore` on first use and cached in a spatial
grid for O(1) proximity queries. The `SignClusterer` prevents icon overlap by
zoom-based filtering and 20m proximity deduplication.

### Layer 2: Per-Region Map Tile Packs

Map tiles (`.mbtiles`) are **per-region** and loaded from external storage. Each
pack contains:

- `basemap.mbtiles` — vector tiles (typically 40-80 MB per region)
- `places.db` — FTS4 place search index (2-3 MB)
- `routing-graph/` — A* routing graph (1-5 MB)
- `fonts/` — Noto Sans glyphs for offline labels
- `metadata.json` — bounds, zoom range, attribution

#### Pack Discovery

`MapPack.discover()` walks these roots in priority order:
1. App-private storage (`/data/data/com.ts8.launcher/files/map-pack/`)
2. App-external storage (`/Android/data/com.ts8.launcher/files/map-pack/`)
3. Shared storage (`/storage/emulated/0/ts8/map-pack/`)
4. USB storage (`/storage/usb0/ts8/map-pack/`, etc.)

Multiple packs can coexist — `listPacks()` collects all valid packs across all
roots. The driver chooses which to activate via Map Management UI.

#### Pack Naming Convention

Packs follow the pattern: `<region>-<version>/`

Examples:
- `danang-quangnam-2026.10/` — Da Nang & Quang Nam
- `hanoi-2026.10/` — Hà Nội
- `hochiminh-2026.10/` — TP. Hồ Chí Minh
- `vungtau-2026.10/` — Bà Rịa - Vũng Tàu

### Layer 3: Route Corridor Packs (for long trips)

For long-distance trips (e.g., Hanoi → Saigon, ~1700 km), a single region pack
is not enough. The strategy:

1. **Driver requests route** (voice or search)
2. **Routing engine computes A* path** using the installed pack's routing graph
   — if the destination is outside the pack's bounds, the engine reports
   `NO_END_NODE` honestly
3. **Corridor pack download** — when internet is available, the app fetches a
   "corridor pack" that contains tiles + routing graph for a 10km buffer along
   the route. This is a server-side operation that pre-computes the corridor.
4. **Fallback: waypoint packs** — if corridor download is unavailable, the
   driver can install packs for intermediate cities along the route

#### Corridor Pack Size

A 1700 km route with a 10 km buffer covers ~17,000 km². At zoom 6-14 vector
tiles, this is approximately 150-200 MB — within USB stick capacity.

## User Experience

### Initial Setup

1. Driver installs the launcher
2. On first map open, sees "Chưa cài map pack" with instructions
3. Driver obtains a pack for their home province (USB stick, download)
4. Pack is imported via Map Management → Import

### Daily Use

- Driver sees their region's map with traffic signs overlaid
- Speed alerts work from the nationwide sign data even before a pack is installed
- Voice navigation works within the pack's bounds

### Long-Distance Travel

1. Driver says "dẫn đường tới Sài Gòn"
2. If destination is outside current pack bounds:
   - App reports "Điểm đến nằm ngoài vùng bản đồ đã cài"
   - Suggests installing the destination region's pack
   - If internet available, offers corridor pack download
3. Once corridor pack is installed, full turn-by-turn navigation works end-to-end

### Multi-Province Users

Drivers who regularly travel between provinces (e.g., Da Nang ↔ Quảng Nam ↔ Huế)
can install multiple packs simultaneously. The Map Management screen lists all
installed packs and lets the driver switch the active one. The app does not
auto-switch based on GPS position — the driver chooses, because:

- Auto-switching discards the current route
- The driver may want to stay on a broader pack even when a detailed one is
  available
- A wrong auto-switch in a tunnel (where GPS is lost) would be confusing

## Future: Vietnam National Pack

A future "Vietnam National" pack at zoom 6-10 (major highways only) could provide
nationwide routing without per-region installation. Estimated size: 200-300 MB.
This would cover the Hanoi → Saigon case at highway level, with detailed packs
added for start/end cities.

## Technical Implementation Status

| Component | Status |
|---|---|
| Nationwide sign data in APK | ✅ Complete (178 cells, 6.8 MB) |
| SignClusterer (overlap prevention) | ✅ Complete |
| Per-region pack discovery | ✅ Complete (`MapPack.discover()`) |
| Multiple pack coexistence | ✅ Complete (`listPacks()`) |
| Pack activation/switching | ✅ Complete (Map Management UI) |
| Pack import from USB | ✅ Complete (`PackImporter`) |
| Corridor pack download | 🔲 Future (requires server) |
| National highway pack | 🔲 Future |
| Auto-switch between packs | 🔲 Not planned (driver choice) |
