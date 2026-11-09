# Third-party notices

What this build contains that somebody else wrote, under what licence, and what
it obliges. Every licence below was read from the artifact actually resolved on
this build host (the `.pom` in the Gradle cache, or `pip show`) rather than
recalled — so the table and the build agree.

The short version:

- **Code:** all third-party code is Apache-2.0, BSD-2-Clause, BSD-3-Clause or MIT.
  No GPL, no LGPL, no AGPL anywhere in the APK.
- **Data:** the map pack is **ODbL 1.0** (OpenStreetMap). That licence has real
  obligations, and §3 says how this build meets them.
- **Nothing from YouTube and nothing from Waze.** No extractor, no scraper, no
  reverse-engineered endpoint — the spec forbids all three and none is present.

---

## 1. Runtime dependencies in the APK

These ship inside `app-debug.apk`. Versions are the resolved ones
(`:app:dependencies --configuration debugRuntimeClasspath`), not the requested
ones.

### 1.1 Map rendering

| artifact | version | licence | source |
|----------|---------|---------|--------|
| `org.maplibre.gl:android-sdk` | 12.0.0 | **BSD-2-Clause** | <https://opensource.org/licenses/BSD-2-Clause> |
| `org.maplibre.gl:maplibre-android-gestures` | 0.0.4 | **BSD-2-Clause** | same |
| `org.maplibre.gl:android-sdk-geojson` | 6.0.1 | **Apache-2.0** | <https://www.apache.org/licenses/LICENSE-2.0> |
| `org.maplibre.gl:android-sdk-turf` | 6.0.1 | **Apache-2.0** | same |

MapLibre Native is the only native code in the APK. It contributes four
`libmaplibre.so` builds (arm64-v8a, armeabi-v7a, x86, x86_64) and is what makes
the APK 58 MB — see `TEST_REPORT.md` §1.1.

The BSD-2-Clause obligation is the usual one: keep the copyright notice and the
licence text in distributions, and do not use the authors' names to endorse. This
document is that notice. The full text is in the artifact; a copy should accompany
any APK handed to a third party.

**MapLibre's own attribution widget is turned off** (`MapLibreRuntime.configure`
sets `isAttributionEnabled = false`) because it loads its logo and text from
network assets, and this app has no network on the map path. The ODbL credit is
drawn by the launcher's UI instead, from the pack's own `attribution` field —
which carries the same "© OpenStreetMap contributors" text. See §3.

### 1.2 AndroidX and Material

All **Apache-2.0**. Declared directly:

| artifact | version |
|----------|---------|
| `androidx.core:core-ktx` | 1.13.1 |
| `androidx.appcompat:appcompat` | 1.6.1 |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 |
| `androidx.recyclerview:recyclerview` | 1.3.2 |
| `androidx.viewpager2:viewpager2` | 1.1.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 |
| `androidx.lifecycle:lifecycle-service` | 2.8.7 |
| `androidx.media:media` | 1.7.0 |
| `androidx.preference:preference-ktx` | 1.2.1 |
| `androidx.annotation:annotation` | 1.7.1 |

Pulled in transitively (each also Apache-2.0): `androidx.fragment` 1.8.2,
`androidx.activity` 1.8.1, `androidx.core` 1.13.1, `androidx.lifecycle`
(common / runtime / viewmodel / livedata / process, 2.8.7), `androidx.savedstate`
1.2.1, `androidx.vectordrawable`, `androidx.emoji2`, `androidx.profileinstaller`
1.3.1, `androidx.startup`, `androidx.tracing`, `androidx.window`,
`androidx.versionedparcelable`, `androidx.resourceinspection`,
`androidx.collection`, `androidx.arch.core`, `androidx.concurrent`,
`androidx.drawerlayout`, `androidx.slidingpanelayout`, `androidx.customview`,
`androidx.coordinatorlayout`, `androidx.cardview`, `androidx.viewpager`,
`androidx.documentfile`, `androidx.loader`, `androidx.print`,
`androidx.interpolator`, `androidx.dynamicanimation`, `androidx.transition`,
`androidx.legacy-support-core-utils`, `androidx.cursoradapter`,
`androidx.localbroadcastmanager`, `androidx.databinding:viewbinding` 8.7.3.

| `com.google.android.material:material` | 1.11.0 | **Apache-2.0** |

### 1.3 Kotlin and coroutines

| artifact | version | licence |
|----------|---------|---------|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.0.20 (resolved; the build compiles with 1.9.25) | **Apache-2.0** |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | **Apache-2.0** |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.7.3 | **Apache-2.0** |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 |

### 1.4 Everything else in the APK

| artifact | version | licence | why it is there |
|----------|---------|---------|-----------------|
| `com.jakewharton.timber` | 5.0.1 | **Apache-2.0** | transitive of MapLibre |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | **Apache-2.0** | transitive of MapLibre |
| `com.squareup.okio:okio` / `okio-jvm` | 3.6.0 | **Apache-2.0** | transitive of okhttp |
| `com.google.code.gson:gson` | 2.10.1 | **Apache-2.0** | transitive of MapLibre |
| `androidx.annotation:annotation-experimental` | 1.4.0 | Apache-2.0 | transitive |

**Note on okhttp.** It is present as MapLibre's transitive dependency. This app
never performs a network request: the style is generated locally with
`mbtiles://<absolute path>` and glyphs come from the pack directory
(`MAP_DATA_PIPELINE.md` §5). OkHttp is on the classpath but no code path reaches
it. It is listed because it is in the APK, not because it is used.

**Note on `coreLibraryDesugaring`: `com.android.tools:desugar_jdk_libs` 2.0.4**
— Apache-2.0. Required for `java.time` and friends at API 23
(`ANDROID_6_COMPATIBILITY.md` §3).

### 1.5 Test-only dependencies (not in the APK)

| artifact | version | licence |
|----------|---------|---------|
| `junit:junit` | 4.13.2 | **Eclipse Public License 1.0** |
| `org.robolectric:robolectric` | 4.11.1 | **MIT** |
| `androidx.test:core` | 1.5.0 | Apache-2.0 |
| `androidx.test.ext:junit` | 1.1.5 | Apache-2.0 |
| `androidx.test:runner` | 1.5.2 | Apache-2.0 |

JUnit 4's EPL-1.0 is a weak copyleft on *modifications to JUnit itself*; it does
not reach the code under test, and none of it ships in the APK. Robolectric's MIT
likewise applies to test runs only.

---

## 2. Build-time tools

Not shipped, but used to produce what is shipped.

| tool | version | licence |
|------|---------|---------|
| Gradle | 8.11.1 | Apache-2.0 |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 (with the Android SDK terms) |
| Kotlin compiler | 1.9.25 | Apache-2.0 |
| JDK (Temurin/Adoptium build) | 17.0.20.1+1 | GPL-2.0 **with the Classpath Exception** |

The JDK's GPLv2+CE is the standard Java situation: the compiler is GPL, but the
Classpath Exception means compiling against it places no obligation on the
compiled output. Nothing from the JDK is redistributed in the APK.

---

## 3. Map data — the one licence with real obligations

The pack at `danang-2026.10` is derived from **OpenStreetMap**, which is
**© OpenStreetMap contributors, licensed under ODbL 1.0** (Open Database
Licence). <https://www.openstreetmap.org/copyright>

ODbL is a share-alike licence on the *database*, not on the map image. What it
requires of this build, and where each requirement is met:

| obligation | how this build meets it |
|------------|-------------------------|
| **Attribute** the source wherever the data is used | the pack's `metadata.json` carries `"attribution": "© OpenStreetMap contributors"` and `attributionUrl`; `MapPack.Metadata` reads it; `StyleBuilder` puts it into the source's `attribution` field; `SettingsActivity` shows it on the pack row. The string is never hardcoded in the app, so a different pack cannot silently mis-credit. |
| **Keep the data open** — a derived database distributed publicly must be offered under ODbL | the pack is a derived database (`places.db`, `routing-graph/`, `basemap.mbtiles`). Distributing it to a third party means distributing it under ODbL. **This campaign has not distributed the pack to anyone.** |
| **Do not add technical restrictions** on the data | the pack is plain files in a documented directory with a documented format (`docs/internal/FORMAT_SPECS.md`); no DRM, no encryption, no access restriction |
| **Produced works** (rendered map images) may be under other terms, with attribution | the rendered tiles fall under this clause; the attribution row above is what covers them |

**What this means in practice:** someone may install this APK on their own head
unit and use the pack freely. Someone may **not** be given the pack as a
proprietary asset, and anyone who redistributes a modified pack inherits the
ODbL obligation. If the pack is ever published, it must be published under ODbL
with this attribution — not folded into a closed product.

The pack is **not** shipped inside the APK. It is a separate directory the driver
copies in (`ADB_INSTALL_AND_RECOVERY.md` §3), which keeps the ODbL-covered
database a separate work from the Apache/BSD-licensed app.

### 3.1 Tile and POI provenance

| artifact | derived from | licence of the result |
|----------|--------------|----------------------|
| `basemap.mbtiles` (748 tiles, z6–14) | OSM ways/nodes, clipped to the Đà Nẵng bbox via `clip_osm.py` | ODbL |
| `places.db` (11,268 places) | OSM names and addresses | ODbL |
| `routing-graph/` (67,859 nodes, 167,859 edges) | OSM highway geometry | ODbL |
| `fonts/` glyph ranges | see §3.2 | not ODbL |

The source extract is Geofabrik's Vietnam download
(<https://download.geofabrik.de/asia/vietnam.html>), which is itself ODbL.
Clipping does not change the licence of the derivative.

### 3.2 Glyphs

The pack's `fonts/` holds 7 PBF ranges × 2 stacks, taken from the
**Klokantech Noto Sans** distribution (`tools/mapdata/fonts/`, directory names
`Klokantech Noto Sans Regular` and `… Bold`).

Noto Sans is licensed under the **SIL Open Font License 1.1**
(<https://scripts.sil.org/OFL>), and the Klokantech packaging of it adds no further
restriction on embedding. OFL permits bundling and embedding in a product,
including a commercial one, provided the font is not sold on its own and the
reserved font name is not reused for a modified version. This build uses the font
unmodified, only as glyph PBFs for map labels.

⚠️ **Caveat, stated plainly:** the source directories under
`tools/mapdata/fonts/` are present but **empty** on this build host — the glyph
PBFs used to build the pack were consumed at build time and remain only in the
pack (`.work/pack/danang/fonts/`). The licence above is the one the Klokantech
Noto Sans distribution carries; it was **not** verified by reading a licence file
from this repository, because none is checked in. If the pack is redistributed,
add the OFL text and the upstream copyright notice to the pack directory.

### 3.3 Data the pack deliberately does not contain

No Google, HERE, TomTom or Waze data. No traffic, no live or historical speeds,
no speed limits beyond the ~6 % of ways OSM tags `maxspeed`
(`KNOWN_LIMITATIONS.md` §C). Nothing in the pack was scraped from a service
whose terms forbid it.

---

## 4. What is explicitly *not* here

The spec forbids these, and a licence section is a good place to record that the
forbidden thing is also absent:

| not present | check |
|-------------|-------|
| **YouTube** extractor, downloader, scraper or any audio extraction | no dependency named youtube, youtubedl, yt-dlp, NewPipe, extractor, or any media-stream parser; `core/music-provider-online` is an **empty module** — manifest only, no Kotlin source (user directive, deferred out of this campaign) |
| **Waze** map core or reverse-engineered endpoints | no Waze dependency; the map is MapLibre on a local pack |
| Google Maps SDK | not a dependency |
| any GPL/LGPL/AGPL library | verified against the full resolved classpath in §1 — none present |

The `core/music-provider-online` and `core/diagnostics` modules exist in
`settings.gradle.kts` and contain only `src/main/AndroidManifest.xml`. They add
nothing to the APK and bring no licence obligation.

---

## 5. Notices to carry with a distributed APK

If this APK leaves this machine, ship these alongside it:

1. **This document.**
2. **The MapLibre BSD-2-Clause text**, with its copyright line — from
   `org.maplibre.gl:android-sdk:12.0.0`, `LICENSE.md` inside the AAR or the
   project at <https://github.com/maplibre/maplibre-native>.
3. **The Apache-2.0 text** once (it covers AndroidX, Material, Kotlin,
   coroutines, okhttp, okio, gson, timber, desugar, AGP, Gradle, Kotlin compiler).
4. **The ODbL 1.0 text and the OSM attribution**, if the map pack travels with it.
5. **The SIL OFL 1.1 text and the Noto Sans copyright**, if the pack travels with
   its glyphs.

The APK's own code is not covered here — this document is about third parties.
