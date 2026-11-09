# PROMPT REPAIR LAUNCHER OLED C2 / TS8

Bạn là AI repair agent. Launcher đã build, cài đặt và chạy được, nhưng còn các lỗi chức năng và sai lệch UI/UX bên dưới. Hãy audit implementation hiện tại, xác định root cause, sửa trực tiếp, build/test lại và cung cấp evidence. Không viết lại toàn bộ project nếu không cần thiết.

## 1. Bối cảnh cố định

- Thiết bị mục tiêu: OLED C2 / TS8.
- System: `V10.5.5_20210311.193033_TW2-THEME1`.
- MCU: `TS8.4.3-100-61-A4A195-211125`.
- Target: Android 6 / API 23 / landscape / phần cứng hạn chế.
- Launcher hiện đã build và chạy được.
- Ảnh design mới nhất là UI source-of-truth.
- Không có menu dọc bên trái; chỉ giữ bottom navigation.
- Campaign này vẫn là launcher APK. Không root, flash, unpack/repack hoặc chỉnh firmware.

## 2. Các lỗi đã xác nhận

1. Không load được map-pack dù map-pack đã được tạo và đặt đúng folder theo hướng dẫn.
2. Chưa có màn hình/tính năng quản lý và cài đặt map riêng.
3. Chưa có tính năng `Voice Instant`.
4. Music online chưa được triển khai; vòng trước chỉ hoàn thành local music.
5. UI chưa bám đúng ảnh design đã cung cấp.
6. UX chuyển màn hình, trạng thái và mode chưa đúng design.
7. Chưa có Parking Mode và chưa có cơ chế xác định trạng thái đỗ xe.

## 3. Mục tiêu repair

- Sửa map-pack bằng root-cause thật, không workaround hard-code một path duy nhất.
- Tạo Map Management hoàn chỉnh để import, validate, activate, update và remove map-pack.
- Bổ sung Voice Instant theo kiến trúc có thể chạy trên API 23.
- Bổ sung Music Online qua nguồn hợp lệ và không làm hỏng Local Music.
- Chỉnh UI pixel-close với design mới nhất.
- Hoàn thiện UX/state transitions và Parking Mode.
- Duy trì khả năng quay về launcher gốc; không chỉnh firmware.

---

# A. AUDIT TRƯỚC KHI SỬA

## 4. Audit bắt buộc

Trước khi code, phải kiểm tra và ghi vào `docs/REPAIR_AUDIT.md`:

- Cấu trúc module, navigation và state store hiện tại.
- Map-pack loader/importer/validator đang dùng.
- Path thực tế, Storage Access Framework, permission và scoped/unscoped storage behavior trên API 23.
- Schema/metadata/checksum/version của map-pack.
- Cách MapLibre đọc `MBTiles`, style, glyph, sprite và tile URL.
- Cách routing graph và `places.db` được resolve.
- Music service, provider boundary và audio focus hiện tại.
- UI hiện tại so với từng màn hình trong ảnh design.
- Các screen/mode nào mới chỉ là static layout hoặc chưa wired production.

Không được kết luận “file sai folder” nếu chưa có evidence từ log, resolved path, file existence, permission, checksum và parser error.

---

# B. REPAIR MAP-PACK

## 5. Điều tra root cause

Instrument map-pack pipeline bằng structured diagnostics:

```text
DISCOVERING
→ FOUND
→ VALIDATING_METADATA
→ VALIDATING_FILES
→ VALIDATING_CHECKSUM
→ IMPORTING
→ ACTIVATING
→ READY
```

Failure phải có mã rõ ràng:

```text
PACK_NOT_FOUND
PERMISSION_DENIED
INVALID_METADATA
UNSUPPORTED_SCHEMA
CHECKSUM_MISMATCH
INSUFFICIENT_STORAGE
MBTILES_OPEN_FAILED
STYLE_LOAD_FAILED
GLYPH_MISSING
SPRITE_MISSING
PLACE_DB_OPEN_FAILED
ROUTING_GRAPH_INCOMPATIBLE
ACTIVATION_FAILED
```

Log tối thiểu:

- Candidate root paths.
- URI hoặc canonical path đã resolve.
- Package ID/version/schema.
- File tồn tại, size và checksum.
- SQLite open result và bảng bắt buộc.
- Tile query test ở một `z/x/y` hợp lệ.
- Style source URL sau resolve.
- Routing graph compatibility/version.

Không log dữ liệu vị trí cá nhân ngoài diagnostics mode.

## 6. Hỗ trợ hai dạng import

### A. Folder/package có sẵn

```text
<region>/<version>/
├─ metadata.json
├─ basemap.mbtiles
├─ places.db
├─ routing-graph/
├─ style/
│  ├─ style.json
│  ├─ sprites/
│  └─ glyphs/
└─ checksums.sha256
```

### B. File package

Hỗ trợ file ZIP riêng của dự án, ví dụ `.ts8map`, chứa đúng cấu trúc trên. Import phải:

1. Chọn file bằng file picker/SAF hoặc browse storage hợp lệ với API 23.
2. Copy vào staging directory.
3. Chặn Zip Slip/path traversal.
4. Validate trước khi activate.
5. Import atomic: lỗi thì giữ pack cũ.
6. Ghi manifest và trạng thái import.

## 7. MBTiles compatibility

Xác minh:

- Các bảng `metadata`, `tiles` hoặc schema vector-tile thực tế.
- `format`, bounds, minzoom, maxzoom.
- TMS/XYZ Y-axis conversion.
- Tile blob có gzip hay không.
- MapLibre không thể đọc trực tiếp theo cách hiện tại thì tạo local tile provider/protocol adapter phù hợp, không yêu cầu Internet.
- Style, glyph và sprite phải resolve từ local asset/storage.

Tạo integration test mở một tile thực và render map tại Đà Nẵng.

---

# C. MAP MANAGEMENT / MAP SETTINGS

## 8. Màn hình Maps

Tạo `Map Management` trong Settings hoặc App Drawer, gồm:

- Danh sách map-pack đã cài.
- Region, version, dung lượng, ngày dữ liệu và trạng thái.
- Active pack.
- Import from USB/storage.
- Validate/repair registration.
- Activate.
- Remove, có confirmation.
- Storage location.
- Free space.
- 2D/3D buildings toggle.
- Day/night/auto style.
- Routing data status.
- Search index status.
- Diagnostics chi tiết khi lỗi.

### Cài đặt hiển thị bản đồ

Map Management phải có nhóm `Hiển thị` và áp dụng thay đổi ngay khi có thể:

- Chế độ bản đồ: `2D`, `3D`, `Tự động`.
- Giao diện: `Sáng`, `Tối`, `Theo thời gian`.
- Góc nhìn khi dẫn đường: `Hướng Bắc`, `Theo hướng xe`, `Phối cảnh`.
- Mức zoom mặc định và mức zoom khi navigation.
- Bật/tắt tòa nhà 3D.
- Bật/tắt tên đường, POI, địa danh và số nhà nếu dữ liệu có hỗ trợ.
- Mật độ POI: `Thấp`, `Vừa`, `Cao`.
- Kích thước chữ và icon: `Nhỏ`, `Vừa`, `Lớn`.
- Hiển thị route đã đi và route còn lại.
- Màu route chính, route thay thế và độ tương phản day/night.
- Auto-follow vehicle, auto-center và thời gian quay lại follow mode sau khi người dùng kéo map.
- Bật/tắt nút zoom, la bàn, recenter và overview.
- Chế độ hiệu năng: `Chất lượng`, `Cân bằng`, `Tiết kiệm`; trên TS8 phải mặc định `Cân bằng`.
- Có nút `Khôi phục mặc định` cho riêng nhóm hiển thị.

Mọi setting phải được lưu bền vững, restore sau process restart/reboot và không yêu cầu import lại map-pack.

### Cài đặt tốc độ và cảnh báo lái xe

Map Management phải có nhóm `Tốc độ & cảnh báo`:

- Đơn vị tốc độ: `km/h` hoặc `mph`; mặc định `km/h`.
- Bật/tắt hiển thị tốc độ hiện tại trên Home và Navigation.
- Bật/tắt hiển thị giới hạn tốc độ nếu map data có `maxspeed` hoặc nguồn hợp lệ khác.
- Ngưỡng lọc tốc độ GPS tối thiểu để tránh số nhảy khi xe đứng yên.
- Smoothing tốc độ: `Nhanh`, `Cân bằng`, `Mượt`; mặc định `Cân bằng`.
- Bật/tắt cảnh báo vượt tốc độ.
- Ngưỡng cảnh báo: `0`, `+5`, `+10`, `+15 km/h` hoặc giá trị tùy chỉnh hợp lệ.
- Hình thức cảnh báo: `Chỉ hình ảnh`, `Âm thanh`, `Cả hai`.
- Âm lượng cảnh báo độc lập nhưng phải tuân thủ audio focus.
- Cooldown cảnh báo để không lặp liên tục.
- Chỉ cảnh báo khi giới hạn tốc độ có source/confidence hợp lệ; nếu không có dữ liệu phải hiển thị `--`, không suy đoán.
- Có tùy chọn chỉ cảnh báo khi tốc độ vượt ngưỡng liên tục trong một khoảng thời gian ngắn để tránh GPS spike.
- Lưu source, timestamp và confidence của giới hạn tốc độ trong diagnostics khi có.

### Speed state và nguồn dữ liệu

Tạo boundary riêng, không đọc GPS trực tiếp trong UI:

```text
SpeedMonitor
├─ LocationSpeedSource
├─ VehicleSpeedSource (future/verified bridge)
├─ SpeedFilter
├─ SpeedLimitResolver
├─ OverspeedDetector
└─ SpeedAlertController
```

Quy tắc:

1. Nếu có vehicle speed từ bridge đã được kiểm chứng thì ưu tiên nguồn xe.
2. Nếu chưa có, dùng GPS speed đã validate và smoothing.
3. Không dùng một GPS sample đơn lẻ để kết luận vượt tốc hoặc xe đã di chuyển.
4. Khi accuracy kém, timestamp cũ hoặc speed không hợp lý, giữ trạng thái `UNRELIABLE` và không phát cảnh báo.
5. Giới hạn tốc độ ưu tiên dữ liệu route/map đã có source; không hard-code theo loại đường.

State tối thiểu:

```text
UNKNOWN
STATIONARY
MOVING
UNRELIABLE
OVERSPEED_PENDING
OVERSPEED_ACTIVE
```

UI hiển thị speed card theo design:

- Tốc độ hiện tại lớn, dễ đọc.
- Biển giới hạn tốc độ riêng biệt.
- Khi vượt tốc chỉ đổi màu/cảnh báo có kiểm soát, không animation nhấp nháy liên tục.
- Trong Parking Mode có thể ẩn speed card hoặc hiển thị `0 km/h` chỉ khi dữ liệu đáng tin cậy.

Không cho xóa active pack nếu chưa chọn pack thay thế, trừ khi người dùng xác nhận chuyển sang trạng thái `NO_MAP`.

## 9. Empty/error states

- Chưa có map: hiển thị CTA `Cài bản đồ`, không để màn hình đen.
- Pack lỗi: hiển thị nguyên nhân dễ hiểu và nút `Kiểm tra lại`.
- Thiếu routing graph: map/search vẫn dùng được, navigation báo unavailable.
- Thiếu `places.db`: map/routing vẫn dùng được, search offline báo unavailable.

---

# D. VOICE INSTANT

## 10. Định nghĩa capability

Triển khai `Voice Instant` như voice command nhanh từ launcher, không phải chatbot tổng quát. Người dùng chạm nút micro hoặc dùng trigger được hỗ trợ, sau đó nói lệnh ngắn.

MVP commands:

```text
Mở bản đồ
Dẫn đường đến <địa điểm>
Tìm <địa điểm>
Phát nhạc
Dừng nhạc
Bài tiếp theo
Bài trước
Mở điện thoại
Mở ứng dụng <tên>
Tăng âm lượng
Giảm âm lượng
Về trang chủ
```

## 11. Kiến trúc Voice

```text
VoiceInstantController
├─ VoiceTrigger
├─ SpeechRecognizerAdapter
├─ IntentParser
├─ CommandRouter
├─ VoiceFeedback
└─ VoiceStateStore
```

State:

```text
IDLE → LISTENING → PROCESSING → CONFIRMING → EXECUTING → SUCCESS/ERROR
```

Yêu cầu:

- Dùng Android SpeechRecognizer nếu khả dụng trên API 23.
- Nếu speech service offline không tồn tại, hiển thị rõ `Voice service unavailable`; không crash và không giả offline support.
- Thiết kế adapter để sau này thay bằng engine offline local.
- Lệnh có tác động lớn hoặc destination mơ hồ phải xác nhận trước khi chạy.
- Khi voice bắt đầu: duck/pause music phù hợp; sau đó restore chính xác.
- UI overlay nhỏ, không che maneuver/navigation.
- Timeout, cancel, no-match và permission denial phải xử lý đầy đủ.
- Không ghi âm nền liên tục trong MVP.

Nếu thuật ngữ `Voice Instant` trong source/design đã có định nghĩa khác, giữ compatibility và ghi rõ mapping trong tài liệu.

---

# E. MUSIC ONLINE

## 12. Scope hợp lệ

Giữ Local/USB Music hiện tại và bổ sung provider thật theo thứ tự:

1. Internet Radio stream hợp lệ.
2. Podcast RSS.
3. Navidrome hoặc Jellyfin cá nhân.
4. Provider chính thức khác nếu có API playback hợp lệ.

Không dùng YouTube extractor/scraper, `yt-dlp`, NewPipe extractor, reverse endpoint, tách audio hoặc download YouTube.

## 13. Provider architecture

```text
OnlineMusicProvider
├─ search(query, page)
├─ browse(category, page)
├─ resolve(mediaId)
├─ getPlayableSource(mediaId)
└─ health()
```

Yêu cầu:

- Provider lỗi không ảnh hưởng local music.
- Timeout, retry có giới hạn, circuit breaker và offline fallback.
- Search UI phân biệt `Trên thiết bị` và `Trực tuyến`.
- Queue hỗ trợ local và online item nhưng không duplicate playback state.
- Stream phải chạy qua MusicPlaybackService/MediaSession hiện có.
- Hiển thị loading, buffering, retry và unavailable state.
- Không cache/download nội dung khi license/provider không cho phép.

MVP ưu tiên Internet Radio hoặc Navidrome/Jellyfin nếu config có sẵn. Nếu thiếu endpoint/credential thì implement provider, settings và test double, nhưng phải báo `BLOCKED_EXTERNAL_CONFIG`, không giả đã phát online production.

---

# F. UI REPAIR THEO DESIGN

## 14. Design source-of-truth

So sánh từng màn hình với ảnh design mới nhất và tạo `docs/UI_GAP_REPORT.md` gồm:

- Layout hierarchy.
- Tỷ lệ map/music.
- Padding/margin.
- Typography.
- Icon size/stroke.
- Card radius/border.
- Active/inactive colors.
- Bottom navigation position.
- Status bar.
- Empty/loading/error states.

Không tự redesign. Không thêm menu trái.

## 15. Bảy màn hình bắt buộc

1. Home Map-first.
2. Navigation.
3. Music Fullscreen.
4. Search/Destination Preview.
5. App Drawer.
6. Quick Settings.
7. Parking Mode.

Yêu cầu:

- Scale tốt ở 1024x600 và các landscape ratio gần tương đương.
- Bottom nav cố định: `Home | Map | Music | Phone | Apps`.
- Active state duy nhất, không duplicate navigation.
- Map/music giữ state khi chuyển màn hình.
- Không recreate engine mỗi lần đổi tab.
- Không dùng runtime blur/shader nặng; dùng gradient/artwork cache tĩnh.

---

# G. UX, SCREEN TRANSITIONS VÀ MODE

## 16. App state machine

Tạo state machine thật, không điều hướng rời rạc theo click:

```text
HOME
MAP_BROWSE
SEARCH
DESTINATION_PREVIEW
ROUTE_PREVIEW
NAVIGATING
MUSIC_EXPANDED
APP_DRAWER
QUICK_SETTINGS
PARKING
```

Overlay state độc lập:

```text
VOICE_LISTENING
VOICE_CONFIRMATION
MAP_IMPORT_PROGRESS
ERROR_DIALOG
```

Rules:

- Bottom navigation đổi primary destination nhưng không hủy map/music engine.
- Back đóng overlay trước, sau đó quay về state hợp lý.
- Khi bắt đầu navigation, UI chuyển sang Navigation mode.
- Khi mở Music, player mở rộng nhưng route/navigation tiếp tục nền.
- Khi quay Home trong lúc navigation, Home hiển thị navigation-aware layout.
- Process recreation phải restore state an toàn.

## 17. Transition

- Chỉ dùng alpha/translation ngắn, nhẹ trên TS8.
- Không animation liên tục.
- Không block input trong transition.
- Tôn trọng reduced-animation/low-performance mode.
- Navigation instruction và camera map không bị reset khi chuyển panel.

---

# H. PARKING MODE

## 18. Không xác định chỉ bằng GPS

Không dùng GPS speed = 0 làm điều kiện duy nhất vì xe dừng đèn đỏ hoặc GPS mất tín hiệu cũng cho kết quả tương tự.

Tạo `ParkingStateResolver` với nhiều nguồn tín hiệu:

```text
Explicit/manual parking action
Vehicle/ACC/parking-brake signal nếu API hiện có cung cấp
Ignition/accessory state nếu đọc được an toàn
Navigation inactive
GPS speed dưới threshold trong thời gian đủ dài
Location stability
No active call/reverse-camera state
```

Priority:

1. Tín hiệu xe đáng tin cậy nếu đã có public/verified bridge.
2. Manual action.
3. Heuristic GPS/time chỉ là fallback.

Không reverse API MCU mới trong campaign này nếu chưa có contract/evidence.

## 19. Parking mode behavior

- Có nút vào/thoát manual trong Quick Settings.
- Auto-enter chỉ bật bằng setting và yêu cầu confidence đủ cao.
- Không vào Parking Mode khi dừng đèn đỏ.
- Không thoát chỉ vì một GPS spike.
- Khi phát hiện xe di chuyển liên tục vượt threshold, thoát Parking Mode.
- Parking UI: đồng hồ lớn, ngày tháng, artwork/background tĩnh, music compact và bottom navigation.
- Không tự động phát nhạc hoặc mở app.
- Lưu reason/confidence để diagnostics.

State output:

```text
DRIVING
STOPPED_TEMPORARILY
PARKING_CANDIDATE
PARKED
UNKNOWN
```

---

# I. TEST VÀ ACCEPTANCE GATE

## 20. Test bắt buộc

### Map-pack
- Import từ internal storage và USB.
- Folder và `.ts8map` package.
- Pack hợp lệ, thiếu file, checksum sai, schema sai, thiếu dung lượng.
- Activate pack mới không phá pack cũ.
- Cold restart vẫn load active pack.
- Offline hoàn toàn vẫn render/search/route.
- Display settings persist sau restart và thay đổi đúng map style/camera/layer.
- Speed display với xe đứng yên, di chuyển, GPS yếu và GPS spike.
- Speed-limit unavailable phải hiện `--`, không dùng dữ liệu giả.
- Overspeed visual/audio threshold, debounce và cooldown hoạt động đúng.

### Voice Instant
- Permission granted/denied.
- Speech service available/unavailable.
- Command hợp lệ, mơ hồ, no-match, timeout và cancel.
- Voice trong khi music phát và trong khi navigation.

### Music Online
- Search/browse/play/buffer/retry.
- Mất mạng giữa stream.
- Provider timeout.
- Chuyển online → local và local → online.
- MediaSession/audio focus không regression.

### UI/UX
- Screenshot comparison đủ 7 màn hình.
- Bottom nav, Back, process recreation và state restoration.
- Navigation vẫn tiếp tục khi mở Music/App Drawer.
- Không còn menu trái.

### Parking
- Manual enter/exit.
- GPS speed 0 ở đèn đỏ không auto-enter.
- GPS mất không tự kết luận parked.
- Di chuyển trở lại thì thoát với debounce.
- Nếu không có vehicle signal, hiển thị heuristic/manual limitation rõ ràng.

## 21. Acceptance gate

Chỉ PASS khi:

- Root cause map-pack được xác nhận và sửa bằng evidence.
- Có Map Management hoạt động thật, gồm quản lý pack, cài đặt hiển thị và cài đặt tốc độ/cảnh báo.
- Active map-pack và toàn bộ display/speed settings tự load lại sau restart.
- Speed monitor không cảnh báo sai từ một GPS spike và không suy đoán giới hạn tốc độ khi thiếu dữ liệu.
- Voice Instant chạy được hoặc báo rõ external platform limitation, không crash.
- Có ít nhất một online music provider production-capable khi config hợp lệ; nếu thiếu credential phải báo PARTIAL/BLOCKED, không PASS giả.
- UI bám design, đủ 7 màn hình, không menu trái.
- UX state machine và transition hoạt động, không mất Map/Music state.
- Parking Mode có manual mode và resolver nhiều tín hiệu; không chỉ dựa GPS.
- Build/test API 23 đạt; không fatal compile/lint, ANR hoặc crash nghiêm trọng.
- Không sửa firmware hoặc launcher gốc.

## 22. Output bắt buộc

- Source đã repair và APK debug.
- `docs/REPAIR_AUDIT.md`
- `docs/MAP_PACK_ROOT_CAUSE.md`
- `docs/MAP_MANAGEMENT.md`, bao gồm display settings, speed source/filter, speed-limit resolver và overspeed policy.
- `docs/VOICE_INSTANT.md`
- `docs/ONLINE_MUSIC.md`
- `docs/UI_GAP_REPORT.md`
- `docs/UX_STATE_MACHINE.md`
- `docs/PARKING_MODE.md`
- `docs/TEST_REPORT.md`
- `docs/KNOWN_LIMITATIONS.md`
- Screenshot 7 màn hình sau repair.
- Log/evidence cho import và load map-pack.

## 23. Báo cáo cuối

```text
Status: PASS | PARTIAL | BLOCKED | FAIL
Root causes:
Implemented:
Evidence:
Files changed:
Build/tests:
UI comparison:
Map-pack result:
Map display/speed settings result:
Voice result:
Online music result:
Parking mode result:
Known limitations:
External blockers:
Next recommended step:
```

Không dừng ở việc viết report. Hãy implement, test, tự repair và verify. Không chuyển sang sửa firmware trong campaign này.
