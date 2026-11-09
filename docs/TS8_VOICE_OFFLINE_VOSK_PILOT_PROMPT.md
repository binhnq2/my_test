# TS8 Voice Offline Pilot - Vosk-first Implementation Prompt

Bạn là implementation/repair agent trong sandbox. Hãy tích hợp thử nghiệm Voice Instant OFFLINE vào launcher hiện tại, build/test/tự repair, nhưng không tuyên bố chạy tốt trên TS8 khi chưa có device evidence.

## 1. Bối cảnh khóa
- Target: OLED C2 / TS8, Android 6, API 23, landscape, CPU Cortex-A7/ARMv7, RAM thấp.
- Voice hiện tại chỉ dùng Android `SpeechRecognizer`; TS8 thật báo `Voice service unavailable`.
- Giữ kiến trúc hiện có: `SpeechRecognizerAdapter → VoiceInstantController → CommandRouter → IntentParser → VoiceFeedback`.
- `IntentParser` tiếng Việt hiện có phải được tái sử dụng, không viết parser thứ hai.
- Không triển khai wake word, nghe nền liên tục, GPT/Grok, backend STT hoặc firmware/MCU trong batch này.
- Push-to-talk là interaction duy nhất.

## 2. Mục tiêu
Tạo provider nhận dạng tiếng Việt offline nhẹ để Voice Instant vẫn chạy khi:
- ROM không có RecognitionService.
- Không có Internet.
- Người dùng cần lệnh Map/Music/Apps cơ bản.

Ưu tiên kỹ thuật:
1. Vosk Android + model tiếng Việt nhỏ, hợp lệ để phân phối.
2. Nếu model Vosk tiếng Việt không đạt license/ARMv7/hiệu năng, đánh giá Sherpa-ONNX streaming như candidate thay thế, nhưng không nhúng cả hai engine production trong cùng APK.
3. Không dùng Whisper.cpp cho TS8 trong batch này.

## 3. Kiến trúc provider
Tạo boundary chung:

```text
RecognitionProvider
├── AndroidSystemRecognitionProvider
├── VoskOfflineRecognitionProvider
└── RecognitionProviderRouter
```

Router policy:
- `OFFLINE_ONLY`: ưu tiên Vosk; không gọi mạng.
- `AUTO`: dùng Android recognizer nếu available và healthy; nếu unavailable/disconnected/network failure thì fallback Vosk.
- `SYSTEM_ONLY`: giữ hành vi cũ để diagnostics/test.
- Không fallback lặp vô hạn; một request chỉ chuyển provider tối đa một lần.

Mọi Voice button toàn app phải gọi cùng shared entry point/controller; không tạo recognizer riêng theo Activity.

## 4. Model pack độc lập
Không đóng model lớn trực tiếp vào APK chính. Tạo package:

```text
voice-models/vi-VN/<version>/
├── metadata.json
├── model/
├── command_grammar.json
├── checksums.sha256
└── LICENSES.md
```

`metadata.json` phải có: schemaVersion, engine, locale, modelVersion, minSdk, supportedAbis, size, sha256, license, source URL và vocabulary mode.

Tạo Voice Model Management trong Settings:
- Trạng thái: NOT_INSTALLED, VALIDATING, READY, INCOMPATIBLE, CORRUPT, FAILED.
- Import từ USB/storage; staging + checksum + atomic activation.
- Remove/replace model.
- Không yêu cầu Internet sau khi model đã cài.
- Nếu chưa có model hợp lệ, hiện CTA `Cài mô hình giọng nói offline`, không crash.

## 5. Model và license gate
Trước khi tích hợp model:
- Xác minh model thật sự hỗ trợ tiếng Việt, ARMv7/API 23 và offline.
- Ghi chính xác source, version, checksum, license và quyền phân phối thương mại.
- Không dùng model không rõ nguồn hoặc license.
- Không giả lập model/test data rồi báo production-ready.
- Nếu không tìm được Vietnamese small model hợp lệ, vẫn hoàn thiện provider/model-pack framework và báo `BLOCKED_MODEL_ASSET`.

## 6. Nhận dạng lệnh offline
MVP commands:
- Mở bản đồ; về trang chủ.
- Mở nhạc; phát/dừng nhạc; bài tiếp theo; bài trước.
- Tăng/giảm âm lượng.
- Mở điện thoại; mở ứng dụng <tên>.
- Tìm <địa điểm>; dẫn đường đến <địa điểm>.

Dùng command grammar/vocabulary giới hạn cho lệnh cố định.
Với địa điểm:
- Lấy POI phổ biến từ active `places.db`.
- Tạo vocabulary giới hạn theo vùng, có cap rõ để không tăng RAM/quá chậm.
- Normalize có dấu/không dấu nhưng không giả định model ngoại ngữ sẽ phiên âm đúng tiếng Việt.
- Nếu POI mơ hồ, mở destination choices; không tự chọn bừa.

## 7. Audio pipeline
- Dùng `AudioRecord` 16 kHz, mono, PCM 16-bit, nguồn `VOICE_RECOGNITION` nếu hoạt động.
- Khi bắt đầu: request audio focus, duck/pause Music, đợi ngắn rồi capture.
- Có VAD/silence timeout; không giữ mic mở vô hạn.
- Khi kết thúc/cancel/error: release microphone và restore audio chính xác.
- Không chạy inference trên main thread.
- Không để Map/Music bị ANR khi load model hoặc infer.

## 8. Partial transcript
- Giữ partial results thật cho cả System và Offline provider.
- Partial chỉ cập nhật overlay, có debounce; không parse/execute.
- Chỉ final transcript mới đi vào `IntentParser`.
- Không lưu partial transcript/history mặc định.

## 9. Confirmation policy
Không yêu cầu chạm xác nhận cho lệnh an toàn, confidence cao:
- Home/Map/Music, play/pause/next/previous, volume, mở app rõ ràng.

Chỉ xác nhận khi:
- Nhiều địa điểm/app cùng tên.
- Đổi destination khi đang navigation.
- Gọi điện hoặc action nhạy cảm.
- Confidence thấp.

Nếu cần xác nhận, hỗ trợ `đồng ý`, `hủy`, `không` bằng giọng nói; nút chạm vẫn tồn tại.

## 10. TTS offline thực dụng
- Giữ Android TTS nếu tiếng Việt available.
- Nếu không có Vietnamese TTS, dùng tone + text và audio prompt đóng gói cho câu cố định; không báo TTS thành công giả.
- Không thêm neural TTS model nặng trong batch này.

## 11. Diagnostics và đo hiệu năng
Ghi và export:
- Provider selected/fallback reason.
- Model state/version/size/checksum/ABI.
- Model load time, peak memory estimate, recognition latency.
- Audio init result, sample rate, timeout/error code.
- Final transcript, confidence nếu engine cung cấp; không lưu audio mặc định.

Performance gate ban đầu:
- Không OOM/ANR trong sandbox tests.
- Model lazy-load; không load khi launcher khởi động nếu Voice chưa dùng.
- Có unload/release policy khi low-memory.
- Không làm tăng APK chính bằng model asset.

## 12. Sandbox tests
- Build debug APK, minSdk 23, include `armeabi-v7a` native library.
- Unit tests: provider routing, single fallback, parser, confirmation, model validation, checksum, state machine.
- Integration tests bằng WAV tiếng Việt hợp pháp/tự tạo: 15 command cố định, có dấu/không dấu transcript path.
- Test missing/corrupt/incompatible model, mic busy, permission denied, cancel, timeout.
- Không dùng test synthetic để tuyên bố độ chính xác thực tế trong cabin.

## 13. Device pilot checklist
Tạo checklist ngắn cho người dùng test trên TS8 thật:
- Cài/validate model từ USB.
- Cold/warm model load.
- 15 command khi xe đứng yên, nhạc tắt.
- 15 command khi nhạc đang phát và phải duck/restore.
- Chạy offline hoàn toàn.
- Ghi latency, command đúng/sai, crash/ANR, RAM/process kill.
- Test 5 POI Đà Nẵng từ `places.db`.

## 14. Acceptance và trạng thái
Chỉ được báo:
- `SANDBOX_PASS_DEVICE_PENDING`
- `BLOCKED_MODEL_ASSET`
- `SANDBOX_FAIL`
- `BLOCKED`

Không báo `DEVICE_PASS`, không gọi Voice offline production-ready trước khi có kết quả TS8.

## 15. Output
- Source + APK debug + SHA-256.
- `docs/VOICE_OFFLINE_ARCHITECTURE.md`
- `docs/VOICE_MODEL_PACK.md`
- `docs/VOICE_MODEL_LICENSE.md`
- `docs/VOICE_OFFLINE_SANDBOX_TEST.md`
- `docs/VOICE_OFFLINE_DEVICE_CHECKLIST.md`
- `docs/VOICE_OFFLINE_KNOWN_LIMITATIONS.md`
- Báo cáo cuối: root cause, engine/model decision, files changed, build/tests, APK/hash, model/license evidence, performance, blockers và device checklist.

Clarifications before implementation:

1. Physical-device ownership

You do not have access to the physical TS8 device.

Do not attempt to claim, simulate or infer physical-device verification.
Do not require ADB, TS8 screenshots or TS8 logs to complete the sandbox phase.

Your responsibility is:
- implementation;
- sandbox build and tests;
- APK and SHA-256;
- in-app diagnostics;
- a device checklist for the user.

The user will independently install and verify the APK on TS8.

Allowed final statuses remain:
- SANDBOX_PASS_DEVICE_PENDING
- BLOCKED_MODEL_ASSET
- SANDBOX_FAIL
- BLOCKED

Never report DEVICE_PASS.

2. Recognition error taxonomy

Do not map all recognition failures to SERVICE_UNAVAILABLE.

Preserve distinct states at minimum:
- SERVICE_UNAVAILABLE
- NETWORK_ERROR
- LANGUAGE_UNAVAILABLE
- NO_MATCH
- MIC_BUSY
- PERMISSION_DENIED
- TIMEOUT
- CANCELLED
- INTERNAL_ERROR

SERVICE_UNAVAILABLE is only for:
- no RecognitionService;
- isRecognitionAvailable() == false;
- provider bind/creation failure;
- provider disconnected or permanently unusable.

3. Provider fallback policy

Fallback from AndroidSystemRecognitionProvider to VoskOfflineRecognitionProvider
only when:
- system recognition service is unavailable;
- service is disconnected;
- required language is unavailable;
- network is unavailable or fails and an offline model is READY.

Do not fallback when:
- microphone permission is denied;
- microphone is busy;
- the user cancels;
- the request times out because the user did not speak;
- the recognizer returns NO_MATCH once while remaining healthy.

A voice request may change provider at most once.

4. Provider lifecycle

- All Android SpeechRecognizer API calls must run on the main thread.
- Always call destroy() when the system recognizer is no longer needed.
- Vosk and Android providers must never hold the microphone concurrently.
- Always stop and release AudioRecord on success, cancel, timeout and error.
- Release native recognizer/model resources according to the documented
  low-memory and idle policy.
- Do not leak Activity context into a long-lived voice provider.

5. Transcript behavior

Normalization only improves transcripts that already contain correct Vietnamese
words with or without accents.

Do not claim that an English acoustic model can reliably transcribe Vietnamese.

Partial results:
- update overlay only;
- debounce UI updates;
- never execute commands;
- never persist by default.

Only final results may enter IntentParser.

6. Model evidence

Do not report a model as suitable only because Vosk supports Vietnamese in
general.

The exact selected model must have:
- a real download/source location;
- a version;
- checksum;
- Vietnamese language evidence;
- ARMv7/API 23 compatibility evidence;
- license text;
- evidence that commercial redistribution is permitted.

If any item is missing, return BLOCKED_MODEL_ASSET rather than embedding an
unverified model.

Continue implementation after applying these clarifications.