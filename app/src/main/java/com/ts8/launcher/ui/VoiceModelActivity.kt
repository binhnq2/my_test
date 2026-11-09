package com.ts8.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ts8.core.voice.offline.VoiceModelManifest
import com.ts8.core.voice.offline.VoiceModelState
import com.ts8.core.voice.offline.VoiceModelStore
import com.ts8.core.voice.offline.VoiceModelValidator
import com.ts8.core.voice.offline.totalBytes
import com.ts8.launcher.R
import com.ts8.launcher.di.AppGraph
import java.io.File
import java.util.Locale

/**
 * Voice Model Management (§4 of the voice pilot).
 *
 * **What this screen has to make possible**, in the order the brief lists it:
 * see the state of the installed pack; import one from USB — either a `.zip`
 * or an already-extracted folder; replace it; remove it. Nothing here downloads
 * anything, and after a successful install the unit never needs the network to
 * recognise speech again.
 *
 * **Why the store, and not this Activity, is the state authority.** Every row
 * below is drawn from [VoiceModelStore.Snapshot] — the same object the router
 * consults before choosing a backend and the same one Diagnostics exports. A
 * screen that decided for itself whether the model "looks fine" would disagree
 * with the recogniser exactly in the case that matters: a pack that validates
 * but the native loader refuses.
 *
 * **Why validation runs off the main thread.** [VoiceModelStore.install]
 * hashes every file in a 51 MB pack, which is seconds on a Cortex-A7. The one
 * thing this screen must not do is freeze the panel while the driver waits —
 * §11's performance gate names an ANR as a failure, not a slow frame.
 *
 * **Why the driver gets a confirmation on removal.** A voice model is the only
 * way this unit hears Vietnamese. Deleting it without a question is the same
 * class of mistake as deleting the active map pack, and the campaign rule
 * against destroying unconfirmed state applies to both.
 */
class VoiceModelActivity : BaseScreenActivity() {

    /** Not a tab: opened from Settings, Back returns there. */
    override val screen: BottomNavView.Screen = BottomNavView.Screen.NONE

    /** §16's machine has no voice-model state; this screen claims none. */
    override val primaryState: com.ts8.launcher.runtime.AppStateMachine.State? = null

    private lateinit var graph: AppGraph
    private lateinit var store: VoiceModelStore

    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var reasonText: TextView
    private lateinit var statusText: TextView
    private lateinit var foundList: LinearLayout
    private lateinit var actionRow: LinearLayout

    private val main = Handler(Looper.getMainLooper())

    /** True while an import or a validation is running; blocks a second one. */
    private var busy = false

    /** The last snapshot rendered, so the action row matches the state on screen. */
    private var snapshot: VoiceModelStore.Snapshot = VoiceModelStore.Snapshot(
        VoiceModelState.NOT_INSTALLED
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_model)
        bindNavigation()

        graph = AppGraph.require()
        store = graph.voiceModelStore

        stateText = findViewById(R.id.voiceModelState)
        detailText = findViewById(R.id.voiceModelDetail)
        reasonText = findViewById(R.id.voiceModelReason)
        statusText = findViewById(R.id.voiceModelStatus)
        foundList = findViewById(R.id.voiceModelFound)
        actionRow = findViewById(R.id.voiceModelActions)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnImportZip).setOnClickListener { pickArchive() }
        findViewById<View>(R.id.btnScan).setOnClickListener { scan() }

        // The snapshot is re-derived from disk on the IO pool: a pack the driver
        // copied in over USB while Settings was open is not in the cached
        // verdict, and the cache is the one the router trusts.
        revalidate()
    }

    // -- rendering -----------------------------------------------------------

    /**
     * Re-validates the active pack and re-renders.
     *
     * Runs on [AppGraph.io] because it hashes the pack. The render happens on
     * the main thread afterwards; a snapshot that arrived after the screen went
     * away is dropped rather than drawn into a dead window.
     */
    private fun revalidate() {
        if (busy) return
        busy = true
        renderBusy(true)
        graph.io().execute {
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.revalidate: calling store.refresh()")
            val fresh = runCatching { store.refresh() }.getOrNull()
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.revalidate: state=${fresh?.state ?: "null"}")
            if (fresh?.directory != null) {
                val dir = fresh.directory!!
                com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.revalidate: dir=${dir.absolutePath}")
            }
            main.post {
                busy = false
                renderBusy(false)
                if (isFinishing || isDestroyed) return@post
                render(fresh ?: store.snapshot())
            }
        }
    }

    private fun render(snapshot: VoiceModelStore.Snapshot) {
        this.snapshot = snapshot
        val manifest = snapshot.manifest

        stateText.text = getString(R.string.settings_voice_model_state, stateLabel(snapshot.state))
        stateText.setTextColor(
            ContextCompat.getColor(
                this,
                if (snapshot.ready) R.color.success else R.color.text_primary
            )
        )

        // Identity block. Every line comes from the manifest the validator
        // accepted, so a support call reading the screen reads the same values
        // the diagnostics file carries.
        val detail = buildList {
            if (manifest != null) {
                add(getString(R.string.voice_model_version, manifest.modelVersion))
                if (manifest.size > 0L) {
                    add(getString(R.string.voice_model_size, formatBytes(manifest.size)))
                }
                add(getString(R.string.voice_model_license, manifest.license))
                add(getString(R.string.voice_model_abi, manifest.supportedAbis.joinToString(", ")))
                add(getString(R.string.voice_model_checksum, manifest.sha256.take(16) + "…"))
                add(getString(R.string.voice_model_source, manifest.sourceUrl))
            }
        }
        if (detail.isEmpty()) {
            detailText.visibility = View.GONE
        } else {
            detailText.visibility = View.VISIBLE
            detailText.text = detail.joinToString("\n")
        }

        // The verdict's own sentence. Shown for every non-READY state and for
        // none of the others: on a working unit this row would be noise, and on
        // a broken one it is the only actionable line on the screen.
        val reason = snapshot.reason
        if (snapshot.ready || reason.isNullOrBlank()) {
            reasonText.visibility = View.GONE
        } else {
            reasonText.visibility = View.VISIBLE
            reasonText.text = getString(R.string.voice_model_reason, reason)
            reasonText.setTextColor(ContextCompat.getColor(this, R.color.danger))
        }

        renderActions(snapshot)
    }

    /**
     * The buttons, rebuilt per state.
     *
     * Remove is offered only when there is a directory to remove — including
     * the FAILED case, where the pack is on disk but the loader refused it and
     * reinstalling is the fix. Install from a found folder is added by
     * [renderFound], because it depends on what the scan found rather than on
     * the state.
     */
    private fun renderActions(snapshot: VoiceModelStore.Snapshot) {
        actionRow.removeAllViews()
        actionRow.addView(actionButton(getString(R.string.voice_model_rescan)) { revalidate() })
        if (snapshot.directory != null || snapshot.state == VoiceModelState.READY) {
            actionRow.addView(
                actionButton(getString(R.string.voice_model_remove)) { confirmRemove() }
            )
        }
    }

    private fun renderBusy(waiting: Boolean) {
        if (waiting) {
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.voice_model_import_running)
        }
    }

    private fun showStatus(message: String, failure: Boolean = false) {
        statusText.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(
            ContextCompat.getColor(this, if (failure) R.color.danger else R.color.text_secondary)
        )
    }

    private fun stateLabel(state: VoiceModelState): String = getString(
        when (state) {
            VoiceModelState.NOT_INSTALLED -> R.string.voice_model_state_not_installed
            VoiceModelState.VALIDATING -> R.string.voice_model_state_validating
            VoiceModelState.READY -> R.string.voice_model_state_ready
            VoiceModelState.INCOMPATIBLE -> R.string.voice_model_state_incompatible
            VoiceModelState.CORRUPT -> R.string.voice_model_state_corrupt
            VoiceModelState.FAILED -> R.string.voice_model_state_failed
        }
    )

    // -- zip import ----------------------------------------------------------

    /**
     * Opens the system picker for a `.zip`.
     *
     * `ACTION_GET_CONTENT` rather than `ACTION_OPEN_DOCUMENT` for the same
     * reason Map Management uses it: the import is a one-shot copy into
     * app-private storage, and persistable-grant handling is unreliable on
     * ROMs of this vintage. The URI is copied to a file first, so a provider
     * that stops answering mid-extraction cannot produce a half-installed pack.
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
        importArchive(uri, data.clipData)
    }

    private fun importArchive(uri: Uri, clipData: android.content.ClipData?) {
        if (busy) return
        busy = true
        renderBusy(true)

        val name = queryDisplayName(uri, clipData) ?: "voice-model.zip"
        graph.io().execute {
            val staged = File(cacheDir, "voice-model-" + name.replace(NOT_SAFE, "-"))
            val copied = runCatching {
                staged.parentFile?.mkdirs()
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("không mở được tệp đã chọn")
                input.use { source -> staged.outputStream().use { sink -> source.copyTo(sink) } }
                staged
            }.getOrNull()

            if (copied == null) {
                main.post {
                    busy = false
                    if (isFinishing || isDestroyed) return@post
                    render(snapshot)
                    showStatus(
                        getString(
                            R.string.voice_model_import_failed,
                            "không đọc được tệp"
                        ),
                        failure = true
                    )
                }
                return@execute
            }

            // `importZip` extracts to staging, validates, and only then writes
            // the active marker. A failure leaves the working pack untouched.
            val result = runCatching {
                store.importZip(name) { copied.inputStream() }
            }.getOrElse { e ->
                VoiceModelStore.ImportResult.Failed(
                    VoiceModelState.CORRUPT,
                    "lỗi không xác định: ${e.message ?: e.javaClass.simpleName}",
                    VoiceModelState.VALIDATING
                )
            }
            copied.delete()

            main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                when (result) {
                    is VoiceModelStore.ImportResult.Ok -> {
                        // The recogniser caches the loaded model; without this
                        // it keeps decoding with the pack the driver just
                        // replaced until the process restarts.
                        runCatching { graph.voiceInstant() }
                        runCatching { graph.onVoiceModelChanged() }
                        render(result.snapshot)
                        showStatus(
                            getString(
                                R.string.voice_model_import_done,
                                result.snapshot.manifest?.modelVersion
                                    ?: result.snapshot.directory?.name.orEmpty()
                            )
                        )
                    }
                    is VoiceModelStore.ImportResult.Failed -> {
                        render(store.snapshot())
                        showStatus(
                            getString(R.string.voice_model_import_failed, result.reason) +
                                "\n" + getString(R.string.voice_model_kept_old),
                            failure = true
                        )
                    }
                }
            }
        }
    }

    /**
     * The picker's display name, falling back to the URI's last segment.
     *
     * Used for the staging directory name and nothing else — the pack's
     * identity is `metadata.json`'s, never the archive's.
     */
    private fun queryDisplayName(uri: Uri, clipData: android.content.ClipData?): String? {
        val fromClip = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val raw = (fromClip ?: uri.lastPathSegment ?: uri.toString()).substringAfterLast('/')
        return raw.substringBefore('?').takeIf { it.isNotBlank() }
    }

    // -- folder scan ---------------------------------------------------------

    /**
     * Walks the roots a driver actually copies voice models to, off the main thread.
     *
     * **Why this uses [AppGraph.voiceModelSearchRoots] and not the old
     * `packSearchRoots`.** The old scan reused the map-pack search roots,
     * stripped the last path component, and walked the result looking for
     * `metadata.json`. That found *map pack* manifests — which declare
     * `formatVersion` and `basemap`, not `engine: vosk` — and listed them
     * as voice models. The driver saw the map pack folder, tapped Install,
     * and it failed validation because there is no `model/am/final.mdl`
     * in a map pack. The scan now walks voice-model-specific paths.
     *
     * **Why a scan rather than a file picker for folders.** API 23 has no
     * directory picker in the framework this ROM ships, and
     * `ACTION_OPEN_DOCUMENT_TREE` arrived in API 24. The Vosk model packs
     * arrive as one top-level directory, and most drivers copy that folder
     * straight onto a stick, so walking the same roots the map importer
     * walks is what makes "import from USB" real here.
     */
    private fun scan() {
        if (busy) return
        busy = true
        renderBusy(true)
        graph.io().execute {
            val roots = runCatching { graph.voiceModelSearchRoots() }
                .getOrDefault(emptyList())
                .distinct()
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.scan: ${roots.size} roots")
            for (root in roots) {
                com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.scan: root=${root.absolutePath} exists=${root.isDirectory}")
            }
            val found = LinkedHashMap<String, File>()
            for (root in roots) {
                collectPacks(root, 0, found)
            }
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.scan: found ${found.size} model(s)")
            for (dir in found.values) {
                com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.scan: found model at ${dir.absolutePath}")
            }
            main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                render(snapshot)
                renderFound(found.values.toList())
            }
        }
    }

    /**
     * Finds directories holding a voice model `metadata.json`, at most three
     * levels down.
     *
     * **Why the `metadata.json` is checked for `engine`.** A map pack also
     * has a `metadata.json` — but it declares `formatVersion` and `basemap`,
     * not `engine: "vosk"`. Without this check the scan would list map pack
     * directories as voice models, and every Install would fail validation.
     * The check is cheap (one file read + string contains) and runs only
     * when a `metadata.json` is actually found, so it does not slow the walk.
     */
    private fun collectPacks(dir: File, depth: Int, out: MutableMap<String, File>) {
        val exists = runCatching { dir.isDirectory }.getOrDefault(false)
        if (!exists) return
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (out.containsKey(canonical)) return

        val manifest = File(dir, VoiceModelValidator.MANIFEST_NAME)
        if (manifest.isFile) {
            // Verify this is a voice model manifest, not a map pack manifest.
            // A voice model declares `"engine": "vosk"` and `"locale": "vi-VN"`.
            // A map pack declares `"formatVersion"` and `"basemap"`.
            val isVoiceModel = runCatching {
                val text = manifest.readText(Charsets.UTF_8)
                text.contains("\"engine\"") && text.contains("\"vosk\"")
            }.getOrDefault(false)
            if (isVoiceModel) {
                out[canonical] = dir
            }
            return
        }
        // A pack is one of two shallow layouts on a stick:
        // `<root>/<version>/` or `<root>/<folder>/<version>/`.
        if (depth >= SCAN_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: return
        for (child in children) {
            // Skip the trees that never hold a pack and are expensive to walk.
            if (child.name in SKIP_DIRS) continue
            collectPacks(child, depth + 1, out)
        }
    }

    /**
     * Lists what the scan found, each with an Install button.
     *
     * Installing from a folder re-uses [VoiceModelStore.install], which moves
     * the directory. That is deliberate: a copy would double a 51 MB pack on
     * the same small eMMC, and the driver's USB copy is the master.
     */
    private fun renderFound(found: List<File>) {
        foundList.removeAllViews()
        if (found.isEmpty()) {
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.voice_model_scan_empty)
            statusText.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
            return
        }
        showStatus(getString(R.string.voice_model_scan_found, found.size))

        for (dir in found) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundResource(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            card.addView(text(dir.name, R.color.text_primary, 15f))
            card.addView(text(dir.absolutePath, R.color.text_tertiary, 11f))
            val size = runCatching { dir.totalBytes() }.getOrDefault(0L)
            card.addView(text(formatBytes(size), R.color.text_secondary, 12f))
            card.addView(
                actionButton(getString(R.string.voice_model_install)) { installFolder(dir) }
            )
            foundList.addView(card)
        }
    }

    private fun installFolder(dir: File) {
        if (busy) return
        busy = true
        renderBusy(true)
        com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.install: installing from ${dir.absolutePath}")
        graph.io().execute {
            val staged = File(cacheDir, "voice-stage-" + dir.name.replace(NOT_SAFE, "-"))
            com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.install: copying to staging ${staged.absolutePath}")
            val result = runCatching {
                staged.deleteRecursively()
                dir.copyRecursively(staged, overwrite = true)
                com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.install: copy done, calling store.install()")
                store.install(staged)
            }.getOrElse { e ->
                com.ts8.launcher.runtime.DiagnosticLogger.error("VoiceModel.install: exception: ${e.message}", e)
                VoiceModelStore.ImportResult.Failed(
                    VoiceModelState.FAILED,
                    "lỗi không xác định: ${e.message ?: e.javaClass.simpleName}",
                    VoiceModelState.VALIDATING
                )
            }
            runCatching { staged.deleteRecursively() }
            when (result) {
                is VoiceModelStore.ImportResult.Ok ->
                    com.ts8.launcher.runtime.DiagnosticLogger.log("VoiceModel.install: OK, version=${result.snapshot.manifest?.modelVersion}")
                is VoiceModelStore.ImportResult.Failed ->
                    com.ts8.launcher.runtime.DiagnosticLogger.error("VoiceModel.install: FAILED, reason=${result.reason}")
            }

            main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                when (result) {
                    is VoiceModelStore.ImportResult.Ok -> {
                        runCatching { graph.voiceInstant() }
                        runCatching { graph.onVoiceModelChanged() }
                        render(result.snapshot)
                        foundList.removeAllViews()
                        showStatus(
                            getString(
                                R.string.voice_model_import_done,
                                result.snapshot.manifest?.modelVersion ?: dir.name
                            )
                        )
                    }
                    is VoiceModelStore.ImportResult.Failed -> {
                        render(store.snapshot())
                        showStatus(
                            getString(R.string.voice_model_import_failed, result.reason) +
                                "\n" + getString(R.string.voice_model_kept_old),
                            failure = true
                        )
                    }
                }
            }
        }
    }

    // -- removal -------------------------------------------------------------

    /**
     * Confirms, then removes.
     *
     * The dialog states what stops working rather than what is deleted: "các
     * lệnh giọng nói sẽ không hoạt động" is the consequence the driver is
     * agreeing to, and the campaign rule against unconfirmed destruction is
     * what the confirmation is for.
     */
    private fun confirmRemove() {
        showTrackedDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_model_remove_title)
                .setMessage(R.string.voice_model_remove_body)
                .setNegativeButton(R.string.generic_cancel, null)
                .setPositiveButton(R.string.voice_model_remove) { _, _ -> removeModel() }
                .show()
        )
    }

    private fun removeModel() {
        if (busy) return
        busy = true
        graph.io().execute {
            val cleared = runCatching { store.remove(confirmed = true) }
                .getOrElse { store.snapshot() }
            main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                // The provider must drop the resident model: a removed pack that
                // is still in memory would keep answering commands for a model
                // the driver just uninstalled.
                runCatching { graph.onVoiceModelChanged() }
                render(cleared)
                foundList.removeAllViews()
                showStatus(getString(R.string.voice_model_removed))
            }
        }
    }

    // -- small helpers -------------------------------------------------------

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
            ).apply {
                marginEnd = dp(8)
                topMargin = dp(8)
            }
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun text(value: String, colourRes: Int, sizeSp: Float): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(ContextCompat.getColor(this@VoiceModelActivity, colourRes))
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK = 0x7A02

        /** Deep enough for `<root>/<folder>/<version>/`, shallow enough to be fast. */
        private const val SCAN_DEPTH = 3

        /** Characters unsafe in a cache file name. */
        private val NOT_SAFE = Regex("[^A-Za-z0-9._-]")

        /** Trees that never hold a model and are expensive to walk. */
        private val SKIP_DIRS = setOf(
            "Android", "DCIM", "Movies", "Music", "Pictures", "Podcasts", "Ringtones"
        )

        /** Opens Voice Model Management from Settings. */
        fun intentTo(context: android.content.Context): Intent =
            Intent(context, VoiceModelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
