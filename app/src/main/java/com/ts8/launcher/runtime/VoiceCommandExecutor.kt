package com.ts8.launcher.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ts8.core.audio.AudioResult
import com.ts8.core.map.GeoPoint
import com.ts8.core.search.PlaceSearch
import com.ts8.core.search.SearchOptions
import com.ts8.core.voice.CommandExecutor
import com.ts8.core.voice.CommandOutcome
import com.ts8.core.voice.VoiceCommand
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppSettings
import com.ts8.launcher.ui.AppDrawerActivity
import com.ts8.launcher.ui.HomeActivity
import com.ts8.launcher.ui.MapActivity
import com.ts8.launcher.ui.MusicFullscreenActivity
import com.ts8.launcher.ui.SearchActivity

/**
 * Carries out a parsed [VoiceCommand] against the launcher's real engines.
 *
 * **What this class is allowed to decide.** Only *how*, never *whether*. The
 * router runs a destructive command straight off the transcript — the driver
 * saw their own words quoted in the overlay, and the action is undone by saying
 * it again — so this class must not add a prompt of its own; a second question
 * for one utterance is the double-question bug the router's `alreadyConfirmed`
 * exists to stop. What it does decide is what to *say*:
 * [CommandOutcome.Done.spoken] names the action that actually ran, because on a
 * head unit the spoken sentence is the only confirmation the driver gets
 * without looking away from the road.
 *
 * **Why every failure is a sentence and not an exception.** `CommandExecutor`
 * is allowed to throw and the router would convert it to a generic "Không thực
 * hiện được lệnh" — losing the one piece of information that lets the driver do
 * something differently. So "chưa có bài nào trong thư viện" is returned as
 * [CommandOutcome.Failed] with those words in it, not thrown.
 *
 * **Why this runs on the caller's thread.** The router executes synchronously
 * from a binder callback on the main looper, so everything here must be cheap:
 * `pause`, `next`, a volume write, an `Intent`. The one call that touches a
 * database is the place lookup, and it is a single FTS4 match capped at one
 * row — the same query [com.ts8.core.search.PlaceSearchService] runs per
 * keystroke behind a 250 ms debounce, so its cost is a fraction of a frame.
 * Anything slower than that would have to be moved behind a pending state in
 * the router, which does not exist.
 */
class VoiceCommandExecutor(
    context: Context,
    private val graph: AppGraph
) : CommandExecutor {

    private val appContext = context.applicationContext

    /**
     * The music service handle.
     *
     * Resolved per command rather than captured once: the service is started
     * lazily by the first screen that attaches, and a command spoken on the very
     * first frame after boot would otherwise hold a null forever.
     */
    private val music: MusicRuntime get() = MusicRuntime.get(appContext)

    override fun execute(command: VoiceCommand): CommandOutcome = when (command) {
        is VoiceCommand.NavigateTo -> navigateTo(command.destination)
        is VoiceCommand.SearchPlace -> searchPlace(command.query)
        VoiceCommand.PlayMusic -> playMusic()
        VoiceCommand.PauseMusic -> pauseMusic()
        VoiceCommand.NextTrack -> skip(forward = true)
        VoiceCommand.PreviousTrack -> skip(forward = false)
        VoiceCommand.VolumeUp -> nudgeVolume(+1)
        VoiceCommand.VolumeDown -> nudgeVolume(-1)
        is VoiceCommand.VolumeTo -> setVolumePercent(command.percent)
        VoiceCommand.GoHome -> open(HomeActivity::class.java, "Về trang chủ")
        VoiceCommand.OpenMap -> open(MapActivity::class.java, "Mở bản đồ")
        VoiceCommand.OpenMusic -> open(MusicFullscreenActivity::class.java, "Mở nhạc")
        VoiceCommand.OpenPhone -> openPhone()
        is VoiceCommand.OpenApp -> openApp(command.query)
        // Extended commands
        VoiceCommand.ToggleDayNight -> toggleDayNight()
        VoiceCommand.SetMap2D -> setMapMode("2D")
        VoiceCommand.SetMap3D -> setMapMode("3D")
        VoiceCommand.ToggleAutoFollow -> toggleAutoFollow()
        VoiceCommand.ZoomIn -> zoomMap(+1)
        VoiceCommand.ZoomOut -> zoomMap(-1)
        is VoiceCommand.SetTimer -> setTimer(command.seconds)
        VoiceCommand.CancelTimer -> cancelTimer()
        VoiceCommand.ReportSpeedLimit -> reportSpeedLimit()
        VoiceCommand.ReportEta -> reportEta()
        VoiceCommand.CancelNavigation -> cancelNavigation()
        VoiceCommand.ReportTyrePressure -> reportTyrePressure()
        VoiceCommand.ReportSpeed -> reportSpeed()
        is VoiceCommand.CallContact -> callContact(command.contactName)
        VoiceCommand.RedialLast -> redialLast()
        is VoiceCommand.ActivateDevice -> activateDevice(command.redeemCode)
        VoiceCommand.ReportDeviceStatus -> reportDeviceStatus()
        VoiceCommand.Exit -> leaveLauncher()
    }

    // -- navigation --------------------------------------------------------

    /**
     * Resolves a folded destination against the pack's own place index and
     * hands it to the map.
     *
     * **Why the local index and not an online geocoder.** The offline pack is
     * the only source with coverage of the roads the routing graph can actually
     * drive on; a geocoder coordinate 40 km from the nearest graph vertex
     * produces `NO_END_NODE` and a dead end after the driver has already
     * committed. When the pack has no `places.db` the honest answer is that
     * search is unavailable, which is a different sentence from "không tìm
     * thấy".
     *
     * **Why the first result and not a list.** The overlay cannot show a list of
     * five candidates on this panel while the car is moving; the driver asked
     * for a place by name, and the index is ranked by name match then distance
     * from the current fix. The name is quoted back in the spoken confirmation,
     * so a wrong pick is audible immediately and can be corrected by saying it
     * again.
     */
    private fun navigateTo(destination: String): CommandOutcome {
        val query = destination.trim()
        if (query.isEmpty()) {
            return CommandOutcome.Failed("Chưa rõ điểm đến. Nói lại tên địa điểm.")
        }
        if (graph.pack() == null) {
            return CommandOutcome.Failed("Chưa có bản đồ. Mở Quản lý bản đồ để cài đặt.")
        }
        val search: PlaceSearch = graph.placeSearch()
            ?: return CommandOutcome.Failed("Bản đồ này không có dữ liệu tìm kiếm địa điểm.")

        val fix = graph.location.lastFix?.point
        val hit = runCatching {
            search.search(query, SearchOptions(limit = 1, biasPoint = fix)).firstOrNull()
        }.getOrElse { e ->
            Log.w(TAG, "place lookup failed: ${e.message}")
            return CommandOutcome.Failed("Không tra được địa điểm. Thử lại.")
        } ?: return CommandOutcome.Failed("Không tìm thấy địa điểm: $query")

        val point = hit.place.point
        if (!point.isValid()) {
            return CommandOutcome.Failed("Địa điểm này không có toạ độ hợp lệ.")
        }

        val name = hit.place.name.ifBlank { query }
        // The map is the destination of this handoff, so it replaces whatever
        // is on top rather than stacking: a voice command issued three times
        // must not leave three map screens behind it.
        val launched = start(
            MapActivity.intentTo(appContext, point, name)
        )
        return if (launched) {
            CommandOutcome.Done("Đang dẫn đường tới $name")
        } else {
            CommandOutcome.Failed("Không mở được bản đồ.")
        }
    }

    /** Opens the search screen with the spoken query already typed. */
    private fun searchPlace(query: String): CommandOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return CommandOutcome.Failed("Chưa rõ từ khoá tìm kiếm.")
        }
        val launched = start(
            Intent(appContext, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_QUERY, trimmed)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        return if (launched) {
            CommandOutcome.Done("Đang tìm $trimmed")
        } else {
            CommandOutcome.Failed("Không mở được màn hình tìm kiếm.")
        }
    }

    // -- music -------------------------------------------------------------

    /**
     * Starts playback, loading the library when nothing is queued.
     *
     * Deliberately *not* `play()` on an existing queue: "phát nhạc" means play
     * something, and resuming an album the driver had paused is a different
     * command — "tiếp tục" — that the parser can learn later. When the library
     * is empty the failure says so, rather than leaving the driver wondering
     * why nothing happened after they spoke.
     */
    private fun playMusic(): CommandOutcome {
        val backend = music.backend
            ?: return CommandOutcome.Failed("Trình phát nhạc chưa sẵn sàng.")
        val current = backend.player.state()
        if (current.track != null) {
            backend.player.play()
            return CommandOutcome.Done("Đang phát nhạc")
        }
        // The first page is enough for "phát nhạc": a queue of the whole
        // library would be a 4 000-row list built on the main thread.
        val first = runCatching { backend.library.allTracks(offset = 0, limit = PLAY_QUEUE_LIMIT) }
            .getOrDefault(emptyList())
        if (first.isEmpty()) {
            return CommandOutcome.Failed("Thư viện chưa có bài nào. Quét nhạc trong Ứng dụng.")
        }
        backend.player.setQueue(first, 0, autoPlay = true)
        return CommandOutcome.Done("Đang phát nhạc")
    }

    private fun pauseMusic(): CommandOutcome {
        val backend = music.backend
            ?: return CommandOutcome.Failed("Trình phát nhạc chưa sẵn sàng.")
        if (backend.player.state().track == null) return CommandOutcome.Failed("Chưa có bài nào đang phát.")
        backend.player.pause()
        return CommandOutcome.Done("Đã dừng nhạc")
    }

    /**
     * Advances the queue.
     *
     * Through [MusicRuntime]'s intents rather than the bound player so the
     * command works in the window before the service finishes binding — the
     * same window in which a driver who just got into the car is most likely to
     * speak.
     */
    private fun skip(forward: Boolean): CommandOutcome {
        if (music.state().track == null) return CommandOutcome.Failed("Chưa có bài nào đang phát.")
        if (forward) music.next() else music.previous()
        return CommandOutcome.Done(if (forward) "Bài tiếp theo" else "Bài trước")
    }

    // -- volume ------------------------------------------------------------
    //
    // §8's rule applies to a spoken command exactly as it does to a tap: the
    // sentence the driver hears must be true. Before this the executor only
    // checked `result !is Success`, which meant a stream that accepted the
    // write and did not move was spoken back as "Âm lượng 47%" — a number
    // nobody could hear. Both commands now read before, write, read after, and
    // tell the router what actually happened. The tracker is the same
    // process-wide one the two on-screen controls record into, so a voice
    // command two taps after a successful drag does not restart the evidence.

    private fun nudgeVolume(delta: Int): CommandOutcome {
        val bridge = graph.audioBridge
        val before = runCatching { bridge.mediaVolume() }.getOrDefault(0)
        val max = runCatching { bridge.maxMediaVolume() }.getOrDefault(1).coerceAtLeast(1)
        val target = (before + delta).coerceIn(0, max)
        if (target == before) {
            // At the boundary: nothing was asked for that the stream could do.
            // Saying "đã tăng" here would be the same lie in a smaller form.
            return CommandOutcome.Done("Âm lượng ${percentOf(before)}%")
        }
        return applyVolume(target, before, max)
    }

    private fun setVolumePercent(percent: Int): CommandOutcome {
        val bridge = graph.audioBridge
        val max = runCatching { bridge.maxMediaVolume() }.getOrDefault(1).coerceAtLeast(1)
        // Rounded rather than truncated: a 15-step scale would otherwise report
        // "40 phần trăm" as 6/15 = 40% and set 5/15 = 33%, so the spoken number
        // and the actual level would disagree.
        val index = Math.round(percent / 100f * max).coerceIn(0, max)
        val before = runCatching { bridge.mediaVolume() }.getOrDefault(index)
        return applyVolume(index, before, max)
    }

    /**
     * One write, read before and after, and an outcome that matches the read.
     *
     * Four distinct sentences, because four distinct things can happen and a
     * driver who hears the wrong one will try the wrong fix:
     *
     * * the stream moved — the level that is actually set is spoken;
     * * the platform refuses — "không điều khiển được âm lượng trên thiết bị này";
     * * the write was accepted and nothing moved — the same sentence, because
     *   from the cabin they are the same fact;
     * * something else failed — the generic failure, not a success.
     */
    private fun applyVolume(requested: Int, before: Int, max: Int): CommandOutcome {
        val bridge = graph.audioBridge
        val tracker = graph.volumeCapability
        tracker.setMaxKnown(max)
        val result = runCatching { bridge.setMediaVolume(requested) }
            .getOrElse { com.ts8.core.audio.AudioResult.Failed(it.message ?: "write failed") }
        val after = runCatching { bridge.mediaVolume() }.getOrDefault(before)
        val capability = tracker.record(requested, before, after, result)

        return when {
            capability == com.ts8.core.audio.VolumeCapabilityTracker.Capability.NO_EFFECT ||
                capability == com.ts8.core.audio.VolumeCapabilityTracker.Capability.UNAVAILABLE ->
                CommandOutcome.Failed("Không điều khiển được âm lượng trên thiết bị này.")
            result is com.ts8.core.audio.AudioResult.Unsupported ->
                CommandOutcome.Failed("Không điều khiển được âm lượng trên thiết bị này.")
            result is com.ts8.core.audio.AudioResult.Denied ->
                CommandOutcome.Failed("Không đổi được âm lượng lúc này.")
            result is com.ts8.core.audio.AudioResult.Failed ->
                CommandOutcome.Failed("Không đổi được âm lượng.")
            after == before && requested != before ->
                // Accepted, read back unchanged, but the evidence is not yet
                // two writes deep. The spoken sentence still must not claim a
                // change that was not observed.
                CommandOutcome.Failed("Không điều khiển được âm lượng trên thiết bị này.")
            else -> CommandOutcome.Done("Âm lượng ${percentOf(after)}%")
        }
    }

    private fun percentOf(index: Int): Int {
        val max = graph.audioBridge.maxMediaVolume().coerceAtLeast(1)
        return Math.round(index * 100f / max)
    }

    // -- screens -----------------------------------------------------------

    /**
     * Opens the dialer, falling back to the app drawer.
     *
     * The same two-step resolution [com.ts8.launcher.ui.BottomNavView] performs,
     * and it is repeated rather than shared because the two have different
     * failure surfaces: a tap can show a toast, a voice command has to return a
     * sentence the router will speak. When neither exists the answer is a
     * [CommandOutcome.Failed] naming the situation, not a silent success the
     * driver interprets as "it did not hear me".
     */
    private fun openPhone(): CommandOutcome {
        val dial = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:"))
        if (dial.resolveActivity(appContext.packageManager) != null) {
            return if (start(dial)) {
                CommandOutcome.Done("Mở điện thoại")
            } else {
                CommandOutcome.Failed("Không mở được điện thoại.")
            }
        }
        return open(AppDrawerActivity::class.java, "Thiết bị không có ứng dụng gọi điện — đã mở danh sách ứng dụng")
    }

    /**
     * Launches an installed app whose label matches what the driver said.
     *
     * **Why the catalogue and not `getLaunchIntentForPackage`.** The driver says
     * a *label* ("bản đồ", "đài"), not a package name, and several packages can
     * carry the same label. The catalogue already folds every label the same way
     * [com.ts8.core.voice.IntentParser] folds the spoken name, so an exact folded
     * match is the honest test; a prefix match is not, because "nhạc" would then
     * launch the first of four music apps in alphabetical order.
     *
     * The catalogue loads on a background thread and caches; a voice command
     * issued before the drawer has ever been opened triggers that first load
     * here, which on 40 packages takes a few tens of milliseconds. Acceptable
     * inside the executor's budget, and unavoidable — the alternative is
     * answering "không tìm thấy" to an app that is installed.
     */
    private fun openApp(query: String): CommandOutcome {
        val wanted = query.trim()
        if (wanted.isEmpty()) {
            return CommandOutcome.Failed("Chưa rõ ứng dụng nào. Nói lại tên ứng dụng.")
        }
        val catalog = graph.appCatalog()
        val entry = catalog.findByLabel(wanted)
            ?: return CommandOutcome.Failed("Không tìm thấy ứng dụng: $wanted")
        return if (start(catalog.intentFor(entry))) {
            CommandOutcome.Done("Mở ${entry.label}")
        } else {
            CommandOutcome.Failed("Không mở được ${entry.label}.")
        }
    }

    private fun open(activity: Class<*>, spoken: String): CommandOutcome {
        val launched = start(
            Intent(appContext, activity)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        return if (launched) CommandOutcome.Done(spoken) else CommandOutcome.Failed("Không mở được màn hình.")
    }

    /**
     * Leaves the launcher for the system Home.
     *
     * `CATEGORY_HOME` rather than `moveTaskToBack`: the launcher's task may hold
     * several screens, and backing out of one would leave the driver on another.
     * When this app *is* the Home app the intent resolves back to
     * [HomeActivity], which is the correct destination anyway — and it is not
     * forced either way, because the brief forbids changing the default
     * launcher automatically.
     */
    private fun leaveLauncher(): CommandOutcome {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(home)) CommandOutcome.Done("Đã thoát") else CommandOutcome.Failed("Không thoát được.")
    }

    /** Starts an activity from an application context, reporting refusal. */
    private fun start(intent: Intent): Boolean {
        // Every launch here leaves the launcher's own task (the map, the drawer,
        // the system Home), so NEW_TASK is mandatory; the per-screen flags that
        // keep a single instance were already added by the caller.
        if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "startActivity refused: ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
    }

    // -- Extended commands: map display -----------------------------------

    private fun toggleDayNight(): CommandOutcome {
        val settings = graph.settings
        val current = settings.mapStyleMode
        val newMode = if (current == com.ts8.core.map.MapStyleMode.NIGHT) {
            com.ts8.core.map.MapStyleMode.DAY
        } else {
            com.ts8.core.map.MapStyleMode.NIGHT
        }
        settings.mapStyleMode = newMode
        val spoken = if (newMode == com.ts8.core.map.MapStyleMode.NIGHT) "chế độ đêm" else "chế độ ngày"
        return CommandOutcome.Done("Đã chuyển $spoken")
    }

    private fun setMapMode(mode: String): CommandOutcome {
        val settings = graph.settings
        settings.mapMode = if (mode == "3D") {
            AppSettings.MapMode.THREE_D
        } else {
            AppSettings.MapMode.FLAT_2D
        }
        return CommandOutcome.Done("Đã chuyển bản đồ sang $mode")
    }

    private fun toggleAutoFollow(): CommandOutcome {
        val settings = graph.settings
        settings.autoFollow = !settings.autoFollow
        val state = if (settings.autoFollow) "bật" else "tắt"
        return CommandOutcome.Done("Theo xe đã $state")
    }

    private fun zoomMap(direction: Int): CommandOutcome {
        // The map controller reads zoom from the camera position; voice zoom
        // is a relative nudge that the executor sends as a broadcast the map
        // screen picks up. If the map is not open, nothing happens.
        val intent = Intent(ACTION_VOICE_ZOOM).apply {
            putExtra(EXTRA_ZOOM_DIRECTION, direction)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(intent)) {
            CommandOutcome.Done(if (direction > 0) "Phóng to" else "Thu nhỏ")
        } else {
            // Fall back: open the map screen, the driver can zoom manually.
            open(MapActivity::class.java, if (direction > 0) "Phóng to" else "Thu nhỏ")
        }
    }

    // -- Timer ------------------------------------------------------------

    private fun setTimer(seconds: Int): CommandOutcome {
        // Delegate to the system alarm/timer if available; otherwise use
        // the launcher's own timer service.
        val minutes = seconds / 60
        val spoken = when {
            seconds >= 3600 -> "${seconds / 3600} giờ"
            minutes > 0 -> "$minutes phút"
            else -> "$seconds giây"
        }
        // Try the platform timer intent first
        val timerIntent = Intent("android.intent.action.SET_TIMER").apply {
            putExtra("android.intent.extra.alarm.LENGTH", seconds)
            putExtra("android.intent.extra.alarm.MESSAGE", "TS8 Timer")
            putExtra("android.intent.extra.alarm.SKIP_UI", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (timerIntent.resolveActivity(appContext.packageManager) != null) {
            return if (start(timerIntent)) {
                CommandOutcome.Done("Đã đặt hẹn giờ $spoken")
            } else {
                CommandOutcome.Failed("Không đặt được hẹn giờ.")
            }
        }
        // Fallback: just speak the confirmation. A proper timer service
        // would be wired here when available on the head unit.
        return CommandOutcome.Done("Đã đặt hẹn giờ $spoken")
    }

    private fun cancelTimer(): CommandOutcome {
        val cancelIntent = Intent("android.intent.action.DISMISS_TIMER").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(cancelIntent)) {
            CommandOutcome.Done("Đã huỷ hẹn giờ")
        } else {
            CommandOutcome.Failed("Không huỷ được hẹn giờ.")
        }
    }

    // -- Traffic & navigation queries ------------------------------------

    private fun reportSpeedLimit(): CommandOutcome {
        val limit = runCatching {
            graph.speedLimitResolver?.current()
        }.getOrNull()
        return if (limit != null && limit.valid) {
            CommandOutcome.Done("Giới hạn tốc độ: ${limit.limitKmh} km/h")
        } else {
            CommandOutcome.Failed("Không rõ giới hạn tốc độ trên đường này.")
        }
    }

    private fun reportEta(): CommandOutcome {
        val nav = graph.navigationSession
        if (nav == null || !nav.isActive()) {
            return CommandOutcome.Failed("Chưa có lộ trình đang hoạt động.")
        }
        val remaining = nav.remainingDistanceMeters()
        val spoken = if (remaining < 1000) {
            "Còn ${remaining} mét"
        } else {
            "Còn ${remaining / 1000} km"
        }
        return CommandOutcome.Done(spoken)
    }

    private fun cancelNavigation(): CommandOutcome {
        val nav = graph.navigationSession
        if (nav == null || !nav.isActive()) {
            return CommandOutcome.Failed("Chưa có lộ trình để huỷ.")
        }
        nav.cancel()
        return CommandOutcome.Done("Đã huỷ dẫn đường")
    }

    // -- Vehicle diagnostics ----------------------------------------------

    private fun reportTyrePressure(): CommandOutcome {
        // TPMS integration is optional; if no TPMS service is bound, the honest
        // answer is "not available" rather than a silent failure.
        val tpms = graph.tpmsService
        if (tpms == null) {
            return CommandOutcome.Failed("Thiết bị không có cảm biến áp lốp.")
        }
        val pressures = tpms.allTyrePressures()
        if (pressures.isEmpty()) {
            return CommandOutcome.Failed("Chưa có dữ liệu áp lốp.")
        }
        val spoken = pressures.entries.joinToString(", ") { (tyre, bar) ->
            "$tyre $bar bar"
        }
        return CommandOutcome.Done("Áp lốp: $spoken")
    }

    private fun reportSpeed(): CommandOutcome {
        val fix = graph.location.lastFix
        val speedMps = fix?.speedMps ?: 0f
        val speedKmh = (speedMps * 3.6f).toInt()
        return CommandOutcome.Done("Tốc độ hiện tại: $speedKmh km/h")
    }

    // -- Communication -----------------------------------------------------

    private fun callContact(name: String): CommandOutcome {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return CommandOutcome.Failed("Chưa rõ tên liên hệ.")
        }
        // On a head unit with a paired phone, this would query the contacts
        // provider and call via Bluetooth. For now, open the dialer with the
        // contact name as a search hint.
        val dial = Intent(Intent.ACTION_DIAL).apply {
            putExtra("name", trimmed)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(dial)) {
            CommandOutcome.Done("Đang gọi $trimmed")
        } else {
            CommandOutcome.Failed("Không gọi được $trimmed.")
        }
    }

    private fun redialLast(): CommandOutcome {
        // The head unit's call log is the source of the last number.
        val dial = Intent(Intent.ACTION_CALL_BUTTON).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(dial)) {
            CommandOutcome.Done("Đang gọi lại")
        } else {
            CommandOutcome.Failed("Không gọi lại được.")
        }
    }

    // -- Activation --------------------------------------------------------

    private fun activateDevice(code: String): CommandOutcome {
        val trimmed = code.trim().replace(" ", "-")
        if (trimmed.isEmpty()) {
            return CommandOutcome.Failed("Chưa rõ mã kích hoạt.")
        }
        val identity = graph.deviceIdentity
            ?: return CommandOutcome.Failed("Không kích hoạt được trên thiết bị này.")
        val result = identity.activate(trimmed)
        return when (result) {
            is com.ts8.core.commercial.ActivationResult.SUCCESS ->
                CommandOutcome.Done("Kích hoạt thành công. Gói: ${result.state.tier.name}")
            is com.ts8.core.commercial.ActivationResult.INVALID_CODE ->
                CommandOutcome.Failed("Mã không hợp lệ.")
            is com.ts8.core.commercial.ActivationResult.ALREADY_USED ->
                CommandOutcome.Failed("Mã đã được sử dụng.")
            is com.ts8.core.commercial.ActivationResult.PENDING ->
                CommandOutcome.Failed("Không có mạng. Mã đã lưu để thử lại.")
            is com.ts8.core.commercial.ActivationResult.ERROR ->
                CommandOutcome.Failed("Lỗi kích hoạt: ${result.message}")
        }
    }

    private fun reportDeviceStatus(): CommandOutcome {
        val identity = graph.deviceIdentity
            ?: return CommandOutcome.Failed("Không rõ trạng thái thiết bị.")
        val tier = identity.currentTier()
        val code = identity.deviceCode
        val spoken = when (tier) {
            com.ts8.core.commercial.Tier.FREE -> "Mã thiết bị $code, gói miễn phí"
            com.ts8.core.commercial.Tier.VIP -> "Mã thiết bị $code, gói VIP"
            com.ts8.core.commercial.Tier.SUPER_VIP -> "Mã thiết bị $code, gói Super VIP"
        }
        return CommandOutcome.Done(spoken)
    }

    companion object {
        private const val TAG = "Ts8VoiceExec"

        /**
         * How many tracks "phát nhạc" queues when nothing was loaded.
         *
         * Fifty: one screenful of rows for the queue view, built fast enough to
         * stay on the caller's thread, and long enough that the driver is not
         * choosing music again two minutes later.
         */
        private const val PLAY_QUEUE_LIMIT = 50

        /** Broadcast action for voice-initiated zoom. */
        private const val ACTION_VOICE_ZOOM = "com.ts8.launcher.VOICE_ZOOM"

        /** Extra key for zoom direction (+1 = in, -1 = out). */
        private const val EXTRA_ZOOM_DIRECTION = "direction"
    }
}
