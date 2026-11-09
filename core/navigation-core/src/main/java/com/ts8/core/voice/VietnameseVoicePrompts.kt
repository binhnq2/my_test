package com.ts8.core.voice

/**
 * Every Vietnamese sentence Voice Instant can say or show, in one place.
 *
 * **Why this is not `strings.xml`.** The overlay draws from `strings.xml`; the
 * TTS engine speaks from Kotlin. On this head unit those two paths can carry
 * different text — the overlay is refreshed by an Activity that may be
 * recreated mid-prompt while the TTS queue keeps its old utterance — and the
 * driver then hears "Xác nhận: dẫn đường tới Đà Nẵng?" while reading something
 * else. Keeping the sentences here, and having the app module bind its
 * `strings.xml` entries to these functions (or call them directly for TTS),
 * makes disagreement a compile-time impossibility rather than a QA finding.
 *
 * Full diacritics are mandatory, not cosmetic: the TS8's Vietnamese TTS engine
 * reads unaccented text as a string of foreign syllables, and a driver hearing
 * "dan duong toi da nang" instead of "dẫn đường tới Đà Nẵng" loses the one
 * confirmation the feature exists to give.
 *
 * Nothing here fabricates detail. [notUnderstood] quotes what was actually
 * heard; [forError] says only what the error enum knows.
 */
object VietnameseVoicePrompts {

    // -- overlay labels ------------------------------------------------------

    /** Shown while the recogniser is capturing. */
    const val LISTENING: String = "Đang nghe…"

    /**
     * Spoken when the microphone opens, so the driver knows voice is active
     * even with no TTS engine — the beep carries the non-verbal cue, and this
     * line is the verbal one. Short, because the driver is about to speak and
     * a long prompt would talk over them.
     */
    const val PROMPT_LISTENING: String = "Xin mời nói"

    /** Shown between transcript and outcome. */
    const val PROCESSING: String = "Đang xử lý…"

    /** Overlay buttons, matching `voice_confirm_yes` / `voice_confirm_no`. */
    const val CONFIRM_YES: String = "Đồng ý"

    const val CONFIRM_NO: String = "Huỷ"

    // -- the fixed sentences -------------------------------------------------

    /**
     * The spec's exact wording, kept verbatim: a service that is not installed
     * is a platform limitation the driver must be told about plainly, not
     * softened into "thử lại sau" that implies retrying could work.
     */
    const val SERVICE_UNAVAILABLE: String = "Dịch vụ giọng nói không khả dụng"

    /** Overlay short form, for the 1024x600 bar where the full sentence will not fit. */
    const val SERVICE_UNAVAILABLE_SHORT: String = "Voice"

    /** The driver pressed Back, or answered "no". */
    const val CANCELLED: String = "Đã huỷ"

    /** The interaction was abandoned without an answer. */
    const val TIMEOUT: String = "Hết thời gian chờ"

    /** The cancellation sentence; a function so call sites read as speech. */
    fun cancelled(): String = CANCELLED

    /** The timeout sentence; a function so call sites read as speech. */
    fun timeout(): String = TIMEOUT

    /** A command ran but the executor refused or failed it without detail. */
    const val FAILED: String = "Không thực hiện được lệnh"

    /** Generic failure with no detail; prefer [failedWith]. */
    fun failed(): String = FAILED

    /** A command ran but the executor said why it could not. */
    fun failedWith(reason: String): String =
        if (reason.isBlank()) FAILED else reason

    /** Success line when the executor has no better sentence of its own. */
    fun success(command: VoiceCommand): String = "Đã thực hiện: ${label(command)}"

    /**
     * The question asked before a destructive command runs.
     *
     * Names the action rather than asking "are you sure?": a driver who did not
     * intend the command needs to hear *what* is about to happen to spot the
     * misrecognition, and "bạn chắc chắn?" gives them nothing to judge.
     */
    fun confirm(command: VoiceCommand): String = when (command) {
        is VoiceCommand.NavigateTo ->
            "Xác nhận dẫn đường tới ${command.destination}? Nói đồng ý hoặc huỷ."
        VoiceCommand.PlayMusic ->
            "Phát nhạc sẽ thay danh sách đang phát. Đồng ý?"
        else ->
            "Xác nhận ${label(command)}? Nói đồng ý hoặc huỷ."
    }

    /**
     * The response to a phrase that could mean several commands.
     *
     * Names the candidates instead of asking "bạn muốn gì?": on a 1024x600
     * overlay the driver reads two options in the time it takes to say one, and
     * the candidates are exactly what [VoiceCommand] can run.
     */
    fun ambiguous(candidates: List<VoiceCommand>): String =
        if (candidates.isEmpty()) {
            "Chưa rõ lệnh. Bạn muốn mở bản đồ hay mở nhạc?"
        } else {
            val names = candidates.joinToString(" hay ") { label(it) }
            "Chưa rõ lệnh. Bạn muốn $names?"
        }

    /**
     * Unrecognised speech. Quotes the transcript — folded by the parser, so the
     * quoted text is what the recogniser actually produced — and never guesses
     * at a near match.
     */
    fun notUnderstood(text: String): String {
        val heard = text.trim()
        return if (heard.isEmpty()) {
            "Không nghe rõ. Thử lại hoặc nói ngắn hơn."
        } else {
            "Không hiểu lệnh: “$heard”"
        }
    }

    /**
     * The sentence for a recogniser or router failure.
     *
     * **Why each new state gets its own sentence rather than sharing one.** A
     * driver acts on what they read. "Dịch vụ giọng nói không khả dụng" invites
     * them to give up on voice entirely; "Micro đang được ứng dụng khác dùng"
     * tells them the truth and is fixed by closing that app; "Chưa có dữ liệu
     * tiếng Việt cho bộ nhận dạng này" points them at the model they can
     * install. One message for all of them is one message that is wrong for
     * most of them.
     */
    fun forError(error: VoiceError): String = when (error) {
        VoiceError.SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE
        VoiceError.NO_SPEECH -> "Không nghe thấy giọng nói. Thử lại."
        VoiceError.NO_MATCH -> "Không nghe rõ. Thử lại hoặc nói ngắn hơn."
        VoiceError.TIMEOUT -> TIMEOUT
        VoiceError.NOT_UNDERSTOOD -> "Không hiểu lệnh. Thử nói ngắn hơn."
        VoiceError.COMMAND_FAILED -> FAILED
        VoiceError.PERMISSION_DENIED -> "Cần quyền micro để dùng lệnh giọng nói"
        // Since this market's unit is offline most of the time, the network
        // sentence names the reason rather than the remedy: the remedy is
        // automatic (the offline model answers the same request) and telling
        // the driver to "check the connection" would be advice they cannot act
        // on while driving.
        VoiceError.NETWORK_ERROR -> "Không kết nối được dịch vụ nhận dạng"
        VoiceError.LANGUAGE_UNAVAILABLE ->
            "Bộ nhận dạng này chưa có tiếng Việt. Cài mô hình giọng nói offline trong Cài đặt."
        VoiceError.MIC_BUSY -> "Micro đang được ứng dụng khác dùng"
        VoiceError.CANCELLED -> CANCELLED
        VoiceError.INTERNAL_ERROR -> "Bộ nhận dạng gặp lỗi. Thử lại."
    }

    // -- labels ---------------------------------------------------------------

    /**
     * Short Vietnamese name for a command, used in prompts and logs.
     *
     * The `when` is exhaustive over the sealed class, so adding a command
     * without naming it fails the build here rather than reaching the driver as
     * a raw Kotlin class name.
     */
    fun label(command: VoiceCommand): String = when (command) {
        is VoiceCommand.NavigateTo -> "dẫn đường tới ${command.destination}"
        is VoiceCommand.SearchPlace -> "tìm ${command.query}"
        VoiceCommand.PlayMusic -> "phát nhạc"
        VoiceCommand.PauseMusic -> "dừng nhạc"
        VoiceCommand.NextTrack -> "bài tiếp theo"
        VoiceCommand.PreviousTrack -> "bài trước"
        VoiceCommand.VolumeUp -> "tăng âm lượng"
        VoiceCommand.VolumeDown -> "giảm âm lượng"
        is VoiceCommand.VolumeTo -> "âm lượng ${command.percent} phần trăm"
        VoiceCommand.GoHome -> "về trang chủ"
        VoiceCommand.OpenMap -> "mở bản đồ"
        VoiceCommand.OpenMusic -> "mở nhạc"
        VoiceCommand.OpenPhone -> "mở điện thoại"
        is VoiceCommand.OpenApp -> "mở ứng dụng ${command.query}"
        // Extended commands
        VoiceCommand.ToggleDayNight -> "chuyển chế độ ngày/đêm"
        VoiceCommand.SetMap2D -> "bản đồ 2D"
        VoiceCommand.SetMap3D -> "bản đồ 3D"
        VoiceCommand.ToggleAutoFollow -> "bật/tắt theo xe"
        VoiceCommand.ZoomIn -> "phóng to"
        VoiceCommand.ZoomOut -> "thu nhỏ"
        is VoiceCommand.SetTimer -> "hẹn giờ ${command.seconds} giây"
        VoiceCommand.CancelTimer -> "huỷ hẹn giờ"
        VoiceCommand.ReportSpeedLimit -> "giới hạn tốc độ"
        VoiceCommand.ReportEta -> "còn bao xa"
        VoiceCommand.CancelNavigation -> "huỷ dẫn đường"
        VoiceCommand.ReportTyrePressure -> "áp lốp"
        VoiceCommand.ReportSpeed -> "tốc độ hiện tại"
        is VoiceCommand.CallContact -> "gọi ${command.contactName}"
        VoiceCommand.RedialLast -> "gọi lại"
        is VoiceCommand.ActivateDevice -> "kích hoạt ${command.redeemCode}"
        VoiceCommand.ReportDeviceStatus -> "trạng thái thiết bị"
        VoiceCommand.Exit -> "thoát"
    }
}
