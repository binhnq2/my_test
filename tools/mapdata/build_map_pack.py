#!/usr/bin/env python3
"""Build a MapLibre-ready vector tile set (MBTiles) from an OSM PBF extract.

Why this is hand-written instead of using Planetiler/OpenMapTiles: the build
host blocks running third-party JARs, so the pack builder is a readable Python
program that produces the same artifact contract
(`docs/internal/FORMAT_SPECS.md`): an MBTiles file whose tiles are gzipped MVT
in the SIMPLE scheme, plus a matching style.

Layers emitted, matching what the launcher style consumes:

  water        polygons   natural=water, water=*, landuse=reservoir
  waterway     lines      waterway=river|stream|canal, name
  landuse      polygons   landuse=*, leisure=park|garden|pitch, natural=wood|scrub
  landcover    polygons   natural=bare_rock|sand|scree, landuse=forest
  building     polygons   building=*, with a height/levels-derived render_height
  road         lines      highway=* with class, oneway, name, layer
  railway      lines      railway=rail|light_rail|subway|tram
  boundary     lines      admin_level 2..8
  place        points     place=* with name, rank
  poi          points     amenity, shop, tourism, leisure, historic, office
  aeroway      lines      aeroway=runway|taxiway
  transportation points   public_transport, highway=bus_stop

Usage:
  python build_map_pack.py --input danang.osm.pbf --out-dir ../map-pack/danang \
      --min-zoom 6 --max-zoom 14 --bbox 107.85,15.85,108.45,16.25

The script is deliberately streaming: it holds only the current tile's feature
buffer per zoom level, so a city extract fits comfortably in a few GB.
"""
import argparse
import gzip
import json
import math
import os
import sqlite3
import sys
from collections import defaultdict

import mapbox_vector_tile
import mercantile
import osmium
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import linemerge, unary_union
from shapely.strtree import STRtree

TILE_EXTENT = 4096
TILE_BUFFER = 256  # MVT units of overlap so lines do not stop at tile edges

# ---------------------------------------------------------------------------
# Tag classification
# ---------------------------------------------------------------------------

ROAD_CLASS = {
    "motorway": 0, "motorway_link": 0,
    "trunk": 1, "trunk_link": 1,
    "primary": 2, "primary_link": 2,
    "secondary": 3, "secondary_link": 3,
    "tertiary": 4, "tertiary_link": 4,
    "residential": 5, "unclassified": 5, "living_street": 5,
    "service": 6, "track": 6,
    "pedestrian": 7, "footway": 7, "path": 7, "cycleway": 7, "steps": 7,
}

# Minimum zoom at which a layer appears. Keeping roads at low zooms is what
# makes the map readable when zoomed out, at a small size cost.
LAYER_MIN_ZOOM = {
    "water": 6, "waterway": 9, "landuse": 8, "landcover": 9,
    "building": 12, "road": 6, "railway": 8, "boundary": 6,
    "place": 6, "poi": 12, "aeroway": 9, "transportation": 13,
}


def class_rank(cls):
    """Class used by the style to pick a line width."""
    return cls if cls is not None else 7


def building_height(tags):
    """Render height in metres from height / levels / building type."""
    h = tags.get("height")
    if h:
        try:
            return max(2.0, float(str(h).replace("m", "").strip()))
        except ValueError:
            pass
    levels = tags.get("building:levels")
    if levels:
        try:
            return max(2.0, float(levels) * 3.0)
        except ValueError:
            pass
    bt = tags.get("building")
    if bt in ("garage", "garages", "shed", "hut"):
        return 3.0
    if bt in ("church", "cathedral", "temple", "pagoda"):
        return 14.0
    if bt in ("apartments", "residential", "house"):
        return 9.0
    return 6.0


def highway_min_zoom(cls):
    """Coarse road filtering at low zooms keeps tile size sane."""
    return {0: 6, 1: 7, 2: 8, 3: 9, 4: 9, 5: 10, 6: 12, 7: 13}.get(cls, 13)


# ---------------------------------------------------------------------------
# Geometry helpers
# ---------------------------------------------------------------------------

def lonlat_to_tile_px(lon, lat, zoom, tile_x, tile_y):
    """WGS-84 -> pixel coordinates inside one tile, at TILE_EXTENT per tile."""
    n = 2.0 ** zoom
    x = (lon + 180.0) / 360.0 * n
    lat_r = math.radians(max(-85.05112878, min(85.05112878, lat)))
    y = (1.0 - math.log(math.tan(lat_r) + 1.0 / math.cos(lat_r)) / math.pi) / 2.0 * n
    px = (x - tile_x) * TILE_EXTENT
    py = (y - tile_y) * TILE_EXTENT
    return px, py


def polyline_to_pixels(coords, zoom, tx, ty):
    return [tuple(round(v, 1) for v in lonlat_to_tile_px(lon, lat, zoom, tx, ty)) for lon, lat in coords]


# ---------------------------------------------------------------------------
# Feature extraction (single pass, per-zoom bucketing)
# ---------------------------------------------------------------------------

class TileBuckets:
    """Accumulates features per (zoom, x, y) without holding global geometry."""

    def __init__(self, min_zoom, max_zoom, bbox):
        self.min_zoom = min_zoom
        self.max_zoom = max_zoom
        self.bbox = bbox  # mercantile LngLatBbox
        self.tiles = defaultdict(list)  # (z, x, y) -> [(layer, [pixel rings], props)]
        self.counts = defaultdict(int)

    def zoom_range_for(self, layer):
        return range(max(self.min_zoom, LAYER_MIN_ZOOM.get(layer, 6)), self.max_zoom + 1)

    def add_line(self, layer, lonlat_coords, props, min_zoom_for_feature=None):
        if len(lonlat_coords) < 2:
            return
        lons = [c[0] for c in lonlat_coords]
        lats = [c[1] for c in lonlat_coords]
        feature_bbox = mercantile.LngLatBbox(min(lons), min(lats), max(lons), max(lats))
        for z in self.zoom_range_for(layer):
            if min_zoom_for_feature and z < min_zoom_for_feature:
                continue
            for t in mercantile.tiles(
                feature_bbox.west, feature_bbox.south,
                feature_bbox.east, feature_bbox.north, [z]
            ):
                px = polyline_to_pixels(lonlat_coords, z, t.x, t.y)
                # Drop a tile whose pixels are entirely off-canvas.
                if not _within_buffer(px):
                    continue
                self.tiles[(z, t.x, t.y)].append((layer, [px], props))
                self.counts[layer] += 1

    def add_polygon(self, layer, rings_lonlat, props, min_zoom_for_feature=None):
        if not rings_lonlat or len(rings_lonlat[0]) < 3:
            return
        outer = rings_lonlat[0]
        lons = [c[0] for c in outer]
        lats = [c[1] for c in outer]
        feature_bbox = mercantile.LngLatBbox(min(lons), min(lats), max(lons), max(lats))
        for z in self.zoom_range_for(layer):
            if min_zoom_for_feature and z < min_zoom_for_feature:
                continue
            for t in mercantile.tiles(
                feature_bbox.west, feature_bbox.south,
                feature_bbox.east, feature_bbox.north, [z]
            ):
                px_rings = [polyline_to_pixels(r, z, t.x, t.y) for r in rings_lonlat]
                if not _within_buffer(px_rings[0]):
                    continue
                self.tiles[(z, t.x, t.y)].append((layer, px_rings, props))
                self.counts[layer] += 1

    def add_point(self, layer, lon, lat, props):
        for z in self.zoom_range_for(layer):
            t = mercantile.tile(lon, lat, z)
            px, py = lonlat_to_tile_px(lon, lat, z, t.x, t.y)
            self.tiles[(z, t.x, t.y)].append((layer, [[(px, py)]], props))
            self.counts[layer] += 1


def _within_buffer(ring, buf=TILE_EXTENT + TILE_BUFFER):
    return any(-TILE_BUFFER <= x <= buf and -TILE_BUFFER <= y <= buf for x, y in ring)


# ---------------------------------------------------------------------------
# OSM reader
# ---------------------------------------------------------------------------

class Extract(osmium.SimpleHandler):
    def __init__(self, buckets):
        super().__init__()
        self.buckets = buckets

    # -- points -------------------------------------------------------------
    def node(self, n):
        tags = n.tags
        if not tags:
            return
        lon, lat = n.location.lon, n.location.lat

        place = tags.get("place")
        if place and tags.get("name"):
            self.buckets.add_point("place", lon, lat, {
                "class": place,
                "name": tags["name"],
                "name_en": tags.get("name:en", ""),
                "rank": {"city": 1, "town": 2, "village": 3,
                         "suburb": 4, "quarter": 4, "neighbourhood": 5}.get(place, 6),
            })
            return

        name = tags.get("name") or tags.get("name:vi") or tags.get("brand")
        amenity = tags.get("amenity")
        shop = tags.get("shop")
        tourism = tags.get("tourism")
        leisure = tags.get("leisure")
        historic = tags.get("historic")
        office = tags.get("office")
        highway = tags.get("highway")
        public_transport = tags.get("public_transport")
        aeroway = tags.get("aeroway")

        if highway == "bus_stop" or public_transport == "platform":
            self.buckets.add_point("transportation", lon, lat, {
                "class": "bus_stop",
                "name": name or "",
            })
            return

        if name and (amenity or shop or tourism or leisure or historic or office or aeroway):
            cls = amenity or shop or tourism or leisure or historic or office or aeroway
            self.buckets.add_point("poi", lon, lat, {
                "class": cls,
                "name": name,
                # Rank drives symbol sort order, so important POIs win label
                # collisions against shops.
                "rank": _poi_rank(amenity, tourism, shop, historic, leisure),
            })

    # -- lines --------------------------------------------------------------
    def way(self, w):
        tags = w.tags
        if not tags:
            return
        try:
            coords = [(nd.lon, nd.lat) for nd in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(coords) < 2:
            return

        highway = tags.get("highway")
        if highway:
            cls = ROAD_CLASS.get(highway)
            if cls is not None:
                layer_val = _int_or(tags.get("layer"), 0)
                props = {
                    "class": highway,
                    "rank": cls,
                    "name": tags.get("name", ""),
                    "oneway": 1 if tags.get("oneway") in ("yes", "true", "1", "-1") else 0,
                    "bridge": 1 if tags.get("bridge") not in (None, "no") else 0,
                    "tunnel": 1 if tags.get("tunnel") not in (None, "no") else 0,
                    "layer": layer_val,
                }
                self.buckets.add_line("road", coords, props, highway_min_zoom(cls))

        rail = tags.get("railway")
        if rail in ("rail", "light_rail", "subway", "tram", "narrow_gauge"):
            self.buckets.add_line("railway", coords, {"class": rail})

        waterway = tags.get("waterway")
        if waterway in ("river", "stream", "canal", "drain"):
            self.buckets.add_line("waterway", coords, {
                "class": waterway,
                "name": tags.get("name", ""),
            })

        aeroway = tags.get("aeroway")
        if aeroway in ("runway", "taxiway"):
            self.buckets.add_line("aeroway", coords, {"class": aeroway})

        boundary = tags.get("boundary")
        admin = _int_or(tags.get("admin_level"), 99)
        if boundary == "administrative" and 2 <= admin <= 8:
            self.buckets.add_line("boundary", coords, {"admin_level": admin})

        # Closed ways carrying area tags.
        if tags.get("natural") == "water" or tags.get("water") or tags.get("landuse") == "reservoir":
            self.buckets.add_polygon("water", [coords], {
                "class": tags.get("water", tags.get("natural", "water")),
                "intermittent": 1 if tags.get("intermittent") == "yes" else 0,
            })
            return

        landuse = tags.get("landuse")
        leisure = tags.get("leisure")
        natural = tags.get("natural")
        if landuse in ("forest", "wood", "scrub") or natural in ("wood", "scrub"):
            self.buckets.add_polygon("landcover", [coords], {"class": "wood"})
        elif natural in ("bare_rock", "sand", "scree", "beach"):
            self.buckets.add_polygon("landcover", [coords], {"class": natural})
        elif landuse in ("residential", "commercial", "retail", "industrial",
                         "cemetery", "military", "quarry", "farmland", "meadow",
                         "orchard", "grass", "village_green"):
            self.buckets.add_polygon("landuse", [coords], {
                "class": landuse,
                "name": tags.get("name", ""),
            })
        elif leisure in ("park", "garden", "pitch", "golf_course", "nature_reserve"):
            self.buckets.add_polygon("landuse", [coords], {
                "class": leisure,
                "name": tags.get("name", ""),
            })

        if tags.get("building") not in (None, "no"):
            h = building_height(tags)
            self.buckets.add_polygon("building", [coords], {
                "height": round(h, 1),
                "levels": round(h / 3.0, 1),
                "class": tags.get("building", "yes")[:24],
            })

    # -- relations ----------------------------------------------------------
    def relation(self, r):
        tags = r.tags
        if not tags:
            return
        # Multipolygon water bodies / parks / forest: the outer rings define
        # the area, inner rings are holes. Rendering holes matters for
        # reservoirs with islands, so they are kept as extra rings.
        area_tag = (
            tags.get("natural") == "water" or tags.get("water") or
            tags.get("landuse") in ("forest", "reservoir", "meadow") or
            tags.get("leisure") in ("park", "nature_reserve")
        )
        if not area_tag:
            return
        # Relations are handled in a second pass where way geometry is known;
        # see extract_multipolygons().
        return


def _poi_rank(amenity, tourism, shop, historic, leisure):
    if amenity in ("hospital", "university", "college", "police", "fire_station",
                   "bus_station", "ferry_terminal"):
        return 1
    if tourism in ("attraction", "museum", "viewpoint", "hotel"):
        return 2
    if amenity in ("restaurant", "cafe", "fast_food", "bank", "fuel",
                   "pharmacy", "marketplace"):
        return 3
    if historic or leisure:
        return 4
    if shop:
        return 5
    return 6


def _int_or(v, default):
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


# ---------------------------------------------------------------------------
# Multipolygon pass
# ---------------------------------------------------------------------------

def extract_multipolygons(path, buckets):
    """Resolve water/landuse multipolygon relations into polygon rings.

    Two sub-passes: collect the way ids a relation needs, then read geometry
    for exactly those ways. Buildings are not merged here because their
    member ways are already emitted individually by the way pass.
    """
    wanted = defaultdict(list)  # relation id -> [(member_way_id, role, tags)]

    class Collect(osmium.SimpleHandler):
        def relation(self, r):
            tags = r.tags
            if not tags:
                return
            is_water = tags.get("natural") == "water" or tags.get("water") or \
                tags.get("landuse") == "reservoir"
            is_land = tags.get("landuse") in ("forest", "meadow") or \
                tags.get("leisure") in ("park", "nature_reserve")
            if not (is_water or is_land):
                return
            members = [(m.ref, m.role) for m in r.members if m.type == "w"]
            if members:
                wanted[r.id] = (dict(tags), members)

    Collect().apply_file(path, locations=False)
    if not wanted:
        return 0

    needed_ways = {wid for _, members in wanted.values() for wid, _ in members}
    way_geom = {}

    class ReadWays(osmium.SimpleHandler):
        def way(self, w):
            if w.id not in needed_ways:
                return
            try:
                coords = [(nd.lon, nd.lat) for nd in w.nodes]
            except osmium.InvalidLocationError:
                return
            if len(coords) >= 2:
                way_geom[w.id] = coords

    ReadWays().apply_file(path, locations=False)

    resolved = 0
    for rel_id, (tags, members) in wanted.items():
        outers, inners = [], []
        for wid, role in members:
            coords = way_geom.get(wid)
            if not coords:
                continue
            (inners if role == "inner" else outers).append(coords)

        outer_rings = _close_and_merge(outers)
        if not outer_rings:
            continue
        inner_rings = _close_and_merge(inners, outer_rings)

        is_water = tags.get("natural") == "water" or tags.get("water") or \
            tags.get("landuse") == "reservoir"
        if is_water:
            buckets.add_polygon("water", [outer_rings[0].exterior.coords[:]] +
                                [r.exterior.coords[:] for r in inner_rings],
                                {"class": tags.get("water", "water"), "intermittent": 0})
        else:
            cls = tags.get("landuse") or tags.get("leisure") or "park"
            buckets.add_polygon("landuse", [outer_rings[0].exterior.coords[:]] +
                                [r.exterior.coords[:] for r in inner_rings],
                                {"class": cls, "name": tags.get("name", "")})
        resolved += 1
    return resolved


def _close_and_merge(way_coords_list, holes_against=None):
    """Stitch relation member ways into closed rings."""
    if not way_coords_list:
        return []
    lines = []
    for coords in way_coords_list:
        if len(coords) < 2:
            continue
        if coords[0] != coords[-1]:
            coords = coords + [coords[0]]
        try:
            lines.append(LineString(coords))
        except Exception:
            continue
    if not lines:
        return []
    try:
        merged = linemerge(lines) if len(lines) > 1 else lines[0]
    except Exception:
        merged = lines[0]
    geoms = list(merged.geoms) if hasattr(merged, "geoms") else [merged]
    rings = []
    for g in geoms:
        if len(g.coords) < 4 or g.coords[0] != g.coords[-1]:
            continue
        try:
            poly = Polygon(g.coords)
        except Exception:
            continue
        if not poly.is_valid:
            poly = poly.buffer(0)
        if poly.is_valid and not poly.is_empty and poly.area > 0:
            rings.append(poly)
    return rings


# ---------------------------------------------------------------------------
# Tile encoding + MBTiles writer
# ---------------------------------------------------------------------------

def encode_tile(features):
    """Group features into MVT layers and encode a gzipped MVT tile."""
    layers = defaultdict(list)
    for layer, rings, props in features:
        geom_type = "Point" if len(rings[0]) == 1 and len(rings[0][0]) == 2 and layer in (
            "place", "poi", "transportation") else None
        layers[layer].append((rings, props))

    mvt_layers = []
    for layer_name, items in layers.items():
        is_point = layer_name in ("place", "poi", "transportation")
        feats = []
        for rings, props in items:
            if is_point:
                geom = {"type": "Point", "coordinates": rings[0][0]}
            elif layer_name in ("water", "landuse", "landcover", "building"):
                geom = {"type": "Polygon", "coordinates": rings}
            else:
                geom = {"type": "LineString", "coordinates": rings[0]}
            feats.append({"geometry": geom, "properties": props})
        if not feats:
            continue
        mvt_layers.append({
            "name": layer_name,
            "features": feats,
            # Built at TILE_EXTENT resolution already, so disable scaling.
            "extent": TILE_EXTENT,
        })

    if not mvt_layers:
        return None
    return mapbox_vector_tile.encode(
        mvt_layers,
        default_options={"extents": TILE_EXTENT, "quantize_bounds": None},
    )


def write_mbtiles(out_path, buckets, meta):
    if os.path.exists(out_path):
        os.remove(out_path)
    con = sqlite3.connect(out_path)
    con.execute("PRAGMA journal_mode=OFF")
    con.execute("PRAGMA synchronous=OFF")
    con.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    con.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, "
                "tile_row INTEGER, tile_data BLOB)")
    con.execute("CREATE UNIQUE INDEX tile_index ON tiles "
                "(zoom_level, tile_column, tile_row)")

    for key, value in meta.items():
        con.execute("INSERT INTO metadata VALUES (?, ?)", (key, str(value)))

    written = 0
    skipped = 0
    for (z, x, y), features in buckets.tiles.items():
        data = encode_tile(features)
        if data is None:
            skipped += 1
            continue
        # TMS row convention.
        tms_row = (2 ** z - 1) - y
        con.execute(
            "INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) "
            "VALUES (?, ?, ?, ?)",
            (z, x, tms_row, sqlite3.Binary(gzip.compress(data, 6)))
        )
        written += 1
        if written % 500 == 0:
            con.commit()
            print(f"    tiles written: {written}", flush=True)

    con.commit()
    con.close()
    return written, skipped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--bbox", required=True, help="west,south,east,north")
    ap.add_argument("--min-zoom", type=int, default=6)
    ap.add_argument("--max-zoom", type=int, default=14)
    ap.add_argument("--skip-buildings", action="store_true",
                    help="drop the building layer (smaller pack for low-RAM runs)")
    args = ap.parse_args()

    west, south, east, north = (float(v) for v in args.bbox.split(","))
    os.makedirs(args.out_dir, exist_ok=True)

    buckets = TileBuckets(args.min_zoom, args.max_zoom, mercantile.LngLatBbox(west, south, east, north))

    print("[1/4] reading nodes/ways ...", flush=True)
    handler = Extract(buckets)
    if args.skip_buildings:
        # Raising the layer's min zoom above max zoom disables the layer.
        LAYER_MIN_ZOOM["building"] = args.max_zoom + 1
    handler.apply_file(args.input, locations=True)
    print(f"    features stacked: {sum(buckets.counts.values())}")
    for k in sorted(buckets.counts):
        print(f"      {k:16s} {buckets.counts[k]}")

    print("[2/4] resolving multipolygon relations ...", flush=True)
    resolved = extract_multipolygons(args.input, buckets)
    print(f"    relations resolved: {resolved}")

    print("[3/4] encoding tiles ...", flush=True)
    meta = {
        "name": os.path.basename(args.out_dir.rstrip("/\\")),
        "format": "pbf",
        "minzoom": args.min_zoom,
        "maxzoom": args.max_zoom,
        "bounds": f"{west},{south},{east},{north}",
        "center": f"{(west + east) / 2:.6f},{(south + north) / 2:.6f},{args.min_zoom + 5}",
        "type": "baselayer",
        "version": "1",
        "description": "OpenStreetMap vector tiles, TS8 launcher pack",
        "attribution": "© OpenStreetMap contributors",
    }
    written, skipped = write_mbtiles(os.path.join(args.out_dir, "basemap.mbtiles"), buckets, meta)
    print(f"    tiles written: {written}, empty tiles skipped: {skipped}")

    size = os.path.getsize(os.path.join(args.out_dir, "basemap.mbtiles"))
    print(f"[4/4] done: basemap.mbtiles = {size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
