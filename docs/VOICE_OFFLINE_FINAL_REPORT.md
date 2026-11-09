# Báo cáo cuối — Voice Offline (Vosk) cho TS8

Batch theo `docs/TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`. Mỗi mục dưới đây trỏ tới
một tệp, một con số đo được, hoặc một lệnh chạy lại được. Không có mục nào là kết
quả trên TS8 thật.

---

**Trạng thái: `SANDBOX_PASS_DEVICE_PENDING`**

Build và toàn bộ test chạy được trên host này đều xanh; APK debug và APK release
đều build được; gói model đã validate bằng validator của chính sản phẩm. **Chưa
có lần chạy nào trên thiết bị đích** — người dùng sẽ cài và kiểm theo checklist
§13. Trạng thái này không được nâng lên `DEVICE_PASS` bởi bất kỳ tài liệu nào
trong batch.

Lý do không phải `BLOCKED_MODEL_ASSET`: cả bảy mục bằng chứng của §5 đều có, xem
mục 6. Lý do không phải `SANDBOX_FAIL`: xem mục 4.

---

## 1. Root cause

Voice Instant chỉ có **một** đường để nghe người lái: `android.speech.
SpeechRecognizer`. Firmware của TS8 không ship `RecognitionService` nào, nên
`SpeechRecognizer.isRecognitionAvailable()` trả `false` và mọi lần bấm micro kết
thúc ở `Voice service unavailable`.

Không phải lỗi quyền, không phải lỗi cấu hình, không phải "file sai folder":
không có **service nào để bind tới**. Vì vậy:

- Không có bản sửa nào ở phía gọi — thêm retry, thêm cờ, thêm `startActivity` cho
  prompt của Google — làm feature chạy được.
- Cách duy nhất là có một bộ nhận dạng **không cần `RecognitionService`**, và
  cách duy nhất trên phần cứng này là đọc model Kaldi từ bộ nhớ trong.

Đây cũng là lý do batch này dựng `RecognitionProvider` trước khi dựng engine: nếu
chỉ "thêm Vosk", lần tới một ROM có Google app lại không dùng được đường platform.

## 2. Quyết định engine và model

| Câu hỏi | Trả lời | Vì sao |
|---|---|---|
| Engine | **Vosk Android** `com.alphacephei:vosk-android:0.3.47` | Là ưu tiên 1 của §3; có `.so` cho `armeabi-v7a`; chạy offline hoàn toàn; license Apache 2.0 |
| Sherpa-ONNX | **không dùng** | §3: chỉ dùng nếu Vosk thất bại. Vosk không thất bại trong batch này, và brief cấm nhúng hai engine production trong cùng APK |
| Whisper.cpp | **không dùng** | §3 cấm cho TS8 trong batch này |
| Model | `vosk-model-small-vn-0.4` | Tiếng Việt thật (3 bằng chứng độc lập), license Apache 2.0, phân phối lại được. ZIP tải về 33.656.337 byte; **đã giải nén** thành payload 53.293.024 byte — con số quyết định dung lượng đĩa trên TS8 là 53 MB, không phải 32 MB |
| Đóng gói | **ngoài APK** | §5: `voice-models/vi-VN/<version>/`, cài từ USB. APK không tăng vì model |

Version engine được **ghim chính xác**, không dùng range: nâng version là đổi ABI
native mà APK ship, và đổi luôn hợp đồng JNI mà `VoskOfflineRecognitionProvider`
dựa vào.

## 3. Kiến trúc và những gì đã làm

Chi tiết đầy đủ: [VOICE_OFFLINE_ARCHITECTURE.md](VOICE_OFFLINE_ARCHITECTURE.md).

```
VoiceTrigger → VoiceOverlayHost → VoiceInstantController
                                        │
                    ┌───────────────────┴───────────────────┐
        VoiceFeedback (TTS + duck)              RecognitionProviderRouter
        VoiceCommandExecutor                     ├─ AndroidSystemRecognitionProvider
                    │                            └─ VoskOfflineRecognitionProvider
                    ▼                                   ├─ VadGate
                 VoiceRouter                            ├─ VoskResult
                 ├─ IntentParser  (tái sử dụng)         └─ VoiceModelStore
                 └─ VoiceAnswer
```

Ba ràng buộc của brief và cách chúng được giữ:

| Ràng buộc | Cách giữ |
|---|---|
| "Giữ kiến trúc hiện có" | Không lớp nào của luồng cũ bị thay. `SpeechRecognizerAdapter`, `VoiceInstantController`, `CommandRouter`, `IntentParser`, `VoiceFeedback` vẫn là các lớp đó; cái được thêm là một seam `RecognitionProvider` **dưới** adapter, và `SpeechRecognizer` vẫn chỉ được chạm từ một chỗ |
| "`IntentParser` phải được tái sử dụng, không viết parser thứ hai" | Không có bảng nghĩa thứ hai: mọi câu trong `CommandGrammar.FIXED` được đối chiếu ngược lại `IntentParser`, và `CommandGrammarTest` assert `SelfCheck.disagreements()` rỗng — lệch là **test đỏ**, không thể vào bản build đã qua gate. Câu POI thì được **sinh** từ tên trong `places.db` + hai tiền tố |
| "Một entry point chung cho mọi Voice button" | Controller nằm trong application graph (`AppGraph.voiceInstant()`), overlay chỉ render `snapshot()` |
| "Push-to-talk là tương tác duy nhất" | Không wake word, không nghe nền; `AudioRecord` chỉ mở giữa `start()` và kết thúc interaction |

Các con số vận hành đang dùng:

| Hằng số | Giá trị | Ở đâu |
|---|---|---|
| Sample rate | 16.000 Hz mono PCM 16-bit | `VadGate.SAMPLE_RATE_HZ` |
| Silence timeout | 1.500 ms | `VadGate.DEFAULT_SILENCE_TIMEOUT_MS` |
| Trần một câu | 15.000 ms | `VadGate.DEFAULT_MAX_UTTERANCE_MS` |
| Unload model khi rảnh | 120.000 ms | `VoskOfflineRecognitionProvider.DEFAULT_UNLOAD_IDLE_MS` |
| Debounce partial | 250 ms | `VoiceInstantController.PARTIAL_MIN_INTERVAL_MS` |
| Số lần hỏi lại câu trả lời | 2 | `VoiceInstantController.MAX_ANSWER_ATTEMPTS` |
| Trần POI trong grammar | 200 | `CommandGrammar.DEFAULT_POI_CAP` |

## 4. Build và test

```
gradlew test --offline --rerun-tasks   →  512 test, 0 failure, 0 error, 0 skip
```

Giống hệt cho cả hai variant `debug` và `release` — đếm lại từ JUnit XML sau lần
chạy cuối, không dựa vào dòng tổng kết của Gradle.

| Module | Test |
|---|---:|
| `app` | 89 |
| `core:location-engine` | 77 |
| `core:map-api` | 11 |
| `core:map-engine-maplibre` | 10 |
| `core:music-engine-local` | 97 |
| `core:music-provider-online` | 30 |
| `core:navigation-core` | 55 |
| `core:routing-engine-offline` | 12 |
| `core:vehicle-audio-bridge` | 13 |
| `core:voice-offline` | **118** |
| **Tổng** | **512** |

Bản release **không còn là "chưa thử"**: `assembleRelease --offline` chạy xanh, và
keep-rule Vosk/JNA đã được kiểm bằng phép so sánh có/không (bỏ khối keep →
`org.vosk.*` type trong dex 20 → 2, `org.vosk.Recognizer` → `q2.a`; khôi phục →
APK release ra đúng sha256 ban đầu). Bảng đầy đủ ở
[VOICE_OFFLINE_SANDBOX_TEST.md](VOICE_OFFLINE_SANDBOX_TEST.md) §7.

Điều **chưa** có: integration test âm thanh → transcript bằng WAV tiếng Việt hợp
pháp. Phần "có dấu / không dấu đều parse ra cùng lệnh" đã được phủ ở mức văn bản
(`IntentParserTest`); phần âm thanh thì không, vì tạo WAV bằng TTS máy sẽ là test
synthetic — thứ §12 cấm dùng để tuyên bố độ chính xác.

## 5. APK và SHA-256

| | Debug (bàn giao) | Release (đã build, chưa chạy) |
|---|---|---|
| Tệp | `app/build/outputs/apk/debug/app-debug.apk` | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Kích thước | **49.799.534 byte** | 43.065.958 byte |
| SHA-256 | `17d1fb18d49565deaf4992f865763de9a00b7229d64fa194e70627b5062a2710` | `78d0befbe6b9a6ea20e2ae5b1d3becfbcadc0dab4f8fbac9a495e25ef9ed1121` |
| `minSdk` | 23 | 23 |
| ABI | `armeabi-v7a`, `arm64-v8a` | như trên |
| Model trong APK | **không có** | **không có** |

Đã kiểm bằng cách đọc trực tiếp APK: 1.094 entry, sáu tệp `lib/*`, và **không**
entry nào khớp `am/final.mdl` hay bất kỳ đường dẫn model nào. Có `libvosk.so`
(engine) nhưng không có model.

Phân bố `lib/` trong APK debug:

| Tệp | `armeabi-v7a` | `arm64-v8a` |
|---|---:|---:|
| `libvosk.so` | 8.279.808 | 8.862.928 |
| `libmaplibre.so` | 9.442.152 | 13.019.152 |
| `libjnidispatch.so` | 122.296 | 168.176 |

APK **nhỏ đi** so với bản trước batch dù có thêm Vosk, vì `ndk { abiFilters }` bỏ
`x86`/`x86_64`/`armeabi`/`mips`/`mips64` của `libmaplibre.so` và
`libjnidispatch.so`. Hai thay đổi không tách được bằng một con số duy nhất: cùng
cây này mà không lọc ABI là **96.469.038 byte**. Đóng góp riêng của Vosk vào APK là
hai `libvosk.so`: 8.279.808 + 8.862.928 = **17.142.736 byte**.

> APK đổi hash giữa hai lần build dù mã nguồn không đổi (zip padding). Muốn so
> sánh, đo **trong cùng một lần gọi gradle**. Xem `VOICE_OFFLINE_SANDBOX_TEST.md` §2.
>
> Cả hai hash trên được đo lại lần cuối bằng một lần `--rerun-tasks
> --no-build-cache` cho mỗi variant, sau **toàn bộ** thay đổi của batch kể cả các
> lần sửa comment. Đáng ghi lại một chi tiết: ở giữa batch, một lần build
> incremental ra 50.209.827 rồi 50.111.735 byte cho cùng cây nguồn; build lại sạch
> ra đúng 49.799.534 — nên chỉ con số của lần build sạch mới dùng được.

Lần build cuối, cùng một cây nguồn (toàn bộ suite + hai APK trong một lần gọi
`--rerun-tasks --no-build-cache`, 842 task, `BUILD SUCCESSFUL in 1m 11s`): cả hai
APK ra **đúng hai hash ở bảng trên**, không lệch một byte.

## 6. Bằng chứng model và license

Chi tiết đầy đủ: [VOICE_MODEL_LICENSE.md](VOICE_MODEL_LICENSE.md). Checklist §5:

| Yêu cầu | Bằng chứng |
|---|---|
| download/source location thật | `https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip`, `.work/voskmodel/curl.log`: `http=200 size=33656337` |
| version | `vosk-model-small-vn-0.4` — trong tên thư mục, `metadata.json`, trang models |
| checksum | SHA-256 ZIP `efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5`; 16 dòng trong `checksums.sha256` |
| Vietnamese language evidence | trang models ("Vietnamese", "Lightweight Vietnamese model") + `model/README` ("Small Vietnamese model for Vosk") + mã ISO `vn` |
| ARMv7/API 23 | `lib/armeabi-v7a/libvosk.so` có trong APK; model là dữ liệu Kaldi (không phụ thuộc ABI); `vosk-android` khai minSdk 21 ≤ 23 |
| license text | Apache 2.0, cột license của dòng Vietnamese trên trang models; `LICENSES.md` trong pack |
| commercial redistribution | Apache 2.0 §4; model không sửa đổi byte nào, ba tệp thêm vào được nêu rõ |

**Điểm yếu đã biết:** ZIP của Alphacephei **không kèm tệp license nào** (đã kiểm
14 tệp). Bằng chứng license là trang web của nhà cung cấp, không phải một tệp
trong gói — rủi ro pháp lý, không phải kỹ thuật. Trước khi phân phối thương mại,
đơn vị phát hành nên xác nhận lại bằng văn bản.

Gói đã đóng: payload **53.293.024 byte**, tổng 53.295.073 byte, digest
`checksums.sha256` = `7e09b1c829ad96c07818fd51be8fd878ef108f107017468f6b4945249f7083fb`.
`VoiceModelPackIntegrationTest` chạy **validator của chính sản phẩm** trên gói này
và ra `READY` với `deviceSdk = 23`.

## 7. Hiệu năng — những gì đã đảm bảo và những gì chưa đo

**Đã đảm bảo bằng cấu trúc (không phải bằng số đo):**

- Inference Vosk chạy trên `ExecutorService` riêng; **không** có inference trên
  main thread, nên không có đường nào dẫn tới ANR vì decode.
- Model nạp **lazy** ở lần dùng đầu và giải phóng sau 120 s rảnh.
- Micro được `stop()` → `release()` trong một `finally` duy nhất, trên mọi nhánh.
- Không hai provider giữ micro cùng lúc: router `releaseActive()` trước khi chuyển.
- Model **không** nằm trong APK, nên APK không tăng vì model.

**Chưa đo — không tài liệu nào trong batch này được dùng để tuyên bố mục này:**

- Cold load trên Cortex-A7 mất bao nhiêu giây.
- Peak native heap thật của model khi nạp.
- Latency nhận dạng.
- Có ANR/OOM khi bản đồ đang render và model nạp cùng lúc không.
- TTS có giọng tiếng Việt trên firmware này không.

`DiagnosticsActivity` đã xuất đúng các field cần cho việc đo (`voice.model.loadMs`,
`voice.model.peakNativeHeap`, `voice.latencyMs`, …) để người chạy checklist chỉ
phải chép số, không phải tự suy ra.

## 8. Blocker

Không có blocker kỹ thuật nào chặn batch ở mức sandbox.

Ba khoảng trống đã biết, **không phải blocker** nhưng không được che:

1. **Ba trong bốn điều kiện xác nhận của §9 chưa có đường kích hoạt** (đổi
   destination khi đang navigation, gọi điện/hành động nhạy cảm, confidence thấp).
   Chỉ "nhiều địa điểm cùng tên" đang hỏi. Ghi ở
   [VOICE_OFFLINE_KNOWN_LIMITATIONS.md](VOICE_OFFLINE_KNOWN_LIMITATIONS.md) §7.
2. **Không có hook `onTrimMemory`** gọi `providers.release()`. Policy low-memory
   hiện dựa vào unload 120 s, chưa dựa vào tín hiệu từ hệ thống. §11.
3. **Không có integration test âm thanh.** Xem mục 4.

Không có mục nào trong ba mục trên làm feature sai; chúng làm feature **chưa phủ
hết brief**, và được ghi thành giới hạn thay vì được mô tả là "đã xong".

## 9. Checklist thiết bị

[VOICE_OFFLINE_DEVICE_CHECKLIST.md](VOICE_OFFLINE_DEVICE_CHECKLIST.md) — 10 mục,
chạy được **không cần ADB**:

1. Cài và validate model từ USB, gồm ca ZIP hỏng phải giữ nguyên model đang dùng.
2. Cold load / warm load / unload sau 2 phút.
3. 15 lệnh cố định × 3 khoảng cách = 45 lần nói.
4. 5 POI Đà Nẵng, nói cả tên gốc lẫn cách gọi địa phương; POI mơ hồ phải hỏi.
5. 15 lệnh khi nhạc đang phát — duck/restore, đo âm lượng trước/giữa/sau.
6. Chạy hoàn toàn ngoại tuyến.
7. Bảy ca lỗi bắt buộc (permission denied, mic bận, im lặng, Back, câu không hiểu,
   gỡ model, thay model giữa chừng).
8. Chép tối đa 21 dòng `voice.*` sau mỗi nhóm.
9. Bảng kết quả tổng.
10. Cách báo kết quả — dòng **transcript** là quan trọng nhất, vì nó phân biệt
    "model nghe sai" với "model nghe đúng, parser không hiểu".

Không mục nào trong checklist này đã được chạy. Tài liệu đó là hướng dẫn, không
phải biên bản.

## 10. Tệp thay đổi

Danh sách đầy đủ, từng tệp một, ở
[_CHANGED_FILES.md](_CHANGED_FILES.md) mục **2026-11-02**.

Tóm tắt theo vùng:

| Vùng | Thay đổi |
|---|---|
| `:core:voice-offline` (module mới) | 10 tệp main + 8 tệp test (118 test) |
| `app` | 2 provider mới, controller, `AppGraph`, Settings, `VoiceModelActivity`, Diagnostics, manifest, strings, `build.gradle.kts`, `proguard-rules.pro` |
| `:core:navigation-core` | `VoiceError` 7 → 12 trạng thái lỗi (thêm `NETWORK_ERROR`, `LANGUAGE_UNAVAILABLE`, `MIC_BUSY`, `CANCELLED`, `INTERNAL_ERROR`; giữ nguyên bảy giá trị cũ), `VoiceAnswer`, `CommandRouter.answer()/rearm()` |
| `:core:place-search` | `topPlaces(limit)` cho grammar |
| `tools/` | `tools/voicepack/` (builder + grammar), `gen_transfer_full.py` |
| `docs/` | 6 tài liệu §15 + báo cáo này + `TRANSFER.md` |
| `transfer_full/` | sinh lại: 345 file, ~8,3 MB trong 7 tệp; đã đối chứng 345/345 byte-for-byte |
