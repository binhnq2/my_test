#!/usr/bin/env python3
"""Clip an OSM PBF extract to a bounding box (complete ways + relations).

Two-pass clip:
  pass 1 - collect every node inside the box and every way that references one
  pass 2 - emit kept nodes (with coordinates), kept ways, and relations whose
           members survived

Usage:
  python clip_osm.py --input vn.osm.pbf --output danang.osm.pbf \
      --bbox 107.85,15.85,108.45,16.25
"""
import argparse
import sys

import osmium


def first_pass(path, min_lon, min_lat, max_lon, max_lat):
    """Return (node ids in box, way ids touching the box)."""
    in_box = set()
    ways_touching = set()

    class Handler(osmium.SimpleHandler):
        def node(self, n):
            lon, lat = n.location.lon, n.location.lat
            if min_lon <= lon <= max_lon and min_lat <= lat <= max_lat:
                in_box.add(n.id)

        def way(self, w):
            for nd in w.nodes:
                if nd.ref in in_box:
                    ways_touching.add(w.id)
                    return

    Handler().apply_file(path, locations=False)
    return in_box, ways_touching


def second_pass(path, out_path, min_lon, min_lat, max_lon, max_lat):
    in_box, kept_ways = first_pass(path, min_lon, min_lat, max_lon, max_lat)
    needed_nodes = set(in_box)
    kept_relations = set()
    way_member_counts = {}

    # Collect every node referenced by a kept way, and relations that point at
    # kept ways (water/landuse multipolygons).
    class Scan(osmium.SimpleHandler):
        def way(self, w):
            if w.id in kept_ways:
                for nd in w.nodes:
                    needed_nodes.add(nd.ref)

        def relation(self, r):
            member_ids = [m.ref for m in r.members if m.type == "w"]
            if any(mid in kept_ways for mid in member_ids):
                kept_relations.add(r.id)
                way_member_counts[r.id] = len(member_ids)

    Scan().apply_file(path, locations=False)

    # Relations may reference nodes/relations we have not kept; a relation is
    # only written when all of its type-w members are available.
    writer = osmium.SimpleWriter(out_path)
    stats = {"nodes": 0, "ways": 0, "relations": 0}

    class Writer(osmium.SimpleHandler):
        def node(self, n):
            if n.id in needed_nodes:
                writer.add_node(n)
                stats["nodes"] += 1

        def way(self, w):
            if w.id in kept_ways:
                writer.add_way(w)
                stats["ways"] += 1

        def relation(self, r):
            if r.id in kept_relations:
                if all(m.ref in kept_ways for m in r.members if m.type == "w"):
                    writer.add_relation(r)
                    stats["relations"] += 1

    Writer().apply_file(path, locations=False)
    writer.close()
    return stats, len(in_box), len(kept_ways)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--bbox", required=True, help="min_lon,min_lat,max_lon,max_lat")
    args = ap.parse_args()

    min_lon, min_lat, max_lon, max_lat = (float(v) for v in args.bbox.split(","))
    stats, nodes_in_box, ways = second_pass(args.input, args.output, min_lon, min_lat, max_lon, max_lat)
    print(f"clip bbox={args.bbox}")
    print(f"  nodes in box : {nodes_in_box}")
    print(f"  ways kept    : {ways}")
    print(f"  written      : nodes={stats['nodes']} ways={stats['ways']} relations={stats['relations']}")
    if stats["nodes"] == 0:
        sys.exit("empty clip - check bbox")


if __name__ == "__main__":
    main()
