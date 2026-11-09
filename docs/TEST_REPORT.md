# Test report

What was run, on what, and what the result was. Nothing here is inferred: each
row says where the evidence came from, and anything that could not be run is
marked **not run** rather than assumed.

**Environment for everything below:** this build host, Windows 11, JDK 17
(`D:/Tools/_toolchain/jdk-17.0.20.1+1`), AGP 8.7.3, Kotlin 1.9.25, Gradle 8.11.1.
**No Android device and no emulator were available** — `adb devices` is empty
and the host reports `Virtualization Enabled In Firmware: No`. Every "not run on
device" row below is a consequence of that single fact.

## 1. Build

| step | command | result |
|------|---------|--------|
| Kotlin compile | `./gradlew :app:compileDebugKotlin --offline` | **exit 0**, no warnings that are errors |
| Debug APK | `./gradlew clean :app:assembleDebug --offline` | **exit 0** → `app/build/outputs/apk/debug/app-debug.apk`, **58,599,327 bytes**, sha256 `5400FDA4…` |
| Unit tests | `./gradlew test --offline` | **exit 0**, 316 tests (see §2) |
| Lint | `./gradlew :app:lintDebug` (network required) | see §4 |

The debug APK is `isMinifyEnabled = false` by design — a stack trace from the
field has to name the class. Release enables R8 and resource shrinking; the
release build was not exercised in this campaign because the acceptance target
is a debug APK (spec §10).

### 1.0 Two sizes, and which one is real

Three clean builds of the repaired tree produce **58,599,327 bytes** and sha256
`5400FDA4E17EDFEB424571BD8397AF06328E394F23BF7806FB24CA802B4CC164`. Run from
`clean` more than once to confirm the number is reproducible rather than an
artefact of the D8 entry ordering described below. The third run was made after
reverting a deliberate one-line edit, which is why it also proves the tree was
restored exactly.

An **incremental** build of the same tree reports **58,756,310 bytes**
(sha256 `DAAAF06F…`) — a 156,983-byte difference. That is not a different
program; it is the same one with dead gap bytes left between ZIP entries, and
`zipalign` copies a gap it finds rather than reclaiming it. **58,599,327 is the
number to compare against.**

The size of that gap is a property of *which* files changed, not a constant: a
one-line edit to `SpeedViews.kt` produced the 156,983 figure above, while at an
earlier revision a different edit produced 354,066. Quote the clean-build number
and treat any incremental figure as unverifiable without the edit that caused it.

Three earlier revisions of this table are worth keeping in the record, because
they say what moved:

| tree | bytes | sha256 | what changed |
|---|---|---|---|
| before the repair campaign | 58,203,447 | `cb047eb5…` | — |
| repaired, before the UI round | 58,597,737 | `68424D0C…` | park/map/voice/online modules and resources |
| repaired, after UI round 1 | 58,597,732 | `94B07DD4…` | speed digit 24→36 sp, unit 10→18 sp, roundel 56→64 dp, ring 6→7 dp |
| repaired, after UI round 2 | **58,599,327** | `5400FDA4…` | nav accent → cyan, ETA strip → raised navy + green duration, speed cluster → bottom-left, library tab pill, hollow play ring, compact card keeps heart/shuffle/queue |

UI round 1 moved the size **5 bytes down** on a 58 MB APK — the expected
magnitude for five edited XML attribute values, where one digit in a binary XML
resource is a byte. UI round 2 moved it **1,595 bytes up** for two new drawables
(`bg_tab_pill.xml`, `bg_circle_ring.xml`) and four restructured layouts. Neither
number is a sign that a change failed to land; §2's compile, the reproducible
clean builds, and the entry count in §1.1 are the evidence that it did.

### 1.1 APK contents

1083 entries (measured on the 58,599,327-byte clean build — two more than the
previous revision, matching the two added drawables). The size is
dominated by MapLibre's native libraries, one per ABI:

| entry | compressed |
|-------|-----------|
| `lib/x86/libmaplibre.so` | 12.7 MB |
| `lib/x86_64/libmaplibre.so` | 12.7 MB |
| `lib/arm64-v8a/libmaplibre.so` | 12.4 MB |
| `lib/armeabi-v7a/libmaplibre.so` | 9.0 MB |
| `classes.dex` … `classes11.dex` | 7.1 MB total (11 dex files) |
| `resources.arsc` | 1.2 MB |

The TS8 is 32-bit ARM, so `armeabi-v7a` is the one that matters; the other three
exist so the same APK runs on an emulator. An ABI split would cut the install
size by ~36 MB and is the obvious next optimisation — noted, not done, because
a single universal APK is what sideloads onto a head unit most simply.

## 2. Unit tests — 316 tests, 21 classes, 0 failures

Run: `./gradlew test --offline`. Results read from the JUnit XML rather than
from the console summary:

| class | module | tests | result |
|-------|--------|-------|--------|
| `AppStateMachineTest` | app | 26 | pass |
| `OnlineTrackIdTest` | app | 6 | pass |
| `DefaultLocationEngineTest` | location-engine | 21 | pass |
| `PackPathsTest` | map-engine-maplibre | 10 | pass |
| `RouteGeometryTest` | map-api | 11 | pass |
| `AudioTagReaderTest` | music-engine-local | 20 | pass |
| `CheckpointCodecTest` | music-engine-local | 9 | pass |
| `CheckpointRestoreTest` | music-engine-local | 8 | pass |
| `PlaybackCoreTest` | music-engine-local | 27 | pass |
| `PlaybackQueueTest` | music-engine-local | 18 | pass |
| `ScanDiffTest` | music-engine-local | 11 | pass |
| `TrackIdsTest` | music-engine-local | 4 | pass |
| `CircuitBreakerTest` | music-provider-online | 10 | pass |
| `OnlineMusicGatewayTest` | music-provider-online | 20 | pass |
| `NavigationControllerTest` | navigation-core | 19 | pass |
| `IntentParserTest` | navigation-core | 27 | pass |
| `VoiceScenariosTest` | navigation-core | 1 | pass |
| `ParkingResolverTest` | location-engine | 26 | pass |
| `ParkingScenariosTest` | location-engine | 2 | pass |
| `SpeedPipelineTest` | location-engine | 28 | pass |
| `AStarRouterPackTest` | routing-engine-offline | 12 | pass |
| **total** | | **316** | **0 failures, 0 errors, 0 skipped** |

Four of the twenty-one classes live in files named after a neighbouring class —
`CheckpointRestoreTest` in `PlaybackCoreTest.kt`, `CheckpointCodecTest` /
`ScanDiffTest` / `TrackIdsTest` in `PlaybackQueueTest.kt`. That is deliberate:
each pair asserts the same object from two directions (the writer and the
reader, the queue and the checkpoint), and splitting them into separate files
would scatter one contract across four places.

Two more are thin gates rather than suites: `ParkingScenariosTest` and
`VoiceScenariosTest` run the hand-written acceptance lists that already lived in
`ParkingScenarios` and `VoiceScenarios`. Those objects return failure strings
instead of asserting, so a diagnostics screen on the unit can run them without a
thrown exception taking down the screen that reports the fault — but the side
effect was that `gradlew test` never ran eighteen cases about parking and voice
routing. The gates are the bridge, and they fail with the scenario's own line.

`SpeedPipelineTest` is the file that found a real defect: `SpeedMonitor.Config.enabled`
was declared and read by nobody, so the master switch could be turned off and the
monitor would keep running the whole pipeline. It is now read at the top of
`recompute()`, and the first test in that class is the one that pins it. The other
27 assert the rules that decide whether a safety alert fires — the confirmation
window, the cooldown, hysteresis, source re-seeding, and the `--` semantics for an
unknown limit.

`AStarRouterPackTest` deserves a note: it runs against the **real** Đà Nẵng pack
at `D:/Tools/_data/pack-test`, not a mock. It reports 12/12 passing with 0
skipped, which means the pack was present and the router produced real
distances. The windows it asserts are the values `tools/mapdata/verify_graph.py`
reports for the same pairs on the same bytes, so a reader/writer format drift
appears as a number leaving its window rather than as a wrong route nobody
notices.

`PackPathsTest` is the first test `core:map-engine-maplibre` has ever had. It
pins the exact byte string MapLibre's native decoder receives for every local
asset URL, because three of its failure modes are silent: a missing `file://`
scheme, a path percent-encoded twice (`%20` → `%2520`), and an encoded
`{fontstack}` placeholder (the glyph template then 404s and the labels vanish
with no error). All three produce a blank map and no exception, so the tests
assert the encoded text rather than "a URL was returned".

What the 316 cover, in behaviour terms:

- **state machine (26)** — every legal and illegal transition, overlay
  stacking, Back order, process-death degradation, one-shot restore
- **online id space (6)** — bit 61 vs bit 62 disjointness, FNV determinism,
  separator collisions
- **map-engine-maplibre URLs (10)** — scheme, single encode, UTF-8 bytes, literal
  `/` and placeholders, Windows drive/backslash handling, idempotence
- **speed pipeline (28)** — stale/implausible/degraded rejection, dead band,
  source re-seed, limit provenance and confidence, confirmation window, cooldown,
  hysteresis, the master switch, mph as display-only
- **parking (26 + 12 gated scenarios)** — red light, lost fix, GPS spike, dwell
  restart after a gap, vehicle-signal priority, blockers, exit latch, and that
  the evidence string never carries a coordinate
- **voice parsing (27 + scenarios)** — diacritic folding, navigation before
  search, exact phrase before bare command, ambiguity instead of a guess,
  volume clamping, fillers, confirmation policy
- **location (21)** — outlier rejection, smoothing, bearing filter, snapping
- **route progress (11)** — metres→index over uneven segments (§8's split)
- **playback decisions (27+18)** — resume after a call only if it was playing,
  duck/unduck balance, repeat/shuffle, restart threshold, queue end
- **checkpoints (17)** — save/restore round-trip across a simulated reboot
- **scanning (15)** — incremental diff, stable USB ids, collision with MediaStore
- **audio tags (20)** — real container headers for the listed formats
- **navigation (19)** — the state machine, reroute policy, arrival, announcements
- **online gateway (30)** — provider order, partial results, `BLOCKED_EXTERNAL_CONFIG`,
  breaker open/half-open/closed
- **routing (12)** — real A* over the real pack

## 3. What was verified without a device

| claim | how it was verified | result |
|-------|---------------------|--------|
| FTS4 search works on the real index | SQL against `.work/pack/danang/places.db` | `cau r*` → "Cầu Rồng" (id 1693) first |
| the index has real data | SQL count | 11,268 places: 6,313 poi / 4,874 street / 81 place |
| the routing graph is well-formed | header parse + `verify_graph.py` + 12 JVM tests | 67,859 nodes, 167,859 edges, bbox correct, CRC present |
| the tile pack is complete | MBTiles query | 748 tiles, zoom 6–14, bounds match the manifest |
| the pack manifest agrees with the tiles | `write_manifest.py` reads bounds from the tiles | by construction — the manifest cannot claim more than the store has |
| the style is fully local | `StyleBuilder` source review | `mbtiles://<abs path>`, glyphs from the pack dir, no URL anywhere |
| no network on the map path | `MapLibreRuntime.configure` review | logo/attribution/compass off; no style URL is ever passed |
| normalisation parity builder↔reader | both implemented from the same 5 rules | see `OFFLINE_SEARCH_ROUTING.md` §1.2 |
| the whole load path, end to end, on the real pack | `tools/mapdata/verify_pack.py` | exit 0, `RESULT: map would load` — see §6.2 |

### 3.1 `verify_pack.py` — the import/load evidence, and its negative cases

`tools/mapdata/verify_pack.py` is a **second implementation** of
`MapPack.load` + `PackValidator` in Python. It walks the same eight stages in
the same order and prints the same `key=value` lines `PackDiagnostics.report()`
writes, so the record below can be reproduced on a machine with no head unit.
Writing it twice is the point: if the Kotlin reader and this one disagree about
a pack, one of them is wrong.

Positive case, on `.work/pack/danang`:

```
stage_trail=VALIDATING_METADATA → … → VALIDATING_FILES
failure=none
pack_id=danang-2026.10
format_version=1
metadata_bytes=691
  file=basemap.mbtiles size=10698752
checksum=ok=19 mismatch=0 missing=0
mbtiles=tables=metadata,tiles format=pbf minzoom=6 maxzoom=14
tile_query=z=11 x=1639 y_tms=1116 bytes=341257 magic=1f8b
places_db=places=11268 fts4=True
style=glyphs=ok
routing=bin=true idx=true version=1
RESULT: map would load
```

Four negative cases were run to prove the verifier fails for the right reason
rather than passing everything:

| mutation | reported |
|---|---|
| `metadata.json` removed | `INVALID_METADATA` at `VALIDATING_METADATA` |
| pack directory absent | `PACK_NOT_FOUND` at `DISCOVERING` |
| one byte flipped in `basemap.mbtiles` | `CHECKSUM_MISMATCH`, `mismatch=1` |
| (unmutated control) | `failure=none`, exit 0 |

The `magic=1f8b` in `tile_query` is the gzip header: the tile at the pack's own
centre is a real compressed vector tile, not an empty blob. `y_tms=1116` is the
**TMS** row, because the native reader flips XYZ → TMS itself and a probe with
the XYZ row would pass on a store whose rows happen to be flipped.

`--json` emits the same data for a script to assert on; `--verbose` adds the
per-stage detail trail.

`D:/Tools/_data/pack-test` is **not** a pack root: it holds `basemap.mbtiles`,
`places.db` and `routing-graph/` but no `metadata.json` or `checksums.sha256`,
because it exists as the fixture `AStarRouterPackTest` and `verify_graph.py`
read, not as something the launcher can open. Running the verifier against it
correctly reports `INVALID_METADATA`.

## 4. Lint

`:app:lintDebug` — **errors: 0, fatal: 0.** Warnings remain, and they are listed
rather than suppressed wholesale:

| check | count | disposition |
|-------|-------|-------------|
| `UnusedResources` | ~40 | mostly strings drawn by code; each is a real signal that a string drifted, so not disabled |
| `LogNotTimber` | 16 | no Timber in this build; the check assumes it |
| `GradleDependency` | 12 | newer versions exist; the pinned ones are the ones validated on this toolchain |
| `DiscouragedApi` | 9 | `MediaStore.Audio.Media.DATA` and friends — required for a real file path at API 23 |
| `UseCompoundDrawables` | 9 | a deliberate `ImageView` + `TextView` pair in rows that align independently |
| `HardcodedText` | 9 | `tools:text` previews and the parking clock format |
| `Overdraw` | 8 | theme backgrounds stacked behind opaque fragments |
| `SetTextI18n` | 6 | number formatting that is already locale-correct by construction |

**`ExpiredTargetSdkVersion` is disabled**, deliberately and with the rationale in
`app/build.gradle.kts` and `ANDROID_6_COMPATIBILITY.md` §2. It is a fatal-severity
check aimed at Play Store submissions; this APK is never submitted and
targetSdk 30 is the device's behaviour model.

Lint needs **network**. `:app:lintDebug --offline` fails with "No cached version
of `com.android.tools.lint:lint-gradle:31.7.3`", because the lint tool itself is
resolved per run. `compileDebugKotlin`, `assembleDebug` and `test` all work
offline.

## 5. Not run — the honest list

| acceptance item (spec §10) | status | why |
|---------------------------|--------|-----|
| APK installs on API 23 | **not run on a device** | no device attached |
| no left menu on any screen | **verified in source** | no drawer/NavigationView/left panel in any layout; not visually confirmed |
| 7 screens match the design and scale to 1024×600 | **verified in source**, not visually | every width is `0dp`+constraint or weighted; no screenshot was taken |
| Đà Nẵng map runs truly offline | **partly verified** — pack, style and offline contract verified; rendering not observed | no device |
| search / route / GPS work with the network off | search and routing **verified on real data**; GPS and rendering not | no device |
| music plays in background, MediaSession, no crash on USB removal | **logic verified by tests**; runtime not observed | no device |
| TTS duck/restore, no regression to Phone/Radio/Camera/CANBUS | **duck logic verified by tests**; Radio cannot be paused at all (documented) | no device |
| app drawer opens real apps, graceful on failure | **verified in source**; not run | no device |
| stock launcher never removed, ADB recovery documented | **verified in manifest and docs** | — |
| 2-hour soak (navigation + music) | **not run** | no device, no emulator |

Every row above is also in `KNOWN_LIMITATIONS.md`, with what the driver sees as
a result.

## 6. How to reproduce all of this

```sh
./gradlew clean :app:assembleDebug --offline   # APK, 58,599,327 bytes
./gradlew test --offline                       # 316 tests
./gradlew :app:lintDebug                       # needs network
```

`AStarRouterPackTest` skips, with a printed reason, when
`D:/Tools/_data/pack-test` is absent. It never silently passes.

### 6.1 Reproducing it on another machine

`transfer/1-Setup-Environment.ps1` plus the second script's five files
(`2-Create-Sources.ps1` and its four payload parts `2a`–`2d`) do the above on a
machine with nothing else installed. The driver's `5/5 result` step compares the
APK's sha256 against `5400FDA4…`, the same hash recorded in §1.0, so the transfer
can state "identical" rather than "roughly the same size". See `TRANSFER.md`.

> **Superseded for the current tree.** This section describes the five-file set
> in `transfer/`, which deliberately omits the test sources — a tree built from
> it reports 0 tests. `transfer_full/` is the current set: seven files, 315
> files embedded, tests and the 10.7 MB map fixture deliberately excluded, the
> design PNG and every document included. `TRANSFER.md` documents it and
> `KNOWN_LIMITATIONS.md` §F records the end-to-end evidence. The tests in this
> report can only be run from the repository, not from a transferred tree.

**Verified against the split layout, before this campaign's changes.** A clean
end-to-end run — empty directory, script 1, then the five-file script 2 into an
empty project directory — produced a byte-identical APK, and the driver printed
`[ok] identical to the build this transfer was verified against`. A byte
comparison of the 212 files it wrote against the repository gave
`differ=0 missing=0`. Splitting the payload into four parts therefore changed no
bytes that reach disk; only the packaging of the here-strings changed.

**Regenerated, and verified end to end, for this round.** The payload now
carries 276 files (275 text, 1 binary) in five files totalling 2,240,880 bytes.
Two checks were run against it, both from an empty directory:

| check | result |
|---|---|
| every file it writes vs the repository, by sha256 | **276 written, 276 identical, 0 differ, 0 not-in-repo** |
| clean build of the written tree vs this report's APK | **58,599,327 bytes, sha256 `5400FDA4…` — identical** |

The first attempt at this verification reported **275 identical, 1 differ**.
The differing file was `SpeedViews.kt`, and the cause was not the payload: this
campaign's own edit to that file had been restored with PowerShell 5.1's
`Set-Content -Encoding utf8`, which writes a BOM. The BOM was stripped before
the numbers above were taken, and the repository was rebuilt and re-tested
afterwards to confirm it. Worth recording because the symptom — one file
different, in a tree that otherwise matches byte for byte — reads exactly like
a payload bug, and the fix was in the source, not in `transfer/`.

That second row is the one that matters. It means the transfer reproduces not
merely the same file list but the same compiled artifact, so the hash in §1.0
and the hash the driver's `5/5 result` step checks are the same number for a
real reason rather than because both were copied from the same place.

Reproduce it:

```powershell
# into an empty directory, no build
.\transfer\2-Create-Sources.ps1 -ProjectDir D:\_verify -SkipBuild

# then the full check: script 1 for the per-machine files, script 2 with the build
```

`gradle.properties` is deliberately absent from the payload — script 1 writes it
per machine, and the build host's copy hard-codes this host's JDK path and
corporate proxy truststore. A verification run has to supply one; doing so is
not a workaround, it is the step script 1 exists for.

The transferred tree deliberately omits `src/test`, so `gradlew test` there
reports 0 tests. That is a property of the payload, not a regression.

### 6.2 Reproducing the import/load evidence

```sh
# positive: the real pack, exit 0, "RESULT: map would load"
python -I -X utf8 tools/mapdata/verify_pack.py --pack .work/pack/danang --verbose

# machine-readable form, for a script or a diff against a logcat line
python -I -X utf8 tools/mapdata/verify_pack.py --pack .work/pack/danang --json
```

The verifier's key vocabulary is `PackDiagnostics.report()`'s, so a line from
it can be compared to a logcat line character for character. If they differ,
one implementation has drifted and the difference is the evidence.

The off-device cross-check for the routing graph:

```sh
python -I tools/mapdata/verify_graph.py \
    --pack <pack dir> --from 16.0616,108.2270 --to 16.0472,108.2015
```
