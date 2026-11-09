# Kiến trúc Voice Offline (Vosk) — TS8

Tài liệu này mô tả **kiến trúc đang có trong code** của batch voice offline, theo
§3 và §15 của `TS8_VOICE_OFFLINE_VOSK_PILOT_PROMPT.md`. Mọi khẳng định dưới đây
trỏ tới một lớp có thật trong repo; không có phần nào là thiết kế dự kiến.

> **Trạng thái:** `SANDBOX_PASS_DEVICE_PENDING`. Chưa có kết quả đo trên TS8 thật
> và tài liệu này không tuyên bố có.

---

## 1. Vấn đề gốc

Voice Instant hiện tại chỉ có **một** đường để nghe người lái: `android.speech.
SpeechRecognizer`. Firmware của TS8 không có `RecognitionService` nào, nên
`SpeechRecognizer.isRecognitionAvailable()` trả `false` và mọi lần bấm micro đều
kết thúc ở `Voice service unavailable`. Đây không phải lỗi cấu hình hay quyền —
không có gì để bind tới.

Hệ quả là feature **chưa từng chạy** trên thiết bị đích, dù code chạy đúng trên
máy có Google app. Sửa bằng cách thêm một provider đọc model Kaldi từ bộ nhớ
trong, không cần mạng.

## 2. Kiến trúc sau batch này

```
VoiceTrigger (app)  — xin quyền RECORD_AUDIO tại lần bấm micro
  │
  ├─ VoiceOverlayHost (app, theo Activity)  — renderer, không giữ engine
  └─ VoiceInstantController (app, process-level)  — chủ sở hữu một lượt
        │
        ├─ VoiceFeedback (app)            TTS tiếng Việt + duck nhạc
        ├─ VoiceCommandExecutor (app)     thực thi lệnh trên Navigation/Music/Audio
        │
        └─ RecognitionProviderRouter (core:voice-offline)
              ├─ AndroidSystemRecognitionProvider (app)  SpeechRecognizer
              └─ VoskOfflineRecognitionProvider    (app)  AudioRecord + Vosk
                    │
                    ├─ VadGate          (core)  khi nào câu nói kết thúc
                    ├─ VoskResult       (core)  đọc JSON Vosk
                    └─ VoiceModelStore  (core)  pack nào đang active

  Sau khi có transcript:
    VoiceRouter (core:navigation-core)
      ├─ IntentParser   (core, tái sử dụng nguyên vẹn)
      ├─ VoiceAnswer    (core, cho câu trả lời đồng ý/huỷ)
      └─ CommandExecutor (app)
```

Hai nguyên tắc giữ nguyên từ kiến trúc cũ và không được vi phạm:

- **Không engine nào nằm trong Activity.** Controller nằm trong application
  graph, overlay chỉ render `VoiceRouter.snapshot()`. Một màn bị recreate không
  được kết thúc phiên micro đang chạy.
- **Không có parser thứ hai.** `IntentParser` là nơi duy nhất biến câu tiếng Việt
  thành `VoiceCommand`. `CommandGrammar` có một bảng câu **viết tay** — nhưng bảng
  đó không có nghĩa riêng của nó: `CommandGrammarTest` đối chiếu từng câu với
  `IntentParser` qua `SelfCheck.disagreements()`, nên một câu lệch nghĩa là test
  đỏ. Câu mang tên địa điểm thì **sinh** từ `places.db`, không viết tay.

## 3. Đường đi của một lần bấm micro

1. `VoiceTrigger` kiểm `RECORD_AUDIO`; chưa có thì xin tại đây, không xin lúc
   boot (`app/src/main/AndroidManifest.xml` có ghi chú lý do).
2. `VoiceInstantController.start()` đặt `interactionActive`, gọi
   `feedback.prepare()` **trước** khi mở micro — nhạc đang phát là nguồn sai
   transcript lớn nhất trong cabin.
3. Dựng `RecognitionRequest` với grammar của pack bản đồ đang active, rồi
   `providers.newSession(request, callback).begin()`.
4. Router chọn provider theo policy (mục 4) và gọi `start()` trên nó.
5. Provider báo `onPartial` trong lúc người lái nói — chỉ overlay, có throttle.
6. Provider báo `onFinal(hypotheses, confidence)` hoặc `onError(error, detail)`.
7. Controller chọn hypothesis mà `IntentParser` xử lý được
   (`chooseHypothesis`), rồi `router.onTranscript(chosen)`.
8. `VoiceRouter` parse → `EXECUTING` → `CommandExecutor` → `SUCCESS`/`ERROR`,
   kèm câu tiếng Việt mà `VoiceFeedback` đọc lên.
9. Mọi nhánh kết thúc đi qua `finishInteraction()`: đóng route, trả âm lượng nhạc,
   lưu snapshot, huỷ timer.

## 4. Router: ba policy, tối đa một lần chuyển

`RecognitionPolicy` (`core/voice-offline/.../RecognitionProvider.kt`):

| Policy | Hành vi |
|---|---|
| `OFFLINE_ONLY` | Chỉ Vosk. Không gọi mạng trong mọi trường hợp. Không có model READY ⇒ `SERVICE_UNAVAILABLE`, **không** rơi sang system. |
| `AUTO` | Dùng system nếu `isUsable()`; không thì đi thẳng sang Vosk. Fallback khi system lỗi thuộc bảng dưới. |
| `SYSTEM_ONLY` | Hành vi trước pilot, giữ cho diagnostics và test hồi quy. Có model READY vẫn không dùng. |

Policy được đọc **tại thời điểm quyết định**, không cache lúc khởi tạo: người lái
đổi trong Settings giữa hai câu phải có tác dụng ở câu sau.

Bảng fallback (`VoiceError.allowsOfflineFallback()`), có test cho **cả 12** giá trị
enum chứ không chỉ các trường hợp được nhắc:

| Lỗi | Chuyển sang Vosk? | Lý do |
|---|---|---|
| `SERVICE_UNAVAILABLE` | có | không có recogniser, hoặc nó ngắt kết nối vĩnh viễn — đúng ca mà pilot tồn tại để giải quyết |
| `NETWORK_ERROR` | có | tạm thời; model trả lời đúng câu đó mà không cần mạng |
| `LANGUAGE_UNAVAILABLE` | có | có recogniser nhưng không có tiếng Việt |
| `MIC_BUSY` | **không** | Vosk cần đúng thiết bị đó; chuyển sẽ hỏng y hệt |
| `PERMISSION_DENIED` | **không** | không có quyền thì cả hai đều không có âm thanh |
| `CANCELLED` | **không** | người lái đã rời; chạy lệnh của họ lúc này còn tệ hơn không làm gì |
| `NO_SPEECH` / `TIMEOUT` | **không** | không có gì để nhận dạng lại |
| `NO_MATCH` | **không** | đã nghe và không giải mã được; một lần no-match khi provider còn khoẻ không được tiêu hai lần nhận dạng |
| `INTERNAL_ERROR` | **không** | trạng thái không xác định, không đẩy model native vào đó |
| `NOT_UNDERSTOOD` / `COMMAND_FAILED` | **không** | không phải chuyện khả dụng của recogniser |

`Session.switches` đếm số lần đổi provider cho **một request**, chặn ở 1. Lỗi
đến muộn từ provider đã bị bỏ bị loại bằng so sánh tham chiếu (`active !== from`),
vì một số firmware Android 6 giao `onError` sau khi đã `cancel()`.

## 5. Vì sao Vosk chạy trên thread riêng

`VoskOfflineRecognitionProvider` sở hữu **một** `ExecutorService` đơn luồng
(`ts8-vosk`) cho việc nạp model, vòng decode và `getFinalResult`. Model Kaldi
33 MB mở trên main looper là ANR, không phải một frame chậm.

Danh sách điều bắt buộc của provider, và chỗ code thực hiện:

| Điều kiện (§7) | Thực hiện |
|---|---|
| `AudioRecord` 16 kHz, mono, PCM 16-bit | `SAMPLE_RATE_HZ = 16_000`, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT` |
| nguồn `VOICE_RECOGNITION` nếu chạy được | `SOURCES = [VOICE_RECOGNITION, MIC]`, thử lần lượt, ghi lại nguồn nào thắng |
| không giữ mic vô hạn | `VadGate` với silence 1,5 s / trần 15 s / no-speech 2,5 s |
| luôn release micro | một `finally` trong `Session.run()` gọi `closeMicrophone()` trên mọi nhánh |
| không chạy inference trên main thread | toàn bộ `run()` trên `worker` |
| không hai provider cùng giữ mic | router chỉ `start()` một provider tại một thời điểm; `releaseActive()` trước khi chuyển |

Thứ tự tắt micro là `stop()` rồi `release()`: trên Android 6, release một
`AudioRecord` đang ghi để lại input device mở cho tới khi process chết.

## 6. Model lazy-load và unload

- Model **không** được nạp lúc launcher khởi động. `VoiceModelStore.snapshot()`
  đọc verdict đã cache, không chạm đĩa.
- Lần nạp đầu xảy ra trong `Session.run()`, trên worker, và thời gian được đo
  vào `VoiceDiagnostics.modelLoadMs`.
- Model được giữ lại `DEFAULT_UNLOAD_IDLE_MS = 120_000` ms sau câu cuối, rồi được
  giải phóng trên chính worker (`unloadNow`), nên không thể đua với decode.
- `onModelChanged()` xoá `unusableReason` và giải phóng model cũ: thay pack trong
  Settings phải có tác dụng mà không cần khởi động lại process.

## 7. Vòng đời `AudioRecord` và audio focus

Thứ tự cố định, và mọi bước đều có đường thoát:

```
feedback.prepare()        duck nhạc (VehicleAudioBridge.requestFocus(NAVIGATION))
openMicrophone()          AudioRecord(VOICE_RECOGNITION → MIC), startRecording
settleBeforeCapture()     250 ms cho đuôi bài nhạc đã duck
captureLoop()             đọc frame 20 ms, đẩy vào Vosk, hỏi VadGate
finally                   closeMicrophone(): stop() → release()
finishInteraction()       feedback.end(): trả âm lượng
```

`settleBeforeCapture` bị ngắt bởi `abandoned`, nên một lần Back trong 250 ms đó
không làm micro mở thêm.

## 8. Partial (§8)

- Giữ nguyên partial thật cho **cả hai** provider.
- Chỉ overlay: `VoiceInstantController.onPartial` đẩy caption, throttle 250 ms, bỏ
  partial trùng.
- Không parse, không execute, không ghi đĩa. Chỉ final mới vào `IntentParser`.
- `VoiceDiagnostics` **không có** trường partial, đúng yêu cầu "không lưu".

## 9. Confirmation (§9)

Prompt chỉ xuất hiện khi executor trả `CommandOutcome.NeedsConfirmation` — hiện là
ca hai địa điểm trùng tên. Lệnh thường (map, nhạc, âm lượng, mở app rõ ràng) chạy
thẳng, không hỏi, theo đúng §9.

Khi prompt đang mở:

- `VoiceRouter.onTranscript` **bỏ** mọi transcript — câu thường không thể chạy một
  lệnh đang chờ xác nhận.
- `VoiceInstantController` mở micro **riêng cho câu trả lời**
  (`maybeListenForAnswer`), với grammar chỉ gồm 15 câu đồng ý/huỷ.
- `VoiceRouter.answer()` là cửa vào duy nhất đọc câu trả lời; khớp toàn câu sau khi
  fold, **phủ định trước khẳng định**, nên "không đồng ý" không bị đọc thành đồng ý.
- Tối đa 2 lần thử (`MAX_ANSWER_ATTEMPTS`), mỗi lần vẫn chịu deadline 8 s; hết thì
  prompt tự đóng.
- Hai nút chạm **vẫn còn**. Nói và chạm đi qua cùng `applyAnswer()` nên không thể
  lệch nhau ở phần dọn dẹp.
- `router.rearm()` trả lại trọn cửa sổ 8 s cho prompt sau khi câu hỏi đã được đọc.

## 10. TTS (§10)

`VoiceFeedback` giữ Android TTS với `Locale("vi","VN")`.
`canSpeak()` trả `false` khi engine chưa khởi tạo xong hoặc không có tiếng Việt —
không có đường nào báo TTS thành công giả. Khi không nói được, overlay vẫn hiện
câu chữ, và đó là kênh phản hồi thật. Không có neural TTS nào được thêm.

Chấm dứt câu được kiểm trong `DiagnosticsActivity`: dòng `voice` ghi
`tts_ready` / `tts_missing` **kèm** policy đang dùng.

## 11. Diagnostics (§11)

`VoiceDiagnostics` (21 trường, tất cả nullable) → `toLines()` xuất dạng
`voice.<key>=<value>`:

```
voice.provider / policy / switches / fallbackTrail
voice.model.state / version / size / sha256 / abi / loadMs / warm / peakNativeHeap
voice.audio.initOk / sampleRate / source
voice.latencyMs / endReason
voice.error / errorCode / transcript / confidence
```

Phần **routing** do router hợp vào: `provider`, `policy`, `switches`,
`fallbackTrail` không provider nào tự thấy được, nên `RecognitionProviderRouter.
lastDiagnostics()` chọn bản ghi của **đúng request mà route mô tả** rồi áp
`RouteSummary` lên đó. Chỉ bản ghi của cùng provider mới được stamp — nếu không,
một lượt system chạy sau một lượt Vosk sẽ mang policy và số lần chuyển của lượt
kia.

Không có âm thanh, không có toạ độ, không có partial trong bản ghi.
`NO_AUDIO_NOTE` nói thẳng điều đó cho người đọc file export.

## 12. Ranh giới module

| Module | Chứa | Không chứa |
|---|---|---|
| `core:voice-offline` | provider seam, router, validator, store, grammar, VAD, diagnostics, JSON | `Context`, `AudioRecord`, `SpeechRecognizer` |
| `core:navigation-core` | `VoiceRouter`, `IntentParser`, `VoiceAnswer`, `VoiceCommand` | microphone, TTS |
| `app` | hai provider thật, controller, overlay, Settings, Diagnostics | luật quyết định provider |

`core:voice-offline` không phụ thuộc Android framework API nào ngoài những gì
đã có trong `java.*`/`org.json` thay thế — nên toàn bộ router, validator, store,
VAD và grammar test được trên JVM thuần, không cần Robolectric.

## 13. Điểm vào duy nhất

Mọi nút Voice trong app đi qua `BaseScreenActivity.voice`
(`VoiceOverlayHost`) → `AppGraph.voiceInstant()` → **một**
`VoiceInstantController` cho cả process. Không Activity nào tự tạo recogniser.
`AppGraph` dựng controller trong `synchronized` block nên hai màn cùng gọi lần
đầu không thể sinh hai controller.

## 14. Tài liệu liên quan

- [VOICE_MODEL_PACK.md](VOICE_MODEL_PACK.md) — định dạng gói và cách cài
- [VOICE_MODEL_LICENSE.md](VOICE_MODEL_LICENSE.md) — bằng chứng license
- [VOICE_OFFLINE_SANDBOX_TEST.md](VOICE_OFFLINE_SANDBOX_TEST.md) — kết quả test
- [VOICE_OFFLINE_DEVICE_CHECKLIST.md](VOICE_OFFLINE_DEVICE_CHECKLIST.md) — checklist trên TS8
- [VOICE_OFFLINE_KNOWN_LIMITATIONS.md](VOICE_OFFLINE_KNOWN_LIMITATIONS.md) — giới hạn
- [VOICE_INSTANT.md](VOICE_INSTANT.md) — kiến trúc Voice Instant trước batch này
