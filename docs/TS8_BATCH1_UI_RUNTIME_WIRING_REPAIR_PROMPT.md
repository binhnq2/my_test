# TS8 Launcher Repair - Batch 1: UI Shell & Runtime Wiring

Bạn là implementation/repair agent chạy trong sandbox. Hãy audit source hiện tại, sửa trực tiếp, build, test và tự repair. Không viết lại toàn bộ project và không mở rộng sang firmware.

## 1. Bối cảnh khóa
- Target: OLED C2 / TS8, Android 6, API 23, landscape, phần cứng thấp.
- Ảnh `UI_design.png` là visual source-of-truth.
- Đọc đầy đủ: `AUDIT_REPORT.md`, `IMPLEMENTATION_FINDINGS.md`, `RUNTIME_WIRING.md`, `FILE_INDEX.md`, `SOURCE_TREE.txt`, 4 `SOURCE_AUDIT_BUNDLE_*.md` và source thật.
- Kết quả thực tế: UI dark phẳng; status bị lặp; Map bị Music che; volume không tác động cabin; Music tự bật keyboard; Online Music khó tìm; Map trắng; Map Management không mở được; Parking chưa tạo mode rõ; Voice không có service trên TS8.
- Bạn không truy cập được TS8 thật. Không được báo `DEVICE_PASS`.

## 2. Scope duy nhất của Batch 1
Sửa UI shell và production wiring để tạo APK cho người dùng test trên TS8.

KHÔNG sửa sâu MapLibre/MBTiles renderer, routing graph, backend STT, AI Voice, firmware, MCU hoặc Auto Parking.

## 3. UI design system
- Không chỉ đổi một màu nền. Tạo/reuse token nhất quán cho navy background, blue-tinted surfaces, elevated cards, borders, active accent, text hierarchy và gradients nhẹ.
- Không runtime blur, shader nặng, animation vô hạn hoặc video background.
- Bổ sung asset/drawable cần thiết để UI có chiều sâu gần design nhưng vẫn nhẹ trên TS8.
- Loại bỏ giao diện dark-flat hiện tại; card, bottom bar, map chrome và music surface phải phân lớp rõ.
- Tối ưu cho 1024x600 và các landscape ratio gần tương đương.

## 4. System status và Home
- TS8 đã có vendor status bar. Không lặp clock/date/battery/Wi-Fi/Bluetooth/mobile ở Home, Map, Music, Search và Apps.
- Bỏ hoặc disable `StatusBarView` đầy đủ trên các screen trên; không cố ép fullscreen nếu vendor bar vẫn hiện.
- Home chỉ giữ top overlay nhỏ cho Weather, Voice và Quick Settings nếu cần.
- Weather phải có `LOADING/READY/UNAVAILABLE`; không hard-code nhiệt độ giả. Nếu chưa có provider, hiển thị unavailable gọn hoặc ẩn có chủ đích.
- Đồng hồ lớn chỉ dùng trong Parking Mode.

## 5. Screen composition
- Home: Map panel + normal Music card bên phải như design.
- Map Browse tab: full-width Map; tuyệt đối không hiển thị Music card nổi.
- Navigation: Map và compact Music card là hai vùng layout không chồng lên nhau; maneuver, overview, mute, end-route luôn nhìn và bấm được.
- Music Fullscreen: artwork/metadata/controls/list/source selector đúng hierarchy design.
- Search, App Drawer, Quick Settings và Parking phải có composition riêng, không chỉ đổi title của cùng một màn hình.
- Bottom navigation duy nhất: Home | Map | Music | Phone | Apps. Không menu trái, không duplicate action.

## 6. Map empty/error shell và Map Management wiring
- Khai báo `MapManagementActivity` đúng trong Manifest và kiểm tra mọi launch Intent.
- Có entry vào Map Management từ: Map error/empty state, Settings > Bản đồ, và App Drawer/Quick Settings phù hợp.
- Map không render trong Batch 1 vẫn không được để màn hình trắng.
- Hiển thị state: INITIALIZING, VALIDATING_PACK, LOADING_STYLE, WAITING_FIRST_FRAME, READY, FAILED.
- FAILED phải hiện error code, active pack, action `Thử lại`, `Cài đặt bản đồ`, `Chi tiết`.
- Không tuyên bố đã sửa Map rendering; chỉ bảo đảm UI/error/entry wiring.

## 7. Music UX và Online entry
- Khi mở Music, root container nhận initial focus; search field không tự focus và keyboard mặc định ẩn.
- Keyboard chỉ mở sau khi người dùng chạm Search.
- Thêm source selector rõ: `Trên thiết bị | USB | Trực tuyến`.
- Trong Trực tuyến hiển thị Radio, Podcast, Navidrome/Jellyfin.
- Provider thiếu config vẫn hiển thị CTA `Thiết lập`, không âm thầm biến mất.
- Giữ MusicPlaybackService hiện tại; không tạo playback state thứ hai.

## 8. Volume capability trung thực
- Giữ `AudioManager` bridge nhưng thêm read-before/write/read-after và trạng thái `SUPPORTED`, `NO_EFFECT`, `UNAVAILABLE`.
- Không thay đổi UI volume giả khi write thất bại hoặc read-back không đổi.
- Nếu TS8 vendor/MCU volume chưa hỗ trợ, hiển thị `Không hỗ trợ trên thiết bị này` hoặc ẩn control theo capability.
- Không reverse SYU/MCU trong batch này; giữ boundary `VehicleAudioBridge` cho adapter sau.

## 9. Parking Mode thủ công
- Manual action trong Quick Settings phải chuyển rõ sang Parking screen và thoát được.
- Parking screen bám design: background phong cảnh/xe nhẹ, clock lớn, date, music compact, bottom navigation.
- Không tự phát nhạc hoặc tự mở app.
- Auto Parking giữ mặc định OFF và gắn nhãn `Chưa khả dụng` nếu chưa có verified vehicle signals.
- Không báo Auto Parking hoàn thành từ GPS heuristic.

## 10. Launcher mode/state
- Không rewrite sang Fragment/Compose.
- Tạo hoặc hoàn thiện shared `LauncherModeStore`: HOME, MAP_BROWSE, NAVIGATING, MUSIC, SEARCH, APP_DRAWER, QUICK_SETTINGS, PARKING.
- Bottom navigation đổi mode nhưng không recreate Map/Music engine không cần thiết.
- Back đóng overlay trước rồi về mode hợp lý.
- Không để Home `onResume()` ghi đè state vừa restore một cách vô điều kiện.
- Bảo toàn playback/navigation state khi chuyển screen.

## 11. Diagnostics tối thiểu
- Thêm màn/overlay Diagnostics có: app version, build hash, current mode, active map pack, map phase/error, music service/provider, voice availability, parking state, volume capability.
- Có `Export diagnostics` tạo file text trong location người dùng truy cập được.
- Không ghi credential hoặc dữ liệu vị trí cá nhân mặc định.

## 12. Test trong sandbox
- Build debug APK với minSdk 23.
- Chạy unit/integration tests liên quan mode, visibility, focus, Manifest intents, volume capability và manual parking.
- Thêm regression tests:
  1. MAP_BROWSE không có Music card.
  2. NAVIGATING controls không bị Music card che theo layout constraints.
  3. Music search không initial-focus.
  4. Map Management resolve được từ Manifest.
  5. Manual Parking chuyển mode và thoát được.
  6. Status view không lặp trên screen thường.
  7. Online source entry luôn discoverable.
- Không dùng screenshot/layout preview làm bằng chứng chạy đúng trên TS8.

## 13. Acceptance gate
Chỉ được kết luận `SANDBOX_PASS_DEVICE_PENDING` khi:
- Build/test PASS, APK và SHA-256 có thật.
- UI source bám `UI_design.png`, không dark-flat đơn sắc.
- Không custom status trùng vendor bar trên 6 screen thường.
- Map Browse không có Music overlay.
- Map error có CTA mở Map Management và Activity resolve được.
- Music không tự bật keyboard; Online Music discoverable.
- Volume không giả thành công.
- Manual Parking production-wired; Auto Parking không bị báo hoàn thành.
- Diagnostics export có thật.

Nếu chưa đạt, tiếp tục repair. Không mở Batch 2.

## 14. Output
- Source đã sửa và APK debug.
- `docs/BATCH1_ROOT_CAUSE_AND_CHANGES.md`
- `docs/BATCH1_UI_GAP_CLOSURE.md`
- `docs/BATCH1_RUNTIME_WIRING.md`
- `docs/BATCH1_SANDBOX_TEST_REPORT.md`
- `docs/BATCH1_DEVICE_TEST_CHECKLIST.md`
- `docs/KNOWN_LIMITATIONS.md`
- APK SHA-256 và danh sách file thay đổi.

## 15. Device checklist phải ngắn và cụ thể
Tạo checklist để người dùng kiểm tra trên TS8:
- 7 màn hình/mode.
- Status duplication.
- Map Browse overlay.
- Map error/Map Management.
- Music keyboard và Online entry.
- Volume capability.
- Manual Parking.
- Diagnostics export.

## 16. Báo cáo cuối
Status chỉ được là `SANDBOX_PASS_DEVICE_PENDING`, `SANDBOX_FAIL` hoặc `BLOCKED`.
Nêu: root causes, implemented, files changed, build/tests, APK/hash, unresolved risks, device checklist và chính xác điều kiện mở Batch 2.
