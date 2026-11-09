#!/usr/bin/env python3
"""Emit `Fix-2026-11-01b.ps1`: the 21 files changed by the second 2026-11-01 round.

The recipient already has the tree. This carries only what changed since the
first round, so a copy that has already been patched can be brought forward
without moving the whole repository again. No test sources are included -- the
build does not need them.

The three defects, and what each file group fixes:

    Point 1 -- the map settings button vanished once the map loaded
        `MapPhaseSurface.render` sets its whole root GONE at READY, and the
        only map-management control lived inside it. On a working map -- the
        normal case -- there was no way into pack management at all. A
        persistent `btnMapSettings` now sits in the browse chrome on both the
        map and home screens.

    Point 2 -- voice search always asked for confirmation after speaking
        `VoiceRouter.Config.requireConfirmation` defaulted to true and nothing
        overrode it, so NavigateTo and PlayMusic always stopped at CONFIRMING
        behind a yes/no bar. The transcript was logged but never shown: the
        snapshot had no field for it. The default is now false, the transcript
        is part of the snapshot, and the overlay quotes it back.

    Point 3 -- the parking screen did not work
        Two independent causes, and fixing either alone leaves it broken:
        (a) the screen had no way out to the map or to music;
        (b) it could never appear on its own -- `parkingAutoEnter` defaulted to
            false with no UI able to write it, and the detector's tick only ran
            while Home or Parking was started, so with the map forward the
            reference count was zero and nothing noticed the car stop.

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

Usage: python -I tools/transfer/gen_fix_20261101b.py
"""

import hashlib
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_PATH = os.path.join(ROOT, 'transfer_full', 'Fix-2026-11-01b.ps1')

FILES = [
    'app/src/main/java/com/ts8/launcher/ui/ParkingDestinations.kt',
    'app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt',
    'app/src/main/java/com/ts8/launcher/ui/ScreenOffActivity.kt',
    'app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt',
    'app/src/main/java/com/ts8/launcher/ui/MapActivity.kt',
    'app/src/main/java/com/ts8/launcher/ui/ParkingModeActivity.kt',
    'app/src/main/java/com/ts8/launcher/ui/SettingsActivity.kt',
    'app/src/main/java/com/ts8/launcher/ui/VoiceOverlayView.kt',
    'app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt',
    'app/src/main/java/com/ts8/launcher/runtime/VoiceCommandExecutor.kt',
    'app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt',
    'app/src/main/res/layout/activity_home.xml',
    'app/src/main/res/layout/activity_map.xml',
    'app/src/main/res/layout/activity_parking_mode.xml',
    'app/src/main/res/layout/activity_settings.xml',
    'app/src/main/res/layout/view_voice_overlay.xml',
    'app/src/main/res/values/strings.xml',
    'core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt',
    'core/navigation-core/src/main/java/com/ts8/core/voice/VoiceScenarios.kt',
    'core/navigation-core/src/main/java/com/ts8/core/voice/VoiceState.kt',
    'core/navigation-core/src/main/java/com/ts8/core/voice/VoiceStateStore.kt',
]

EXPECTED_APK_SHA = 'd1361ae187b467a3bfc2af00141363c684c0a7843c3e0555ccd3ec743052a353'
EXPECTED_APK_SIZE = 58688307

PREAMBLE = r'''#Requires -Version 5.1
<#
.SYNOPSIS
    Overwrite the 21 source files changed on 2026-11-01 (second round), then verify.

.DESCRIPTION
    This script is deliberately small. It is not a source-tree transfer -- the
    `2-Create-Sources.ps1` payload already carries the whole repository. This
    carries only the files that changed after the previous fix, so a tree that
    was already copied can be brought up to date without moving 316 files again.

    What changed, and why:

      Point 1 -- the map settings button disappeared once the map loaded
        MapPhaseSurface.render sets its whole root GONE the moment the map is
        READY, and the only map-management control lived inside that root. On a
        working map -- the normal case -- there was no way into pack management
        at all. btnMapSettings now lives in the browse chrome of both the map
        and the home screen.

      Point 2 -- voice search always asked to confirm after you spoke
        VoiceRouter.Config.requireConfirmation defaulted to true and nothing
        overrode it, so a navigation or music command always stopped at
        CONFIRMING behind a yes/no bar: two interactions where one was asked
        for, answered by a tap a driver should not have to give. The default is
        now false. What replaces the prompt is the transcript itself -- the
        overlay quotes "Ban noi: ..." -- so a misrecognition is still visible.

        CONFIRMING is kept, but only for an executor that genuinely cannot
        choose (one name, two places). That is a disambiguation, not a safety
        gate.

      Point 3 -- the parking screen did not work
        Two independent causes, and fixing either one alone leaves it broken:

        (a) The screen had one exit and no destinations. It now offers the map
            and music. The launch goes through ParkingDestinations.kt rather
            than into ParkingModeActivity.kt, because ParkingMusicContractTest
            holds that file to a rule: nothing in it may launch a screen or
            begin playback. Putting the call there would have meant weakening
            the test.

        (b) It could never appear by itself. The auto-enter setting defaulted
            to false and no UI could write it, so the resolver refused forever
            and the screen was reachable only from Quick Settings. Separately,
            the detector's one-second tick only ran while Home or Parking was
            started -- so a driver navigating with the map up, which is exactly
            when a car stops somewhere, had the reference count at zero and the
            car coming to rest was never noticed.

        Both are fixed. The setting now defaults to true and has two working
        chips in Settings, and BaseScreenActivity joins the detector for every
        screen. ScreenOffActivity opts out: opening the parking clock over the
        black window the driver just asked for would undo that screen entirely.

        The evidence gates are untouched and still refuse on their own terms: a
        stop under twenty seconds is traffic, the location engine has to agree
        the position stopped wandering, a lost fix restarts the dwell rather
        than banking it, and a call, the reverse camera or a running route
        blocks entry outright. Speed zero alone was never enough and still is
        not.

    No test sources are written. The build does not need them.

.PARAMETER ProjectDir
    The existing project root to patch. Default: .\TS8CarLauncher next to this
    script. The directory must already exist -- this script augments a tree, it
    does not create one, and silently creating an empty directory would hide the
    mistake until the build failed for an unrelated reason.

.PARAMETER VerifyOnly
    Write nothing; only check that the files already match.

.EXAMPLE
    powershell -File .\Fix-2026-11-01b.ps1

.EXAMPLE
    powershell -File .\Fix-2026-11-01b.ps1 -ProjectDir C:\work\TS8

.NOTES
    This file is UTF-8 WITH a BOM on purpose. Without the BOM, PowerShell 5.1
    reads it as the system ANSI codepage and corrupts every Vietnamese string in
    the here-strings below. Do not re-save it as "UTF-8 without BOM".

    The files it writes must NOT have a BOM: a BOM in a .kt file is a compile
    error and a BOM in an .xml file breaks the parser's first tag.
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

# These files were built into this APK. The script does not build -- its job is
# to put the sources in place -- so this is stated rather than checked, and the
# recipient compares after their own `gradlew :app:assembleDebug`.
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

Write-Step '21 source files -> project tree'
Write-Host "    project : $ProjectDir" -ForegroundColor DarkGray

if (-not (Test-Path -LiteralPath $ProjectDir)) {
    Write-Fail "project root not found: $ProjectDir"
    throw ('Project root not found: ' + $ProjectDir + '. Pass -ProjectDir, or run ' +
           'this script from the directory the project was created in.')
}

# The files, as hashes -- used by the verify pass below and printed so a reader
# can compare against their own tree without running anything.
$Expected = [ordered]@{}
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
Write-Host "    These files were built into an APK of $KnownGoodSize bytes" -ForegroundColor DarkGray
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
    for rel, payload, _ in embedded:
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
    print('  {} files'.format(len(embedded)))
    print('  script: {} bytes'.format(os.path.getsize(OUT_PATH)))
    print('  built into APK: {} ({} bytes)'.format(EXPECTED_APK_SHA, EXPECTED_APK_SIZE))
    return 0


if __name__ == '__main__':
    sys.exit(main())
