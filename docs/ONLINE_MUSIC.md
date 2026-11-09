# Music Online (§12–13)

Music Online bổ sung nguồn phát qua mạng **mà không làm hỏng Local Music**. Tài liệu này ghi hành vi thật, ranh giới pháp lý đã tuân thủ, và trạng thái thật của từng provider — kể cả khi trạng thái đó là `BLOCKED_EXTERNAL_CONFIG`.

---

## 0. Kết luận trạng thái trước khi đọc chi tiết

| Provider | Cần cấu hình? | Trạng thái |
|---|---|---|
| **Internet Radio** (Radio Browser) | **Không** | ✅ production-capable — chạy được ngay trên unit chưa cấu hình gì |
| **Podcast RSS** | Có — URL feed | ⚠️ `BLOCKED_EXTERNAL_CONFIG` khi chưa nhập feed |
| **Navidrome / Jellyfin** (Subsonic API) | Có — server + user + password | ⚠️ `BLOCKED_EXTERNAL_CONFIG` khi chưa nhập |

Theo §21: *"Có ít nhất một online music provider production-capable khi config hợp lệ; nếu thiếu credential phải báo PARTIAL/BLOCKED, không PASS giả."* — Điều kiện này **đạt** nhờ Radio Browser (không cần credential). Hai provider còn lại **báo đúng** `BLOCKED_EXTERNAL_CONFIG` thay vì trả danh sách rỗng.

---

## 1. Ranh giới pháp lý — phần tuyệt đối không làm

Campaign có ràng buộc nguyên văn: *"Không dùng YouTube extractor/scraper, `yt-dlp`, NewPipe extractor, reverse endpoint, tách audio hoặc download YouTube."*

Cách thực thi trong code, không chỉ trong tài liệu:

- `RadioBrowserProvider` **chỉ** gọi `/json/stations/search` trên mirror host — endpoint công bố. Không theo trang riêng của đài, không scrape directory site, không resolve stream URL qua redirect không tài liệu. URL Radio Browser trả về được đưa thẳng cho player và **không gì khác**.
- `NavidromeProvider` dùng **Subsonic API công khai** trên server do **chính người lái** cấu hình.
- `PodcastRssProvider` đọc feed RSS do người lái nhập.
- **Không download audio.** Không có đường nào trong module ghi stream xuống đĩa.
- **Không cache URL stream.** Đây không phải quy ước: `OnlineMusicGateway` **không** lưu URL giữa các lần gọi, và tài liệu của nó ghi rõ cache trong tương lai **phải** từ chối entry đã quá `expiresAtElapsedMs` — đưa player một token bị thu hồi tạo ra stream bắt đầu rồi chết im lặng, không phân biệt được với app hỏng.

---

## 2. Vì sao có facade thay vì UI giữ provider

`OnlineMusicGateway` là entry point **duy nhất** UI gọi, và là nơi **duy nhất** thứ tự provider được quyết định.

Brief cố định thứ tự: **Internet Radio → Podcast RSS → Navidrome/Jellyfin**. Thứ tự được thực thi ở ba màn hình là thứ tự sẽ **bất đồng** giữa chúng trong vòng một tháng. Facade tập trung hai luật khiến tìm kiếm hỗn hợp dùng được trong xe:

1. **Provider hỏng không tốn gì.** Một provider timeout **không được** biến tìm kiếm thành một lần chờ 20 giây. Mỗi lời gọi bị chặn bởi timeout của `HttpFetcherImpl` **và** bởi `CircuitBreaker` riêng của nó; failure đóng góp danh sách rỗng vào aggregate và **một dòng** vào `status`, và tìm kiếm trả về những gì provider còn sống đã sinh ra. **Kết quả một phần là hành vi đúng** ở đây: người lái hỏi một bài hát, không hỏi báo cáo sức khoẻ hệ thống.
2. **Chưa cấu hình thì phải nói ra.** `blockedConfig()` nêu tên provider không có endpoint/credential, để màn hình in `BLOCKED_EXTERNAL_CONFIG` thay vì danh sách rỗng mà người lái đọc là "không có bài đó". Bịa một provider online chạy được trên máy không có cấu hình là đúng kiểu không trung thực mà brief gọi tên.

**Điều cố ý không có ở đây: audio.** `getPlayableSource` trả **URL**; mở nó là việc của `MusicPlaybackService`/`MediaPlayer`, vì trạng thái phát **phải có đúng một chủ** — nếu không, một queue chứa cả item local và online sẽ hiện hai dòng "đang phát" khác nhau.

---

## 3. `CircuitBreaker` — biến "chậm và hỏng" thành "từ chối ngay"

**Failure mode nó chặn.** Một provider down **không fail nhanh** — nó fail **tại timeout**. Với 4 s connect + 4 s read, một provider chết tốn 8 giây, và một tìm kiếm fan-out ba provider tốn đúng chừng đó **mỗi lần gõ Enter**. Trên head unit này, ô tìm kiếm là màn người lái dùng **khi đang chạy**; đơ 8 giây mỗi query không phải "giảm chức năng", nó là **nguy hiểm**.

| Tham số | Giá trị | Lý do |
|---|---|---|
| `failureThreshold` | **2** liên tiếp | Provider timeout hai lần liên tiếp trong một cooldown **không** đang hồi phục giữa chúng |
| `cooldownMs` | mặc định của class | Hết cooldown → half-open, cho **đúng một** probe |
| `clock` | `SystemClock::elapsedRealtime` | **Không bao giờ** wall-clock: RTC của TS8 không đáng tin xuyên khoá điện |

**Half-open cho đúng một probe.** Cho mọi search đang xếp hàng đi qua ở cuối cooldown sẽ dựng lại đúng cơn dồn mà breaker sinh ra để chặn.

**Thread-safe không lock trên hot path.** State là một `AtomicInteger`, clock inject — vừa test được trên JVM, vừa dùng chung an toàn giữa các executor thread. Hai thread đua nhau ở ranh giới half-open có thể cùng được nhận trong cùng một micro-giây; giá là một probe thừa, và đó là lý do không đáng dùng monitor.

**Vì sao counter tự viết, không dùng thư viện.** Hystrix và Resilience4j đều kéo theo đồ thị dependency mà APK này **không gánh nổi** lên head unit có link sideload chậm, và không cái nào cho thứ class này không có: threshold, cooldown, một probe.

---

## 4. Mã lỗi: `when` buộc mọi call site quyết định

`ProviderResult` là **sealed type, không phải exception** — vì failure ở đây là kết quả **bình thường** (người lái trong hầm sẽ sinh một cái mỗi lần search), và code phải `catch` exception để đọc trạng thái dự kiến **luôn** kết thúc bằng một `catch (Throwable)` ở đâu đó nuốt bug thật. `when` trên type này **buộc** mọi call site quyết định UI làm gì cho từng code.

| `ProviderFailureCode` | UI nên làm |
|---|---|
| `BLOCKED_EXTERNAL_CONFIG` | Câu chỉ người lái vào Settings nhập địa chỉ **— không retry được** |
| `TIMEOUT` | Nút "Thử lại" |
| `NETWORK_ERROR` | Nút "Thử lại" |
| `UNAUTHORIZED` | Câu kiểm tra lại tài khoản/mật khẩu |
| `NOT_FOUND` | "Bài này không còn" |
| `UNSUPPORTED` | (vd. browse trên RSS) — ẩn hành động |
| `PARSE_ERROR` | Câu báo phản hồi không đọc được |
| `RATE_LIMITED` | Chờ rồi thử lại |
| `CIRCUIT_OPEN` | **Không gì cả** — provider đã biết là down, hỏi lại ngay là cách một tìm kiếm tốn 20 giây |

---

## 5. `OnlineMusicRuntime`: cầu Android

`OnlineMusicGateway` **cố ý đồng bộ** và không biết gì về Android: mọi provider call đều block — đúng cho background pool, **chết người** cho ô tìm kiếm. `OnlineMusicRuntime` là nơi **duy nhất** trong app:

1. dựng gateway từ settings,
2. đưa call của nó ra khỏi main thread và post kết quả về,
3. implement `StreamResolver` để player local mở được stream **mà không** phải phụ thuộc vào bất kỳ online provider nào.

### 5.1 Gateway dựng lại mỗi khi settings đổi

Người lái vừa gõ địa chỉ server thì mong lần search **tiếp theo** dùng nó, không phải lần khởi động sau. Provider là object rẻ (một base URL và một circuit breaker), nên dựng lại không tốn gì; cache qua một lần đổi setting là cách một màn hình hỏi host người lái **đã thay**.

`configKey()` ghép `serverUrl | user | serverKind | feedUrl`. Gateway được key theo chuỗi này chứ **không** bị invalidate bằng callback: settings là một file `SharedPreferences` mà code khác ghi trực tiếp, nên thời điểm **đáng tin** để nhận ra thay đổi là lúc gateway được hỏi tới. Counter `ticket` bỏ câu trả lời in-flight sinh ra dưới cấu hình cũ, để một DNS lookup chậm **không** render dòng của server cũ dưới tên server mới.

### 5.2 Provider luôn được dựng, kể cả khi trống

`build()` **luôn** dựng `NavidromeProvider` dù base URL rỗng. Đó là điều khiến `blockedConfig()` **nêu tên** được nó. Bỏ nó đi khi chưa cấu hình sẽ tạo đúng failure §13 cấm — kết quả rỗng đọc thành "không có bài đó".

### 5.3 Online tắt → local hoàn toàn không bị ảnh hưởng

`AppSettings.onlineEnabled = false` làm `search` trả aggregate rỗng, `status` trả danh sách rỗng, và resolver của player **từ chối**. Local music **không bị ảnh hưởng chút nào** — ràng buộc *"provider lỗi không ảnh hưởng local music"* của §13 được đẩy xa hơn một bậc.

Callback của `search` **luôn** chạy trên main thread và **luôn** được gọi **đúng một lần** — kể cả khi online tắt, nơi nó nhận snapshot rỗng thay vì im lặng. Một màn hình chờ callback không bao giờ tới sẽ hiện spinner mãi mãi, không phân biệt được với treo.

---

## 6. Playback: URL được resolve lúc chạm, không phải mỗi `next()`

**Vì sao queue giữ URL đã resolve chứ không phải provider id.** Player mở track bằng **một chuỗi**, và re-resolve mỗi `next()` là một request mạng mỗi bài trên kết nối có thể là hotspot điện thoại. `toTrack` resolve **một lần**, lúc row được chạm, và URL là thứ vào queue.

**Vì sao resolve lúc chạm.** Một kết quả search chỉ mang *gợi ý* là có URL; contract của gateway là URL thật **phải được cấp lại trước khi phát**, vì một đài có thể đổi giữa lúc search và lúc chạm. Resolve lúc chạm là điểm **sớm nhất** mà câu trả lời còn đúng, và **muộn nhất** trước khi player cần.

`streamResolver()` xử lý ba trường hợp:
- `track.source` đã là `http…` → trả `Playable` ngay (đường **bình thường**, vì `toTrack` đã resolve trước khi queue).
- có `providerId` và source không phải URL → `resolveSource()`.
- không có `providerId` → `Unavailable("Bài này không còn nguồn phát", retryable = false)`.

`isLive` được suy từ `durationMs <= 0L` cho đường URL, và từ `source.value.isStreaming` cho đường resolve. Đài không có độ dài, nên mọi track từ Radio Browser có `durationMs = 0` và `isLive = true` — model local đã coi `0` là "không biết", nên queue hỗn hợp tính tiến trình **không cần** nhánh đặc biệt.

**URL hết hạn.** `freshSource()` kiểm `expiresAtElapsedMs` **trước** khi trả: quá hạn → `NOT_FOUND` với câu *"Địa chỉ phát đã hết hạn, cần tải lại"*, không phải một stream chết sau ba giây.

---

## 7. Không gian id: online không đụng USB

`Track` id là `Long`. Hai không gian được tách bằng **bit cao**:

| Nguồn | Bit | Định nghĩa |
|---|---|---|
| USB path hash | **62** | `TrackIds.USB_BIT = 1L shl 62` (`:core:music-engine-local`) |
| Online | **61** | `OnlineMusicRuntime.ONLINE_BIT = 1L shl 61` |

`onlineTrackId(providerId, trackId)` = FNV-1a trên `"$providerId\u0000$trackId"`, mask `ONLINE_BIT - 1`, rồi OR bit 61.

- **Bit 61, không phải 62.** Dùng 62 sẽ cho id online và id USB **đụng nhau** — hai không gian phải rời nhau vì đúng lý do đã ghi trong `TrackIds.forUsbPath`.
- **NUL làm dấu phân cách** để `("ab","c")` và `("a","bc")` ra hash khác nhau.
- **Deterministic theo `(providerId, trackId)`** → cùng một đài có cùng id xuyên các lần search. Đó là điều cho phép queue highlight nó, và giữ id trùng chuỗi giữa hai provider **tách nhau**.

---

## 8. Giới hạn mạng và kích thước

| Hằng | Giá trị | Lý do |
|---|---|---|
| `CONNECT_TIMEOUT_MS` | 4 000 | Head unit modem chậm; 4 s đủ cho một TLS handshake bình thường |
| `READ_TIMEOUT_MS` | 4 000 | Tổng một provider ≤ 8 s; ba provider **song song** vẫn trong ngân sách breaker cho phép |
| `MAX_BODY_BYTES` | 1 048 576 (1 MiB) | Chặn endpoint hỏng/độc hại làm tràn heap 128 MB |
| `DEFAULT_LIMIT` | 30 | Vài trăm KB qua modem, đủ lấp màn search một lần |
| `STATUS_TTL_MS` | (throttle) | Probe mọi provider mỗi frame là tự DoS chính mình |

`HttpFetcherImpl` **ném** nếu bị gọi trên main thread, thay vì treo launcher.

---

## 9. MediaSession / audio focus — không regression

Âm thanh online đi qua **cùng** `MusicPlaybackService` và `MediaPlayer` như local. Không có player thứ hai, không có `MediaSession` thứ hai. Vì vậy:

- Audio focus xử lý giống hệt local — cùng `VehicleAudioBridge`, cùng listener `onCallEnded` / `onAudioRouteChanged`.
- Duck cho Voice Instant (xem `VOICE_INSTANT.md` §11) áp dụng **cả** khi đang phát online: nó scale `STREAM_MUSIC`, không phân biệt nguồn.
- Chuyển online → local và local → online chỉ là **thay item trong cùng queue**, không dựng lại service.

---

## 10. Phủ test (§20)

`OnlineMusicGatewayTest` và `CircuitBreakerTest` chạy trên JVM, không cần mạng thật:

| Kịch bản §20 | Cách phủ |
|---|---|
| Search / browse / play / buffer / retry | Gateway fan-out + `ProviderResult` từ fake provider |
| Mất mạng giữa stream | `HttpFetcher` fake ném `NETWORK_ERROR` → `ProviderResult.Failure` retryable |
| Provider timeout | `TIMEOUT` code, không kéo dài aggregate |
| Chuyển online → local / local → online | Test id space: `OnlineTrackIdTest` (6 test) pin `ONLINE_BIT` và tính rời nhau với `USB_BIT` |
| MediaSession / audio focus | Cùng service — test hành vi `VehicleAudioBridge` |

---

## 11. Giới hạn và external blocker

- **Không có credential production.** Repo không có server Navidrome/Jellyfin, không có feed podcast. Đây là **BLOCKED_EXTERNAL_CONFIG hợp lệ**, không phải lỗi — và code **báo ra** thay vì trả danh sách rỗng.
- **Radio Browser phụ thuộc mirror.** `de1.api.radio-browser.info` là một mirror có thể down; project khuyến nghị client dùng tên ổn định hoặc rotate. `baseUrl` nhận từ constructor nên Settings và test có thể trỏ nơi khác; **chưa** có logic rotate mirror tự động.
- **Không test trên thiết bị.** Toàn bộ đối chứng bằng code + unit test JVM. Chưa có ảnh chụp màn hình nhạc online trên OLED C2.
- **Không có download/offline cache.** Cố ý — xem §1.
- **Chất lượng stream do nguồn quyết định.** Không có transcoding; một đài bitrate cao sẽ tốn modem.
