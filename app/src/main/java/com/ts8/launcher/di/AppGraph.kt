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

    /**
     * The traffic-sign alert pipeline.
     *
     * Owned here for the same reason [speed] is: proximity alerts must survive
     * screen switches, and the spatial-grid sign cache must not be rebuilt per
     * screen. Subscribes to [location] (reference-counted alongside the speed
     * pipeline) and feeds posted speed limits into [speed]'s limit resolver.
     *
     * Lazily built so a driver who only listens to music never opens the
     * `vnsigns/` asset index.
     */
    val traffic: com.ts8.launcher.runtime.TrafficAlertRuntime by lazy {
        com.ts8.launcher.runtime.TrafficAlertRuntime(appContext, this, settings)
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

    /**
     * Where a voice model may be found on the filesystem and USB.
     *
     * **Why this is separate from [packSearchRoots].** Voice models live in
     * `voice-models/` directories, not `map-pack/`. The old voice scan
     * reused `packSearchRoots()` (map-pack paths) and stripped the last
     * component to get the parent — which meant it walked map-pack
     * directories looking for voice model `metadata.json` files, and
     * found map pack manifests instead. A map pack manifest declares
     * `formatVersion` and `basemap`, not `engine` and `locale`, so
     * installing it as a voice model always failed — but the driver saw
     * the map pack's folder listed as a "model" and did not understand
     * why it would not install.
     */
    fun voiceModelSearchRoots(): List<File> = buildList {
        // App-private storage: where VoiceModelStore installs models.
        add(File(appContext.filesDir, VOICE_MODELS_DIR))
        // App-external storage.
        val ext = appContext.getExternalFilesDir(null)
        if (ext != null) add(File(ext, VOICE_MODELS_DIR))
        // Shared storage — the paths a driver copies a model folder to.
        add(File("/storage/emulated/0/ts8/voice-models"))
        add(File("/storage/emulated/0/TS8/voice-models"))
        add(File("/storage/emulated/0/voice-models"))
        add(File("/sdcard/voice-models"))
        // USB roots — the model folder may be at the root of a USB stick.
        val usbRoots = arrayOf(
            "/storage/usb0", "/storage/usb1", "/storage/usbdisk",
            "/storage/usb_disk", "/mnt/usb_storage", "/storage/UsbDriveA"
        )
        for (u in usbRoots) {
            add(File(u, "voice-models"))
            // Also search the USB root itself — a driver may copy the model
            // folder directly to the root of the stick.
            add(File(u))
        }
        // The generic shared storage root, for a model folder copied loose.
        add(File("/storage/emulated/0/ts8"))
        add(File("/storage/emulated/0/TS8"))
        add(File("/storage/emulated/0/Download"))
    }

    /**
     * The pack download manager — fetches the server catalog and downloads
     * `.ts8map` archives for the Map Management screen.
     *
     * One instance: the state (downloading / installing / error) must be
     * visible to the UI even if the Activity is recreated during a download.
     * The manager runs on [io] and publishes state via a listener the UI sets.
     */
    val packDownloadManager: com.ts8.core.map.maplibre.PackDownloadManager by lazy {
        val installRoot = File(appContext.filesDir, "map-pack")
        val importer = com.ts8.core.map.maplibre.PackImporter(installRoot)
        com.ts8.core.map.maplibre.PackDownloadManager(appContext, importer)
    }

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

    // -- commercial ---------------------------------------------------------

    /**
     * Device identity and activation state.
     *
     * Lazily built so a driver who never uses premium features never reads
     * the activation file. The identity is stable for the lifetime of the
     * process; the activation state is re-read from storage on every access
     * to [currentTier] so a code entered mid-drive takes effect immediately.
     */
    val deviceIdentity: com.ts8.core.commercial.DeviceIdentity? by lazy {
        runCatching { com.ts8.core.commercial.DeviceIdentity(appContext) }
            .onFailure { Log.w(TAG, "device identity init failed: ${it.message}") }
            .getOrNull()
    }

    // -- speed limit resolver ----------------------------------------------
    //
    // Exposed so the voice executor can report the current limit. The actual
    // resolver lives inside [speed], but that field constructs the whole
    // speed pipeline; this is a lightweight accessor that only returns the
    // resolver if the pipeline has already been built.

    val speedLimitResolver: com.ts8.core.speed.SpeedLimitResolver?
        get() = runCatching { speed.limitResolver() }.getOrNull()

    // -- navigation session ------------------------------------------------
    //
    // The current navigation session, if any. Voice commands like "còn bao
    // xa" query this; it is null when no route is active.

    @Volatile
    var navigationSession: com.ts8.launcher.runtime.NavigationSession? = null

    // -- TPMS ---------------------------------------------------------------
    //
    // Optional tyre pressure monitoring service. Most TS8 head units do not
    // have a TPMS receiver; the field is null when no service is bound, and
    // the voice executor says so rather than crashing.

    @Volatile
    var tpmsService: com.ts8.launcher.runtime.TpmsService? = null

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

    /**
     * The installed offline voice models.
     *
     * `<app>/files/voice-models`, the layout `VOICE_MODEL_PACK.md` documents.
     * Created here and not by the Settings screen so the controller's
     * `offlineModelReady` and the screen's snapshot are the same object: two
     * stores over one directory would each cache their own verdict, and the
     * one that refreshed last would win.
     *
     * **The ABI list is filtered against the APK's own.** `Build.SUPPORTED_ABIS`
     * on this unit is `arm64-v8a, armeabi-v7a, armeabi`, but the APK ships only
     * the first two; a pack declaring `armeabi` would validate as compatible and
     * then fail to load a `.so` that is not in the package.
     */
    val voiceModelStore: com.ts8.core.voice.offline.VoiceModelStore by lazy {
        com.ts8.core.voice.offline.VoiceModelStore(
            root = File(appContext.filesDir, VOICE_MODELS_DIR),
            deviceSdk = Build.VERSION.SDK_INT,
            deviceAbis = { com.ts8.core.voice.offline.VoiceModelManifest.ACCEPTED_ABIS }
        )
    }

    /**
     * A different voice pack is now active on disk.
     *
     * **Why the graph owns this call rather than the Settings screen.** Two
     * things cached a model: the offline provider holds a loaded `Model`, and
     * the store holds a verdict. The screen that replaced the pack must not
     * have to know either fact — it says "the model changed" once, and the
     * graph frees the resident engine and re-reads the store. Calling the
     * provider directly would be a second copy of that wiring, and the first
     * time one of the two was forgotten the driver would be told their new
     * model works while the old one answers.
     *
     * Wrapped rather than bare: this runs from an Activity's IO callback, and a
     * provider that throws on shutdown must not take that callback down after
     * an install that already succeeded.
     */
    fun onVoiceModelChanged() {
        runCatching { recognitionRouter.onModelChanged() }
    }

    private val systemRecognition: com.ts8.launcher.runtime.AndroidSystemRecognitionProvider by lazy {
        com.ts8.launcher.runtime.AndroidSystemRecognitionProvider(appContext)
    }

    private val offlineRecognition: com.ts8.launcher.runtime.VoskOfflineRecognitionProvider by lazy {
        com.ts8.launcher.runtime.VoskOfflineRecognitionProvider(
            context = appContext,
            modelStore = voiceModelStore,
            grammar = { voiceGrammar() }
        )
    }

    /**
     * Chooses between the two recognisers for each request.
     *
     * The policy is read through [settings] at the moment of the decision, so a
     * driver switching to "chỉ ngoại tuyến" in Settings takes effect on the next
     * press rather than at the next cold start.
     */
    private val recognitionRouter: com.ts8.core.voice.offline.RecognitionProviderRouter by lazy {
        com.ts8.core.voice.offline.RecognitionProviderRouter(
            system = systemRecognition,
            offline = offlineRecognition,
            policy = { settings.recognitionPolicy },
            offlineModelReady = { voiceModelStore.isReady() }
        )
    }

    /**
     * The constrained vocabulary for the offline recogniser.
     *
     * **Why it is built from the live place index rather than a constant.** §6
     * requires the grammar to be limited to the fixed commands plus POIs from
     * the *active* `places.db`, so `dẫn đường tới Cầu Rồng` decodes once the Đà
     * Nẵng pack is installed and the grammar does not carry Hà Nội landmarks on
     * a unit that has never seen that map.
     *
     * **Why the queries are allowed to cost milliseconds.** The router calls
     * this once per press, before the microphone opens — not per frame — and
     * `topPlaces` is one indexed `SELECT … LIMIT 200`. The alternative, caching
     * the string, would go stale the moment the driver swaps packs, which is the
     * same class of bug as a cached `SharedPreferences` read.
     */
    private fun voiceGrammar(): String? {
        val places = runCatching { placeSearch()?.topPlaces(COMMAND_GRAMMAR_POI_CAP) }
            .getOrNull()
            .orEmpty()
            .map { it.name }
        return com.ts8.core.voice.offline.CommandGrammar.build(places)
    }

    fun voiceInstant(): com.ts8.launcher.runtime.VoiceInstantController {
        voiceController?.let { return it }
        synchronized(this) {
            voiceController?.let { return it }
            val created = com.ts8.launcher.runtime.VoiceInstantController(
                context = appContext,
                feedback = com.ts8.launcher.runtime.VoiceFeedback(appContext, audioBridge),
                executor = com.ts8.launcher.runtime.VoiceCommandExecutor(appContext, this),
                store = com.ts8.core.voice.VoiceStateStore(runtimeState),
                providers = recognitionRouter,
                grammar = { voiceGrammar() },
                answerGrammar = { com.ts8.core.voice.offline.CommandGrammar.answerGrammar() },
                beepEnabled = { settings.voiceBeepEnabled },
                listeningPromptEnabled = { settings.voiceListeningPromptEnabled },
                autoExecute = { settings.voiceAutoExecute }
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

    /**
     * The shared launcher mode (§10).
     *
     * One instance for the process, beside [uiState] but answering a different
     * question: [uiState] is the ten-state machine a screen may claim, while
     * this is the eight-mode store the UI actually reads, including whether a
     * route is running behind whatever screen is in front.
     *
     * It holds no engine, so constructing it is free and every screen may read
     * it on every resume.
     */
    val launcherMode: com.ts8.launcher.runtime.LauncherModeStore by lazy {
        com.ts8.launcher.runtime.LauncherModeStore()
    }

    /**
     * Whether this device's media volume actually responds to a write (§8).
     *
     * Process-wide because the same stream is stepped from the bottom bar, from
     * Quick Settings and from a voice command. Per-control trackers would each
     * need their own two taps to learn what one tap anywhere already proved,
     * and a driver who turned the knob three times would be told "no effect"
     * three times if the evidence were not shared.
     *
     * Constructed with the default threshold of two, per §8's rule.
     */
    val volumeCapability: com.ts8.core.audio.VolumeCapabilityTracker by lazy {
        com.ts8.core.audio.VolumeCapabilityTracker()
    }

    /**
     * The map shell's phase (§6).
     *
     * One instance for the process, not one per screen: Home and Map both draw
     * a map, and a driver who watches Home fail and then opens Map must see the
     * same failure rather than a fresh `INITIALIZING` that delays the same
     * answer by another twelve seconds.
     */
    val mapPhase: com.ts8.launcher.runtime.MapPhaseStore by lazy {
        com.ts8.launcher.runtime.MapPhaseStore()
    }

    companion object {
        private const val TAG = "Ts8AppGraph"

        /** `files/voice-models`, the root `VoiceModelStore` owns. */
        const val VOICE_MODELS_DIR = "voice-models"

        /**
         * How many POI names the recogniser's grammar may carry.
         *
         * Matches `CommandGrammar.DEFAULT_POI_CAP`: the cap is stated here as
         * well because this is the call site that pays for it in decode graph,
         * and a change in one without the other would be a silent size change on
         * a device with no headroom.
         */
        const val COMMAND_GRAMMAR_POI_CAP = com.ts8.core.voice.offline.CommandGrammar.DEFAULT_POI_CAP
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
