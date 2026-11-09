#!/usr/bin/env python3
"""Build `places.db` — the offline search index — from an OSM PBF extract.

Format contract: `docs/internal/FORMAT_SPECS.md` section 2.

The normalisation here must match `VietnameseText.normalize` in
`core:place-search` exactly; the app cannot rebuild the index at runtime, so a
mismatch silently loses results. Both sides implement:
  NFKD -> strip combining marks -> đ/Đ to d -> lowercase -> collapse spaces.
"""
import argparse
import os
import sqlite3
import sys
import unicodedata
from collections import defaultdict

import osmium

# Importance tiers drive ranking. A city outranks a bridge, which outranks a
# cafe — so "da nang" never loses to "Da Nang Coffee".
PLACE_IMPORTANCE = {
    "city": 95, "town": 80, "village": 60, "suburb": 50, "quarter": 48,
    "neighbourhood": 40, "hamlet": 30, "locality": 25, "borough": 45,
}

AMENITY_IMPORTANCE = {
    "hospital": 70, "university": 68, "college": 60, "bus_station": 62,
    "ferry_terminal": 62, "police": 55, "fire_station": 55,
    "airport": 75, "marketplace": 50, "bank": 40, "fuel": 45,
    "pharmacy": 35, "restaurant": 30, "cafe": 28, "fast_food": 26,
    "school": 50, "kindergarten": 30, "parking": 32, "cinema": 40,
    "theatre": 42, "library": 42, "museum": 46, "place_of_worship": 38,
}

TOURISM_IMPORTANCE = {
    "attraction": 55, "museum": 50, "viewpoint": 45, "hotel": 40,
    "guest_house": 30, "zoo": 48, "theme_park": 50,
}

HIGHWAY_IMPORTANCE = {
    "motorway": 62, "trunk": 60, "primary": 58, "secondary": 52,
    "tertiary": 46, "residential": 34, "unclassified": 30,
    "living_street": 26, "service": 20, "pedestrian": 30,
}

# Named bridges and other structures are highly searchable in Vietnam.
MAN_MADE_IMPORTANCE = {"bridge": 58}


def normalize(text):
    """Must match core:place-search VietnameseText.normalize byte for byte."""
    if not text:
        return ""
    s = unicodedata.normalize("NFKD", text)
    s = s.replace("đ", "d").replace("Đ", "D")
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.replace("ı", "i")
    s = s.lower()
    return " ".join(s.split())


class PlaceCollector(osmium.SimpleHandler):
    def __init__(self, bbox):
        super().__init__()
        self.west, self.south, self.east, self.north = bbox
        self.places = []
        # Deduplicate: a way and its relation can carry the same feature.
        self.seen = set()
        # Streets and settlements arrive as many OSM way segments — "Cầu Rồng"
        # is three primary-highway ways. They are merged into one search row
        # per (name, category, subclass); POIs stay individual.
        self.merged = {}

    def inside(self, lat, lon):
        return self.west <= lon <= self.east and self.south <= lat <= self.north

    def add(self, osm_type, osm_id, name, address, category, subclass,
            lat, lon, importance, bbox=None):
        if category in ("street", "place"):
            key = (category, normalize(name), subclass)
            existing = self.merged.get(key)
            if existing is None:
                self.merged[key] = {
                    "osm_type": osm_type, "osm_id": osm_id, "name": name,
                    "name_norm": normalize(name),
                    "address": address, "address_norm": normalize(address or ""),
                    "category": category, "subclass": subclass,
                    "lat": lat, "lon": lon, "importance": importance,
                    "bbox": list(bbox) if bbox else None,
                    "_n": 1,
                }
            else:
                # A running mean keeps the anchor near the middle of the whole
                # street instead of at whichever segment was read last.
                n = existing["_n"]
                existing["lat"] = (existing["lat"] * n + lat) / (n + 1)
                existing["lon"] = (existing["lon"] * n + lon) / (n + 1)
                existing["_n"] = n + 1
                existing["importance"] = max(existing["importance"], importance)
                if bbox:
                    if existing["bbox"] is None:
                        existing["bbox"] = list(bbox)
                    else:
                        b = existing["bbox"]
                        existing["bbox"] = [
                            min(b[0], bbox[0]), min(b[1], bbox[1]),
                            max(b[2], bbox[2]), max(b[3], bbox[3]),
                        ]
                if not existing["address"] and address:
                    existing["address"] = address
                    existing["address_norm"] = normalize(address)
            return

        key = (category, name, round(lat, 5), round(lon, 5))
        if key in self.seen:
            return
        self.seen.add(key)
        self.places.append({
            "osm_type": osm_type, "osm_id": osm_id, "name": name,
            "name_norm": normalize(name),
            "address": address, "address_norm": normalize(address or ""),
            "category": category, "subclass": subclass,
            "lat": lat, "lon": lon, "importance": importance,
            "bbox": bbox,
        })

    def finish(self):
        """Fold merged street/settlement rows into the result list."""
        for row in self.merged.values():
            row.pop("_n", None)
            if row["bbox"]:
                row["bbox"] = tuple(row["bbox"])
            self.places.append(row)
        self.merged.clear()
        # Deterministic order so repeated builds of the same extract assign
        # identical ids.
        self.places.sort(key=lambda p: (p["category"], p["name_norm"], p["lat"]))
        return self.places

    # -- nodes --------------------------------------------------------------
    def node(self, n):
        tags = n.tags
        if not tags or not self.inside(n.location.lat, n.location.lon):
            return
        lat, lon = n.location.lat, n.location.lon
        name = tags.get("name") or tags.get("name:vi")
        if not name:
            return

        place = tags.get("place")
        if place:
            self.add("node", n.id, name, self._address(tags), "place", place,
                     lat, lon, PLACE_IMPORTANCE.get(place, 40))
            return

        amenity = tags.get("amenity")
        if amenity:
            self.add("node", n.id, name, self._address(tags), "poi", amenity,
                     lat, lon, AMENITY_IMPORTANCE.get(amenity, 22))
            return

        tourism = tags.get("tourism")
        if tourism:
            self.add("node", n.id, name, self._address(tags), "poi", tourism,
                     lat, lon, TOURISM_IMPORTANCE.get(tourism, 25))
            return

        historic = tags.get("historic")
        if historic:
            self.add("node", n.id, name, self._address(tags), "poi", historic,
                     lat, lon, 44)
            return

        leisure = tags.get("leisure")
        if leisure and leisure not in ("pitch", "playground"):
            self.add("node", n.id, name, self._address(tags), "poi", leisure,
                     lat, lon, 32)
            return

        shop = tags.get("shop")
        if shop:
            self.add("node", n.id, name, self._address(tags), "poi", shop,
                     lat, lon, 18)
            return

        natural = tags.get("natural")
        if natural in ("beach", "bay", "peak", "cave_entrance", "spring", "water"):
            # Beaches and bays are major landmarks in Đà Nẵng and are tagged
            # with `natural`, not with an amenity/tourism key.
            self.add("node", n.id, name, self._address(tags), "poi", natural,
                     lat, lon, 48)
            return

        highway = tags.get("highway")
        if highway:
            self.add("node", n.id, name, self._address(tags), "poi", highway,
                     lat, lon, 24)

    # -- ways ---------------------------------------------------------------
    def way(self, w):
        tags = w.tags
        if not tags:
            return
        name = tags.get("name") or tags.get("name:vi")
        if not name:
            return
        try:
            nodes = [(nd.lat, nd.lon) for nd in w.nodes]
        except osmium.InvalidLocationError:
            return
        if not nodes:
            return
        # Centroid of the node envelope is good enough for a search anchor,
        # and it is stable without loading full polygon geometry.
        lats = [p[0] for p in nodes]
        lons = [p[1] for p in nodes]
        lat = sum(lats) / len(lats)
        lon = sum(lons) / len(lons)
        if not self.inside(lat, lon):
            return
        bbox = (min(lons), min(lats), max(lons), max(lats))

        highway = tags.get("highway")
        if highway:
            imp = HIGHWAY_IMPORTANCE.get(highway, 22)
            # A named bridge is a landmark, not just a road segment.
            if tags.get("bridge") not in (None, "no"):
                imp = max(imp, 56)
            self.add("way", w.id, name, self._address(tags), "street", highway,
                     lat, lon, imp, bbox)
            return

        man_made = tags.get("man_made")
        if man_made == "bridge":
            self.add("way", w.id, name, self._address(tags), "poi", "bridge",
                     lat, lon, MAN_MADE_IMPORTANCE["bridge"], bbox)
            return

        building = tags.get("building")
        if building and building not in ("yes", "no"):
            self.add("way", w.id, name, self._address(tags), "poi", building,
                     lat, lon, 30, bbox)
            return

        if tags.get("place"):
            place = tags["place"]
            self.add("way", w.id, name, self._address(tags), "place", place,
                     lat, lon, PLACE_IMPORTANCE.get(place, 40), bbox)

    def _address(self, tags):
        parts = []
        hn = tags.get("addr:housenumber")
        street = tags.get("addr:street")
        if hn and street:
            parts.append(f"{hn} {street}")
        elif street:
            parts.append(street)
        elif hn:
            parts.append(hn)
        for key in ("addr:ward", "addr:suburb", "addr:city", "addr:district"):
            v = tags.get(key)
            if v and v not in parts:
                parts.append(v)
        return ", ".join(parts) if parts else None


def write_db(path, places):
    if os.path.exists(path):
        os.remove(path)
    con = sqlite3.connect(path)
    con.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        CREATE TABLE places (
          id        INTEGER PRIMARY KEY,
          osm_type  TEXT    NOT NULL,
          osm_id    INTEGER NOT NULL,
          name      TEXT    NOT NULL,
          name_norm TEXT    NOT NULL,
          address   TEXT,
          category  TEXT    NOT NULL,
          subclass  TEXT,
          lat       REAL    NOT NULL,
          lon       REAL    NOT NULL,
          bbox_w    REAL, bbox_s REAL, bbox_e REAL, bbox_n REAL,
          importance INTEGER NOT NULL DEFAULT 0
        );
        CREATE VIRTUAL TABLE places_fts USING fts4(
          name_norm, address_norm, tokenize=simple, content=''
        );
    """)

    rows = []
    for i, p in enumerate(places, start=1):
        box = p["bbox"] or (None, None, None, None)
        rows.append((
            i, p["osm_type"], p["osm_id"], p["name"], p["name_norm"],
            p["address"], p["category"], p["subclass"], p["lat"], p["lon"],
            box[0], box[1], box[2], box[3], p["importance"]
        ))
    con.executemany(
        "INSERT INTO places VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows
    )
    # The FTS docid must equal places.id so the JOIN is on rowid.
    con.executemany(
        "INSERT INTO places_fts (docid, name_norm, address_norm) VALUES (?,?,?)",
        [(i, p["name_norm"], p["address_norm"]) for i, p in enumerate(places, start=1)]
    )
    con.execute("CREATE INDEX idx_places_importance ON places(importance DESC, id)")
    con.execute("CREATE INDEX idx_places_latlon ON places(lat, lon)")
    con.execute("CREATE INDEX idx_places_category ON places(category)")
    con.execute("PRAGMA user_version=1")
    con.commit()

    # FTS4 contentless tables need an explicit optimize to merge segments.
    con.execute("INSERT INTO places_fts(places_fts) VALUES('optimize')")
    con.commit()
    con.execute("VACUUM")
    con.commit()
    con.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--bbox", required=True, help="west,south,east,north")
    args = ap.parse_args()

    bbox = tuple(float(v) for v in args.bbox.split(","))
    collector = PlaceCollector(bbox)

    print("[1/2] collecting named places ...", flush=True)
    collector.apply_file(args.input, locations=True)
    places = collector.finish()
    print(f"    places collected: {len(places)}")

    by_cat = defaultdict(int)
    for p in places:
        by_cat[p["category"]] += 1
    for k in sorted(by_cat):
        print(f"      {k:10s} {by_cat[k]}")

    if not places:
        sys.exit("no places collected; check bbox")

    print("[2/2] writing places.db ...", flush=True)
    write_db(args.out, places)
    size = os.path.getsize(args.out)
    print(f"    places.db = {size / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
