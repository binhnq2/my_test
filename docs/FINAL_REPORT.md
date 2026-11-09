# Repair campaign — final report

Written to spec §23's block format. Every claim below points at a document or
a command that can be re-run; nothing here is inferred from the code looking
correct.

---

**Status:** **PARTIAL** — all in-scope defects that could be repaired and
verified on this host are repaired and verified; the acceptance gate is not
clean, and the reason is one hardware fact, not a code defect.

The distinguishing evidence is the pair of numbers in §Build/tests: 316 unit
tests green and a reproducible APK, against zero device runs. The APK has never
been installed. That is the whole of the reason for PARTIAL.

---

**Root causes**

1. **`SpeedMonitor.Config.enabled` was declared and read by nobody.** The
   master speed switch existed in the config object and in `AppSettings`, and no
   line of `speed/` ever read it — so turning it off left the entire pipeline
   running. Found by writing `SpeedPipelineTest`, not by reading the code; the
   field looked used because `AppSettings.speedConfig()` passes it.
2. **Eighteen acceptance scenarios were runnable only from a diagnostics
   screen.** `ParkingScenarios` (12) and `VoiceScenarios` (6) return failure
   strings instead of asserting, so a screen on the unit could run them without
   a thrown exception taking down the screen reporting the fault. The cost was
   invisible: `gradlew test` never ran them, so parking auto-entry and voice
   confirmation policy were covered by hand only.
3. **Three documents disagreed with the tree.** `TEST_REPORT.md` under-counted
   the suite, `MAP_PACK_ROOT_CAUSE.md` carried a fabricated diagnostics sample
   whose key names (`pipeline=`, `packId=`, `sqlite=`) do not exist in
   `PackDiagnostics.report()`, and `KNOWN_LIMITATIONS.md` still described
   `transfer/` as pre-repair.
4. **The speed chip was typographically wrong against the design.** The design
   draws a 22 px digit over a 10 px unit (ratio 2.20); the app drew 24 sp over
   10 sp whose cap height is ~17 px — both lines too small in a 146 dp card.
5. **The speed-limit roundel was 9 dp undersized.** Design 47 px = 64.9 dp,
   drawn at 56 dp, so the ring rendered 1 dp thinner in absolute terms at a size
   where 1 dp is visible.

---

**Implemented**

| area | what changed |
|---|---|
| speed pipeline | `SpeedMonitor.recompute()` now honours `Config.enabled`: off publishes an honest empty state (`--`, no alert) and resets the detector so re-enabling cannot inherit a half-counted window |
| speed chip | `speedValue` 24 sp → `text_display` 36 sp, `speedUnit` 10 sp → `text_title` 18 sp, on Home **and** Map |
| speed-limit roundel | 56 dp → 64 dp, ring 6 dp → 7 dp, Home `marginStart` 88 → 96 dp so it still clears the chip; still `gone` until the pack carries a real limit |
| test coverage | `SpeedPipelineTest` (28), `ParkingResolverTest` (26), `ParkingScenariosTest` (2), `IntentParserTest` (27), `VoiceScenariosTest` (1) — 84 new tests, 21 classes, 316 total |
| transfer | `transfer/` regenerated: 276 files, 2,240,880 bytes; hash constants updated to the new clean build. Later superseded by `transfer_full/` (315 files; PNG and docs included, unit tests and the map fixture excluded) — see `TRANSFER.md` |
| docs | `UI_GAP_REPORT.md` (new), and `TEST_REPORT.md` / `KNOWN_LIMITATIONS.md` / `MAP_PACK_ROOT_CAUSE.md` / `TRANSFER.md` corrected to the tree |

Nothing was redesigned, no screen restructured, no element added, and no left
menu exists anywhere.

---

**Evidence**

| claim | evidence | re-run |
|---|---|---|
| 316 tests, 0 failures | JUnit XML, deduped by class | `./gradlew test --offline` |
| APK is reproducible | three clean builds, same 58,599,327 bytes / `5400FDA4…` | `./gradlew clean :app:assembleDebug --offline` |
| pack loads | `verify_pack.py` → `RESULT: map would load`; 4 mutations fail for the right reason | `python -I -X utf8 tools/mapdata/verify_pack.py --pack .work/pack/danang --verbose` |
| real routing | 12 tests over the real Đà Nẵng graph | `AStarRouterPackTest` |
| transfer reproduces the APK | 276/276 sha256-identical from an empty dir; clean build identical | §TEST_REPORT 6.1 |
| roundel is 64.9 dp | flood-fill of the ring component, reproducible script in `UI_GAP_REPORT.md` §5 | that script |
| no left menu | no `DrawerLayout`/`NavigationView`/start-edge panel in any layout | grep |

The `MAP_PACK_ROOT_CAUSE.md` sample was **replaced, not reworded**: the old one
was hand-copied and contained key names that do not occur in the code. A
document whose evidence cannot be produced by running the code is not evidence,
and correcting the prose around it would have hidden that.

---

**Files changed**

Source (5):
`core/location-engine/src/main/java/com/ts8/core/speed/SpeedMonitor.kt`,
`app/src/main/res/layout/activity_home.xml`,
`app/src/main/res/layout/activity_map.xml`,
`app/src/main/res/drawable/bg_speed_limit.xml`,
`tools/transfer/gen_transfer.py`

Source (round 2, UI appearance — 10):
`app/src/main/res/values/colors.xml`,
`app/src/main/res/drawable/bg_eta_strip.xml`,
`app/src/main/res/drawable/bg_tab_pill.xml` (new),
`app/src/main/res/drawable/bg_circle_ring.xml` (new),
`app/src/main/res/color/music_tab_tint.xml`,
`app/src/main/res/layout/activity_map.xml`,
`app/src/main/res/layout/activity_music_fullscreen.xml`,
`app/src/main/res/layout/view_music_card.xml`,
`app/src/main/java/com/ts8/launcher/ui/MapActivity.kt`,
`app/src/main/java/com/ts8/launcher/ui/MusicCardView.kt`

Tests (5 new):
`speed/SpeedPipelineTest.kt`, `parking/ParkingResolverTest.kt`,
`parking/ParkingScenariosTest.kt`, `voice/IntentParserTest.kt`,
`voice/VoiceScenariosTest.kt`

Docs (6):
`UI_GAP_REPORT.md` (new), `TEST_REPORT.md`, `KNOWN_LIMITATIONS.md`,
`MAP_PACK_ROOT_CAUSE.md`, `TRANSFER.md`, `UI_IMPLEMENTATION.md`

Regenerated: `transfer/` (5 scripts, 2,240,880 bytes)

---

**Build/tests**

```
./gradlew clean :app:assembleDebug --offline   →  exit 0
    app/build/outputs/apk/debug/app-debug.apk   58,599,327 bytes
    sha256 5400FDA4E17EDFEB424571BD8397AF06328E394F23BF7806FB24CA802B4CC164
    1083 entries

./gradlew test --offline                       →  exit 0
    316 tests, 21 classes, 0 failures, 0 errors, 0 skipped
```

Both numbers reproduced from `clean`, three times. Lint is **not** clean of
warnings (~107 across 8 checks) and every one is listed with its disposition in
`TEST_REPORT.md` §4; errors and fatals are zero.

**Not run:** install, launch, render, drive, soak. No device and no emulator —
`Virtualization Enabled In Firmware: No`.

---

**UI comparison**

`docs/UI_GAP_REPORT.md` covers all ten axes §14 names. Method: pixel
measurement of `docs/UI_design.png` against layout XML and `dimens.xml`, using
a single scale factor of 1024/742 = 1.380, validated twice against the 44 dp
status bar.

Three real size gaps found, and **all three came from ratios rather than
absolute dp**, which matters because the collage turned out not to preserve
1024×600:

| axis | design | was | now |
|---|---|---|---|
| typography, digit : unit | 2.20 : 1 at 22 px cap | 24 : 10 sp at ~17 px cap | **36 : 18 sp** |
| icon size, roundel | 64.9 dp, ring 10.6 % | 56 dp, ring 10.7 % | **64 dp, ring 10.9 %** |
| map/music ratio | 73.4 / 26.6 | 70.7 / 29.3 | unchanged, deliberate |

A second pass found six **appearance** gaps — places where the geometry was
right and the colours or shapes were wrong. These are not size numbers, and
they were found by sampling patches of the sheet rather than by measuring
edges:

| axis | design | was | now |
|---|---|---|---|
| active nav item | cyan `rgb(32,226,252)` on three panels | `@color/accent` blue | **`icon_active` `#FF20E2FC`** |
| ETA strip surface | navy `rgb(1,22,36)` | solid `@color/success` | **`surface_card_raised`** |
| ETA strip text | only the duration green | whole line `text_on_accent` | **`text_primary` + green duration span** |
| speed chip position | bottom-left, beside the roundel | top-right, roundel on a separate offset | **one bottom-left row** |
| library tab, selected | outlined pill + underscore | label colour only | **outlined pill** (underscore not drawn) |
| play button | hollow ring, white glyph | solid accent disc | **2 dp ring, `text_primary` glyph** |
| compact music card | heart / shuffle / queue | entire secondary row hidden | **repeat hidden only** |

The play-button row is the one with a caveat: the sheet's ring is ~1 px at the
panel scale, i.e. 1.4 dp, and is drawn at 2 dp — under one pixel different at
the target density, but not an exact reproduction.

**The map/music ratio was not fixed on purpose.** Narrowing the card to 273 dp
makes the design's own strings (`Chạy Về Nơi Phía Anh`) wrap where the design
does not wrap them. An overflowing title is a worse defect than a 27 dp
proportion nobody can see without a ruler.

**`bottom_nav_height` is filed as measured-and-open, not fixed.** It measures
61 dp against a declared 72 dp, but the collage compresses vertically, so that
measurement cannot support a change. Reporting it as a gap to fix would be
false precision.

Two mid-work corrections are on the record. An earlier measurement reported the
speed digit as a single 47 px band and implied a 65 sp digit; row-by-row
scanning showed two bands, 22 px and 10 px. And `UI_GAP_REPORT.md` originally
asserted that no colour could be read better than ±10 per channel — patch
averaging reads the nav accent to within 9/255 across three panels, so that
claim was about the method, not the sheet, and it was withdrawn rather than
quietly softened. Both corrections are left visible because a report that drops
its own errors teaches the reader nothing about how much to trust the rest.

---

**Map-pack result:** **PASS offline.** The pack at `.work/pack/danang` loads
through all eight validation stages: `pack_id=danang-2026.10`,
`format_version=1`, 19 checksums OK / 0 mismatch / 0 missing, 748 tiles zoom
6–14, 11,268 places with FTS4, glyphs OK, routing graph version 1. A real tile
at the pack's own centre decompresses to 341,257 bytes of gzip vector data.

Four mutations were run to prove the verifier fails for the right reason:
missing `metadata.json` → `INVALID_METADATA`; absent directory →
`PACK_NOT_FOUND`; one flipped byte → `CHECKSUM_MISMATCH`. It is a second
implementation of the Kotlin loader, written so a disagreement between them is
visible.

**Rendering is unverified** — no device. What is proven is that every input the
renderer reads is present and valid.

---

**Map display / speed settings result:** **PARTIAL.**
Map display settings persist and reach `StyleBuilder`: the label layers
(`disp_label_house_v1`), the theme and the unit are read from
`AppSettings.mapDisplayConfig()` and emitted into the style JSON. The speed
unit and the speed-limit visibility setting both write and read back.

Two honest limits: `disp_label_house_v1` can render **nothing** because most
packs carry no `housenumber` source layer, so turning "Số nhà" on may produce
no visible change (`KNOWN_LIMITATIONS.md` D7); and the speed roundel stays
hidden because the pack tags `maxspeed` on only ~6 % of highway ways (C1).
Neither is a defect — the data is absent and the UI does not fabricate it.

---

**Voice result:** **PARTIAL — wired, platform unverifiable.**
`VoiceTrigger`, `VoiceInstantController`, `SpeechRecognizerAdapter`,
`VoiceRouter`, `IntentParser`, `VoiceFeedback` and the overlay all exist and are
covered by 27 parser tests plus the gated scenario list. Diacritic folding,
navigation-before-search ordering, exact-phrase-before-bare-command, ambiguity
instead of a guess, volume clamping and confirmation-only-for-destructive are
all asserted.

The unverifiable half is the ROM: whether the TS8 ships a Vietnamese
`SpeechRecognizer`, and whether its `TextToSpeech` has a Vietnamese voice.
**No wake word** — background listening is forbidden by the campaign's own
constraint, so the microphone opens only on a tap.

---

**Online music result:** **PARTIAL.**
Three providers sit behind `OnlineMusicGateway`. Internet Radio (Radio Browser)
needs no configuration and is the one that ships production-capable. Podcast
RSS and Navidrome/Jellyfin return `BLOCKED_EXTERNAL_CONFIG` with a specific
Vietnamese message naming what is missing — they are implemented, written and
tested against doubles, and **no credential exists in this repository**, so no
provider was demonstrated against a real server.

No YouTube extractor, `yt-dlp`, scraper, reverse endpoint or audio split
anywhere — the constraint is respected in code and not merely in intent.

---

**Parking mode result:** **PARTIAL.**
Auto-entry works on the fallback path: 20 s stationary and stable to become a
candidate, 120 s to reach HIGH confidence, red lights never a candidate, a
single GPS spike never dismisses, a lost fix never exits, and manual entry is
always available. 26 resolver tests plus the 12 gated acceptance scenarios.

**No vehicle-signal source.** `ParkingSignals` declares gear/ignition/brake and
`ParkingStateResolver` branches for each, but `ParkingRuntime` never sets them —
that needs a bridge contract this campaign does not have, and spec §18 forbids
reversing the MCU API without one. So `REVERSE_ACTIVE` never blocks, and a
noisy receiver degrades to `STOPPED_TEMPORARILY` rather than a false PARKED.

---

**Known limitations**

Everything is in `KNOWN_LIMITATIONS.md` with what the driver sees as a result.
The ones that block acceptance:

- **A1 — no device, no emulator.** The single reason the gate is not clean.
- **A2 — the 2-hour soak cannot be performed here.**
- **C1 — speed limits on ~6 % of highway ways.** The roundel is present and
  hidden; when a fuller pack is built it appears with no code change.
- **C3 — no turn restrictions** (`graph.bin` flags = 0). The most likely source
  of a route a local driver would call wrong.
- **C5 — private-access destinations (Ba Na Hills) are unreachable** by design.
- **D8 — no vehicle-signal source for parking.**
- **D9 — no screenshots.** The UI comparison is measurement, not observation.
- **F — a transferred tree has no tests.** 0 tests is not 316 passing, and
  script 2 says so rather than letting the number be misread.

---

**External blockers**

| blocker | kind | what would unblock it |
|---|---|---|
| no head unit attached | hardware | a TS8 with USB debugging, or an emulator on a host with VT-x enabled |
| no Vietnamese `SpeechRecognizer` on the ROM | platform | the unit's own ROM; the code degrades honestly but cannot report which case it is in |
| no podcast / Navidrome credential | config | a feed URL or server address entered in Settings — `BLOCKED_EXTERNAL_CONFIG` until then |
| no MCU/CANBUS contract | scope | out of scope by the campaign's own constraint |
| turn restrictions absent from `graph.bin` | data | a builder pass that emits bit 0; a separate piece of work |

---

**Next recommended step**

**Install the APK on the unit and run the acceptance list.** Everything that can
be settled off-device is settled and reproducible; what remains is one `adb
install` and roughly an hour of driving, and no further code reading can
substitute for it.

The order that gets the most information per minute:

1. `adb install -r app-debug.apk` on the TS8 — the first thing ever to run.
2. Open the map with Wi-Fi and data **off**, and confirm tiles render from the
   local pack. This is the claim the entire map campaign rests on and the only
   one with zero on-device evidence.
3. Take the seven §22 screenshots and compare them against
   `docs/UI_GAP_REPORT.md` — specifically the speed chip, where the 36/18 sp
   change is a prediction about a rendered frame that nobody has seen.
4. Say "mở nhạc" and "tìm đường Đà Nẵng" to check whether the ROM's recogniser
   is Vietnamese-capable, and record which of D3's four cases the unit is in.
5. Start navigation plus music and leave it for two hours.

If step 2 fails, the next work is the map load path, not the UI. If step 2
passes and step 3 shows the chip wrong, the typography numbers in
`UI_GAP_REPORT.md` §4 are what to re-measure first — they are predictions from
pixels, and a rendered frame outranks them.
