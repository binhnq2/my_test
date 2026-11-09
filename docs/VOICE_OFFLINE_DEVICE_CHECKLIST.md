# Checklist pilot trên TS8 thật — Voice Offline

Theo §13 của `TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`.

> **Người thực hiện:** người dùng, trên đầu TS8 thật.
> **Trạng thái hiện tại:** chưa ai chạy checklist này. Tài liệu này là hướng dẫn,
> không phải biên bản.
>
> Không mục nào trong các tài liệu của batch này được coi là "đã qua" cho tới khi
> có kết quả ghi vào đây.

---

## 0. Chuẩn bị

| Cần gì | Ghi chú |
|---|---|
| APK debug | `app-debug.apk`, SHA-256 `17D1FB18…A2710`, 49.799.534 byte. Xác nhận hash trước khi cài. |
| Gói mô hình | `voicemodels-vi-VN-vosk-model-small-vn-0.4.zip` (hoặc chép cả thư mục). Trên USB. |
| Map pack | pack có `places.db` của khu vực test (Đà Nẵng), đã cài qua Map Management. |
| Nhạc | ít nhất một bài/đài phát được, để test duck/restore. |
| Cách ghi kết quả | Chụp màn hình `Cài đặt → Chẩn đoán` sau mỗi nhóm, hoặc ghi tay các dòng `voice.*`. |

Cài APK:

```powershell
adb install -r app-debug.apk
```

Nếu `adb` không có trên máy test, chép APK vào USB và cài bằng trình quản lý tệp
của đầu — không cần ADB cho checklist này.

## 1. Cài và validate mô hình từ USB

- [ ] Cài APK, mở launcher. **Không crash khi chưa có mô hình.**
- [ ] Mở `Cài đặt` → mục **Giọng nói**. Trạng thái mô hình: `chưa cài`.
      Nút phải là **"Cài mô hình giọng nói offline"**.
- [ ] Bấm micro khi chưa có mô hình → overlay báo cần cài mô hình, **không crash**,
      **không có** vòng xoay treo.
- [ ] Cắm USB, vào `Cài đặt → Quản lý mô hình giọng nói`.
- [ ] **"Tìm trên bộ nhớ và USB"** → thấy thư mục/gói mô hình.
- [ ] Cài. Kỳ vọng: `đang kiểm tra` rồi `sẵn sàng`.
      Ghi lại **thời gian từ lúc bấm Cài tới lúc `sẵn sàng`** (giây).
- [ ] Kiểm tra: màn hình Quản lý hiện `Phiên bản: vosk-model-small-vn-0.4`,
      `Dung lượng: 53.293.024` (hoặc ~50,8 MB), `ABI: armeabi-v7a, arm64-v8a`,
      `Giấy phép: Apache-2.0`.
- [ ] Thử cài **lại** cùng gói: phải thay được, không hỏng pack đang dùng.
- [ ] Thử cài một ZIP **hỏng** (ví dụ cắt bớt file): phải báo `hỏng hoặc sai
      checksum`, và **mô hình đang dùng vẫn giữ nguyên** (bấm micro vẫn chạy).

## 2. Cold load / warm load

- [ ] **Cold:** khởi động lại đầu (hoặc `adb shell am force-stop com.ts8.launcher`),
      mở launcher, bấm micro ngay, nói một lệnh. Ghi **độ trễ từ bấm micro tới lúc
      nghe tiếng bíp/overlay "Đang nghe…"** — đây là cold load.
- [ ] Đọc `voice.model.loadMs` trong Chẩn đoán. Ghi số.
- [ ] Đọc `voice.model.peakNativeHeap` (byte). Ghi số.
- [ ] **Warm:** bấm micro lần 2 trong vòng 2 phút. Lệnh phải phản hồi nhanh hơn lần
      đầu rõ rệt; `voice.model.loadMs` **không xuất hiện** (model đã nạp).
- [ ] Để yên 3 phút (quá `DEFAULT_UNLOAD_IDLE_MS = 120 s`), bấm micro lần 3. Phải
      nạp lại (cold) — đúng policy, không phải lỗi.
- [ ] **Không ANR:** trong suốt mục này, không có hộp thoại "Ứng dụng không phản hồi".

Tiêu chí sơ bộ: `loadMs` nên dưới ~3 s trên Cortex-A7. Nếu trên 8 s, ghi lại và
coi là cần xem lại chính sách lazy-load.

## 3. 15 lệnh cố định — xe đứng yên, nhạc TẮT

Nói rõ, tốc độ bình thường. Ghi **Đ** (đúng) / **S** (sai) / **K** (không nghe) và
**độ trễ** (giây, từ lúc nói xong tới lúc có phản hồi).

| # | Câu | Kỳ vọng | KQ | Trễ |
|---|---|---|---|---|
| 1 | mở bản đồ | mở Map | | |
| 2 | trang chủ | về Home | | |
| 3 | mở nhạc | mở Music | | |
| 4 | phát nhạc | phát tiếp/đang phát | | |
| 5 | tạm dừng | dừng nhạc | | |
| 6 | dừng nhạc | dừng nhạc | | |
| 7 | bài tiếp theo | next | | |
| 8 | chuyển bài | next | | |
| 9 | bài trước | previous | | |
| 10 | quay lại bài | previous | | |
| 11 | tăng âm lượng | volume +1 bậc | | |
| 12 | giảm âm lượng | volume −1 bậc | | |
| 13 | mở điện thoại | mở app gọi | | |
| 14 | gọi điện | như trên (hoặc DIAL) | | |
| 15 | mở ứng dụng <tên app có trên máy> | mở đúng app | | |

Lặp mỗi câu **3 lần** ở các khoảng cách nói khác nhau (gần micro, xa micro, có
tiếng ồn) — 45 lần nói. Ghi tỉ lệ đúng/45.

## 4. Lệnh tìm / dẫn đường với POI Đà Nẵng

Cần map pack có `places.db` Đà Nẵng đã cài.

- [ ] "tìm Cầu Rồng" → hiện kết quả tìm kiếm.
- [ ] "dẫn đường tới Cầu Rồng" → tính đường và vào navigation.
- [ ] Thử 5 POI nổi bật của Đà Nẵng (chọn 5 địa điểm có tiếng, ví dụ: Cầu Rồng,
      Bà Nà Hills, chợ Hàn, sân bay Đà Nẵng, biển Mỹ Khê). Với mỗi POI: nói **2 lần**
      (một đúng nguyên văn tên trong `places.db`, một nói như người địa phương).
- [ ] **POI mơ hồ:** tìm một tên trùng (ví dụ "Bến Thành" nếu pack có hai mục) →
      phải hỏi lựa chọn, **không tự chọn bừa**.
      Ghi lại câu hỏi hiện ra.
- [ ] Nếu hỏi lựa chọn: thử trả lời bằng giọng **"đồng ý"** → phải thực hiện.
- [ ] Lặp với **"huỷ"** → phải bỏ, không thực hiện lệnh.

## 5. 15 lệnh khi nhạc ĐANG phát — duck/restore

- [ ] Phát nhạc ở âm lượng nghe rõ bình thường.
- [ ] Nói "bài tiếp theo". Kỳ vọng: nhạc **giảm nhỏ** ngay khi micro mở, lệnh chạy,
      rồi nhạc **trở lại đúng âm lượng cũ**.
- [ ] Đo **âm lượng trước / lúc duck / sau khi trả** — phải bằng nhau ở hai đầu.
      Ghi sai lệch nếu có.
- [ ] Lặp toàn bộ 15 lệnh ở mục 3 khi nhạc đang phát.
- [ ] Đặc biệt chú ý: nhạc **không** được tắt hẳn, **không** được chạy tiếp ở âm
      lượng duck sau khi interaction kết thúc.
- [ ] Huỷ giữa lúc đang nói (bấm X): nhạc phải trả âm lượng, micro phải đóng.

## 6. Chạy hoàn toàn ngoại tuyến

- [ ] Ngắt mạng hoàn toàn (rút USB 3G/4G hoặc tắt Wi-Fi; nếu không tắt được thì
      dùng `adb shell svc wifi disable` và `svc data disable`).
- [ ] Kiểm tra Chẩn đoán vẫn báo model `READY`.
- [ ] Lặp lại toàn bộ 15 lệnh. **Không lệnh nào được yêu cầu mạng** để chạy.
- [ ] Trong `Cài đặt → Giọng nói`, chọn **"Chỉ ngoại tuyến"**. Bấm micro, nói
      "mở bản đồ". Phải chạy, không có lỗi mạng.
- [ ] Chọn lại **"Tự động"** (mặc định báo cáo).

## 7. Các ca lỗi bắt buộc thử

| Ca | Cách làm | Kỳ vọng |
|---|---|---|
| **Permission denied** | Vào Settings hệ thống → App → quyền → tắt Micro. Bấm micro. | Overlay báo cần quyền micro, có nút mở Settings. **Không crash.** |
| **Mic bận** | Mở app ghi âm/đàm thoại đang giữ micro rồi bấm micro. | Báo "Micro đang bận". Khi app kia nhả, lần sau phải chạy lại. |
| **Không nói gì** | Bấm micro, im lặng 5 s. | Overlay tự đóng sau ~2,5 s trống + timeout; báo không nghe thấy. Mic đóng. |
| **Huỷ bằng Back** | Bấm micro, nói dở, bấm Back. | Đóng overlay, **không** chạy lệnh, nhạc trả âm lượng. |
| **Câu không hiểu** | Nói "thời tiết hôm nay thế nào". | Báo không hiểu lệnh + **trích lại câu đã nghe**. Không crash. |
| **Gỡ model rồi bấm micro** | Gỡ mô hình, bấm micro. | CTA cài mô hình, không crash. |
| **Thay model giữa chừng** | Cài model mới khi app đang chạy, rồi bấm micro. | Dùng model mới ngay, không cần khởi động lại app. |

## 8. Ghi nhận hiệu năng và độ ổn định

Sau mỗi nhóm test, mở `Cài đặt → Chẩn đoán` và **chép lại các dòng**. Danh sách
dưới đây là toàn bộ những gì `VoiceDiagnostics.toLines()` có thể in ra — tối đa
**21** dòng; dòng nào không có giá trị (ví dụ `model.loadMs` khi model đã nạp sẵn)
sẽ **không xuất hiện**, nên một lượt thường thấy 15–19 dòng chứ không phải 21.

```
voice.provider=
voice.policy=
voice.switches=
voice.fallbackTrail=
voice.model.state=
voice.model.version=
voice.model.size=
voice.model.sha256=
voice.model.abi=
voice.model.loadMs=
voice.model.warm=
voice.model.peakNativeHeap=
voice.audio.initOk=
voice.audio.sampleRate=
voice.audio.source=
voice.latencyMs=
voice.endReason=
voice.error=
voice.errorCode=
voice.transcript=
voice.confidence=
```

Ghi thêm bằng tay:

- [ ] `adb shell dumpsys meminfo com.ts8.launcher` **trước** và **sau** lần dùng
      voice đầu tiên — chênh lệch bao nhiêu MB?
- [ ] `adb shell dumpsys package com.ts8.launcher | grep -i anr` — có ANR không?
- [ ] Launcher có bị hệ thống kill khi đang mở bản đồ và vừa dùng voice không?
      (Quan sát: bản đồ tải lại từ đầu.)
- [ ] Nhiệt độ/CPU có tăng bất thường sau vài lần bấm micro không? (Cảm nhận hoặc
      đọc từ app hệ thống.)

## 9. Bảng kết quả tổng

Điền sau khi xong, để quyết định bước tiếp theo:

| Hạng mục | Kết quả |
|---|---|
| Cài model từ USB | |
| Cold load (ms) | |
| Peak native heap (byte) | |
| Số lệnh đúng / 45 | |
| Latency trung bình (ms) | |
| POI đúng / 10 | |
| Duck/restore đúng | có / không |
| Offline hoàn toàn | có / không |
| Crash | số lần |
| ANR | số lần |
| Process bị kill | số lần |

## 10. Cách báo kết quả

Gửi kèm: bảng mục 9, ảnh màn hình Chẩn đoán, và với mỗi lỗi một dòng
`câu nói → transcript máy nghe được → hành vi`. Dòng **transcript** là quan trọng
nhất: nó phân biệt "model nghe sai" với "model nghe đúng, parser không hiểu" — hai
lỗi phải sửa ở hai chỗ khác nhau.
