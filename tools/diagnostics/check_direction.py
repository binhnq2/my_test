"""Check the drawn route against the directed routing graph.

Two questions, both answered from bytes rather than from intent:

1. **Is every drawn segment on a permitted directed edge?** The graph stores each
   traversable direction as its own edge, with its geometry pre-oriented for that
   direction (bit 12 reverses the shared blob). So a route segment that matches an
   edge at 0 deg is driving with traffic; one that only matches at 180 deg is
   driving against it — the wrong carriageway, i.e. the wrong lane.
2. **How much of the route is one-way?** A useful sanity number for the map area.

Nothing here re-runs the router; it takes the polyline the app sent to MapLibre.
"""
import math
import os
import struct
import sys

# Point this at the graph under test.
P = os.environ.get("TS8_GRAPH", r"D:/_ts8_toolchain/graph_rebuild/graph.bin")
NONE = 0xFFFFFFFF

buf = open(P, "rb").read()
(hdr, ver, flags, N, E, west, south, east, north, name_off, name_len, geo_off, _) = \
    struct.unpack_from("<4sHHII4dIIII", buf, 0)
NODE_OFF = 64
EDGE_OFF = NODE_OFF + N * 16

nlat = [0.0] * N
nlon = [0.0] * N
nfirst = [0] * N
ncnt = [0] * N
for i in range(N):
    a, b, f, c = struct.unpack_from("<iiII", buf, NODE_OFF + i * 16)
    nlat[i] = a / 1e7
    nlon[i] = b / 1e7
    nfirst[i] = f
    ncnt[i] = c

etarget = [0] * E
elen = [0.0] * E
eflags = [0] * E
egeo = [0] * E
for i in range(E):
    t, d, f, sp, ni, rev, g = struct.unpack_from("<IIHHIII", buf, EDGE_OFF + i * 24)
    etarget[i] = t
    elen[i] = d / 100.0
    eflags[i] = f
    egeo[i] = g


def geom(i):
    if egeo[i] == NONE:
        return []
    o = geo_off + egeo[i]
    (cnt,) = struct.unpack_from("<I", buf, o)
    pts = [(struct.unpack_from("<i", buf, o + 4 + k * 8)[0] / 1e7,
            struct.unpack_from("<i", buf, o + 4 + k * 8 + 4)[0] / 1e7) for k in range(cnt)]
    return list(reversed(pts)) if eflags[i] & (1 << 12) else pts


KX = 111320.0 * math.cos(math.radians(16.06))
KY = 110540.0


def flat(p):
    return (p[1] * KX, p[0] * KY)


def seg_dist(p, a, b):
    ax, ay = flat(a)
    bx, by = flat(b)
    px, py = flat(p)
    dx, dy = bx - ax, by - ay
    if dx * dx + dy * dy < 1e-12:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def bearing(a, b):
    y = math.sin(math.radians(b[1] - a[1])) * math.cos(math.radians(b[0]))
    x = (math.cos(math.radians(a[0])) * math.sin(math.radians(b[0]))
         - math.sin(math.radians(a[0])) * math.cos(math.radians(b[0]))
         * math.cos(math.radians(b[1] - a[1])))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def ang_diff(a, b):
    return abs((a - b + 180) % 360 - 180)


def load_route(path):
    import re
    parts = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.search(r"TS8RouteDump: P(\d+)/(.*)$", line)
        if m:
            parts[int(m.group(1))] = m.group(2)
    blob = "".join(parts[i] for i in sorted(parts))
    pts = []
    for chunk in blob.split(";"):
        if chunk:
            lat, lon = chunk.split(",")
            pts.append((float(lat), float(lon)))
    return pts


def build_edge_index():
    """Bucket every edge geometry segment, tagged with its edge id."""
    # edge -> source node, computed once from the CSR ranges rather than
    # scanning every node for every edge (that is O(N*E) and never returns).
    edge_src = [-1] * E
    for n in range(N):
        f, c = nfirst[n], ncnt[n]
        for k in range(c):
            if 0 <= f + k < E:
                edge_src[f + k] = n

    buckets = {}
    oneway_edges = 0
    for i in range(E):
        if eflags[i] & (1 << 8):  # car-forbidden, never drawn by the router
            continue
        if eflags[i] & 1:
            oneway_edges += 1
        chain = geom(i)
        src = edge_src[i]
        if src >= 0 and etarget[i] < N:
            # The blob holds INTERIOR points only: the two spans from the source
            # node to the first interior point and from the last interior point
            # to the target node are implicit. Indexing the blob alone leaves
            # those two spans invisible, which is what produced phantom
            # "no edge found" segments at every edge boundary.
            chain = [(nlat[src], nlon[src])] + chain + [(nlat[etarget[i]], nlon[etarget[i]])]
        elif len(chain) < 2:
            continue
        if len(chain) < 2:
            continue
        for k in range(len(chain) - 1):
            a, b = chain[k], chain[k + 1]
            if a == b:
                continue
            # Subdivide: bucketing a 200 m segment by its midpoint alone leaves
            # it invisible from a query point 70 m along the same road, which
            # showed up as phantom "no edge found" segments.
            pieces = max(1, int(math.hypot(flat(a)[0] - flat(b)[0],
                                           flat(a)[1] - flat(b)[1]) / 20.0))
            for s in range(pieces):
                t0, t1 = s / pieces, (s + 1) / pieces
                sa = (a[0] + t0 * (b[0] - a[0]), a[1] + t0 * (b[1] - a[1]))
                sb = (a[0] + t1 * (b[0] - a[0]), a[1] + t1 * (b[1] - a[1]))
                mid = ((sa[0] + sb[0]) / 2, (sa[1] + sb[1]) / 2)
                key = (int(mid[0] * 3000), int(mid[1] * 3000))
                for ddx in (-1, 0, 1):
                    for ddy in (-1, 0, 1):
                        buckets.setdefault((key[0] + ddx, key[1] + ddy), []).append((sa, sb, i))
    return buckets, oneway_edges


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "route.log"
    pts = load_route(path)
    print(f"route: {len(pts)} points")

    buckets, oneway_edges = build_edge_index()
    print(f"graph: {N} nodes, {E} edges, {oneway_edges} one-way directed edges")

    with_traffic = against = no_match = 0
    no_match_list = []
    violations = []
    checked = 0
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        length = math.hypot(flat(a)[0] - flat(b)[0], flat(a)[1] - flat(b)[1])
        if length < 1.0:
            continue
        mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        key = (int(mid[0] * 3000), int(mid[1] * 3000))
        cand = []
        for ddx in (-1, 0, 1):
            for ddy in (-1, 0, 1):
                cand += buckets.get((key[0] + ddx, key[1] + ddy), [])
        if not cand:
            no_match += 1
            continue
        rb = bearing(a, b)
        chord_mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        # A two-way road stores BOTH directions as separate edges with the same
        # geometry (one reversed), so "nearest candidate" is a coin toss between
        # them and comparing against it proves nothing. The question is whether
        # ANY permitted edge near this segment runs the way the route runs.
        # Distance is measured from the chord's midpoint: the index is tiled in
        # short pieces, so an endpoint-based distance would count along-distance
        # whenever the chord is longer than a piece.
        aligned = against_candidate = False
        best_d = 1e18
        best_edge_bearing = None
        global_min = 1e18
        for ca, cb, ei in cand:
            d = seg_dist(chord_mid, ca, cb)
            if d < global_min:
                global_min = d
            if d > 3.0:
                continue
            if d < best_d:
                best_d = d
                best_edge_bearing = bearing(ca, cb)
            delta = ang_diff(rb, bearing(ca, cb))
            if delta <= 60:
                aligned = True
            elif delta >= 120:
                against_candidate = True
        if aligned:
            checked += 1
            with_traffic += 1
        elif against_candidate:
            checked += 1
            against += 1
            if len(violations) < 10:
                violations.append((i, length, best_d, rb, best_edge_bearing, a, b))
        else:
            no_match += 1
            if len(no_match_list) < 25:
                no_match_list.append((i, length, a, global_min))

    print(f"\nsegments checked: {checked}")
    print(f"  drawn WITH the permitted edge direction : {with_traffic}")
    print(f"  drawn AGAINST the permitted direction   : {against}")
    print(f"  no graph edge found near the segment    : {no_match}")
    if no_match_list:
        print("\n  segments with no permitted edge within 3 m:")
        for i, L, a, gm in no_match_list:
            print(f"    seg {i:3d}  {L:6.1f} m  nearest edge {gm:6.1f} m  at {a[0]:.6f},{a[1]:.6f}")
    if violations:
        print("\n  violations (segment, length, dist-to-edge, route bearing, edge bearing, start):")
        for i, L, d, rb, fb, a, b in violations:
            print(f"    seg {i}: {L:6.1f} m  d={d:5.2f} m  route {rb:5.1f} deg  edge {fb:5.1f} deg  "
                  f"at {a[0]:.6f},{a[1]:.6f}")
    else:
        print("\n  no segment drives against a permitted direction")


if __name__ == "__main__":
    main()
