"""Write a pack's `metadata.json` and `checksums.sha256`.

The manifest is generated rather than hand-written so the two files that must
agree — the tile bounds in `basemap.mbtiles` and the bounds in the manifest —
are read from the tile store instead of retyped. A pack whose manifest claims a
larger area than its tiles is the classic "blank map at the edge" bug.

`checksums.sha256` uses the `<hex>  <relative/path>` format that
`sha256sum -c` reads, and is written last so it can never list itself.
"""
import argparse, hashlib, json, os, sqlite3, sys

def mbtiles_meta(path):
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return {k: v for k, v in con.execute("select name, value from metadata")}
    finally:
        con.close()

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True)
    ap.add_argument("--pack-id", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--generated-at", required=True)
    ap.add_argument("--glyph-stacks", default="Noto Sans Regular,Noto Sans Bold")
    a = ap.parse_args()

    root = os.path.abspath(a.pack)
    mb = os.path.join(root, "basemap.mbtiles")
    if not os.path.isfile(mb):
        sys.exit(f"missing {mb}")
    m = mbtiles_meta(mb)
    bounds = [float(v) for v in m["bounds"].split(",")]
    center = [float(v) for v in m.get("center", "108.2215,16.0678,12").split(",")]

    # The camera is allowed past the tile range on purpose: buildings are
    # extruded from zoom 15 and the pack's tiles stop at 14, so MapLibre's
    # overzoom is what makes the 3D view possible at all.
    tile_max = int(m["maxzoom"])

    manifest = {
        "formatVersion": 1,
        "packId": a.pack_id,
        "name": a.name,
        "generatedAt": a.generated_at,
        "bounds": bounds,
        "center": center[:2],
        "defaultZoom": float(center[2]) if len(center) > 2 else 12.0,
        "minZoom": int(m["minzoom"]),
        "maxZoom": tile_max,
        "maxCameraZoom": max(tile_max + 4, 18),
        "tileExtent": 4096,
        "tileScheme": "simple",
        "attribution": m.get("attribution", "© OpenStreetMap contributors"),
        "attributionUrl": "https://www.openstreetmap.org/copyright",
        "basemap": "basemap.mbtiles",
        "placesDb": "places.db",
        "routingGraph": "routing-graph",
        # No "style" block: the style is generated at runtime by
        # core:map-engine-maplibre/StyleBuilder, because it has to name the
        # absolute tile and glyph paths of this install.
        "glyphs": "fonts/{fontstack}/{range}.pbf",
        "sprite": ""
    }
    if a.glyph_stacks:
        manifest["glyphStacks"] = [s.strip() for s in a.glyph_stacks.split(",") if s.strip()]

    out = os.path.join(root, "metadata.json")
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(f"metadata.json  {os.path.getsize(out)} B  bounds={bounds} zoom={manifest['minZoom']}..{tile_max}")

    # Checksums, relative to the pack root, excluding the checksum file itself.
    lines = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for fn in sorted(filenames):
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if rel == "checksums.sha256":
                continue
            lines.append(f"{sha256(full)}  {rel}")
    lines.sort(key=lambda l: l.split("  ", 1)[1])
    cs = os.path.join(root, "checksums.sha256")
    with open(cs, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"checksums.sha256 {len(lines)} entries")

if __name__ == "__main__":
    main()
