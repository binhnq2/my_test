"""Measure how far the app's drawn route polyline sits from the drawn roads.

Ground truth is the polyline the app actually sent to the map (`setRoute`), read
from a chunked logcat dump, compared against the `road` layer of the same
`.mbtiles` the renderer draws. Nothing is re-derived from the routing graph, so
this measures the thing the driver sees.
"""
import collections
import gzip
import os
import json
import math
import re
import sqlite3
import sys
import zlib

import mapbox_vector_tile
from PIL import Image, ImageDraw

# Point these at the pack under test; nothing is assumed about the build host.
MB = os.environ.get("TS8_PACK", r"D:/_ts8_toolchain/basemap_water.mbtiles")
EXT = 4096
KX = 111320.0 * math.cos(math.radians(16.06))
KY = 110540.0


def load_route(log_path):
    parts = {}
    order = []
    for line in open(log_path, encoding="utf-8", errors="replace"):
        m = re.search(r"TS8RouteDump: P(\d+)/(.*)$", line)
        if m:
            i = int(m.group(1))
            parts[i] = m.group(2)
            order.append(i)
    if not parts:
        sys.exit("no route chunks in log")
    blob = "".join(parts[i] for i in sorted(parts))
    pts = []
    for chunk in blob.split(";"):
        if not chunk:
            continue
        lat, lon = chunk.split(",")
        pts.append((float(lat), float(lon)))
    return pts


def lon2x(lon, z):
    return (lon + 180.0) / 360.0 * (1 << z)


def lat2y(lat, z):
    s = math.sin(math.radians(lat))
    return (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * (1 << z)


def xyz2tms(y, z):
    return (1 << z) - 1 - y


def load_roads(bbox, z):
    x0 = int(lon2x(bbox[0], z)); x1 = int(lon2x(bbox[2], z))
    y0 = int(lat2y(bbox[3], z)); y1 = int(lat2y(bbox[1], z))
    db = sqlite3.connect(MB)
    roads, classes, tiles, missing = [], collections.Counter(), 0, 0
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            row = db.execute(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                (z, x, xyz2tms(y, z)),
            ).fetchone()
            if not row:
                missing += 1
                continue
            raw = row[0]
            if raw[:2] == b"\x1f\x8b":
                raw = gzip.decompress(raw)
            elif raw[:1] == b"\x78":
                raw = zlib.decompress(raw)
            try:
                tile = mapbox_vector_tile.decode(raw)
            except Exception:
                continue
            tiles += 1
            layer = tile.get("road")
            if not layer:
                continue
            for f in layer["features"]:
                g = f["geometry"]
                if g["type"] != "LineString":
                    continue
                classes[str(f.get("properties", {}).get("class"))] += 1
                seq = []
                for px, py in g["coordinates"]:
                    lon = (x + px / EXT) * 360.0 / (1 << z) - 180.0
                    n = math.pi - 2 * math.pi * (y + py / EXT) / (1 << z)
                    lat = math.degrees(math.atan(math.sinh(n)))
                    seq.append((lat, lon))
                if len(seq) > 1:
                    roads.append(seq)
    db.close()
    return roads, classes, tiles, missing


def flat(p):
    return (p[1] * KX, p[0] * KY)


def seg_dist(p, a, b):
    ax, ay = flat(a); bx, by = flat(b); px, py = flat(p)
    dx, dy = bx - ax, by - ay
    if dx * dx + dy * dy < 1e-12:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def build_index(roads):
    buckets = collections.defaultdict(list)
    for seq in roads:
        for i in range(len(seq) - 1):
            a, b = seq[i], seq[i + 1]
            mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            key = (int(mid[0] * 2000), int(mid[1] * 2000))
            for ddx in (-1, 0, 1):
                for ddy in (-1, 0, 1):
                    buckets[(key[0] + ddx, key[1] + ddy)].append((a, b))
    return buckets


def nearest(buckets, p, span=2):
    key = (int(p[0] * 2000), int(p[1] * 2000))
    cand = []
    for ddx in range(-span, span + 1):
        for ddy in range(-span, span + 1):
            cand += buckets.get((key[0] + ddx, key[1] + ddy), [])
    best, best_seg = 1e18, None
    for a, b in cand:
        d = seg_dist(p, a, b)
        if d < best:
            best, best_seg = d, (a, b)
    return best, best_seg


def hav(a, b):
    R = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(min(1, math.sqrt(x)))


def bearing(a, b):
    y = math.sin(math.radians(b[1] - a[1])) * math.cos(math.radians(b[0]))
    x = math.cos(math.radians(a[0])) * math.sin(math.radians(b[0])) - \
        math.sin(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.cos(math.radians(b[1] - a[1]))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def main():
    log_path = sys.argv[1] if len(sys.argv) > 1 else "route.log"
    tag = sys.argv[2] if len(sys.argv) > 2 else "A"
    pts = load_route(log_path)
    total = sum(hav(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
    print(f"route {tag}: {len(pts)} points, {total:.1f} m")

    lats = [p[0] for p in pts]; lons = [p[1] for p in pts]
    bbox = (min(lons) - 0.005, min(lats) - 0.005, max(lons) + 0.005, max(lats) + 0.005)

    for z in (14, 16):
        roads, classes, tiles, missing = load_roads(bbox, z)
        print(f"\n=== z{z}: {len(roads)} road lines from {tiles} tiles ({missing} tiles missing) ===")
        if not roads:
            continue
        buckets = build_index(roads)

        # every route vertex
        dists = []
        worst = []
        for i, p in enumerate(pts):
            d, _ = nearest(buckets, p)
            dists.append(d)
            if d > 20.0:
                worst.append((d, i, p))
        dists_sorted = sorted(dists)
        n = len(dists_sorted)
        pct = lambda q: dists_sorted[min(n - 1, int(q * n))]
        print(f"  vertex -> nearest road:  median {pct(0.5):.2f} m   "
              f"p90 {pct(0.90):.2f} m   p99 {pct(0.99):.2f} m   max {dists_sorted[-1]:.2f} m")
        over5 = sum(1 for d in dists if d > 5)
        over20 = sum(1 for d in dists if d > 20)
        print(f"  vertices >5 m off: {over5}/{n}   >20 m off: {over20}/{n}")
        if worst:
            for d, i, p in sorted(worst, reverse=True)[:8]:
                print(f"    off-road vertex #{i} {p[0]:.6f},{p[1]:.6f}  {d:.1f} m from any road")

        # segments: is the whole segment on a road, or only its ends?
        print("  segments whose midpoint is far from a road (possible crow-fly):")
        flagged = 0
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            L = hav(a, b)
            if L < 30:
                continue
            mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            d, _ = nearest(buckets, mid)
            if d > 15.0:
                flagged += 1
                if flagged <= 10:
                    print(f"    seg {i}->{i+1} len {L:6.1f} m  midpoint {d:6.1f} m off road")
        if flagged == 0:
            print("    none")
        else:
            print(f"    total flagged: {flagged}")

        # direction sanity: route heading vs nearest road heading
        mism = 0
        checked = 0
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            if hav(a, b) < 20:
                continue
            mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            d, seg = nearest(buckets, mid)
            if seg is None or d > 8:
                continue
            checked += 1
            rb = bearing(a, b)
            fb = bearing(seg[0], seg[1])
            delta = abs((rb - fb + 180) % 360 - 180)
            delta = min(delta, 180 - delta)  # way direction is arbitrary for 2-way roads
            if delta > 45:
                mism += 1
        print(f"  heading vs nearest road (2-way tolerant): {mism}/{checked} segments differ >45 deg")

        if z == 14:
            render(pts, roads, bbox, f"route_{tag}_vs_roads.png")


def render(pts, roads, bbox, out):
    W, H = 1400, 1100
    def to_px(p):
        return ((p[1] - bbox[0]) / (bbox[2] - bbox[0]) * W,
                (bbox[3] - p[0]) / (bbox[3] - bbox[1]) * H)
    im = Image.new("RGB", (W, H), (245, 245, 240))
    dr = ImageDraw.Draw(im)
    for seq in roads:
        dr.line([to_px(q) for q in seq], fill=(185, 185, 180), width=2)
    dr.line([to_px(q) for q in pts], fill=(30, 90, 230), width=3)
    for i in (0, len(pts) - 1):
        x, y = to_px(pts[i])
        dr.ellipse((x - 7, y - 7, x + 7, y + 7), fill=(220, 30, 30))
    im.save(out)
    print(f"  saved {out}")


if __name__ == "__main__":
    main()
