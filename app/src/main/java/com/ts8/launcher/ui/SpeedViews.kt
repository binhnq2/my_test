package com.ts8.launcher.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ts8.core.speed.SpeedState
import com.ts8.core.speed.SpeedUiState
import com.ts8.launcher.R

/**
 * Binds one [SpeedUiState] onto the speed chip and the speed-limit roundel.
 *
 * **Why this is shared by every screen.** The brief requires one `SpeedMonitor`
 * boundary and forbids the UI from reading GPS. Two screens rendering the same
 * state with their own formatting is the same defect at a smaller scale: Home
 * would print `48`, the map `48.2`, and one of them would forget to hide the
 * roundel when the pack has no limit. One function, one appearance.
 *
 * **Why `--` rather than a hidden view.** Â§8 says a missing limit must show `--`
 * and must not be guessed. Hiding the roundel entirely makes "the pack has no
 * limit here" indistinguishable from "the feature is off"; printing a number
 * would be an invention. `--` in the roundel is the only honest third option.
 */
object SpeedViews {

    /**
     * Applies [state] to the views.
     *
     * Every view is optional so a screen that only has a speed chip (the
     * parking overlay, for instance) can call the same function.
     */
    fun bind(
        chip: View?,
        value: TextView?,
        unit: TextView?,
        limitRoundel: View?,
        limitValue: TextView?,
        state: SpeedUiState
    ) {
        // The chip carries both readouts, so it exists only when the driver
        // asked to see either of them.
        val showChip = state.showSpeedSetting || state.showLimitSetting
        chip?.visibility = if (showChip) View.VISIBLE else View.GONE

        value?.apply {
            text = state.displaySpeed?.toString() ?: NO_DATA
            // Red while the alert is live: the visual half of a VISUAL/BOTH
            // alert, which must work with the volume at zero.
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (state.alerting) R.color.danger else R.color.text_primary
                )
            )
            visibility = if (state.showSpeedSetting) View.VISIBLE else View.GONE
        }

        unit?.apply {
            text = state.unitLabel
            visibility = if (state.showSpeedSetting) View.VISIBLE else View.GONE
        }

        limitRoundel?.visibility = if (state.showLimitSetting) View.VISIBLE else View.GONE
        limitValue?.apply {
            // `--` when the pack has no answer for this road. The brief's rule:
            // never show a limit that is not backed by a source.
            text = state.displayLimit?.toString() ?: NO_DATA
            // The roundel carries the warning too, not just the speed number.
            // A driver glancing at the sign is looking at the roundel, and a
            // roundel that stayed its normal colour while the number beside it
            // turned red made the alert depend on which half of the chip the
            // eye happened to land on. Red here means "you are over this".
            val colour = when {
                state.alerting -> R.color.danger
                state.displayLimit == null -> R.color.text_tertiary
                else -> R.color.speed_limit_ring
            }
            setTextColor(ContextCompat.getColor(context, colour))
        }
    }

    /**
     * Accessibility/description of the current state for the chip.
     *
     * Kept short: it is read by a screen reader and also shown in the
     * diagnostics panel, so it names the state and the numbers, not a sentence.
     */
    fun describe(state: SpeedUiState): String {
        val speed = state.displaySpeed?.let { "$it ${state.unitLabel}" } ?: NO_DATA
        val limit = state.displayLimit?.let { "$it ${state.unitLabel}" } ?: NO_DATA
        return "speed=$speed state=${state.state} limit=$limit"
    }

    /** The placeholder the brief fixes for "no data". */
    const val NO_DATA = "--"

    /** True when the state can drive an alert at all. */
    fun canAlert(state: SpeedUiState): Boolean =
        state.state == SpeedState.OVERSPEED_PENDING || state.state == SpeedState.OVERSPEED_ACTIVE
}
