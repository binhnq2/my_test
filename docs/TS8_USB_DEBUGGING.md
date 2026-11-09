# TS8 USB Debugging Troubleshooting Guide

## Problem

USB debugging is enabled on the TS8 head unit (Settings → Developer Options →
USB Debugging = ON), but the PC does not recognize the device. `adb devices`
shows nothing or shows "unauthorized" / "offline".

## Root Causes (most common first)

### 1. USB Cable — Data vs Charge-Only

**This is the #1 cause.** Many USB cables that come with car head units,
power banks, or cheap accessories are **charge-only** — they have power
pins but no data pins. The TS8 shows "USB Debugging ON" but no data flows.

**Test:** Plug a USB flash drive into the TS8's USB port. If the TS8 does
not detect it, the port or cable may be charge-only. Plug a phone with
the same cable into a PC — if the PC doesn't see it, the cable is
charge-only.

**Fix:** Use a known data cable (the one that came with a Samsung/Xiaomi
phone, or any cable rated for data transfer).

### 2. USB Mode — MTP vs PTP vs ADB

The TS8 may default to MTP (Media Transfer Protocol) mode. On some vendor
ROMs, MTP and ADB conflict — the device shows in Windows Device Manager
as an MTP device but never offers the ADB interface.

**Fix on the TS8:**
1. Connect USB to PC
2. Pull down the notification bar
3. Look for "USB connection" / "USB charging" notification
4. Tap it and select "Transfer files" (MTP) or "PTP" (Photo Transfer)
5. If a dialog appears "Allow USB debugging?" → tap **OK**

**If no notification appears:**
- Some TS8 ROMs hide the USB mode notification. Try:
  - Settings → Storage → Menu → USB computer connection → select MTP
  - Or: Settings → Developer Options → Toggle USB Debugging OFF then ON

### 3. ADB Interface Driver (Windows)

Windows does not include an ADB driver for most Chinese Android head
units. The device appears in Device Manager with a yellow exclamation
mark under "Other devices" as an unknown "Android" or "TS8" device.

**Fix — install a generic ADB driver:**

1. Download **Google USB Driver** from:
   https://developer.android.com/studio/run/win-usb
2. Or download **15 seconds ADB Installer** from:
   https://forum.xda-developers.com/t/tool-15-second-adb-installer-v1-4-3.2588979/
3. Or use **Zadig** (free, no admin needed):
   - Download from https://zadig.akeo.ie/
   - Options → List All Devices
   - Select the TS8 device
   - Replace driver with WinUSB (libusb)

**Alternative — install ADB Driver Installer:**
1. Download from https://adbdriver.com/
2. Run as Administrator
3. Click "Install" — it auto-detects the device

### 4. ADB Authorization Dialog

The first time you connect, the TS8 should show a dialog:
"Allow USB debugging? The computer's RSA key fingerprint is: XX:XX:..."

If you missed this dialog or tapped "Deny":
- The device shows as "unauthorized" in `adb devices`
- Pull down the notification bar — the dialog may be there
- Or: Settings → Developer Options → "Revoke USB debugging authorizations"
  then reconnect

### 5. TS8 Custom ROM Issues

Some TS8 head units run heavily modified Android 6 ROMs that:
- Disable ADB over USB entirely (only allow ADB over Wi-Fi)
- Require a vendor-specific USB driver
- Have a broken `adbd` daemon

**Test ADB over Wi-Fi:**
1. On the TS8: Settings → Developer Options → ADB over network = ON
   (or "Wireless debugging")
2. Find the TS8's IP address (Settings → About → Network)
3. On PC: `adb connect <ip-address>:5555`
4. If this works, USB debugging is broken at the ROM/driver level
   but you can still use ADB over Wi-Fi

### 6. USB Port Type

The TS8 may have multiple USB ports:
- **USB Host port** (for USB sticks) — this is for devices plugged INTO the TS8
- **USB Device port** (for connecting TO a PC) — this is the one you need

Some TS8 units only have a Host port and no Device port. In that case,
USB debugging via cable is physically impossible.

**Check:** Look at the back of the TS8 unit. There should be:
- A micro-USB or USB-C port labeled "USB" or "DEBUG"
- This is different from the standard USB-A ports meant for USB sticks

## Workaround: In-App Diagnostic Log

Since USB debugging may not work, the app now includes a **diagnostic log
file** that captures all map loading errors. You don't need ADB at all.

### How to access the log

1. Install the latest APK on the TS8
2. Open the app → go to Map Management (gear icon on the map screen)
3. Scroll to the "Chẩn đoán" (Diagnostics) section
4. Tap "Xem nhật ký lỗi" (View error log)
5. The log shows the last 150 lines of diagnostic output

### Where the log file is saved

The log file is at:
```
/storage/emulated/0/ts8/logs/ts8-diag-YYYY-MM-DD.txt
```

To retrieve it without USB debugging:
1. Use a file manager app on the TS8 (or install one from APK)
2. Navigate to `ts8/logs/`
3. Copy the file to a USB stick
4. Or: share via Bluetooth / email / cloud

### What the log shows

The log captures:
- Device info (model, Android version, ABI)
- Map pack discovery (which roots were searched, what was found)
- Pack details (tiles path, glyphs path, bounds, zoom range)
- Style loading (JSON size, mode, success/failure)
- First frame rendering (timeout or success)
- Any uncaught exceptions (with full stack trace)

### Example log entries

**Normal load:**
```
[10:15:23.451] === TS8 Launcher diagnostic log ===
[10:15:23.455] Device: TS8 Cortex-A7
[10:15:23.456] Android: 6.0 (SDK 23)
[10:15:23.457] ABIs: armeabi-v7a, armeabi
[10:15:23.458] Ts8LauncherApp.onCreate
[10:15:24.012] MapHost.create: loading pack
[10:15:24.156] MapHost: pack OK: Da Nang - Quang Nam, dir=/data/.../map-pack/danang
[10:15:24.158] MapHost: tiles=/data/.../map-pack/danang/basemap.mbtiles
[10:15:24.160] MapHost: glyphs=/data/.../map-pack/danang/fonts
[10:15:24.162] MapHost: bounds=GeoBounds(w=108.0, s=15.8, e=109.0, n=16.2), zoom=5-14
[10:15:25.234] MapHost: style loaded successfully
[10:15:27.891] MapHost: first frame drawn — map READY
```

**Style failure:**
```
[10:15:24.156] MapHost: pack OK: Da Nang - Quang Nam
[10:15:24.158] MapHost: tiles=/data/.../basemap.mbtiles
[10:15:24.160] MapHost: glyphs=null          ← problem: no fonts dir
[10:15:25.234] MapHost: style load FAILED: Invalid file URL
```

**Timeout:**
```
[10:15:24.156] MapHost: pack OK: Da Nang
[10:15:25.234] setStyle: json length=12543, mode=DAY
[10:15:25.235] setStyle: tilesUri=mbtiles://%2Fdata%2F...%2Fbasemap.mbtiles
[10:15:45.236] MapHost: first frame TIMEOUT (20000ms)   ← hung renderer
```

## Quick Checklist

| Check | How |
|---|---|
| Cable is data-capable | Plug a USB stick via OTG — does the TS8 see it? |
| USB mode is MTP/PTP | Pull down notification → select "Transfer files" |
| ADB driver installed | Check Device Manager — no yellow exclamation |
| Authorization dialog | Revoke auths in Dev Options, reconnect |
| Try Wi-Fi ADB | `adb connect <ip>:5555` |
| Read diagnostic log | Map Management → "Xem nhật ký lỗi" |
