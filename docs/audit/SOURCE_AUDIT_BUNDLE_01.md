# Source audit bundle 01

Excerpted verbatim from the working tree. Every section is
`## <relative path>` followed by `BEGIN FILE` / `END FILE` with the
file's exact contents between them. Nothing is summarised, elided or
reformatted.

Files in this bundle: 35

---

## app/build.gradle.kts

BEGIN FILE
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

android {
    namespace = "com.ts8.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ts8.launcher"
        minSdk = 23
        targetSdk = 30
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/*.kotlin_module"
        )
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        // API-23 guard: every call above minSdk must be annotated or guarded.
        checkReleaseBuilds = true

        // `ExpiredTargetSdkVersion` is fatal-severity by default and exists to
        // push Play Store submissions to a recent targetSdk. This APK is never
        // submitted to Play and never installed on a modern phone: it is the
        // home app for an Android 6 head unit, and targetSdk 30 is chosen so the
        // runtime applies the pre-scoped-storage, pre-split-permission rules the
        // device actually has. Raising it to satisfy the check would change the
        // permission model and the storage access this launcher is built
        // around. See docs/ANDROID_6_COMPATIBILITY.md.
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests {
            // The unit tests in this module are pure JVM and the classes under
            // test are pure state tables — the state machine, the id-space
            // arithmetic — not Activities. They call `android.util.Log` on the
            // paths they exercise, and Robolectric would answer that by
            // downloading an `android-all` jar that is not in this build host's
            // offline cache. Returning the framework defaults instead keeps the
            // suite runnable with `--offline`, which is the only way this host
            // can build at all. Robolectric-based tests can still opt in with
            // `@RunWith(RobolectricTestRunner::class)` where a jar is present.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:map-api"))
    implementation(project(":core:map-engine-maplibre"))
    implementation(project(":core:place-search"))
    implementation(project(":core:routing-engine-offline"))
    implementation(project(":core:location-engine"))
    implementation(project(":core:navigation-core"))
    implementation(project(":core:music-api"))
    implementation(project(":core:music-engine-local"))
    implementation(project(":core:music-provider-online"))
    implementation(project(":core:vehicle-audio-bridge"))
    implementation(project(":core:diagnostics"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.preference:preference-ktx:1.2.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}

END FILE

## app/proguard-rules.pro

BEGIN FILE
# R8 rules for the release build.
#
# The debug build does not use this file (`isMinifyEnabled = false`), and the
# acceptance target of this campaign is a debug APK — so these rules have not
# been exercised by a release build. They are here because `app/build.gradle.kts`
# names the file, and a release build without it would fail at the proguard step
# rather than at a rule that is actually wrong.

# ---------------------------------------------------------------- MapLibre
#
# MapLibre's Java layer is reached from native code through JNI, so the class and
# method names the C++ side looks up by string cannot be renamed.
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# The JNI layer looks up these by name; the MapLibre geojson/turf artifacts keep
# the old `com.mapbox` package prefix in some builds and `org.maplibre` in
# others, so both spellings are kept. A `-keep` for a package that does not
# exist is not an error — R8 reports it as an unused rule at most.
-keep class org.maplibre.geojson.** { *; }
-keep class org.maplibre.turf.** { *; }
-keep class com.mapbox.geojson.** { *; }
-keep class com.mapbox.turf.** { *; }
-dontwarn com.mapbox.**

# OkHttp is MapLibre's transitive dependency and is never called by this app
# (see THIRD_PARTY_LICENSES.md §1.4). Keeping its optional platform hooks out of
# the way avoids warnings for the Conscrypt and BouncyCastle integrations, which
# are absent on API 23.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ------------------------------------------------------- reflective lookups
#
# `MapPack` and `MusicPlaybackService` are named in the manifest, and the
# `StorageVolume` handling in `UsbMediaReceiver` calls into a platform class by
# reflection (API 24+ on an API 23 device — see ANDROID_6_COMPATIBILITY.md §3.1).
-keep class com.ts8.launcher.service.** { *; }
-keep class com.ts8.launcher.Ts8LauncherApp { *; }
-keep class com.ts8.launcher.ui.**Activity { *; }

# Kotlin metadata is read by the Kotlin reflection path in a few places; keeping
# it costs little and an obfuscated `@Metadata` is a runtime crash that reads
# like a source bug.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep class kotlin.Metadata { *; }

# --------------------------------------------------------------- diagnostics
#
# A stack trace from the field has to name the class. Keep the line numbers, and
# keep the source file name so a report can be mapped back to a file.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

END FILE

## app/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Launcher / home app -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.SET_WALLPAPER" />
    <uses-permission android:name="android.permission.EXPAND_STATUS_BAR" />

    <!-- Location -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_LOCATION_EXTRA_COMMANDS" />

    <!-- Voice Instant (§10). Requested at the moment the driver first taps the
         mic, never at boot: a launcher that asks for the microphone on first
         start teaches the driver to deny it. If it is denied the overlay says
         so and points at Settings — it never retries silently. -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- Online music (§12–13). Without INTERNET every provider fails with a
         transport error and the driver is told the station is unavailable when
         the real fault is the manifest. ACCESS_NETWORK_STATE is what lets the
         gateway tell "offline" apart from "the provider is down" before it
         spends a request on a dead modem. Both are install-time permissions on
         API 23, so neither needs a runtime request. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Music / media -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission
        android:name="android.permission.MEDIA_CONTENT_CONTROL"
        tools:ignore="ProtectedPermissions" />

    <!-- System state for quick settings -->
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.WRITE_SETTINGS"
        tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.REORDER_TASKS" />
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />

    <!-- Hardware that must not block installation on the head unit -->
    <uses-feature android:name="android.hardware.location.gps" android:required="false" />
    <uses-feature android:name="android.hardware.wifi" android:required="false" />
    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />
    <uses-feature android:name="android.hardware.usb.host" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-feature android:name="android.hardware.screen.landscape" android:required="false" />
    <uses-feature
        android:glEsVersion="0x00020000"
        android:required="true" />

    <!-- Modern OpenGLES 3.0 is what MapLibre needs; declare it optional so a
         device without ES3 still installs and the map engine reports it. -->
    <uses-feature android:glEsVersion="0x00030000" android:required="false" />

    <!-- Android 11+ package visibility: launcher must see launchable apps -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
        <intent>
            <action android:name="android.intent.action.DIAL" />
        </intent>
        <!-- Voice Instant needs to see the recogniser service. Harmless on
             API 23 (package visibility arrived in API 30) and required on a
             newer host build. -->
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>

    <application
        android:name=".Ts8LauncherApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:largeHeap="true"
        android:supportsRtl="true"
        android:theme="@style/Theme.Ts8Launcher"
        android:hardwareAccelerated="true"
        tools:targetApi="34">

        <!-- Home activity. Not the default launcher by default: the user opts
             in via Settings, so the stock launcher is never removed.

             `CATEGORY_HOME` + `CATEGORY_DEFAULT` are what make this app a
             *candidate* in Android's own "choose Home" dialog. They do not make
             it the Home app — that requires the driver to pick it, and the
             stock launcher stays installed and selectable either way. Without
             these two lines the app is only a normal launchable app and cannot
             be assigned the Home role at all, which would silently make the
             manual opt-in in the spec impossible. -->
        <activity
            android:name=".ui.HomeActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout|uiMode|density"
            android:resizeableActivity="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.MusicFullscreenActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|uiMode"
            android:launchMode="singleTop" />

        <activity
            android:name=".ui.MapActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout|uiMode|density"
            android:launchMode="singleTop" />

        <!-- Opened over Home or over the map, so not singleTask: finishing it
             must return to whichever screen launched it. -->
        <activity
            android:name=".ui.SearchActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:windowSoftInputMode="stateAlwaysVisible"
            android:launchMode="singleTop" />

        <activity
            android:name=".ui.AppDrawerActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:launchMode="singleTop" />

        <activity
            android:name=".ui.QuickSettingsActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:launchMode="singleTop" />

        <activity
            android:name=".ui.SettingsActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:launchMode="singleTop" />

        <activity
            android:name=".ui.ParkingModeActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:launchMode="singleTop" />

        <!-- A black window standing in for the panel being off. Its own task
             is not wanted: it is dismissed by a tap and must leave the map and
             the music exactly as they were. -->
        <activity
            android:name=".ui.ScreenOffActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:theme="@style/Theme.Ts8Launcher.Fullscreen"
            android:launchMode="singleTop" />

        <!-- Music playback lives in a service so it survives activity changes -->
        <service
            android:name=".service.MusicPlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />

        <receiver
            android:name=".service.MediaButtonReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_BUTTON" />
            </intent-filter>
        </receiver>

        <receiver
            android:name=".service.UsbMediaReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
                <action android:name="android.hardware.usb.action.USB_DEVICE_DETACHED" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_MOUNTED" />
                <action android:name="android.intent.action.MEDIA_UNMOUNTED" />
                <action android:name="android.intent.action.MEDIA_EJECT" />
                <action android:name="android.intent.action.MEDIA_REMOVED" />
                <data android:scheme="file" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/usb_device_filter" />
        </receiver>
    </application>
</manifest>

END FILE

## app/src/main/java/com/ts8/launcher/Ts8LauncherApp.kt

BEGIN FILE
package com.ts8.launcher

import android.app.Application

/**
 * Process-wide entry point.
 *
 * Holds nothing but the service registry: every engine is created lazily by
 * [com.ts8.launcher.di.AppGraph] so a cold start does not pay for the map
 * engine, the routing graph, or a media scan.
 */
class Ts8LauncherApp : Application() {
    lateinit var graph: com.ts8.launcher.di.AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = com.ts8.launcher.di.AppGraph(this)
        com.ts8.launcher.di.AppGraph.install(graph)
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/di/AppGraph.kt

BEGIN FILE
package com.ts8.launcher.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ts8.core.audio.AndroidVehicleAudioBridge
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.location.DefaultLocationEngine
import com.ts8.core.location.LocationEngine
import com.ts8.core.map.maplibre.MapPack
import com.ts8.core.map.maplibre.MapPack.LoadResult
import com.ts8.core.map.maplibre.PackDiagnostics
import com.ts8.core.map.maplibre.PackValidator
import com.ts8.core.music.local.LocalMusicBackend
import com.ts8.core.navigation.NavigationController
import com.ts8.core.navigation.VoiceGuidance
import com.ts8.core.routing.AStarRouter
import com.ts8.core.routing.RoutingEngine
import com.ts8.core.search.SqlitePlaceSearch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one place every engine is constructed.
 *
 * **Why a graph instead of a service locator with statics.** The spec requires
 * that no engine lives inside an Activity. This class is what makes that
 * structural rather than a convention: the Activity asks for interfaces, and
 * the only code that knows what implements them is here. Swapping the map
 * engine, the routing engine, or the audio bridge is a change in this file.
 *
 * **Why everything is lazy and nothing is created in `onCreate`.** The head
 * unit's cold start budget is spent on the launcher being tappable, not on
 * opening a 6 MB routing graph. [mapPack] alone touches storage; [router] maps
 * a file into memory. Each is built on first use, and each failure is recorded
 * rather than thrown, because a launcher that crashes on a missing USB stick is
 * a launcher that strands the driver.
 *
 * **Threading.** Construction is synchronized and cheap; the expensive parts
 * (opening SQLite, mapping the graph) are done on [ioExecutor], and the UI
 * calls this class from the main thread only for the getters that are already
 * cheap. [mapPack] and [placeSearch] are the two exceptions, and they document
 * their own cost.
 */
class AppGraph(context: Context) {

    /**
     * Held as the application context, not the argument.
     *
     * [current] is a static field below, so whatever this instance holds lives
     * for the process. Storing the caller's context would be safe today — the
     * only constructor site passes the Application — but it would make the
     * leak depend on every future call site getting it right. Converting once,
     * here, makes it impossible to get wrong.
     */
    private val context: Context = context.applicationContext

    val appContext: Context get() = context

    /**
     * The settings store.
     *
     * Owned here rather than constructed per screen so that the active-pack
     * pointer [loadPack] reads and the one Map Management writes are the same
     * `SharedPreferences` instance, and so a change listener registered by one
     * screen is visible to the others.
     */
    val settings: com.ts8.launcher.runtime.AppSettings by lazy {
        com.ts8.launcher.runtime.AppSettings(context)
    }

    /** Background pool for search, scan and pack reload. Two threads: one for
     *  a running media scan, one so search never waits behind it. */
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ts8-io").apply { isDaemon = true }
    }

    fun io(): ExecutorService = ioExecutor

    // -- audio -------------------------------------------------------------

    /**
     * Owns the media stream, audio focus and duck/unduck for the whole app.
     * Exactly one instance: two bridges would fight over the same stream and
     * one would restore a volume the other had already changed.
     */
    val audioBridge: VehicleAudioBridge by lazy {
        AndroidVehicleAudioBridge(appContext).also { bridge ->
            bridge.setFocusListener(musicBridgeListener)
        }
    }

    /**
     * The listener the *player* needs. Installed by [MusicRuntime] once the
     * service exists, because the player is not owned by this class — it lives
     * in the playback service so it survives Activity changes.
     */
    @Volatile
    var musicBridgeListener: com.ts8.core.audio.AudioFocusListener =
        object : com.ts8.core.audio.AudioFocusListener {
            override fun onFocusLostPermanent() = Unit
            override fun onFocusLostTransient(mayDuck: Boolean) = Unit
            override fun onFocusGained() = Unit
            override fun onCallStarted() = Unit
            override fun onCallEnded() = Unit
            override fun onAudioRouteChanged(route: com.ts8.core.music.AudioRoute) = Unit
        }
        private set

    fun installFocusListener(listener: com.ts8.core.audio.AudioFocusListener?) {
        musicBridgeListener = listener ?: object : com.ts8.core.audio.AudioFocusListener {
            override fun onFocusLostPermanent() = Unit
            override fun onFocusLostTransient(mayDuck: Boolean) = Unit
            override fun onFocusGained() = Unit
            override fun onCallStarted() = Unit
            override fun onCallEnded() = Unit
            override fun onAudioRouteChanged(route: com.ts8.core.music.AudioRoute) = Unit
        }
        audioBridge.setFocusListener(musicBridgeListener)
    }

    // -- map ---------------------------------------------------------------

    /** Result of the pack search, cached so a screen switch does not re-walk storage. */
    @Volatile
    private var packResult: MapPack.LoadResult? = null

    /** Validation report for the pack in [packResult], filled in on the IO pool. */
    @Volatile
    private var packReport: PackValidator.Report? = null

    @Volatile
    private var packValidating = false

    /** Bumped on every [invalidatePack] so a slow validation cannot publish stale results. */
    @Volatile
    private var packGeneration = 0

    /**
     * True when this process may read shared storage.
     *
     * **Why the loader needs to be told.** On API 23, `File.isDirectory()`
     * returns `false` — without throwing — for a directory the process is not
     * allowed to `stat`. A pack sitting in `/storage/emulated/0/ts8/map-pack`
     * is therefore indistinguishable from one that does not exist, and the
     * driver is told to go copy files that are already there. Checking the
     * permission first turns that into an accurate message.
     *
     * The check is skipped entirely below API 23, where external storage is
     * granted at install time.
     */
    private fun storageReadable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The installed map pack, or a failure explaining why there is none.
     *
     * Reading this walks a handful of directories and parses one small JSON
     * file, so it is safe on the main thread, but it is memoised because the
     * USB roots are slow to stat when no stick is present.
     *
     * The memo is dropped by [invalidatePack], which the permission callback
     * and the Map Management rescan both call. Without that, the first run —
     * which happens before the storage permission dialog is answered — would
     * cache a `PERMISSION_DENIED` for the life of the process.
     */
    fun loadPack(): MapPack.LoadResult {
        packResult?.let { return it }
        synchronized(this) {
            packResult?.let { return it }
            val generation = packGeneration
            val diagnostics = PackDiagnostics()
            val result = MapPack.discover(
                roots = MapPack.defaultRoots(appContext),
                diagnostics = diagnostics,
                storageReadable = storageReadable(),
                preferred = settings.activePackPath?.let { File(it) }
            )
            lastPackDiagnostics = diagnostics
            packResult = result
            if (result is MapPack.LoadResult.Failure) {
                Log.w(TAG, "map pack unavailable [${result.code}]: ${result.reason}")
                Log.i(TAG, diagnostics.report())
            } else if (result is MapPack.LoadResult.Ok) {
                Log.i(TAG, "map pack found: ${result.pack}")
                scheduleValidation(result.pack, generation)
            }
            return result
        }
    }

    /**
     * The diagnostics of the most recent discovery pass.
     *
     * Kept so Map Management can show the candidate roots, the resolved
     * canonical path and the stage trail even when discovery succeeded — a
     * driver whose pack was found in the wrong folder needs to see *which*
     * folder was used, not only that something worked.
     */
    @Volatile
    private var lastPackDiagnostics: PackDiagnostics? = null

    fun packDiagnostics(): PackDiagnostics? = lastPackDiagnostics

    /**
     * Every usable pack currently on storage, for the Map Management list.
     *
     * Walks the same roots [loadPack] walks, but collects rather than stops at
     * the first, because a driver with two regions installed needs to see both
     * in order to choose. Runs synchronously — call it from the IO pool; it
     * stats every root and parses one manifest per candidate.
     */
    fun listPacks(): List<MapPack> {
        val found = ArrayList<MapPack>(4)
        val seen = HashSet<String>(4)
        val diagnostics = PackDiagnostics()
        val roots = MapPack.defaultRoots(appContext)
        for (root in roots) {
            if (!root.isDirectory) continue
            collectPacks(root, 0, found, seen, diagnostics)
        }
        return found
    }

    private fun collectPacks(
        directory: File,
        depth: Int,
        out: MutableList<MapPack>,
        seen: MutableSet<String>,
        diagnostics: PackDiagnostics
    ) {
        val canonical = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
        if (!seen.add(canonical)) return

        if (File(directory, "metadata.json").isFile) {
            val loaded = MapPack.load(directory, diagnostics)
            if (loaded is MapPack.LoadResult.Ok) {
                out += loaded.pack
                return
            }
            diagnostics.candidate("$canonical pack=invalid")
            return
        }
        if (depth >= 2) return
        if (directory.name in PACK_CONTENT_DIRS) return
        val children = runCatching { directory.listFiles() }.getOrNull()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: return
        for (child in children) collectPacks(child, depth + 1, out, seen, diagnostics)
    }

    fun pack(): MapPack? = (loadPack() as? MapPack.LoadResult.Ok)?.pack

    /**
     * The last validation report, or null while it is still being computed.
     *
     * Validation hashes every pack file and opens two SQLite databases, so it
     * never runs on the caller's thread. The UI shows the pack as usable the
     * moment [loadPack] finds it and upgrades its status when this arrives —
     * which is what keeps a 20 MB checksum pass off the first frame.
     */
    fun packReport(): PackValidator.Report? = packReport

    fun isPackValidating(): Boolean = packValidating

    /**
     * The one-line reason navigation is unavailable, or null when it is.
     *
     * Distinct from "no pack": a pack can be perfectly good for the map and
     * have no `routing-graph/`, and telling the driver to install a map when
     * they already have one sends them to fix the wrong thing.
     */
    fun navigationUnavailableReason(): String? {
        val result = loadPack()
        if (result is MapPack.LoadResult.Failure) return result.reason
        val report = packReport
        if (report != null && !report.routingOk) {
            return "Map pack thiếu dữ liệu đường đi (routing-graph) — không thể dẫn đường"
        }
        return null
    }

    /** Runs [PackValidator] off the main thread and publishes the report. */
    private fun scheduleValidation(pack: MapPack, generation: Int) {
        if (packValidating) return
        packValidating = true
        ioExecutor.execute {
            val report = runCatching {
                PackValidator().validate(pack, PackDiagnostics())
            }.getOrElse { e ->
                Log.w(TAG, "pack validation crashed: ${e.message}")
                null
            }
            synchronized(this) {
                if (generation != packGeneration) return@synchronized
                packValidating = false
                packReport = report
                report?.let {
                    Log.i(TAG, "pack validation: ${it.diagnostics.summary()}")
                    Log.i(TAG, it.diagnostics.report())
                    if (!it.routingOk) Log.w(TAG, "routing graph unusable: ${it.diagnostics.routingSummary}")
                    if (!it.placesOk) Log.w(TAG, "place index unusable: ${it.diagnostics.placeDbSummary}")
                }
            }
        }
    }

    /**
     * Forces a fresh validation of the current pack, e.g. after a rescan.
     *
     * Returns immediately; [packReport] is replaced when the pass finishes.
     */
    fun revalidatePack() {
        val pack = pack() ?: return
        synchronized(this) { packValidating = false }
        scheduleValidation(pack, packGeneration)
    }

    /** Forgets the cached result so the next screen retries, e.g. after the
     *  driver plugs in a USB stick. */
    fun invalidatePack() {
        synchronized(this) {
            packGeneration++
            packResult = null
            packReport = null
            packValidating = false
            // The service holds a `PlaceSearch` over the old pack's database.
            // Closing the database without dropping the service would leave the
            // search box dispatching into a closed handle until the next
            // keystroke discovered it — which is a failed search, not an
            // obvious "no pack". Order matters: the service is closed first so
            // it stops dispatching, then the engine it wraps.
            synchronized(searchLock) {
                searchService?.close()
                searchService = null
                searchInstance?.close()
                searchInstance = null
            }
            synchronized(routerLock) { routerInstance?.close(); routerInstance = null }
            synchronized(navLock) {
                // The navigator holds the router it was built on; leaving it
                // alive means the map tab keeps driving a closed graph.
                navigationInstance?.shutdown()
                navigationInstance = null
            }
        }
    }

    // -- place search ------------------------------------------------------

    private val searchLock = Any()
    private var searchInstance: SqlitePlaceSearch? = null

    /**
     * The offline place index, or null when the pack has no `places.db`.
     *
     * Opening the database is a few milliseconds but it is done lazily and
     * guarded, because it is the one engine that is useless without a pack.
     */
    fun placeSearch(): SqlitePlaceSearch? {
        synchronized(searchLock) {
            searchInstance?.let { if (it.isReady()) return it else { it.close(); searchInstance = null } }
            val pack = pack() ?: return null
            val db = pack.databaseFile() ?: return null
            val opened = SqlitePlaceSearch.open(db, pack.bounds)
            searchInstance = opened
            return opened
        }
    }

    /**
     * The asynchronous search front end the search box talks to, or null when
     * there is no pack.
     *
     * Memoised separately from [placeSearch] because it owns an executor and a
     * debounce state; two instances would mean two dispatchers racing to
     * answer the same keystroke.
     */
    @Volatile
    private var searchService: com.ts8.core.search.PlaceSearchService? = null

    fun searchService(): com.ts8.core.search.PlaceSearchService? {
        searchService?.let { return it }
        synchronized(searchLock) {
            searchService?.let { return it }
            val engine = placeSearch() ?: return null
            val created = com.ts8.core.search.PlaceSearchService(engine, ioExecutor, debounceMs = 250L)
            searchService = created
            return created
        }
    }

    // -- routing -----------------------------------------------------------

    private val routerLock = Any()
    private var routerInstance: AStarRouter? = null

    /**
     * The A* router over the pack's `routing-graph/`.
     *
     * `AStarRouter.open` memory-maps `graph.bin`, so this allocates almost no
     * heap. It is nevertheless built lazily: a driver who only listens to music
     * should never pay for it.
     */
    fun routing(): RoutingEngine? {
        synchronized(routerLock) {
            routerInstance?.let { return it }
            val pack = pack() ?: return null
            val dir = pack.routingDir() ?: return null
            val opened = AStarRouter.open(dir)
            routerInstance = opened
            return opened
        }
    }

    // -- location ----------------------------------------------------------

    /** The platform-free GPS pipeline. One instance: its smoothing state must
     *  not be reset by a screen change, or the car's bearing jumps. */
    val locationEngine: LocationEngine by lazy { DefaultLocationEngine() }

    /**
     * The process-wide GPS subscription.
     *
     * Distinct from [locationEngine]: that one is the filter, this one is the
     * `LocationManager` registration plus the main-thread fan-out. Screens
     * reference-count it, so three visible screens share one receiver and one
     * continuous track.
     */
    val location: com.ts8.launcher.runtime.LocationRuntime by lazy {
        com.ts8.launcher.runtime.LocationRuntime(appContext, locationEngine)
    }

    // -- navigation --------------------------------------------------------

    /**
     * The navigation state machine, built on first use and only when a router
     * exists. A null here means the map tab shows "chưa có dữ liệu" instead of
     * pretending to navigate, which the spec requires.
     */
    private val navLock = Any()
    private var navigationInstance: NavigationController? = null

    fun navigation(): NavigationController? {
        synchronized(navLock) {
            navigationInstance?.let { return it }
            val router = routing() ?: return null
            val created = NavigationController(router)
            navigationInstance = created
            return created
        }
    }

    /** Vietnamese voice guidance, constructed once and shared by every screen. */
    val voice: VoiceGuidance by lazy { VoiceGuidance(appContext, audioBridge) }

    /**
     * The speed pipeline (§8 "Tốc độ & cảnh báo").
     *
     * Owned here for the same reason [location] is: the number must not depend
     * on which screen is visible. It reads the routing graph for the road's
     * limit, feeds itself from [location], and plays the alert through
     * [audioBridge]. Lazily built so a driver who only listens to music never
     * constructs a `ToneGenerator`.
     */
    val speed: com.ts8.launcher.runtime.SpeedRuntime by lazy {
        com.ts8.launcher.runtime.SpeedRuntime(appContext, settings, this)
    }

    // -- music -------------------------------------------------------------

    /**
     * The local music backend.
     *
     * Deliberately *not* built here: it belongs to [com.ts8.launcher.service
     * .MusicPlaybackService], which is the process-level owner that outlives
     * every Activity. This getter exists only for code that has already been
     * handed the service's backend and needs the same instance; it returns null
     * until the service has started, so a screen cannot accidentally create a
     * second database handle.
     */
    @Volatile
    var musicBackend: LocalMusicBackend? = null
        private set

    fun attachMusicBackend(backend: LocalMusicBackend) {
        musicBackend = backend
    }

    fun detachMusicBackend(backend: LocalMusicBackend) {
        if (musicBackend === backend) musicBackend = null
    }

    /** Where a map pack may be installed, for the UI to name in its error. */
    fun packSearchRoots(): List<File> = MapPack.defaultRoots(appContext)

    // -- app catalogue -----------------------------------------------------

    /**
     * The installed-app catalogue, shared by the drawer and by Voice Instant.
     *
     * **Why one instance.** The catalogue caches the resolved list and owns a
     * loader thread; a second instance would re-query `PackageManager` and, worse,
     * hold a second thread for the life of the process. The drawer and a voice
     * command asking "mở đài" must agree on what exists.
     */
    @Volatile
    private var appCatalogInstance: com.ts8.launcher.ui.AppCatalog? = null

    fun appCatalog(): com.ts8.launcher.ui.AppCatalog {
        appCatalogInstance?.let { return it }
        synchronized(this) {
            appCatalogInstance?.let { return it }
            val created = com.ts8.launcher.ui.AppCatalog(appContext)
            appCatalogInstance = created
            return created
        }
    }

    // -- runtime state store -----------------------------------------------

    /**
     * The preference row store the platform-free modules write through.
     *
     * One instance for the process: `VoiceStateStore` and `ParkingStateStore`
     * both wrap it, and two adapters over the same file would each hold their
     * own `SharedPreferences` handle — which on API 23 is not a problem for
     * correctness but is a second on-disk cache for no benefit.
     */
    val runtimeState: com.ts8.launcher.runtime.PrefsKeyValueStore by lazy {
        com.ts8.launcher.runtime.PrefsKeyValueStore(appContext)
    }

    // -- voice -------------------------------------------------------------

    /**
     * Voice Instant's process-level controller, built on first use.
     *
     * **Why the graph owns it.** The microphone session, the ducked music and a
     * pending confirmation must outlive the screen that opened them: the driver
     * can speak "dẫn đường tới Cầu Rồng" from Home and the overlay must still be
     * there when the map comes forward. An Activity-owned controller would be
     * destroyed by exactly the navigation the command performs.
     *
     * **Why lazy.** Voice is the one feature whose setup costs a `TextToSpeech`
     * binding and a `SpeechRecognizer` construction; a driver who never speaks a
     * command should never pay for either, and a unit whose TTS engine hangs on
     * init must not delay the launcher's cold start.
     */
    @Volatile
    private var voiceController: com.ts8.launcher.runtime.VoiceInstantController? = null

    fun voiceInstant(): com.ts8.launcher.runtime.VoiceInstantController {
        voiceController?.let { return it }
        synchronized(this) {
            voiceController?.let { return it }
            val created = com.ts8.launcher.runtime.VoiceInstantController(
                context = appContext,
                feedback = com.ts8.launcher.runtime.VoiceFeedback(appContext, audioBridge),
                executor = com.ts8.launcher.runtime.VoiceCommandExecutor(appContext, this),
                store = com.ts8.core.voice.VoiceStateStore(runtimeState)
            )
            voiceController = created
            return created
        }
    }

    // -- parking -----------------------------------------------------------

    /**
     * The parking resolver (§18), one per process.
     *
     * Kept here rather than in `ParkingModeActivity` because the resolver
     * accumulates dwell across ticks and screens: a screen-owned instance would
     * restart its two-minute window every time the driver left and came back,
     * which is the same as never reaching it. Lazily built and driven from the
     * main looper by whatever screen is listening.
     */
    val parkingResolver: com.ts8.core.parking.ParkingStateResolver by lazy {
        com.ts8.core.parking.ParkingStateResolver(
            clock = { android.os.SystemClock.elapsedRealtime() }
        )
    }

    /** The persisted last decision, so a reboot lands on the same screen. */
    val parkingStore: com.ts8.core.parking.ParkingStateStore by lazy {
        com.ts8.core.parking.ParkingStateStore(runtimeState) {
            android.os.SystemClock.elapsedRealtime()
        }
    }

    /**
     * The process-wide parking detector (§18–19).
     *
     * Owned here for the same reason [speed] is: the decision depends on dwell
     * accumulated over minutes, so an instance created per screen would restart
     * its window every time the driver left the launcher and came back — which
     * is the same as never reaching it. It drives [parkingResolver] from the
     * main looper and can open `ParkingModeActivity` by itself.
     */
    val parking: com.ts8.launcher.runtime.ParkingRuntime by lazy {
        com.ts8.launcher.runtime.ParkingRuntime(appContext, settings, this)
    }

    /**
     * The navigation state machine's state without building one.
     *
     * The parking detector asks "is guidance running" once a second. Calling
     * [navigation] for that would *create* the controller on the first tick —
     * memory-mapping the routing graph for a driver who only opened the
     * launcher to check the time. This answers IDLE until something really is
     * navigating, which is the honest answer and the cheap one.
     */
    fun navigationStateIfBuilt(): com.ts8.core.navigation.NavigationState =
        navigationInstance?.state() ?: com.ts8.core.navigation.NavigationState.IDLE

    /**
     * The controller itself, but only if something already built it.
     *
     * §16's navigation-aware Home needs more than [navigationStateIfBuilt]'s
     * one enum: it needs the next maneuver and the distance to it, which only
     * [NavigationController.uiState] carries. It still must not *build* the
     * controller — a driver who opened the launcher to look at the time would
     * pay for memory-mapping the routing graph to draw a strip that stays
     * hidden. Null therefore means "nothing is navigating", and Home hides the
     * strip on exactly that answer.
     *
     * Read under [navLock] rather than bare: [navigation] assigns the field
     * from whichever thread first asked, and a torn read here would be a
     * half-constructed controller handed to a UI callback.
     */
    fun navigationIfBuilt(): NavigationController? = synchronized(navLock) { navigationInstance }

    // -- online music ------------------------------------------------------

    /**
     * The online catalogue, its providers and the player's stream resolver
     * (§12–13), built on first use.
     *
     * **Why one instance.** The gateway holds each provider's circuit breaker
     * and the status cache; a per-screen instance would let three screens each
     * open a breaker on the same dead host and make the search screen disagree
     * with the music screen about whether Radio Browser is up. It also owns the
     * resolver the *player* calls, which must be the same object the screens
     * configured from.
     *
     * **Why lazy.** A driver who only plays USB music never touches the network
     * in this launcher, and building a gateway is cheap but pointless. It is
     * built the first time a screen asks for it or the service installed the
     * resolver.
     */
    val onlineMusic: com.ts8.launcher.runtime.OnlineMusicRuntime by lazy {
        com.ts8.launcher.runtime.OnlineMusicRuntime(appContext, settings, this)
    }

    // -- ui state ----------------------------------------------------------

    /**
     * The §16 state machine, one per process.
     *
     * **Why not one per Activity.** The whole point of the machine is that two
     * screens disagreeing about the primary state is impossible. A per-activity
     * instance would be rebuilt by the very navigation it is supposed to
     * describe, so NAVIGATING could not outlive a trip to the music screen —
     * which is §16's "khi quay Home trong lúc navigation, Home hiển thị
     * navigation-aware layout".
     *
     * **Why lazy.** A screen that never reads it pays nothing, and the machine
     * constructs only an enum and a store reference.
     */
    val uiState: com.ts8.launcher.runtime.AppStateMachine by lazy {
        com.ts8.launcher.runtime.AppStateMachine(runtimeState) {
            // Asked only when the machine actually restores, which is the one
            // moment the answer matters. `navigationStateIfBuilt` is the right
            // question here rather than `navigation()`: a process that came
            // back may legitimately have nothing to navigate with, and asking
            // it to build a controller just to be told "no" would spend the
            // routing graph's memory to learn that.
            when (navigationStateIfBuilt()) {
                com.ts8.core.navigation.NavigationState.NAVIGATING,
                com.ts8.core.navigation.NavigationState.REROUTING,
                com.ts8.core.navigation.NavigationState.ARRIVED -> true
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "Ts8AppGraph"
        /** Subdirectories of a pack, never a pack of their own. */
        private val PACK_CONTENT_DIRS = setOf(
            "fonts", "routing-graph", "style", "sprites", "tiles", "glyphs"
        )

        /** Set by [com.ts8.launcher.Ts8LauncherApp]; the Activities read it. */
        @Volatile
        var current: AppGraph? = null
            private set

        fun install(graph: AppGraph) {
            current = graph
        }

        fun require(): AppGraph =
            current ?: error("AppGraph chưa được khởi tạo; Ts8LauncherApp phải chạy trước")
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.os.SystemClock
import com.ts8.core.map.MapStyleMode
import com.ts8.core.speed.SpeedFilter
import com.ts8.core.speed.SpeedMonitor
import com.ts8.core.speed.SpeedUnit

/**
 * Every setting the launcher owns, in one place.
 *
 * **Why these and not more.** Everything in this file is a setting the launcher
 * itself acts on. A preference the app cannot enforce — a system brightness it
 * has no permission for, a launcher default it refuses to steal — would be a
 * switch that appears to work and does nothing, which the spec forbids.
 * Brightness lives in `Settings.System` and is not duplicated here.
 *
 * **Why one file rather than a setting per screen.** The map style is chosen in
 * Quick Settings, shown on Home and used by the navigation screen; the speed
 * unit is used by the speed chip on Home and the map. Three screens reading
 * three copies of a flag is how they end up disagreeing. One store, read on
 * `onResume` (or through a listener), cannot.
 *
 * **Why every enum is stored by name and parsed defensively.** A prefs file
 * written by a newer build, or half-written after a power cut on a head unit
 * that loses power with the ignition, must not brick a screen. An unreadable
 * value falls back to the documented default rather than throwing, and the
 * default is the one the brief names — `Cân bằng` for performance, `km/h` for
 * speed — not an arbitrary zero.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun registerListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // =======================================================================
    // Map style
    // =======================================================================

    /**
     * How the map decides its day/night palette.
     *
     * `AUTO` by default: the head unit's own clock is the only signal available
     * offline, and a driver who never opens Quick Settings should still get a
     * dark map at night.
     */
    var mapStyleMode: MapStyleMode
        get() = enumOrDefault(KEY_STYLE, MapStyleMode.AUTO)
        set(value) = put(KEY_STYLE, value.name)

    /** The mode a tap on a day/night control moves to. */
    fun nextStyleMode(): MapStyleMode = when (mapStyleMode) {
        MapStyleMode.AUTO -> MapStyleMode.DAY
        MapStyleMode.DAY -> MapStyleMode.NIGHT
        MapStyleMode.NIGHT -> MapStyleMode.AUTO
    }

    // =======================================================================
    // Display group (§8 "Hiển thị")
    // =======================================================================

    /** 2D / 3D / automatic tilt. */
    enum class MapMode { FLAT_2D, THREE_D, AUTO }

    /** Navigation camera framing. */
    enum class CameraMode { NORTH_UP, HEADING_UP, PERSPECTIVE }

    enum class PoiDensity { LOW, NORMAL, HIGH }

    enum class TextScale { SMALL, NORMAL, LARGE }

    /**
     * How much of the GPU budget the map may take.
     *
     * `BALANCED` is the default because the brief names it, and because it is
     * the only one that is correct on all three uses of this panel: a 1024x600
     * head unit with a weak GPU renders fine at balanced, struggles at quality
     * while navigating, and looks visibly poor at saver.
     */
    enum class Performance { QUALITY, BALANCED, SAVER }

    var mapMode: MapMode
        get() = enumOrDefault(KEY_MAP_MODE, MapMode.FLAT_2D)
        set(value) = put(KEY_MAP_MODE, value.name)

    var cameraMode: CameraMode
        get() = enumOrDefault(KEY_CAMERA_MODE, CameraMode.HEADING_UP)
        set(value) = put(KEY_CAMERA_MODE, value.name)

    /** Zoom used when a screen opens with no better idea of where the car is. */
    var defaultZoom: Float
        get() = floatOr(KEY_DEFAULT_ZOOM, DEFAULT_ZOOM)
        set(value) = prefs.edit().putFloat(KEY_DEFAULT_ZOOM, value.coerceIn(MIN_ZOOM, MAX_ZOOM)).apply()

    /** Zoom used while navigating; closer than [defaultZoom] so turns are readable. */
    var navigationZoom: Float
        get() = floatOr(KEY_NAV_ZOOM, NAV_ZOOM)
        set(value) = prefs.edit().putFloat(KEY_NAV_ZOOM, value.coerceIn(MIN_ZOOM, MAX_ZOOM)).apply()

    /** Extruded buildings, gated below zoom 15 by the style itself. */
    var buildings3d: Boolean
        get() = boolOr(KEY_BUILDINGS_3D, true)
        set(value) = putBool(KEY_BUILDINGS_3D, value)

    var labelRoads: Boolean
        get() = boolOr(KEY_LABEL_ROAD, true)
        set(value) = putBool(KEY_LABEL_ROAD, value)

    var labelPoi: Boolean
        get() = boolOr(KEY_LABEL_POI, true)
        set(value) = putBool(KEY_LABEL_POI, value)

    var labelPlace: Boolean
        get() = boolOr(KEY_LABEL_PLACE, true)
        set(value) = putBool(KEY_LABEL_PLACE, value)

    var labelHouseNumber: Boolean
        get() = boolOr(KEY_LABEL_HOUSE, false)
        set(value) = putBool(KEY_LABEL_HOUSE, value)

    var poiDensity: PoiDensity
        get() = enumOrDefault(KEY_POI_DENSITY, PoiDensity.NORMAL)
        set(value) = put(KEY_POI_DENSITY, value.name)

    var textScale: TextScale
        get() = enumOrDefault(KEY_TEXT_SCALE, TextScale.NORMAL)
        set(value) = put(KEY_TEXT_SCALE, value.name)

    /** Draw the part of the route already driven, rather than erasing it. */
    var showTravelledRoute: Boolean
        get() = boolOr(KEY_ROUTE_TRAVELLED, true)
        set(value) = putBool(KEY_ROUTE_TRAVELLED, value)

    /** ARGB for the main route line. */
    var routeColour: Int
        get() = intOr(KEY_ROUTE_COLOUR, 0xFF3B82F6.toInt())
        set(value) = prefs.edit().putInt(KEY_ROUTE_COLOUR, value).apply()

    var altRouteColour: Int
        get() = intOr(KEY_ALT_COLOUR, 0xFF6C7A91.toInt())
        set(value) = prefs.edit().putInt(KEY_ALT_COLOUR, value).apply()

    var autoFollow: Boolean
        get() = boolOr(KEY_AUTO_FOLLOW, true)
        set(value) = putBool(KEY_AUTO_FOLLOW, value)

    var autoCentre: Boolean
        get() = boolOr(KEY_AUTO_CENTRE, true)
        set(value) = putBool(KEY_AUTO_CENTRE, value)

    /**
     * Seconds before a panned-away camera returns to the car; `0` disables it.
     *
     * A driver who pans to look at a side street and then has to drive must not
     * have the camera snap back mid-manoeuvre, and a driver who taps "recenter"
     * expects it to stay. Both are the same setting.
     */
    var followReturnSeconds: Int
        get() = intOr(KEY_FOLLOW_RETURN, 10)
        set(value) = prefs.edit().putInt(KEY_FOLLOW_RETURN, value.coerceIn(0, 60)).apply()

    var showZoomControls: Boolean
        get() = boolOr(KEY_SHOW_ZOOM, true)
        set(value) = putBool(KEY_SHOW_ZOOM, value)

    var showCompass: Boolean
        get() = boolOr(KEY_SHOW_COMPASS, true)
        set(value) = putBool(KEY_SHOW_COMPASS, value)

    var showRecenter: Boolean
        get() = boolOr(KEY_SHOW_RECENTER, true)
        set(value) = putBool(KEY_SHOW_RECENTER, value)

    var showOverview: Boolean
        get() = boolOr(KEY_SHOW_OVERVIEW, true)
        set(value) = putBool(KEY_SHOW_OVERVIEW, value)

    var performance: Performance
        get() = enumOrDefault(KEY_PERFORMANCE, Performance.BALANCED)
        set(value) = put(KEY_PERFORMANCE, value.name)

    /**
     * Puts every display setting back to its documented default.
     *
     * Only the display keys, not the whole store: the driver pressing "Khôi
     * phục mặc định" on the display panel must not also lose their music
     * library scan path or their speed-alert volume.
     */
    fun restoreDisplayDefaults() {
        prefs.edit()
            .remove(KEY_MAP_MODE)
            .remove(KEY_STYLE)
            .remove(KEY_CAMERA_MODE)
            .remove(KEY_DEFAULT_ZOOM)
            .remove(KEY_NAV_ZOOM)
            .remove(KEY_BUILDINGS_3D)
            .remove(KEY_LABEL_ROAD)
            .remove(KEY_LABEL_POI)
            .remove(KEY_LABEL_PLACE)
            .remove(KEY_LABEL_HOUSE)
            .remove(KEY_POI_DENSITY)
            .remove(KEY_TEXT_SCALE)
            .remove(KEY_ROUTE_TRAVELLED)
            .remove(KEY_ROUTE_COLOUR)
            .remove(KEY_ALT_COLOUR)
            .remove(KEY_AUTO_FOLLOW)
            .remove(KEY_AUTO_CENTRE)
            .remove(KEY_FOLLOW_RETURN)
            .remove(KEY_SHOW_ZOOM)
            .remove(KEY_SHOW_COMPASS)
            .remove(KEY_SHOW_RECENTER)
            .remove(KEY_SHOW_OVERVIEW)
            .remove(KEY_PERFORMANCE)
            .apply()
    }

    // =======================================================================
    // Speed & alerts (§8 "Tốc độ & cảnh báo")
    // =======================================================================

    /** `km/h` on this market; the brief names it as the default. */
    var speedUnit: SpeedUnit
        get() = enumOrDefault(KEY_SPEED_UNIT, SpeedUnit.KMH)
        set(value) = put(KEY_SPEED_UNIT, value.name)

    var showCurrentSpeed: Boolean
        get() = boolOr(KEY_SHOW_SPEED, true)
        set(value) = putBool(KEY_SHOW_SPEED, value)

    var showSpeedLimit: Boolean
        get() = boolOr(KEY_SHOW_LIMIT, true)
        set(value) = putBool(KEY_SHOW_LIMIT, value)

    /**
     * Below this the readout is forced to 0, in km/h; `0` disables the filter.
     *
     * Stored in km/h even when the driver reads mph, because the threshold is a
     * property of the receiver's noise floor, not of the driver's unit choice.
     */
    var gpsFilterKmh: Int
        get() = intOr(KEY_GPS_FILTER, 0)
        set(value) = prefs.edit().putInt(KEY_GPS_FILTER, value.coerceIn(0, 20)).apply()

    var speedSmoothing: SpeedFilter.Smoothing
        get() = enumOrDefault(KEY_SMOOTHING, SpeedFilter.Smoothing.BALANCED)
        set(value) = put(KEY_SMOOTHING, value.name)

    var speedAlertsEnabled: Boolean
        get() = boolOr(KEY_ALERTS, true)
        set(value) = putBool(KEY_ALERTS, value)

    /** How far over the limit counts as overspeed, in km/h (0/5/10/15/custom). */
    var overspeedThresholdKmh: Int
        get() = intOr(KEY_THRESHOLD, 5)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value.coerceIn(0, 50)).apply()

    var speedAlertForm: SpeedMonitor.AlertForm
        get() = enumOrDefault(KEY_ALERT_FORM, SpeedMonitor.AlertForm.BOTH)
        set(value) = put(KEY_ALERT_FORM, value.name)

    /**
     * Alert loudness 0..1, independent of the music slider.
     *
     * Applied as a scale on the alert's own playback, so turning music down
     * does not turn the warning down; audio focus is still requested for the
     * alert, which is what the brief's "respects audio focus" requires.
     */
    var speedAlertVolume: Float
        get() = floatOr(KEY_ALERT_VOLUME, 0.8f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_ALERT_VOLUME, value.coerceIn(0f, 1f)).apply()

    var speedAlertCooldownMs: Long
        get() = intOr(KEY_COOLDOWN, 15) * 1000L
        set(value) = prefs.edit().putInt(KEY_COOLDOWN, (value / 1000L).toInt().coerceIn(5, 120)).apply()

    /** Only alert after the speed stayed over the threshold for a window. */
    var sustainedOverOnly: Boolean
        get() = boolOr(KEY_SUSTAINED, true)
        set(value) = putBool(KEY_SUSTAINED, value)

    /** The live configuration the speed pipeline runs on. */
    fun speedConfig(): SpeedMonitor.Config = SpeedMonitor.Config(
        enabled = true,
        unit = speedUnit,
        showSpeed = showCurrentSpeed,
        showLimit = showSpeedLimit,
        smoothing = speedSmoothing,
        thresholdKmh = overspeedThresholdKmh,
        alertsEnabled = speedAlertsEnabled,
        alertForm = speedAlertForm,
        alertVolume = speedAlertVolume,
        sustainedOnly = sustainedOverOnly,
        deadBandMps = gpsFilterKmh / 3.6,
        cooldownMs = speedAlertCooldownMs
    )

    /**
     * The `Hiển thị` group as one immutable snapshot for the renderer.
     *
     * **Why this lives here and not in the map module.** `core:map-api` has no
     * context and must not read preferences; the launcher is the only layer that
     * knows both the store and the renderer. This function is the single
     * translation, so a new display setting cannot be stored, shown in the panel
     * and then forgotten on the way to the style — the failure this exists to
     * prevent.
     */
    fun mapDisplayConfig(): com.ts8.core.map.MapDisplayConfig =
        com.ts8.core.map.MapDisplayConfig(
            mode = when (mapMode) {
                MapMode.FLAT_2D -> com.ts8.core.map.MapDisplayConfig.MapMode.FLAT_2D
                MapMode.THREE_D -> com.ts8.core.map.MapDisplayConfig.MapMode.THREE_D
                MapMode.AUTO -> com.ts8.core.map.MapDisplayConfig.MapMode.AUTO
            },
            buildings3d = buildings3d,
            labelRoads = labelRoads,
            labelPoi = labelPoi,
            labelPlace = labelPlace,
            labelHouseNumbers = labelHouseNumber,
            poiDensity = when (poiDensity) {
                PoiDensity.LOW -> com.ts8.core.map.MapDisplayConfig.PoiDensity.LOW
                PoiDensity.NORMAL -> com.ts8.core.map.MapDisplayConfig.PoiDensity.NORMAL
                PoiDensity.HIGH -> com.ts8.core.map.MapDisplayConfig.PoiDensity.HIGH
            },
            textScale = when (textScale) {
                TextScale.SMALL -> com.ts8.core.map.MapDisplayConfig.TextScale.SMALL
                TextScale.NORMAL -> com.ts8.core.map.MapDisplayConfig.TextScale.NORMAL
                TextScale.LARGE -> com.ts8.core.map.MapDisplayConfig.TextScale.LARGE
            },
            routeColour = routeColour,
            altRouteColour = altRouteColour,
            showTravelledRoute = showTravelledRoute,
            performance = when (performance) {
                Performance.QUALITY -> com.ts8.core.map.MapDisplayConfig.Performance.QUALITY
                Performance.BALANCED -> com.ts8.core.map.MapDisplayConfig.Performance.BALANCED
                Performance.SAVER -> com.ts8.core.map.MapDisplayConfig.Performance.SAVER
            }
        )

    // =======================================================================
    // Map pack pointer
    // =======================================================================

    /**
     * Directory of the pack the driver explicitly activated, or null.
     *
     * **Why a pointer at all when discovery works.** Discovery picks the first
     * valid pack in root priority order, which is right for a single-pack unit
     * and wrong the moment two regions are installed: the driver activates
     * Đà Nẵng, plugs in a Hà Nội pack higher in the list, and silently gets Hà
     * Nội. The pointer makes "which pack is active" a stored decision rather
     * than an accident of directory sort order.
     */
    var activePackPath: String?
        get() = prefs.getString(KEY_ACTIVE_PACK, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_ACTIVE_PACK) else putString(KEY_ACTIVE_PACK, value)
        }.apply()

    /** Set when the driver confirmed "no map" after removing the active pack. */
    var noMapConfirmed: Boolean
        get() = boolOr(KEY_NO_MAP, false)
        set(value) = putBool(KEY_NO_MAP, value)

    // =======================================================================
    // Parking (§18)
    // =======================================================================

    /**
     * Whether the launcher may enter Parking Mode by itself.
     *
     * Off by default. Auto-entry is the one behaviour in that screen that can
     * surprise a driver — a wrong guess over a shop front hides the map — so it
     * is opt-in and additionally requires high confidence from the resolver.
     */
    var parkingAutoEnter: Boolean
        get() = boolOr(KEY_PARKING_AUTO, false)
        set(value) = putBool(KEY_PARKING_AUTO, value)

    // =======================================================================
    // Online music (§13)
    // =======================================================================

    /** Base URL for a Navidrome/Subsonic-compatible server; empty = unconfigured. */
    var onlineServerUrl: String
        get() = prefs.getString(KEY_ONLINE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ONLINE_URL, value.trim()).apply()

    var onlineUser: String
        get() = prefs.getString(KEY_ONLINE_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ONLINE_USER, value.trim()).apply()

    /**
     * Password/token for the music server.
     *
     * Stored in the app's private prefs, which is the only store Android 6 gives
     * an app without a keystore ceremony. It is never logged and never shown
     * back to the UI — the settings row shows only whether it is set.
     */
    var onlineToken: String
        get() = prefs.getString(KEY_ONLINE_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ONLINE_TOKEN, value).apply()

    var onlineEnabled: Boolean
        get() = boolOr(KEY_ONLINE_ENABLED, true)
        set(value) = putBool(KEY_ONLINE_ENABLED, value)

    /**
     * Podcast RSS feed the driver subscribed to; empty = unconfigured.
     *
     * Separate from [onlineServerUrl] because a feed and a Subsonic server are
     * unrelated endpoints: one setting for both would make entering a podcast
     * address silently break the music server, and the provider that reads it
     * would be the one to find out.
     */
    var onlineFeedUrl: String
        get() = prefs.getString(KEY_ONLINE_FEED, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ONLINE_FEED, value.trim()).apply()

    /**
     * Which Subsonic-compatible family [onlineServerUrl] points at.
     *
     * Jellyfin and Navidrome share the Subsonic API but not every endpoint's
     * availability, and the provider's [com.ts8.core.music.online.NavidromeProvider.kind]
     * is what the UI labels a result with. Stored as a string name, not an
     * ordinal: an enum reordered in a later build must not silently move a
     * driver's Jellyfin onto the Navidrome branch.
     */
    var onlineServerKind: String
        get() = prefs.getString(KEY_ONLINE_KIND, com.ts8.core.music.online.ProviderKind.NAVIDROME.name)
            .orEmpty()
        set(value) = prefs.edit().putString(KEY_ONLINE_KIND, value).apply()

    // =======================================================================
    // Internals
    // =======================================================================

    private fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun putBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun boolOr(key: String, fallback: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, fallback) }.getOrDefault(fallback)

    private fun intOr(key: String, fallback: Int): Int =
        runCatching { prefs.getInt(key, fallback) }.getOrDefault(fallback)

    private fun floatOr(key: String, fallback: Float): Float =
        runCatching { prefs.getFloat(key, fallback) }.getOrDefault(fallback)

    /**
     * Reads an enum by name, falling back rather than throwing.
     *
     * `ClassCastException` is a real case here: a value written as a string by
     * an older build that stored an ordinal would come back as an int.
     */
    private inline fun <reified T : Enum<T>> enumOrDefault(key: String, fallback: T): T {
        val raw = runCatching { prefs.getString(key, null) }.getOrNull() ?: return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    companion object {
        private const val PREFS = "ts8_settings"

        // Map style
        private const val KEY_STYLE = "map_style_mode_v1"

        // Display
        private const val KEY_MAP_MODE = "disp_map_mode_v1"
        private const val KEY_CAMERA_MODE = "disp_camera_v1"
        private const val KEY_DEFAULT_ZOOM = "disp_zoom_default_v1"
        private const val KEY_NAV_ZOOM = "disp_zoom_nav_v1"
        private const val KEY_BUILDINGS_3D = "disp_buildings_v1"
        private const val KEY_LABEL_ROAD = "disp_label_road_v1"
        private const val KEY_LABEL_POI = "disp_label_poi_v1"
        private const val KEY_LABEL_PLACE = "disp_label_place_v1"
        private const val KEY_LABEL_HOUSE = "disp_label_house_v1"
        private const val KEY_POI_DENSITY = "disp_poi_density_v1"
        private const val KEY_TEXT_SCALE = "disp_text_scale_v1"
        private const val KEY_ROUTE_TRAVELLED = "disp_route_travelled_v1"
        private const val KEY_ROUTE_COLOUR = "disp_route_colour_v1"
        private const val KEY_ALT_COLOUR = "disp_alt_colour_v1"
        private const val KEY_AUTO_FOLLOW = "disp_auto_follow_v1"
        private const val KEY_AUTO_CENTRE = "disp_auto_centre_v1"
        private const val KEY_FOLLOW_RETURN = "disp_follow_return_v1"
        private const val KEY_SHOW_ZOOM = "disp_show_zoom_v1"
        private const val KEY_SHOW_COMPASS = "disp_show_compass_v1"
        private const val KEY_SHOW_RECENTER = "disp_show_recenter_v1"
        private const val KEY_SHOW_OVERVIEW = "disp_show_overview_v1"
        private const val KEY_PERFORMANCE = "disp_performance_v1"

        // Speed
        private const val KEY_SPEED_UNIT = "speed_unit_v1"
        private const val KEY_SHOW_SPEED = "speed_show_v1"
        private const val KEY_SHOW_LIMIT = "speed_limit_show_v1"
        private const val KEY_GPS_FILTER = "speed_gps_filter_v1"
        private const val KEY_SMOOTHING = "speed_smoothing_v1"
        private const val KEY_ALERTS = "speed_alerts_v1"
        private const val KEY_THRESHOLD = "speed_threshold_v1"
        private const val KEY_ALERT_FORM = "speed_alert_form_v1"
        private const val KEY_ALERT_VOLUME = "speed_alert_volume_v1"
        private const val KEY_COOLDOWN = "speed_cooldown_s_v1"
        private const val KEY_SUSTAINED = "speed_sustained_v1"

        // Pack + parking + online
        private const val KEY_ACTIVE_PACK = "pack_active_path_v1"
        private const val KEY_NO_MAP = "pack_no_map_v1"
        private const val KEY_PARKING_AUTO = "parking_auto_v1"
        private const val KEY_ONLINE_URL = "online_server_url_v1"
        private const val KEY_ONLINE_USER = "online_server_user_v1"
        private const val KEY_ONLINE_TOKEN = "online_server_token_v1"
        private const val KEY_ONLINE_ENABLED = "online_enabled_v1"
        private const val KEY_ONLINE_FEED = "online_feed_url_v1"
        private const val KEY_ONLINE_KIND = "online_server_kind_v1"

        /** Same range the map controller enforces, kept here for the settings UI. */
        const val MIN_ZOOM = 6.0f
        const val MAX_ZOOM = 18.0f
        const val DEFAULT_ZOOM = 12.0f
        const val NAV_ZOOM = 16.5f
    }
}

/** Convenience so a screen can build the speed configuration in one call. */
fun AppSettings.speedConfigNow(): SpeedMonitor.Config = speedConfig()

/** The current elapsed-realtime clock, used by the speed pipeline. */
fun elapsedClock(): Long = SystemClock.elapsedRealtime()

END FILE

## app/src/main/java/com/ts8/launcher/runtime/AppStateMachine.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.util.Log
import com.ts8.core.navigation.NavigationState

/**
 * The app's primary screen state machine (§16–17).
 *
 * **Why a machine and not "whatever Activity is on top".** The brief's rule is
 * not that ten screens must exist — they already do — but that navigation
 * between them is a transition on one state, so three things that used to be
 * guesses become facts: which screen is primary while an overlay is up, what
 * Back does, and what "still navigating while the driver is on Home" means.
 * Without it, `MapActivity` and `HomeActivity` each decide locally and a driver
 * who starts guidance from Home and then taps the music tab leaves the machine
 * describing a state nobody is in.
 *
 * **What this machine does *not* own.** It holds no engine and no player. The
 * map renderer, the navigator, the router and the `MediaPlayer` keep living in
 * [com.ts8.launcher.di.AppGraph] and in the playback service, which is what
 * makes §16's "bottom navigation đổi primary destination nhưng không hủy
 * map/music engine" structural rather than a promise: there is nothing here
 * to destroy.
 *
 * **Why overlays are a separate stack.** §16 lists four overlay states that
 * are orthogonal to the ten primary ones — the voice bar can appear over the
 * map while a route is being previewed, and the import progress sits over Map
 * Management while the screen underneath is still usable-ish. Folding them
 * into the primary enum would multiply ten states by four and make "Back
 * closes the overlay first" a switch over forty cases.
 *
 * **What survives a process death.** Only the primary state, and only the ones
 * that can be true again. [restore] degrades the transient ones exactly as
 * `VoiceStateStore` does for the voice overlay: a DESTINATION_PREVIEW whose
 * destination was never persisted, a SEARCH with no query, and a NAVIGATING
 * whose navigator died with the process all come back as the screen they were
 * drawn over, [AppState.MAP_BROWSE]. The overlay stack is never restored: an
 * import has no archive handle after a kill and an error dialog's cause is
 * gone, so restoring either would be a spinner that never stops.
 */
class AppStateMachine(
    private val store: StateStore,
    /**
     * Whether guidance is really running *now*.
     *
     * Asked only once, at the moment of the restore, because it is a question
     * about the process that came back: `AppGraph` answers false until it has
     * rebuilt a navigator, and rebuilding one just to answer this would defeat
     * the point of [restore] — a driver must not pay for the routing graph to
     * find out whether they are still navigating.
     */
    private val navigationRunning: () -> Boolean = { false }
) {

    /** The ten primary destinations of §16. */
    enum class State {
        HOME,
        MAP_BROWSE,
        SEARCH,
        DESTINATION_PREVIEW,
        ROUTE_PREVIEW,
        NAVIGATING,
        MUSIC_EXPANDED,
        APP_DRAWER,
        QUICK_SETTINGS,
        PARKING
    }

    /** The four overlays of §16, in the order they are stacked. */
    enum class Overlay {
        VOICE_LISTENING,
        VOICE_CONFIRMATION,
        MAP_IMPORT_PROGRESS,
        ERROR_DIALOG
    }

    /** Told about every accepted move, on the main thread. */
    fun interface Listener {
        fun onStateChanged(previous: State, current: State, overlays: List<Overlay>)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /** Guarded by this; the machine is main-looper-owned but readable off it. */
    @Volatile
    private var current: State = State.HOME

    private val overlayStack = ArrayList<Overlay>(4)

    // -- reading -----------------------------------------------------------

    fun state(): State = current

    /** The overlay Back would close, or null when nothing is stacked. */
    fun topOverlay(): Overlay? = synchronized(overlayStack) {
        overlayStack.lastOrNull()
    }

    fun overlays(): List<Overlay> = synchronized(overlayStack) { ArrayList(overlayStack) }

    fun isOverlayShowing(): Boolean = synchronized(overlayStack) { overlayStack.isNotEmpty() }

    // -- moving ------------------------------------------------------------

    /**
     * Moves to [next] if the transition is legal.
     *
     * Returns false for an illegal move rather than throwing or silently
     * accepting: a screen that asks for a transition the machine refuses has a
     * bug, and a launcher that crashes in front of a driver over an internal
     * bookkeeping error is a worse outcome than a log line. The illegal case is
     * real — a navigator callback that arrives after the driver already left
     * for the app drawer asks for MAP_BROWSE from APP_DRAWER, which is legal,
     * but one that arrives after PARKING is not.
     */
    fun moveTo(next: State): Boolean {
        val previous: State
        synchronized(this) {
            previous = current
            if (next == previous) return true
            if (!allowed(previous, next)) {
                Log.w(TAG, "illegal transition $previous -> $next ignored")
                return false
            }
            current = next
        }
        notify(previous, next)
        save()
        return true
    }

    /**
     * Mirrors the navigator's own state into the UI state (§16).
     *
     * **Why this exists separately from [moveTo].** Guidance can start, get
     * cancelled and finish while the driver is looking at another screen; the
     * navigation controller is the authority on whether a route is running, and
     * this is the one-way adapter from its vocabulary to the UI's. It never
     * moves the driver away from a non-map screen: starting a route from the
     * app drawer is not possible, but a voice command can start one from Home,
     * and Home is a legal destination while navigating.
     */
    fun syncNavigation(navigationState: NavigationState) {
        val target = when (navigationState) {
            NavigationState.DESTINATION_PREVIEW -> State.DESTINATION_PREVIEW
            NavigationState.ROUTE_CALCULATING,
            NavigationState.ROUTE_PREVIEW -> State.ROUTE_PREVIEW
            NavigationState.NAVIGATING,
            NavigationState.REROUTING,
            NavigationState.ARRIVED -> State.NAVIGATING
            NavigationState.SEARCHING -> State.SEARCH
            NavigationState.IDLE -> when (current) {
                // A finished or cancelled route returns the map to browsing;
                // any other screen keeps what it is showing, because ending a
                // route must not yank the driver out of the music screen.
                State.DESTINATION_PREVIEW,
                State.ROUTE_PREVIEW,
                State.NAVIGATING -> State.MAP_BROWSE
                else -> current
            }
        }
        moveTo(target)
    }

    // -- overlays ----------------------------------------------------------

    /**
     * Pushes [overlay] unless it is already on top.
     *
     * Re-pushing an overlay that is already stacked is a no-op rather than a
     * second entry: the voice bar re-renders on every state change and would
     * otherwise push VOICE_LISTENING once per frame, so a single Back press
     * would have to close an unbounded number of them.
     */
    fun pushOverlay(overlay: Overlay): Boolean = synchronized(overlayStack) {
        if (overlayStack.lastOrNull() == overlay) return false
        overlayStack.remove(overlay)
        overlayStack.add(overlay)
        true
    }.also { added -> if (added) notifyOverlays() }

    /** Removes [overlay] from anywhere in the stack. */
    fun popOverlay(overlay: Overlay): Boolean = synchronized(overlayStack) {
        overlayStack.remove(overlay)
    }.also { removed -> if (removed) notifyOverlays() }

    /**
     * Closes the top overlay. True when there was one.
     *
     * This is §16's "Back đóng overlay trước". The screen's own Back handling
     * runs only when this returns false, which is what stops a Back meant for
     * the microphone from closing the map underneath it.
     */
    fun popTopOverlay(): Boolean {
        val removed = synchronized(overlayStack) {
            if (overlayStack.isEmpty()) null else overlayStack.removeAt(overlayStack.lastIndex)
        } ?: return false
        notifyOverlays()
        return true
    }

    /** True once [restoreAfterProcessDeath] has run, so it cannot run twice. */
    @Volatile
    private var restoredOnce = false

    /**
     * Restores the stored state on the first call and never again.
     *
     * **Why once.** Every screen calls this from `onCreate`, and a process that
     * has already restored must not have a later screen's creation overwrite
     * the live state with what was on disk before the driver started moving.
     * The first screen to be created after a cold start is the one that owns
     * the restore; the rest inherit its result.
     *
     * **Why here and not in `Ts8LauncherApp`.** The Application runs before any
     * window exists and for every process start including a service-only one;
     * restoring there would decide the UI's state for a process that may never
     * draw. Called from a screen instead, it runs exactly when there is
     * something to restore *into*.
     *
     * @return the state the driver is now in.
     */
    fun restoreAfterProcessDeath(): State {
        synchronized(this) {
            if (restoredOnce) return current
            restoredOnce = true
        }
        return restore(navigationRunning())
    }

    // -- persistence -------------------------------------------------------

    /**
     * Saves the primary state.
     *
     * Only the primary state, and only for states that can be true again — see
     * [restore]. Written synchronously (`commit` in the store) because the
     * write happens on a screen change, which is exactly a moment the head unit
     * can lose power.
     */
    fun save() {
        runCatching { store.put(KEY, current.name) }
    }

    /**
     * Restores after a process death, degrading what cannot be true again.
     *
     * @param navigationStillRunning whether the process that came back really
     *        has guidance running. The caller asks [com.ts8.launcher.di.AppGraph]
     *        *after* it has rebuilt the navigator, if it rebuilt one at all;
     *        answering `false` here is always safe and is what a fresh process
     *        reports.
     */
    fun restore(navigationStillRunning: Boolean): State {
        val raw = runCatching { store.get(KEY) }.getOrNull()
        val stored = raw?.let { runCatching { State.valueOf(it) }.getOrNull() }
            ?: return current
        // A search with no query, a destination preview whose destination was
        // never written and a route preview whose route died with the old
        // process all come back as the map. Navigation is different: the route
        // is recomputed from the persisted checkpoint, so it can genuinely
        // still be running.
        val restored = when (stored) {
            State.SEARCH,
            State.DESTINATION_PREVIEW,
            State.ROUTE_PREVIEW -> State.MAP_BROWSE
            State.NAVIGATING -> if (navigationStillRunning) State.NAVIGATING else State.MAP_BROWSE
            else -> stored
        }
        val previous = synchronized(this) {
            val before = current
            current = restored
            before
        }
        if (previous != restored) notify(previous, restored)
        return restored
    }

    // -- listener ----------------------------------------------------------

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notify(previous: State, next: State) {
        val stack = overlays()
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(previous, next, stack) }
                .onFailure { Log.w(TAG, "state listener failed: ${it.message}") }
        }
    }

    private fun notifyOverlays() {
        val stack = overlays()
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(current, current, stack) }
                .onFailure { Log.w(TAG, "state listener failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "Ts8AppState"
        const val KEY = "ui.primary_state"

        /**
         * The legal moves.
         *
         * Written as the destination's permitted sources rather than as a set
         * of pairs: a state added later has exactly one line to update, and a
         * reviewer can read "what may come before NAVIGATING" in one place.
         *
         * **What is deliberately absent.** `PARKING` accepts only Home and the
         * map: the parking screen is a full-bleed panel the driver exits by
         * tapping, and reaching it from an overlay-heavy screen would stack a
         * full-screen panel over one already there. `MUSIC_EXPANDED` accepts
         * every browsing screen and nothing that is mid-route-selection, which
         * matches the rule that opening music must not disturb a route.
         */
        private fun allowed(from: State, to: State): Boolean = when (to) {
            State.HOME -> true
            // Every state may return to browsing the map: it is the screen the
            // map tab draws and the one a cancelled route falls back to.
            State.MAP_BROWSE -> true
            State.SEARCH -> from == State.HOME || from == State.MAP_BROWSE ||
                from == State.DESTINATION_PREVIEW || from == State.ROUTE_PREVIEW
            State.DESTINATION_PREVIEW -> from == State.MAP_BROWSE || from == State.SEARCH
            State.ROUTE_PREVIEW -> from == State.DESTINATION_PREVIEW ||
                from == State.MAP_BROWSE || from == State.NAVIGATING
            State.NAVIGATING -> from == State.DESTINATION_PREVIEW ||
                from == State.ROUTE_PREVIEW || from == State.MAP_BROWSE ||
                from == State.HOME || from == State.MUSIC_EXPANDED ||
                from == State.APP_DRAWER || from == State.QUICK_SETTINGS
            State.MUSIC_EXPANDED -> from == State.HOME || from == State.MAP_BROWSE ||
                from == State.NAVIGATING || from == State.APP_DRAWER ||
                from == State.QUICK_SETTINGS || from == State.PARKING
            State.APP_DRAWER -> from == State.HOME || from == State.MAP_BROWSE ||
                from == State.MUSIC_EXPANDED || from == State.NAVIGATING ||
                from == State.QUICK_SETTINGS
            State.QUICK_SETTINGS -> from == State.HOME || from == State.MAP_BROWSE ||
                from == State.MUSIC_EXPANDED || from == State.APP_DRAWER ||
                from == State.NAVIGATING
            State.PARKING -> from == State.HOME || from == State.MAP_BROWSE
        }
    }
}

/**
 * The two operations the machine needs from a store.
 *
 * Same shape, and the same reasoning, as `VoiceStateStore.KeyValueStore`: a
 * plain get/put pair is trivially fakeable on a JVM, and a `SharedPreferences`
 * reference would drag Android into a type that is otherwise a pure state
 * table. [PrefsKeyValueStore] implements it alongside the two core-module
 * interfaces it already satisfies.
 */
interface StateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/LocationRuntime.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ts8.core.location.AndroidLocationSource
import com.ts8.core.location.LocationEngine
import com.ts8.core.location.LocationFix
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one GPS subscription in the process.
 *
 * **Why this is not owned by a screen.** The spec says the position must keep
 * flowing while the driver moves between Home, the map and the music screen.
 * `LocationManager` re-registers do not just cost time — they discard the
 * smoothing state in [LocationEngine], so the marker jumps and the bearing
 * resets every time a screen opens. One source, reference-counted by the
 * screens that are visible, keeps a single continuous track.
 *
 * **Why the fan-out is here.** [AndroidLocationSource] delivers on its own
 * background thread. Listeners here are UI objects, so every delivery is
 * hopped to the main thread exactly once, before the fan-out. Without that,
 * each screen would have to remember to hop, and one that forgot would touch
 * a View from the wrong thread.
 */
class LocationRuntime(
    context: Context,
    private val engine: LocationEngine
) {

    interface Listener {
        /** An accepted, smoothed, possibly snapped fix. Main thread. */
        fun onFix(fix: LocationFix)

        /** Receiver status changed — used to show "đang tìm tín hiệu GPS". */
        fun onGpsStatus(hasFix: Boolean, satellites: Int) {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var source: AndroidLocationSource? = null

    /** Number of visible screens that asked for fixes. */
    private var refCount = 0

    @Volatile
    private var lastFixInternal: LocationFix? = null

    @Volatile
    private var lastGpsFix = false

    @Volatile
    private var lastSatellites = 0

    val lastFix: LocationFix? get() = lastFixInternal
    val hasGpsFix: Boolean get() = lastGpsFix
    val satellites: Int get() = lastSatellites

    /** True when the platform will let us read position at all. */
    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Registers a listener and starts the receiver if this is the first user.
     *
     * Safe to call from `onStart` without checking anything: a second screen
     * simply joins the stream. The caller must pair it with [release].
     */
    fun acquire(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        refCount++
        if (refCount == 1) start()
        // A screen that opens after the first fix must not sit blank until the
        // next one — it gets the current position immediately.
        lastFixInternal?.let { main.post { deliver(it) } }
    }

    fun release(listener: Listener) {
        listeners.remove(listener)
        if (refCount > 0) refCount--
        if (refCount == 0) stop()
    }

    private fun start() {
        if (source != null) return
        if (!hasPermission()) {
            Log.i(TAG, "no location permission; map will show the pack centre")
            return
        }
        val created = AndroidLocationSource(appContext, engine) { fix ->
            lastFixInternal = fix
            main.post { deliver(fix) }
        }
        source = created
        // `start` is annotated @RequiresPermission; `hasPermission()` above is
        // the check lint cannot see through, because it is a method on this
        // class rather than an inline `checkSelfPermission` call. The call is
        // still wrapped in `runCatching` because a vendor ROM can throw from a
        // provider that was denied even though the grant says otherwise.
        @Suppress("MissingPermission")
        runCatching { created.start() }
            .onFailure { Log.w(TAG, "location start failed: ${it.message}"); source = null }
        scheduleStatusPoll(created)
    }

    /**
     * Polls the satellite state on the main thread.
     *
     * [AndroidLocationSource] exposes its counters as plain fields; polling at
     * 2 s is enough for a status line and avoids adding a callback interface to
     * the core module for what is purely cosmetic.
     */
    private fun scheduleStatusPoll(created: AndroidLocationSource) {
        val poll = object : Runnable {
            override fun run() {
                if (source !== created) return
                val changed = lastGpsFix != created.hasGpsFix ||
                    lastSatellites != created.satelliteCount
                lastGpsFix = created.hasGpsFix
                lastSatellites = created.satelliteCount
                if (changed) {
                    for (l in listeners) runCatching { l.onGpsStatus(lastGpsFix, lastSatellites) }
                }
                main.postDelayed(this, STATUS_POLL_MS)
            }
        }
        main.postDelayed(poll, STATUS_POLL_MS)
    }

    private fun stop() {
        source?.let { runCatching { it.stop() } }
        source = null
        lastGpsFix = false
        lastSatellites = 0
    }

    private fun deliver(fix: LocationFix) {
        for (l in listeners) {
            // A listener that throws must not stop the others: the map still
            // needs its fix after the music card's binding failed.
            runCatching { l.onFix(fix) }
        }
    }

    companion object {
        private const val TAG = "Ts8LocationRuntime"
        private const val STATUS_POLL_MS = 2_000L
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/MapHost.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ts8.core.map.maplibre.MapLibreMapController
import com.ts8.core.map.maplibre.MapPack
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * The Activity-side owner of one [MapView] and its [MapLibreMapController].
 *
 * **Why a class rather than three copies in three screens.** Home and the
 * navigation screen both show a map, and both must get four things exactly
 * right: `MapLibre.getInstance` before the `MapView` constructor, the MapView's
 * own lifecycle forwarded in order, the pack-missing case turned into a message
 * instead of a crash, and the controller destroyed before the view. Each of
 * those is a silent failure when it is wrong — a black rectangle, a leaked GL
 * context, or a thrown `MapLibreConfigurationException` — so it is written once.
 *
 * **Why `MapLibre.getInstance` comes first.** Verified against the 12.0.0 AAR
 * bytecode, not assumed: `MapView.initialize()` calls `MapLibre.hasInstance()`
 * and throws `MapLibreConfigurationException` when it is false. There is no
 * lazy self-initialisation. It also needs **no API key** — `getInstance(Context)`
 * passes `null` to both the constructor and `FileSource.setApiKey` and never
 * calls `throwIfApiKeyInvalid` — which is what makes a fully offline pack
 * possible.
 *
 * **Failure is a state, not an exception.** A pack that is missing, half-copied
 * or unreadable leaves [failure] set and [controller] null. The screen shows
 * that reason. Nothing here draws a placeholder map: a blank grid that looks
 * like a map is worse than a sentence.
 */
class MapHost(
    private val context: Context,
    private val graph: AppGraph,
    private val container: ViewGroup
) {

    /** Non-null once the renderer exists and can be driven. */
    var controller: MapLibreMapController? = null
        private set

    /** Why the map is unavailable, aimed at the driver. Null when it works. */
    var failure: String? = null
        private set

    private var mapView: MapView? = null
    private var created = false

    /**
     * Builds the map and attaches it to [container].
     *
     * @param savedInstanceState forwarded to `MapView.onCreate`, which is how
     *        MapLibre restores its camera after a configuration change.
     */
    fun create(savedInstanceState: Bundle?) {
        if (created) return
        created = true

        val result = graph.loadPack()
        val pack = (result as? MapPack.LoadResult.Ok)?.pack
        if (pack == null) {
            failure = (result as? MapPack.LoadResult.Failure)?.reason
                ?: context.getString(R.string.home_map_unavailable)
            return
        }

        val view = try {
            // The runtime must exist before the view: MapView.initialize()
            // throws MapLibreConfigurationException when it does not.
            MapLibre.getInstance(context)
            MapView(context, MapLibreMapOptions.createFromAttributes(context))
        } catch (e: Throwable) {
            // A firmware with a broken GL driver throws here rather than at the
            // first draw. The launcher stays usable with a message.
            Log.w(TAG, "map view construction failed: ${e.message}")
            failure = context.getString(R.string.home_map_unavailable)
            return
        }

        mapView = view
        // Index 0 so every overlay the screen declares in XML stays above the
        // map without the screen having to know the map exists.
        container.addView(
            view,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val c = MapLibreMapController(context, pack, view)
        // The display snapshot goes in before the style loads: `onMapReady`
        // builds the first document from it, so the map opens already obeying
        // the stored settings instead of snapping to them a frame later.
        c.applyDisplayConfig(graph.settings.mapDisplayConfig())
        controller = c
        view.onCreate(savedInstanceState)
        c.attach()
    }

    /**
     * Re-reads the `Hiển thị` settings and pushes them to the renderer.
     *
     * **Why the host owns this rather than each screen.** Both map screens call
     * it from `onResume`, and the settings panel changes the same values. Three
     * call sites reading the store three ways is how a toggle ends up applied on
     * one screen and not the other. The controller returns immediately when the
     * snapshot is unchanged, so calling this on every resume is cheap.
     */
    fun refreshDisplayConfig() {
        controller?.applyDisplayConfig(graph.settings.mapDisplayConfig())
    }

    /** Attaches a camera-follow policy and any overlays the screen wants. */
    fun withController(block: (MapLibreMapController) -> Unit) {
        controller?.let(block)
    }

    // -- lifecycle ----------------------------------------------------------
    //
    // Forwarded in the platform's order, and guarded so a host whose
    // construction failed is a no-op rather than an NPE in onPause.

    fun onStart() {
        mapView?.onStart()
    }

    fun onResume() {
        mapView?.onResume()
    }

    fun onPause() {
        mapView?.onPause()
    }

    fun onStop() {
        mapView?.onStop()
    }

    fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
    }

    /**
     * Destroys the controller before the view.
     *
     * The order matters: the controller holds a `Style` reference into the
     * native map, and `MapView.onDestroy` releases that map. Destroying the
     * view first would leave the controller calling into freed native memory
     * on any later `setRoute` from a navigation callback that is still running.
     */
    fun onDestroy() {
        controller?.onDestroyView()
        controller = null
        mapView = null
        created = false
    }

    companion object {
        private const val TAG = "Ts8MapHost"
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/MusicRuntime.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.ts8.core.music.MusicBackend
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.PlaybackListener
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.Track
import com.ts8.core.music.local.LocalMusicBackend
import com.ts8.launcher.service.MusicPlaybackService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The UI's connection to the playback service.
 *
 * **Why the screens do not bind directly.** Three screens show playback state
 * — Home's music card, the Navigation strip and the full music screen — and
 * each one would otherwise hold its own `ServiceConnection` and its own
 * `PlaybackListener`. Service binding is not free and, worse, each listener
 * would receive state in a slightly different order relative to `onResume`,
 * so the three cards could disagree about whether music is playing.
 *
 * **Why reference counted.** The service is started with `startService` as
 * well as bound: it keeps playing when no screen is bound (that is the whole
 * point of the foreground service), and it is not killed when the last screen
 * unbinds. The connection itself stays open while at least one screen is
 * attached, and closes when the last one leaves, which keeps the binder
 * reference out of the process's memory when nothing is visible.
 *
 * **API 23 note.** `bindService` is asynchronous even when the service is
 * already running, so a screen that binds in `onStart` and renders in
 * `onResume` would show "chưa phát nhạc" for one frame. [state] therefore
 * returns the last known snapshot, which is correct across a screen switch.
 */
class MusicRuntime(context: Context) {

    /** See the note on `AppGraph`: the instance is a static field, so only the
     *  application context may be kept. */
    private val context: Context = context.applicationContext

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Every attached screen's callback. */
    private val observers = CopyOnWriteArrayList<Observer>()

    @Volatile
    private var bound = false
    private var binding = false
    private var refCount = 0

    /**
     * The backend, once bound.
     *
     * Reaches past [MusicBackend] to the local implementation because the
     * scanner lives there; the UI needs `library` and `player` from the
     * interface, and the service owns everything else.
     */
    @Volatile
    var backend: LocalMusicBackend? = null
        private set

    interface Observer {
        /** Playback state changed. Main thread. */
        fun onPlaybackState(state: PlaybackState)

        /** The backend became available (or went away, with a null argument). */
        fun onBackendChanged(backend: MusicBackend?) {}
    }

    /** Internal listener forwarded from the player into [observers]. */
    private val playbackListener = object : PlaybackListener {
        override fun onStateChanged(state: PlaybackState) = publish(state)

        override fun onError(error: PlaybackError, track: Track?) {
            // The player also pushes a state carrying the error, so the UI gets
            // both; this path exists to log which track failed.
            Log.w(TAG, "playback error $error on ${track?.title}")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as? MusicPlaybackService.LocalBinder
            val b = local?.backend()
            binding = false
            if (b == null) {
                bound = false
                Log.w(TAG, "bound to a service with no backend")
                return
            }
            bound = true
            backend = b
            b.player.addListener(playbackListener)
            main.post {
                for (o in observers) runCatching { o.onBackendChanged(b) }
                publish(b.player.state())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The process is still alive (or the service is restarting); the
            // player object is gone, so its listener registration went with it.
            bound = false
            binding = false
            backend = null
            stopTicking()
            main.post {
                for (o in observers) runCatching { o.onBackendChanged(null) }
            }
        }
    }

    /** Last snapshot, so a screen switching in has something to draw at once. */
    @Volatile
    private var lastState: PlaybackState = PlaybackState()

    fun state(): PlaybackState = lastState

    // -- position ticking ------------------------------------------------------
    //
    // §13/§14: the seek bar and the elapsed counter must advance while a track
    // plays. Nothing used to produce a `PlaybackState` between *events*, so a
    // screen sat on the position it was given when playback started until the
    // track changed — a bar frozen at 0:00 for four minutes, which reads as a
    // broken player.
    //
    // Three places could have owned this and only one is right:
    //
    // - `LocalMusicPlayer` could call `notifyState()` in its checkpoint ticker.
    //   That ticker runs every ten seconds (its job is the resume point) and
    //   its notifications fan out to the *service* as well, which rebuilds the
    //   MediaSession and the notification on every tick. Ten notification
    //   rebuilds a minute is real work on this SoC for a number nobody is
    //   reading in the notification shade.
    // - The screens could each run their own ticker, which is three tickers
    //   drifting apart and three chances to leak a `Handler` callback.
    // - Here, which is the seam where a UI-facing snapshot already exists.
    //
    // Publishing to [observers] and **not** to the service is deliberate: the
    // session's position is already correct because both session builders put
    // `st.positionMs` in the extras and the platform extrapolates from the
    // update time. Only the visible UI needs a heartbeat.

    /** True while [uiTicker] is queued, so it cannot be posted twice. */
    private var ticking = false

    private val uiTicker = object : Runnable {
        override fun run() {
            ticking = false
            val b = backend
            // Stop when nothing is playing: a paused player's position does not
            // change, and a ticker left running on a paused screen would wake
            // the CPU once a second to redraw the same digits.
            if (b == null || !lastState.isPlaying) return
            publish(b.player.state())
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        if (ticking) return
        ticking = true
        main.postDelayed(uiTicker, POSITION_TICK_MS)
    }

    private fun stopTicking() {
        ticking = false
        main.removeCallbacks(uiTicker)
    }

    /** Attaches an observer, starting the service and binding on first use.
     *
     * Always fail-soft: a firmware that refuses the service start leaves the
     * launcher usable with an empty music card, which is what the spec means by
     * graceful degradation.
     */
    fun attach(observer: Observer) {
        if (!observers.contains(observer)) observers.add(observer)
        refCount++
        observer.onPlaybackState(lastState)
        val b = backend
        if (b != null) {
            observer.onBackendChanged(b)
            scheduleTick()
            return
        }
        if (binding) return

        binding = true
        // startService first: the service is what survives the unbind, and
        // starting it before binding avoids an onServiceConnected for a service
        // that is about to be stopped and restarted.
        MusicPlaybackService.start(appContext)
        val intent = Intent(appContext, MusicPlaybackService::class.java)
        val ok = runCatching { appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!ok) {
            binding = false
            Log.w(TAG, "bindService refused; music UI will stay empty")
        }
    }

    fun detach(observer: Observer) {
        observers.remove(observer)
        if (refCount > 0) refCount--
        if (refCount > 0) return
        // The last screen has gone: nothing is watching the position, and the
        // service's own checkpoint ticker already covers what must persist.
        stopTicking()
        unbind()
    }

    private fun unbind() {
        backend?.player?.removeListener(playbackListener)
        if (bound || binding) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        binding = false
        backend = null
    }

    private fun publish(state: PlaybackState) {
        lastState = state
        // A transition into or out of playing starts or stops the heartbeat.
        // Done here rather than in the observer callback so a track that starts
        // from any screen — a voice command, the notification, a queue tap —
        // begins ticking without that screen having to know it must.
        if (state.isPlaying) scheduleTick() else stopTicking()
        main.post {
            for (o in observers) runCatching { o.onPlaybackState(state) }
        }
    }

    // -- transport -----------------------------------------------------------
    //
    // These go through the running service's intents rather than the bound
    // player, so a tap works even in the window before the binding completes
    // (which is the state the very first screen after boot is in).

    fun play() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_PLAY)
    fun pause() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_PAUSE)
    fun toggle() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_TOGGLE)
    fun next() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_NEXT)
    fun previous() = MusicPlaybackService.send(appContext, MusicPlaybackService.ACTION_PREV)

    /** Seek needs the bound player: the service has no seek intent because a
     *  position in an `Intent` would be a second source of truth. */
    fun seekTo(positionMs: Long) {
        backend?.player?.seekTo(positionMs)
    }

    fun setShuffle(enabled: Boolean) {
        backend?.player?.setShuffle(enabled)
    }

    fun setRepeatMode(mode: com.ts8.core.music.RepeatMode) {
        backend?.player?.setRepeatMode(mode)
    }

    fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean = true) {
        backend?.player?.setQueue(tracks, startIndex, autoPlay)
    }

    companion object {
        private const val TAG = "Ts8MusicRuntime"

        /**
         * 500 ms, not 1000: a seek-bar thumb that moves in one-second steps on
         * a 4:00 track is visibly stuttering, and half a second is roughly the
         * shortest interval a driver reads as smooth without the redraw cost
         * of a 60 Hz animation — which §17 forbids anyway.
         */
        private const val POSITION_TICK_MS = 500L

        /**
         * One runtime per process. The Application holds it so that two
         * Activities attaching at the same moment (Home resumes while the
         * music screen finishes its own `onStart`) share one binding.
         */
        @Volatile
        private var instance: MusicRuntime? = null

        fun get(context: Context): MusicRuntime =
            instance ?: synchronized(this) {
                instance ?: MusicRuntime(context.applicationContext).also { instance = it }
            }
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/OnlineMusicRuntime.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.StreamResolution
import com.ts8.core.music.StreamResolver
import com.ts8.core.music.Track
import com.ts8.core.music.online.BrowseCategory
import com.ts8.core.music.online.NavidromeProvider
import com.ts8.core.music.online.OnlineMusicGateway
import com.ts8.core.music.online.OnlineTrack
import com.ts8.core.music.online.PlayableSource
import com.ts8.core.music.online.PodcastRssProvider
import com.ts8.core.music.online.ProviderFailureCode
import com.ts8.core.music.online.ProviderKind
import com.ts8.core.music.online.ProviderResult
import com.ts8.core.music.online.RadioBrowserProvider
import com.ts8.core.music.online.SearchAggregate
import com.ts8.launcher.di.AppGraph
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's front end to online music (§12–13).
 *
 * **What this class is for.** [OnlineMusicGateway] is deliberately synchronous
 * and knows nothing about Android: every provider call blocks, which is correct
 * for a background pool and fatal for a search box. This class is the single
 * place in the app that (a) builds the gateway from settings, (b) moves its
 * calls off the main thread and posts the answer back, and (c) implements
 * [StreamResolver] so the local player can open a stream without the local
 * engine having to depend on any online provider.
 *
 * **Why the gateway is rebuilt on every settings change.** A driver who has
 * just typed a server address expects the next search to use it, not the next
 * launch. Providers are cheap objects (a base URL and a circuit breaker), so
 * rebuilding costs nothing; caching one across a settings change is how a
 * screen ends up asking a host the driver has already replaced. The generation
 * counter below drops an in-flight answer that was produced against the old
 * configuration, so a slow DNS lookup cannot render the previous server's rows
 * under the new one's name.
 *
 * **Why `BLOCKED_EXTERNAL_CONFIG` is a first-class result here.** §13 is
 * explicit: an unconfigured provider must be reported as unconfigured, never as
 * "no results". [status] carries the gateway's own [OnlineMusicGateway.blockedConfig]
 * sentences verbatim, so the screen has nothing to invent.
 *
 * **What is never here.** No caching of stream URLs, no downloading of audio,
 * no extraction from a service without a public playback API. A resolved URL is
 * handed to `MediaPlayer` once and dropped; the gateway re-resolves on the next
 * play, which is what keeps an expired token from producing a stream that dies
 * three seconds in.
 *
 * **When online is off.** [AppSettings.onlineEnabled] `false` makes [search]
 * return an empty aggregate and [status] an empty list, and the player's
 * resolver refuses — local music is completely unaffected, which is §13's
 * "provider lỗi không ảnh hưởng local music" taken one step further.
 */
class OnlineMusicRuntime(
    context: Context,
    private val settings: AppSettings,
    private val graph: AppGraph
) {

    /** One search's worth of results plus what each source said about it. */
    data class Snapshot(
        /** The query or category this answers, for the stale-answer check. */
        val ticket: Int,
        /** Merged results, in the brief's provider order. */
        val tracks: List<OnlineTrack>,
        /** One line per provider: how many it returned, or why it could not. */
        val lines: List<ProviderLine>,
        /** Set only when nothing worked; Vietnamese and shown as-is. */
        val failure: String?
    ) {
        val isTotalFailure: Boolean get() = tracks.isEmpty() && failure != null
    }

    /** A provider's contribution, as the search screen draws it. */
    data class ProviderLine(
        val providerId: String,
        val displayName: String,
        val kind: ProviderKind,
        /** `null` when the provider answered; the Vietnamese reason when it did not. */
        val failure: String?,
        /** True when the provider needs settings the driver has not entered. */
        val blockedConfig: Boolean,
        val resultCount: Int
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = graph.io()

    /** Bumped on every request so a late answer cannot paint over a new query. */
    private val ticket = AtomicInteger(0)

    /**
     * The gateway, rebuilt when the configuration it was built from changes.
     *
     * Keyed by [configKey] rather than invalidated by a callback: settings are
     * a `SharedPreferences` file other code writes to directly, so the reliable
     * moment to notice a change is the moment the gateway is asked for.
     */
    @Volatile
    private var gateway: OnlineMusicGateway? = null

    @Volatile
    private var gatewayKey: String = ""

    private val lock = Any()

    // -- gateway -----------------------------------------------------------

    /** The current gateway, or null when online music is switched off. */
    fun gateway(): OnlineMusicGateway? {
        if (!settings.onlineEnabled) return null
        val key = configKey()
        gateway?.let { if (key == gatewayKey) return it }
        synchronized(lock) {
            gateway?.let { if (key == gatewayKey) return it }
            val built = build()
            gateway = built
            gatewayKey = key
            return built
        }
    }

    /**
     * Drops the built gateway so the next call names a freshly built one.
     *
     * Called by the settings screen after a save. It exists because the
     * password is not in [configKey] — see there — and a provider captures its
     * credentials at construction, so a corrected password would otherwise
     * keep failing until the process restarted. Invalidating is cheap: the
     * providers are a base URL, a circuit breaker and a feed address.
     */
    fun invalidate() {
        synchronized(lock) {
            gateway = null
            gatewayKey = ""
        }
    }

    /**
     * Everything the providers were built from, as one string.
     *
     * The password is deliberately **not** part of the key. It is not needed to
     * decide whether the provider set changed (the username and the host are),
     * and a key built from it would put a credential in a field that exists to
     * be compared. A changed password therefore does not change this key — see
     * [invalidate], which the settings screen calls after it saves, because a
     * provider holds its password from construction and cannot see a later
     * edit on its own.
     */
    private fun configKey(): String = buildString {
        append(settings.onlineServerUrl)
        append('|')
        append(settings.onlineUser)
        append('|')
        append(settings.onlineServerKind)
        append('|')
        append(settings.onlineFeedUrl)
    }

    /**
     * Builds the provider list in the order §12 fixes.
     *
     * Radio first: it is the only provider that needs no configuration at all,
     * so it is the one guaranteed to return something on a unit whose driver
     * has not set anything up. Podcast and Navidrome follow because both need
     * the driver to have entered an address — and when they have not, they
     * answer `BLOCKED_EXTERNAL_CONFIG` and the screen says so.
     *
     * The Subsonic provider is **always** constructed, even with an empty base
     * URL: that is what makes `blockedConfig()` able to name it. Omitting it
     * when unconfigured would produce the exact failure §13 forbids — an empty
     * result that reads as "no such song".
     */
    private fun build(): OnlineMusicGateway {
        val providers = ArrayList<com.ts8.core.music.online.OnlineMusicProvider>(3)
        providers.add(RadioBrowserProvider())

        val feed = settings.onlineFeedUrl.takeIf { it.isNotBlank() }
        providers.add(PodcastRssProvider(feedUrl = feed))

        providers.add(
            NavidromeProvider(
                baseUrl = settings.onlineServerUrl.takeIf { it.isNotBlank() },
                username = settings.onlineUser.takeIf { it.isNotBlank() },
                password = settings.onlineToken.takeIf { it.isNotBlank() },
                kind = serverKind()
            )
        )
        return OnlineMusicGateway(providers, clock = { SystemClock.elapsedRealtime() })
    }
    /**
     * The stored server family, or Navidrome when the stored value is unknown.
     *
     * Falling back rather than throwing: a preferences file written by a
     * build that had a provider this one does not is a routine downgrade, and
     * the Subsonic API is identical enough that the wrong label costs less than
     * an online screen that refuses to open.
     */
    private fun serverKind(): ProviderKind {
        val stored = settings.onlineServerKind
        return runCatching { ProviderKind.valueOf(stored) }
            .getOrDefault(ProviderKind.NAVIDROME)
            .let { if (it == ProviderKind.NAVIDROME || it == ProviderKind.JELLYFIN) it else ProviderKind.NAVIDROME }
    }

    // -- search ------------------------------------------------------------

    /**
     * Searches every configured provider, off the main thread.
     *
     * The callback always runs on the main thread and is always called exactly
     * once — including when online music is off, where it receives an empty
     * snapshot rather than silence. A screen waiting for a callback that never
     * comes shows a spinner forever, which is indistinguishable from a hang.
     */
    fun search(
        query: String,
        limit: Int = OnlineMusicGateway.MAX_AGGREGATE_LIMIT,
        onDone: (Snapshot) -> Unit
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onDone(Snapshot(ticket.incrementAndGet(), emptyList(), emptyList(), null))
            return
        }
        dispatch(onDone) { gw -> gw.search(trimmed, limit) }
    }

    /** Browses a shelf; same contract as [search]. */
    fun browse(
        category: BrowseCategory,
        limit: Int = OnlineMusicGateway.MAX_AGGREGATE_LIMIT,
        onDone: (Snapshot) -> Unit
    ) {
        dispatch(onDone) { gw -> gw.browse(category, limit) }
    }

    /**
     * Runs one aggregate call and folds it into a [Snapshot].
     *
     * The ticket check is why the counter is shared: the driver types `c`,
     * `ca`, `cau` and three searches are in flight over a phone hotspot.
     * Without it the slowest answer wins, which for a search box means the list
     * flickers back to a query the driver already deleted.
     */
    private fun dispatch(
        onDone: (Snapshot) -> Unit,
        call: (OnlineMusicGateway) -> SearchAggregate
    ) {
        val id = ticket.incrementAndGet()
        val gw = gateway()
        if (gw == null) {
            // Online is off. Answer, do not stay silent: an empty list plus a
            // sentence is the truth, and a never-called callback is a spinner.
            onDone(
                Snapshot(
                    id,
                    emptyList(),
                    emptyList(),
                    "Nhạc trực tuyến đang tắt trong Cài đặt"
                )
            )
            return
        }
        executor.execute {
            val snapshot = runCatching { toSnapshot(id, call(gw)) }
                .getOrElse { failure ->
                    Log.w(TAG, "online call failed: ${failure.message}")
                    Snapshot(id, emptyList(), emptyList(), "Không hỏi được nguồn trực tuyến")
                }
            main.post {
                // A newer query has been issued; this answer describes a list
                // the driver is no longer looking at.
                if (id != ticket.get()) return@post
                onDone(snapshot)
            }
        }
    }

    private fun toSnapshot(id: Int, aggregate: SearchAggregate): Snapshot {
        val lines = ArrayList<ProviderLine>(aggregate.perProvider.size)
        for ((providerId, result) in aggregate.perProvider) {
            when (result) {
                is ProviderResult.Ok -> lines.add(
                    ProviderLine(
                        providerId = providerId,
                        displayName = providerLabel(providerId),
                        kind = providerKind(providerId),
                        failure = null,
                        blockedConfig = false,
                        resultCount = result.value.size
                    )
                )
                // The failure's own message is used rather than a sentence
                // invented here: the provider knows whether it is a timeout, a
                // 429, a bad token or a missing address, and §13's
                // BLOCKED_EXTERNAL_CONFIG is exactly the code whose message
                // names the setting to enter.
                is ProviderResult.Failure -> lines.add(
                    ProviderLine(
                        providerId = providerId,
                        displayName = providerLabel(providerId),
                        kind = providerKind(providerId),
                        failure = result.message,
                        blockedConfig = result.code == ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG,
                        resultCount = 0
                    )
                )
            }
        }
        return Snapshot(
            ticket = id,
            tracks = aggregate.tracks,
            lines = lines,
            failure = aggregate.failure?.message
        )
    }

    private fun providerKind(providerId: String): ProviderKind = when (providerId) {
        "radio-browser" -> ProviderKind.INTERNET_RADIO
        "podcast" -> ProviderKind.PODCAST_RSS
        "subsonic" -> if (serverKind() == ProviderKind.JELLYFIN) ProviderKind.JELLYFIN
        else ProviderKind.NAVIDROME
        else -> ProviderKind.OTHER
    }

    /**
     * A human label for a provider id.
     *
     * The gateway exposes ids, not display names, on [SearchAggregate]'s map —
     * a deliberate choice on its side (the map is keyed by the stable id so a
     * saved queue can refer to it). The three ids below are the ones this app
     * constructs; an unknown id falls back to itself rather than to a
     * fabricated sentence.
     */
    private fun providerLabel(providerId: String): String = when (providerId) {
        "radio-browser" -> "Đài phát thanh"
        "podcast" -> "Podcast"
        "subsonic" -> if (serverKind() == ProviderKind.JELLYFIN) "Jellyfin" else "Navidrome"
        else -> providerId
    }

    // -- playback ----------------------------------------------------------

    /**
     * The player's [StreamResolver].
     *
     * Installed on the local player by [com.ts8.launcher.service.MusicPlaybackService]
     * so an online track in the queue opens like any other. Called off the main
     * thread by the player, blocks on the provider, and answers with a reason
     * rather than throwing.
     *
     * **Why the queue holds a resolved URL and not a provider id.** The player
     * opens a track with a single string, and re-resolving on every `next()`
     * would be one network request per track on a connection that may be a
     * phone hotspot. `toTrack` below resolves once, when the row is tapped, and
     * the URL is what enters the queue — the same lifetime the gateway's own
     * docs prescribe, because nothing caches it beyond that one open.
     */
    fun streamResolver(): StreamResolver = StreamResolver { track ->
        // Already a URL: nothing to resolve. This is the normal path, because
        // `toTrack` resolved before queueing.
        if (track.source.startsWith("http", ignoreCase = true)) {
            return@StreamResolver StreamResolution.Playable(
                url = track.source,
                mimeType = track.mimeType,
                durationMs = track.durationMs,
                isLive = track.durationMs <= 0L
            )
        }
        val providerId = track.providerId
            ?: return@StreamResolver StreamResolution.Unavailable("Bài này không còn nguồn phát", false)
        val source = resolveSource(providerId, track.source)
        when (source) {
            is ProviderResult.Ok -> StreamResolution.Playable(
                url = source.value.url,
                mimeType = source.value.mimeType,
                durationMs = track.durationMs,
                isLive = source.value.isStreaming
            )
            is ProviderResult.Failure -> StreamResolution.Unavailable(
                source.message,
                retryable = source.retryable
            )
        }
    }

    /**
     * Resolves a stream URL for a track, synchronously.
     *
     * Called from [streamResolver] (off the main thread, by the player) and
     * from [toTrack] (on the IO pool). Never from a UI callback directly.
     */
    fun resolveSource(providerId: String, trackId: String): ProviderResult<PlayableSource> {
        val gw = gateway()
            ?: return ProviderResult.Failure(
                ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG,
                "Nhạc trực tuyến đang tắt trong Cài đặt",
                retryable = false
            )
        return gw.freshSource(providerId, trackId)
    }

    /**
     * Turns a search result into a queueable [Track] with a real URL.
     *
     * **Why resolution happens here, at tap time.** A search result carries
     * only a hint that a URL exists; the gateway's contract is that a real URL
     * must be re-issued before playback, because a station can move between the
     * search and the tap. Resolving at the moment of the tap is the earliest
     * point at which the answer is still true, and the latest point before the
     * player needs it.
     *
     * Returns null when no URL could be produced; the caller shows the reason
     * it already has from the same failure.
     */
    fun toTrack(online: OnlineTrack): Track? {
        val source = resolveSource(online.providerId, online.id)
        val url = (source as? ProviderResult.Ok)?.value?.url
        if (url.isNullOrBlank()) return null
        return toTrack(online, url)
    }

    /**
     * The same conversion with a URL already in hand.
     *
     * **Why the id is a hash, and not `OnlineTrack.id`.** A `Track.id` is a
     * `Long` in the library's id space, and an online item is not in the
     * library. A hash that sets a bit no library id can carry keeps the two
     * spaces disjoint, exactly as `TrackIds` does for USB. Two providers may
     * also issue the same id string — the provider id is part of the hash, so
     * they cannot collide.
     */
    fun toTrack(online: OnlineTrack, url: String): Track = Track(
        id = onlineTrackId(online.providerId, online.id),
        source = url,
        title = online.title,
        artist = online.artist,
        album = online.album,
        albumId = null,
        artistId = null,
        durationMs = online.durationMs,
        folder = null,
        trackNumber = 0,
        year = 0,
        mimeType = null,
        sizeBytes = 0,
        modifiedSeconds = 0,
        sourceKind = Track.SourceKind.ONLINE,
        providerId = online.providerId
    )

    // -- status ------------------------------------------------------------

    /**
     * One line per provider for the search screen's header, off the main thread.
     *
     * Uses the gateway's throttled [OnlineMusicGateway.status], so opening the
     * screen repeatedly does not probe the network each time. The unconfigured
     * sentences are appended verbatim from [OnlineMusicGateway.blockedConfig]
     * — §13's `BLOCKED_EXTERNAL_CONFIG` requirement, unmet by inventing a
     * message here.
     */
    fun status(onDone: (List<String>) -> Unit) {
        val gw = gateway()
        if (gw == null) {
            main.post { onDone(emptyList()) }
            return
        }
        executor.execute {
            val lines = runCatching {
                val blocked = gw.blockedConfig()
                if (blocked.isEmpty()) {
                    gw.status().map { status ->
                        val state = if (status.health.available) "sẵn sàng"
                        else status.health.detail ?: "không phản hồi"
                        "${status.displayName}: $state"
                    }
                } else {
                    blocked
                }
            }.getOrElse { emptyList() }
            main.post { onDone(lines) }
        }
    }

    /** True when nothing is configured, so the screen can say it once up front. */
    fun hasAnyProvider(): Boolean = gateway() != null

    companion object {
        private const val TAG = "Ts8OnlineMusic"

        /**
         * Bit marking an id as an online track rather than a library row.
         *
         * Bit 61, not 62: 62 is what `TrackIds` uses for a USB path hash, and
         * reusing it would let an online id and a USB id collide — the two
         * spaces must stay disjoint for the same reason described on
         * `TrackIds.forUsbPath`.
         */
        private const val ONLINE_BIT = 1L shl 61

        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 0x100000001b3L

        /**
         * A stable `Long` id for an online track.
         *
         * Deterministic on `(providerId, trackId)`, so the same station gets the
         * same id across searches — which is what lets the queue highlight it
         * and what keeps two providers' identical id strings apart.
         */
        fun onlineTrackId(providerId: String, trackId: String): Long {
            var hash = FNV_OFFSET
            for (ch in "$providerId\u0000$trackId") {
                hash = hash xor (ch.code.toLong() and 0xFFFF)
                hash *= FNV_PRIME
            }
            return (hash and (ONLINE_BIT - 1)) or ONLINE_BIT
        }
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ts8.core.location.LocationFix
import com.ts8.core.music.AudioRoute
import com.ts8.core.navigation.NavigationState
import com.ts8.core.parking.LocationStability
import com.ts8.core.parking.ParkingDecision
import com.ts8.core.parking.ParkingReason
import com.ts8.core.parking.ParkingSignals
import com.ts8.core.parking.ParkingState
import com.ts8.launcher.di.AppGraph
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The only code that knows how this launcher's signals become a
 * [ParkingSignals] snapshot (§18).
 *
 * **Why this exists.** [com.ts8.core.parking.ParkingStateResolver] is a pure
 * function on purpose: it takes one snapshot and returns one decision, so it
 * can be run against synthetic tracks with no head unit. Something has to
 * assemble that snapshot from six sources that never agree on a rate — the
 * location pipeline, a stability window, the navigation state machine, the
 * audio bridge's call detection, the driver's button, and a clock — and
 * something has to do it once for the whole process rather than once per
 * screen. That is this class.
 *
 * **Why a one-second tick instead of resolving on every GPS fix.** Dwell is
 * measured in time, and the resolver only learns that time passed when it is
 * called. Resolving only on a fix would stop the two-minute window dead the
 * moment the receiver loses sky view — which is exactly the car-park case this
 * feature exists for — and would also never notice a driver who pressed the
 * button while the fix stream was quiet. The tick is the heartbeat; fixes only
 * move the stability window forward.
 *
 * **Why a stale fix is reported as `null` speed, not as its last value.** A
 * receiver that went quiet ten seconds ago is not measuring a stationary car;
 * it is not measuring anything. Reporting the last speed would let a car that
 * drove into a tunnel "stay" at 0 km/h and accumulate dwell against a
 * measurement nobody made. [ParkingSignals] draws that distinction explicitly
 * and this class is where it has to be honoured.
 *
 * **The reverse camera stays `false` and that is a limitation, not a choice.**
 * On this firmware the reverse camera is drawn by the MCU, never by an Android
 * Activity, so a third-party app has no state to read. §18 forbids reverse
 * engineering the new MCU contract in this campaign, so the signal is passed as
 * absent-by-false with the consequence documented in
 * `docs/KNOWN_LIMITATIONS.md`: a driver who reverses into a space with the
 * auto-enter setting on can see the parking screen appear over the camera
 * picture after the dwell elapses. The navigation blocker is the one that still
 * protects the common case.
 */
class ParkingRuntime(
    private val context: Context,
    private val settings: AppSettings,
    private val graph: AppGraph
) {

    /** Told about every decision that changed. Delivered on the main thread. */
    interface Listener {
        fun onParkingDecision(decision: ParkingDecision)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val stability = LocationStability()

    /** Screens that asked for parking detection; the tick runs while any is up. */
    private var refCount = 0

    /**
     * The last decision, seeded from storage so a restart lands where it left
     * off rather than on a blank map.
     *
     * A restored decision is used for **display only**: [autoOpened] is seeded
     * from it so a reboot cannot push the parking screen forward on evidence
     * that was never re-measured this boot. The resolver will confirm or reject
     * it on the first tick, and only a fresh confirmation may open the screen.
     */
    private var last: ParkingDecision = runCatching { graph.parkingStore.restore() }
        .getOrDefault(ParkingDecision.UNKNOWN)

    private var autoOpened: Boolean = last.showsParkingScreen

    /** Set by [requestEnter]/[requestExit]; consumed by the next snapshot. */
    private var pendingManual: Boolean? = null

    private val tick = object : Runnable {
        override fun run() {
            if (refCount == 0) return
            evaluate()
            main.postDelayed(this, TICK_MS)
        }
    }

    /**
     * The GPS subscription.
     *
     * Its only job is to feed the stability window. It deliberately does not
     * call [evaluate]: the tick already runs at this rate, and resolving from
     * two sources would publish each decision twice and race the manual flag.
     */
    private val locationListener = object : com.ts8.launcher.runtime.LocationRuntime.Listener {
        override fun onFix(fix: LocationFix) {
            stability.add(fix.point, fix.elapsedRealtimeMs)
        }
    }

    // -- public surface ----------------------------------------------------

    /** The current decision, or the restored one before the first tick. */
    fun decision(): ParkingDecision = last

    /** True while the resolver holds the car parked. */
    fun isParked(): Boolean = last.state == ParkingState.PARKED

    /**
     * The driver pressed "vào chế độ đỗ xe".
     *
     * Returns nothing and applies on the next tick (within a second). It is not
     * applied synchronously because the resolver's dwell bookkeeping expects to
     * see snapshots in time order, and a snapshot stamped by a button press and
     * another stamped by the tick could arrive out of order.
     */
    fun requestEnter() {
        pendingManual = true
        autoOpened = false
        main.removeCallbacks(tick)
        if (refCount > 0) main.post(tick) else evaluate()
    }

    /**
     * The driver pressed "thoát chế độ đỗ xe".
     *
     * The resolver latches this until the car has actually driven away, which
     * is what stops the heuristic from re-entering the screen the driver just
     * dismissed.
     */
    fun requestExit() {
        pendingManual = false
        main.removeCallbacks(tick)
        if (refCount > 0) main.post(tick) else evaluate()
    }

    /**
     * Starts the tick and joins the GPS stream.
     *
     * Reference-counted exactly like [LocationRuntime], so a screen destroyed
     * without releasing cannot pin the ticker and the receiver forever.
     */
    fun acquire(listener: Listener?) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener)
        refCount++
        if (refCount == 1) {
            graph.location.acquire(locationListener)
            main.post(tick)
        }
        listener?.onParkingDecision(last)
    }

    fun release(listener: Listener?) {
        if (listener != null) listeners.remove(listener)
        if (refCount > 0) refCount--
        if (refCount == 0) {
            main.removeCallbacks(tick)
            graph.location.release(locationListener)
            // The window is time-bounded, and time spent off the launcher is
            // not evidence: keeping it would credit the car with dwell while
            // the process was on the music screen with no fix arriving.
            stability.reset()
        }
    }

    // -- the snapshot ------------------------------------------------------

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val manual = pendingManual
        pendingManual = null

        val fix = graph.location.lastFix
        // The pipeline's own timestamp, not `now`: a fix that arrived a second
        // ago is a measurement taken a second ago.
        val fresh = fix != null && now - fix.elapsedRealtimeMs <= FIX_FRESH_MS

        val signals = ParkingSignals(
            // `LocationFix.speedMps` is a filtered Float that is never negative;
            // `ParkingSignals` wants a Double because its absence has to be
            // representable, which the Float cannot do.
            speedMps = if (fresh) fix!!.speedMps.toDouble() else null,
            speedAccuracyMeters = if (fresh) fix!!.accuracyMeters else null,
            locationStable = if (fresh) stability.verdict(now) else null,
            navigationActive = graph.navigationStateIfBuilt() == NavigationState.NAVIGATING,
            // `currentRoute` reads `AudioManager`'s mode, which is how API 23
            // reports an in-progress or ringing call without a telephony
            // permission. It is a cheap field read, safe on this tick.
            callActive = graph.audioBridge.currentRoute() == AudioRoute.CALL,
            reverseCameraActive = false,
            explicitManualRequest = manual,
            elapsedRealtimeMs = now
        )

        val resolved = runCatching {
            graph.parkingResolver.resolve(signals, settings.parkingAutoEnter)
        }.getOrElse { failure ->
            // The resolver is pure and should not throw; if it ever does, the
            // honest answer is "no answer" rather than a stale screen.
            Log.w(TAG, "parking resolve failed: ${failure.message}")
            ParkingDecision.UNKNOWN
        }

        val changed = resolved.state != last.state ||
            resolved.confidence != last.confidence ||
            resolved.reason != last.reason ||
            resolved.evidence != last.evidence
        last = resolved

        if (changed) {
            persist(resolved)
            for (l in listeners.toTypedArray()) runCatching { l.onParkingDecision(resolved) }
        }
        maybeOpenParkingScreen(resolved)
    }

    /**
     * Writes the decision and keeps the auto-open latch honest.
     *
     * Persisted on change rather than on every tick: the row is a disk write,
     * and a car standing still for ten minutes produces six hundred identical
     * decisions.
     */
    private fun persist(decision: ParkingDecision) {
        runCatching { graph.parkingStore.save(decision) }
    }

    /**
     * Opens the parking screen once per parked episode, and only on evidence.
     *
     * Three conditions, all required and all already checked by the resolver:
     * [ParkingDecision.showsParkingScreen] means PARKED at HIGH confidence,
     * which the resolver only grants when the driver's auto-enter setting is on;
     * and a [ParkingReason.MANUAL] decision is excluded because the manual
     * button already opened the screen itself. The latch is what keeps the
     * driver who taps "chạm để quay lại" from being thrown back into the screen
     * a second later while the car is still standing.
     */
    private fun maybeOpenParkingScreen(decision: ParkingDecision) {
        if (decision.state != ParkingState.PARKED) {
            autoOpened = false
            return
        }
        if (!decision.showsParkingScreen || decision.reason == ParkingReason.MANUAL) return
        if (autoOpened) return
        autoOpened = true
        val opened = runCatching {
            context.startActivity(
                Intent(context, com.ts8.launcher.ui.ParkingModeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        if (opened.isFailure) {
            Log.w(TAG, "could not open parking screen: ${opened.exceptionOrNull()?.message}")
        }
    }

    companion object {
        private const val TAG = "Ts8ParkingRuntime"

        /**
         * The tick, in milliseconds.
         *
         * One hertz matches the receiver and the resolver's own dwell
         * granularity; the parking decision is never needed faster than that,
         * and a faster tick would spend the main thread's frame budget on a
         * screen that is not moving.
         */
        private const val TICK_MS = 1_000L

        /**
         * How old a fix may be and still describe the car now.
         *
         * Five seconds is five missed samples at 1 Hz — long enough to ride out
         * a bad patch of sky, short enough that a car which drove out of a car
         * park and lost its receiver is not reported as "standing still".
         */
        private const val FIX_FRESH_MS = 5_000L
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/PrefsKeyValueStore.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.content.SharedPreferences

/**
 * `SharedPreferences` as the two platform-free get/put pairs the core modules
 * declare.
 *
 * **Why one class implements both interfaces.** `com.ts8.core.voice.KeyValueStore`
 * and `com.ts8.core.parking.KeyValueStore` are declared in two modules that must
 * not depend on each other, and both are exactly `get(key): String?` plus
 * `put(key, value)`. Their signatures are identical, so a single adapter can
 * satisfy both and the app does not end up with two near-identical private
 * preference files with two different corruption behaviours.
 *
 * **Why a separate prefs file from `AppSettings`.** The settings file is what a
 * driver configures; this one is what the app remembers about its own last
 * action (`voice.last_state`, `parking_last_decision_v1`). Keeping the last
 * voice state out of the settings file means a driver who clears a setting
 * cannot accidentally clear the record that explains what the screen was doing.
 *
 * **Why writes are `commit()` and not `apply()`.** Both stores are written at
 * the end of an interaction — exactly the moment before the head unit may lose
 * power with the ignition. `apply()` is asynchronous and a process killed
 * before its background write lands leaves the old row, which is the stuck
 * overlay `VoiceStateStore` exists to prevent. These writes happen a handful of
 * times per interaction, never per frame, so the synchronous cost is a few
 * hundred microseconds on a path that already stops the microphone.
 *
 * **Why failures are swallowed.** A preferences write can fail on a full or
 * read-only data partition. There is nothing the driver could do about it and
 * no screen that should stop working because of it: the store's contract is
 * "a failed write degrades to no state", and a crash here would be worse than
 * the missing row.
 */
class PrefsKeyValueStore(
    context: Context,
    fileName: String = PREFS
) : com.ts8.core.voice.KeyValueStore,
    com.ts8.core.parking.KeyValueStore,
    com.ts8.launcher.runtime.StateStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun get(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    override fun put(key: String, value: String) {
        runCatching { prefs.edit().putString(key, value).commit() }
    }

    companion object {
        /**
         * Not `ts8_settings`: see the note above on why the two are separate.
         */
        const val PREFS = "ts8_runtime_state"
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/SavedPlaces.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import com.ts8.core.map.GeoPoint
import com.ts8.core.search.Place
import org.json.JSONArray
import org.json.JSONObject

/**
 * Places the driver saved from the search screen.
 *
 * **Why this exists at all.** The design puts a "Lưu" button next to
 * "Lộ trình". A button that does nothing, or that shows a toast saying
 * "đã lưu" while writing nowhere, is exactly the fabricated success the spec
 * forbids. So the button writes to a real store, and the store is read back:
 * an empty query on the search screen lists what was saved.
 *
 * **Why SharedPreferences rather than SQLite.** This holds a handful of
 * records — a home address, work, a favourite car park. A table, a cursor and
 * a schema version for that is more code than the feature. The file is small
 * enough to parse on the main thread, which is what lets the search screen show
 * the list without a spinner.
 *
 * **Coordinates are stored as doubles, not text.** Round-tripping through a
 * formatted string loses precision at the metre level, which is the difference
 * between routing to the bridge ramp and routing to the river.
 */
class SavedPlaces(private val context: Context) {

    /** A saved place, reduced to what the search list renders. */
    data class Saved(
        val name: String,
        val address: String?,
        val point: GeoPoint,
        val savedAtMs: Long
    ) {
        /** Stable identity for de-duplication: same name at the same spot. */
        val key: String get() = "$name|${point.lat}|${point.lon}"
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved places, newest first. Empty when nothing has been saved. */
    fun all(): List<Saved> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("n").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lat = o.optDouble("la", Double.NaN)
                val lon = o.optDouble("lo", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                val point = GeoPoint(lat, lon)
                if (!point.isValid()) return@mapNotNull null
                Saved(
                    name = name,
                    address = o.optString("a").takeIf { it.isNotBlank() },
                    point = point,
                    savedAtMs = o.optLong("t", 0L)
                )
            }
        }.getOrDefault(emptyList())
        return parsed.sortedByDescending { it.savedAtMs }
    }

    fun isSaved(place: Place): Boolean = contains(place.name, place.point)

    /**
     * True when a record with this name at this spot is stored.
     *
     * Name-and-point rather than a search-result id: the saved list is built
     * from records that have already been detached from the search index, and
     * the same bridge found twice from two queries must toggle off, not save
     * twice.
     */
    fun contains(name: String, point: GeoPoint): Boolean =
        all().any { it.name == name && it.point == point }

    /**
     * Saves a name at a point, or removes it if it is already there.
     *
     * Toggling rather than always adding is what makes the button's label
     * ("Lưu" / "Đã lưu") honest without a second control.
     *
     * @return true when the place is now saved.
     */
    fun toggle(
        name: String,
        address: String?,
        point: GeoPoint,
        atMs: Long = System.currentTimeMillis()
    ): Boolean {
        val current = all()
        val existing = current.firstOrNull { it.name == name && it.point == point }
        val next = if (existing != null) {
            current.filterNot { it.key == existing.key }
        } else {
            val entry = Saved(
                name = name,
                address = address,
                point = point,
                savedAtMs = atMs
            )
            // Capped so the JSON stays small and the list stays scrollable.
            (listOf(entry) + current).take(MAX_ENTRIES)
        }
        write(next)
        return existing == null
    }

    /** Convenience overload for a search hit. */
    fun toggle(place: Place, atMs: Long = System.currentTimeMillis()): Boolean =
        toggle(place.name, place.address, place.point, atMs)

    fun remove(saved: Saved) {
        write(all().filterNot { it.key == saved.key })
    }

    private fun write(entries: List<Saved>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject().apply {
                    put("n", e.name)
                    e.address?.let { put("a", it) }
                    put("la", e.point.lat)
                    put("lo", e.point.lon)
                    put("t", e.savedAtMs)
                }
            )
        }
        // `commit` on the main thread would be a disk write the driver waits
        // for; `apply` is queued and is what a preference write should use.
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "ts8_saved_places"
        private const val KEY = "places_v1"

        /** Enough for home, work and a handful of regular stops. */
        private const val MAX_ENTRIES = 30
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.ts8.core.voice.VoiceError

/**
 * The one class in the app that touches `android.speech.SpeechRecognizer`.
 *
 * **Why an adapter and not a controller.** §10 asks for a replaceable adapter,
 * and the reason is concrete on this unit: the TS8's vendor firmware may ship
 * no recogniser at all, may ship one that only understands English, or may ship
 * a Google app that is disabled by the ROM. All three are invisible from the
 * calling code, which only wants "the driver said something" or "this failed
 * and here is why". Keeping every platform type behind this class is what lets
 * the rest of Voice Instant be written — and reasoned about — without knowing
 * which of those units it is running on.
 *
 * **Why it does not loop.** There is no continuous recognition here and no
 * restart-on-silence. The brief forbids recording in the background, and a
 * recogniser that restarts itself is exactly that: a microphone held open by a
 * screen the driver has left. One call in, at most one result out, then the
 * recogniser is destroyed.
 *
 * **Why `EXTRA_PREFER_OFFLINE` is set.** This is a head unit: the driver may be
 * in a tunnel or a car park with no data. The extra asks the platform for its
 * on-device model first. On API 23 the flag is advisory and a unit with no
 * offline Vietnamese model simply ignores it — which is why a recogniser that
 * then fails to reach the network is reported as `SERVICE_UNAVAILABLE` rather
 * than retried forever.
 *
 * **Threading.** `SpeechRecognizer` requires every method on the main thread,
 * and delivers its callbacks on it too. The adapter asserts this by construction
 * — it is only called from the main looper — and hops nothing.
 */
class SpeechRecognizerAdapter(
    private val context: Context,
    private val languageTag: String = DEFAULT_LANGUAGE
) {

    /** Final outcomes, all on the main thread. */
    interface Callback {
        /** The recogniser began capturing audio. */
        fun onReady()

        /** Audio is ending; the transcript is being decoded. */
        fun onProcessing()

        /** One or more hypotheses, best first. Never empty. */
        fun onResult(hypotheses: List<String>)

        /** A closed failure. Never null. */
        fun onError(error: VoiceError, detail: String?)
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null

    /**
     * True between [start] and the first terminal callback.
     *
     * The platform can deliver both `onError` and `onResults` for one session on
     * some firmware; without this flag the callback would see two outcomes for
     * one utterance and the router would run a command that had already been
     * reported as failed.
     */
    private var sessionActive = false

    /** Whether this unit can recognise at all, cached after the first probe. */
    @Volatile
    private var available: Boolean? = null

    /**
     * The language this adapter will ask for, as a BCP-47 tag.
     *
     * Vietnamese on this market. It is a constructor parameter rather than a
     * constant because a fleet in another country needs a different one, and a
     * recogniser asked for the wrong language returns confident nonsense —
     * which the parser then reports as "không hiểu lệnh", blaming the driver
     * for the unit's setup.
     */
    val language: String get() = languageTag

    /**
     * True when a recogniser exists and is not on the device's disabled list.
     *
     * `SpeechRecognizer.isRecognitionAvailable` answers "is there an activity
     * that handles the intent", not "will it work when asked"; a firmware with
     * the Google app disabled answers `true` and then fails every session with
     * `ERROR_CLIENT`. That is why this is only a hint and the real verdict is
     * whatever [start] reports — the brief's `Voice service unavailable` path is
     * driven by a failed session, not by this probe.
     */
    fun isAvailable(): Boolean {
        available?.let { return it }
        val probe = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
            .getOrDefault(false)
        available = probe
        return probe
    }

    /**
     * Opens one recognition session.
     *
     * A second call while a session is running is refused rather than queued:
     * two overlapping recognisers on one microphone produce a session that
     * errors with `ERROR_RECOGNIZER_BUSY` and a transcript attributed to the
     * wrong utterance. The caller (the controller) is expected to cancel first.
     *
     * @return false when no session could be started and [Callback.onError] has
     *         already been invoked with the reason.
     */
    fun start(callback: Callback): Boolean {
        if (sessionActive) {
            callback.onError(VoiceError.COMMAND_FAILED, "đang có phiên nhận dạng khác")
            return false
        }
        if (!isAvailable()) {
            callback.onError(
                VoiceError.SERVICE_UNAVAILABLE,
                "SpeechRecognizer.isRecognitionAvailable = false"
            )
            return false
        }

        destroyRecognizer()
        this.callback = callback

        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrNull()
        if (created == null) {
            // A firmware that reports the service as available and then refuses
            // to construct it: the recogniser exists but is unusable.
            this.callback = null
            callback.onError(VoiceError.SERVICE_UNAVAILABLE, "createSpeechRecognizer = null")
            return false
        }
        created.setRecognitionListener(listener)
        recognizer = created

        val intent = runCatching { buildIntent() }.getOrElse { e ->
            destroyRecognizer()
            this.callback = null
            callback.onError(VoiceError.SERVICE_UNAVAILABLE, e.message)
            return false
        }

        sessionActive = true
        val started = runCatching { created.startListening(intent) }
        if (started.isFailure) {
            sessionActive = false
            destroyRecognizer()
            this.callback = null
            val detail = started.exceptionOrNull()?.let {
                "${it.javaClass.simpleName}: ${it.message}"
            }
            callback.onError(VoiceError.SERVICE_UNAVAILABLE, detail)
            return false
        }
        return true
    }

    /**
     * Ends the session if one is open.
     *
     * `stopListening` asks the recogniser to decode what it has; `cancel` throws
     * the audio away. Cancel is what Back wants — the driver is dismissing the
     * overlay, and a transcript arriving afterwards would execute a command they
     * just abandoned. Both are called because some Android 6 firmware ignores
     * `cancel` on a recogniser that never fired `onReady`.
     */
    fun cancel() {
        if (!sessionActive) {
            destroyRecognizer()
            return
        }
        sessionActive = false
        val r = recognizer
        runCatching { r?.cancel() }
        runCatching { r?.stopListening() }
        callback = null
        destroyRecognizer()
    }

    /**
     * Asks the recogniser to finish and decode.
     *
     * Used when the driver has stopped talking and the overlay's own timer is
     * about to expire: stopping early produces a transcript a second sooner
     * than letting the platform's end-of-speech silence do it.
     */
    fun finishListening() {
        if (!sessionActive) return
        val r = recognizer ?: return
        runCatching { r.stopListening() }
    }

    /** Releases the recogniser. Safe to call repeatedly. */
    fun release() {
        sessionActive = false
        callback = null
        destroyRecognizer()
    }

    // -- internals ----------------------------------------------------------

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            // Partial results are what let the overlay show "đang nghe…" with a
            // live hypothesis. They are *not* what gets executed: only the final
            // result reaches the router, because a partial hypothesis for
            // "dẫn đường tới Đà Nẵng" is "dẫn đường tới đà" — a different
            // destination.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Ask for a few hypotheses so the controller can prefer one that
            // parses. `MAX_RESULTS` is honoured by Google's recogniser and
            // ignored by others, which is why the controller does not depend on
            // more than one arriving.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.destroy() }
    }

    /** Delivers one terminal outcome at most, then clears the session. */
    private fun terminate(block: (Callback) -> Unit) {
        if (!sessionActive) return
        sessionActive = false
        val target = callback
        callback = null
        destroyRecognizer()
        target?.let { block(it) }
    }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionActive) callback?.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (sessionActive) callback?.onProcessing()
        }

        override fun onError(error: Int) {
            val mapped = mapError(error)
            Log.i(TAG, "recognition error $error -> $mapped")
            terminate { it.onError(mapped, "SpeechRecognizer error $error") }
        }

        override fun onResults(results: Bundle?) {
            val hypotheses = hypothesesOf(results)
            if (hypotheses.isEmpty()) {
                // A session that ends with no hypothesis is a no-match, not a
                // no-speech: the platform heard something and could not decode
                // it. Reporting NO_SPEECH here would tell the driver to speak up
                // when they already had.
                terminate { it.onError(VoiceError.NO_MATCH, "kết quả rỗng") }
                return
            }
            terminate { it.onResult(hypotheses) }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun hypothesesOf(results: Bundle?): List<String> {
        val raw = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return emptyList()
        return raw.mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_HYPOTHESES)
    }

    /**
     * Maps a platform error code onto the closed `VoiceError` set.
     *
     * **Why `ERROR_NETWORK` and `ERROR_NETWORK_TIMEOUT` are `SERVICE_UNAVAILABLE`
     * and not `TIMEOUT`.** Both mean "this unit cannot reach its recogniser",
     * which is a permanent-looking condition on a car in a tunnel. Reporting
     * them as a timeout would make the overlay say "hết thời gian chờ" and
     * invite the driver to speak again — into a service that is not there.
     * `ERROR_SPEECH_TIMEOUT` is the genuine "you said nothing" case.
     */
    private fun mapError(error: Int): VoiceError = when (error) {
        // The driver said nothing before the end-of-speech timer. The only
        // genuine "try saying it again" case.
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NO_SPEECH

        // Speech was captured and nothing decoded from it.
        SpeechRecognizer.ERROR_NO_MATCH -> VoiceError.NO_MATCH

        // No recogniser bound, or the app disabled on this firmware.
        SpeechRecognizer.ERROR_CLIENT -> VoiceError.SERVICE_UNAVAILABLE
        SpeechRecognizer.ERROR_SERVER -> VoiceError.SERVICE_UNAVAILABLE
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceError.SERVICE_UNAVAILABLE
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.SERVICE_UNAVAILABLE

        // No route to the recogniser. Permanent-looking in a tunnel, and calling
        // it a timeout would invite a retry into nothing.
        SpeechRecognizer.ERROR_NETWORK -> VoiceError.SERVICE_UNAVAILABLE
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError.SERVICE_UNAVAILABLE

        // No `RECORD_AUDIO` grant. Fixable by the driver, so it stays distinct.
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.PERMISSION_DENIED

        // Audio hardware in use by another app; retryable in a moment.
        SpeechRecognizer.ERROR_AUDIO -> VoiceError.COMMAND_FAILED

        // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 33+,
        // named by their values here so the code compiles against API 23
        // constants). A unit with no Vietnamese model cannot run this feature,
        // and silently trying English would execute nothing the driver said.
        12, 13 -> VoiceError.SERVICE_UNAVAILABLE

        else -> VoiceError.COMMAND_FAILED
    }

    companion object {
        private const val TAG = "Ts8SpeechAdapter"

        /** Vietnamese; this unit's market and the only language its parser knows. */
        const val DEFAULT_LANGUAGE = "vi-VN"

        /** Enough alternatives to recover from one misheard word, and no more. */
        private const val MAX_HYPOTHESES = 5
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/SpeedRuntime.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.location.LocationFix
import com.ts8.core.map.GeoPoint
import com.ts8.core.music.AudioRoute
import com.ts8.core.speed.SpeedAlertController
import com.ts8.core.speed.SpeedLimit
import com.ts8.core.speed.SpeedMonitor
import com.ts8.core.speed.SpeedUiState
import com.ts8.launcher.di.AppGraph
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * The process-wide owner of the speed pipeline.
 *
 * **Why this exists as a runtime object.** [SpeedMonitor] itself is deliberately
 * platform-free: it takes a clock, a config, and samples. Something has to
 * connect it to the one GPS subscription, to the routing graph's per-edge speed,
 * to the stored settings and to the speakers — and it has to do that once, for
 * the whole process, so Home, the map tab and the alert path all read the same
 * number and the filter's state survives a screen change.
 *
 * **Why the limit lookup is throttled and off-thread.** `AStarRouter.limitAt`
 * searches a spatial index and walks the edges leaving a node. It is fast, but
 * it is not free, and calling it on every 1 Hz GPS callback on the main thread
 * would spend the frame budget of the map. It runs on the graph's IO pool, only
 * after the car has moved far enough for the answer to possibly change, and its
 * result is applied back on the main thread because [SpeedMonitor] is not
 * thread-safe by design (it is a state machine, and its listeners touch views).
 *
 * **Why the alert goes through [VehicleAudioBridge].** The brief requires an
 * alert volume independent of the music *that still respects audio focus*. API
 * 23 has no separate alert stream a normal app can own, so the honest
 * implementation is: request focus through the same bridge navigation uses,
 * play a short tone through `STREAM_MUSIC` scaled by the driver's alert-volume
 * setting, release focus. If focus is refused the alert downgrades to visual
 * and [SpeedAlertController.Delivery.streamedReason] says why.
 */
class SpeedRuntime(
    private val context: Context,
    private val settings: AppSettings,
    private val graph: AppGraph
) {

    private val main = Handler(Looper.getMainLooper())

    /** The one monitor. Built with the settings as they were at first use; */
    /** [refreshConfig] keeps it in step afterwards. */
    private val monitor = SpeedMonitor(
        clock = ::elapsedClock,
        config = settings.speedConfig()
    )

    private val alerts = SpeedAlertController(ToneSink(), ::elapsedClock)

    private val listeners = CopyOnWriteArrayList<SpeedMonitor.Listener>()

    /** Screens that asked for speed; the GPS feed runs while any is visible. */
    private var refCount = 0

    /** True once the pipeline is subscribed to [LocationRuntime]. */
    private val subscribed = AtomicBoolean(false)

    private var lastLimitPoint: GeoPoint? = null
    private var lastLimitLookupMs = 0L
    private var limitLookupInFlight = false

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshConfig() }

    /**
     * The monitor's own listener fan-out plus alert delivery.
     *
     * Registered as an [SpeedMonitor.AlertListener] so the monitor can tell us
     * *when* to sound without ever knowing that audio exists.
     */
    private val alertBridge = object : SpeedMonitor.AlertListener {
        override fun onSpeedState(state: SpeedUiState) {
            for (l in listeners.toTypedArray()) runCatching { l.onSpeedState(state) }
        }

        override fun onOverspeed(
            overByKmh: Int,
            limit: SpeedLimit,
            form: SpeedMonitor.AlertForm,
            volume: Float
        ) {
            // The controller decides whether the sound actually happens and
            // reports the reason when it does not. Nothing here claims audio.
            val delivery = alerts.deliver(overByKmh, form, volume)
            lastSilentReason = delivery.streamedReason
            Log.i(
                TAG,
                "overspeed +${overByKmh}km/h limit=${limit.limitKmh} ${limit.confidence} " +
                    "visual=${delivery.visual} audio=${delivery.audio}" +
                    (delivery.streamedReason?.let { " reason=$it" } ?: "")
            )
        }
    }

    init {
        monitor.addListener(alertBridge)
        settings.registerListener(prefsListener)
    }

    // -- lifecycle ---------------------------------------------------------

    /**
     * Starts feeding and joining the GPS stream.
     *
     * Reference counted exactly like [LocationRuntime] itself, so a screen that
     * is destroyed without releasing cannot pin the receiver forever: the count
     * bottoms out at zero.
     */
    fun acquire(listener: SpeedMonitor.Listener?) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener)
        refCount++
        if (refCount == 1) {
            refreshConfig()
            graph.location.acquire(locationListener)
            subscribed.set(true)
            // Show whatever the last accepted fix already was; the first new
            // sample may be a second away and the readout would sit at `--`.
            graph.location.lastFix?.let { monitor.onFix(it) }
            monitor.tick()
        }
        listener?.let { it.onSpeedState(monitor.state()) }
    }

    fun release(listener: SpeedMonitor.Listener?) {
        if (listener != null) listeners.remove(listener)
        if (refCount > 0) refCount--
        if (refCount == 0) stopFeeding()
    }

    private fun stopFeeding() {
        graph.location.release(locationListener)
        subscribed.set(false)
        alerts.silence()
        // The monitor keeps its last state (a stale sample resolves to UNKNOWN
        // on the next read), but the filter is reset so a long gap does not
        // blend the speed from before the pause into the speed after it.
        monitor.reset()
    }

    /** Re-applies every setting; called on a preference change and on acquire. */
    fun refreshConfig() {
        val config = settings.speedConfig()
        monitor.configure(config)
        // The monitor re-emits through [alertBridge], so every visible screen
        // picks the new unit/threshold up without being touched.
    }

    /** Current rendered state, for a screen that is binding late. */
    fun state(): SpeedUiState = monitor.state()

    fun alerting(): Boolean = monitor.state().alerting

    /** The reason string of the last alert that could not be sounded, if any. */
    fun lastSilentAlertReason(): String? = lastSilentReason

    @Volatile
    private var lastSilentReason: String? = null

    // -- GPS ---------------------------------------------------------------

    private val locationListener = object : LocationRuntime.Listener {
        override fun onFix(fix: LocationFix) {
            monitor.onFix(fix)
            maybeResolveLimit(fix)
        }
    }

    /**
     * Looks up the speed limit for the road under the car, throttled.
     *
     * Two gates: distance (the answer cannot change while the car has not moved
     * a road segment's worth) and time (a stationary car must not hammer the
     * router once a second). Both are needed — a stationary car still produces
     * fixes, and distance alone would open the gate on GPS jitter.
     */
    private fun maybeResolveLimit(fix: LocationFix) {
        val now = elapsedClock()
        val previous = lastLimitPoint
        val moved = previous == null || distanceMeters(previous, fix.point) >= LIMIT_RECHECK_METERS
        val elapsed = now - lastLimitLookupMs >= LIMIT_RECHECK_MS
        if (!moved && !elapsed) return
        if (limitLookupInFlight) return

        lastLimitPoint = fix.point
        lastLimitLookupMs = now
        limitLookupInFlight = true

        graph.io().execute {
            val limitKmh = runCatching { graph.routing()?.limitAt(fix.point) }.getOrNull()
            main.post {
                limitLookupInFlight = false
                if (limitKmh != null && limitKmh > 0) {
                    monitor.onPackLimit(limitKmh)
                } else {
                    // No edge within range (a car park, an unmapped service
                    // road) means no limit: clear rather than carry the previous
                    // road's sign onto this one.
                    monitor.clearLimit()
                }
            }
        }
    }

    /**
     * Flat-earth distance, good to a few percent over the 25 m this gates.
     *
     * Haversine would be more correct and is unnecessary: the only decision
     * this feeds is "did the car move far enough to re-ask the router", and both
     * approximations agree at that scale on every latitude this unit drives.
     */
    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val latRad = Math.toRadians((a.lat + b.lat) / 2.0)
        val dy = (b.lat - a.lat) * 111_320.0
        val dx = (b.lon - a.lon) * 111_320.0 * Math.cos(latRad)
        return Math.hypot(dx, dy)
    }

    // -- alert audio -------------------------------------------------------

    /**
     * Plays a short tone on `STREAM_MUSIC` while holding navigation focus.
     *
     * **Why a `ToneGenerator` and not a bundled sound file.** A tone needs no
     * asset, no decoder, and no `MediaPlayer` lifecycle to leak; the platform
     * generates it. The duration is deliberately short — an alert that overlaps
     * a turn instruction makes both unintelligible.
     */
    private inner class ToneSink : SpeedAlertController.SpeedAlertSink {

        private var generator: ToneGenerator? = null
        private var playing = false

        override fun acquireAlertFocus(): Boolean {
            val result = graph.audioBridge.requestFocus(AudioRoute.NAVIGATION)
            // Duck the music for the duration, the same way a prompt does. The
            // bridge is idempotent, so a prompt already in progress is not
            // double-ducked.
            runCatching { graph.audioBridge.duck() }
            return when (result) {
                is com.ts8.core.audio.AudioResult.Success -> true
                else -> false
            }
        }

        override fun releaseAlertFocus() {
            runCatching { generator?.release() }
            generator = null
            playing = false
            runCatching { graph.audioBridge.unduck() }
            runCatching { graph.audioBridge.abandonFocus(AudioRoute.NAVIGATION) }
        }

        override fun play(tone: SpeedAlertController.AlertTone, volume: Float) {
            val percent = (volume.coerceIn(0f, 1f) * 100f).toInt().coerceIn(1, 100)
            val created = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, percent) }
                .getOrNull() ?: throw IllegalStateException("không tạo được ToneGenerator")
            generator = created
            val id = when (tone) {
                SpeedAlertController.AlertTone.OVERSPEED -> ToneGenerator.TONE_PROP_BEEP2
                SpeedAlertController.AlertTone.OVERSPEED_RECOVERED -> ToneGenerator.TONE_PROP_ACK
            }
            playing = created.startTone(id, ALERT_TONE_MS)
            if (!playing) throw IllegalStateException("ToneGenerator từ chối phát")
        }

        override fun stop() {
            runCatching { generator?.stopTone() }
            runCatching { generator?.release() }
            generator = null
            playing = false
        }
    }

    // -- diagnostics -------------------------------------------------------

    /**
     * One line for the diagnostics panel.
     *
     * Carries the state, the raw and displayed value, the limit with its
     * provenance and the sample age — everything needed to tell "no data" from
     * "lookup stopped". Contains no coordinate: the limit's *source* is a fact
     * about the pack, the position is not.
     */
    fun diagnosticLine(): String {
        val s = monitor.state()
        val limit = if (s.limitKmh != null) {
            "${s.limitKmh} ${s.limitSource}=${monitorLimitConfidence()}"
        } else {
            "-- (không có dữ liệu)"
        }
        return "speed=${s.speedKmh ?: "--"}${s.unitLabel} state=${s.state} " +
            "limit=$limit age=${s.sampleAgeMs}ms src=${s.sampleSource ?: "-"}"
    }

    private fun monitorLimitConfidence(): String =
        // Read through the resolver's own answer rather than recomputing it, so
        // the diagnostics cannot disagree with what the alert path used.
        when (monitor.state().limitSource) {
            com.ts8.core.speed.LimitSource.ROUTING_GRAPH -> "MEDIUM"
            com.ts8.core.speed.LimitSource.VEHICLE_BUS -> "HIGH"
            com.ts8.core.speed.LimitSource.NONE -> "NONE"
        }

    companion object {
        private const val TAG = "Ts8SpeedRuntime"

        /** How far the car must travel before the limit is looked up again. */
        private const val LIMIT_RECHECK_METERS = 25.0

        /** Upper bound on lookups while stationary or jittering. */
        private const val LIMIT_RECHECK_MS = 5_000L

        /** Length of the alert beep, milliseconds. */
        private const val ALERT_TONE_MS = 350
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/VoiceCommandExecutor.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ts8.core.audio.AudioResult
import com.ts8.core.map.GeoPoint
import com.ts8.core.search.PlaceSearch
import com.ts8.core.search.SearchOptions
import com.ts8.core.voice.CommandExecutor
import com.ts8.core.voice.CommandOutcome
import com.ts8.core.voice.VoiceCommand
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.ui.AppDrawerActivity
import com.ts8.launcher.ui.HomeActivity
import com.ts8.launcher.ui.MapActivity
import com.ts8.launcher.ui.MusicFullscreenActivity
import com.ts8.launcher.ui.SearchActivity

/**
 * Carries out a parsed [VoiceCommand] against the launcher's real engines.
 *
 * **What this class is allowed to decide.** Only *how*, never *whether*: the
 * router has already asked for confirmation on the two destructive commands, and
 * this class must not re-ask (a second prompt for one utterance is the
 * double-question bug the router's `alreadyConfirmed` exists to stop). What it
 * does decide is what to *say*: [CommandOutcome.Done.spoken] names the action
 * that actually ran, because on a head unit the spoken sentence is the only
 * confirmation the driver gets without looking away from the road.
 *
 * **Why every failure is a sentence and not an exception.** `CommandExecutor`
 * is allowed to throw and the router would convert it to a generic "Không thực
 * hiện được lệnh" — losing the one piece of information that lets the driver do
 * something differently. So "chưa có bài nào trong thư viện" is returned as
 * [CommandOutcome.Failed] with those words in it, not thrown.
 *
 * **Why this runs on the caller's thread.** The router executes synchronously
 * from a binder callback on the main looper, so everything here must be cheap:
 * `pause`, `next`, a volume write, an `Intent`. The one call that touches a
 * database is the place lookup, and it is a single FTS4 match capped at one
 * row — the same query [com.ts8.core.search.PlaceSearchService] runs per
 * keystroke behind a 250 ms debounce, so its cost is a fraction of a frame.
 * Anything slower than that would have to be moved behind a pending state in
 * the router, which does not exist.
 */
class VoiceCommandExecutor(
    context: Context,
    private val graph: AppGraph
) : CommandExecutor {

    private val appContext = context.applicationContext

    /**
     * The music service handle.
     *
     * Resolved per command rather than captured once: the service is started
     * lazily by the first screen that attaches, and a command spoken on the very
     * first frame after boot would otherwise hold a null forever.
     */
    private val music: MusicRuntime get() = MusicRuntime.get(appContext)

    override fun execute(command: VoiceCommand): CommandOutcome = when (command) {
        is VoiceCommand.NavigateTo -> navigateTo(command.destination)
        is VoiceCommand.SearchPlace -> searchPlace(command.query)
        VoiceCommand.PlayMusic -> playMusic()
        VoiceCommand.PauseMusic -> pauseMusic()
        VoiceCommand.NextTrack -> skip(forward = true)
        VoiceCommand.PreviousTrack -> skip(forward = false)
        VoiceCommand.VolumeUp -> nudgeVolume(+1)
        VoiceCommand.VolumeDown -> nudgeVolume(-1)
        is VoiceCommand.VolumeTo -> setVolumePercent(command.percent)
        VoiceCommand.GoHome -> open(HomeActivity::class.java, "Về trang chủ")
        VoiceCommand.OpenMap -> open(MapActivity::class.java, "Mở bản đồ")
        VoiceCommand.OpenMusic -> open(MusicFullscreenActivity::class.java, "Mở nhạc")
        VoiceCommand.OpenPhone -> openPhone()
        is VoiceCommand.OpenApp -> openApp(command.query)
        VoiceCommand.Exit -> leaveLauncher()
    }

    // -- navigation --------------------------------------------------------

    /**
     * Resolves a folded destination against the pack's own place index and
     * hands it to the map.
     *
     * **Why the local index and not an online geocoder.** The offline pack is
     * the only source with coverage of the roads the routing graph can actually
     * drive on; a geocoder coordinate 40 km from the nearest graph vertex
     * produces `NO_END_NODE` and a dead end after the driver has already
     * committed. When the pack has no `places.db` the honest answer is that
     * search is unavailable, which is a different sentence from "không tìm
     * thấy".
     *
     * **Why the first result and not a list.** The overlay cannot show a list of
     * five candidates on this panel while the car is moving; the driver asked
     * for a place by name, and the index is ranked by name match then distance
     * from the current fix. The name is quoted back in the spoken confirmation,
     * so a wrong pick is audible immediately and can be corrected by saying it
     * again.
     */
    private fun navigateTo(destination: String): CommandOutcome {
        val query = destination.trim()
        if (query.isEmpty()) {
            return CommandOutcome.Failed("Chưa rõ điểm đến. Nói lại tên địa điểm.")
        }
        if (graph.pack() == null) {
            return CommandOutcome.Failed("Chưa có bản đồ. Mở Quản lý bản đồ để cài đặt.")
        }
        val search: PlaceSearch = graph.placeSearch()
            ?: return CommandOutcome.Failed("Bản đồ này không có dữ liệu tìm kiếm địa điểm.")

        val fix = graph.location.lastFix?.point
        val hit = runCatching {
            search.search(query, SearchOptions(limit = 1, biasPoint = fix)).firstOrNull()
        }.getOrElse { e ->
            Log.w(TAG, "place lookup failed: ${e.message}")
            return CommandOutcome.Failed("Không tra được địa điểm. Thử lại.")
        } ?: return CommandOutcome.Failed("Không tìm thấy địa điểm: $query")

        val point = hit.place.point
        if (!point.isValid()) {
            return CommandOutcome.Failed("Địa điểm này không có toạ độ hợp lệ.")
        }

        val name = hit.place.name.ifBlank { query }
        // The map is the destination of this handoff, so it replaces whatever
        // is on top rather than stacking: a voice command issued three times
        // must not leave three map screens behind it.
        val launched = start(
            MapActivity.intentTo(appContext, point, name)
        )
        return if (launched) {
            CommandOutcome.Done("Đang dẫn đường tới $name")
        } else {
            CommandOutcome.Failed("Không mở được bản đồ.")
        }
    }

    /** Opens the search screen with the spoken query already typed. */
    private fun searchPlace(query: String): CommandOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return CommandOutcome.Failed("Chưa rõ từ khoá tìm kiếm.")
        }
        val launched = start(
            Intent(appContext, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_QUERY, trimmed)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        return if (launched) {
            CommandOutcome.Done("Đang tìm $trimmed")
        } else {
            CommandOutcome.Failed("Không mở được màn hình tìm kiếm.")
        }
    }

    // -- music -------------------------------------------------------------

    /**
     * Starts playback, loading the library when nothing is queued.
     *
     * Deliberately *not* `play()` on an existing queue: the router asked the
     * driver before this ran precisely because "phát nhạc" replaces the queue,
     * and resuming an album they had paused is a different command. When the
     * library is empty the failure says so, rather than leaving the driver
     * wondering why nothing happened after they confirmed.
     */
    private fun playMusic(): CommandOutcome {
        val backend = music.backend
            ?: return CommandOutcome.Failed("Trình phát nhạc chưa sẵn sàng.")
        val current = backend.player.state()
        if (current.track != null) {
            backend.player.play()
            return CommandOutcome.Done("Đang phát nhạc")
        }
        // The first page is enough for "phát nhạc": a queue of the whole
        // library would be a 4 000-row list built on the main thread.
        val first = runCatching { backend.library.allTracks(offset = 0, limit = PLAY_QUEUE_LIMIT) }
            .getOrDefault(emptyList())
        if (first.isEmpty()) {
            return CommandOutcome.Failed("Thư viện chưa có bài nào. Quét nhạc trong Ứng dụng.")
        }
        backend.player.setQueue(first, 0, autoPlay = true)
        return CommandOutcome.Done("Đang phát nhạc")
    }

    private fun pauseMusic(): CommandOutcome {
        val backend = music.backend
            ?: return CommandOutcome.Failed("Trình phát nhạc chưa sẵn sàng.")
        if (backend.player.state().track == null) return CommandOutcome.Failed("Chưa có bài nào đang phát.")
        backend.player.pause()
        return CommandOutcome.Done("Đã dừng nhạc")
    }

    /**
     * Advances the queue.
     *
     * Through [MusicRuntime]'s intents rather than the bound player so the
     * command works in the window before the service finishes binding — the
     * same window in which a driver who just got into the car is most likely to
     * speak.
     */
    private fun skip(forward: Boolean): CommandOutcome {
        if (music.state().track == null) return CommandOutcome.Failed("Chưa có bài nào đang phát.")
        if (forward) music.next() else music.previous()
        return CommandOutcome.Done(if (forward) "Bài tiếp theo" else "Bài trước")
    }

    // -- volume ------------------------------------------------------------

    private fun nudgeVolume(delta: Int): CommandOutcome {
        val bridge = graph.audioBridge
        val target = (bridge.mediaVolume() + delta).coerceIn(0, bridge.maxMediaVolume())
        val result = bridge.setMediaVolume(target)
        if (result !is AudioResult.Success) {
            return CommandOutcome.Failed("Không đổi được âm lượng.")
        }
        return CommandOutcome.Done("Âm lượng ${percentOf(target)}%")
    }

    private fun setVolumePercent(percent: Int): CommandOutcome {
        val bridge = graph.audioBridge
        val max = bridge.maxMediaVolume().coerceAtLeast(1)
        // Rounded rather than truncated: a 15-step scale would otherwise report
        // "40 phần trăm" as 6/15 = 40% and set 5/15 = 33%, so the spoken number
        // and the actual level would disagree.
        val index = Math.round(percent / 100f * max).coerceIn(0, max)
        val result = bridge.setMediaVolume(index)
        if (result !is AudioResult.Success) {
            return CommandOutcome.Failed("Không đặt được âm lượng.")
        }
        return CommandOutcome.Done("Âm lượng ${percentOf(index)}%")
    }

    private fun percentOf(index: Int): Int {
        val max = graph.audioBridge.maxMediaVolume().coerceAtLeast(1)
        return Math.round(index * 100f / max)
    }

    // -- screens -----------------------------------------------------------

    /**
     * Opens the dialer, falling back to the app drawer.
     *
     * The same two-step resolution [com.ts8.launcher.ui.BottomNavView] performs,
     * and it is repeated rather than shared because the two have different
     * failure surfaces: a tap can show a toast, a voice command has to return a
     * sentence the router will speak. When neither exists the answer is a
     * [CommandOutcome.Failed] naming the situation, not a silent success the
     * driver interprets as "it did not hear me".
     */
    private fun openPhone(): CommandOutcome {
        val dial = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:"))
        if (dial.resolveActivity(appContext.packageManager) != null) {
            return if (start(dial)) {
                CommandOutcome.Done("Mở điện thoại")
            } else {
                CommandOutcome.Failed("Không mở được điện thoại.")
            }
        }
        return open(AppDrawerActivity::class.java, "Thiết bị không có ứng dụng gọi điện — đã mở danh sách ứng dụng")
    }

    /**
     * Launches an installed app whose label matches what the driver said.
     *
     * **Why the catalogue and not `getLaunchIntentForPackage`.** The driver says
     * a *label* ("bản đồ", "đài"), not a package name, and several packages can
     * carry the same label. The catalogue already folds every label the same way
     * [com.ts8.core.voice.IntentParser] folds the spoken name, so an exact folded
     * match is the honest test; a prefix match is not, because "nhạc" would then
     * launch the first of four music apps in alphabetical order.
     *
     * The catalogue loads on a background thread and caches; a voice command
     * issued before the drawer has ever been opened triggers that first load
     * here, which on 40 packages takes a few tens of milliseconds. Acceptable
     * inside the executor's budget, and unavoidable — the alternative is
     * answering "không tìm thấy" to an app that is installed.
     */
    private fun openApp(query: String): CommandOutcome {
        val wanted = query.trim()
        if (wanted.isEmpty()) {
            return CommandOutcome.Failed("Chưa rõ ứng dụng nào. Nói lại tên ứng dụng.")
        }
        val catalog = graph.appCatalog()
        val entry = catalog.findByLabel(wanted)
            ?: return CommandOutcome.Failed("Không tìm thấy ứng dụng: $wanted")
        return if (start(catalog.intentFor(entry))) {
            CommandOutcome.Done("Mở ${entry.label}")
        } else {
            CommandOutcome.Failed("Không mở được ${entry.label}.")
        }
    }

    private fun open(activity: Class<*>, spoken: String): CommandOutcome {
        val launched = start(
            Intent(appContext, activity)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        return if (launched) CommandOutcome.Done(spoken) else CommandOutcome.Failed("Không mở được màn hình.")
    }

    /**
     * Leaves the launcher for the system Home.
     *
     * `CATEGORY_HOME` rather than `moveTaskToBack`: the launcher's task may hold
     * several screens, and backing out of one would leave the driver on another.
     * When this app *is* the Home app the intent resolves back to
     * [HomeActivity], which is the correct destination anyway — and it is not
     * forced either way, because the brief forbids changing the default
     * launcher automatically.
     */
    private fun leaveLauncher(): CommandOutcome {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(home)) CommandOutcome.Done("Đã thoát") else CommandOutcome.Failed("Không thoát được.")
    }

    /** Starts an activity from an application context, reporting refusal. */
    private fun start(intent: Intent): Boolean {
        // Every launch here leaves the launcher's own task (the map, the drawer,
        // the system Home), so NEW_TASK is mandatory; the per-screen flags that
        // keep a single instance were already added by the caller.
        if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "startActivity refused: ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
    }

    companion object {
        private const val TAG = "Ts8VoiceExec"

        /**
         * How many tracks "phát nhạc" queues when nothing was loaded.
         *
         * Fifty: one screenful of rows for the queue view, built fast enough to
         * stay on the caller's thread, and long enough that the driver is not
         * choosing music again two minutes later.
         */
        private const val PLAY_QUEUE_LIMIT = 50
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/VoiceFeedback.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.ts8.core.audio.AudioResult
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.music.AudioRoute
import java.util.Locale

/**
 * Speaks the router's sentences, and holds the music down while it does.
 *
 * **Why duck rather than pause.** A prompt over a paused track leaves the car
 * silent for two seconds and then restarts the song, which the driver reads as a
 * skip. [VehicleAudioBridge.duck] scales `STREAM_MUSIC` to a quarter, so the
 * prompt is audible and the track keeps its place; `unduck` restores exactly the
 * level that was there before, which is what stops a prompt from permanently
 * changing the driver's volume.
 *
 * **Why the music is ducked for the whole interaction, not per sentence.** One
 * voice interaction can speak three times in a row — "Đang nghe…", then the
 * confirmation question, then the result. Ducking and unducking around each
 * produces a track that swells between them. The controller counts: [begin] once
 * when the overlay opens and [end] once when it closes.
 *
 * **Why TTS availability is probed, not assumed.** A head unit whose firmware
 * stripped the speech engine must still run Voice Instant — the overlay and the
 * command executors work, only the audio is missing. [isLanguageAvailable]
 * returning false makes the controller say nothing rather than hand text to an
 * engine that will drop it, and the overlay remains the feedback channel.
 *
 * **API 23 note.** `TextToSpeech.speak` with a `Bundle` of parameters does not
 * exist before API 21, and the `QUEUE_FLUSH` overload used here does. The
 * utterance id is what [UtteranceProgressListener] reports, which is how
 * [onDone] knows the duck can be released.
 */
class VoiceFeedback(
    context: Context,
    private val audioBridge: VehicleAudioBridge
) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Null until the engine binds, and null forever on a unit without one. */
    private var tts: TextToSpeech? = null

    /** Null while the engine is still initialising. */
    @Volatile
    private var languageOk: Boolean? = null

    @Volatile
    private var initialising = false

    /** True while the bridge is ducked on this class's behalf. */
    private var ducked = false

    /** Runs after the current utterance ends, or immediately if none is speaking. */
    private var afterSpeech: (() -> Unit)? = null

    /**
     * Prepares the engine.
     *
     * Idempotent and cheap after the first call. Called when the overlay opens
     * rather than at app start, so a driver who never uses voice never pays for
     * a TTS binding — and so a unit whose engine hangs on init does not delay
     * the launcher's cold start.
     */
    fun prepare() {
        if (tts != null || initialising) return
        initialising = true
        val created = runCatching {
            TextToSpeech(appContext) { status ->
                initialising = false
                if (status != TextToSpeech.SUCCESS) {
                    // No engine, or it failed to initialise: stay silent and let
                    // the overlay be the only feedback, rather than retrying
                    // forever on a firmware that will never have one.
                    Log.w(TAG, "TTS init failed: $status")
                    languageOk = false
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                val result = runCatching { engine.setLanguage(Locale("vi", "VN")) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                languageOk = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (languageOk != true) {
                    Log.w(TAG, "no Vietnamese TTS voice; voice feedback is visual only")
                }
                runCatching { engine.setSpeechRate(SPEECH_RATE) }
            }
        }.getOrNull()
        tts = created
        if (created == null) {
            initialising = false
            languageOk = false
            return
        }
        created.setOnUtteranceProgressListener(progress)
    }

    /**
     * True when a sentence can actually be spoken.
     *
     * Tri-state internally — null means "still initialising" — but callers get
     * a boolean, and an uninitialised engine answers `false`: promising to speak
     * and then not doing it is the failure this method exists to prevent.
     */
    fun canSpeak(): Boolean = languageOk == true

    /**
     * Speaks [text], queueing behind anything already speaking.
     *
     * `QUEUE_FLUSH` is deliberately **not** used: the "Đang nghe…" prompt and
     * the result sentence arrive close together, and flushing the first means
     * the driver hears the outcome with no context for it.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }
        prepare()
        val engine = tts
        if (engine == null || languageOk != true) {
            // No audio; the overlay carries the message. The completion still
            // runs, because a caller waiting on it must not hang on a mute unit.
            Log.i(TAG, "voice feedback unavailable; text only: $text")
            onComplete?.invoke()
            return
        }
        if (onComplete != null) afterSpeech = onComplete else afterSpeech = null
        val id = "utterance-${nextUtteranceId++}"
        val queued = runCatching { engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) }
            .getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            afterSpeech = null
            Log.w(TAG, "speak() refused: $queued")
            onComplete?.invoke()
        }
    }

    /** Drops anything queued, e.g. when the driver presses Back mid-prompt. */
    fun stopSpeaking() {
        runCatching { tts?.stop() }
        afterSpeech = null
    }

    /**
     * Takes the music down for the duration of the interaction.
     *
     * If audio focus is refused the duck is skipped rather than forced: another
     * app owns the stream, and scaling `STREAM_MUSIC` under it would change
     * their volume, not ours.
     */
    fun begin() {
        prepare()
        val focus = runCatching { audioBridge.requestFocus(AudioRoute.NAVIGATION) }.getOrNull()
        if (focus !is AudioResult.Success) {
            // Nothing to duck, but TTS may still be heard over the other app's
            // stream on some firmware, so the interaction is not abandoned.
            Log.i(TAG, "no navigation focus; music not ducked for voice")
            return
        }
        val ducked = runCatching { audioBridge.duck() }.isSuccess
        this.ducked = ducked
    }

    /** Releases the duck and any queued speech. Safe when never begun. */
    fun end() {
        stopSpeaking()
        if (!ducked) return
        ducked = false
        runCatching { audioBridge.unduck() }
        runCatching { audioBridge.abandonFocus(AudioRoute.NAVIGATION) }
    }

    /** Releases the TTS engine. The controller calls this when it is destroyed. */
    fun release() {
        end()
        val engine = tts
        tts = null
        languageOk = null
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
    }

    // -- internals ----------------------------------------------------------

    private var nextUtteranceId = 0

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        /** Delivered on a binder thread; anything touching the UI is hopped. */
        override fun onDone(utteranceId: String?) {
            val next = afterSpeech ?: return
            afterSpeech = null
            main.post { runCatching { next() } }
        }

        @Deprecated("Deprecated in Java; the platform still calls it on API 23.")
        override fun onError(utteranceId: String?) {
            onDone(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onDone(utteranceId)
        }
    }

    companion object {
        private const val TAG = "Ts8VoiceFeedback"

        /**
         * Slightly faster than default. Vietnamese prompts are short and the
         * driver is moving; the default rate reads as languid on a car speaker.
         */
        private const val SPEECH_RATE = 1.05f
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ts8.core.voice.CommandExecutor
import com.ts8.core.voice.IntentParser
import com.ts8.core.voice.VoiceCommand
import com.ts8.core.voice.VoiceError
import com.ts8.core.voice.VoiceRouter
import com.ts8.core.voice.VoiceState
import com.ts8.core.voice.VoiceStateSnapshot
import com.ts8.core.voice.VoiceStateStore

/**
 * Voice Instant's process-level owner: microphone, router, speech in and out.
 *
 * **Why it lives here and not in the overlay Activity.** The overlay is created
 * and destroyed by whatever screen the driver is on; the recogniser session, the
 * ducked music and the pending confirmation must not end because a screen was
 * recreated. Keeping the controller in the application graph makes the overlay a
 * *renderer* of [snapshot] — the same rule the spec applies to the map holds
 * here too: no engine lives inside an Activity.
 *
 * **What this class is not.** It contains no command semantics. Which phrase
 * means what is [IntentParser]'s, when to confirm is [VoiceRouter]'s, and what a
 * command actually does is the app's [CommandExecutor]. This file is the wiring
 * the brief calls `VoiceInstantController`: permission check, session lifecycle,
 * the timeout timer, and the store hand-off.
 *
 * **One timer for the whole interaction.** [VoiceRouter] owns the deadline
 * ([VoiceRouter.remainingMs]) but cannot post anything; this class posts a
 * single delayed message and re-arms it on every state change. That is what
 * makes a `CONFIRMING` prompt close itself after eight seconds, and what makes
 * `SUCCESS`/`ERROR` auto-dismiss on the same budget instead of sitting there
 * until ignition off.
 *
 * **No background listening.** There is exactly one entry point that opens the
 * microphone ([start]) and every exit path funnels through [finishInteraction],
 * which cancels the recogniser. A process that dies mid-overlay is restored
 * **IDLE** by [VoiceStateStore], because the microphone died with it.
 */
class VoiceInstantController(
    context: Context,
    private val feedback: VoiceFeedback,
    private val executor: CommandExecutor,
    private val store: VoiceStateStore
) {

    /**
     * What the overlay draws. Implemented by the screen.
     *
     * Both callbacks arrive on the main thread, because the router can be driven
     * from the recogniser's binder callback.
     */
    interface Listener {
        /** Every state change, so the overlay redraws exactly when it must. */
        fun onState(snapshot: VoiceStateSnapshot)

        /**
         * The sentence being spoken — the prompt, the confirmation question, the
         * result, or the failure reason.
         *
         * Carried separately from [VoiceStateSnapshot] because the reason is
         * Vietnamese prose ("Không hiểu lệnh: cà phê") assembled by the prompt
         * layer, while the snapshot only carries the closed [VoiceError] enum.
         * The overlay shows this line; a driver in a noisy cabin reads it when
         * they cannot hear it.
         */
        fun onCaption(text: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter = SpeechRecognizerAdapter(appContext)

    private val router = VoiceRouter(
        executor = CommandExecutor { command -> executor.execute(command) },
        clock = { SystemClock.elapsedRealtime() }
    )

    private var listener: Listener? = null

    /** True between [start] and the first terminal state of that interaction. */
    private var interactionActive = false

    /** The pending router deadline message, so it can be replaced. */
    private val timeoutTick = Runnable { onTimeoutTick() }

    init {
        router.setListener(object : VoiceRouter.Listener {
            override fun onStateChanged(snapshot: VoiceStateSnapshot) = publish(snapshot)

            override fun onSpeak(text: String) {
                val target = listener
                if (target != null) main.post { runCatching { target.onCaption(text) } }
                main.post { feedback.speak(text) }
            }
        })
    }

    // -- observer ----------------------------------------------------------

    /** Attaches the overlay and immediately hands it the current state. */
    fun attach(listener: Listener) {
        this.listener = listener
        runCatching { listener.onState(router.snapshot()) }
    }

    /** Detaches without stopping an interaction that is still running. */
    fun detach() {
        listener = null
    }

    fun snapshot(): VoiceStateSnapshot = router.snapshot()

    /** The command awaiting confirmation, for the overlay's question line. */
    fun pendingCommand(): VoiceCommand? = router.pendingCommand()

    // -- start / stop ------------------------------------------------------

    /**
     * Opens the microphone and starts an interaction.
     *
     * Every precondition is checked here and reported through the router, so the
     * overlay shows the *same* surface as a failed recognition. The cases the
     * brief names are distinct and all reachable:
     *
     * - `PERMISSION_DENIED` — `RECORD_AUDIO` never granted; the overlay points
     *   the driver at Settings because it is fixable there.
     * - `SERVICE_UNAVAILABLE` — the unit has no recogniser, or the firmware
     *   disabled the one it has. The brief's exact sentence, `Voice service
     *   unavailable`, is what the overlay shows.
     * - a normal session.
     *
     * @return false only when an interaction is already running.
     */
    fun start(): Boolean {
        if (interactionActive) return false
        interactionActive = true

        // Duck and prepare before the microphone opens: the recogniser hears the
        // cabin through the same speakers, and a loud track is the single
        // largest cause of a wrong transcript on this unit.
        feedback.prepare()

        if (!hasRecordPermission()) {
            router.onRecognitionError(VoiceError.PERMISSION_DENIED)
            persistAndArm()
            return true
        }

        feedback.begin()
        router.onListeningStarted()

        val started = adapter.start(object : SpeechRecognizerAdapter.Callback {
            override fun onReady() {
                // Already LISTENING; nothing to change. Logged because a unit
                // that takes seconds to become ready is worth knowing about.
                Log.i(TAG, "recogniser ready")
            }

            override fun onProcessing() {
                // `onEndOfSpeech` only means the audio is closed; the router
                // moves to PROCESSING on the transcript itself.
                Log.i(TAG, "recogniser end-of-speech")
            }

            override fun onResult(hypotheses: List<String>) {
                val chosen = chooseHypothesis(hypotheses)
                Log.i(TAG, "transcript: \"$chosen\" (of ${hypotheses.size})")
                router.onTranscript(chosen)
                persistAndArm()
            }

            override fun onError(error: VoiceError, detail: String?) {
                Log.w(TAG, "recognition failed: $error ($detail)")
                router.onRecognitionError(error)
                persistAndArm()
            }
        })

        if (!started) {
            // `adapter.start` already delivered the error through the callback
            // above; only the bookkeeping is left.
            Log.i(TAG, "recogniser session did not open")
        }
        persistAndArm()
        return true
    }

    /**
     * Answers a confirmation prompt.
     *
     * A spoken "đồng ý" is *not* accepted: the recogniser's hypothesis for it is
     * unreliable enough that treating it as the answer both mis-fires and leaves
     * the prompt open. The overlay has two buttons for exactly this reason, and
     * [VoiceRouter.onTranscript] drops a transcript that arrives in CONFIRMING.
     */
    fun confirm(yes: Boolean) {
        router.confirm(yes)
        persistAndArm()
    }

    /**
     * Back / the close button: abandon the interaction.
     *
     * Cancels the microphone first, so no transcript can land after the state
     * has moved to IDLE, then tells the router. [VoiceRouter.cancel] announces
     * the abandonment through [VoiceRouter.Listener.onSpeak], which is what
     * restores the music's level.
     */
    fun cancel() {
        adapter.cancel()
        router.cancel()
        persistAndArm()
        finishIfIdle()
    }

    /**
     * The driver refused `RECORD_AUDIO`, or the firmware would not even ask.
     *
     * Separate from [start] because the platform answers asynchronously: the
     * trigger has already returned to the caller by the time the dialog is
     * dismissed, and there is no session to open. The overlay must still say
     * something, and it must be the fixable case rather than a generic failure,
     * so the error goes through the router exactly as a recogniser error does.
     */
    fun permissionRefused() {
        if (interactionActive) return
        interactionActive = true
        router.onRecognitionError(VoiceError.PERMISSION_DENIED)
        persistAndArm()
    }

    /** Called by the owning screen when it goes away, e.g. on `onPause`. */
    fun stop() {
        if (interactionActive) cancel()
    }

    /** Releases the recogniser and the TTS engine. Process teardown only. */
    fun release() {
        main.removeCallbacks(timeoutTick)
        adapter.release()
        feedback.release()
        router.setListener(null)
        listener = null
        interactionActive = false
    }

    /**
     * The state the overlay should start with in a new process.
     *
     * [VoiceStateStore.restore] degrades every in-flight state to IDLE and
     * returns null when nothing was stored; null becomes the honest idle
     * snapshot rather than a crash.
     */
    fun restoredSnapshot(): VoiceStateSnapshot {
        val now = SystemClock.elapsedRealtime()
        return store.restore(now) ?: VoiceStateSnapshot.idle(now)
    }

    // -- internals ---------------------------------------------------------

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Picks the hypothesis the parser can act on.
     *
     * The recogniser's top result is not always the one Voice Instant can use:
     * "mở bản đồ" often arrives second behind "mở bạn đồ". Trying each in order
     * and taking the first that parses to a command turns a wrong ranking into a
     * correct action; if none parses, the **original first** hypothesis is
     * returned so the overlay quotes what was actually heard rather than the
     * last thing tried.
     */
    private fun chooseHypothesis(hypotheses: List<String>): String {
        if (hypotheses.size <= 1) return hypotheses.firstOrNull().orEmpty()
        for (candidate in hypotheses) {
            if (IntentParser.parse(candidate) is IntentParser.ParseResult.Command) return candidate
        }
        return hypotheses.first()
    }

    /** Publishes a snapshot and tears down if it is the end of the interaction. */
    private fun publish(snapshot: VoiceStateSnapshot) {
        listener?.let { target ->
            main.post { runCatching { target.onState(snapshot) } }
        }
        if (snapshot.state == VoiceState.IDLE) finishInteraction()
    }

    /** Saves the snapshot and re-arms the deadline in one step. */
    private fun persistAndArm() {
        runCatching { store.save(router.snapshot()) }
        armTimeout()
    }

    /**
     * Tears down everything the interaction held.
     *
     * Reached from every path that lands on IDLE — success, failure, cancel and
     * the router's own timeout — so the microphone, the duck and the timer
     * cannot outlive the overlay because one path forgot.
     */
    private fun finishInteraction() {
        if (!interactionActive) return
        interactionActive = false
        main.removeCallbacks(timeoutTick)
        adapter.cancel()
        feedback.end()
        runCatching { store.save(router.snapshot()) }
    }

    /** No-op unless the router is already home; keeps [cancel] ordering simple. */
    private fun finishIfIdle() {
        if (router.state() == VoiceState.IDLE) finishInteraction()
    }

    /**
     * Re-arms the router's deadline.
     *
     * One message, always replaced: re-arming on each state change is what makes
     * SUCCESS and ERROR auto-dismiss on the same budget a confirmation gets,
     * without a second timer anywhere.
     */
    private fun armTimeout() {
        main.removeCallbacks(timeoutTick)
        if (router.state() == VoiceState.IDLE) return
        val remaining = router.remainingMs()
        if (remaining <= 0L) {
            // Already past it (a slow main thread), but the router refuses to
            // fire early, so this is only legal once the deadline has passed.
            main.post(timeoutTick)
            return
        }
        main.postDelayed(timeoutTick, remaining + TIMEOUT_SLACK_MS)
    }

    private fun onTimeoutTick() {
        if (router.state() == VoiceState.IDLE) return
        router.onTimeout()
        val state = router.state()
        if (state == VoiceState.IDLE) {
            // `publish` already ran [finishInteraction] through the listener
            // path; persisting here covers a router built without a listener.
            runCatching { store.save(router.snapshot()) }
        } else {
            // The router ignored an early tick; re-arm against the real deadline.
            armTimeout()
        }
    }

    companion object {
        private const val TAG = "Ts8VoiceInstant"

        /**
         * The router measures with `elapsedRealtime` and its own `remainingMs`;
         * a `Handler` can fire a millisecond early on a busy looper, and the
         * router would then ignore that tick and never be re-armed. The slack
         * makes the post land after the deadline it is posted for.
         */
        private const val TIMEOUT_SLACK_MS = 80L
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/runtime/VoiceTrigger.kt

BEGIN FILE
package com.ts8.launcher.runtime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The one way Voice Instant is opened, and the only place the microphone
 * permission is asked for.
 *
 * **Why this is a class and not a call to [VoiceInstantController.start] from
 * each screen.** §11 names `VoiceTrigger` as a component, and the reason is the
 * permission dance: on API 23 the first tap must request `RECORD_AUDIO`, the
 * answer arrives asynchronously in `onRequestPermissionsResult`, and the
 * interaction has to start *after* it. Three screens doing that independently
 * would be three chances to start a recogniser with no grant, which on this
 * firmware produces `ERROR_INSUFFICIENT_PERMISSIONS` a second later instead of
 * a dialog.
 *
 * **Why it asks on the mic tap and never at boot.** A launcher that asks for
 * the microphone on first start teaches every driver to deny it. The permission
 * is requested at the moment its purpose is obvious — the driver just tapped a
 * microphone — and a denial is reported through the overlay as
 * `PERMISSION_DENIED`, which is the one error the driver can fix in Settings.
 *
 * **Why `shouldShowRequestPermissionRationale` is not consulted.** On this head
 * unit the driver is in a car, not reading an explanation screen; the overlay
 * already says why the microphone is needed and offers a Settings button. A
 * second dialog explaining the same thing before the system dialog is one more
 * tap between the driver and the command.
 *
 * **Threading.** Main thread only: `requestPermissions` must be called there,
 * and the controller it forwards to drives its recogniser from the main looper.
 */
class VoiceTrigger(
    private val activity: Activity,
    private val controller: VoiceInstantController
) {

    /** True while a permission request this class issued is outstanding. */
    private var awaitingPermission = false

    /**
     * Starts a voice interaction, requesting the microphone permission if it
     * has never been granted.
     *
     * @return true when a session was opened or a permission dialog appeared;
     *         false when an interaction is already running, in which case the
     *         tap is a no-op rather than a second recogniser.
     */
    fun request(): Boolean {
        if (hasPermission()) {
            awaitingPermission = false
            return controller.start()
        }
        if (awaitingPermission) return false
        awaitingPermission = true
        val asked = runCatching {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO
            )
        }.isSuccess
        if (!asked) {
            // A firmware that refuses to show the dialog still has to produce a
            // visible outcome; the overlay's PERMISSION_DENIED path is it.
            awaitingPermission = false
            controller.permissionRefused()
        }
        return asked
    }

    /**
     * Forwards the platform's answer.
     *
     * Called from the owning screen's `onRequestPermissionsResult`. A grant
     * starts the session the driver asked for; a denial is announced through the
     * controller so the overlay shows the Settings affordance, and nothing is
     * retried behind their back.
     *
     * @return true when this call consumed the result.
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != REQUEST_AUDIO) return false
        awaitingPermission = false
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED ||
            hasPermission()
        if (granted) {
            controller.start()
        } else {
            Log.i(TAG, "RECORD_AUDIO denied; voice will report PERMISSION_DENIED")
            controller.permissionRefused()
        }
        return true
    }

    /**
     * Opens the system page for this app's permissions.
     *
     * `ACTION_APPLICATION_DETAILS_SETTINGS` rather than a manufacture-specific
     * intent: the latter is not resolvable on a head unit whose Settings app is
     * the vendor's, and an unresolvable intent would leave the driver with a
     * dead button on the one error they can fix.
     */
    fun openPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { activity.startActivity(intent) }
        if (opened.isFailure) Log.w(TAG, "permission settings unavailable on this firmware")
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        activity as Context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Ts8VoiceTrigger"

        /**
         * Request code for `RECORD_AUDIO`.
         *
         * Distinct from every other code in the app (location is `0x7411`,
         * the launcher's own first-run set is its own) so a screen forwarding
         * two results cannot confuse them.
         */
        const val REQUEST_AUDIO = 0x7A11
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/service/MediaButtonReceiver.kt

BEGIN FILE
package com.ts8.launcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * Media buttons from the steering wheel and the head unit's own hardware keys.
 *
 * **Why a receiver and not the session's callback alone.** The session callback
 * is the modern path, but on this firmware the CANBUS app injects
 * `ACTION_MEDIA_BUTTON` broadcasts rather than going through the session, so
 * without this receiver the steering-wheel keys do nothing while the
 * notification buttons work.
 *
 * **Why `goAsync`.** `startService` on API 23 from a receiver is a synchronous
 * call; doing it on the main thread is correct but the receiver must not hold
 * the broadcast beyond its window either. The service start is fire-and-forget
 * and the service owns the actual work, so no `goAsync` is needed — the note
 * exists because the opposite is a tempting mistake here.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = mediaEvent(intent) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> MusicPlaybackService.ACTION_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MusicPlaybackService.ACTION_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MusicPlaybackService.ACTION_TOGGLE
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                MusicPlaybackService.ACTION_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND ->
                MusicPlaybackService.ACTION_PREV
            KeyEvent.KEYCODE_MEDIA_STOP -> MusicPlaybackService.ACTION_STOP
            // Head units commonly send headset hook as the single multifunction
            // key; treating it as play/pause is what the driver expects.
            KeyEvent.KEYCODE_HEADSETHOOK -> MusicPlaybackService.ACTION_TOGGLE
            else -> return
        }
        Log.d(TAG, "media key ${event.keyCode} -> $action")
        MusicPlaybackService.send(context, action)
    }

    /**
     * Reads the key event out of the broadcast.
     *
     * The deprecated `getParcelableExtra(name)` is correct and available on
     * every API this app ships to (min 23), so the newer typed overload is not
     * needed and its `TIRAMISU` constant would be a verified-but-dead branch.
     */
    private fun mediaEvent(intent: Intent): KeyEvent? {
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }

    companion object {
        private const val TAG = "Ts8MediaButton"
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/service/MusicPlaybackService.kt

BEGIN FILE
package com.ts8.launcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaSession
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.PlaybackListener
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.Track
import com.ts8.core.music.local.LocalMusicBackend
import com.ts8.launcher.R
import com.ts8.launcher.ui.MusicFullscreenActivity
import com.ts8.launcher.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The process-level owner of music playback.
 *
 * **Why a Service and not the Activity.** The spec requires music to keep
 * playing through screen changes, through the map opening, and through an
 * Activity recreate. A `MediaPlayer` owned by an Activity dies with it, so the
 * player, its queue and its resume position live here and the UI binds.
 *
 * **Why foreground.** On API 23 a background service is killed when the system
 * wants memory, and the head unit wants memory constantly while rendering the
 * map. A foreground service with an ongoing notification is the only way to
 * keep playing while the driver is on the navigation screen with the map busy.
 *
 * **MediaSession.** Uses `MediaSessionCompat`, whose framework backport works
 * on API 23 and keeps the media buttons and the (vendor) Bluetooth AVRCP
 * controls driving this player. A raw `android.media.session.MediaSession` is
 * used *in addition* on API 21+ so the transport controls reach the system's
 * media notification on firmware that ignores the compat shim.
 *
 * **USB removal.** The bridge between the scan and playback is explicit:
 * [onStorageRemoved] stops playback *before* the file vanishes, saves the
 * checkpoint, and reports [PlaybackError.SOURCE_UNAVAILABLE] — so pulling the
 * stick pauses safely instead of throwing inside `MediaPlayer`.
 */
class MusicPlaybackService : Service(), PlaybackListener {

    private val binder = LocalBinder()

    /** Set once [onCreate] has built the backend; null after [onDestroy]. */
    @Volatile
    private var backend: LocalMusicBackend? = null

    private lateinit var graph: AppGraph
    private var session: MediaSessionCompat? = null
    private var frameworkSession: MediaSession? = null

    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null

    @Volatile
    private var lastState: PlaybackState = PlaybackState()

    /** Cached so every `onStateChanged` does not re-read prefs. */
    private lateinit var prefs: SharedPreferences

    inner class LocalBinder : Binder() {
        /** The backend, or null when the service is shutting down. */
        fun backend(): LocalMusicBackend? = backend

        fun service(): MusicPlaybackService = this@MusicPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.require()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val created = LocalMusicBackend(this, graph.audioBridge)
        backend = created
        graph.attachMusicBackend(created)

        // §13: the player opens vector streams through the app's online gateway
        // rather than through any provider the local engine could name. Installed
        // unconditionally — an online track whose provider is unconfigured gets a
        // STREAM_UNAVAILABLE with the provider's own Vietnamese reason, which is
        // a better answer than a silent no-op. Local playback is untouched by
        // this: a MEDIA_STORE or USB source never consults the resolver.
        runCatching { created.player.setStreamResolver(graph.onlineMusic.streamResolver()) }
            .onFailure { Log.w(TAG, "stream resolver unavailable: ${it.message}") }

        // The player is the focus listener: a call, a nav prompt or another app
        // taking the stream must reach it, and nothing else in the process
        // should be reacting to focus.
        runCatching {
            graph.installFocusListener(created.player.asFocusListener())
        }

        created.player.addListener(this)
        created.library.markReady()

        createChannels()
        buildMediaSession(created, graph)
        restoreCheckpoint(created)

        startForegroundSafe()
        kickOffScan(created)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleIntent(intent)
        // START_STICKY so the system restarts the service after a low-memory
        // kill; the checkpoint is what makes that restart seamless.
        return START_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel()
        backend?.let { b ->
            runCatching { b.player.saveCheckpoint() }
            runCatching { b.player.removeListener(this) }
            runCatching { b.player.release() }
            graph.detachMusicBackend(b)
            runCatching { b.close() }
        }
        backend = null
        session?.release()
        session = null
        frameworkSession?.release()
        frameworkSession = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    // -- intents -----------------------------------------------------------

    /**
     * Handles the transport intents the notification, the media buttons and the
     * UI all send. One action vocabulary so the three callers cannot drift.
     */
    private fun handleIntent(intent: Intent?) {
        val b = backend ?: return
        when (intent?.action) {
            ACTION_PLAY -> b.player.play()
            ACTION_PAUSE -> b.player.pause()
            ACTION_TOGGLE -> b.player.togglePlayPause()
            ACTION_NEXT -> b.player.next()
            ACTION_PREV -> b.player.previous()
            ACTION_STOP -> {
                b.player.pause()
                b.player.saveCheckpoint()
                stopSelf()
            }
            ACTION_STORAGE_REMOVED -> onStorageRemoved(intent.getStringExtra(EXTRA_PATH))
        }
    }

    /**
     * Stops playback because a USB/SD volume went away.
     *
     * Deliberately *before* the path is unreadable: `MediaPlayer` throws
     * `IOException` from a background thread if its file vanishes mid-read, and
     * that is the crash the spec calls out. Pausing here means the file is
     * still open and the position is still known.
     */
    private fun onStorageRemoved(path: String?) {
        val b = backend ?: return
        val current = lastState.track
        val affectsCurrent = path == null || current == null ||
            current.source.startsWith(path) ||
            !File(current.source).exists()
        if (!affectsCurrent) return
        runCatching {
            b.player.pause()
            b.player.saveCheckpoint()
        }
        prefs.edit().putString(KEY_USB_REMOVED_AT, System.currentTimeMillis().toString()).apply()
        publish(PlaybackError.SOURCE_UNAVAILABLE)
    }

    // -- scan --------------------------------------------------------------

    /**
     * Runs an incremental scan off the main thread.
     *
     * The scanner itself decides what changed; this only schedules it and gets
     * out of the way. A scan while the map is drawing is the exact contention
     * the spec warns about, so it is also delayed slightly past cold start.
     */
    private fun kickOffScan(b: LocalMusicBackend) {
        scanJob?.cancel()
        scanJob = scope?.launch {
            runCatching {
                if (!b.scanner.canScan()) return@launch
                val result = b.scanner.scan(forceFullScan = false)
                Log.i(TAG, "scan: scanned=${result.scannedFiles} added=${result.added} " +
                    "updated=${result.updated} removed=${result.removed} " +
                    "unsupported=${result.unsupported} in ${result.durationMs} ms")
                b.library.markReady()
                if (result.changedAnything) {
                    // The queue may now point at tracks that no longer exist.
                    // Reloading the checkpoint by id drops the stale entries
                    // instead of playing silence.
                    publish(null)
                }
            }.onFailure { Log.w(TAG, "scan failed: ${it.message}") }
        }
    }

    // -- session -----------------------------------------------------------

    private fun buildMediaSession(b: LocalMusicBackend, graph: AppGraph) {
        val compat = MediaSessionCompat(this, "Ts8Music").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = b.player.play()
                override fun onPause() = b.player.pause()
                override fun onSkipToNext() = b.player.next()
                override fun onSkipToPrevious() = b.player.previous()
                override fun onSeekTo(pos: Long) = b.player.seekTo(pos)
                override fun onStop() = handleIntent(Intent(ACTION_STOP))
            })
            isActive = true
        }
        session = compat

        // The framework session is what makes the transport actions reach the
        // system's own media UI on this firmware. On API 23 it needs a
        // framework PlaybackState with the actions declared, and the compat
        // session does not populate that on its own.
        runCatching {
            val fs = MediaSession(this, "Ts8MusicFramework")
            fs.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = b.player.play()
                override fun onPause() = b.player.pause()
                override fun onSkipToNext() = b.player.next()
                override fun onSkipToPrevious() = b.player.previous()
                override fun onSeekTo(pos: Long) = b.player.seekTo(pos)
                override fun onStop() = handleIntent(Intent(ACTION_STOP))
            }, null)
            fs.isActive = true
            frameworkSession = fs
        }.onFailure { Log.w(TAG, "framework MediaSession unavailable: ${it.message}") }

        // Nothing here needs the graph beyond the audio bridge already installed
        // by onCreate; the parameter is kept so a later SyuAudioBridge can be
        // wired into the session's transport without changing this signature.
        @Suppress("UNUSED_EXPRESSION")
        graph
    }

    /**
     * Mirrors the player state into both sessions and the notification.
     *
     * Called from `onStateChanged` and after a transport action, so the
     * metadata and the notification never describe a different track than the
     * one playing.
     */
    private fun publish(error: PlaybackError?) {
        val b = backend ?: return
        val st = runCatching { b.player.state() }.getOrNull() ?: return
        if (error != null) lastState = st.copy(error = error) else lastState = st

        val track = lastState.track
        session?.let { s ->
            s.setMetadata(
                MediaMetadataCompatBuilder.build(track)
            )
            s.setPlaybackState(PlaybackStateCompatBuilder.build(lastState))
        }
        frameworkSession?.let { fs ->
            runCatching {
                fs.setMetadata(FrameworkMetadataBuilder.build(track))
                fs.setPlaybackState(FrameworkPlaybackStateBuilder.build(lastState, this))
            }
        }
        updateNotification(lastState)
    }

    override fun onStateChanged(state: PlaybackState) {
        lastState = state
        // The listener arrives on the main thread; publishing touches
        // MediaSession and Notification, both of which require it.
        publish(null)
    }

    override fun onError(error: PlaybackError, track: Track?) {
        Log.w(TAG, "playback error $error on ${track?.id}")
        lastState = lastState.copy(error = error, isPlaying = false)
        publish(error)
    }

    // -- checkpoint --------------------------------------------------------

    private fun restoreCheckpoint(b: LocalMusicBackend) {
        runCatching {
            val cp = b.checkpoint.load() ?: return
            if (cp.queueTrackIds.isEmpty()) return
            // Rebuild the queue from ids: a track that vanished (USB pulled
            // overnight) drops out rather than becoming a hole in the queue.
            val tracks = cp.queueTrackIds.mapNotNull { b.library.track(it) }
            if (tracks.isEmpty()) {
                b.checkpoint.clear()
                return
            }
            // Shuffle and repeat are restored before the queue so the first
            // `next()` after resume follows the same order as before.
            b.player.setShuffle(cp.shuffle)
            b.player.setRepeatMode(cp.repeatMode)
            val index = tracks.indexOfFirst { it.id == cp.trackId }.coerceAtLeast(0)
            b.player.setQueue(tracks, index, autoPlay = false)
            if (cp.positionMs > 0) b.player.seekTo(cp.positionMs)
        }
    }

    // -- notification ------------------------------------------------------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, "Phát nhạc", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun updateNotification(st: PlaybackState) {
        val n = buildNotification(st)
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(st: PlaybackState): Notification {
        val track = st.track
        val title = track?.title ?: getString(R.string.home_music_empty)
        val subtitle = track?.subtitle?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)

        val builder = NotificationCompat.Builder(this, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_nav_music)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(st.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

        if (track != null) {
            builder
                .addAction(
                    R.drawable.ic_prev, getString(R.string.music_previous),
                    serviceAction(ACTION_PREV, 1)
                )
                .addAction(
                    if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    getString(if (st.isPlaying) R.string.music_pause else R.string.music_play),
                    serviceAction(ACTION_TOGGLE, 2)
                )
                .addAction(
                    R.drawable.ic_next, getString(R.string.music_next),
                    serviceAction(ACTION_NEXT, 3)
                )
        }

        val style = androidx.media.app.NotificationCompat.MediaStyle()
        style.setShowActionsInCompactView(0, 1, 2)
        session?.sessionToken?.let { style.setMediaSession(it) }
        builder.setStyle(style)

        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MusicFullscreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, intent, pendingFlags())
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingFlags())
    }

    /**
     * `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`, with no version branch.
     *
     * `FLAG_IMMUTABLE` was added in API 23 — this app's `minSdk` — and the
     * value is a compile-time constant, so naming it here needs no guard.
     * (The platform only *requires* mutability to be declared when `targetSdk`
     * is 31+; this APK targets 30, so it is a deliberate choice rather than a
     * requirement.)
     */
    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /**
     * Enters the foreground, tolerating a platform that refuses.
     *
     * `startForeground` on API 23 starts a notification; on API 26+ it requires
     * a channel, which [createChannels] made. A refusal is not fatal — playback
     * still works, it is just killable — so it is logged rather than crashing
     * a launcher.
     */
    private fun startForegroundSafe() {
        val n = buildNotification(lastState)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure { Log.w(TAG, "startForeground refused: ${it.message}") }
    }

    companion object {
        private const val TAG = "Ts8MusicService"
        private const val CHANNEL_PLAYBACK = "ts8.playback"
        private const val NOTIFICATION_ID = 0x7853
        private const val PREFS = "ts8.music.service"
        private const val KEY_USB_REMOVED_AT = "usb_removed_at"

        const val ACTION_PLAY = "com.ts8.launcher.music.PLAY"
        const val ACTION_PAUSE = "com.ts8.launcher.music.PAUSE"
        const val ACTION_TOGGLE = "com.ts8.launcher.music.TOGGLE"
        const val ACTION_NEXT = "com.ts8.launcher.music.NEXT"
        const val ACTION_PREV = "com.ts8.launcher.music.PREV"
        const val ACTION_STOP = "com.ts8.launcher.music.STOP"
        const val ACTION_STORAGE_REMOVED = "com.ts8.launcher.music.STORAGE_REMOVED"
        const val EXTRA_PATH = "path"

        /** Starts the service without a transport action, so it can scan. */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, MusicPlaybackService::class.java))
            }
        }

        /** Sends a transport action to the running service. */
        fun send(context: Context, action: String) {
            runCatching {
                context.startService(
                    Intent(context, MusicPlaybackService::class.java).setAction(action)
                )
            }
        }

        /** Tells the service a volume was unmounted. */
        fun notifyStorageRemoved(context: Context, path: String?) {
            send(context, ACTION_STORAGE_REMOVED)
        }
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/service/SessionBuilders.kt

BEGIN FILE
package com.ts8.launcher.service

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.ts8.core.music.PlaybackState as MusicPlaybackState
import com.ts8.core.music.Track

/**
 * Translators between the engine's [MusicPlaybackState] and the two session
 * APIs the platform offers.
 *
 * **Why both.** `MediaSessionCompat` is the portable metadata container and is
 * what the vendor Bluetooth stack reads over AVRCP. The framework
 * `android.media.session.MediaSession` is what the system's own media
 * notification and lock-screen transport read on API 21+. Feeding only the
 * compat session leaves the notification's play/pause dead on some firmware,
 * and feeding only the framework session loses the AVRCP metadata. Both are
 * cheap to update from one state object, so both are kept in step here rather
 * than at every call site.
 *
 * **Why not `MediaSessionCompat` only.** androidx.media 1.7 drops the
 * `PlaybackStateCompat.ACTION_*` to `PlaybackStateCompat` mapping for API 23 in
 * a way that omits `ACTION_SEEK_TO`; declaring the actions explicitly keeps
 * seek available on the notification.
 */
internal object PlaybackStateCompatBuilder {

    /** Bitmask of everything this player can do while a track is loaded. */
    private const val ACTIONS =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

    fun build(st: MusicPlaybackState): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(if (st.hasTrack) ACTIONS else 0L)
            .setState(stateCode(st), st.positionMs, playbackSpeed(st))
            .build()

    internal fun stateCode(st: MusicPlaybackState): Int = when {
        st.error != null -> PlaybackStateCompat.STATE_ERROR
        st.isPlaying -> PlaybackStateCompat.STATE_PLAYING
        st.hasTrack -> PlaybackStateCompat.STATE_PAUSED
        else -> PlaybackStateCompat.STATE_NONE
    }

    /** 1.0 while playing, 0.0 otherwise: the session API treats any other value
     *  as a speed multiplier and the vendor head unit's progress bar wobbles. */
    internal fun playbackSpeed(st: MusicPlaybackState): Float = if (st.isPlaying) 1f else 0f
}

internal object MediaMetadataCompatBuilder {

    fun build(track: Track?): MediaMetadataCompat {
        val b = MediaMetadataCompat.Builder()
        if (track == null) {
            return b.putString(MediaMetadataCompat.METADATA_KEY_TITLE, "").build()
        }
        b.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
        b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist ?: "")
        b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album ?: "")
        b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
        b.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, track.trackNumber.toLong())
        b.putLong(MediaMetadataCompat.METADATA_KEY_YEAR, track.year.toLong())
        // The media id is the track id as a string: the session uses it to tell
        // "same track, new position" from "different track" when the vendor app
        // queries over AVRCP.
        b.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id.toString())
        return b.build()
    }
}

internal object FrameworkMetadataBuilder {

    fun build(track: Track?): MediaMetadata {
        val b = MediaMetadata.Builder()
        if (track == null) {
            return b.putString(MediaMetadata.METADATA_KEY_TITLE, "").build()
        }
        b.putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
        b.putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist ?: "")
        b.putString(MediaMetadata.METADATA_KEY_ALBUM, track.album ?: "")
        b.putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
        b.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, track.trackNumber.toLong())
        b.putLong(MediaMetadata.METADATA_KEY_YEAR, track.year.toLong())
        b.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.id.toString())
        return b.build()
    }
}

internal object FrameworkPlaybackStateBuilder {

    /**
     * Framework state. It needs a real [PendingIntent] for the "now playing"
     * tap, and `setActions` must be non-zero or the transport buttons render
     * disabled — which is the specific symptom this object exists to prevent.
     */
    fun build(st: MusicPlaybackState, context: Context): PlaybackState {
        val actions = if (!st.hasTrack) 0L else
            (PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP)

        val code = when {
            st.error != null -> PlaybackState.STATE_ERROR
            st.isPlaying -> PlaybackState.STATE_PLAYING
            st.hasTrack -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }

        return PlaybackState.Builder()
            .setActions(actions)
            .setState(code, st.positionMs, if (st.isPlaying) 1f else 0f)
            .build()
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/service/UsbMediaReceiver.kt

BEGIN FILE
package com.ts8.launcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.util.Log

/**
 * Tells the music service that a storage volume went away.
 *
 * **Why a manifest receiver for storage.** `MEDIA_UNMOUNTED`, `MEDIA_EJECT` and
 * `MEDIA_REMOVED` are delivered when the driver pulls the stick, and they are
 * the *only* advance warning before the files stop being readable. Handling
 * them here means playback pauses with its position saved, rather than
 * `MediaPlayer` throwing an `IOException` from a background thread — the exact
 * crash the spec forbids.
 *
 * **Why every storage action, not just unmount.** Vendor firmware on this head
 * unit sends `MEDIA_EJECT` on some sticks and `MEDIA_UNMOUNTED` on others, and
 * `MEDIA_REMOVED` without a prior unmount when the driver yanks it. All three
 * funnel into one call, which is idempotent on the service side.
 *
 * **USB device attach** is deliberately not used to start a scan: the volume is
 * not mounted yet at that point, so a scan would see nothing. The media actions
 * are the ones that fire after the filesystem is ready.
 *
 * **API 23 note.** The volume extra is read as `Parcelable` and reflected on,
 * never typed as `StorageVolume`. `StorageVolume` is API 24, so a typed read is
 * a `NoSuchMethodError` on this unit on the very path that is supposed to
 * prevent a crash — the one place where being precise about the class would
 * defeat the purpose of the code.
 */
class UsbMediaReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.data?.path ?: volumePathFrom(intent)
        when (intent.action) {
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL -> {
                Log.i(TAG, "storage gone: ${intent.action} path=$path")
                MusicPlaybackService.notifyStorageRemoved(context, path)
            }
            Intent.ACTION_MEDIA_MOUNTED -> {
                // A stick was inserted: the incremental scanner picks up only
                // the new files, and the service is already running.
                Log.i(TAG, "storage mounted: $path")
                MusicPlaybackService.start(context)
            }
        }
    }

    /**
     * Extracts a volume's mount point without naming `StorageVolume`.
     *
     * `getPath()` is the method that exists on API 23 but is hidden;
     * `getDirectory()` is public but API 30+. Both are tried reflectively on
     * whatever `Parcelable` the intent carried, and a failure returns null —
     * which tells the service "a volume changed, re-check everything". That is
     * the safe answer: the service re-verifies each track's file instead of
     * trusting a path that may not have been read correctly.
     */
    private fun volumePathFrom(intent: Intent): String? {
        val volume: Parcelable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VOLUME, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_VOLUME)
        } ?: return null

        @Suppress("PrivateApi")
        return runCatching {
            val cls = volume.javaClass
            val legacy = runCatching { cls.getMethod("getPath") }.getOrNull()
            val modern = if (legacy == null) {
                runCatching { cls.getMethod("getDirectory") }.getOrNull()
            } else {
                null
            }
            val raw = (legacy ?: modern)?.invoke(volume)
            // `getDirectory()` returns a File; `getPath()` returns a String.
            (raw as? String) ?: (raw as? java.io.File)?.absolutePath
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Ts8UsbMedia"
        private const val EXTRA_VOLUME = "android.os.storage.extra.STORAGE_VOLUME"
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/AlbumArtLoader.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Album art for list rows.
 *
 * **Why not the platform's own art loader.** The launcher must not depend on
 * Glide or Picasso: both pull a request pipeline and a thread pool the head unit
 * does not need, and the only images this app ever shows are album covers
 * addressed by album id. A single background thread with a counted LRU is the
 * whole requirement.
 *
 * **Why the decode is bounded.** The library can hold thousands of rows and the
 * driver scrolls fast. Decoding every requested cover at full resolution would
 * allocate a few megabytes per screenful on a device with 1 GB of RAM, so this
 * decodes with `inSampleSize` straight to the size the row shows and keeps only
 * the last [cacheKilobytes] of results.
 *
 * **Absence is answered, not guessed.** A track with no album id (every USB
 * file in this build) returns null, and the row draws its neutral icon. The
 * loader never invents a default cover.
 */
class AlbumArtLoader(context: Context, private val targetPx: Int) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-album-art").apply { isDaemon = true }
    }

    private val cache = object : LruCache<Long, Bitmap>(cacheKilobytes) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    /** In-flight keys, so a fast scroll does not queue the same cover twice. */
    private val pending = HashSet<Long>()

    /**
     * Returns a cached cover immediately, or null and starts a decode.
     *
     * The callback always runs on the main thread; [onLoaded] is skipped when
     * the image is already cached, so callers must set the cached one first.
     */
    fun load(albumId: Long?, onLoaded: (Long, Bitmap) -> Unit): Bitmap? {
        if (albumId == null || albumId <= 0L) return null
        cache.get(albumId)?.let { return it }
        synchronized(pending) {
            if (!pending.add(albumId)) return null
        }
        executor.execute {
            val bitmap = decode(albumId)
            main.post {
                synchronized(pending) { pending.remove(albumId) }
                if (bitmap != null) {
                    cache.put(albumId, bitmap)
                    onLoaded(albumId, bitmap)
                }
            }
        }
        return null
    }

    private fun decode(albumId: Long): Bitmap? {
        val uri: Uri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
        return runCatching {
            // Pass one: measure without allocating pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            var width = bounds.outWidth
            var height = bounds.outHeight
            while (width / 2 >= targetPx && height / 2 >= targetPx) {
                width /= 2
                height /= 2
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            // Pass two: decode at the row's size.
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    fun clear() {
        cache.evictAll()
    }

    /** Called when the owning screen goes away. */
    fun shutdown() {
        cache.evictAll()
        executor.shutdownNow()
    }

    companion object {
        /**
         * Deprecated at API 29 in favour of `ContentResolver.loadThumbnail`.
         * This app runs on API 23, where this endpoint is the only route, and
         * the deprecation is a compile-time warning rather than a runtime risk.
         */
        @Suppress("DEPRECATION")
        private val ALBUM_ART_URI: Uri =
            Uri.parse("content://media/external/audio/albumart")

        /** 4 MB: roughly 25 covers at row size, well inside a 1 GB device. */
        private const val cacheKilobytes = 4 * 1024
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/AppCatalog.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The list of apps this launcher may open.
 *
 * **Read once, on a background thread.** `queryIntentActivities` on a firmware
 * with 40 installed packages takes a few tens of milliseconds and touches
 * PackageManager, which holds a lock other apps contend for. Doing it in
 * `onCreate` on the main thread is how a launcher gets a slow first frame.
 *
 * **Icons are decoded lazily, per tile.** Loading 40 drawables up front costs
 * several megabytes on a device that may have 1 GB, and the grid shows only one
 * screenful. [iconFor] is called from the adapter when a tile is bound.
 *
 * **The launcher itself is filtered out.** Tapping our own tile would push a
 * second copy of the app drawer on top of the first. Every other packaged app
 * with a MAIN/LAUNCHER activity is shown, including vendor apps the design
 * names (Radio, EQ, Car Info) when the firmware ships them.
 */
class AppCatalog(private val context: Context) {

    /** One launchable app. */
    data class AppEntry(
        val packageName: String,
        val activityName: String,
        /** Label as the firmware declares it, in the current locale. */
        val label: String,
        /** Sort key: the label without diacritics, lowercased. */
        val sortKey: String
    ) {
        /** Snapshot of the activity in [packageName], for a stable compare. */
        val key: String get() = "$packageName/$activityName"
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-apps").apply { isDaemon = true }
    }

    private val pm: PackageManager get() = appContext.packageManager

    /** Cached result, so returning to the drawer does not re-query. */
    @Volatile
    private var cached: List<AppEntry>? = null

    /**
     * Loads the list, calling back on the main thread.
     *
     * @param force re-read from PackageManager instead of the cache; used after
     *        an install event, which this build does not observe yet.
     */
    fun load(force: Boolean = false, onLoaded: (List<AppEntry>) -> Unit) {
        cached?.let { if (!force) { onLoaded(it); return } }
        loader.execute {
            val list = query()
            cached = list
            main.post { onLoaded(list) }
        }
    }

    private fun query(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())

        val collator = Collator.getInstance(Locale("vi", "VN")).apply {
            // A Vietnamese collator sorts "Đ" next to "D" and puts diacritics
            // where a driver expects them; plain code-point order scatters it.
            strength = Collator.PRIMARY
        }

        val entries = resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val pkg = activity.packageName ?: return@mapNotNull null
            val name = activity.name ?: return@mapNotNull null
            // Our own drawer is not an app the driver should open from itself.
            if (pkg == appContext.packageName) return@mapNotNull null
            val label = runCatching {
                info.loadLabel(pm)?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            AppEntry(
                packageName = pkg,
                activityName = name,
                label = label,
                sortKey = VietnameseText.fold(label)
            )
        }.distinctBy { it.key }

        return entries.sortedWith { a, b ->
            val byName = collator.compare(a.sortKey, b.sortKey)
            if (byName != 0) byName else a.key.compareTo(b.key)
        }
    }

    /** The app's own icon, or null when the package will not give one. */
    fun iconFor(entry: AppEntry): Drawable? = runCatching {
        pm.getActivityIcon(
            android.content.ComponentName(entry.packageName, entry.activityName)
        )
    }.getOrNull()

    /**
     * The installed app whose label folds to [spoken], or null.
     *
     * **Why synchronous here while [load] is not.** A voice command has already
     * spoken the driver's words and cannot return a "pending" outcome — the
     * router has no such state — so the answer has to be available when this
     * returns. The work is the same `queryIntentActivities` call [load] makes,
     * it is cached afterwards, and the caller runs it on the main thread inside
     * the executor's budget. Being synchronous is what makes "không tìm thấy
     * ứng dụng" true when it is said.
     *
     * **Exact folded match only.** A prefix match would resolve "nhạc" to
     * whichever of several music apps sorts first, which is a wrong app launched
     * confidently. Exact-only means a label the driver mispronounced produces a
     * failure they can hear and correct.
     */
    fun findByLabel(spoken: String): AppEntry? {
        val wanted = normalize(spoken)
        if (wanted.isEmpty()) return null
        val list = cached ?: query().also { cached = it }
        return list.firstOrNull { normalize(it.label) == wanted }
    }

    /**
     * Folding plus whitespace collapsing, for the comparison only.
     *
     * [VietnameseText.fold] is used unchanged for sorting, where keeping the
     * label's own spacing is harmless; matching needs the spoken form and the
     * label to agree on spacing, because the parser joins tokens with single
     * spaces while a label may carry a double space or a trailing one.
     */
    private fun normalize(text: String): String =
        VietnameseText.fold(text).replace(WHITESPACE, " ").trim()

    /**
     * Builds the intent that opens [entry].
     *
     * An explicit component first: it is the one that the launcher's own
     * resolution just verified, so it cannot open a chooser. The exception is a
     * package that shares a process alias — the explicit form still works
     * there, because the component is what the system resolved.
     */
    fun intentFor(entry: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun shutdown() {
        loader.shutdownNow()
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Lowercase, diacritic-free folding for sorting and filtering.
 *
 * Deliberately a small local helper rather than a dependency on the
 * `place-search` module's `VietnameseText`: the app layer should not reach into
 * a core module for a six-line function, and the two have different jobs —
 * that one folds for FTS matching, this one only for `Collator` ordering.
 */
internal object VietnameseText {

    private val folds = mapOf(
        'à' to 'a', 'á' to 'a', 'ạ' to 'a', 'ả' to 'a', 'ã' to 'a',
        'â' to 'a', 'ầ' to 'a', 'ấ' to 'a', 'ậ' to 'a', 'ẩ' to 'a', 'ẫ' to 'a',
        'ă' to 'a', 'ằ' to 'a', 'ắ' to 'a', 'ặ' to 'a', 'ẳ' to 'a', 'ẵ' to 'a',
        'è' to 'e', 'é' to 'e', 'ẹ' to 'e', 'ẻ' to 'e', 'ẽ' to 'e',
        'ê' to 'e', 'ề' to 'e', 'ế' to 'e', 'ệ' to 'e', 'ể' to 'e', 'ễ' to 'e',
        'ì' to 'i', 'í' to 'i', 'ị' to 'i', 'ỉ' to 'i', 'ĩ' to 'i',
        'ò' to 'o', 'ó' to 'o', 'ọ' to 'o', 'ỏ' to 'o', 'õ' to 'o',
        'ô' to 'o', 'ồ' to 'o', 'ố' to 'o', 'ộ' to 'o', 'ổ' to 'o', 'ỗ' to 'o',
        'ơ' to 'o', 'ờ' to 'o', 'ớ' to 'o', 'ợ' to 'o', 'ở' to 'o', 'ỡ' to 'o',
        'ù' to 'u', 'ú' to 'u', 'ụ' to 'u', 'ủ' to 'u', 'ũ' to 'u',
        'ư' to 'u', 'ừ' to 'u', 'ứ' to 'u', 'ự' to 'u', 'ử' to 'u', 'ữ' to 'u',
        'ỳ' to 'y', 'ý' to 'y', 'ỵ' to 'y', 'ỷ' to 'y', 'ỹ' to 'y',
        'đ' to 'd'
    )

    fun fold(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length)
        for (ch in lower) {
            out.append(folds[ch] ?: ch)
        }
        return out.toString()
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/AppDrawerActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ts8.launcher.R

/**
 * Apps — design state 5, the app drawer.
 *
 * **Every tile is a real installed app.** The list comes from
 * [AppCatalog], which resolves MAIN/LAUNCHER activities through
 * PackageManager; nothing is hard-coded, so the drawer on a firmware without a
 * Radio shows no Radio, and on one with vendor apps shows them. A tile that
 * opens nothing is the failure the spec names, so a tap that the system refuses
 * reports it rather than doing nothing.
 *
 * **Failures are answered, not silenced.** A launch that throws — a package the
 * firmware restricted, an activity that was disabled after the list was read —
 * shows a toast naming the failure. Launching is the one action here that can
 * legitimately be refused by the platform, and the driver deserves to know
 * their tap was received.
 */
class AppDrawerActivity : BaseScreenActivity() {

    override val screen: BottomNavView.Screen = BottomNavView.Screen.APPS

    private lateinit var catalog: AppCatalog
    private lateinit var grid: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var adapter: AppGridAdapter

    private val main = Handler(Looper.getMainLooper())

    /** Everything installed, before the filter. */
    private var allApps: List<AppCatalog.AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_drawer)
        bindNavigation()

        catalog = AppCatalog(this)
        grid = findViewById(R.id.appsGrid)
        emptyView = findViewById(R.id.appsEmpty)
        emptyText = findViewById(R.id.appsEmptyText)

        adapter = AppGridAdapter(catalog) { entry -> open(entry) }
        grid.layoutManager = GridLayoutManager(this, spanCount())
        grid.adapter = adapter
        // Tiles have a fixed height, so the grid skips a measure per scroll.
        grid.setHasFixedSize(true)

        val search = findViewById<EditText>(R.id.appsSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        catalog.load { apps ->
            allApps = apps
            applyFilter("")
        }
    }

    override fun onDestroy() {
        catalog.shutdown()
        super.onDestroy()
    }

    /**
     * Columns from the available width rather than a fixed count.
     *
     * The acceptance criterion is "at least 1024×600"; a fixed 6-column grid
     * that is right at 1024 turns into fat tiles on a wider panel, and one that
     * is right at 1280 leaves 5 columns of dead space at 1024. Dividing by a
     * target tile width keeps the density constant at both.
     */
    private fun spanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        if (widthDp <= 0) return DEFAULT_COLUMNS
        val columns = widthDp / TARGET_TILE_DP
        return columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    /**
     * Filters on the folded label.
     *
     * Folding both sides is what makes typing `camera` find `Camera` and typing
     * `dien thoai` find `Điện thoại` — the driver's keyboard on this unit may
     * not offer Vietnamese input at all, so a diacritic-sensitive match would
     * make half the list unreachable.
     */
    private fun applyFilter(rawQuery: String) {
        val query = VietnameseText.fold(rawQuery.trim())
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter { entry ->
                entry.sortKey.contains(query) ||
                    VietnameseText.fold(entry.packageName).contains(query)
            }
        }
        adapter.submit(filtered)

        if (filtered.isEmpty()) {
            grid.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            // "No results" and "no apps at all" are different problems, and the
            // driver can act on only one of them.
            emptyText.setText(
                if (allApps.isEmpty()) R.string.apps_empty else R.string.search_no_results
            )
        } else {
            grid.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    /**
     * Opens an app.
     *
     * The explicit component intent is built by [AppCatalog]; it is wrapped
     * because a firmware can refuse it after the fact (a disabled activity, a
     * package being updated). A `SecurityException` here means the system will
     * not let this launcher start that activity, which is a real answer the
     * driver should see rather than a dead tap.
     */
    private fun open(entry: AppCatalog.AppEntry) {
        val result = runCatching { startActivity(catalog.intentFor(entry)) }
        result.onFailure { error ->
            val message = when (error) {
                is SecurityException -> getString(R.string.apps_permission_denied)
                else -> getString(R.string.apps_open_failed)
            }
            Toast.makeText(this, "$message\n${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // -- adapter -------------------------------------------------------------

    private inner class AppGridAdapter(
        private val catalog: AppCatalog,
        private val onClick: (AppCatalog.AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppGridViewHolder>() {

        private var items: List<AppCatalog.AppEntry> = emptyList()

        fun submit(next: List<AppCatalog.AppEntry>) {
            items = next
            // The list changes wholesale on a filter keystroke; a full rebind is
            // cheaper than a diff over dozens of unchanged tiles and cannot be
            // wrong about which tile moved.
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppGridViewHolder =
            AppGridViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.view_app_tile, parent, false)
            )

        override fun onBindViewHolder(holder: AppGridViewHolder, position: Int) {
            val entry = items[position]
            holder.bind(entry, catalog)
            holder.itemView.setOnClickListener { onClick(entry) }
        }
    }

    private class AppGridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val label: TextView = view.findViewById(R.id.appLabel)

        fun bind(entry: AppCatalog.AppEntry, catalog: AppCatalog) {
            label.text = entry.label
            val drawable = catalog.iconFor(entry)
            if (drawable == null) {
                // A package that gives no icon still gets a tile: a blank square
                // is the honest rendering, and the label still identifies it.
                icon.setImageDrawable(null)
                icon.setBackgroundResource(R.drawable.bg_card_raised)
            } else {
                icon.setBackground(null)
                icon.setImageDrawable(drawable)
            }
        }
    }

    companion object {
        /** Roughly the width the design gives one tile, including its gaps. */
        private const val TARGET_TILE_DP = 150
        private const val MIN_COLUMNS = 4
        private const val MAX_COLUMNS = 8
        private const val DEFAULT_COLUMNS = 6
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppStateMachine

/**
 * Shared behaviour for every launcher screen.
 *
 * **Why a base class rather than a shared layout includes and copy-paste.** The
 * seven screens differ entirely in their content but agree on four things: the
 * status row at the top, the bottom bar at the bottom, the landscape/immersive
 * window treatment, and what the bottom bar does when tapped. Putting those
 * here means a screen cannot accidentally ship with two nav bars or none, which
 * is the failure the spec's "bottom navigation is the only navigation" rule is
 * guarding against.
 *
 * **Back handling.** A launcher has no back stack to pop. Pressing back on any
 * screen goes to Home, and back on Home does nothing — it must not exit to a
 * black screen on a head unit with no system UI behind it.
 *
 * **Voice Instant's overlay lives here (§11).** Every launcher screen can
 * receive a voice command — the driver says "dẫn đường tới Cầu Rồng" from Home
 * and the map comes forward mid-interaction — so the bar belongs to the base
 * class rather than to the screens that happen to draw a microphone. It is a
 * child of the content root, added in [onCreate] after the subclass set its
 * layout, and it takes Back only while a voice interaction is in flight
 * ([VoiceOverlayHost.handleBack]); otherwise Back behaves exactly as before.
 */
abstract class BaseScreenActivity : AppCompatActivity() {

    /** Which nav item this screen highlights. */
    protected abstract val screen: BottomNavView.Screen

    /**
     * The screen's Voice Instant surface.
     *
     * Subclasses that want their own microphone button call [startVoice]; a
     * screen that does not draw one still gets the overlay when a command
     * arrives from elsewhere, because the host is installed for everyone.
     */
    protected val voice: VoiceOverlayHost by lazy { VoiceOverlayHost(this) }

    private var nav: BottomNavView? = null

    /**
     * The primary state this screen represents in the §16 machine, or null for
     * a screen that is not one of the ten.
     *
     * Derived from [screen] rather than declared per subclass: every tab screen
     * maps one-to-one onto a state, and the screens opened *over* another
     * override this to name the state they are. Settings and Map Management
     * return null deliberately — they are reached from Quick Settings, they
     * are not in §16's enumeration, and a launcher settings page is not a
     * destination the state machine should claim the driver arrived at.
     */
    protected open val primaryState: AppStateMachine.State?
        get() = when (screen) {
            BottomNavView.Screen.HOME -> AppStateMachine.State.HOME
            BottomNavView.Screen.MAP -> AppStateMachine.State.MAP_BROWSE
            BottomNavView.Screen.MUSIC -> AppStateMachine.State.MUSIC_EXPANDED
            BottomNavView.Screen.APPS -> AppStateMachine.State.APP_DRAWER
            BottomNavView.Screen.NONE -> null
        }

    /** The process-wide §16 machine; null only if the graph is not installed. */
    protected val uiState: AppStateMachine?
        get() = runCatching { AppGraph.require().uiState }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §16's "process recreation phải restore state an toàn", done here
        // because every screen passes through this method and the first one to
        // arrive after a cold start is the one that owns the restore — see
        // [AppStateMachine.restoreAfterProcessDeath], which is one-shot.
        //
        // What it buys, precisely, because it is easy to overclaim: the machine
        // starts from the stored state and *degraded* to something that can be
        // true again (a SEARCH with no query and a preview whose destination
        // died with the old process both become MAP_BROWSE), instead of
        // claiming HOME and then contradicting itself on the first resume. No
        // screen is relaunched from it: launching an Activity from another
        // Activity's `onCreate` is exactly the loop this rule is guarding
        // against, and the head unit's own launcher role means the process is
        // rebuilt by Home anyway.
        runCatching { AppGraph.require().uiState.restoreAfterProcessDeath() }
        // The launcher is the only chrome on this display: no title bar, no
        // status bar. Android 6 has no window inset APIs that matter here.
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN
    }

    /**
     * Installs the voice bar the moment the screen's layout exists.
     *
     * **Why here and not in [onCreate].** `setContentView` clears
     * `android.R.id.content` before inflating, so a bar added before the
     * subclass set its layout would be removed by it; and adding it afterwards
     * would need every one of the twelve screens to remember a call. The
     * platform delivers `onContentChanged()` right after inflation (it is the
     * `Window.Callback` notification that the content view was replaced), which
     * is the single point that is both after the layout and independent of the
     * screen.
     */
    override fun onContentChanged() {
        super.onContentChanged()
        voice.install()
    }

    /** Called after the content view is set, to wire the bar. */
    protected fun bindNavigation() {
        val bar = findViewById<BottomNavView?>(R.id.bottomNav)
        nav = bar
        if (bar == null) return
        bar.setSelectedScreen(screen)
        bar.onNavigate = { target -> navigateTo(target) }
    }

    /**
     * Opens the microphone.
     *
     * The one entry point a screen's microphone affordance needs; the
     * permission request, the denial path and the recogniser session all live
     * behind it so no screen can get them differently.
     */
    protected fun startVoice(): Boolean = voice.trigger()

    override fun onStart() {
        super.onStart()
        voice.attach()
    }

    override fun onStop() {
        // Detaches the rendering only. A command in flight — the driver said
        // "mở bản đồ" and the map is coming forward — must survive this screen
        // stopping, which is why the controller lives in `AppGraph`.
        voice.detach()
        super.onStop()
    }

    override fun onDestroy() {
        voice.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        voice.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onResume() {
        super.onResume()
        // §16: the machine follows the screen that is actually in front. Done
        // in `onResume` rather than `onCreate` so returning to a screen that
        // was left behind — REORDER_TO_FRONT reuses the instance — re-asserts
        // it, and so a refused transition is logged rather than silently
        // diverging.
        uiState?.let { machine -> primaryState?.let { machine.moveTo(it) } }
        nav?.setSelectedScreen(screen)
        nav?.refreshVolume()
        findViewById<TextView?>(R.id.clockText)?.text = ClockFormat.time()
        findViewById<TextView?>(R.id.dateText)?.text = ClockFormat.date()
    }

    /**
     * Switches screen.
     *
     * A screen that is already showing is not re-launched: on a head unit,
     * tapping "Trang chủ" while on Home must not restart the map's GL surface
     * and cost two seconds of black.
     */
    private fun navigateTo(target: BottomNavView.Screen) {
        if (target == screen) return
        val next = when (target) {
            BottomNavView.Screen.HOME -> HomeActivity::class.java
            BottomNavView.Screen.MAP -> MapActivity::class.java
            BottomNavView.Screen.MUSIC -> MusicFullscreenActivity::class.java
            BottomNavView.Screen.APPS -> AppDrawerActivity::class.java
            BottomNavView.Screen.NONE -> return
        }
        runCatching {
            startActivity(
                Intent(this, next).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            // REORDER_TO_FRONT reuses an existing instance; the standard
            // activity transition would slide the map in from the side, which
            // reads as a modal sheet. Launcher screens cross-fade, unless §17's
            // reduced-animation / low-performance rule says otherwise.
            ScreenTransition.apply(this)
        }
    }

    /**
     * Intercepts back and routes it through [onBack].
     *
     * `super.onBackPressed()` is deliberately **not** called. Its AppCompat
     * body finishes the activity, which is precisely what a launcher screen
     * must not do: the driver would land on an empty display. The subclasses'
     * [onBack] decide instead — a tab screen goes Home, Home ignores it, and a
     * screen opened over another (search, quick settings) finishes itself.
     * Lint's `MissingSuperCall` is a heuristic for the ordinary case, where an
     * override that skips the super call loses the platform's default; here the
     * default is the bug.
     *
     * **A running voice interaction owns Back** (§17: "Back đóng overlay
     * trước"). It is checked first and consumes the press, so a driver who
     * started a command by mistake presses Back once and the microphone stops
     * instead of a screen closing underneath a live recogniser.
     */
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        if (voice.handleBack()) return
        // §16's "Back đóng overlay trước, sau đó quay về state hợp lý": an
        // overlay the machine knows about is closed before any screen-level
        // Back runs, so a Back aimed at a progress panel or a dialog cannot
        // close the screen underneath it. `VoiceOverlayHost` pops its own entry
        // as part of `handleBack`, so the two do not double-pop.
        val closed = uiState?.popTopOverlay() ?: false
        if (closed) {
            // The overlay is gone; whatever leaving it means *for this screen*
            // is the screen's business. Ignoring the press is right for a
            // dialog the driver dismissed, and wrong for an import panel whose
            // screen has nothing left to show.
            onOverlayDismissed()
            return
        }
        onBack()
    }

    /**
     * Called when a §16 overlay was closed by Back.
     *
     * The default does nothing, which is the correct answer for the voice bar
     * and for a dialog: the screen behind them was never gone. Screens that
     * pushed an overlay representing *their own* long-running work override
     * this to leave as well.
     */
    protected open fun onOverlayDismissed() = Unit

    /**
     * Shows [dialog] and keeps §16's ERROR_DIALOG overlay in step with it.
     *
     * **Why a dialog is an overlay and not a state.** It is drawn over whatever
     * screen is current, it takes Back, and dismissing it must not close that
     * screen — the exact three properties §16 lists for an overlay. Tracking it
     * here is what makes the rule true for dialogs too, instead of only for the
     * ones the machine happens to know about.
     *
     * **Why the pop is on dismiss, not on the button.** A dialog dismissed by a
     * tap outside, by Back or by the platform's own cancel path leaves the same
     * way, and only `setOnDismissListener` sees all three. Popping from the
     * positive button would leave the overlay stacked over a dialog that is
     * already gone, and the next Back would then be swallowed by a phantom.
     */
    protected fun showTrackedDialog(dialog: android.app.AlertDialog): android.app.AlertDialog {
        val machine = uiState
        machine?.pushOverlay(AppStateMachine.Overlay.ERROR_DIALOG)
        dialog.setOnDismissListener {
            machine?.popOverlay(AppStateMachine.Overlay.ERROR_DIALOG)
        }
        return dialog
    }

    /**
     * Where back goes. Overridable so a screen opened *over* another — search,
     * which can be reached from Home or from the map — can return to whichever
     * one launched it instead of jumping to Home.
     *
     * The default is the launcher rule: a tab screen's back goes Home, and Home
     * ignores it.
     */
    protected open fun onBack() {
        if (screen == BottomNavView.Screen.HOME) {
            // Deliberately does nothing: a Home app that exits leaves the
            // driver on a black or stock-launcher screen with no way back.
            return
        }
        startActivity(
            Intent(this, HomeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ts8.core.audio.AudioResult
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph

/**
 * The one navigation bar in the app.
 *
 * **Why it is a custom view rather than a fragment.** Every screen needs the
 * identical bar in the identical position, and a fragment would let one screen
 * drift (a screen that forgot to add it, a screen that reordered the items).
 * As a view inflated into each screen's layout, the bar is part of the screen
 * and cannot be omitted by omission.
 *
 * **The Phone item is not a screen.** On a head unit, tapping "Điện thoại" is a
 * request to place a call, and the design's phone icon means exactly that. So
 * this item resolves a dialer intent instead of pushing a screen — and when the
 * device has no dialer, it opens the app drawer filtered to what is installed
 * rather than failing silently, which is the spec's "graceful when an app is
 * missing" requirement.
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Which screen is showing, so the bar can render the selected item. */
    enum class Screen { HOME, MAP, MUSIC, APPS, NONE }

    var onNavigate: ((Screen) -> Unit)? = null

    private val items: Map<Screen, LinearLayout>
    private val icons: Map<Screen, ImageView>
    private val labels: Map<Screen, TextView>

    private val volumeLabel: TextView
    private var selected: Screen = Screen.NONE

    init {
        LayoutInflater.from(context).inflate(R.layout.view_bottom_nav, this, true)

        items = mapOf(
            Screen.HOME to findViewById(R.id.navHome),
            Screen.MAP to findViewById(R.id.navMap),
            Screen.MUSIC to findViewById(R.id.navMusic),
            Screen.APPS to findViewById(R.id.navApps)
        )
        icons = mapOf(
            Screen.HOME to findViewById(R.id.navHomeIcon),
            Screen.MAP to findViewById(R.id.navMapIcon),
            Screen.MUSIC to findViewById(R.id.navMusicIcon),
            Screen.APPS to findViewById(R.id.navAppsIcon)
        )
        labels = mapOf(
            Screen.HOME to findViewById(R.id.navHomeLabel),
            Screen.MAP to findViewById(R.id.navMapLabel),
            Screen.MUSIC to findViewById(R.id.navMusicLabel),
            Screen.APPS to findViewById(R.id.navAppsLabel)
        )
        volumeLabel = findViewById(R.id.volLabel)

        items.forEach { (screen, item) ->
            item.setOnClickListener { select(screen) }
        }
        findViewById<LinearLayout>(R.id.navPhone).setOnClickListener { openDialer() }

        findViewById<View>(R.id.volDown).setOnClickListener { stepVolume(-1) }
        findViewById<View>(R.id.volUp).setOnClickListener { stepVolume(+1) }

        refreshVolume()
    }

    /** Highlights [screen] and leaves the others muted. */
    fun setSelectedScreen(screen: Screen) {
        if (selected == screen) return
        selected = screen
        items.forEach { (s, item) ->
            item.isSelected = s == screen
            // The label colour is a selector on `state_selected`, so setting it
            // on the item propagates without touching the child views.
            labels[s]?.isSelected = s == screen
        }
    }

    private fun select(screen: Screen) {
        setSelectedScreen(screen)
        onNavigate?.invoke(screen)
    }

    // -- phone -------------------------------------------------------------

    /**
     * Opens the platform dialer.
     *
     * Two fallbacks, in order: the dial pad, then the app drawer. A head unit
     * whose vendor firmware stripped `com.android.dialer` still has to let the
     * driver reach whatever it uses to make calls, so the drawer is a truthful
     * answer rather than an error toast.
     */
    private fun openDialer() {
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (dial.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(dial) }
                .onFailure { openDrawerInstead() }
            return
        }
        openDrawerInstead()
    }

    private fun openDrawerInstead() {
        val opened = runCatching {
            context.startActivity(
                Intent(context, AppDrawerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        if (opened.isFailure) {
            // Neither a dialer nor our own drawer: say so instead of a dead tap.
            Toast.makeText(context, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // -- volume ------------------------------------------------------------

    /**
     * Steps the media stream and shows the new level.
     *
     * Through [com.ts8.core.audio.VehicleAudioBridge] rather than
     * `AudioManager` directly, so that a later firmware bridge which routes
     * volume through the MCU changes one implementation instead of every
     * control.
     */
    private fun stepVolume(delta: Int) {
        val bridge = AppGraph.current?.audioBridge ?: return
        val current = bridge.mediaVolume()
        val max = bridge.maxMediaVolume().coerceAtLeast(1)
        val target = (current + delta).coerceIn(0, max)
        if (target == current) return
        when (val r = bridge.setMediaVolume(target)) {
            is AudioResult.Success, is AudioResult.Denied -> refreshVolume()
            is AudioResult.Unsupported, is AudioResult.Failed ->
                Toast.makeText(context, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-reads the stream level; called on attach and after a step. */
    fun refreshVolume() {
        val bridge = AppGraph.current?.audioBridge ?: return
        volumeLabel.text = bridge.mediaVolume().toString()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshVolume()
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/ClockFormat.kt

BEGIN FILE
package com.ts8.launcher.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Time and distance formatting in the forms the design shows.
 *
 * Deliberately a plain object with no Android dependency beyond `Locale`, so
 * every formatter is unit-testable. The two rules that matter on a head unit:
 * the clock is 24-hour (the design shows `17:24`, not `5:24 PM`), and the date
 * is written in Vietnamese with the weekday spelled out.
 */
object ClockFormat {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ROOT)

    /** Weekday names as the design writes them: "Thứ 7, 23 Tháng 11". */
    private val weekdays = arrayOf(
        "CN", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7"
    )

    private val months = arrayOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"
    )

    fun time(at: Long = System.currentTimeMillis()): String = timeFmt.format(Date(at))

    fun date(at: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val weekday = weekdays[c.get(Calendar.DAY_OF_WEEK) - 1]
        val day = c.get(Calendar.DAY_OF_MONTH)
        val month = months[c.get(Calendar.MONTH)]
        return "$weekday, $day Tháng $month"
    }

    /** Distance in the units the design uses: metres under 1 km, else one decimal. */
    fun distance(meters: Double): String = when {
        meters < 0 -> "—"
        meters < 1000 -> "${meters.toInt()} m"
        // Vietnamese writes the decimal separator as a comma; `Locale.ROOT`
        // formats a dot, so the replacement is explicit rather than a locale
        // lookup that would follow the device's language setting.
        else -> String.format(Locale.ROOT, "%.1f km", meters / 1000.0).replace('.', ',')
    }

    /** Distance phrased for a maneuver: "300 m", "1,2 km". */
    fun maneuverDistance(meters: Double): String = when {
        meters < 0 -> "—"
        meters < 100 -> "${(meters / 10).toInt() * 10} m"
        meters < 1000 -> "${(meters / 50).toInt() * 50} m"
        // Vietnamese uses a comma for the decimal separator.
        else -> String.format(Locale.ROOT, "%.1f km", meters / 1000.0).replace('.', ',')
    }

    /** Duration for the ETA row: "12 phút", "1 giờ 05 phút". */
    fun duration(seconds: Int): String {
        if (seconds <= 0) return "—"
        val totalMinutes = (seconds + 59) / 60
        return when {
            totalMinutes < 60 -> "$totalMinutes phút"
            else -> {
                val h = totalMinutes / 60
                val m = totalMinutes % 60
                if (m == 0) "$h giờ" else String.format(Locale.ROOT, "%d giờ %02d phút", h, m)
            }
        }
    }

    /** Arrival clock time, computed from the remaining duration. */
    fun eta(nowMs: Long, remainingSeconds: Int): String =
        time(nowMs + remainingSeconds * 1000L)

    /** Playback position: "01:25". Hours are added only past an hour. */
    fun trackPosition(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /** Speed for the speed card: whole km/h, which is all a driver reads. */
    fun speed(mps: Float): String = (mps * 3.6f).toInt().coerceAtLeast(0).toString()
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.ts8.core.location.LocationFix
import com.ts8.core.map.CameraFollowMode
import com.ts8.core.map.CameraPosition
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.navigation.NavigationController
import com.ts8.core.speed.SpeedMonitor
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppSettings
import com.ts8.launcher.runtime.LocationRuntime
import com.ts8.launcher.runtime.MapHost
import com.ts8.launcher.runtime.MusicRuntime
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Home — design state 1, and the screen the head unit boots into.
 *
 * **What is real on this screen.** The map is the actual MapLibre renderer on
 * the actual local pack; the speed chip is the filtered speed from the GPS
 * pipeline; the music card is the playback service's own state; the connectivity
 * icons are read from the platform by [StatusBarView]. There is no demo data
 * anywhere: a missing pack shows a sentence, a missing GPS fix shows "đang tìm
 * tín hiệu", an empty library shows "chưa phát nhạc".
 *
 * **Why the speed-limit roundel stays hidden.** The design paints `60` in it.
 * This pack tags `maxspeed` on roughly 6 % of highway ways and the routing graph
 * derives its speed from the road class instead, so there is no per-road limit
 * to show. Printing the design's 60 unconditionally would be fabricating a
 * safety-relevant number; the view exists, is driven by a real lookup, and
 * hides itself when the lookup has no answer. See `KNOWN_LIMITATIONS.md`.
 *
 * **One GPS subscription for the process.** This screen acquires the
 * reference-counted [LocationRuntime] stream in `onStart` and releases in
 * `onStop`, so moving to the map tab does not re-register the receiver and throw
 * away the smoothing state — which is what stops the marker from jumping and
 * the compass from resetting on every screen change.
 */
class HomeActivity : BaseScreenActivity() {

    override val screen: BottomNavView.Screen = BottomNavView.Screen.HOME

    private lateinit var graph: AppGraph
    private lateinit var music: MusicRuntime
    private var mapHost: MapHost? = null

    private lateinit var speedValue: TextView
    private lateinit var speedUnit: TextView
    private lateinit var speedLimit: View
    private lateinit var speedLimitValue: TextView
    private lateinit var gpsHint: TextView
    private lateinit var mapPlaceholder: View
    private lateinit var mapPlaceholderText: TextView
    private lateinit var musicCard: MusicCardView
    private lateinit var navStrip: View
    private lateinit var navStripIcon: ImageView
    private lateinit var navStripText: TextView

    private val main = Handler(Looper.getMainLooper())

    /** Last fix, so a zoom tap or a recentre does not need a new sample. */
    private var lastFix: LocationFix? = null

    // -- location ------------------------------------------------------------

    private val locationListener = object : LocationRuntime.Listener {
        override fun onFix(fix: LocationFix) {
            lastFix = fix
            // The speed readout is **not** written here. It comes from
            // `graph.speed`, so Home, the map tab and the alert path render the
            // same number from the same filter. This listener only owns the
            // things that are Home's: the marker and the "no signal" hint.
            gpsHint.visibility = View.GONE
            mapHost?.withController { it.setVehicleMarker(fix.point, fix.bearingDeg ?: 0f) }
            applyAutoFollow(fix)
            // One re-read per accepted fix, which is the rate the strip's own
            // content changes at and the rate the navigator recomputes
            // progress at. Cheaper than a second listener on a controller whose
            // announcements belong to the map screen, and it stops refreshing
            // by itself the moment Home leaves (`onStop` drops this listener).
            refreshNavStrip()
        }

        override fun onGpsStatus(hasFix: Boolean, satellites: Int) {
            // No fix yet: say so rather than showing a speed of 0 as if it were
            // a measurement.
            if (!hasFix && lastFix == null) gpsHint.visibility = View.VISIBLE
        }
    }

    /**
     * Keeps the camera on the car when the driver asked for it.
     *
     * Home is not navigation, so this is the two settings the brief puts in
     * `Hiển thị`: `Tự bám theo xe` (the camera follows) and `Tự căn giữa` (it
     * only re-centres, without rotating). Both are off while the driver is
     * looking at a side street, and the return delay decides when that ends.
     */
    private fun applyAutoFollow(fix: LocationFix) {
        val settings = AppSettings(this)
        val suspended = System.currentTimeMillis() < followSuspendedUntil
        val wantsFollow = settings.autoFollow || settings.autoCentre
        if (!wantsFollow || suspended) return
        mapHost?.withController { controller ->
            val bearing = if (settings.autoFollow && !settings.autoCentre) {
                fix.bearingDeg ?: 0f
            } else {
                0f
            }
            controller.moveCamera(
                CameraPosition(fix.point, FOLLOW_ZOOM, bearing),
                animate = true
            )
        }
    }

    /**
     * Renders the shared speed state on the chip and the roundel.
     *
     * The roundel shows `--` when the pack has no limit for the road rather than
     * hiding: a hidden roundel cannot be told apart from "the feature is off",
     * and a fabricated number is what the brief forbids outright.
     */
    private val speedListener = SpeedMonitor.Listener { state ->
        SpeedViews.bind(
            chip = findViewById(R.id.speedChip),
            value = speedValue,
            unit = speedUnit,
            limitRoundel = speedLimit,
            limitValue = speedLimitValue,
            state = state
        )
    }

    // -- music ---------------------------------------------------------------

    private val musicObserver = object : MusicRuntime.Observer {
        override fun onPlaybackState(state: PlaybackState) {
            musicCard.bind(state)
            musicCard.isFavorite = state.track
                ?.let { runCatching { music.backend?.library?.isFavorite(it.id) }.getOrNull() }
                ?: false
        }

        override fun onBackendChanged(backend: com.ts8.core.music.MusicBackend?) {
            // Re-query the favourite flag once the library exists; the state
            // snapshot alone cannot answer it.
            onPlaybackState(music.state())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindNavigation()

        graph = AppGraph.require()
        music = MusicRuntime.get(this)

        speedValue = findViewById(R.id.speedValue)
        speedUnit = findViewById(R.id.speedUnit)
        speedLimit = findViewById(R.id.speedLimit)
        speedLimitValue = findViewById(R.id.speedLimitValue)
        gpsHint = findViewById(R.id.gpsHint)
        mapPlaceholder = findViewById(R.id.mapPlaceholder)
        mapPlaceholderText = findViewById(R.id.mapPlaceholderText)
        musicCard = findViewById(R.id.musicCard)
        navStrip = findViewById(R.id.navStrip)
        navStripIcon = findViewById(R.id.navStripIcon)
        navStripText = findViewById(R.id.navStripText)

        // Both readouts start as `--`: no sample yet and no limit resolved for
        // the road. [speedListener] replaces them the moment the pipeline has
        // something honest to show.
        SpeedViews.bind(
            chip = findViewById(R.id.speedChip),
            value = speedValue,
            unit = speedUnit,
            limitRoundel = speedLimit,
            limitValue = speedLimitValue,
            state = com.ts8.core.speed.SpeedUiState.EMPTY
        )
        wireMap(savedInstanceState)
        wireMusicCard()
        wireMapControls()
        wireSearchEntry()
        wireMapPlaceholderActions()
        wireNavStrip()

        ensurePermissions()
    }

    /**
     * The two ways out of "no map": import one, or look again.
     *
     * Both are real actions, not relabelled toasts — a driver whose pack is on a
     * USB stick has to be able to reach the import screen without knowing that
     * Settings exists.
     */
    private fun wireMapPlaceholderActions() {
        findViewById<View>(R.id.btnPlaceholderImport).setOnClickListener {
            startActivity(MapManagementActivity.intentTo(this))
            ScreenTransition.apply(this)
        }
        findViewById<View>(R.id.btnPlaceholderRetry).setOnClickListener {
            graph.invalidatePack()
            rebuildMap()
        }
    }

    // -- navigation-aware home (§16) -----------------------------------------

    /**
     * Shows the live instruction while guidance is running.
     *
     * **Why Home needs this at all.** §16 says the driver who presses Home
     * mid-route must get a *navigation-aware* Home, not a Home that pretends
     * nothing is happening. The map still draws, the music card still plays,
     * and one strip tells them the next turn — tapping it returns to guidance.
     *
     * **Where the data comes from.** [AppGraph.navigation] is asked, not
     * built: a driver on Home who never navigated must not pay for
     * memory-mapping the routing graph, which is why
     * [AppGraph.navigationStateIfBuilt] exists. Home therefore renders the
     * strip from the navigator's own [NavigationController.uiState] only when
     * a controller already exists, and leaves it hidden otherwise.
     *
     * **Why it re-reads on resume rather than sampling.** The navigator notifies
     * its listener from its own thread and the listener belongs to
     * `MapActivity`; subscribing from Home would be a second listener receiving
     * announcements it must not speak. A strip refreshed when Home comes
     * forward and on each accepted fix is enough for a 1 Hz instruction that
     * changes every few hundred metres.
     */
    private fun wireNavStrip() {
        navStrip.setOnClickListener {
            startActivity(
                Intent(this, MapActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            ScreenTransition.apply(this)
        }
        refreshNavStrip()
    }

    private fun refreshNavStrip() {
        val nav = graph.navigationIfBuilt()
        val ui = nav?.uiState()
        val progress = ui?.progress
        val running = ui != null &&
            (ui.state == com.ts8.core.navigation.NavigationState.NAVIGATING ||
                ui.state == com.ts8.core.navigation.NavigationState.REROUTING ||
                ui.state == com.ts8.core.navigation.NavigationState.ARRIVED)
        if (!running || progress == null) {
            navStrip.visibility = View.GONE
            return
        }

        val next = progress.nextManeuver
        navStripIcon.setImageResource(maneuverIcon(next))
        val instruction = next?.streetName?.takeIf { it.isNotBlank() }
            ?: next?.instruction?.takeIf { it.isNotBlank() }
            ?: getString(R.string.nav_no_destination)
        navStripText.text = if (ui.state == com.ts8.core.navigation.NavigationState.ARRIVED) {
            getString(R.string.nav_arrived)
        } else {
            NavigationController.formatDistance(progress.distanceToNextManeuverMeters) +
                " · " + instruction
        }
        navStrip.visibility = View.VISIBLE
    }

    /**
     * Refreshes the strip the moment the §16 machine moves, not only on a fix.
     *
     * **The case this exists for.** Guidance can *end* between two GPS samples —
     * the driver arrives and the navigator drops to IDLE — and on a head unit in
     * a car park the fix stream can stall for a minute at a time. A strip driven
     * only by `onFix` would then keep showing "200 m · rẽ phải" over a road the
     * driver already left, which reads as guidance that will not stop. The
     * machine's own transition is the authoritative moment, and it reaches Home
     * whether the receiver is delivering or not.
     *
     * Posted rather than called inline: the navigator notifies from its own
     * executor and `syncNavigation` runs on that hop, so a listener here could
     * be entered off the main thread and would then touch views off it.
     */
    private val stateListener = com.ts8.launcher.runtime.AppStateMachine.Listener { _, _, _ ->
        main.post { refreshNavStrip() }
    }

    /** The same icon vocabulary the navigation screen uses, so the two agree. */
    private fun maneuverIcon(maneuver: com.ts8.core.routing.Maneuver?): Int = when (maneuver?.type) {
        null -> R.drawable.ic_maneuver_straight
        com.ts8.core.routing.ManeuverType.ARRIVE -> R.drawable.ic_maneuver_arrive
        com.ts8.core.routing.ManeuverType.DEPART -> R.drawable.ic_maneuver_depart
        com.ts8.core.routing.ManeuverType.ROUNDABOUT -> R.drawable.ic_maneuver_roundabout
        com.ts8.core.routing.ManeuverType.CONTINUE -> R.drawable.ic_maneuver_straight
        else -> when (maneuver.direction) {
            com.ts8.core.routing.ManeuverDirection.LEFT -> R.drawable.ic_maneuver_left
            com.ts8.core.routing.ManeuverDirection.RIGHT -> R.drawable.ic_maneuver_right
            com.ts8.core.routing.ManeuverDirection.SLIGHT_LEFT -> R.drawable.ic_maneuver_slight_left
            com.ts8.core.routing.ManeuverDirection.SLIGHT_RIGHT -> R.drawable.ic_maneuver_slight_right
            com.ts8.core.routing.ManeuverDirection.SHARP_LEFT -> R.drawable.ic_maneuver_left
            com.ts8.core.routing.ManeuverDirection.SHARP_RIGHT -> R.drawable.ic_maneuver_right
            com.ts8.core.routing.ManeuverDirection.UTURN -> R.drawable.ic_maneuver_uturn
            else -> R.drawable.ic_maneuver_straight
        }
    }

    // -- map -----------------------------------------------------------------

    private fun wireMap(savedInstanceState: Bundle?) {
        val container = findViewById<FrameLayout>(R.id.mapHost)
        val host = MapHost(this, graph, container)
        mapHost = host
        host.create(savedInstanceState)

        val failure = host.failure
        if (failure != null) {
            // Real message, real cause. Never a drawn placeholder map.
            mapPlaceholder.visibility = View.VISIBLE
            mapPlaceholderText.text = failure
            return
        }
        mapPlaceholder.visibility = View.GONE

        host.withController { controller ->
            controller.setReadyListener(object : com.ts8.core.map.MapController.ReadyListener {
                override fun onReady() {
                    // Pack centre until the first fix arrives, so the screen is
                    // a map rather than a grey rectangle for the first seconds.
                    AppGraph.current?.pack()?.let { pack ->
                        pack.center?.let { centre ->
                            controller.moveCamera(
                                CameraPosition(centre, pack.metadata.defaultZoom),
                                animate = false
                            )
                        }
                    }
                    controller.setCameraFollow(CameraFollowMode.NONE)
                    lastFix?.let { it -> controller.setVehicleMarker(it.point, it.bearingDeg ?: 0f) }
                }

                override fun onFailed(reason: String) {
                    mapPlaceholder.visibility = View.VISIBLE
                    mapPlaceholderText.text = reason
                }
            })
        }
    }

    private fun wireMapControls() {
        findViewById<ImageButton>(R.id.btnZoomIn).setOnClickListener {
            noteManualPan()
            mapHost?.withController { zoomBy(it, +1) }
        }
        findViewById<ImageButton>(R.id.btnZoomOut).setOnClickListener {
            noteManualPan()
            mapHost?.withController { zoomBy(it, -1) }
        }
        findViewById<ImageButton>(R.id.btnRecenter).setOnClickListener {
            recentre()
        }
        findViewById<ImageButton>(R.id.btnLayers).setOnClickListener {
            toggleStyleMode()
        }
        applyControlVisibility()
    }

    /**
     * Hides the zoom and recentre buttons the driver switched off in `Hiển thị`.
     *
     * The layer button is not part of that group: it cycles day/night, which is
     * a safety-relevant control in a dark car and has no other always-visible
     * affordance on this screen.
     */
    private fun applyControlVisibility() {
        val settings = AppSettings(this)
        val zoom = if (settings.showZoomControls) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.btnZoomIn).visibility = zoom
        findViewById<ImageButton>(R.id.btnZoomOut).visibility = zoom
        findViewById<ImageButton>(R.id.btnRecenter).visibility =
            if (settings.showRecenter) View.VISIBLE else View.GONE
    }

    private fun zoomBy(controller: com.ts8.core.map.maplibre.MapLibreMapController, delta: Int) {
        val current = controller.getCameraPosition()
        val zoom = (current.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        controller.moveCamera(current.copy(zoom = zoom), animate = true)
    }

    /**
     * Returns the camera to the car, and re-arms follow.
     *
     * With no fix this does nothing rather than panning to a guess: a map that
     * recentres on the wrong place is worse than one that stays put.
     */
    private fun recentre() {
        val fix = lastFix ?: return
        mapHost?.withController { controller ->
            controller.setCameraFollow(CameraFollowMode.FOLLOW_BEARING)
            controller.moveCamera(
                CameraPosition(fix.point, RECENTRE_ZOOM, fix.bearingDeg ?: 0f),
                animate = true
            )
        }
        // An explicit recentre re-arms auto-follow even if a pan had suspended
        // it, which is what the driver is asking for by pressing this.
        followSuspendedUntil = 0L
    }

    /**
     * Remembers that the driver moved the map by hand, so auto-follow can back off.
     *
     * **Why a timestamp rather than a boolean.** The setting is "quay lại bám xe
     * sau N giây", not "tắt bám xe": the camera is supposed to come back on its
     * own after the driver finishes looking at a side street. `0` in the setting
     * means stay free until recentre, which is expressed by a deadline of
     * [Long.MAX_VALUE].
     */
    private fun noteManualPan() {
        val seconds = AppSettings(this).followReturnSeconds
        followSuspendedUntil =
            if (seconds <= 0) Long.MAX_VALUE else System.currentTimeMillis() + seconds * 1000L
    }

    /** When auto-follow may resume; `0` means it is armed. */
    private var followSuspendedUntil = 0L

    /**
     * Cycles Day → Night → Auto and says which it is.
     *
     * `AUTO` resolves from the local clock inside the controller, which is the
     * only signal available offline; the driver's manual choice always wins
     * until they cycle past it.
     *
     * **The choice is written to the settings store, not just to the
     * controller.** Quick Settings has a tile for the same setting, and two
     * controls for one value that each update only their own copy is how they
     * end up disagreeing — the driver picks "Ban đêm" here, opens Quick
     * Settings, and reads "Tự động".
     */
    private fun toggleStyleMode() {
        val settings = AppSettings(this)
        val next = settings.nextStyleMode()
        settings.mapStyleMode = next
        mapHost?.withController { controller -> controller.setStyleMode(next) }
    }

    /**
     * Re-applies the stored day/night mode.
     *
     * Runs in `onResume` because that is the only moment the controller is
     * guaranteed to exist: the map is built in `onCreate` but the style loads
     * asynchronously, and [MapLibreMapController.setStyleMode] records the
     * request when it is called early and builds the right palette when the
     * style arrives.
     */
    private fun applyStoredStyleMode() {
        val stored = AppSettings(this).mapStyleMode
        mapHost?.withController { controller -> controller.setStyleMode(stored) }
        // The display group is stored separately and changes on its own: the
        // driver can flip "Toà nhà 3D" without touching day/night. Pushing both
        // here is what makes a settings change visible on return, and the
        // controller skips the rebuild when nothing actually moved.
        mapHost?.refreshDisplayConfig()
        // `Tự bám theo xe` and the control toggles are launcher settings changed
        // from another Activity, so the screen that was left behind has to
        // re-read them; the style snapshot alone does not carry them.
        applyControlVisibility()
    }

    /**
     * Whether the roundel is shown at all.
     *
     * Kept as the single place a screen can force the roundel off, e.g. while
     * the map is showing a placeholder and there is no road to speak of.
     */
    @Suppress("unused")
    private fun hideSpeedLimit() {
        speedLimit.visibility = View.GONE
    }

    // -- music ---------------------------------------------------------------

    private fun wireMusicCard() {
        musicCard.onPlayPause = { music.toggle() }
        musicCard.onNext = { music.next() }
        musicCard.onPrevious = { music.previous() }
        musicCard.onToggleShuffle = {
            val current = music.state().shuffle
            music.setShuffle(!current)
        }
        musicCard.onCycleRepeat = {
            val next = music.state().repeatMode.next()
            music.setRepeatMode(next)
        }
        musicCard.onToggleFavorite = favorite@{
            // No implicit label is available here: the lambda is assigned to a
            // property, not passed to a function, so an early return needs the
            // explicit `favorite@` label above.
            val track = music.state().track ?: return@favorite
            val library = music.backend?.library ?: return@favorite
            main.post {
                val nowFavourite = !library.isFavorite(track.id)
                library.setFavorite(track.id, nowFavourite)
                musicCard.isFavorite = nowFavourite
            }
        }
        musicCard.onOpenQueue = { openMusic() }
    }

    private fun openMusic() {
        startActivity(
            Intent(this, MusicFullscreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun wireSearchEntry() {
        findViewById<View>(R.id.searchField).setOnClickListener {
            startActivity(
                Intent(this, SearchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            ScreenTransition.apply(this)
        }
        // The microphone is a child of the field, so its click does not reach
        // the field's listener; it opens Voice Instant instead of the keyboard.
        findViewById<ImageButton>(R.id.btnVoice).setOnClickListener {
            startVoice()
        }
    }

    // -- permissions ---------------------------------------------------------

    /**
     * Asks for GPS and storage on first run.
     *
     * On API 23 this is a runtime dialog with no "permanently denied" concept
     * until the user ticks "don't ask again". The launcher stays fully usable
     * when both are refused: the map still renders the pack, music shows the
     * empty state. It never blocks startup on a permission — a head unit that
     * refuses to show its home screen until a dialog is answered is a head unit
     * the driver cannot use.
     */
    private fun ensurePermissions() {
        val wanted = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (wanted.isEmpty()) return
        runCatching {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.isEmpty()) return

        val locationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val storageGranted = hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ||
            hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        // The runtime decides whether to start the receiver, so re-acquiring
        // here is what makes a just-granted permission take effect without a
        // relaunch.
        if (locationGranted && isStarted) {
            graph.location.release(locationListener)
            graph.location.acquire(locationListener)
        }

        // **The pack lookup must be retried, not just the GPS.** The first
        // discovery runs during onCreate, i.e. before this dialog is answered,
        // and on API 23 a directory the process may not stat reports as "not a
        // directory". Without dropping the memo and rebuilding the map, a
        // correctly installed pack stays "not found" until the app is killed.
        if (storageGranted) {
            graph.invalidatePack()
            rebuildMap()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Tears the map down and builds it again after the pack lookup changed. */
    private fun rebuildMap() {
        mapHost?.onDestroy()
        mapHost = null
        val container = findViewById<FrameLayout>(R.id.mapHost)
        container.removeAllViews()
        wireMap(savedInstanceState = null)
        // onStart was already delivered to this Activity; a MapView created
        // afterwards needs its own, or it stays stopped with a blank surface.
        if (isStarted) mapHost?.onStart()
    }

    // -- lifecycle -----------------------------------------------------------

    override fun onStart() {
        super.onStart()
        isStarted = true
        // Forwarded, and this line is load-bearing. MapHost passes it to
        // MapView.onStart, which is what resumes the GL thread. Home used to
        // omit it while still forwarding onResume/onPause/onStop, so after the
        // first tab switch the map surface was stopped and never started again
        // — a frozen last frame on the screen the unit boots into.
        mapHost?.onStart()
        graph.location.acquire(locationListener)
        graph.speed.acquire(speedListener)
        // Home is the screen the unit boots into and the one that stays up
        // while the car is parked, so it is the main reason the parking
        // detector runs at all. Passing no listener: Home draws nothing from
        // the decision — the detector opens the parking screen itself when the
        // evidence and the setting agree.
        graph.parking.acquire(null)
        music.attach(musicObserver)
        // Registered for exactly as long as this screen is visible: the strip is
        // the only thing Home draws from the §16 machine, and a listener left
        // registered behind a stopped screen would keep querying the navigator
        // for a strip nobody can see.
        uiState?.addListener(stateListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Without this the camera is lost on every rotation-equivalent config
        // change (locale, night mode, docking), and Home re-centres on the pack
        // instead of on the car.
        mapHost?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        mapHost?.onResume()
        // Quick Settings may have changed the day/night mode while this screen
        // was behind it. Applying the stored value here is what makes that
        // tile's change visible on return.
        applyStoredStyleMode()
        // Guidance may have started, ended or arrived while Home was behind the
        // map screen. Coming forward is exactly when the strip should catch up.
        refreshNavStrip()
        // The map's GL surface is destroyed and rebuilt between tabs; without
        // this the map stays frozen on the last frame it drew.
        mapHost?.withController { controller ->
            if (controller.isReady() && lastFix == null) {
                graph.pack()?.center?.let { centre ->
                    runCatching {
                        controller.moveCamera(
                            CameraPosition(centre, graph.pack()?.metadata?.defaultZoom ?: 12.0),
                            animate = false
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        mapHost?.onPause()
        super.onPause()
    }

    override fun onStop() {
        uiState?.removeListener(stateListener)
        music.detach(musicObserver)
        graph.parking.release(null)
        graph.speed.release(speedListener)
        graph.location.release(locationListener)
        isStarted = false
        mapHost?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapHost?.onDestroy()
        mapHost = null
        super.onDestroy()
    }

    /** Set in `onStart`, read by the permission callback. */
    private var isStarted = false

    companion object {
        private const val REQUEST_PERMISSIONS = 0x7410
        private const val MIN_ZOOM = 6.0
        private const val MAX_ZOOM = 18.0
        private const val RECENTRE_ZOOM = 17.0

        /** Zoom the home map settles at while auto-following the car. */
        private const val FOLLOW_ZOOM = 16.5
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/MapActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ts8.core.location.LocationFix
import com.ts8.core.map.CameraFollowMode
import com.ts8.core.map.CameraPosition
import com.ts8.core.map.GeoPoint
import com.ts8.core.map.MapController
import com.ts8.core.map.RouteGeometry
import com.ts8.core.map.maplibre.MapLibreMapController
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.navigation.Announcement
import com.ts8.core.navigation.NavigationController
import com.ts8.core.navigation.NavigationEndReason
import com.ts8.core.navigation.NavigationProgress
import com.ts8.core.navigation.NavigationState
import com.ts8.core.navigation.NavigationUiState
import com.ts8.core.routing.Maneuver
import com.ts8.core.routing.ManeuverDirection
import com.ts8.core.routing.ManeuverType
import com.ts8.core.routing.Route
import com.ts8.core.speed.SpeedMonitor
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppSettings
import com.ts8.launcher.runtime.LocationRuntime
import com.ts8.launcher.runtime.MapHost
import com.ts8.launcher.runtime.MusicRuntime

/**
 * Navigation — design states 2 and 3, and the only screen that owns guidance.
 *
 * **Everything on this screen comes from the navigator or the GPS pipeline.**
 * The maneuver distance and instruction are [NavigationProgress] fields, the
 * ETA row is derived from the same progress object, the speed is the filtered
 * fix, and the route line is the router's own geometry. When there is nothing
 * to show the card is hidden rather than filled with a sample instruction.
 *
 * **The speed-limit roundel is hidden.** The design paints `60` on it. This
 * pack tags `maxspeed` on about 6 % of highway ways and the routing graph
 * derives edge speed from road class instead, so there is no per-road limit to
 * read. The view is bound to a real lookup that this build cannot answer, so it
 * stays gone. Showing the design's number would be fabricating a safety value.
 *
 * **Guidance outlives this Activity's visibility.** The location subscription
 * and the navigator listener are taken in [onCreate] and released in
 * [onDestroy], not in `onStart`/`onStop`. Switching to the music screen must not
 * silence a turn instruction the driver is about to reach. The cost is that the
 * GPS receiver stays warm while this screen exists on the back stack, which on
 * a wired head unit is the correct trade.
 *
 * **Every navigator callback is hopped to the main thread.** The controller
 * runs route computation on its own executor and notifies from there, while
 * transitions the driver triggers arrive on the main thread. One hop at this
 * boundary is what keeps the rest of the file free of threading guesses.
 */
class MapActivity : BaseScreenActivity() {

    override val screen: BottomNavView.Screen = BottomNavView.Screen.MAP

    private lateinit var graph: AppGraph
    private lateinit var music: MusicRuntime

    private var mapHost: MapHost? = null
    private var navigator: NavigationController? = null

    private lateinit var mapPlaceholder: View
    private lateinit var mapPlaceholderText: TextView
    private lateinit var maneuverCard: View
    private lateinit var maneuverIcon: ImageView
    private lateinit var maneuverDistance: TextView
    private lateinit var maneuverInstruction: TextView
    private lateinit var maneuverThen: TextView
    private lateinit var etaStrip: TextView
    private lateinit var speedChip: View
    private lateinit var speedValue: TextView
    private lateinit var speedUnit: TextView
    private lateinit var speedLimit: View
    private lateinit var speedLimitValue: TextView
    private lateinit var speedCluster: View
    private lateinit var guidanceControls: View
    private lateinit var browseControls: View
    private lateinit var destinationCard: View
    private lateinit var destinationName: TextView
    private lateinit var destinationSummary: TextView
    private lateinit var destinationMessage: TextView
    private lateinit var stateBanner: TextView
    private lateinit var searchField: View
    private lateinit var musicCard: MusicCardView

    private val main = Handler(Looper.getMainLooper())

    /** Last fix, so a destination chosen before the first fix can wait for one. */
    private var lastFix: LocationFix? = null

    /** A destination picked in the search screen, waiting for a position. */
    private var pendingDestination: PendingDestination? = null

    /** Mirrors the controller, so the mute button can flip without a round trip. */
    private var muted = false
    private var overview = false

    private data class PendingDestination(val point: GeoPoint, val name: String)

    // -- location ------------------------------------------------------------

    private val locationListener = object : LocationRuntime.Listener {
        override fun onFix(fix: LocationFix) {
            lastFix = fix
            // The speed chip and roundel are driven by `graph.speed`, not by
            // this fix: the number must match Home's and the alert path's, and
            // this screen must not open its own filter state.
            val pending = pendingDestination
            if (pending != null) {
                pendingDestination = null
                // The driver picked a place before the receiver had a fix; now
                // there is one, so the route can start where the car actually is.
                navigator?.selectDestination(pending.point, pending.name, fix.point)
            }

            navigator?.onLocation(fix)

            mapHost?.withController { controller ->
                controller.onNavigationFix(fix.point, fix.bearingDeg ?: 0f)
            }
        }
    }

    /**
     * Renders the shared speed state.
     *
     * The chip is only visible while there is a number, matching the design's
     * behaviour on this screen (the speed card appears once navigation starts).
     */
    private val speedListener = SpeedMonitor.Listener { state ->
        val hasReading = state.speedKmh != null
        applySpeedState(state)
        if (!hasReading) speedChip.visibility = View.GONE
    }

    /**
     * Binds the speed state and keeps the wrapping cluster in step.
     *
     * `SpeedViews.bind` owns the visibility of the chip and the roundel
     * individually, because either can be off on its own. The cluster that
     * positions them is a plain container, so it would otherwise keep its
     * place in the layout as an empty, invisible box. Deriving its visibility
     * from the two children means there is still exactly one place that
     * decides what is shown.
     */
    private fun applySpeedState(state: com.ts8.core.speed.SpeedUiState) {
        SpeedViews.bind(
            chip = speedChip,
            value = speedValue,
            unit = speedUnit,
            limitRoundel = speedLimit,
            limitValue = speedLimitValue,
            state = state
        )
        syncSpeedCluster()
    }

    private fun syncSpeedCluster() {
        val any = speedChip.visibility == View.VISIBLE || speedLimit.visibility == View.VISIBLE
        speedCluster.visibility = if (any) View.VISIBLE else View.GONE
    }

    // -- navigation ----------------------------------------------------------

    /**
     * Forwards navigator events onto the main thread.
     *
     * The controller notifies from whichever thread caused the transition —
     * route results come back on its executor — so every callback lands here
     * through [post].
     */
    private val navListener = object : NavigationController.Listener {
        override fun onStateChanged(uiState: NavigationUiState) {
            main.post { if (isActive) render(uiState) }
        }

        override fun onAnnounce(announcement: Announcement) {
            main.post {
                if (!isActive) return@post
                val nav = navigator ?: return@post
                if (!nav.shouldSpeak(announcement)) return@post
                val spoke = graph.voice.speak(announcement.text, announcement.urgent)
                // Visual guidance still works without a voice, so a failure to
                // speak is logged rather than surfaced: the driver sees the
                // card either way.
                if (!spoke) {
                    android.util.Log.w(TAG, "no Vietnamese voice for: ${announcement.text}")
                }
            }
        }

        override fun onRouteChanged(route: Route?) {
            main.post {
                if (!isActive) return@post
                applyRoute(route)
            }
        }

        override fun onOverviewChanged(overview: Boolean) {
            main.post {
                if (!isActive) return@post
                this@MapActivity.overview = overview
                val nav = navigator ?: return@post
                val r = nav.route() ?: return@post
                if (overview) {
                    mapHost?.withController { it.fitRoute(RouteGeometry(r.points), FIT_PADDING_PX) }
                } else {
                    lastFix?.let { fix -> resumeFollowing(fix) }
                }
            }
        }

        override fun onEnded(reason: NavigationEndReason) {
            main.post {
                if (!isActive) return@post
                applyRoute(null)
                graph.locationEngine.setRoute(null)
                mapHost?.withController { controller ->
                    controller.setCameraFollow(CameraFollowMode.NONE)
                    controller.setDestinationMarker(null, null)
                }
            }
        }
    }

    // -- music ---------------------------------------------------------------

    private val musicObserver = object : MusicRuntime.Observer {
        override fun onPlaybackState(state: PlaybackState) {
            musicCard.bind(state)
            musicCard.isFavorite = state.track
                ?.let { runCatching { music.backend?.library?.isFavorite(it.id) }.getOrNull() }
                ?: false
        }

        override fun onBackendChanged(backend: com.ts8.core.music.MusicBackend?) {
            onPlaybackState(music.state())
        }
    }

    // -- lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        bindNavigation()

        graph = AppGraph.require()
        music = MusicRuntime.get(this)

        bindViews()

        // The chip and roundel show `--` until the pipeline has a real number;
        // an empty state does not invent a reading.
        applySpeedState(com.ts8.core.speed.SpeedUiState.EMPTY)
        speedChip.visibility = View.GONE
        syncSpeedCluster()

        wireMap(savedInstanceState)
        wireMusicCard()
        wireControls()
        wireSearchEntry()

        val nav = graph.navigation()
        navigator = nav
        if (nav == null) {
            // No pack means no router means no navigation. Say so instead of
            // offering buttons that cannot work.
            showBanner(getString(R.string.nav_no_pack), visible = true)
        } else {
            nav.setListener(navListener)
            nav.uiState().let { render(it) }
        }

        graph.voice.init()
        val intentDestination = readDestination(intent)
        if (intentDestination != null) {
            startToDestination(intentDestination)
        }

        ensureLocationPermission()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        readDestination(intent)?.let { startToDestination(it) }
    }

    private fun bindViews() {
        mapPlaceholder = findViewById(R.id.mapPlaceholder)
        mapPlaceholderText = findViewById(R.id.mapPlaceholderText)
        maneuverCard = findViewById(R.id.maneuverCard)
        maneuverIcon = findViewById(R.id.maneuverIcon)
        maneuverDistance = findViewById(R.id.maneuverDistance)
        maneuverInstruction = findViewById(R.id.maneuverInstruction)
        maneuverThen = findViewById(R.id.maneuverThen)
        etaStrip = findViewById(R.id.etaStrip)
        speedChip = findViewById(R.id.speedChip)
        speedValue = findViewById(R.id.speedValue)
        speedUnit = findViewById(R.id.speedUnit)
        speedLimit = findViewById(R.id.speedLimit)
        speedLimitValue = findViewById(R.id.speedLimitValue)
        speedCluster = findViewById(R.id.speedCluster)
        guidanceControls = findViewById(R.id.guidanceControls)
        browseControls = findViewById(R.id.browseControls)
        destinationCard = findViewById(R.id.destinationCard)
        destinationName = findViewById(R.id.destinationName)
        destinationSummary = findViewById(R.id.destinationSummary)
        destinationMessage = findViewById(R.id.destinationMessage)
        stateBanner = findViewById(R.id.stateBanner)
        searchField = findViewById(R.id.searchField)
        musicCard = findViewById(R.id.musicCard)
    }

    /** True between [onCreate] and [onDestroy]; guards the posted callbacks. */
    private var isActive = false

    override fun onStart() {
        super.onStart()
        isActive = true
        graph.location.acquire(locationListener)
        graph.speed.acquire(speedListener)
        music.attach(musicObserver)
        mapHost?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapHost?.onResume()
        // The day/night mode is a launcher setting, changed from Quick Settings
        // or from Home's layer button. Re-applying it on resume is what makes
        // the map tab agree with the control the driver actually used. The
        // controller returns immediately when the palette already matches, so
        // this does not rebuild the style on every tab switch.
        val storedMode = AppSettings(this).mapStyleMode
        mapHost?.withController { controller -> controller.setStyleMode(storedMode) }
        // Same reason as on Home: the `Hiển thị` group is a second, independent
        // input to the style document, changed from a screen that this one was
        // not looking at.
        mapHost?.refreshDisplayConfig()
        // A change to `Góc nhìn khi dẫn đường` or to the control visibility is
        // not part of the style snapshot, so it is re-read here too — the
        // settings panel is a different Activity and this one is not destroyed
        // while it is open.
        applyControlVisibility()
        if (navigator?.state() == NavigationState.NAVIGATING) {
            lastFix?.let { fix -> resumeFollowing(fix) }
        }
        // The navigator keeps running while another screen is up, so the panel
        // has to catch up on whatever happened in the meantime.
        navigator?.uiState()?.let { render(it) }
    }

    override fun onPause() {
        mapHost?.onPause()
        super.onPause()
    }

    override fun onStop() {
        music.detach(musicObserver)
        graph.speed.release(speedListener)
        graph.location.release(locationListener)
        isActive = false
        mapHost?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapHost?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // Detach only: the controller belongs to the process graph and its
        // executor is shared with every later screen. Shutting it down here
        // would make routing dead for the rest of the drive.
        navigator?.setListener(null)
        graph.locationEngine.setRoute(null)
        if (graph.voice.isSpeaking()) graph.voice.stop()
        mapHost?.onDestroy()
        mapHost = null
        super.onDestroy()
    }

    // -- map -----------------------------------------------------------------

    private fun wireMap(savedInstanceState: Bundle?) {
        val container = findViewById<FrameLayout>(R.id.mapHost)
        val host = MapHost(this, graph, container)
        mapHost = host
        host.create(savedInstanceState)

        val failure = host.failure
        if (failure != null) {
            mapPlaceholder.visibility = View.VISIBLE
            mapPlaceholderText.text = failure
            return
        }
        mapPlaceholder.visibility = View.GONE

        host.withController { controller ->
            controller.setReadyListener(object : MapController.ReadyListener {
                override fun onReady() {
                    val nav = navigator
                    val route = nav?.route()
                    if (route != null) {
                        applyRoute(route)
                    } else {
                        graph.pack()?.center?.let { centre ->
                            controller.moveCamera(
                                CameraPosition(centre, graph.pack()?.metadata?.defaultZoom ?: 12.0),
                                animate = false
                            )
                        }
                    }
                    lastFix?.let { fix ->
                        controller.setVehicleMarker(fix.point, fix.bearingDeg ?: 0f)
                        if (nav?.state() == NavigationState.NAVIGATING) resumeFollowing(fix)
                    }
                    nav?.destination()?.let { dest ->
                        controller.setDestinationMarker(dest, nav.uiState().destinationName)
                    }
                }

                override fun onFailed(reason: String) {
                    mapPlaceholder.visibility = View.VISIBLE
                    mapPlaceholderText.text = reason
                }
            })
        }
    }

    /**
     * Pushes a route into both the renderer and the snapping pipeline.
     *
     * The order is load-bearing: [com.ts8.core.location.LocationEngine] must be
     * told the polyline before the next fix arrives, otherwise that fix has no
     * projection and the navigator counts it as an off-route sample.
     */
    private fun applyRoute(route: Route?) {
        val nav = navigator
        if (route == null) {
            graph.locationEngine.setRoute(null)
            mapHost?.withController { controller ->
                controller.setRoute(null)
                controller.setDestinationMarker(null, null)
            }
            return
        }
        graph.locationEngine.setRoute(route.points)
        mapHost?.withController { controller ->
            controller.setRoute(RouteGeometry(route.points))
            nav?.destination()?.let { dest ->
                controller.setDestinationMarker(dest, nav.uiState().destinationName)
            }
            // A freshly computed route is shown whole: the driver asked "how do
            // I get there", and answering with a zoomed-in view of the car says
            // nothing about the destination.
            controller.fitRoute(RouteGeometry(route.points), FIT_PADDING_PX)
        }
    }

    /** Re-arms the follow camera after the driver has panned the map. */
    private fun resumeFollowing(fix: LocationFix) {
        mapHost?.withController { controller ->
            controller.setCameraFollow(followModeForNavigation())
            controller.moveCamera(
                CameraPosition(
                    fix.point,
                    AppSettings(this).navigationZoom.toDouble(),
                    if (AppSettings(this).cameraMode == AppSettings.CameraMode.NORTH_UP) {
                        0f
                    } else {
                        fix.bearingDeg ?: 0f
                    },
                    if (AppSettings(this).cameraMode == AppSettings.CameraMode.PERSPECTIVE) {
                        NAV_TILT
                    } else {
                        0f
                    }
                ),
                animate = true
            )
        }
    }

    // -- rendering -----------------------------------------------------------

    /**
     * Turns a navigator snapshot into the screen.
     *
     * One function, so every state's chrome is decided in one place and no two
     * overlays can be visible at once by accident.
     */
    private fun render(ui: NavigationUiState) {
        val guiding = ui.state == NavigationState.NAVIGATING ||
            ui.state == NavigationState.REROUTING ||
            ui.state == NavigationState.ARRIVED

        // §16: the machine mirrors the navigator. This screen is the one that
        // owns guidance, so a state change here is what moves the app between
        // MAP_BROWSE, DESTINATION_PREVIEW, ROUTE_PREVIEW and NAVIGATING — and
        // it is what makes Home's navigation-aware strip appear when the driver
        // leaves mid-route.
        uiState?.syncNavigation(ui.state)

        maneuverCard.visibility = if (guiding) View.VISIBLE else View.GONE
        etaStrip.visibility = if (guiding) View.VISIBLE else View.GONE
        guidanceControls.visibility = if (guiding) View.VISIBLE else View.GONE
        browseControls.visibility = if (guiding) View.GONE else View.VISIBLE

        val showingPreview = ui.state == NavigationState.ROUTE_PREVIEW ||
            (ui.state == NavigationState.DESTINATION_PREVIEW && ui.message != null)
        destinationCard.visibility = if (showingPreview) View.VISIBLE else View.GONE
        searchField.visibility =
            if (ui.state == NavigationState.IDLE || ui.state == NavigationState.SEARCHING) {
                View.VISIBLE
            } else {
                View.GONE
            }

        // The compact music strip only earns its space while the map is being
        // read as a map; during preview the destination card needs the corner.
        musicCard.visibility =
            if (guiding || ui.state == NavigationState.IDLE) View.VISIBLE else View.GONE

        muted = ui.muted
        overview = ui.overview
        updateMuteButton()

        renderBanner(ui)
        renderManeuver(ui)
        renderPreview(ui)

        ui.destination?.let { dest ->
            mapHost?.withController { it.setDestinationMarker(dest, ui.destinationName) }
        }

        // §8's "route đã đi và route còn lại". The split is derived from the
        // navigator's own `travelledMeters` against the polyline the renderer was
        // given, so the boundary sits where the car actually is rather than
        // where a fraction of the point count would put it. Sent on every
        // render, which is one fix per accepted sample — the same rate at which
        // the strip's numbers change, and far below the rate that would make
        // this a frame cost.
        //
        // Outside guidance the split is reset to "nothing driven": a route that
        // is only being previewed has not been driven at all, and a leftover
        // split from the previous trip would grey out the front of a new one.
        val progress = ui.progress
        val route = ui.route
        mapHost?.withController { controller ->
            if (progress != null && route != null && guiding) {
                controller.setRouteProgress(
                    RouteGeometry(route.points).indexAtDistance(progress.travelledMeters)
                )
            } else {
                controller.setRouteProgress(0)
            }
        }

        // Camera policy: follow while guiding, free otherwise. **How** it follows
        // is the driver's `Góc nhìn khi dẫn đường` setting, not a constant:
        // north-up and heading-up are genuinely different products and the
        // brief lists all three.
        mapHost?.withController { controller ->
            controller.setCameraFollow(
                if (guiding && ui.state != NavigationState.ARRIVED) {
                    followModeForNavigation()
                } else {
                    CameraFollowMode.NONE
                }
            )
        }
    }

    private fun renderBanner(ui: NavigationUiState) {
        val text = when (ui.state) {
            NavigationState.ROUTE_CALCULATING -> getString(R.string.nav_calculating)
            NavigationState.REROUTING -> getString(R.string.nav_rerouting)
            NavigationState.ARRIVED -> getString(R.string.nav_arrived)
            else -> ui.message
        }
        showBanner(text, text != null)
    }

    private fun showBanner(text: String?, visible: Boolean) {
        if (!visible || text.isNullOrBlank()) {
            stateBanner.visibility = View.GONE
            return
        }
        stateBanner.text = text
        stateBanner.visibility = View.VISIBLE
    }

    private fun renderManeuver(ui: NavigationUiState) {
        val progress = ui.progress
        if (progress == null) {
            maneuverDistance.text = null
            maneuverInstruction.text = getString(R.string.nav_no_destination)
            maneuverThen.visibility = View.GONE
            return
        }

        val next = progress.nextManeuver
        maneuverIcon.setImageResource(iconFor(next))
        maneuverDistance.text = when {
            ui.state == NavigationState.ARRIVED -> getString(R.string.nav_arrived)
            else -> NavigationController.formatDistance(progress.distanceToNextManeuverMeters)
        }
        maneuverInstruction.text = instructionFor(next)

        val following = progress.followingManeuver
        val then = following?.streetName ?: following?.instruction
        if (ui.state == NavigationState.NAVIGATING && !then.isNullOrBlank()) {
            maneuverThen.text = getString(R.string.nav_then) + " · " + then
            maneuverThen.visibility = View.VISIBLE
        } else {
            maneuverThen.visibility = View.GONE
        }

        etaStrip.text = formatEta(progress)
    }

    private fun formatEta(progress: NavigationProgress): CharSequence {
        val duration = ClockFormat.duration(progress.remainingSeconds)
        val distance = NavigationController.formatDistance(progress.remainingMeters)
            .ifBlank { getString(R.string.search_km) }
        val arrival = when {
            progress.etaEpochMs > 0L -> ClockFormat.time(progress.etaEpochMs)
            progress.remainingSeconds > 0 -> ClockFormat.eta(
                System.currentTimeMillis(),
                progress.remainingSeconds
            )
            else -> "—"
        }
        val text = getString(R.string.nav_eta_format, duration, distance, arrival)

        // The design sheet colours only the duration green and leaves the
        // distance and arrival white on the dark strip — measured on the
        // Navigation panel, where the 318 saturated pixels in the whole band
        // are all inside "12 phút". The strip's background is the raised navy
        // (see bg_eta_strip.xml), so the green is what makes the number the
        // driver glances at read first.
        val span = SpannableString(text)
        val start = text.indexOf(duration)
        if (start >= 0 && duration.isNotEmpty()) {
            span.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.success)),
                start,
                start + duration.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return span
    }

    private fun renderPreview(ui: NavigationUiState) {
        destinationName.text = ui.destinationName ?: getString(R.string.nav_no_destination)

        val route = ui.route
        if (route != null) {
            destinationSummary.text = getString(
                R.string.nav_route_summary,
                NavigationController.formatDistance(route.distanceMeters),
                ClockFormat.duration(route.durationSeconds)
            )
        } else {
            destinationSummary.text = getString(R.string.nav_no_route)
        }

        val error = ui.message
        if (error != null && ui.state != NavigationState.NAVIGATING) {
            destinationMessage.text = error
            destinationMessage.visibility = View.VISIBLE
        } else {
            destinationMessage.visibility = View.GONE
        }

        // A start button with no route computes nothing, so it is disabled
        // rather than offered as a dead tap. Looked up once: the same view is
        // disabled and dimmed, and two `findViewById` calls for one id is the
        // pattern a cut-and-paste error hides in.
        val startButton = findViewById<Button>(R.id.btnStart)
        startButton.isEnabled = route != null
        startButton.alpha = if (route != null) 1f else 0.4f
    }

    private fun instructionFor(maneuver: Maneuver?): String = when {
        maneuver == null -> getString(R.string.nav_no_destination)
        maneuver.type == ManeuverType.ARRIVE -> getString(R.string.nav_arrived)
        maneuver.instruction.isNotBlank() -> maneuver.instruction
        // Explicit local: `streetName` is a public API property from another
        // module, so Kotlin cannot smart-cast it after the null check.
        else -> maneuver.streetName?.takeIf { it.isNotBlank() } ?: getString(R.string.nav_no_route)
    }

    private fun iconFor(maneuver: Maneuver?): Int = when (maneuver?.type) {
        null -> R.drawable.ic_maneuver_straight
        ManeuverType.ARRIVE -> R.drawable.ic_maneuver_arrive
        ManeuverType.DEPART -> R.drawable.ic_maneuver_depart
        ManeuverType.ROUNDABOUT -> R.drawable.ic_maneuver_roundabout
        ManeuverType.CONTINUE -> R.drawable.ic_maneuver_straight
        ManeuverType.TURN -> when (maneuver.direction) {
            ManeuverDirection.LEFT -> R.drawable.ic_maneuver_left
            ManeuverDirection.RIGHT -> R.drawable.ic_maneuver_right
            ManeuverDirection.SLIGHT_LEFT -> R.drawable.ic_maneuver_slight_left
            ManeuverDirection.SLIGHT_RIGHT -> R.drawable.ic_maneuver_slight_right
            ManeuverDirection.SHARP_LEFT -> R.drawable.ic_maneuver_left
            ManeuverDirection.SHARP_RIGHT -> R.drawable.ic_maneuver_right
            ManeuverDirection.UTURN -> R.drawable.ic_maneuver_uturn
            ManeuverDirection.STRAIGHT, ManeuverDirection.NONE ->
                R.drawable.ic_maneuver_straight
        }
    }

    // -- controls ------------------------------------------------------------

    private fun wireControls() {
        findViewById<ImageButton>(R.id.btnZoomIn).setOnClickListener {
            mapHost?.withController { zoomBy(it, +1) }
        }
        findViewById<ImageButton>(R.id.btnZoomOut).setOnClickListener {
            mapHost?.withController { zoomBy(it, -1) }
        }
        findViewById<ImageButton>(R.id.btnRecenter).setOnClickListener {
            lastFix?.let { resumeFollowing(it) }
        }
        findViewById<ImageButton>(R.id.btnMute).setOnClickListener {
            val nav = navigator ?: return@setOnClickListener
            nav.setMuted(!nav.isMuted())
        }
        findViewById<ImageButton>(R.id.btnOverview).setOnClickListener {
            val nav = navigator ?: return@setOnClickListener
            // `overview` mirrors the controller's own flag, kept in sync by
            // `onOverviewChanged`; reading it back here avoids a second getter
            // that could disagree with what the camera is actually doing.
            nav.showOverview(!overview)
        }
        findViewById<ImageButton>(R.id.btnEnd).setOnClickListener {
            navigator?.end(NavigationEndReason.USER_ENDED)
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            navigator?.startNavigation()
        }
        findViewById<Button>(R.id.btnCancelPreview).setOnClickListener {
            navigator?.end(NavigationEndReason.CANCELLED_PREVIEW)
        }
        applyControlVisibility()
    }

    /**
     * Hides the controls the driver switched off in `Hiển thị`.
     *
     * **Why the buttons are hidden rather than disabled.** A greyed-out zoom
     * button is still a button that costs a tap to discover it does nothing; the
     * setting says "I do not want these on screen", and on a 1024x600 panel the
     * few hundred pixels a hidden control frees are the point of the setting.
     * The guidance controls are deliberately not covered: mute, overview and end
     * are safety-relevant while navigating and are not part of that group.
     */
    private fun applyControlVisibility() {
        val settings = AppSettings(this)
        val zoomButtons = settings.showZoomControls
        findViewById<ImageButton>(R.id.btnZoomIn).visibility =
            if (zoomButtons) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.btnZoomOut).visibility =
            if (zoomButtons) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.btnRecenter).visibility =
            if (settings.showRecenter) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.btnOverview).visibility =
            if (settings.showOverview) View.VISIBLE else View.GONE
    }

    /**
     * The follow policy the driver chose, as a camera mode.
     *
     * `NAVIGATING` is the only state that follows; the mode decides how. A
     * driver who picked `Hướng Bắc` gets the vehicle centred with north up and
     * no tilt — which is the mode the brief calls NORTH_UP and which some people
     * prefer in a city grid.
     */
    private fun followModeForNavigation(): CameraFollowMode =
        when (AppSettings(this).cameraMode) {
            AppSettings.CameraMode.NORTH_UP -> CameraFollowMode.FOLLOW
            AppSettings.CameraMode.HEADING_UP -> CameraFollowMode.FOLLOW_BEARING
            AppSettings.CameraMode.PERSPECTIVE -> CameraFollowMode.FOLLOW_BEARING_TILT
        }

    private fun zoomBy(controller: MapLibreMapController, delta: Int) {
        val current = controller.getCameraPosition()
        controller.moveCamera(
            current.copy(zoom = (current.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)),
            animate = true
        )
    }

    private fun updateMuteButton() {
        val button = findViewById<ImageButton>(R.id.btnMute)
        button.setImageResource(if (muted) R.drawable.ic_mute else R.drawable.ic_volume)
        button.contentDescription =
            getString(if (muted) R.string.nav_unmute else R.string.nav_mute)
    }

    private fun wireSearchEntry() {
        searchField.setOnClickListener { openSearch() }
        // Sibling target inside the same field: the mic opens Voice Instant and
        // the rest of the field still opens search, which is the behaviour the
        // design draws and the two are separate hit areas so a thumb cannot pick
        // the wrong one.
        findViewById<ImageButton>(R.id.btnVoice)?.setOnClickListener { startVoice() }
    }

    private fun openSearch() {
        startActivity(
            Intent(this, SearchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        ScreenTransition.apply(this)
    }

    // -- destination handoff --------------------------------------------------

    private fun readDestination(intent: Intent?): PendingDestination? {
        if (intent == null) return null
        if (!intent.hasExtra(EXTRA_DEST_LAT) || !intent.hasExtra(EXTRA_DEST_LON)) return null
        val lat = intent.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_DEST_LON, Double.NaN)
        // Read every extra before removing any of them: `removeExtra` is what
        // marks the handoff consumed, and reading after it would return null.
        val name = intent.getStringExtra(EXTRA_DEST_NAME) ?: ""
        intent.removeExtra(EXTRA_DEST_LAT)
        intent.removeExtra(EXTRA_DEST_LON)
        intent.removeExtra(EXTRA_DEST_NAME)
        if (lat.isNaN() || lon.isNaN()) return null
        val point = GeoPoint(lat, lon)
        if (!point.isValid()) return null
        return PendingDestination(point, name)
    }

    /**
     * Starts a route to a place chosen elsewhere.
     *
     * With no fix yet the choice is held rather than refused: on a cold boot the
     * receiver usually needs a few seconds, and "chưa có GPS" would make the
     * driver repeat the search.
     */
    private fun startToDestination(destination: PendingDestination) {
        val nav = navigator ?: return
        nav.beginSearch()
        val from = lastFix?.point ?: graph.location.lastFix?.point
        if (from == null) {
            pendingDestination = destination
            showBanner(getString(R.string.home_gps_searching), visible = true)
        } else {
            nav.selectDestination(destination.point, destination.name, from)
        }
        mapHost?.withController {
            it.setDestinationMarker(destination.point, destination.name)
        }
    }

    // -- music ---------------------------------------------------------------

    private fun wireMusicCard() {
        musicCard.onPlayPause = { music.toggle() }
        musicCard.onNext = { music.next() }
        musicCard.onPrevious = { music.previous() }
        musicCard.onToggleShuffle = { music.setShuffle(!music.state().shuffle) }
        musicCard.onCycleRepeat = {
            val mode = music.state().repeatMode
            music.setRepeatMode(
                when (mode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
            )
        }
        musicCard.onToggleFavorite = favorite@{
            val track = music.state().track ?: return@favorite
            val library = music.backend?.library ?: return@favorite
            main.post {
                val nowFavourite = !library.isFavorite(track.id)
                library.setFavorite(track.id, nowFavourite)
                musicCard.isFavorite = nowFavourite
            }
        }
        musicCard.onOpenQueue = {
            startActivity(
                Intent(this, MusicFullscreenActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    // -- permissions ---------------------------------------------------------

    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && isActive) {
            // The runtime only starts the receiver when a listener asks for it,
            // so re-acquiring here is what makes a just-granted permission count.
            graph.location.release(locationListener)
            graph.location.acquire(locationListener)
        }
    }

    companion object {
        private const val TAG = "Ts8MapActivity"
        private const val REQUEST_LOCATION = 0x7411
        private const val MIN_ZOOM = 6.0
        private const val MAX_ZOOM = 19.0
        private const val FIT_PADDING_PX = 90

        /** Camera tilt for `Phối cảnh`, degrees. Matches the design's 3D view. */
        private const val NAV_TILT = 45f

        /** Extras the search screen (and any future caller) uses to pick a place. */
        const val EXTRA_DEST_LAT = "com.ts8.launcher.extra.DEST_LAT"
        const val EXTRA_DEST_LON = "com.ts8.launcher.extra.DEST_LON"
        const val EXTRA_DEST_NAME = "com.ts8.launcher.extra.DEST_NAME"

        /** Builds the intent that routes to [point]. */
        fun intentTo(context: android.content.Context, point: GeoPoint, name: String): Intent =
            Intent(context, MapActivity::class.java)
                .putExtra(EXTRA_DEST_LAT, point.lat)
                .putExtra(EXTRA_DEST_LON, point.lon)
                .putExtra(EXTRA_DEST_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/MapManagementActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ts8.core.map.MapStyleMode
import com.ts8.core.map.maplibre.MapPack
import com.ts8.core.map.maplibre.PackDiagnostics
import com.ts8.core.map.maplibre.PackFailureCode
import com.ts8.core.map.maplibre.PackImporter
import com.ts8.core.map.maplibre.PackValidator
import com.ts8.core.speed.SpeedFilter
import com.ts8.core.speed.SpeedMonitor
import com.ts8.core.speed.SpeedUnit
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppSettings
import com.ts8.launcher.runtime.AppStateMachine
import java.io.File
import java.util.Locale

/**
 * Map Management — installed packs, import, and the two settings groups from §8.
 *
 * **Why every row here is real.** Each control writes a preference that a screen
 * actually reads ([AppSettings]), or performs an action whose result the driver
 * sees on this same screen (import progress, validation report, free space). A
 * settings screen whose switches do nothing is the specific failure the brief
 * calls out, and it is easy to produce by accident: adding a control before the
 * code that consumes it.
 *
 * **Why the import is asynchronous and staged.** A 20 MB archive takes seconds
 * to extract on this CPU. Doing it on the main thread freezes the panel, and
 * doing it in place risks a half-replaced pack if the ignition cuts. The work
 * runs on [AppGraph.io] through [PackImporter], which extracts to a staging
 * directory, validates there, and only then swaps it in.
 *
 * **Why the active pack is protected.** Removing the pack the map is currently
 * drawing leaves a black screen with no way back, so removal of the active pack
 * asks the driver to confirm that they are choosing to have *no map* — the rule
 * the campaign brief states verbatim.
 */
class MapManagementActivity : BaseScreenActivity() {

    /** Not a tab: opened from the map placeholder, Settings or Quick Settings. */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    private lateinit var graph: AppGraph
    private lateinit var settings: AppSettings

    private lateinit var packList: LinearLayout
    private lateinit var packEmpty: TextView
    private lateinit var storageText: TextView
    private lateinit var importStatus: TextView
    private lateinit var diagText: TextView
    private lateinit var displayGroup: LinearLayout
    private lateinit var speedGroup: LinearLayout

    private val main = Handler(Looper.getMainLooper())

    /** Packs currently on storage, from the most recent pass. */
    private var packs: List<MapPack> = emptyList()

    /** Byte size per pack path, measured on the IO thread. */
    private val sizes = HashMap<String, Long>(4)

    /** True while a background import is running; blocks a second one. */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_management)
        bindNavigation()

        graph = AppGraph.require()
        settings = graph.settings

        packList = findViewById(R.id.packList)
        packEmpty = findViewById(R.id.packEmpty)
        storageText = findViewById(R.id.storageText)
        importStatus = findViewById(R.id.importStatus)
        diagText = findViewById(R.id.diagText)
        displayGroup = findViewById(R.id.displayGroup)
        speedGroup = findViewById(R.id.speedGroup)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnImport).setOnClickListener { pickArchive() }
        findViewById<View>(R.id.btnRescan).setOnClickListener { rescan() }
        findViewById<View>(R.id.btnRestoreDisplay).setOnClickListener { restoreDisplayDefaults() }
        findViewById<View>(R.id.btnCopyDiag).setOnClickListener { copyDiagnostics() }

        buildDisplayGroup()
        buildSpeedGroup()
        refreshPacks()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the picker or from a USB insert: re-read rather than
        // showing the state from when the screen was first opened.
        if (!busy) refreshPacks()
        renderDiagnostics()
    }

    // =======================================================================
    // Packs
    // =======================================================================

    /**
     * Re-walks storage off the main thread and re-renders the list.
     *
     * The walk stats every root and parses one manifest per candidate, then
     * measures each pack's size by walking its tree. On a unit with a USB stick
     * full of music that is tens of milliseconds, but storage access never
     * happens on the thread that draws.
     */
    private fun refreshPacks() {
        renderStorage()
        graph.io().execute {
            val found = runCatching { graph.listPacks() }.getOrElse { emptyList() }
            val measured = found.map { pack ->
                pack.directory.absolutePath to runCatching { pack.sizeBytes() }.getOrDefault(0L)
            }
            main.post {
                packs = found
                sizes.clear()
                sizes.putAll(measured)
                renderPacks()
                renderDiagnostics()
            }
        }
    }

    private fun renderPacks() {
        packList.removeAllViews()
        packEmpty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE

        val activePath = graph.pack()?.directory?.absolutePath
        val report = graph.packReport()

        for (pack in packs) {
            packList.addView(
                packCard(pack, isActive = pack.directory.absolutePath == activePath, report = report)
            )
        }
    }

    /**
     * One installed pack: identity, size, data date, capability status, actions.
     *
     * The capability lines are the "degraded modes are explicit" requirement: a
     * pack without `routing-graph/` still draws a map and still searches, and
     * says so, rather than failing as a whole.
     */
    private fun packCard(pack: MapPack, isActive: Boolean, report: PackValidator.Report?): View {
        val meta = pack.metadata
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        card.addView(text(meta.name, R.color.text_primary, 16f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (isActive) {
            card.addView(text(getString(R.string.map_pack_active), R.color.accent_text, 12f))
        }

        card.addView(text(getString(R.string.map_pack_region, meta.packId), R.color.text_secondary, 12f))
        card.addView(text(
            getString(
                R.string.map_pack_version,
                "v${meta.formatVersion} · zoom ${meta.minZoom.toInt()}–${meta.maxZoom.toInt()}"
            ),
            R.color.text_secondary, 12f
        ))
        card.addView(text(
            getString(R.string.map_pack_size, formatBytes(sizes[pack.directory.absolutePath] ?: 0L)),
            R.color.text_secondary, 12f
        ))
        card.addView(text(
            getString(R.string.map_pack_data_date, meta.generatedAt ?: "-"),
            if (meta.generatedAt == null) R.color.text_tertiary else R.color.text_secondary, 12f
        ))

        // Capability rows. Only the active pack has actually been opened by the
        // validator; for the others the file presence is checked directly, which
        // is cheap and honest about what is known.
        val routingOk = if (isActive && report != null) report.routingOk else pack.routingDir() != null
        val searchOk = if (isActive && report != null) report.placesOk else pack.databaseFile() != null

        card.addView(text(
            if (routingOk) getString(R.string.map_pack_routing_ok)
            else getString(R.string.map_pack_routing_missing),
            if (routingOk) R.color.success else R.color.danger, 12f
        ))
        card.addView(text(
            if (searchOk) getString(R.string.map_pack_search_ok, 0)
            else getString(R.string.map_pack_search_missing),
            if (searchOk) R.color.success else R.color.danger, 12f
        ))

        val status = when {
            isActive && graph.isPackValidating() -> getString(R.string.map_pack_status_validating)
            isActive && report != null && !report.ok ->
                getString(R.string.map_pack_status_invalid, report.code?.name ?: "?")
            else -> getString(R.string.map_pack_status_ready)
        }
        card.addView(text(
            status,
            if (isActive && report != null && !report.ok) R.color.danger else R.color.text_tertiary,
            12f
        ).apply { setPadding(0, dp(4), 0, dp(6)) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        if (!isActive) {
            actions.addView(actionButton(getString(R.string.map_action_activate)) { activate(pack) })
        }
        actions.addView(actionButton(getString(R.string.map_action_validate)) {
            graph.revalidatePack()
            Toast.makeText(this, R.string.map_pack_status_validating, Toast.LENGTH_SHORT).show()
            main.postDelayed({
                renderPacks()
                renderDiagnostics()
            }, REFRESH_AFTER_VALIDATION_MS)
        })
        actions.addView(actionButton(getString(R.string.map_action_remove)) {
            confirmRemove(pack, isActive)
        })
        card.addView(actions)

        return card
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_chip)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    /**
     * Points the app at [pack] and re-reads storage.
     *
     * Writing the pointer is what makes the choice stick across a reboot: the
     * loader reads it ahead of the root walk, so a second region installed later
     * cannot silently take over.
     */
    private fun activate(pack: MapPack) {
        settings.activePackPath = pack.directory.absolutePath
        settings.noMapConfirmed = false
        graph.invalidatePack()
        Toast.makeText(
            this,
            getString(R.string.map_activate_done, pack.metadata.name),
            Toast.LENGTH_SHORT
        ).show()
        refreshPacks()
    }

    /**
     * Confirms, then removes.
     *
     * Guarding the active pack is a distinct dialog with distinct words: the
     * driver must understand they are choosing to have no map, not merely to
     * delete a folder.
     */
    private fun confirmRemove(pack: MapPack, isActive: Boolean) {
        val body = if (isActive) {
            getString(R.string.map_remove_active_body, pack.metadata.name)
        } else {
            getString(R.string.map_remove_body, pack.metadata.name)
        }
        val positive = if (isActive) {
            getString(R.string.map_remove_confirm_no_map)
        } else {
            getString(R.string.map_action_remove)
        }

        // §16's ERROR_DIALOG overlay for the modal — the name in the brief
        // covers any dialog that takes Back ahead of the screen, and this one
        // is the case that matters most: a Back meant to cancel a "no map"
        // confirmation must not close Map Management and leave the driver on
        // the map placeholder wondering what they agreed to.
        showTrackedDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.map_remove_title)
                .setMessage(body)
                .setNegativeButton(R.string.generic_cancel, null)
                .setPositiveButton(positive) { _, _ -> remove(pack, isActive) }
                .show()
        )
    }

    private fun remove(pack: MapPack, wasActive: Boolean) {
        graph.io().execute {
            val deleted = runCatching { pack.directory.deleteRecursively() }.getOrDefault(false)
            main.post {
                if (!deleted) {
                    Toast.makeText(
                        this,
                        getString(R.string.map_remove_failed, pack.directory.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                if (wasActive) {
                    // Explicit NO_MAP, not an accident: the pointer is dropped
                    // only now, after the driver confirmed.
                    settings.activePackPath = null
                    settings.noMapConfirmed = true
                    graph.invalidatePack()
                }
                Toast.makeText(
                    this,
                    getString(R.string.map_remove_done, pack.metadata.name),
                    Toast.LENGTH_SHORT
                ).show()
                refreshPacks()
            }
        }
    }

    // =======================================================================
    // Import
    // =======================================================================

    /**
     * Opens the system file picker for a `.ts8map` / `.zip`.
     *
     * `ACTION_GET_CONTENT` rather than `ACTION_OPEN_DOCUMENT`: the import is a
     * one-shot copy into app-private storage, so a persistable URI grant buys
     * nothing, and the persistable-grant path is unreliable on vendor ROMs of
     * this vintage.
     */
    private fun pickArchive() {
        if (busy) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/octet-stream")
            )
        }
        val opened = runCatching { startActivityForResult(intent, REQUEST_PICK) }
        if (opened.isFailure) {
            Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        startImport(uri, data.clipData)
    }

    /**
     * Copies the archive into app-private cache, then hands it to
     * [PackImporter].
     *
     * **Why the copy first.** A content URI from the picker is readable only
     * while the granting Activity is alive and the provider keeps answering; an
     * extraction that takes twenty seconds can outlive that. Copying first also
     * makes the Zip Slip guard meaningful, because the archive is then read from
     * a file this app controls.
     */
    private fun startImport(uri: Uri, clipData: ClipData?) {
        if (busy) return
        busy = true
        // §16's MAP_IMPORT_PROGRESS overlay. Pushed here rather than in the IO
        // callback because the driver can press Back during the copy, and the
        // rule is that Back closes the overlay rather than the screen the
        // import is going to change underneath them.
        runCatching { AppGraph.require().uiState.pushOverlay(AppStateMachine.Overlay.MAP_IMPORT_PROGRESS) }
        showImportStatus(getString(R.string.map_import_running))

        val name = queryDisplayName(uri, clipData) ?: "pack.ts8map"
        val installRoot = File(filesDir, "map-pack")

        graph.io().execute {
            val stage = File(cacheDir, "import-" + name.replace(NOT_SAFE, "-"))
            val copied = runCatching {
                stage.parentFile?.mkdirs()
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("không mở được file đã chọn")
                input.use { source -> stage.outputStream().use { sink -> source.copyTo(sink) } }
                stage
            }.getOrNull()

            if (copied == null) {
                main.post {
                    busy = false
                    popImportOverlay()
                    showImportStatus(getString(R.string.map_import_failed, "không đọc được file"))
                }
                return@execute
            }

            val diagnostics = PackDiagnostics()
            val result = runCatching {
                PackImporter(installRoot).import(
                    archiveName = name,
                    open = { copied.inputStream() },
                    diagnostics = diagnostics
                )
            }.getOrElse { e ->
                PackImporter.Result.Failure(
                    PackFailureCode.INVALID_METADATA,
                    e.message ?: "lỗi không xác định",
                    diagnostics
                )
            }
            copied.delete()

            main.post {
                busy = false
                popImportOverlay()
                when (result) {
                    is PackImporter.Result.Ok -> {
                        // The driver's intent when importing is to use the pack,
                        // so activation is part of the import, not a second tap.
                        settings.activePackPath = result.directory.absolutePath
                        settings.noMapConfirmed = false
                        graph.invalidatePack()
                        val active = graph.pack()
                        showImportStatus(
                            getString(
                                R.string.map_import_done,
                                active?.metadata?.name ?: result.directory.name
                            ) + "\n" + getString(R.string.map_pack_size, formatBytes(result.bytes))
                        )
                        refreshPacks()
                    }
                    is PackImporter.Result.Failure -> {
                        // The old pack is untouched; say so rather than leaving
                        // the driver wondering whether they just lost their map.
                        val hint = when (result.code) {
                            PackFailureCode.INSUFFICIENT_STORAGE ->
                                "\n" + getString(R.string.map_import_no_room)
                            else -> ""
                        }
                        showImportStatus(
                            getString(R.string.map_import_failed, result.message) + hint + "\n" +
                                getString(R.string.map_import_kept_old)
                        )
                        diagText.text = result.diagnostics.report()
                    }
                }
            }
        }
    }

    /**
     * Pops §16's import overlay, whatever the outcome.
     *
     * Called from every terminal branch of [startImport] — success, read
     * failure and pack failure — so the overlay cannot outlive the work it
     * describes. `popOverlay` rather than `popTopOverlay`: a voice bar raised
     * mid-import must not be closed by an import that happens to finish.
     */
    private fun popImportOverlay() {
        runCatching {
            AppGraph.require().uiState.popOverlay(AppStateMachine.Overlay.MAP_IMPORT_PROGRESS)
        }
    }

    /**
     * Back during an import leaves the screen too.
     *
     * The overlay itself is closed by the base class; this only adds the part
     * that is this screen's: a driver who pressed Back on a progress panel
     * wants to be out of Map Management, not staring at a panel that will
     * change a pack behind them. The import itself is not cancelled — it runs
     * on the IO executor and finishes either way, since a half-extracted
     * staging directory is what the importer's own swap step is designed to
     * make harmless.
     */
    override fun onOverlayDismissed() {
        if (busy) finish()
    }

    private fun queryDisplayName(uri: Uri, clipData: ClipData?): String? {
        val fromClip = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val raw = (fromClip ?: uri.lastPathSegment ?: uri.toString()).substringAfterLast('/')
        return raw.substringBefore('?').takeIf { it.isNotBlank() }
    }

    private fun showImportStatus(message: String) {
        importStatus.visibility = View.VISIBLE
        importStatus.text = message
    }

    // =======================================================================
    // Storage + diagnostics
    // =======================================================================

    private fun renderStorage() {
        val root = File(filesDir, "map-pack")
        val free = runCatching { root.usableSpace }.getOrDefault(0L)
        val total = runCatching { root.totalSpace }.getOrDefault(0L)
        storageText.text = getString(R.string.map_storage_location, root.absolutePath) + "\n" +
            getString(R.string.map_free_space, formatBytes(free), formatBytes(total))
    }

    /**
     * Shows the discovery + validation trail.
     *
     * This is the panel a support call reads out loud: candidate roots, the
     * resolved canonical path, the tile query and routing compatibility, in the
     * exact shape `PackDiagnostics.report()` emits. It contains no driver
     * position — a tile z/x/y is a fact about the pack, not about the car.
     */
    private fun renderDiagnostics() {
        val discovery = graph.packDiagnostics()
        val report = graph.packReport()
        val builder = StringBuilder()
        discovery?.let { builder.append(it.report()) }
        if (report != null) {
            builder.appendLine()
            builder.appendLine("--- validation ---")
            builder.appendLine("ok=${report.ok} code=${report.code?.name ?: "none"}")
            builder.appendLine(
                "checksum=${report.checksumOk} mbtiles=${report.mbtilesOk} places=${report.placesOk}"
            )
            builder.appendLine(
                "routing=${report.routingOk} glyphs=${report.glyphsOk} sprite=${report.spriteOk}"
            )
            report.tileQuery?.let { builder.appendLine("tile=$it") }
            report.message?.let { builder.appendLine("message=$it") }
            builder.append(report.diagnostics.report())
        }
        diagText.text = builder.toString().ifBlank { getString(R.string.map_diag_empty) }
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ts8-map-diagnostics", diagText.text))
        Toast.makeText(this, R.string.map_diag_copied, Toast.LENGTH_SHORT).show()
    }

    private fun rescan() {
        graph.invalidatePack()
        graph.loadPack()
        refreshPacks()
        val pack = graph.pack()
        Toast.makeText(
            this,
            if (pack != null) getString(R.string.map_activate_done, pack.metadata.name)
            else getString(R.string.map_pack_none),
            Toast.LENGTH_SHORT
        ).show()
    }

    // =======================================================================
    // Display group (§8 "Hiển thị")
    // =======================================================================

    private fun buildDisplayGroup() {
        displayGroup.removeAllViews()

        optionRow(displayGroup, R.string.disp_mode, listOf(
            getString(R.string.disp_mode_2d) to AppSettings.MapMode.FLAT_2D,
            getString(R.string.disp_mode_3d) to AppSettings.MapMode.THREE_D,
            getString(R.string.disp_mode_auto) to AppSettings.MapMode.AUTO
        ), settings.mapMode) { settings.mapMode = it }

        optionRow(displayGroup, R.string.disp_theme, listOf(
            getString(R.string.disp_theme_day) to MapStyleMode.DAY,
            getString(R.string.disp_theme_night) to MapStyleMode.NIGHT,
            getString(R.string.disp_theme_auto) to MapStyleMode.AUTO
        ), settings.mapStyleMode) { settings.mapStyleMode = it }

        optionRow(displayGroup, R.string.disp_camera, listOf(
            getString(R.string.disp_camera_north) to AppSettings.CameraMode.NORTH_UP,
            getString(R.string.disp_camera_heading) to AppSettings.CameraMode.HEADING_UP,
            getString(R.string.disp_camera_perspective) to AppSettings.CameraMode.PERSPECTIVE
        ), settings.cameraMode) { settings.cameraMode = it }

        stepperRow(displayGroup, R.string.disp_zoom_default, settings.defaultZoom, 6f, 18f) {
            settings.defaultZoom = it
        }
        stepperRow(displayGroup, R.string.disp_zoom_nav, settings.navigationZoom, 14f, 18f) {
            settings.navigationZoom = it
        }

        toggleRow(displayGroup, R.string.disp_buildings_3d, settings.buildings3d) {
            settings.buildings3d = it
        }
        toggleRow(displayGroup, R.string.disp_labels_road, settings.labelRoads) { settings.labelRoads = it }
        toggleRow(displayGroup, R.string.disp_labels_poi, settings.labelPoi) { settings.labelPoi = it }
        toggleRow(displayGroup, R.string.disp_labels_place, settings.labelPlace) { settings.labelPlace = it }
        toggleRow(displayGroup, R.string.disp_labels_housenumber, settings.labelHouseNumber) {
            settings.labelHouseNumber = it
        }

        optionRow(displayGroup, R.string.disp_poi_density, listOf(
            getString(R.string.disp_poi_low) to AppSettings.PoiDensity.LOW,
            getString(R.string.disp_poi_normal) to AppSettings.PoiDensity.NORMAL,
            getString(R.string.disp_poi_high) to AppSettings.PoiDensity.HIGH
        ), settings.poiDensity) { settings.poiDensity = it }

        optionRow(displayGroup, R.string.disp_text_size, listOf(
            getString(R.string.disp_size_small) to AppSettings.TextScale.SMALL,
            getString(R.string.disp_size_normal) to AppSettings.TextScale.NORMAL,
            getString(R.string.disp_size_large) to AppSettings.TextScale.LARGE
        ), settings.textScale) { settings.textScale = it }

        toggleRow(displayGroup, R.string.disp_route_travelled, settings.showTravelledRoute) {
            settings.showTravelledRoute = it
        }

        colourRow(displayGroup, R.string.disp_route_colour, settings.routeColour) {
            settings.routeColour = it
        }
        colourRow(displayGroup, R.string.disp_route_alt_colour, settings.altRouteColour) {
            settings.altRouteColour = it
        }

        toggleRow(displayGroup, R.string.disp_auto_follow, settings.autoFollow) {
            settings.autoFollow = it
        }
        toggleRow(displayGroup, R.string.disp_auto_centre, settings.autoCentre) {
            settings.autoCentre = it
        }

        optionRow(displayGroup, R.string.disp_follow_return, listOf(
            getString(R.string.disp_follow_return_5) to 5,
            getString(R.string.disp_follow_return_10) to 10,
            getString(R.string.disp_follow_return_20) to 20,
            getString(R.string.disp_follow_return_off) to 0
        ), settings.followReturnSeconds) { settings.followReturnSeconds = it }

        toggleRow(displayGroup, R.string.disp_show_zoom, settings.showZoomControls) {
            settings.showZoomControls = it
        }
        toggleRow(displayGroup, R.string.disp_show_compass, settings.showCompass) {
            settings.showCompass = it
        }
        toggleRow(displayGroup, R.string.disp_show_recenter, settings.showRecenter) {
            settings.showRecenter = it
        }
        toggleRow(displayGroup, R.string.disp_show_overview, settings.showOverview) {
            settings.showOverview = it
        }

        // `Cân bằng` is the documented default; the chips make that visible
        // without a separate "reset" step.
        optionRow(displayGroup, R.string.disp_performance, listOf(
            getString(R.string.disp_perf_quality) to AppSettings.Performance.QUALITY,
            getString(R.string.disp_perf_balanced) to AppSettings.Performance.BALANCED,
            getString(R.string.disp_perf_saver) to AppSettings.Performance.SAVER
        ), settings.performance) { settings.performance = it }
    }

    private fun restoreDisplayDefaults() {
        settings.restoreDisplayDefaults()
        buildDisplayGroup()
        Toast.makeText(this, R.string.disp_restored, Toast.LENGTH_SHORT).show()
    }

    // =======================================================================
    // Speed group (§8 "Tốc độ & cảnh báo")
    // =======================================================================

    private fun buildSpeedGroup() {
        speedGroup.removeAllViews()

        optionRow(speedGroup, R.string.speed_unit, listOf(
            getString(R.string.speed_unit_kmh) to SpeedUnit.KMH,
            getString(R.string.speed_unit_mph) to SpeedUnit.MPH
        ), settings.speedUnit) { settings.speedUnit = it }

        toggleRow(speedGroup, R.string.speed_show_current, settings.showCurrentSpeed) {
            settings.showCurrentSpeed = it
        }
        toggleRow(speedGroup, R.string.speed_show_limit, settings.showSpeedLimit) {
            settings.showSpeedLimit = it
        }

        optionRow(speedGroup, R.string.speed_gps_filter, listOf(
            getString(R.string.speed_gps_filter_off) to 0,
            "2 km/h" to 2,
            "4 km/h" to 4,
            "6 km/h" to 6
        ), settings.gpsFilterKmh) { settings.gpsFilterKmh = it }

        optionRow(speedGroup, R.string.speed_smoothing, listOf(
            getString(R.string.speed_smooth_fast) to SpeedFilter.Smoothing.FAST,
            getString(R.string.speed_smooth_balanced) to SpeedFilter.Smoothing.BALANCED,
            getString(R.string.speed_smooth_smooth) to SpeedFilter.Smoothing.SMOOTH
        ), settings.speedSmoothing) { settings.speedSmoothing = it }

        toggleRow(speedGroup, R.string.speed_alerts, settings.speedAlertsEnabled) {
            settings.speedAlertsEnabled = it
        }

        optionRow(speedGroup, R.string.speed_threshold, listOf(
            getString(R.string.speed_threshold_0) to 0,
            getString(R.string.speed_threshold_5) to 5,
            getString(R.string.speed_threshold_10) to 10,
            getString(R.string.speed_threshold_15) to 15
        ), settings.overspeedThresholdKmh) { settings.overspeedThresholdKmh = it }

        optionRow(speedGroup, R.string.speed_alert_form, listOf(
            getString(R.string.speed_alert_visual) to SpeedMonitor.AlertForm.VISUAL,
            getString(R.string.speed_alert_audio) to SpeedMonitor.AlertForm.AUDIO,
            getString(R.string.speed_alert_both) to SpeedMonitor.AlertForm.BOTH
        ), settings.speedAlertForm) { settings.speedAlertForm = it }

        stepperRow(
            speedGroup,
            R.string.speed_alert_volume,
            settings.speedAlertVolume * 100f,
            0f, 100f,
            suffix = "%"
        ) { settings.speedAlertVolume = it / 100f }

        optionRow(speedGroup, R.string.speed_cooldown, listOf(
            getString(R.string.speed_cooldown_5) to 5_000L,
            getString(R.string.speed_cooldown_15) to 15_000L,
            getString(R.string.speed_cooldown_30) to 30_000L,
            getString(R.string.speed_cooldown_60) to 60_000L
        ), settings.speedAlertCooldownMs) { settings.speedAlertCooldownMs = it }

        toggleRow(speedGroup, R.string.speed_sustained, settings.sustainedOverOnly) {
            settings.sustainedOverOnly = it
        }

        // Live status: the limit provenance, so the driver can tell "no data"
        // from "broken". `--` on the HUD and this line are the only two honest
        // answers when the pack has no limit for the road.
        val source = if (!settings.showSpeedLimit) "ẩn" else speedLimitProvenance()
        speedGroup.addView(text(
            getString(R.string.speed_state, if (settings.speedAlertsEnabled) "BẬT" else "TẮT") +
                "\n" + getString(R.string.speed_limit_source, source),
            R.color.text_tertiary, 12f
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    /**
     * Where a limit could come from, in words.
     *
     * Deliberately not a number: the resolver's value depends on where the car
     * is, and inventing one here would be exactly the guesswork the brief bans.
     * The confidence wording matches [com.ts8.core.speed.SpeedLimitResolver].
     */
    private fun speedLimitProvenance(): String {
        val report = graph.packReport()
        val hasRouting = report?.routingOk ?: (graph.pack()?.routingDir() != null)
        return if (hasRouting) {
            "dữ liệu route/map (tin cậy trung bình khi chỉ có mặc định theo loại đường)"
        } else {
            "không có — hiển thị --"
        }
    }

    // =======================================================================
    // Row builders
    // =======================================================================

    private fun label(target: LinearLayout, resId: Int) {
        target.addView(text(getString(resId), R.color.text_secondary, 13f).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
    }

    /**
     * A segmented control: one chip per option, exactly one selected.
     *
     * Built from `TextView`s rather than a `RadioGroup` because the design uses
     * pill chips, not radio buttons, and a horizontal chip row fits four options
     * within 1024 dp without wrapping.
     */
    private fun <T> optionRow(
        target: LinearLayout,
        titleRes: Int,
        options: List<Pair<String, T>>,
        selected: T,
        onSelect: (T) -> Unit
    ) {
        label(target, titleRes)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        for ((labelText, value) in options) {
            row.addView(chip(labelText, value == selected) {
                onSelect(value)
                rebuild()
            })
        }
        target.addView(row)
    }

    /**
     * A two-way switch drawn as chips.
     *
     * Not a `Switch`: the platform widget on this ROM renders in the theme's
     * disabled colour and is nearly invisible on the dark card. Chips keep the
     * 44 dp touch target and read at a glance.
     */
    private fun toggleRow(
        target: LinearLayout,
        titleRes: Int,
        value: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        label(target, titleRes)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(chip(getString(R.string.qs_on), value) {
            onChange(true)
            rebuild()
        })
        row.addView(chip(getString(R.string.qs_off), !value) {
            onChange(false)
            rebuild()
        })
        target.addView(row)
    }

    /** A numeric stepper with minus/plus chips and the live value between them. */
    private fun stepperRow(
        target: LinearLayout,
        titleRes: Int,
        value: Float,
        min: Float,
        max: Float,
        suffix: String = "",
        onChange: (Float) -> Unit
    ) {
        label(target, titleRes)
        val step = if (max <= 20f) 1f else 10f
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val display = text(formatNumber(value) + suffix, R.color.text_primary, 14f).apply {
            minWidth = dp(64)
            gravity = Gravity.CENTER
        }
        row.addView(chip("−", false) {
            onChange((value - step).coerceAtLeast(min))
            rebuild()
        })
        row.addView(display)
        row.addView(chip("+", false) {
            onChange((value + step).coerceAtMost(max))
            rebuild()
        })
        target.addView(row)
    }

    /**
     * A fixed palette rather than a free picker.
     *
     * A full HSV picker is a second screen, a second Activity result and a
     * colour that can be invisible against the map. Four presets chosen for
     * contrast on both day and night palettes cover the actual need.
     */
    private fun colourRow(
        target: LinearLayout,
        titleRes: Int,
        current: Int,
        onChange: (Int) -> Unit
    ) {
        label(target, titleRes)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        for ((name, colour) in COLOUR_PRESETS) {
            row.addView(chip(name, colour == current) {
                onChange(colour)
                rebuild()
            })
        }
        target.addView(row)
    }

    private fun chip(labelText: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = labelText
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(40)
            setTextColor(
                ContextCompat.getColor(
                    this@MapManagementActivity,
                    if (selected) R.color.text_on_accent else R.color.text_secondary
                )
            )
            setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_chip)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun rebuild() {
        buildDisplayGroup()
        buildSpeedGroup()
    }

    // =======================================================================
    // Small helpers
    // =======================================================================

    private fun text(value: String, colourRes: Int, sizeSp: Float): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(ContextCompat.getColor(this@MapManagementActivity, colourRes))
        }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(
            Locale.US,
            if (value >= 100) "%.0f %s" else "%.1f %s",
            value, units[unit]
        )
    }

    private fun formatNumber(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(Locale.US, "%.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK = 0x7A01

        /** How long to wait before re-reading the validation report. */
        private const val REFRESH_AFTER_VALIDATION_MS = 500L

        /** Characters unsafe in a cache file name. */
        private val NOT_SAFE = Regex("[^A-Za-z0-9._-]")

        /** Route-line presets: readable on both palette halves. */
        private val COLOUR_PRESETS = listOf(
            "Xanh dương" to 0xFF3B82F6.toInt(),
            "Xanh lá" to 0xFF22C55E.toInt(),
            "Cam" to 0xFFF59E0B.toInt(),
            "Xám" to 0xFF6C7A91.toInt()
        )

        fun intentTo(context: Context): Intent =
            Intent(context, MapManagementActivity::class.java)
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/MusicCardView.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import com.ts8.launcher.R
import java.util.concurrent.Executors

/**
 * The music card that Home and the navigation screen both show.
 *
 * **Passive by design.** The card never touches `MusicRuntime`, the player or a
 * service. A screen binds once, and pushes each `PlaybackState` in with [bind];
 * taps leave through the callbacks below. If the card subscribed to the player
 * itself, three screens showing a card would mean three subscriptions and a
 * state that can disagree between them the moment one card's listener is
 * registered a frame later.
 *
 * **Artwork is real or absent.** The design shows an album cover. The engine
 * stores a MediaStore album id, not a bitmap, so the cover is resolved through
 * the platform's album-art endpoint for MediaStore tracks. USB tracks have no
 * album id and this build extracts no embedded pictures, so they show the
 * neutral tile. Drawing a stock cover would be claiming artwork the file does
 * not have.
 *
 * **Two shapes, one view.** `musicCompact` in XML shrinks the artwork block and
 * drops the heart/shuffle/repeat/queue row, which is what the navigation screen
 * needs to give the maneuver card its space.
 */
class MusicCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** What a tap on a control should do. The screen decides, not the card. */
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onToggleFavorite: (() -> Unit)? = null
    var onToggleShuffle: (() -> Unit)? = null
    var onCycleRepeat: (() -> Unit)? = null
    var onOpenQueue: (() -> Unit)? = null

    private val artwork: ImageView
    private val artworkPlaceholder: ImageView
    private val artworkFrame: FrameLayout
    private val title: TextView
    private val subtitle: TextView
    private val position: ProgressBar
    private val positionText: TextView
    private val durationText: TextView
    private val playPause: ImageButton
    private val nextButton: ImageButton
    private val previousButton: ImageButton
    private val favorite: ImageButton
    private val shuffle: ImageButton
    private val repeatButton: ImageButton
    private val errorText: TextView

    /** Guards against binding the same track twice while a decode is running. */
    private var artworkTrackId: Long = -1

    private val artworkLoader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-artwork").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    init {
        LayoutInflater.from(context).inflate(R.layout.view_music_card, this, true)

        artwork = findViewById(R.id.artwork)
        artworkPlaceholder = findViewById(R.id.artworkPlaceholder)
        artworkFrame = findViewById(R.id.artworkFrame)
        title = findViewById(R.id.trackTitle)
        subtitle = findViewById(R.id.trackSubtitle)
        position = findViewById(R.id.position)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)
        playPause = findViewById(R.id.btnPlayPause)
        nextButton = findViewById(R.id.btnNext)
        previousButton = findViewById(R.id.btnPrevious)
        favorite = findViewById(R.id.btnFavorite)
        shuffle = findViewById(R.id.btnShuffle)
        repeatButton = findViewById(R.id.btnRepeat)
        errorText = findViewById(R.id.errorText)

        val a = context.obtainStyledAttributes(attrs, R.styleable.MusicCardView)
        val compact = a.getBoolean(R.styleable.MusicCardView_musicCompact, false)
        a.recycle()

        if (compact) {
            // The design's compact card keeps a secondary row, but only three
            // of the four icons: heart, shuffle, queue. Repeat is the one it
            // drops — measured on the Navigation panel, where the row under the
            // transport controls holds exactly three glyphs at x 1342, 1408 and
            // 1481 in sheet coordinates, and none of them is the circular-arrow
            // repeat mark.
            //
            // This used to hide the whole row. That was wrong in both
            // directions: it removed three icons the design keeps, and it left
            // the card's bottom edge empty where the sheet is not.
            repeatButton.visibility = GONE
            artworkFrame.minimumHeight =
                resources.getDimensionPixelSize(R.dimen.music_artwork_size_compact)
            val small = resources.getDimensionPixelSize(R.dimen.music_artwork_size_compact) / 2
            val lp = artworkPlaceholder.layoutParams
            lp.width = small
            lp.height = small
            artworkPlaceholder.layoutParams = lp
        }

        playPause.setOnClickListener { onPlayPause?.invoke() }
        nextButton.setOnClickListener { onNext?.invoke() }
        previousButton.setOnClickListener { onPrevious?.invoke() }
        favorite.setOnClickListener { onToggleFavorite?.invoke() }
        shuffle.setOnClickListener { onToggleShuffle?.invoke() }
        repeatButton.setOnClickListener { onCycleRepeat?.invoke() }
        findViewById<ImageButton>(R.id.btnQueue).setOnClickListener { onOpenQueue?.invoke() }
    }

    /**
     * Renders a state snapshot.
     *
     * Called on the main thread with every `PlaybackState` the screen receives,
     * up to once a second during playback. Everything here is a property write,
     * so that is cheap; the only real work is the artwork decode, which is
     * memoised by track id.
     */
    fun bind(state: PlaybackState) {
        val track = state.track
        if (track == null) {
            title.setText(R.string.home_music_empty)
            subtitle.setText(R.string.home_music_hint)
            position.progress = 0
            positionText.text = ClockFormat.trackPosition(0)
            durationText.text = ClockFormat.trackPosition(0)
            setArtworkPlaceholder()
            artworkTrackId = -1
        } else {
            title.text = track.title.ifBlank { context.getString(R.string.music_unknown_track) }
            subtitle.text = track.subtitle.ifBlank {
                context.getString(R.string.music_unknown_artist)
            }
            position.progress = (state.progressFraction * PROGRESS_MAX).toInt()
            positionText.text = ClockFormat.trackPosition(state.positionMs)
            durationText.text = ClockFormat.trackPosition(state.durationMs)
            if (artworkTrackId != track.id) {
                artworkTrackId = track.id
                loadArtwork(track)
            }
        }

        playPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playPause.contentDescription = context.getString(
            if (state.isPlaying) R.string.music_pause else R.string.music_play
        )

        // A transport with nothing in the queue is a dead tap, so it is
        // disabled rather than silently doing nothing.
        val enabled = track != null
        playPause.isEnabled = enabled
        playPause.alpha = if (enabled) 1f else 0.4f
        nextButton.isEnabled = enabled
        previousButton.isEnabled = enabled

        applyFavorite()
        shuffle.setColorFilter(
            context.getColor(if (state.shuffle) R.color.accent else R.color.text_secondary)
        )
        repeatButton.setColorFilter(
            context.getColor(
                if (state.repeatMode == RepeatMode.OFF) R.color.text_secondary else R.color.accent
            )
        )
        repeatButton.contentDescription = context.getString(
            if (state.repeatMode == RepeatMode.ONE) {
                R.string.music_repeat_one
            } else {
                R.string.music_repeat
            }
        )

        val error = state.error
        if (error == null) {
            errorText.visibility = GONE
        } else {
            errorText.visibility = VISIBLE
            errorText.text = describeError(error)
        }
    }

    /** Mirrors the library's favourite flag; the screen sets it. */
    var isFavorite: Boolean = false
        set(value) {
            field = value
            applyFavorite()
        }

    private fun applyFavorite() {
        favorite.setImageResource(
            if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        favorite.setColorFilter(
            context.getColor(if (isFavorite) R.color.accent else R.color.text_secondary)
        )
    }

    private fun describeError(error: PlaybackError): String = context.getString(
        when (error) {
            PlaybackError.SOURCE_UNAVAILABLE -> R.string.music_source_unavailable
            PlaybackError.UNSUPPORTED_FORMAT -> R.string.qs_unavailable
            PlaybackError.DECODE_FAILED -> R.string.generic_error
            // Focus loss is not an error to report: the player resumes by
            // itself when the call or the other app is done.
            PlaybackError.AUDIO_FOCUS_LOST -> R.string.music_pause
            // A stream failure is not a USB failure, and the card has room for
            // one line: the sentence names the network so the driver does not
            // go looking for a stick.
            PlaybackError.STREAM_UNAVAILABLE -> R.string.music_stream_unavailable
            PlaybackError.STREAM_INTERRUPTED -> R.string.music_stream_interrupted
            PlaybackError.UNKNOWN -> R.string.generic_error
        }
    )

    // -- artwork -----------------------------------------------------------

    /**
     * Resolves album art for a MediaStore track.
     *
     * The URI is the platform's own album-art endpoint, which exists on API 23
     * — deprecated at 29 but this app runs on 23, where it is the only route. A
     * USB track has no album id (the scanner assigns a synthetic one), so it
     * never reaches here and keeps the placeholder.
     */
    private fun loadArtwork(track: Track) {
        val albumId = track.albumId
        if (albumId == null || albumId <= 0L) {
            setArtworkPlaceholder()
            return
        }
        val uri = ContentUris.withAppendedId(ARTWORK_URI, albumId)
        artworkLoader.execute {
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
            main.post {
                // A decode that finished after the track changed must not paint
                // the previous cover over the new title.
                if (artworkTrackId != track.id) return@post
                if (bitmap == null) setArtworkPlaceholder() else setArtwork(bitmap)
            }
        }
    }

    private fun setArtwork(bitmap: Bitmap) {
        artwork.setImageBitmap(bitmap)
        artwork.visibility = VISIBLE
        artworkPlaceholder.visibility = GONE
    }

    private fun setArtworkPlaceholder() {
        artwork.setImageDrawable(null)
        artwork.visibility = GONE
        artworkPlaceholder.visibility = VISIBLE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The card is recreated with its screen; keeping a decode thread alive
        // for a view that is gone would leak for the life of the process.
        artworkLoader.shutdownNow()
    }

    companion object {
        private const val PROGRESS_MAX = 1000
        private val ARTWORK_URI: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

END FILE

## app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt

BEGIN FILE
package com.ts8.launcher.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ts8.core.music.MusicBackend
import com.ts8.core.music.MusicCollection
import com.ts8.core.music.MusicLibrary
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.music.Track
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.MusicRuntime

/**
 * Music — design state 3, fullscreen.
 *
 * **Playback is not owned here.** The player lives in
 * [com.ts8.launcher.service.MusicPlaybackService] so it survives this Activity
 * being destroyed and recreated, which on a head unit happens on every USB
 * mount and every theme change. This screen binds to it, renders its state and
 * sends commands; killing the screen leaves the music playing, as it must.
 *
 * **The library is paged off the main thread.** Every query below is a SQLite
 * read. They run on [AppGraph.io] and are posted back, and each load carries a
 * generation number so a tab switch during a slow query cannot paint the
 * previous tab's rows over the new one.
 *
 * **What the design shows and this screen does not fabricate.** The `FLAC`
 * badge is filled from the track's own MIME type and hidden when there is none;
 * the artwork is a real album cover from MediaStore or the neutral tile; the
 * left column's title falls back to "Chưa phát nhạc" when nothing is loaded.
 * There is no sample track, no stock cover, and no invented duration.
 */
class MusicFullscreenActivity : BaseScreenActivity() {

    override val screen: BottomNavView.Screen = BottomNavView.Screen.MUSIC

    private lateinit var graph: AppGraph
    private lateinit var music: MusicRuntime

    private lateinit var artworkFrame: View
    private lateinit var artwork: ImageView
    private lateinit var artworkPlaceholder: ImageView
    private lateinit var codecBadge: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackSubtitle: TextView
    private lateinit var trackMeta: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var libraryList: RecyclerView
    private lateinit var libraryEmpty: View
    private lateinit var libraryEmptyText: TextView
    private lateinit var libraryEmptyHint: TextView
    private lateinit var libraryLoading: View
    private lateinit var libraryFooter: TextView
    private lateinit var tabs: Map<Tab, TextView>

    private lateinit var adapter: MusicRowAdapter
    private lateinit var artLoader: AlbumArtLoader
    private lateinit var searchField: android.widget.EditText

    private val main = Handler(Looper.getMainLooper())

    /** Which tab the right column is showing. */
    private var tab: Tab = Tab.SONGS

    /** Bumped on every library load; a stale answer is dropped by number. */
    private var loadGeneration = 0

    /** Rows currently rendered, so the playing marker can be re-applied. */
    private var currentRows: List<MusicRowAdapter.Row> = emptyList()

    /**
     * The online results for the current query, kept so a tap can resolve one
     * without searching again.
     *
     * Keyed by the row key the adapter holds, because the adapter's payload is
     * deliberately opaque — it reports the row it rendered, and this is what
     * turns that back into the provider's own object.
     */
    private var onlineRows: Map<String, com.ts8.core.music.online.OnlineTrack> = emptyMap()

    /**
     * True while the field has text, i.e. the right column shows search results
     * rather than the tabbed library.
     *
     * The field is the mode: there is no third control to get out of sync with,
     * and clearing it is the documented way back to the tabs.
     */
    private var searching = false

    /** Where the query in flight came from, so the loader can be cancelled. */
    private val searchDebounce = Runnable { runOnlineSearch() }

    /** True while the driver is dragging the seek bar, so playback does not
     *  yank the thumb out from under them. */
    private var scrubbing = false

    /** Set while a programmatic seek is in flight, to ignore its echo. */
    private var playingTrackId: Long? = null

    private enum class Tab { PLAYLIST, SONGS, ALBUMS, ARTISTS }

    // -- player --------------------------------------------------------------

    private val observer = object : MusicRuntime.Observer {
        override fun onPlaybackState(state: PlaybackState) {
            renderPlayback(state)
        }

        override fun onBackendChanged(backend: MusicBackend?) {
            renderPlayback(music.state())
            // A backend appearing means the scan may have finished, so the
            // library the driver is looking at can now be stale.
            loadLibrary()
        }
    }

    // -- lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_fullscreen)
        bindNavigation()

        graph = AppGraph.require()
        music = MusicRuntime.get(this)

        bindViews()
        artLoader = AlbumArtLoader(this, targetPx())
        buildList()
        wireTabs()
        wireTransport()
        wireSeekBar()
        wireSearch()

        renderPlayback(music.state())
        loadLibrary()
    }

    private fun bindViews() {
        artworkFrame = findViewById(R.id.artworkFrame)
        artwork = findViewById(R.id.artwork)
        artworkPlaceholder = findViewById(R.id.artworkPlaceholder)
        codecBadge = findViewById(R.id.codecBadge)
        trackTitle = findViewById(R.id.trackTitle)
        trackSubtitle = findViewById(R.id.trackSubtitle)
        trackMeta = findViewById(R.id.trackMeta)
        seekBar = findViewById(R.id.seekBar)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        libraryList = findViewById(R.id.libraryList)
        libraryEmpty = findViewById(R.id.libraryEmpty)
        libraryEmptyText = findViewById(R.id.libraryEmptyText)
        libraryEmptyHint = findViewById(R.id.libraryEmptyHint)
        libraryLoading = findViewById(R.id.libraryLoading)
        libraryFooter = findViewById(R.id.libraryFooter)
        searchField = findViewById(R.id.musicSearch)
    }

    override fun onStart() {
        super.onStart()
        music.attach(observer)
    }

    override fun onResume() {
        super.onResume()
        renderPlayback(music.state())
    }

    override fun onPause() {
        // The resume point is written here rather than in `onStop` because the
        // player may be killed with the process at any point after that.
        runCatching { music.backend?.player?.saveCheckpoint() }
        super.onPause()
    }

    override fun onStop() {
        music.detach(observer)
        super.onStop()
    }

    override fun onDestroy() {
        artLoader.shutdown()
        super.onDestroy()
    }

    // -- rendering the player ------------------------------------------------

    private fun renderPlayback(state: PlaybackState) {
        val track = state.track
        if (track == null) {
            trackTitle.setText(R.string.home_music_empty)
            trackSubtitle.setText(R.string.home_music_hint)
            trackMeta.visibility = View.GONE
            codecBadge.visibility = View.GONE
            seekBar.progress = 0
            seekBar.isEnabled = false
            positionText.text = ClockFormat.trackPosition(0)
            durationText.text = ClockFormat.trackPosition(0)
            showArtworkPlaceholder()
            playingTrackId = null
        } else {
            trackTitle.text = track.title.ifBlank { getString(R.string.music_unknown_track) }
            trackSubtitle.text = track.subtitle.ifBlank {
                getString(R.string.music_unknown_artist)
            }
            renderMeta(track)
            renderCodecBadge(track)

            if (!scrubbing) {
                seekBar.isEnabled = true
                val duration = if (state.durationMs > 0) state.durationMs else track.durationMs
                seekBar.progress = if (duration <= 0L) 0 else {
                    ((state.positionMs.toFloat() / duration) * SEEK_MAX).toInt()
                        .coerceIn(0, SEEK_MAX)
                }
                positionText.text = ClockFormat.trackPosition(state.positionMs)
                durationText.text = ClockFormat.trackPosition(duration)
            }

            if (playingTrackId != track.id) {
                playingTrackId = track.id
                loadCover(track)
            }
            refreshPlayingRow(track.id)
        }

        btnPlayPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlayPause.contentDescription =
            getString(if (state.isPlaying) R.string.music_pause else R.string.music_play)
        btnPlayPause.alpha = if (track != null) 1f else 0.4f
        btnPlayPause.isEnabled = track != null
        btnNext.isEnabled = track != null
        btnPrevious.isEnabled = track != null

        val favourite = track?.let { t ->
            runCatching { music.backend?.library?.isFavorite(t.id) }.getOrNull()
        } ?: false
        btnFavorite.setImageResource(
            if (favourite) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        btnFavorite.setColorFilter(
            getColor(if (favourite) R.color.accent else R.color.text_secondary)
        )

        btnShuffle.setColorFilter(
            getColor(if (state.shuffle) R.color.accent else R.color.text_secondary)
        )
        btnRepeat.setColorFilter(
            getColor(
                if (state.repeatMode == RepeatMode.OFF) R.color.text_secondary else R.color.accent
            )
        )
        btnRepeat.contentDescription = getString(
            if (state.repeatMode == RepeatMode.ONE) R.string.music_repeat_one else R.string.music_repeat
        )

        val error = state.error
        if (error != null) {
            // Errors go where the subtitle was, so the transport never moves.
            trackSubtitle.text = describeError(error)
        }
    }

    /** Album and year, from the track's own tags; hidden when untagged. */
    private fun renderMeta(track: Track) {
        val parts = buildList {
            track.album?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (track.year > 0) add(track.year.toString())
        }
        if (parts.isEmpty()) {
            trackMeta.visibility = View.GONE
        } else {
            trackMeta.text = parts.joinToString(" · ")
            trackMeta.visibility = View.VISIBLE
        }
    }

    /**
     * The design's `FLAC` badge, driven by the file's own type.
     *
     * Only shown for a lossless container, and only when the engine actually
     * reported one. A track with no MIME type says nothing rather than assuming
     * a format — the badge is a claim about the audio, so it must be true.
     */
    private fun renderCodecBadge(track: Track) {
        val label = when (track.mimeType?.lowercase()) {
            "audio/flac", "audio/x-flac" -> "FLAC"
            "audio/wav", "audio/x-wav", "audio/wave" -> "WAV"
            else -> null
        }
        if (label == null) {
            codecBadge.visibility = View.GONE
        } else {
            codecBadge.text = label
            codecBadge.visibility = View.VISIBLE
        }
    }

    private fun describeError(error: com.ts8.core.music.PlaybackError): String =
        getString(
            when (error) {
                com.ts8.core.music.PlaybackError.SOURCE_UNAVAILABLE -> R.string.music_usb_removed
                com.ts8.core.music.PlaybackError.UNSUPPORTED_FORMAT -> R.string.qs_unavailable
                com.ts8.core.music.PlaybackError.DECODE_FAILED -> R.string.generic_error
                com.ts8.core.music.PlaybackError.AUDIO_FOCUS_LOST -> R.string.music_pause
                com.ts8.core.music.PlaybackError.STREAM_UNAVAILABLE ->
                    R.string.music_stream_unavailable
                com.ts8.core.music.PlaybackError.STREAM_INTERRUPTED ->
                    R.string.music_stream_interrupted
                com.ts8.core.music.PlaybackError.UNKNOWN -> R.string.generic_error
            }
        )

    // -- artwork -------------------------------------------------------------

    private fun targetPx(): Int =
        resources.getDimensionPixelSize(R.dimen.music_artwork_large)

    private fun loadCover(track: Track) {
        val cached = artLoader.load(track.albumId) { albumId, bitmap ->
            if (playingTrackId != null && track.albumId == albumId) showArtwork(bitmap)
        }
        if (cached != null) showArtwork(cached) else showArtworkPlaceholder()
    }

    private fun showArtwork(bitmap: android.graphics.Bitmap) {
        artwork.setImageBitmap(bitmap)
        artwork.visibility = View.VISIBLE
        artworkPlaceholder.visibility = View.GONE
    }

    private fun showArtworkPlaceholder() {
        artwork.setImageDrawable(null)
        artwork.visibility = View.GONE
        artworkPlaceholder.visibility = View.VISIBLE
    }

    // -- transport -----------------------------------------------------------

    private fun wireTransport() {
        btnPlayPause.setOnClickListener { music.toggle() }
        btnNext.setOnClickListener { music.next() }
        btnPrevious.setOnClickListener { music.previous() }
        btnShuffle.setOnClickListener { music.setShuffle(!music.state().shuffle) }
        btnRepeat.setOnClickListener { music.setRepeatMode(music.state().repeatMode.next()) }
        btnFavorite.setOnClickListener {
            val track = music.state().track ?: return@setOnClickListener
            val library = music.backend?.library ?: return@setOnClickListener
            main.post {
                val nowFavourite = !library.isFavorite(track.id)
                library.setFavorite(track.id, nowFavourite)
                renderPlayback(music.state())
            }
        }
    }

    /**
     * A seek bar that does not fight the driver.
     *
     * `onProgressChanged` fires for programmatic updates too, so the seek is
     * only issued `fromUser`. Without the `scrubbing` guard, the player's own
     * position push would move the thumb back mid-drag and the drag would
     * become unusable.
     */
    private fun wireSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = effectiveDuration()
                if (duration <= 0L) return
                positionText.text =
                    ClockFormat.trackPosition((progress.toLong() * duration) / SEEK_MAX)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                val duration = effectiveDuration()
                if (duration > 0L) {
                    music.seekTo((bar.progress.toLong() * duration) / SEEK_MAX)
                }
            }
        })
    }

    private fun effectiveDuration(): Long {
        val state = music.state()
        return if (state.durationMs > 0) state.durationMs else state.track?.durationMs ?: 0L
    }

    // -- search: local and online (§13) --------------------------------------

    /**
     * Wires the single field that drives both catalogues.
     *
     * **Why one field and not two searches.** A driver in a car types once. The
     * app then asks the local library and every configured provider for the
     * same words and shows the answers as two labelled groups, `Trên thiết bị`
     * and `Trực tuyến`. Two separate search boxes would make the driver decide
     * which catalogue has a song before they know the answer — which is
     * exactly the question the search was supposed to answer.
     *
     * **Why the debounce differs from the online call's own.** The local query
     * is a SQLite read and runs on every keystroke at once; the online call is
     * a network round trip per provider and waits [ONLINE_DEBOUNCE_MS]. Firing
     * a round trip per character would be four providers × six characters on a
     * phone hotspot.
     */
    private fun wireSearch() {
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim().orEmpty()
                main.removeCallbacks(searchDebounce)
                if (text.isEmpty()) {
                    searching = false
                    onlineRows = emptyMap()
                    loadLibrary()
                    return
                }
                searching = true
                // Local results are immediate: they are already on disk, and a
                // driver should see their own music the moment they type its
                // name rather than after a network timeout.
                showLocalMatches(text)
                main.postDelayed(searchDebounce, ONLINE_DEBOUNCE_MS)
            }
        })
        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                main.removeCallbacks(searchDebounce)
                runOnlineSearch()
                true
            } else {
                false
            }
        }
    }

    /**
     * Renders the local matches at once, with a "searching online" hint below.
     *
     * The hint is a row, not a spinner: it is the honest statement that a
     * second group is still coming, and it keeps the list from jumping when the
     * online rows arrive (the heading and the placeholder row are replaced
     * rather than inserted).
     */
    private fun showLocalMatches(query: String) {
        val library = music.backend?.library
        val generation = ++loadGeneration
        if (library == null) {
            showLoading(true)
            return
        }
        graph.io().execute {
            val tracks = runCatching { library.searchTracks(query, PAGE_SIZE) }.getOrDefault(emptyList())
            main.post {
                if (generation != loadGeneration || !searching) return@post
                val local = tracks.map { MusicRowAdapter.trackRow(it, it.id == playingTrackId) }
                renderSearch(local, online = emptyList(), pending = true, query = query)
            }
        }
    }

    /** Kicks the online half of the search. */
    private fun runOnlineSearch() {
        val query = searchField.text?.toString()?.trim().orEmpty()
        if (query.isEmpty() || !searching) return
        val generation = loadGeneration
        showOnlineLoading(true)
        graph.onlineMusic.search(query) { snapshot ->
            if (!searching || generation != loadGeneration) return@search
            showOnlineLoading(false)
            onlineRows = snapshot.tracks.associateBy {
                "o:${it.providerId}:${it.id}"
            }
            // Re-run the local half so the two groups are rendered from one
            // consistent pass: the driver may have typed more since the local
            // query above, and a local list from an older prefix beside an
            // online list from the current one is a list that disagrees with
            // itself.
            val library = music.backend?.library
            if (library == null) {
                renderSearch(emptyList(), snapshot.tracks, pending = false, query = query,
                    failure = snapshot.failure)
                return@search
            }
            graph.io().execute {
                val tracks = runCatching { library.searchTracks(query, PAGE_SIZE) }
                    .getOrDefault(emptyList())
                main.post {
                    if (!searching || generation != loadGeneration) return@post
                    renderSearch(
                        local = tracks.map {
                            MusicRowAdapter.trackRow(it, it.id == playingTrackId)
                        },
                        online = snapshot.tracks,
                        pending = false,
                        query = query,
                        failure = snapshot.failure
                    )
                }
            }
        }
    }

    /**
     * Draws the two groups, with a heading for each that has content.
     *
     * A group with no results and no failure is *omitted*, not drawn empty: an
     * empty `Trực tuyến` heading under a working local list would read as "the
     * internet found nothing", which the app does not know. A group with a
     * failure, however, is drawn with the reason — that is the case where the
     * driver must be told the search did not happen.
     */
    private fun renderSearch(
        local: List<MusicRowAdapter.Row>,
        online: List<com.ts8.core.music.online.OnlineTrack>,
        pending: Boolean,
        query: String,
        failure: String? = null
    ) {
        val rows = ArrayList<MusicRowAdapter.Row>(local.size + online.size + 4)
        if (local.isNotEmpty()) {
            rows.add(MusicRowAdapter.headerRow(getString(R.string.music_search_local_group)))
            rows.addAll(local)
        }
        val onlineRowsMapped = online.map { MusicRowAdapter.onlineRow(it, providerLabel(it.providerId)) }
        if (onlineRowsMapped.isNotEmpty()) {
            rows.add(MusicRowAdapter.headerRow(getString(R.string.music_search_online_group)))
            rows.addAll(onlineRowsMapped)
        }

        val message = when {
            rows.isNotEmpty() -> null
            pending -> getString(R.string.music_online_loading)
            failure != null -> failure
            else -> getString(R.string.music_search_empty, query)
        }

        if (message != null) {
            currentRows = emptyList()
            adapter.submit(emptyList())
            libraryList.visibility = View.GONE
            libraryEmpty.visibility = View.VISIBLE
            libraryEmptyText.text = message
            libraryEmptyHint.visibility = View.GONE
        } else {
            currentRows = rows
            adapter.submit(rows)
            libraryList.visibility = View.VISIBLE
            libraryEmpty.visibility = View.GONE
        }
        libraryFooter.visibility = View.GONE
        applyTabVisibility(!searching)
    }

    /**
     * The provider's name for a result.
     *
     * Duplicated from `OnlineMusicRuntime`'s own label on purpose: that one is
     * used where the gateway's ids are the only thing available, this one where
     * a screen has an [com.ts8.core.music.online.OnlineTrack] in hand. A single
     * shared helper would need the runtime as a dependency of the adapter,
     * which is the coupling the adapter's opaque payload exists to avoid.
     */
    private fun providerLabel(providerId: String): String = when (providerId) {
        "radio-browser" -> "Đài phát thanh"
        "podcast" -> "Podcast"
        "subsonic" -> "Navidrome"
        else -> providerId
    }

    private fun showOnlineLoading(loading: Boolean) {
        // The footer is where a one-line status belongs: it does not move the
        // list, and it disappears the moment the answer arrives.
        if (loading) {
            libraryFooter.text = getString(R.string.music_online_loading)
            libraryFooter.visibility = View.VISIBLE
        } else if (!searching) {
            libraryFooter.visibility = View.GONE
        }
    }

    /** Tabs are meaningless while a query is showing; the field is the mode. */
    private fun applyTabVisibility(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        tabs.values.forEach { it.visibility = visibility }
    }

    // -- library -------------------------------------------------------------

    private fun buildList() {
        adapter = MusicRowAdapter(artLoader)
        adapter.onRowClick = { row -> onRowClicked(row) }
        libraryList.layoutManager = LinearLayoutManager(this)
        libraryList.adapter = adapter
        // A row height is fixed, so the list can skip a measure pass per scroll
        // frame — worth doing on this CPU.
        libraryList.setHasFixedSize(true)
    }

    private fun wireTabs() {
        tabs = mapOf(
            Tab.PLAYLIST to findViewById(R.id.tabPlaylist),
            Tab.SONGS to findViewById(R.id.tabSongs),
            Tab.ALBUMS to findViewById(R.id.tabAlbums),
            Tab.ARTISTS to findViewById(R.id.tabArtists)
        )
        tabs.forEach { (tab, view) ->
            view.setOnClickListener {
                this.tab = tab
                applyTabSelection()
                loadLibrary()
            }
        }
        applyTabSelection()
    }

    private fun applyTabSelection() {
        tabs.forEach { (t, view) -> view.isSelected = t == tab }
    }

    private fun loadLibrary() {
        val backend = music.backend
        val generation = ++loadGeneration
        if (backend == null) {
            // The service has not connected yet. Say "loading" rather than
            // "empty": the difference matters, one of them is a false claim.
            showLoading(true)
            return
        }
        val library = backend.library
        val tabAtStart = tab
        showLoading(true)
        graph.io().execute {
            val loaded = runCatching {
                // Both numbers in one pass: the footer is a count and a sum,
                // and two separate trips to SQLite to build one line is a
                // round trip the panel does not need.
                Batch(
                    rows = queryTab(library, tabAtStart),
                    trackCount = library.trackCount(),
                    totalMs = library.totalDurationMs()
                )
            }
            main.post {
                if (generation != loadGeneration) return@post
                showLoading(false)
                loaded.onSuccess { batch ->
                    renderRows(batch.rows)
                    renderFooter(batch.trackCount, batch.totalMs)
                }.onFailure { renderLibraryFailure() }
            }
        }
    }

    /** Everything one library load produces, computed once on the IO thread. */
    private class Batch(val rows: List<MusicRowAdapter.Row>, val trackCount: Int, val totalMs: Long)

    private fun renderFooter(trackCount: Int, totalMs: Long) {
        if (trackCount <= 0) {
            libraryFooter.visibility = View.GONE
            return
        }
        libraryFooter.text = getString(
            R.string.music_library_footer,
            trackCount,
            ClockFormat.duration((totalMs / 1000).toInt())
        )
        libraryFooter.visibility = View.VISIBLE
    }

    /** The query for one tab. Always called on an IO thread. */
    private fun queryTab(library: MusicLibrary, tab: Tab): List<MusicRowAdapter.Row> {
        val countLabel: (Int) -> String = { getString(R.string.music_track_count, it) }
        return when (tab) {
            Tab.SONGS -> library.allTracks(0, PAGE_SIZE).map { track ->
                MusicRowAdapter.trackRow(track, playing = track.id == playingTrackId)
            }

            Tab.PLAYLIST -> {
                // "Danh sách phát" is the queue that is actually loaded, which is
                // what the design's highlighted row means. The player exposes it
                // by state, so the current track plus recently played is the
                // honest content until a persisted queue exists.
                library.recentlyPlayed(PAGE_SIZE).map { track ->
                    MusicRowAdapter.trackRow(track, playing = track.id == playingTrackId)
                }
            }

            Tab.ALBUMS -> library.albums().map { MusicRowAdapter.collectionRow(it, countLabel) }

            Tab.ARTISTS -> library.artists().map { MusicRowAdapter.collectionRow(it, countLabel) }
        }
    }

    private fun renderRows(rows: List<MusicRowAdapter.Row>) {
        currentRows = rows
        adapter.submit(rows)
        if (rows.isEmpty()) {
            libraryList.visibility = View.GONE
            libraryEmpty.visibility = View.VISIBLE
            // "Nothing here" differs by tab: an empty album list on a unit whose
            // songs did load is not the same statement as an empty library, so
            // the hint is only offered where adding music would help.
            libraryEmptyText.setText(R.string.music_empty)
            libraryEmptyHint.visibility =
                if (tab == Tab.SONGS || tab == Tab.PLAYLIST) View.VISIBLE else View.GONE
        } else {
            libraryList.visibility = View.VISIBLE
            libraryEmpty.visibility = View.GONE
        }
    }

    private fun renderLibraryFailure() {
        adapter.submit(emptyList())
        libraryList.visibility = View.GONE
        libraryEmpty.visibility = View.VISIBLE
        // A failed query is not "no music"; the storage may be unreadable.
        libraryEmptyText.setText(R.string.music_source_unavailable)
        libraryEmptyHint.visibility = View.GONE
    }

    private fun showLoading(loading: Boolean) {
        libraryLoading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            libraryEmpty.visibility = View.GONE
        }
    }

    /**
     * Rebuilds only the playing flags, without re-querying.
     *
     * Called on every state push — that is, once a second during playback.
     * A full reload per second would be a SQLite query per second for a change
     * that is one boolean per row.
     */
    private fun refreshPlayingRow(trackId: Long) {
        val updated = currentRows.map { row ->
            val playing = row.track?.id == trackId
            if (playing == row.playing) row else row.copy(playing = playing)
        }
        if (updated != currentRows) {
            currentRows = updated
            adapter.submit(updated)
        }
    }

    private fun onRowClicked(row: MusicRowAdapter.Row) {
        // A heading is not a target; the click listener is disabled on it too,
        // but a keyboard/d-pad activation can still reach the callback.
        if (row.header) return
        val collection = row.collection
        if (collection != null) {
            openCollection(collection)
            return
        }
        val track = row.track
        if (track != null) {
            playLocalFromRow(track)
            return
        }
        // An online row: its payload is the provider's own track, held in
        // `onlineRows`. Resolving it costs a network round trip, so it happens
        // here, on tap, and not during the bind.
        val online = onlineRows[row.key] ?: return
        playOnline(online)
    }

    private fun playLocalFromRow(track: Track) {
        // Play the queue as the list shows it, from the tapped row, so "next"
        // means the next row on screen rather than the next track in the album.
        val queue = currentRows.mapNotNull { it.track }
        val startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        if (queue.isEmpty()) {
            music.setQueue(listOf(track), 0)
        } else {
            music.setQueue(queue, startIndex)
        }
    }

    /**
     * Plays one search result from an online provider.
     *
     * **Why this is not `setQueue` of the whole online group.** Every item in
     * that group would need its own resolve call before the player could open
     * the first one, which is four to thirty network requests to play a single
     * station. A single-item queue is what the driver tapped, and the local
     * list beside it stays playable through its own rows.
     *
     * **Why the resolve happens on the IO pool and the queue is set on main.**
     * `toTrack` blocks on the provider; `setQueue` must run where the player
     * expects it. Doing the resolve on the main thread would freeze the screen
     * for the connect timeout of a dead host.
     */
    private fun playOnline(online: com.ts8.core.music.online.OnlineTrack) {
        val runtime = graph.onlineMusic
        showOnlineLoading(true)
        graph.io().execute {
            val track = runtime.toTrack(online)
            main.post {
                showOnlineLoading(false)
                if (track == null) {
                    // The provider's own sentence is the honest one; the
                    // gateway already produced it and there is no better
                    // wording to invent here.
                    libraryFooter.text = getString(R.string.music_stream_unavailable)
                    libraryFooter.visibility = View.VISIBLE
                    return@post
                }
                music.setQueue(listOf(track), 0)
            }
        }
    }

    /** Drills into an album or artist by replacing the list with its tracks. */
    private fun openCollection(collection: MusicCollection) {
        val library = music.backend?.library ?: return
        val generation = ++loadGeneration
        showLoading(true)
        graph.io().execute {
            val tracks = runCatching {
                when (collection.kind) {
                    MusicCollection.Kind.ALBUM -> library.tracksInAlbum(collection.id)
                    MusicCollection.Kind.ARTIST -> library.tracksByArtist(collection.id)
                    MusicCollection.Kind.FOLDER -> library.tracksInFolder(collection.name)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            main.post {
                if (generation != loadGeneration) return@post
                showLoading(false)
                renderRows(
                    tracks.map { MusicRowAdapter.trackRow(it, it.id == playingTrackId) }
                )
            }
        }
    }

    companion object {
        /** 500 rows per page: enough to scroll a while, small enough to bind
         *  without a visible stall on this CPU. */
        private const val PAGE_SIZE = 500

        private const val SEEK_MAX = 1000

        /**
         * How long typing must pause before the online half of a search runs.
         *
         * 400 ms is long enough that `cầu rồng` is one request and not nine,
         * short enough that a driver who stopped typing is not left waiting.
         * The local half runs on every keystroke regardless — it is a SQLite
         * read and there is nothing to gain by delaying it.
         */
        private const val ONLINE_DEBOUNCE_MS = 400L
    }
}

END FILE

