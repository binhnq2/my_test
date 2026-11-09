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
