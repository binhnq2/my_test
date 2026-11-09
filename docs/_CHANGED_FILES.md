# Changed files — running manifest

This repository is **not under git**, so this file is the only record of what was
added or modified. Each block lists files to copy, newest block first.

Format: `action` · path · one-line reason. Paths are relative to the repository
root (`D:\Tools\Car_Launcher`).

---

## 2026-11-02 — voice offline (Vosk) cho TS8, trọn batch

Batch theo `docs/TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`. Toàn bộ batch đã xong
ở mức sandbox: module nhận dạng, hai provider, router, model pack trên đĩa, màn
hình quản lý model, diagnostics, sáu tài liệu §15 và báo cáo cuối.

Kết quả cuối: **512 test / 0 failure / 0 skip** cho mỗi variant `debug` và
`release`; APK debug **49.799.534 byte**, sha256
`17d1fb18d49565deaf4992f865763de9a00b7229d64fa194e70627b5062a2710`.
Trạng thái: **`SANDBOX_PASS_DEVICE_PENDING`** — chưa có mục nào là kết quả đo
trên TS8 thật, và không tài liệu nào trong batch tuyên bố ngược lại.

### Build script

`modified` · `settings.gradle.kts` · thêm `include(":core:voice-offline")`
`added` · `core/voice-offline/build.gradle.kts` · module Android library qua convention plugin `ts8.core-module`
`modified` · `app/build.gradle.kts` · `implementation(project(":core:voice-offline"))`; ghim `com.alphacephei:vosk-android:0.3.47` (không dùng range: nâng version là đổi ABI native APK ship và đổi hợp đồng JNI); `ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }` — bỏ `x86`/`x86_64`/`armeabi`/`mips`/`mips64` của `libmaplibre.so` và `libjnidispatch.so`, APK 96.469.038 → 49.799.534 byte, và cũng là thứ làm `VoiceModelManifest.ACCEPTED_ABIS` thành lời khai đúng
`modified` · `app/proguard-rules.pro` · thêm mục `# ---- Vosk`: keep `org.vosk.**` và `com.sun.jna.**`, `-dontwarn`, keep `* implements com.sun.jna.TypeMapper` / `* extends com.sun.jna.Structure` / `* extends com.sun.jna.CallbackReference`. **Đã kiểm bằng phép so sánh**: bỏ khối này rồi build release thì `org.vosk.*` type trong dex còn 20 → 2 và `org.vosk.Recognizer` bị đổi tên thành `q2.a`; khôi phục lại thì APK release ra đúng sha256 ban đầu

### `:core:voice-offline` — main

`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/MiniJson.kt` · đọc JSON không phụ thuộc thư viện (vì `org.json` là stub trên JVM); accessor ở file-level để module khác dùng được
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/VoiceModelManifest.kt` · `metadata.json` + `enum VoiceModelState` sáu trạng thái, kiểm schema/engine/locale/ABI/minSdk/sha256
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/VoiceModelValidator.kt` · kiểm pack theo thứ tự rẻ-trước; `payloadBytes()` tách khỏi `totalBytes()` vì size không thể tính cả chính `metadata.json`/`checksums.sha256`
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/VoiceModelStore.kt` · layout `<root>/vi-VN/<version>/`, `active.txt` nguyên tử, staging rồi activate, chặn Zip-Slip, từ chối gỡ khi chưa xác nhận
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/RecognitionProvider.kt` · ranh giới provider, `RecognitionRequest`, `RecognitionCallback`, `RecognitionPolicy`, bảng `allowsOfflineFallback()`
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/RecognitionProviderRouter.kt` · chọn provider theo policy, **tối đa một lần chuyển mỗi request**, bỏ lỗi đến muộn từ provider đã bị bỏ; phát `RouteSummary` (policy/switches/fallbackTrail) vào đúng bản ghi diagnostics của request đó, khớp theo `providerId`
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/VoiceDiagnostics.kt` · bản ghi một lượt voice; không có âm thanh, không có toạ độ
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/CommandGrammar.kt` · sinh grammar Vosk từ đúng bảng của `IntentParser`; `SelfCheck` chứng minh mọi câu sinh ra đều parse ngược được; cap POI
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/VadGate.kt` · cắt utterance bằng năng lượng, floor thích nghi, `minSpeechMs`, trần thời gian
`added` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/VoskResult.kt` · đọc hai shape JSON của Vosk; `text` rỗng là `NO_MATCH`; confidence null khi engine không báo

### `:core:voice-offline` — test (118)

`added` · `core/voice-offline/src/test/java/com/ts8/core/voice/offline/MiniJsonTest.kt` · 10 test
`added` · `core/voice-offline/src/test/java/com/ts8/core/voice/offline/VoiceModelValidatorTest.kt` · 21 test, fixture theo đúng layout Kaldi
`added` · `core/voice-offline/src/test/java/com/ts8/core/voice/offline/VoiceModelStoreTest.kt` · 19 test: cài/`active.txt` nguyên tử, trượt validate giữ pack cũ, Zip-Slip bị chặn
`added` · `core/voice-offline/src/test/java/com/ts8/core/voice/offline/RecognitionProviderRouterTest.kt` · 26 test: bảng fallback cho cả 12 giá trị `VoiceError`, ma trận policy, trần một lần chuyển, lỗi đến muộn, provider throw/refuse, và 4 test rằng bản ghi diagnostics mang đúng policy/switches/trail của request tương ứng
`added` · `core/voice-offline/src/test/java/com/ts8/core/voice/offline/CommandGrammarTest.kt` · 15 test, gồm `SelfCheck`, tính ổn định của grammar và cap POI
`added` · `core/voice-offline/src/test/java/com/ts8/core/voice/offline/VadGateTest.kt` · 12 test
`added` · `core/voice-offline/src/test/java/com/ts8/core/voice/offline/VoskResultTest.kt` · 11 test
`added` · `core/voice-offline/src/test/java/com/ts8/core/voice/offline/VoiceModelPackIntegrationTest.kt` · 4 test **chạy validator của sản phẩm trên gói thật** ở `.work/voicepack/vi-VN/vosk-model-small-vn-0.4` (đổi được bằng `TS8_VOICE_PACK_DIR`); `assumeTrue` nên checkout không có gói sẽ **skip**, không pass im lặng

`:core:voice-offline:testDebugUnitTest` — **118 test, 0 lỗi, 0 skip**.

### Hai provider Android + controller

`modified` · `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` · thêm `isUsable` và đường trả lỗi chi tiết; tách `SERVICE_UNAVAILABLE` đúng định nghĩa của brief
`added` · `app/src/main/java/com/ts8/launcher/runtime/AndroidSystemRecognitionProvider.kt` · adapter đặt sau seam `RecognitionProvider`; `id`, `isUsable`, đổi `List<String>` → `(hypotheses, confidence)`; mọi lệnh hạ xuống main looper để tính chất "chỉ main thread" là của lớp này chứ không phải hợp đồng người gọi phải nhớ
`added` · `app/src/main/java/com/ts8/launcher/runtime/VoskOfflineRecognitionProvider.kt` · `AudioRecord` 16 kHz mono PCM 16-bit nguồn `VOICE_RECOGNITION`, `VadGate`, inference trên executor riêng, nạp model lazy, giải phóng sau `DEFAULT_UNLOAD_IDLE_MS = 120_000`; mọi nhánh (thành công, im lặng, trần thời gian, Back, mất quyền, thiết bị lỗi) đi qua một `finally` nhả micro
`modified` · `app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt` · nhận `RecognitionProviderRouter`, thêm phiên **nghe câu trả lời** cho confirmation (`maybeListenForAnswer`, `router.answer()`, `router.rearm()`, `MAX_ANSWER_ATTEMPTS = 2`), chặn partial theo debounce, chuyển một phần thông tin chẩn đoán
`modified` · `app/src/main/java/com/ts8/launcher/di/AppGraph.kt` · `voiceModelStore`, `recognitionRouter`, `onVoiceModelChanged()`, grammar từ `places.db` của pack đang active, `COMMAND_GRAMMAR_POI_CAP`

### Điều khiển lệnh (navigation-core)

`modified` · `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceState.kt` · `VoiceError` 7 → 12: thêm `NETWORK_ERROR`, `LANGUAGE_UNAVAILABLE`, `MIC_BUSY`, `CANCELLED`, `INTERNAL_ERROR`; siết lại doc của `SERVICE_UNAVAILABLE`; `VoiceStateSnapshot.transcript`
`modified` · `core/navigation-core/src/main/java/com/ts8/core/voice/VietnameseVoicePrompts.kt` · `forError` phủ đủ 12 lỗi, mỗi lỗi một câu nói khác nhau
`modified` · `core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt` · `answer(transcript)` và `rearm()` cho §9 (trả lời bằng giọng), `requireConfirmation` mặc định `false`, transcript vào snapshot
`added` · `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceAnswer.kt` · parser ĐỒNG Ý/KHÔNG riêng, khớp **nguyên câu sau fold** (không phải prefix), danh sách phủ định kiểm trước; `dung`/`đúng` cố ý bị loại vì fold trùng `dừng`
`added` · `core/navigation-core/src/test/java/com/ts8/core/voice/VoiceAnswerTest.kt` · test cho bảng câu trả lời (navigation-core: **55** test)

### Tra cứu POI cho grammar

`modified` · `core/place-search/src/main/java/com/ts8/core/search/PlaceSearch.kt` · thêm `topPlaces(limit)` với default `emptyList()` — backend không có index xếp hạng vẫn hợp lệ, grammar chỉ còn câu lệnh cố định
`modified` · `core/place-search/src/main/java/com/ts8/core/search/SqlitePlaceSearch.kt` · `topPlaces` đọc `category = 'poi'`, tên không rỗng, `DISTINCT` theo tên, xếp theo `importance` — cùng thứ hạng mà bản đồ dùng để chọn nhãn

### Màn hình cài đặt / quản lý model / chẩn đoán

`added` · `app/src/main/java/com/ts8/launcher/ui/VoiceModelActivity.kt` · Voice Model Management: sáu trạng thái lấy từ `VoiceModelStore.snapshot()` (Activity không tự quyết "trông có vẻ ổn"), cài từ `.zip`, quét bộ nhớ trong + USB, cài lại, gỡ có xác nhận
`added` · `app/src/main/res/layout/activity_voice_model.xml` · layout của màn hình trên
`modified` · `app/src/main/java/com/ts8/launcher/ui/SettingsActivity.kt` · mục **Giọng nói**: policy ba lựa chọn, trạng thái model, CTA `Cài mô hình giọng nói offline` khi chưa có
`modified` · `app/src/main/res/layout/activity_settings.xml` · khối Giọng nói
`modified` · `app/src/main/java/com/ts8/launcher/ui/VoiceOverlayView.kt` · 3 nhánh `errorTitle`; nút "thử lại" nhận thêm `NETWORK_ERROR`/`MIC_BUSY`/`INTERNAL_ERROR`; hiển thị `Bạn nói: "…"`
`modified` · `app/src/main/java/com/ts8/launcher/runtime/DiagnosticsReport.kt` · `voiceDetails`: mỗi dòng `voice.key=value`
`modified` · `app/src/main/java/com/ts8/launcher/ui/DiagnosticsActivity.kt` · dòng nhận dạng ghi cả policy, provider và trạng thái model; xuất `voiceDetails`
`modified` · `app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt` · `recognitionPolicy` (`AUTO`/`OFFLINE_ONLY`/`SYSTEM_ONLY`)
`modified` · `app/src/main/res/values/strings.xml` · 40 chuỗi: policy, sáu trạng thái model, toàn bộ nhãn màn hình quản lý, `voice_error_language`/`voice_error_mic_busy`/`voice_error_cancelled`, ba dòng diagnostics
`modified` · `app/src/main/AndroidManifest.xml` · khai `VoiceModelActivity` (không export, landscape, singleTop); `<queries>` cho `android.speech.RecognitionService`

### Model pack (công cụ + artefact)

`added` · `tools/voicepack/build_voice_pack.py` · đóng gói Kaldi dir + `metadata.json` + `command_grammar.json` + `checksums.sha256` + `LICENSES.md`; `size`/`sha256` tính trên payload, không tính chính hai tệp mô tả
`added` · `tools/voicepack/command_grammar.json` · 16 câu cố định
`added` · `.work/voicepack/vi-VN/vosk-model-small-vn-0.4/` (18 tệp) · gói đã build: payload **53.293.024 byte**, tổng 53.295.073 byte, digest `checksums.sha256` = `7e09b1c829ad96c07818fd51be8fd878ef108f107017468f6b4945249f7083fb`; **không** nằm trong APK

### Tài liệu (§15)

`added` · `docs/VOICE_OFFLINE_ARCHITECTURE.md` · kiến trúc, đường đi của một request, bảng fallback đủ 10 dòng, vì sao inference ở thread riêng, lifecycle `AudioRecord`, §8 partial, §9 confirmation, §10 TTS, §11 diagnostics
`added` · `docs/VOICE_MODEL_PACK.md` · layout pack, 12 trường `metadata.json`, thứ tự 10 bước validate, cài ZIP/USB, 7 bước activate nguyên tử, sáu trạng thái
`added` · `docs/VOICE_MODEL_LICENSE.md` · bằng chứng license từng mục của §5, gồm phát hiện **ZIP không kèm tệp license nào** (14 tệp đã kiểm) và việc license chỉ được công bố trên trang models
`added` · `docs/VOICE_OFFLINE_SANDBOX_TEST.md` · môi trường, APK, 512 test, 118 test voice-offline, 4 integration test, §7 bản release có minify (đã build + đối chứng keep-rule Vosk/JNA), mục "những gì test này KHÔNG chứng minh", khoảng trống test đã biết
`added` · `docs/VOICE_OFFLINE_DEVICE_CHECKLIST.md` · 10 mục cho người chạy trên TS8, gồm 15 câu lệnh × 3 lần, 5 POI Đà Nẵng, bảng 7 ca lỗi bắt buộc, danh sách đúng các key `voice.*`
`added` · `docs/VOICE_OFFLINE_KNOWN_LIMITATIONS.md` · 16 giới hạn thật — nổi bật: chưa có số đo nào trên TS8, không có bằng chứng độ chính xác trong cabin, 3/4 điều kiện xác nhận của §9 chưa có đường kích hoạt, chưa có hook `onTrimMemory`; §13 ghi bản release đã build được + bảng đối chứng keep-rule, nhưng chưa chạy trên thiết bị
`added` · `docs/VOICE_OFFLINE_FINAL_REPORT.md` · báo cáo cuối của §15: root cause (`SpeechRecognizer` không có service để bind, nên không phải lỗi quyền/cấu hình), quyết định engine + model và vì sao loại Sherpa-ONNX/Whisper.cpp, kiến trúc + bốn ràng buộc, 512 test, hai APK kèm sha256, bảy mục bằng chứng license, phần hiệu năng **đã đảm bảo bằng cấu trúc** tách khỏi phần **chưa đo**, ba khoảng trống (không phải blocker), checklist thiết bị. Trạng thái `SANDBOX_PASS_DEVICE_PENDING`
`modified` · `tools/transfer/gen_transfer_full.py` · `KNOWN_GOOD_SHA`/`KNOWN_GOOD_SIZE` sang APK 2026-11-02 (`17d1fb18…` / 49.799.534)
`modified` · `docs/TRANSFER.md` · cập nhật 345 file / 340 text / 5 binary, APK known-good mới, và ghi rõ model pack 53 MB **không** đi trong payload
`modified` · `app/proguard-rules.pro` · sửa chú thích đã lạc hậu: khối đầu nói "these rules have not been exercised by a release build" và khối Vosk nói "none of this has been exercised by a release build yet" — cả hai đã bị phép đo ở `VOICE_OFFLINE_SANDBOX_TEST.md` §7 bác bỏ, nay ghi đúng là bản release đã build trên host này, chỉ còn thiếu lần chạy trên thiết bị
`modified` · `docs/VOICE_OFFLINE_DEVICE_CHECKLIST.md` · mục 8: danh sách `voice.*` từ 17 → **21 dòng**, đúng bằng những gì `VoiceDiagnostics.toLines()` phát ra (`model.size`, `model.sha256`, `model.abi`, `model.warm` trước đây bị thiếu); ghi rõ dòng không có giá trị sẽ **không xuất hiện**
`modified` · `core/voice-offline/src/main/java/com/ts8/core/voice/offline/CommandGrammar.kt` · sửa hai chú thích nói sai: (a) "grammar is derived and not written out" — sai, `FIXED` viết tay, chỉ *nghĩa* của nó là do `IntentParser` quyết định; (b) "SelfCheck fails the build" — sai, nó không tự chạy, `CommandGrammarTest` mới là chỗ assert. Giữ nguyên số dòng để APK không đổi hash
`modified` · `transfer_full/2-Create-Sources.ps1` + `2a`–`2f-Write-Tree-*.ps1` · sinh lại: 345 file, ~8,3 MB trong 7 tệp; đã chạy thật vào thư mục trống rồi so **byte-for-byte** với cây nguồn bằng script độc lập (không dùng bảng hash của chính generator): **345/345, 0 thiếu, 0 thừa, 0 sai byte**

---

## 2026-11-01 (later) — ba điểm sau khi launcher đã chạy được trên TS8

Ba lỗi người dùng báo sau khi cài bản chạy được: nút cài đặt bản đồ biến mất khi
map đã load; voice search luôn hỏi xác nhận sau khi nói; màn hình parking không
hoạt động.

**Điểm 1 — nút cài đặt bản đồ.** `MapPhaseSurface.render` đặt `root.visibility
= View.GONE` ngay khi `snapshot.isReady`, và nút `Cài đặt bản đồ` duy nhất nằm
trong cây đó. Nên trên một bản đồ đang chạy tốt — trường hợp bình thường — không
còn đường nào vào quản lý pack. Thêm `btnMapSettings` vào chrome duyệt bản đồ
(`browseControls` của `activity_map`, cột điều khiển phải của `activity_home`),
nối tới `MapManagementActivity.start(...)`, có Toast khi mở thất bại.

**Điểm 2 — modal xác nhận voice.** `VoiceRouter.Config.requireConfirmation` mặc
định `true` và `VoiceInstantController` không override, nên `NavigateTo` và
`PlayMusic` luôn dừng ở CONFIRMING với hai nút Có/Không. Transcript chỉ được log,
không hiển thị: `VoiceStateSnapshot` không có trường nào cho nó. Đổi mặc định
sang `false`, thêm `transcript` vào snapshot, ghi nhận trong `onTranscript`, và
hiển thị `Bạn nói: "…"` trong overlay. CONFIRMING được giữ nguyên nhưng chỉ còn
dùng cho trường hợp executor tự trả `NeedsConfirmation` (một tên, hai địa điểm)
— đó là disambiguation, không phải cổng an toàn.

**Điểm 3 — màn hình parking.** Hai nửa, và cả hai đều phải sửa:

(a) Màn hình không có đường ra map/music — thêm `btnParkingMap` +
`btnParkingMusic`, launch đi qua file mới `ParkingDestinations.kt` để
`ParkingMusicContractTest` (cấm chuỗi `startActivity`/`startService` trong
`ParkingModeActivity.kt`) vẫn còn hiệu lực.

(b) Nó gần như không bao giờ tự hiện, vì hai nguyên nhân độc lập:
`parkingAutoEnter` mặc định `false` và **không có UI nào ghi được nó** (Settings
chỉ có nhãn tĩnh "Chưa khả dụng"); và tick 1 Hz của `ParkingRuntime` chỉ chạy khi
Home hoặc chính Parking đang started — nên khi đang lái với map mở, `refCount`
bằng 0, xe dừng lại mà không ai biết. Đổi mặc định sang `true` (resolver vẫn tự
từ chối theo dwell 20 s / stability verdict / blocker cuộc gọi–lùi xe–dẫn đường;
tốc độ 0 một mình chưa bao giờ đủ), thay nhãn tĩnh bằng hai chip
`parkingAutoOff`/`parkingAutoOn`, và cho `BaseScreenActivity` giữ detector trên
mọi màn hình — trừ `ScreenOffActivity`, vì nếu không thì đồng hồ đỗ xe sẽ mở đè
lên chính màn hình đen mà người lái vừa yêu cầu.

**Kết quả build/test.** 64 suite / 772 test / 0 failure / 0 skipped. APK
`app-debug.apk` 58,688,307 byte, sha256
`d1361ae187b467a3bfc2af00141363c684c0a7843c3e0555ccd3ec743052a353`, ký v1+v2
hợp lệ (`apksigner verify`).

`added` · `app/src/main/java/com/ts8/launcher/ui/ParkingDestinations.kt` · mở Map/Music từ Parking, trả `false` thay vì ném khi không mở được; đặt ở file riêng để lệnh cấm của `ParkingMusicContractTest` vẫn có nghĩa
`added` · `app/src/test/java/com/ts8/launcher/ui/ParkingEntryTest.kt` · 8 test chặn tái diễn cho cả ba điểm: detector ở `BaseScreenActivity`, `ScreenOffActivity` opt-out, `parkingAutoEnter` mặc định true, hai chip Settings, hai nút parking, helper launch, `btnMapSettings` ở cả hai layout và không nằm trong `mapPhase`
`modified` · `app/src/main/res/layout/activity_map.xml` · thêm `btnMapSettings` vào `browseControls` (con đầu tiên, trước `btnRecenter`)
`modified` · `app/src/main/res/layout/activity_home.xml` · thêm `btnMapSettings` sau `btnLayers` trong cột điều khiển phải
`modified` · `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt` · wire `btnMapSettings` → `openMapManagement()`, Toast `map_management_unavailable` khi thất bại
`modified` · `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt` · wire `btnMapSettings` → `MapManagementActivity.start(this)` + Toast
`modified` · `app/src/main/res/layout/view_voice_overlay.xml` · thêm `voiceHeard` (giữa `voiceTitle` và `voiceCaption`), mặc định `gone`
`modified` · `app/src/main/java/com/ts8/launcher/ui/VoiceOverlayView.kt` · render `Bạn nói: "…"` từ `VoiceStateSnapshot.transcript`; ẩn khi null/blank
`modified` · `core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt` · `Config.requireConfirmation` mặc định `false`; thêm trường `transcript`, ghi trong `onTranscript` trước khi parse, xoá trong `onListeningStarted`; nhánh `NeedsConfirmation` chỉ từ chối lần hỏi **thứ hai**
`modified` · `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceState.kt` · `VoiceStateSnapshot` thêm trường thứ năm `transcript: String? = null`; KDoc CONFIRMING nói rõ đây không còn là đường thường ngày
`modified` · `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceStateStore.kt` · KDoc: transcript không được persist (codec không có key), snapshot sau restore luôn có `transcript = null`
`modified` · `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceScenarios.kt` · `checkDestructiveRunsWithoutPrompt`, `checkTranscriptIsQuoted`, `checkExecutorDisambiguationStillAsks`, `checkNonDestructiveRunsImmediately`; `checkTimeout` viết lại để vẫn chạm được CONFIRMING qua disambiguation
`modified` · `app/src/main/java/com/ts8/launcher/runtime/VoiceCommandExecutor.kt` · KDoc: router chạy lệnh phá huỷ ngay từ transcript, không còn chờ xác nhận
`modified` · `app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt` · KDoc `confirm()`: chỉ còn dùng cho disambiguation do executor yêu cầu
`modified` · `app/src/main/res/layout/activity_parking_mode.xml` · thêm hàng `btnParkingMap` (`Button.Primary`) + `btnParkingMusic` (`Button.Ghost`) trong `parkingPane`
`modified` · `app/src/main/java/com/ts8/launcher/ui/ParkingModeActivity.kt` · wire hai nút qua `ParkingDestinations`; `showUnavailable()`; viết lại comment để không còn chứa chuỗi bị cấm
`modified` · `app/src/main/res/layout/activity_settings.xml` · thay nhãn tĩnh `parkingAutoState` bằng hai chip `parkingAutoOff`/`parkingAutoOn`
`modified` · `app/src/main/java/com/ts8/launcher/ui/SettingsActivity.kt` · thêm `renderParkingAuto()` + `styleParkingChip()`, gọi cuối `onCreate`; cập nhật KDoc
`modified` · `app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt` · `parkingAutoEnter` mặc định `false` → `true`, KDoc giải thích vì sao và cổng nào vẫn giữ nó trung thực
`modified` · `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt` · `onStart`/`onStop` giữ `parking.acquire(null)`/`release(null)`; thêm `runsParkingDetector` (mặc định true)
`modified` · `app/src/main/java/com/ts8/launcher/ui/ScreenOffActivity.kt` · `runsParkingDetector = false` để đồng hồ đỗ xe không mở đè màn hình đen
`modified` · `app/src/main/res/values/strings.xml` · thêm `parking_choice_map`, `parking_choice_music`, `screen_unavailable`, `voice_heard`; sửa `parking_auto_hint` cho khớp điều kiện thật; xoá `parking_auto_unavailable` (không còn dùng)
`added` · `tools/transfer/gen_fix_20261101b.py` · sinh `transfer_full/Fix-2026-11-01b.ps1` — đúng 21 file của vòng này, không có test
`added` · `transfer_full/Fix-2026-11-01b.ps1` · 400,204 byte; UTF-8 BOM, CRLF; ghi 21 file rồi tự verify sha256; đã chạy thử vào thư mục trống → 21/21 khớp **byte-for-byte** với cây nguồn, không BOM ở file đích, `-VerifyOnly` hoạt động

---

## 2026-11-01 — device boot failure + MAP_FIRST_FRAME_TIMEOUT investigation

`added` · `tools/transfer/gen_fix_20261101.py` · sinh `transfer_full/Fix-2026-11-01.ps1` — chỉ 4 file đã sửa, không có test, để vá một cây đã copy sẵn
`added` · `transfer_full/Fix-2026-11-01.ps1` · 62,319 byte; UTF-8 BOM, CRLF; ghi đè đúng 4 file rồi tự verify sha256; đã chạy thử vào thư mục trống → 4/4 file khớp **byte-for-byte** với cây nguồn (so bằng `cmp`, không dùng bảng hash của chính script), không BOM ở file đích, `-VerifyOnly` hoạt động

**Root cause 3 — `MAP_FIRST_FRAME_TIMEOUT` không thể xảy ra vì màn hình đã chết
trước đó.** `view_map_phase.xml` có root là `<merge>`, nhưng cả `activity_home`
lẫn `activity_map` đều `<include android:id="@+id/mapPhase" .../>` nó rồi gọi
`findViewById(R.id.mapPhase)`. `LayoutInflater.parseInclude` có một nhánh riêng
cho root `<merge>`: gọi `rInflate` rồi nhảy thẳng tới `close()`, **không bao giờ**
chạy tới đoạn áp `android:id` của thẻ `<include>` (`setId`), `LayoutParams` hay
visibility. `<merge>` bị bỏ và các con được gắn thẳng vào cha ⇒ **không view nào**
trong cây kết quả mang `R.id.mapPhase` ⇒ `findViewById` trả `null` ⇒
`MapPhaseSurface.root` là tham số non-null của Kotlin ⇒ NPE trong `onCreate`,
trên **mọi** API level.

Bằng chứng: bytecode `LayoutInflater` API 23 (`layoutlib.jar` của platform
android-23): offset 321 `"merge".equals(name)` → `rInflate` → `goto 520`, nhảy
qua `setId` ở offset 448. Vẫn giữ nguyên trong AOSP hiện tại. Đã đối chiếu:
`aapt2 dump xmltree` trên APK cũ cho thấy `id mapPhase` **chỉ** có ở `activity_home`
và `activity_map`, **không** có trong `view_map_phase` — tức không có view nào
thực sự nhận id đó.

**Vì sao 673 test cũ không bắt được.** Toàn bộ suite là JVM thuần, không inflate
layout. `StatusBarAbsenceTest` có đọc `res/layout` nhưng chỉ tìm thẻ bị cấm;
không có test nào đối chiếu id trên `<include>` với root của layout đích.

`modified` · `app/src/main/res/layout/view_map_phase.xml` · root `<merge>` → `<FrameLayout>` (match_parent × match_parent); thêm KDoc giải thích vì sao `<merge>` làm mất id của `<include>`
`added` · `app/src/test/java/com/ts8/launcher/ui/IncludedLayoutIdTest.kt` · 2 test chặn tái diễn: không `<include>` nào mang `android:id` trỏ tới layout root `<merge>`; và khẳng định hai màn hình bản đồ thật sự dựa vào id đó
`modified` · `tools/transfer/gen_transfer_full.py` · `KNOWN_GOOD_SHA`/`SIZE` cập nhật sang APK 2026-11-01 (bản cũ `b0a26b62…` là build chết trong `setContentView`)

**Root cause 1 — launcher không khởi động được trên TS8 (Android 6 / API 23).**
`CompactTopBar` (file MỚI, không có trong bản chạy được) gọi
`View.requireViewById` — API 28. D8 retarget tham chiếu sang chính lớp con
(`Lcom/ts8/launcher/ui/CompactTopBar;.requireViewById`), nên công cụ audit cũ
chỉ kiểm tra owner bắt đầu bằng `android/` đã bỏ sót. View này được inflate từ
`setContentView(R.layout.activity_home)` → ném `NoSuchMethodError` trước khung
hình đầu tiên. Đã thay bằng `findViewById` + kiểm tra null.

**Root cause 2 — `MAP_FIRST_FRAME_TIMEOUT`.** Pha tên là "chờ khung hình đầu
tiên" nhưng được huỷ bởi `onDidFinishLoadingMap` — sự kiện "nạp xong", không
phải "đã vẽ". Trên pack phủ tile không đầy đủ, sự kiện đó có thể không bao giờ
tới. Đã thêm `addOnDidFinishRenderingFrameListener`.

`modified` · `app/src/main/java/com/ts8/launcher/ui/CompactTopBar.kt` · thay `requireViewById` (API 28) bằng `findViewById` + helper `require()`; thêm import `android.view.View`
`modified` · `app/src/main/java/com/ts8/launcher/runtime/MapHost.kt` · thêm `addOnDidFinishRenderingFrameListener` làm tín hiệu huỷ timeout; giữ `onDidFinishLoadingMap` làm tín hiệu sớm hơn
`modified` · `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackValidator.kt` · `readHead` dùng `readNBytes` (Java 9, desugar 2.0.4 không backport) → đọc bằng vòng lặp `read()`; lỗi trước đây bị `runCatching` nuốt nên mọi pack hợp lệ đều bị báo `ROUTING_GRAPH_INCOMPATIBLE` trên API 23
`added` · `.work/apicheck_inherited.py` · công cụ audit bắt đúng lớp lỗi "method thừa hưởng gọi qua lớp con của app" mà 4 công cụ trước bỏ sót
`added` · `.work/extract_old_tree.py` · trích bộ `transfer/` (bản chạy được 30/10) ra `.work/oldtree/` để diff; xác minh 276/276 file khớp SHA-256 baseline
`added` · `.work/oldtree/` (276 files) · cây nguồn của bản CÀI ĐƯỢC trên TS8, dùng làm baseline so sánh
`added` · `.work/dex11/` + `.work/dex11dump/` · 11 dex của APK hiện tại và `dexdump -d` tương ứng
`added` · `.work/ml12/` · `classes.jar` + disassembly MapLibre 12.0.0 dùng để truy vết chuỗi callback
`added` · `.work/verify3/` · 11 dex của APK sạch cuối cùng + dexdump, dùng để xác minh hai bản sửa có mặt trong bytecode
`added` · `.work/mlso/` · `libmaplibre.so` (armeabi-v7a) dùng đối chiếu symbol native

APK sạch sau khi sửa: **58,737,433 byte**, sha256
`8ff9a9f44925ccfb18ab24a046a4db84b770f938c531aff5b21e86b988bf135c`,
11 dex. Bytecode đã xác minh: `CompactTopBar.requireViewById` = 0 lần,
`FileInputStream.readNBytes` = 0 lần, `addOnDidFinishRenderingFrameListener`
có mặt trong `MapHost`. `aapt2 dump xmltree` xác nhận root `view_map_phase.xml`
trong APK là `FrameLayout`.

Test: **62 suite / 756 test / 0 lỗi / 0 skip**. Riêng `IncludedLayoutIdTest`
đã được kiểm chứng ngược — tạm đổi root về `<merge>` thì test **fail** đúng
thông báo, khôi phục `FrameLayout` thì **pass**, nên nó thật sự bắt được lỗi
chứ không phải test rỗng.

Payload `transfer_full/` đã tạo lại: **316 file / 7,840,729 byte**, và đã
chạy driver thật rồi so byte-for-byte với cây nguồn: **316/316, 0 thiếu,
0 thừa, 0 sai byte**.

---

## 2026-10-31 — transfer payload, several rounds

`modified` · `tools/transfer/gen_transfer_full.py` · added `INCLUDE_TESTS` and `INCLUDE_TESTDATA` switches; payload went 343 → 316 → 315 files
`modified` · `docs/TRANSFER.md` · rewritten for the 7-file set, then again when the tests and the map fixture were dropped
`modified` · `docs/KNOWN_LIMITATIONS.md` · §F records both omissions and the 315/315 end-to-end evidence
`modified` · `docs/TEST_REPORT.md` · §6.1 marks the old 5-file set superseded
`modified` · `docs/FINAL_REPORT.md` · transfer row updated
`modified` · `.work/verify_transfer_full.py` · added fixture-exclusion to the independent byte comparison; takes the output directory as an argument
`added` · `transfer_full/*.ps1` (7 files) · regenerated 3 times; final set is 315 files / 7,828,792 bytes
`added` · `transfer_full/README.md` · Vietnamese instructions, including how to re-enable the two switches

`modified` · `app/src/main/res/layout/activity_parking_mode.xml` · added the compact music card
`modified` · `app/src/main/java/com/ts8/launcher/ui/ParkingModeActivity.kt` · attaches `MusicRuntime`, mirrors `PlaybackState`
`added` · `app/src/test/java/com/ts8/launcher/ui/ParkingMusicContractTest.kt` · 3 structural tests for that card
`modified` · `docs/BATCH1_SANDBOX_TEST_REPORT.md` · APK hash `b0a26b62…`, 30 suites / 376 tests, §5 changed files
`modified` · `docs/BATCH1_UI_GAP_CLOSURE.md` · §9 compact music row closed, conditional
`modified` · `docs/BATCH1_RUNTIME_WIRING.md` · Parking → compact music card added to the wiring table
`modified` · `docs/BATCH1_ROOT_CAUSE_AND_CHANGES.md` · RC-9 counts eight new test files
`modified` · `docs/BATCH1_DEVICE_TEST_CHECKLIST.md` · step 7 covers the card
