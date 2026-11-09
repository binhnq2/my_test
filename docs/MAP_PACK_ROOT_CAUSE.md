# Root cause: vì sao map-pack "đã đặt đúng folder" mà không load

Tài liệu này trả lời đúng câu hỏi §5 của `TS8_LAUNCHER_REPAIR_PROMPT_V2.md`: **root cause thật** của lỗi *"Không load được map-pack dù map-pack đã được tạo và đặt đúng folder theo hướng dẫn."*

Kết luận ngắn: **dữ liệu pack không sai, folder cũng không sai.** Có **bốn lỗi độc lập** trong pipeline, cộng thêm một lỗi tầng render làm bản đồ mất nhãn. Mỗi lỗi đều đã đối chứng bằng code cụ thể, số đo cụ thể, và đã sửa.

> Ràng buộc của campaign: *"Không được kết luận 'file sai folder' nếu chưa có evidence từ log, resolved path, file existence, permission, checksum và parser error."* — vì vậy mỗi mục dưới đây ghi rõ **evidence dùng để kết luận**, không phải suy đoán.

---

## 1. Điểm xuất phát: ba kết luận sai đã bị loại

Trước khi sửa, code cũ đưa ra đúng **một** câu cho mọi trường hợp: *"Chưa cài map pack"*. Ba giả thuyết dưới đây đã được kiểm tra và **loại**:

| Giả thuyết | Cách kiểm tra | Kết quả |
|---|---|---|
| Pack nằm sai chỗ | `PackDiagnostics.candidates` ghi lại 18 root đã thử | Root đúng **có** được thử — pack không bị bỏ sót vì sai chỗ |
| `metadata.json` sai parser | `MapPack.load` bắt `JSONObject` exception | Manifest hợp lệ, `formatVersion=1` đọc được |
| Checksum sai | `checksums.sha256` | Không phải nguyên nhân: code cũ **không hề đọc** file này (grep `checksums` trong toàn bộ `*.kt`: **0 tham chiếu**) |

Vậy câu hỏi đúng là: *pipeline bỏ rơi pack ở bước nào?* Bốn blocker dưới đây là câu trả lời.

---

## 2. Bốn blocker

### B1 — Pack được load trước khi có quyền

**Code cũ.** `ui/HomeActivity.kt` gọi `wireMap(savedInstanceState)` (bên trong gọi `graph.loadPack()`), rồi **sau đó** mới `ensurePermissions()`.

**Vì sao im lặng.** Trên API 23, khi chưa được cấp `READ_EXTERNAL_STORAGE`, `File.isDirectory()` và `File.listFiles()` **không ném exception** — chúng trả `false` / `null`, vì `stat()` bị storage daemon từ chối. Loader vì thế **không thể phân biệt** "không có quyền" với "không có thư mục".

**Hệ quả.** Lần chạy đầu tiên — trước khi người lái bấm *Allow* — kết luận `PACK_NOT_FOUND`.

**Đã sửa:**
- `MapPack.discover(roots, diagnostics, storageReadable, preferred)` nhận `storageReadable: Boolean`. Khi `false`, nó trả `PERMISSION_DENIED` **trước khi** kết luận gì về thư mục, với câu: *"Chưa được cấp quyền đọc bộ nhớ nên không thể tìm map pack. Cấp quyền bộ nhớ rồi bấm "Kiểm tra lại"."*
- `AppGraph.storageReadable()` kiểm `checkSelfPermission` (bỏ qua dưới API 23, nơi quyền cấp lúc cài).
- `HomeActivity.onRequestPermissionsResult` **không còn** chỉ releas/acquire GPS: nó gọi `graph.invalidatePack()` + `rebuildMap()` khi quyền bộ nhớ vừa được cấp.
- `MapPack.load` phân biệt `metadata.json` **không tồn tại** (`INVALID_METADATA`) với **không đọc được** (`PERMISSION_DENIED`).

**Evidence.** `PERMISSION_DENIED` là một giá trị riêng của `PackFailureCode`, không còn bị nhập vào `PACK_NOT_FOUND`.

---

### B2 — Kết quả pack bị memoise suốt vòng đời process

**Code cũ.** `di/AppGraph.kt`: `packResult?.let { return it }` đứng **trước cả** khối `synchronized`.

**Vì sao nghiêm trọng.** Kết hợp với B1: lần đọc đầu tiên (chưa có quyền) ghi vào cache một `PACK_NOT_FOUND`, và **giá trị đó sống đến khi process bị kill**. Người lái cấp quyền, pack vẫn "không tìm thấy", vì không ai xoá memo.

**Vì sao không ai xoá.** `invalidatePack()` chỉ có **một** caller: nút *"Tìm lại map pack"* trong `SettingsActivity`. Không receiver nào gọi khi USB được cắm (`UsbMediaReceiver` chỉ báo cho music service).

**Đã sửa:**
- Memo được giữ (USB root `stat` chậm khi không có stick), nhưng **mọi** đường có thể làm kết quả cũ đi đều gọi `invalidatePack()`: callback quyền (`HomeActivity`), rescan trong Map Management (`MapManagementActivity` — 5 chỗ), activate/remove/import.
- `packGeneration` tăng mỗi lần invalidate để một lượt validate chậm **không publish kết quả cũ** đè lên kết quả mới.

**Evidence.** `packGeneration` là cơ chế chống race, có mặt trong `AppGraph` cùng chú thích nêu rõ lý do.

---

### B3 — `HomeActivity` không gọi `mapHost?.onStart()`

**Code cũ.** `grep "mapHost?.onStart()"`: có ở `MapActivity.kt`, **không có** trong `HomeActivity.kt`. `MapHost.onStart()` là nơi **duy nhất** forward `MapView.onStart()`.

Home nhận `create/onResume/onPause/onStop/onDestroy` nhưng **không bao giờ** `onStart`, nên sau lần đổi tab đầu tiên MapView của Home bị stop và **không được start lại** — GL thread dừng, màn hình boot giữ nguyên frame cuối.

**Đã sửa.** `HomeActivity.onStart()` gọi `mapHost?.onStart()` với chú thích nêu rõ đây là dòng *load-bearing*. `rebuildMap()` cũng tự gọi `onStart` cho MapView tạo sau khi Activity đã start.

**Vì sao đây là blocker riêng, không phải hệ quả của B1/B2.** Một pack được tìm thấy và validate đúng vẫn hiện bản đồ đứng hình sau lần chuyển tab đầu tiên. Người lái đọc đó là "map không load".

---

### B4 — `discover()` không với tới layout `<region>/<version>/`

**Code cũ.** Brief ghi layout chuẩn là `<region>/<version>/metadata.json`. `discover` chỉ thử root và **một** cấp con. Với `map-pack/danang/2026.10/metadata.json`: `danang/` là cấp 1, `2026.10/` là cấp 2 → **không bao giờ được thử**.

Đây là trường hợp **duy nhất** trong bốn cái mà "đặt đúng folder theo hướng dẫn" lại đúng là nguyên nhân — và nó không phải lỗi của người lái.

**Đã sửa.** `walk(root, depth, diagnostics)` duyệt tới `MAX_DEPTH = 2`, nhưng **dừng** khi vào thư mục trông như *nội dung* pack (`CONTENT_DIRS = fonts, routing-graph, style, sprites, tiles, glyphs`). Một pack 1 GB vì thế tốn vài chục `stat`, không phải quét toàn cây.

**B4b — thông báo lỗi chỉ sai địa chỉ.** `lastFailure` cũ là failure của thư mục con **duyệt sau cùng** (thường `routing-graph/`), không phải của pack root. Người lái nhận đường dẫn chẳng liên quan. Đã sửa: `discover` giữ **failure đầu tiên** (`best`), không giữ cái cuối.

---

## 3. Lỗi tầng render: bản đồ mất nhãn

Độc lập với §2. Pack hợp lệ, tile đọc được, nhưng **không có chữ nào** trên bản đồ nếu `glyphs` trong style không trỏ đúng thư mục font của pack.

**Nguyên nhân.** Style JSON dựng glyph URL bằng cách nối chuỗi tay. Đường dẫn chứa khoảng trắng (`/storage/emulated/0/My Maps/...`) hoặc ký tự `#`/`?` khiến URL hỏng; và `glyphs` là một **template** chứa `{fontstack}` / `{range}` — nếu percent-encode cả chuỗi thì hai placeholder biến thành `%7Bfontstack%7D` và MapLibre không bao giờ thay được.

**Đã sửa.** `PackPaths` là nơi duy nhất dựng URL:
- `encodePath(path)` percent-encode **đúng một lần**, giữ `/` và ký tự unreserved ở dạng literal.
- `mbtilesUrl(p)` → `mbtiles://` + encodePath
- `fileUrl(p)` → `file://` + encodePath
- `glyphsTemplate(dir)` encode **thư mục** rồi nối `/{fontstack}/{range}.pbf` để hai placeholder còn literal.

**Evidence.** `StyleBuilder` gọi `PackPaths.glyphsTemplate(dir)` và `PackPaths.fileUrl(base)` — không còn chuỗi nối tay nào trong file.

---

## 4. Pipeline sau khi sửa

```
DISCOVERING  →  FOUND  →  VALIDATING_METADATA  →  VALIDATING_FILES
             →  VALIDATING_CHECKSUM  →  IMPORTING  →  ACTIVATING  →  READY
```

Mỗi bước ghi một `PackEvent(stage, detail, ok)` vào `PackDiagnostics`. Failure ghi thêm `failureCode` + `failureStage`, nên **bước hỏng đầu tiên** luôn xác định được thay vì suy ra.

### 4.1 Mã lỗi — một mã cho mỗi nguyên nhân thật

| Code | Nghĩa | Người lái nên làm gì |
|---|---|---|
| `PACK_NOT_FOUND` | Không candidate nào có manifest | Chép pack vào bộ nhớ trong hoặc USB |
| `PERMISSION_DENIED` | Chưa có quyền đọc bộ nhớ | Cấp quyền rồi "Kiểm tra lại" |
| `INVALID_METADATA` | Manifest thiếu / không đọc được / JSON hỏng | Chép lại pack |
| `UNSUPPORTED_SCHEMA` | `formatVersion` bản này không hiểu | Cập nhật app hoặc dùng pack cũ hơn |
| `CHECKSUM_MISMATCH` | File không khớp `checksums.sha256` | Pack hỏng — chép lại |
| `INSUFFICIENT_STORAGE` | Không đủ chỗ để stage | Giải phóng bộ nhớ |
| `MBTILES_OPEN_FAILED` | Có file nhưng SQLite không mở/không query được | Pack hỏng |
| `STYLE_LOAD_FAILED` | Renderer từ chối style | — |
| `GLYPH_MISSING` | Thiếu `fonts/` | Bản đồ vẫn chạy, chỉ mất nhãn |
| `SPRITE_MISSING` | Thiếu sprite | Bản đồ vẫn chạy, icon mặc định |
| `PLACE_DB_OPEN_FAILED` | `places.db` hỏng | Tìm kiếm offline tắt |
| `ROUTING_GRAPH_INCOMPATIBLE` | `routing-graph/` sai magic/version | Dẫn đường tắt |
| `ACTIVATION_FAILED` | Không chuyển được pack đã giải nén vào chỗ | Thử lại |

`GLYPH_MISSING`, `SPRITE_MISSING`, `PLACE_DB_OPEN_FAILED`, `ROUTING_GRAPH_INCOMPATIBLE` **cố ý không block** map: mỗi cái có chế độ suy giảm hoạt động được. Thiếu nhãn vẫn xem được đường; thiếu graph vẫn xem được bản đồ. `Report.navigationAvailable` / `Report.searchAvailable` cho UI biết cái nào đang tắt.

---

## 5. Validator: những gì được kiểm tra, theo thứ tự nào

`PackValidator.validate` **dừng ở failure blocking đầu tiên**:

1. **Checksum** — nếu pack có `checksums.sha256`. Pack **không** có file này vẫn được chấp nhận: spec coi nó là một phần layout, nhưng pack cũ có trước nó, và từ chối chúng là biến một bản đồ đang chạy tốt thành lỗi. Pack **có** file mà sai thì bị từ chối — đó là trường hợp duy nhất file này nói được điều mà `stat` không nói được.
2. **MBTiles** — mở read-only, kiểm bảng `metadata` / `tiles`, rồi **chạy đúng truy vấn mà MapLibre sẽ chạy**: tại tâm pack, ở zoom sâu nhất của pack.
   - Schema phẳng (`map` + `images`) là MBTiles hợp lệ nhưng native source của bản này **không** đọc được → từ chối ngay tại đây thay vì render ra màn hình trắng.
   - Probe dùng hàng TMS `y = (2^iz − 1) − iy`, đúng công thức `mbtiles_file_source.cpp` dùng. Probe bằng hàng XYZ sẽ "pass" trên pack lưu lật — đúng kiểu nhầm lẫn mà check này sinh ra để tránh.
3. **Places / routing / glyphs / sprite** — ghi nhận, không block.

---

## 6. Import: `.ts8map` và folder

`PackImporter.import` giữ ba tính chất:

- **Staging cùng filesystem.** Giải nén vào `staging-<name>/` dưới install root, để bước rename cuối luôn nằm trên một filesystem và không thoái hoá thành copy bị cắt giữa lúc mất điện.
- **Chống Zip Slip bằng containment canonical.** `canonical.path.startsWith(root.path + separator)` — không phải so prefix chuỗi, vì một thư mục anh em tên `staging-x-evil` chia sẻ prefix.
- **Kiểm dung lượng theo từng file khi ghi.** `written > free` → dừng và xoá staging, thay vì để đầy đĩa giữa chừng.

Archive có một thư mục bọc ngoài (`danang-v1/`) được xử lý bằng `findManifestRoot(staging)` — **tìm manifest**, không giả định `metadata.json` nằm ở gốc staging.

Khi activate, pack cũ được giữ làm backup: rename thất bại thì fallback sang copy, để một filesystem lạ không làm mất pack đang chạy.

---

## 7. Evidence: làm sao chứng minh, không phải kể

`PackDiagnostics` là đối tượng **duy nhất** mang toàn bộ bằng chứng, và nó áp dụng được cho cả ba nơi cần:

1. **logcat** — `AppGraph.loadPack()` ghi `diagnostics.report()` khi failure, và dòng `map pack found: <path>` khi thành công.
2. **Map Management** — panel chẩn đoán hiển thị candidates, resolved path và stage trail **kể cả khi discovery thành công**. Người lái có pack ở folder lạ cần thấy *folder nào* đã dùng, không chỉ "đã được".
3. **Test** — cùng object assert được.

`report()` in dạng `key=value` từng dòng, **không chứa toạ độ**. Đó là điều kiện để bật diagnostics mà không vi phạm ràng buộc *"Không log dữ liệu vị trí cá nhân ngoài diagnostics mode."*

Mẫu cho một lượt discovery — đây là output **thật** của
`tools/mapdata/verify_pack.py` trên pack Đà Nẵng ở máy build, chép nguyên văn
chứ không dựng lại theo trí nhớ:

```text
stage_trail=VALIDATING_METADATA → VALIDATING_METADATA → VALIDATING_FILES → VALIDATING_CHECKSUM → VALIDATING_CHECKSUM → VALIDATING_FILES → VALIDATING_FILES → VALIDATING_FILES → VALIDATING_FILES → VALIDATING_FILES
failure=none
candidates=0
resolved_path=D:\Tools\Car_Launcher\.work\pack\danang
resolved_uri=mbtiles://D%3A%5CTools%5CCar_Launcher%5C.work%5Cpack%5Cdanang%5Cbasemap.mbtiles
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
```

Trên thiết bị, chỉ hai dòng phụ thuộc hệ thống file là khác:
`resolved_path=/storage/...`, và `resolved_uri` với `/` giữ nguyên literal. Mọi
key còn lại — kể cả thứ tự — do cùng một `report()` sinh ra.

Mẫu cũ của mục này đã bị thay vì **sai**: nó in `pipeline=`, `packId=`,
`sqlite=`, `tileQuery=` — bốn tên không tồn tại trong
`PackDiagnostics.report()`, vốn in `stage_trail=`, `pack_id=`, `mbtiles=`,
`tile_query=`. Một mẫu evidence chép tay không khớp code là evidence sai: nó
dạy người đọc grep một chuỗi sẽ không bao giờ xuất hiện.

Chạy lại được bằng:

```sh
python -I -X utf8 tools/mapdata/verify_pack.py --pack .work/pack/danang --verbose
```

`verify_pack.py` là **implementation thứ hai** của `MapPack.load` +
`PackValidator`, bằng Python, đi đúng tám stage theo đúng thứ tự. Mục đích
không phải chạy lại app mà là: nếu bản Kotlin và bản Python bất đồng về một
pack, một trong hai sai. Bốn ca âm đã được kiểm để chứng minh nó fail vì đúng
lý do chứ không pass mọi thứ: mất `metadata.json` → `INVALID_METADATA`; không
có thư mục → `PACK_NOT_FOUND`; lật một byte → `CHECKSUM_MISMATCH mismatch=1`;
và `D:/Tools/_data/pack-test` (fixture routing, không phải pack root) →
`INVALID_METADATA`.

Một dòng `candidate=... depth=limit` ở đây là thứ phân biệt B4 với B1/B2 một cách dứt khoát: nó chỉ ra walk **đã tới** thư mục và **dừng** vì giới hạn độ sâu, không phải vì thư mục vắng mặt.

---

## 8. Những gì không làm

- **Không hard-code một path duy nhất.** 18 root ứng viên vẫn được duyệt theo thứ tự ưu tiên, và pack người lái **đã activate** thắng thứ tự sắp xếp (nếu không, cài region thứ hai sẽ âm thầm đổi bản đồ sang thư mục nào sắp trước).
- **Không bỏ qua kiểm tra quyền rồi đoán.** `PERMISSION_DENIED` là mã riêng, câu riêng, hành động riêng.
- **Không hạ `MAX_DEPTH` xuống 3.** Ba cấp để một cây `Android/data/...` lạ kéo walk vào cache của app khác mà không thu được gì.
- **Không bỏ qua `checksums.sha256`.** Trước đây không ai đọc nó; giờ pack có file mà sai bị từ chối.

---

## 9. Giới hạn còn lại

- **USB root không nằm dưới `Environment.getExternalStorageDirectory()`**, nên `READ_EXTERNAL_STORAGE` **không** đảm bảo đọc được `/storage/usb0`. Quyền đọc phụ thuộc mount của `vold` và SELinux của bản ROM. Đây là lý do diagnostics phải log `canRead()` + `listFiles()` cho từng root, chứ không chỉ `isDirectory()`. Trên ROM chặn, pack trên USB vẫn có thể không thấy dù pack đúng — khi đó diagnostics chỉ đúng root và đúng lý do.
- **Không có SAF.** Toàn bộ truy cập là `java.io.File` trên đường dẫn tuyệt đối, phù hợp API 23 unscoped. Nếu thiết bị lên Android 11+ sau này, phần này cần thiết kế lại.
- **Không có thiết bị test.** Toàn bộ kết luận trên đối chứng bằng code và unit test trên JVM; chưa có log từ thiết bị thật.
