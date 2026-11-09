# Android 6 compatibility

The target device reports `V10.5.5_20210311.193033_TW2-THEME1`, MCU
`TS8.4.3-100-61-A4A195-211125`, Android **6.0.1 / API 23**, landscape, weak
hardware. This document records every decision that exists because of that API
level — not as a list of trivia, but as the reasoning that would otherwise be
lost and re-litigated.

## 1. Build configuration

| setting | value | why |
|---------|-------|-----|
| `minSdk` | 23 | the device |
| `targetSdk` | **30** | the device's behaviour model, see §2 |
| `compileSdk` | 34 | only for compiling against a current toolchain; no API-34 call is made at runtime without a guard |
| `sourceCompatibility` | 1.8 | the oldest the toolchain accepts |
| `coreLibraryDesugaring` | on | `java.time` and friends are rewritten for API 23 |

**No `buildFeatures.buildConfig`.** `BuildConfig.VERSION_NAME` does not exist in
this build; the Settings screen reads `packageManager.getPackageInfo` instead.
Adding the flag to get two constants is not worth a build feature.

## 2. Why `targetSdk` is 30 and not 34

This is the decision that will look wrong to anyone auditing the build without
reading it, so:

- `targetSdk` is **not** `minSdk`. It changes the *behaviour model* the runtime
  applies, not the API surface available.
- At targetSdk 30 the app keeps pre-scoped-storage rules and the legacy
  `READ_EXTERNAL_STORAGE` grant, which is what the launcher is built around: it
  has to read a map pack and a music library from paths on a USB stick that the
  driver chose.
- Raising it to 33/34 would mean `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`
  runtime grants and a storage-access framework flow — a permission model this
  device, whose ROM predates all of it, does not implement.

`ExpiredTargetSdkVersion` is therefore **disabled** in `app/build.gradle.kts`
lint, with the same reasoning written in place. It is a fatal-severity check
whose only purpose is to push Play Store submissions to a recent targetSdk; this
APK is never submitted to Play and never installed on a modern phone.

`USE_EXACT_ALARM`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`
and the split notification permission all arrive after 30; the ones that are
declared are guarded at the call site, and the ones that are irrelevant are not
declared at all.

## 3. API traps, each with the fix that is in the code

### 3.1 `StorageVolume` is API 24 — the one that would have crashed

`UsbMediaReceiver` reads the volume extra. The obvious code is:

```kotlin
intent.getParcelableExtra<StorageVolume>(EXTRA_VOLUME)   // NoSuchMethodError on API 23
```

`NoSuchMethodError` on the very path that exists to prevent a crash. The fix
reads a plain `Parcelable` and reflects on `getPath()` (hidden on API 23) or
`getDirectory()` (public, API 30+):

```kotlin
val volume: Parcelable = if (SDK_INT >= TIRAMISU) {
    intent.getParcelableExtra(EXTRA_VOLUME, Parcelable::class.java)
} else { @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_VOLUME) } ?: return null
```

and returns null when neither is found. Null is the *safe* answer: it tells the
service "a volume changed, re-check everything" rather than trusting a path that
may not have been read correctly.

### 3.2 FTS4, not FTS5

Android 6's SQLite has FTS4 and no FTS5. This is why search is a candidate
generator plus a Kotlin scorer instead of `bm25`. See
`OFFLINE_SEARCH_ROUTING.md` §1.

### 3.3 `TypedArray.recycle()`, not `close()`

`close()` on `TypedArray` is API 31. Every custom view uses `recycle()`.

### 3.4 Text-to-speech overload

`TextToSpeech.speak(CharSequence, …)` is API 21+, so the `String` overload used
here is not actually blocked — but the call is still written with an explicit
`SDK_INT >= LOLLIPOP` branch, because the guard documents which overload the
engine on this firmware accepts and costs nothing.

### 3.5 `WifiManager.setWifiEnabled` returns `boolean` in Java, `Unit` in Kotlin

As a **synthetic property setter** it returns `Unit`. This compiled to a real
error the first time:

```kotlin
val accepted = runCatching { wm.isWifiEnabled = target }.getOrDefault(false)  // garbage type
```

The fix calls the method, which does return the boolean:

```kotlin
@Suppress("DEPRECATION")
val accepted = runCatching { wm.setWifiEnabled(target) }.getOrDefault(false)
```

The return value is checked, because on this unit turning Wi-Fi on can be
refused by the firmware, and the tile must not show ON when nothing happened.

### 3.6 `WifiManagerLeak`

Lint requires `WifiManager` to come from the **application** context. Every
`getSystemService` call for a system manager goes through a single helper:

```kotlin
private fun wifiManager(): WifiManager? =
    applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
```

### 3.7 `BLUETOOTH_CONNECT` does not exist at targetSdk 30

`BluetoothAdapter.enable()` needs `BLUETOOTH_ADMIN`, declared in the manifest.
There is no runtime BLUETOOTH permission at this targetSdk, so
`@Suppress("MissingPermission")` is correct and the boolean return is checked.

### 3.8 `PendingIntent` mutability

`FLAG_IMMUTABLE` exists from API 23 — this app's `minSdk` — and is a
compile-time constant, so it is used directly with no version branch. The
platform only *requires* a declared mutability at targetSdk 31+; naming it
anyway is the choice that keeps the intent from being rewritten by another app.

### 3.9 Notification channels

`NotificationChannel` is API 26. `createChannels()` returns early below O, and
`NotificationCompat.Builder` supplies the channel id that API 23 ignores. One
code path, both levels.

### 3.10 `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`

The three-argument `startForeground` is API 29. Guarded:
`SDK_INT >= Q` uses it, otherwise the two-argument form. The service declares
`android:foregroundServiceType="mediaPlayback"` in the manifest, which API 23
ignores and API 29+ reads.

### 3.11 Audio focus

`AudioFocusRequest` is API 26. Both paths are implemented in
`AndroidVehicleAudioBridge`, so identical code runs on the unit and on a modern
emulator.

### 3.12 `MediaStore` and `_DATA`

`MediaStore.Audio.Media.DATA` is deprecated but is the only column that yields a
filesystem path usable with `MediaPlayer.setDataSource`, and scoped storage
arrives later than this device. `@Suppress("DEPRECATION")` with a comment saying
exactly that.

### 3.13 `@RequiresPermission` that lint cannot see through

`LocationRuntime.start()` guards with `hasPermission()` — a method call, not an
inline `checkSelfPermission`, so lint reports a missing check. The call is also
wrapped in `runCatching` because a vendor ROM can throw from a provider whose
grant says otherwise. `@Suppress("MissingPermission")` with that explanation.

### 3.14 `onBackPressed` and `MissingSuperCall`

`BaseScreenActivity` overrides `onBackPressed` and does **not** call super.
AppCompat's default body *finishes the Activity* — which is correct on a phone
with a back stack the user can navigate, and wrong on a launcher where the
visible screen is the only screen. The callback is routed to `onBack()`, and the
suppression carries a KDoc explaining why.

### 3.15 `Locale.ROOT` for lowercasing

Not API-level, but repository-wide and easy to get wrong: normalisation
lowercases with `Locale.ROOT`. The device's locale is Vietnamese, and Turkish
`I → ı` style rules would corrupt tokens in any locale that has them.

## 4. Gradle and toolchain

| component | version | note |
|-----------|---------|------|
| AGP | 8.7.3 | |
| Kotlin | 1.9.25 | |
| Gradle | 8.11.1 | |
| JDK | 17 (`D:/Tools/_toolchain/jdk-17.0.20.1+1`) | pinned via `org.gradle.java.home` |

The JDK is pinned because the host also has JDK 27 on `PATH`, and Kotlin
1.9.25's compiler cannot parse `"27"` as a `JavaVersion` release string — every
script compile fails with an unrelated message. AGP 8.7 also requires 17.

The build host sits behind an SSL-inspecting proxy; `gradle.properties` carries
the truststore and proxy lines and a comment saying which three lines to delete
on a machine without the proxy. **Lint needs network** — `:app:lintDebug
--offline` fails with "No cached version of `com.android.tools.lint:lint-gradle`"
because the lint tool is resolved per-run; `compileDebugKotlin` and
`assembleDebug` do work offline.

## 5. Hardware reality on the unit

| capability | state | effect on the app |
|-----------|-------|-------------------|
| GL ES 2.0 | required in manifest | MapLibre has a surface |
| GL ES 3.0 | declared optional, not required | the app installs either way; the engine reports what it found |
| GPS | optional in manifest | the map shows "đang tìm tín hiệu" rather than failing |
| Wi-Fi / Bluetooth / USB host / touchscreen / landscape | all optional | a unit missing one still installs |
| RAM | small | `largeHeap="true"`, one `MapView` at a time, no cached player |
| CPU | weak | routing on its own executor, search on one IO thread, scan off the main path |

`largeHeap` is not a wish: a 6 MB memory-mapped graph plus a GL surface plus a
`MediaPlayer` buffer on a device of this class needs the extra headroom, and
every one of those has a documented reason to exist.

## 6. What this build deliberately does **not** do

- No root, no `su`, no firmware unpack, no flash. The firmware tree is an asset
  for a later phase and nothing in this repository reads it.
- No `PowerManager.goToSleep()` (signature-protected): "screen off" is a black
  full-screen Activity.
- No `PowerManager.reboot()` (signature-protected): "restart" restarts the
  launcher task.
- No automatic takeover of the Home role and no disabling of the stock
  launcher. Opting in is a manual step in Android's own settings, and
  `ADB_INSTALL_AND_RECOVERY.md` carries the escape hatch.
- No `PREVENT_POWER_SAVE`, no MCU/radio source switching, no CANBUS writes:
  all need privileges this app does not have, and none is faked.
