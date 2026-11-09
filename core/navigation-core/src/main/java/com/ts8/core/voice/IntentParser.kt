package com.ts8.core.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Turns a Vietnamese transcript into one command, or into an honest "I do not
 * know".
 *
 * **Why the input is folded and not merely lowercased.** The platform
 * recogniser on the TS8 produces whatever the installed language pack thinks it
 * heard: sometimes `Dẫn đường tới Đà Nẵng`, sometimes `Dan duong toi Da Nang`
 * when the driver's firmware has no Vietnamese model. Both must reach
 * [VoiceCommand.NavigateTo] with the *same* destination, so [fold] strips every
 * tone mark and turns `đ` into `d` before any pattern is tried. The destination
 * itself is also folded, because it is a lookup key for the local place index —
 * which folds the same way (see `VietnameseText.normalize`) — not a string to
 * print. Keeping the diacritics would make the two spellings two different
 * places, which is exactly the bug the folding exists to prevent.
 *
 * **Why unrecognised input never becomes a command.** A voice UI that guesses
 * is worse than one that asks: "mở" could mean the map, the music screen or an
 * app, and acting on a guess changes the screen under a driver's hand. So the
 * three outcomes are deliberately asymmetric — a command, an explicit list of
 * candidates, or nothing. There is no "best effort" branch, and no default
 * command to fall back to.
 */
object IntentParser {

    /**
     * What a transcript meant.
     *
     * [Command.matchedPhrase] is the folded phrase that matched, kept for the
     * log: when a driver reports "it did the wrong thing", the phrase is the
     * only way to tell which rule fired without replaying the audio.
     */
    sealed class ParseResult {
        /** One command, confidently identified. */
        data class Command(val command: VoiceCommand, val matchedPhrase: String) : ParseResult()

        /**
         * The phrase is a real prefix of several commands and the rest was not
         * said. The overlay must list [candidates] and ask, not pick one.
         */
        data class Ambiguous(val candidates: List<VoiceCommand>) : ParseResult()

        /**
         * Nothing matched. [text] is the original transcript, diacritics and
         * all, so the prompt can quote what was actually heard rather than a
         * folded version the driver never said.
         */
        data class Unrecognized(val text: String) : ParseResult()
    }

    /** Folders applied to a destination before it is accepted. */
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}%]+")
    private val SPACES = Regex("\\s+")

    /**
     * Normalises text for matching: tone marks removed, `đ`→`d`, lowercased,
     * punctuation and runs of whitespace collapsed to single spaces.
     *
     * This is also what makes the two spellings of a destination equal
     * (`VoiceCommand.NavigateTo.destination` carries the folded form), and it
     * mirrors `VietnameseText.normalize` in `:core:place-search` closely enough
     * that the folded key is directly usable as a search query.
     *
     * `%` survives on purpose — "âm lượng 40%" only parses if the sign is still
     * there after folding, and it cannot collide with a Vietnamese word.
     */
    fun fold(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var s = Normalizer.normalize(input, Normalizer.Form.NFKD)
        s = s.replace('đ', 'd').replace('Đ', 'D')
        s = MARKS.replace(s, "")
        s = s.lowercase(Locale.ROOT)
        s = NON_WORD.replace(s, " ")
        s = SPACES.replace(s, " ")
        return s.trim()
    }

    /**
     * Parses one transcript.
     *
     * Order matters and is fixed: navigation before search ("tìm đường" must
     * not become `tìm` + "đường"), argument commands before the bare command
     * they start with ("mở bản đồ" before "mở"), and the ambiguity check last,
     * so a phrase that could be completed is never reported as ambiguous.
     */
    fun parse(transcript: String?): ParseResult {
        val original = transcript.orEmpty()
        val raw = fold(original)
        if (raw.isEmpty()) return ParseResult.Unrecognized(original)

        val tokens = trimFillers(raw.split(' ').filter { it.isNotEmpty() })
        if (tokens.isEmpty()) return ParseResult.Unrecognized(original)
        val rest = tokens.joinToString(" ")

        // 1. Navigation: "dẫn đường / chỉ đường / tìm đường" + toi|den|di + X.
        for (trigger in NAVIGATE_TRIGGERS) {
            if (!rest.startsWithTokens(trigger)) continue
            val tail = rest.dropTokens(trigger.size)
            if (tail.isEmpty()) {
                // The driver named an action but no place. Asking "đi đâu?" is
                // the overlay's job; inventing a destination here is not an
                // option, and neither is running navigation to nowhere.
                return ParseResult.Unrecognized(original)
            }
            val destination = tail.dropOptionalConnector().trim()
            if (destination.isEmpty()) return ParseResult.Unrecognized(original)
            return ParseResult.Command(VoiceCommand.NavigateTo(destination), trigger.joinToString(" "))
        }

        // 2. Search: "tìm / tìm kiếm" + X. Anything else beginning with "tìm"
        //    was already claimed by the navigation trigger above.
        for (trigger in SEARCH_TRIGGERS) {
            if (!rest.startsWithTokens(trigger)) continue
            val tail = rest.dropTokens(trigger.size)
            if (tail.isEmpty()) return ParseResult.Unrecognized(original)
            return ParseResult.Command(VoiceCommand.SearchPlace(tail), trigger.joinToString(" "))
        }

        // 3. Timer: "đặt hẹn giờ / đặt giờ / hẹn giờ" + <number> + phút|giây|giờ.
        TIMER_SET.matchEntire(rest)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2]
            val seconds = when (unit) {
                "phut" -> amount * 60
                "gio" -> amount * 3600
                else -> amount // "giay" or bare number
            }
            if (seconds in 1..86400) {
                return ParseResult.Command(VoiceCommand.SetTimer(seconds), "dat hen gio")
            }
        }

        // 4. Activation: "kích hoạt" + mã.
        for (trigger in ACTIVATE_TRIGGERS) {
            if (!rest.startsWithTokens(trigger)) continue
            val tail = rest.dropTokens(trigger.size)
            if (tail.isEmpty()) return ParseResult.Unrecognized(original)
            return ParseResult.Command(VoiceCommand.ActivateDevice(tail), trigger.joinToString(" "))
        }

        // 5. Call contact: "gọi" + tên (not "gọi điện" which is the exact phrase).
        for (trigger in CALL_TRIGGERS) {
            if (!rest.startsWithTokens(trigger)) continue
            val tail = rest.dropTokens(trigger.size)
            if (tail.isEmpty()) return ParseResult.Unrecognized(original)
            // "gọi điện" was already handled by the exact phrase table below;
            // this only fires when there is a name after "gọi".
            return ParseResult.Command(VoiceCommand.CallContact(tail), trigger.joinToString(" "))
        }

        // 6. Absolute volume: "âm lượng 40 phần trăm", "đặt âm lượng 40%".
        VOLUME_ABSOLUTE.matchEntire(rest)?.let { match ->
            val percent = match.groupValues[1].toIntOrNull() ?: return@let
            // Clamped rather than rejected: "âm lượng 150 phần trăm" is a
            // driver overstating, not an invalid utterance, and the hardware
            // cannot go past 100 anyway.
            return ParseResult.Command(
                VoiceCommand.VolumeTo(percent.coerceIn(0, 100)),
                "am luong"
            )
        }

        // 7. Exact phrases. Longest first, so "mở nhạc" never resolves through
        //    the bare "mở" entry.
        for (entry in PHRASES) {
            if (rest != entry.phrase) continue
            return ParseResult.Command(entry.command, entry.phrase)
        }

        // 8. App launch, with an argument. After the exact table so
        //    "mở ứng dụng nhạc" stays [VoiceCommand.OpenMusic] — the music screen
        //    — instead of becoming a request to launch an app called "nhạc",
        //    which the app module would then have to fail to find.
        for (trigger in APP_TRIGGERS) {
            if (!rest.startsWithTokens(trigger)) continue
            val tail = rest.dropTokens(trigger.size)
            if (tail.isEmpty()) {
                // "mở ứng dụng" with no name describes the drawer, which has no
                // command. Unrecognized is the honest answer; guessing an app
                // would launch something the driver did not name.
                return ParseResult.Unrecognized(original)
            }
            return ParseResult.Command(VoiceCommand.OpenApp(tail), trigger.joinToString(" "))
        }

        // 9. Ambiguous / empty-handed. "mở" alone is the canonical case; the
        //    candidates are only the commands this module can actually run.
        when (rest) {
            "mo" -> return ParseResult.Ambiguous(listOf(VoiceCommand.OpenMap, VoiceCommand.OpenMusic))
            "am luong" -> return ParseResult.Ambiguous(
                listOf(VoiceCommand.VolumeUp, VoiceCommand.VolumeDown)
            )
        }

        return ParseResult.Unrecognized(original)
    }

    /** Leading politeness that carries no command and must not block a match. */
    private val LEADING_FILLERS = listOf(
        "xin chao", "xin", "lam on", "vui long", "giup toi", "giup", "hay"
    )

    /** Trailing particles Vietnamese speakers add without changing meaning. */
    private val TRAILING_FILLERS = setOf("nhe", "nao", "a", "voi", "giup", "the", "ay", "di")

    private fun trimFillers(tokens: List<String>): List<String> {
        var start = 0
        var end = tokens.size

        var changed = true
        while (changed && start < end) {
            changed = false
            for (filler in LEADING_FILLERS) {
                val f = filler.split(' ')
                if (end - start >= f.size &&
                    tokens.subList(start, start + f.size) == f
                ) {
                    start += f.size
                    changed = true
                    break
                }
            }
        }

        changed = true
        while (changed && start < end) {
            changed = false
            if (tokens[end - 1] in TRAILING_FILLERS && end - 1 > start) {
                end--
                changed = true
            }
        }
        // "hay" is both a leading filler and a legitimate one-word utterance
        // ("hãy"), so never trim the input to nothing.
        return if (start >= end) tokens else tokens.subList(start, end)
    }

    private val NAVIGATE_TRIGGERS = listOf(
        listOf("dan", "duong"),
        listOf("chi", "duong"),
        listOf("tim", "duong")
    )

    private val SEARCH_TRIGGERS = listOf(
        listOf("tim", "kiem"),
        listOf("tim")
    )

    /** Timer triggers: "đặt hẹn giờ", "hẹn giờ", "đặt giờ". */
    private val TIMER_TRIGGERS = listOf(
        listOf("dat", "hen", "gio"),
        listOf("hen", "gio"),
        listOf("dat", "gio")
    )

    /** Activation triggers: "kích hoạt", "nhập mã". */
    private val ACTIVATE_TRIGGERS = listOf(
        listOf("kich", "hoat"),
        listOf("nhap", "ma")
    )

    /** Call contact triggers: "gọi cho", "gọi". "gọi điện" is an exact phrase. */
    private val CALL_TRIGGERS = listOf(
        listOf("goi", "cho")
    )

    /**
     * App-launch triggers, longest first.
     *
     * "mo ung dung" before "mo app" so the longer form is not consumed by the
     * shorter one, and neither is tried before the exact-phrase table has had
     * its chance — "mở nhạc" must stay [VoiceCommand.OpenMusic] rather than
     * becoming a request to open an app called "nhạc".
     */
    private val APP_TRIGGERS = listOf(
        listOf("mo", "ung", "dung"),
        listOf("mo", "app"),
        listOf("chay", "ung", "dung")
    )

    private fun String.startsWithTokens(prefix: List<String>): Boolean {
        val parts = split(' ').filter { it.isNotEmpty() }
        if (parts.size < prefix.size) return false
        for (i in prefix.indices) if (parts[i] != prefix[i]) return false
        return true
    }

    private fun String.dropTokens(n: Int): String =
        split(' ').filter { it.isNotEmpty() }.drop(n).joinToString(" ")

    /** Removes exactly one `toi` / `den` / `di` connector in front of a place. */
    private fun String.dropOptionalConnector(): String {
        val parts = split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return if (parts[0] in CONNECTORS) parts.drop(1).joinToString(" ") else parts.joinToString(" ")
    }

    private val CONNECTORS = setOf("toi", "den", "di", "ve")

    /**
     * Absolute volume: "am luong [la] <so> [phan tram|pt|%]".
     *
     * Group 1 is the number. Both the spoken unit and the bare `%` are accepted
     * because the recogniser returns whichever the driver used.
     */
    private val VOLUME_ABSOLUTE = Regex(
        "^(?:am luong|dat am luong|chinh am luong|doi am luong)(?: la)? (\\d{1,3})(?: phan tram| pt| ?%)?$"
    )

    /**
     * Timer: "dat hen gio|hen gio|dat gio <so> <donvi>".
     *
     * Group 1 is the number, group 2 is the unit (phut/gio/giay).
     * Matches "5 phut", "30 giay", "1 gio".
     */
    private val TIMER_SET = Regex(
        "^(?:dat hen gio|hen gio|dat gio) (\\d{1,4}) (phut|gio|giay)$"
    )

    private class Phrase(val phrase: String, val command: VoiceCommand)

    /**
     * Fixed phrases, longest first.
     *
     * `mở nhạc` and `mở bản đồ` share a prefix with the ambiguous `mở`, which is
     * why the table is matched by whole phrase and never by first token.
     */
    private val PHRASES: List<Phrase> = listOf(
        // Map & display
        Phrase("mo ban do", VoiceCommand.OpenMap),
        Phrase("mo dien thoai", VoiceCommand.OpenPhone),
        Phrase("goi dien", VoiceCommand.OpenPhone),
        Phrase("mo nhac va", VoiceCommand.OpenMusic),
        Phrase("mo nhac", VoiceCommand.OpenMusic),
        Phrase("mo ung dung nhac", VoiceCommand.OpenMusic),
        // Day/night toggle
        Phrase("chuyen ban do dem", VoiceCommand.ToggleDayNight),
        Phrase("chuyen ban do ngay", VoiceCommand.ToggleDayNight),
        Phrase("che do dem", VoiceCommand.ToggleDayNight),
        Phrase("che do ngay", VoiceCommand.ToggleDayNight),
        Phrase("ban do dem", VoiceCommand.ToggleDayNight),
        Phrase("ban do ngay", VoiceCommand.ToggleDayNight),
        // 2D/3D
        Phrase("ban do 2d", VoiceCommand.SetMap2D),
        Phrase("ban do 3d", VoiceCommand.SetMap3D),
        Phrase("chuyen 3d", VoiceCommand.SetMap3D),
        Phrase("chuyen 2d", VoiceCommand.SetMap2D),
        // Auto-follow
        Phrase("bat auto", VoiceCommand.ToggleAutoFollow),
        Phrase("tat auto", VoiceCommand.ToggleAutoFollow),
        Phrase("bat theo xe", VoiceCommand.ToggleAutoFollow),
        Phrase("tat theo xe", VoiceCommand.ToggleAutoFollow),
        // Zoom
        Phrase("phong to", VoiceCommand.ZoomIn),
        Phrase("thu nho", VoiceCommand.ZoomOut),
        Phrase("lam to", VoiceCommand.ZoomIn),
        Phrase("lam nho", VoiceCommand.ZoomOut),
        // Music
        Phrase("phat nhac", VoiceCommand.PlayMusic),
        Phrase("tam dung", VoiceCommand.PauseMusic),
        Phrase("dung nhac", VoiceCommand.PauseMusic),
        Phrase("bai tiep theo", VoiceCommand.NextTrack),
        Phrase("bai tiep", VoiceCommand.NextTrack),
        Phrase("chuyen bai", VoiceCommand.NextTrack),
        Phrase("bai truoc", VoiceCommand.PreviousTrack),
        Phrase("quay lai bai", VoiceCommand.PreviousTrack),
        // Volume
        Phrase("tang am luong", VoiceCommand.VolumeUp),
        Phrase("giam am luong", VoiceCommand.VolumeDown),
        // Timer
        Phrase("huy hen gio", VoiceCommand.CancelTimer),
        Phrase("huy gio", VoiceCommand.CancelTimer),
        Phrase("huy dem nguoc", VoiceCommand.CancelTimer),
        // Traffic queries
        Phrase("gioi han toc do", VoiceCommand.ReportSpeedLimit),
        Phrase("toc do toi da", VoiceCommand.ReportSpeedLimit),
        Phrase("con bao xa", VoiceCommand.ReportEta),
        Phrase("con bao xa nua", VoiceCommand.ReportEta),
        Phrase("bao xa con lai", VoiceCommand.ReportEta),
        Phrase("bao gio den", VoiceCommand.ReportEta),
        Phrase("con bao lau", VoiceCommand.ReportEta),
        // Navigation
        Phrase("huy lo trinh", VoiceCommand.CancelNavigation),
        Phrase("huy dan duong", VoiceCommand.CancelNavigation),
        Phrase("dung dan duong", VoiceCommand.CancelNavigation),
        Phrase("ket thuc hanh trinh", VoiceCommand.CancelNavigation),
        // Vehicle diagnostics
        Phrase("ap lop", VoiceCommand.ReportTyrePressure),
        Phrase("kiem tra lop", VoiceCommand.ReportTyrePressure),
        Phrase("ap suat lop", VoiceCommand.ReportTyrePressure),
        Phrase("toc do hien tai", VoiceCommand.ReportSpeed),
        Phrase("dang di bao nhanh", VoiceCommand.ReportSpeed),
        // Communication
        Phrase("goi lai", VoiceCommand.RedialLast),
        Phrase("goi lai cuoc gan nhat", VoiceCommand.RedialLast),
        // Device & activation
        Phrase("trang thai thiet bi", VoiceCommand.ReportDeviceStatus),
        Phrase("ma thiet bi", VoiceCommand.ReportDeviceStatus),
        Phrase("xem ma", VoiceCommand.ReportDeviceStatus),
        // Home & exit
        Phrase("ve nha", VoiceCommand.GoHome),
        Phrase("trang chu", VoiceCommand.GoHome),
        Phrase("thoat", VoiceCommand.Exit),
        Phrase("thoat ra", VoiceCommand.Exit),
        Phrase("dong lai", VoiceCommand.Exit)
    )
}
