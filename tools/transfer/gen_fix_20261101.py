#!/usr/bin/env python3
"""Emit `Fix-2026-11-01.ps1`: the four source files fixed today, and nothing else.

The recipient already has the tree; what they need is a script that overwrites
exactly the files that changed, so a rebuild picks up the three fixes without
re-transferring 316 files. No test sources are included.

The four files, and the defect each one carries in the recipient's copy:

    app/src/main/java/com/ts8/launcher/ui/CompactTopBar.kt
        `View.requireViewById` is API 28. D8 retargets the dex reference to the
        subclass, so an audit that filters on `owner starts with android/` misses
        it. Throws NoSuchMethodError inside setContentView on API 23, before the
        first frame.

    app/src/main/java/com/ts8/launcher/runtime/MapHost.kt
        MAP_FIRST_FRAME_TIMEOUT was cancelled by onDidFinishLoadingMap, which
        fires on load *completion* and may never arrive on a pack with partial
        tile coverage. Now also listens to onDidFinishRenderingFrame, gated on
        the style having parsed.

    app/src/main/res/layout/view_map_phase.xml
        A `<merge>` root swallows the `<include>` tag's `android:id`:
        `LayoutInflater.parseInclude` takes a branch that calls `rInflate` and
        jumps past the `setId`. No view carries R.id.mapPhase, so
        findViewById returns null and both map screens NPE in onCreate.

    core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackValidator.kt
        `readHead` used `readNBytes` (Java 9), which desugar 2.0.4 does not
        backport; `runCatching` swallowed the failure into an empty array and
        every valid pack reported ROUTING_GRAPH_INCOMPATIBLE on API 23.

Encoding rules are the ones `gen_transfer_full.py` uses, because they are the
ones already proven to round-trip byte for byte:
  * the emitted .ps1 is UTF-8 WITH BOM -- PowerShell 5.1 decodes a BOM-less
    .ps1 as the system ANSI codepage, which corrupts every Vietnamese string;
  * a single-quoted here-string keeps its body verbatim, and PowerShell 5.1
    strips exactly one newline before the closing '@, so the writer re-adds
    exactly one;
  * the .ps1 is CRLF, so the captured body arrives CRLF and the writer
    normalises it back to LF;
  * every file is asserted to survive that path byte for byte before it is
    embedded -- a file that would not is an error, not a silent corruption.

Usage: python -I tools/transfer/gen_fix_20261101.py
"""

import hashlib
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_PATH = os.path.join(ROOT, 'transfer_full', 'Fix-2026-11-01.ps1')

FILES = [
    'app/src/main/java/com/ts8/launcher/ui/CompactTopBar.kt',
    'app/src/main/java/com/ts8/launcher/runtime/MapHost.kt',
    'app/src/main/res/layout/view_map_phase.xml',
    'core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackValidator.kt',
]

EXPECTED_APK_SHA = '8ff9a9f44925ccfb18ab24a046a4db84b770f938c531aff5b21e86b988bf135c'
EXPECTED_APK_SIZE = 58737433

PREAMBLE = r'''#Requires -Version 5.1
<#
.SYNOPSIS
    Overwrite the four source files fixed on 2026-11-01, then verify them.

.DESCRIPTION
    This script is deliberately small. It is not a source-tree transfer -- the
    `2-Create-Sources.ps1` payload already carries the whole repository. This
    carries only the files that changed, so a tree that was already copied can
    be brought up to date without moving 316 files again.

    What each file fixes:

      CompactTopBar.kt
        View.requireViewById is API 28 and this view is inflated from
        setContentView. On the TS8 (API 23) that threw NoSuchMethodError during
        Activity start, before any frame, with the launcher dead and no log the
        driver could reach. D8 rewrites the dex reference to name the SUBCLASS,
        which is why an audit filtering on `android/*` owners never saw it.

      MapHost.kt
        The phase is called WAITING_FIRST_FRAME and the failure is
        MAP_FIRST_FRAME_TIMEOUT, but the timeout was cancelled by
        onDidFinishLoadingMap -- "everything settled", which on a pack with
        partial tile coverage may never happen. It now also listens to
        onDidFinishRenderingFrame, gated on the style having parsed so that a
        blank pre-style frame cannot report an empty map as ready.

      view_map_phase.xml
        The root was <merge>. LayoutInflater.parseInclude has a branch for that
        which calls rInflate and jumps straight to close(), never reaching the
        code that applies the <include> tag's android:id. No view in the result
        carries R.id.mapPhase, so findViewById returns null and both map screens
        throw in onCreate -- on every API level, not just the TS8. The root is
        now a FrameLayout.

      PackValidator.kt
        readHead used readNBytes (Java 9). desugar_jdk_libs 2.0.4 does not
        backport it, and runCatching swallowed the failure into an empty
        ByteArray, so every valid pack was reported as
        ROUTING_GRAPH_INCOMPATIBLE on API 23. It now reads with a plain loop.

    No test sources are written. The build does not need them.

.PARAMETER ProjectDir
    The existing project root to patch. Default: .\TS8CarLauncher next to this
    script. The directory must already exist -- this script augments a tree, it
    does not create one, and silently creating an empty directory would hide the
    mistake until the build failed for an unrelated reason.

.PARAMETER VerifyOnly
    Write nothing; only check that the four files already match.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Fix-2026-11-01.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Fix-2026-11-01.ps1 -ProjectDir C:\work\TS8

.NOTES
    This file is UTF-8 WITH a BOM on purpose. Without the BOM, PowerShell 5.1
    reads it as the system ANSI codepage and corrupts every Vietnamese string in
    the here-strings below. Do not re-save it as "UTF-8 without BOM".

    The four files it writes must NOT have a BOM: a BOM in a .kt file is a
    compile error and a BOM in an .xml file breaks the parser's first tag.
#>
[CmdletBinding()]
param(
    [string] $ProjectDir,
    [switch] $VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$here = $PSScriptRoot
if (-not $ProjectDir) { $ProjectDir = Join-Path $here 'TS8CarLauncher' }

# The four files were built into this APK. The script does not build -- its job
# is to put the sources in place -- so this is stated rather than checked, and
# the recipient compares after their own `gradlew :app:assembleDebug`.
$KnownGoodSha  = '__APK_SHA__'
$KnownGoodSize = __APK_SIZE__

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Step([string] $Text) { Write-Host ''; Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Ok([string] $Text)   { Write-Host "    [ok]   $Text" -ForegroundColor Green }
function Write-Fail([string] $Text) { Write-Host "    [FAIL] $Text" -ForegroundColor Red }

function New-Dir([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Force -Path $Path | Out-Null
    }
}

# The writer that `2-Create-Sources.ps1` uses, unchanged, because it is the one
# whose output was verified byte for byte against the repository. Two
# normalisations, both required:
#  1. This .ps1 is stored CRLF and a here-string keeps it verbatim, so the
#     captured text arrives CRLF; every member of the tree is LF.
#  2. PowerShell 5.1 removes exactly ONE newline before the closing '@ -- not
#     "the trailing newline", one single one. So one is re-added.
function Write-Text([string] $RelPath, [string] $Text) {
    if ($VerifyOnly) { return }
    $full = Join-Path $ProjectDir $RelPath
    New-Dir (Split-Path -Parent $full)

    $norm = $Text -replace "`r`n", "`n"
    $norm = $norm -replace "`r", "`n"
    $norm += "`n"

    [IO.File]::WriteAllText($full, $norm, $Utf8NoBom)
}

Write-Step '4 source files -> project tree'
Write-Host "    project : $ProjectDir" -ForegroundColor DarkGray

if (-not (Test-Path -LiteralPath $ProjectDir)) {
    Write-Fail "project root not found: $ProjectDir"
    throw ('Project root not found: ' + $ProjectDir + '. Pass -ProjectDir, or run ' +
           'this script from the directory the project was created in.')
}

# The four files, as hashes -- used by the verify pass below and printed so a
# reader can compare against their own tree without running anything.
$Expected = [ordered]@{}
'''

MIDDLE = r'''
'''

EPILOGUE = r'''
Write-Step 'verifying'

$bad = 0
foreach ($rel in $Expected.Keys) {
    $full = Join-Path $ProjectDir $rel
    if (-not (Test-Path -LiteralPath $full)) {
        Write-Fail "missing   $rel"
        $bad++
        continue
    }
    $actual = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -eq $Expected[$rel]) {
        Write-Ok ("{0}  {1}" -f $actual.Substring(0, 16), $rel)
    } else {
        Write-Fail ("mismatch  $rel")
        Write-Host "              expected $($Expected[$rel])" -ForegroundColor DarkGray
        Write-Host "              actual   $actual" -ForegroundColor DarkGray
        $bad++
    }
}

Write-Step 'result'

if ($bad -ne 0) {
    Write-Fail "$bad of $($Expected.Count) file(s) did not match."
    throw 'Fix not applied cleanly. Nothing else in the tree was touched.'
}

Write-Ok "$($Expected.Count) of $($Expected.Count) files match"

if ($VerifyOnly) {
    Write-Host ''
    Write-Host '    verify-only: nothing was written.' -ForegroundColor DarkGray
    return
}

Write-Host ''
Write-Host '    Next: rebuild, then compare the APK.' -ForegroundColor DarkGray
Write-Host '      cd ' $ProjectDir
Write-Host '      .\gradlew.bat :app:assembleDebug'
Write-Host ''
Write-Host "    The four files were built into an APK of $KnownGoodSize bytes" -ForegroundColor DarkGray
Write-Host "      sha256 $KnownGoodSha" -ForegroundColor DarkGray
Write-Host '    A rebuild from the same toolchain should reproduce it.' -ForegroundColor DarkGray
'''


def read_text(rel):
    """Return the file's text, asserting the here-string path reproduces it."""
    with open(os.path.join(ROOT, rel), 'rb') as fh:
        data = fh.read()
    try:
        text = data.decode('utf-8-sig')
    except UnicodeDecodeError as exc:
        raise SystemExit('{}: not valid UTF-8 ({})'.format(rel, exc))

    text = text.replace('\r\n', '\n').replace('\r', '\n')
    if not text.endswith('\n'):
        text += '\n'

    for line in text.split('\n'):
        if line.startswith("'@"):
            raise SystemExit(
                "{}: contains a line starting with '@, which would terminate "
                'the here-string early'.format(rel))

    if text.encode('utf-8') != data:
        raise SystemExit(
            '{}: the here-string path would not reproduce this file byte for '
            'byte -- BOM, CRLF, or no trailing newline. It cannot travel as '
            'text.'.format(rel))
    return text


def main():
    embedded = []
    for rel in FILES:
        payload = read_text(rel)
        embedded.append((rel, payload, hashlib.sha256(payload.encode('utf-8')).hexdigest()))

    out = io.StringIO()
    out.write(PREAMBLE)
    for rel, _, digest in embedded:
        out.write("$Expected['{}'] = '{}'\n".format(rel, digest))
    out.write(MIDDLE)
    for rel, payload, _ in embedded:
        # Same emission shape as gen_transfer_full.Unit.emit().
        out.write("\nWrite-Text '{}' @'\n{}'@\n".format(rel, payload))
    out.write(EPILOGUE)

    text = out.getvalue()
    text = text.replace('__APK_SHA__', EXPECTED_APK_SHA).replace(
        '__APK_SIZE__', str(EXPECTED_APK_SIZE))

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, 'w', encoding='utf-8-sig', newline='\r\n') as fh:
        fh.write(text)

    print('wrote {}'.format(OUT_PATH))
    for rel, payload, digest in embedded:
        print('  {:6d} bytes  {}  {}'.format(
            len(payload.encode('utf-8')), digest[:16], rel))
    print('  script: {} bytes'.format(os.path.getsize(OUT_PATH)))
    print('  built into APK: {} ({} bytes)'.format(EXPECTED_APK_SHA, EXPECTED_APK_SIZE))
    return 0


if __name__ == '__main__':
    sys.exit(main())
