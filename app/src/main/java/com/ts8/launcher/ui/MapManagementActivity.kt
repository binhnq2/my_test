package com.ts8.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ts8.core.map.MapStyleMode
import com.ts8.core.map.maplibre.MapPack
import com.ts8.core.map.maplibre.PackDiagnostics
import com.ts8.core.map.maplibre.PackDownloadClient
import com.ts8.core.map.maplibre.PackDownloadManager
import com.ts8.core.map.maplibre.PackFailureCode
import com.ts8.core.map.maplibre.PackImporter
import com.ts8.core.map.maplibre.PackValidator
import com.ts8.core.speed.SpeedFilter
import com.ts8.core.speed.SpeedMonitor
import com.ts8.core.speed.SpeedUnit
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import com.ts8.launcher.runtime.AppSettings
import com.ts8.launcher.runtime.AppStateMachine
import java.io.File
import java.util.Locale

/**
 * Map Management — installed packs, import, and the two settings groups from §8.
 *
 * **Why every row here is real.** Each control writes a preference that a screen
 * actually reads ([AppSettings]), or performs an action whose result the driver
 * sees on this same screen (import progress, validation report, free space). A
 * settings screen whose switches do nothing is the specific failure the brief
 * calls out, and it is easy to produce by accident: adding a control before the
 * code that consumes it.
 *
 * **Why the import is asynchronous and staged.** A 20 MB archive takes seconds
 * to extract on this CPU. Doing it on the main thread freezes the panel, and
 * doing it in place risks a half-replaced pack if the ignition cuts. The work
 * runs on [AppGraph.io] through [PackImporter], which extracts to a staging
 * directory, validates there, and only then swaps it in.
 *
 * **Why the active pack is protected.** Removing the pack the map is currently
 * drawing leaves a black screen with no way back, so removal of the active pack
 * asks the driver to confirm that they are choosing to have *no map* — the rule
 * the campaign brief states verbatim.
 */
class MapManagementActivity : BaseScreenActivity() {

    /** Not a tab: opened from the map placeholder, Settings or Quick Settings. */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    private lateinit var graph: AppGraph
    private lateinit var settings: AppSettings

    private lateinit var packList: LinearLayout
    private lateinit var packEmpty: TextView
    private lateinit var storageText: TextView
    private lateinit var importStatus: TextView
    private lateinit var diagText: TextView
    private lateinit var displayGroup: LinearLayout
    private lateinit var speedGroup: LinearLayout

    // Download section
    private lateinit var serverUrlInput: EditText
    private lateinit var catalogList: LinearLayout
    private lateinit var downloadStatus: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var btnDownloadCancel: Button

    private val main = Handler(Looper.getMainLooper())

    /** Packs currently on storage, from the most recent pass. */
    private var packs: List<MapPack> = emptyList()

    /** Byte size per pack path, measured on the IO thread. */
    private val sizes = HashMap<String, Long>(4)

    /** True while a background import is running; blocks a second one. */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_management)
        bindNavigation()

        graph = AppGraph.require()
        settings = graph.settings

        packList = findViewById(R.id.packList)
        packEmpty = findViewById(R.id.packEmpty)
        storageText = findViewById(R.id.storageText)
        importStatus = findViewById(R.id.importStatus)
        diagText = findViewById(R.id.diagText)
        displayGroup = findViewById(R.id.displayGroup)
        speedGroup = findViewById(R.id.speedGroup)

        // Download section
        serverUrlInput = findViewById(R.id.serverUrlInput)
        catalogList = findViewById(R.id.catalogList)
        downloadStatus = findViewById(R.id.downloadStatus)
        downloadProgress = findViewById(R.id.downloadProgress)
        btnDownloadCancel = findViewById(R.id.btnDownloadCancel)

        serverUrlInput.setText(settings.mapServerUrl)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnImport).setOnClickListener { pickArchive() }
        findViewById<View>(R.id.btnRescan).setOnClickListener { rescan() }
        findViewById<View>(R.id.btnRestoreDisplay).setOnClickListener { restoreDisplayDefaults() }
        findViewById<View>(R.id.btnCopyDiag).setOnClickListener { copyDiagnostics() }
        findViewById<View>(R.id.btnViewLog).setOnClickListener { toggleDiagLog() }
        findViewById<View>(R.id.btnSaveServer).setOnClickListener { saveServerUrl() }
        findViewById<View>(R.id.btnFetchCatalog).setOnClickListener { fetchCatalog() }
        btnDownloadCancel.setOnClickListener { cancelDownload() }

        // Listen for download state changes
        graph.packDownloadManager.stateListener = { state ->
            main.post { renderDownloadState(state) }
        }

        buildDisplayGroup()
        buildSpeedGroup()
        refreshPacks()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the picker or from a USB insert: re-read rather than
        // showing the state from when the screen was first opened.
        if (!busy) refreshPacks()
        renderDiagnostics()
    }

    // =======================================================================
    // Packs
    // =======================================================================

    /**
     * Re-walks storage off the main thread and re-renders the list.
     *
     * The walk stats every root and parses one manifest per candidate, then
     * measures each pack's size by walking its tree. On a unit with a USB stick
     * full of music that is tens of milliseconds, but storage access never
     * happens on the thread that draws.
     */
    private fun refreshPacks() {
        renderStorage()
        graph.io().execute {
            val found = runCatching { graph.listPacks() }.getOrElse { emptyList() }
            val measured = found.map { pack ->
                pack.directory.absolutePath to runCatching { pack.sizeBytes() }.getOrDefault(0L)
            }
            main.post {
                packs = found
                sizes.clear()
                sizes.putAll(measured)
                renderPacks()
                renderDiagnostics()
            }
        }
    }

    private fun renderPacks() {
        packList.removeAllViews()
        packEmpty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE

        val activePath = graph.pack()?.directory?.absolutePath
        val report = graph.packReport()

        for (pack in packs) {
            packList.addView(
                packCard(pack, isActive = pack.directory.absolutePath == activePath, report = report)
            )
        }
    }

    /**
     * One installed pack: identity, size, data date, capability status, actions.
     *
     * The capability lines are the "degraded modes are explicit" requirement: a
     * pack without `routing-graph/` still draws a map and still searches, and
     * says so, rather than failing as a whole.
     */
    private fun packCard(pack: MapPack, isActive: Boolean, report: PackValidator.Report?): View {
        val meta = pack.metadata
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        card.addView(text(meta.name, R.color.text_primary, 16f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (isActive) {
            card.addView(text(getString(R.string.map_pack_active), R.color.accent_text, 12f))
        }

        card.addView(text(getString(R.string.map_pack_region, meta.packId), R.color.text_secondary, 12f))
        card.addView(text(
            getString(
                R.string.map_pack_version,
                "v${meta.formatVersion} · zoom ${meta.minZoom.toInt()}–${meta.maxZoom.toInt()}"
            ),
            R.color.text_secondary, 12f
        ))
        card.addView(text(
            getString(R.string.map_pack_size, formatBytes(sizes[pack.directory.absolutePath] ?: 0L)),
            R.color.text_secondary, 12f
        ))
        card.addView(text(
            getString(R.string.map_pack_data_date, meta.generatedAt ?: "-"),
            if (meta.generatedAt == null) R.color.text_tertiary else R.color.text_secondary, 12f
        ))

        // Capability rows. Only the active pack has actually been opened by the
        // validator; for the others the file presence is checked directly, which
        // is cheap and honest about what is known.
        val routingOk = if (isActive && report != null) report.routingOk else pack.routingDir() != null
        val searchOk = if (isActive && report != null) report.placesOk else pack.databaseFile() != null

        card.addView(text(
            if (routingOk) getString(R.string.map_pack_routing_ok)
            else getString(R.string.map_pack_routing_missing),
            if (routingOk) R.color.success else R.color.danger, 12f
        ))
        card.addView(text(
            if (searchOk) getString(R.string.map_pack_search_ok, 0)
            else getString(R.string.map_pack_search_missing),
            if (searchOk) R.color.success else R.color.danger, 12f
        ))

        val status = when {
            isActive && graph.isPackValidating() -> getString(R.string.map_pack_status_validating)
            isActive && report != null && !report.ok ->
                getString(R.string.map_pack_status_invalid, report.code?.name ?: "?")
            else -> getString(R.string.map_pack_status_ready)
        }
        card.addView(text(
            status,
            if (isActive && report != null && !report.ok) R.color.danger else R.color.text_tertiary,
            12f
        ).apply { setPadding(0, dp(4), 0, dp(6)) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        if (!isActive) {
            actions.addView(actionButton(getString(R.string.map_action_activate)) { activate(pack) })
        }
        actions.addView(actionButton(getString(R.string.map_action_validate)) {
            graph.revalidatePack()
            Toast.makeText(this, R.string.map_pack_status_validating, Toast.LENGTH_SHORT).show()
            main.postDelayed({
                renderPacks()
                renderDiagnostics()
            }, REFRESH_AFTER_VALIDATION_MS)
        })
        actions.addView(actionButton(getString(R.string.map_action_remove)) {
            confirmRemove(pack, isActive)
        })
        card.addView(actions)

        return card
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_chip)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    /**
     * Points the app at [pack] and re-reads storage.
     *
     * Writing the pointer is what makes the choice stick across a reboot: the
     * loader reads it ahead of the root walk, so a second region installed later
     * cannot silently take over.
     */
    private fun activate(pack: MapPack) {
        settings.activePackPath = pack.directory.absolutePath
        settings.noMapConfirmed = false
        graph.invalidatePack()
        Toast.makeText(
            this,
            getString(R.string.map_activate_done, pack.metadata.name),
            Toast.LENGTH_SHORT
        ).show()
        refreshPacks()
    }

    /**
     * Confirms, then removes.
     *
     * Guarding the active pack is a distinct dialog with distinct words: the
     * driver must understand they are choosing to have no map, not merely to
     * delete a folder.
     */
    private fun confirmRemove(pack: MapPack, isActive: Boolean) {
        val body = if (isActive) {
            getString(R.string.map_remove_active_body, pack.metadata.name)
        } else {
            getString(R.string.map_remove_body, pack.metadata.name)
        }
        val positive = if (isActive) {
            getString(R.string.map_remove_confirm_no_map)
        } else {
            getString(R.string.map_action_remove)
        }

        // §16's ERROR_DIALOG overlay for the modal — the name in the brief
        // covers any dialog that takes Back ahead of the screen, and this one
        // is the case that matters most: a Back meant to cancel a "no map"
        // confirmation must not close Map Management and leave the driver on
        // the map placeholder wondering what they agreed to.
        showTrackedDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.map_remove_title)
                .setMessage(body)
                .setNegativeButton(R.string.generic_cancel, null)
                .setPositiveButton(positive) { _, _ -> remove(pack, isActive) }
                .show()
        )
    }

    private fun remove(pack: MapPack, wasActive: Boolean) {
        graph.io().execute {
            val deleted = runCatching { pack.directory.deleteRecursively() }.getOrDefault(false)
            main.post {
                if (!deleted) {
                    Toast.makeText(
                        this,
                        getString(R.string.map_remove_failed, pack.directory.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                if (wasActive) {
                    // Explicit NO_MAP, not an accident: the pointer is dropped
                    // only now, after the driver confirmed.
                    settings.activePackPath = null
                    settings.noMapConfirmed = true
                    graph.invalidatePack()
                }
                Toast.makeText(
                    this,
                    getString(R.string.map_remove_done, pack.metadata.name),
                    Toast.LENGTH_SHORT
                ).show()
                refreshPacks()
            }
        }
    }

    // =======================================================================
    // Import
    // =======================================================================

    /**
     * Opens the system file picker for a `.ts8map` / `.zip`.
     *
     * `ACTION_GET_CONTENT` rather than `ACTION_OPEN_DOCUMENT`: the import is a
     * one-shot copy into app-private storage, so a persistable URI grant buys
     * nothing, and the persistable-grant path is unreliable on vendor ROMs of
     * this vintage.
     */
    private fun pickArchive() {
        if (busy) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/octet-stream")
            )
        }
        val opened = runCatching { startActivityForResult(intent, REQUEST_PICK) }
        if (opened.isFailure) {
            Toast.makeText(this, R.string.qs_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        startImport(uri, data.clipData)
    }

    /**
     * Copies the archive into app-private cache, then hands it to
     * [PackImporter].
     *
     * **Why the copy first.** A content URI from the picker is readable only
     * while the granting Activity is alive and the provider keeps answering; an
     * extraction that takes twenty seconds can outlive that. Copying first also
     * makes the Zip Slip guard meaningful, because the archive is then read from
     * a file this app controls.
     */
    private fun startImport(uri: Uri, clipData: ClipData?) {
        if (busy) return
        busy = true
        // §16's MAP_IMPORT_PROGRESS overlay. Pushed here rather than in the IO
        // callback because the driver can press Back during the copy, and the
        // rule is that Back closes the overlay rather than the screen the
        // import is going to change underneath them.
        runCatching { AppGraph.require().uiState.pushOverlay(AppStateMachine.Overlay.MAP_IMPORT_PROGRESS) }
        showImportStatus(getString(R.string.map_import_running))

        val name = queryDisplayName(uri, clipData) ?: "pack.ts8map"
        val installRoot = File(filesDir, "map-pack")

        graph.io().execute {
            val stage = File(cacheDir, "import-" + name.replace(NOT_SAFE, "-"))
            val copied = runCatching {
                stage.parentFile?.mkdirs()
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("không mở được file đã chọn")
                input.use { source -> stage.outputStream().use { sink -> source.copyTo(sink) } }
                stage
            }.getOrNull()

            if (copied == null) {
                main.post {
                    busy = false
                    popImportOverlay()
                    showImportStatus(getString(R.string.map_import_failed, "không đọc được file"))
                }
                return@execute
            }

            val diagnostics = PackDiagnostics()
            val result = runCatching {
                PackImporter(installRoot).import(
                    archiveName = name,
                    open = { copied.inputStream() },
                    diagnostics = diagnostics
                )
            }.getOrElse { e ->
                PackImporter.Result.Failure(
                    PackFailureCode.INVALID_METADATA,
                    e.message ?: "lỗi không xác định",
                    diagnostics
                )
            }
            copied.delete()

            main.post {
                busy = false
                popImportOverlay()
                when (result) {
                    is PackImporter.Result.Ok -> {
                        // The driver's intent when importing is to use the pack,
                        // so activation is part of the import, not a second tap.
                        settings.activePackPath = result.directory.absolutePath
                        settings.noMapConfirmed = false
                        graph.invalidatePack()
                        val active = graph.pack()
                        showImportStatus(
                            getString(
                                R.string.map_import_done,
                                active?.metadata?.name ?: result.directory.name
                            ) + "\n" + getString(R.string.map_pack_size, formatBytes(result.bytes))
                        )
                        refreshPacks()
                    }
                    is PackImporter.Result.Failure -> {
                        // The old pack is untouched; say so rather than leaving
                        // the driver wondering whether they just lost their map.
                        val hint = when (result.code) {
                            PackFailureCode.INSUFFICIENT_STORAGE ->
                                "\n" + getString(R.string.map_import_no_room)
                            else -> ""
                        }
                        showImportStatus(
                            getString(R.string.map_import_failed, result.message) + hint + "\n" +
                                getString(R.string.map_import_kept_old)
                        )
                        diagText.text = result.diagnostics.report()
                    }
                }
            }
        }
    }

    // =======================================================================
    // Download from server
    // =======================================================================

    /**
     * Saves the server URL the driver typed into [settings].
     *
     * Stored trimmed, without a trailing slash — [PackDownloadClient]
     * handles the separator when building URLs.
     */
    private fun saveServerUrl() {
        val url = serverUrlInput.text.toString().trim()
        settings.mapServerUrl = url
        Toast.makeText(this, R.string.map_download_server_save, Toast.LENGTH_SHORT).show()
    }

    /**
     * Fetches the pack catalog from the server and renders the list.
     *
     * Runs on the IO executor; the catalog is small JSON (a few KB for a
     * dozen packs), so the fetch is quick on any network that can sustain
     * a pack download.
     */
    private fun fetchCatalog() {
        val url = settings.mapServerUrl
        if (url.isBlank()) {
            downloadStatus.visibility = View.VISIBLE
            downloadStatus.text = getString(R.string.map_download_no_server)
            return
        }
        downloadStatus.visibility = View.VISIBLE
        downloadStatus.text = getString(R.string.map_download_fetching)
        catalogList.removeAllViews()

        graph.io().execute {
            val result = graph.packDownloadManager.fetchCatalog(url)
            main.post {
                when (result) {
                    is PackDownloadClient.CatalogResult.Ok -> {
                        if (result.packs.isEmpty()) {
                            downloadStatus.text = getString(R.string.map_download_fetch_empty)
                        } else {
                            downloadStatus.visibility = View.GONE
                        }
                        renderCatalog(result.packs)
                    }
                    is PackDownloadClient.CatalogResult.Error -> {
                        downloadStatus.text = getString(
                            R.string.map_download_fetch_failed, result.message
                        )
                    }
                }
            }
        }
    }

    /**
     * Renders one card per catalog entry, with download size and a
     * "Tải xuống" button. Already-installed packs show "Đã cài".
     */
    private fun renderCatalog(entries: List<PackDownloadClient.CatalogEntry>) {
        catalogList.removeAllViews()
        val installed = packs

        for (entry in entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(R.drawable.bg_chip)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            card.addView(text(entry.name, R.color.text_primary, 14f).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            val details = buildString {
                append(entry.displaySize())
                if (entry.region.isNotBlank()) {
                    append(" · ").append(getString(R.string.map_download_region, entry.region))
                }
                if (entry.version.isNotBlank()) append(" · v").append(entry.version)
                if (entry.hasRouting) append(" · dẫn đường")
                if (entry.hasSearch) append(" · tìm kiếm")
            }
            card.addView(text(details, R.color.text_secondary, 12f))

            if (entry.generatedAt.isNotBlank()) {
                card.addView(text(
                    getString(R.string.map_pack_data_date, entry.generatedAt),
                    R.color.text_tertiary, 11f
                ))
            }

            val isInstalled = graph.packDownloadManager.isInstalled(entry.packId, installed)
            val btn = Button(this).apply {
                isAllCaps = false
                textSize = 13f
                minHeight = dp(36)
                minimumHeight = dp(36)
                setPadding(dp(12), 0, dp(12), 0)
                if (isInstalled) {
                    text = getString(R.string.map_download_installed)
                    isEnabled = false
                    setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    setBackgroundResource(R.drawable.bg_chip)
                } else {
                    text = getString(R.string.map_download_action)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setBackgroundResource(R.drawable.bg_chip)
                    setOnClickListener { startDownload(entry) }
                }
            }
            card.addView(btn)
            catalogList.addView(card)
        }
    }

    /**
     * Starts downloading a pack on the IO executor.
     */
    private fun startDownload(entry: PackDownloadClient.CatalogEntry) {
        val url = settings.mapServerUrl
        if (url.isBlank()) {
            Toast.makeText(this, R.string.map_download_no_server, Toast.LENGTH_SHORT).show()
            return
        }
        graph.io().execute {
            graph.packDownloadManager.download(entry, url)
        }
    }

    /**
     * Cancels the current download. The `.part` file is kept for resume.
     */
    private fun cancelDownload() {
        graph.packDownloadManager.cancel()
    }

    /**
     * Renders the download state, called on the main thread.
     *
     * Shows progress bar during download, "installing" during extraction,
     * success or error on completion.
     */
    private fun renderDownloadState(state: PackDownloadManager.DownloadState) {
        when (state) {
            is PackDownloadManager.DownloadState.Idle -> {
                downloadStatus.visibility = View.GONE
                downloadProgress.visibility = View.GONE
                btnDownloadCancel.visibility = View.GONE
            }
            is PackDownloadManager.DownloadState.Downloading -> {
                downloadStatus.visibility = View.VISIBLE
                downloadProgress.visibility = View.VISIBLE
                btnDownloadCancel.visibility = View.VISIBLE
                val pct = state.percent
                if (pct >= 0) {
                    downloadProgress.progress = pct
                    downloadStatus.text = getString(
                        R.string.map_download_progress, state.packName, pct
                    )
                } else {
                    downloadProgress.isIndeterminate = true
                    downloadStatus.text = getString(
                        R.string.map_download_progress, state.packName, 0
                    )
                }
            }
            is PackDownloadManager.DownloadState.Installing -> {
                downloadStatus.visibility = View.VISIBLE
                downloadProgress.visibility = View.VISIBLE
                downloadProgress.isIndeterminate = true
                btnDownloadCancel.visibility = View.GONE
                downloadStatus.text = getString(
                    R.string.map_download_installing, state.packName
                )
            }
            is PackDownloadManager.DownloadState.Done -> {
                downloadStatus.visibility = View.VISIBLE
                downloadProgress.visibility = View.GONE
                downloadProgress.isIndeterminate = false
                btnDownloadCancel.visibility = View.GONE
                downloadStatus.text = getString(R.string.map_download_done, state.packName)
                // Refresh the installed pack list + catalog (to mark as installed)
                refreshPacks()
                if (settings.mapServerUrl.isNotBlank()) fetchCatalog()
            }
            is PackDownloadManager.DownloadState.Error -> {
                downloadStatus.visibility = View.VISIBLE
                downloadProgress.visibility = View.GONE
                downloadProgress.isIndeterminate = false
                btnDownloadCancel.visibility = View.GONE
                downloadStatus.text = getString(
                    R.string.map_download_error, state.packName, state.message
                )
            }
            is PackDownloadManager.DownloadState.Cancelled -> {
                downloadStatus.visibility = View.VISIBLE
                downloadProgress.visibility = View.GONE
                downloadProgress.isIndeterminate = false
                btnDownloadCancel.visibility = View.GONE
                val name = (graph.packDownloadManager.state as? PackDownloadManager.DownloadState.Cancelled)
                    ?.let { "" } ?: ""
                downloadStatus.text = getString(R.string.map_download_cancelled, name)
                graph.packDownloadManager.reset()
            }
        }
    }

    /**
     * Pops §16's import overlay, whatever the outcome.
     *
     * Called from every terminal branch of [startImport] — success, read
     * failure and pack failure — so the overlay cannot outlive the work it
     * describes. `popOverlay` rather than `popTopOverlay`: a voice bar raised
     * mid-import must not be closed by an import that happens to finish.
     */
    private fun popImportOverlay() {
        runCatching {
            AppGraph.require().uiState.popOverlay(AppStateMachine.Overlay.MAP_IMPORT_PROGRESS)
        }
    }

    /**
     * Back during an import leaves the screen too.
     *
     * The overlay itself is closed by the base class; this only adds the part
     * that is this screen's: a driver who pressed Back on a progress panel
     * wants to be out of Map Management, not staring at a panel that will
     * change a pack behind them. The import itself is not cancelled — it runs
     * on the IO executor and finishes either way, since a half-extracted
     * staging directory is what the importer's own swap step is designed to
     * make harmless.
     */
    override fun onOverlayDismissed() {
        if (busy) finish()
    }

    private fun queryDisplayName(uri: Uri, clipData: ClipData?): String? {
        val fromClip = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val raw = (fromClip ?: uri.lastPathSegment ?: uri.toString()).substringAfterLast('/')
        return raw.substringBefore('?').takeIf { it.isNotBlank() }
    }

    private fun showImportStatus(message: String) {
        importStatus.visibility = View.VISIBLE
        importStatus.text = message
    }

    // =======================================================================
    // Storage + diagnostics
    // =======================================================================

    private fun renderStorage() {
        val root = File(filesDir, "map-pack")
        val free = runCatching { root.usableSpace }.getOrDefault(0L)
        val total = runCatching { root.totalSpace }.getOrDefault(0L)
        storageText.text = getString(R.string.map_storage_location, root.absolutePath) + "\n" +
            getString(R.string.map_free_space, formatBytes(free), formatBytes(total))
    }

    /**
     * Shows the discovery + validation trail.
     *
     * This is the panel a support call reads out loud: candidate roots, the
     * resolved canonical path, the tile query and routing compatibility, in the
     * exact shape `PackDiagnostics.report()` emits. It contains no driver
     * position — a tile z/x/y is a fact about the pack, not about the car.
     */
    private fun renderDiagnostics() {
        val discovery = graph.packDiagnostics()
        val report = graph.packReport()
        val builder = StringBuilder()
        discovery?.let { builder.append(it.report()) }
        if (report != null) {
            builder.appendLine()
            builder.appendLine("--- validation ---")
            builder.appendLine("ok=${report.ok} code=${report.code?.name ?: "none"}")
            builder.appendLine(
                "checksum=${report.checksumOk} mbtiles=${report.mbtilesOk} places=${report.placesOk}"
            )
            builder.appendLine(
                "routing=${report.routingOk} glyphs=${report.glyphsOk} sprite=${report.spriteOk}"
            )
            report.tileQuery?.let { builder.appendLine("tile=$it") }
            report.message?.let { builder.appendLine("message=$it") }
            builder.append(report.diagnostics.report())
        }
        diagText.text = builder.toString().ifBlank { getString(R.string.map_diag_empty) }
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ts8-map-diagnostics", diagText.text))
        Toast.makeText(this, R.string.map_diag_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows/hides the diagnostic log — the file written by [DiagnosticLogger].
     *
     * This is the key tool for debugging on real TS8 hardware where USB
     * debugging does not work: the log captures every step of the map
     * loading pipeline, including the exact reason the style failed.
     */
    private fun toggleDiagLog() {
        val logView = findViewById<TextView>(R.id.logText)
        if (logView.visibility == View.VISIBLE) {
            logView.visibility = View.GONE
            return
        }
        val path = com.ts8.launcher.runtime.DiagnosticLogger.logFilePath()
        val content = com.ts8.launcher.runtime.DiagnosticLogger.tail(150)
        logView.text = if (path != null) {
            getString(R.string.map_diag_log_path, path) + "\n\n" + content
        } else {
            content
        }
        logView.visibility = View.VISIBLE
    }

    private fun rescan() {
        graph.invalidatePack()
        graph.loadPack()
        refreshPacks()
        val pack = graph.pack()
        Toast.makeText(
            this,
            if (pack != null) getString(R.string.map_activate_done, pack.metadata.name)
            else getString(R.string.map_pack_none),
            Toast.LENGTH_SHORT
        ).show()
    }

    // =======================================================================
    // Display group (§8 "Hiển thị")
    // =======================================================================

    private fun buildDisplayGroup() {
        displayGroup.removeAllViews()

        optionRow(displayGroup, R.string.disp_mode, listOf(
            getString(R.string.disp_mode_2d) to AppSettings.MapMode.FLAT_2D,
            getString(R.string.disp_mode_3d) to AppSettings.MapMode.THREE_D,
            getString(R.string.disp_mode_auto) to AppSettings.MapMode.AUTO
        ), settings.mapMode) { settings.mapMode = it }

        optionRow(displayGroup, R.string.disp_theme, listOf(
            getString(R.string.disp_theme_day) to MapStyleMode.DAY,
            getString(R.string.disp_theme_night) to MapStyleMode.NIGHT,
            getString(R.string.disp_theme_auto) to MapStyleMode.AUTO
        ), settings.mapStyleMode) { settings.mapStyleMode = it }

        optionRow(displayGroup, R.string.disp_camera, listOf(
            getString(R.string.disp_camera_north) to AppSettings.CameraMode.NORTH_UP,
            getString(R.string.disp_camera_heading) to AppSettings.CameraMode.HEADING_UP,
            getString(R.string.disp_camera_perspective) to AppSettings.CameraMode.PERSPECTIVE
        ), settings.cameraMode) { settings.cameraMode = it }

        stepperRow(displayGroup, R.string.disp_zoom_default, settings.defaultZoom, 6f, 18f) {
            settings.defaultZoom = it
        }
        stepperRow(displayGroup, R.string.disp_zoom_nav, settings.navigationZoom, 14f, 18f) {
            settings.navigationZoom = it
        }

        toggleRow(displayGroup, R.string.disp_buildings_3d, settings.buildings3d) {
            settings.buildings3d = it
        }
        toggleRow(displayGroup, R.string.disp_labels_road, settings.labelRoads) { settings.labelRoads = it }
        toggleRow(displayGroup, R.string.disp_labels_poi, settings.labelPoi) { settings.labelPoi = it }
        toggleRow(displayGroup, R.string.disp_labels_place, settings.labelPlace) { settings.labelPlace = it }
        toggleRow(displayGroup, R.string.disp_labels_housenumber, settings.labelHouseNumber) {
            settings.labelHouseNumber = it
        }

        optionRow(displayGroup, R.string.disp_poi_density, listOf(
            getString(R.string.disp_poi_low) to AppSettings.PoiDensity.LOW,
            getString(R.string.disp_poi_normal) to AppSettings.PoiDensity.NORMAL,
            getString(R.string.disp_poi_high) to AppSettings.PoiDensity.HIGH
        ), settings.poiDensity) { settings.poiDensity = it }

        optionRow(displayGroup, R.string.disp_text_size, listOf(
            getString(R.string.disp_size_small) to AppSettings.TextScale.SMALL,
            getString(R.string.disp_size_normal) to AppSettings.TextScale.NORMAL,
            getString(R.string.disp_size_large) to AppSettings.TextScale.LARGE
        ), settings.textScale) { settings.textScale = it }

        toggleRow(displayGroup, R.string.disp_route_travelled, settings.showTravelledRoute) {
            settings.showTravelledRoute = it
        }

        // Default off: the driver reported the card appearing over the route
        // panel as a defect. Kept as a switch rather than deleted so the
        // capability is still reachable without a rebuild.
        toggleRow(displayGroup, R.string.disp_music_card, settings.showMusicCardOnMap) {
            settings.showMusicCardOnMap = it
        }

        colourRow(displayGroup, R.string.disp_route_colour, settings.routeColour) {
            settings.routeColour = it
        }
        colourRow(displayGroup, R.string.disp_route_alt_colour, settings.altRouteColour) {
            settings.altRouteColour = it
        }

        toggleRow(displayGroup, R.string.disp_auto_follow, settings.autoFollow) {
            settings.autoFollow = it
        }
        toggleRow(displayGroup, R.string.disp_auto_centre, settings.autoCentre) {
            settings.autoCentre = it
        }

        optionRow(displayGroup, R.string.disp_follow_return, listOf(
            getString(R.string.disp_follow_return_5) to 5,
            getString(R.string.disp_follow_return_10) to 10,
            getString(R.string.disp_follow_return_20) to 20,
            getString(R.string.disp_follow_return_off) to 0
        ), settings.followReturnSeconds) { settings.followReturnSeconds = it }

        toggleRow(displayGroup, R.string.disp_show_zoom, settings.showZoomControls) {
            settings.showZoomControls = it
        }
        toggleRow(displayGroup, R.string.disp_show_compass, settings.showCompass) {
            settings.showCompass = it
        }
        toggleRow(displayGroup, R.string.disp_show_recenter, settings.showRecenter) {
            settings.showRecenter = it
        }
        toggleRow(displayGroup, R.string.disp_show_overview, settings.showOverview) {
            settings.showOverview = it
        }

        // `Cân bằng` is the documented default; the chips make that visible
        // without a separate "reset" step.
        optionRow(displayGroup, R.string.disp_performance, listOf(
            getString(R.string.disp_perf_quality) to AppSettings.Performance.QUALITY,
            getString(R.string.disp_perf_balanced) to AppSettings.Performance.BALANCED,
            getString(R.string.disp_perf_saver) to AppSettings.Performance.SAVER
        ), settings.performance) { settings.performance = it }
    }

    private fun restoreDisplayDefaults() {
        settings.restoreDisplayDefaults()
        buildDisplayGroup()
        Toast.makeText(this, R.string.disp_restored, Toast.LENGTH_SHORT).show()
    }

    // =======================================================================
    // Speed group (§8 "Tốc độ & cảnh báo")
    // =======================================================================

    private fun buildSpeedGroup() {
        speedGroup.removeAllViews()

        optionRow(speedGroup, R.string.speed_unit, listOf(
            getString(R.string.speed_unit_kmh) to SpeedUnit.KMH,
            getString(R.string.speed_unit_mph) to SpeedUnit.MPH
        ), settings.speedUnit) { settings.speedUnit = it }

        toggleRow(speedGroup, R.string.speed_show_current, settings.showCurrentSpeed) {
            settings.showCurrentSpeed = it
        }
        toggleRow(speedGroup, R.string.speed_show_limit, settings.showSpeedLimit) {
            settings.showSpeedLimit = it
        }

        optionRow(speedGroup, R.string.speed_gps_filter, listOf(
            getString(R.string.speed_gps_filter_off) to 0,
            "2 km/h" to 2,
            "4 km/h" to 4,
            "6 km/h" to 6
        ), settings.gpsFilterKmh) { settings.gpsFilterKmh = it }

        // The cluster is the reference the driver compares against, so the
        // offset is expressed as "add this to the GPS number". A stepper rather
        // than chips: the useful range is every integer in -20..+20 and 41
        // chips would not fit the panel.
        stepperRow(
            speedGroup, R.string.speed_gps_offset,
            settings.gpsSpeedOffsetKmh.toFloat(), -20f, 20f, " km/h"
        ) { settings.gpsSpeedOffsetKmh = it.toInt() }

        optionRow(speedGroup, R.string.speed_smoothing, listOf(
            getString(R.string.speed_smooth_fast) to SpeedFilter.Smoothing.FAST,
            getString(R.string.speed_smooth_balanced) to SpeedFilter.Smoothing.BALANCED,
            getString(R.string.speed_smooth_smooth) to SpeedFilter.Smoothing.SMOOTH
        ), settings.speedSmoothing) { settings.speedSmoothing = it }

        toggleRow(speedGroup, R.string.speed_alerts, settings.speedAlertsEnabled) {
            settings.speedAlertsEnabled = it
        }

        optionRow(speedGroup, R.string.speed_threshold, listOf(
            getString(R.string.speed_threshold_0) to 0,
            getString(R.string.speed_threshold_5) to 5,
            getString(R.string.speed_threshold_10) to 10,
            getString(R.string.speed_threshold_15) to 15
        ), settings.overspeedThresholdKmh) { settings.overspeedThresholdKmh = it }

        optionRow(speedGroup, R.string.speed_alert_form, listOf(
            getString(R.string.speed_alert_visual) to SpeedMonitor.AlertForm.VISUAL,
            getString(R.string.speed_alert_audio) to SpeedMonitor.AlertForm.AUDIO,
            getString(R.string.speed_alert_both) to SpeedMonitor.AlertForm.BOTH
        ), settings.speedAlertForm) { settings.speedAlertForm = it }

        stepperRow(
            speedGroup,
            R.string.speed_alert_volume,
            settings.speedAlertVolume * 100f,
            0f, 100f,
            suffix = "%"
        ) { settings.speedAlertVolume = it / 100f }

        optionRow(speedGroup, R.string.speed_cooldown, listOf(
            getString(R.string.speed_cooldown_5) to 5_000L,
            getString(R.string.speed_cooldown_15) to 15_000L,
            getString(R.string.speed_cooldown_30) to 30_000L,
            getString(R.string.speed_cooldown_60) to 60_000L
        ), settings.speedAlertCooldownMs) { settings.speedAlertCooldownMs = it }

        toggleRow(speedGroup, R.string.speed_sustained, settings.sustainedOverOnly) {
            settings.sustainedOverOnly = it
        }

        // Live status: the limit provenance, so the driver can tell "no data"
        // from "broken". `--` on the HUD and this line are the only two honest
        // answers when the pack has no limit for the road.
        val source = if (!settings.showSpeedLimit) "ẩn" else speedLimitProvenance()
        speedGroup.addView(text(
            getString(R.string.speed_state, if (settings.speedAlertsEnabled) "BẬT" else "TẮT") +
                "\n" + getString(R.string.speed_limit_source, source),
            R.color.text_tertiary, 12f
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    /**
     * Where a limit could come from, in words.
     *
     * Deliberately not a number: the resolver's value depends on where the car
     * is, and inventing one here would be exactly the guesswork the brief bans.
     * The confidence wording matches [com.ts8.core.speed.SpeedLimitResolver].
     */
    private fun speedLimitProvenance(): String {
        val report = graph.packReport()
        val hasRouting = report?.routingOk ?: (graph.pack()?.routingDir() != null)
        return if (hasRouting) {
            "dữ liệu route/map (tin cậy trung bình khi chỉ có mặc định theo loại đường)"
        } else {
            "không có — hiển thị --"
        }
    }

    // =======================================================================
    // Row builders
    // =======================================================================

    private fun label(target: LinearLayout, resId: Int) {
        target.addView(text(getString(resId), R.color.text_secondary, 13f).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
    }

    /**
     * A segmented control: one chip per option, exactly one selected.
     *
     * Built from `TextView`s rather than a `RadioGroup` because the design uses
     * pill chips, not radio buttons, and a horizontal chip row fits four options
     * within 1024 dp without wrapping.
     */
    private fun <T> optionRow(
        target: LinearLayout,
        titleRes: Int,
        options: List<Pair<String, T>>,
        selected: T,
        onSelect: (T) -> Unit
    ) {
        label(target, titleRes)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        for ((labelText, value) in options) {
            row.addView(chip(labelText, value == selected) {
                onSelect(value)
                rebuild()
            })
        }
        target.addView(row)
    }

    /**
     * A two-way switch drawn as chips.
     *
     * Not a `Switch`: the platform widget on this ROM renders in the theme's
     * disabled colour and is nearly invisible on the dark card. Chips keep the
     * 44 dp touch target and read at a glance.
     */
    private fun toggleRow(
        target: LinearLayout,
        titleRes: Int,
        value: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        label(target, titleRes)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(chip(getString(R.string.qs_on), value) {
            onChange(true)
            rebuild()
        })
        row.addView(chip(getString(R.string.qs_off), !value) {
            onChange(false)
            rebuild()
        })
        target.addView(row)
    }

    /** A numeric stepper with minus/plus chips and the live value between them. */
    private fun stepperRow(
        target: LinearLayout,
        titleRes: Int,
        value: Float,
        min: Float,
        max: Float,
        suffix: String = "",
        onChange: (Float) -> Unit
    ) {
        label(target, titleRes)
        val step = if (max <= 20f) 1f else 10f
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val display = text(formatNumber(value) + suffix, R.color.text_primary, 14f).apply {
            minWidth = dp(64)
            gravity = Gravity.CENTER
        }
        row.addView(chip("−", false) {
            onChange((value - step).coerceAtLeast(min))
            rebuild()
        })
        row.addView(display)
        row.addView(chip("+", false) {
            onChange((value + step).coerceAtMost(max))
            rebuild()
        })
        target.addView(row)
    }

    /**
     * A fixed palette rather than a free picker.
     *
     * A full HSV picker is a second screen, a second Activity result and a
     * colour that can be invisible against the map. Four presets chosen for
     * contrast on both day and night palettes cover the actual need.
     */
    private fun colourRow(
        target: LinearLayout,
        titleRes: Int,
        current: Int,
        onChange: (Int) -> Unit
    ) {
        label(target, titleRes)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        for ((name, colour) in COLOUR_PRESETS) {
            row.addView(chip(name, colour == current) {
                onChange(colour)
                rebuild()
            })
        }
        target.addView(row)
    }

    private fun chip(labelText: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = labelText
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(40)
            setTextColor(
                ContextCompat.getColor(
                    this@MapManagementActivity,
                    if (selected) R.color.text_on_accent else R.color.text_secondary
                )
            )
            setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_chip)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun rebuild() {
        buildDisplayGroup()
        buildSpeedGroup()
    }

    // =======================================================================
    // Small helpers
    // =======================================================================

    private fun text(value: String, colourRes: Int, sizeSp: Float): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(ContextCompat.getColor(this@MapManagementActivity, colourRes))
        }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(
            Locale.US,
            if (value >= 100) "%.0f %s" else "%.1f %s",
            value, units[unit]
        )
    }

    private fun formatNumber(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(Locale.US, "%.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK = 0x7A01

        /** How long to wait before re-reading the validation report. */
        private const val REFRESH_AFTER_VALIDATION_MS = 500L

        /** Characters unsafe in a cache file name. */
        private val NOT_SAFE = Regex("[^A-Za-z0-9._-]")

        /** Route-line presets: readable on both palette halves. */
        private val COLOUR_PRESETS = listOf(
            "Xanh dương" to 0xFF3B82F6.toInt(),
            "Xanh lá" to 0xFF22C55E.toInt(),
            "Cam" to 0xFFF59E0B.toInt(),
            "Xám" to 0xFF6C7A91.toInt()
        )

        fun intentTo(context: Context): Intent =
            Intent(context, MapManagementActivity::class.java)

        /**
         * Opens Map Management from anywhere, and never throws.
         *
         * §6 requires four entry points — the map error surface, Settings >
         * Bản đồ, the app drawer and Quick Settings. Each of them was written
         * against an Activity that was not in the manifest, so each of them
         * threw `ActivityNotFoundException` and took its caller down with it.
         * The declaration fixes the cause; this helper is what makes the fix
         * survive a future rename — a caller that cannot resolve the Activity
         * gets a `false` back rather than a crash in the middle of a screen the
         * driver is using.
         *
         * Returns true when an Activity was actually started.
         */
        fun start(context: Context): Boolean = runCatching {
            context.startActivity(
                intentTo(context).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            true
        }.getOrDefault(false)
    }
}
