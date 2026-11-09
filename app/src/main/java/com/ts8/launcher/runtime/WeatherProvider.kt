package com.ts8.launcher.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.ts8.core.map.GeoPoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One real weather reading for a real coordinate.
 *
 * **Why this type exists at all.** The top bar used to print a hard-coded
 * `Đà Nẵng 28°C · Nắng` whenever its HTTP call failed — a fabricated number in
 * the one place a driver reads at a glance. `KNOWN_LIMITATIONS.md` D4 says a
 * plausible-looking degree is exactly what this project forbids, so the failure
 * path now returns `null` and the chip says it has nothing.
 */
data class WeatherReport(
    val temperatureC: Int,
    /** Vietnamese condition word, from the WMO code the API returns. */
    val condition: String,
    val latitude: Double,
    val longitude: Double,
    val fetchedAtMs: Long = System.currentTimeMillis()
) {
    /** The one line both the top bar and the parking screen print. */
    val label: String get() = "$temperatureC°C · $condition"
}

/**
 * Open-Meteo, the free provider that needs no API key and no account.
 *
 * **Why this provider.** It is the only weather service that answers a plain
 * HTTPS GET with current conditions for arbitrary coordinates without a key,
 * a signup or an attribution requirement that would need a network-loaded
 * image. That is what makes it usable on a head unit whose owner cannot be
 * asked to register for anything, and it keeps the app's only network call a
 * single JSON fetch whose schema is small enough to parse without a library.
 *
 * **What it is not.** Not a cached forecast, not a fallback to a fixed city: a
 * request either returns a reading for the coordinates it was given, or
 * `null`. Nothing here invents a temperature.
 *
 * Threading: [fetch] does the request on its own thread and delivers on the
 * main thread, so a caller in a View can assign the result directly.
 */
object WeatherProvider {

    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    private const val TIMEOUT_MS = 5_000

    /** Logcat filter for diagnosing a fetch that returns nothing. */
    private const val TAG = "Ts8Weather"

    private val main = Handler(Looper.getMainLooper())

    /**
     * Fetches current conditions for [location].
     *
     * @param callback receives the reading, or `null` when the request failed,
     *        the body did not parse, or there is no usable temperature in it.
     */
    fun fetch(location: GeoPoint, callback: (WeatherReport?) -> Unit) {
        // Three decimals is ~110 m — far finer than a weather grid cell, and it
        // keeps the URL stable so a car parked at the same kerb reuses the same
        // request instead of a new one per GPS jitter.
        val lat = round(location.lat)
        val lon = round(location.lon)
        Thread({
            val report = runCatching { request(lat, lon) }.getOrNull()
            main.post { callback(report) }
        }, "ts8-weather").apply { isDaemon = true }.start()
    }

    private fun request(lat: Double, lon: Double): WeatherReport? {
        val url = URL(
            "$ENDPOINT?latitude=$lat&longitude=$lon" +
                "&current_weather=true&timezone=auto"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $code for $url")
                return null
            }
            val body = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val current = JSONObject(body).optJSONObject("current_weather") ?: run {
                Log.w(TAG, "no current_weather in response (${body.length} bytes)")
                return null
            }
            val temperature = current.optDouble("temperature", Double.NaN)
            if (temperature.isNaN()) {
                Log.w(TAG, "no temperature in current_weather")
                return null
            }
            WeatherReport(
                temperatureC = Math.round(temperature).toInt(),
                condition = condition(current.optInt("weathercode", -1)),
                latitude = lat,
                longitude = lon
            )
        } catch (e: Exception) {
            // The one place a network failure becomes visible: without it a
            // missing chip is indistinguishable from a missing fix.
            Log.w(TAG, "request failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * WMO 4677 code to the word a Vietnamese driver expects.
     *
     * The API returns the code, not text, so this table is the whole
     * translation layer. An unknown code maps to `--` rather than to a guess:
     * a condition the app does not understand is not the same as "Nắng".
     */
    private fun condition(code: Int): String = when (code) {
        0 -> "Trời quang"
        1 -> "Ít mây"
        2 -> "Có mây"
        3 -> "Nhiều mây"
        45, 48 -> "Sương mù"
        51, 53, 55, 56, 57 -> "Mưa phùn"
        61, 63, 65, 66, 67 -> "Mưa"
        71, 73, 75, 77 -> "Tuyết"
        80, 81, 82 -> "Mưa rào"
        85, 86 -> "Mưa tuyết"
        95, 96, 99 -> "Dông"
        else -> "--"
    }

    private fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}

/**
 * Where the weather request gets its coordinates.
 *
 * **Why two sources.** The launcher's own [LocationRuntime] only has a fix while
 * a screen that needs one is holding it, and the top bar is drawn on screens
 * that do not. `LocationManager.getLastKnownLocation` fills that gap without
 * starting a provider, so the chip can be correct on the music screen too.
 *
 * **Why null instead of a default city.** A pack centre is not where the car is;
 * printing Đà Nẵng's weather to a car in Huế would be the same lie as printing
 * a fixed 28°C. No fix means no reading, and the chip says so.
 */
object WeatherLocation {

    fun resolve(context: Context, runtimeFix: GeoPoint?): GeoPoint? {
        runtimeFix?.let { return it }
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        for (provider in PROVIDERS) {
            val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                ?: continue
            // A fresh emulator and some vendor ROMs report 0,0 until the first
            // real fix; weather for the Gulf of Guinea is not useful.
            if (location.latitude == 0.0 && location.longitude == 0.0) continue
            return GeoPoint(location.latitude, location.longitude)
        }
        return null
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** GPS first: it is the one that knows where the car actually is. */
    private val PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
}
