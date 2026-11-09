# Installing on the head unit, and getting out again

This is the operational document for the TS8. It covers, in order: preparing the
unit, installing the APK, installing a map pack, making the launcher the Home
app **by hand**, and — the part that matters when something goes wrong — three
independent ways back to the stock launcher.

The spec forbids replacing the default launcher automatically. That is why every
step here is manual and why "recovery" is a first-class section rather than a
footnote: the driver holds the Home role, and the app never takes it.

**Package:** `com.ts8.launcher`
**APK:** `app/build/outputs/apk/debug/app-debug.apk`
**Home activity:** `com.ts8.launcher/.ui.HomeActivity`
**Minimum / target API:** 23 / 30

---

## 1. Before touching the unit

### 1.1 What is needed

| item | why |
|------|-----|
| USB-A male to USB-A male cable | the TS8's data port is a host port; a normal phone cable does not have the right ends |
| a PC with `adb` | `D:/Tools/_toolchain/android-sdk/platform-tools/adb.exe` on this build host, or any platform-tools install |
| the debug APK | built per `TEST_REPORT.md` §1 |
| a FAT32 or exFAT USB stick | for the map pack, not for the APK |
| the map pack directory | `danang-2026.10`, built per `MAP_DATA_PIPELINE.md` |

### 1.2 Enable ADB on the unit

The TS8 firmware ships with USB debugging reachable from its own settings app.
On `V10.5.5_20210311.193033_TW2-THEME1` the path is **Settings → About device →
tap "Build number" seven times → back → Developer options → USB debugging**. The
exact menu tree varies between TS8 firmware revisions; if the entry is missing,
the port is a host port only and the unit must be updated from a USB stick by its
own updater — this document does not cover that, and neither does the project
(`KNOWN_LIMITATIONS.md` §B, and the campaign's standing rule against firmware
work).

### 1.3 Confirm the PC sees it

```sh
adb devices -l
```

Expected — a line for the unit, and **`device`**, not `unauthorized` or
`offline`:

```
<serial>   device product:... model:... device:...
```

If the first `adb devices` after plugging in prints nothing but opens a
confirmation dialog **on the head unit's screen**, accept it there. The dialog is
shown on the unit, not on the PC; this is the single most common reason a first
install appears to fail.

Record the serial. Everything below assumes `-s <serial>` when more than one
device is attached.

```sh
adb -s <serial> shell getprop ro.build.version.sdk     # expect 23
adb -s <serial> shell getprop ro.product.model
```

---

## 2. Install the APK

### 2.1 Install

```sh
adb -s <serial> install -r -d app/build/outputs/apk/debug/app-debug.apk
```

- `-r` reinstalls over the existing copy, keeping app data (the music database,
  checkpoints, saved places).
- `-d` allows a downgrade. Needed only when reinstalling an older build during a
  bisect; harmless otherwise.

Expect `Success`. The APK is **58,203,447 bytes** (see `TEST_REPORT.md` §1.0), so
the push takes a noticeable moment over the unit's USB 2.0 port. A `Failure
[INSTALL_FAILED_UPDATE_INCOMPATIBLE]` means the previously installed copy was
signed with a different key: uninstall first (§2.3), then install.

### 2.2 Why not to launch it from the stock launcher yet

The app is a candidate Home app but is not the Home app (that is §4). Launching
it from the stock launcher's app list is safe and is the right way to check the
build before handing over the Home role:

```sh
adb -s <serial> shell am start -n com.ts8.launcher/.ui.HomeActivity
```

### 2.3 Uninstall

```sh
adb -s <serial> uninstall com.ts8.launcher
```

This removes the music database, the playback checkpoints and the saved places,
because they live in app-private storage. Map packs are **not** touched: they are
read from shared storage or USB and are not in the app's private directory.

---

## 3. Install a map pack

### 3.1 Where the app looks

`MapPack.defaultRoots()` searches, in this order, and the **first readable pack
wins**:

| # | root | notes |
|---|------|-------|
| 1 | `<app files>/map-pack` | private, survives a USB pull; needs `run-as` to write (§3.2) |
| 2 | `<external files>/map-pack` | shared, per-app directory |
| 3 | `/storage/emulated/0/ts8/map-pack` | simplest path for a file manager |
| 4 | `/storage/emulated/0/TS8/map-pack` | case variant |
| 5 | `/storage/usb0/ts8/map-pack`, `/storage/usb1/…`, `/storage/usbdisk/…`, `/storage/usb_disk/…`, `/mnt/usb_storage/…`, `/storage/UsbDriveA/…` | each also with the `TS8` capitalization |

Each root may **be** a pack or **contain** pack folders; children are tried in
sorted order. So both of these work:

```
/storage/emulated/0/ts8/map-pack/            <- a pack directly (metadata.json here)
/storage/emulated/0/ts8/map-pack/danang/     <- a folder holding packs
```

### 3.2 Push a pack

A pack is a directory, so it goes up with `push` and must land in a directory the
app can read.

```sh
adb -s <serial> shell mkdir -p /storage/emulated/0/ts8/map-pack
adb -s <serial> push .work/pack/danang /storage/emulated/0/ts8/map-pack/danang
```

Verify — all six of these must exist, and `metadata.json` must parse:

```sh
adb -s <serial> shell ls /storage/emulated/0/ts8/map-pack/danang
# basemap.mbtiles  checksums.sha256  fonts  metadata.json  places.db  routing-graph
```

The pack is **12.7 MB** on disk, of which `basemap.mbtiles` is 10.7 MB, so the
push is quick. Do not push the `__pycache__` or the working files — only the
pack directory.

### 3.3 Make the app notice it

The pack result is memoised for the life of the process (see `AppGraph.loadPack`),
so a pack pushed while the app is running is not seen until either the app is
restarted or the driver taps **Settings → Bản đồ → Tìm lại map pack**. That
action calls `invalidatePack()`, which also closes the search service, the SQLite
handle, the router and the navigation controller, in that order, then re-discovers
on the next screen.

By ADB, the equivalent is a process restart:

```sh
adb -s <serial> shell am force-stop com.ts8.launcher
adb -s <serial> shell am start -n com.ts8.launcher/.ui.HomeActivity
```

### 3.4 If the map stays empty

The map placeholder shows "Chưa cài map pack — không thể dẫn đường". Check, in
order:

1. **`metadata.json` present and parseable.** A pack without it is not a pack;
   `MapPack.load` reports a failure naming the file.
2. **Read permission.** `/storage/emulated/0/ts8/` created by `adb shell` is owned
   by `shell`; on some firmware the app can still read it, on others not. The
   reliable fallbacks are the app's own external directory (§3.1 #2) or the
   private directory via `run-as`:

   ```sh
   adb -s <serial> push .work/pack/danang /data/local/tmp/danang
   adb -s <serial> shell run-as com.ts8.launcher mkdir -p files/map-pack/danang
   adb -s <serial> shell "run-as com.ts8.launcher cp -r /data/local/tmp/danang/. files/map-pack/danang/"
   ```

   `run-as` only works on a debuggable build, which this APK is
   (`isMinifyEnabled = false` and `debuggable true`).
3. **Log.** The discovery failure is logged rather than thrown:

   ```sh
   adb -s <serial> logcat -s Ts8AppGraph:W Ts8MapLibre:W
   ```

   `map pack unavailable: <reason>` names the exact failure.

---

## 4. Making it the Home app — by hand, and only by hand

### 4.1 What the app declares

`HomeActivity` carries two intent filters:

| filter | effect |
|--------|--------|
| `MAIN` + `LAUNCHER` | appears in the stock launcher's app list; a normal launchable app |
| `MAIN` + `HOME` + `DEFAULT` | appears in Android's "choose Home app" dialog as a **candidate** |

The second filter is what makes the manual opt-in possible at all. It does **not**
make the app Home — Android only assigns the role when a person picks it in a
system dialog. Nothing in this app calls `PackageManager.setComponentEnabledSetting`
on the stock launcher, and nothing disables it: `KNOWN_LIMITATIONS.md` §B2.

### 4.2 The two intended ways to opt in

**On the unit, no PC:** Settings → Apps → Default apps → Home app → **TS8
Launcher**. Or press Home and pick from the dialog Android shows.

**By ADB**, which is the reproducible way:

```sh
adb -s <serial> shell cmd package set-home-activity com.ts8.launcher/.ui.HomeActivity
```

`cmd package set-home-activity` exists from Android 8. On the TS8's Android 6 it
is **not** available. The API 23 equivalent is a role preference stored in
`settings`:

```sh
adb -s <serial> shell settings put secure user_setup_complete 1
adb -s <serial> shell am start -a android.intent.action.MAIN \
    -c android.intent.category.HOME
```

That opens the chooser; the driver taps the app and selects "Always". **There is
no supported API 23 command that sets the default Home non-interactively** — the
preference is written by the chooser itself when a human taps. Anything that
writes it by other means is out of scope here, and the project does not do it.

Confirm which app holds the role. `dumpsys package` prints the resolved Home
component under a `Preferred Activities User 0` heading; on Android 6 the exact
format varies by firmware, so read it rather than grep for a fixed string:

```sh
adb -s <serial> shell dumpsys package | grep -i -A3 "home"
```

A line naming `com.ts8.launcher/.ui.HomeActivity` means the app holds the role.
This command is diagnostic only — it was **not** executed against a unit in this
campaign (`KNOWN_LIMITATIONS.md` §A1), so treat the output shape as "read what it
says", not as a guaranteed format.

### 4.3 Before handing over the role

Do this once, in this order, so that a mistake cannot strand the unit:

1. Install the APK (§2), launch it from the stock launcher (§2.2), and confirm
   **Home renders** — status bar, four nav items, and either the map or the
   placeholder sentence.
2. Confirm **Back does nothing on Home** (by design, `BaseScreenActivity`), and
   that Back on Map/Music/Apps returns to Home.
3. Only then opt in to the Home role.

If Home rendered correctly as a normal app, the Home role cannot produce a black
screen — the same Activity is drawn either way.

---

## 5. Recovery — three ways back

Read this section before §4, not after it. All three work with no PC on the
unit's screen beyond a cable.

### 5.1 Recovery A — the chooser still exists (the common case)

If the app crashes on launch, or draws nothing, Android's own chooser is still
reachable and the stock launcher is still installed and enabled:

1. Press **Home**. If a chooser appears, pick the stock launcher.
2. If no chooser appears, go to **Settings → Apps → Default apps → Home app** and
   pick the stock launcher.

This is the path that exists precisely because the app never disables anything.
It requires only that the system Settings app is reachable — which it is, from
the app drawer or from a hardware Settings key on the unit.

### 5.2 Recovery B — ADB, when the screen is unusable

```sh
# 1. Take this app out of the Home candidates. The stock launcher becomes the
#    only candidate, which Android resolves without showing a dialog.
adb -s <serial> shell pm disable-user --user 0 com.ts8.launcher

# 2. Wake the screen and press Home.
adb -s <serial> shell input keyevent KEYCODE_WAKEUP
adb -s <serial> shell am start -a android.intent.action.MAIN \
    -c android.intent.category.HOME
```

Try step 1 before anything more drastic: it is reversible in one command and
does not touch system settings.

Only if the preference itself is stuck — the chooser keeps re-selecting this app
even though it is disabled — reset the settings store:

```sh
adb -s <serial> shell pm clear com.android.settings
```

⚠️ **This resets system settings to defaults.** On a head unit that includes
paired Bluetooth devices and saved Wi-Fi networks. It is the last option before
uninstalling, not the first.

Step 1 is fully reversible:

```sh
adb -s <serial> shell pm enable com.ts8.launcher
```

### 5.3 Recovery C — uninstall

The last resort, and the one that always works as long as ADB does:

```sh
adb -s <serial> uninstall com.ts8.launcher
adb -s <serial> shell input keyevent KEYCODE_HOME
```

With the app gone, Android falls back to the stock launcher. Map packs on shared
storage and USB are **not** removed (§2.3), so a reinstall picks the same pack
back up.

### 5.4 If ADB is gone too

ADB is lost only if USB debugging was turned off or the cable is unusable. In
that state:

- **Recovery A still works** from the unit's own Settings, as long as the system
  UI is reachable. This is why step 4.3 exists.
- **Nothing in this project can brick the unit.** No partition is written, no
  system app is disabled, no firmware is flashed — the app is a normal
  user-installed APK. The worst state it can create is "the Home app draws
  nothing, and the driver must reach Settings", which Recovery A resolves.

---

## 6. What this document deliberately does not cover

| not covered | why |
|-------------|-----|
| flashing, root, bootloader unlock, MCU update | out of scope for this campaign, and out of scope for the project |
| disabling the stock launcher to force this one | forbidden by the spec, and the mechanism that creates unrecoverable units |
| setting the Home role non-interactively on API 23 | no supported API; see §4.2 |
| installing to a device with a locked bootloader | the APK is a normal debug build and needs no privileged install |
| sideloading from a USB stick without ADB | firmware-dependent; the TS8's own file manager differs between revisions, and this document only claims what was verified |

---

## 7. Quick reference

```sh
# install / reinstall
adb -s <serial> install -r -d app/build/outputs/apk/debug/app-debug.apk

# launch as a normal app
adb -s <serial> shell am start -n com.ts8.launcher/.ui.HomeActivity

# push the map pack
adb -s <serial> shell mkdir -p /storage/emulated/0/ts8/map-pack
adb -s <serial> push .work/pack/danang /storage/emulated/0/ts8/map-pack/danang

# make it re-read the pack
adb -s <serial> shell am force-stop com.ts8.launcher
adb -s <serial> shell am start -n com.ts8.launcher/.ui.HomeActivity

# hand over the Home role (opens the system chooser — a human taps)
adb -s <serial> shell am start -a android.intent.action.MAIN -c android.intent.category.HOME

# get the Home role back
adb -s <serial> shell pm disable-user --user 0 com.ts8.launcher
adb -s <serial> shell input keyevent KEYCODE_WAKEUP
adb -s <serial> shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s <serial> shell pm enable com.ts8.launcher     # to try again later

# observe
adb -s <serial> logcat -s Ts8AppGraph:V Ts8MapActivity:V Ts8MapLibre:V \
    Ts8MusicService:V Ts8LocalPlayer:V Ts8LocationRuntime:V
```
