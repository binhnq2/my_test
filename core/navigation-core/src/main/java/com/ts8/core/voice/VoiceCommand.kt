package com.ts8.core.voice

/**
 * The closed set of things Voice Instant may do.
 *
 * **Why a sealed class and not an intent string.** The one failure this
 * prevents is the router inventing an action: if the parser can only return a
 * [VoiceCommand] it has a constructor for, an unrecognised phrase has no
 * representable answer except [ParseResult.Unrecognized]. Strings would let a
 * typo in the parser reach the executor as a command that "exists" and then
 * fails at the far end, after the music has already been ducked.
 *
 * **Which commands are destructive.** [destructive] is true for exactly two:
 *
 * - [NAVIGATE_TO] throws away an active route. The driver may be 400 m from a
 *   junction; silently replacing the line is the single worst thing a voice
 *   command can do in a moving car.
 * - [PLAY_MUSIC] replaces the queue. "Phát nhạc" while an album is playing is
 *   not "resume" — the executor cannot tell those apart from the phrase alone,
 *   so it must ask.
 *
 * Everything else changes a value (volume), advances a cursor (next/previous),
 * pauses (reversible), or opens a screen. Replacing a queue or a route cannot
 * be undone from the overlay, which is what separates the two.
 *
 * **Confirmation policy.** [needsConfirmation] is true exactly when
 * [destructive] is true. Genuinely ambiguous input is *not* a command property:
 * it is [ParseResult.Ambiguous], because there is no command object to carry
 * the flag — that is the point of "never guess".
 *
 * [EXIT] deliberately does not ask. It is the escape hatch the driver reaches
 * for when the launcher itself is misbehaving; a confirmation gate on "thoát"
 * would hold them inside the broken screen it exists to leave.
 */
sealed class VoiceCommand {

    /** Stable identifier, for the log and [VoiceStateSnapshot.lastCommandId]. */
    abstract val id: String

    /** True when executing this discards state the driver cannot get back. */
    abstract val destructive: Boolean

    /** True when the driver must answer yes/no before [VoiceRouter] executes. */
    abstract val needsConfirmation: Boolean

    /**
     * Route to [destination], a **folded** place key.
     *
     * Folded, not as spoken: the requirement is that "Dẫn đường tới Đà Nẵng"
     * and "dan duong toi Da Nang" produce the *same* destination, and they only
     * do if both are normalised before they are stored. It is also the key the
     * local place index expects — `VietnameseText.normalize` folds identically
     * — so the executor can hand it straight to `PlaceSearch` without a second
     * normalisation step that could drift.
     *
     * The cost is that TTS reads the key without tone marks. Restoring them
     * would mean guessing which of several Vietnamese readings the driver
     * meant, which is exactly the fabrication this module refuses; the app
     * module may substitute the original transcript for speech if it wants
     * nicer audio, but the key itself stays folded.
     */
    data class NavigateTo(val destination: String) : VoiceCommand() {
        override val id: String = "navigate_to"
        override val destructive: Boolean = true
        override val needsConfirmation: Boolean = true
    }

    /**
     * Look [query] up in the local place index and show the results.
     *
     * Folded for the same reason as [NavigateTo.destination]: it is a search
     * key, and the index is built on folded text.
     */
    data class SearchPlace(val query: String) : VoiceCommand() {
        override val id: String = "search_place"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Start playback, replacing whatever queue is loaded. */
    data object PlayMusic : VoiceCommand() {
        override val id: String = "play_music"
        override val destructive: Boolean = true
        override val needsConfirmation: Boolean = true
    }

    /** Pause. Reversible by [PlayMusic], so no prompt. */
    data object PauseMusic : VoiceCommand() {
        override val id: String = "pause_music"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Advance one track. Reversible with [PreviousTrack]. */
    data object NextTrack : VoiceCommand() {
        override val id: String = "next_track"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Go back one track. Reversible with [NextTrack]. */
    data object PreviousTrack : VoiceCommand() {
        override val id: String = "previous_track"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** One step up. The executor clamps at the maximum. */
    data object VolumeUp : VoiceCommand() {
        override val id: String = "volume_up"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** One step down. The executor clamps at silence. */
    data object VolumeDown : VoiceCommand() {
        override val id: String = "volume_down"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Absolute volume, for "âm lượng 40 phần trăm".
     *
     * Not one of the twelve MVP phrases; added because the parser recognises
     * the percentage form and turning it into two hundred [VolumeDown]s would
     * be worse than one set. [percent] is clamped to 0..100 when the command is
     * built, so the executor never has to decide what 150% means.
     */
    data class VolumeTo(val percent: Int) : VoiceCommand() {
        override val id: String = "volume_to"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false

        init {
            require(percent in 0..100) { "percent must be 0..100, was $percent" }
        }
    }

    /** Leave voice and return to the launcher home screen. */
    data object GoHome : VoiceCommand() {
        override val id: String = "go_home"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Open the map screen without a destination. */
    data object OpenMap : VoiceCommand() {
        override val id: String = "open_map"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Open the music screen without starting playback. */
    data object OpenMusic : VoiceCommand() {
        override val id: String = "open_music"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Open the phone screen or the platform dialer.
     *
     * A separate command from [OpenApp] even though the bottom bar's Phone item
     * is not a screen: the driver's intent is "call someone", and the app module
     * resolves it to the dialer, to whatever firmware app handles `ACTION_DIAL`,
     * or to the app drawer — three different outcomes carrying one meaning.
     */
    data object OpenPhone : VoiceCommand() {
        override val id: String = "open_phone"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Launch an installed app by the name the driver said.
     *
     * [query] is folded, like every other lookup key here, because the app
     * catalogue sorts on folded labels and the recogniser returns either
     * spelling. The name is not resolved in this module: `PackageManager` lives
     * in the app module, and a voice layer that could invent an app would be
     * able to launch something the driver never looked at.
     *
     * Not destructive: opening an app changes the screen, which is what the
     * driver asked for, and Back returns. Confirming here would put a question
     * in front of the most common command a driver says.
     */
    data class OpenApp(val query: String) : VoiceCommand() {
        override val id: String = "open_app"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    // -- Settings & system commands (extended set) -------------------------

    /**
     * Toggle between day and night map theme.
     *
     * Not destructive: the previous theme is one tap or one phrase away.
     */
    data object ToggleDayNight : VoiceCommand() {
        override val id: String = "toggle_day_night"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Switch map to 2D flat mode. */
    data object SetMap2D : VoiceCommand() {
        override val id: String = "set_map_2d"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Switch map to 3D perspective mode. */
    data object SetMap3D : VoiceCommand() {
        override val id: String = "set_map_3d"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Toggle map auto-follow / auto-centre on the vehicle.
     *
     * The executor reads the current setting and flips it, so "tắt auto" and
     * "bật auto" both route through here.
     */
    data object ToggleAutoFollow : VoiceCommand() {
        override val id: String = "toggle_auto_follow"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Zoom in one step on the map. */
    data object ZoomIn : VoiceCommand() {
        override val id: String = "zoom_in"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Zoom out one step on the map. */
    data object ZoomOut : VoiceCommand() {
        override val id: String = "zoom_out"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    // -- Timer & reminders -------------------------------------------------

    /**
     * Start a countdown timer for [seconds].
     *
     * Common use: "đặt hẹn giờ 5 phút" for a parking meter or a quick errand.
     * The executor clamps to a sane range (1 second to 24 hours).
     */
    data class SetTimer(val seconds: Int) : VoiceCommand() {
        override val id: String = "set_timer"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false

        init {
            require(seconds in 1..86400) { "seconds must be 1..86400, was $seconds" }
        }
    }

    /** Cancel any running timer. */
    data object CancelTimer : VoiceCommand() {
        override val id: String = "cancel_timer"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    // -- Traffic & navigation queries -------------------------------------

    /**
     * Report the current speed limit on the road the car is on.
     *
     * The executor queries `SpeedLimitResolver` and speaks the value or "không
     * rõ" when no data exists. Not a command that changes anything — it is a
     * query the driver asks without taking their eyes off the road.
     */
    data object ReportSpeedLimit : VoiceCommand() {
        override val id: String = "report_speed_limit"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Report the remaining distance and ETA to the current destination.
     *
     * Only meaningful when a route is active; the executor says "chưa có lộ
     * trình" otherwise.
     */
    data object ReportEta : VoiceCommand() {
        override val id: String = "report_eta"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Cancel the current navigation route and return to free-map mode. */
    data object CancelNavigation : VoiceCommand() {
        override val id: String = "cancel_navigation"
        override val destructive: Boolean = true
        override val needsConfirmation: Boolean = true
    }

    // -- Vehicle diagnostics ----------------------------------------------

    /**
     * Read out TPMS tyre pressures if the head unit has a TPMS receiver.
     *
     * The executor queries the TPMS service; if no sensor data is available,
     * the spoken answer is "không có dữ liệu áp lốp".
     */
    data object ReportTyrePressure : VoiceCommand() {
        override val id: String = "report_tyre_pressure"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Report the current vehicle speed as read by GPS.
     *
     * Distinct from [ReportSpeedLimit] — one is the limit, the other is the
     * actual speed.
     */
    data object ReportSpeed : VoiceCommand() {
        override val id: String = "report_speed"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    // -- Communication -----------------------------------------------------

    /**
     * Call [contactName] by looking it up in the phone's contacts.
     *
     * On a head unit with a paired phone, this resolves the contact name and
     * calls via Bluetooth. The executor handles the "not found" case.
     */
    data class CallContact(val contactName: String) : VoiceCommand() {
        override val id: String = "call_contact"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Redial the last called number.
     *
     * Not destructive but potentially embarrassing — confirmation is left to
     * the executor's discretion.
     */
    data object RedialLast : VoiceCommand() {
        override val id: String = "redial_last"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    // -- Activation --------------------------------------------------------

    /**
     * Activate the device with [redeemCode].
     *
     * "Kích hoạt mã XIAOZHI-VIP-30D" — the driver reads a code off a card and
     * speaks it. The executor passes it to `DeviceIdentity.activate()`.
     */
    data class ActivateDevice(val redeemCode: String) : VoiceCommand() {
        override val id: String = "activate_device"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Read out the device code and current tier. */
    data object ReportDeviceStatus : VoiceCommand() {
        override val id: String = "report_device_status"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Close Voice Instant / leave the launcher. Never asks; see the class doc. */
    data object Exit : VoiceCommand() {
        override val id: String = "exit"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    companion object {
        /**
         * Every command instance the router can be handed, for diagnostics and
         * for [VoiceScenarios] to prove the table is exhaustive. Argument
         * commands appear with their empty/zero placeholder so the list is a
         * type inventory, not a suggestion of valid values.
         */
        fun allTypes(): List<VoiceCommand> = listOf(
            NavigateTo(""),
            SearchPlace(""),
            PlayMusic,
            PauseMusic,
            NextTrack,
            PreviousTrack,
            VolumeUp,
            VolumeDown,
            VolumeTo(0),
            GoHome,
            OpenMap,
            OpenMusic,
            OpenPhone,
            OpenApp(""),
            ToggleDayNight,
            SetMap2D,
            SetMap3D,
            ToggleAutoFollow,
            ZoomIn,
            ZoomOut,
            SetTimer(0),
            CancelTimer,
            ReportSpeedLimit,
            ReportEta,
            CancelNavigation,
            ReportTyrePressure,
            ReportSpeed,
            CallContact(""),
            RedialLast,
            ActivateDevice(""),
            ReportDeviceStatus,
            Exit
        )
    }
}
