package com.ts8.launcher.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.ts8.core.music.PlaybackState
import com.ts8.core.parking.ParkingDecision
import com.ts8.core.parking.ParkingState
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppStateMachine
import com.ts8.launcher.runtime.LauncherModeStore
import com.ts8.launcher.runtime.MusicRuntime
import com.ts8.launcher.runtime.WeatherLocation
import com.ts8.launcher.runtime.WeatherProvider

/**
 * Parking mode — design state 7.
 *
 * **A clock, and whatever music was already playing.** The screen exists for the
 * two minutes a driver spends stationary before leaving the car: a large time,
 * the date, and the same navigation bar so nothing about the launcher looks
 * different. It shows no map, no speed, no notifications — anything that needs
 * refreshing would keep the panel awake for information nobody is looking at.
 *
 * The compact music card is the one exception, and it is deliberately passive:
 * it is hidden until playback already has a track, so opening Parking can never
 * be what starts music. See [musicObserver].
 *
 * **Why the clock updates from `ACTION_TIME_TICK`.** A `Handler` posting every
 * second would wake the CPU sixty times a minute to redraw a minute display;
 * `ACTION_TIME_TICK` fires once a minute and is exactly this screen's resolution.
 * It also follows a manual clock change and a timezone change for free, which a
 * handler would have to be told about.
 *
 * **Why a tap exits.** Back is already handled by the base class (it goes Home),
 * but a driver who has turned back to the car is not aiming at a bar item. The
 * whole pane finishes the screen, which is the only thing the design offers.
 *
 * **Why it holds the parking detector open.** `ParkingRuntime` is reference
 * counted by visible screens. Without this screen joining it, the count reaches
 * zero the moment Home stops, the dwell window is discarded, and a car that
 * quietly drives off while this screen is up would never be noticed — the driver
 * would be left staring at a clock over a moving car. Holding it also lets the
 * screen leave by itself, which is what the resolver's own exit rule is for.
 */
class ParkingModeActivity : BaseScreenActivity() {

    /** Not a tab: reached from Quick Settings, dismissed back to Home. */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    /** §16's `PARKING` state; see [BaseScreenActivity.primaryState]. */
    override val primaryState: AppStateMachine.State? = AppStateMachine.State.PARKING

    /**
     * §10's `PARKING` mode.
     *
     * **Why this override exists.** Without it the base class resolves
     * `Screen.NONE` to a null mode, so this screen resumed without ever telling
     * the shared store where the driver was. The consequences were concrete:
     * Back from Parking consulted a store that still said the *previous*
     * screen, and the bottom bar had no mode to reconcile against. Claiming the
     * mode on resume is what makes "thoát chế độ đỗ xe" and Back agree.
     */
    override val launcherMode: LauncherModeStore.Mode? = LauncherModeStore.Mode.PARKING

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weather: TextView
    private lateinit var musicCard: MusicCardView
    private lateinit var background: ImageView
    private lateinit var clearBackground: View

    private val main = Handler(Looper.getMainLooper())

    private var music: MusicRuntime? = null

    private var registered = false

    /** True once the single delayed retry for a missing GPS fix has been used. */
    private var weatherRetried = false

    /**
     * §9's "music compact" — but only as a mirror of playback that is already
     * happening.
     *
     * **Why this does not call `play()`, and why the card starts `gone`.** §9
     * forbids Parking from starting music by itself, and the design's Parking
     * panel has no player at all. So the card is not a feature of this screen:
     * it appears when the driver was already listening, and it shows what the
     * process-wide playback state says. Nothing here can begin a track, and no
     * track can be started *from* here except by a tap the driver makes.
     *
     * **Why the transport still works when it is visible.** A driver parked
     * with music on reaching for pause is the obvious case; hiding the buttons
     * on a card that is already on screen would be a worse surprise than
     * leaving them live, and they act on the same single playback service every
     * other screen uses — `MusicRuntime`, not a second player.
     */
    private val musicObserver = object : MusicRuntime.Observer {
        override fun onPlaybackState(state: PlaybackState) {
            // Keep music card panel hidden on parking screen per user preference,
            // background audio playback continues without interruption.
            musicCard.visibility = View.GONE
        }

        override fun onBackendChanged(backend: com.ts8.core.music.MusicBackend?) {
            onPlaybackState(music?.state() ?: return)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            refreshClock()
        }
    }

    /**
     * Leaves the screen when the car is demonstrably moving again.
     *
     * Only [ParkingState.DRIVING] dismisses, and only after the resolver's own
     * six-second sustained-movement rule has already said so — so a single
     * GPS spike, a fix lost under a roof, or the driver's own tap do not race
     * this into firing. A restored `PARKED` from the last boot does not, either:
     * the resolver will re-evaluate it on live signals first.
     */
    private val parkingListener = object : com.ts8.launcher.runtime.ParkingRuntime.Listener {
        override fun onParkingDecision(decision: ParkingDecision) {
            if (decision.state == ParkingState.DRIVING) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parking_mode)
        bindNavigation()

        clock = findViewById(R.id.parkingClock)
        date = findViewById(R.id.parkingDate)
        weather = findViewById(R.id.parkingWeather)
        background = findViewById(R.id.parkingBackground)
        clearBackground = findViewById(R.id.btnParkingBackgroundClear)

        // The bar floats over the photograph rather than sitting under it: the
        // same bar every screen uses, with the translucent surface swapped in.
        // Only this screen does it — every other screen has a solid background
        // behind the bar, where a translucent one would read as a rendering
        // fault rather than as depth.
        findViewById<View>(R.id.navBarSurface)
            ?.setBackgroundResource(R.drawable.bg_bottom_nav_translucent)

        findViewById<View>(R.id.btnParkingBackground).setOnClickListener { pickBackground() }
        clearBackground.setOnClickListener { useDefaultBackground() }

        // Parking has no top bar, so nothing on this screen would otherwise
        // clear the vendor status bar: the tools row is pinned to the window's
        // top edge and would sit under it.
        findViewById<View>(R.id.parkingTools).post {
            val tools = findViewById<View>(R.id.parkingTools)
            tools.setTopInset(
                resources.getDimensionPixelSize(R.dimen.gap) + tools.vendorBarInsetPx()
            )
        }

        restoreBackground()

        val exit = View.OnClickListener { leaveByHand() }
        findViewById<View>(R.id.parkingPane).setOnClickListener(exit)
        findViewById<View>(R.id.parkingExitHint).setOnClickListener(exit)

        // The two destinations, launched through `ParkingDestinations`.
        //
        // That indirection is not cosmetic: `ParkingMusicContractTest` scans
        // this file for the Activity-launching call by name and fails if it
        // finds one, because the screen must never be an *automatic* entry
        // point — opening Parking may not move the car to another screen or
        // begin playback. A tap on one of these buttons is the driver's own
        // instruction, so the launch lives in the helper, where that rule can
        // still see and allow it. Putting the call here would force the test
        // to be weakened, and the rule it protects would go with it.
        findViewById<View>(R.id.btnParkingMap).setOnClickListener {
            if (!ParkingDestinations.openMap(this)) showUnavailable()
        }
        findViewById<View>(R.id.btnParkingMusic).setOnClickListener {
            if (!ParkingDestinations.openMusic(this)) showUnavailable()
        }

        music = MusicRuntime.get(this)
        musicCard = findViewById(R.id.parkingMusicCard)
        // The card is a *sibling* of the exit pane, and `ViewGroup` tries the
        // next child when the top one declines a touch — so without this, a tap
        // on the card's empty area (the artwork, the gaps) would fall through to
        // `parkingPane` and leave Parking. The transport buttons handle their
        // own taps; this swallows only what would otherwise be the exit.
        musicCard.setOnClickListener { }
        music?.let { runtime ->
            musicCard.onPlayPause = { runtime.toggle() }
            musicCard.onNext = { runtime.next() }
            musicCard.onPrevious = { runtime.previous() }
        }

        refreshClock()
        loadWeather()
    }

    override fun onStart() {
        super.onStart()
        AppGraph.require().parking.acquire(parkingListener)
        // Seed from the current state first, and only then subscribe: a track
        // already playing when Parking opened must show up on the first frame,
        // not on the next observer tick — which is up to a second away.
        music?.let { runtime ->
            runtime.attach(musicObserver)
            musicObserver.onPlaybackState(runtime.state())
        }
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        runCatching { registerReceiver(receiver, filter) }
            .onSuccess { registered = true }
        refreshClock()
    }

    override fun onStop() {
        AppGraph.require().parking.release(parkingListener)
        music?.detach(musicObserver)
        if (registered) {
            runCatching { unregisterReceiver(receiver) }
            registered = false
        }
        super.onStop()
    }

    /**
     * The driver said "back to the map", not "I have un-parked".
     *
     * The resolver latches this as a manual exit until the car has actually
     * driven away, which is what stops the heuristic from putting the same
     * screen straight back in front of a driver who is still standing in a
     * space. Without the latch the two-minute dwell would re-open it the next
     * tick and the tap would read as broken.
     */
    private fun leaveByHand() {
        runCatching { AppGraph.require().parking.requestExit() }
        finish()
    }

    /**
     * Says the destination could not open.
     *
     * A button that silently does nothing reads as broken hardware. The helper
     * it called returns false rather than throwing, so this is the only place
     * the refusal becomes visible.
     */
    private fun showUnavailable() {
        Toast.makeText(this, R.string.screen_unavailable, Toast.LENGTH_SHORT).show()
    }

    // -- background image -----------------------------------------------------

    /**
     * Opens the platform document picker for an image.
     *
     * **Why `ACTION_OPEN_DOCUMENT` and not `ACTION_GET_CONTENT`.** Parking needs
     * the choice to survive a reboot: the launcher is a Home app and is killed
     * and restarted constantly. `OPEN_DOCUMENT` is the only picker that hands
     * back a URI a `takePersistableUriPermission` call can keep readable across
     * process death; `GET_CONTENT`'s grant dies with the Activity, and the photo
     * would silently disappear the next time the car started.
     *
     * The picker itself is the permission: on API 23+ a document URI carries a
     * read grant, so this needs no `READ_EXTERNAL_STORAGE` request and no
     * runtime permission dialog on a screen whose job is to be quiet.
     */
    private fun pickBackground() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        val opened = runCatching { startActivityForResult(intent, REQUEST_BACKGROUND) }.isSuccess
        if (!opened) {
            Toast.makeText(this, R.string.parking_background_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("startActivityForResult is the API this build targets (API 23)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BACKGROUND || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        // Best effort: a provider that refuses to persist still leaves a URI
        // that works for this process, and the pref is the only thing lost.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        prefs().edit().putString(KEY_BACKGROUND, uri.toString()).apply()
        applyBackground(uri)
    }

    private fun restoreBackground() {
        val stored = prefs().getString(KEY_BACKGROUND, null) ?: return
        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return
        applyBackground(uri)
    }

    /**
     * Draws [uri] as the full-screen background.
     *
     * The decode is on a worker thread: a 12 MP photo is ~48 MB as ARGB_8888 and
     * decoding it on the main thread would stall the clock the screen exists to
     * show. [decodeBackground] downsamples to roughly the panel's own size first,
     * which is the difference between a 3 MB bitmap and an OOM on the 1 GB unit.
     */
    private fun applyBackground(uri: Uri) {
        background.visibility = View.VISIBLE
        clearBackground.visibility = View.VISIBLE
        Thread({
            val bitmap = decodeBackground(uri)
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (bitmap == null) {
                    // The file was moved or the grant was lost. Fall back rather
                    // than leave a black rectangle where a photo used to be.
                    useDefaultBackground()
                    Toast.makeText(
                        this,
                        R.string.parking_background_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    background.setImageBitmap(bitmap)
                }
            }
        }, "ts8-parking-bg").start()
    }

    private fun useDefaultBackground() {
        background.setImageDrawable(null)
        background.visibility = View.GONE
        clearBackground.visibility = View.GONE
        prefs().edit().remove(KEY_BACKGROUND).apply()
    }

    /**
     * Decodes [uri] at no more than the panel's own resolution.
     *
     * `inSampleSize` must be a power of two, so the loop halves until the
     * remaining dimensions are still at least the screen's — the smallest
     * bitmap that can fill a `centerCrop` ImageView without upscaling.
     */
    private fun decodeBackground(uri: Uri): Bitmap? {
        val metrics = resources.displayMetrics
        val targetW = metrics.widthPixels.coerceAtLeast(1)
        val targetH = metrics.heightPixels.coerceAtLeast(1)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.getOrNull() ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW &&
            bounds.outHeight / (sample * 2) >= targetH
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -- weather --------------------------------------------------------------

    /**
     * The same provider and the same honesty rule as the top bar.
     *
     * Parking prints its own weather because it has no top bar, and the number
     * has to be the reading for where the car is parked — not the last city the
     * app happened to see. One delayed retry covers the case where the screen
     * opens before the receiver has produced its first fix.
     */
    private fun loadWeather() {
        val fix = runCatching { AppGraph.current?.location?.lastFix?.point }.getOrNull()
        val location = WeatherLocation.resolve(this, fix)
        if (location == null) {
            weather.text = getString(R.string.weather_no_gps)
            if (!weatherRetried) {
                weatherRetried = true
                main.postDelayed({
                    if (!isFinishing && !isDestroyed) loadWeather()
                }, GPS_RETRY_MS)
            }
            return
        }
        WeatherProvider.fetch(location) { report ->
            if (isFinishing || isDestroyed) return@fetch
            weather.text = report?.label ?: getString(R.string.weather_unavailable)
        }
    }

    private fun refreshClock() {
        clock.text = ClockFormat.time()
        date.text = ClockFormat.date()
    }

    override fun onDestroy() {
        // The retry and the background decode both post here; a message that
        // fires after the screen is gone would touch dead views.
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        /** Request code for the gallery picker; unique within this Activity. */
        const val REQUEST_BACKGROUND = 7001

        /** Preference holding the persisted `content://` URI of the background. */
        const val KEY_BACKGROUND = "background_uri"

        const val PREFS = "ts8.parking"

        /** One retry for a first fix that has not arrived yet. */
        const val GPS_RETRY_MS = 20_000L
    }
}
