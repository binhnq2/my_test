package com.ts8.launcher.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ts8.core.map.maplibre.MapPack
import com.ts8.core.music.online.ProviderKind
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.DiagnosticLogger

/**
 * Settings — reachable from Quick Settings, not one of the design's seven
 * states.
 *
 * **What belongs here.** Only two kinds of row: a fact the launcher can read
 * from the platform, and an action the launcher can actually perform. That is
 * the map pack it is currently using, which permissions it holds, which build
 * this is, and a rescan that re-walks storage for a newly plugged-in USB stick.
 * Everything else a settings screen usually offers — a launcher default, a
 * screen timeout, a reboot — either belongs to the platform or needs a
 * signature permission, and a row that pretends otherwise is the fabricated
 * success the spec forbids.
 *
 * **Why the pack section is first.** A head unit with no map pack is the single
 * most common failure state of this launcher, and the exact path it looked in
 * is the one piece of information that lets a driver fix it. Burying it behind
 * a general settings list would mean the driver never finds it.
 *
 * **Why permissions are re-read in `onResume`.** The driver leaves this screen
 * to grant a permission in the system dialog and comes back. Reading the grant
 * state once in `onCreate` would show a stale "chưa cấp" for a permission that
 * was granted thirty seconds earlier.
 *
 * **No diagnostics entry.** `core/diagnostics` is an empty shell in this build;
 * a button that opens nothing is worse than no button. It comes back with the
 * module.
 */
class SettingsActivity : BaseScreenActivity() {

    /** Not a tab: opened from Quick Settings, back returns there. */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    private lateinit var graph: AppGraph

    private lateinit var packName: TextView
    private lateinit var packPath: TextView
    private lateinit var packAttribution: TextView
    private lateinit var permissionList: LinearLayout

    private lateinit var onlineToggleRow: LinearLayout
    private lateinit var onlineKindRow: LinearLayout
    private lateinit var onlineFeed: EditText
    private lateinit var onlineServer: EditText
    private lateinit var onlineUser: EditText
    private lateinit var onlinePass: EditText
    private lateinit var onlinePassState: TextView
    private lateinit var onlineStatus: TextView

    private lateinit var voiceModelState: TextView
    private lateinit var voiceModelDetail: TextView
    private lateinit var voicePolicyRow: LinearLayout

    // Voice feedback toggles
    private lateinit var voiceBeepRow: LinearLayout
    private lateinit var voicePromptRow: LinearLayout
    private lateinit var voiceAutoExecRow: LinearLayout

    // Driving alert toggles
    private lateinit var alertMasterRow: LinearLayout
    private lateinit var alertSpeedRow: LinearLayout
    private lateinit var alertCameraRow: LinearLayout
    private lateinit var alertNavRow: LinearLayout

    // System info
    private lateinit var deviceModelText: TextView
    private lateinit var deviceBoardText: TextView
    private lateinit var deviceScreenText: TextView
    private lateinit var memoryText: TextView
    private lateinit var storageText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindNavigation()

        graph = AppGraph.require()

        packName = findViewById(R.id.packName)
        packPath = findViewById(R.id.packPath)
        packAttribution = findViewById(R.id.packAttribution)
        permissionList = findViewById(R.id.permissionList)

        onlineToggleRow = findViewById(R.id.onlineToggleRow)
        onlineKindRow = findViewById(R.id.onlineKindRow)
        onlineFeed = findViewById(R.id.onlineFeed)
        onlineServer = findViewById(R.id.onlineServer)
        onlineUser = findViewById(R.id.onlineUser)
        onlinePass = findViewById(R.id.onlinePass)
        onlinePassState = findViewById(R.id.onlinePassState)
        onlineStatus = findViewById(R.id.onlineStatus)

        voiceModelState = findViewById(R.id.voiceModelState)
        voiceModelDetail = findViewById(R.id.voiceModelDetail)
        voicePolicyRow = findViewById(R.id.voicePolicyRow)

        voiceBeepRow = findViewById(R.id.voiceBeepRow)
        voicePromptRow = findViewById(R.id.voicePromptRow)
        voiceAutoExecRow = findViewById(R.id.voiceAutoExecRow)

        alertMasterRow = findViewById(R.id.alertMasterRow)
        alertSpeedRow = findViewById(R.id.alertSpeedRow)
        alertCameraRow = findViewById(R.id.alertCameraRow)
        alertNavRow = findViewById(R.id.alertNavRow)

        deviceModelText = findViewById(R.id.deviceModelText)
        deviceBoardText = findViewById(R.id.deviceBoardText)
        deviceScreenText = findViewById(R.id.deviceScreenText)
        memoryText = findViewById(R.id.memoryText)
        storageText = findViewById(R.id.storageText)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRescanPack).setOnClickListener { rescanPack() }
        findViewById<View>(R.id.btnSaveOnline).setOnClickListener { saveOnline() }
        // §6's Settings entry into Map Management. Guarded by the helper rather
        // than a bare startActivity: an undeclared or renamed Activity would
        // otherwise take this screen down with ActivityNotFoundException.
        findViewById<View>(R.id.btnManagePack).setOnClickListener {
            MapManagementActivity.start(this)
        }
        findViewById<View>(R.id.btnDiagnostics).setOnClickListener {
            startActivity(DiagnosticsActivity.intentTo(this))
        }
        findViewById<View>(R.id.btnMapDiag).setOnClickListener {
            showMapDiagnostics()
        }
        // §4's CTA. Guarded rather than a bare startActivity for the same
        // reason Map Management's entry is: a renamed or undeclared Activity
        // must not take Settings down with ActivityNotFoundException.
        findViewById<View>(R.id.btnVoiceModelManage).setOnClickListener {
            val opened = runCatching {
                startActivity(VoiceModelActivity.intentTo(this))
            }.isSuccess
            if (!opened) Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }

        bindVersion()
        renderPack()
        renderOnline()
        renderParkingAuto()
        renderVoicePolicy()
        renderVoiceFeedback()
        renderDrivingAlerts()
        renderSystemInfo()
    }

    override fun onResume() {
        super.onResume()
        // The driver comes back from the system permission dialog; the grant
        // state has to be re-read or the list keeps saying "chưa cấp".
        renderPermissions()
        // The voice pack can have been installed, replaced or removed while the
        // driver was in Voice Model Management. Re-read the cached verdict —
        // that one is cheap and it is the one the router reads.
        renderVoiceModel()
    }

    // -- build ---------------------------------------------------------------

    /**
     * Version and platform, both read from the platform rather than from a
     * constant: a build number pasted into a string drifts from the APK the
     * driver installed, and this screen exists partly so a support call can
     * read the exact build out loud.
     *
     * `BuildConfig` is not available here — this module does not generate it —
     * and `PackageInfo` is the authority either way.
     */
    private fun bindVersion() {
        val info = runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        val versionName = info?.versionName ?: getString(R.string.settings_version_unknown)
        @Suppress("DEPRECATION")
        val versionCode = info?.versionCode ?: 0
        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.settings_version, versionName, versionCode)
        findViewById<TextView>(R.id.platformText).text =
            getString(R.string.settings_platform, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
    }

    // -- map pack ------------------------------------------------------------

    /**
     * Renders whatever the pack lookup currently says — including a failure.
     *
     * The failure text is the same sentence the map screen shows, not a
     * generic "không tìm thấy dữ liệu": it names the directories that were
     * searched, which is the only actionable part.
     */
    private fun renderPack() {
        when (val result = graph.loadPack()) {
            is MapPack.LoadResult.Ok -> {
                val meta = result.pack.metadata
                packName.text = meta.name
                packName.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                packPath.text = result.pack.directory.absolutePath
                val attribution = meta.attribution
                if (attribution.isNullOrBlank()) {
                    packAttribution.visibility = View.GONE
                } else {
                    packAttribution.visibility = View.VISIBLE
                    packAttribution.text = attribution
                }
            }
            is MapPack.LoadResult.Failure -> {
                packName.text = getString(R.string.settings_pack_missing)
                packName.setTextColor(ContextCompat.getColor(this, R.color.danger))
                packPath.text = result.reason
                packAttribution.visibility = View.GONE
            }
        }
    }

    /**
     * Forgets the cached lookup and tries again.
     *
     * This is the action a driver takes after plugging in the USB stick that
     * carries the map. It also drops the search service and the router, which
     * were built on the previous pack — [AppGraph.invalidatePack] owns that
     * ordering, and doing it here instead would be a second copy of the rule.
     */
    private fun rescanPack() {
        graph.invalidatePack()
        renderPack()
        val loaded = graph.pack()
        val message = if (loaded != null) {
            getString(R.string.settings_pack_ready, loaded.metadata.name)
        } else {
            getString(R.string.settings_pack_missing)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // -- online music (§12–13) -----------------------------------------------

    /**
     * Fills the four fields and the two chip rows from what is stored.
     *
     * **Why the password field is left empty even when one is stored.** A
     * password re-rendered into a text field is a password on a screen at
     * arm's length from the driver's seat, and on this ROM a `textPassword`
     * field with content shows its dots while the cursor is in it. The line
     * underneath states whether something is stored, which is the fact the
     * driver actually needs; an empty field means "keep it" and typing replaces
     * it. That is also why [saveOnline] only writes the field when non-blank.
     *
     * **Why the status line shows the gateway's own sentences.** §13's
     * `BLOCKED_EXTERNAL_CONFIG` names the setting that is missing; a sentence
     * composed here would be a second, possibly different, description of the
     * same state.
     */
    private fun renderOnline() {
        val settings = graph.settings
        onlineFeed.setText(settings.onlineFeedUrl)
        onlineServer.setText(settings.onlineServerUrl)
        onlineUser.setText(settings.onlineUser)
        onlinePass.setText("")

        onlinePassState.text = if (settings.onlineToken.isNotBlank()) {
            getString(R.string.settings_online_pass_saved)
        } else {
            getString(R.string.settings_online_pass_none)
        }

        renderOnlineToggle(settings.onlineEnabled)
        renderKindChips(settings.onlineServerKind)

        graph.onlineMusic.status { lines ->
            if (isFinishing || isDestroyed) return@status
            if (lines.isEmpty()) {
                onlineStatus.visibility = View.GONE
            } else {
                onlineStatus.visibility = View.VISIBLE
                onlineStatus.text = lines.joinToString("\n")
            }
        }
    }

    private fun renderOnlineToggle(enabled: Boolean) {
        onlineToggleRow.removeAllViews()
        val title = TextView(this).apply {
            text = getString(R.string.music_online_settings_title)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        onlineToggleRow.addView(title)
        onlineToggleRow.addView(
            chip(getString(R.string.qs_on), enabled) {
                graph.settings.onlineEnabled = true
                graph.onlineMusic.invalidate()
                renderOnline()
            }
        )
        onlineToggleRow.addView(
            chip(getString(R.string.qs_off), !enabled) {
                graph.settings.onlineEnabled = false
                graph.onlineMusic.invalidate()
                renderOnline()
            }
        )
    }

    /**
     * Two chips for the Subsonic family.
     *
     * Both write the same enum, and the choice is a label rather than a
     * behaviour difference on the wire: Jellyfin's Subsonic endpoint takes the
     * same requests, and the value decides what the result rows are called.
     * Stored by name so a later build cannot move a driver's Jellyfin onto the
     * Navidrome branch by reordering an enum.
     */
    private fun renderKindChips(stored: String) {
        onlineKindRow.removeAllViews()
        for (kind in listOf(ProviderKind.NAVIDROME, ProviderKind.JELLYFIN)) {
            val label = if (kind == ProviderKind.JELLYFIN) "Jellyfin" else "Navidrome"
            onlineKindRow.addView(
                chip(label, stored == kind.name) {
                    graph.settings.onlineServerKind = kind.name
                    graph.onlineMusic.invalidate()
                    renderOnline()
                }
            )
        }
    }

    /**
     * Writes the fields back and forces the gateway to be rebuilt.
     *
     * `invalidate()` is not optional: a provider captures its address and
     * credentials when it is constructed, so a corrected host or password
     * would otherwise be ignored by the instance already in hand until the
     * process restarted. The state line is re-read afterwards, which is what
     * turns "saved" from a toast into evidence.
     */
    private fun saveOnline() {
        val settings = graph.settings
        settings.onlineFeedUrl = onlineFeed.text?.toString().orEmpty()
        settings.onlineServerUrl = onlineServer.text?.toString().orEmpty()
        settings.onlineUser = onlineUser.text?.toString().orEmpty()
        // Blank = keep the stored one, never "clear it": an empty password
        // field is the screen's normal state after a reopen.
        val typed = onlinePass.text?.toString().orEmpty()
        if (typed.isNotBlank()) settings.onlineToken = typed

        graph.onlineMusic.invalidate()
        onlinePass.setText("")
        Toast.makeText(this, R.string.settings_online_saved, Toast.LENGTH_SHORT).show()
        renderOnline()
    }

    // -- parking (§9, §18) -----------------------------------------------------

    /**
     * The "tự vào chế độ đỗ xe" switch, as two chips.
     *
     * **What turning it on does and does not do.** It is the `allowAutoEnter`
     * argument [com.ts8.core.parking.ParkingStateResolver.resolve] already
     * takes — the resolver has always read it, and until now nothing in the UI
     * ever wrote it, so it was permanently false and the parking screen could
     * only be reached from Quick Settings. That is the "màn hình parking không
     * hoạt động" state: the screen worked, the route to it did not.
     *
     * It is on by default, because the parking screen is the design's state 7
     * and a fresh install that never showed it is what the driver reported as
     * "màn hình parking không hoạt động". Being on is permission to act, not
     * evidence: the resolver still refuses on its own terms — a stop shorter
     * than the candidate window is traffic, the location engine has to agree
     * the position stopped wandering, a lost fix restarts the dwell rather than
     * banking it, and a call, the reverse camera or a running route blocks
     * entry outright. Speed zero alone has never been enough and still is not.
     *
     * **No vehicle-bus claim.** This unit does not document a signal contract,
     * so gear-Park and ignition-off stay absent and the evaluator falls back to
     * the corroborated GPS path. That is stated in `parking_auto_hint` rather
     * than hidden, because a driver whose car really is in P deserves to know
     * which evidence the launcher is using.
     */
    private fun renderParkingAuto() {
        val enabled = graph.settings.parkingAutoEnter
        val off = findViewById<TextView>(R.id.parkingAutoOff)
        val on = findViewById<TextView>(R.id.parkingAutoOn)

        styleParkingChip(off, selected = !enabled)
        styleParkingChip(on, selected = enabled)

        off.setOnClickListener {
            if (graph.settings.parkingAutoEnter) {
                graph.settings.parkingAutoEnter = false
                renderParkingAuto()
            }
        }
        on.setOnClickListener {
            if (!graph.settings.parkingAutoEnter) {
                graph.settings.parkingAutoEnter = true
                renderParkingAuto()
            }
        }
    }

    /**
     * The selected chip is the primary fill; the other is the plain chip.
     *
     * Written as a method rather than through [chip] because these two are
     * declared in XML — the row existed as static text before it became a
     * setting, and inflating them here would mean removing and re-adding views
     * on every tap.
     */
    private fun styleParkingChip(view: TextView, selected: Boolean) {
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.text_on_accent else R.color.text_secondary
            )
        )
        view.setBackgroundResource(
            if (selected) R.drawable.bg_button_primary else R.drawable.bg_chip
        )
    }

    // -- voice (§4 of the voice pilot) ---------------------------------------

    /**
     * The installed model's state, as the store answered it.
     *
     * **Why the cached snapshot and not a fresh validation.** Refreshing here
     * hashes 51 MB on every resume; the store's cached verdict is what the
     * router trusts, so it is what the driver is shown. Voice Model Management
     * is where a re-read happens, and it runs off the main thread.
     */
    private fun renderVoiceModel() {
        val snapshot = runCatching { graph.voiceModelStore.snapshot() }.getOrNull()
            ?: com.ts8.core.voice.offline.VoiceModelStore.Snapshot(
                com.ts8.core.voice.offline.VoiceModelState.NOT_INSTALLED
            )
        voiceModelState.text = getString(
            R.string.settings_voice_model_state,
            voiceModelStateLabel(snapshot.state)
        )
        voiceModelState.setTextColor(
            ContextCompat.getColor(
                this,
                if (snapshot.ready) R.color.success else R.color.text_primary
            )
        )

        val manifest = snapshot.manifest
        if (manifest == null) {
            voiceModelDetail.visibility = View.GONE
        } else {
            voiceModelDetail.visibility = View.VISIBLE
            voiceModelDetail.text = getString(
                R.string.voice_model_version,
                manifest.modelVersion
            ) + " · " + getString(
                R.string.voice_model_abi,
                manifest.supportedAbis.joinToString(", ")
            )
        }

        // §4: the CTA names the action that is actually available. With nothing
        // valid installed it is the install entry point; once a pack is READY
        // the same button is the management screen, and there is no second
        // button for "manage" that could disagree with this one.
        findViewById<android.widget.Button>(R.id.btnVoiceModelManage).setText(
            if (snapshot.ready) R.string.settings_voice_model_manage
            else R.string.settings_voice_model_cta
        )
    }

    private fun voiceModelStateLabel(
        state: com.ts8.core.voice.offline.VoiceModelState
    ): String = getString(
        when (state) {
            com.ts8.core.voice.offline.VoiceModelState.NOT_INSTALLED ->
                R.string.voice_model_state_not_installed
            com.ts8.core.voice.offline.VoiceModelState.VALIDATING ->
                R.string.voice_model_state_validating
            com.ts8.core.voice.offline.VoiceModelState.READY -> R.string.voice_model_state_ready
            com.ts8.core.voice.offline.VoiceModelState.INCOMPATIBLE ->
                R.string.voice_model_state_incompatible
            com.ts8.core.voice.offline.VoiceModelState.CORRUPT -> R.string.voice_model_state_corrupt
            com.ts8.core.voice.offline.VoiceModelState.FAILED -> R.string.voice_model_state_failed
        }
    )

    /**
     * The recognition policy, as three chips.
     *
     * Each chip writes [AppSettings.recognitionPolicy] by enum name, which the
     * router re-reads at the moment of the next press — no restart and no
     * cached value to go stale. `SYSTEM_ONLY` is offered rather than hidden
     * because it is the diagnostic mode the brief keeps: it is what a support
     * call asks a driver to switch to in order to tell "the platform path is
     * broken" from "the model is broken".
     */
    private fun renderVoicePolicy() {
        val stored = graph.settings.recognitionPolicy
        voicePolicyRow.removeAllViews()
        for (policy in com.ts8.core.voice.offline.RecognitionPolicy.entries) {
            val label = getString(
                when (policy) {
                    com.ts8.core.voice.offline.RecognitionPolicy.AUTO ->
                        R.string.voice_policy_auto
                    com.ts8.core.voice.offline.RecognitionPolicy.OFFLINE_ONLY ->
                        R.string.voice_policy_offline_only
                    com.ts8.core.voice.offline.RecognitionPolicy.SYSTEM_ONLY ->
                        R.string.voice_policy_system_only
                }
            )
            voicePolicyRow.addView(
                chip(label, policy == stored) {
                    graph.settings.recognitionPolicy = policy
                    renderVoicePolicy()
                }
            )
        }
    }

    // -- voice feedback (beep + TTS) ------------------------------------------

    /**
     * Three toggles inspired by the reference APK's voice alert system:
     * - Beep on mic open / recognition end / error
     * - TTS "Xin mời nói" when mic opens
     * - Auto-execute without confirmation dialog
     *
     * Each uses the chip pattern the rest of this screen already uses — the
     * platform Switch is invisible against the card on this ROM.
     */
    private fun renderVoiceFeedback() {
        renderToggleRow(
            voiceBeepRow,
            getString(R.string.settings_voice_beep),
            graph.settings.voiceBeepEnabled
        ) { enabled ->
            graph.settings.voiceBeepEnabled = enabled
        }

        renderToggleRow(
            voicePromptRow,
            getString(R.string.settings_voice_prompt_listen),
            graph.settings.voiceListeningPromptEnabled
        ) { enabled ->
            graph.settings.voiceListeningPromptEnabled = enabled
        }

        renderToggleRow(
            voiceAutoExecRow,
            getString(R.string.settings_voice_auto_exec),
            graph.settings.voiceAutoExecute
        ) { enabled ->
            graph.settings.voiceAutoExecute = enabled
        }
    }

    // -- driving alerts (inspired by reference APK) ---------------------------

    /**
     * Alert toggles matching the reference APK's `driving_alert_*` preferences.
     *
     * The reference APK stores `driving_alerts_muted` as a master switch and
     * individual `driving_alert_speed`, `driving_alert_cam`, etc. for each
     * alert category. We do the same: a master toggle and four sub-toggles.
     */
    private fun renderDrivingAlerts() {
        renderToggleRow(
            alertMasterRow,
            getString(R.string.settings_alerts_master),
            graph.settings.drivingAlertsEnabled
        ) { enabled ->
            graph.settings.drivingAlertsEnabled = enabled
        }

        renderToggleRow(
            alertSpeedRow,
            getString(R.string.settings_alert_speed),
            graph.settings.alertSpeedEnabled
        ) { enabled ->
            graph.settings.alertSpeedEnabled = enabled
        }

        renderToggleRow(
            alertCameraRow,
            getString(R.string.settings_alert_camera),
            graph.settings.alertCameraEnabled
        ) { enabled ->
            graph.settings.alertCameraEnabled = enabled
        }

        renderToggleRow(
            alertNavRow,
            getString(R.string.settings_alert_navigation),
            graph.settings.alertNavigationEnabled
        ) { enabled ->
            graph.settings.alertNavigationEnabled = enabled
        }
    }

    /**
     * A toggle row: label on the left, two chips (Bật/Tắt) on the right.
     *
     * Reuses the same chip style the online and parking rows use, because the
     * platform Switch draws in the theme's disabled colour on this ROM and
     * disappears against the card.
     */
    private fun renderToggleRow(
        container: LinearLayout,
        label: String,
        enabled: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        container.removeAllViews()
        val title = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        container.addView(title)
        container.addView(
            chip(getString(R.string.qs_on), enabled) {
                onChange(true)
                renderVoiceFeedback()
                renderDrivingAlerts()
            }
        )
        container.addView(
            chip(getString(R.string.qs_off), !enabled) {
                onChange(false)
                renderVoiceFeedback()
                renderDrivingAlerts()
            }
        )
    }

    // -- system info ---------------------------------------------------------

    /**
     * Device hardware facts read from the platform: model, board, screen
     * resolution, RAM, and storage. These are the facts a support call needs,
     * read from the platform rather than guessed.
     */
    private fun renderSystemInfo() {
        deviceModelText.text = getString(R.string.settings_device_model, Build.MODEL ?: "unknown")
        deviceBoardText.text = getString(R.string.settings_device_board, Build.BOARD ?: "unknown")

        val wm = resources.displayMetrics
        deviceScreenText.text = getString(
            R.string.settings_device_screen, wm.widthPixels, wm.heightPixels
        )

        // RAM: ActivityManager.MemoryInfo is available on API 23.
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = if (memInfo != null) memInfo.totalMem / (1024 * 1024) else 0
        memoryText.text = getString(
            R.string.settings_memory_info,
            if (totalRamMb > 0) "${totalRamMb} MB" else "N/A"
        )

        // Storage: external storage is the one the map pack lives on.
        val storagePath = Environment.getExternalStorageDirectory().absolutePath
        val stat = StatFs(storagePath)
        val totalBytes = stat.totalBytes
        val availBytes = stat.availableBytes
        val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
        val availGb = availBytes / (1024.0 * 1024.0 * 1024.0)
        storageText.text = getString(
            R.string.settings_storage_info,
            "%.1f / %.1f GB".format(availGb, totalGb)
        )
    }

    /**
     * Shows the map diagnostic log — the same lines MapLibreMapController
     * writes through MapDiagnosticLogger, including the getMapAsync timeout
     * that identifies the GL-surface failure on this ROM.
     */
    private fun showMapDiagnostics() {
        val message = DiagnosticLogger.tail(50)
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_map_diag)
            .setMessage(message)
            .setPositiveButton(R.string.generic_close, null)
        builder.show()
    }

    // -- permissions ---------------------------------------------------------

    /**
     * One row per permission the launcher genuinely asks for.
     *
     * Only three, and only the three that change what the launcher can do:
     * location for the map and navigation, storage for the music library, and
     * `WRITE_SETTINGS` for the brightness slider. Microphone, contacts and the
     * rest are not listed because nothing in this build uses them, and a
     * permission row for an unused permission teaches the driver to distrust
     * the list.
     */
    private fun renderPermissions() {
        permissionList.removeAllViews()
        addPermission(R.string.perm_location_title, Manifest.permission.ACCESS_FINE_LOCATION)
        addPermission(R.string.perm_storage_title, Manifest.permission.READ_EXTERNAL_STORAGE)
        addWriteSettingsPermission()
    }

    private fun addPermission(labelRes: Int, permission: String) {
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        addRow(
            label = getString(labelRes),
            granted = granted,
            // A runtime permission is re-requested by the screen that needs it;
            // from here the only useful destination is the app's own page,
            // where a "don't ask again" denial can be undone.
            action = if (granted) null else View.OnClickListener { openAppDetails() }
        )
    }

    /**
     * `WRITE_SETTINGS` is a special permission with its own grant screen, not a
     * runtime permission, so its request path is different from the two above
     * and it gets its own row rather than being folded in.
     */
    private fun addWriteSettingsPermission() {
        val granted = runCatching { Settings.System.canWrite(this) }.getOrDefault(false)
        addRow(
            label = getString(R.string.settings_perm_brightness),
            granted = granted,
            action = if (granted) null else View.OnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                val opened = runCatching { startActivity(intent) }
                if (opened.isFailure) {
                    Toast.makeText(this, R.string.qs_permission_required, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun addRow(label: String, granted: Boolean, action: View.OnClickListener?) {
        val row = TextView(this).apply {
            text = if (granted) getString(R.string.settings_perm_granted, label)
            else getString(R.string.settings_perm_denied, label)
            setTextColor(
                ContextCompat.getColor(
                    this@SettingsActivity,
                    if (granted) R.color.text_secondary else R.color.accent_text
                )
            )
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        if (action != null) {
            row.setOnClickListener(action)
        }
        permissionList.addView(row)
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A 44 dp chip, the same control the map settings panel uses.
     *
     * Not a `Switch`: on this ROM the platform switch draws in the theme's
     * disabled colour and all but vanishes against the dark card. A chip is
     * also a 44 dp touch target by construction, which is the size a driver
     * can hit without looking.
     */
    private fun chip(labelText: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = labelText
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minWidth = dp(72)
            minHeight = dp(40)
            setTextColor(
                ContextCompat.getColor(
                    this@SettingsActivity,
                    if (selected) R.color.text_on_accent else R.color.text_secondary
                )
            )
            setBackgroundResource(
                if (selected) R.drawable.bg_button_primary else R.drawable.bg_chip
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
