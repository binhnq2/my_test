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
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ts8.core.location.LocationFix
import com.ts8.core.map.CameraFollowMode
import com.ts8.core.map.CameraPosition
import com.ts8.core.map.GeoPoint
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
import com.ts8.core.traffic.TrafficAlert
import com.ts8.core.traffic.TrafficAlertController
import com.ts8.core.traffic.TrafficSign
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppSettings
import com.ts8.launcher.runtime.LocationRuntime
import com.ts8.launcher.runtime.MapHost
import com.ts8.launcher.runtime.MapPhaseStore
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

    private lateinit var mapPhase: MapPhaseSurface
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
    private lateinit var trafficAlertCard: LinearLayout
    private lateinit var trafficAlertIcon: ImageView
    private lateinit var trafficAlertLabel: TextView
    private lateinit var trafficAlertDistance: TextView

    private val main = Handler(Looper.getMainLooper())

    /** Last fix, so a destination chosen before the first fix can wait for one. */
    private var lastFix: LocationFix? = null

    /** A destination picked in the search screen, waiting for a position. */
    private var pendingDestination: PendingDestination? = null

    /**
     * True once the camera has been placed on the car.
     *
     * The map used to open on the pack's centre every time. That is the right
     * fallback for a pack with no fix, but with a fix it left the driver
     * looking at the middle of the province while the car sat in a corner of
     * the panel — the marker was drawn, just not anywhere the driver was
     * looking. One placement per screen (and per style rebuild) is enough: the
     * follow camera takes over from there, and a driver who pans away keeps
     * their view until they press re-centre.
     */
    private var centredOnVehicle = false

    /**
     * True once the driver has panned or pinched the map themselves.
     *
     * While false the browse camera re-centres on every fix, so a moving car
     * stays under the map instead of driving off the edge of it. A gesture
     * clears the flag and stops the re-centring; the re-centre button sets it
     * back.
     */
    private var userPanned = false

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

            // Keep the car under the map while the driver has not taken the
            // camera over — but only in free navigation. From the moment a
            // route exists, the fix stream must not fight `fitRoute` or the
            // follow camera; see MapChrome.centresOnVehicle.
            val state = navigator?.state() ?: NavigationState.IDLE
            if (!userPanned && MapChrome.centresOnVehicle(state)) {
                followVehicle(fix)
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
        val cornerTaken = ::destinationCard.isInitialized &&
            destinationCard.visibility == View.VISIBLE
        speedCluster.visibility =
            if (any && !cornerTaken) View.VISIBLE else View.GONE
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
                // `driving_alert_nav_v1`: the driver muted turn-by-turn voice.
                // The cards still update — this is the spoken half only.
                if (!graph.settings.alertNavigationEnabled) return@post
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

    // -- traffic alerts ------------------------------------------------------

    /**
     * Receives traffic-sign proximity alerts and sign-overlay updates.
     *
     * **Why a separate listener, not a re-use of the speed listener.** The
     * traffic pipeline runs on its own reference-counted runtime
     * ([com.ts8.launcher.runtime.TrafficAlertRuntime]); its alerts have their
     * own card, their own icon vocabulary, and their own voice-prompt logic
     * (Vietnamese TTS via [TrafficAlertViews.voicePrompt]). Folding them into
     * the speed listener would make one object responsible for two unrelated
     * UI concerns and would tie the alert card's visibility to the speed
     * pipeline's lifecycle, which starts and stops at different times.
     *
     * **Sign-overlay push.** When `onSignsUpdated` fires, the cached signs are
     * pushed to the map controller as [com.ts8.core.map.TrafficSignOverlay]
     * objects. The conversion happens inside `TrafficAlertRuntime` to keep the
     * map-engine module free of a dependency on the location-engine module.
     */
    private val trafficListener = object : TrafficAlertController.Listener {
        override fun onTrafficAlert(alert: TrafficAlert?) {
            main.post {
                if (!isActive) return@post
                TrafficAlertViews.bind(
                    card = trafficAlertCard,
                    icon = trafficAlertIcon,
                    label = trafficAlertLabel,
                    distance = trafficAlertDistance,
                    alert = alert
                )
                // Voice prompt only on the first alert for a given sign — the
                // controller's cooldown prevents re-firing within 30 s, and
                // `firstAlert` is the gate that stops distance updates from
                // re-speaking.
                if (alert != null && alert.shouldSpeak) {
                    val prompt = TrafficAlertViews.voicePrompt(alert)
                    // `urgent = false`: a traffic alert is advisory and must
                    // queue behind an active navigation prompt rather than cut
                    // it off. The distance sign stays on screen either way.
                    val spoke = graph.voice.speak(prompt, urgent = false)
                    if (!spoke) {
                        android.util.Log.w(TAG, "no voice for traffic alert: $prompt")
                    }
                }
            }
        }

        override fun onSignsUpdated(signs: List<TrafficSign>) {
            main.post {
                if (!isActive) return@post
                // Push the sign overlay to the map so sign markers appear on
                // the road. The zoom is read from the controller so the overlay
                // density matches what the driver can actually see — at low
                // zoom only cameras are shown, at high zoom everything is.
                mapHost?.withController { controller ->
                    val zoom = controller.getCameraPosition().zoom
                    graph.traffic.pushSignOverlayTo(controller, zoom)
                }
            }
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

        // The maneuver card and the state banner sit inside the full-bleed map,
        // which starts at the top of the window — behind the vendor bar and
        // behind the app's own 54 dp top overlay. Their XML margin only clears
        // the overlay; the vendor bar height is only known at runtime, so the
        // remainder is added here. Without it the card's distance line is drawn
        // under the vendor bar on a unit whose bar is taller than 32 dp.
        findViewById<View>(R.id.statusBar).post {
            val below = resources.getDimensionPixelSize(R.dimen.top_overlay_height) +
                findViewById<View>(R.id.statusBar).vendorBarInsetPx()
            findViewById<View>(R.id.topLeftStack).setTopInset(below)
            findViewById<View>(R.id.stateBanner).setTopInset(below)
        }

        // §6: the same phase surface Home draws, bound to the same store. A
        // driver who watched Home fail and then opened Map sees that failure
        // rather than a second twelve-second wait.
        mapPhase = MapPhaseSurface(this, findViewById(R.id.mapPhase), graph.mapPhase) {
            graph.invalidatePack()
            rebuildMap()
        }

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
            // offering buttons that cannot work — and prefer the specific
            // reason when there is one, so a pack that exists but has no
            // routing graph does not send the driver off to install a map they
            // already have.
            showBanner(
                graph.navigationUnavailableReason() ?: getString(R.string.nav_no_pack),
                visible = true
            )
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
        trafficAlertCard = findViewById(R.id.trafficAlertCard)
        trafficAlertIcon = findViewById(R.id.trafficAlertIcon)
        trafficAlertLabel = findViewById(R.id.trafficAlertLabel)
        trafficAlertDistance = findViewById(R.id.trafficAlertDistance)
    }

    /** True between [onCreate] and [onDestroy]; guards the posted callbacks. */
    private var isActive = false

    /**
     * Display settings, read once.
     *
     * `render()` runs on every accepted GPS fix, so it must not build a new
     * [AppSettings] — and re-open the preference file — per frame. The
     * settings panel is a separate Activity that this one is not destroyed
     * for, which is why the values are read on resume rather than cached at
     * construction.
     */
    private val settings: AppSettings by lazy { AppSettings(this) }

    override fun onStart() {
        super.onStart()
        isActive = true
        graph.location.acquire(locationListener)
        graph.speed.acquire(speedListener)
        graph.traffic.acquire(trafficListener)
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
        // Traffic alerts also run while the screen is away (the runtime is
        // process-wide and reference-counted). Catch up on the current alert so
        // the card shows immediately rather than waiting for the next fix.
        TrafficAlertViews.bind(
            card = trafficAlertCard,
            icon = trafficAlertIcon,
            label = trafficAlertLabel,
            distance = trafficAlertDistance,
            alert = graph.traffic.currentAlert()
        )
    }

    override fun onPause() {
        mapHost?.onPause()
        super.onPause()
    }

    override fun onStop() {
        music.detach(musicObserver)
        graph.speed.release(speedListener)
        graph.traffic.release(trafficListener)
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
        graph.mapPhase.moveTo(MapPhaseStore.Phase.VALIDATING_PACK)
        // The host owns the phase machine from here on (§6): it is the only
        // holder of the `MapView`, and therefore the only layer that hears
        // "a frame was drawn" as well as "the style parsed". The screen keeps
        // what is its own — camera placement and route replay.
        host.setRenderListener(object : MapHost.RenderListener {
            override fun onStyleReady(controller: MapLibreMapController) {
                // A gesture hands the camera to the driver until they ask for
                // the car back.
                controller.onUserGesture = { userPanned = true }
                val nav = navigator
                val route = nav?.route()
                if (route != null) {
                    applyRoute(route)
                } else {
                    // With a fix, the map belongs on the car; the pack centre is
                    // only the fallback for a screen opened before the first fix
                    // (the listener re-centres as soon as one arrives).
                    val fix = lastFix
                    if (fix != null) {
                        centreOnVehicle(fix, animate = false)
                    } else {
                        graph.pack()?.center?.let { centre ->
                            controller.moveCamera(
                                CameraPosition(centre, graph.pack()?.metadata?.defaultZoom ?: 12.0),
                                animate = false
                            )
                        }
                    }
                }
                lastFix?.let { fix ->
                    controller.setVehicleMarker(fix.point, fix.bearingDeg ?: 0f)
                    if (nav?.state() == NavigationState.NAVIGATING) resumeFollowing(fix)
                }
                nav?.destination()?.let { dest ->
                    controller.setDestinationMarker(dest, nav.uiState().destinationName)
                }
                // Push the current traffic-sign overlay onto the freshly-loaded
                // style. The runtime holds signs loaded from the 9-cell
                // neighbourhood around the last GPS fix; without this replay the
                // sign markers would disappear every time the style rebuilds
                // (day/night switch, layer toggle, camera mode change).
                // The zoom is read from the controller so the overlay density
                // matches what the driver can see.
                val zoom = controller.getCameraPosition().zoom
                graph.traffic.pushSignOverlayTo(controller, zoom)
            }
        })
        host.create(savedInstanceState)

        val failure = host.failure
        if (failure != null) {
            graph.mapPhase.fail(
                errorCode = packErrorCode(failure),
                detail = failure,
                packName = safePackName(graph)
            )
            return
        }
        if (graph.mapPhase.snapshot.phase != MapPhaseStore.Phase.READY) {
            graph.mapPhase.moveTo(MapPhaseStore.Phase.LOADING_STYLE)
        }
    }

    /**
     * Tears the map down and builds it again after `Thử lại` on the error
     * surface.
     *
     * The phase include is a child of `mapHost`, so the container is emptied
     * around it rather than with `removeAllViews` — the latter would detach the
     * surface that is about to report whether the retry worked.
     */
    private fun rebuildMap() {
        mapHost?.onDestroy()
        mapHost = null
        val container = findViewById<FrameLayout>(R.id.mapHost)
        val phase = findViewById<View>(R.id.mapPhase)
        val index = container.indexOfChild(phase)
        container.removeAllViews()
        if (index >= 0) {
            container.addView(
                phase,
                // Clamped: `removeAllViews` leaves the container empty, so the
                // index captured before it is out of range. Same crash as the
                // Home screen's rebuild (IndexOutOfBoundsException: index=1
                // count=0, TS8 log 2026-11-08 18:29:28); `MapHost` adds the map
                // view back at 0, so the phase ends up where it was anyway.
                index.coerceIn(0, container.childCount),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        wireMap(savedInstanceState = null)
        // `onStart` was already delivered to this Activity; a MapView created
        // afterwards needs its own, or it stays stopped with a blank surface.
        if (isActive) mapHost?.onStart()
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
                controller.setAlternativeRoutes(emptyList())
                controller.setDestinationMarker(null, null)
            }
            return
        }
        graph.locationEngine.setRoute(route.points)
        // The options the driver did not pick. Only while the route is still a
        // question — once guidance starts the choice is gone and a second line
        // would just be noise over the road the car is already on.
        val others = nav?.alternatives().orEmpty()
            .filter { it !== route }
            .map { RouteGeometry(it.points) }
        val guiding = nav?.state() == NavigationState.NAVIGATING ||
            nav?.state() == NavigationState.REROUTING
        mapHost?.withController { controller ->
            controller.setRoute(RouteGeometry(route.points))
            controller.setAlternativeRoutes(
                if (nav?.state() == NavigationState.NAVIGATING) emptyList() else others
            )
            nav?.destination()?.let { dest ->
                controller.setDestinationMarker(dest, nav.uiState().destinationName)
            }
            // A route the driver is previewing is shown whole: they asked "how do
            // I get there", and answering with a zoomed-in view of the car says
            // nothing about the destination.
            //
            // **But not mid-drive.** `applyRoute` also runs on every reroute, and
            // fitting 6 km of road into the panel at that moment yanks the camera
            // away from the junction the driver is about to reach, for the one
            // second until the next fix re-arms the follow camera. While guiding,
            // the follow camera owns the view — unless the driver asked for the
            // overview, which is exactly a request to see the whole route.
            if (!guiding || overview) {
                controller.fitRoute(RouteGeometry(route.points), FIT_PADDING_PX)
            }
        }
    }

    /**
     * Keeps the car under the map **without touching the driver's zoom**.
     *
     * This is the per-fix path, so it runs about once a second. It used to call
     * [centreOnVehicle], which wrote `navigationZoom` every time — so a `+` tap
     * survived roughly one second before the next fix put it back, and zooming
     * felt broken. The camera is read first and only its target is replaced;
     * zoom, bearing and tilt are the driver's.
     *
     * Bearing and tilt are forced flat here on purpose: this is the browse
     * camera, and a parked driver reading the map does not want it rotating with
     * the compass. The guidance camera is a different policy
     * (`setCameraFollow` + `onNavigationFix`).
     */
    private fun followVehicle(fix: LocationFix) {
        val animate = centredOnVehicle
        centredOnVehicle = true
        mapHost?.withController { controller ->
            controller.moveCamera(
                controller.getCameraPosition().copy(
                    target = fix.point,
                    bearing = 0f,
                    tilt = 0f
                ),
                animate = animate
            )
        }
    }

    /**
     * Places the camera on the car at the configured navigation zoom.
     *
     * Used **once**, when the style becomes ready and there is already a fix:
     * the first view has no driver zoom to preserve, so the configured value is
     * the only sensible one. Every later camera move goes through
     * [followVehicle], which keeps whatever zoom is on screen — that is what
     * makes the `+`/`−` buttons stick.
     */
    private fun centreOnVehicle(fix: LocationFix, animate: Boolean) {
        centredOnVehicle = true
        mapHost?.withController { controller ->
            controller.moveCamera(
                CameraPosition(fix.point, settings.navigationZoom.toDouble()),
                animate = animate
            )
        }
    }

    /** Re-arms the follow camera after the driver has panned the map. */
    private fun resumeFollowing(fix: LocationFix) {
        centredOnVehicle = true
        mapHost?.withController { controller ->
            controller.setCameraFollow(followModeForNavigation())
            // Re-centring is about where the camera points, not how close it is:
            // the zoom the driver set survives it.
            controller.moveCamera(
                CameraPosition(
                    fix.point,
                    controller.getCameraPosition().zoom,
                    if (settings.cameraMode == AppSettings.CameraMode.NORTH_UP) {
                        0f
                    } else {
                        fix.bearingDeg ?: 0f
                    },
                    if (settings.cameraMode == AppSettings.CameraMode.PERSPECTIVE) {
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
        val guiding = MapChrome.showsGuidanceChrome(ui.state)

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

        // The speed pair and the destination card both live in the bottom-left
        // corner. The card wins while a route is being previewed; putting them
        // on top of each other is worse than showing one number fewer. Guidance
        // itself hides the card again, so the speed comes back for the drive.
        syncSpeedCluster()

        // §5's music-card rule now lives in MapChrome so the same answer can be
        // asserted without an Activity. It is deliberately narrower than
        // `guiding`: the card belongs to the *navigation* composition only, and
        // Map Browse must show none (`MapChromeTest`).
        musicCard.visibility =
            if (MapChrome.showsMusicCard(ui.state, settings.showMusicCardOnMap)) {
                View.VISIBLE
            } else {
                View.GONE
            }

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

        renderRouteOptions(ui)
    }

    /**
     * Fills the route-choice list inside the destination card.
     *
     * **Why a list and not "the best one, silently".** The driver asked for
     * "nhiều lộ trình thì hiển thị ra hết, và suggest lộ trình nào tốt nhất" —
     * show all of them, and say which is best. So the fastest is labelled
     * `Nhanh nhất` rather than being the only thing offered, and the other
     * corridors stay one tap away.
     *
     * Rows are rebuilt on every render because the selection changes the
     * highlighting and the distances are cheap to format. The list is hidden
     * outside a preview: during guidance the choice is closed.
     */
    private fun renderRouteOptions(ui: NavigationUiState) {
        val container = findViewById<LinearLayout>(R.id.routeOptions)
        val nav = navigator
        val options = nav?.alternatives().orEmpty()
        val previewing = ui.state == NavigationState.ROUTE_PREVIEW ||
            (ui.state == NavigationState.DESTINATION_PREVIEW && ui.route != null)
        // A list of one is not a choice: showing it would add a row that cannot
        // do anything and push the start button down.
        if (!previewing || options.size < 2) {
            container.visibility = View.GONE
            container.removeAllViews()
            return
        }
        container.visibility = View.VISIBLE
        container.removeAllViews()

        val active = nav?.route()
        val density = resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (6 * density).toInt()
        val gapPx = (4 * density).toInt()

        options.forEachIndexed { index, candidate ->
            val selected = candidate === active
            val row = TextView(this).apply {
                text = buildString {
                    if (index == 0) append(getString(R.string.nav_route_best)).append(" · ")
                    append(NavigationController.formatDistance(candidate.distanceMeters))
                    append(" · ")
                    append(ClockFormat.duration(candidate.durationSeconds))
                }
                textSize = 14f
                setTextColor(getColor(if (selected) R.color.accent else R.color.text_secondary))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                contentDescription = getString(
                    R.string.nav_route_option_desc, index + 1, options.size
                )
                setOnClickListener {
                    navigator?.selectAlternative(index)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = gapPx
            container.addView(row, lp)
        }
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
            userPanned = false
            val fix = lastFix ?: return@setOnClickListener
            // While guiding, re-centring means re-arming the navigation camera;
            // in browse it means going back to following the car, north up.
            // Neither changes the zoom: this button answers "where is the car",
            // and a driver who zoomed in to read a junction would not expect the
            // button to throw that away.
            if (navigator?.state() == NavigationState.NAVIGATING) {
                resumeFollowing(fix)
            } else {
                followVehicle(fix)
            }
        }
        // §6's Map Management entry, kept out of the phase surface on purpose.
        // The surface's own `Cài đặt bản đồ` only exists while the shell is
        // failing; once the map draws, `MapPhaseSurface.render` hides the whole
        // thing and that button with it. This one is part of the browse chrome,
        // so a loaded map still has a way into pack management.
        findViewById<ImageButton>(R.id.btnMapSettings).setOnClickListener {
            openMapManagement()
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

    /**
     * Opens Map Management, or says why it could not.
     *
     * [MapManagementActivity.start] returns false instead of throwing — it is
     * written to survive a renamed or undeclared Activity. Swallowing that false
     * would make the new settings button look dead, which is worse than the
     * crash it was protecting against, so the refusal is reported.
     */
    private fun openMapManagement() {
        if (!MapManagementActivity.start(this)) {
            Toast.makeText(this, R.string.map_management_unavailable, Toast.LENGTH_SHORT).show()
        } else {
            ScreenTransition.apply(this)
        }
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
