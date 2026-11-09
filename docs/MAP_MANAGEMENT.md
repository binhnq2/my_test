# Map Management — quản lý pack, cài đặt hiển thị, tốc độ và cảnh báo

Màn hình `MapManagementActivity` là nơi duy nhất người lái import / validate / activate / remove map-pack và chỉnh toàn bộ tham số hiển thị + tốc độ. Tài liệu này ghi lại **hành vi thật của code**, không phải mong muốn.

Mục §22 của brief yêu cầu tài liệu này bao gồm bốn phần: quản lý pack, display settings, **speed source/filter**, **speed-limit resolver** và **overspeed policy**. Bốn phần đó là bốn mục lớn dưới đây.

---

## 1. Quản lý pack

### 1.1 Danh sách pack

`AppGraph.listPacks()` duyệt **cùng 18 root** mà `loadPack()` duyệt, nhưng **thu thập** thay vì dừng ở pack đầu tiên — người lái cài hai region cần thấy cả hai để chọn.

Mỗi pack là một card gồm: tên, `packId`, kích thước, `formatVersion`, trạng thái validate (`nav` / `search` có sẵn hay không), và đường dẫn thư mục.

### 1.2 Bốn hành động

| Nút | Hành vi | Ràng buộc |
|---|---|---|
| **Dùng** (activate) | Ghi `pack_active_path_v1`, `invalidatePack()`, rebuild map | Không cần pack thay thế vì pack cũ vẫn còn trên đĩa |
| **Xoá** (remove) | Xoá thư mục pack | **Chặn xoá active pack** trừ khi người lái xác nhận chuyển sang `NO_MAP` |
| **Nhập** (import) | SAF picker → giải nén `.ts8map` vào install root → validate → activate | Cần dung lượng; có thanh tiến trình theo stage |
| **Quét lại** (rescan) | `invalidatePack()` + duyệt lại toàn bộ root | Dùng khi vừa cắm USB |

**Vì sao chặn xoá active pack.** Brief bắt buộc: *"Không cho xóa active pack nếu chưa chọn pack thay thế, trừ khi người dùng xác nhận chuyển sang trạng thái `NO_MAP`."* Code kiểm `isActive` trước khi xoá và hiện dialog xác nhận riêng. Xoá xong, nếu pack đó đang active, `pack_no_map_v1 = true` được ghi để app **không** lặng lẽ rơi về pack khác mà người lái chưa chọn.

### 1.3 Import: `.ts8map`

Luồng: SAF `ACTION_OPEN_DOCUMENT` (một trong `application/zip`, `application/octet-stream`, `*/*`) → `PackImporter.import` chạy trên IO pool → overlay `MAP_IMPORT_PROGRESS` → validate → activate.

Ba tính chất của importer (đã ghi kỹ ở `MAP_PACK_ROOT_CAUSE.md` §6):

- **Staging cùng filesystem** để rename cuối không bị cắt giữa lúc mất điện.
- **Chống Zip Slip bằng containment canonical** — không phải so prefix chuỗi.
- **Kiểm dung lượng khi ghi từng file**, dừng và xoá staging nếu vượt.

Archive có thư mục bọc ngoài (`danang-v1/`) được xử lý bằng `findManifestRoot()`.

### 1.4 Folder pack

Người lái có thể cài thẳng bằng cách chép thư mục. Layout được nhận:

```
<root>/metadata.json                          (pack ngay tại root)
<root>/<packId>/metadata.json                 (1 cấp)
<root>/<region>/<version>/metadata.json       (2 cấp — layout tài liệu)
```

Walk dừng ở `MAX_DEPTH = 2` và **không** đi vào thư mục nội dung pack (`fonts`, `routing-graph`, `style`, `sprites`, `tiles`, `glyphs`).

### 1.5 Chẩn đoán

Nút **"Sao chép chẩn đoán"** copy `PackDiagnostics.report()` vào clipboard — dạng `key=value` từng dòng, **không có toạ độ**. Panel `diagText` hiển thị cùng nội dung. Kể cả khi discovery **thành công**, panel vẫn hiện candidate roots và resolved path: người lái có pack ở folder lạ cần thấy *folder nào* đã dùng.

### 1.6 NO_MAP

Khi không có pack hoặc người lái đã xác nhận xoá pack active, app vào trạng thái `NO_MAP`: map hiển thị placeholder có hướng dẫn, **không** render style rỗng, và **không** bật dẫn đường/tìm kiếm. Support/navigation tương ứng tắt theo `Report.navigationAvailable` / `Report.searchAvailable`.

---

## 2. Display settings — `Hiển thị`

Toàn bộ nhóm này là **23 khoá** trong `AppSettings`, mỗi khoá có hậu tố `_v1` để đổi định dạng không đọc nhầm giá trị cũ. Tất cả đọc lại sau restart (`SharedPreferences`).

| Nhóm | Khoá | Giá trị |
|---|---|---|
| Bản đồ | `disp_map_mode_v1` | `FLAT_2D` (mặc định) / `THREE_D` / `AUTO` |
| | `disp_theme` (`map_style_mode_v1`) | `DAY` / `NIGHT` / `AUTO` |
| | `disp_camera_v1` | `NORTH_UP` / `HEADING_UP` (mặc định) / `PERSPECTIVE` |
| Zoom | `disp_zoom_default_v1` | 6..18, mặc định tài liệu |
| | `disp_zoom_nav_v1` | 14..18 |
| 3D & nhãn | `disp_buildings_v1` | `true` |
| | `disp_label_road_v1` / `disp_label_poi_v1` / `disp_label_place_v1` | `true` |
| | `disp_label_house_v1` | `false` — xem §2.2 |
| | `disp_poi_density_v1` | `LOW` / `NORMAL` / `HIGH` |
| | `disp_text_scale_v1` | `SMALL` / `NORMAL` / `LARGE` |
| Route | `disp_route_travelled_v1` | `true` — §2.1 |
| | `disp_route_colour_v1` | ARGB, mặc định `0xFF3B82F6` |
| | `disp_alt_colour_v1` | ARGB, mặc định `0xFF6C7A91` |
| Camera | `disp_auto_follow_v1` / `disp_auto_centre_v1` | `true` |
| | `disp_follow_return_v1` | 0/5/10/20 s; **0 = tắt** |
| Nút điều khiển | `disp_show_zoom_v1` / `disp_show_compass_v1` / `disp_show_recenter_v1` / `disp_show_overview_v1` | `true` |
| Hiệu năng | `disp_performance_v1` | `QUALITY` / `BALANCED` (mặc định) / `SAVER` |

**Một translation duy nhất.** `AppSettings.mapDisplayConfig()` dựng `MapDisplayConfig` — snapshot immutable cho renderer. `core:map-api` không có `Context` và **không được** đọc preferences; launcher là tầng duy nhất biết cả store và renderer. Vì chỉ có một hàm dịch, một setting mới **không thể** bị lưu, hiện trong panel, rồi bị bỏ quên trên đường tới style.

**"Khôi phục mặc định"** (`restoreDisplayDefaults()`) chỉ `remove` các khoá hiển thị. Người lái đang đứng ở panel hiển thị mà bấm nút này **không** mất đường dẫn quét nhạc hay âm lượng cảnh báo tốc độ.

### 2.1 `Hiển thị route đã đi và route còn lại` (§8) — đường đi đầy đủ

Đây là setting bị "lưu mà không tới renderer" trước khi sửa. Chuỗi hoàn chỉnh:

```
AppSettings.showTravelledRoute            (disp_route_travelled_v1, mặc định true)
  → MapDisplayConfig.showTravelledRoute
    → MapController.setRouteProgress(travelledIndex)        [được gọi mỗi render]
      → RouteGeometry.indexAtDistance(travelledMeters)      [mét → index đỉnh]
        → GeoJSON 2 feature: có/không thuộc tính `travelled`
          → switchCase(get("travelled"), 0.35f, 1f) trong layer line + casing
```

Ba điểm cần hiểu:

- **Mét → index phải đo, không đếm.** Điểm route không cách đều: một đoạn cao tốc là hai đỉnh cách nhau 9 km, một ngã tư nội đô là mười đỉnh trong 400 m. Lấy phần trăm số đỉnh sẽ đặt xe cách vị trí thật vài km. `indexAtDistance` cộng dồn khoảng cách great-circle.
- **Index thô và index được vẽ là hai thứ khác nhau.** `MapLibreMapController` **luôn** giữ `travelledIndex` thô; việc có vẽ chia hay không do `display.showTravelledRoute` quyết định **lúc ghi geometry**. Nhờ vậy, tắt `Hiện đường đã đi` giữa chuyến rồi bật lại **không** đóng băng ranh giới ở chỗ xe đang đứng.
- **Ba đường rebuild style đều replay lại phần chia.** `onStyleLoaded` (khởi động nguội / route đang chờ), `setStyleMode` (đổi ngày/đêm), `applyDisplayConfig` (đổi `Hiển thị`). `setRoute` **cố ý** xoá `travelledIndex` (index của polyline cũ vẽ sang polyline mới sẽ tô xám một đoạn đường chưa đi, và trên một reroute quay đầu thì đó là nửa đường phía trước), nên mỗi chỗ replay phải **chụp lại giá trị trước khi** gọi `setRoute`.

### 2.2 `disp_label_house_v1` — setting có thật, layer có thể rỗng

Setting được lưu, được đọc trong `mapDisplayConfig()`, và `StyleBuilder` dựng layer nhãn số nhà khi bật. Nhưng **cảnh báo trung thực**: hầu hết pack ở định dạng hiện tại **không có** lớp `housenumber` trong `basemap.mbtiles`, nên bật lên có thể **không thấy gì thay đổi**. Đó là giới hạn dữ liệu, không phải lỗi UI. Đã ghi trong `KNOWN_LIMITATIONS.md`.

---

## 3. Speed source và filter — `Tốc độ & cảnh báo`

Chuỗi giá trị tốc độ đi qua **bốn** tầng, mỗi tầng từ chối một loại sai cụ thể. Không tầng nào tự bịa số.

```
GPS/vehicle sample
  → SpeedGate.reject()          loại mẫu không đáng tin
  → SpeedGate.applyDeadBand()   ép nhiễu dưới ngưỡng về 0
  → SpeedFilter.update()        làm mượt theo alpha
  → SpeedMonitor                phát ra readout + trạng thái cho UI
```

### 3.1 `SpeedGate` — mẫu có đáng tin không

Đây là nơi thực thi đúng ràng buộc *"không được cảnh báo sai từ một GPS spike"*. Mẫu bị từ chối vì một trong các lý do:

| Lý do | Ngưỡng | Nó chặn cái gì |
|---|---|---|
| `stale` | > 5 000 ms | Mẫu cũ platform replay lại sau hầm |
| `timestamp in future` | < −1 000 ms | Đồng hồ không đơn điệu |
| `accuracy` | > 60 m | Hầm, phố cao tầng, cold start — GPS đang đoán |
| `implausible` | > 45 m/s (~160 km/h) | Cú nhảy ở cầu / dưới tán cây |
| `negative speed` | < 0 | Dữ liệu hỏng |
| `degraded` | cờ từ engine | Engine trả giá trị cũ chứ không phải mẫu mới |

Mẫu `degraded` **vẫn** được dùng để giữ readout (đó là mục đích của việc giữ giá trị) nhưng **không** được mở cảnh báo.

Khi mẫu bị từ chối, `SpeedState.UNRELIABLE` — **mọi** consumer phía sau im lặng. Không consumer nào phải tự kiểm lại.

### 3.2 Dead-band — `ngưỡng lọc tốc độ GPS`

Setting `speed_gps_filter_v1` (0/2/4/6 km/h, mặc định **0 = tắt**). Dưới ngưỡng → giá trị **ép về 0**, không phải "làm tròn nhỏ". Đây là điều kiện để xe đứng yên không hiện "1 km/h".

Ngưỡng được **truyền vào** `applyDeadBand()` chứ không đọc từ `config` lúc khởi tạo, vì nó đổi từ màn hình setting **trong khi** bộ thu đang chạy; một giá trị nướng vào constructor sẽ bị bỏ qua đến lần khởi động process sau.

### 3.3 `SpeedFilter` — làm mượt

Ba mức, đúng tên brief:

| Mức | alpha | Dùng khi |
|---|---|---|
| `Nhanh` (FAST) | 0.55 | Cảnh báo không được tới sau bẫy |
| `Cân bằng` (BALANCED) | 0.32 | **Mặc định** — số đọc ổn định |
| `Mượt` (SMOOTH) | 0.16 | Đọc số dễ chịu, chậm hơn |

**Đổi `SpeedSource` thì re-seed, không blend.** Trộn số đọc từ bus với số đọc GPS tạo ra một số **không nguồn nào báo** — đúng loại giá trị bịa mà brief cấm. Mẫu đầu sau khi đổi nguồn được lấy nguyên.

**Vì sao không dùng filter của location engine.** Filter đó làm mượt **vị trí** và sinh speed như tác dụng phụ, tuned cho việc vẽ marker. Dùng lại hệ số của nó cho cảnh báo sẽ ghép hai yêu cầu khác nhau: đổi độ mượt của marker sẽ đổi thời điểm cảnh báo nổ.

### 3.4 Settings của nhóm này

| Khoá | Giá trị | Ghi chú |
|---|---|---|
| `speed_unit_v1` | `KMH` (mặc định) / `MPH` | |
| `speed_show_v1` / `speed_limit_show_v1` | `true` | |
| `speed_gps_filter_v1` | 0/2/4/6 km/h | Lưu **theo km/h** kể cả khi đọc mph — ngưỡng là thuộc tính của nhiễu bộ thu, không phải lựa chọn đơn vị |
| `speed_smoothing_v1` | FAST / **BALANCED** / SMOOTH | |
| `speed_alerts_v1` | `true` | |
| `speed_threshold_v1` | 0/5/10/15 km/h | Mặc định 5 |
| `speed_alert_form_v1` | VISUAL / AUDIO / **BOTH** | |
| `speed_alert_volume_v1` | 0..1, mặc định 0.8 | Âm lượng **riêng**, không theo slider nhạc |
| `speed_cooldown_s_v1` | 5/15/30/60 s | Mặc định 15 |
| `speed_sustained_v1` | `true` | Chỉ cảnh báo khi vượt **liên tục** |

**Âm lượng cảnh báo độc lập với nhạc.** Áp dụng như hệ số trên lần phát của chính cảnh báo, nên vặn nhạc xuống **không** vặn cảnh báo xuống. Audio focus vẫn được xin cho cảnh báo — đúng yêu cầu "respects audio focus" của brief.

---

## 4. Speed-limit resolver

### 4.1 Vì sao không hard-code theo loại đường

Design có một biển tròn `60`. Đường tắt là "khu dân cư = 50, cao tốc = 80". Đó là **bịa một con số liên quan an toàn**. Ràng buộc của campaign: *"Giới hạn tốc độ ưu tiên dữ liệu route/map đã có source; không hard-code theo loại đường."*

Graph routing của pack mang `speedKph` theo từng cạnh; pack builder lấy từ tag `maxspeed` của OSM **khi có**, và từ default theo road class khi không có. Cả hai **đều là dữ liệu thật về con đường đó**, nhưng **không** đáng tin như nhau — `SpeedLimitResolver` ghi lại sự khác biệt đó thay vì xoá phẳng.

### 4.2 Nguồn và độ tin cậy

| Nguồn | API | Confidence | Ý nghĩa |
|---|---|---|---|
| Tag `maxspeed` của OSM trong pack | `setTagged(kph)` | **HIGH** | Biển báo thật |
| `speedKph` của graph (tag hoặc default) | `setPackLimit(kph)` | **MEDIUM** | Pack trộn hai loại trong một field, không có bit phân biệt → khai HIGH là nói quá dữ liệu |
| Bus xe qua bridge đã kiểm chứng | `setVehicleLimit(kph)` | **HIGH** | Xe tự tin — **vượt** pack trong priority |
| Không có gì | `SpeedLimit.NONE` | — | UI hiện `--`, detector **không bao giờ** cảnh báo |

Ràng buộc *"Chỉ cảnh báo khi giới hạn tốc độ có source/confidence hợp lệ; nếu không có dữ liệu phải hiển thị `--`, không suy đoán"* được thực thi ở đúng hai chỗ: `SpeedLimit.valid` là cổng duy nhất của detector, và `SpeedLimit.NONE` là giá trị **có thể biểu diễn được** — không phải null ngầm.

### 4.3 Rời đường → xoá, không giữ

`cleared()` khi xe rời con đường đã resolve giới hạn. Giữ một `50` từ phố cũ sang cao tốc vừa vào sẽ tạo cảnh báo trên tốc độ hợp pháp. Readout về `--` ngay, và detector tự re-arm.

### 4.4 Hiển thị nguồn trong panel

`MapManagementActivity.speedLimitProvenance()` **cố ý** không hiện một con số: giá trị của resolver phụ thuộc xe đang ở đâu, bịa một số ở đây chính là guesswork bị cấm. Nó hiện một trong hai câu:

- có routing graph → *"dữ liệu route/map (tin cậy trung bình khi chỉ có mặc định theo loại đường)"*
- không có → *"không có — hiển thị `--`"*

---

## 5. Overspeed policy

`OverspeedDetector` — ba luật, mỗi luật sửa một cảnh báo sai có thật:

1. **Không có limit hợp lệ → không có event.** `SpeedLimit.valid` phải `true`.
2. **Liên tục, không phải tức thời.** Vượt ngưỡng chỉ thành active sau `confirmMs = 2 500 ms`. Đây là phần "chống GPS spike": một mẫu 90 km/h trong vùng 50 khi qua cầu **không** được nổ.
3. **Cooldown sau khi đã phát.** `cooldownMs` (setting, mặc định 15 s) — điều kiện còn đúng thì **không** nổ lại mỗi tick.

Thêm **hysteresis 3 km/h**: chỉ re-arm khi tốc độ đã xuống **rõ dưới** limit, để một tốc độ lơ lửng đúng ngưỡng không nổ lặp.

Công thức: `over = speedKmh > limit.limitKmh + thresholdKmh`. Một biển 50 với ngưỡng 5 → cảnh báo ở **55**.

Người lái tắt `speed_sustained_v1` → một mẫu vượt ngưỡng là đủ. Setting `speed_alert_form_v1` chọn `VISUAL` / `AUDIO` / `BOTH`.

---

## 6. Persist và phục hồi

Mọi khoá trên nằm trong `SharedPreferences` với hậu tố `_v1`, nên **tự load lại sau restart**: pack active, toàn bộ display, toàn bộ speed. Không có đường nào ghi giá trị "tạm thời" vào một biến static rồi mất khi process chết.

Ngoại lệ duy nhất là các giá trị **suy ra theo vị trí** (giới hạn tốc độ hiện tại, phần route đã đi) — chúng **không** được persist, vì chúng là trạng thái của xe lúc này chứ không phải lựa chọn của người lái.

---

## 7. Giới hạn

- **Không có bridge tín hiệu xe.** Priority #1 của brief (tín hiệu xe đáng tin) hiện **không** có nguồn: chưa có contract/evidence cho API MCU. `setVehicleLimit` tồn tại và đúng, nhưng chưa caller nào trong production gọi nó. Vì vậy thực tế nhóm này chạy trên nguồn pack (MEDIUM/HIGH theo tag).
- **Confidence MEDIUM là trần của pack hiện tại.** Pack chưa mang cờ "giá trị này từ tag hay từ default theo class". Khi pack builder thêm cờ đó, `setTagged` là đường báo HIGH.
- **Không có `housenumber` layer** trong hầu hết pack (§2.2).
- **Không test trên thiết bị.** Toàn bộ trên là hành vi code + unit test JVM. Chưa có ảnh chụp panel trên OLED C2.
