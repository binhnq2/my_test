# Giới hạn đã biết — Voice Offline (Vosk) trên TS8

Theo §15 của `TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`. Mọi mục dưới đây là một
**giới hạn thật** của bản build này, không phải việc cần làm sau.

> Đọc mục này trước khi tin bất kỳ con số nào khác trong bộ tài liệu.

---

## 1. License chỉ được công bố trên trang web, không có trong ZIP

Gói `vosk-model-small-vn-0.4.zip` **không kèm tệp license nào** — đã kiểm trên bản
giải nén, 14 tệp, không có `LICENSE`/`COPYING`/`NOTICE`.

Bằng chứng license là **cột license trên trang
`https://alphacephei.com/vosk/models`**, ghi `Apache 2.0` cho dòng Vietnamese. Trang
đó có thể đổi hoặc biến mất. `LICENSES.md` trong pack ghi lại nội dung và URL tại
thời điểm build để người kiểm tra sau không phải suy đoán, nhưng nó **không phải**
văn bản license gốc.

**Hệ quả:** trước khi phân phối thương mại, đơn vị phát hành nên xác nhận lại với
Alphacephei bằng văn bản. Đây là rủi ro pháp lý, không phải rủi ro kỹ thuật.

## 2. Chưa có kết quả đo trên TS8

Không có mục nào trong bộ tài liệu này là kết quả đo trên thiết bị thật. Cụ thể
**chưa biết**:

- Vosk có nạp được model trên firmware này không (JVM test không chạy được `.so` ARM).
- `AudioRecord` với nguồn `VOICE_RECOGNITION` có mở được không, hay rơi về `MIC`.
- Cold load mất bao nhiêu giây trên Cortex-A7.
- Peak native heap của model là bao nhiêu trong thực tế.
- Latency nhận dạng.
- Có ANR/OOM khi bản đồ đang render và model nạp cùng lúc không.
- TTS có giọng tiếng Việt trên firmware này không.

Checklist để trả lời từng câu: [VOICE_OFFLINE_DEVICE_CHECKLIST.md](VOICE_OFFLINE_DEVICE_CHECKLIST.md).

## 3. Không có bằng chứng độ chính xác nhận dạng

WER **15,70** trong `model/README` là số của **nhà cung cấp trên bộ VIVOS**,
`exp/chain/tdnn/decode_test/wer_10_0.0` — điều kiện phòng thu, không phải cabin.

Không có:
- WAV tiếng Việt hợp pháp hoặc tự tạo trong sandbox để chạy integration test âm thanh.
- Kết quả đo tiếng Việt trong môi trường ồn của xe.
- Đo khoảng cách micro → người nói trên TS8.

**Một model nhận dạng tiếng Anh không thể nhận dạng tiếng Việt đáng tin cậy**, và
tài liệu này không tuyên bố ngược lại. Model ở đây là model tiếng Việt; giới hạn
nằm ở việc **chưa biết nó chính xác bao nhiêu trong cabin**.

## 4. Kết quả bị giới hạn bởi grammar

Grammar của Vosk chỉ chứa:
- 14 câu lệnh cố định (lúc chạy, sinh từ `CommandGrammar.FIXED`),
- 200 POI phổ biến nhất từ `places.db` (mỗi POI hai dạng: `tìm …`, `dẫn đường tới …`),
- 2 tiền tố trống.

Gói model giao kèm có `command_grammar.json` chỉ **16** câu — 14 câu cố định cộng
đúng hai tiền tố, **không** có POI nào. Danh sách POI đến từ `places.db` của pack
bản đồ đang active lúc chạy, nên lúc chạy tổng có thể lên tới 414 câu
(14 + 2 + 200 × 2). Con số 16 trong pack không phải là trần — nó là phần cố định.

Một địa điểm **không nằm trong 200 POI đó sẽ không bao giờ được nhận dạng**, kể cả
khi người lái nói đúng tên. Họ sẽ nhận "không nghe rõ". Đây là lựa chọn có chủ ý —
grammar không giới hạn sẽ làm graph decode tăng lên hàng trăm MB trên CPU này — nhưng
nó là một giới hạn chức năng thật.

Cap được nêu trong `CommandGrammar.DEFAULT_POI_CAP` và
`AppGraph.COMMAND_GRAMMAR_POI_CAP`; cả hai là cùng một số.

## 5. Normalize chỉ sửa transcript đã có từ đúng

`IntentParser` fold dấu, nên "mở bản đồ" và "mo ban do" **đều** parse ra cùng một
lệnh. Điều đó chỉ giúp khi model đã phiên âm ra đúng các từ tiếng Việt đó.

Nó **không** cứu được:
- Một từ bị model nghe thành từ khác ("mở bạn đồ").
- Một câu bị thiếu từ.
- Tiếng Anh, tiếng địa phương, hoặc tên riêng không có trong grammar.

## 6. Câu trả lời xác nhận chỉ có 15 dạng

`CommandGrammar.ANSWERS` là danh sách đóng: *đồng ý, vâng, ừ, được, phải, ok, ô kê,
huỷ, huỷ bỏ, huỷ lệnh, không, không đồng ý, không cần, không được, thôi*.

"Chuẩn rồi", "được luôn", "tất nhiên" **không** được nhận là đồng ý. Người lái sẽ
được nhắc lại đúng hai từ và vẫn còn hai nút chạm.

`dung`/`đúng` bị loại có chủ ý: fold lại thành cùng một chuỗi với `dừng`, nên không
thể đọc an toàn.

## 7. Ba trong bốn điều kiện xác nhận của §9 chưa có đường kích hoạt

§9 liệt kê bốn trường hợp cần xác nhận. Bản build này **chỉ** hỏi ở trường hợp đầu:

| §9 | Trạng thái |
|---|---|
| Nhiều địa điểm/app cùng tên | ✅ có — `CommandOutcome.NeedsConfirmation` |
| Đổi destination khi đang navigation | ❌ chưa có — `VoiceCommandExecutor` chưa kiểm trạng thái navigation |
| Gọi điện hoặc action nhạy cảm | ❌ chưa có — `openPhone` mở `ACTION_DIAL`, không gọi trực tiếp, nên hiện chưa cần chặn |
| Confidence thấp | ❌ chưa có — Vosk báo confidence nhưng controller chưa dùng nó để quyết định xác nhận |

Ba mục ❌ là **khoảng trống đã biết**, không phải thiết kế. Chúng được nêu ở đây
thay vì được che bằng một câu "đã theo §9".

## 8. `CONFIRMING` không dùng được với `OFFLINE_ONLY` khi chưa có model

Nếu policy là `OFFLINE_ONLY` và chưa cài model, `VoiceRouter` vẫn có thể vào
`CONFIRMING` (do executor hỏi), nhưng `maybeListenForAnswer()` không mở được
provider nào. Prompt vẫn còn hai nút chạm và tự đóng sau 8 s. Không crash, nhưng
người lái không thể trả lời bằng giọng trong ca này.

## 9. Không có wake word, không nghe nền

Cố ý, theo brief. Push-to-talk là tương tác duy nhất. Không có chế độ nào nghe liên
tục, và `AudioRecord` chỉ mở giữa `start()` và kết thúc interaction.

## 10. Model 53 MB chiếm bộ nhớ trong

Bộ nhớ trong của TS8 có hạn, và model nằm ở `files/voice-models/` (~53 MB), cộng
`libvosk.so` trong APK (~17 MB cho hai ABI, trước khi nén).

Policy hiện tại: model được giải phóng sau **120 giây** không dùng. Nếu firmware
này có ít RAM, 120 s có thể là quá dài — **chưa đo**, xem mục 2.

## 11. Không có hook `onTrimMemory`

`RecognitionProviderRouter.release()` tồn tại và giải phóng cả hai provider, nhưng
**chưa có chỗ nào gọi nó khi hệ thống báo thiếu bộ nhớ**. Hiện nó chỉ được gọi khi
controller bị huỷ (process teardown).

Nghĩa là: nếu bản đồ cần RAM đúng lúc model còn nằm trong bộ nhớ (trong 120 s sau
câu cuối), không có cơ chế nhường. Đây là việc cần làm trước khi coi policy
low-memory là đã đủ.

## 12. Chưa test tranh chấp đa luồng

`VoiceRouter` đồng bộ hoá mọi cửa vào bằng `@Synchronized`, nhưng các test gọi tuần
tự. Tính đúng đắn dưới tranh chấp dựa vào việc đồng bộ hoá đó, không dựa vào một test
đã chạy.

## 13. APK bàn giao là debug; bản release đã build được nhưng chưa chạy

APK bàn giao là `app-debug.apk` (không minify, không shrink). Bản release có
`isMinifyEnabled = true` và `isShrinkResources = true`.

**Bản release đã được build trong batch này** (không chỉ được suy luận):
`gradlew :app:assembleRelease --offline` → `BUILD SUCCESSFUL`,
`app-release-unsigned.apk` 43.065.958 byte, sha256
`78d0befbe6b9a6ea20e2ae5b1d3becfbcadc0dab4f8fbac9a495e25ef9ed1121`.

Keep-rule `org.vosk.**` / `com.sun.jna.**` trong `app/proguard-rules.pro` cũng đã
được **kiểm bằng phép so sánh có/không**, không chỉ bằng một lần build xanh. Bỏ
khối `# ---- Vosk` rồi build lại cho kết quả:

| | có keep-rule | không có keep-rule |
|---|---|---|
| `org.vosk.*` type còn trong dex | **20** | **2** (`LibVosk`, `Model`) |
| `com.sun.jna.*` type còn trong dex | **127** | **6** |
| `org.vosk.Recognizer` | giữ nguyên tên | **đổi thành `Lq2/a;`** |
| APK | 43.065.958 byte | 43.033.190 byte |

Nghĩa là nếu thiếu khối đó, `VoskOfflineRecognitionProvider` gọi
`new Recognizer(...)` trên một lớp mà JNA không còn thấy — đúng kiểu hỏng chỉ lộ
ra ở bản release. Đây là bằng chứng **ở mức bytecode**, không phải ở mức thiết bị.

Một điều học được trong lúc kiểm, ghi lại để không ai lo thừa: tên **phương thức
native** của `LibVosk` (`vosk_model_new`, `vosk_recognizer_accept_waveform_s`, …)
vẫn được giữ ngay cả khi thiếu keep-rule, vì
`proguard-android-optimize.txt` đã có
`-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`.
Thứ bị phá là tên **lớp Java** và các lớp JNA mà chúng dựa vào, không phải tên
symbol native.

**Phần vẫn chưa biết.** Chưa có lần chạy release nào trên thiết bị: R8 có thể đã
đổi thứ tự field của một `Structure` subclass, hoặc shrink thứ gì đó chỉ được
gọi từ native. Trước khi phát hành bản release, vẫn phải cài và **nạp model trên
TS8**; triệu chứng nếu hỏng là `UnsatisfiedLinkError`/`ClassNotFoundException`
**chỉ có ở bản release**.

## 14. Kích thước APK và ABI

`abiFilters` chỉ giữ `armeabi-v7a` và `arm64-v8a`. Một thiết bị khác dùng `x86`
(giả lập, đầu Android khác) **sẽ không cài được** APK này.

Đây là quyết định có chủ ý cho phạm vi campaign (TS8 là ARMv7), nhưng nó làm APK
không còn là "một file chạy mọi nơi".

## 15. Không có giao diện chọn độ nhạy VAD

`VadGate` có các hằng số (`DEFAULT_SILENCE_TIMEOUT_MS = 1500`,
`DEFAULT_MAX_UTTERANCE_MS = 15000`, `DEFAULT_MIN_SPEECH_MS = 300`,
`DEFAULT_NO_SPEECH_TIMEOUT_MS = 2500`) nhưng **không cấu hình được từ UI**. Nếu cabin
ồn làm VAD cắt sớm, cách duy nhất hiện tại là sửa hằng số và build lại.

## 16. Chẩn đoán chỉ là bản ghi cuối, trong bộ nhớ

`VoiceDiagnostics` giữ **một** lượt gần nhất, trong bộ nhớ process. Nó mất khi
process chết, và không có lịch sử nhiều lượt. Muốn so sánh 45 lần nói, phải chép
tay hoặc chụp màn hình giữa các lần.

Đây là giới hạn có chủ ý theo §8 ("không lưu partial transcript/history mặc định"),
nhưng nó làm việc đo trên thiết bị nặng hơn.
