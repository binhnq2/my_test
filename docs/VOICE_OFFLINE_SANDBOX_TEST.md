# Sandbox test report — Voice Offline (Vosk)

Theo §12 của `TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`.

> **Trạng thái: `SANDBOX_PASS_DEVICE_PENDING`.** Mọi kết quả dưới đây là kết quả
> build và test **trong sandbox**. Không có mục nào là kết quả đo trên TS8 thật,
> và tài liệu này không tuyên bố có. Không dòng nào trong đây được dùng để nói
> "voice offline đã chạy tốt trên xe".

---

## 1. Môi trường

| Hạng mục | Giá trị |
|---|---|
| Ngày chạy | 2026-11-02 |
| AGP / Kotlin / Gradle | 8.7.3 / 1.9.25 / 8.11.1 |
| JDK | 17 (`D:/Tools/_toolchain/jdk-17.0.20.1+1`) |
| Android SDK | `D:/Tools/_toolchain/android-sdk`, build-tools 34.0.0 |
| Chế độ build | `--offline` (host nằm sau proxy SSL-inspecting) |
| `minSdk` / `targetSdk` / `compileSdk` | 23 / 30 / 34 |
| ABI trong APK | `armeabi-v7a`, `arm64-v8a` |

## 2. APK

| Hạng mục | Giá trị |
|---|---|
| Tệp | `app/build/outputs/apk/debug/app-debug.apk` |
| Kích thước | **49.799.534 byte** (47,49 MiB) |
| SHA-256 | `17D1FB18D49565DEAF4992F865763DE9A00B7229D64FA194E70627B5062A2710` |
| `minSdk` | 23 |
| `lib/armeabi-v7a/libvosk.so` | 8.279.808 byte — có mặt |
| `lib/armeabi-v7a/libmaplibre.so` | 9.442.152 byte |
| `lib/armeabi-v7a/libjnidispatch.so` | 122.296 byte |
| `lib/arm64-v8a/…` | cùng bộ ba, cho arm64 |
| Model trong APK | **không có** — xem mục 6 |

Lệnh đo lại:

```powershell
Set-Location D:\Tools\Car_Launcher
.\gradlew :app:assembleDebug --offline
$apk = "app\build\outputs\apk\debug\app-debug.apk"
(Get-Item $apk).Length
(Get-FileHash $apk -Algorithm SHA256).Hash
```

> **APK đổi hash giữa hai lần build dù không đổi mã nguồn.** Zip padding trong
> `packageDebug` làm nội dung byte khác nhau. Muốn so sánh, đo **trong cùng một
> lần gọi gradle** như lệnh trên, hoặc dùng `--rerun-tasks`. Hash ở bảng trên là
> của lần build đã sinh ra APK đang nằm trên đĩa.

## 3. Tổng hợp unit test

`.\gradlew test --offline` — **512 test, 0 lỗi, 0 failure, 0 skip**, cho mỗi
variant `debug` và `release`.

| Module | Số test |
|---|---:|
| `app` | 89 |
| `core:location-engine` | 77 |
| `core:map-api` | 11 |
| `core:map-engine-maplibre` | 10 |
| `core:music-engine-local` | 97 |
| `core:music-provider-online` | 30 |
| `core:navigation-core` | **55** |
| `core:routing-engine-offline` | 12 |
| `core:vehicle-audio-bridge` | 13 |
| `core:voice-offline` | **118** |
| **Tổng** | **512** |

## 4. `core:voice-offline` — 118 test

| Lớp test | Số test | Chứng minh điều gì |
|---|---:|---|
| `RecognitionProviderRouterTest` | 26 | bảng fallback đúng cho **cả 12** giá trị `VoiceError`; tối đa một lần chuyển; policy đọc theo từng request; lỗi đến muộn bị bỏ; provider throw/refuse không làm treo; bản ghi export mang đúng `policy`/`switches`/`fallbackTrail` của request tương ứng |
| `VoiceModelValidatorTest` | 21 | schema/engine/locale/minSdk/ABI → `INCOMPATIBLE`; thiếu file, sai size, sai checksum, grammar rỗng, đường dẫn thoát thư mục → `CORRUPT` |
| `VoiceModelStoreTest` | 19 | cài/ghi `active.txt` nguyên tử; trượt validate thì giữ pack cũ; gỡ khi chưa xác nhận bị từ chối; Zip-Slip bị chặn; `markLoadFailed` không persist |
| `CommandGrammarTest` | 15 | mọi câu trong `FIXED` parse ngược lại đúng lệnh; mọi câu trong `ANSWERS` đọc được thành `VoiceAnswer`; không câu trả lời nào là lệnh; grammar là JSON hợp lệ; cap POI |
| `VadGateTest` | 12 | silence/no-speech/trần thời gian; không cắt giữa hai từ; floor thích nghi |
| `VoskResultTest` | 11 | hai shape JSON của Vosk; `text` rỗng là `NO_MATCH`; confidence null khi engine không báo |
| `MiniJsonTest` | 10 | parser JSON thay `org.json` (là stub trên JVM thuần) |
| `VoiceModelPackIntegrationTest` | 4 | **chạy validator của sản phẩm trên gói thật** — xem mục 5 |
| **Tổng** | **118** | |

## 5. Integration test trên gói thật

`VoiceModelPackIntegrationTest` không dùng fixture: nó trỏ vào
`.work/voicepack/vi-VN/vosk-model-small-vn-0.4` (đổi được bằng `TS8_VOICE_PACK_DIR`)
và chạy đúng `VoiceModelValidator` mà bản Settings sẽ chạy.

| Test | Kết quả |
|---|---|
| pack validate ra `READY` với `deviceSdk = 23`, `ACCEPTED_ABIS` | **pass**, `actualBytes = 53.293.024` |
| manifest đúng engine `vosk`, locale `vi-VN`, version `vosk-model-small-vn-0.4`, `minSdk ≤ 23`, có `armeabi-v7a`, license `Apache-2.0`, `sourceUrl` trỏ `alphacephei.com/vosk/models/`, vocabulary `GRAMMAR`, sha256 là 64 hex | **pass** |
| `LICENSES.md` tồn tại, nêu "Apache License 2.0" và "alphacephei.com/vosk" | **pass** |
| bản copy bị cắt → `CORRUPT`; đổi 1 byte trong `am/final.mdl` → digest đổi | **pass** |

Ba test đầu dùng `assumeTrue(packDir.isDirectory)`, nên một checkout không có gói
sẽ **skip** chứ không pass im lặng. Trong lần chạy này gói có mặt và cả 4 test đều
chạy thật (`skip=0`).

## 6. Model không nằm trong APK

`app-debug.apk` chứa `libvosk.so` (engine, ~8 MB mỗi ABI) nhưng **không** chứa
model. Model 53 MB đến từ USB và được cài vào `files/voice-models/` lúc chạy.

Đây là yêu cầu "Không làm tăng APK chính bằng model asset". Đóng góp của Vosk vào
APK là **hai bản `libvosk.so`**: 8.279.808 (`armeabi-v7a`) + 8.862.928
(`arm64-v8a`) = **17.142.736 byte** (16,3 MiB), cộng `libjnidispatch.so` của JNA
(122.296 + 168.176 byte). Không phải 53 MB model.

**Về kích thước APK so với bản trước batch.** Batch này đồng thời thêm `abiFilters`
(`armeabi-v7a`, `arm64-v8a`), nên APK **nhỏ đi** so với bản dựng không lọc ABI —
bản không lọc là 96.469.038 byte vì còn mang `x86`, `x86_64` và các
`libjnidispatch.so` cũ của `armeabi`/`mips`/`mips64`. Hai thay đổi này không tách
được bằng một con số duy nhất, nên ở đây ghi cả hai phép đo thay vì một delta dễ
gây hiểu nhầm.

## 7. Bản release có minify: đã build và đã đối chứng

APK bàn giao là bản **debug**. Nhưng bản release không còn là "chưa thử": nó đã
build được, và keep-rule của Vosk/JNA đã được kiểm bằng một phép so sánh chứ không
bằng lòng tin.

```powershell
Set-Location D:\Tools\Car_Launcher
.\gradlew :app:assembleRelease --offline
# app/build/outputs/apk/release/app-release-unsigned.apk
# 43.065.958 byte, sha256 78d0befbe6b9a6ea20e2ae5b1d3becfbcadc0dab4f8fbac9a495e25ef9ed1121
```

Cùng cây mã nguồn, bỏ khối `# ---- Vosk` khỏi `app/proguard-rules.pro` rồi build
lại, cho:

| | có keep-rule | không có keep-rule |
|---|---|---|
| `org.vosk.*` type trong dex | **20** | **2** |
| `com.sun.jna.*` type trong dex | **127** | **6** |
| `org.vosk.Recognizer` | `org.vosk.Recognizer` | **`q2.a`** |
| APK | 43.065.958 byte | 43.033.190 byte |

Đo bằng cách trích các `.dex` từ APK rồi liệt kê type descriptor
(`Lorg/vosk/…;`, `Lcom/sun/jna/…;`) và đọc `app/build/outputs/mapping/release/mapping.txt`.
Khối keep-rule đã được khôi phục byte-for-byte sau phép thử, và APK release
build lại ra **đúng sha256 ban đầu** — nên bảng trên không phải kết quả của một
cây đã bị sửa sót.

Một chi tiết ngược với trực giác, để không ai lo thừa: tên **phương thức native**
của `LibVosk` (`vosk_model_new`, …) vẫn còn cả khi thiếu keep-rule, vì
`proguard-android-optimize.txt` đã có `-keepclasseswithmembernames` cho
`native <methods>`. Thứ bị phá là tên **lớp**, và đó mới là chỗ JNA tra cứu.

**Vẫn chưa chứng minh:** bản release chưa từng chạy trên thiết bị. Xem
[VOICE_OFFLINE_KNOWN_LIMITATIONS.md](VOICE_OFFLINE_KNOWN_LIMITATIONS.md#13-apk-bàn-giao-là-debug-bản-release-đã-build-được-nhưng-chưa-chạy).

## 8. Những gì test này KHÔNG chứng minh

Danh sách này quan trọng ngang phần đã chứng minh:

| Không chứng minh | Vì sao | Ai sẽ chứng minh |
|---|---|---|
| Độ chính xác nhận dạng tiếng Việt trong cabin | không có WAV tiếng Việt hợp pháp/tự tạo trong batch này, và test synthetic không được dùng để tuyên bố độ chính xác | người dùng, trên TS8, theo checklist §13 |
| Vosk nạp được model trên thiết bị thật | JVM test không chạy được `.so` ARM | người dùng, trên TS8 |
| `AudioRecord` mở được với `VOICE_RECOGNITION` trên firmware này | cần thiết bị thật | người dùng, trên TS8 |
| Không ANR/OOM khi model nạp cùng lúc bản đồ đang render | cần thiết bị thật | người dùng, trên TS8 |
| Latency và peak memory | cần thiết bị thật | người dùng, qua `DiagnosticsActivity` |
| TTS tiếng Việt có giọng trên firmware này | cần thiết bị thật | người dùng |

## 9. Khoảng trống test đã biết

Nêu rõ để không ai đọc bảng trên thành "đã phủ hết §12":

- **Không có integration test bằng WAV tiếng Việt.** §12 yêu cầu "WAV tiếng Việt
  hợp pháp/tự tạo: 15 command cố định, có dấu/không dấu transcript path". Phần
  "transcript path" (có dấu và không dấu đều parse ra cùng lệnh) **đã** được phủ
  trong `IntentParserTest` (27 test) ở mức văn bản. Phần **âm thanh → transcript**
  thì chưa, vì không có bộ WAV hợp pháp trong batch này và tạo bằng TTS máy sẽ là
  test synthetic — thứ §12 cấm dùng để tuyên bố độ chính xác.
- **Không có Robolectric cho hai provider Android.** `AndroidSystemRecognitionProvider`
  và `VoskOfflineRecognitionProvider` cần `SpeechRecognizer`/`AudioRecord`/`.so`
  thật; host này build offline và không có `android-all` jar. Hai lớp đó được test
  gián tiếp qua router (bằng fake) và qua cấu trúc (mọi nhánh release mic nằm trong
  một `finally`).
- **`@Synchronized` của `VoiceRouter` không được test đa luồng.** Các test gọi
  tuần tự; tính đúng đắn dưới tranh chấp dựa vào việc mọi cửa vào đều đồng bộ hoá,
  không dựa vào một test.

## 10. Lệnh tái lập

```powershell
Set-Location D:\Tools\Car_Launcher
.\gradlew test --offline
.\gradlew :app:assembleDebug --offline
.\gradlew :app:assembleRelease --offline
```

Sau khi đổi `res/layout` **hoặc** `app/proguard-rules.pro`, phải thêm
`--rerun-tasks`, nếu không tác vụ tương ứng có thể được coi là UP-TO-DATE và báo
xanh cho mã cũ. Với `assembleRelease`, việc UP-TO-DATE còn nguy hiểm hơn: mapping
đọc ra sẽ là của lần minify trước, tức bảng §7 sẽ mô tả một APK không còn tồn tại.

Đếm test từ JUnit XML (dùng được khi Gradle nuốt mất dòng tổng kết):

```bash
for d in */build/test-results/testDebugUnitTest core/*/build/test-results/testDebugUnitTest; do
  [ -d "$d" ] || continue
  n=$(grep -ho 'tests="[0-9]*"' "$d"/*.xml | sed 's/[^0-9]//g' | awk '{s+=$1} END{print s+0}')
  f=$(grep -ho 'failures="[0-9]*"' "$d"/*.xml | sed 's/[^0-9]//g' | awk '{s+=$1} END{print s+0}')
  echo "$d tests=$n failures=$f"
done
```
