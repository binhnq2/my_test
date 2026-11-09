package com.ts8.core.map.maplibre

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the full pack download pipeline: catalog → download → import.
 *
 * **Why a separate class from [PackDownloadClient].** The client is a dumb
 * HTTP pipe — it fetches bytes and checks a hash. This class owns the
 * state machine: which pack is being downloaded, where the temp file goes,
 * how to hand the result to [PackImporter], and how to report progress to
 * the UI. Keeping them separate means the client is testable without an
 * Android Context, and the manager is testable with a fake client.
 *
 * **Lifecycle.** A single instance lives in [AppGraph] for the app's lifetime.
 * Only one download at a time is allowed — a TS8 unit on Wi-Fi can barely
 * sustain one download, and two would split the bandwidth and double the
 * chance of an ignition-cut leaving both packs half-installed.
 *
 * **Flow:**
 * 1. [fetchCatalog] — asks the client for the server's pack list
 * 2. [download] — downloads the `.ts8map` to a temp file, then hands it to
 *    [PackImporter] for extraction + validation + activation
 * 3. Progress flows through [DownloadState] to the UI via a callback
 *
 * **Storage.** Temp files go in the app's cache dir (`context.cacheDir/ts8-downloads/`).
 * The cache dir is auto-cleaned by the OS under storage pressure, which is
 * correct — a half-downloaded `.part` file is disposable.
 */
class PackDownloadManager(
    private val context: Context,
    private val importer: PackImporter,
    private val clientFactory: (String) -> PackDownloadClient = { url ->
        PackDownloadClient(url)
    }
) {

    companion object {
        private const val TAG = "PackDownloadManager"
        private const val DOWNLOAD_DIR = "ts8-downloads"
    }

    /**
     * The current state of the download pipeline, observable by the UI.
     *
     * The UI shows:
     * - `Idle` — the catalog list with a "Tải xuống" button per pack
     * - `Downloading` — a progress bar and a "Huỷ" button
     * - `Installing` — "Đang cài đặt…" (no progress bar; extraction is fast)
     * - `Done` — "Đã cài xong" + auto-refresh the installed pack list
     * - `Error` — the error message + "Thử lại" button
     */
    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(
            val packId: String,
            val packName: String,
            val bytesReceived: Long,
            val totalBytes: Long
        ) : DownloadState() {
            /** 0..100 for the progress bar. */
            val percent: Int
                get() = if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt()
                        else -1
        }
        data class Installing(val packId: String, val packName: String) : DownloadState()
        data class Done(val packId: String, val packName: String, val directory: java.io.File) : DownloadState()
        data class Error(val packId: String, val packName: String, val message: String) : DownloadState()
        data object Cancelled : DownloadState()
    }

    @Volatile
    var state: DownloadState = DownloadState.Idle
        private set

    /** Callback fired on every state change. Set by the UI; called on the IO thread. */
    @Volatile
    var stateListener: ((DownloadState) -> Unit)? = null

    private val cancelFlag = AtomicBoolean(false)

    /** True while a download is in progress; blocks starting a second one. */
    val isBusy: Boolean get() = cancelFlag.get() || state !is DownloadState.Idle

    private fun publish(s: DownloadState) {
        state = s
        stateListener?.invoke(s)
    }

    /**
     * Fetches the catalog from the server.
     *
     * @param serverUrl the base URL, e.g. `https://maps.ts8.vn/packs`
     */
    fun fetchCatalog(serverUrl: String): PackDownloadClient.CatalogResult {
        val client = clientFactory(serverUrl)
        return client.fetchCatalog()
    }

    /**
     * Downloads and installs a pack.
     *
     * Must be called on a background thread (the caller should use
     * [AppGraph.io]). Publishes [DownloadState] updates to [stateListener].
     *
     * @param entry the catalog entry to download
     * @param serverUrl the base URL for the download client
     */
    fun download(entry: PackDownloadClient.CatalogEntry, serverUrl: String) {
        if (isBusy) {
            Log.w(TAG, "download requested but busy, ignoring")
            return
        }

        cancelFlag.set(false)
        val dir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        val destFile = File(dir, entry.fileName)

        val client = clientFactory(serverUrl)

        // Phase 1: download
        publish(DownloadState.Downloading(
            entry.packId, entry.name, 0, entry.sizeBytes
        ))

        val result = client.download(
            entry = entry,
            destFile = destFile,
            expectedSha256 = entry.sha256,
            progress = { received, total ->
                publish(DownloadState.Downloading(
                    entry.packId, entry.name, received, total
                ))
            },
            isCancelled = { cancelFlag.get() }
        )

        when (result) {
            is PackDownloadClient.DownloadResult.Ok -> {
                Log.i(TAG, "download OK, importing ${result.file}")
            }
            is PackDownloadClient.DownloadResult.Cancelled -> {
                publish(DownloadState.Cancelled)
                return
            }
            is PackDownloadClient.DownloadResult.Error -> {
                publish(DownloadState.Error(
                    entry.packId, entry.name, result.message
                ))
                return
            }
        }

        // Phase 2: install (extract + validate + activate)
        publish(DownloadState.Installing(entry.packId, entry.name))

        val diagnostics = PackDiagnostics()
        val importResult = try {
            importer.import(
                archiveName = entry.fileName,
                open = { FileInputStream(result.file) },
                diagnostics = diagnostics
            )
        } catch (e: CancellationException) {
            publish(DownloadState.Cancelled)
            return
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            publish(DownloadState.Error(
                entry.packId, entry.name, "Lỗi cài đặt: ${e.message}"
            ))
            return
        }

        // Clean up the downloaded archive regardless of import outcome.
        result.file.delete()

        when (importResult) {
            is PackImporter.Result.Ok -> {
                Log.i(TAG, "import OK: ${importResult.directory}")
                publish(DownloadState.Done(
                    entry.packId, entry.name, importResult.directory
                ))
            }
            is PackImporter.Result.Failure -> {
                Log.e(TAG, "import failed: ${importResult.code} ${importResult.message}")
                publish(DownloadState.Error(
                    entry.packId, entry.name, importResult.message
                ))
            }
        }
    }

    /**
     * Cancels the current download. The `.part` file is preserved for resume.
     * Returns immediately; the download thread will publish [DownloadState.Cancelled]
     * when it actually stops.
     */
    fun cancel() {
        cancelFlag.set(true)
    }

    /** Resets to [DownloadState.Idle], clearing any error or done state. */
    fun reset() {
        cancelFlag.set(false)
        publish(DownloadState.Idle)
    }

    /**
     * Checks whether a pack with [packId] is already installed, so the UI can
     * show "Đã cài" instead of "Tải xuống".
     */
    fun isInstalled(packId: String, installedPacks: List<MapPack>): Boolean {
        return installedPacks.any { it.metadata.packId == packId }
    }
}
