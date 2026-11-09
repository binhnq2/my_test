# License mô hình giọng nói và engine

Theo §5 của `TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`. Tài liệu này ghi **bằng
chứng**, không phải lời khẳng định. Mỗi mục dưới đây trả lời được bằng một tệp,
một URL còn truy cập được, hoặc một dòng trong gói đã build.

> **Phạm vi.** Đây là đánh giá kỹ thuật của người triển khai, **không phải ý kiến
> pháp lý**. Trước khi phân phối thương mại, đơn vị phát hành phải tự xác nhận.

---

## 1. Mô hình: `vosk-model-small-vn-0.4`

| Trường | Giá trị | Nguồn |
|---|---|---|
| Nguồn tải | `https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip` | tải thật, `.work/voskmodel/curl.log` |
| HTTP status | `200` | `curl.log` dòng 2 |
| Kích thước ZIP | 33.656.337 byte | `curl.log`; khớp `ls -l` trên đĩa |
| SHA-256 ZIP | `efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5` | `curl.log` dòng 4 |
| Phiên bản | `vosk-model-small-vn-0.4` | tên thư mục trong ZIP + trang models |
| Ngôn ngữ | Vietnamese | trang models, dòng "Vietnamese" |
| License | Apache 2.0 | trang models, **cột license** của dòng Vietnamese |
| Văn bản license | `https://www.apache.org/licenses/LICENSE-2.0` | — |

### Bằng chứng license

Trang danh mục `https://alphacephei.com/vosk/models` liệt kê nguyên văn:

```
vosk-model-small-vn-0.4   32M   15.70 (Vivos test)   Lightweight Vietnamese model   Apache 2.0
```

Gói ZIP **không kèm bất kỳ tệp license nào**. Điều này đã được kiểm trực tiếp trên
bản đã giải nén: 14 tệp, không có `LICENSE`, `COPYING`, `NOTICE` hay tương tự. Vì
vậy `LICENSES.md` trong pack là **do dự án này tạo ra**, ghi lại nguồn của tuyên bố
license để người kiểm tra không phải suy đoán, và nó phải được giữ lại khi pack
được chép sang thiết bị.

Đây là điểm yếu đã biết và được ghi ở
[VOICE_OFFLINE_KNOWN_LIMITATIONS.md](VOICE_OFFLINE_KNOWN_LIMITATIONS.md#1-license-chỉ-được-công-bố-trên-trang-web-không-có-trong-zip):
bằng chứng license là **trang web của nhà cung cấp**, không phải tệp trong gói.

### Phân phối lại

Apache License 2.0 cho phép phân phối lại, kể cả trong sản phẩm thương mại, với
điều kiện giữ thông báo bản quyền và giấy phép, và nêu rõ các thay đổi.

- **Không sửa đổi nội dung mô hình.** Các tệp dưới `model/` được copy nguyên byte
  từ ZIP gốc; `checksums.sha256` chứng minh điều đó, và mỗi hash ở đây cũng khớp
  với hash đã tính trên bản giải nén trước khi đóng gói.
- **Có thêm ba tệp** không thuộc mô hình gốc: `metadata.json`,
  `command_grammar.json`, `LICENSES.md`. Chúng do dự án launcher tạo, không phải
  của Alphacephei, và việc thêm chúng được nêu rõ ngay trong `LICENSES.md` của
  pack — đúng yêu cầu "nêu rõ các thay đổi".

### Bằng chứng tiếng Việt

Ba nguồn độc lập, không chỉ một:

1. **Trang models** ghi "Vietnamese" / "Lightweight Vietnamese model".
2. **`model/README`** trong gói, nguyên văn: `Small Vietnamese model for Vosk`.
3. **Tên gói** `vosk-model-small-vn-0.4`, trong đó `vn` là mã ISO cho Việt Nam.

WER 15,70 trong `README` (`exp/chain/tdnn/decode_test/wer_10_0.0`) là số đo của
**nhà cung cấp trên bộ VIVOS**, không phải số đo trong cabin xe. Không có kết quả
đo nào trên TS8 trong tài liệu này.

### Bằng chứng ARMv7 / API 23

Model Vosk là **dữ liệu Kaldi**, không phải mã máy — nó độc lập với kiến trúc CPU.
Thứ có kiến trúc là `libvosk.so` (trong APK, không trong pack). Cụ thể:

| Hạng mục | Bằng chứng |
|---|---|
| `armeabi-v7a` có trong APK | `lib/armeabi-v7a/libvosk.so` (8.279.808 byte), liệt kê từ APK đã build |
| `arm64-v8a` có trong APK | `lib/arm64-v8a/libvosk.so` (8.862.928 byte) |
| API 23 | `vosk-android:0.3.47` khai `minSdkVersion 21`; APK của ta `minSdk = 23` |
| CPU | TS8 là Cortex-A7 (ARMv7-A) — nằm trong tập mà `armeabi-v7a` phủ |
| Model không cần NEON/ABI cụ thể | toàn bộ là file dữ liệu đọc bằng `fopen`, xem `checksums.sha256` |

`abiFilters` của `app/build.gradle.kts` chỉ giữ `armeabi-v7a` và `arm64-v8a`, khớp
đúng `VoiceModelManifest.ACCEPTED_ABIS`, nên một pack cho ABI khác bị validator báo
`INCOMPATIBLE` thay vì hỏng lúc nạp `.so`.

## 2. Engine: `com.alphacephei:vosk-android:0.3.47`

| Trường | Giá trị |
|---|---|
| Toạ độ Maven | `com.alphacephei:vosk-android:0.3.47` |
| License | Apache License 2.0 |
| Nguồn | `https://github.com/alphacep/vosk-api` |
| Ghi chú | đóng trong APK, **không** trong pack mô hình |

Version được **ghim chính xác** trong `app/build.gradle.kts`, không dùng range:
nâng version đổi ABI native mà APK ship, và đổi luôn hợp đồng JNI mà
`VoskOfflineRecognitionProvider` dựa vào.

### JNA (phụ thuộc bắc cầu)

Vosk Android dùng JNA để gọi `libvosk.so`. Artifact
`net.java.dev.jna:jna:5.13.0` (kèm trong `vosk-android`) có license kép
**Apache License 2.0 / LGPL 2.1**. Ta dùng theo nhánh Apache 2.0.

Điều này cũng giải thích bốn `libjnidispatch.so` cũ từng nằm trong APK
(`armeabi`, `mips`, `mips64`, `x86`): chúng đến từ JNA, không phải Vosk. `abiFilters`
đã loại chúng khỏi bản build này.

## 3. Thành phần của dự án (không phải bên thứ ba)

Ba tệp sau trong pack do batch này tạo, thuộc dự án launcher:

- `metadata.json` — mô tả gói, sinh bởi `tools/voicepack/build_voice_pack.py`
- `command_grammar.json` — 16 câu lệnh, sinh từ bảng `CommandGrammar.FIXED`
- `LICENSES.md` — tệp này ở dạng trong pack

## 4. Những gì KHÔNG có trong gói

- Không có mã thực thi nào. Vosk nạp một thư mục Kaldi, không nạp `.so` từ pack.
- Không có giọng nói đóng gói (packaged audio prompt) — §10 cho phép dùng nếu
  không có TTS tiếng Việt, nhưng batch này chưa cần vì `VoiceFeedback` đã kiểm
  `Locale("vi","VN")`.
- Không có dữ liệu người dùng, không có bản ghi âm mẫu.

## 5. Checklist chứng cứ (§5, đã đối chiếu từng mục)

| Yêu cầu của brief | Trạng thái | Bằng chứng |
|---|---|---|
| download/source location thật | ✅ | `curl.log`: HTTP 200, 33.656.337 byte từ `alphacephei.com` |
| version | ✅ | `vosk-model-small-vn-0.4`, trong tên thư mục, `metadata.json` và trang models |
| checksum | ✅ | SHA-256 ZIP + SHA-256 từng tệp trong `checksums.sha256` |
| Vietnamese language evidence | ✅ | trang models + `model/README` + mã `vn` |
| ARMv7/API 23 compatibility evidence | ✅ | `lib/armeabi-v7a/libvosk.so` trong APK; model là dữ liệu; minSdk 23 |
| license text | ✅ | Apache 2.0, công bố trên trang models; `LICENSES.md` trong pack |
| evidence commercial redistribution permitted | ✅ | Apache 2.0 §4 (redistribution), nội dung model không sửa đổi |

Không mục nào thiếu, nên batch này **không** trả `BLOCKED_MODEL_ASSET`.
