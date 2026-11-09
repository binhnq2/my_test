# PROMPT IMPLEMENT LAUNCHER THẬT CHO OLED C2 / TS8

Bạn là AI implementation agent. Hãy audit source hiện tại, sau đó trực tiếp triển khai, build, test và tự sửa lỗi cho tới khi đạt acceptance gate.

## 1. Bối cảnh cố định
- Thiết bị mục tiêu: OLED C2 / TS8.
- System: `V10.5.5_20210311.193033_TW2-THEME1`.
- MCU: `TS8.4.3-100-61-A4A195-211125`.
- Target kỹ thuật: Android 6, API 23, landscape, phần cứng yếu.
- Firmware local chỉ là tài sản cho phase sau; KHÔNG unpack, root, flash hoặc chỉnh firmware trong campaign này.
- Launcher phải chạy ổn dưới dạng APK/Home app trước, sau đó mới cân nhắc tích hợp firmware.
- Đọc hai MD định hướng trước và ảnh design mới nhất. Ảnh mới nhất là UI source-of-truth: không có menu trái, chỉ có bottom navigation.

## 2. Mục tiêu
Tạo launcher production-capable, không dùng mock cho Map hoặc Music:
1. Home map-first đúng design.
2. Map offline thật: render, tìm địa điểm, GPS, route, navigation và reroute.
3. Music thật: local/USB, background playback, playlist và audio focus.
4. App drawer mở ứng dụng đã cài.
5. Quick settings chỉ thực thi action Android 6 thực sự cho phép.
6. Chạy ổn định như Home launcher nhưng không thay/xóa launcher gốc.

## 3. Không thực hiện
- Không thu thập thêm thông tin thiết bị trong campaign này; code theo Android 6/API 23.
- Không dùng Compose, Flutter, React Native hoặc WebView làm UI/map renderer chính.
- Không làm phạt nguội, TPMS, weather widget hoặc dashboard thừa.
- Không dùng Waze làm map core; không reverse-engineer endpoint Waze.
- Không dùng YouTube scraper/extractor, `yt-dlp`, NewPipe extractor hoặc tải/chuyển audio YouTube.
- Không giả báo thành công với setting/action cần system privilege.

## 4. Công nghệ bắt buộc
- Android Studio, Kotlin/Java theo source hiện tại, Android Views/XML, `minSdkVersion 23`.
- Map core: MapLibre Native Android phiên bản tương thích API 23/OpenGL ES cũ; khóa version sau compatibility build.
- Map data: OpenStreetMap Việt Nam; MVP sử dụng map pack Đà Nẵng offline.
- Search: SQLite FTS, hỗ trợ tiếng Việt có dấu/không dấu và ranking theo khoảng cách.
- Routing: GraphHopper offline phiên bản tương thích Android 6; graph cache build trên PC, load bằng memory-mapped access nếu hỗ trợ.
- Music: foreground/background Service, MediaSessionCompat, AudioFocus, MediaStore và USB storage.
- Chạy I/O, search, route và media scan ngoài main thread.

## 5. UI phải bám design mới nhất
Implement 7 trạng thái/màn hình:
1. Home: map lớn bên trái/giữa, music card bên phải, status trên, bottom navigation dưới.
2. Navigation: maneuver nổi bật, route lớn, ETA/distance, compact music, overview/mute/end.
3. Music fullscreen: artwork, metadata, progress, controls, playlist/song/album/artist.
4. Search: ô tìm kiếm, kết quả địa điểm, map preview, destination card, route/save.
5. App Drawer: grid app thật từ PackageManager và mở bằng launch Intent.
6. Quick Settings: Wi-Fi/Bluetooth/data/theme/brightness/volume theo quyền khả dụng.
7. Parking Mode: đồng hồ lớn, background tĩnh, bottom navigation nhất quán.

Bottom navigation duy nhất: `Home | Map | Music | Phone | Apps`. Không tạo menu trái ở bất kỳ màn hình nào.

## 6. Map thật
- Render hoàn toàn từ asset/map pack local; style, glyph và icon không phụ thuộc Internet.
- Map pack: `metadata.json`, `basemap.mbtiles`, `places.db`, `routing-graph/`, `checksums.sha256`.
- Hiển thị road, water, landuse, label, POI, route line, vehicle marker, day/night style.
- 3D buildings dùng `fill-extrusion` từ building height/levels; chỉ bật zoom >= 15 và có fallback 2D.
- Search prefix: `cau r` phải tìm được `Cầu Rồng`; debounce, tối đa 20 kết quả, không block UI.
- GPS pipeline: validate sample → smoothing → bearing filter → snap-to-route theo distance/direction/confidence.
- Navigation state: `IDLE → SEARCHING → DESTINATION_PREVIEW → ROUTE_CALCULATING → ROUTE_PREVIEW → NAVIGATING → REROUTING → ARRIVED`.
- Không reroute từ một GPS spike; cần nhiều sample xác nhận và cooldown.
- TTS tiếng Việt, duck music khi đọc chỉ dẫn rồi restore.
- Traffic online chỉ là provider tùy chọn; offline map/navigation phải chạy khi mất mạng.

## 7. Music thật
- Scan incremental MediaStore và USB/SD; không scan toàn bộ mỗi lần boot.
- Hỗ trợ MP3, AAC/M4A, OGG, WAV; FLAC chỉ bật sau codec test.
- Có track/album/artist/folder/playlist/favorite/recent/queue và checkpoint.
- Play/pause/next/previous/seek, shuffle, repeat, resume vị trí.
- Music tiếp tục khi chuyển màn hình, mở map hoặc Activity recreate.
- USB bị tháo khi phát phải pause an toàn, không crash.
- Audio policy: Bluetooth call pause; navigation TTS duck; chọn radio pause/abandon music; restore có kiểm soát.
- Tạo `VehicleAudioBridge`; trước mắt dùng Android audio API thật, giữ boundary để thêm `SyuAudioBridge` sau khi reverse firmware.
- Online music chỉ qua Internet radio, podcast RSS, Jellyfin/Navidrome hoặc provider chính thức hợp lệ. YouTube chỉ dùng official player/integration nếu API 23 còn tương thích.

## 8. Kiến trúc
Tách rõ: `launcher-ui`, `map-api`, `map-engine-maplibre`, `place-search`, `routing-engine-offline`, `location-engine`, `navigation-core`, `music-api`, `music-engine-local`, `music-provider-online`, `vehicle-audio-bridge`, `diagnostics`, và PC tools tạo map/search/routing package.
Không đặt engine/service trong Activity. Không dùng dữ liệu demo để giả lập production result.

## 9. Thứ tự triển khai
1. Audit source, hai MD và design; lập gap report ngắn.
2. Khóa Gradle/toolchain/minSdk 23 và build baseline.
3. Implement launcher shell + 7 màn hình + bottom navigation.
4. Tạo map pack Đà Nẵng và tích hợp MapLibre offline thật.
5. Implement SQLite place search và GraphHopper route thật.
6. Implement GPS navigation, snap/reroute/TTS.
7. Implement local/USB music service và audio focus.
8. Tích hợp Map + Music vào Home, không duplicate state.
9. Build, test, profiling, repair và soak test.

## 10. Acceptance gate
- APK build/install được với API 23; không fatal lint/compile error.
- Không có menu trái; 7 màn hình bám design và scale được tối thiểu 1024x600.
- Map Đà Nẵng chạy offline thật, search thật, route thật và GPS navigation thật.
- Tắt mạng vẫn map/search/route được trong vùng đã cài.
- Music local/USB phát background thật, MediaSession hoạt động, tháo USB không crash.
- TTS duck/restore music đúng; không regression Phone/Radio/Camera/CANBUS do launcher gây ra.
- App Drawer mở app thật; thiếu app/permission/capability được xử lý graceful.
- Không thay default launcher tự động, không disable launcher gốc; có ADB recovery instruction.
- Test navigation + music liên tục ít nhất 2 giờ nếu có thiết bị; nếu chưa có thiết bị, chạy emulator/API 23 và ghi rõ phần bắt buộc test trên xe.

## 11. Output bắt buộc
- Source hoàn chỉnh và APK debug.
- `docs/ARCHITECTURE.md`
- `docs/UI_IMPLEMENTATION.md`
- `docs/MAP_DATA_PIPELINE.md`
- `docs/OFFLINE_SEARCH_ROUTING.md`
- `docs/NAVIGATION_STATE_MACHINE.md`
- `docs/MUSIC_AUDIO_INTEGRATION.md`
- `docs/ANDROID_6_COMPATIBILITY.md`
- `docs/TEST_REPORT.md`
- `docs/KNOWN_LIMITATIONS.md`
- `docs/ADB_INSTALL_AND_RECOVERY.md`
- `docs/THIRD_PARTY_LICENSES.md`

# 12. NGUỒN THAM KHẢO KỸ THUẬT

- MapLibre Native Android: https://maplibre.org/maplibre-native/android/api/
- MapLibre Android examples: https://maplibre.org/maplibre-native/android/examples/
- OpenStreetMap Vietnam extract: https://download.geofabrik.de/asia/vietnam.html
- OpenMapTiles: https://openmaptiles.org/
- Valhalla: https://valhalla.github.io/valhalla/
- Nominatim: https://nominatim.org/
- YouTube API Terms: https://developers.google.com/youtube/terms/api-services-terms-of-service
- YouTube Developer Policies: https://developers.google.com/youtube/terms/developer-policies

## 13. Báo cáo cuối
`Status: PASS | PARTIAL | BLOCKED | FAIL`; nêu Implemented, Evidence, Files changed, Build/tests, Performance, Android 6 compatibility, Known limitations, Risks và Next step.
Không chuyển sang chỉnh firmware. Nếu gate chưa đạt, tiếp tục điều tra, repair và verify trong cùng campaign.
