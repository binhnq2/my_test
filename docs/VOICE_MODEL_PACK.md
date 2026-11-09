# Định dạng gói mô hình giọng nói offline

Theo §4 của `TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`. Gói mô hình **không nằm trong
APK**; nó được cài từ USB/storage vào bộ nhớ trong của app.

> Mọi con số trong tài liệu này đo trên **gói đã build thật** tại
> `.work/voicepack/vi-VN/vosk-model-small-vn-0.4` của repo này, ngày 2026-11-02.

---

## 1. Layout

```
voice-models/vi-VN/<modelVersion>/
├── metadata.json          bắt buộc, mô tả gói
├── model/                 thư mục Kaldi, sao nguyên byte
│   ├── am/final.mdl
│   ├── conf/{mfcc.conf,model.conf}
│   ├── graph/{Gr.fst,HCLr.fst,disambig_tid.int,phones/word_boundary.int}
│   ├── ivector/{final.dubm,final.ie,final.mat,global_cmvn.stats,online_cmvn.conf,splice.conf}
│   └── README
├── command_grammar.json   bắt buộc khi vocabularyMode = GRAMMAR
├── checksums.sha256       sha256sum format
└── LICENSES.md            bằng chứng license (ZIP gốc không có file này)
```

Trên thiết bị, `VoiceModelStore` dùng thêm hai đường dẫn ngoài pack:

```
<root>/vi-VN/active.txt          một dòng: <modelVersion> đang dùng
<root>/staging/<name>/           đích giải nén, Vosk không bao giờ đọc
```

`<root>` là `files/voice-models` trong sandbox của app
(`AppGraph.VOICE_MODELS_DIR`).

## 2. `metadata.json`

Đủ 12 trường §4 yêu cầu:

| Trường | Kiểu | Gói thật |
|---|---|---|
| `schemaVersion` | int | `1` |
| `engine` | string | `vosk` |
| `locale` | string | `vi-VN` |
| `modelVersion` | string | `vosk-model-small-vn-0.4` |
| `minSdk` | int | `23` |
| `supportedAbis` | string[] | `["armeabi-v7a", "arm64-v8a"]` |
| `size` | int64 | `53293024` |
| `sha256` | string | `7e09b1c829ad96c07818fd51be8fd878ef108f107017468f6b4945249f7083fb` |
| `license` | string | `Apache-2.0` |
| `licenseUrl` | string | `https://www.apache.org/licenses/LICENSE-2.0` |
| `sourceUrl` | string | `https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip` |
| `vocabularyMode` | string | `GRAMMAR` |

Thêm một trường tuỳ chọn `description`. Không trường nào ngoài danh sách này
được đọc, và trường lạ bị bỏ qua chứ không gây lỗi — pack của batch sau có thể
thêm trường mà bản này vẫn đọc được.

### `size` là payload, không phải toàn thư mục

`size` = tổng byte của mọi file **trừ** `metadata.json` và `checksums.sha256`.
Lý do nằm trong `VoiceModelValidator.kt`: `size` nằm trong `metadata.json` nên nếu
tính cả nó, giá trị khai báo phụ thuộc vào số chữ số của chính nó; và
`checksums.sha256` không thể chứa hash của chính nó. Đếm cả hai sẽ làm **mọi** gói
hợp lệ đều trượt, và một phép kiểm trượt phổ quát thì sớm muộn cũng bị nới cho tới
khi nó không kiểm gì nữa.

```
payload  = 53.293.024 byte
toàn bộ  = 53.295.073 byte   (+metadata.json +checksums.sha256)
```

### `sha256` là digest của `checksums.sha256`

Không phải digest của cả thư mục (không tính được nếu không tự tham chiếu). Đây là
digest của tệp checksum, nên "thư mục này còn khớp metadata nó mang không" trả lời
được bằng một lần băm. `checksums.sha256` phủ từng file payload theo định dạng
`sha256sum` (`<hex>  <path>`, sắp xếp theo path), nên `sha256sum -c` chạy được ngay
trên máy tính.

## 3. Thứ tự kiểm khi cài

`VoiceModelValidator.validate()` chạy **rẻ và quyết định trước**, để gói sai engine
hay sai kiến trúc bị loại trong mili-giây thay vì sau khi băm 51 MB:

| # | Kiểm | Kết quả khi trượt |
|---|---|---|
| 1 | `metadata.json` đọc và parse được | `CORRUPT` |
| 2 | `schemaVersion` = 1 | `INCOMPATIBLE` |
| 3 | `engine` = `vosk` | `INCOMPATIBLE` |
| 4 | `locale` = `vi-VN` | `INCOMPATIBLE` |
| 5 | `minSdk` ≤ API thiết bị | `INCOMPATIBLE` |
| 6 | có ít nhất một ABI giao với `ACCEPTED_ABIS` | `INCOMPATIBLE` |
| 7 | có `model/` và `am/final.mdl`, `graph/HCLr.fst`, `graph/Gr.fst` | `CORRUPT` |
| 8 | có `command_grammar.json` khi khai `GRAMMAR`, và là mảng chuỗi không rỗng | `CORRUPT` |
| 9 | `size` khớp tổng byte trên đĩa | `CORRUPT` |
| 10 | từng dòng `checksums.sha256` khớp | `CORRUPT` |

Gói dành cho máy khác được báo `INCOMPATIBLE` chứ không `CORRUPT`: copy lại lần nữa
không làm nó chạy được, nên hai trạng thái phải dẫn tới hai lời khuyên khác nhau.

Kiểm checksum **không bỏ qua** dòng nào: một dòng trỏ tới file không tồn tại là
lỗi, vì tệp checksum chỉ phủ các file đang có sẽ hợp lệ hoá một gói đã bị xoá mất
acoustic model. Đường dẫn trong tệp checksum bị kiểm bằng `canonicalFile` để không
thể trỏ ra ngoài thư mục pack.

## 4. Cài đặt

### Từ ZIP

`VoiceModelStore.importZip(name, open)`:

1. Giải nén vào `staging/<name>` với **Zip-Slip guard**: mỗi entry được kiểm
   `canonicalFile` vẫn nằm trong thư mục staging.
2. `findManifestRoot` dò tối đa 3 cấp để chấp nhận ZIP có một thư mục bọc ngoài
   (kiểu ZIP tải từ web).
3. `install(staged)`.

### Từ thư mục USB

`VoiceModelActivity.scan()` duyệt bộ nhớ trong và USB (có kiểm quyền đọc), tìm thư
mục có `metadata.json` ở độ sâu ≤ 3, rồi `installFolder(dir)`.

### Kích hoạt nguyên tử

`install()` làm đúng thứ tự:

1. Validate thư mục staging.
2. Kiểm `locale` và `modelVersion` khớp regex đường dẫn (`LocaleName`,
   `VersionName`) — nếu không, một pack tên `../..` sẽ thoát khỏi thư mục model.
3. Chuyển thư mục cũ sang `<version>.old` (fallback sang copy nếu `renameTo` hỏng
   trên FUSE/FAT32).
4. Chuyển staging vào `<locale>/<version>/`.
5. **Validate lại tại đích** — vì nhánh copy không bảo đảm byte giống hệt.
6. Ghi `active.txt` qua temp-and-rename.
7. Xoá backup.

Trượt ở bước 5 hoặc 6 thì thư mục mới bị xoá và bản `.old` được khôi phục. Mô hình
đang chạy không bao giờ bị gỡ để lấy chỗ.

## 5. Trạng thái

`VoiceModelState` — sáu giá trị §4 yêu cầu:

| Trạng thái | Nghĩa | Người lái cần làm |
|---|---|---|
| `NOT_INSTALLED` | chưa có pack nào | cài một pack |
| `VALIDATING` | đang kiểm (trạng thái tạm của lượt import) | chờ |
| `READY` | hợp lệ và là pack đang dùng | không gì |
| `INCOMPATIBLE` | đúng pack, sai máy (engine/locale/ABI/minSdk) | dùng pack khác |
| `CORRUPT` | hỏng, thiếu file, sai checksum, sai dung lượng | copy lại từ USB |
| `FAILED` | pass validator nhưng Vosk từ chối nạp | cài lại pack đó |

`FAILED` chỉ đến từ `VoskOfflineRecognitionProvider`, và **không được persist**:
nó sống trong bộ nhớ cho tới khi process chết. Lần khởi động sau pack được
validate lại từ đĩa; nếu byte còn nguyên thì lại `READY`. Lý do: một lần nạp hỏng
có thể là OOM do bản đồ đang chiếm RAM, không phải thuộc tính của pack, và persist
sẽ biến lỗi tạm thời thành phán quyết vĩnh viễn.

## 6. Gỡ và thay

- Gỡ: `VoiceModelActivity.confirmRemove()` → `store.remove(confirmed = true)`, có
  hộp thoại xác nhận. Sau khi gỡ, lần bấm micro tiếp theo trả
  `LANGUAGE_UNAVAILABLE` → overlay hiện CTA cài model; không crash.
- Thay: cài pack mới; bản cũ được chuyển sang `.old` trong lúc chuyển rồi xoá khi
  thành công.
- Sau khi đổi pack, màn hình gọi `AppGraph.onVoiceModelChanged()`: giải phóng model
  Vosk đang nạp và xoá verdict `unusableReason` để pack mới được thử.

## 7. Không cần Internet

Sau khi `READY`, không đường nào trong luồng nhận dạng offline chạm mạng:
`VoskOfflineRecognitionProvider` không import `java.net.*`, không có HTTP client,
và `OFFLINE_ONLY` không gọi provider system. Quyền `INTERNET` trong manifest là
cho nhạc online, không liên quan tới đường này.

## 8. Dựng lại gói

```bash
python -I tools/voicepack/build_voice_pack.py \
    --model   .work/voskmodel/x/vosk-model-small-vn-0.4 \
    --out     .work/voicepack/vi-VN/vosk-model-small-vn-0.4 \
    --grammar tools/voicepack/command_grammar.json
```

Script từ chối ghi đè thư mục không rỗng (một gói dựng đè lên gói dở sẽ băm hỗn
hợp cả hai), copy model nguyên byte, và in ra payload/total/sha256/phrase count.
Chạy bằng `python -I` vì thư mục model là dữ liệu tải từ Internet và không được
nằm trên đường tìm module của Python.

## 9. Kiểm chứng gói đã build

`VoiceModelPackIntegrationTest` chạy **validator của sản phẩm** trên gói thật ở
đường dẫn trên (đổi được bằng biến môi trường `TS8_VOICE_PACK_DIR`):

- pack validate ra `READY` với `deviceSdk = 23` và `ACCEPTED_ABIS`
- manifest đúng engine/locale/version, `minSdk ≤ 23`, có `armeabi-v7a`
- `LICENSES.md` tồn tại và nêu đúng license + nguồn
- bản copy bị cắt ngắn báo `CORRUPT`; đổi 1 byte trong `am/final.mdl` đổi digest

Không có gói → các test này bị **skip** (`assumeTrue`), không pass im lặng.
