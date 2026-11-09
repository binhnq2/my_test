package com.ts8.launcher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ts8.core.music.online.ProviderKind
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppStateMachine
import com.ts8.launcher.runtime.BuildFingerprint
import com.ts8.launcher.runtime.DiagnosticsReport
import com.ts8.launcher.runtime.LauncherModeStore
import com.ts8.launcher.runtime.MusicRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics (§11) — the last resort a driver has when the panel misbehaves.
 *
 * **What this screen is for, stated as a rule.** A support conversation on a
 * head unit happens over the phone, with the driver parked and the panel in
 * front of them. Everything on this page exists so that conversation has
 * facts instead of descriptions: the exact build, the exact mode, which pack
 * the map thinks it is drawing, why it is not drawing, whether music's service
 * is even connected, whether the unit has a Vietnamese voice at all, and
 * whether the volume knob is being received by anything. None of those is a
 * guess and none is inferred — each line is read from the object that owns it.
 *
 * **Why the screen and the export share one object.** [DiagnosticsReport] is
 * built once per render and both rendered and written. A screen that composed
 * its own text would drift from the file the driver sent, and the file is the
 * artefact a developer reads — a discrepancy between the two is worse than no
 * export at all.
 *
 * **What the file will never contain.** No password, no online-service token,
 * no RSS or server URL value (the *state* "đã cấu hình" is reported, not the
 * address), and no coordinates. §11 forbids credentials and personal location
 * data by default, and [DiagnosticsReport] has no field that could hold either
 * — that is a structural guarantee, not a filter.
 *
 * **Why the build hash is computed off the main thread.** It is the SHA-256 of
 * the 58 MB APK on eMMC. Doing that on the frame that opens this screen would
 * be a visible stall, so the line reads "đang tính…" and fills in when the IO
 * pool answers.
 */
class DiagnosticsActivity : BaseScreenActivity() {

    /** Not a tab: opened from Settings, Back returns there. */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    /** §16's machine has no diagnostics state; this screen claims none. */
    override val primaryState: AppStateMachine.State? = null

    private lateinit var graph: AppGraph
    private lateinit var list: LinearLayout
    private lateinit var exportResult: TextView

    /** The digest line's view, replaced in place when the hash arrives. */
    private var buildHashValue: TextView? = null

    private var buildHash: String = BuildFingerprint.cached() ?: BuildFingerprint.PENDING

    /** The report as last rendered; what the export writes. */
    private var report: DiagnosticsReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        bindNavigation()

        graph = AppGraph.require()
        list = findViewById(R.id.diagnosticsList)
        exportResult = findViewById(R.id.exportResult)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnExport).setOnClickListener { export() }

        render()
        resolveBuildHash()
    }

    override fun onResume() {
        super.onResume()
        // Mode, map phase and parking state can all have changed while the
        // driver was on another screen or in the system dialogs. Re-render
        // rather than showing what was true when this screen was created.
        render()
    }

    // -- rendering -----------------------------------------------------------

    /**
     * Reads every subsystem's own answer and builds the report.
     *
     * Each line goes through [runCatching] individually rather than one guard
     * around the whole method: a subsystem that throws must cost the driver its
     * own line, not the other eleven. This is the one screen whose entire
     * purpose is to still say something when something else is broken.
     */
    private fun render() {
        val built = buildReport()
        report = built
        renderRows(built)
    }

    private fun buildReport(): DiagnosticsReport {
        val settings = graph.settings
        val mode = runCatching { graph.launcherMode }.getOrNull()
        val navState = runCatching { graph.navigationStateIfBuilt() }
            .getOrDefault(com.ts8.core.navigation.NavigationState.IDLE)
        val packName = runCatching { graph.pack()?.metadata?.name }.getOrNull()
        val phase = runCatching { graph.mapPhase.snapshot }.getOrNull()

        val music = runCatching {
            val runtime = MusicRuntime.get(this)
            val state = runtime.state()
            val title = state.track?.title
            when {
                title.isNullOrBlank() -> getString(R.string.diagnostics_music_idle)
                state.isPlaying -> getString(R.string.diagnostics_music_playing, title)
                else -> getString(R.string.diagnostics_music_paused, title)
            }
        }.getOrDefault(getString(R.string.diagnostics_value_none))

        val service = runCatching {
            if (graph.musicBackend != null) {
                getString(R.string.diagnostics_service_connected)
            } else {
                getString(R.string.diagnostics_service_disconnected)
            }
        }.getOrDefault(getString(R.string.diagnostics_value_none))

        // §11 asks for the provider that was *selected*, not the one that
        // happens to exist: on the TS8 the platform recogniser is absent and the
        // offline model is the only path, so a line that reported the platform
        // probe alone would say "missing" on a unit where voice works.
        val voice = runCatching {
            val tts = if (graph.voice.isVietnameseAvailable()) {
                getString(R.string.diagnostics_voice_tts_ready)
            } else {
                getString(R.string.diagnostics_voice_tts_missing)
            }
            "$tts · ${voiceRecognitionLine(settings.recognitionPolicy)}"
        }.getOrDefault(getString(R.string.diagnostics_value_none))

        val voiceDetails = runCatching {
            graph.voiceInstant().diagnostics()?.toLines().orEmpty()
        }.getOrDefault(emptyList())

        val parking = runCatching {
            val decision = graph.parking.decision()
            "${decision.state.name} · ${decision.confidence.name}"
        }.getOrDefault(getString(R.string.diagnostics_value_none))

        val volume = runCatching {
            val bridge = graph.audioBridge
            val capability = graph.volumeCapability
            val fixed = runCatching { bridge.isVolumeFixed() }.getOrDefault(false)
            val level = runCatching { bridge.mediaVolume() }.getOrDefault(-1)
            val max = runCatching { bridge.maxMediaVolume() }.getOrDefault(-1)
            val state = if (fixed) {
                getString(R.string.volume_unavailable)
            } else {
                getString(
                    when (capability.capability) {
                        com.ts8.core.audio.VolumeCapabilityTracker.Capability.SUPPORTED ->
                            R.string.volume_supported
                        com.ts8.core.audio.VolumeCapabilityTracker.Capability.NO_EFFECT ->
                            R.string.volume_no_effect_short
                        com.ts8.core.audio.VolumeCapabilityTracker.Capability.UNAVAILABLE ->
                            R.string.volume_unavailable
                        com.ts8.core.audio.VolumeCapabilityTracker.Capability.UNKNOWN ->
                            R.string.volume_unknown
                    }
                )
            }
            "$state · $level/$max"
        }.getOrDefault(getString(R.string.diagnostics_value_none))

        val version = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val versionCode = version?.versionCode ?: 0
        val versionName = version?.versionName ?: getString(R.string.settings_version_unknown)

        return DiagnosticsReport(
            generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .format(Date(System.currentTimeMillis())),
            appVersion = "$versionName ($versionCode)",
            buildHash = buildHash,
            platform = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            mode = mode?.effectiveMode()?.name ?: LauncherModeStore.Mode.HOME.name,
            navigation = navState.name + if (mode?.navActive == true) " · đang chạy" else "",
            mapPack = packName ?: getString(R.string.diagnostics_value_none),
            mapPhase = phase?.diagnosticsLine() ?: getString(R.string.diagnostics_value_none),
            music = "$music · $service",
            voice = voice,
            parking = parking,
            volume = volume,
            providers = runCatching { providerLines(settings.onlineEnabled) }.getOrDefault(emptyList()),
            voiceDetails = voiceDetails,
            labels = labels()
        )
    }

    /**
     * Which backend voice will use, and whether the offline model is loadable.
     *
     * **Why both halves.** The platform probe answers "does this firmware ship a
     * recogniser" — on the TS8 the answer is no, and the line must still say
     * something useful. The model state answers "can the fallback actually
     * answer", which is the half the driver can fix, and it names the installed
     * version so a support call knows what the unit is running.
     *
     * The policy is quoted as well: a unit in `OFFLINE_ONLY` with no model is
     * genuinely unable to hear anything, and that combination is the one worth
     * spotting in an exported file.
     */
    private fun voiceRecognitionLine(
        policy: com.ts8.core.voice.offline.RecognitionPolicy
    ): String {
        val system = runCatching {
            com.ts8.launcher.runtime.SpeechRecognizerAdapter(graph.appContext).isAvailable()
        }.getOrDefault(false)
        val model = runCatching { graph.voiceModelStore.snapshot() }.getOrNull()

        val provider = when {
            // Mirrors `RecognitionProviderRouter.begin`: the policy decides the
            // first provider, and AUTO prefers the platform one only when it is
            // actually usable.
            policy == com.ts8.core.voice.offline.RecognitionPolicy.OFFLINE_ONLY -> "vosk"
            policy == com.ts8.core.voice.offline.RecognitionPolicy.SYSTEM_ONLY -> "system"
            system -> "system"
            else -> "vosk"
        }
        val modelLine = when {
            model == null -> getString(R.string.diagnostics_voice_model_unknown)
            model.state == com.ts8.core.voice.offline.VoiceModelState.READY ->
                getString(
                    R.string.diagnostics_voice_model_ready,
                    model.manifest?.modelVersion ?: "?"
                )
            else -> getString(R.string.diagnostics_voice_model_state, model.state.name)
        }
        val recognizer = if (system) {
            getString(R.string.diagnostics_voice_recognition_ready)
        } else {
            getString(R.string.diagnostics_voice_recognition_missing)
        }
        return "$recognizer · $provider · $modelLine"
    }

    /**
     * One line per provider: name and configuration **state**.
     *
     * Deliberately not the configured value. The state answers the only
     * question a support call asks ("is this source set up at all"), and the
     * value would be an endpoint, which §11 puts out of bounds for the export.
     */
    private fun providerLines(onlineEnabled: Boolean): List<String> {
        val infos = graph.onlineMusic.providers()
        if (infos.isEmpty()) return emptyList()
        return infos.map { info ->
            val state = when {
                !onlineEnabled -> getString(R.string.music_search_offline)
                info.configured -> getString(R.string.music_source_configured)
                info.missing != null -> getString(R.string.music_source_missing, info.missing)
                else -> getString(R.string.music_source_not_configured)
            }
            // The kind name as well as the display name: "Navidrome" and
            // "Jellyfin" both mean the Subsonic family, and the enum is what
            // a developer greps for.
            "${info.displayName} (${shortKind(info.kind)}): $state"
        }
    }

    private fun shortKind(kind: ProviderKind): String = when (kind) {
        ProviderKind.INTERNET_RADIO -> "radio"
        ProviderKind.PODCAST_RSS -> "podcast"
        ProviderKind.NAVIDROME, ProviderKind.JELLYFIN -> "subsonic"
        ProviderKind.OTHER -> "other"
    }

    private fun labels(): DiagnosticsReport.Labels = DiagnosticsReport.Labels(
        title = getString(R.string.diagnostics_title),
        generatedAt = getString(R.string.diagnostics_generated_at),
        appVersion = getString(R.string.diagnostics_app_version),
        buildHash = getString(R.string.diagnostics_build_hash),
        platform = getString(R.string.diagnostics_platform),
        mode = getString(R.string.diagnostics_mode),
        navigation = getString(R.string.diagnostics_navigation),
        mapPack = getString(R.string.diagnostics_map_pack),
        mapPhase = getString(R.string.diagnostics_map_phase),
        music = getString(R.string.diagnostics_music),
        voice = getString(R.string.diagnostics_voice),
        parking = getString(R.string.diagnostics_parking),
        volume = getString(R.string.diagnostics_volume),
        providers = getString(R.string.diagnostics_providers),
        footer = getString(R.string.diagnostics_footer)
    )

    /**
     * Draws the report as label/value rows.
     *
     * The values are built by the same report object the export writes, so a
     * row cannot be on screen and missing from the file. Grouping is a blank
     * spacing between the identity block, the map block and the runtime block,
     * which is how the page stays readable at 600 dp without section headers
     * that would need translating twice.
     */
    private fun renderRows(report: DiagnosticsReport) {
        list.removeAllViews()
        buildHashValue = null

        addRow(report.labels.appVersion, report.appVersion)
        buildHashValue = addRow(report.labels.buildHash, report.buildHash, keep = true)
        addRow(report.labels.platform, report.platform)

        addSpacer()

        addRow(report.labels.mode, report.mode)
        addRow(report.labels.navigation, report.navigation)

        addSpacer()

        addRow(report.labels.mapPack, report.mapPack)
        addRow(report.labels.mapPhase, report.mapPhase)

        addSpacer()

        addRow(report.labels.music, report.music)
        addRow(report.labels.voice, report.voice)
        addRow(report.labels.parking, report.parking)
        addRow(report.labels.volume, report.volume)

        if (report.providers.isNotEmpty()) {
            addSpacer()
            addRow(report.labels.providers, report.providers.joinToString("\n"))
        }
    }

    /**
     * Two-line row: dim label, bright value.
     *
     * Stacked rather than side by side because three of the values here are
     * sentences ("NaviDrome (subsonic): Chưa cấu hình — thiếu địa chỉ máy chủ"),
     * and a label column wide enough for those on a 1024-wide panel would leave
     * no room for the value.
     *
     * @param keep retained so [resolveBuildHash] can replace this row's value
     *        without rebuilding the whole list and losing the scroll position.
     */
    private fun addRow(label: String, value: String, keep: Boolean = false): TextView {
        val labelView = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@DiagnosticsActivity, R.color.text_tertiary))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        }
        val valueView = TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@DiagnosticsActivity, R.color.text_primary))
            textSize = 13f
            setPadding(0, 0, 0, dp(2))
        }
        list.addView(labelView)
        list.addView(valueView)
        if (keep) buildHashValue = valueView
        return valueView
    }

    private fun addSpacer() {
        list.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
                )
            }
        )
    }

    // -- build hash ----------------------------------------------------------

    /**
     * Hashing the installed APK takes a second on eMMC; the line fills in when
     * it lands rather than blocking the first frame.
     */
    private fun resolveBuildHash() {
        if (buildHash != BuildFingerprint.PENDING) return
        graph.io().execute {
            val computed = runCatching {
                BuildFingerprint.compute(File(applicationInfo.sourceDir))
            }.getOrDefault(BuildFingerprint.UNKNOWN)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                buildHash = computed
                // Update in place: rebuilding the list would reset the scroll
                // position of a page the driver may already be reading.
                buildHashValue?.text = computed
                report = report?.copy(buildHash = computed)
            }
        }
    }

    // -- export --------------------------------------------------------------

    /**
     * Writes [report] to a file the driver can actually reach.
     *
     * **Destination order, and why.** `Download` first: on this ROM the file
     * manager and the USB-stick transfer both browse it, and it is where a
     * person looking for "the file the car saved" will look. Writing there
     * needs `WRITE_EXTERNAL_STORAGE`, which is install-time on API 23 but also
     * a runtime permission there, so a denied grant falls through instead of
     * failing. The app's own external files directory is the fallback: no
     * permission, still readable over MTP and `adb pull`, which is the copy a
     * developer needs.
     *
     * **Why the path is shown afterwards.** An export whose location the driver
     * cannot find is not an export. The result line prints the absolute path,
     * and the failure case prints the reason rather than the same line with a
     * sad tone.
     */
    private fun export() {
        val current = report ?: buildReport()
        val target = pickTarget() ?: run {
            exportResult.visibility = View.VISIBLE
            exportResult.text = getString(
                R.string.diagnostics_export_failed,
                getString(R.string.diagnostics_no_storage)
            )
            return
        }
        val written = runCatching {
            target.parentFile?.mkdirs()
            target.writeText(current.render(), Charsets.UTF_8)
        }
        exportResult.visibility = View.VISIBLE
        if (written.isSuccess) {
            exportResult.text = getString(R.string.diagnostics_exported, target.absolutePath)
            Toast.makeText(this, R.string.diagnostics_export_done, Toast.LENGTH_SHORT).show()
        } else {
            exportResult.text = getString(
                R.string.diagnostics_export_failed,
                written.exceptionOrNull()?.message ?: getString(R.string.diagnostics_value_none)
            )
        }
    }

    /**
     * The first destination that can actually be written, or null.
     *
     * The permission is probed before the directory, because on API 23 a
     * missing grant makes `mkdirs()` return false rather than throw — the same
     * silent `File.isDirectory()` behaviour the map loader documents.
     */
    private fun pickTarget(): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
            .format(Date(System.currentTimeMillis()))
        val name = "ts8-diagnostics-$stamp.txt"

        val canWriteStorage = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED &&
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

        if (canWriteStorage) {
            val downloads = runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }.getOrNull()
            if (downloads != null) {
                val candidate = File(downloads, name)
                if (runCatching {
                        downloads.mkdirs()
                        candidate.createNewFile() || candidate.canWrite()
                    }.getOrDefault(false)
                ) {
                    return candidate
                }
            }
        }

        val own = getExternalFilesDir(null) ?: filesDir
        val fallback = File(own, name)
        val ok = runCatching {
            own.mkdirs()
            fallback.createNewFile() || fallback.canWrite()
        }.getOrDefault(false)
        return if (ok) fallback else null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Opens Diagnostics from Settings. */
        fun intentTo(context: Context): Intent =
            Intent(context, DiagnosticsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
