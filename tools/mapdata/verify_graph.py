#!/usr/bin/env python3
"""Independent verifier for `graph.bin` / `graph.idx`.

This is deliberately a *second implementation* of the reader and of A*, in a
different language. If the Kotlin reader disagrees with this one, one of the
two is wrong — which is the whole point of keeping it: a bug in the interpreter
of the format cannot hide behind the same bug in the writer.

Usage:
    python -I -X utf8 tools/mapdata/verify_graph.py \
        --pack D:/Tools/_data/pack-test \
        --from 16.0616,108.2270 --to 16.0472,108.2015
"""
import argparse
import heapq
import math
import os
import struct
import sys

HEADER = 64
NODE_REC = 16
EDGE_REC = 24
NONE_U32 = 0xFFFFFFFF

FLAG_ONEWAY = 1 << 0
FLAG_TOLL = 1 << 1
FLAG_FERRY = 1 << 2
FLAG_CAR_NO = 1 << 8
FLAG_GEOM_REVERSED = 1 << 12


def haversine(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


class Graph:
    def __init__(self, path):
        self.buf = open(path, "rb").read()
        (magic, ver, _flags, self.n_nodes, self.n_edges,
         self.west, self.south, self.east, self.north,
         self.name_off, self.name_len, self.geo_off, _pad) = \
            struct.unpack_from("<4sHHII4dIIII", self.buf, 0)
        assert magic == b"TS8G", magic
        assert ver == 1, ver
        self.node_off = HEADER
        self.edge_off = HEADER + NODE_REC * self.n_nodes
        self.names = self._read_names()

    def _read_names(self):
        out, o, end = [], self.name_off, self.name_off + self.name_len
        while o + 2 <= end:
            (ln,) = struct.unpack_from("<H", self.buf, o)
            if o + 2 + ln > end:
                break
            out.append(self.buf[o + 2:o + 2 + ln].decode("utf-8"))
            o += 2 + ln
        return out

    def node(self, i):
        lat, lon, first, cnt = struct.unpack_from("<iiII", self.buf,
                                                  self.node_off + i * NODE_REC)
        return lat / 1e7, lon / 1e7, first, cnt

    def edge(self, i):
        target, dist_cm, flags, speed, name_i, rev, geo = struct.unpack_from(
            "<IIHHIII", self.buf, self.edge_off + i * EDGE_REC)
        return dict(target=target, m=dist_cm / 100.0, flags=flags, kph=speed,
                    name_i=name_i, rev=rev, geo=geo)

    def name(self, e):
        return None if e["name_i"] == NONE_U32 else self.names[e["name_i"]]

    def geom(self, e):
        if e["geo"] == NONE_U32:
            return []
        o = self.geo_off + e["geo"]
        (cnt,) = struct.unpack_from("<I", self.buf, o)
        pts = [struct.unpack_from("<ii", self.buf, o + 4 + k * 8) for k in range(cnt)]
        pts = [(a / 1e7, b / 1e7) for a, b in pts]
        return list(reversed(pts)) if e["flags"] & FLAG_GEOM_REVERSED else pts


class Index:
    def __init__(self, path):
        self.buf = open(path, "rb").read()
        (magic, ver, cell_e7, cols, rows, west, south, east, north) = \
            struct.unpack_from("<4sHHII4d", self.buf, 0)
        assert magic == b"TS8I", magic
        self.cell = cell_e7 / 1e7
        self.cols, self.rows = cols, rows
        self.west, self.south = west, south
        self.start_off = struct.calcsize("<4sHHII4d")
        self.nodes_off = self.start_off + (cols * rows + 1) * 4

    def cell_nodes(self, c, r):
        if not (0 <= c < self.cols and 0 <= r < self.rows):
            return []
        cell = r * self.cols + c
        (a,) = struct.unpack_from("<I", self.buf, self.start_off + cell * 4)
        (b,) = struct.unpack_from("<I", self.buf, self.start_off + (cell + 1) * 4)
        return list(struct.unpack_from("<%dI" % (b - a), self.buf,
                                       self.nodes_off + a * 4))


def nearest(g, idx, lat, lon, rings=6, accept=None):
    c0 = int((lon - idx.west) / idx.cell)
    r0 = int((lat - idx.south) / idx.cell)
    best, best_d = -1, float("inf")
    for ring in range(rings + 1):
        for dr in range(-ring, ring + 1):
            for dc in range(-ring, ring + 1):
                if ring and abs(dr) != ring and abs(dc) != ring:
                    continue
                for nid in idx.cell_nodes(c0 + dc, r0 + dr):
                    if accept is not None and not accept[nid]:
                        continue
                    la, lo, _, _ = g.node(nid)
                    d = haversine(lat, lon, la, lo)
                    if d < best_d:
                        best_d, best = d, nid
        if best >= 0 and best_d < ring * idx.cell * 110000.0:
            break
    return best, best_d


def routable_mask(g):
    """Nodes touching at least one car-accessible edge, mirroring the Kotlin reader.

    Snapping without this mask lands on footway vertices near parks and beaches,
    which then report the destination as unreachable.
    """
    ok = bytearray(g.n_nodes)
    for n in range(g.n_nodes):
        _, _, first, cnt = g.node(n)
        for k in range(cnt):
            e = g.edge(first + k)
            if e["flags"] & FLAG_CAR_NO:
                continue
            ok[n] = 1
            if 0 <= e["target"] < g.n_nodes:
                ok[e["target"]] = 1
    return ok


def route(g, idx, src, dst, accept=None):
    start, sd = nearest(g, idx, *src, accept=accept)
    end, ed = nearest(g, idx, *dst, accept=accept)
    if start < 0 or end < 0:
        return None
    n = g.n_nodes
    dist = [float("inf")] * n
    prev_e = [-1] * n
    prev_n = [-1] * n
    closed = bytearray(n)
    dist[start] = 0.0

    def h(u):
        la, lo, _, _ = g.node(u)
        return haversine(la, lo, dst[0], dst[1]) / (120.0 * 1000 / 3600)

    heap = [(h(start), start)]
    while heap:
        _, u = heapq.heappop(heap)
        if closed[u]:
            continue
        if u == end:
            break
        closed[u] = 1
        _, _, first, cnt = g.node(u)
        for k in range(cnt):
            ei = first + k
            e = g.edge(ei)
            if e["flags"] & FLAG_CAR_NO:
                continue
            v = e["target"]
            if closed[v]:
                continue
            secs = e["m"] / (max(e["kph"], 1) * 1000 / 3600)
            nd = dist[u] + secs
            if nd < dist[v]:
                dist[v] = nd
                prev_e[v] = ei
                prev_n[v] = u
                heapq.heappush(heap, (nd + h(v), v))

    if prev_e[end] < 0 and end != start:
        return None

    nodes, edges, cur = [], [], end
    while cur != start:
        nodes.append(cur)
        edges.append(prev_e[cur])
        cur = prev_n[cur]
    nodes.append(start)
    nodes.reverse()
    edges.reverse()

    metres, secs, pts = 0.0, 0.0, []
    la, lo, _, _ = g.node(nodes[0])
    pts.append((la, lo))
    for u, ei in zip(nodes, edges):
        e = g.edge(ei)
        metres += e["m"]
        secs += e["m"] / (max(e["kph"], 1) * 1000 / 3600)
        pts.extend(g.geom(e))
        la, lo, _, _ = g.node(e["target"])
        pts.append((la, lo))

    return dict(start=start, end=end, snap_from=sd, snap_to=ed,
                metres=metres, secs=secs, nodes=len(nodes), edges=len(edges),
                points=pts, hops=[g.name(g.edge(ei)) for ei in edges])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True)
    ap.add_argument("--from", dest="src", required=True)
    ap.add_argument("--to", dest="dst", required=True)
    ap.add_argument("--dump-name-at", type=int, default=-1)
    args = ap.parse_args()

    gd = os.path.join(args.pack, "routing-graph")
    g = Graph(os.path.join(gd, "graph.bin"))
    idx = Index(os.path.join(gd, "graph.idx"))

    print(f"nodes={g.n_nodes} edges={g.n_edges} names={len(g.names)}")
    print(f"bbox=({g.west}, {g.south}, {g.east}, {g.north})")
    print(f"grid={idx.cols}x{idx.rows} cell={idx.cell:.6f}deg")

    # Integrity: every edge target must be a real node, and every node's edge
    # range must lie inside the edge table.
    bad_target = bad_range = 0
    for i in range(0, g.n_nodes):
        _, _, first, cnt = g.node(i)
        if first + cnt > g.n_edges:
            bad_range += 1
            continue
        for k in range(cnt):
            e = g.edge(first + k)
            if e["target"] >= g.n_nodes:
                bad_target += 1
    print(f"integrity: bad_targets={bad_target} bad_ranges={bad_range}")

    # Directionality check: a bidirectional street must have a paired edge.
    paired = unpaired_bidir = 0
    for i in range(g.n_edges):
        e = g.edge(i)
        if e["rev"] != NONE_U32:
            paired += 1
            if (e["rev"] < 0 or e["rev"] >= g.n_edges):
                unpaired_bidir += 1
    print(f"paired edges={paired} broken_pairs={unpaired_bidir}")

    src = tuple(float(v) for v in args.src.split(","))
    dst = tuple(float(v) for v in args.dst.split(","))

    accept = routable_mask(g)
    print(f"routable nodes: {sum(accept)}/{g.n_nodes}")

    r = route(g, idx, src, dst, accept)
    if r is None:
        print("ROUTE: unreachable")
        return 1

    print(f"\nstart node {r['start']} (snap {r['snap_from']:.1f} m)")
    print(f"end   node {r['end']} (snap {r['snap_to']:.1f} m)")
    print(f"route {r['metres']/1000:.2f} km in {r['secs']/60:.1f} min "
          f"over {r['edges']} edges, {len(r['points'])} geometry points")
    straight = haversine(src[0], src[1], dst[0], dst[1])
    print(f"straight-line {straight/1000:.2f} km -> detour ratio "
          f"{r['metres']/max(straight,1):.2f}")

    # A route that is wildly longer than the straight line usually means the
    # graph is fragmented or a one-way made the destination a sink.
    if r["metres"] > straight * 4 + 2000:
        print("WARN: detour ratio suspiciously high")

    names = [n for n in r["hops"] if n]
    print(f"streets: {' > '.join(dict.fromkeys(names[:12]))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
