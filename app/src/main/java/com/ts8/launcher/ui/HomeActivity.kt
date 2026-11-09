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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ts8.core.location.LocationFix
import com.ts8.core.map.CameraFollowMode
import com.ts8.core.map.CameraPosition
import com.ts8.core.map.maplibre.MapLibreMapController
import com.ts8.core.music.PlaybackState
import com.ts8.core.music.RepeatMode
import com.ts8.core.navigation.NavigationController
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
    private lateinit var mapPhase: MapPhaseSurface
    private lateinit var musicCard: MusicCardView
    private lateinit var navStrip: View
    private lateinit var navStripIcon: ImageView
    private lateinit var navStripText: TextView
    private lateinit var trafficAlertCard: LinearLayout
    private lateinit var trafficAlertIcon: ImageView
    private lateinit var trafficAlertLabel: TextView
    private lateinit var trafficAlertDistance: TextView

    private val main = Handler(Looper.getMainLooper())

    /** Last fix, so a zoom tap or a recentre does not need a new sample. */
    private var lastFix: LocationFix? = null

    /**
     * Whether the card has ever been placed on the car.
     *
     * The first placement picks a street-level zoom; every later one keeps the
     * driver's. Without this flag the first fix would follow at the pack's
     * province-wide `defaultZoom` (11) and the card would show half the city.
     */
    private var placedOnCar = false

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
            // Target only: the zoom stays wherever the driver left it. Writing
            // FOLLOW_ZOOM here on every fix is what made the `+`/`−` buttons on
            // this card look broken — the zoom was undone within a second.
            controller.moveCamera(
                controller.getCameraPosition().copy(
                    target = fix.point,
                    zoom = cameraZoom(controller),
                    bearing = bearing,
                    tilt = 0f
                ),
                animate = true
            )
        }
    }

    /**
     * The zoom to place the car at: street level the first time, then the
     * driver's own.
     *
     * The card starts on the pack centre at `defaultZoom` (11), which is a
     * province-wide view and useless for "where is my car". So the first
     * placement still picks a zoom; every later one keeps whatever is on screen,
     * which is the whole point of [zoomBy] surviving a fix.
     */
    private fun cameraZoom(controller: com.ts8.core.map.maplibre.MapLibreMapController): Double {
        if (!placedOnCar) {
            placedOnCar = true
            return FOLLOW_ZOOM
        }
        return controller.getCameraPosition().zoom
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

    // -- traffic alerts ------------------------------------------------------

    /**
     * Receives traffic-sign proximity alerts and sign-overlay updates.
     *
     * **Why Home shows traffic alerts at all.** The unit boots into Home and
     * often stays there — a driver listening to music with the map card in the
     * corner is the common case. If the alert card only existed on the map tab,
     * a speed-limit sign or a camera 200 m ahead would go unannounced whenever
     * the driver was not looking at the full map. Both screens therefore render
     * the same alert from the same process-wide runtime.
     *
     * **Threading.** The controller notifies on the main thread (it is fed from
     * [LocationRuntime], which delivers there), but the `post` is kept for the
     * same reason as in [MapActivity]: a future change to the store's loading
     * thread must not turn a view write into a crash.
     */
    private val trafficListener = object : TrafficAlertController.Listener {
        override fun onTrafficAlert(alert: TrafficAlert?) {
            main.post {
                if (!isStarted) return@post
                TrafficAlertViews.bind(
                    card = trafficAlertCard,
                    icon = trafficAlertIcon,
                    label = trafficAlertLabel,
                    distance = trafficAlertDistance,
                    alert = alert
                )
                // Speak once per sign: `shouldSpeak` is true only on the first
                // alert in the controller's 30 s cooldown window, so the driver
                // hears one prompt per sign rather than one per GPS sample.
                if (alert != null && alert.shouldSpeak) {
                    val prompt = TrafficAlertViews.voicePrompt(alert)
                    // Advisory, not urgent: never cut off an active navigation
                    // instruction for a traffic sign.
                    val spoke = graph.voice.speak(prompt, urgent = false)
                    if (!spoke) {
                        android.util.Log.w(TAG, "no voice for traffic alert: $prompt")
                    }
                }
            }
        }

        override fun onSignsUpdated(signs: List<TrafficSign>) {
            main.post {
                if (!isStarted) return@post
                // Home draws a real map card too, so the sign markers belong on
                // it exactly as they do on the full map screen. The runtime owns
                // the TrafficSign → TrafficSignOverlay conversion so the map
                // engine never sees a location-engine type. The zoom is read
                // from the controller so the overlay density matches what the
                // driver can see.
                mapHost?.withController { controller ->
                    val zoom = controller.getCameraPosition().zoom
                    graph.traffic.pushSignOverlayTo(controller, zoom)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindNavigation()

        graph = AppGraph.require()
        music = MusicRuntime.get(this)
        // Home now speaks traffic alerts, and `VoiceGuidance.speak` returns
        // `false` until the engine is initialised — a silent failure the driver
        // would read as "the alert feature is broken". `init` is idempotent, so
        // the map screen's own call is unaffected.
        graph.voice.init()

        speedValue = findViewById(R.id.speedValue)
        speedUnit = findViewById(R.id.speedUnit)
        speedLimit = findViewById(R.id.speedLimit)
        speedLimitValue = findViewById(R.id.speedLimitValue)
        gpsHint = findViewById(R.id.gpsHint)
        musicCard = findViewById(R.id.musicCard)
        navStrip = findViewById(R.id.navStrip)
        navStripIcon = findViewById(R.id.navStripIcon)
        navStripText = findViewById(R.id.navStripText)
        trafficAlertCard = findViewById(R.id.trafficAlertCard)
        trafficAlertIcon = findViewById(R.id.trafficAlertIcon)
        trafficAlertLabel = findViewById(R.id.trafficAlertLabel)
        trafficAlertDistance = findViewById(R.id.trafficAlertDistance)

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
        mapPhase = MapPhaseSurface(this, findViewById(R.id.mapPhase), graph.mapPhase) {
            // `Thử lại` after a stick was plugged in: drop the cached lookup,
            // which also drops the search service and router built on the
            // previous pack, then rebuild the view from scratch.
            graph.invalidatePack()
            rebuildMap()
        }
        wireMap(savedInstanceState)
        wireMusicCard()
        wireMapControls()
        wireSearchEntry()
        wireNavStrip()

        ensurePermissions()
    }

    /**
     * The two ways out of "no map" now live in the shared phase surface.
     *
     * This screen used to bind them itself: an import button that opened Map
     * Management and a retry that re-ran discovery. That is exactly what
     * §6's shared surface does, and doing it twice is how the two map screens
     * end up disagreeing. See [MapPhaseSurface].
     */

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
        graph.mapPhase.moveTo(MapPhaseStore.Phase.VALIDATING_PACK)
        // See `MapActivity.wireMap`: the host drives all six §6 phases, because
        // it owns the view that reports the first frame. Home keeps only the
        // camera default it wants when the style comes up.
        host.setRenderListener(object : MapHost.RenderListener {
            override fun onStyleReady(controller: MapLibreMapController) {
                // Pack centre until the first fix arrives, so the screen is a
                // map rather than a grey rectangle for the first seconds.
                AppGraph.current?.pack()?.let { pack ->
                    pack.center?.let { centre ->
                        val fix = lastFix
                        val pos = fix?.point ?: centre
                        val bearing = fix?.bearingDeg ?: 0f
                        controller.moveCamera(
                            CameraPosition(pos, pack.metadata.defaultZoom),
                            animate = false
                        )
                        controller.setVehicleMarker(pos, bearing)
                    }
                }
                controller.setCameraFollow(CameraFollowMode.NONE)
                // Replay the sign overlay on every style rebuild (day/night
                // switch, layer toggle, `rebuildMap` after a pack change).
                // Without this the markers vanish on the first style change and
                // never come back until a new cell loads. The zoom is read from
                // the controller so the overlay density matches the view.
                val zoom = controller.getCameraPosition().zoom
                graph.traffic.pushSignOverlayTo(controller, zoom)
            }
        })
        host.create(savedInstanceState)

        val failure = host.failure
        if (failure != null) {
            // Real message, real cause, and a code the driver can read out.
            // Never a drawn placeholder map.
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
        // The map shell's own `Cài đặt bản đồ` lives inside `view_map_phase`
        // and is hidden the moment the map reaches READY. Home is the screen the
        // unit boots into, so without this one a working map would have no way
        // into pack management from here at all.
        findViewById<ImageButton>(R.id.btnMapSettings).setOnClickListener {
            if (!MapManagementActivity.start(this)) {
                Toast.makeText(
                    this,
                    R.string.map_management_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                ScreenTransition.apply(this)
            }
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
        // Guard against null: on a restart after crash the layout may not be
        // fully inflated when onResume fires.
        findViewById<ImageButton?>(R.id.btnZoomIn)?.visibility = zoom
        findViewById<ImageButton?>(R.id.btnZoomOut)?.visibility = zoom
        findViewById<ImageButton?>(R.id.btnRecenter)?.visibility =
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
     *
     * The zoom is deliberately left alone — same rule as the map screen. This
     * button answers "where is the car", and a driver who zoomed in on this card
     * to read a junction should not lose that by asking to be re-centred.
     */
    private fun recentre() {
        val fix = lastFix ?: return
        mapHost?.withController { controller ->
            controller.setCameraFollow(CameraFollowMode.FOLLOW_BEARING)
            controller.moveCamera(
                controller.getCameraPosition().copy(
                    target = fix.point,
                    zoom = cameraZoom(controller),
                    bearing = fix.bearingDeg ?: 0f,
                    tilt = 0f
                ),
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
        // `mapHost` also holds the phase surface (the include), so the container
        // is emptied *around* it rather than with `removeAllViews`: that call
        // would detach the error surface as well, and a rebuild that then failed
        // would have nothing left to report the failure on.
        val phase = findViewById<View>(R.id.mapPhase)
        val phaseIndex = container.indexOfChild(phase)
        container.removeAllViews()
        if (phaseIndex >= 0) {
            container.addView(
                phase,
                // Clamped, not the captured index. `removeAllViews` above leaves
                // the container empty, so re-inserting at the index the phase had
                // *before* the clear throws `IndexOutOfBoundsException: index=1
                // count=0` — which is exactly what killed the app when the driver
                // tapped `Thử lại` on the TS8 (log 2026-11-08 18:29:28). The map
                // view is added back at 0 by `MapHost`, so the phase lands in the
                // same place either way.
                phaseIndex.coerceIn(0, container.childCount),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        // The surface caches nothing about the renderer; it is bound to the
        // store, which survived. Re-binding would add a second listener.
        graph.mapPhase.reset()
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
        // Same reference-counted pipeline the map screen uses. Because the
        // runtime is process-wide, moving Home → Map does not reload the
        // spatial index or re-open the GPS receiver; the count simply goes
        // 1 → 2 → 1.
        graph.traffic.acquire(trafficListener)
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
        // Same catch-up for traffic alerts: the runtime kept running while this
        // screen was away, so the card must reflect the alert that is current
        // now rather than wait for the next GPS sample to arrive.
        TrafficAlertViews.bind(
            card = trafficAlertCard,
            icon = trafficAlertIcon,
            label = trafficAlertLabel,
            distance = trafficAlertDistance,
            alert = graph.traffic.currentAlert()
        )
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
        graph.traffic.release(trafficListener)
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
        private const val TAG = "Ts8HomeActivity"
        private const val REQUEST_PERMISSIONS = 0x7410
        private const val MIN_ZOOM = 6.0
        private const val MAX_ZOOM = 18.0

        /**
         * Zoom the home map settles at the **first** time it is placed on the
         * car. After that the driver's own zoom wins; see [cameraZoom].
         */
        private const val FOLLOW_ZOOM = 16.5
    }
}
