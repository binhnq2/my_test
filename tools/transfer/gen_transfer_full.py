#!/usr/bin/env python3
"""Generate the transfer payload -- driver + six data parts.

This is the Batch-1 successor to `gen_transfer.py`. The two scripts differ in
exactly one respect: this one also carries the MBTiles fixture, the design PNG
and every document, so the transferred tree is the repository rather than a
buildable subset of it.

Unit tests are *not* in the payload. `INCLUDE_TESTS` below is the switch; it is
False because the recipient does not want them. See that constant for what is
lost and why the APK is unaffected.

What is left out, and why each one is not source:

    gradle.properties      machine-specific: pins D:/Tools/_toolchain/jdk-17…
                           and a corporate proxy truststore. Script 1 writes a
                           correct one per machine. Shipping this copy would
                           break every build off this host.
    local.properties       machine-specific by definition (the SDK path).
    transfer/              this directory: the payload cannot contain itself.
    build/, .gradle/       compiler output and caches, never transferable.
    .work/, .audit/        scratch: probe archives, downloaded OSM extracts.
    *.log                  build logs from this host.
    **/src/test/           unit tests -- see INCLUDE_TESTS below.
    tools/testdata/        10.7 MB map fixture -- see INCLUDE_TESTDATA below.

Binary payload. The previous script embedded one 43 KB jar as a single base64
blob. This tree also carries a 2.3 MB design PNG, and putting a 3 MB here-string
into one part would make that part unusable to open or diff. So binaries are cut
into fixed-size chunks and the chunks are spread across parts like any other
file; the driver reassembles each one and checks its sha256 before it is used.
The chunk size matters for a second reason: a single-quoted PowerShell
here-string cannot be edited by a tool that re-wraps lines, and 40 KB chunks stay
well inside what 5.1 parses quickly.

Encoding rules, unchanged from the previous generator because they were right:
  * Every emitted .ps1 is UTF-8 WITH BOM. PowerShell 5.1 decodes a BOM-less
    .ps1 as the system ANSI codepage, which corrupts every Vietnamese string in
    the payload before a single line of it runs. Dot-sourcing does not change
    how a part is decoded, so the parts need the BOM for the same reason.
  * Text is embedded in single-quoted here-strings (@' ... '@), which suppress
    all interpolation. The only sequence that can break one is a line starting
    with '@ -- refused here rather than discovered by a compiler an hour later.

Usage:
  python -I tools/transfer/gen_transfer_full.py
"""
import os
import sys
import base64
import hashlib

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, 'transfer_full')
DRIVER = os.path.join(OUT_DIR, '2-Create-Sources.ps1')
PART_COUNT = 6
PARTS = ['2{a}-Write-Tree-{n}.ps1'.format(a=chr(ord('a') + i), n=i + 1)
         for i in range(PART_COUNT)]

# The APK this transfer is verified against.
#
# Was Batch 1's `b0a26b62…` / 58,686,466 bytes, then 2026-11-01's
# `8ff9a9f4…` / 58,737,433 for the three start-up fixes. Replaced on 2026-11-02
# by the build that carries offline voice: `:core:voice-offline`, the two
# recognition providers and the voice-model management screen.
#
# It is *smaller* than the build it replaces, which is the part worth stating
# plainly. Vosk adds `libvosk.so` (8,279,808 + 8,862,928 = 17,142,736 bytes
# uncompressed across the two ARM ABIs), and `ndk { abiFilters }` removes the
# `x86`/`x86_64`/`armeabi`/`mips`/`mips64` copies of `libmaplibre.so` and
# `libjnidispatch.so`. The filter wins by about 9 MB. Without the filter this
# same tree builds a 96,469,038-byte APK, so the two are not a single delta and
# both figures are recorded in docs/VOICE_OFFLINE_SANDBOX_TEST.md §6.
#
# Verify with `(Get-FileHash app-debug.apk -Algorithm SHA256).Hash` plus a length
# check; the size matters because a truncated copy of a valid APK can hash-diff
# for a boring reason.
KNOWN_GOOD_SHA = '17d1fb18d49565deaf4992f865763de9a00b7229d64fa194e70627b5062a2710'
KNOWN_GOOD_SIZE = 49799534

# Unit tests are left out of the payload at the recipient's request: they are a
# quality gate for the build host, not part of the program. Consequences, both
# stated in the driver's own output so they cannot be misread as "0 tests is
# not 512 passing":
#
#   * `gradlew test` on the transferred tree reports 0 tests.
#   * A few of those tests are structural: they read the repository by path
#     (app/src/test/.../RepoFiles.kt walks up to settings.gradle.kts and then
#     asserts the *main* sources still say what the reports claim). That check
#     only exists where the test sources are.
#
# The APK is byte-identical either way: nothing under src/test reaches a
# `main` source set, an APK input, or AAPT. Flipping this to True restores the
# full set.
INCLUDE_TESTS = False

# The 10.7 MB MBTiles fixture is left out too, and for a reason verified rather
# than assumed. Nothing in the repository reads it:
#
#   grep -rn "testdata" --include=*.kt --include=*.kts --include=*.java .
#       -> no hits outside these transfer scripts
#   AStarRouterPackTest -- the only suite that opens a pack -- reads
#       D:/Tools/_data/pack-test, a directory *outside* the repository, and
#       skips itself cleanly when that directory is absent. It never looks here.
#   An APK built without the fixture contains no entry matching "mbtiles".
#
# It is also a byte-for-byte duplicate of pack-test/basemap.mbtiles, reproducible
# from OSM with tools/mapdata/build_map_pack.py. Shipping 14.3 MB of base64 --
# 64% of the whole payload -- to carry a file nothing opens is the wrong trade.
# The file stays in the repository; it just does not travel.
INCLUDE_TESTDATA = False

SKIP_DIRS = {'build', '.gradle', '__pycache__', '.git', '.idea', '.work', '.audit'}

# Directories at the repository root that are scanned wholesale.
ROOT_DIRS = ['app', 'core', 'buildSrc', 'gradle', 'tools', 'docs', '.vscode']

# Individual root files that belong to the source tree.
ROOT_FILES = ['build.gradle.kts', 'settings.gradle.kts', 'gradlew', 'gradlew.bat']

# Not source, or actively harmful on another machine. Every entry here is
# deliberate; there is no "everything else" fallback that could silently grow.
EXCLUDED = {
    'gradle.properties',   # machine-specific JDK pin + proxy truststore
    'local.properties',    # machine-specific SDK path
}

# 40 KB of base64 per here-string. Big enough that the driver loops 447 times
# rather than 40 000, small enough that a part opens in an editor.
CHUNK_B64 = 40 * 1024


def is_excluded(rel):
    p = rel.replace(os.sep, '/')
    if p in EXCLUDED:
        return True
    if not INCLUDE_TESTS and ('/src/test/' in p or '/src/androidTest/' in p):
        return True
    if not INCLUDE_TESTDATA and p.startswith('tools/testdata/'):
        return True
    # The output directory itself, if someone regenerates in place.
    if p.startswith('transfer/') or p.startswith('transfer_full/'):
        return True
    if p.endswith('.log'):
        return True
    return False


def collect():
    files = []

    def walk(path):
        if not os.path.isdir(path):
            return
        for dirpath, dirnames, filenames in os.walk(path):
            dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
            for name in sorted(filenames):
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, ROOT)
                if is_excluded(rel):
                    continue
                files.append(rel)

    for d in ROOT_DIRS:
        walk(os.path.join(ROOT, d))

    for name in ROOT_FILES:
        p = os.path.join(ROOT, name)
        if os.path.isfile(p) and not is_excluded(name):
            files.append(name)

    return sorted(set(files), key=lambda r: r.replace(os.sep, '/'))


def classify(rel):
    full = os.path.join(ROOT, rel)
    with open(full, 'rb') as fh:
        data = fh.read()
    if b'\x00' in data:
        return 'binary', data
    try:
        text = data.decode('utf-8-sig')
    except UnicodeDecodeError as exc:
        raise SystemExit('{}: not valid UTF-8 ({})'.format(rel, exc))
    # Normalise to LF before embedding: the emitted .ps1 is CRLF, so a payload
    # that already carried CRLF would come out as \r\r\n.
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    for line in text.split('\n'):
        if line.startswith("'@"):
            raise SystemExit(
                "{}: contains a line starting with '@, which would terminate the "
                'here-string early'.format(rel))
    if not text.endswith('\n'):
        text += '\n'

    # Does the here-string path reproduce this file byte for byte?
    #
    # The forward write is: decode CRLF as LF, then re-add exactly one newline.
    # That is right for the overwhelming majority of the tree, but it is not
    # right for every file, and guessing which ones is how a transfer silently
    # ships different bytes than the repository it claims to copy. Two files in
    # this tree are the counter-example:
    #
    #   .vscode/settings.json        CRLF, and no trailing newline at all
    #   docs/batch1/BASELINE_SHA256.tsv   CRLF throughout, plus a UTF-8 BOM
    #
    # Rather than special-casing them by name -- the next one added would be
    # missed -- the question is asked of every file: would the written bytes
    # equal the read bytes? If not, ship the exact bytes as base64 and no
    # normalisation runs at all. This check is why the round-trip is provably
    # byte-identical instead of merely believed to be.
    if expected_bytes(rel, 'text', text) != data:
        return 'binary', data
    return 'text', text


def wants_crlf(rel):
    """cmd.exe mishandles LF-only batch files, so .bat/.cmd keep CRLF."""
    return rel.replace(os.sep, '/').lower().endswith(('.bat', '.cmd'))


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def expected_bytes(rel, kind, payload):
    """The exact bytes Write-Text / Write-Crlf / Write-B64 put on disk."""
    if kind == 'binary':
        return payload
    if wants_crlf(rel):
        body = payload
        if not body.endswith('\n'):
            body += '\n'
        return body.replace('\n', '\r\n').encode('utf-8')
    return payload.encode('utf-8')


class Unit:
    """One emitted here-string: a whole file, or one chunk of a binary."""

    __slots__ = ('kind', 'rel', 'index', 'total', 'body', 'weight', 'sha')

    def __init__(self, kind, rel, body, weight, index=0, total=1, sha=''):
        self.kind = kind          # 'text' | 'crlf' | 'b64'
        self.rel = rel
        self.body = body
        self.weight = weight
        self.index = index
        self.total = total
        self.sha = sha

    def emit(self):
        if self.kind == 'b64':
            return "\nWrite-B64Part '{}' {} {} {} @'\n{}'@\n".format(
                self.rel, self.index, self.total, self.sha, self.body)
        fn = 'Write-Crlf' if self.kind == 'crlf' else 'Write-Text'
        return "\n{} '{}' @'\n{}'@\n".format(fn, self.rel, self.body)


def build_units(entries):
    """Turn classified files into a flat, ordered list of emittable units."""
    units = []
    for rel, kind, payload in entries:
        if kind == 'binary':
            b64 = base64.b64encode(payload)
            digest = sha256(payload)
            # Split on a 3-byte boundary so no base64 quantum is cut in half.
            step = CHUNK_B64 - (CHUNK_B64 % 4)
            chunks = [b64[i:i + step] for i in range(0, len(b64), step)] or [b'']
            for i, chunk in enumerate(chunks, start=1):
                text = chunk.decode('ascii')
                wrapped = '\n'.join(text[j:j + 100] for j in range(0, len(text), 100))
                units.append(Unit('b64', rel, wrapped + '\n', len(chunk),
                                  index=i, total=len(chunks), sha=digest))
        else:
            kind_out = 'crlf' if wants_crlf(rel) else 'text'
            units.append(Unit(kind_out, rel, payload, len(payload.encode('utf-8'))))
    return units


def partition(units, n):
    """Split into n contiguous slices of roughly equal *emitted* size.

    Contiguous, not round-robin, so each part stays thematically coherent: all
    of app/src/main/res lands in one, all of core/ in another. That is what
    makes a 300 KB part readable when somebody opens it at 2am.
    """
    if len(units) < n:
        raise SystemExit('{} units cannot be split into {} parts'.format(len(units), n))
    prefix = [0]
    for u in units:
        prefix.append(prefix[-1] + u.weight)
    total = prefix[-1]

    cuts = [0]
    for i in range(1, n):
        target = total * i / n
        lo = cuts[-1] + 1                 # at least one unit per part
        hi = len(units) - (n - i)         # reserve one for each part after
        cuts.append(min(range(lo, hi + 1), key=lambda k: abs(prefix[k] - target)))
    cuts.append(len(units))
    return [units[cuts[i]:cuts[i + 1]] for i in range(n)]


def write_script(path, text):
    """UTF-8 WITH BOM, CRLF -- see the module docstring."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8-sig', newline='\r\n') as fh:
        fh.write(text)


def part_header(index, chunk):
    first = chunk[0]
    last = chunk[-1]
    span = 'from {}'.format(first.rel)
    if first.total > 1:
        span += ' (chunk {}/{})'.format(first.index, first.total)
    span += '\n    through\n    {}'.format(last.rel)
    if last.total > 1:
        span += ' (chunk {}/{})'.format(last.index, last.total)
    letter = chr(ord('a') + index - 1)
    return PART_TEMPLATE.format(index=index, total=PART_COUNT, count=len(chunk),
                                letter=letter, span=span)


PART_TEMPLATE = r'''#Requires -Version 5.1
<#
.SYNOPSIS
    Payload part {index} of {total} for 2-Create-Sources.ps1 -- {count} units.

.DESCRIPTION
    Holds {count} here-strings, {span}

    This file is NOT runnable on its own and is not meant to be read as a
    program: it is a data file that happens to be PowerShell.
    2-Create-Sources.ps1 dot-sources it, so every Write-Text / Write-Crlf /
    Write-B64Part call below runs with the writers, $ProjectDir and $VerifyOnly
    that the driver defined.

.NOTES
    UTF-8 WITH a BOM, like the driver, because PowerShell 5.1 decodes a BOM-less
    .ps1 as the system ANSI codepage and the Vietnamese strings below would be
    corrupted before any of this code runs.
#>

if (-not (Get-Command Write-Text -CommandType Function -ErrorAction SilentlyContinue)) {{
    Write-Host ''
    Write-Host "  2{letter}-Write-Tree-{index}.ps1 is part {index} of {total} of the source payload." -ForegroundColor Yellow
    Write-Host '  It is loaded by 2-Create-Sources.ps1, which must sit in this same folder.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '  Run this instead:' -ForegroundColor White
    Write-Host '      powershell -ExecutionPolicy Bypass -File .\2-Create-Sources.ps1' -ForegroundColor White
    Write-Host ''
    exit 1
}}

'''


def main():
    files = collect()
    entries = []
    for rel in files:
        kind, payload = classify(rel)
        entries.append((rel.replace(os.sep, '/'), kind, payload))

    units = build_units(entries)
    text_files = sum(1 for _, k, _ in entries if k == 'text')
    binary_files = len(entries) - text_files
    slices = partition(units, PART_COUNT)

    # -- driver ------------------------------------------------------------
    parts = []
    add = parts.append
    add(HEADER)
    add(DRIVER_LOAD)
    add('\n# ==================================================== expected hashes\n')
    add('# sha256 of every file, checked after the write so a truncated payload\n')
    add('# fails here rather than inside a compiler error. Lives in the driver, not\n')
    add('# in a part, so the check runs once over the whole tree no matter how the\n')
    add('# parts were divided.\n')
    add('$Expected = @{\n')
    for rel, kind, payload in entries:
        add("    '{}' = '{}'\n".format(rel, sha256(expected_bytes(rel, kind, payload))))
    add('}\n\n')
    add('# Binaries are written in chunks; this is how many and what they must\n')
    add('# hash to once reassembled.\n')
    add('$BinarySizes = @{\n')
    for rel, kind, payload in entries:
        if kind == 'binary':
            add("    '{}' = @{{ parts = {}; sha256 = '{}'; size = {} }}\n".format(
                rel, max(1, -(-len(base64.b64encode(payload)) // (CHUNK_B64 - CHUNK_B64 % 4))),
                sha256(payload), len(payload)))
    add('}\n')
    add(FOOTER)

    write_script(DRIVER, ''.join(parts))

    # -- payload parts ------------------------------------------------------
    for index, (name, chunk) in enumerate(zip(PARTS, slices), start=1):
        body = [part_header(index, chunk)]
        for unit in chunk:
            body.append(unit.emit())
        write_script(os.path.join(OUT_DIR, name), ''.join(body))

    print('wrote {}'.format(DRIVER))
    print('  files embedded : {} ({} text, {} binary)'.format(
        len(entries), text_files, binary_files))
    print('  unit tests     : {}'.format('included' if INCLUDE_TESTS else 'excluded (INCLUDE_TESTS = False)'))
    print('  units emitted  : {} (binaries are chunked)'.format(len(units)))
    grand = 0
    for name, chunk in zip(PARTS, slices):
        path = os.path.join(OUT_DIR, name)
        size = os.path.getsize(path)
        grand += size
        print('  {:<22} {:>12,} bytes  {:>4} units  {}'.format(
            name, size, len(chunk),
            sha256(open(path, 'rb').read())[:16]))
    driver_size = os.path.getsize(DRIVER)
    grand += driver_size
    print('  {:<22} {:>12,} bytes  (driver)'.format('2-Create-Sources.ps1', driver_size))
    print('  total          : {:,} bytes in {} files'.format(grand, len(PARTS) + 1))
    print('  known good APK : {} ({} bytes)'.format(KNOWN_GOOD_SHA, KNOWN_GOOD_SIZE))


HEADER = r'''#Requires -Version 5.1
<#
.SYNOPSIS
    Writes the complete TS8 Launcher source tree, then builds the debug APK.

.DESCRIPTION
    Driver for the second half of the transfer. It carries the whole program:
    application sources, all twelve core modules, buildSrc, the Gradle wrapper,
    the map fixture, the design PNG and all documentation, across six
    companions:

        2a-Write-Tree-1.ps1
        2b-Write-Tree-2.ps1
        2c-Write-Tree-3.ps1
        2d-Write-Tree-4.ps1
        2e-Write-Tree-5.ps1
        2f-Write-Tree-6.ps1

    They are dot-sourced from this file's directory, in that order, and share
    its scope -- which is why they are not run directly: they call the writers
    (Write-Text, Write-Crlf, Write-B64Part) and read the parameters
    ($ProjectDir, $VerifyOnly) defined here.

    Order of work:
      1. Check the environment (JDK 17, Android SDK, gradle.properties).
      2. Load the six parts; each writes its share of the tree.
      3. Reassemble and hash the binaries, then verify every file.
      4. Build :app:assembleDebug.
      5. Report the APK path, size and sha256.

    All seven files must sit in the same directory. Copy the whole set.

.PARAMETER ProjectDir
    Where to create the project. Default: .\TS8CarLauncher next to this script.
    An existing directory is reused, not deleted -- re-running overwrites the
    embedded files and leaves anything else alone.

.PARAMETER SkipBuild
    Write the tree and verify it, but do not run Gradle.

.PARAMETER VerifyOnly
    Write nothing; only check that an existing tree matches the embedded hashes.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\2-Create-Sources.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\2-Create-Sources.ps1 -ProjectDir C:\work\TS8

.NOTES
    Every one of these files is UTF-8 WITH a BOM on purpose. Without the BOM,
    PowerShell 5.1 reads them as the system ANSI codepage and every Vietnamese
    string in the here-strings is corrupted. Do not re-save any of them as
    "UTF-8 without BOM", and do not mix parts from different transfers -- the
    driver checks the tree against embedded hashes and stops on the first
    mismatch.
#>
[CmdletBinding()]
param(
    [string] $ProjectDir,
    [switch] $SkipBuild,
    [switch] $VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# The build this transfer was verified against: the 2026-11-02 rebuild that
# adds offline voice, not Batch 1's and not 2026-11-01's. Comparing the sha256
# says "this is the same artifact", which is stronger than comparing the size --
# and here it also says the artifact is not the one that dies in
# `setContentView` on the TS8.
$KnownGoodSha  = '17d1fb18d49565deaf4992f865763de9a00b7229d64fa194e70627b5062a2710'
$KnownGoodSize = 49799534

# ============================================================== helpers

function Write-Step([string] $Text) {
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Write-Ok([string] $Text)  { Write-Host "    [ok]   $Text" -ForegroundColor Green }
function Write-Skip([string] $Text){ Write-Host "    [skip] $Text" -ForegroundColor DarkGray }
function Write-Warn2([string] $Text){ Write-Host "    [warn] $Text" -ForegroundColor Yellow }
function Write-Err2([string] $Text) { Write-Host "    [FAIL] $Text" -ForegroundColor Red }

function New-Dir([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Force -Path $Path | Out-Null
    }
}

# UTF-8 WITHOUT BOM. The opposite of what these script files themselves need:
# the .ps1 files must carry a BOM for PowerShell 5.1 to read them, but every
# file they write must NOT, because a BOM in a .kt file is a compile error and
# a BOM in a .properties file breaks the first key.
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Text([string] $RelPath, [string] $Text) {
    if ($VerifyOnly) { return }
    $full = Join-Path $ProjectDir $RelPath
    New-Dir (Split-Path -Parent $full)

    # Two normalisations, both required for the written bytes to equal the bytes
    # that were hashed:
    #  1. These .ps1 files are stored CRLF and a here-string keeps them
    #     verbatim, so the captured text arrives CRLF. Every member of the tree
    #     is LF, which is what the Kotlin compiler and AAPT expect.
    #  2. PowerShell 5.1 removes exactly ONE newline before the closing '@ --
    #     not "the trailing newline", one single one. The generator therefore
    #     embeds the file's real content and this side re-adds exactly one, so a
    #     file ending in a blank line (the audit bundles do) keeps its second
    #     newline instead of losing it. Guarding this with EndsWith() was the
    #     bug: it put back only what the check thought was missing, which is
    #     nothing at all once the remaining text still ends in a newline.
    $norm = $Text -replace "`r`n", "`n"
    $norm = $norm -replace "`r", "`n"
    $norm += "`n"

    [IO.File]::WriteAllText($full, $norm, $Utf8NoBom)
}

# The one file class that must keep CRLF: batch files. cmd.exe's parser fails on
# an LF-only batch file at a `goto :label` whose label sits at end of file, and
# gradlew.bat is how every Windows build starts.
function Write-Crlf([string] $RelPath, [string] $Text) {
    if ($VerifyOnly) { return }
    $full = Join-Path $ProjectDir $RelPath
    New-Dir (Split-Path -Parent $full)
    $norm = $Text -replace "`r`n", "`n"
    $norm = $norm -replace "`r", "`n"
    $norm += "`n"          # same one-newline rule as Write-Text, see above
    $norm = $norm -replace "`n", "`r`n"
    [IO.File]::WriteAllText($full, $norm, $Utf8NoBom)
}

# Binaries do not fit in one here-string: the map fixture alone is 10.7 MB, and
# a single 14 MB base64 literal would make its part unopenable. They arrive as
# numbered chunks spread across the parts, so this collects rather than writes;
# Complete-Binaries assembles and hashes each file once, after the last part.
$script:BinaryChunks = @{}

function Write-B64Part([string] $RelPath, [int] $Index, [int] $Total,
                       [string] $Sha, [string] $Base64) {
    if ($VerifyOnly) { return }
    if (-not $script:BinaryChunks.ContainsKey($RelPath)) {
        $script:BinaryChunks[$RelPath] = New-Object 'System.Collections.Generic.SortedDictionary[int,string]'
    }
    $script:BinaryChunks[$RelPath][$Index] = ($Base64 -replace '\s', '')
}

function Complete-Binaries() {
    if ($VerifyOnly) { return }
    foreach ($rel in $script:BinaryChunks.Keys) {
        $chunks = $script:BinaryChunks[$rel]
        $expectedCount = $BinarySizes[$rel].parts
        if ($chunks.Count -ne $expectedCount) {
            throw "$rel arrived in $($chunks.Count) chunks, expected $expectedCount. " +
                  'The payload set is incomplete or mixed across transfers.'
        }
        $sb = New-Object System.Text.StringBuilder
        foreach ($key in $chunks.Keys) { [void] $sb.Append($chunks[$key]) }
        $bytes = [Convert]::FromBase64String($sb.ToString())
        $full = Join-Path $ProjectDir $rel
        New-Dir (Split-Path -Parent $full)
        [IO.File]::WriteAllBytes($full, $bytes)
    }
}

function Get-Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# ============================================================== banner

# $PSScriptRoot is set when this file is run with -File; fall back to the
# invoking command's path for the `& .\2-Create-Sources.ps1` form.
$here = $PSScriptRoot
if (-not $here) { $here = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $ProjectDir) { $ProjectDir = Join-Path $here 'TS8CarLauncher' }
$ProjectDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ProjectDir)

Write-Host ''
Write-Host '  TS8 Launcher - complete source tree and build (script 2 of 2)' -ForegroundColor White
Write-Host "  project   : $ProjectDir"
Write-Host "  powershell: $($PSVersionTable.PSVersion)"
if ($VerifyOnly) { Write-Host '  mode      : verify only' }
elseif ($SkipBuild) { Write-Host '  mode      : write, no build' }
Write-Host ''

# ============================================================== 1. environment

Write-Step '1/5  environment'

# -- gradle.properties, written by script 1 ----------------------------------
# Read first, because it is the most reliable source for the JDK path: script 1
# records the exact JDK it installed there, even when JAVA_HOME is not set in
# this shell. This file is the one member of the tree that is deliberately not
# in the payload -- it carries a machine-specific JDK path and, on the original
# build host, a corporate proxy truststore.
$gradleProps = Join-Path $ProjectDir 'gradle.properties'
if (-not (Test-Path $gradleProps) -and -not $VerifyOnly) {
    if ($SkipBuild) {
        Write-Warn2 'gradle.properties is absent (script 1 has not run for this directory)'
    } else {
        throw ('gradle.properties is missing from ' + $ProjectDir + '. It is generated, ' +
               'not part of this payload, because it carries a machine-specific JDK path. ' +
               'Run 1-Setup-Environment.ps1 -ProjectDir "' + $ProjectDir + '" first.')
    }
}

$propsText = ''
if (Test-Path $gradleProps) {
    $propsText = Get-Content -LiteralPath $gradleProps -Raw
    if ($propsText -match 'proxyHost') {
        Write-Warn2 'gradle.properties still names a proxy; the build fails if that proxy is unreachable'
    }
    if ($propsText -notmatch 'org\.gradle\.java\.home') {
        Write-Warn2 'gradle.properties has no org.gradle.java.home; Gradle uses whatever JDK it finds'
    }
    Write-Ok 'gradle.properties present'
}

# -- JDK 17 ------------------------------------------------------------------
$javaHome = $null
if ($propsText -match '(?m)^\s*org\.gradle\.java\.home\s*=\s*(.+?)\s*$') {
    $pinned = $Matches[1].Trim().Replace('/', [IO.Path]::DirectorySeparatorChar)
    if (Test-Path (Join-Path $pinned 'bin\java.exe')) { $javaHome = $pinned }
}
if (-not $javaHome -and $env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaHome = $env:JAVA_HOME
}
if (-not $javaHome) {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) { $javaHome = Split-Path -Parent (Split-Path -Parent $javaCmd.Source) }
}

if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    if ($SkipBuild -or $VerifyOnly) {
        Write-Warn2 'no JDK found; fine for -SkipBuild, the build step would fail'
    } else {
        throw 'No JDK found. Run 1-Setup-Environment.ps1 first, or set JAVA_HOME to a JDK 17.'
    }
} else {
    # java -version writes to stderr; redirecting a native command's stderr with
    # ErrorActionPreference = 'Stop' turns each line into a terminating
    # NativeCommandError in PowerShell 5.1 -- the version check would abort the
    # script it guards. Letting cmd.exe do the redirect avoids the mechanism.
    $javaVersion = (& cmd.exe /c "`"$javaHome\bin\java.exe`" -version 2>&1" | Out-String)
    if ($javaVersion -notmatch 'version "17') {
        if ($SkipBuild -or $VerifyOnly) {
            Write-Warn2 "JDK at $javaHome is not 17; fine for -SkipBuild, the build would fail"
        } else {
            throw "JAVA_HOME points at a non-17 JDK ($javaHome). AGP 8.7 and Kotlin 1.9.25 need 17."
        }
    } else {
        Write-Ok "JDK 17: $javaHome"
    }
}

# -- local.properties, written by script 1 -----------------------------------
$localProps = Join-Path $ProjectDir 'local.properties'
if (Test-Path $localProps) {
    $sdkLine = (Get-Content -LiteralPath $localProps |
        Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1)
    if ($sdkLine) {
        $sdkPath = ($sdkLine -split '=', 2)[1].Trim()
        if (Test-Path $sdkPath) { Write-Ok "SDK: $sdkPath" }
        else { Write-Warn2 "local.properties names an SDK that does not exist: $sdkPath" }
    } else {
        Write-Warn2 'local.properties has no sdk.dir line'
    }
} elseif (-not $VerifyOnly) {
    Write-Warn2 'local.properties missing; AGP can still find the SDK via ANDROID_HOME'
}

'''

DRIVER_LOAD = r'''
# ============================================================== 2. write

Write-Step '2/5  writing the source tree'

# The six payload parts are dot-sourced, not run as child processes and not
# invoked with &, for one reason: they must see this script's writers, its
# $ProjectDir and its $VerifyOnly. A child scope would need a copy of all of
# that passed in, and a copy is a place for the two to drift apart.
$partNames = @(
    '2a-Write-Tree-1.ps1',
    '2b-Write-Tree-2.ps1',
    '2c-Write-Tree-3.ps1',
    '2d-Write-Tree-4.ps1',
    '2e-Write-Tree-5.ps1',
    '2f-Write-Tree-6.ps1'
)

# Check every part before loading any: writing half a tree and then stopping
# leaves a directory that looks more complete than it is.
$missingParts = @()
foreach ($part in $partNames) {
    if (-not (Test-Path -LiteralPath (Join-Path $here $part))) { $missingParts += $part }
}
if ($missingParts.Count -gt 0) {
    Write-Err2 "$($missingParts.Count) of the $($partNames.Count) payload parts are not next to this script"
    $missingParts | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
    Write-Host ''
    Write-Host "  They must all sit in: $here" -ForegroundColor Yellow
    Write-Host '  Copy the whole set -- 2-Create-Sources.ps1 plus 2a through 2f -- together.' -ForegroundColor Yellow
    throw 'Payload parts missing. Nothing was written.'
}

if (-not $VerifyOnly) { New-Dir $ProjectDir }

$partNo = 0
foreach ($part in $partNames) {
    $partNo++
    $path = Join-Path $here $part
    if (-not $VerifyOnly) {
        Write-Host "    $partNo/$($partNames.Count)  $part" -ForegroundColor DarkGray
    }
    . $path
}
if (-not $VerifyOnly) {
    Write-Ok "$($partNames.Count) payload parts loaded"
} else {
    Write-Skip 'verify only: parts loaded, nothing written'
}

'''

FOOTER = r'''
# Binaries were collected, not written: assemble them now that every chunk has
# arrived. This runs after the tables above, because Complete-Binaries reads
# $BinarySizes to know how many chunks each file should have -- calling it
# earlier would throw "variable not set" under Set-StrictMode.
Complete-Binaries
if (-not $VerifyOnly) { Write-Ok "$($BinarySizes.Count) binary file(s) reassembled" }

# ============================================================== 3. verify

Write-Step '3/5  verifying'

$missing = @()
$mismatched = @()
foreach ($rel in $Expected.Keys) {
    $full = Join-Path $ProjectDir $rel
    if (-not (Test-Path -LiteralPath $full)) { $missing += $rel; continue }
    $actual = Get-Sha256 $full
    if ($actual -ne $Expected[$rel]) { $mismatched += $rel }
}

if ($missing.Count -gt 0) {
    Write-Err2 "$($missing.Count) file(s) missing"
    $missing | Select-Object -First 10 | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
    throw 'The source tree is incomplete. Nothing was built.'
}
if ($mismatched.Count -gt 0) {
    Write-Err2 "$($mismatched.Count) file(s) do not match their expected hash"
    $mismatched | Select-Object -First 10 | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
    throw 'The source tree is corrupt. Nothing was built.'
}

$total = $Expected.Count
if ($VerifyOnly) {
    Write-Ok "$total files present and matching"
} else {
    Write-Ok "$total files written and verified"
}

if ($VerifyOnly) {
    Write-Host ''
    Write-Host '  Tree matches. Nothing was written.' -ForegroundColor Green
    return
}

# A file count is a weak signal, so name the things a partial run would most
# likely be missing rather than trusting the total alone. These span every
# layer the tree needs: the build script, the manifest, Kotlin sources from two
# modules, a resource, the wrapper jar and the binaries that did travel.
foreach ($probe in @(
    'settings.gradle.kts',
    'build.gradle.kts',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt',
    'app/src/main/java/com/ts8/launcher/ui/ParkingModeActivity.kt',
    'app/src/main/res/layout/activity_parking_mode.xml',
    'core/routing-engine-offline/src/main/java/com/ts8/core/routing/AStarRouter.kt',
    'core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/VolumeCapabilityTracker.kt',
    'buildSrc/src/main/kotlin/CoreModulePlugin.kt',
    'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties',
    'docs/UI_design.png',
    'docs/ARCHITECTURE.md',
    'docs/BATCH1_SANDBOX_TEST_REPORT.md')) {
    if (-not (Test-Path -LiteralPath (Join-Path $ProjectDir $probe))) {
        throw "structural file missing: $probe"
    }
}
Write-Ok 'structure checks passed'

# Test sources are deliberately not in this payload, so `gradlew test` here
# reports 0 tests. Say so plainly, and say why that is not a sign of a broken
# tree: the APK is assembled from main source sets only, so it is identical
# whether or not the tests travelled.
$testDirs = @(Get-ChildItem -Path $ProjectDir -Recurse -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'test' -and $_.Parent.Name -eq 'src' })
if ($testDirs.Count -eq 0) {
    Write-Skip 'no test sources in this payload -- `gradlew test` here reports 0 tests'
} else {
    Write-Ok "$($testDirs.Count) module(s) carry test sources"
}

# ============================================================== 4. build

Write-Step '4/5  building the debug APK'

if ($SkipBuild) {
    Write-Skip 'build skipped (-SkipBuild)'
} else {
    $gradlew = Join-Path $ProjectDir 'gradlew.bat'
    if (-not (Test-Path $gradlew)) { throw "gradlew.bat missing at $gradlew" }

    Push-Location $ProjectDir
    $buildExit = 0
    try {
        # No --offline: a fresh machine has no Gradle cache, so the dependency
        # graph is meant to be fetched. The first run downloads Gradle itself
        # (~130 MB) plus AGP and the AndroidX/MapLibre artifacts; expect several
        # minutes. -NoDaemon because this is a one-shot transfer build.
        Write-Host '    gradlew.bat :app:assembleDebug' -ForegroundColor DarkGray
        Write-Host '    (first run downloads Gradle and all dependencies)' -ForegroundColor DarkGray
        & cmd.exe /c "`"$gradlew`" :app:assembleDebug --no-daemon"
        $buildExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($buildExit -ne 0) {
        Write-Host ''
        Write-Err2 "gradle exited $buildExit"
        Write-Host ''
        Write-Host '  Common causes, in order of likelihood:' -ForegroundColor Yellow
        Write-Host '    - no network, or a captive portal between this machine and the internet'
        Write-Host '    - local.properties points at the wrong Android SDK'
        Write-Host '    - a JDK other than 17 was picked (check org.gradle.java.home)'
        Write-Host '    - platform 34 or build-tools 34.0.0 not installed'
        Write-Host ''
        Write-Host '  Re-run 1-Setup-Environment.ps1 to repair the toolchain.' -ForegroundColor Yellow
        throw 'Build failed.'
    }
    Write-Ok 'build succeeded'
}

# ============================================================== 5. result

Write-Step '5/5  result'

$apk = Join-Path $ProjectDir 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $size = (Get-Item -LiteralPath $apk).Length
    $sha  = Get-Sha256 $apk
    Write-Host ''
    Write-Host '  APK built.' -ForegroundColor Green
    Write-Host ''
    Write-Host "    path   $apk"
    Write-Host "    size   $('{0:N0}' -f $size) bytes  ($([math]::Round($size / 1MB, 1)) MB)"
    Write-Host "    sha256 $sha"
    Write-Host ''

    # Comparing the sha256 is a stronger statement than comparing the size: it
    # says this is the same artifact that was tested, not merely one of similar
    # bulk. It holds because a clean build is reproducible -- the same tree
    # produces the same bytes. An incremental build does NOT (D8 reorders dex
    # entries and packageDebug keeps dead gap bytes), so a mismatch is expected
    # when the build directory was not empty, and is not a failure.
    if ($sha -eq $KnownGoodSha) {
        Write-Ok 'identical to the build this transfer was verified against'
    } else {
        Write-Warn2 'sha256 differs from the verified build.'
        Write-Warn2 "  verified: $KnownGoodSha ($('{0:N0}' -f $KnownGoodSize) bytes)"
        Write-Warn2 '  Expected after an incremental build (delete app\build\outputs and rebuild for an exact match).'
        Write-Warn2 '  Unexpected if the source tree was modified.'
    }

    # The size check that actually matters: a missing ABI is 9-13 MB, not a
    # rounding difference, so check for the entries rather than a byte count.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $names = $zip.Entries | ForEach-Object { $_.FullName }
        foreach ($abi in @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')) {
            if (-not ($names -contains "lib/$abi/libmaplibre.so")) {
                Write-Warn2 "APK is missing lib/$abi/libmaplibre.so"
            }
        }
    } finally {
        $zip.Dispose()
    }

    Write-Host '  Install on the head unit:' -ForegroundColor White
    Write-Host '    adb install -r -d app\build\outputs\apk\debug\app-debug.apk'
    Write-Host ''
    Write-Host '  See docs\ADB_INSTALL_AND_RECOVERY.md for the map pack, the Home role,' -ForegroundColor DarkGray
    Write-Host '  and three ways back to the stock launcher.' -ForegroundColor DarkGray
} elseif ($SkipBuild) {
    Write-Skip 'no APK (build was skipped)'
} else {
    Write-Err2 "build reported success but $apk does not exist"
    throw 'APK missing after a successful build.'
}
Write-Host ''
'''

if __name__ == '__main__':
    main()
