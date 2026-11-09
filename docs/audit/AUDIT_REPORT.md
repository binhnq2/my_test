# Source audit — final report

Read-only audit of the Car_Launcher production source. No production source was
modified, no file was renamed or deleted, no functionality was rebuilt, and no
secret value appears in any output.

---

## 1. Status

**PASS**

All six required outputs exist, the bundle verifies byte-for-byte against the
tree, and every citation in the two analysis reports was re-checked against the
line on disk.

| requirement | result |
|---|---|
| `SOURCE_TREE.txt` | produced, 259 files indexed |
| `SOURCE_AUDIT_BUNDLE_01..04.md` | produced, 259 sections, 4 bundles |
| `RUNTIME_WIRING.md` | produced, 15 flows, each concluded |
| `IMPLEMENTATION_FINDINGS.md` | produced, 15 findings, each with ID/severity/symptom/root/evidence/impact/repair boundary |
| `FILE_INDEX.md` | produced, every bundled file + absent-but-requested + present-but-unwired |
| `REDACTION_REPORT.md` | produced, "No secrets detected", method and exclusions recorded |
| production source modified | **no** |
| secrets in output | **none** |

---

## 2. Output files

| file | bytes |
|---|---|
| `SOURCE_TREE.txt` | 11,171 |
| `SOURCE_AUDIT_BUNDLE_01.md` | 470,423 |
| `SOURCE_AUDIT_BUNDLE_02.md` | 472,394 |
| `SOURCE_AUDIT_BUNDLE_03.md` | 470,167 |
| `SOURCE_AUDIT_BUNDLE_04.md` | 370,751 |
| `RUNTIME_WIRING.md` | 46,170 |
| `IMPLEMENTATION_FINDINGS.md` | 57,267 |
| `FILE_INDEX.md` | 41,963 |
| `REDACTION_REPORT.md` | 6,722 |
| `AUDIT_REPORT.md` (this file) | 15,627 |

Total delivered: **1,962,655 bytes** (≈1.87 MiB). The tree and the four source
bundles are 1,794,906 bytes of that; the five analysis reports are 167,749 bytes.

Two build scripts are also present and re-runnable but are not deliverables:
`build_audit_bundle.py` (generates the tree and bundles) and
`verify_audit_bundle.py` (independent check, described in §5).

---

## 3. Files included / excluded

**Included: 259 files.** Every first-party source file that a reviewer needs to
reconstruct the build and follow the code: 13 Gradle modules' build scripts and
`settings.gradle.kts`, the root `gradle.properties` and `build.gradle.kts`, the
Gradle wrapper properties, the Android manifest, all 115 main Kotlin sources, all
17 test Kotlin sources, and all 96 resource XML files.

By module, the bundle splits as 35 / 125 / 55 / 44 files.

**Excluded, and why:**

| excluded | reason |
|---|---|
| `.git`, `.gradle`, `build/` | VCS metadata and build output |
| `node_modules` | not present; no JS toolchain |
| APK / AAB | binary build artifacts |
| binary assets (`.png`, `.jpg`, `.webp`, `.ttf`, `.so`) | not source; `docs/UI_design.png` is referenced by the reports but not bundled |
| map packs (`.mbtiles`), routing graphs, MBTiles caches | runtime data, not source |
| `local.properties` | host-specific: `sdk.dir=D:/Tools/_toolchain/android-sdk` |
| large logs | not source |
| credential or keystore files | none exist in the tree |
| `app/src/main.zip` | 283,965-byte archive of `app/src/main`; every file inside is already bundled individually, so bundling the archive would only duplicate them. Verified byte-identical to the current tree (141/141 entries), so it hides no unaccounted-for edit. |

The project has a credential *path* — an optional self-hosted music server — but
carries no credential in source: the token is read from SharedPreferences at
runtime and never rendered back to a screen. See `REDACTION_REPORT.md` §2.

---

## 4. Secrets redacted

**None — nothing was redacted, because no secret exists in the tree.**

The scanner ran five value-shape patterns (assigned credential literals, PEM
private-key headers, Google/GitHub/OpenAI key shapes) over every bundled line and
found **0 hits**. It then classified all 63 occurrences of
`api_key|apikey|secret|token|password|passwd|credential`, and every one is a
variable name, a preference key, a UI label, or a comment explaining why a
credential is *not* stored.

Two host-specific items are present in the included `gradle.properties` and are
recorded rather than silently passed over: the JDK's published default
truststore password `changeit` (the documented `keytool` argument in the same
file, allow-listed by literal so a future real password cannot hide behind that
pattern), and the internal proxy hostname `fdnproxy-fc-lan.fsoft.fpt.vn`. Neither
is a credential. **The reviewer should treat the proxy hostname as an
organisational detail and not redistribute the bundle outside the review.**

---

## 5. Validation

Every check below was run against the delivered files, not against the generator.

### 5.1 Bundle fidelity

`python -I docs/audit/verify_audit_bundle.py`:

```
indexed paths     : 259
bundled sections  : 259
bundles           : 4
byte-identical    : 259/259
placeholder hits  : 0
secret shapes     : 0

PASS  every indexed file is bundled verbatim, no placeholders, no credentials
```

The verifier re-reads each `## path` / `BEGIN FILE` / `END FILE` section out of
the bundles and compares it byte-for-byte with the file on disk. It is
independent of the build step, so a bundle produced by an older revision of the
generator would still be caught. `placeholder hits : 0` confirms no section was
replaced by a summary, an ellipsis or a description.

### 5.2 Citation accuracy

Every `| file | line | snippet |` row in `IMPLEMENTATION_FINDINGS.md` and
`RUNTIME_WIRING.md` was re-read from disk and the quoted snippet compared with
the actual text at that line:

```
CITATIONS: 78/78 valid, 0 bad
```

Rows whose third column names a symbol rather than quoting source (for example
`navigateTo`, `bindNavigation`, `onMapReady`) are excluded from that count
because they are not citations of text.

### 5.3 No truncation

```
… in IMPLEMENTATION_FINDINGS.md : 0
… in RUNTIME_WIRING.md           : 0
```

Source is never abbreviated. Where a multi-line block is cited, every line is
quoted, joined by ` / `.

### 5.4 Test totals

Test results were read from `build/test-results/testDebugUnitTest/TEST-*.xml`,
de-duplicated across the debug/release variants, and **restricted to suites
whose `src/test` source exists in the tree**:

```
LIVE suites: 17   tests: 284   failures: 0   errors: 0   skipped: 0
STALE XML (no test source): 4  — 32 tests excluded
```

The four excluded suites are `CheckpointCodecTest` (9), `CheckpointRestoreTest`
(8), `ScanDiffTest` (11) and `TrackIdsTest` (4); all four carry the identical
timestamp `2026-10-30T12:49:48`, no `.kt` source exists for any of them, and they
are recorded as gap 5 of finding F-15 rather than counted.

### 5.5 Nothing outside this directory was modified

```
find . -newermt "-3 hours" -type f -not -path "./docs/audit/*" -not -path "*/build/*"
```

returns exactly one hit: `app/src/main.zip`, dated 08:07 — **before** this audit
began at 08:21. It is an archive of `app/src/main` and was compared against the
live tree: **141 entries, 141 byte-identical, 0 differing, 0 missing.** No file
under `app/` or `core/` was touched by this work.

### 5.6 What this validation does not cover

The bundle proves that *what is reported about the source* is accurate. It does
not prove runtime behaviour on the unit. Five claims are explicitly marked
unverifiable from source in `IMPLEMENTATION_FINDINGS.md`: keyboard appearance on
Music, whether the firmware honours `setStreamVolume`, whether the map draws a
first frame with a given pack, whether a speech service exists on the unit, and
whether auto-parking would work with real vehicle signals.

---

## 6. Missing requested files

Every path the brief named was located or its absence established by search.
Full detail is in `FILE_INDEX.md`; the substantive ones:

| requested | actual situation |
|---|---|
| `gradle/libs.versions.toml` | does not exist — no version catalog; versions are literals in each build script |
| a navigation graph | does not exist — zero `nav_graph` / `NavHost` / `androidx.navigation` references |
| Fragments, ViewModels | do not exist — zero hits; the app is Activities + Views |
| `view_weather.xml` | does not exist — the weather chip is `weatherChip` inside `view_status_bar.xml:55` |
| `ScreenModeCoordinator` | does not exist |
| `AppStateStore` | does not exist — nearest are `AppStateMachine` and `PrefsKeyValueStore` |
| `LocalMusicRepository` | does not exist — nearest is `LocalMusicBackend` |
| `core/diagnostics` | module exists and is **empty** — zero Kotlin files |

The naming differences matter: a reviewer searching for these exact names would
conclude the features are absent, when in several cases the capability exists
under another name.

---

## 7. Production wiring findings

### 7.1 The 15 flows

| conclusion | count | flows |
|---|---|---|
| **PRODUCTION_WIRED** | 6 | 1 launch→Home, 2 tab→Map, 6 tab→Music, 9 music panel visibility, 10 volume→AudioManager, 11 manual parking |
| **PARTIALLY_WIRED** | 6 | 3 pack→first frame, 4 white map→error, 8 online music entry, 13 voice, 14 back/nav, 15 process recreation |
| **NOT_WIRED** | 2 | 5 Map Management entry, 7 Music search focus/keyboard |
| **BLOCKED** | 1 | 12 auto parking |
| MOCK_ONLY | 0 | — |

`BLOCKED` for FLOW 12 is the brief's own vocabulary for a dependency that needs
external configuration: the plumbing is wired end to end and no vehicle signal
source exists to feed it.

### 7.2 Severity distribution

| severity | count | findings |
|---|---|---|
| **P0** | 1 | F-11 |
| **P1** | 1 | F-10 |
| **P2** | 3 | F-05, F-12, F-13 |
| **P3** | 7 | F-01, F-02, F-04, F-06, F-08, F-09, F-15 |
| NONE | 3 | F-03, F-07, F-14 (premise refuted) |

### 7.3 The single most serious defect

**F-11 (P0).** `MapManagementActivity` is fully implemented and is **not
declared in `AndroidManifest.xml`**. The manifest declares exactly nine
activities; this is not among them, in the source manifest, the merged manifest
or the packaged APK manifest. The one production call site,
`HomeActivity.kt:216`, is unguarded, so tapping the button throws
`ActivityNotFoundException` and takes the Home activity — the launcher itself —
down with it.

That button is inside a container that is `android:visibility="gone"` and only
becomes visible when pack loading has already failed. So the entry point appears
exactly in the broken state and crashes when used; a driver whose map works never
sees it at all. The whole pack-management feature — the only way to import,
validate, activate or remove a map pack — is unreachable.

The blocking repair is one manifest entry. The guard on the call is a separate,
independent fix.

### 7.4 What the brief's premises got wrong

Four premises were checked and refuted; the report records the refutation rather
than confirming a defect that is not there:

- **F-03** — Home does not duplicate a system status bar; the app sets
  `SYSTEM_UI_FLAG_FULLSCREEN` (`BaseScreenActivity.kt:94-97`), so there is no
  system bar to duplicate.
- **F-07** — volume is not demo state; `AndroidVehicleAudioBridge.setMediaVolume`
  performs a real `AudioManager.setStreamVolume(STREAM_MUSIC, …)`.
- **F-14** — confirmation is not missing from most commands; it is by design
  restricted to the two destructive ones, and `VoiceCommand.kt:26-27` documents
  the policy.
- **F-13's framing** — voice does not merely "depend on" Android speech; the
  adapter's own KDoc names the firmware variants where no recogniser exists, and
  there is no fallback of any kind.

### 7.5 Patterns that recur

Three shapes account for most of the findings:

1. **Built but unreachable.** `MapManagementActivity` (F-11),
   `OnlineMusicRuntime.browse` (F-09), `MapLibreMapController.onSaveInstanceState`
   (F-15), `VoiceInstantController.restoredSnapshot` (F-15),
   `PackFailureCode.STYLE_LOAD_FAILED` (F-10). Several have tests and KDoc that
   describe them as working.
2. **Failure that is silent.** No style-load listener exists, so a rejected
   style never reaches the screen (F-10); pack validation runs into a report only
   the unreachable screen reads (F-10); auto-parking fails with no log, toast or
   Settings hint (F-12).
3. **A setting with no surface.** `parkingAutoEnter` defaults to `false` and has
   zero setter call sites, while its label and hint strings are declared and
   unused — so the automatic branch of the resolver is dead code on every
   install (F-12).

---

## 8. Bundle size and checksums

| file | bytes | SHA-256 |
|---|---|---|
| `SOURCE_TREE.txt` | 11,171 | `163410e8c98726d20bda66e4eaa7205e0d0aa17f03f8d3c25f5502fd8e6f396a` |
| `SOURCE_AUDIT_BUNDLE_01.md` | 470,423 | `89f39db2749ade7f5343252d2f458aad65105f2c87db3326440dff80131bb845` |
| `SOURCE_AUDIT_BUNDLE_02.md` | 472,394 | `23a39d640cb2ad3b6d8a8d95861bf7d3a2487eee9b9a119d734622dc1733ca0b` |
| `SOURCE_AUDIT_BUNDLE_03.md` | 470,167 | `0e00fe58bdff7811c9a45788c37b09681b371325f56fd0d037ccf509df1f3bb1` |
| `SOURCE_AUDIT_BUNDLE_04.md` | 370,751 | `2e15435dbada8f3128f025be3c3fbe92abcbe146090077a51f44320dbc5343fd` |
| `RUNTIME_WIRING.md` | 46,170 | `71e19ce23ab5342a250760b39dd43a912f960287abe2a3e2da8c75db296a9a74` |
| `IMPLEMENTATION_FINDINGS.md` | 57,267 | `c32cc262700d5957f164041190a67a64474b6d751b48432195d21b1b1ccc561b` |
| `FILE_INDEX.md` | 41,963 | `3e72f2e4f97ba72018864df74acb54e1841aadec8db93b6f36a2bfc830d2741a` |
| `REDACTION_REPORT.md` | 6,722 | `4234ffc0dd61f5afa128143012753bfe8194fd3263fb274aec0e3d640799249c` |
| `AUDIT_REPORT.md` | 15,627 | see below |

Total: **1,962,655 bytes** across the ten delivered files, as measured before
this file's final save; a self-referential size cannot be exact, so
`stat -c %s` on each file is authoritative. Checksums are SHA-256 of the files as
delivered; `sha256sum` output for the same files matched. `AUDIT_REPORT.md`
deliberately carries no self-hash: it would be wrong the moment it was written.
Hash it with `sha256sum AUDIT_REPORT.md` to pin the copy you received.

---

## 9. Notes for the external reviewer

**Read the bundle before the reports.** `SOURCE_AUDIT_BUNDLE_01..04.md` are the
evidence; everything in the analysis reports is a claim about them. Each section
is delimited by `## relative/path` and `BEGIN FILE` / `END FILE` and is
byte-identical to the file on disk.

**The bundle is a snapshot, not a build.** The Gradle wrapper properties,
module build scripts and every source file are present, so the project can be
reconstructed, but no build was run for this audit and no APK is included.
`build/` is excluded except for the test-result XML files the reports cite, which
are under `core/music-engine-local/build/test-results/` and are quoted in the
reports rather than bundled.

**Four test-result XML files are stale.** `CheckpointCodecTest`,
`CheckpointRestoreTest`, `ScanDiffTest` and `TrackIdsTest` report green with no
source in the tree. Do not count them. The verified total is **17 suites,
284 tests, 0 failures**.

**Line numbers are as of this snapshot.** A later edit to any file shifts them.
The snippet column exists so a citation can be re-found after drift.

**Two items are environment-specific and should not be redistributed.** The
default truststore password and the internal proxy hostname in
`gradle.properties`, both explained in `REDACTION_REPORT.md` §3.

**Five claims cannot be settled from source** and are marked as such in
`IMPLEMENTATION_FINDINGS.md`: keyboard appearance on the Music screen, whether
the head unit's firmware honours `setStreamVolume`, whether a given pack draws a
first frame, whether the unit ships a speech recogniser, and whether auto-parking
would work given real vehicle signals. Source proves the call and the wiring; it
cannot prove the device.
