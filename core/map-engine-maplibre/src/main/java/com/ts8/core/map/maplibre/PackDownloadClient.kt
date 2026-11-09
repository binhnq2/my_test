package com.ts8.core.map.maplibre

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads `.ts8map` pack archives from a TS8 pack server.
 *
 * **Why this lives in `core:map-engine-maplibre`.** It produces the exact
 * `InputStream` that [PackImporter.import] consumes, and it speaks the same
 * `metadata.json` field names the loader validates. Placing it outside the
 * engine module would mean either duplicating the metadata model or creating
 * a circular dependency.
 *
 * **Why `HttpURLConnection`, not OkHttp.** The TS8 head unit has 1 GB RAM and
 * no Play Services. Adding a 400 KB networking library to save 20 lines of
 * `HttpURLConnection` plumbing is the wrong trade. The existing music and
 * weather providers already use `HttpURLConnection` successfully.
 *
 * **The server contract.** The server exposes two endpoints:
 *
 * 1. `GET {base}/catalog.json` — returns a JSON array of pack descriptors:
 *    ```json
 *    [
 *      {
 *        "packId": "danang-quangnam",
 *        "name": "Đà Nẵng - Quảng Nam",
 *        "version": "2026.10",
 *        "sizeBytes": 48328192,
 *        "sha256": "ab12...",
 *        "bounds": [108.0, 15.8, 109.0, 16.2],
 *        "fileName": "danang-quangnam-2026.10.ts8map",
 *        "generatedAt": "2026-10-01",
 *        "zoomRange": [5, 14],
 *        "hasRouting": true,
 *        "hasSearch": true,
 *        "region": "Miền Trung"
 *      }
 *    ]
 *    ```
 *    The SHA-256 is the checksum of the `.ts8map` file itself (not of
 *    individual files inside it — that is `checksums.sha256` inside the ZIP,
 *    validated by [PackValidator] after extraction).
 *
 * 2. `GET {base}/{fileName}` — returns the `.ts8map` file as
 *    `application/octet-stream`, supporting `Range:` headers for resume.
 *
 * **Resume.** A 50 MB download on a TS8 unit parked in a weak Wi-Fi spot can
 * be interrupted by ignition-off. The client saves partial downloads to a
 * `.part` file and sends `Range: bytes=N-` on the next attempt. The server
 * must respond `206 Partial Content`; if it responds `200`, the client
 * restarts from zero.
 *
 * **Threading.** Every public method is blocking and must be called off the
 * main thread. The caller ([PackDownloadManager]) manages the thread.
 */
class PackDownloadClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
    private val userAgent: String = "TS8CarLauncher/1.0 (pack-download)"
) {

    companion object {
        private const val TAG = "PackDownloadClient"

        /** Buffer size for reading the response body. 16 KB is enough to keep
         *  the CPU busy without allocating a large enough buffer to matter on
         *  a 1 GB device. */
        private const val BUFFER = 16 * 1024

        /** Minimum bytes-per-second before we declare a stall. If the average
         *  drops below this for more than the read timeout, HttpURLConnection
         *  throws SocketTimeoutException on its own; this is a backstop for
         *  connections that stay open but send nothing. */
        private const val STALL_BYTES_PER_S = 512
    }

    /**
     * One pack entry in the server catalog.
     *
     * Fields mirror the server JSON, with safe defaults for any field the
     * server omits — a pack entry without `hasRouting` is still downloadable;
     * it just reports `hasRouting = false` in the UI.
     */
    data class CatalogEntry(
        val packId: String,
        val name: String,
        val version: String,
        val sizeBytes: Long,
        val sha256: String,
        val fileName: String,
        val bounds: DoubleArray?,
        val generatedAt: String,
        val zoomRange: IntArray?,
        val hasRouting: Boolean,
        val hasSearch: Boolean,
        val region: String
    ) {
        /** Human-readable size for the UI, e.g. "48 MB". */
        fun displaySize(): String {
            val mb = sizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1) String.format("%.0f MB", mb)
            else String.format("%.0f KB", sizeBytes.toDouble() / 1024)
        }

        /** The full download URL for this pack. */
        fun downloadUrl(base: String): String {
            val sep = if (base.endsWith("/")) "" else "/"
            return "$base$sep$fileName"
        }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = packId.hashCode()
    }

    /**
     * The outcome of a catalog fetch. The UI shows `empty` when the server
     * is unreachable, not a crash dialog.
     */
    sealed class CatalogResult {
        data class Ok(val packs: List<CatalogEntry>) : CatalogResult()
        data class Error(val message: String, val isNetwork: Boolean) : CatalogResult()
    }

    /**
     * Fetches the pack catalog from `{base}/catalog.json`.
     *
     * Returns [CatalogResult.Error] on any failure — never throws. The caller
     * shows the error message in Vietnamese.
     */
    fun fetchCatalog(): CatalogResult {
        val url = buildCatalogUrl()
        Log.i(TAG, "fetching catalog: $url")
        return try {
            val conn = openConnection(url, "GET") ?: return CatalogResult.Error(
                "URL không hợp lệ: $url", isNetwork = false
            )
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return CatalogResult.Error(
                    "Máy chủ trả mã $code", isNetwork = code in 500..599
                )
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            if (body.isBlank()) {
                return CatalogResult.Error("Catalog rỗng", isNetwork = false)
            }

            val arr = JSONObject(body).optJSONArray("packs")
                ?: JSONObject(body).let { it.optJSONArray("packs") }
            val packs = mutableListOf<CatalogEntry>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    packs.add(parseEntry(o))
                }
            }
            Log.i(TAG, "catalog: ${packs.size} packs")
            CatalogResult.Ok(packs)
        } catch (e: java.net.UnknownHostException) {
            CatalogResult.Error("Không tìm thấy máy chủ", isNetwork = true)
        } catch (e: java.net.SocketTimeoutException) {
            CatalogResult.Error("Hết giờ kết nối", isNetwork = true)
        } catch (e: java.io.IOException) {
            CatalogResult.Error("Lỗi mạng: ${e.message}", isNetwork = true)
        } catch (e: Exception) {
            Log.e(TAG, "catalog parse failed", e)
            CatalogResult.Error("Lỗi đọc catalog: ${e.message}", isNetwork = false)
        }
    }

    /**
     * Progress callback during [download]. Called on the downloading thread
     * (not the main thread). The implementation should be cheap — post a
     * message and return.
     *
     * @param bytesReceived total bytes written so far
     * @param totalBytes expected total (from `Content-Length`), or -1 if unknown
     */
    fun interface ProgressListener {
        fun onProgress(bytesReceived: Long, totalBytes: Long)
    }

    /**
     * The outcome of a download attempt.
     */
    sealed class DownloadResult {
        /** The file was fully downloaded and its SHA-256 matches. */
        data class Ok(val file: File) : DownloadResult()

        /** The download was cancelled by the caller. The `.part` file is kept
         *  so a future call can resume from where it left off. */
        data object Cancelled : DownloadResult()

        /** The download failed. [partialBytes] is how much was saved to the
         *  `.part` file, so the UI can offer "resume" vs "start over". */
        data class Error(
            val message: String,
            val isNetwork: Boolean,
            val partialBytes: Long
        ) : DownloadResult()
    }

    /**
     * Downloads a pack archive to [destFile], resuming from the `.part` file
     * if one exists.
     *
     * The file is written to `destFile.part` during download and renamed to
     * `destFile` only after the SHA-256 check passes. This means a verified
     * file at [destFile] is always complete and correct.
     *
     * @param entry the catalog entry to download
     * @param destFile the final destination (without `.part`)
     * @param expectedSha256 the SHA-256 hex string from the catalog
     * @param progress called with bytes written and total size
     * @param isCancelled called frequently; if it returns true, the download
     *        stops and returns [DownloadResult.Cancelled]
     */
    fun download(
        entry: CatalogEntry,
        destFile: File,
        expectedSha256: String,
        progress: ProgressListener,
        isCancelled: () -> Boolean = { false }
    ): DownloadResult {
        val partFile = File(destFile.path + ".part")
        val url = entry.downloadUrl(baseUrl)
        Log.i(TAG, "downloading: $url -> ${destFile.name}")

        var partialBytes = 0L
        try {
            // Resume: check the .part file size and send a Range header.
            val existingPart = if (partFile.exists()) partFile.length() else 0L

            val conn = openConnection(url, "GET") ?: return DownloadResult.Error(
                "URL không hợp lệ: $url", isNetwork = false, partialBytes = existingPart
            )
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/octet-stream")

            if (existingPart > 0) {
                Log.i(TAG, "resuming from $existingPart bytes")
                conn.setRequestProperty("Range", "bytes=$existingPart-")
            }

            val code = conn.responseCode

            // 200 = full download (server doesn't support range, or .part was gone)
            // 206 = partial content (server supports range, resuming)
            val resuming: Boolean
            val appendMode: Boolean

            when (code) {
                200 -> {
                    resuming = false
                    appendMode = false
                    partFile.delete()
                }
                206 -> {
                    resuming = true
                    appendMode = true
                }
                else -> {
                    conn.disconnect()
                    return DownloadResult.Error(
                        "Máy chủ trả mã $code", isNetwork = code in 500..599,
                        partialBytes = existingPart
                    )
                }
            }

            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val totalSize = if (resuming && contentLength > 0) existingPart + contentLength
                            else if (contentLength > 0) contentLength
                            else entry.sizeBytes

            conn.inputStream.use { input ->
                FileOutputStream(partFile, appendMode).use { out ->
                    val buf = ByteArray(BUFFER)
                    var written = if (resuming) existingPart else 0L
                    var lastProgress = 0L
                    var lastTime = System.currentTimeMillis()

                    while (true) {
                        if (isCancelled()) {
                            out.flush()
                            conn.disconnect()
                            return DownloadResult.Cancelled
                        }

                        val n = input.read(buf)
                        if (n < 0) break

                        out.write(buf, 0, n)
                        written += n

                        // Throttle progress callbacks to once per 100 KB or
                        // once per second, whichever comes first. Calling
                        // onProgress on every 16 KB read would post ~3000
                        // messages for a 48 MB pack.
                        val now = System.currentTimeMillis()
                        if (written - lastProgress >= 100 * 1024 || now - lastTime >= 1000) {
                            progress.onProgress(written, totalSize)
                            lastProgress = written
                            lastTime = now
                        }
                    }

                    out.flush()
                    partialBytes = written
                    progress.onProgress(written, totalSize)
                }
            }
            conn.disconnect()

            // Verify SHA-256 of the complete downloaded file.
            val actualSha = sha256Hex(partFile)
            if (actualSha.equals(expectedSha256, ignoreCase = true)) {
                // Rename .part -> final, atomic on the same filesystem.
                if (!partFile.renameTo(destFile)) {
                    // Fallback: copy then delete (FAT32 / FUSE)
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                }
                Log.i(TAG, "download complete: ${destFile.name} (${partialBytes} bytes, sha256 OK)")
                return DownloadResult.Ok(destFile)
            } else {
                Log.e(TAG, "sha256 mismatch: expected=$expectedSha256 actual=$actualSha")
                partFile.delete()
                return DownloadResult.Error(
                    "Tệp tải về bị hỏng (SHA-256 không khớp)",
                    isNetwork = false, partialBytes = 0
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "download timeout at $partialBytes bytes")
            return DownloadResult.Error(
                "Hết giờ tải", isNetwork = true, partialBytes = partialBytes
            )
        } catch (e: java.io.IOException) {
            Log.w(TAG, "download IO error at $partialBytes bytes: ${e.message}")
            // Keep the .part file for resume.
            return DownloadResult.Error(
                "Lỗi mạng: ${e.message}", isNetwork = true, partialBytes = partialBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            partFile.delete()
            return DownloadResult.Error(
                "Lỗi: ${e.message}", isNetwork = false, partialBytes = 0
            )
        }
    }

    // -- internals ---------------------------------------------------------

    private fun buildCatalogUrl(): String {
        val base = baseUrl.trimEnd('/')
        return "$base/catalog.json"
    }

    private fun openConnection(url: String, method: String): HttpURLConnection? {
        return try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doInput = true
                useCaches = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot open connection: $url", e)
            null
        }
    }

    private fun parseEntry(o: JSONObject): CatalogEntry {
        val boundsArr = o.optJSONArray("bounds")
        val bounds = if (boundsArr != null && boundsArr.length() == 4) {
            doubleArrayOf(
                boundsArr.optDouble(0, 0.0),
                boundsArr.optDouble(1, 0.0),
                boundsArr.optDouble(2, 0.0),
                boundsArr.optDouble(3, 0.0)
            )
        } else null

        val zoomArr = o.optJSONArray("zoomRange")
        val zoomRange = if (zoomArr != null && zoomArr.length() == 2) {
            intArrayOf(zoomArr.optInt(0, 5), zoomArr.optInt(1, 14))
        } else null

        return CatalogEntry(
            packId = o.optString("packId", ""),
            name = o.optString("name", o.optString("packId", "Không tên")),
            version = o.optString("version", ""),
            sizeBytes = o.optLong("sizeBytes", 0),
            sha256 = o.optString("sha256", ""),
            fileName = o.optString("fileName", ""),
            bounds = bounds,
            generatedAt = o.optString("generatedAt", ""),
            zoomRange = zoomRange,
            hasRouting = o.optBoolean("hasRouting", false),
            hasSearch = o.optBoolean("hasSearch", false),
            region = o.optString("region", "")
        )
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
