# Transferring the project to another machine

Seven PowerShell files and nothing else. Copy all of them into one empty
directory on the target machine and run the two numbered scripts in order.

```
1-Setup-Environment.ps1    toolchain: JDK 17, Android SDK, licences, per-machine files
2-Create-Sources.ps1       driver: checks, integrity table, build, result
2a-Write-Tree-1.ps1        payload part 1 of 6
2b-Write-Tree-2.ps1        payload part 2 of 6
2c-Write-Tree-3.ps1        payload part 3 of 6
2d-Write-Tree-4.ps1        payload part 4 of 6
2e-Write-Tree-5.ps1        payload part 5 of 6
2f-Write-Tree-6.ps1        payload part 6 of 6
```

`2a`–`2f` are data, not programs. They hold the source of **345 files** as
here-strings and are loaded by `2-Create-Sources.ps1`; running one directly just
prints a pointer back to the driver. **All seven must sit in the same folder** —
the driver refuses to start a partial write if any part is missing.

This set supersedes the earlier four-part transfer: it also carries the design
PNG and every document, so the target machine has the whole program and not a
buildable subset of it. Unit tests and the 10.7 MB map fixture are the
deliberate omissions — see "What is deliberately not transferred" below.

The source of all seven lives in `../transfer_full/`. After a transfer they sit
wherever you put them; this document travels with the tree so the person on the
other machine has it.

## What is in the payload

| | files | why it is here |
|---|---:|---|
| `app/src/main` | 160 | the launcher itself, including the two recognition providers and the voice-model screen |
| `core/` (12 modules) | 105 | the runtime, map, music, volume, storage, diagnostics and voice layers |
| `app/*.gradle.kts`, `proguard-rules.pro` | 2 | the application module's build script and the R8 rules, Vosk/JNA included |
| `buildSrc/` | 3 | the build logic the module scripts depend on |
| `gradle/` | 2 | `gradle-wrapper.jar` and the distribution pin, base64 |
| `tools/` (everything else, generator included) | 15 | `tools/transfer/gen_transfer_full.py` ships on purpose: it is the same selection rule this tree was written from, so the transfer can be regenerated and re-checked later. `tools/voicepack/` builds the model pack |
| `docs/` | 53 | the reports this transfer is meant to reproduce, including the six §15 `VOICE_*` documents, the batch's final report, plus `UI_design.png` (2.3 MB) |
| `.vscode/` + root files | 5 | editor settings and `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradlew.bat` |

345 files in total: 340 text, 5 binary. Binaries are base64 in 40 KB chunks
spread across the parts, reassembled and hashed by the driver before the build.
There is no `androidTest` source set in this project — the on-device checklist is
a human procedure, not an instrumented test — so nothing is missing when that
directory does not appear.

The **model pack does not travel in this payload**. It is 53 MB of third-party
data with its own licence obligations and its own delivery route (USB, then
`Cài đặt → Quản lý mô hình giọng nói`); see `VOICE_MODEL_PACK.md`. A transferred
tree therefore builds an APK that runs with no microphone until the pack is
installed — which is the state the brief requires the app to survive.

## What the target machine needs

| | |
|---|---|
| Windows | 10 1803 or later — `tar.exe` is used to unpack the JDK |
| PowerShell | 5.1, the one that ships with Windows. PowerShell 7 is not required |
| Network | direct internet access. **No proxy configuration.** If a proxy is in the way, set `HTTP_PROXY` / `HTTPS_PROXY` in the environment before running |
| Disk | about 6 GB free (JDK ~300 MB, SDK ~2 GB, Gradle plus caches ~2 GB, APK ~48 MB), plus 8.3 MB for the transfer itself |
| Privileges | none. Administrator is not needed |
| Android Studio | **not required** |

## Running it

```powershell
cd <directory holding the seven scripts>
powershell -ExecutionPolicy Bypass -File .\1-Setup-Environment.ps1
powershell -ExecutionPolicy Bypass -File .\2-Create-Sources.ps1
```

That is the whole procedure. The driver ends by printing the APK path, its size,
its sha256 and the `adb install` line. The project lands in `.\TS8CarLauncher`
next to the scripts.

No arguments are required. If the machine has no `D:` drive, script 1 uses
`C:\_ts8_toolchain` instead of its default and says so; pass `-InstallRoot` to
choose a location yourself.

**If the toolchain is already installed** on this machine — script 1 ran before
and `gradlew.bat --version` succeeded — skip script 1 and run script 2 alone.
Script 2 only needs a JDK 17 on `JAVA_HOME` or `PATH`, or the `org.gradle.java.home`
pin that script 1 recorded in `gradle.properties`.

## Parameters

**Script 1**

| parameter | default | meaning |
|---|---|---|
| `-InstallRoot` | `D:\_ts8_toolchain` | where the JDK and the Android SDK go |
| `-ProjectDir` | `.\TS8CarLauncher` | where `local.properties` and `gradle.properties` are written |
| `-SdkOnly` | | use a JDK 17 already on the machine; install only the SDK |
| `-Force` | | redownload and replace what is already installed |

**Script 2**

| parameter | meaning |
|---|---|
| `-ProjectDir` | where to build the tree; an existing directory is reused, not deleted |
| `-SkipBuild` | write and verify the files, do not run Gradle |
| `-VerifyOnly` | write nothing; only compare an existing tree against the embedded hashes |

The six parts take no parameters. Everything they need — `$ProjectDir`,
`-VerifyOnly`, the writers — is defined by the driver before it loads them.

Putting everything on drive C:

```powershell
powershell -ExecutionPolicy Bypass -File .\1-Setup-Environment.ps1 `
    -InstallRoot C:\ts8 -ProjectDir C:\work\TS8CarLauncher
powershell -ExecutionPolicy Bypass -File .\2-Create-Sources.ps1 -ProjectDir C:\work\TS8CarLauncher
```

## What script 1 does

1. Probes the network with a real GET and reports the HTTP status.
2. Reads the system proxy out of the registry — the JVM does not read that key,
   so a machine that "has internet" can still fail every `sdkmanager` download —
   and passes `--proxy` when one is found.
3. JDK 17. An existing JDK is used if its major version is already 17; otherwise
   Temurin is downloaded. Kotlin 1.9.25 cannot parse a newer release string, and
   AGP 8.7 requires 17.
4. `cmdline-tools`, licences accepted non-interactively, then `platform-tools`,
   `build-tools;34.0.0`, `platforms;android-34` and `platforms;android-23`.
   Platform 23 is there so lint and the API-level checks run against the version
   the head unit actually has.
5. Writes `local.properties` (the SDK path) and `gradle.properties` (the JDK 17
   pin, no proxy, no truststore). Both are UTF-8 **without** a BOM: a BOM in a
   `.properties` file becomes part of the first key name, so `sdk.dir` would be
   read as `U+FEFFsdk.dir`, silently ignored. The script checks the three bytes
   after writing rather than trusting the writer.
6. `gradlew.bat --version` if the source tree is already present.

Every step is idempotent. Re-running repairs a partial install and changes
nothing that already works.

## What script 2 does

1. Reads `gradle.properties` for the JDK 17 pin script 1 recorded, then falls
   back to `JAVA_HOME`, then to `PATH`.
2. Checks that all six parts are present, then dot-sources them in order. They
   create the directories and write 345 files (340 text, 5 binary — the Gradle
   wrapper jar, the design PNG, and three files that cannot survive the
   CRLF/LF round-trip, base64 in 40 KB chunks).
3. Compares every file against an embedded SHA-256 table held in the driver and
   stops on the first mismatch. The count is checked too, so a part that stops
   early cannot be mistaken for a complete tree.
4. Runs `gradlew.bat :app:assembleDebug --no-daemon`.
5. Prints the APK path, size and sha256, and compares hash and size against
   `17D1FB18…A2710` / 49,799,534 bytes — the 2026-11-02 build carrying offline
   voice, the one this transfer was verified against
   (`VOICE_OFFLINE_SANDBOX_TEST.md` §2). A match means the APK on the new machine
   is byte-identical to the one that was tested.

### Normalisations the parts rely on

Two, both needed for the written bytes to equal the hashed bytes, and both
solved inside the driver rather than by pre-processing the files:

- The `.ps1` files are stored CRLF and a here-string keeps them verbatim, so the
  captured text arrives CRLF. Every member of the tree is LF. The writers convert.
- PowerShell 5.1 strips **exactly one** newline before the closing `'@` — not
  "the trailing newline", one single one. The writers append one newline
  unconditionally to compensate. A file that already ends with a blank line is
  the case that exposes a conditional guard: the file comes out one byte short.

A handful of files cannot survive that round-trip at all — `.vscode/settings.json`
(CRLF, no trailing newline), `docs/batch1/BASELINE_SHA256.tsv` (CRLF **and** a
BOM), `gradlew.bat` (CRLF), and `docs/TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`
(a trailing blank line, the case the paragraph above describes) — so the
generator re-derives each file's forward write and, when it would not reproduce
the bytes, embeds that file as base64 instead. That check is what takes the
transfer from "looks right" to 345/345 at zero byte mismatch.

## What is deliberately not transferred

| | why |
|---|---|
| `gradle.properties` | it pins the original build host's JDK path and carries that host's proxy truststore. Script 1 writes a per-machine replacement |
| `local.properties` | it holds that host's absolute SDK path. Script 1 writes a per-machine replacement |
| `build/`, `.gradle/`, `.idea/`, `__pycache__/` | build output and caches, reproducible from the sources above |
| `.work/`, `.audit/` | scratch scripts used while producing the reports |
| `*.log` | capture files from this host |
| `transfer/`, `transfer_full/` | the generated scripts themselves. Including them would make the payload carry its own output, and the next regeneration would differ |
| `src/test/`, `src/androidTest/` | unit tests, left out at the recipient's request to keep the set small. **Consequence: `gradlew test` on the target machine reports 0 tests.** 0 tests is not 512 passing — the 512 tests in `VOICE_OFFLINE_SANDBOX_TEST.md` §3 belong to the repository, and script 2 prints a `[skip]` line saying so rather than letting the number be misread. The APK is unaffected: nothing under `src/test` reaches a `main` source set or an APK input, which is why the transferred tree still builds to the same `17D1FB18…` sha256. A few of those tests are structural — they read the repository by path and assert the main sources still say what the reports claim — so that check exists only on the build host |
| `tools/testdata/basemap.mbtiles` | 10.7 MB map fixture. Nothing in the repository reads it: no Kotlin, Kotlin-DSL or Gradle file references `tools/testdata`, an APK built without it contains no matching entry, and the only suite that opens a pack (`AStarRouterPackTest`) reads `D:/Tools/_data/pack-test` **outside** the repository and skips itself when that directory is absent. It is also a byte-for-byte duplicate of `pack-test/basemap.mbtiles`, and reproducible from OSM with `tools/mapdata/build_map_pack.py`. At 14.3 MB of base64 it was most of the whole payload; dropping it takes the set from 22.4 MB to 8.3 MB at no cost to the build. The file stays in the repository |
| `.work/voicepack/…` (53 MB model pack) | third-party data with its own licence obligations, delivered on USB and installed through `Cài đặt → Quản lý mô hình giọng nói`. See `VOICE_MODEL_PACK.md`. The APK never contains it |

## When the build fails

Script 2 prints the causes ranked by likelihood. The three that actually happen:

| symptom | cause |
|---|---|
| `SDK location not found` | script 1 was not run, or `local.properties` was moved. Run script 1 again |
| `Unsupported class file major version`, or a Kotlin compiler crash | the daemon picked a different JDK. Check that `org.gradle.java.home` in `gradle.properties` points at JDK 17 |
| dependency download fails | network or proxy. Script 1 already probes both; if the target network differs, set `HTTP_PROXY` / `HTTPS_PROXY` |

## After the APK exists

`ADB_INSTALL_AND_RECOVERY.md`, also written into the tree, covers installing it,
finding the map pack, taking the Home role **by hand**, and three independent
ways back to the stock launcher.

```powershell
adb install -r -d TS8CarLauncher\app\build\outputs\apk\debug\app-debug.apk
```

The unit is Android 6 / API 23, which `minSdk 23` matches exactly.

## Checking the tree without building it

To re-check the transfer later without touching a file:

```powershell
powershell -ExecutionPolicy Bypass -File .\2-Create-Sources.ps1 -VerifyOnly
```

That compares every one of the 345 files against the SHA-256 table embedded in
the driver and prints how many matched; it writes nothing.

`gradlew test` on a transferred tree is **not** a useful check here — the test
sources were left out, so it reports 0 tests. That figure comes from the
repository, not from this set; run it there, where `VOICE_OFFLINE_SANDBOX_TEST.md`
§3 records 512 tests / 0 failures / 0 skipped.
