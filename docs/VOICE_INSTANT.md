# Voice Instant (§10–11)

Voice Instant là một tương tác **một lượt**: người lái chạm micro, nói một câu, launcher hiểu — hoặc nói rõ là không hiểu — rồi đóng. Không nghe nền, không restart khi im lặng.

Tài liệu này ghi hành vi thật của code và **giới hạn nền tảng** trên TS8 / API 23, theo đúng yêu cầu §10–11 của brief.

---

## 1. Kiến trúc: không engine nào nằm trong Activity

```
VoiceTrigger            (app)  mở micro, xin quyền RECORD_AUDIO
  → VoiceInstantController (app, process-level)  sở hữu phiên
      → SpeechRecognizerAdapter  (app)   nơi DUY NHẤT chạm android.speech.*
      → VoiceFeedback            (app)  TTS + duck nhạc
      → VoiceRouter     (core:navigation-core)  state machine + deadline
          → IntentParser  (core)  câu nói → VoiceCommand
          → CommandExecutor (app)  thực thi lệnh
      → VoiceStateStore (core)  phục hồi sau process death
  → VoiceOverlayHost / VoiceOverlayView (app)  renderer của snapshot
```

**Vì sao controller ở process-level, không ở Activity.** Overlay bị tạo/huỷ bởi bất kỳ màn nào người lái đang đứng; phiên recogniser, nhạc đang duck và câu xác nhận đang chờ **không được** kết thúc vì một màn bị recreate. Controller nằm trong application graph biến overlay thành **renderer của `snapshot()`** — đúng nguyên tắc áp cho map: *không engine nào nằm trong Activity*.

**Vì sao router ở `core`, không ở `app`.** Nó là logic thuần (state + deadline), không cần `Context`. Ở `core` nó **test được trên JVM**, và đó là điều kiện để §20's danh sách test voice (hợp lệ / mơ hồ / no-match / timeout / cancel) là test thật, không phải kịch bản thủ công.

---

## 2. Adapter: `SpeechRecognizerAdapter`

Lý do tồn tại cụ thể trên chiếc xe này: firmware TS8 có thể **không có** recogniser, có thể có nhưng **chỉ hiểu tiếng Anh**, hoặc có Google app bị ROM disable. Cả ba đều **vô hình** từ calling code — nó chỉ cần biết *"người lái đã nói gì đó"* hoặc *"cái này hỏng và đây là lý do"*.

Thuộc tính bắt buộc:

- **Không lặp.** Không continuous recognition, không restart-on-silence. Brief cấm ghi âm nền; một recogniser tự restart **chính là** điều đó — micro bị giữ mở bởi màn người lái đã rời. Một lần vào, tối đa một kết quả ra, rồi huỷ recogniser.
- **`EXTRA_PREFER_OFFLINE` được đặt.** Đây là head unit: người lái có thể đang ở hầm hoặc bãi xe không có data. Extra này xin platform dùng model on-device trước. Trên API 23 cờ này **chỉ là advisory**; máy không có model tiếng Việt offline sẽ bỏ qua — vì vậy recogniser sau đó không kết nối được mạng được báo `SERVICE_UNAVAILABLE`, **không** retry vô hạn.
- **Chống kết quả đôi.** Một số firmware giao **cả** `onError` **và** `onResults` cho cùng một phiên. Cờ `in flight` bảo đảm callback thấy **một** kết quả cho một lần nói — nếu không, router có thể chạy một lệnh đã có kết quả.
- **Threading.** `SpeechRecognizer` yêu cầu mọi method trên main thread và cũng giao callback trên đó. Adapter chỉ được gọi từ main looper và không hop thread.

---

## 3. Bảy trạng thái và vì sao `CONFIRMING` là state, không phải cờ

```text
IDLE → LISTENING → PROCESSING → { EXECUTING → SUCCESS
                                | CONFIRMING → EXECUTING → SUCCESS
                                | ERROR }
```

**`CONFIRMING` là state vì cờ dialog là vô hình với mọi thứ không phải dialog.** Hệ quả cụ thể của việc nó là boolean:
- Back sẽ đóng một cửa sổ mà router vẫn tin là đang mở.
- Process recreation sẽ khôi phục một "không" chưa từng xảy ra.
- Ducking nhạc đã bắt đầu cho câu hỏi xác nhận sẽ **không bao giờ** được hoàn lại, vì không ai đếm.

Là một state, "đang chờ có/không" là sự thật **duy nhất** overlay render từ đó, là state **duy nhất** Back nghĩa là cancel chứ không phải pop màn, và là state router tự timeout được.

Transcript chỉ được đẩy máy tiến từ `IDLE` hoặc `ERROR`; ngoài ra router **bỏ** — coi đó là bug của caller thay vì đoán.

Ngoài ra `CONFIRMING` **từ chối mở micro**: người lái đang trả lời một câu hỏi, mở micro sẽ làm "đồng ý" của họ thành transcript tiếp theo thay vì câu trả lời.

---

## 4. Parser: hiểu cả khi mất dấu

### 4.1 Vì sao fold chứ không chỉ lowercase

Recogniser trên TS8 sinh ra bất cứ thứ gì language pack nó cài nghĩ là đã nghe: có khi `Dẫn đường tới Đà Nẵng`, có khi `Dan duong toi Da Nang` khi firmware không có model tiếng Việt. Cả hai phải tới **cùng** `VoiceCommand.NavigateTo` với **cùng** đích đến.

Vì vậy `IntentParser.fold()`:
- bỏ **mọi** dấu thanh (`\p{Mn}+`),
- `đ` → `d`,
- lowercase,
- gộp dấu câu và khoảng trắng,
- **giữ `%`** để `âm lượng 40%` còn parse được (và `%` không thể đụng từ tiếng Việt).

Đích đến cũng được fold, vì nó là **khoá tra cứu** cho place index offline — index fold y hệt (`VietnameseText.normalize`) — không phải chuỗi để in. Giữ dấu sẽ làm hai cách viết thành hai địa điểm khác nhau, đúng cái bug mà folding sinh ra để chặn.

### 4.2 Ba kết quả, không có "best effort"

| Kết quả | Nghĩa | Hành vi |
|---|---|---|
| `Command(cmd, matchedPhrase)` | Một lệnh, chắc chắn | Chạy (hoặc hỏi xác nhận) |
| `Ambiguous(candidates)` | Là prefix thật của nhiều lệnh và phần còn lại không được nói | Overlay **liệt kê** và hỏi, không chọn hộ |
| `Unrecognized(text)` | Không khớp gì | Hiện `text` **nguyên bản có dấu** — cái người lái thực sự nói, không phải bản đã fold |

**Vì sao không đoán.** Một voice UI đoán còn tệ hơn một voice UI hỏi: "mở" có thể là map, màn nhạc hoặc một app; hành động theo phỏng đoán sẽ **đổi màn hình dưới tay người lái**. Không có nhánh "cố gắng tốt nhất", không có lệnh mặc định để rơi về.

---

## 5. Chọn hypothesis: xếp hạng sai không thành lỗi

Recogniser **không phải lúc nào** cũng trả kết quả đúng ở vị trí đầu: "mở bản đồ" thường về thứ hai sau "mở bạn đồ". `chooseHypothesis()` thử từng hypothesis theo thứ tự, lấy **cái đầu tiên parse được thành lệnh**; nếu không cái nào parse được thì trả về **hypothesis đầu tiên nguyên bản** để overlay trích đúng cái đã nghe, không phải thứ cuối cùng đã thử.

---

## 6. Danh mục lệnh

`VoiceCommand` là sealed class, không phải intent string — vì một lỗi duy nhất cần loại bỏ: `ParseResult.Ambiguous` không có command object nào để mang. Các lệnh:

| Nhóm | Lệnh |
|---|---|
| Dẫn đường | `NavigateTo(destination)`, `SearchPlace(query)`, `OpenMap` |
| Nhạc | `PlayMusic`, `PauseMusic`, `NextTrack`, `PreviousTrack`, `OpenMusic` |
| Âm lượng | `VolumeUp`, `VolumeDown`, `VolumeTo(percent)` |
| Màn hình | `GoHome`, `OpenPhone`, `OpenApp(query)` |
| Khác | `Exit` |

### 6.1 `needsConfirmation` ⟺ `destructive`

`VoiceScenarios.verify()` assert đúng hai chiều: một lệnh **hỏi xác nhận** mà **không** destructive, hoặc **destructive** mà **không hỏi**, đều là failure. Đây là bất biến được test, không phải quy ước.

`VoiceRouter.Config.requireConfirmation` mặc định `true`. Đặt `false` cho phép lệnh destructive chạy không hỏi; **cố ý** chỉ để một fleet operator quyết, và một router instance **không bao giờ** được dựng với nó một cách tình cờ.

---

## 7. Timeout: một timer cho cả tương tác

`VoiceRouter` **sở hữu deadline** (`remainingMs()`) nhưng **không post** gì — nó không có timer thread. `VoiceInstantController` post **một** message trễ và re-arm trên mỗi lần đổi state.

- `timeoutMs = 8 000` — đủ lâu để người lái nói "đồng ý" sau khi câu hỏi đọc xong, đủ ngắn để overlay biến mất trước một ngã tư người lái cần thấy.
- Nhờ vậy câu `CONFIRMING` tự đóng sau 8 giây, và `SUCCESS`/`ERROR` tự dismiss **trên cùng ngân sách** thay vì nằm đó đến lúc tắt khoá điện.
- Overlay **không** render live state: `snapshot()` là **bản copy**, vì router mutate field dưới lock, và một overlay giữ object sống sẽ render state và timestamp của hai thời điểm khác nhau.

---

## 8. Xác nhận: chỉ bằng nút

`VoiceInstantController.confirm(yes: Boolean)` — **"đồng ý" nói bằng miệng KHÔNG được chấp nhận.** Hypothesis của recogniser cho "đồng ý" không đáng tin đến mức coi nó là câu trả lời **vừa mis-fire vừa** để prompt mở. Overlay có hai nút đúng vì lý do này, và `VoiceRouter.onTranscript` **bỏ** transcript đến trong `CONFIRMING`.

---

## 9. Lỗi: một giá trị enum cho mỗi cách hỏng

`VoiceError` —— không cái nào bị nuốt im lặng. Một failure người lái không thấy được **không phân biệt được** với một lệnh đang chạy, và đó là cách một overlay kẹt ở "Đang xử lý…" đến lúc tắt máy.

| `VoiceError` | Nghĩa | Người lái làm gì |
|---|---|---|
| `SERVICE_UNAVAILABLE` | Không có recogniser, hoặc service không bind | Overlay hiện đúng câu *"Voice service unavailable"* |
| `NO_SPEECH` | Không nghe thấy gì trước end-of-speech timeout | Nói lại |
| `NO_MATCH` | Có tiếng nhưng không có hypothesis | Nói lại, rõ hơn |
| `TIMEOUT` | Không nói gì, hoặc câu xác nhận không được trả lời | — |
| `NOT_UNDERSTOOD` | Nghe và transcribe được, nhưng không phải lệnh của mình | Thử câu khác |
| `COMMAND_FAILED` | Executor từ chối hoặc hỏng **sau khi** đã nhận | — |
| `PERMISSION_DENIED` | `RECORD_AUDIO` chưa từng được cấp | **Sửa được** trong Settings — overlay trỏ tới đó |

`PERMISSION_DENIED` **khác** `SERVICE_UNAVAILABLE` về bản chất: cái đầu người lái sửa được, cái sau thì không.

---

## 10. Quyền: xin lúc chạm micro, không bao giờ lúc boot

`VoiceTrigger` là nơi **duy nhất** xin `RECORD_AUDIO` và là đường **duy nhất** mở Voice Instant.

- **Xin lúc chạm micro, không lúc boot.** Launcher xin micro ở lần khởi động đầu sẽ **dạy mọi người lái từ chối**. Quyền được xin đúng lúc mục đích hiển nhiên — người lái vừa chạm micro.
- **Không hỏi `shouldShowRequestPermissionRationale`.** Trên head unit, người lái đang ngồi trong xe, không đọc màn giải thích; overlay đã nói vì sao cần micro và có nút Settings. Thêm một dialog giải thích cùng điều đó **trước** dialog hệ thống là một lần chạm nữa giữa người lái và lệnh.
- **Vì sao là class, không phải mỗi màn tự gọi `start()`.** Trên API 23, lần chạm đầu phải request, câu trả lời về **bất đồng bộ** ở `onRequestPermissionsResult`, và tương tác phải bắt đầu **sau** đó. Ba màn làm việc này độc lập là ba cơ hội mở recogniser không có grant — mà trên firmware này kết quả là `ERROR_INSUFFICIENT_PERMISSIONS` sau một giây thay vì một dialog.
- **Trả lời từ chối.** `VoiceInstantController.permissionRefused()` tách khỏi `start()` vì platform trả lời bất đồng bộ: trigger đã trả về caller trước khi dialog bị dismiss, và không có phiên nào để mở. Overlay vẫn phải nói gì đó, và phải là trường hợp **sửa được**, nên lỗi đi qua router hệt như recogniser error.

---

## 11. Ducking: nhạc xuống, không dừng

`VoiceFeedback` **duck** `STREAM_MUSIC` xuống một phần tư thay vì pause.

- Prompt trên một track đã pause để xe im 2 giây rồi nhạc chạy lại — người lái đọc đó là **nhảy bài**.
- `unduck` trả về **đúng mức** có trước, nên một prompt **không bao giờ** thay đổi vĩnh viễn âm lượng của người lái.
- **Duck cho cả tương tác, không theo từng câu.** Một tương tác có thể đọc ba lần liền ("Đang nghe…", câu hỏi, kết quả). Duck/unduck quanh từng câu tạo ra track phình lên giữa chúng. Controller đếm: `begin` một lần khi overlay mở, `end` một lần khi đóng.
- **Chuẩn bị trước khi micro mở.** Recogniser nghe cabin qua chính loa đó; một track to là nguyên nhân **lớn nhất** của transcript sai trên máy này.

**TTS không có sẵn thì không crash.** Máy bị firmware strip speech engine vẫn phải chạy Voice Instant — overlay và executor đều hoạt động, chỉ thiếu audio. `isLanguageAvailable()` trả false thì controller **không nói gì** thay vì đưa text cho engine nuốt mất; overlay vẫn là kênh phản hồi.

**API 23 note.** `TextToSpeech.speak` với `Bundle` không có trước API 21; overload `QUEUE_FLUSH` dùng ở đây thì có. Utterance id là thứ `UtteranceProgressListener` báo về, và đó là cách `onDone` biết có thể nhả duck.

---

## 12. Process death: không có micro nào sống sót

`VoiceStateStore.restore()` **hạ mọi state đang dở về `IDLE`**. Không lưu trữ "đang nghe" hay "đang chờ xác nhận" xuyên process — micro đã chết cùng process, và khôi phục `LISTENING` sẽ hiện "Đang nghe…" cho một micro không tồn tại.

`VoiceStateSnapshot.idle(now)` là snapshot trung thực khi store không có gì.

---

## 13. Phủ test (§20)

`VoiceScenarios` là harness chạy trên JVM, không cần thiết bị:

| Kịch bản | Assert |
|---|---|
| Parser: lệnh hợp lệ | Trả `Command` với `matchedPhrase` đúng |
| Parser: mơ hồ | Trả `Ambiguous`, **không** trả `Command` |
| Parser: no-match | Trả `Unrecognized` giữ nguyên dấu |
| Router: confirm yes | Tới `CONFIRMING`, **chưa** chạy; yes → chạy một lần |
| Router: confirm no | Không chạy lệnh destructive |
| Router: timeout | 8 s không trả lời → rời `CONFIRMING` |
| Router: không hiểu | Không **bao giờ** chạy lệnh |
| Router: non-destructive | Chạy ngay, **một** lần, kết `SUCCESS` |
| Bất biến confirm | `needsConfirmation ⟺ destructive` |

Tương tác này **không** gọi Android — không `SpeechRecognizer`, không `TextToSpeech`, không `Activity`. Vì vậy "voice chạy được" là khẳng định verify được trên CI, không phải "đã bấm thử trên xe".

---

## 14. Giới hạn nền tảng

- **Không có model tiếng Việt offline trên mọi unit.** `EXTRA_PREFER_OFFLINE` là advisory trên API 23. Unit không có model sẽ cần mạng; không có mạng → `SERVICE_UNAVAILABLE`, **không** retry. Đây là giới hạn của firmware, không phải bug — và nó được báo ra, không bị nuốt.
- **Chất lượng transcript phụ thuộc engine vendor.** Folding dấu xử lý trường hợp engine trả không dấu, nhưng **không thể** sửa engine trả sai hẳn từ. `Ambiguous` + `Unrecognized` là chỗ trung thực để dừng lại.
- **Không wake word.** Không có "Hey TS8": brief không yêu cầu, và một wake word luôn nghe là vi phạm chính ràng buộc "không ghi âm nền".
- **Không có tín hiệu cabin chất lượng cao** (echo cancellation của head unit) — duck nhạc là cách giảm nhiễu duy nhất trong tầm tay.
- **Không test trên thiết bị thật.** Chưa có unit nào bật được recogniser; toàn bộ kết luận trên đối chứng bằng code + `VoiceScenarios` trên JVM.
