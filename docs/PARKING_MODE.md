# Parking Mode (§18–19)

Parking Mode quyết định khi nào launcher hiện màn đồng hồ đỗ xe. Câu hỏi khó **không phải** là "làm sao biết xe dừng" mà là **"làm sao biết mà không kết luận sai"**. Một cái đồng hồ đỗ xe hiện ra ở đèn đỏ là một tính năng người lái sẽ tắt đi và không bật lại.

Tài liệu này ghi hành vi thật của resolver, của runtime và của màn hình.

---

## 1. Ràng buộc gốc

Brief §18 mở đầu bằng câu cấm trực tiếp: *"Không dùng GPS speed = 0 làm điều kiện duy nhất vì xe dừng đèn đỏ hoặc GPS mất tín hiệu cũng cho kết quả tương tự."*

Hai trường hợp đó khác nhau **ở bản chất**, không phải ở ngưỡng:

| Tình huống | Speed | Điều gì thật sự xảy ra |
|---|---|---|
| Đèn đỏ | ≈ 0, chính xác tốt | Xe đang chạy, chỉ là đang dừng |
| Trong hầm | `null` | **Không biết** xe đang làm gì |
| Bãi đỗ | ≈ 0, chính xác tốt, **ổn định lâu** | Xe đỗ |

Chỉ dòng cuối được phép mở màn hình. Một ngưỡng tốc độ không phân biệt được ba dòng này — nó chỉ phân biệt hai. Vì vậy resolver **không** có nhánh "speed == 0 → PARKED"; nó có **năm trạng thái** và một bộ **tín hiệu độc lập**.

---

## 2. Năm trạng thái

```text
DRIVING              đang chạy, hoặc dừng đủ ngắn để là giao thông
STOPPED_TEMPORARILY  dưới ngưỡng dừng, chưa đủ lâu để là ứng viên
PARKING_CANDIDATE    có bằng chứng đỗ, nhưng bị chặn hoặc không được phép vào
PARKED               xác nhận đỗ; màn hình có thể hiện
UNKNOWN              không đủ thông tin đáng tin để nói gì
```

**Vì sao `STOPPED_TEMPORARILY` tồn tại.** Đây là chỗ trung thực cho một đèn đỏ. Phương án thay thế — hai trạng thái `DRIVING` / `PARKED` — buộc **mọi giây đứng yên** phải được xếp vào một trong hai, và một detector buộc phải chọn **luôn** kết thúc bằng việc hiện đồng hồ đỗ ở ngã tư.

**Vì sao `PARKING_CANDIDATE` tồn tại.** Tín hiệu đồng ý xe đã đỗ, nhưng **có blocker** (cuộc gọi, camera lùi, đang dẫn đường) hoặc **setting tự động đang tắt** giữ bản đồ trên màn. Đó là một sự thật riêng, không phải "gần PARKED".

**`UNKNOWN` là câu trả lời trung thực.** `ParkingDecision.UNKNOWN` là object có thật, không phải null: mọi đường failure resolve về nó thay vì về `PARKED` hoặc `DRIVING` trông hợp lý. Một caller quên kiểm sẽ **không có gì để hiện** thay vì hiện sai màn.

### 2.1 Độ tin cậy

| Mức | Ý nghĩa |
|---|---|
| `HIGH` | Đủ để **tự động mở** màn hình |
| `MEDIUM` | Bằng chứng thật, nhưng cùng reading xảy ra ở mọi đèn đỏ dài → chỉ là candidate |
| `LOW` | Có tín hiệu nhưng yếu |
| `NONE` | Chỉ dành cho `UNKNOWN` — một quyết định **không bao giờ** nhận độ tin cậy về "không có gì" |

**`showsParkingScreen` = `state == PARKED && confidence == HIGH`.** Đó là **một** điều kiện, không phải hai chỗ kiểm riêng.

### 2.2 Lý do — cho diagnostics

`ParkingReason` có 10 giá trị, **mỗi giá trị ứng với đúng một nhánh** trong `resolve()`. Vì vậy một báo cáo lỗi nói `CALL_ACTIVE` trỏ tới **một chỗ** trong code.

`VEHICLE_GEAR_PARK`, `VEHICLE_IGNITION_OFF`, `VEHICLE_PARKING_BRAKE`, `MANUAL`, `GPS_HEURISTIC`, `SPEED_ABOVE_THRESHOLD`, `INSUFFICIENT_SIGNALS`, `CALL_ACTIVE`, `REVERSE_ACTIVE`, `NAVIGATION_ACTIVE`.

---

## 3. Tín hiệu vào

`ParkingSignals` — **mọi** trường là nullable để "không biết" **biểu diễn được**:

| Nhóm | Trường | Nguồn thật hiện nay |
|---|---|---|
| Tín hiệu xe | `vehicleGearPark`, `vehicleAccOn`, `vehicleParkingBrake`, `vehicleIgnitionOff` | ⚠️ **không có bridge** — xem §10 |
| GPS | `speedMps`, `speedAccuracyMeters`, `locationStable` | `LocationFix` + `LocationStability` |
| Ngữ cảnh nền | `navigationActive`, `callActive`, `reverseCameraActive` | `NavigationState`, `AudioManager` mode |
| Lệnh người lái | `explicitManualRequest` | Nút trong Quick Settings / màn đỗ xe |
| Thời gian | `elapsedRealtimeMs` | `SystemClock.elapsedRealtime()` |

Nullable chứ không `Boolean = false`: `false` nghĩa là "tín hiệu nói KHÔNG", `null` nghĩa là "không có tín hiệu". Gộp hai thứ này là cách một detector bịa ra "không đỗ" khi thực ra nó không biết.

---

## 4. Priority — thứ tự thực thi thật trong `resolve()`

Thứ tự này là **code**, không phải ý định:

```
1.  Snapshot cũ (age > 5 s)        → UNKNOWN
2.  Manual EXIT (explicit == false) → DRIVING / STOPPED_TEMPORARILY, LOW, MANUAL
3.  Đang PARKED:
      3a. chạy liên tục ≥ 6 s      → DRIVING, HIGH, SPEED_ABOVE_THRESHOLD
      3b. manual enter ghi lại lý do → giữ PARKED
      3c. còn lại                  → giữ PARKED  (chống GPS spike)
4.  Manual-exit latch còn hiệu lực:
      4a. chạy liên tục ≥ 6 s      → nhả latch, đi tiếp
      4b. còn lại                  → DRIVING / STOPPED_TEMPORARILY, LOW, MANUAL
5.  Tốc độ đo được mâu thuẫn bằng chứng (và không có manual enter)
                                    → DRIVING, HIGH, SPEED_ABOVE_THRESHOLD
6.  Tín hiệu xe (ignition-off / gear-P / parking-brake)
                                    → automatic(...)
7.  Manual ENTER                    → PARKED, HIGH, MANUAL
8.  GPS heuristic (speed + dwell + locationStable)
                                    → automatic(...) hoặc candidate
```

Ba điểm quan trọng:

- **Bước 2 đứng trước và đứng một mình.** Một lần manual *thoát* là chỉ thị **duy nhất** không bao giờ được bằng chứng nó đang phủ nhận lấn át — nếu không, một reading cần số sẽ làm nút Thoát trông như **chết**.
- **Bước 5 tách "bằng chứng" khỏi "chỉ thị".** Một đường bus còn đọc "P" trong khi xe chạy 30 km/h là frame cũ → tốc độ thắng. Nhưng nó **không** phủ nhận một *chỉ thị*, nên check này bị bỏ qua khi người lái vừa yêu cầu vào đỗ.
- **Manual ENTER đứng trên heuristic và không cần dwell.** Đó là lệnh, không phải suy luận.

---

## 5. `automatic()` — ba cổng, một chỗ

Mọi nhánh *suy ra* (bước 6 và 8) đều đi qua **cùng một** hàm, áp ba cổng theo thứ tự:

1. **Blocker** — `callActive` → `CALL_ACTIVE`; `reverseCameraActive` → `REVERSE_ACTIVE`; `navigationActive` → `NAVIGATION_ACTIVE`. Có blocker → `PARKING_CANDIDATE`, MEDIUM, evidence kèm câu giải thích (`"... Nhưng đang có cuộc gọi nên không tự vào."`).
2. **Setting tự động** — `allowAutoEnter == false` → `PARKING_CANDIDATE`, evidence `"Chế độ tự động vào đang tắt."`
3. **Ngưỡng độ tin cậy** — chỉ `HIGH` mới thành `PARKED`.

**Vì sao gom một chỗ.** Nếu mỗi nhánh (cần số, ignition, heuristic) tự kiểm blocker, sớm muộn một nhánh **sẽ bỏ qua** một luật mà nhánh khác tuân.

`allowAutoEnter` là **tham số**, không phải field: người lái có thể đổi setting khi đang đỗ, và tick tiếp theo **phải** tôn trọng giá trị mới. Cache trong constructor sẽ làm checkbox trông như không có tác dụng cho đến khi process restart.

---

## 6. GPS heuristic — vì sao cần ba điều kiện

Một kết luận cần **đồng thời**:

| Điều kiện | Ngưỡng | Vì sao |
|---|---|---|
| Có fix **dùng được** | `accuracyMeters ≤ 40 m` | Fix tệ hơn ngưỡng **không nói gì** về chuyển động |
| Đứng yên liên tục | `candidateDwellMs = 20 s` | Dưới ngưỡng này là giao thông |
| Vị trí **ổn định** | `LocationStability` trả `true` | Dwell dưới ngưỡng dùng để chứng minh xe **không di chuyển**, không chỉ là GPS im lặng |

Đỗ **xác nhận** cần thêm: `parkDwellMs = 120 s` → `HIGH`; từ 20 s đến 120 s → `MEDIUM` (candidate).

Thêm `stopSpeedMps = 1.0` (≈3.6 km/h) làm ngưỡng dừng và `exitSpeedMps = 3.0` (≈10.8 km/h) làm ngưỡng thoát.

### 6.1 Không có fix → không kết luận

`noFixGraceMs = 15 s`. Nếu không có fix dùng được lâu hơn 15 giây, dwell đã "giữ" **không bao giờ được quan sát** → bị xoá, để một hầm dài không đỗ xe theo độ dài của nó. Mẫu về lại thì đoạn đếm **khởi động lại**.

Câu evidence khi không có fix: *"Không có fix GPS; mất tín hiệu không có nghĩa là đã đỗ."*

### 6.2 Snapshot cũ → UNKNOWN

`maxSnapshotAgeMs = 5 s`. Một mẫu cũ không mô tả xe **lúc này**; resolver trả `UNKNOWN` với evidence `"Mẫu tín hiệu đã cũ (N ms); không dùng để kết luận."`

### 6.3 `LocationStability`

Cửa sổ `25 000 ms`, drift tối đa `10.0 m`, span tối thiểu `8 000 ms`. Trả `null` khi chưa đủ mẫu — và `null` **lan thẳng** vào `ParkingSignals.locationStable`, nơi nó biến `stable == true` thành `false` chứ không phải mặc định true.

---

## 7. Thoát: không vì một spike

Ba luật:

1. **Đang PARKED** → chỉ thoát khi `exitSpeedMps ≥ 3.0` **liên tục** `exitHoldMs = 6 000 ms`. Một xóc tốc độ ngắn giữ nguyên trạng thái, evidence: *"Có xóc tốc độ ngắn nhưng chưa đủ thời gian giữ; vẫn giữ trạng thái đỗ."*
2. **Mất fix khi đang PARKED** không đổi gì — nhánh `parked` xử lý trước nhánh GPS.
3. **Manual exit latch** giữ cho tới khi xe chạy đủ lâu, để xe đứng yên ngay sau khi bấm Thoát **không** bị kéo vào lại.

---

## 8. Runtime: `ParkingRuntime`

- **Tick 1 Hz** (`TICK_MS = 1 000`). Khớp độ phân giải dwell của resolver; nhanh hơn là tiêu frame budget của main thread cho một màn không di chuyển.
- **`FIX_FRESH_MS = 5 000`** — fix cũ hơn 5 giây không được đưa vào. Năm mẫu trượt ở 1 Hz: đủ để vượt một đoạn trời xấu, đủ ngắn để xe vừa lái ra khỏi bãi mà mất tín hiệu không bị báo là "đang đứng".
- **Dùng timestamp của pipeline, không dùng `now`**: fix đến một giây trước là **đo** một giây trước.
- **`callActive`** đọc `AudioManager`'s mode qua `graph.audioBridge.currentRoute() == AudioRoute.CALL` — cách API 23 báo cuộc gọi đang diễn ra **mà không cần telephony permission**.
- **`reverseCameraActive`** hiện **luôn `false`** — xem §10.
- **Persist chỉ khi decision đổi** (state / confidence / reason / evidence). Một xe đứng 10 phút sinh 600 decision giống hệt; ghi đĩa 600 lần là vô nghĩa.
- **Resolver ném → `UNKNOWN`.** Resolver thuần và không nên ném; nếu nó ném, câu trả lời trung thực là "không có câu trả lời", không phải một màn hình cũ.

### 8.1 Mở màn hình: một lần cho mỗi lần đỗ

`maybeOpenParkingScreen()` yêu cầu **đồng thời**:
- `state == PARKED`,
- `showsParkingScreen` (PARKED + HIGH, mà resolver chỉ cấp khi setting bật),
- `reason != MANUAL` (nút manual đã tự mở màn rồi),
- `autoOpened == false`.

`autoOpened` được nhả **ngay khi state rời PARKED** — đó là điều giữ người lái vừa bấm "chạm để quay lại" **không** bị ném lại vào màn một giây sau trong khi xe vẫn đứng.

---

## 9. Màn hình Parking

`ParkingModeActivity`: đồng hồ lớn (`parkingClock`, 96 sp), ngày tháng (`parkingDate`), artwork/nền tĩnh, nhạc compact và bottom navigation. Chạm vào `parkingPane` hoặc `parkingExitHint` → thoát.

Đúng §19:
- **Không tự phát nhạc.** Không có call nào tới playback từ Activity này.
- **Không tự mở app.**
- **Lưu reason/confidence** để diagnostics (qua `ParkingStateStore`).

### 9.1 Evidence không có toạ độ

`ParkingDecision.evidence` là câu tiếng Việt ngắn cho màn diagnostics. Nó **cố ý** không chứa toạ độ và **không chứa vị trí nào cả** — type sinh ra nó **không bao giờ nhận một position**, và `ParkingStateStore` ghi chuỗi này xuống đĩa.

Đó là cách thực thi ràng buộc *"Không log dữ liệu vị trí cá nhân ngoài diagnostics mode."* ở mức **type system**, không phải ở mức quy ước: "log evidence làm lộ chỗ xe đỗ" trở thành **không thể**, chứ không chỉ là "không được khuyến khích".

---

## 10. Giới hạn — phần quan trọng nhất của tài liệu này

### 10.1 Không có bridge tín hiệu xe

`ParkingSignals` có bốn trường tín hiệu xe, resolver có ba nhánh dùng chúng, và `ParkingReason` có ba giá trị cho chúng. Nhưng **`ParkingRuntime` không set trường nào trong bốn trường đó** — chúng luôn `null` trong production.

Lý do: ràng buộc campaign *"Không reverse API MCU mới trong campaign này nếu chưa có contract/evidence."* Chưa có contract/evidence cho bridge tín hiệu xe trên TS8.

Hệ quả **thực tế**: trên unit này, priority #1 của §18 (tín hiệu xe đáng tin cậy) **không hoạt động**. Đường đi thật là:

```
manual (nếu người lái bấm)  →  GPS heuristic 20 s + ổn định  →  HIGH ở 120 s
```

Đó chính xác là điều §18 gọi là *"Heuristic GPS/time chỉ là fallback"* và §19 yêu cầu phải nói rõ: **đây là chế độ heuristic + manual, không phải phát hiện dựa trên tín hiệu xe.**

Resolver **sẵn sàng**: khi có bridge, chỉ cần `ParkingRuntime` điền bốn trường đó và hành vi tự đúng — không cần sửa resolver. Đó là lý do kiến trúc giữ chúng.

### 10.2 Camera lùi không có nguồn

`reverseCameraActive = false` hard-coded trong runtime. Trên unit này, camera lùi do hệ thống đảm nhiệm và **không có API launcher đọc được** trong phạm vi campaign. Blocker `REVERSE_ACTIVE` tồn tại và test được, nhưng **không bao giờ trigger** trong production.

Hệ quả: nếu người lái vào số R ở bãi và chờ lâu, heuristic vẫn có thể đạt HIGH. Rủi ro thực tế thấp (thời gian lùi thường < 120 s), nhưng **không bằng không**.

### 10.3 `locationStable` phụ thuộc receiver

`LocationStability` dựa trên drift giữa các fix. Một receiver rất ồn ào (hoặc một bãi đỗ dưới tán cây) sẽ **không bao giờ** trả `true` → xe đỗ thật sẽ dừng ở `STOPPED_TEMPORARILY` thay vì `PARKED`. Đó là hành vi **đúng theo hướng an toàn** (không hiện đồng hồ sai), nhưng người lái sẽ phải bấm nút manual.

### 10.4 Không test trên thiết bị

Toàn bộ trên đối chứng bằng code + `ParkingScenarios` trên JVM. Chưa có log tick thật từ xe, chưa có ảnh chụp màn đỗ trên OLED C2.

---

## 11. Phủ test (§20)

| Kịch bản §20 | Cách phủ |
|---|---|
| Manual enter / exit | `explicitManualRequest = true/false` → PARKED/MANUAL; rồi latch giữ |
| GPS speed 0 ở đèn đỏ **không** auto-enter | dwell < 20 s → `STOPPED_TEMPORARILY`, không PARKED |
| GPS mất **không** tự kết luận parked | `speedMps = null` → `UNKNOWN`, reason `INSUFFICIENT_SIGNALS` |
| Di chuyển lại → thoát có debounce | > exitSpeed liên tục 6 s → DRIVING; spike ngắn → giữ |
| Không có vehicle signal → hiển thị limitation | §10.1 tài liệu hoá; `ParkingScenarios` chạy không có tín hiệu xe |

`ParkingScenarios` chạy trên JVM với clock inject — không cần `Context`, không cần GPS.
