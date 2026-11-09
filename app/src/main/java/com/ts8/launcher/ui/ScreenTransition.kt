package com.ts8.launcher.ui

import android.app.Activity
import android.provider.Settings
import com.ts8.launcher.runtime.AppSettings

/**
 * §17's transitions, in one place.
 *
 * The brief's four rules are short and easy to satisfy one screen at a time and
 * impossible to keep that way by hand:
 *
 * 1. *"Chỉ dùng alpha/translation ngắn, nhẹ trên TS8."* Nothing here rotates,
 *    scales or runs longer than the platform's own fade. A cross-fade is the
 *    cheapest transition that still reads as a change rather than a glitch.
 * 2. *"Không animation liên tục."* There is no animator object and no loop: a
 *    window transition runs once, when a window changes, and then it is over.
 *    Nothing in this class can keep drawing.
 * 3. *"Không block input trong transition."* `overridePendingTransition` finishes
 *    before input resumes because it is the *window manager* animating two
 *    already-drawn surfaces; the Activity is never waiting on it. Nothing here
 *    posts a delayed action that holds a flag the UI checks.
 * 4. *"Tôn trọng reduced-animation/low-performance mode."* Both switches are
 *    read here — the platform's own animator scale (a driver can set it to 0,
 *    and developer options do) and the launcher's `Tiết kiệm` performance mode,
 *    which exists precisely because this GPU struggles with composite work.
 *    Either one being off means an instant cut.
 *
 * **Why the platform setting is `Settings.Global`, not `System`.** The animator
 * duration scale lives in `Global` from API 17 onwards; reading `System` would
 * silently return the default and the driver's choice of "no animations" would
 * be ignored on every screen.
 */
object ScreenTransition {

    /** The concrete transition for this device right now. */
    enum class Kind {
        /** A short cross-fade. */
        FADE,

        /** No animation at all. */
        NONE
    }

    /**
     * What [activity] should use.
     *
     * Reads preferences, so call this where a transition is about to happen
     * (a click, a nav tap) rather than caching it: a driver can change
     * `Tiết kiệm` in Settings and the very next screen change must honour it.
     */
    fun kind(activity: Activity): Kind {
        val saver = runCatching {
            AppSettings(activity).performance == AppSettings.Performance.SAVER
        }.getOrDefault(false)
        if (saver) return Kind.NONE
        return if (animationsEnabled(activity)) Kind.FADE else Kind.NONE
    }

    /**
     * Applies the transition to the activity that is leaving.
     *
     * Must be called after `startActivity` — the pairing is the platform's, not
     * this class's — and is a no-op when the kind is [Kind.NONE], which is what
     * makes it safe to call unconditionally.
     */
    fun apply(activity: Activity) {
        if (kind(activity) == Kind.NONE) return
        runCatching {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    /**
     * Whether the platform is animating windows at all.
     *
     * Zero means the driver (or a very old vendor ROM's power saver) switched
     * animations off, and honouring that is not politeness: an animator scale
     * of 0 makes `overridePendingTransition` show nothing, so a screen written
     * to *wait* for a transition would wait forever. Anything unreadable is
     * treated as enabled, because a fade that is slightly unwanted is a smaller
     * fault than a launcher that silently stops transitioning.
     */
    private fun animationsEnabled(activity: Activity): Boolean {
        val scale = runCatching {
            Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return scale > 0f
    }
}
