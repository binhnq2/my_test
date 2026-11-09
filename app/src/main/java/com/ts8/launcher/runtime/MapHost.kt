package com.ts8.launcher.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    /**
     * The screen's reaction to the renderer's own two outcomes.
     *
     * **Why this is not a plain `ReadyListener` on the controller.** §6 needs
     * the phase store driven from *every* moment the renderer reports, and
     * `ReadyListener` reports only two of them: the style parsed, or the style
     * failed to build. The third — the renderer produced a frame — is a
     * `MapView` concern, and the driver of the phase machine therefore has to be
     * whoever owns the view. That is this class. The screen keeps only what is
     * genuinely its own: which camera to sit on and which route to replay.
     */
    interface RenderListener {
        /** The style is live; [controller] can be driven. */
        fun onStyleReady(controller: MapLibreMapController)

        /**
         * The style could not be built. The error surface already shows the
         * reason from the phase store; a screen with extra work to undo (a
         * hidden maneuver card, a stopped follow) hooks it here.
         */
        fun onStyleFailed(reason: String) {}
    }

    /** Non-null once the renderer exists and can be driven. */
    var controller: MapLibreMapController? = null
        private set

    /** Why the map is unavailable, aimed at the driver. Null when it works. */
    var failure: String? = null
        private set

    /** The pack's display name, captured once so a later lookup cannot hang. */
    private var packName: String? = null

    private var renderListener: RenderListener? = null

    private var mapView: MapView? = null
    private var created = false
    private var disposed = false
    private var firstFrameSeen = false

    /**
     * True while the current attempt renders through a `TextureView`.
     *
     * The first attempt always uses MapLibre's default `SurfaceView`. If no GL
     * surface appears, [retryWithTexture] flips this and builds a second view.
     */
    private var textureMode = false

    /** The pack resolved by [create], re-used by the retry. */
    private var packRef: MapPack? = null

    /** Forwarded to `MapView.onCreate` on every attempt. */
    private var savedState: Bundle? = null

    /** Fires when [MAP_READY_WATCHDOG_MS] passes with no map object. */
    private val readyWatchdog = Runnable { onMapWatchdog() }

    /** Which attempt this is: 0 = SurfaceView, 1 = TextureView. */
    private var attempt = 0

    /**
     * Whether the Activity has delivered `onStart` / `onResume` to this host.
     *
     * Kept so a view built *after* those callbacks — the TextureView retry, which
     * runs from a delayed watchdog — can be brought up to the same state. A
     * `MapView` that never gets `onStart` keeps its renderer stopped: the surface
     * exists, the map object exists, and not one frame is drawn.
     */
    private var started = false
    private var resumed = false

    /**
     * True once the style has parsed and overlays are attached.
     *
     * The renderer draws frames before that — a cleared background, the first
     * camera — so a frame listener without this gate would call an empty map
     * ready and un-hide the shell over nothing.
     */
    private var styleReady = false

    /**
     * Armed when the style is live and cancelled by the first drawn frame.
     *
     * Without it `WAITING_FIRST_FRAME` would be a screen that says "đang chờ
     * khung hình đầu tiên" forever: `MapLibreMapController` registers a success
     * lambda on `setStyle` and nothing else, so a native style rejection that
     * returns neither success nor failure leaves the shell claiming progress it
     * cannot demonstrate. Timing out converts that silence into a truthful
     * failure with three actions, and [onFirstFrameDrawn] still recovers if the
     * frame arrives late.
     *
     * **What cancels it.** `MapView.OnDidFinishRenderingFrameListener`, which
     * the renderer calls after each frame it actually draws. See the
     * registration in [create] for why `OnDidFinishLoadingMapListener` — which
     * fires on load completion, not on a frame, and may never fire on a partial
     * pack — is kept only as a second, earlier-or-equal signal.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val firstFrameTimeout = Runnable { onFirstFrameTimedOut() }

    /** Installs the screen's reaction. Call before [create]. */
    fun setRenderListener(listener: RenderListener?) {
        renderListener = listener
    }

    /**
     * Builds the map and attaches it to [container].
     *
     * @param savedInstanceState forwarded to `MapView.onCreate`, which is how
     *        MapLibre restores its camera after a configuration change.
     */
    fun create(savedInstanceState: Bundle?) {
        if (created) return
        created = true
        disposed = false
        // Reset per host: a screen that rebuilds the map must start from the
        // SurfaceView attempt again, not from whatever mode the last one ended in.
        textureMode = false
        attempt = 0
        savedState = savedInstanceState

        DiagnosticLogger.log("MapHost.create: loading pack")
        val result = graph.loadPack()
        val pack = (result as? MapPack.LoadResult.Ok)?.pack
        if (pack == null) {
            val reason = (result as? MapPack.LoadResult.Failure)?.reason
                ?: context.getString(R.string.home_map_unavailable)
            DiagnosticLogger.error("MapHost: pack load failed: $reason")
            failure = reason
            return
        }

        // Captured here, not re-read on failure. `graph.pack()` is a filesystem
        // walk, and re-running it inside the error handler is the one call that
        // must not be able to hang on the very directory that just failed.
        packRef = pack
        packName = runCatching { pack.metadata.name }.getOrNull()
        DiagnosticLogger.log("MapHost: pack OK: $packName, dir=${pack.directory.absolutePath}")
        DiagnosticLogger.log("MapHost: tiles=${pack.tilesPath}")
        // Stage fonts on internal storage before the style is built.
        // On real TS8 hardware, USB paths may be readable from Java but
        // blocked for MapLibre's native C++ file reads — copying to
        // filesDir/fonts/ eliminates this failure.
        val packGlyphs = pack.resolveGlyphsDir()
        DiagnosticLogger.log("MapHost: packGlyphs=${packGlyphs?.absolutePath ?: "null"}")
        val stagedFonts = com.ts8.core.map.maplibre.GlyphFallback.ensureFontsOnInternal(context, packGlyphs)
        DiagnosticLogger.log("MapHost: stagedFonts=${stagedFonts?.absolutePath ?: "FAILED"}")
        if (stagedFonts == null) {
            DiagnosticLogger.error("MapHost: FONT STAGING FAILED — map may hang at LOADING_STYLE")
        }
        DiagnosticLogger.log("MapHost: bounds=${pack.bounds}, zoom=${pack.metadata.minZoom}-${pack.metadata.maxZoom}")

        attachView(pack)
    }

    /**
     * Builds one `MapView` and its controller against the pack [create] loaded.
     *
     * Split out so the GL-surface retry can build a second view without
     * repeating the pack discovery, the font staging or the log lines — and so
     * the pack cannot drift between the two attempts.
     */
    private fun attachView(pack: MapPack) {
        // `textureMode` is the only difference between the two attempts:
        // false (SurfaceView) first, true (TextureView) if the surface never
        // appeared. Everything else is identical on purpose, so the retry cannot
        // fail for a different reason than the first attempt did.
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(textureMode)
        val view = try {
            // The runtime must exist before the view: MapView.initialize()
            // throws MapLibreConfigurationException when it does not.
            MapLibre.getInstance(context)
            MapView(context, options)
        } catch (e: Throwable) {
            // A firmware with a broken GL driver throws here rather than at the
            // first draw. The launcher stays usable with a message.
            DiagnosticLogger.error("MapHost: MapView construction failed", e)
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

        // Registered before the style is requested, so a rejection that fires
        // while the controller is still parsing cannot slip between the two.
        //
        // **`onDidFinishRenderingFrame`, not `onDidFinishLoadingMap`.** The two
        // are not synonyms, and the difference is exactly the reported symptom.
        // `onDidFinishLoadingMap` fires when the map's *load* completes — every
        // source, tile and glyph request it decided to make has settled — which
        // on a partial pack may be never. `onDidFinishRenderingFrame` fires
        // after every frame the renderer actually draws, carrying
        // `fullyRendered` and the two timings. Since this phase is named
        // WAITING_FIRST_FRAME and the failure is MAP_FIRST_FRAME_TIMEOUT, the
        // frame callback is the one that means what the phase says.
        view.addOnDidFinishRenderingFrameListener { _, _, _ -> onFirstFrameDrawn() }
        // Kept as well: a map that finishes loading has certainly drawn, and
        // this is the earlier of the two on a complete pack.
        view.addOnDidFinishLoadingMapListener { onFirstFrameDrawn() }
        view.addOnDidFailLoadingMapListener { reason -> onStyleFailed(reason) }

        val c = MapLibreMapController(context, pack, view)
        // **The host owns the "never came up" policy now.** The controller's own
        // 15 s timeout would mark the phase FAILED halfway through the retry,
        // leaving the second attempt drawing into a screen that already says the
        // map is unavailable. `0` disables it; [onMapWatchdog] is the one
        // deadline, and it knows how many attempts have been made.
        c.mapReadyTimeoutMs = 0L
        // The display snapshot goes in before the style loads: `onMapReady`
        // builds the first document from it, so the map opens already obeying
        // the stored settings instead of snapping to them a frame later.
        c.applyDisplayConfig(graph.settings.mapDisplayConfig())
        c.setReadyListener(object : com.ts8.core.map.MapController.ReadyListener {
            override fun onReady() = onStyleLoaded()
            override fun onFailed(reason: String) = onStyleFailed(reason)
        })
        controller = c
        view.onCreate(savedState)
        c.attach()
        logSurfaceState("attached attempt=${attempt}, texture=$textureMode")
        // A second look once layout has run: the first call happens before the
        // traversal, so its width/height are always 0 and cannot tell a
        // zero-sized view from an unmeasured one.
        mainHandler.postDelayed({ logSurfaceState("2s after attach") }, 2_000L)

        // A retried view is born after the Activity's own onStart/onResume.
        // Replaying them here is what makes the second attempt equivalent to the
        // first instead of a stopped renderer.
        if (started) view.onStart()
        if (resumed) view.onResume()

        mainHandler.removeCallbacks(readyWatchdog)
        mainHandler.postDelayed(readyWatchdog, MAP_READY_WATCHDOG_MS)
    }

    /**
     * Called when the renderer has not produced a map object in time.
     *
     * **Why the map object and not a frame.** `MapView.getMapAsync` only calls
     * back from `onSurfaceCreated`, so "no map object" is precisely "the GL
     * surface never came up" — the failure this device has shown four times in
     * one log. A map object that exists but never draws is a different bug and
     * is handled by the first-frame timeout.
     */
    private fun onMapWatchdog() {
        if (disposed || !created) return
        if (controller?.isMapCreated == true) {
            DiagnosticLogger.log("MapHost: map object created — watchdog stands down")
            return
        }
        logSurfaceState("watchdog ${MAP_READY_WATCHDOG_MS}ms, no map object")

        if (!textureMode) {
            DiagnosticLogger.error(
                "MapHost: no GL surface in SurfaceView mode after " +
                    "${MAP_READY_WATCHDOG_MS}ms — retrying with TextureView"
            )
            retryWithTexture()
            return
        }

        DiagnosticLogger.error(
            "MapHost: no GL surface with SurfaceView or TextureView — " +
                "the renderer cannot start on this ROM"
        )
        val detail = context.getString(R.string.map_error_renderer_unavailable)
        failure = detail
        graph.mapPhase.fail(
            errorCode = MapPhaseStore.CODE_RENDERER_UNAVAILABLE,
            detail = detail,
            packName = packName
        )
        renderListener?.onStyleFailed(detail)
    }

    /** Second attempt: same pack, same settings, `TextureView` renderer. */
    private fun retryWithTexture() {
        val pack = packRef ?: return
        teardownView()
        textureMode = true
        attempt++
        // **No saved state on the retry.** `MapView.onCreate(bundle)` with the
        // `STATE_HAS_SAVED_STATE` flag restores a camera into a native map that
        // the new view does not have yet — it would take the
        // `onRestoreInstanceState` branch against a freshly created surface. The
        // first attempt keeps the Activity's bundle; the second starts clean.
        savedState = null
        attachView(pack)
    }

    /**
     * Destroys the current attempt without touching [created] or [disposed].
     *
     * **The order matters and so does the removal.** `onDestroyView` destroys
     * the controller and then calls `MapView.onDestroy`, which is what stops the
     * GL thread; removing the view afterwards is what keeps a rebuild from
     * stacking a dead surface under the new one. The rebuild path used to drop
     * the reference and leave the view in the container.
     */
    private fun teardownView() {
        mainHandler.removeCallbacks(readyWatchdog)
        val view = mapView
        controller?.setReadyListener(null)
        runCatching { controller?.onDestroyView() }
        controller = null
        if (view != null) {
            runCatching { container.removeView(view) }
        }
        mapView = null
        styleReady = false
        firstFrameSeen = false
    }

    /**
     * One line describing whether the renderer could possibly work.
     *
     * Added because this device's log had exactly one fact — "onMapReady never
     * fired" — and four different causes fit it: a zero-sized view, an
     * unattached view, a SurfaceView whose holder never got a buffer, and a
     * TextureView renderer. The size, the attachment, the renderer class and the
     * holder's validity tell them apart on the next install.
     */
    private fun logSurfaceState(where: String) {
        val v = mapView
        if (v == null) {
            DiagnosticLogger.log("MapHost[$where]: mapView=null")
            return
        }
        val render = if (v.childCount > 0) v.getChildAt(0) else null
        val holder = (render as? android.view.SurfaceView)?.holder
        DiagnosticLogger.log(
            "MapHost[$where]: view=${v.width}x${v.height} vis=${v.visibility} " +
                "attached=${v.isAttachedToWindow} container=${container.width}x${container.height} " +
                "children=${v.childCount} renderer=${render?.javaClass?.name ?: "none"} " +
                "holderValid=${holder?.surface?.isValid} " +
                "mapCreated=${controller?.isMapCreated}"
        )
    }

    // -- phase machine (§6) ---------------------------------------------------

    /**
     * The style is parsed and overlays are attached — but nothing has drawn yet.
     *
     * Home and Map used to go straight from `LOADING_STYLE` to `READY` here,
     * which hid the error surface while the panel was still a black rectangle
     * for the first frames. `WAITING_FIRST_FRAME` is exactly that interval, and
     * it is the phase that makes the timeout possible.
     */
    private fun onStyleLoaded() {
        if (disposed) return
        DiagnosticLogger.log("MapHost: style loaded successfully")
        graph.mapPhase.setPack(packName)
        styleReady = true
        // A rebuild — a palette flip, a display-config change — replays
        // `onReady` through the same listener. The renderer has already drawn
        // once by then, so there is no first frame to wait for, and arming the
        // timeout again would turn every later style change into a 12-second
        // window in which a working map reports itself as broken.
        if (firstFrameSeen) {
            graph.mapPhase.moveTo(MapPhaseStore.Phase.READY)
        } else {
            graph.mapPhase.moveTo(MapPhaseStore.Phase.WAITING_FIRST_FRAME)
            mainHandler.removeCallbacks(firstFrameTimeout)
            mainHandler.postDelayed(firstFrameTimeout, MapPhaseStore.FIRST_FRAME_TIMEOUT_MS)
        }

        controller?.let { renderListener?.onStyleReady(it) }
    }

    /**
     * A frame has been drawn: everything the shell was covering may go away.
     *
     * `MapView`'s own listeners drive this, so the transition means what it
     * says — the renderer produced pixels, not that a style document parsed.
     * It is idempotent and deliberately re-armable: MapLibre fires it again
     * after every style rebuild, and a rebuild that never draws must time out
     * exactly like a first start would.
     *
     * **`fullyRendered` is ignored on purpose.** The flag says every source,
     * tile and glyph request has settled — which is precisely the condition
     * that may never be met on a pack with partial coverage, and the thing this
     * fix exists to stop waiting for. A frame drawn is a frame drawn.
     *
     * **A frame before the style is ready does not count.** The renderer emits
     * frames from the moment the surface exists — a cleared background, the
     * first camera position — so an unguarded listener would report `READY`
     * while the style was still parsing and un-hide the shell over an empty
     * map. [styleReady] is what makes this listener mean "the map drew itself",
     * not "the surface was touched".
     */
    private fun onFirstFrameDrawn() {
        if (disposed || !created || !styleReady) return
        DiagnosticLogger.log("MapHost: first frame drawn — map READY")
        mainHandler.removeCallbacks(firstFrameTimeout)
        firstFrameSeen = true
        graph.mapPhase.setPack(packName)
        graph.mapPhase.moveTo(MapPhaseStore.Phase.READY)
    }

    private fun onFirstFrameTimedOut() {
        if (disposed || !created) return
        // A frame that arrived between the post and this run has already moved
        // the store to READY; overwriting that would report a failure over a
        // working map.
        if (graph.mapPhase.snapshot.phase == MapPhaseStore.Phase.READY) return
        DiagnosticLogger.error("MapHost: first frame TIMEOUT (${MapPhaseStore.FIRST_FRAME_TIMEOUT_MS}ms)")
        graph.mapPhase.fail(
            errorCode = MapPhaseStore.CODE_FIRST_FRAME_TIMEOUT,
            detail = context.getString(
                R.string.map_error_first_frame_timeout,
                (MapPhaseStore.FIRST_FRAME_TIMEOUT_MS / 1000).toInt()
            ),
            packName = packName
        )
    }

    private fun onStyleFailed(reason: String) {
        if (disposed) return
        DiagnosticLogger.error("MapHost: style load FAILED: $reason")
        stopFirstFrameWait()
        graph.mapPhase.fail(
            errorCode = MapPhaseStore.CODE_STYLE_FAILED,
            detail = reason,
            packName = packName
        )
        renderListener?.onStyleFailed(reason)
    }

    private fun stopFirstFrameWait() {
        mainHandler.removeCallbacks(firstFrameTimeout)
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
        started = true
        mapView?.onStart()
    }

    fun onResume() {
        resumed = true
        mapView?.onResume()
    }

    fun onPause() {
        resumed = false
        mapView?.onPause()
    }

    fun onStop() {
        started = false
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
        // The timeout holds a reference to this host and, through it, to the
        // Activity. A pending post that fires after the screen is gone would
        // write a failure into the process-wide phase store that no screen is
        // showing — and then re-open a map that the driver has left.
        disposed = true
        stopFirstFrameWait()
        renderListener = null
        teardownView()
        created = false
    }

    companion object {
        private const val TAG = "Ts8MapHost"

        /**
         * How long the SurfaceView attempt gets before the TextureView retry.
         *
         * 12 s: the field log shows the renderer either comes up in ~1 s or not
         * at all, and the driver is watching a "đang tải" surface in the
         * meantime. Long enough for a cold Cortex-A7 to inflate the view and
         * create the surface, short enough that a dead surface is not mistaken
         * for a slow one.
         */
        private const val MAP_READY_WATCHDOG_MS = 12_000L
    }
}
