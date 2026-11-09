# REPAIR AUDIT — launcher OLED C2 / TS8

Tài liệu này là cổng bắt buộc của §4 trước khi sửa code. Mỗi kết luận dưới đây
đều kèm evidence cụ thể (file:dòng, số đo, hoặc kết quả lệnh). Không có kết luận
nào dựa trên suy đoán về đường dẫn.

Mốc đo: build debug baseline `:app:assembleDebug --offline` exit 0; 72 file
`.kt` trong `app/src/main/java` + `core/`; 16 layout; 6 lớp test / 149 test.

---

## 1. Cấu trúc module, navigation và state store

### 1.1 Module

`settings.gradle.kts` khai báo 1 app + 11 module core:

```
:app
:core:map-api              contract bản đồ (MapController, GeoPoint, MapStyleMode)
:core:map-engine-maplibre  engine MapLibre 12.0.0
:core:place-search         SQLite FTS4 trên places.db
:core:routing-engine-offline  A* + memory-mapped graph
:core:location-engine      pipeline GPS (validate → spike → EMA → snap)
:core:navigation-core      NavigationController (máy trạng thái dẫn đường)
:core:music-api            contract nhạc
:core:music-engine-local   MediaStore/USB + MediaPlayer
:core:music-provider-online  RỖNG
:core:vehicle-audio-bridge audio focus / volume
:core:diagnostics          RỖNG
```

`core:music-provider-online` và `core:diagnostics` chỉ có `build.gradle.kts` và
`<manifest />` rỗng; không Kotlin nào import chúng, nhưng cả hai vẫn nằm trong
dependency graph của `:app`.

### 1.2 Navigation

- 9 Activity; 8 kế thừa `BaseScreenActivity`, `ScreenOffActivity` cũng vậy.
- `BaseScreenActivity.navigateTo()` (`ui/BaseScreenActivity.kt:66-87`) chỉ
  `startActivity(Intent(...).addFlags(REORDER_TO_FRONT or SINGLE_TOP))` +
  `overridePendingTransition`. Không `finish()`, không `CLEAR_TOP`, không
  `startActivityForResult`.
- Không tồn tại router, ViewModel, NavController, `onRetainCustomNonConfigurationInstance`
  trong `app/src/main/java` (grep trả về 0).
- Máy trạng thái duy nhất trong repo là `core:navigation-core`
  (`NavigationController`, `NavigationState`), chỉ với tới qua `AppGraph.navigation()`.

Hệ quả đo được: task launcher giữ đồng thời Home + Map + Music + Apps + QS;
`QuickSettingsActivity` chỉ có đúng một lời gọi `finish()` (trong dialog restart,
`ui/QuickSettingsActivity.kt:378-385`).

### 1.3 State store (những gì thực sự được lưu)

| Store | File | Nội dung |
|---|---|---|
| `AppSettings` | `ts8_settings` | **1 key**: `map_style_mode_v1` (DAY/NIGHT/AUTO) |
| `SavedPlaces` | `ts8_saved_places` | 1 key `places_v1`, JSON array, tối đa 30 |
| `MusicPlaybackService` | `ts8.music.service` | 1 key `usb_removed_at` — **ghi mà không ai đọc** |
| checkpoint nhạc | `music-library.db` bảng `checkpoint` | track_id, position_ms, queue_json |

Không còn persistence nào khác. Route, destination, camera, tab nhạc, filter
app drawer, GPS fix cuối cùng đều chỉ nằm trong RAM.

**Không có state machine cấp app.** Đây là blocker cho §16.

---

## 2. Map-pack loader / importer / validator đang dùng

### 2.1 Loader

`core/map-engine-maplibre/.../MapPack.kt`.

- `MapPack.load(directory)` — 6 nhánh `Failure`:
  `!isDirectory`, thiếu `metadata.json`, JSON hỏng, `formatVersion != 1`,
  thiếu `basemap.mbtiles`, `maxZoom < minZoom`.
- `MapPack.discover(roots)` — duyệt mỗi root, thử `load(root)`, rồi duyệt **một
  cấp con** (`root.listFiles()` → mỗi child thư mục → `load(child)`).
- `MapPack.defaultRoots(context)` — 16 đường dẫn (xem §3.1).

### 2.2 Importer

**Không tồn tại.** Grep toàn repo cho `ACTION_OPEN_DOCUMENT`,
`ACTION_GET_CONTENT`, `DocumentFile`, `takePersistableUriPermission`,
`ActivityResult`: **0 kết quả**. Không có SAF, không có file picker, không có
giải nén ZIP, không có `.ts8map`.

### 2.3 Validator

Không có validator thật. `load()` chỉ `File.isFile` trên `basemap.mbtiles`:

- Không kiểm tra file đọc được, không kiểm tra SQLite mở được.
- Không đọc `checksums.sha256` — **grep `checksums` trong toàn bộ `*.kt`: 0 tham chiếu**.
- Không kiểm tra `places.db` / `routing-graph/` có mở được hay không.
- Không kiểm tra tile trả về dữ liệu.

### 2.4 Bốn blocker của pipeline (đã đối chứng bằng code + số đo)

**B1 — Pack được load trước khi có quyền.**
`ui/HomeActivity.kt:126` gọi `wireMap(savedInstanceState)` (bên trong gọi
`graph.loadPack()` tại `runtime/MapHost.kt:67`), rồi **`:131`** mới gọi
`ensurePermissions()`. `onRequestPermissionsResult` (`:334-352`) chỉ
`release`/`acquire` GPS, **không** `graph.invalidatePack()`, không thử lại pack.

**B2 — Kết quả pack bị memoise suốt vòng đời process.**
`di/AppGraph.kt:121-132`: `packResult?.let { return it }` trước cả `synchronized`.
`invalidatePack()` (`:138`) có đúng **một** caller: `SettingsActivity.kt:150`
(nút "Tìm lại map pack"). Không có receiver nào gọi khi USB được cắm
(`UsbMediaReceiver` chỉ báo cho music service).

**B3 — `HomeActivity` không gọi `mapHost?.onStart()`.**
Grep `mapHost?.onStart()`: có ở `MapActivity.kt:296`, **không có** trong
`HomeActivity.kt`. `MapHost.onStart()` (`runtime/MapHost.kt:116-118`) là nơi
duy nhất forward `MapView.onStart()`. Home nhận
`create/onResume/onPause/onStop/onDestroy` nhưng không bao giờ `onStart`, nên sau
lần đổi tab đầu tiên MapView của Home bị stop và không được start lại.

**B4 — `discover()` không với tới layout `<region>/<version>/`.**
Brief yêu cầu `<region>/<version>/metadata.json`. `discover` chỉ thử root và
**một** cấp con; với `map-pack/danang/2026.10/metadata.json` thì `danang/` là
cấp 1 và `2026.10/` là cấp 2 → **không bao giờ được thử**.

Ngoài ra `lastFailure` là failure của thư mục con **duyệt sau cùng** (ví dụ
`fonts/`), không phải của pack root — nên thông báo lỗi chỉ sai địa chỉ.

### 2.5 Đường dẫn tới người lái

`MapHost.create()` lấy `(result as? Failure)?.reason` (`runtime/MapHost.kt:70`)
và đưa thẳng vào `mapPlaceholderText`. `SettingsActivity` (doc tại
`ui/SettingsActivity.kt:113-116`) **khẳng định** chuỗi lỗi liệt kê các thư mục
đã tìm — điều này **sai**: chuỗi chỉ chứa đường dẫn của đúng một thư mục.

---

## 3. Đường dẫn thực tế, SAF, permission, scoped/unscoped storage trên API 23

### 3.1 16 root ứng viên, đúng thứ tự

```
1..2  <filesDir>/map-pack
3..4  <getExternalFilesDir(null)>/map-pack          (nếu != null)
5     /storage/emulated/0/ts8/map-pack
6     /storage/emulated/0/TS8/map-pack
7..18 (6 USB root) × ("ts8/map-pack", "TS8/map-pack")
      /storage/usb0 /storage/usb1 /storage/usbdisk
      /storage/usb_disk /mnt/usb_storage /storage/UsbDriveA
```

Thực tế `buildList` sinh ra 16 phần tử khi `getExternalFilesDir` khác null.

### 3.2 SAF

Không dùng. Toàn bộ truy cập là `java.io.File` trên đường dẫn tuyệt đối.

### 3.3 Permission

Manifest có `READ_EXTERNAL_STORAGE` (không giới hạn maxSdk),
`WRITE_EXTERNAL_STORAGE maxSdkVersion="28"`. Trên API 23 cả hai là runtime
permission.

**Hành vi quan trọng (nguồn gốc của kết luận sai "file sai folder"):** khi chưa
được cấp `READ_EXTERNAL_STORAGE`, `File.isDirectory()` và `File.listFiles()`
không ném exception — chúng trả `false` / `null` vì `stat()` bị storage daemon
từ chối. Vì vậy loader **không thể phân biệt** "không có quyền" với "không có
thư mục". Kết hợp với B1/B2, lần chạy đầu tiên (trước khi driver bấm Allow) sẽ
ghi vào cache một `PACK_NOT_FOUND` và giữ nguyên nó.

Đây là lý do phải thêm mã `PERMISSION_DENIED` tách khỏi `PACK_NOT_FOUND`, và
phải kiểm tra `checkSelfPermission` **trước** khi kết luận về thư mục.

### 3.4 Scoped / unscoped

API 23 là unscoped: app đọc được toàn bộ `/storage/emulated/0` **sau khi** được
cấp `READ_EXTERNAL_STORAGE`. Không có `MediaStore`-only, không cần SAF cho thư
mục công khai. Nhưng: `/storage/usb0` và các đường dẫn USB không nằm dưới
`Environment.getExternalStorageDirectory()`, nên quyền `READ_EXTERNAL_STORAGE`
**không** đảm bảo đọc được chúng; chúng phụ thuộc mount của hệ thống
(`vold`) và SELinux của bản ROM. Đây là lý do phải log `canRead()` +
`listFiles()` cho từng root chứ không chỉ `isDirectory()`.

---

## 4. Schema / metadata / checksum / version của map-pack

### 4.1 Ba nguồn đang lệch nhau

| Nguồn | `style` | `sprite` | `glyphs` | `maxCameraZoom` |
|---|---|---|---|---|
| `docs/internal/FORMAT_SPECS.md` §1.1 | có (`style{day,night}`) | `sprites/sprite` | có | không |
| `tools/mapdata/write_manifest.py` (pack thực tế) | **không** | `""` | có | `18` |
| `MapPack.Metadata` | có, default `style-day.json` | có | có | có, fallback `maxZoom` |

- `metadata.json` thực tế: 691 byte, `formatVersion 1`,
  `packId "danang-2026.10"`, `bounds [107.85,15.85,108.45,16.25]`,
  `center [108.15,16.05]`, `defaultZoom 11.0`, `minZoom 6`, `maxZoom 14`,
  `maxCameraZoom 18`, `tileExtent 4096`, `tileScheme "simple"`,
  `basemap/placesDb/routingGraph`, `glyphs "fonts/{fontstack}/{range}.pbf"`,
  `sprite ""`, `glyphStacks ["Noto Sans Regular","Noto Sans Bold"]`.
- `MapPack.stylePath` và `styleFile()` có **0 call site** → style luôn được
  `StyleBuilder` sinh tại runtime, khối `style` trong manifest là **inert**.
- `Metadata.sprite` được parse nhưng `StyleBuilder` **không bao giờ** ghi key
  `sprite` (grep: `sprite` chỉ xuất hiện trong một comment ở `StyleBuilder.kt:53`).
- `tileExtent` / `tileScheme` được chấp nhận và **bỏ qua**.

### 4.2 Checksum — đo lại trong campaign này

`checksums.sha256` (19 dòng) đối chiếu với đĩa:

```
ok 19   mismatch 0   missing 0
listed_bytes 20,889,204
files_on_disk 20 (19 file được liệt kê + chính checksums.sha256)
```

→ **Dữ liệu pack lành.** Không có file hỏng, không thiếu file. Vậy nguyên nhân
"không load được pack" **không** phải dữ liệu, mà là pipeline ở §2.4.

### 4.3 Version

`SUPPORTED_FORMAT_VERSION = 1`; pack là v1 → khớp. `graph.bin` magic `TS8G` +
version 1; `graph.idx` magic `TS8I`. Cả hai khớp spec §3.1/§3.4.

---

## 5. Cách MapLibre đọc MBTiles, style, glyph, sprite và tile URL

Nguồn: source MapLibre Native đã tải trong `.work/mlx/` (mrl.cpp, lfs.cpp,
mbtiles_file_source.cpp) + AAR 12.0.0 đóng trong APK.

### 5.1 Thứ tự dispatch (`mrl.cpp:69-124`)

```
asset → mbtiles → pmtiles → localFile → database → network
```

Nếu không nguồn nào `canRequest` → `Response::Error(Other, "Unsupported resource request.")`.

### 5.2 `mbtiles://`

- `acceptsURL` = `url.starts_with("mbtiles://")` (`mbtiles_file_source.cpp:31-33`).
- Đường dẫn = `percentDecode(url.substr(strlen("mbtiles://")))` (`:35-37`).
- `request_tile` (`:185-210`): `y = (2^iz - 1) - iy`, rồi
  `SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?`.
  Nếu blob gzip (`0x1f8b`) thì giải nén.
- `request_tilejson` đọc bảng `metadata`, buộc `scheme = "xyz"`.

→ **TMS/XYZ là việc của native, không phải của app.** App chỉ phải đưa đúng
`mbtiles://` + đường dẫn tuyệt đối đã percent-encode.

### 5.3 `file://`

`LocalFileSource::acceptsURL` **chỉ** nhận `file://` (`lfs.cpp:15-17`); URL khác
trả `"Invalid file URL"`. Đường dẫn sau đó được `percentDecode`.

### 5.4 Glyph — **root cause #2**

`StyleBuilder.kt:56-63`:

```kotlin
pack.resolveGlyphsDir()?.let { dir ->
    val stack = pack.metadata.glyphs?.substringAfter("fonts/")?.substringBefore('{')
        ?: "Noto Sans Regular"                      // biến chết, không dùng
    json.put("glyphs", "${dir.absolutePath}/{fontstack}/{range}.pbf")   // KHÔNG có scheme
}
```

Chuỗi sinh ra là `/data/.../fonts/{fontstack}/{range}.pbf`. Trình phân giải URL
của MapLibre coi đây là đường dẫn **không scheme** → rơi qua asset, mbtiles,
pmtiles, rồi `LocalFileSource` từ chối vì không bắt đầu bằng `file://` → rơi
tiếp `database` → network → `"Unsupported resource request."`.

Kết quả: **mọi layer `symbol` (place/road/poi label) không có glyph**, nên
không vẽ được nhãn. Bản đồ chỉ còn hình học. Đây là lỗi thứ hai độc lập với §2.4.

`localFileSource` chỉ được đăng ký khi có `file://`; vì vậy sửa đúng là
`file://` + percent-encode đường dẫn.

### 5.5 Sprite

Không có key `sprite` trong style. Pin/vehicle/route được đăng ký bằng icon
runtime. Vì vậy `SPRITE_MISSING` hiện **không** phải nguyên nhân gây lỗi, nhưng
brief vẫn yêu cầu mã lỗi này — nó phải được phát khi pack khai báo `sprite`
khác rỗng mà file không tồn tại.

### 5.6 Tile URL — lỗi tiềm ẩn thứ ba

`StyleBuilder.kt:87`:

```kotlin
source.put("tiles", org.json.JSONArray().put("mbtiles://${pack.tilesUri}"))
```

Nối chuỗi thô. `percentDecode` ở phía native sẽ giải mã `%` nếu có, nhưng app
**không** encode. Nếu đường dẫn chứa dấu cách hoặc ký tự non-ASCII (ví dụ
`/storage/emulated/0/ts8/Map Pack Đà Nẵng/`), URL sẽ hỏng và native trả
`PACK_NOT_FOUND`/không có tile — im lặng, không thông báo.

### 5.7 Dữ liệu tile — đo trong campaign này

```
bảng:      metadata, tiles
format:    pbf
minzoom:   6      maxzoom: 14
bounds:    107.85,15.85,108.45,16.25  (khớp manifest)
tiles:     748   (z6:1 z7:1 z8:2 z9:4 z10:10 z11:21 z12:55 z13:160 z14:494)
sample:    z11/x1636/y1117  256 B  magic 1f8b  (gzip)
Đà Nẵng 16.0544,108.2022 → z11 hit 341,257 B · z13 hit 173,516 B · z14 hit 40,619 B
layer z13: place poi transportation road railway landuse water building aeroway waterway boundary landcover
```

→ 10 source-layer mà `StyleBuilder` dùng **đều có** trong tile.

---

## 6. Routing graph và `places.db` được resolve thế nào

### 6.1 places.db

`AppGraph.placeSearch()` (`di/AppGraph.kt:173-183`) → `pack.databaseFile()` →
`File(directory, metadata.placesDb).takeIf { it.isFile }` → `SqlitePlaceSearch.open(db, bounds)`.

Đo trên pack:

```
places.db  2,277,376 B
tables:    places, places_fts_segments, places_fts_segdir,
           places_fts_docsize, places_fts_stat, places_fts
places:    11,268 dòng
places_fts: FTS4 content='' (external content) — đúng thiết kế cho API 23
```

→ Không có `user_version` = 1 được set trong pack, và loader **không kiểm tra**
cột bắt buộc. Nếu `places.db` hỏng, `SqlitePlaceSearch.open` trả null và
`SearchActivity` hiện `search_unavailable` — nhưng **không** phân biệt được
"không có file" với "file hỏng".

### 6.2 routing graph

`AppGraph.routing()` (`:222-230`) → `pack.routingDir()` →
`File(directory, metadata.routingGraph).takeIf { it.isDirectory }` →
`AStarRouter.open(dir)` → `RoutingGraph.open(File(dir,"graph.bin"))` +
`GraphIndex.open(File(dir,"graph.idx"), nodeCount)`.

Cả hai trả `null` khi magic/version sai (`RoutingGraph.kt:223-233`,
`GraphIndex.kt:140-151`). Không có thông báo nào tới UI khi `null`: `MapActivity`
hiện `nav_no_pack` — chẩn đoán sai, vì pack có thể tồn tại mà chỉ thiếu graph.

Đo trên pack:

```
graph.bin  6,047,219 B  magic "TS8G"  version 1
graph.idx    513,492 B  magic "TS8I"  version 1
```

→ graph hợp lệ. Lỗi hiển thị `nav_no_pack` khi thiếu graph là **lỗi chẩn đoán**,
không phải lỗi dữ liệu.

---

## 7. Music service, provider boundary, audio focus hiện tại

### 7.1 Service

`service/MusicPlaybackService.kt` — foreground `mediaPlayback`, `START_STICKY`,
`MediaSession`, `MediaButtonReceiver` khai báo trong manifest nhưng **không được
gắn vào session nào**. Phát qua `MediaPlayer` trên `STREAM_MUSIC`.

### 7.2 Provider boundary

- `core:music-provider-online` **rỗng**.
- `Track.SourceKind` chỉ có `MEDIA_STORE | USB`.
- `LocalMusicPlayer.sourceExists()` chỉ chấp nhận `content://`, còn lại đòi
  `File(source).isFile && canRead() && length() > 0` → **một URL `http(s)://`
  bị báo `SOURCE_UNAVAILABLE` trước khi tới `setDataSource`**.
- Không có `STATE_BUFFERING`; `PlaybackError` chỉ có 5 giá trị hướng file cục bộ
  (`SOURCE_UNAVAILABLE, UNSUPPORTED_FORMAT, DECODE_FAILED, AUDIO_FOCUS_LOST, UNKNOWN`)
  và đang bị **hai `when` exhaustive** trong UI (`MusicFullscreenActivity`,
  `MusicCardView`) — thêm giá trị sẽ vỡ build nếu không sửa cả hai.

### 7.3 Audio focus

- Toàn bộ âm thanh đi qua `STREAM_MUSIC`; `duck()` hạ và khôi phục **cùng**
  stream đó.
- `onFocusGained` nhánh unduck là code chết:
  `return if (wasDucked) Transport.NONE else Transport.NONE`.
- TTS không set `KEY_PARAM_STREAM`.
- **Không có âm lượng cảnh báo độc lập** — §8 yêu cầu, hiện trạng không có.

### 7.4 Các lỗi vận hành đã xác nhận

- Position không cập nhật: `checkpointTicker` lưu checkpoint mỗi 10 s nhưng
  **không** phát `PlaybackState`, nên seek bar đứng giữa các sự kiện rời rạc.
- `onFocusGained` / 2 lần request focus mỗi track trên đường play-from-null.
- `notifyStorageRemoved` mất tham số `path` (`send()` chỉ set action) → mọi sự
  kiện volume đều bị coi là ảnh hưởng track hiện tại.
- Checkpoint restore mất vị trí resume: `setQueue(autoPlay=false)` prepare với
  `startAtMs=0`, rồi `seekTo(cp.positionMs)` rơi vào nhánh `player == null`.

---

## 8. UI hiện tại so với từng màn hình design

Ảnh design: `docs/UI_design.png` — 7 màn, bố cục 1024×600 landscape.

| # | Design | Hiện trạng | Kết luận |
|---|---|---|---|
| 1 | Home: map tràn trái, music card 300dp phải, speed card + biển 60, cột control dọc, search bar dưới, bottom nav 5 mục | `activity_home.xml` khớp cấu trúc: `mapHost` end→`musicCard` 300dp, `speedChip` minWidth 72dp, `speedLimit` 56dp `gone`, 4 nút 44dp, `searchField` 48dp | **Khớp khung**; thiếu: biển tốc độ không bao giờ hiện, `speedChip` không được code nào đọc (luôn "0"), weather chip ẩn |
| 2 | Navigation: maneuver card 300m + "Sau đó", ETA strip, speed 48 + biển 60, music card, control dẫn đường | `activity_map.xml` có `maneuverCard`, `etaStrip`, `guidanceControls` (mute/overview/end), `browseControls`, `destinationCard` | **Khớp khung**; biển tốc độ bị force `GONE`; không có Voice |
| 3 | Music fullscreen: ảnh 248dp + tiêu đề + meta + seek + 6 transport; panel phải 4 tab + list | `activity_music_fullscreen.xml` đúng hai cột, `btnPlayPause` 64dp, tab 40dp, `libraryEmpty/Loading/Footer` | **Khớp khung**; seek bar đứng; tab "Danh sách phát" là recently-played; không có online |
| 4 | Search: ô tìm + list kết quả + khung xem trước bản đồ + card chi tiết + `Lộ trình`/`Lưu` | `activity_search.xml` có `searchBar`, `resultsPane`, `detailPane`, `splitGuide` | **Lệch**: 3 id `searchBar/splitGuide/resultsPane/detailPane` không được code tham chiếu; không có map preview (đã ghi trong `UI_IMPLEMENTATION.md`) |
| 5 | App Drawer: lưới 6 cột icon 64dp | `activity_app_drawer.xml` + `AppCatalog` | **Khớp khung**; không phân biệt lỗi liệt kê với máy rỗng; không theo dõi install/remove |
| 6 | Quick Settings: 4 tile + 2 slider + 3 action | `activity_quick_settings.xml`; `qsTitle/qsNote/brightnessIcon/actionRow/volumeRow/toggleRow` không được dùng; `qsNote` luôn `gone` | **Khớp khung**; slider âm lượng bỏ qua `AudioResult`; brightness trộn thang 0–255 và 0–100; "Khởi động lại" chỉ restart task |
| 7 | Parking: đồng hồ lớn, ngày, thông điệp, bottom nav | `activity_parking_mode.xml` đúng (clock 96sp, gradient thay ảnh) | **Khớp khung nhưng tĩnh**: không có resolver, không auto-enter, không có live map/music card |

### 8.1 Bottom navigation — lệch mô hình

`BottomNavView.Screen` chỉ có `HOME, MAP, MUSIC, APPS, NONE`. **Không có `PHONE`.**
Mục "Điện thoại" trong `view_bottom_nav.xml:82-96` chỉ là click →
`ACTION_DIAL tel:` (fallback app drawer), **không** `setSelectedScreen`. Vì vậy
design 5 mục nhưng chỉ 4 mục có trạng thái active.

Readout `‹ 24.0° ›` của design được implement thành **stepper âm lượng media**
(`volDown/volIcon/volLabel/volUp`), hiển thị số nguyên. Lý do đã ghi trong
`UI_IMPLEMENTATION.md` (HVAC thuộc CANBUS, không có API). Đây là deviation được
chấp nhận, phải ghi lại trong `UI_GAP_REPORT.md`.

### 8.2 Các id có trong layout nhưng không bao giờ được wire

`weatherChip` (status bar, luôn gone) · `qsNote` (luôn gone) · `speedChip`
(Home — luôn "0") · `navPhoneIcon/navPhoneLabel` · `nowPlayingPanel` ·
`topLeftStack` · `settingsScroll/settingsTitle` · `appsHeader` · `transportRow` ·
`searchBar/splitGuide/resultsPane/detailPane`.

---

## 9. Screen/mode nào chỉ là static layout hoặc chưa wired production

| Screen/mode | Trạng thái | Evidence |
|---|---|---|
| Home map | **Chưa wired** — MapView không nhận `onStart` sau tab đầu; camera không lưu | `HomeActivity.kt:354-359`; không có `onSaveInstanceState` |
| Home speed card | **Static** — luôn "0", không đọc `LocationFix.speedMps` | `HomeActivity` không bind `speedValue` |
| Speed-limit roundel | **Static/dead** — `setSpeedLimit(null)` gọi đúng một lần | `HomeActivity.kt:125,248-256` |
| Map navigation | **Wired một phần** — thiếu graph/voice; biển tốc độ force GONE | `MapActivity.kt:230-231,276` |
| Parking Mode | **Static** — không resolver, không auto, không tín hiệu xe | `activity_parking_mode.xml` + `ParkingModeActivity.kt` |
| Map Management | **Không tồn tại** | không có Activity/layout/string |
| Voice Instant | **Không tồn tại** — không string, không permission `RECORD_AUDIO` | grep 0 |
| Online music | **Không tồn tại** — module rỗng | §7.2 |
| App state machine | **Không tồn tại** | §1.2 |
| QS "Khởi động lại" | **Ghi sai** — chỉ restart task, không reboot | `QuickSettingsActivity.kt:374-387` |
| Music "Danh sách phát" | **Ghi sai** — là recently-played | `MusicFullscreenActivity` |
| `core:diagnostics` | **Rỗng** | 0 file `.kt` |

---

## 10. Tổng hợp root cause cho lỗi #1 (map không load)

Bốn nguyên nhân độc lập, **tất cả đều cần sửa**; sửa một cái không đủ:

| # | Root cause | Fix bắt buộc |
|---|---|---|
| R1 | Pack được discover **trước** khi xin `READ_EXTERNAL_STORAGE`, rồi bị cache vĩnh viễn | Kiểm tra quyền trước, `invalidatePack()` khi được cấp, phát `PERMISSION_DENIED` riêng |
| R2 | `discover()` chỉ duyệt 1 cấp → layout `<region>/<version>/` không tới được | Duyệt theo độ sâu có giới hạn + ghi nhận từng ứng viên |
| R3 | `HomeActivity` không forward `onStart` → MapView chết sau tab đầu | Forward đầy đủ lifecycle |
| R4 | Glyph URL thiếu `file://` → mọi label không vẽ được | Sinh URL đúng scheme + percent-encode |

Thêm hai lỗi phụ đã xác nhận: URI mbtiles không percent-encode (§5.6), và
`checksums.sha256` chưa từng được xác minh trên thiết bị (§4.2).

**Không có evidence nào cho kết luận "file sai folder".** Pack trên đĩa lành
(19/19 checksum, 748 tile, graph và places.db hợp lệ). Lỗi nằm ở pipeline.
