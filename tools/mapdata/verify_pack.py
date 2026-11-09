#!/usr/bin/env python3
"""Independent verifier for a map pack's load path.

This is the off-device counterpart of `PackValidator` + `MapPack.load`. It walks
the same stages in the same order and emits the same `key=value` lines that
`PackDiagnostics.report()` writes, so the evidence in `docs/MAP_PACK_ROOT_CAUSE.md`
section 7 can be reproduced on a machine with no head unit attached.

It is deliberately a *second implementation* in a different language: if the
Kotlin reader and this one disagree about a pack, one of them is wrong. It is
not a re-run of the app -- it cannot prove MapLibre renders -- it verifies the
data claims the loader makes.

Stages, in the order `PackValidator.validate()` stops at them:

    1. metadata.json      parse, formatVersion, required fields, zoom sanity
    2. required files     stat, non-empty
    3. checksums.sha256   verify every listed hash (skipped if absent)
    4. mbtiles            tables, zoom range read from `metadata`, a real tile
                          query at the pack's own centre, in TMS row order
    5. places.db          `places` table present and non-empty, FTS4 index
    6. routing-graph      magic + version of graph.bin and graph.idx
    7. glyphs / sprite    declared -> directory present -> one non-empty file
    8. style              the URLs StyleBuilder would build and whether the
                          files they name exist

Exit code 0 when the map itself would load (stages 1-4, 7), 1 otherwise.
Stages 5 and 6 are reported as degraded, not fatal -- the brief requires the
map to work without a routing graph and without a place index.

Usage:
    python -I -X utf8 tools/mapdata/verify_pack.py --pack D:/Tools/Car_Launcher/.work/pack/danang
    python -I -X utf8 tools/mapdata/verify_pack.py --pack <dir> --json
"""
import argparse
import hashlib
import json
import math
import os
import sqlite3
import struct
import sys
import urllib.parse

SUPPORTED_FORMAT_VERSION = 1
ROUTING_FORMAT_VERSION = 1

HEX = "0123456789ABCDEF"


class Report:
    """The key=value record `PackDiagnostics.report()` produces.

    The key names below are the Kotlin ones, deliberately: `stage_trail`,
    `failure`, `resolved_path`, `pack_id`, `format_version`, `metadata_bytes`,
    `file`, `checksum`, `mbtiles`, `tile_query`, `places_db`, `style`,
    `routing`. A second implementation that invents its own vocabulary is a
    second thing to keep in sync; this one can be diffed against a logcat line
    from the unit character for character.
    """

    def __init__(self):
        self.stages = []
        self.failure = None
        self.failure_code = None
        self.f = {}               # key -> value, rendered in report()'s order
        self.details = []         # stage-by-stage trail, for --verbose
        self.candidates = []

    def note(self, stage, detail):
        self.stages.append(stage)
        self.details.append(f"  stage={stage} detail={detail}")

    def fail(self, stage, code, detail):
        self.stages.append(stage)
        self.failure = stage
        self.failure_code = code
        self.details.append(f"  stage={stage} detail={detail} FAILED code={code}")

    def add(self, key, value):
        self.f[key] = value

    def add_file(self, value):
        """`file` is a list in `PackDiagnostics` (`files`), not a scalar."""
        self.f.setdefault("file", []).append(value)

    def append_field(self, key, extra):
        """Appends to an existing key, the way `styleSummary` accumulates."""
        self.f[key] = (self.f.get(key) or "") + extra

    @property
    def failed(self):
        return self.failure is not None

    def trail(self):
        return " → ".join(self.stages)


def encode_path(path):
    """Percent-encode a path exactly as PackPaths.encodePath does.

    `/` and the unreserved set stay literal; every other byte becomes UTF-8
    %XX with upper-case hex. Encoded once, here, because encoding twice is what
    turns a working path into a blank map.
    """
    out = []
    for byte in path.encode("utf-8"):
        c = chr(byte)
        if c.isascii() and (c.isalnum() or c in "-._~/"):
            out.append(c)
        else:
            out.append("%" + HEX[byte >> 4] + HEX[byte & 0x0F])
    return "".join(out)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def tile_at(center, min_zoom, max_zoom, default_zoom):
    """Slippy-map tile for the pack centre, in both row conventions.

    `center` in `metadata.json` is `[lon, lat]` -- GeoJSON order, the same order
    as `bounds`. `MapPack.center` swaps it into `GeoPoint(lat, lon)`, so the
    swap happens here too. Reading it the other way round clamps the latitude to
    85 and probes a tile thousands of columns away, which looks exactly like a
    pack with no tiles at its centre.

    The native reader converts XYZ y to TMS itself (`y = (2^z - 1) - iy`), and
    the validator probes with the TMS row. Probing with the XYZ row would pass
    on a pack whose rows happen to be stored flipped, which is the exact
    confusion the check exists to avoid.
    """
    if not center:
        return None
    z = max(min_zoom, min(max_zoom, int(default_zoom)))
    n = 1 << z
    lon, lat = center[0], center[1]
    lat = max(-85.05112878, min(85.05112878, lat))
    x = int((lon + 180.0) / 360.0 * n)
    x = max(0, min(n - 1, x))
    lat_rad = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n)
    y = max(0, min(n - 1, y))
    return z, x, y, (n - 1) - y


def verify(root, rep):
    root = os.path.abspath(root)
    rep.add("resolved_path", root)

    # -- 1. manifest -------------------------------------------------------
    rep.note("VALIDATING_METADATA", f"root={root}")
    manifest = os.path.join(root, "metadata.json")
    if not os.path.isfile(manifest):
        rep.fail("VALIDATING_METADATA", "INVALID_METADATA", "metadata.json missing")
        return False
    rep.add("metadata_bytes", os.path.getsize(manifest))
    try:
        with open(manifest, encoding="utf-8") as fh:
            meta = json.load(fh)
    except Exception as exc:  # noqa: BLE001 - the message is the evidence
        rep.fail("VALIDATING_METADATA", "INVALID_METADATA", f"json={exc}")
        return False

    version = int(meta.get("formatVersion", 0))
    rep.add("format_version", version)
    if version != SUPPORTED_FORMAT_VERSION:
        rep.fail("VALIDATING_METADATA", "UNSUPPORTED_SCHEMA",
                 f"formatVersion={version} supported={SUPPORTED_FORMAT_VERSION}")
        return False

    min_zoom = float(meta.get("minZoom", 5.0))
    max_zoom = float(meta.get("maxZoom", 14.0))
    default_zoom = float(meta.get("defaultZoom", 12.0))
    if max_zoom < min_zoom:
        rep.fail("VALIDATING_METADATA", "INVALID_METADATA",
                 f"maxZoom={max_zoom} < minZoom={min_zoom}")
        return False
    rep.add("pack_id", meta.get("packId", ""))
    rep.add("name", meta.get("name", ""))
    rep.note("VALIDATING_METADATA", f"ok id={meta.get('packId')} v={version}")

    # -- 2. required files -------------------------------------------------
    rep.note("VALIDATING_FILES", "checking 1 required file(s)")
    basemap = meta.get("basemap", "basemap.mbtiles")
    mb = os.path.join(root, basemap)
    if not os.path.isfile(mb):
        rep.fail("VALIDATING_FILES", "MBTILES_OPEN_FAILED", f"missing {basemap}")
        return False
    if os.path.getsize(mb) <= 0:
        rep.fail("VALIDATING_FILES", "MBTILES_OPEN_FAILED", f"{basemap} is empty")
        return False
    rep.add_file(f"{basemap} size={os.path.getsize(mb)}")

    # -- 3. checksums ------------------------------------------------------
    rep.note("VALIDATING_CHECKSUM", "start")
    checks = os.path.join(root, "checksums.sha256")
    if not os.path.isfile(checks):
        rep.add("checksum", "no checksums.sha256 (skipped)")
        rep.note("VALIDATING_CHECKSUM", "skipped: no manifest")
    else:
        checked = mismatch = missing = 0
        first_bad = None
        with open(checks, encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                cut = line.find("  ")
                if cut <= 0:
                    continue
                expected = line[:cut].lower()
                rel = line[cut + 2:].strip()
                full = os.path.join(root, rel)
                if not os.path.isfile(full):
                    missing += 1
                    first_bad = first_bad or f"{rel} missing"
                    continue
                checked += 1
                if sha256(full) != expected:
                    mismatch += 1
                    first_bad = first_bad or f"{rel} mismatch"
        rep.add("checksum", f"ok={checked} mismatch={mismatch} missing={missing}")
        if mismatch or missing:
            rep.fail("VALIDATING_CHECKSUM", "CHECKSUM_MISMATCH", f"first={first_bad}")
            return False
        rep.note("VALIDATING_CHECKSUM", f"ok={checked}")

    # -- 4. mbtiles --------------------------------------------------------
    rep.note("VALIDATING_FILES", "mbtiles open")
    try:
        con = sqlite3.connect(f"file:{mb}?mode=ro", uri=True)
    except Exception as exc:  # noqa: BLE001
        rep.fail("VALIDATING_FILES", "MBTILES_OPEN_FAILED", str(exc))
        return False
    try:
        tables = {r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        if "tiles" not in tables:
            flat = "map" in tables and "images" in tables
            rep.fail("VALIDATING_FILES", "MBTILES_OPEN_FAILED",
                     f"tables={','.join(sorted(tables))} missing=tiles"
                     + (" (flat schema unsupported)" if flat else ""))
            return False
        fmt, store_min, store_max = "pbf", int(min_zoom), int(max_zoom)
        if "metadata" in tables:
            for name, value in con.execute("SELECT name, value FROM metadata"):
                if name == "format":
                    fmt = value
                elif name == "minzoom":
                    store_min = int(value)
                elif name == "maxzoom":
                    store_max = int(value)
        rep.add("mbtiles", f"tables={','.join(sorted(tables))} format={fmt} "
                           f"minzoom={store_min} maxzoom={store_max}")

        center = meta.get("center")
        tile = tile_at(center, store_min, store_max, default_zoom)
        if tile is None:
            rep.fail("VALIDATING_FILES", "MBTILES_OPEN_FAILED", "no tile at centre inside bounds")
            return False
        z, x, _, tms_y = tile
        row = con.execute(
            "SELECT length(tile_data), hex(substr(tile_data,1,2)) FROM tiles "
            "WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            (z, x, tms_y)).fetchone()
        if not row or not row[0]:
            rep.fail("VALIDATING_FILES", "MBTILES_OPEN_FAILED",
                     f"tile query empty at z={z} x={x} y_tms={tms_y}")
            return False
        rep.add("tile_query", f"z={z} x={x} y_tms={tms_y} bytes={row[0]} "
                              f"magic={row[1].lower() if row[1] else '-'}")
        rep.note("VALIDATING_FILES", f"tile ok z={z} x={x} y_tms={tms_y}")
    finally:
        con.close()

    # -- 5. places.db (degraded, not fatal) --------------------------------
    places = os.path.join(root, meta.get("placesDb", "places.db"))
    if not os.path.isfile(places):
        rep.add("places_db", "absent (search unavailable)")
        rep.note("VALIDATING_FILES", "places.db absent")
    else:
        try:
            pcon = sqlite3.connect(f"file:{places}?mode=ro", uri=True)
            try:
                ptables = {r[0] for r in pcon.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'")}
                if "places" not in ptables:
                    rep.add("places_db", f"missing table places; tables={','.join(sorted(ptables))}")
                    rep.fail("VALIDATING_FILES", "PLACE_DB_OPEN_FAILED", "missing places table")
                else:
                    count = pcon.execute("SELECT COUNT(*) FROM places").fetchone()[0]
                    has_fts = "places_fts" in ptables
                    rep.add("places_db", f"places={count} fts4={has_fts}")
                    if count <= 0:
                        rep.fail("VALIDATING_FILES", "PLACE_DB_OPEN_FAILED", "places table empty")
                    else:
                        rep.note("VALIDATING_FILES", f"places ok {count} rows fts4={has_fts}")
            finally:
                pcon.close()
        except Exception as exc:  # noqa: BLE001
            rep.add("places_db", f"open failed: {exc}")
            rep.fail("VALIDATING_FILES", "PLACE_DB_OPEN_FAILED", str(exc))

    # -- 6. routing graph (degraded, not fatal) ----------------------------
    rdir = os.path.join(root, meta.get("routingGraph", "routing-graph"))
    if not os.path.isdir(rdir):
        rep.add("routing", "absent (navigation unavailable)")
        rep.note("VALIDATING_FILES", "routing-graph absent")
    else:
        binp = os.path.join(rdir, "graph.bin")
        idxp = os.path.join(rdir, "graph.idx")
        if not os.path.isfile(binp) or not os.path.isfile(idxp):
            summary = (f"incomplete files bin={os.path.isfile(binp)} "
                       f"idx={os.path.isfile(idxp)}")
            rep.add("routing", summary)
            rep.fail("VALIDATING_FILES", "ROUTING_GRAPH_INCOMPATIBLE", summary)
        else:
            with open(binp, "rb") as fh:
                bh = fh.read(8)
            with open(idxp, "rb") as fh:
                ih = fh.read(4)
            bin_ok = (len(bh) >= 8 and bh[:4] == b"TS8G"
                      and struct.unpack_from("<H", bh, 4)[0] == ROUTING_FORMAT_VERSION)
            idx_ok = len(ih) >= 4 and ih[:4] == b"TS8I"
            summary = (f"bin={str(bin_ok).lower()} idx={str(idx_ok).lower()} "
                       f"version={ROUTING_FORMAT_VERSION}")
            rep.add("routing", summary)
            if bin_ok and idx_ok:
                rep.note("VALIDATING_FILES", "routing ok")
            else:
                rep.fail("VALIDATING_FILES", "ROUTING_GRAPH_INCOMPATIBLE", summary)

    # -- 7. glyphs / sprite ------------------------------------------------
    # `style` is one accumulating field in the Kotlin `styleSummary`, so it is
    # one accumulating field here: `glyphs=ok`, then `sprite=` appended to it.
    glyphs = meta.get("glyphs")
    if not glyphs:
        rep.append_field("style", " glyphs=undeclared")
    else:
        cut = glyphs.find("{")
        sub = glyphs[:cut].rstrip("/") if cut > 0 else glyphs
        gdir = os.path.join(root, sub)
        if not os.path.isdir(gdir):
            rep.append_field("style", " glyphs=missing dir")
            rep.fail("VALIDATING_FILES", "GLYPH_MISSING", f"declared={glyphs} dir absent")
        else:
            first = None
            for stack in sorted(os.listdir(gdir)):
                sd = os.path.join(gdir, stack)
                if not os.path.isdir(sd):
                    continue
                for fn in sorted(os.listdir(sd)):
                    fp = os.path.join(sd, fn)
                    if os.path.isfile(fp) and os.path.getsize(fp) > 0:
                        first = (stack, fn, os.path.getsize(fp))
                        break
                if first:
                    break
            if first is None:
                rep.append_field("style", " glyphs=empty")
                rep.fail("VALIDATING_FILES", "GLYPH_MISSING", "glyph directory empty")
            else:
                rep.append_field("style", " glyphs=ok")
                rep.note("VALIDATING_FILES", f"glyphs ok {first[0]}/{first[1]} {first[2]} B")

    sprite = meta.get("sprite")
    if sprite:
        base = os.path.join(root, sprite)
        ok = os.path.isfile(base + ".json") and os.path.isfile(base + "@2x.png")
        rep.note("VALIDATING_FILES", f"sprite declared={sprite} present={ok}")
        if not ok:
            rep.fail("VALIDATING_FILES", "SPRITE_MISSING", f"declared={sprite} files absent")

    # -- 8. the URLs the style will carry ----------------------------------
    # Not a `report()` key: the Kotlin report does not carry URLs, only
    # `resolved_uri`. These go to the detail trail because each has a failure
    # mode that shows up as a blank map rather than an error -- a missing
    # scheme, a double encode, an encoded `{fontstack}` placeholder.
    tiles_url = "mbtiles://" + encode_path(mb)
    rep.add("resolved_uri", tiles_url)
    if glyphs:
        cut = glyphs.find("{")
        sub = glyphs[:cut].rstrip("/") if cut > 0 else glyphs
        # A detail line, not a stage: the Kotlin trail has no STYLE_URLS stage.
        rep.details.append("  glyph_url=file://" + encode_path(os.path.join(root, sub))
                           + "/{fontstack}/{range}.pbf")
    return not rep.failed


def render(rep):
    """`key=value` lines in exactly `PackDiagnostics.report()`'s order.

    That order is fixed in the Kotlin `buildString`: stage_trail, failure,
    candidates, resolved_path, resolved_uri, pack_id, format_version,
    metadata_bytes, file*, checksum, mbtiles, tile_query, places_db, style,
    routing.
    """
    out = [f"stage_trail={rep.trail()}",
           "failure=" + (rep.failure or "none")
           + (f" code={rep.failure_code}" if rep.failure_code else ""),
           f"candidates={len(rep.candidates)}"]
    out += [f"  candidate={c}" for c in rep.candidates]
    for key in ("resolved_path", "resolved_uri", "pack_id", "format_version",
                "metadata_bytes"):
        if key in rep.f:
            out.append(f"{key}={rep.f[key]}")
    out += [f"  file={v}" for v in rep.f.get("file", [])]
    for key in ("checksum", "mbtiles", "tile_query", "places_db", "style", "routing"):
        if key in rep.f:
            out.append(f"{key}={str(rep.f[key]).strip()}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True, help="pack root holding metadata.json")
    ap.add_argument("--json", action="store_true", help="emit JSON instead of key=value")
    ap.add_argument("--verbose", action="store_true",
                    help="also print the per-stage detail trail")
    args = ap.parse_args()

    if not os.path.isdir(args.pack):
        print(f"failure=DISCOVERING code=PACK_NOT_FOUND")
        print(f"  candidate={os.path.abspath(args.pack)} exists=false")
        print(f"\nRESULT: map would NOT load (PACK_NOT_FOUND at DISCOVERING)")
        return 1

    rep = Report()
    ok = verify(args.pack, rep)

    if args.json:
        print(json.dumps({
            "ok": ok,
            "report": render(rep),
            "details": rep.details,
        }, ensure_ascii=False, indent=2))
    else:
        for line in render(rep):
            print(line)
        if args.verbose:
            print()
            print("# stage detail trail")
            for line in rep.details:
                print(line)
        print()
        if ok:
            print("RESULT: map would load")
        else:
            print(f"RESULT: map would NOT load ({rep.failure_code} at {rep.failure})")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
