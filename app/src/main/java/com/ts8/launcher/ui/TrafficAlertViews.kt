package com.ts8.launcher.ui

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ts8.core.traffic.SignType
import com.ts8.core.traffic.TrafficAlert
import com.ts8.launcher.R

/**
 * Binds a [TrafficAlert] onto the traffic alert card views.
 *
 * Shared by Home and Map so both screens show the same alert UI: an icon, a
 * label, and a distance in metres. When `alert` is `null`, the card is hidden.
 *
 * **Why this is not a custom view.** The alert card is four views in a
 * `LinearLayout`; a custom view would add an inflate and a class for no
 * rendering benefit. The binding is simple enough that a function is clearer.
 */
object TrafficAlertViews {

    /**
     * Binds the alert card.
     *
     * @param card The `trafficAlertCard` container.
     * @param icon The `trafficAlertIcon` ImageView.
     * @param label The `trafficAlertLabel` TextView.
     * @param distance The `trafficAlertDistance` TextView.
     * @param alert The current alert, or `null` to hide the card.
     */
    fun bind(
        card: LinearLayout?,
        icon: ImageView?,
        label: TextView?,
        distance: TextView?,
        alert: TrafficAlert?
    ) {
        if (card == null) return
        if (alert == null) {
            card.visibility = View.GONE
            return
        }

        card.visibility = View.VISIBLE

        // Pick the icon drawable based on sign type.
        val iconRes = when (alert.sign.signType) {
            SignType.SPEED_LIMIT -> R.drawable.ic_speed_limit
            SignType.CAMERA_SPEED,
            SignType.CAMERA_RED_LIGHT -> R.drawable.ic_traffic_camera
            SignType.RESIDENTIAL_BEGIN,
            SignType.RESIDENTIAL_END -> R.drawable.ic_residential
            else -> R.drawable.ic_warning
        }
        icon?.setImageResource(iconRes)

        // Label: the sign's display name.
        label?.text = alert.label

        // Distance: round to nearest 10 m below 100 m, nearest 50 m above.
        val meters = alert.distanceMeters
        distance?.text = if (meters < 100) {
            "${meters.toInt()} m"
        } else {
            "${(meters / 50).toInt() * 50} m"
        }

        // Colour: red when within 150 m (urgent), normal otherwise.
        val isUrgent = meters < 150
        distance?.setTextColor(
            if (isUrgent) card.context.getColor(R.color.danger)
            else card.context.getColor(R.color.text_secondary)
        )
    }

    /**
     * Formats the distance for TTS voice prompts.
     *
     * Returns a Vietnamese phrase like "250 mét" or "1,2 kilomet", used when
     * the controller fires a first alert.
     */
    fun formatDistanceForTts(meters: Double): String {
        return if (meters < 1000) {
            "${meters.toInt()} mét"
        } else {
            val km = meters / 1000.0
            // Vietnamese convention: comma as decimal separator.
            "${String.format("%.1f", km).replace('.', ',')} kilomet"
        }
    }

    /**
     * Builds the Vietnamese voice prompt for a traffic alert.
     *
     * The prompt is spoken once when the alert first fires
     * (`alert.firstAlert == true`).
     */
    fun voicePrompt(alert: TrafficAlert): String {
        val distText = formatDistanceForTts(alert.distanceMeters)
        val signText = alert.sign.signType.displayLabel(alert.sign.speedLimit)
        return "Chú ý, $signText cách $distText."
    }
}
