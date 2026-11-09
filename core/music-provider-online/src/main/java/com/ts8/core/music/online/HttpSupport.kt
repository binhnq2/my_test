package com.ts8.core.music.online

import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * One HTTP GET, reduced to what a provider actually needs.
 *
 * The interface exists so every provider can be driven in a JVM test with a
 * scripted responder instead of a network. A provider that can only be
 * exercised against the real Radio Browser API is a provider nobody will test
 * after the first week — and the cases that matter (429, a truncated body, a
 * server that never answers) are exactly the ones a live server rarely shows
 * you on demand.
 */
fun interface HttpFetcher {
    /**
     * Performs the request.
     *
     * Must not throw: transport failures arrive as
     * [FetchResult.transportError]. Implementations that need to abort for a
     * programming error (network on the UI thread) are allowed to throw
     * [IllegalStateException], and that is the only expected exception.
     */
    // No default argument here: a `fun interface` whose single method declares
    // one is rejected by the compiler ("Single abstract member cannot declare
    // default values"). Call sites pass their header map explicitly, which
    // keeps the SAM lambda a two-argument function.
    fun get(url: String, headers: Map<String, String>): FetchResult
}

/**
 * A response, or the reason there was none.
 *
 * [status] is `0` when no HTTP response was received at all, and [body] is
 * null in that case. Keeping both halves nullable-but-distinct is what lets a
 * provider tell "the server said 404" (`status == 404`, body maybe present)
 * from "the modem was off" (`transportError != null`), which produce different
 * [ProviderFailureCode]s and different things for the driver to do.
 */
data class FetchResult(
    /** HTTP status, or `0` when the request never reached a server. */
    val status: Int,
    /** Response body as text, or null when none was read. */
    val body: String?,
    /** Set when the request failed before an HTTP response: DNS, TLS, timeout, refused. */
    val transportError: String?
) {
    /** True when a response arrived with a 2xx status. */
    val isSuccess: Boolean get() = transportError == null && status in 200..299

    /**
     * True when the failure looks like a timeout rather than a refusal.
     *
     * `SocketTimeoutException` and `ConnectException` both arrive as
     * [transportError] strings on this platform; the distinction matters
     * because a slow server is retryable on the spot while a refused
     * connection usually is not.
     */
    val isTimeout: Boolean
        get() = transportError?.contains("timed out", ignoreCase = true) == true ||
            transportError?.contains("timeout", ignoreCase = true) == true
}

/**
 * The only network implementation in this module: `HttpURLConnection`.
 *
 * **Why not OkHttp.** Adding it would put a second HTTP stack and its
 * dependency graph into an APK that is sideloaded over a slow link onto a
 * head unit with 1 GB of RAM. Everything the providers need — a timeout, a
 * gzip body, a status code — `HttpURLConnection` already does, and on API 23
 * it is the stack the platform itself would use.
 *
 * **Why the timeouts are small and split.** [CONNECT_TIMEOUT_MS] bounds how
 * long a dead host may hold a worker; [READ_TIMEOUT_MS] bounds a server that
 * accepted the connection and then stalled. Eight seconds total is already
 * more than a driver waiting at a traffic light will tolerate; the circuit
 * breaker exists so that cost is paid at most twice per cooldown rather than
 * on every query.
 *
 * **Why the main-thread check throws.** On Android 6 an `HttpURLConnection`
 * call on the UI thread does not fail — it blocks the looper, and the map
 * stops drawing while the driver watches. `NetworkOnMainThreadException` is
 * only thrown for sockets under `StrictMode` on some paths, so it cannot be
 * relied on. This check makes the mistake produce a stack trace naming the
 * cost, the first time it happens, instead of one frame of a frozen launcher
 * that nobody can attribute afterwards.
 *
 * **Why redirects are not followed across hosts.** Radio Browser directs
 * clients to a mirror, and a station URL that answers `302` to somewhere else
 * is a normal thing for a stream resolver to encounter. Following it silently
 * means a provider configured for one trusted host can be walked to an
 * arbitrary one by a response body. Redirects are reported as the body-less
 * 3xx they are, and the caller decides.
 */
class HttpFetcherImpl(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT
) : HttpFetcher {

    private fun assertNotMainThread() {
        // getMainLooper() returns null on a plain JVM, which is how the unit
        // tests run; there is no UI thread to block there, so the assertion
        // has to be skipped rather than firing on a null comparison.
        val main = Looper.getMainLooper() ?: return
        if (Looper.myLooper() === main) {
            throw IllegalStateException(
                "Gọi mạng trên luồng giao diện sẽ đóng băng bản đồ của xe trong suốt " +
                    "thời gian timeout (tới ${connectTimeoutMs + readTimeoutMs} ms). " +
                    "Hãy gọi provider qua OnlineMusicGateway trên luồng nền."
            )
        }
    }

    override fun get(url: String, headers: Map<String, String>): FetchResult {
        assertNotMainThread()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as? HttpURLConnection)
                ?: return FetchResult(0, null, "URL không phải HTTP: $url")
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            // Explicit, because the platform default is to follow redirects,
            // including to another host.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", userAgent)
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }

            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) {
                connection.inputStream
            } else {
                // Error bodies (429 retry hints, Subsonic's JSON error) are
                // worth reading, but a missing error stream is not an error.
                runCatching { connection.errorStream }.getOrNull()
            }
            val body = stream?.use { readBody(it, connection) }
            return FetchResult(status, body, null)
        } catch (e: Throwable) {
            // Catches Throwable, not Exception, because `URL.openConnection`
            // on a malformed URL throws MalformedURLException (an Exception)
            // while a few platform paths surface Errors; neither may escape a
            // provider call, which the UI treats as infallible.
            return FetchResult(0, null, describe(e))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun readBody(stream: InputStream, connection: HttpURLConnection): String {
        val encoding = connection.contentEncoding
        val decoded = if (encoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(stream)
        } else {
            stream
        }
        // Read incrementally instead of trusting Content-Length: a stream that
        // lies about its length would otherwise over-allocate on a heap this
        // small. MAX_BODY_BYTES also stops a hostile or broken endpoint from
        // returning a body large enough to OOM the process.
        decoded.use { input ->
            val out = ByteArrayOutputStream(8 * 1024)
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_BODY_BYTES) {
                    throw IllegalStateException("Phản hồi quá lớn (> $MAX_BODY_BYTES byte)")
                }
                out.write(buffer, 0, read)
            }
            return out.toString("UTF-8")
        }
    }

    private fun describe(e: Throwable): String {
        val name = e.javaClass.simpleName
        val message = e.message
        return if (message.isNullOrBlank()) name else "$name: $message"
    }

    companion object {
        /** Four seconds. Long enough for a 3G round trip, short enough to notice. */
        const val CONNECT_TIMEOUT_MS = 4_000

        /** Four seconds of silence after the connection is a stalled server. */
        const val READ_TIMEOUT_MS = 4_000

        /**
         * 1 MB. A Radio Browser search page is a few tens of kilobytes; a
         * podcast feed with hundreds of episodes is larger but still well
         * under this. The cap is a guard against a body that would OOM a 128 MB
         * heap, not a tuning value.
         */
        const val MAX_BODY_BYTES = 1_048_576

        /**
         * Identifies the app to the Radio Browser mirror, which asks clients to
         * send a real user agent so they can be counted rather than blocked.
         */
        const val DEFAULT_USER_AGENT = "TS8CarLauncher/1.0 (Android; music-provider-online)"
    }
}

/**
 * Percent-encodes a query parameter value.
 *
 * A separate top-level function rather than inlined at each call site because
 * a station call sign can contain `&`, `+` or a space, and one missed
 * `encode` turns a search for "Rock & Roll" into a search for "Rock " — a
 * silent wrong answer, which is worse than a visible failure.
 */
fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * Builds `base?a=1&b=2` from already-encoded pairs.
 *
 * Kept here so every provider assembles URLs the same way; hand-concatenated
 * query strings are where the double-`?` and dropped-`&` bugs live.
 */
fun buildUrl(base: String, params: List<Pair<String, String>>): String {
    if (params.isEmpty()) return base
    val separator = if (base.contains('?')) '&' else '?'
    return buildString {
        append(base)
        append(separator)
        append(params.joinToString("&") { (k, v) -> "$k=$v" })
    }
}
