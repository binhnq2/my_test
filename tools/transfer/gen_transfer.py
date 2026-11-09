#!/usr/bin/env python3
"""Generate script 2 of the transfer -- one driver and four data parts.

Why generated rather than hand-written: the script has to reproduce ~210 files
byte-for-byte. Retyping them by hand is how a single Unicode character in a
Vietnamese string goes missing, and a lost character here is a lost character in
the driver's UI. This reads every file from disk and embeds it literally.

Why five files rather than one: the payload is 1.2 MB of here-strings, which is
an unpleasant thing to open, diff or copy around. It is split into four data
parts of roughly equal size, plus a small driver that holds the parameters, the
environment check, the integrity table, the Gradle call and the result report.
The driver dot-sources the parts, so they share its scope and its writers --
that, and not a copy of the helpers in each part, is what keeps the written
bytes identical whichever part writes a file.

    transfer/2-Create-Sources.ps1    driver
    transfer/2a-Write-Tree-1.ps1     payload part 1 of 4
    transfer/2b-Write-Tree-2.ps1     payload part 2 of 4
    transfer/2c-Write-Tree-3.ps1     payload part 3 of 4
    transfer/2d-Write-Tree-4.ps1     payload part 4 of 4

Encoding rules, both of which matter:
  * Every output is UTF-8 WITH BOM. PowerShell 5.1 reads a .ps1 without a BOM as
    ANSI, which mangles every Vietnamese character in the here-strings. That
    applies to the parts too: dot-sourcing does not change how they are decoded.
  * Text files are embedded in single-quoted here-strings (@' ... '@), which
    suppress all PowerShell interpolation. The only character sequence that can
    break one is a line starting with '@ -- the generator refuses to proceed if
    any file contains one.

Usage:
  python -I tools/transfer/gen_transfer.py
"""
import os
import sys
import base64
import hashlib

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, 'transfer')
DRIVER = os.path.join(OUT_DIR, '2-Create-Sources.ps1')
PARTS = [
    '2a-Write-Tree-1.ps1',
    '2b-Write-Tree-2.ps1',
    '2c-Write-Tree-3.ps1',
    '2d-Write-Tree-4.ps1',
]

SKIP_DIRS = {'build', '.gradle', '__pycache__', '.git', '.idea'}

# Test sources are excluded on purpose: the user's brief allows skipping them,
# and they are the only part of the tree that does not affect the APK. They stay
# in the repository so `gradlew test` still works there.
def is_test_source(rel):
    p = rel.replace(os.sep, '/')
    return '/src/test/' in p or '/src/androidTest/' in p

# tools/testdata holds a 10.7 MB .mbtiles copy used by the routing test; it is
# test-only, and the same bytes live in the map pack.
#
# tools/transfer holds this generator. It is not shipped for a different reason:
# the tree on the target machine has no test sources, no PNGs and no prompt doc
# (all of them deliberately left behind), so running the generator there would
# produce a DIFFERENT, smaller script 2 that silently omits files. Shipping a
# tool that regenerates a wrong artefact is worse than not shipping it.
def is_excluded(rel):
    p = rel.replace(os.sep, '/')
    return p.startswith('tools/testdata/') or p.startswith('tools/transfer/')

def collect():
    files = []

    def walk(path):
        for dirpath, dirnames, filenames in os.walk(path):
            dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
            for name in sorted(filenames):
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, ROOT)
                if is_test_source(rel) or is_excluded(rel):
                    continue
                files.append(rel)

    walk(os.path.join(ROOT, 'app', 'src'))
    files.append(os.path.join('app', 'build.gradle.kts'))
    files.append(os.path.join('app', 'proguard-rules.pro'))

    for module in sorted(os.listdir(os.path.join(ROOT, 'core'))):
        mdir = os.path.join(ROOT, 'core', module)
        if not os.path.isdir(mdir):
            continue
        gradle = os.path.join(mdir, 'build.gradle.kts')
        if os.path.isfile(gradle):
            files.append(os.path.relpath(gradle, ROOT))
        src = os.path.join(mdir, 'src')
        if os.path.isdir(src):
            walk(src)

    for name in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
                 'gradlew', 'gradlew.bat'):
        p = os.path.join(ROOT, name)
        if os.path.isfile(p):
            files.append(name)

    for name in ('gradle-wrapper.properties', 'gradle-wrapper.jar'):
        p = os.path.join(ROOT, 'gradle', 'wrapper', name)
        if os.path.isfile(p):
            files.append(os.path.join('gradle', 'wrapper', name))

    walk(os.path.join(ROOT, 'buildSrc', 'src'))
    bs = os.path.join(ROOT, 'buildSrc', 'build.gradle.kts')
    if os.path.isfile(bs):
        files.append(os.path.join('buildSrc', 'build.gradle.kts'))

    walk(os.path.join(ROOT, 'tools'))
    walk(os.path.join(ROOT, 'docs'))

    # Drop the PNG (2.3 MB of design reference, not needed to build) and the
    # prompt file, which is the brief rather than a deliverable.
    keep = []
    for rel in files:
        p = rel.replace(os.sep, '/')
        if p.endswith('.png'):
            continue
        if p == 'docs/TS8_REAL_LAUNCHER_MAP_MUSIC_PROMPT.md':
            continue
        if p == 'gradle.properties':
            # Script 1 writes this fresh for each machine. Shipping the build
            # host's copy would carry its JDK path and proxy truststore, both of
            # which are wrong here -- so the payload deliberately omits it and
            # script 2 fails loudly if script 1 was not run.
            continue
        keep.append(rel)
    return sorted(set(keep), key=lambda r: r.replace(os.sep, '/'))

def classify(rel):
    full = os.path.join(ROOT, rel)
    with open(full, 'rb') as fh:
        data = fh.read()
    if b'\x00' in data:
        return 'binary', data
    try:
        text = data.decode('utf-8-sig')
    except UnicodeDecodeError as exc:
        raise SystemExit(f'{rel}: not valid UTF-8 ({exc})')
    # Normalise line endings to LF before embedding.
    #
    # Two layout files and gradlew.bat are CRLF on this host. Embedding them
    # as-is would be actively wrong: the generated .ps1 is itself written with
    # CRLF, and a payload that already contains CRLF would come out as \r\r\n.
    # So the payload is always LF, and the few files that need CRLF on disk get
    # it back in Write-Text (see the $Crlf flag below).
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    for line in text.split('\n'):
        if line.startswith("'@"):
            raise SystemExit(f"{rel}: contains a line starting with '@, which "
                             f"would terminate the here-string early")
    if not text.endswith('\n'):
        text += '\n'
    return 'text', text

def wants_crlf(rel):
    """cmd.exe mishandles LF-only batch files, so .bat must be written CRLF.

    Everything else is LF, which is what git, AAPT and the Kotlin compiler all
    expect and what the verified tree contains.
    """
    return rel.replace(os.sep, '/').lower().endswith(('.bat', '.cmd'))

def expected_bytes(rel, kind, payload):
    """The exact bytes Write-Text / Write-B64 will put on disk."""
    if kind == 'binary':
        return payload
    if wants_crlf(rel):
        body = payload
        if not body.endswith('\n'):
            body += '\n'
        return body.replace('\n', '\r\n').encode('utf-8')
    return payload.encode('utf-8')

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def partition(entries, n):
    """Split into n contiguous slices of roughly equal payload size.

    Contiguous rather than round-robin so each part stays thematically coherent
    -- all of app/src/main/res in one file, all of core/ in another -- which is
    what makes a 300 KB part readable when someone opens it.
    """
    if len(entries) < n:
        raise SystemExit(f'{len(entries)} files cannot be split into {n} parts')
    weights = []
    for _, kind, payload in entries:
        # Base64 inflates by a third, and it is that text, not the raw bytes,
        # that ends up in the part -- so weigh what is actually written.
        if kind == 'binary':
            weights.append(len(base64.b64encode(payload)))
        else:
            weights.append(len(payload.encode('utf-8')))

    prefix = [0]
    for w in weights:
        prefix.append(prefix[-1] + w)
    total = prefix[-1]

    cuts = [0]
    for i in range(1, n):
        target = total * i / n
        lo = cuts[-1] + 1                 # leave at least one file per part
        hi = len(entries) - (n - i)       # and reserve one for each part after
        cuts.append(min(range(lo, hi + 1), key=lambda k: abs(prefix[k] - target)))
    cuts.append(len(entries))
    return [entries[cuts[i]:cuts[i + 1]] for i in range(n)]

def main():
    files = collect()
    entries = []
    for rel in files:
        kind, payload = classify(rel)
        entries.append((rel.replace(os.sep, '/'), kind, payload))

    text_count = sum(1 for _, k, _ in entries if k == 'text')
    binary_count = len(entries) - text_count

    slices = partition(entries, len(PARTS))

    # -- the driver -------------------------------------------------------
    parts = []
    add = parts.append
    add(HEADER)
    add(DRIVER_LOAD)
    add('\n# ==================================================== integrity table\n')
    add('# sha256 of every file written above, in all four parts. Checked after the\n')
    add('# write so a truncated payload fails here rather than inside a compiler\n')
    add('# error. Living in the driver rather than in a part means the check runs\n')
    add('# once, over the whole tree, no matter how the parts were divided.\n')
    add('$Expected = @{\n')
    for rel, kind, payload in entries:
        raw = expected_bytes(rel, kind, payload)
        add(f"    '{rel}' = '{sha256(raw)}'\n")
    add('}\n')
    add(FOOTER)

    write_script(DRIVER, ''.join(parts))

    # -- the payload parts ------------------------------------------------
    for index, (name, chunk) in enumerate(zip(PARTS, slices), start=1):
        body = []
        add = body.append
        add(part_header(index, chunk))
        for rel, kind, payload in chunk:
            if kind == 'text':
                if wants_crlf(rel):
                    add(f"\nWrite-Crlf '{rel}' @'\n{payload}'@\n")
                else:
                    add(f"\nWrite-Text '{rel}' @'\n{payload}'@\n")
            else:
                b64 = base64.b64encode(payload).decode('ascii')
                wrapped = '\n'.join(b64[i:i + 100] for i in range(0, len(b64), 100))
                add(f"\nWrite-B64 '{rel}' @'\n{wrapped}\n'@\n")
        write_script(os.path.join(OUT_DIR, name), ''.join(body))

    print(f'wrote {DRIVER}')
    print(f'  files embedded : {len(entries)} ({text_count} text, {binary_count} binary)')
    grand = 0
    for name, chunk in zip(PARTS, slices):
        path = os.path.join(OUT_DIR, name)
        size = os.path.getsize(path)
        grand += size
        print(f'  {name:<22} {size:>10,} bytes  {len(chunk):>4} files  '
              f'{sha256(open(path, "rb").read())}')
    driver_size = os.path.getsize(DRIVER)
    grand += driver_size
    print(f'  {"2-Create-Sources.ps1":<22} {driver_size:>10,} bytes  (driver)')
    print(f'  total          : {grand:,} bytes in {len(PARTS) + 1} files')


def write_script(path, text):
    # UTF-8 WITH BOM, CRLF: PowerShell 5.1 reads a BOM-less .ps1 as the ANSI
    # codepage and turns every Vietnamese string in the here-strings into
    # mojibake. Dot-sourcing a part does not change how that part is decoded, so
    # the parts need the BOM for the same reason the driver does.
    with open(path, 'w', encoding='utf-8-sig', newline='\r\n') as fh:
        fh.write(text)


def part_header(index, chunk):
    count = len(chunk)
    return PART_TEMPLATE.format(index=index, total=len(PARTS), count=count,
                                letter=chr(ord('a') + index - 1),
                                first=chunk[0][0], last=chunk[-1][0])


PART_TEMPLATE = r'''#Requires -Version 5.1
<#
.SYNOPSIS
    Payload part {index} of {total} for 2-Create-Sources.ps1 -- {count} files.

.DESCRIPTION
    Holds the source of {count} files as single-quoted here-strings, from
    {first}
    through
    {last}

    This file is NOT runnable on its own and is not meant to be read as a
    program: it is a data file that happens to be PowerShell. 2-Create-Sources.ps1
    dot-sources it, so every Write-Text / Write-Crlf / Write-B64 call below runs
    with the writers, $ProjectDir and $VerifyOnly that the driver defined.

.NOTES
    UTF-8 WITH a BOM, like the driver, because PowerShell 5.1 decodes a BOM-less
    .ps1 as the system ANSI codepage and the Vietnamese strings below would be
    corrupted before any of this code runs.
#>

# A part is not a program. Run on its own it has no writers and no ProjectDir,
# and every call below would fail one at a time with a confusing error; say
# what is wrong once instead. Dot-sourced, Write-Text already exists by here,
# because the driver defines its helpers before it loads any part.
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

HEADER = r'''#Requires -Version 5.1
<#
.SYNOPSIS
    Writes the complete TS8 Launcher source tree, then builds the debug APK.

.DESCRIPTION
    This is the driver for the second half of the transfer. The source of every
    file that matters is embedded as text (and as base64 for the one binary, the
    Gradle wrapper jar) across four companion files:

        2a-Write-Tree-1.ps1
        2b-Write-Tree-2.ps1
        2c-Write-Tree-3.ps1
        2d-Write-Tree-4.ps1

    They are loaded from the directory this file sits in, in that order, and
    share its scope -- which is why they are not run directly: they call the
    writers (Write-Text, Write-Crlf, Write-B64) and read the parameters
    ($ProjectDir, $VerifyOnly) that are defined here. Running one of them on its
    own does nothing useful, and says so.

    Order of work:
      1. Check the environment (JDK 17, Android SDK, gradle.properties).
      2. Load the four parts; each writes its share of the tree.
      3. Verify every file against an embedded SHA-256.
      4. Build :app:assembleDebug.
      5. Report the APK path and size.

    Test sources are deliberately absent (see the note near the file list). They
    do not affect the APK; the repository keeps them.

    All five files must sit in the same directory. Copy the whole set.

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
    This file and the four parts are UTF-8 WITH a BOM on purpose. Without the
    BOM, PowerShell 5.1 reads them as the system ANSI codepage and every
    Vietnamese string in the here-strings is corrupted. Do not re-save any of
    them as "UTF-8 without BOM", and do not mix parts from different transfers --
    the driver checks them against each other and stops if they do not belong
    together.
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

# The build this transfer was verified against, recorded so the result step can
# say whether the APK it just produced is the same artifact. See TEST_REPORT.md
# section 1.0 for why this and not the size is the meaningful comparison.
$KnownGoodSha  = '5400FDA4E17EDFEB424571BD8397AF06328E394F23BF7806FB24CA802B4CC164'
$KnownGoodSize = 58599327

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

# UTF-8 WITHOUT BOM. This is the opposite of what these script files themselves
# need: the .ps1 files must carry a BOM for PowerShell 5.1 to read them, but
# every file they write must NOT, because a BOM in a .kt file is a compile error
# and a BOM in a .properties file breaks the first key.
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Text([string] $RelPath, [string] $Text) {
    if ($VerifyOnly) { return }
    $full = Join-Path $ProjectDir $RelPath
    New-Dir (Split-Path -Parent $full)

    # Two normalisations, both required for the written bytes to equal the bytes
    # that were hashed:
    #
    #  1. These .ps1 files are stored with CRLF line endings, and a here-string
    #     keeps them verbatim, so the captured text arrives CRLF. Every file in
    #     the tree is LF -- which is what the repository, git and AAPT all use.
    #  2. PowerShell drops the last newline before the closing '@, so a file
    #     that ends with a newline (all of them do) would lose it.
    #
    # Doing both here means the fix holds even if these scripts are later
    # re-saved with different line endings, and it holds for whichever part
    # writes the file, because the parts share this function.
    $norm = $Text -replace "`r`n", "`n"
    $norm = $norm -replace "`r", "`n"
    if (-not $norm.EndsWith("`n")) { $norm += "`n" }

    # WriteAllText with an explicit no-BOM encoding: a BOM in a .kt file is a
    # compile error and a BOM in a .properties file breaks the first key.
    [IO.File]::WriteAllText($full, $norm, $Utf8NoBom)
}

# The one file class that must keep CRLF: batch files. cmd.exe's parser fails on
# LF-only batch files at a `goto :label` whose label sits at end of file, and
# gradlew.bat is how every Windows build starts.
function Write-Crlf([string] $RelPath, [string] $Text) {
    if ($VerifyOnly) { return }
    $full = Join-Path $ProjectDir $RelPath
    New-Dir (Split-Path -Parent $full)
    $norm = $Text -replace "`r`n", "`n"
    $norm = $norm -replace "`r", "`n"
    if (-not $norm.EndsWith("`n")) { $norm += "`n" }
    $norm = $norm -replace "`n", "`r`n"
    [IO.File]::WriteAllText($full, $norm, $Utf8NoBom)
}

function Write-B64([string] $RelPath, [string] $Base64) {
    if ($VerifyOnly) { return }
    $full = Join-Path $ProjectDir $RelPath
    New-Dir (Split-Path -Parent $full)
    $clean = ($Base64 -replace '\s', '')
    $bytes = [Convert]::FromBase64String($clean)
    [IO.File]::WriteAllBytes($full, $bytes)
}

function Get-Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# ============================================================== banner

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ProjectDir) { $ProjectDir = Join-Path $here 'TS8CarLauncher' }
$ProjectDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ProjectDir)

Write-Host ''
Write-Host '  TS8 Launcher - source tree and build (script 2 of 2)' -ForegroundColor White
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
# this shell.
$gradleProps = Join-Path $ProjectDir 'gradle.properties'
if (-not (Test-Path $gradleProps) -and -not $VerifyOnly) {
    if ($SkipBuild) {
        # Writing the tree without building does not need a Gradle config, but
        # say so loudly: the tree is incomplete until script 1 runs.
        Write-Warn2 'gradle.properties is absent (script 1 has not run for this directory)'
    } else {
        throw ("gradle.properties is missing from $ProjectDir. It is generated, not " +
               'part of this payload, because it carries a machine-specific JDK path. ' +
               'Run 1-Setup-Environment.ps1 -ProjectDir "' + $ProjectDir + '" first.')
    }
}

$propsText = ''
if (Test-Path $gradleProps) {
    $propsText = Get-Content -LiteralPath $gradleProps -Raw
    if ($propsText -match 'proxyHost') {
        Write-Warn2 'gradle.properties still names a proxy; the build will fail if that proxy is unreachable'
    }
    if ($propsText -notmatch 'org\.gradle\.java\.home') {
        Write-Warn2 'gradle.properties has no org.gradle.java.home; Gradle will use whatever JDK it finds'
    }
    Write-Ok 'gradle.properties present'
}

# -- JDK 17 ------------------------------------------------------------------
# Three sources, in order of trustworthiness: the pin script 1 wrote, JAVA_HOME,
# then PATH. The pin wins because script 1 knows exactly which JDK it installed.
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
    # java -version writes to stderr, and redirecting a native command's stderr
    # with ErrorActionPreference = 'Stop' turns each line into a terminating
    # NativeCommandError in PowerShell 5.1 -- the version check would abort the
    # script it is trying to guard. Letting cmd.exe do the redirect avoids the
    # whole mechanism.
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
    $sdkLine = (Get-Content -LiteralPath $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1)
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

# The four payload parts are dot-sourced, not run as child processes and not
# invoked with &, for one reason: they must see this script's writers, its
# $ProjectDir and its $VerifyOnly. A child scope would need a copy of all of that
# to be passed in, and a copy is a place for the two to drift apart.
#
# Dot-sourcing also means a part cannot call `exit` -- it would end the driver.
# None of them can: the generator only ever emits Write-Text/Write-Crlf/
# Write-B64 calls, one per embedded file.
#
# The presence check comes before the project directory is created: bailing out
# after `New-Dir` would leave an empty directory behind and make "nothing was
# written" a half-truth.
$here = $PSScriptRoot
$missingParts = @()
foreach ($part in @(
    '2a-Write-Tree-1.ps1',
    '2b-Write-Tree-2.ps1',
    '2c-Write-Tree-3.ps1',
    '2d-Write-Tree-4.ps1')) {
    if (-not (Test-Path -LiteralPath (Join-Path $here $part))) { $missingParts += $part }
}
if ($missingParts.Count -gt 0) {
    # Check all four before loading any: writing half a tree and then stopping
    # leaves a directory that looks more complete than it is.
    Write-Err2 "$($missingParts.Count) of the 4 payload parts are not next to this script"
    $missingParts | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
    Write-Host ''
    Write-Host "  They must all sit in: $here" -ForegroundColor Yellow
    Write-Host '  Copy the whole set -- 2-Create-Sources.ps1 plus 2a through 2d -- together.' -ForegroundColor Yellow
    throw 'Payload parts missing. Nothing was written.'
}

if (-not $VerifyOnly) { New-Dir $ProjectDir }

$partNo = 0
foreach ($part in @(
    '2a-Write-Tree-1.ps1',
    '2b-Write-Tree-2.ps1',
    '2c-Write-Tree-3.ps1',
    '2d-Write-Tree-4.ps1')) {
    $partNo++
    $path = Join-Path $here $part
    if (-not $VerifyOnly) {
        Write-Host "    $partNo/4  $part" -ForegroundColor DarkGray
    }
    . $path
}
if (-not $VerifyOnly) {
    Write-Ok 'four payload parts loaded'
} else {
    # The parts are still loaded -- they are what defines the writer calls -- but
    # every one of those returns immediately when $VerifyOnly is set.
    Write-Skip 'verify only: parts loaded, nothing written'
}

'''

FOOTER = r'''
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
# likely be missing rather than trusting the total alone.
foreach ($probe in @(
    'settings.gradle.kts',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt',
    'core/routing-engine-offline/src/main/java/com/ts8/core/routing/AStarRouter.kt',
    'gradle/wrapper/gradle-wrapper.jar',
    'docs/ARCHITECTURE.md')) {
    if (-not (Test-Path -LiteralPath (Join-Path $ProjectDir $probe))) {
        throw "structural file missing: $probe"
    }
}
Write-Ok 'structure checks passed'

# Say this out loud rather than leaving it to be discovered: `gradlew test`
# against this tree reports 0 tests, because test sources are not part of the
# payload. 0 tests is not the same as 316 passing, and the report in
# docs/TEST_REPORT.md belongs to the tree that has them.
$testDirs = @(Get-ChildItem -Path $ProjectDir -Recurse -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'test' -and $_.Parent.Name -eq 'src' })
if ($testDirs.Count -eq 0) {
    Write-Warn2 'no src/test directories: `gradlew test` here will report 0 tests (by design)'
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
        # No --offline: this machine has no proxy, so Gradle is meant to fetch
        # the dependency graph. The first run downloads Gradle itself (~130 MB)
        # plus AGP and the AndroidX/MapLibre artifacts; expect several minutes.
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
    Write-Host "    path  $apk"
    Write-Host "    size  $('{0:N0}' -f $size) bytes  ($([math]::Round($size / 1MB, 1)) MB)"
    Write-Host "    sha256 $sha"
    Write-Host ''

    # Comparing the sha256 is a stronger statement than comparing the size: it
    # says this is the same artifact that was tested, not merely one of similar
    # bulk. It holds because a clean build here is reproducible -- the same tree
    # produces the same bytes. An incremental build does NOT (D8 reorders the
    # dex entries and packageDebug keeps dead gap bytes), so a mismatch is
    # expected when the build directory was not empty, and is not a failure.
    if ($sha -eq $KnownGoodSha) {
        Write-Ok 'identical to the build this transfer was verified against'
    } else {
        Write-Warn2 'sha256 differs from the verified build.'
        Write-Warn2 "  verified: $KnownGoodSha ($('{0:N0}' -f $KnownGoodSize) bytes)"
        Write-Warn2 '  Expected after an incremental build (re-run with a clean build dir for an exact match).'
        Write-Warn2 '  Unexpected if the source tree was modified.'
    }

    # The size check that actually matters: a missing ABI is 9-13 MB, not a
    # rounding difference, so check for the entries rather than for a byte count.
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
