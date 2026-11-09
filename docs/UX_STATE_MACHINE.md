# UX state machine và transitions (§16–17)

§16 không yêu cầu mười màn hình — chúng đã có. Nó yêu cầu **điều hướng giữa chúng là chuyển trạng thái trên một state**, để ba thứ từng là phỏng đoán trở thành sự thật:

1. Màn nào là primary khi một overlay đang mở.
2. **Back** làm gì.
3. "Đang dẫn đường trong khi người lái ở Home" nghĩa là gì.

Không có máy trạng thái, `MapActivity` và `HomeActivity` mỗi cái tự quyết cục bộ, và một người lái bắt đầu dẫn đường từ Home rồi chạm tab nhạc sẽ để lại máy mô tả một trạng thái **không ai đang ở**.

---

## 1. Máy **không** sở hữu engine nào

`AppStateMachine` là kiến trúc **thuần**: nó giữ một enum và một stack overlay. Map renderer, navigator, router và `MediaPlayer` tiếp tục sống trong `AppGraph` và playback service.

Đó là điều khiến §16's *"bottom navigation đổi primary destination nhưng không hủy map/music engine"* mang tính **cấu trúc** chứ không phải lời hứa: **không có gì ở đây để huỷ**.

---

## 2. Mười trạng thái primary

```text
HOME              MAP_BROWSE         SEARCH
DESTINATION_PREVIEW  ROUTE_PREVIEW    NAVIGATING
MUSIC_EXPANDED    APP_DRAWER         QUICK_SETTINGS
PARKING
```

## 3. Bốn overlay độc lập

```text
VOICE_LISTENING   VOICE_CONFIRMATION   MAP_IMPORT_PROGRESS   ERROR_DIALOG
```

**Vì sao là stack riêng.** Thanh voice có thể hiện trên bản đồ trong khi route đang preview, và progress import đứng trên Map Management trong khi màn bên dưới vẫn dùng được. Gộp chúng vào enum primary sẽ nhân 10 trạng thái × 4 và biến "Back đóng overlay trước" thành một switch 40 nhánh.

`pushOverlay()` **không đẩy trùng**: thanh voice re-render mỗi lần state đổi và nếu không kiểm sẽ đẩy `VOICE_LISTENING` một lần mỗi frame, khiến **một** lần Back phải đóng **vô số** cái.

---

## 4. Bảng chuyển hợp lệ

Bảng viết dưới dạng **các nguồn được phép của đích**, không phải tập cặp — thêm state sau này chỉ có **một dòng** phải sửa, và reviewer đọc được "cái gì được đi trước `NAVIGATING`" ở một chỗ.

| Đích | Nguồn hợp lệ |
|---|---|
| `HOME` | **mọi** |
| `MAP_BROWSE` | **mọi** |
| `SEARCH` | HOME, MAP_BROWSE, DESTINATION_PREVIEW, ROUTE_PREVIEW |
| `DESTINATION_PREVIEW` | MAP_BROWSE, SEARCH |
| `ROUTE_PREVIEW` | DESTINATION_PREVIEW, MAP_BROWSE, NAVIGATING |
| `NAVIGATING` | DESTINATION_PREVIEW, ROUTE_PREVIEW, MAP_BROWSE, HOME, MUSIC_EXPANDED, APP_DRAWER, QUICK_SETTINGS |
| `MUSIC_EXPANDED` | HOME, MAP_BROWSE, NAVIGATING, APP_DRAWER, QUICK_SETTINGS, PARKING |
| `APP_DRAWER` | HOME, MAP_BROWSE, MUSIC_EXPANDED, NAVIGATING, QUICK_SETTINGS |
| `QUICK_SETTINGS` | HOME, MAP_BROWSE, MUSIC_EXPANDED, APP_DRAWER, NAVIGATING |
| `PARKING` | **chỉ** HOME, MAP_BROWSE |

### 4.1 Vì sao `NAVIGATING` nhận nhiều nguồn

Đây là yêu cầu §16's *"Khi quay Home trong lúc navigation, Home hiển thị navigation-aware layout"* và *"mở Music, player mở rộng nhưng route/navigation tiếp tục nền"*. Người lái **phải** đi được từ `NAVIGATING` sang Home / Music / App Drawer / Quick Settings **mà không** kết thúc route — và **phải** quay lại được `NAVIGATING`.

### 4.2 Vì sao `PARKING` chỉ nhận hai nguồn

Màn đỗ xe là panel full-bleed người lái thoát bằng cách chạm. Đi tới nó từ một màn đã có overlay dày sẽ **chồng** một panel toàn màn lên một panel đã có.

### 4.3 Vì sao `MUSIC_EXPANDED` không nhận màn đang chọn route

Khớp luật: mở nhạc **không được** làm rối một route đang chọn.

### 4.4 Chuyển không hợp lệ bị **từ chối**, không ném

`moveTo()` trả `false` thay vì ném hoặc âm thầm chấp nhận. Một màn yêu cầu chuyển động mà máy từ chối là có bug, và **launcher crash trước mặt người lái vì một lỗi bookkeeping nội bộ là kết quả tệ hơn** một dòng log.

Trường hợp thật có tồn tại: callback navigator đến sau khi người lái đã sang app drawer yêu cầu `MAP_BROWSE` từ `APP_DRAWER` — hợp lệ; nhưng cái đến **sau** `PARKING` thì không.

---

## 5. `syncNavigation` — cầu một chiều

Navigator là **thẩm quyền** về việc route có đang chạy. `syncNavigation(navigationState)` là adapter một chiều từ từ vựng của nó sang của UI:

| `NavigationState` | → UI state |
|---|---|
| `DESTINATION_PREVIEW` | `DESTINATION_PREVIEW` |
| `ROUTE_CALCULATING`, `ROUTE_PREVIEW` | `ROUTE_PREVIEW` |
| `NAVIGATING`, `REROUTING`, `ARRIVED` | `NAVIGATING` |
| `SEARCHING` | `SEARCH` |
| `IDLE` | `MAP_BROWSE` nếu đang ở DESTINATION_PREVIEW / ROUTE_PREVIEW / NAVIGATING; **ngược lại giữ nguyên** |

**`IDLE` giữ nguyên ở mọi màn khác.** Kết thúc route **không được** kéo người lái ra khỏi màn nhạc.

### 5.1 Trống §16 đã đóng trong đợt này

Máy có `addListener` / `removeListener` / `notify`, nhưng **không ai gọi `addListener`** — nên `notify()` là code chết. Đã sửa: `HomeActivity` đăng ký `stateListener` trong `onStart()` và gỡ trong `onStop()`.

**Trường hợp cần nó.** Guidance có thể **kết thúc giữa hai mẫu GPS** — người lái tới nơi và navigator rơi về `IDLE` — và trên một head unit trong bãi xe, luồng fix có thể đứt cả phút. Một strip chỉ do `onFix` điều khiển sẽ tiếp tục hiện "200 m · rẽ phải" trên con đường người lái đã rời, đọc thành guidance **không chịu dừng**. Transition của chính máy là thời điểm thẩm quyền, và nó tới Home **dù receiver có đang giao hay không**.

**Đăng ký `post`, không gọi inline.** Navigator notify từ executor của chính nó và `syncNavigation` chạy trên hop đó, nên listener gọi thẳng có thể bị vào **off main thread** và sau đó chạm view off thread.

**Chỉ đăng ký trong lúc màn hiển thị.** Strip là thứ duy nhất Home vẽ từ máy §16; một listener để lại sau lưng màn đã stop sẽ tiếp tục hỏi navigator cho một strip không ai thấy.

---

## 6. Back

`popTopOverlay()` là §16's *"Back đóng overlay trước"*. Xử lý Back của màn **chỉ chạy khi hàm này trả `false`** — đó là điều chặn một lần Back dành cho micro đóng luôn bản đồ bên dưới.

---

## 7. Process recreation

### 7.1 Chỉ state primary, và chỉ cái có thể đúng lại

| State đã lưu | Khôi phục thành | Lý do |
|---|---|---|
| `SEARCH` | `MAP_BROWSE` | Query chưa từng persist |
| `DESTINATION_PREVIEW` | `MAP_BROWSE` | Destination chưa từng persist |
| `ROUTE_PREVIEW` | `MAP_BROWSE` | Route chết cùng process cũ |
| `NAVIGATING` | `NAVIGATING` nếu `navigationStillRunning`, ngược lại `MAP_BROWSE` | Route **được tính lại** từ checkpoint đã persist — nên nó **có thể** thật sự còn chạy |
| Khác | giữ nguyên | — |

**Overlay stack không bao giờ được khôi phục.** Một lần import không có handle archive sau khi bị kill, và nguyên nhân của một error dialog đã mất; khôi phục cái nào cũng thành một spinner **không bao giờ dừng**.

Cùng nguyên tắc `VoiceStateStore` áp cho overlay voice (xem `VOICE_INSTANT.md` §12).

### 7.2 `restoreAfterProcessDeath()` chạy đúng một lần

Mọi màn gọi nó từ `onCreate`; process **đã** restore rồi thì màn tạo sau **không được** ghi đè live state bằng thứ trên đĩa trước khi người lái bắt đầu di chuyển. **Màn đầu tiên** được tạo sau cold start sở hữu lần restore; các màn còn lại thừa hưởng kết quả.

**Vì sao ở đây, không ở `Ts8LauncherApp`.** Application chạy trước mọi window và cho **mọi** lần khởi động process **kể cả service-only**; restore ở đó sẽ quyết state UI cho một process có thể **không bao giờ vẽ**. Gọi từ một màn, nó chạy đúng khi **có thứ gì để khôi phục vào**.

### 7.3 Ghi đồng bộ

`save()` dùng `commit` trong store, không `apply`. Write xảy ra **khi màn đổi** — đúng lúc head unit có thể mất điện.

---

## 8. Transitions: `ScreenTransition`

§17 có bốn luật ngắn, dễ thoả từng màn và **không thể** giữ bằng tay. Tất cả nằm trong một object:

| Luật §17 | Cách thực thi |
|---|---|
| *"Chỉ dùng alpha/translation ngắn, nhẹ trên TS8."* | Cross-fade của platform. **Không** xoay, **không** scale, **không** dài hơn fade mặc định. Cross-fade là transition **rẻ nhất** mà vẫn đọc thành thay đổi chứ không phải glitch |
| *"Không animation liên tục."* | Không animator object, không loop. Window transition chạy **một lần** khi window đổi, rồi hết. Không gì trong class này có thể tiếp tục vẽ |
| *"Không block input trong transition."* | `overridePendingTransition` kết thúc trước khi input trở lại vì chính **window manager** animate hai surface **đã vẽ**; Activity không bao giờ chờ nó. Không có delayed action nào giữ một cờ UI phải kiểm |
| *"Tôn trọng reduced-animation/low-performance mode."* | Đọc **cả hai**: animator duration scale của platform (người lái có thể đặt 0) **và** `Performance.SAVER` của launcher. **Một trong hai** tắt → cắt tức thì |

**Vì sao đọc `Settings.Global`, không `Settings.System`.** Animator duration scale nằm trong `Global` từ API 17; đọc `System` sẽ **âm thầm** trả default và lựa chọn "không animation" của người lái bị **bỏ qua trên mọi màn**.

**Không cache `kind()`.** Đọc preference tại chỗ transition sắp xảy ra (một click, một lần chạm nav): người lái có thể đổi `Tiết kiệm` trong Settings và **lần đổi màn kế tiếp** phải tôn trọng nó.

**Không có `res/anim`.** Dùng `android.R.anim.fade_in` / `fade_out` — không thêm resource, không thêm frame.

---

## 9. Phủ test

`AppStateMachineTest` — **26 test** trên JVM (không `Context`, không `Activity`, `StateStore` fake):

- Mọi chuyển hợp lệ đi qua.
- Chuyển không hợp lệ bị **từ chối** và **không** đổi state.
- `moveTo` tới state hiện tại trả `true` (idempotent).
- `syncNavigation` cho từng `NavigationState`, gồm `IDLE` giữ nguyên trên MUSIC_EXPANDED.
- `pushOverlay` không đẩy trùng; `popTopOverlay` đóng đúng cái trên cùng.
- Restore: mỗi state degrade đúng; `NAVIGATING` phụ thuộc `navigationStillRunning`.
- `restoreAfterProcessDeath` chỉ chạy một lần.
- Listener nhận đúng `previous`/`current`/overlays.

---

## 10. Giới hạn

- **Không test trên thiết bị.** Máy trạng thái và transition đối chứng bằng unit test JVM + đọc code. **Chưa** có ảnh chụp 7 màn hình sau repair, chưa có video chuyển màn trên OLED C2.
- **`ScreenTransition` cần `Activity`** nên không unit test được trên JVM; luật của nó được kiểm bằng đọc code, không bằng test.
- **Reduced-animation phía platform** phụ thuộc ROM: một số ROM head unit không expose developer options, nên driver chỉ còn công tắc `Tiết kiệm` của launcher.
