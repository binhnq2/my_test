#!/usr/bin/env python3
"""Downloads font PBF glyph ranges for Noto Sans Regular and Noto Sans Bold.
"""
import os
import sys
import urllib.parse
import urllib.request

RANGES = [
    "0-255",
    "256-511",
    "512-767",
    "768-1023",
    "1024-1279",
    "7680-7935",
    "8192-8447",
]

STACKS = ["Noto Sans Regular", "Noto Sans Bold"]

def download_stack(font_dir, stack_name):
    stack_dir = os.path.join(font_dir, stack_name)
    os.makedirs(stack_dir, exist_ok=True)
    base_url = f"https://demotiles.maplibre.org/font/{urllib.parse.quote(stack_name)}"

    for r in RANGES:
        fn = f"{r}.pbf"
        out_path = os.path.join(stack_dir, fn)
        url = f"{base_url}/{fn}"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            with urllib.request.urlopen(req) as resp:
                data = resp.read()
                with open(out_path, "wb") as fh:
                    fh.write(data)
                print(f"  {stack_name}/{fn}: {len(data)} B", flush=True)
        except Exception as e:
            print(f"  FAILED {stack_name}/{fn}: {e}", flush=True)

def main():
    if len(sys.argv) < 2:
        sys.exit("Usage: python download_fonts.py <pack_dir>")
    pack_dir = sys.argv[1]
    font_dir = os.path.join(pack_dir, "fonts")
    os.makedirs(font_dir, exist_ok=True)
    for stack in STACKS:
        print(f"Downloading font stack: {stack} ...", flush=True)
        download_stack(font_dir, stack)

if __name__ == "__main__":
    main()
