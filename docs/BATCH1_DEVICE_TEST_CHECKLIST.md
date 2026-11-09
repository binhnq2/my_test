# Batch 1 — device test checklist (TS8 / OLED C2, Android 6, 1024×600)

For the person with the unit in front of them. Nine steps, each one action and
one thing to look at. Nothing here can be checked on the build host — `adb
devices` is empty and no API 23 emulator can start — so this list is the whole
of the evidence for anything marked *device*.

**Install first**

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected SHA-256 of that file:
`b0a26b623c22ad5db8226f0edca9f64b8e731f61275cf020e5d005cd04a97561`

If `adb install` reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE` or the package is
already the default launcher, see `docs/ADB_INSTALL_AND_RECOVERY.md` before
uninstalling anything.

---

## 1. Seven screens — the launcher looks like `UI_design.png`, not flat dark

Open each in turn: **Home → Map → Music → Search → Apps → Quick Settings →
Parking** (Parking via Quick Settings).

- [ ] Every screen has navy/blue-tinted surfaces with visible card edges, not
      one flat black fill.
- [ ] Cards, the bottom bar and the map chrome each read as a separate layer.
- [ ] Text has three distinguishable weights (title / body / caption).
- [ ] Nothing overlaps: 1024×600, no clipped buttons, no scrolling on any screen.
- [ ] No blur, no moving background, no animated panel.

📸 Take one photo per screen. Those seven photos are the acceptance evidence.

---

## 2. Status duplication

On Home, Map, Music, Search, Apps and Quick Settings:

- [ ] The **vendor** bar at the top still shows clock, date, battery, Wi-Fi,
      Bluetooth — and the launcher does **not** draw a second copy of any of
      them.
- [ ] Home shows three things at the top only: a weather chip on the left, a
      microphone button, and a Quick Settings button on the right.
- [ ] Map shows only the microphone and Quick Settings (no weather chip).
- [ ] Music, Search, Apps and Quick Settings show no clock, no battery, no
      Wi-Fi, no Bluetooth from the launcher.
- [ ] Content on the four non-map screens starts *below* the vendor bar — nothing
      is hidden under it.

**Expected weather chip text:** `Chưa có thời tiết` (no provider in this build).
A number would be a bug.

---

## 3. Map Browse — no floating music card

On **Map** with no route running (just opened, or after ending a route):

- [ ] The map fills the width. There is **no** music card floating anywhere on it.
- [ ] Zoom, recentre and layers buttons are visible and tappable.

Then start a route and watch the same screen:

- [ ] A compact music card appears on the right.
- [ ] The maneuver arrow, ETA/overview, mute and **end route** buttons are all
      visible and can all be tapped — **none of them is under the card**.
- [ ] End the route: the compact card disappears, the map is full-width again.

⚠️ This is the single most important check in this list. §5 requires the
controls to stay tappable while guidance runs; the layout reserves 248 dp
(236 dp card + 12 dp gap) for exactly that, and this is the only way to confirm
the reserve is right on the real panel.

---

## 4. Map error shell and Map Management

To force the error state: rename or remove the active map pack directory on the
unit (or copy the APK to a unit with no pack), then open **Map**.

- [ ] No white screen and no blank grid. Instead: a title, a Vietnamese reason,
      `Mã lỗi: MAP_…`, and `Gói đang dùng: …`.
- [ ] Three buttons are present: **Thử lại**, **Cài đặt bản đồ**, **Chi tiết**.
- [ ] **Cài đặt bản đồ** opens Map Management (it must not crash, and must not
      stay on the error screen).
- [ ] **Chi tiết** opens a dialog with the full reason and the error code.
- [ ] On the progress side, if the map never draws, after **~12 seconds** the
      screen flips from `Đang chờ khung hình đầu tiên…` to a failure with
      `Mã lỗi: MAP_FIRST_FRAME_TIMEOUT`. Record which of the two you saw:

      - [ ] the map drew before 12 s (the phases worked as designed)
      - [ ] it timed out → **this is a finding, not a pass**; note the code

- [ ] Also open Map Management from **Settings → Bản đồ** and from **Quick
      Settings → Quản lý bản đồ**. Both must open the same screen.

---

## 5. Music — keyboard stays down, online music is findable

Open **Music**.

- [ ] The keyboard is **down** on open. The search field is not focused and has
      no blinking cursor.
- [ ] Tapping the search field brings the keyboard up.
- [ ] Back / tapping elsewhere dismisses it again.
- [ ] Three source pills are visible: **Trên thiết bị | USB | Trực tuyến**.
- [ ] Tap **Trực tuyến**: three providers are listed — **Radio**, **Podcast**,
      **Navidrome**/Jellyfin — **even with nothing configured**.
- [ ] An unconfigured provider shows a `Thiết lập` action. Tapping it goes to the
      setup section rather than doing nothing.
- [ ] No provider vanishes from the list because it lacks configuration.

Already-verified in sandbox: the list is built from settings, not from a search
result. This step is about the IME and the layout only.

---

## 6. Volume capability

Open **Quick Settings**.

- [ ] Drag the volume slider. The number ends up at the **level the unit actually
      took**, not the level you dragged to.
- [ ] If the unit ignores the write: the slider snaps back and the label reads
      `Không có tác dụng · N/M` after the second movement.
- [ ] If the unit reports a fixed volume: the slider is disabled and the label
      reads `Không hỗ trợ trên thiết bị này`.
- [ ] Say **"tăng âm lượng"** twice via Voice Instant. The spoken reply names the
      level you can actually hear, or says it did not take effect.

Record which case your unit is in: ☐ SUPPORTED ☐ NO_EFFECT ☐ UNAVAILABLE

This is the §8 question that no host test can answer — the source now reads back
what it wrote instead of assuming.

---

## 7. Manual Parking

In **Quick Settings**, tap **Chế độ đỗ xe**.

- [ ] The screen switches to Parking: large clock, date, light background, bottom
      nav.
- [ ] With **nothing playing**, there is **no** music card — Parking looks
      exactly like the design.
- [ ] Start a track, then come back to Parking: a compact card appears. It shows
      what is playing and pause/next work; tapping it does **not** exit the
      screen.
- [ ] Music does **not** start playing by itself.
- [ ] No app opens by itself.
- [ ] Tap anywhere (or press Back) → you leave Parking and land back on a normal
      screen.
- [ ] The clock updates on the minute without the screen flickering.
- [ ] In **Settings**, the Auto Parking row reads `Chưa khả dụng` and has **no**
      switch. Auto Parking must never claim to work.

---

## 8. Diagnostics export

**Settings → Chẩn đoán**.

- [ ] The screen shows: version, build hash (12 hex chars), current mode, active
      map pack, map phase, music service/provider state, voice availability,
      parking state, volume capability.
- [ ] **Không có** a password, token, URL with credentials, or your GPS position.
- [ ] Tap **Xuất chẩn đoán**: a result line appears with an absolute path.
- [ ] Open that path in a file manager and confirm the file is there and its
      contents match the screen.
- [ ] Send that file back — it is the fastest way to see the unit's real state.

---

## 9. Route state survives a screen change

- [ ] Start a route to a destination.
- [ ] Switch to **Music** with the bottom bar, play something, switch back to
      **Map**.
- [ ] The route is still there, still moving, and the music is still playing —
      no restart, no re-route, no blank map.

---

## What to send back

1. The seven screenshots from step 1.
2. The diagnostics file from step 8.
3. The answers to step 4 (drew vs. timed out) and step 6 (which volume case).
4. Anything from steps 2, 3, 5, 7, 9 that did not behave as written.

Batch 1 stays `SANDBOX_PASS_DEVICE_PENDING` until exactly these arrive and match.
