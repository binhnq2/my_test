package com.ts8.launcher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.WeatherLocation
import com.ts8.launcher.runtime.WeatherProvider
import com.ts8.launcher.runtime.WeatherReport

/**
 * The compact top overlay (§4).
 *
 * **What replaced what, and why.** Every normal screen used to draw
 * [StatusBarView] — clock, date, Wi-Fi, mobile, Bluetooth and battery. The TS8
 * already has a vendor bar painting exactly those six things, so the launcher
 * was drawing a second copy of a bar the driver was already looking at. §4's
 * rule is to stop repeating them.
 *
 * What is left is the one item the vendor bar cannot know: the weather, which
 * has no platform status icon. It is centred in the strip because the corners
 * belong to the screen underneath — the maneuver card on the left of the map,
 * the map controls on the right — and a centred chip collides with neither.
 *
 * **The weather is real or it is absent.** The chip is filled from
 * [WeatherProvider] (Open-Meteo, no key) using the car's own coordinates, and
 * when there is no fix or no answer it prints that it has nothing. It never
 * falls back to a fixed city or a plausible degree: `KNOWN_LIMITATIONS.md` D4
 * records why, and a made-up `28°C` on a car panel is worse than a blank chip.
 *
 * **Why it refreshes on its own.** The bar is attached to every screen, so it
 * cannot rely on a screen to push a value at it. It fetches on attach, re-tries
 * every [RETRY_MS] while it has no fix (the first fix often arrives a few
 * seconds after the launcher starts), and then refreshes every [REFRESH_MS].
 * All of it stops on detach, so a backgrounded launcher makes no requests.
 */
class CompactTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** The three states §4 requires. There is no fourth. */
    enum class WeatherState { LOADING, READY, UNAVAILABLE }

    /**
     * What the microphone does. Set by the screen that owns the bar, because
     * the permission request and the denial path live on the Activity and this
     * view is deliberately ignorant of them.
     */
    var onVoice: (() -> Unit)? = null

    // Assigned in `init`, after the layout has been inflated: a property
    // initialiser would run before that and find an empty FrameLayout.
    private val weatherChip: TextView
    private val btnVoice: ImageButton?
    private val btnQuickSettings: ImageButton?

    private val main = Handler(Looper.getMainLooper())

    /** Last reading that arrived, kept so a failed refresh does not blank the chip. */
    private var report: WeatherReport? = null

    /** Consecutive attempts that produced nothing; bounds the retry loop. */
    private var attempts = 0

    private var attached = false

    private val tick = Runnable { loadWeather() }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_compact_top_bar, this, true)
        weatherChip = findViewById(R.id.weatherChip)
        btnVoice = findViewById(R.id.btnVoice)
        btnQuickSettings = findViewById(R.id.btnQuickSettings)

        isClickable = false
        isFocusable = false

        btnVoice?.setOnClickListener { onVoice?.invoke() }
        btnQuickSettings?.setOnClickListener {
            runCatching { context.startActivity(QuickSettingsActivity.intentTo(context)) }
        }

        // Nothing is claimed before a request has answered.
        setWeather(WeatherState.LOADING, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        attempts = 0
        // The strip is drawn from the top of the window, which is behind the
        // vendor status bar. Move it below that bar before the first frame, or
        // the weather chip is painted under an opaque bar and never seen. Posted
        // because `rootWindowInsets` is not populated until the view is attached.
        post {
            if (attached) setTopInset(vendorBarInsetPx())
        }
        loadWeather()
    }

    override fun onDetachedFromWindow() {
        attached = false
        main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /**
     * Resolves a location, asks for its weather, and schedules the next attempt.
     *
     * Every path through this method either schedules a retry or a refresh, so
     * the chip cannot end up stuck on "Đang tải" for the life of the process.
     */
    private fun loadWeather() {
        if (!attached) return

        val runtimeFix = runCatching { AppGraph.current?.location?.lastFix?.point }.getOrNull()
        val location = WeatherLocation.resolve(context, runtimeFix)

        if (location == null) {
            // No GPS and no last-known fix. Say that, rather than a city the car
            // may not be in — the map screen will produce a fix shortly, and the
            // retry picks it up.
            if (report == null) {
                setWeather(
                    WeatherState.UNAVAILABLE,
                    context.getString(R.string.weather_no_gps)
                )
            }
            retryLater()
            return
        }

        if (report == null) setWeather(WeatherState.LOADING, null)

        WeatherProvider.fetch(location) { result ->
            if (!attached) return@fetch
            if (result != null) {
                report = result
                attempts = 0
                setWeather(WeatherState.READY, result.label)
                main.removeCallbacks(tick)
                main.postDelayed(tick, REFRESH_MS)
            } else {
                // A failed refresh keeps the last real reading: it is still a
                // reading for this car, just an older one. A failed *first*
                // attempt has nothing to keep.
                if (report == null) {
                    setWeather(
                        WeatherState.UNAVAILABLE,
                        context.getString(R.string.weather_unavailable)
                    )
                }
                retryLater()
            }
        }
    }

    private fun retryLater() {
        main.removeCallbacks(tick)
        if (attempts >= MAX_ATTEMPTS) return
        attempts++
        main.postDelayed(tick, RETRY_MS)
    }

    /**
     * Renders one of the three states.
     *
     * A READY call with no text is treated as UNAVAILABLE rather than drawing an
     * empty chip: "ready but saying nothing" is not a state the design has, and
     * an empty pill looks like a rendering bug to the driver.
     */
    private fun setWeather(state: WeatherState, text: CharSequence?) {
        val chip = weatherChip
        when {
            state == WeatherState.READY && !text.isNullOrBlank() -> {
                chip.text = text
                chip.contentDescription = text
                chip.visibility = View.VISIBLE
            }
            state == WeatherState.LOADING -> {
                chip.setText(R.string.weather_loading)
                chip.contentDescription = context.getString(R.string.weather_loading)
                chip.visibility = View.VISIBLE
            }
            else -> {
                chip.text = text ?: context.getString(R.string.weather_unavailable)
                chip.contentDescription =
                    context.getString(R.string.weather_unavailable_long)
                chip.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Keeps the same entry points [StatusBarView] exposed, so a screen that
     * refreshed the old bar still compiles and still means the same thing.
     *
     * There is no clock to refresh: the vendor bar owns it, and §4 forbids a
     * second one outside Parking Mode.
     */
    fun refreshClock() = Unit

    /**
     * Re-reads the weather now — used after a location permission is granted,
     * which is the one moment a previously unavailable reading becomes possible.
     */
    fun refreshState() {
        if (!attached) return
        attempts = 0
        main.removeCallbacks(tick)
        loadWeather()
    }

    private companion object {
        /** 15 minutes: the panel is not a weather station and the API is free. */
        const val REFRESH_MS = 15L * 60L * 1000L

        /** First fixes arrive seconds after boot; retry until one does. */
        const val RETRY_MS = 20L * 1000L

        /** After this many fruitless attempts, wait for a manual refresh. */
        const val MAX_ATTEMPTS = 15
    }
}
