# Map Pack Download Server Contract

## Overview

The TS8 launcher can download map packs directly from a server. The server
must implement two HTTP endpoints. No authentication is required — the
packs are free OSM-derived data. A future commercial tier may add signed
URLs, but the client already handles that via the `Range` header.

## Endpoint 1: Catalog

```
GET {baseUrl}/catalog.json
Accept: application/json
```

### Response

```json
{
  "packs": [
    {
      "packId": "danang-quangnam",
      "name": "Đà Nẵng - Quảng Nam",
      "version": "2026.10",
      "sizeBytes": 48328192,
      "sha256": "a1b2c3d4e5f6...",
      "fileName": "danang-quangnam-2026.10.ts8map",
      "bounds": [108.0, 15.8, 109.0, 16.2],
      "generatedAt": "2026-10-01",
      "zoomRange": [5, 14],
      "hasRouting": true,
      "hasSearch": true,
      "region": "Miền Trung"
    },
    {
      "packId": "hanoi",
      "name": "Hà Nội",
      "version": "2026.10",
      "sizeBytes": 67108864,
      "sha256": "f6e5d4c3b2a1...",
      "fileName": "hanoi-2026.10.ts8map",
      "bounds": [105.5, 20.8, 106.2, 21.5],
      "generatedAt": "2026-10-01",
      "zoomRange": [5, 14],
      "hasRouting": true,
      "hasSearch": true,
      "region": "Miền Bắc"
    }
  ]
}
```

### Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `packId` | string | yes | Stable identifier, used for dedup. Must match `packId` in the pack's `metadata.json`. |
| `name` | string | yes | Display name in Vietnamese. |
| `version` | string | yes | Version label, e.g. `"2026.10"`. |
| `sizeBytes` | long | yes | File size of the `.ts8map` archive in bytes. Used for the progress bar. |
| `sha256` | string | yes | SHA-256 hash of the `.ts8map` file (hex, lowercase). Verified after download. |
| `fileName` | string | yes | Filename appended to `baseUrl` for the download URL. |
| `bounds` | [west, south, east, north] | no | Geographic bounds as doubles. Shown in UI for orientation. |
| `generatedAt` | string | no | Date the pack was built, e.g. `"2026-10-01"`. |
| `zoomRange` | [min, max] | no | Tile zoom range, e.g. `[5, 14]`. |
| `hasRouting` | boolean | no | Whether the pack includes a routing graph. Default `false`. |
| `hasSearch` | boolean | no | Whether the pack includes a place search index. Default `false`. |
| `region` | string | no | Vietnamese region label, e.g. `"Miền Trung"`. |

### HTTP requirements

- Status `200 OK` on success
- `Content-Type: application/json`
- Body must be valid JSON, UTF-8 encoded
- Server should be reachable over HTTPS (TS8 units may reject cleartext on
  Android 6+ depending on network security config)

## Endpoint 2: Download

```
GET {baseUrl}/{fileName}
Accept: application/octet-stream
Range: bytes=0-  (optional, for resume)
```

### Response (full download)

```
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Length: 48328192
```

### Response (resume)

```
HTTP/1.1 206 Partial Content
Content-Range: bytes 10485760-48328191/48328192
Content-Length: 37842432
```

### HTTP requirements

- Must support `Range` requests (HTTP 206). The client sends `Range: bytes=N-`
  when a `.part` file exists from a previous interrupted download.
- If the server does not support range requests, it should respond `200` and
  the client will restart from zero (no error).
- `Content-Length` header is required for progress reporting.
- The file must be served as-is (not gzip-compressed by the server), because
  the client verifies the SHA-256 of the raw bytes.

## Archive format

The `.ts8map` file is a standard ZIP archive. It must contain:

```
danang-quangnam/
├── metadata.json          (required)
├── checksums.sha256       (required)
├── basemap.mbtiles        (required)
├── places.db              (optional, for search)
├── routing-graph/         (optional, for navigation)
│   ├── graph.bin
│   └── graph.idx
├── fonts/                 (optional, for offline labels)
│   └── Noto Sans Regular/
│       └── 0-255.pbf
│       └── ...
├── style-day.json         (optional)
└── style-night.json       (optional)
```

The ZIP may have a single top-level directory or be flat. `PackImporter`
searches up to 2 levels deep for `metadata.json`.

## Server implementation notes

### Nginx static file server

The simplest implementation is a static file server with auto-index disabled:

```nginx
server {
    listen 443 ssl http2;
    server_name maps.ts8.vn;

    ssl_certificate     /etc/letsencrypt/live/maps.ts8.vn/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/maps.ts8.vn/privkey.pem;

    root /var/www/ts8-packs;

    location /catalog.json {
        default_type application/json;
        # Regenerate on deploy: see generate_catalog.py
    }

    location ~ \.ts8map$ {
        default_type application/octet-stream;
        # Enable range requests (default in nginx)
        add_header Accept-Ranges bytes;
    }
}
```

### Catalog generation script

```python
#!/usr/bin/env python3
"""Generate catalog.json from a directory of .ts8map files."""
import json, hashlib, os, sys
from pathlib import Path

PACK_DIR = Path(sys.argv[1] if len(sys.argv) > 1 else "/var/www/ts8-packs")

packs = []
for f in sorted(PACK_DIR.glob("*.ts8map")):
    # Extract metadata.json from the zip to read packId, name, etc.
    import zipfile
    with zipfile.ZipFile(f) as z:
        meta_entry = next(
            (n for n in z.namelist() if n.endswith("metadata.json")), None
        )
        if not meta_entry:
            continue
        meta = json.loads(z.read(meta_entry))

    sha = hashlib.sha256(f.read_bytes()).hexdigest()
    packs.append({
        "packId": meta.get("packId", f.stem),
        "name": meta.get("name", f.stem),
        "version": meta.get("version", ""),
        "sizeBytes": f.stat().st_size,
        "sha256": sha,
        "fileName": f.name,
        "bounds": meta.get("bounds"),
        "generatedAt": meta.get("generatedAt", ""),
        "zoomRange": [meta.get("minZoom", 5), meta.get("maxZoom", 14)],
        "hasRouting": "routingGraph" in meta,
        "hasSearch": "placesDb" in meta,
        "region": meta.get("region", ""),
    })

output = {"packs": packs}
out_path = PACK_DIR / "catalog.json"
out_path.write_text(json.dumps(output, ensure_ascii=False, indent=2))
print(f"Wrote {out_path} with {len(packs)} packs")
```

## Client configuration

The driver enters the server URL in Map Management → "Tải map pack" →
address field. The URL is stored in `AppSettings.mapServerUrl`.

Example: `https://maps.ts8.vn/packs`

The client builds:
- Catalog URL: `{baseUrl}/catalog.json`
- Download URL: `{baseUrl}/{fileName}`
