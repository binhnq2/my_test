#!/usr/bin/env python3
"""Build the offline routing graph (`graph.bin` + `graph.idx`) from OSM PBF.

Format contract: `docs/internal/FORMAT_SPECS.md` section 3.

Approach
--------
1. Stream ways and keep only car-drivable highways. A way becomes a routing
   *segment*; nodes are the OSM nodes where segments meet (`>= 3 incident
   ways`) plus both endpoints of every way, so no segment is ever split in a
   way the graph cannot express.
2. Simplify each way in place: keep the endpoints and any point needed to stay
   within `--simplify-m` metres of the original line. Simplified interior
   points go into the geometry blob for on-device route drawing.
3. Build directed edges: one per direction unless the way is oneway.
4. Emit the CSR-style node table, the edge table, the geometry blob, a name
   table, and the spatial grid index used for GPS snapping.

The output is memory-mapped on device, so the build keeps everything in flat
arrays and writes them once with `struct`/`array` rather than per-record
objects.
"""
import argparse
import array
import math
import os
import re
import struct
import sys
from collections import defaultdict

import osmium

MAGIC_GRAPH = b"TS8G"
MAGIC_INDEX = b"TS8I"
FORMAT_VERSION = 1
NONE_U32 = 0xFFFFFFFF

# Car-drivable highway classes: class id -> (speed kph, is oneway-able)
ROAD_CLASS = {
    "motorway": (0, 100), "motorway_link": (0, 60),
    "trunk": (1, 90), "trunk_link": (1, 50),
    "primary": (2, 70), "primary_link": (2, 40),
    "secondary": (3, 60), "secondary_link": (3, 35),
    "tertiary": (4, 50), "tertiary_link": (4, 30),
    "residential": (5, 35), "unclassified": (5, 35), "living_street": (5, 20),
    "service": (6, 20), "track": (6, 15),
    "pedestrian": (7, 10), "footway": (7, 5), "path": (7, 5),
    "cycleway": (7, 10), "steps": (7, 2),
}

# Classes a car may not drive on. Kept in the graph (so the map and any future
# pedestrian profile work) but flagged inaccessible.
CAR_FORBIDDEN = {"footway", "path", "cycleway", "steps", "pedestrian", "track"}

ONEWAY_TAGS = {"yes", "true", "1", "-1"}

LAT_SCALE = 1e7


def parse_maxspeed(raw):
    """OSM `maxspeed` -> km/h, or None when unusable.

    The pack used to derive every edge speed from a per-highway-class table, so
    a 50 km/h residential street and a signed 30 km/h school zone were routed
    and speed-limited identically. Prefer the real posted limit when OSM has
    one; fall back to the class default only when the tag is absent/garbled.
    Handles `50`, `50 km/h`, `50;30`, `VN:urban`, and `mph` values.
    """
    if not raw:
        return None
    text = str(raw).strip().lower()
    if text in ("none", "no", "unposted", "unknown", "variable", "signals", ""):
        return None
    if "mph" in text:
        head = text.split("mph")[0]
        nums = [int(x) for x in re.findall(r"\d+", head)]
        return int(round(nums[0] * 1.609344)) if nums else None
    # Common OSM shorthands that carry no numeric limit.
    if text.startswith("vn:") or text.startswith("urban") or text.startswith("rural"):
        return None
    nums = [int(x) for x in re.findall(r"\d+", text.split(";")[0])]
    if not nums:
        return None
    value = nums[0]
    return value if 5 <= value <= 150 else None


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def perp_distance(p, a, b):
    """Distance from p to segment ab in metres (equirectangular approximation)."""
    # Project to a local metric plane; adequate for the <1 km segments used here.
    lat0 = math.radians(p[0])
    kx = 111320.0 * math.cos(lat0)
    ky = 110540.0
    ax, ay = a[1] * kx, a[0] * ky
    bx, by = b[1] * kx, b[0] * ky
    px, py = p[1] * kx, p[0] * ky
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def douglas_peucker(coords, tolerance_m):
    """Iterative Ramer-Douglas-Peucker so long ways cannot blow the stack."""
    if len(coords) < 3:
        return list(coords)
    keep = [False] * len(coords)
    keep[0] = keep[-1] = True
    stack = [(0, len(coords) - 1)]
    while stack:
        start, end = stack.pop()
        if end <= start + 1:
            continue
        a, b = coords[start], coords[end]
        max_d = -1.0
        max_i = -1
        for i in range(start + 1, end):
            d = perp_distance(coords[i], a, b)
            if d > max_d:
                max_d, max_i = d, i
        if max_d > tolerance_m and max_i > 0:
            keep[max_i] = True
            stack.append((start, max_i))
            stack.append((max_i, end))
    return [c for c, k in zip(coords, keep) if k]


class WayCollector:
    """First pass: decide which ways enter the graph and where they split."""

    def __init__(self, bbox):
        self.west, self.south, self.east, self.north = bbox
        self.ways = []          # (way_id, [(node_id, lat, lon)], [tags of interest])
        self.node_ref_count = defaultdict(int)  # node -> incident car ways

    def in_bbox(self, lat, lon):
        return (self.west <= lon <= self.east and self.south <= lat <= self.north)

    def accept(self, tags):
        hw = tags.get("highway")
        if hw not in ROAD_CLASS:
            return None
        if tags.get("access") in ("no", "private"):
            return None
        if tags.get("motor_vehicle") in ("no",) or tags.get("vehicle") in ("no",):
            return None
        return hw


class PbfHandler(osmium.SimpleHandler):
    def __init__(self, collector):
        super().__init__()
        self.c = collector

    def way(self, w):
        hw = self.c.accept(w.tags)
        if hw is None:
            return
        try:
            nodes = []
            for nd in w.nodes:
                if not self.c.in_bbox(nd.lat, nd.lon):
                    raise StopIteration
                nodes.append((nd.ref, nd.lat, nd.lon))
        except (osmium.InvalidLocationError, StopIteration):
            return
        if len(nodes) < 2:
            return

        tags = w.tags
        oneway = tags.get("oneway", "")
        if oneway == "-1":
            oneway_dir = -1
        elif oneway in ONEWAY_TAGS or hw in ("motorway", "motorway_link"):
            oneway_dir = 1
        elif tags.get("junction") == "roundabout":
            oneway_dir = 1
        else:
            oneway_dir = 0

        class_speed = ROAD_CLASS[hw][1]
        posted = parse_maxspeed(tags.get("maxspeed"))
        self.c.ways.append((w.id, nodes, {
            "class": ROAD_CLASS[hw][0],
            # A signed limit beats a class average: routing cost and the
            # on-screen limit roundel must describe the same road.
            "speed": posted if posted else class_speed,
            "name": tags.get("name", ""),
            "oneway": oneway_dir,
            "car_forbidden": 1 if hw in CAR_FORBIDDEN else 0,
            "toll": 1 if tags.get("toll") in ("yes", "true", "1") else 0,
            "ferry": 0,
            "roundabout": 1 if tags.get("junction") == "roundabout" else 0,
            "tunnel": 1 if tags.get("tunnel") not in (None, "no") else 0,
            "bridge": 1 if tags.get("bridge") not in (None, "no") else 0,
            "highway": hw,
        }))
        for nd in nodes:
            self.c.node_ref_count[nd[0]] += 1


def build_graph(ways, node_ref_count, simplify_m):
    """Turn OSM ways into a node/edge graph with geometry blobs."""
    # A node becomes a graph vertex when it is shared by 2+ ways (a junction)
    # or is an endpoint of any way: splitting anywhere else would break a
    # continuous way into disconnected pieces.
    endpoint = set()
    for _, nodes, _ in ways:
        endpoint.add(nodes[0][0])
        endpoint.add(nodes[-1][0])

    # Vertices: junction nodes and way endpoints.
    keep_ref = {nid for nid, cnt in node_ref_count.items() if cnt >= 2} | endpoint

    node_index = {}          # osm node id -> graph node id
    node_coords = []         # graph node id -> (lat, lon)
    segments = []            # (from_graph_id, to_graph_id, tags, [interior points])

    def vertex_for(nid, lat, lon):
        gi = node_index.get(nid)
        if gi is None:
            gi = len(node_coords)
            node_index[nid] = gi
            node_coords.append((lat, lon))
        return gi

    for way_id, nodes, tags in ways:
        # Split the way at every kept node.
        current = [nodes[0]]
        for i in range(1, len(nodes)):
            current.append(nodes[i])
            nid = nodes[i][0]
            if nid in keep_ref or i == len(nodes) - 1:
                latlon = [(n[1], n[2]) for n in current]
                simplified = douglas_peucker(latlon, simplify_m)
                from_id = vertex_for(current[0][0], current[0][1], current[0][2])
                to_id = vertex_for(current[-1][0], current[-1][1], current[-1][2])
                if from_id != to_id:
                    interior = simplified[1:-1]
                    segments.append((from_id, to_id, tags, interior))
                current = [current[-1]]
    return node_coords, segments


def write_graph(out_dir, node_coords, segments, bbox, has_restrictions=False):
    os.makedirs(out_dir, exist_ok=True)

    # -- name table --------------------------------------------------------
    name_to_idx = {}
    name_bytes = bytearray()
    for _, _, tags, _ in segments:
        nm = tags["name"]
        if nm and nm not in name_to_idx:
            name_to_idx[nm] = len(name_to_idx)
            encoded = nm.encode("utf-8")
            name_bytes += struct.pack("<H", len(encoded)) + encoded
    name_table = bytes(name_bytes)

    # -- geometry blob -----------------------------------------------------
    geo_blob = bytearray()
    geo_offsets = {}
    for seg_i, (_, _, _, interior) in enumerate(segments):
        if not interior:
            geo_offsets[seg_i] = NONE_U32
            continue
        geo_offsets[seg_i] = len(geo_blob)
        geo_blob += struct.pack("<I", len(interior))
        for lat, lon in interior:
            geo_blob += struct.pack("<ii", int(round(lat * LAT_SCALE)), int(round(lon * LAT_SCALE)))

    # -- edges (CSR) -------------------------------------------------------
    # Two directed edges per bidirectional segment; reverseEdge pairs them so
    # the router can turn around without a U-turn instruction.
    node_count = len(node_coords)
    edges = []  # (target, dist_cm, flags, speed, name_idx, reverse, geo_offset)
    node_edges = [[] for _ in range(node_count)]

    def edge_flags(tags):
        """Flags that do not depend on travel direction.

        Bit 0 (one-way) is informational only: this graph stores *directed*
        edges, so a one-way street is expressed by simply not emitting the
        opposite edge. Setting bit 0 to block traversal would make every
        motorway unroutable.
        """
        f = 0
        if tags["toll"]:
            f |= 1 << 1
        if tags["ferry"]:
            f |= 1 << 2
        if tags["car_forbidden"]:
            f |= 1 << 8
        if tags["roundabout"]:
            f |= 1 << 9
        if tags["tunnel"]:
            f |= 1 << 10
        if tags["bridge"]:
            f |= 1 << 11
        f |= (tags["class"] & 0xF) << 4
        return f

    FLAG_ONEWAY = 1 << 0          # informational: street is one-way
    FLAG_GEOM_REVERSED = 1 << 12  # edge traverses its geometry blob backwards

    for seg_i, (from_id, to_id, tags, interior) in enumerate(segments):
        lat1, lon1 = node_coords[from_id]
        lat2, lon2 = node_coords[to_id]
        # Distance follows the real geometry, not the straight chord.
        dist = 0.0
        prev = (lat1, lon1)
        for p in interior:
            dist += haversine_m(prev[0], prev[1], p[0], p[1])
            prev = p
        dist += haversine_m(prev[0], prev[1], lat2, lon2)
        dist_cm = max(1, int(round(dist * 100)))

        name_idx = name_to_idx.get(tags["name"], NONE_U32)
        geo = geo_offsets[seg_i]
        base = edge_flags(tags)
        oneway = tags["oneway"]

        if oneway == -1:
            # Traffic runs against the way's node order: emit only to_id -> from_id,
            # and walk the shared geometry blob backwards.
            rev_edge = len(edges)
            edges.append([from_id, dist_cm, base | FLAG_ONEWAY | FLAG_GEOM_REVERSED,
                          tags["speed"], name_idx, NONE_U32, geo])
            node_edges[to_id].append(rev_edge)
        else:
            fwd_flags = base | (FLAG_ONEWAY if oneway == 1 else 0)
            fwd_edge = len(edges)
            edges.append([to_id, dist_cm, fwd_flags, tags["speed"], name_idx,
                          NONE_U32, geo])
            node_edges[from_id].append(fwd_edge)

            if oneway == 0:
                rev_edge = len(edges)
                edges.append([from_id, dist_cm, base | FLAG_GEOM_REVERSED,
                              tags["speed"], name_idx, fwd_edge, geo])
                node_edges[to_id].append(rev_edge)
                edges[fwd_edge][5] = rev_edge

    # -- emit --------------------------------------------------------------
    # The node table stores (firstEdge, edgeCount) as a CSR range, so the edge
    # array MUST be laid out grouped by source node. `edges` above was built in
    # segment order, so permute it here and remap every index that points into
    # the array (reverseEdge).
    csr_order = []
    for i in range(node_count):
        csr_order.extend(node_edges[i])
    if len(csr_order) != len(edges):
        raise AssertionError(
            f"edge accounting mismatch: {len(csr_order)} in CSR vs {len(edges)} built")

    remap = [0] * len(edges)
    for new_i, old_i in enumerate(csr_order):
        remap[old_i] = new_i

    first_edge = [0] * node_count
    running = 0
    for i in range(node_count):
        first_edge[i] = running
        running += len(node_edges[i])
    edge_count = len(edges)

    ordered_edges = [edges[old_i] for old_i in csr_order]
    for e in ordered_edges:
        if e[5] != NONE_U32:
            e[5] = remap[e[5]]

    header_size = 64
    node_table_off = header_size
    edge_table_off = node_table_off + 16 * node_count
    geo_off = edge_table_off + 24 * edge_count
    name_off = geo_off + len(geo_blob)
    total = name_off + len(name_table)

    buf = bytearray(total)
    west, south, east, north = bbox
    # Header field 56 is the geometry blob offset. The reader needs it because
    # the blob sits between the edge table and the name table; deriving it
    # from counts would duplicate the blob-size math on both sides.
    struct.pack_into("<4sHHII4dIIII", buf, 0,
                     MAGIC_GRAPH, FORMAT_VERSION, 1 if has_restrictions else 0,
                     node_count, edge_count,
                     west, south, east, north,
                     name_off, len(name_table), geo_off, 0)

    off = node_table_off
    for i, (lat, lon) in enumerate(node_coords):
        struct.pack_into("<iiII", buf, off,
                         int(round(lat * LAT_SCALE)), int(round(lon * LAT_SCALE)),
                         first_edge[i], len(node_edges[i]))
        off += 16

    off = edge_table_off
    for (target, dist_cm, flags, speed, name_idx, rev, geo) in ordered_edges:
        struct.pack_into("<IIHHIII", buf, off, target, dist_cm, flags & 0xFFFF,
                         speed, name_idx, rev, geo)
        off += 24

    buf[geo_off:geo_off + len(geo_blob)] = geo_blob
    buf[name_off:name_off + len(name_table)] = name_table

    # CRC32 over everything except the last 4 bytes (the CRC slot itself).
    import zlib
    crc = zlib.crc32(bytes(buf[:60])) & 0xFFFFFFFF
    struct.pack_into("<I", buf, 60, crc)

    graph_path = os.path.join(out_dir, "graph.bin")
    with open(graph_path, "wb") as fh:
        fh.write(buf)

    # -- spatial index -----------------------------------------------------
    cell_deg = 0.002  # ~220 m cells at this latitude
    cols = max(1, int(math.ceil((east - west) / cell_deg)))
    rows = max(1, int(math.ceil((north - south) / cell_deg)))
    cells = [[] for _ in range(cols * rows)]
    for i, (lat, lon) in enumerate(node_coords):
        c = int((lon - west) / cell_deg)
        r = int((lat - south) / cell_deg)
        if 0 <= c < cols and 0 <= r < rows:
            cells[r * cols + c].append(i)

    idx_buf = bytearray()
    idx_buf += struct.pack("<4sHHII4d", MAGIC_INDEX, FORMAT_VERSION,
                           int(cell_deg * LAT_SCALE), cols, rows,
                           west, south, east, north)
    offsets = array.array("I", [0])
    flat = array.array("I")
    for cell in cells:
        flat.extend(cell)
        offsets.append(len(flat))
    idx_buf += offsets.tobytes()
    idx_buf += flat.tobytes()

    idx_path = os.path.join(out_dir, "graph.idx")
    with open(idx_path, "wb") as fh:
        fh.write(idx_buf)

    return {
        "nodes": node_count,
        "edges": edge_count,
        "segments": len(segments),
        "graph_bytes": total,
        "index_bytes": len(idx_buf),
        "names": len(name_to_idx),
        "graph_path": graph_path,
        "index_path": idx_path,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--bbox", required=True, help="west,south,east,north")
    ap.add_argument("--simplify-m", type=float, default=0.0,
                    help=("geometry tolerance in metres. 0 keeps every OSM vertex that "
                          "is not exactly collinear, so the drawn route overlays the "
                          "road centreline instead of cutting long crow-fly chords. "
                          "Raise this only to trade fidelity for a smaller pack."))
    args = ap.parse_args()

    bbox = tuple(float(v) for v in args.bbox.split(","))
    collector = WayCollector(bbox)

    print("[1/3] collecting drivable ways ...", flush=True)
    PbfHandler(collector).apply_file(args.input, locations=True)
    print(f"    ways kept: {len(collector.ways)}")
    if not collector.ways:
        sys.exit("no drivable ways in bbox")

    print("[2/3] building graph ...", flush=True)
    node_coords, segments = build_graph(collector.ways, collector.node_ref_count, args.simplify_m)
    print(f"    graph nodes: {len(node_coords)}, segments: {len(segments)}")

    print("[3/3] writing graph.bin / graph.idx ...", flush=True)
    stats = write_graph(args.out_dir, node_coords, segments, bbox)
    for k, v in stats.items():
        if k.endswith("_path"):
            print(f"    {k:14s} {v}")
        elif k.endswith("_bytes"):
            print(f"    {k:14s} {v / 1e6:.2f} MB")
        else:
            print(f"    {k:14s} {v}")


if __name__ == "__main__":
    main()
