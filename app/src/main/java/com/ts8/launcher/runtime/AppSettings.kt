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

    /**
     * Draw the compact music card on the Map screen.
     *
     * **Off by default, and that default is the fix for a reported defect.** The
     * card used to appear as soon as guidance started, on top of the route
     * panel, and the driver read it as "the map changed layout when I chose a
     * route". The card is still available from the `Âm nhạc` tab and from Home,
     * so hiding it on Map loses no control; a driver who wants it back can turn
     * it on here.
     */
    var showMusicCardOnMap: Boolean
        get() = boolOr(KEY_MUSIC_MAP, false)
        set(value) = putBool(KEY_MUSIC_MAP, value)

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
            .remove(KEY_MUSIC_MAP)
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

    /**
     * Constant correction added to GPS speed, in km/h, `-20..+20`.
     *
     * The driver's report was "tốc độ trên map đang lệch khoảng 4 đến 5 km/h so
     * với tốc độ xe". The cluster is the reference the driver trusts, so the
     * offset exists to make the readout agree with it. Signed, because a car on
     * smaller-than-stock wheels reads high rather than low.
     */
    var gpsSpeedOffsetKmh: Int
        get() = intOr(KEY_GPS_OFFSET, 0)
        set(value) = prefs.edit().putInt(KEY_GPS_OFFSET, value.coerceIn(-20, 20)).apply()

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
        // The master switch owns every ad-hoc alert, the speed panel owns
        // overspeed only. Both must be on for a beep to sound.
        alertsEnabled = drivingAlertsEnabled && speedAlertsEnabled,
        alertForm = speedAlertForm,
        alertVolume = speedAlertVolume,
        sustainedOnly = sustainedOverOnly,
        deadBandMps = gpsFilterKmh / 3.6,
        gpsOffsetKph = gpsSpeedOffsetKmh.toDouble(),
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
     * **On by default.** Parking is the design's state 7 and the screen a
     * driver expects to see when they stop: §9's whole point is that the clock
     * and the map/music buttons arrive without being asked for. Leaving it off
     * meant a fresh install never showed it, which is the "màn hình parking
     * không hoạt động" report the driver made after installing this build.
     *
     * **What keeps that honest now that it is on.** This flag is not the
     * evidence — it is permission to act on evidence the resolver gathers
     * independently, and every one of those gates still applies: two minutes of
     * sustained dwell (a red light is ninety seconds at worst), a location
     * engine verdict that the position stopped wandering, a dwell restart when
     * the fix drops out, and outright refusal during a call, the reverse camera
     * or an active route. `ParkingStateResolver.automatic` checks the blockers
     * and the confidence *before* it reads this flag at all, and the screen
     * itself closes again after six seconds of sustained movement.
     *
     * A driver who does not want it can turn it off in Settings; the setting is
     * a preference over a working default rather than the switch that makes the
     * feature exist.
     */
    var parkingAutoEnter: Boolean
        get() = boolOr(KEY_PARKING_AUTO, true)
        set(value) = putBool(KEY_PARKING_AUTO, value)

    // =======================================================================
    // Voice (§10, offline pilot)
    // =======================================================================

    /**
     * Which recogniser the launcher is allowed to use.
     *
     * **`AUTO` by default, and why.** The TS8 ships no `RecognitionService` at
     * all, so on that hardware `AUTO` reaches the offline model as soon as one
     * is installed and keeps the platform path otherwise — which is exactly the
     * behaviour that makes a single APK work on both a working phone-like unit
     * and this firmware. `OFFLINE_ONLY` is the fleet setting: it forbids the
     * network path outright, and a driver who chose it must not have their audio
     * sent to a server because a model happened to be missing.
     *
     * Stored by name, not ordinal: an enum reordered in a later build must not
     * silently move a unit out of `OFFLINE_ONLY`.
     */
    var recognitionPolicy: com.ts8.core.voice.offline.RecognitionPolicy
        get() = com.ts8.core.voice.offline.RecognitionPolicy.fromName(
            prefs.getString(KEY_VOICE_POLICY, null)
        )
        set(value) = put(KEY_VOICE_POLICY, value.name)

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
    // Driving alerts (inspired by reference APK)
    // =======================================================================

    /**
     * Master switch for all driving alerts.
     *
     * `driving_alerts_muted` in the reference APK. When false, individual alert
     * toggles below are honoured; when true, no alert plays at all.
     */
    var drivingAlertsEnabled: Boolean
        get() = boolOr(KEY_DRIVING_ALERTS, true)
        set(value) = putBool(KEY_DRIVING_ALERTS, value)

    /** Speed-overspeed voice alert. */
    var alertSpeedEnabled: Boolean
        // One switch, two screens. `Cài đặt` and the speed panel in `Quản lý
        // bản đồ` both offer this toggle; storing them in two keys let one
        // screen paint BẬT while the alert stayed silent because the other had
        // written TẮT. Delegating to the older key makes both read the same
        // value.
        get() = speedAlertsEnabled
        set(value) {
            speedAlertsEnabled = value
        }

    /** Traffic camera alert. */
    var alertCameraEnabled: Boolean
        get() = boolOr(KEY_ALERT_CAM, true)
        set(value) = putBool(KEY_ALERT_CAM, value)

    /** Navigation turn-by-turn alert. */
    var alertNavigationEnabled: Boolean
        get() = boolOr(KEY_ALERT_NAV, true)
        set(value) = putBool(KEY_ALERT_NAV, value)

    // =======================================================================
    // Voice feedback (beep + TTS)
    // =======================================================================

    /**
     * Whether to play beep tones during voice interaction.
     *
     * Beep on mic open, beep on recognition success, error beep on failure.
     * Inspired by the reference APK's MediaPlayer alert pattern.
     */
    var voiceBeepEnabled: Boolean
        get() = boolOr(KEY_VOICE_BEEP, true)
        set(value) = putBool(KEY_VOICE_BEEP, value)

    /**
     * Whether to speak the listening prompt ("Xin mời nói") when mic opens.
     */
    var voiceListeningPromptEnabled: Boolean
        get() = boolOr(KEY_VOICE_PROMPT, true)
        set(value) = putBool(KEY_VOICE_PROMPT, value)

    /**
     * Whether to auto-execute recognised commands without confirmation.
     *
     * Default true: the hands-free pattern — beep → TTS prompt → listen →
     * beep end → execute. No Yes/No dialog.
     */
    var voiceAutoExecute: Boolean
        get() = boolOr(KEY_VOICE_AUTO_EXEC, true)
        set(value) = putBool(KEY_VOICE_AUTO_EXEC, value)

    // =======================================================================
    // Map pack download server
    // =======================================================================

    /**
     * Base URL for the TS8 map pack download server.
     *
     * The server must expose `GET {base}/catalog.json` and
     * `GET {base}/{fileName}`. Empty = unconfigured; the Map Management
     * screen shows the manual import path only.
     *
     * Example: `https://maps.ts8.vn/packs`
     */
    var mapServerUrl: String
        get() = prefs.getString(KEY_MAP_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MAP_SERVER_URL, value.trim()).apply()

    /**
     * Whether the driver has been warned that map downloads use data.
     * Set true after the first "Downloads use Wi-Fi or mobile data" dialog.
     */
    var mapDownloadConsented: Boolean
        get() = boolOr(KEY_MAP_DOWNLOAD_CONSENT, false)
        set(value) = putBool(KEY_MAP_DOWNLOAD_CONSENT, value)

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
        private const val KEY_MUSIC_MAP = "disp_music_map_v1"
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
        private const val KEY_GPS_OFFSET = "speed_gps_offset_v1"
        private const val KEY_SMOOTHING = "speed_smoothing_v1"
        private const val KEY_ALERTS = "speed_alerts_v1"
        private const val KEY_THRESHOLD = "speed_threshold_v1"
        private const val KEY_ALERT_FORM = "speed_alert_form_v1"
        private const val KEY_ALERT_VOLUME = "speed_alert_volume_v1"
        private const val KEY_COOLDOWN = "speed_cooldown_s_v1"
        private const val KEY_SUSTAINED = "speed_sustained_v1"

        // Pack + parking + online
        private const val KEY_VOICE_POLICY = "voice_policy_v1"
        private const val KEY_ACTIVE_PACK = "pack_active_path_v1"
        private const val KEY_NO_MAP = "pack_no_map_v1"
        private const val KEY_PARKING_AUTO = "parking_auto_v1"
        private const val KEY_ONLINE_URL = "online_server_url_v1"
        private const val KEY_ONLINE_USER = "online_server_user_v1"
        private const val KEY_ONLINE_TOKEN = "online_server_token_v1"
        private const val KEY_ONLINE_ENABLED = "online_enabled_v1"
        private const val KEY_ONLINE_FEED = "online_feed_url_v1"
        private const val KEY_ONLINE_KIND = "online_server_kind_v1"
        // Driving alerts + voice feedback
        private const val KEY_DRIVING_ALERTS = "driving_alerts_enabled_v1"
        private const val KEY_ALERT_CAM = "driving_alert_cam_v1"
        private const val KEY_ALERT_NAV = "driving_alert_nav_v1"
        private const val KEY_VOICE_BEEP = "voice_beep_v1"
        private const val KEY_VOICE_PROMPT = "voice_prompt_v1"
        private const val KEY_VOICE_AUTO_EXEC = "voice_auto_exec_v1"

        // Map server
        private const val KEY_MAP_SERVER_URL = "map_server_url_v1"
        private const val KEY_MAP_DOWNLOAD_CONSENT = "map_download_consent_v1"

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
