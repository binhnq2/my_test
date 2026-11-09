#!/usr/bin/env python3
"""Verify the audit bundle against the working tree.

This exists because the bundle's only value is that it is faithful. A reviewer
cannot diff 259 files by eye, so the claim "verbatim" has to be checked by a
machine and the check has to be re-runnable by them.

Checks, in the order the task asks for them:
  1. every path in FILE_INDEX.md appears in a bundle, exactly once;
  2. every bundled section equals the file on disk, byte for byte;
  3. no placeholder markers ("...", "<omitted>", "same as above") in any
     bundled section;
  4. no credential value in any output file.

Run with:
    python -I docs/audit/verify_audit_bundle.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

if hasattr(sys.stdout, "buffer"):
    import io

    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "audit"

SECTION = re.compile(r"^## (.+?)\r?\n\r?\nBEGIN FILE\r?\n(.*?)\r?\n(END FILE(?: END)*)\r?\n", re.M | re.S)
# Markers that would mean content was elided. Checked only inside file bodies,
# not in prose, because the bundle's own preamble legitimately discusses them.
PLACEHOLDERS = [
    "\n...\n",
    "<omitted>",
    "same as above",
    "[truncated]",
    "<snip>",
    "// ... rest unchanged",
    "/* ... */",
]

SECRET_SHAPES = [
    re.compile(r"AIza[0-9A-Za-z_\-]{35}"),
    re.compile(r"gh[pousr]_[0-9A-Za-z]{36,}"),
    re.compile(r"sk-[0-9A-Za-z]{20,}"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
]


def main() -> int:
    failures: list[str] = []

    index_path = OUT / "FILE_INDEX.md"
    if not index_path.exists():
        print("FAIL: FILE_INDEX.md missing — run build_audit_bundle.py first")
        return 1

    indexed: list[str] = []
    for line in index_path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\|\s*(SOURCE_AUDIT_BUNDLE_\d+\.md)\s*\|\s*`([^`]+)`\s*\|", line)
        if m:
            indexed.append(m.group(2))

    sections: dict[str, str] = {}
    duplicates: list[str] = []
    bundles = sorted(OUT.glob("SOURCE_AUDIT_BUNDLE_*.md"))
    for b in bundles:
        text = b.read_text(encoding="utf-8", newline="")
        for m in SECTION.finditer(text):
            path = m.group(1).strip()
            body = m.group(2)
            if path in sections:
                duplicates.append(path)
            sections[path] = body

    print(f"indexed paths     : {len(indexed)}")
    print(f"bundled sections  : {len(sections)}")
    print(f"bundles           : {len(bundles)}")

    if duplicates:
        failures.append(f"{len(duplicates)} path(s) appear in more than one section: {duplicates[:5]}")

    missing = [p for p in indexed if p not in sections]
    if missing:
        failures.append(f"{len(missing)} indexed path(s) absent from every bundle: {missing[:5]}")

    extra = [p for p in sections if p not in indexed]
    if extra:
        failures.append(f"{len(extra)} bundled path(s) absent from FILE_INDEX: {extra[:5]}")

    # Byte comparison. The bundle emits `body + "\n"` between the markers, so
    # the expectation is the file's text with exactly one trailing newline.
    differ: list[str] = []
    for path, body in sections.items():
        disk = ROOT / path
        if not disk.exists():
            differ.append(f"{path} (not on disk)")
            continue
        raw = disk.read_text(encoding="utf-8", newline="")
        if not raw.endswith("\n"):
            raw += "\n"
        if raw != body:
            differ.append(path)
    print(f"byte-identical    : {len(sections) - len(differ)}/{len(sections)}")
    if differ:
        failures.append(f"{len(differ)} section(s) differ from disk: {differ[:8]}")

    placeholders: list[str] = []
    for path, body in sections.items():
        for marker in PLACEHOLDERS:
            if marker in body:
                placeholders.append(f"{path}: {marker!r}")
    print(f"placeholder hits  : {len(placeholders)}")
    if placeholders:
        failures.append(f"placeholder markers found: {placeholders[:5]}")

    secrets: list[str] = []
    for f in list(bundles) + [index_path, OUT / "SOURCE_TREE.txt"] + sorted(OUT.glob("*.md")):
        if not f.exists():
            continue
        text = f.read_text(encoding="utf-8", errors="replace")
        for shape in SECRET_SHAPES:
            for m in shape.finditer(text):
                secrets.append(f"{f.name}: {m.group(0)[:12]}…")
    print(f"secret shapes     : {len(secrets)}")
    if secrets:
        failures.append(f"credential-shaped strings found: {secrets[:5]}")

    print()
    if failures:
        for f in failures:
            print(f"FAIL  {f}")
        return 1
    print("PASS  every indexed file is bundled verbatim, no placeholders, no credentials")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
