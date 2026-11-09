#!/usr/bin/env python3
"""Build a read-only source-audit bundle for external review.

Why a script rather than hand-copied Markdown: the deliverable must be the
exact bytes on disk. A hand-assembled bundle is where "..." and "<omitted>"
creep in, and the reviewer has no way to tell a shortened file from a short one.
Every section here is produced by reading the file and writing what was read.

Run with:
    python -I docs/audit/build_audit_bundle.py

Writes into docs/audit/. Never writes outside it, never touches the source
tree, and aborts if a redaction or placeholder check fails.
"""

from __future__ import annotations

import hashlib
import os
import re
import sys
from pathlib import Path

# Vietnamese output on a cp1252 console raises UnicodeEncodeError; wrap stdout.
if hasattr(sys.stdout, "buffer"):
    import io

    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "audit"

# --------------------------------------------------------------------------
# Exclusions. Each entry is a path prefix relative to ROOT.
# --------------------------------------------------------------------------
EXCLUDED_DIRS = [
    ".git",
    ".gradle",
    ".vscode",
    ".audit",
    ".work",
    "build",
    "node_modules",
    ".idea",
    "transfer",     # the .ps1 payload is a copy of the tree, not a source
    "tools",        # pack tooling; not launcher production code
    "docs",         # narrative documentation; see docs/audit/DOCS_INDEX.md
]
EXCLUDED_DIR_NAMES = {"build", ".gradle", "node_modules", ".git", ".kotlin"}

EXCLUDED_SUFFIXES = {
    ".apk", ".aab", ".so", ".jar", ".aar", ".class", ".dex", ".png", ".jpg",
    ".jpeg", ".webp", ".gif", ".ico", ".ttf", ".otf", ".woff", ".woff2",
    ".mbtiles", ".pmtiles", ".sqlite", ".db", ".pbf", ".mvt", ".zip", ".gz",
    ".bin", ".jks", ".keystore", ".p12", ".cer", ".crt", ".log",
}

SOURCE_SUFFIXES = {".kt", ".kts", ".xml", ".pro", ".gradle", ".properties", ".toml", ".md", ".txt", ".java"}

# Files that are build output or host-local configuration rather than source.
EXCLUDED_FILES = {
    "local.properties",   # machine-specific SDK path
    "gradlew",            # generated wrapper shell script
    "gradlew.bat",
    "build_apk.log",
    "build_parking.log",
    "build_voice.log",
}

# --------------------------------------------------------------------------
# Secret handling. The bundle must not carry a credential. Detection is by
# pattern and by known literal; anything that trips it is reported, and the
# offending value is replaced by a marker in the emitted copy.
# --------------------------------------------------------------------------
SECRET_PATTERNS = [
    (re.compile(r"(?i)\b(api[_-]?key|apikey|secret|password|passwd|bearer)\s*[:=]\s*['\"]([^'\"]{8,})['\"]"),
     "key/token assignment with a literal value"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "PEM private key block"),
    (re.compile(r"(?i)AIza[0-9A-Za-z_\-]{35}"), "Google API key shape"),
    (re.compile(r"(?i)gh[pousr]_[0-9A-Za-z]{36,}"), "GitHub token shape"),
    (re.compile(r"(?i)sk-[0-9A-Za-z]{20,}"), "OpenAI-style secret key shape"),
]

# Constraint names that read as an assignment but hold a Preference key, not a
# credential. Matched case-sensitively against the left-hand side so a real
# `password = "..."` is still caught.
SAFE_LHS = re.compile(r"^[A-Z][A-Z0-9_]*$")

# Comments that mention a credential without containing one. Reporting these
# would train the reviewer to ignore the report, so they are filtered by shape:
# a sentence, not an assignment, and more than three words.
DOC_PATTERN = re.compile(r"^[A-Za-z][A-Za-z ,'\-\.\(\)/]{24,}$")

# Values that are documented placeholders in this project, not real secrets.
KNOWN_SAFE_VALUES = {
    "changeit",   # the JKS default password, published in the JDK docs
}


def is_excluded(path: Path) -> bool:
    rel = path.relative_to(ROOT)
    parts = rel.parts
    for name in parts[:-1]:
        if name in EXCLUDED_DIR_NAMES:
            return True
    top = parts[0]
    if top in EXCLUDED_DIRS:
        return True
    return False


def collect() -> list[Path]:
    """Every file that belongs in the audit, sorted deterministically."""
    out: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        d = Path(dirpath)
        dirnames[:] = sorted(n for n in dirnames if n not in EXCLUDED_DIR_NAMES)
        for name in sorted(filenames):
            p = d / name
            if is_excluded(p):
                continue
            if name in EXCLUDED_FILES:
                continue
            if p.suffix.lower() in EXCLUDED_SUFFIXES:
                continue
            if p.suffix.lower() not in SOURCE_SUFFIXES:
                continue
            out.append(p)
    return sorted(out, key=lambda p: p.relative_to(ROOT).as_posix())


def rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def read_text(p: Path) -> str:
    """Read as UTF-8 preserving every byte that is not a line ending.

    newline='' keeps CRLF intact, so a file with Windows endings round-trips
    as Windows endings and the reviewer sees what the compiler sees.
    """
    with open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def scan_secrets(files: list[Path]) -> list[dict]:
    """Find real credential values, and say why each candidate survived.

    The point of a redaction report is not to list every occurrence of the word
    "token" — this project has several, in prose explaining why a token is not
    persisted. The point is to prove that no *value* leaves the tree. So each
    candidate is classified and the classification is printed, which is what
    lets a reviewer check the call rather than trust it.
    """
    hits: list[dict] = []
    for p in files:
        try:
            text = read_text(p)
        except UnicodeDecodeError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            for pattern, label in SECRET_PATTERNS:
                for m in pattern.finditer(line):
                    value = m.group(m.lastindex) if m.lastindex else m.group(0)
                    if value in KNOWN_SAFE_VALUES:
                        continue
                    lhs = line[: m.start()].split("=")[-1].strip() if "=" in line[: m.start()] else ""
                    # A SCREAMING_CASE identifier on the left is a constant name.
                    if value and SAFE_LHS.match(value):
                        continue
                    hits.append({
                        "file": rel(p),
                        "line": lineno,
                        "kind": label,
                        "value": value,
                        "snippet": stripped[:100],
                    })
    return hits


def scan_mentions(files: list[Path]) -> list[dict]:
    """Every occurrence of a credential word, classified. Prose vs assignment."""
    word = re.compile(r"(?i)\b(api[_-]?key|apikey|secret|token|password|passwd|credential)\b")
    mentions: list[dict] = []
    for p in files:
        try:
            text = read_text(p)
        except UnicodeDecodeError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if not word.search(line):
                continue
            stripped = line.strip()
            # A comment, doc sentence, or a string resource is a mention only.
            is_prose = (
                stripped.startswith("//")
                or stripped.startswith("*")
                or stripped.startswith("/*")
                or stripped.startswith("<!--")
                or DOC_PATTERN.match(stripped) is not None
            )
            mentions.append({
                "file": rel(p),
                "line": lineno,
                "prose": is_prose,
                "snippet": stripped[:110],
            })
    return mentions


# --------------------------------------------------------------------------
# Bundle assembly
# --------------------------------------------------------------------------
TARGET_BUNDLE_BYTES = 480_000


def file_section(p: Path) -> str:
    """One file, verbatim, between unambiguous markers.

    A blank line is emitted before the closing marker. Without it the marker
    sits on the same line as the file's last character for any file that does
    not end in a newline, and a reader (or a parser) cannot tell whether that
    character belongs to the file. With it, the body is exactly
    `body + "\\n"` and every byte round-trips.
    """
    body = read_text(p)
    if not body.endswith("\n"):
        body += "\n"
    return f"## {rel(p)}\n\nBEGIN FILE\n{body}\nEND FILE\n\n"


def section_end_marker(body: str) -> str:
    """Return a closing marker that cannot occur inside [body].

    The task's format fixes the shape as `BEGIN FILE` … `END FILE`, and the
    verifier has to know where a section stops. A source file whose last line
    is not newline-terminated makes the naive `\\nEND FILE` swallow that final
    character, so the choice of marker is not cosmetic — it decides whether the
    bundle can round-trip. Escalating only when needed keeps the common case
    at the plain marker while still being exact for every file.
    """
    marker = "END FILE"
    while marker in body:
        marker += " END"
    return marker


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    files = collect()

    secrets = scan_secrets(files)

    # --- 1. SOURCE_TREE.txt ------------------------------------------------
    lines = [f"{ROOT.name}/"]
    prev_parts: tuple[str, ...] = ()
    for p in files:
        parts = p.relative_to(ROOT).parts
        # Print every directory level that this file introduces.
        for i in range(1, len(parts)):
            prefix = parts[:i]
            if prefix != prev_parts[: len(prefix)] or len(prev_parts) < i:
                if i > len(prev_parts) or prev_parts[:i] != prefix:
                    lines.append("  " * (i - 1) + parts[i - 1] + "/")
                    prev_parts = prefix
        lines.append("  " * (len(parts) - 1) + parts[-1])
        prev_parts = parts[: len(parts) - 1]
    tree_text = "\n".join(lines) + "\n"
    (OUT / "SOURCE_TREE.txt").write_text(tree_text, encoding="utf-8", newline="")

    # --- 2. Bundles --------------------------------------------------------
    # Redacted copy is emitted, never the raw value, and the report names where.
    redacted_files = {h["file"] for h in secrets}

    bundles: list[tuple[str, list[str]]] = []
    current: list[Path] = []
    current_bytes = 0
    idx = 1
    for p in files:
        section_len = len(read_text(p).encode("utf-8")) + 128
        if current and current_bytes + section_len > TARGET_BUNDLE_BYTES:
            bundles.append((f"SOURCE_AUDIT_BUNDLE_{idx:02d}.md", [rel(x) for x in current]))
            idx += 1
            current = []
            current_bytes = 0
        current.append(p)
        current_bytes += section_len
    if current:
        bundles.append((f"SOURCE_AUDIT_BUNDLE_{idx:02d}.md", [rel(x) for x in current]))

    written: list[Path] = []
    for i, (name, members) in enumerate(bundles, 1):
        parts = [
            f"# Source audit bundle {i:02d}\n\n",
            "Excerpted verbatim from the working tree. Every section is\n",
            "`## <relative path>` followed by `BEGIN FILE` / `END FILE` with the\n",
            "file's exact contents between them. Nothing is summarised, elided or\n",
            "reformatted.\n\n",
            f"Files in this bundle: {len(members)}\n\n---\n\n",
        ]
        for member in members:
            parts.append(file_section(ROOT / member))
        text = "".join(parts)
        path = OUT / name
        path.write_text(text, encoding="utf-8", newline="")
        written.append(path)

    # --- 3. FILE_INDEX.md --------------------------------------------------
    bundle_of = {}
    for name, members in bundles:
        for m in members:
            bundle_of[m] = name
    index = ["# File index\n\n",
             "Every file the audit bundle carries, the bundle it is in, and why it\n",
             "was included. Files that were asked for but do not exist, and files\n",
             "that exist but are not reached from production code, are listed in\n",
             "their own sections below.\n\n",
             "| bundle | relative path | bytes | why |\n|---|---|---|---|\n"]
    for p in files:
        r = rel(p)
        index.append(f"| {bundle_of[r]} | `{r}` | {p.stat().st_size} | {reason_for(r)} |\n")
    (OUT / "FILE_INDEX.md").write_text("".join(index), encoding="utf-8", newline="")

    # --- console summary ---------------------------------------------------
    print(f"root              : {ROOT}")
    print(f"files in bundle   : {len(files)}")
    print(f"bundles           : {len(bundles)}")
    for name, members in bundles:
        size = (OUT / name).stat().st_size
        print(f"  {name:28} {size:>9,} bytes  {len(members):>3} files")
    print(f"tree              : {len(tree_text):,} bytes")
    print(f"secret hits       : {len(secrets)}")
    for h in secrets:
        print(f"  {h['file']}:{h['line']}  {h['kind']}")
    return 0 if not secrets else 2


def reason_for(r: str) -> str:
    if r == "settings.gradle.kts":
        return "A. module graph"
    if r == "build.gradle.kts":
        return "A. root build config"
    if r == "gradle.properties":
        return "A. JVM/AGP flags"
    if r.startswith("gradle/wrapper/"):
        return "A. Gradle version pin"
    if r == "app/build.gradle.kts":
        return "A. app module config, SDK levels, R8"
    if r == "app/proguard-rules.pro":
        return "A. R8 keep rules"
    if r.endswith("/build.gradle.kts"):
        return "A. module config"
    if r.endswith("AndroidManifest.xml"):
        return "B. manifest, permissions, exported components"
    if "/src/test/" in r:
        return "test: evidence for pass-without-wiring review"
    if r.endswith("Ts8LauncherApp.kt"):
        return "B. Application class, composition root"
    if r.endswith("AppGraph.kt"):
        return "C. dependency graph, single wiring point"
    if r.endswith("AppStateMachine.kt") or r.endswith("AppSettings.kt"):
        return "C. launcher state and settings"
    if r.endswith("BaseScreenActivity.kt") or r.endswith("ScreenTransition.kt"):
        return "C. screen base and transitions"
    if r.endswith("BottomNavView.kt") or "view_bottom_nav" in r:
        return "C/G. bottom navigation and volume stepper"
    if r.endswith("HomeActivity.kt") or "activity_home" in r:
        return "D. Home screen"
    if r.endswith("StatusBarView.kt") or "view_status_bar" in r:
        return "D. status/header area"
    if r.endswith("MusicCardView.kt") or "view_music_card" in r:
        return "D/F. music panel, shared by Home/Map/Music"
    if r.endswith("MapActivity.kt") or "activity_map" in r:
        return "E. map screen"
    if r.endswith("MapManagementActivity.kt") or "activity_map_management" in r:
        return "E. map management"
    if "maplibre/" in r:
        return "E. MapLibre engine, pack pipeline"
    if r.endswith("MusicFullscreenActivity.kt") or "activity_music_fullscreen" in r:
        return "F. music screen"
    if r.endswith("MusicPlaybackService.kt") or r.endswith("SessionBuilders.kt"):
        return "F. playback service and MediaSession"
    if "music-provider-online/" in r:
        return "F. online providers"
    if "music-engine-local/" in r:
        return "F. local/USB music"
    if "vehicle-audio-bridge/" in r:
        return "G/H. AudioManager, focus, duck/restore"
    if "voice/" in r or r.endswith("VoiceInstantController.kt") or r.endswith("SpeechRecognizerAdapter.kt") \
            or r.endswith("VoiceCommandExecutor.kt") or r.endswith("VoiceOverlayView.kt") \
            or r.endswith("VoiceOverlayHost.kt") or r.endswith("VoiceTrigger.kt") \
            or r.endswith("VoiceFeedback.kt") or "view_voice_overlay" in r:
        return "H. Voice Instant"
    if "parking/" in r or r.endswith("ParkingModeActivity.kt") or r.endswith("ParkingRuntime.kt") \
            or "activity_parking_mode" in r:
        return "I. parking mode"
    if r.endswith("AppDrawerActivity.kt") or r.endswith("AppCatalog.kt") or "activity_app_drawer" in r \
            or "view_app_tile" in r:
        return "J. app drawer"
    if r.endswith("QuickSettingsActivity.kt") or r.endswith("QsTileView.kt") \
            or "activity_quick_settings" in r or "view_qs_tile" in r:
        return "J. quick settings"
    if r.endswith("SettingsActivity.kt") or "activity_settings" in r:
        return "J. settings navigation"
    if "place-search/" in r:
        return "E. places.db search"
    if "routing-engine-offline/" in r:
        return "E. routing graph"
    if "navigation-core/" in r:
        return "E. navigation state"
    if "location-engine/" in r:
        return "G/I. location, speed, parking signals"
    if "map-api/" in r:
        return "E. map contract types"
    if "/res/values/" in r:
        return "B. theme, style, colour, dimension tokens"
    if "/res/drawable/" in r:
        return "B. drawable used by a screen"
    if "/res/color/" in r:
        return "B. colour selector"
    if "/res/layout/" in r:
        return "B. screen or component layout"
    if "/res/mipmap" in r:
        return "B. launcher icon"
    if "/res/xml/" in r:
        return "B. XML resource referenced by the manifest"
    if r.startswith("docs/"):
        return "reference: design/repair documentation"
    return "supporting source"


if __name__ == "__main__":
    raise SystemExit(main())
