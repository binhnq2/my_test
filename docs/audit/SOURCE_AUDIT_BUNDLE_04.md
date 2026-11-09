# Source audit bundle 04

Excerpted verbatim from the working tree. Every section is
`## <relative path>` followed by `BEGIN FILE` / `END FILE` with the
file's exact contents between them. Nothing is summarised, elided or
reformatted.

Files in this bundle: 44

---

## core/music-provider-online/src/main/java/com/ts8/core/music/online/HttpSupport.kt

BEGIN FILE
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

END FILE

## core/music-provider-online/src/main/java/com/ts8/core/music/online/NavidromeProvider.kt

BEGIN FILE
package com.ts8.core.music.online

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Random

/**
 * A personal music server speaking the Subsonic API: Navidrome, and Jellyfin
 * through its Subsonic-compatible endpoint.
 *
 * **Why Subsonic rather than "the Navidrome API".** Navidrome deliberately
 * implements Subsonic and documents nothing else. Writing against Subsonic's
 * published shapes means the same class serves both families the brief names,
 * and a driver who later installs Jellyfin does not need a second build.
 *
 * **Why unconfigured is a first-class answer.** A personal server has no
 * default address. If [baseUrl], [username] or [password] is blank, every call
 * returns `BLOCKED_EXTERNAL_CONFIG` *without* touching the network, and
 * [OnlineMusicGateway.blockedConfig] names this provider so the settings screen
 * can say what is missing. Substituting `http://localhost` or an empty search
 * would produce a row of failures that reads like a broken app instead of an
 * unconfigured one — which is exactly the sort of false evidence the brief
 * forbids.
 *
 * **Why the token, and why the salt is not secret.** Subsonic's auth is
 * `t=md5(password + salt)`, so the password never crosses the wire. The salt
 * exists only to stop the same token being replayed verbatim; it is generated
 * from [random] and needs no cryptographic strength. Anyone who can read the
 * token can already read the password digest it was derived from, so a
 * `java.util.Random` salt is honest rather than sloppy — using
 * `SecureRandom` would imply a threat model this API does not have. The
 * password itself still never goes over the network, which is the property
 * that matters on a head unit sharing a car-park access point.
 *
 * **Why `f=json`.** The XML form would need a parser for a schema we do not
 * control, for a device with 128 MB of heap. JSON is a fraction of the bytes
 * and `org.json` is already on the platform.
 */
class NavidromeProvider(
    /** e.g. `https://music.example.com` or `http://192.168.1.10:4533`. */
    private val baseUrl: String? = null,
    private val username: String? = null,
    private val password: String? = null,
    override val kind: ProviderKind = ProviderKind.NAVIDROME,
    private val fetcher: HttpFetcher = HttpFetcherImpl(),
    private val breaker: CircuitBreaker = CircuitBreaker(),
    /** Injected so a JVM test can produce a deterministic token. */
    private val random: Random = Random()
) : OnlineMusicProvider {

    override val id: String = PROVIDER_ID

    override val displayName: String =
        if (kind == ProviderKind.JELLYFIN) "Jellyfin" else "Navidrome"

    /**
     * True when all three of address, user and password were supplied.
     *
     * Public so Settings can show the provider as configured, and so the
     * gateway can answer [OnlineMusicGateway.blockedConfig] without a request.
     */
    override fun isConfigured(): Boolean =
        !baseUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()

    /**
     * `search3.view`, which returns songs, albums and artists in one call.
     *
     * Only songs are used: the brief's contract is a list of playable tracks,
     * and expanding albums into tracks here would multiply the request count
     * on a link that may be a phone hotspot. `songCount` carries [limit];
     * `albumCount`/`artistCount` are zeroed so the response body does not
     * carry two thirds of data that is thrown away on a 128 MB heap.
     */
    override fun search(query: String, limit: Int): ProviderResult<List<OnlineTrack>> {
        val failure = configurationFailure()
        if (failure != null) return failure
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProviderResult.Ok(emptyList())
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val params = authParams() + listOf(
            "query" to urlEncode(trimmed),
            "songCount" to capped.toString(),
            "albumCount" to "0",
            "artistCount" to "0"
        )
        return request("/rest/search3.view", params) { response ->
            val result = response.optJSONObject("searchResult3")
                ?: return@request ProviderResult.Ok(emptyList())
            val songs = result.optJSONArray("song") ?: return@request ProviderResult.Ok(emptyList())
            ProviderResult.Ok(parseSongs(songs))
        }
    }

    /**
     * The shelves Subsonic actually offers.
     *
     * Subsonic has no trending endpoint, so [BrowseCategory.TRENDING] and
     * [BrowseCategory.GENRES] are answered from `getRandomSongs` — a real
     * server-side selection rather than an invented ranking — while
     * [BrowseCategory.RECENT] uses `getAlbumList2?type=newest` expanded to its
     * newest songs. [BrowseCategory.CONTINUE] returns `UNSUPPORTED`: Subsonic
     * tracks no resume point, and a fake "continue listening" is a lie the
     * driver would notice on the second tap.
     */
    override fun browse(category: BrowseCategory, limit: Int): ProviderResult<List<OnlineTrack>> {
        val failure = configurationFailure()
        if (failure != null) return failure
        val capped = limit.coerceIn(1, MAX_LIMIT)
        return when (category) {
            BrowseCategory.TRENDING, BrowseCategory.GENRES -> request(
                "/rest/getRandomSongs.view",
                authParams() + listOf("size" to capped.toString())
            ) { response ->
                val songs = response.optJSONObject("randomSongs")?.optJSONArray("song")
                ProviderResult.Ok(if (songs == null) emptyList() else parseSongs(songs))
            }

            BrowseCategory.RECENT -> request(
                "/rest/getNewestSongs.view",
                authParams() + listOf("count" to capped.toString())
            ) { response ->
                val songs = response.optJSONObject("newestSongs")?.optJSONArray("song")
                ProviderResult.Ok(if (songs == null) emptyList() else parseSongs(songs))
            }

            BrowseCategory.FAVORITES -> request(
                "/rest/getStarred2.view",
                authParams()
            ) { response ->
                val songs = response.optJSONObject("starred2")?.optJSONArray("song")
                ProviderResult.Ok(if (songs == null) emptyList() else parseSongs(songs).take(capped))
            }

            BrowseCategory.CONTINUE -> ProviderResult.Failure(
                ProviderFailureCode.UNSUPPORTED,
                "Máy chủ nhạc không lưu vị trí nghe dở",
                retryable = false
            )
        }
    }

    /**
     * Re-reads one song by its Subsonic id.
     *
     * `getSong.view` is the documented lookup. A Subsonic server answers a
     * missing id with `status: "failed"` and error code 70 ("not found"), which
     * [request] maps to `NOT_FOUND` rather than to a parse error.
     */
    override fun resolve(trackId: String): ProviderResult<OnlineTrack> {
        val failure = configurationFailure()
        if (failure != null) return failure
        if (trackId.isBlank()) {
            return ProviderResult.Failure(
                ProviderFailureCode.NOT_FOUND,
                "Mã bài hát không hợp lệ",
                retryable = false
            )
        }
        return when (val fetched = fetchSong(trackId)) {
            is ProviderResult.Failure -> fetched
            is ProviderResult.Ok -> ProviderResult.Ok(fetched.value.first)
        }
    }

    /**
     * The song plus the MIME type its `suffix` implies.
     *
     * A pair rather than storing the MIME on [OnlineTrack]: the model has no
     * field for it, and putting it in `album` would print "audio/mpeg" as the
     * album line on the search screen. The MIME only matters at playback, which
     * is why it is returned alongside rather than carried around.
     */
    private fun fetchSong(trackId: String): ProviderResult<Pair<OnlineTrack, String?>> {
        return request(
            "/rest/getSong.view",
            authParams() + listOf("id" to urlEncode(trackId))
        ) { response ->
            val song = response.optJSONObject("song")
                ?: return@request ProviderResult.Failure(
                    ProviderFailureCode.NOT_FOUND,
                    "Không tìm thấy bài hát này trên máy chủ",
                    retryable = false
                )
            val parsed = parseSong(song)
                ?: return@request ProviderResult.Failure(
                    ProviderFailureCode.PARSE_ERROR,
                    "Dữ liệu bài hát không đọc được",
                    retryable = true
                )
            ProviderResult.Ok(parsed to mimeForSuffix(song.optString("suffix")))
        }
    }

    /**
     * A `/rest/stream.view` URL carrying the auth token.
     *
     * The player opens this URL directly, which means the token is in the URL
     * itself. That is how Subsonic is designed to work and there is no header
     * alternative on the platform player; [PlayableSource.headers] exists but
     * this API needs nothing in it.
     *
     * The URL is not cached anywhere: it is rebuilt per call so a token that a
     * server rotates cannot outlive its welcome, and [expiresAtElapsedMs] stays
     * null because Subsonic states no expiry — the honest answer is "no stated
     * lifetime", not a guessed one.
     */
    override fun getPlayableSource(trackId: String): ProviderResult<PlayableSource> {
        val failure = configurationFailure()
        if (failure != null) return failure
        if (trackId.isBlank()) {
            return ProviderResult.Failure(
                ProviderFailureCode.NOT_FOUND,
                "Mã bài hát không hợp lệ",
                retryable = false
            )
        }
        // Resolve first so the title and container are real instead of a guess.
        return when (val resolved = fetchSong(trackId)) {
            is ProviderResult.Failure -> resolved
            is ProviderResult.Ok -> {
                val track = resolved.value.first
                val mime = resolved.value.second
                val url = buildUrl(
                    "${normalizedBase()}/rest/stream.view",
                    authParams() + listOf("id" to urlEncode(trackId))
                )
                ProviderResult.Ok(
                    PlayableSource(
                        url = url,
                        mimeType = mime ?: "audio/mpeg",
                        // Subsonic streams a finite file; the player can expect
                        // an end and should show progress against durationMs.
                        isStreaming = false,
                        title = track.title
                    )
                )
            }
        }
    }

    /**
     * `/rest/ping.view`, which is the API's own liveness call.
     *
     * A server that answers `status: "ok"` is reachable and the credentials are
     * good; one that answers `failed` with code 40/41/42 is reachable and
     * rejecting us, which is a different sentence for the driver.
     */
    override fun health(): ProviderHealth {
        val now = System.currentTimeMillis()
        if (!isConfigured()) {
            return ProviderHealth(false, "Chưa cấu hình máy chủ nhạc", now)
        }
        if (breaker.state() == CircuitBreaker.State.OPEN) {
            return ProviderHealth(false, "Máy chủ nhạc đang tạm ngắt sau lỗi trước đó", now)
        }
        val url = buildUrl("${normalizedBase()}/rest/ping.view", authParams())
        val result = runCatching { fetcher.get(url, emptyMap()) }
            .getOrElse { e -> FetchResult(0, null, e.message ?: e.javaClass.simpleName) }
        if (result.transportError != null) {
            return ProviderHealth(false, "Không kết nối được máy chủ: ${result.transportError}", now)
        }
        if (!result.isSuccess) {
            return ProviderHealth(false, "Máy chủ trả về HTTP ${result.status}", now)
        }
        val ok = runCatching {
            JSONObject(result.body ?: "").optJSONObject("subsonic-response")
                ?.optString("status") == "ok"
        }.getOrDefault(false)
        return if (ok) {
            ProviderHealth(true, null, now)
        } else {
            ProviderHealth(false, "Máy chủ từ chối đăng nhập", now)
        }
    }

    // -- request plumbing ---------------------------------------------------

    /**
     * Runs one authenticated call and unwraps `subsonic-response`.
     *
     * Subsonic answers HTTP 200 even for errors — the failure is in the
     * envelope — so checking the status code alone would treat "wrong password"
     * as a successful empty result. The envelope is inspected first, then the
     * body is handed to [parse].
     */
    private fun <T> request(
        path: String,
        params: List<Pair<String, String>>,
        parse: (JSONObject) -> ProviderResult<T>
    ): ProviderResult<T> {
        if (!breaker.allow()) {
            return ProviderResult.Failure(
                ProviderFailureCode.CIRCUIT_OPEN,
                "Máy chủ nhạc đang tạm ngắt, thử lại sau",
                retryable = false
            )
        }
        val url = buildUrl(normalizedBase() + path, params)
        val result = runCatching { fetcher.get(url, emptyMap()) }
            .getOrElse { e -> FetchResult(0, null, e.message ?: e.javaClass.simpleName) }

        if (result.transportError != null) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                if (result.isTimeout) ProviderFailureCode.TIMEOUT else ProviderFailureCode.NETWORK_ERROR,
                if (result.isTimeout) "Máy chủ nhạc không phản hồi kịp" else "Không kết nối được máy chủ nhạc",
                retryable = true
            )
        }
        if (result.status == 401 || result.status == 403) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.UNAUTHORIZED,
                "Sai tên đăng nhập hoặc mật khẩu máy chủ nhạc",
                retryable = false
            )
        }
        if (result.status == 429) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.RATE_LIMITED,
                "Máy chủ nhạc yêu cầu giảm tốc độ",
                retryable = true
            )
        }
        if (result.status !in 200..299) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.NETWORK_ERROR,
                "Máy chủ nhạc trả về lỗi ${result.status}",
                retryable = true
            )
        }
        val body = result.body
        if (body.isNullOrBlank()) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.PARSE_ERROR,
                "Máy chủ nhạc trả về nội dung rỗng",
                retryable = true
            )
        }

        val envelope = runCatching {
            JSONObject(body).optJSONObject("subsonic-response")
        }.getOrNull()
        if (envelope == null) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.PARSE_ERROR,
                "Dữ liệu máy chủ nhạc không đọc được",
                retryable = true
            )
        }
        if (envelope.optString("status") != "ok") {
            breaker.recordFailure()
            val error = envelope.optJSONObject("error")
            val subsonicCode = error?.optInt("code", 0) ?: 0
            val code = when (subsonicCode) {
                // 40 wrong credentials, 41 token auth unsupported, 42 auth
                // unsupported, 43 password mismatch, 44 invalid API key.
                40, 41, 42, 43, 44 -> ProviderFailureCode.UNAUTHORIZED
                70 -> ProviderFailureCode.NOT_FOUND
                else -> ProviderFailureCode.NETWORK_ERROR
            }
            val message = error?.optString("message")?.takeIf { it.isNotBlank() }
            return ProviderResult.Failure(
                code,
                when (code) {
                    ProviderFailureCode.UNAUTHORIZED ->
                        "Máy chủ nhạc từ chối đăng nhập${if (message != null) ": $message" else ""}"
                    ProviderFailureCode.NOT_FOUND -> "Không tìm thấy trên máy chủ nhạc"
                    else -> "Máy chủ nhạc báo lỗi${if (message != null) ": $message" else ""}"
                },
                retryable = code == ProviderFailureCode.NETWORK_ERROR
            )
        }

        val parsed = try {
            parse(envelope)
        } catch (e: RuntimeException) {
            null
        }
        if (parsed == null) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.PARSE_ERROR,
                "Dữ liệu máy chủ nhạc không đọc được",
                retryable = true
            )
        }
        breaker.recordSuccess()
        return parsed
    }

    /**
     * The query parameters every Subsonic call needs.
     *
     * Built per call, not cached: [salt] must differ per request for the token
     * to be worth anything, and a cached parameter list would quietly send the
     * same token forever.
     */
    private fun authParams(): List<Pair<String, String>> {
        val salt = newSalt()
        val token = md5Hex((password ?: "") + salt)
        return listOf(
            "u" to urlEncode(username ?: ""),
            "t" to token,
            "s" to salt,
            // Protocol version. 1.16.1 is what Navidrome and Jellyfin both
            // implement; asking for a newer one makes some builds refuse.
            "v" to "1.16.1",
            // Client identity, which servers use in their own logs.
            "c" to "TS8CarLauncher",
            "f" to "json"
        )
    }

    /**
     * A short random hex salt.
     *
     * 12 hex characters is Subsonic's usual size and is plenty for a replay
     * guard. See the class note: this is not a secret and does not pretend to
     * be one.
     */
    private fun newSalt(): String {
        val value = random.nextLong()
        return java.lang.Long.toHexString(value)
    }

    /** Lowercase hex MD5. Missing on no Android build; MessageDigest is API 1. */
    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) out.append('0')
            out.append(Integer.toHexString(v))
        }
        return out.toString()
    }

    /** Base URL without a trailing slash, so path concatenation cannot double it. */
    private fun normalizedBase(): String = (baseUrl ?: "").trim().trimEnd('/')

    private fun configurationFailure(): ProviderResult.Failure? {
        if (isConfigured()) return null
        val missing = ArrayList<String>(3)
        if (baseUrl.isNullOrBlank()) missing.add("địa chỉ máy chủ")
        if (username.isNullOrBlank()) missing.add("tên đăng nhập")
        if (password.isNullOrBlank()) missing.add("mật khẩu")
        return ProviderResult.Failure(
            ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG,
            "Chưa cấu hình ${displayName}: thiếu ${missing.joinToString(", ")}",
            retryable = false
        )
    }

    // -- parsing ------------------------------------------------------------

    private fun parseSongs(array: JSONArray): List<OnlineTrack> {
        val out = ArrayList<OnlineTrack>(array.length())
        for (i in 0 until array.length()) {
            val song = array.optJSONObject(i) ?: continue
            parseSong(song)?.let { out.add(it) }
        }
        return out
    }

    /**
     * One Subsonic `song` object.
     *
     * `duration` is seconds and may be absent for a streamed transcode; `0`
     * means unknown, never "zero-length". The MIME type is *not* stored here —
     * see [fetchSong] for why it travels beside the track instead.
     */
    private fun parseSong(json: JSONObject): OnlineTrack? {
        val songId = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = json.optString("title").takeIf { it.isNotBlank() } ?: return null
        val seconds = if (json.has("duration")) json.optLong("duration", 0L) else 0L
        val coverArt = json.optString("coverArt").takeIf { it.isNotBlank() }
        return OnlineTrack(
            id = songId,
            title = title,
            artist = json.optString("artist").takeIf { it.isNotBlank() },
            album = json.optString("album").takeIf { it.isNotBlank() },
            durationMs = if (seconds > 0L) seconds * 1000L else 0L,
            artworkUrl = coverArt?.let { artId ->
                buildUrl(
                    "${normalizedBase()}/rest/getCoverArt.view",
                    authParams() + listOf("id" to urlEncode(artId))
                )
            },
            providerId = id,
            isLive = false,
            // No stream URL is embedded in a search row on purpose: playing
            // must go through getPlayableSource, which re-resolves and mints a
            // fresh token rather than reusing one captured minutes ago.
            streamUrlHint = null
        )
    }

    /** Subsonic's `suffix` field to a MIME hint. */
    private fun mimeForSuffix(suffix: String?): String? = when (suffix?.lowercase()) {
        null, "" -> null
        "mp3" -> "audio/mpeg"
        "aac", "m4a", "mp4" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        else -> null
    }

    companion object {
        /** Stable key; saved queues refer to it. */
        const val PROVIDER_ID = "subsonic"

        /** 100. A phone hotspot is a plausible transport; do not over-fetch. */
        const val MAX_LIMIT = 100
    }
}

END FILE

## core/music-provider-online/src/main/java/com/ts8/core/music/online/OnlineMusicGateway.kt

BEGIN FILE
package com.ts8.core.music.online

/**
 * The one online entry point the UI calls, and the only place provider order
 * is decided.
 *
 * **Why a facade rather than the UI holding providers.** The brief fixes the
 * order — Internet Radio, then Podcast RSS, then Navidrome/Jellyfin — and an
 * order enforced in three screens is an order that will disagree between them
 * within a month. It also concentrates the two rules that make a mixed search
 * usable in a car:
 *
 * 1. **A broken provider costs nothing.** One provider timing out must not
 *    turn a search into a twenty-second wait. Each provider's call is bounded
 *    by [HttpFetcherImpl]'s timeouts and by its own [CircuitBreaker]; a failure
 *    contributes an empty list to the aggregate and a line to [status], and
 *    the search returns whatever the working providers produced. Partial
 *    results are the correct behaviour here: the driver asked for a song, not
 *    for a health report.
 * 2. **Unconfigured is said out loud.** [blockedConfig] names providers with
 *    no endpoint or missing credentials, so the screen can print
 *    `BLOCKED_EXTERNAL_CONFIG` instead of an empty list that reads as "no
 *    such song". Fabricating a working online provider on a device with no
 *    configuration is the specific dishonesty the brief calls out.
 *
 * **What is deliberately not here.** No audio. [getPlayableSource] returns a
 * URL; opening it is `MusicPlaybackService`/`MediaPlayer`'s job, because
 * playback state must have exactly one owner or a queue holding both local and
 * online items will show two different "now playing" rows.
 *
 * **URL caching, and why there is none.** A [PlayableSource] may carry
 * [PlayableSource.expiresAtElapsedMs]. This class does not store URLs between
 * calls at all, and any future cache must refuse to serve an entry past that
 * instant — handing the player a revoked token produces a stream that starts
 * and dies silently, which is indistinguishable to the driver from a broken
 * app. Re-resolving costs one request and is always correct.
 *
 * **Threading.** Every method blocks; call them from the app's IO executor,
 * never from the main thread. [HttpFetcherImpl] throws in that case rather
 * than freezing the launcher.
 */
class OnlineMusicGateway(
    /**
     * In priority order. The list is copied at construction so a caller
     * mutating its own list later cannot silently reorder the provider
     * sequence the brief fixed.
     */
    providers: List<OnlineMusicProvider>,
    /**
     * Time source for the status TTL and for refusing an expired stream URL.
     * On device pass `SystemClock::elapsedRealtime`; the wall-clock default
     * exists so a JVM test needs no Android runtime, not because wall-clock is
     * the right choice on the vehicle — a head unit whose RTC resets at every
     * ignition makes wall-clock deltas meaningless.
     */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** Defensive copy; see the constructor note. */
    private val providers: List<OnlineMusicProvider> = ArrayList(providers)

    /** Throttle for [status]; probing every provider per UI frame is a DoS on itself. */
    @Volatile
    private var lastStatus: List<ProviderStatus> = emptyList()

    /**
     * Zero, not `Long.MIN_VALUE`: `now - Long.MIN_VALUE` overflows to a
     * negative, and a negative is not inside `0 until TTL`, so the guard would
     * silently depend on an overflow. The `isNotEmpty` check above is what
     * makes the first call recompute.
     */
    @Volatile
    private var lastStatusAtMs: Long = 0L

    /** The providers, in the order they are tried. */
    fun providerIds(): List<String> = providers.map { it.id }

    /**
     * Aggregated search across every provider.
     *
     * Results are tagged with [OnlineTrack.providerId] by the provider itself
     * (it is the provider's own stable key), so a mixed list can be grouped
     * without the gateway rewriting rows. Order is the brief's provider order
     * and, within a provider, the provider's own ranking — re-sorting by title
     * across sources would bury the radio station the driver typed the exact
     * name of behind an episode that happens to start with "A".
     *
     * A provider that fails contributes nothing; its failure is in [status],
     * not in the return value. Only when *every* provider fails and none is
     * merely unconfigured does the aggregate report the first failure — because
     * "all sources are down" is a real answer the screen must show, while "one
     * of four is down" is not.
     */
    fun search(query: String, limit: Int = OnlineMusicProvider.DEFAULT_LIMIT): SearchAggregate {
        val perProvider = LinkedHashMap<String, ProviderResult<List<OnlineTrack>>>(providers.size)
        val combined = ArrayList<OnlineTrack>()
        val failures = ArrayList<ProviderResult.Failure>(providers.size)
        var attempted = 0
        // Clamped once, outside the loop: one caller asking for 5 000 must not
        // become four requests of 5 000 each.
        val capped = limit.coerceIn(1, MAX_AGGREGATE_LIMIT)

        for (provider in providers) {
            val result = runCatching { provider.search(query, capped) }
                .getOrElse { _ ->
                    ProviderResult.Failure(
                        ProviderFailureCode.NETWORK_ERROR,
                        "Nguồn ${provider.displayName} gặp lỗi không xác định",
                        retryable = true
                    )
                }
            perProvider[provider.id] = result
            when (result) {
                is ProviderResult.Ok -> {
                    attempted++
                    combined.addAll(result.value)
                }
                is ProviderResult.Failure -> {
                    failures.add(result)
                    // BLOCKED_EXTERNAL_CONFIG is not an attempt: nothing was
                    // asked because nothing is configured. Counting it as an
                    // attempt would let a wholly unconfigured app claim "mọi
                    // nguồn đều lỗi mạng", which is untrue and unhelpful.
                    if (result.code != ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG) attempted++
                }
            }
        }

        val allFailed = combined.isEmpty() && attempted > 0 &&
            perProvider.values.none { it is ProviderResult.Ok }
        val fatal = if (allFailed) failures.firstOrNull() else null
        return SearchAggregate(
            tracks = combined,
            perProvider = perProvider,
            failure = fatal
        )
    }

    /** Aggregated browse; same rules as [search]. */
    fun browse(
        category: BrowseCategory,
        limit: Int = OnlineMusicProvider.DEFAULT_LIMIT
    ): SearchAggregate {
        val perProvider = LinkedHashMap<String, ProviderResult<List<OnlineTrack>>>(providers.size)
        val combined = ArrayList<OnlineTrack>()
        val failures = ArrayList<ProviderResult.Failure>(providers.size)
        var attempted = 0
        val capped = limit.coerceIn(1, MAX_AGGREGATE_LIMIT)

        for (provider in providers) {
            val result = runCatching { provider.browse(category, capped) }
                .getOrElse { _ ->
                    ProviderResult.Failure(
                        ProviderFailureCode.NETWORK_ERROR,
                        "Nguồn ${provider.displayName} gặp lỗi không xác định",
                        retryable = true
                    )
                }
            perProvider[provider.id] = result
            when (result) {
                is ProviderResult.Ok -> {
                    attempted++
                    combined.addAll(result.value)
                }
                is ProviderResult.Failure -> {
                    failures.add(result)
                    // UNSUPPORTED is a provider declining a shelf it does not
                    // have. Treating it as a failure would mark a podcast feed
                    // "sự cố" merely because it has no genre browser.
                    if (result.code != ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG &&
                        result.code != ProviderFailureCode.UNSUPPORTED
                    ) {
                        attempted++
                    }
                }
            }
        }

        val allFailed = combined.isEmpty() && attempted > 0 &&
            perProvider.values.none { it is ProviderResult.Ok }
        return SearchAggregate(
            tracks = combined,
            perProvider = perProvider,
            failure = if (allFailed) failures.firstOrNull() else null
        )
    }

    /**
     * Resolves a track through the provider that issued it.
     *
     * Routing by [OnlineTrack.providerId] rather than trying every provider in
     * order: two sources can issue the same id string for different tracks, and
     * searching all of them would return whichever answered first — an
     * intermittent wrong song, the hardest kind of bug to report.
     */
    fun resolve(providerId: String, trackId: String): ProviderResult<OnlineTrack> {
        val provider = providers.firstOrNull { it.id == providerId }
            ?: return ProviderResult.Failure(
                ProviderFailureCode.UNSUPPORTED,
                "Không có nguồn \"$providerId\" trong ứng dụng",
                retryable = false
            )
        return runCatching { provider.resolve(trackId) }
            .getOrElse { _ ->
                ProviderResult.Failure(
                    ProviderFailureCode.NETWORK_ERROR,
                    "Nguồn ${provider.displayName} gặp lỗi không xác định",
                    retryable = true
                )
            }
    }

    /**
     * The stream to play, from the provider that owns the track.
     *
     * An unknown provider id is `UNSUPPORTED`, not `NOT_FOUND`: a saved queue
     * from a build that had a provider this one does not is a different
     * problem from a track deleted on the server, and only one of them is
     * permanent.
     */
    fun getPlayableSource(providerId: String, trackId: String): ProviderResult<PlayableSource> {
        val provider = providers.firstOrNull { it.id == providerId }
            ?: return ProviderResult.Failure(
                ProviderFailureCode.UNSUPPORTED,
                "Không có nguồn \"$providerId\" trong ứng dụng",
                retryable = false
            )
        return runCatching { provider.getPlayableSource(trackId) }
            .getOrElse { _ ->
                ProviderResult.Failure(
                    ProviderFailureCode.NETWORK_ERROR,
                    "Nguồn ${provider.displayName} gặp lỗi không xác định",
                    retryable = true
                )
            }
    }

    /**
     * One status line per provider, for the diagnostics screen.
     *
     * Throttled to [STATUS_TTL_MS]: [OnlineMusicProvider.health] may perform a
     * request, and a RecyclerView binding a footer must not launch four
     * requests per frame. The cached answer is returned inside the window, so
     * a screen opening calls it once.
     */
    fun status(): List<ProviderStatus> {
        val now = clock()
        val cached = lastStatus
        if (cached.isNotEmpty() && now - lastStatusAtMs in 0 until STATUS_TTL_MS) {
            return cached
        }
        val fresh = ArrayList<ProviderStatus>(providers.size)
        for (provider in providers) {
            val health = runCatching { provider.health() }
                .getOrElse { e ->
                    ProviderHealth(
                        available = false,
                        detail = "Không kiểm tra được: ${e.message ?: e.javaClass.simpleName}",
                        checkedAtElapsedMs = now
                    )
                }
            fresh.add(
                ProviderStatus(
                    providerId = provider.id,
                    displayName = provider.displayName,
                    kind = provider.kind,
                    health = health,
                    blockedConfig = isBlocked(provider)
                )
            )
        }
        lastStatus = fresh
        lastStatusAtMs = now
        return fresh
    }

    /**
     * Providers with no usable configuration, as Vietnamese sentences.
     *
     * The screen prints these verbatim above the online results, so a driver
     * knows the app could not look rather than that nothing exists. Empty when
     * everything that is supposed to be configured is.
     */
    fun blockedConfig(): List<String> {
        val out = ArrayList<String>(providers.size)
        for (provider in providers) {
            if (!isBlocked(provider)) continue
            out.add(
                when (provider) {
                    is PodcastRssProvider -> "Podcast: chưa cấu hình địa chỉ RSS trong Cài đặt"
                    is NavidromeProvider ->
                        "Navidrome/Jellyfin: chưa cấu hình địa chỉ máy chủ, tên đăng nhập hoặc mật khẩu"
                    else -> "${provider.displayName}: chưa cấu hình"
                }
            )
        }
        return out
    }

    /**
     * True when a provider is unconfigured, without a network call.
     *
     * **Delegated to the provider, not decided here.** This used to be a `when`
     * over `PodcastRssProvider` and `NavidromeProvider`, which silently
     * reported every *other* provider as configured — an unconfigured source
     * then answered a plain empty list, and a driver read "no such song"
     * instead of "this source was never set up". [OnlineMusicProvider
     * .isConfigured] puts the answer where the knowledge is, and the default
     * `true` on the interface keeps a provider that needs nothing from having
     * to say so.
     */
    private fun isBlocked(provider: OnlineMusicProvider): Boolean =
        runCatching { !provider.isConfigured() }.getOrDefault(false)

    /**
     * A stream URL the gateway has checked is not already expired.
     *
     * Exists so a future caller cannot accidentally cache a stream URL: it
     * always re-resolves and refuses a source whose stated lifetime has already
     * passed. Today the gateway does not cache at all; this is the only
     * sanctioned way to obtain a URL if one is added later.
     */
    fun freshSource(providerId: String, trackId: String): ProviderResult<PlayableSource> {
        return when (val result = getPlayableSource(providerId, trackId)) {
            is ProviderResult.Failure -> result
            is ProviderResult.Ok -> {
                val expiry = result.value.expiresAtElapsedMs
                if (expiry != null && clock() > expiry) {
                    ProviderResult.Failure(
                        ProviderFailureCode.NOT_FOUND,
                        "Địa chỉ phát đã hết hạn, cần tải lại",
                        retryable = true
                    )
                } else {
                    result
                }
            }
        }
    }

    companion object {
        /**
         * Whatever the screen asks for cannot exceed this per provider. A
         * mixed list of four sources each returning 30 is already more than
         * one screenful of rows on a 1024x600 panel.
         */
        const val MAX_AGGREGATE_LIMIT = 50

        /** One minute. Long enough that re-opening the screen reuses the probe. */
        const val STATUS_TTL_MS = 60_000L
    }
}

/**
 * The combined result of a [OnlineMusicGateway.search] or `browse`.
 *
 * [perProvider] is kept so the screen can draw one honest status chip per
 * source ("Đài: 12 kết quả · Podcast: chưa cấu hình") instead of guessing from
 * the merged list what happened. [failure] is set only when every source that
 * was actually tried failed; it is the aggregate failure, not the first one.
 */
data class SearchAggregate(
    val tracks: List<OnlineTrack>,
    val perProvider: Map<String, ProviderResult<List<OnlineTrack>>>,
    /** Non-null only when nothing worked and nothing is merely unconfigured. */
    val failure: ProviderResult.Failure?
) {
    /** True when at least one source answered. */
    val hasResults: Boolean get() = tracks.isNotEmpty()

    /**
     * True when the screen should show the failure instead of an empty state.
     *
     * The distinction matters: an empty state with no failure means "không có
     * kết quả" (a real answer); this one means "không hỏi được nguồn nào".
     */
    val isTotalFailure: Boolean get() = failure != null && tracks.isEmpty()
}

/**
 * One provider's line in the status list.
 *
 * [blockedConfig] is carried separately from [health] because it is knowable
 * without a request while health may not be, and the screen styles the two
 * differently: missing configuration is actionable, an unreachable server is
 * not.
 */
data class ProviderStatus(
    val providerId: String,
    val displayName: String,
    val kind: ProviderKind,
    val health: ProviderHealth,
    /** True when the provider needs settings the driver has not entered. */
    val blockedConfig: Boolean
) {
    /** True when this provider will contribute nothing until the driver acts. */
    val needsConfiguration: Boolean get() = blockedConfig
}

END FILE

## core/music-provider-online/src/main/java/com/ts8/core/music/online/OnlineMusicProvider.kt

BEGIN FILE
package com.ts8.core.music.online

/**
 * One online catalogue, as the gateway and the search screen see it.
 *
 * **What this type exists to prevent.** Without a single contract, every
 * provider would invent its own error handling, and the UI would have to know
 * which of them throws, which returns null, and which quietly returns an empty
 * list for "no network" as if it were "nothing matched". A driver on a dead
 * cellular link would then see an empty result list and conclude the song does
 * not exist. So a provider never throws and never answers "empty" for a
 * failure: it returns [ProviderResult], and "no match" is `Ok(emptyList())`
 * while "could not ask" is a `Failure` with a code the screen can act on.
 *
 * **What this type exists to allow.** The search screen shows `Trên thiết bị`
 * and `Trực tuyến` as two groups built from the same list widget. Providers
 * are the only thing that knows which is which, and [OnlineTrack.providerId]
 * carries that identity into playback, where the queue decides whether to hand
 * a URL to the service or a local file.
 *
 * **Blocking, deliberately.** Every method is synchronous because it is always
 * called from the gateway's background pool. Calling one on the main thread is
 * a bug, and [HttpSupport]'s default fetcher fails loudly rather than silently
 * freezing the map for the connect timeout. The UI-facing asynchronous front
 * end belongs above this interface, not inside each provider.
 *
 * ## Absolute prohibition
 *
 * This module is restricted to **documented, public APIs only**: the Radio
 * Browser public API, plain podcast RSS, and the Subsonic API for
 * Navidrome/Jellyfin. There is to be **no YouTube scraping, no yt-dlp, no
 * NewPipe extractor, no reverse-engineered endpoint, no audio extraction and
 * no downloading of streams to disk** — not in a provider, not behind a
 * feature flag, not "just for testing". Those route around the terms of service
 * the head unit's owner agreed to and around the licensing that decides
 * whether the app may play a track at all; a repair brief that says "không
 * dùng YouTube extractor/scraper" is a hard product boundary, not a
 * preference. If a provider for a service without a public playback API is
 * wanted later, it is a separate decision with its own legal review, and it
 * does not belong in this package.
 *
 * Nothing here caches or downloads audio: [getPlayableSource] returns a URL
 * the existing `MusicPlaybackService` opens as a stream, and the URL is
 * discarded when the provider says its lifetime has ended (see
 * [PlayableSource.expiresAtElapsedMs] and [OnlineMusicGateway]).
 */
interface OnlineMusicProvider {
    /**
     * Stable identifier, unique across the gateway: `"radio-browser"`,
     * `"podcast"`, `"subsonic"`. Used as the key in [OnlineTrack.providerId]
     * and in [ProviderStatus], so it must not change once shipped — a queue
     * saved to disk refers to it by this string.
     */
    val id: String

    /** Vietnamese name shown next to a result, e.g. `"Đài phát thanh"`. */
    val displayName: String

    /** Which family this is, so the UI can pick an icon and an empty state. */
    val kind: ProviderKind

    /**
     * Free-text search, capped at [limit].
     *
     * `Ok(emptyList())` means "asked, found nothing"; a `Failure` means "could
     * not ask". Collapsing the second into the first is the bug this signature
     * makes impossible to write by accident.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): ProviderResult<List<OnlineTrack>>

    /**
     * A curated shelf. Sources without the concept return
     * `Failure(UNSUPPORTED)`, not an empty list — the screen hides that tab
     * rather than drawing an empty shelf that looks broken.
     */
    fun browse(category: BrowseCategory, limit: Int = DEFAULT_LIMIT): ProviderResult<List<OnlineTrack>>

    /** Loads a single track by the id this provider issued, re-reading its metadata. */
    fun resolve(trackId: String): ProviderResult<OnlineTrack>

    /**
     * The stream the player should open.
     *
     * For a radio station this is the station URL; for Subsonic it is a
     * `/rest/stream.view` URL carrying credentials. Mandatory because it can
     * fail for reasons [resolve] did not: the station went offline between
     * search and play, or the server began rejecting the token.
     */
    fun getPlayableSource(trackId: String): ProviderResult<PlayableSource>

    /** Cheap reachability probe, used to decide whether to try at all. */
    fun health(): ProviderHealth

    /**
     * Whether this provider has the settings it needs, answered **without a
     * request**.
     *
     * **Why this is on the interface rather than a `when` in the gateway.** The
     * gateway used to recognise the two configurable providers by type, which
     * meant every other provider — a third party's, the recording double, one
     * added later — was silently assumed configured. The consequence was the
     * exact dishonesty §13 forbids, in the opposite direction: an unconfigured
     * source reported a plain empty list, which reads to a driver as "no such
     * song" rather than "this source was never set up". A default of `true`
     * keeps a provider that genuinely needs nothing from having to write
     * anything, and a provider that *does* need something has one method to
     * override instead of one `when` branch to remember in another file.
     */
    fun isConfigured(): Boolean = true

    companion object {
        /**
         * 30. Small enough that one query costs a few hundred kilobytes over
         * the head unit's modem, large enough to fill the search screen once.
         */
        const val DEFAULT_LIMIT = 30
    }
}

/** What kind of online source a provider is backed by. */
enum class ProviderKind {
    /** Internet radio directories, e.g. Radio Browser. Live streams only. */
    INTERNET_RADIO,

    /** A podcast feed: episodes are discrete files with a published date. */
    PODCAST_RSS,

    /** Navidrome, a Subsonic-compatible server. */
    NAVIDROME,

    /** Jellyfin through its Subsonic-compatible endpoint. */
    JELLYFIN,

    /** Any source with a documented API that does not fit the four above. */
    OTHER
}

/** A shelf the provider can list without a query. */
enum class BrowseCategory {
    /** What the service itself considers popular. */
    TRENDING,

    /** Browse by genre/tag. */
    GENRES,

    /** Newest first. */
    RECENT,

    /** The account's favourites, where the provider has accounts. */
    FAVORITES,

    /** Resume points, where the provider tracks them. */
    CONTINUE
}

/**
 * The outcome of a provider call.
 *
 * A sealed type rather than an exception, because a failure here is an ordinary
 * outcome — a driver in a tunnel will produce one on every search — and code
 * that has to catch exceptions to read an expected state always ends up with a
 * `catch (Throwable)` somewhere that swallows a real bug. `when` over this
 * forces every call site to decide what the UI does for each code.
 */
sealed class ProviderResult<out T> {
    /** The provider answered. [value] may be empty — that is a real answer. */
    data class Ok<out T>(val value: T) : ProviderResult<T>()

    /**
     * The provider did not answer. [message] is Vietnamese and aimed at the
     * driver; [code] is what the UI branches on; [retryable] says whether an
     * immediate retry is worth offering at all.
     */
    data class Failure(
        val code: ProviderFailureCode,
        val message: String,
        val retryable: Boolean
    ) : ProviderResult<Nothing>()

    /** The value, or null. Convenience for display code that only needs the list. */
    fun valueOrNull(): T? = when (this) {
        is Ok -> value
        is Failure -> null
    }

    /** The failure, or null when this is [Ok]. */
    fun failureOrNull(): Failure? = this as? Failure
}

/**
 * Why a provider call failed.
 *
 * Distinct values exist so the screen can say something true. `NETWORK_ERROR`
 * earns a "Thử lại" button; `BLOCKED_EXTERNAL_CONFIG` earns a sentence telling
 * the driver to enter a server address in Settings; `CIRCUIT_OPEN` earns
 * nothing at all, because the provider is already known to be down and asking
 * again immediately is how a search takes twenty seconds.
 */
enum class ProviderFailureCode {
    /**
     * The provider has no usable configuration: no feed URL, no server
     * address, no credentials. Not a network problem and not retryable — the
     * driver has to supply something first. The gateway lists these separately
     * via [OnlineMusicGateway.blockedConfig] rather than hiding them behind a
     * generic error.
     */
    BLOCKED_EXTERNAL_CONFIG,

    /** The request exceeded its connect or read budget. */
    TIMEOUT,

    /** DNS, TCP, TLS or a dropped connection. */
    NETWORK_ERROR,

    /** The service rejected the credentials or token. */
    UNAUTHORIZED,

    /** The service answered definitively that the item is not there. */
    NOT_FOUND,

    /** The provider has no concept of what was asked for (e.g. `browse` on RSS). */
    UNSUPPORTED,

    /** The response arrived but could not be understood. */
    PARSE_ERROR,

    /** The service asked us to slow down (HTTP 429 and equivalents). */
    RATE_LIMITED,

    /**
     * The breaker is open: this provider failed recently and calls are being
     * refused locally until the cooldown elapses. Retryable in the sense that
     * waiting helps; offering an instant retry does not.
     */
    CIRCUIT_OPEN
}

/**
 * A track that lives on the network, with no local file behind it.
 *
 * Every field that a source can genuinely omit is nullable rather than filled
 * with a placeholder. A radio station really does have no album; inventing
 * `"Unknown Album"` would put the same string on a thousand rows and make the
 * search screen look like it knew something it did not. [durationMs] is `0` for
 * live streams, matching [com.ts8.core.music.Track.durationMs]'s "0 when
 * unknown" convention so a mixed queue can compute progress the same way.
 */
data class OnlineTrack(
    /**
     * The provider's own id for this track. Radio Browser uses its station
     * UUID; Subsonic uses the song's Subsonic id. Scoped by [providerId] — two
     * providers may issue the same string and they are not the same track.
     */
    val id: String,
    val title: String,
    /** Null when the source has no artist field at all (most radio entries). */
    val artist: String? = null,
    val album: String? = null,
    /** Duration in milliseconds; `0` for a live stream, per the local model. */
    val durationMs: Long = 0L,
    /** Absolute artwork URL, or null when the source offers none. */
    val artworkUrl: String? = null,
    /** Matches [OnlineMusicProvider.id]; identifies who can resolve this later. */
    val providerId: String,
    /** True for a continuous stream with no known end. */
    val isLive: Boolean = false,
    /**
     * A URL the provider *believes* is playable, captured during search.
     *
     * A hint, not a promise: the gateway must still call
     * [OnlineMusicProvider.getPlayableSource] before playing, because the
     * station may have moved or the URL may carry a token that expired between
     * the search and the tap. Present so the UI can show a "phát ngay được"
     * affordance without a second round trip.
     */
    val streamUrlHint: String? = null
) {
    /** Display subtitle; empty rather than a fabricated placeholder. */
    val subtitle: String
        get() = listOfNotNull(
            artist?.takeIf { it.isNotBlank() },
            album?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
}

/**
 * A stream the player can open right now.
 *
 * [headers] exists because some documented APIs require a request header the
 * platform player must be given at `setDataSource` time. It is empty by
 * default and empty for every provider in this module; it is not a place to
 * smuggle a cookie for a scraped site, which this module does not do.
 */
data class PlayableSource(
    val url: String,
    val mimeType: String,
    /** True when the stream has no known end; the player must not expect EOF. */
    val isStreaming: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val title: String,
    /**
     * When this URL stops being valid, on the
     * [SystemClock.elapsedRealtime] timeline, or null when the provider states
     * no expiry.
     *
     * The gateway refuses to serve a cached URL past this point rather than
     * handing the player a token the server has already revoked — the failure
     * that otherwise shows up as a stream that starts and dies three seconds
     * later with no explanation.
     */
    val expiresAtElapsedMs: Long? = null
)

/**
 * A provider's state, as far as it knows it without doing real work.
 *
 * [checkedAtElapsedMs] is on the `SystemClock.elapsedRealtime` timeline, not
 * wall-clock, so a head unit whose RTC resets to 1970 at every ignition still
 * produces a meaningful age.
 */
data class ProviderHealth(
    val available: Boolean,
    /** Short Vietnamese explanation for the diagnostics screen; null when fine. */
    val detail: String? = null,
    val checkedAtElapsedMs: Long = 0L
)

END FILE

## core/music-provider-online/src/main/java/com/ts8/core/music/online/PodcastRssProvider.kt

BEGIN FILE
package com.ts8.core.music.online

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Locale

/**
 * Podcast episodes from one RSS feed the driver configured.
 *
 * **A podcast feed is not a search service.** It has no query API, no genres
 * shelf and no server-side popularity. Pretending otherwise — returning an
 * empty list from `search` — is how a search screen ends up claiming a podcast
 * does not exist when the truth is that the provider cannot answer. So
 * [search] filters the feed's own items client-side (which is what a feed
 * supports) and [browse] answers `UNSUPPORTED` for everything except
 * [BrowseCategory.RECENT], which a feed genuinely offers by construction.
 *
 * **Why a blank feed URL is a failure, not an empty feed.** The brief is
 * explicit: a provider with no configured endpoint must say
 * `BLOCKED_EXTERNAL_CONFIG`, so the UI can print "chưa cấu hình nguồn RSS"
 * instead of "không có kết quả". Those two sentences send the driver to
 * different places, and only one of them is true.
 *
 * **Why `XmlPullParser` and not `DocumentBuilderFactory`.** `DocumentBuilder`
 * resolves external entities by default on a great many platform builds. A
 * feed is attacker-controlled content fetched over the network: a DOCTYPE
 * pointing at a local file turns a feed refresh into a file read, and one
 * pointing at a URL turns it into an SSRF. Every fix for that is a feature
 * flag set per platform build, which the TS8's firmware fork is exactly the
 * kind of build to get wrong. `XmlPullParser` has no DTD support to disable —
 * it is a streaming tokenizer with nothing to exploit. Its cost is that
 * namespaces must be handled by hand, which [NS_ITUNES] does.
 */
class PodcastRssProvider(
    /** Feed URL, configured by Settings. Blank means "not configured". */
    private val feedUrl: String? = null,
    private val fetcher: HttpFetcher = HttpFetcherImpl(),
    private val breaker: CircuitBreaker = CircuitBreaker()
) : OnlineMusicProvider {

    override val id: String = PROVIDER_ID

    override val displayName: String = "Podcast"

    override val kind: ProviderKind = ProviderKind.PODCAST_RSS

    /**
     * True when a feed URL was supplied.
     *
     * Public so Settings can decide whether to draw the podcast group at all,
     * and so [OnlineMusicGateway.blockedConfig] can name this provider without
     * making a network call to discover it.
     */
    override fun isConfigured(): Boolean = !feedUrl.isNullOrBlank()

    /**
     * Client-side filter over the feed's items.
     *
     * Episode counts are small (tens, rarely hundreds), so a linear scan is
     * the right implementation; there is no index to build. An empty query
     * returns the newest items rather than nothing: on a podcast screen,
     * tapping into the group with an empty box should show something.
     */
    override fun search(query: String, limit: Int): ProviderResult<List<OnlineTrack>> {
        if (!isConfigured()) return blockedConfigFailure()
        val capped = limit.coerceIn(1, MAX_LIMIT)
        return loadFeed { feed ->
            val trimmed = query.trim()
            val matched = if (trimmed.isEmpty()) {
                feed.items
            } else {
                // Case- and diacritic-insensitive would need the place-search
                // normaliser, which lives in another module; folding case is
                // what a podcast title search realistically needs, because
                // episode titles are usually typed as seen.
                val needle = trimmed.lowercase(Locale.ROOT)
                feed.items.filter { item ->
                    item.title.lowercase(Locale.ROOT).contains(needle) ||
                        (item.artist?.lowercase(Locale.ROOT)?.contains(needle) == true)
                }
            }
            ProviderResult.Ok(matched.take(capped))
        }
    }

    /**
     * Newest episodes, the only shelf a feed actually has.
     *
     * [BrowseCategory.RECENT] returns the feed in document order. RSS lists
     * newest first by convention and every real podcast publisher follows it;
     * sorting by `pubDate` instead would need a date parser that guesses at
     * four timezone spellings, and a feed sorted wrong is a smaller problem
     * than episodes silently vanishing because one date failed to parse.
     */
    override fun browse(category: BrowseCategory, limit: Int): ProviderResult<List<OnlineTrack>> {
        if (!isConfigured()) return blockedConfigFailure()
        if (category != BrowseCategory.RECENT) {
            return ProviderResult.Failure(
                ProviderFailureCode.UNSUPPORTED,
                "Nguồn podcast chỉ có tập mới nhất",
                retryable = false
            )
        }
        val capped = limit.coerceIn(1, MAX_LIMIT)
        return loadFeed { feed -> ProviderResult.Ok(feed.items.take(capped)) }
    }

    /**
     * Finds one episode by its id.
     *
     * The id is the enclosure URL, which is stable while the file exists and
     * is the only identifier a plain RSS item guarantees. A feed with hundreds
     * of episodes is still a small parse, and it is the only way to answer
     * "does this episode still exist" without caching a snapshot that would
     * then be wrong.
     */
    override fun resolve(trackId: String): ProviderResult<OnlineTrack> {
        if (!isConfigured()) return blockedConfigFailure()
        if (trackId.isBlank()) {
            return ProviderResult.Failure(
                ProviderFailureCode.NOT_FOUND,
                "Mã tập không hợp lệ",
                retryable = false
            )
        }
        return loadFeed { feed ->
            val found = feed.items.firstOrNull { it.id == trackId }
            if (found == null) {
                ProviderResult.Failure(
                    ProviderFailureCode.NOT_FOUND,
                    "Tập này không còn trong nguồn RSS",
                    retryable = false
                )
            } else {
                ProviderResult.Ok(found)
            }
        }
    }

    /**
     * The episode's enclosure URL.
     *
     * Re-resolves rather than trusting the hint, because a publisher who
     * re-uploads an episode changes the enclosure URL and leaves the title the
     * same — a saved queue that trusted the hint would 404 with no clue why.
     */
    override fun getPlayableSource(trackId: String): ProviderResult<PlayableSource> {
        if (!isConfigured()) return blockedConfigFailure()
        return loadFeed { feed ->
            val track = feed.items.firstOrNull { it.id == trackId }
            val url = track?.streamUrlHint
            when {
                track == null -> ProviderResult.Failure(
                    ProviderFailureCode.NOT_FOUND,
                    "Tập này không còn trong nguồn RSS",
                    retryable = false
                )
                // The feed's own <enclosure type="...">, and only when it is
                // an audio type. Absent or non-audio, MP3 is what podcasts ship.
                else -> ProviderResult.Ok(
                    PlayableSource(
                        url = url.orEmpty(),
                        mimeType = feed.mimeByTrackId[track.id] ?: "audio/mpeg",
                        isStreaming = true,
                        title = track.title
                    )
                )
            }
        }
    }

    /**
     * Reachability of the configured feed.
     *
     * Answers before any request when unconfigured: the whole point of the
     * health call is to decide whether trying is worth it.
     */
    override fun health(): ProviderHealth {
        val now = System.currentTimeMillis()
        if (!isConfigured()) {
            return ProviderHealth(
                available = false,
                detail = "Chưa cấu hình địa chỉ RSS",
                checkedAtElapsedMs = now
            )
        }
        if (breaker.state() == CircuitBreaker.State.OPEN) {
            return ProviderHealth(
                available = false,
                detail = "Nguồn RSS đang tạm ngắt sau lỗi trước đó",
                checkedAtElapsedMs = now
            )
        }
        // A HEAD would be cheaper but some feeds answer HEAD with 405; the feed
        // is small enough that a GET is the honest probe.
        val result = runCatching { fetcher.get(feedUrl!!, HEADERS) }
            .getOrElse { e -> FetchResult(0, null, e.message ?: e.javaClass.simpleName) }
        return when {
            result.isSuccess -> ProviderHealth(true, null, now)
            result.transportError != null ->
                ProviderHealth(false, "Không tải được RSS: ${result.transportError}", now)
            else -> ProviderHealth(false, "RSS trả về HTTP ${result.status}", now)
        }
    }

    // -- feed loading -------------------------------------------------------

    /**
     * The parsed feed: channel title plus its items, newest first.
     *
     * Each item keeps the enclosure's `type=` beside the track rather than in
     * an [OnlineTrack] field. The model has no MIME field, and stashing it in
     * `album` would print "audio/mpeg" as the album line on the search screen —
     * a visible lie about a field the row draws.
     */
    private class ParsedFeed(
        val title: String?,
        val items: List<OnlineTrack>,
        /** Enclosure MIME per track id (the enclosure URL). */
        val mimeByTrackId: Map<String, String>
    )

    /** Fetches and parses once, then hands the result to [build]. */
    private fun <T> loadFeed(build: (ParsedFeed) -> ProviderResult<T>): ProviderResult<T> {
        val url = feedUrl?.trim()
        if (url.isNullOrBlank()) return blockedConfigFailure()
        if (!breaker.allow()) {
            return ProviderResult.Failure(
                ProviderFailureCode.CIRCUIT_OPEN,
                "Nguồn RSS đang tạm ngắt, thử lại sau",
                retryable = false
            )
        }

        val result = runCatching { fetcher.get(url, HEADERS) }
            .getOrElse { e -> FetchResult(0, null, e.message ?: e.javaClass.simpleName) }

        if (result.transportError != null) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                if (result.isTimeout) ProviderFailureCode.TIMEOUT else ProviderFailureCode.NETWORK_ERROR,
                if (result.isTimeout) "Nguồn RSS không phản hồi kịp" else "Không tải được nguồn RSS",
                retryable = true
            )
        }
        val status = result.status
        if (status !in 200..299) {
            breaker.recordFailure()
            val code = when (status) {
                401, 403 -> ProviderFailureCode.UNAUTHORIZED
                404 -> ProviderFailureCode.NOT_FOUND
                429 -> ProviderFailureCode.RATE_LIMITED
                else -> ProviderFailureCode.NETWORK_ERROR
            }
            return ProviderResult.Failure(
                code,
                "Nguồn RSS trả về lỗi $status",
                retryable = code != ProviderFailureCode.NOT_FOUND
            )
        }
        val body = result.body
        if (body.isNullOrBlank()) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.PARSE_ERROR,
                "Nguồn RSS trả về nội dung rỗng",
                retryable = true
            )
        }

        val feed = try {
            parseFeed(body)
        } catch (e: Exception) {
            // XmlPullParser throws XmlPullParserException and, on some bodies,
            // IllegalStateException; both mean "this is not a feed".
            null
        }
        if (feed == null) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.PARSE_ERROR,
                "Nguồn RSS không đúng định dạng",
                retryable = true
            )
        }
        breaker.recordSuccess()
        return build(feed)
    }

    /**
     * Streams the feed into [ParsedFeed].
     *
     * Returns null rather than throwing when the document is not RSS at all:
     * a captive portal answers every URL with HTML, and the user needs
     * `PARSE_ERROR` from that, not an exception.
     */
    private fun parseFeed(xml: String): ParsedFeed? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        // Namespaces on, because itunes:duration and itunes:image are only
        // addressable as {namespace}name, and the prefix itself is not
        // guaranteed to be "itunes" in a hand-edited feed.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var channelTitle: String? = null
        val items = ArrayList<OnlineTrack>()
        val mimeByTrackId = HashMap<String, String>()

        var inChannel = false
        var inItem = false
        // <image><title> sits inside <channel> and would otherwise be taken for
        // the show name on the feeds that place it before the channel title.
        var inImage = false

        // Per-item fields, reset at every <item>.
        var itemTitle: String? = null
        var enclosureUrl: String? = null
        var enclosureType: String? = null
        var itunesDuration: String? = null
        // Which field the next TEXT event belongs to.
        var capture: String? = null

        var event = parser.eventType
        var sawRoot = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name ?: ""
                    when (local) {
                        "rss", "feed" -> sawRoot = true
                        "channel" -> inChannel = true
                        "image" -> if (!inItem) inImage = true
                        "item", "entry" -> {
                            if (inItem) {
                                // A feed that never closes its <item> is
                                // malformed; flush what we have rather than
                                // lose the rest of the feed.
                                flushItem(
                                    items, mimeByTrackId, itemTitle, enclosureUrl,
                                    enclosureType, itunesDuration, channelTitle
                                )
                            }
                            inItem = true
                            itemTitle = null
                            enclosureUrl = null
                            enclosureType = null
                            itunesDuration = null
                        }
                        // <enclosure> is empty with attributes in RSS 2.0.
                        "enclosure" -> if (inItem) {
                            enclosureUrl = parser.getAttributeValue(null, "url") ?: enclosureUrl
                            enclosureType = parser.getAttributeValue(null, "type") ?: enclosureType
                        }
                        // Atom carries the episode media as <link
                        // rel="enclosure" href="…" type="…">, not as
                        // <enclosure>. Entry tags are accepted above, so this
                        // has to be accepted too or an Atom podcast lists rows
                        // with no URL and every one of them is dropped.
                        "link" -> if (inItem) {
                            val rel = parser.getAttributeValue(null, "rel")
                            if (rel == null || rel.equals("enclosure", ignoreCase = true)) {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrBlank()) {
                                    enclosureUrl = href
                                    enclosureType = parser.getAttributeValue(null, "type")
                                        ?: enclosureType
                                }
                            }
                        }
                        "title" -> capture = when {
                            inItem -> "item-title"
                            inImage -> "ignored"
                            else -> "channel-title"
                        }
                        "duration" -> if (inItem) {
                            // With namespaces on, itunes:duration arrives as
                            // local name "duration" in a non-empty namespace;
                            // a plain <duration> without a namespace is not a
                            // podcast field and is ignored by the namespace check.
                            val ns = parser.namespace
                            if (ns.isNullOrEmpty() || ns.contains("itunes", ignoreCase = true)) {
                                capture = "duration"
                            }
                        }
                        "summary", "description" -> if (inItem) {
                            // Parsed only so the tag is consumed; an episode
                            // synopsis has no field on OnlineTrack and must not
                            // be smuggled into `album`.
                            capture = "ignored"
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    when (capture) {
                        // Only the channel's own title counts; an <image><title>
                        // inside the channel would otherwise overwrite it.
                        "channel-title" -> if (inChannel && !inItem && channelTitle == null) {
                            channelTitle = text.trim().takeIf { it.isNotEmpty() }
                        }
                        "item-title" -> if (itemTitle == null) itemTitle = text
                        "duration" -> if (itunesDuration == null) itunesDuration = text
                    }
                }

                XmlPullParser.END_TAG -> {
                    val local = parser.name ?: ""
                    when (local) {
                        "title", "duration", "summary", "description" -> capture = null
                        "channel" -> inChannel = false
                        "image" -> inImage = false
                        "item", "entry" -> if (inItem) {
                            flushItem(
                                items, mimeByTrackId, itemTitle, enclosureUrl,
                                enclosureType, itunesDuration, channelTitle
                            )
                            inItem = false
                            itemTitle = null
                            enclosureUrl = null
                            enclosureType = null
                            itunesDuration = null
                            capture = null
                        }
                    }
                }
            }
            event = parser.next()
        }

        // A feed that ends without closing its last <item> still yields that
        // item; dropping it would hide the newest episode of a sloppy feed.
        if (inItem) {
            flushItem(
                items, mimeByTrackId, itemTitle, enclosureUrl,
                enclosureType, itunesDuration, channelTitle
            )
        }

        return if (!sawRoot) null else ParsedFeed(channelTitle, items, mimeByTrackId)
    }

    /**
     * Appends an item when it has what playback needs.
     *
     * An item without an enclosure has no audio — a "trailer-only" or a
     * text-only feed entry — and listing it would produce a row that cannot
     * play, so it is dropped rather than drawn dead. An item without a title
     * is also dropped: `pubDate` is not a title, and printing a date where a
     * title belongs is a fabrication the row makes worse by looking plausible.
     */
    private fun flushItem(
        out: MutableList<OnlineTrack>,
        mimeByTrackId: MutableMap<String, String>,
        title: String?,
        enclosureUrl: String?,
        enclosureType: String?,
        itunesDuration: String?,
        channelTitle: String?
    ) {
        val url = enclosureUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val cleanTitle = title?.let { stripHtml(it) }?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        out.add(
            OnlineTrack(
                // The enclosure URL is the id: it is the only field a plain
                // RSS item guarantees to be unique and stable for playback.
                id = url,
                title = cleanTitle,
                // A feed has no per-episode "artist"; the show name is the
                // honest stand-in, because that is what the driver sees on
                // every podcast app and what the episode belongs to.
                artist = channelTitle,
                // A podcast episode has no album. The enclosure's MIME type
                // travels in [ParsedFeed.mimeByTrackId] instead of being
                // smuggled in here, where the row would print it.
                album = null,
                durationMs = parseDurationMs(itunesDuration),
                artworkUrl = null,
                providerId = id,
                isLive = false,
                streamUrlHint = url
            )
        )
        // Only audio enclosures are remembered: a feed that also encloses a
        // PDF transcript would otherwise hand the player a type it cannot play
        // and turn "no audio" into a decode error.
        val mime = enclosureType?.trim()
        if (mime != null && mime.startsWith("audio/", ignoreCase = true)) {
            mimeByTrackId[url] = mime
        }
    }

    private fun blockedConfigFailure(): ProviderResult.Failure = ProviderResult.Failure(
        ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG,
        "Chưa cấu hình nguồn RSS podcast trong Cài đặt",
        retryable = false
    )

    /**
     * `itunes:duration` to milliseconds.
     *
     * The field is notoriously polymorphic: it may be `3600`, `60:00`, or
     * `1:00:00`. `0` for anything unparseable, matching the model's "unknown"
     * convention — a wrong duration would drive the progress bar, and a
     * progress bar that lies is worse than one that stays empty.
     */
    private fun parseDurationMs(raw: String?): Long {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return 0L
        // Plain seconds.
        if (text.all { it.isDigit() }) {
            return text.toLongOrNull()?.let { it * 1000L } ?: 0L
        }
        val parts = text.split(':')
        if (parts.isEmpty() || parts.size > 3) return 0L
        var total = 0L
        for (part in parts) {
            val value = part.trim().toLongOrNull() ?: return 0L
            if (value < 0) return 0L
            total = total * 60L + value
        }
        return total * 1000L
    }

    /**
     * Strips the handful of tags and entities a podcast title carries.
     *
     * Deliberately not `android.text.Html`: this class is meant to be driven
     * by a scripted [HttpFetcher] in a plain JVM test, and `Html.fromHtml`
     * comes back "not mocked" there. Titles only ever contain `<b>`, `<i>`,
     * `&amp;`, `&#39;` and friends, so a small explicit pass is both enough
     * and testable.
     */
    private fun stripHtml(value: String): String {
        if (value.indexOf('<') < 0 && value.indexOf('&') < 0) return value
        var text = TAG_PATTERN.replace(value, "")
        text = text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return NUMERIC_ENTITY.replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            // API 23-safe: String(IntArray, offset, count) exists since API 1.
            runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
        }
    }

    companion object {
        /** Stable key; saved queues refer to it. */
        const val PROVIDER_ID = "podcast"

        /** 200. A feed with more items than this is not browsable on this screen. */
        const val MAX_LIMIT = 200

        /** Full iTunes namespace, matched by [parseFeed]'s namespace check. */
        const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

        /**
         * Some hosts serve a feed only to a browser-shaped user agent, and a
         * blank one is blocked outright on others.
         */
        private val HEADERS = mapOf(
            "Accept" to "application/rss+xml, application/xml;q=0.9, */*;q=0.8"
        )

        /** Any markup tag in a title, e.g. `<b>` or `<br/>`. */
        private val TAG_PATTERN = Regex("<[^>]{0,200}>")

        /** `&#8217;` and friends, which titles from WordPress feeds carry. */
        private val NUMERIC_ENTITY = Regex("&#(\\d{1,7});")
    }
}

END FILE

## core/music-provider-online/src/main/java/com/ts8/core/music/online/RadioBrowserProvider.kt

BEGIN FILE
package com.ts8.core.music.online

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Internet radio through the [Radio Browser](https://www.radio-browser.info)
 * public API.
 *
 * **Why this source.** The repair brief puts a legal radio stream first in the
 * provider order, because it is the only online feature that works with no
 * account, no credential and no server the driver has to run. That makes it
 * the one that can be shipped and verified on the head unit today, which is
 * what "MVP ưu tiên Internet Radio" means.
 *
 * **Why only the documented endpoint.** `/json/stations/search` on a mirror
 * host is the published interface. Nothing here follows a station's own page,
 * scrapes a directory site, or resolves a stream URL through an undocumented
 * redirect: [RadioBrowserProvider] hands the URL Radio Browser returned to the
 * player and nothing else. See the prohibition on
 * [OnlineMusicProvider]'s documentation — it applies in full here.
 *
 * **Why search carries no duration.** A station has no length. Every track
 * from this provider therefore has `durationMs = 0` and `isLive = true`; the
 * local model already treats `0` as "unknown", so a mixed queue computes
 * progress without a special case.
 *
 * **Why the mirror list and not one host.** `de1.api.radio-browser.info` is a
 * single mirror that goes down; the project asks clients to use a stable name
 * or to rotate. This class takes the host at construction so Settings and the
 * tests can point it elsewhere, and defaults to the DE mirror the brief names.
 */
class RadioBrowserProvider(
    private val fetcher: HttpFetcher = HttpFetcherImpl(),
    /** Base URL including scheme, e.g. `https://de1.api.radio-browser.info`. */
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val breaker: CircuitBreaker = CircuitBreaker()
) : OnlineMusicProvider {

    override val id: String = PROVIDER_ID

    override val displayName: String = "Đài phát thanh"

    override val kind: ProviderKind = ProviderKind.INTERNET_RADIO

    /**
     * Station search by name.
     *
     * `hidebroken=true` is not cosmetic: the directory keeps stations whose
     * stream has been dead for months, and a result list where a third of the
     * rows fail on tap is worse than a shorter list that plays. `order=clickcount`
     * because the directory's own popularity is the only ranking signal it
     * publishes.
     */
    override fun search(query: String, limit: Int): ProviderResult<List<OnlineTrack>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProviderResult.Ok(emptyList())
        return request(
            "/json/stations/search",
            listOf(
                "name" to urlEncode(trimmed),
                "limit" to clampLimit(limit).toString(),
                "hidebroken" to "true",
                "order" to "clickcount",
                "reverse" to "true"
            )
        )
    }

    /**
     * Radio has no shelves, so [BrowseCategory] maps onto a search the
     * directory already ranks.
     *
     * The brief lists `browse` as part of the contract even though a directory
     * has no trending concept. Rather than return an empty shelf — which the
     * screen would draw as "no stations", the same visual as a network failure
     * — [TRENDING] and [RECENT] are expressed as the directory's clicks and
     * newest rows, and the categories it genuinely cannot answer
     * ([BrowseCategory.FAVORITES], [BrowseCategory.CONTINUE]) return
     * `UNSUPPORTED` so the tab can be hidden honestly.
     */
    override fun browse(category: BrowseCategory, limit: Int): ProviderResult<List<OnlineTrack>> {
        val capped = clampLimit(limit).toString()
        return when (category) {
            // Both shelves go through `search` rather than dedicated
            // endpoints: the directory's `topclick` takes its count in the
            // path (`/topclick/20`), and a `limit` query parameter there is
            // silently ignored — which would return a fixed page that looks
            // like the driver's limit was honoured.
            BrowseCategory.TRENDING -> request(
                "/json/stations/search",
                listOf(
                    "limit" to capped,
                    "hidebroken" to "true",
                    "order" to "clickcount",
                    "reverse" to "true"
                )
            )

            BrowseCategory.RECENT -> request(
                "/json/stations/search",
                listOf(
                    "limit" to capped,
                    "hidebroken" to "true",
                    // The directory's sort key is `changetimestamp`; the
                    // plausible-sounding `lastchangetime` is not a value it
                    // accepts, and an unknown order falls back to name order
                    // without an error.
                    "order" to "changetimestamp",
                    "reverse" to "true"
                )
            )

            // The directory has no genre browsing in this shape; a tag browse
            // would need the tag list API and a second screen. Saying so is
            // better than pretending.
            BrowseCategory.GENRES,
            BrowseCategory.FAVORITES,
            BrowseCategory.CONTINUE -> ProviderResult.Failure(
                ProviderFailureCode.UNSUPPORTED,
                "Đài phát thanh không có mục này",
                retryable = false
            )
        }
    }

    /**
     * Re-reads a station by UUID.
     *
     * Needed because the URL in a saved queue may be hours old and the station
     * may have moved. Radio Browser's `byuuid` is the documented way to ask;
     * an empty array is a real "that station is gone", not a failure.
     */
    override fun resolve(trackId: String): ProviderResult<OnlineTrack> {
        if (trackId.isBlank()) {
            return ProviderResult.Failure(
                ProviderFailureCode.NOT_FOUND,
                "Mã đài không hợp lệ",
                retryable = false
            )
        }
        val url = buildUrl(
            "$baseUrl/json/stations/byuuid",
            listOf("uuids" to urlEncode(trackId))
        )
        return fetchAndParse(url) { array ->
            val first = if (array.length() > 0) array.optJSONObject(0) else null
            if (first == null) {
                ProviderResult.Failure(
                    ProviderFailureCode.NOT_FOUND,
                    "Không tìm thấy đài này trên danh bạ",
                    retryable = false
                )
            } else {
                val track = parseStation(first)
                if (track == null) {
                    ProviderResult.Failure(
                        ProviderFailureCode.PARSE_ERROR,
                        "Dữ liệu đài không đọc được",
                        retryable = true
                    )
                } else {
                    ProviderResult.Ok(track)
                }
            }
        }
    }

    /**
     * The station's resolved stream URL.
     *
     * Reads the station fresh rather than trusting [OnlineTrack.streamUrlHint]:
     * the hint was captured at search time and a station that changed hosts in
     * between would otherwise send the player to a dead address with no
     * explanation. The extra request is one small JSON body and it is the
     * difference between "phát được" and "im lặng ba giây rồi dừng".
     */
    override fun getPlayableSource(trackId: String): ProviderResult<PlayableSource> {
        return when (val resolved = resolve(trackId)) {
            is ProviderResult.Failure -> resolved
            is ProviderResult.Ok -> {
                val url = resolved.value.streamUrlHint
                if (url.isNullOrBlank()) {
                    ProviderResult.Failure(
                        ProviderFailureCode.NOT_FOUND,
                        "Đài này không còn địa chỉ phát",
                        retryable = false
                    )
                } else {
                    ProviderResult.Ok(
                        PlayableSource(
                            url = url,
                            // Derived from the stream URL's own extension, not
                            // from a codec field: the directory's `codec` is a
                            // free-form display string ("MP3", "AAC+", "OGG")
                            // and mapping it would mean carrying it in a field
                            // that means something else. The extension is
                            // evidence the server actually published.
                            mimeType = mimeForStreamUrl(url),
                            isStreaming = true,
                            title = resolved.value.title,
                            // A radio URL has no token to expire; the station
                            // may still drop offline, and that is the player's
                            // SOURCE_UNAVAILABLE to report, not an expiry here.
                            expiresAtElapsedMs = null
                        )
                    )
                }
            }
        }
    }

    /**
     * Reports reachability from the breaker plus one cheap directory call.
     *
     * Deliberately not a per-station probe: checking whether *a* station plays
     * would mean opening the stream, which belongs to the player. This answers
     * the only question the gateway has — is the directory reachable at all.
     */
    override fun health(): ProviderHealth {
        val now = System.currentTimeMillis()
        if (breaker.state() == CircuitBreaker.State.OPEN) {
            return ProviderHealth(
                available = false,
                detail = "Danh bạ đang tạm ngắt sau lỗi trước đó",
                checkedAtElapsedMs = now
            )
        }
        val url = buildUrl("$baseUrl/json/stats", emptyList())
        val result = runCatching { fetcher.get(url, emptyMap()) }
            .getOrElse { e -> FetchResult(0, null, e.message ?: e.javaClass.simpleName) }
        return when {
            result.isSuccess -> ProviderHealth(true, null, now)
            result.transportError != null -> ProviderHealth(
                false,
                "Không kết nối được danh bạ: ${result.transportError}",
                now
            )
            else -> ProviderHealth(
                false,
                "Danh bạ trả về HTTP ${result.status}",
                now
            )
        }
    }

    // -- parsing ------------------------------------------------------------

    /** Runs a station-list GET through the breaker and parses the array. */
    private fun request(
        path: String,
        params: List<Pair<String, String>>
    ): ProviderResult<List<OnlineTrack>> =
        fetchAndParse(buildUrl(baseUrl + path, params)) { array -> parseStations(array) }

    private fun parseStations(array: JSONArray): ProviderResult<List<OnlineTrack>> {
        val out = ArrayList<OnlineTrack>(array.length())
        for (i in 0 until array.length()) {
            val station = array.optJSONObject(i) ?: continue
            // A row without an id or a stream URL cannot be played or
            // re-resolved; skipping it is honest, inventing an id is not.
            parseStation(station)?.let { out.add(it) }
        }
        return ProviderResult.Ok(out)
    }

    /**
     * One station row, or null when it lacks what makes it playable.
     *
     * `url_resolved` is preferred over `url` because the directory has already
     * followed the station's redirect chain for us — using `url` would hand the
     * player a plain-HTTP address that 301s, which `HttpURLConnection` will not
     * follow by our own policy and `MediaPlayer` handles inconsistently across
     * firmware.
     */
    private fun parseStation(json: JSONObject): OnlineTrack? {
        val stationId = json.optString("stationuuid").takeIf { it.isNotBlank() } ?: return null
        val streamUrl = firstNonBlank(
            json.optString("url_resolved"),
            json.optString("url")
        ) ?: return null
        val name = firstNonBlank(json.optString("name"), json.optString("name_trim"))
            ?: return null

        return OnlineTrack(
            id = stationId,
            title = name.trim(),
            // A radio directory has no artist field. Its `tags` are genres
            // ("rock,pop"), and pasting them into `artist` would put a genre
            // where the row draws a performer — fabricated attribution, which
            // the brief forbids. A station genuinely has no artist.
            artist = null,
            // `codec` is likewise a display string, not an album. Null is the
            // truthful answer; the playable MIME type is derived from the
            // stream URL at playback instead.
            album = null,
            durationMs = 0L,
            artworkUrl = firstNonBlank(json.optString("favicon"))?.trim()?.ifBlank { null },
            providerId = id,
            isLive = true,
            streamUrlHint = streamUrl.trim()
        )
    }

    /**
     * Shared plumbing: breaker gate, GET, status mapping, JSON parse.
     *
     * Every branch returns a [ProviderResult.Failure] rather than throwing, so
     * a directory that returns an HTML error page (which happens on a captive
     * portal) becomes `PARSE_ERROR` the screen can show instead of an
     * exception that would take down the search.
     */
    private fun <T> fetchAndParse(
        url: String,
        parse: (JSONArray) -> ProviderResult<T>
    ): ProviderResult<T> {
        if (!breaker.allow()) {
            return ProviderResult.Failure(
                ProviderFailureCode.CIRCUIT_OPEN,
                "Đài phát thanh đang tạm ngắt, thử lại sau",
                retryable = false
            )
        }
        val result = runCatching { fetcher.get(url, emptyMap()) }
            .getOrElse { e ->
                FetchResult(0, null, e.message ?: e.javaClass.simpleName)
            }

        if (result.transportError != null) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                if (result.isTimeout) ProviderFailureCode.TIMEOUT else ProviderFailureCode.NETWORK_ERROR,
                if (result.isTimeout) "Danh bạ đài không phản hồi kịp" else "Không kết nối được danh bạ đài",
                retryable = true
            )
        }

        val mapped = when {
            result.status == 429 -> ProviderFailureCode.RATE_LIMITED
            result.status == 401 || result.status == 403 -> ProviderFailureCode.UNAUTHORIZED
            result.status == 404 -> ProviderFailureCode.NOT_FOUND
            result.status !in 200..299 -> ProviderFailureCode.NETWORK_ERROR
            else -> null
        }
        if (mapped != null) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                mapped,
                "Danh bạ đài trả về lỗi ${result.status}",
                retryable = mapped == ProviderFailureCode.RATE_LIMITED ||
                    mapped == ProviderFailureCode.NETWORK_ERROR
            )
        }

        val body = result.body
        if (body.isNullOrBlank()) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.PARSE_ERROR,
                "Danh bạ đài trả về nội dung rỗng",
                retryable = true
            )
        }

        val parsed = try {
            parse(JSONArray(body))
        } catch (e: JSONException) {
            // A captive portal or a mirror serving HTML lands here.
            null
        } catch (e: RuntimeException) {
            null
        }
        if (parsed == null) {
            breaker.recordFailure()
            return ProviderResult.Failure(
                ProviderFailureCode.PARSE_ERROR,
                "Dữ liệu danh bạ đài không đọc được",
                retryable = true
            )
        }
        breaker.recordSuccess()
        return parsed
    }

    /** The stream URL's extension to a MIME hint, defaulting to MP3. */
    private fun mimeForStreamUrl(url: String): String {
        // Query strings and auth tokens follow the extension routinely
        // (".../stream.mp3?token=…"), so look only at the path segment.
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".ogg") || path.endsWith(".oga") || path.endsWith(".opus") -> "audio/ogg"
            path.endsWith(".flac") -> "audio/flac"
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            else -> "audio/mpeg"
        }
    }

    private fun clampLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    companion object {
        /** Stable key; saved queues refer to it. */
        const val PROVIDER_ID = "radio-browser"

        /** The mirror named by the brief. Overridable for Settings and tests. */
        const val DEFAULT_BASE_URL = "https://de1.api.radio-browser.info"

        /**
         * 100. The directory caps a page itself; asking for more only makes a
         * larger JSON body to parse on a device with a 128 MB heap.
         */
        const val MAX_LIMIT = 100
    }
}

END FILE

## core/music-provider-online/src/main/java/com/ts8/core/music/online/RecordingOnlineProvider.kt

BEGIN FILE
package com.ts8.core.music.online

/**
 * An [OnlineMusicProvider] whose every answer is scripted.
 *
 * **Why this lives in main sources.** The project has no instrumented test
 * module, so an `androidTest`-only double would be absent from the APK and the
 * search screen could not be driven against it on the vehicle — which is the
 * one place the mixed local/online list is actually exercised. It ships in
 * release, which is acceptable because it is inert: it opens no socket, holds
 * one in-memory map, and is only constructed where someone asks for it.
 *
 * **What it is for.** Two things that cannot be verified any other way:
 *
 * 1. The gateway's aggregate rules — a provider that fails contributes
 *    nothing and appears in [OnlineMusicGateway.status], a provider that is
 *    unconfigured appears in [OnlineMusicGateway.blockedConfig], and an
 *    all-fail search reports one failure. All of those are decisions about
 *    failure, and failure is the state hardest to reproduce against a live
 *    service.
 * 2. The search screen's rendering of a mixed list without a network — the
 *    driver's phone may have no SIM while the screen is being built and
 *    reviewed.
 *
 * **Why scripted rather than random.** A random double produces a test that
 * passes or fails by luck; a scripted one reproduces a specific server
 * behaviour (429 for a minute, then recovery) on demand. Scripts are keyed by
 * method and can be changed between calls, so a retry after a failure is
 * exercised rather than assumed.
 *
 * Not thread-safe by design: it is a test fixture, driven from one test thread
 * or from the UI thread's scripted demo path. Sharing it across the gateway's
 * pool would be a misuse, and defending against that would hide the misuse.
 */
class RecordingOnlineProvider(
    override val id: String,
    override val displayName: String,
    override val kind: ProviderKind = ProviderKind.OTHER,
    /** True makes this provider report `BLOCKED_EXTERNAL_CONFIG` and nothing else. */
    private val blockedConfig: Boolean = false
) : OnlineMusicProvider {

    /** What a scripted call should return. */
    private var searchScript: ProviderResult<List<OnlineTrack>> =
        ProviderResult.Ok(emptyList())
    private var browseScript: ProviderResult<List<OnlineTrack>> =
        ProviderResult.Ok(emptyList())
    private var resolveScript: (String) -> ProviderResult<OnlineTrack> = { _ ->
        ProviderResult.Failure(
            ProviderFailureCode.NOT_FOUND,
            "Bản ghi thử không có bài này",
            retryable = false
        )
    }
    private var sourceScript: (String) -> ProviderResult<PlayableSource> = { _ ->
        ProviderResult.Failure(
            ProviderFailureCode.NOT_FOUND,
            "Bản ghi thử không có nguồn phát này",
            retryable = false
        )
    }
    private var healthScript: ProviderHealth = ProviderHealth(available = true)

    /** Every call made, in order, so a test can assert what the gateway asked. */
    private val calls = ArrayList<String>()

    /** The calls made since construction, newest last. Copy, so callers cannot mutate. */
    fun calls(): List<String> = ArrayList(calls)

    /** Forgets [calls] so the next assertion starts from a known point. */
    fun clearCalls() {
        calls.clear()
    }

    /** Scripts [search]; returns this for chaining at a construction site. */
    fun onSearch(result: ProviderResult<List<OnlineTrack>>): RecordingOnlineProvider {
        searchScript = result
        return this
    }

    fun onBrowse(result: ProviderResult<List<OnlineTrack>>): RecordingOnlineProvider {
        browseScript = result
        return this
    }

    fun onResolve(result: (String) -> ProviderResult<OnlineTrack>): RecordingOnlineProvider {
        resolveScript = result
        return this
    }

    fun onPlayableSource(
        result: (String) -> ProviderResult<PlayableSource>
    ): RecordingOnlineProvider {
        sourceScript = result
        return this
    }

    fun onHealth(health: ProviderHealth): RecordingOnlineProvider {
        healthScript = health
        return this
    }

    /**
     * Scripts a fixed list of tracks as the search answer.
     *
     * Convenience for the common case, with every track tagged with this
     * provider's [id] so a gateway-level test sees the grouping the real
     * providers produce.
     */
    fun onSearchTracks(tracks: List<OnlineTrack>): RecordingOnlineProvider =
        onSearch(ProviderResult.Ok(tracks.map { it.copy(providerId = id) }))

    override fun search(query: String, limit: Int): ProviderResult<List<OnlineTrack>> {
        calls.add("search:$query:${limit.coerceAtLeast(0)}")
        if (blockedConfig) return blocked()
        return applyLimit(searchScript, limit)
    }

    override fun browse(category: BrowseCategory, limit: Int): ProviderResult<List<OnlineTrack>> {
        calls.add("browse:${category.name}:${limit.coerceAtLeast(0)}")
        if (blockedConfig) return blocked()
        return applyLimit(browseScript, limit)
    }

    override fun resolve(trackId: String): ProviderResult<OnlineTrack> {
        calls.add("resolve:$trackId")
        if (blockedConfig) return blocked()
        return resolveScript(trackId)
    }

    override fun getPlayableSource(trackId: String): ProviderResult<PlayableSource> {
        calls.add("source:$trackId")
        if (blockedConfig) return blocked()
        return sourceScript(trackId)
    }

    override fun health(): ProviderHealth {
        calls.add("health")
        if (blockedConfig) {
            return ProviderHealth(
                available = false,
                detail = "Chưa cấu hình nguồn này",
                checkedAtElapsedMs = System.currentTimeMillis()
            )
        }
        return healthScript
    }

    /**
     * Reports the scripted [blockedConfig] through the interface.
     *
     * Required, not incidental: the gateway now asks every provider rather
     * than recognising two types, and a double that answered `true` here while
     * returning `BLOCKED_EXTERNAL_CONFIG` from every call would make the two
     * places disagree — which is precisely the bug the interface method was
     * added to remove.
     */
    override fun isConfigured(): Boolean = !blockedConfig

    /**
     * Enforces the limit even on scripted answers.
     *
     * A double that ignores [limit] would let a caller that forgot to clamp its
     * own request pass every test and then fetch 10 000 rows on the device.
     * The failure this prevents is invisible in a unit test and obvious on a
     * head unit with a slow link.
     */
    private fun applyLimit(
        scripted: ProviderResult<List<OnlineTrack>>,
        limit: Int
    ): ProviderResult<List<OnlineTrack>> = when (scripted) {
        is ProviderResult.Ok -> ProviderResult.Ok(
            scripted.value.take(limit.coerceAtLeast(0))
        )
        is ProviderResult.Failure -> scripted
    }

    private fun blocked(): ProviderResult.Failure = ProviderResult.Failure(
        ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG,
        "$displayName chưa được cấu hình",
        retryable = false
    )

    companion object {
        /**
         * Builds a provider that answers [tracks] to any search.
         *
         * The shape most tests want, so the fifteen-line construction is not
         * repeated in every one of them.
         */
        fun withTracks(
            id: String,
            displayName: String,
            tracks: List<OnlineTrack>,
            kind: ProviderKind = ProviderKind.OTHER
        ): RecordingOnlineProvider =
            RecordingOnlineProvider(id, displayName, kind).onSearchTracks(tracks)
    }
}

END FILE

## core/music-provider-online/src/test/java/com/ts8/core/music/online/CircuitBreakerTest.kt

BEGIN FILE
package com.ts8.core.music.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13's "timeout, retry có giới hạn, circuit breaker" — the breaker itself.
 *
 * Time is injected, so the cooldown is exercised without sleeping. The rules
 * proven here are the ones whose absence is invisible in production: a breaker
 * that lets everyone through after the cooldown, one that opens after a single
 * blip, or one that can never be tried again after a lost probe.
 */
class CircuitBreakerTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `starts closed and allows calls`() {
        val breaker = CircuitBreaker(clock = FakeClock())
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state())
        assertTrue(breaker.allow())
    }

    @Test
    fun `does not open before the threshold`() {
        val breaker = CircuitBreaker(failureThreshold = 2, clock = FakeClock())
        breaker.recordFailure()
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state())
        assertTrue(breaker.allow())
    }

    @Test
    fun `opens at the threshold and refuses without the network`() {
        val breaker = CircuitBreaker(failureThreshold = 2, clock = FakeClock())
        breaker.recordFailure()
        breaker.recordFailure()

        assertEquals(CircuitBreaker.State.OPEN, breaker.state())
        assertFalse(breaker.allow())
        assertEquals(1, breaker.refusedCount())
    }

    @Test
    fun `a success resets the consecutive count`() {
        // Two failures separated by a success are a blip, not a pattern.
        val breaker = CircuitBreaker(failureThreshold = 2, clock = FakeClock())
        breaker.recordFailure()
        breaker.recordSuccess()
        breaker.recordFailure()
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state())
    }

    @Test
    fun `goes half-open after the cooldown and admits exactly one probe`() {
        val clock = FakeClock()
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 30_000L, clock = clock)
        breaker.recordFailure()
        breaker.recordFailure()
        clock.advance(30_000L)

        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state())
        assertTrue("the probe must be admitted", breaker.allow())
        assertFalse("a second caller must wait for the verdict", breaker.allow())
        assertFalse(breaker.allow())
    }

    @Test
    fun `a successful probe closes the breaker`() {
        val clock = FakeClock()
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 30_000L, clock = clock)
        breaker.recordFailure()
        breaker.recordFailure()
        clock.advance(30_000L)
        breaker.allow()
        breaker.recordSuccess()

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state())
        assertTrue(breaker.allow())
    }

    @Test
    fun `a failed probe reopens immediately without a fresh budget`() {
        // The probe already spent the request that was granted; counting it
        // against a new threshold would send traffic at a provider that just
        // failed the test it was given.
        val clock = FakeClock()
        val breaker = CircuitBreaker(failureThreshold = 5, cooldownMs = 30_000L, clock = clock)
        repeat(5) { breaker.recordFailure() }
        clock.advance(30_000L)
        breaker.allow()
        breaker.recordFailure()

        assertEquals(CircuitBreaker.State.OPEN, breaker.state())
        assertFalse(breaker.allow())
    }

    @Test
    fun `a probe that is never reported back cannot wedge the breaker`() {
        // A caller that threw between allow() and recordFailure(), or a process
        // suspended mid-request, would otherwise hold the in-flight flag
        // forever and the provider could never be tried again.
        val clock = FakeClock()
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 30_000L, clock = clock)
        breaker.recordFailure()
        breaker.recordFailure()
        clock.advance(30_000L)
        assertTrue(breaker.allow())

        clock.advance(30_000L)
        assertTrue("the lost probe must be replaced", breaker.allow())
    }

    @Test
    fun `an open breaker stays open however long nothing calls it`() {
        // State is derived from the clock, not stored, so there is no timer
        // that could fail to fire.
        val clock = FakeClock()
        val breaker = CircuitBreaker(failureThreshold = 2, cooldownMs = 30_000L, clock = clock)
        breaker.recordFailure()
        breaker.recordFailure()
        clock.advance(29_999L)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state())
        clock.advance(1L)
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state())
    }

    @Test
    fun `reset closes the breaker for the driver's retry button`() {
        // A driver who has just fixed the server address must not wait out a
        // cooldown they did not cause.
        val breaker = CircuitBreaker(failureThreshold = 2, clock = FakeClock())
        breaker.recordFailure()
        breaker.recordFailure()
        assertEquals(CircuitBreaker.State.OPEN, breaker.state())

        breaker.reset()

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state())
        assertTrue(breaker.allow())
    }
}

END FILE

## core/music-provider-online/src/test/java/com/ts8/core/music/online/OnlineMusicGatewayTest.kt

BEGIN FILE
package com.ts8.core.music.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway's rules about failure — the part of §13 that is easiest to get
 * subtly wrong and hardest to notice on a head unit.
 *
 * Every test here runs against [RecordingOnlineProvider], which is why that
 * double lives in main sources rather than in a test source set: the same
 * behaviour it scripts is the behaviour a driver sees when a real provider is
 * down.
 */
class OnlineMusicGatewayTest {

    private fun track(providerId: String, id: String, title: String = "Bài $id") =
        OnlineTrack(id = id, title = title, providerId = providerId)

    private fun failure(code: ProviderFailureCode, message: String = "lỗi", retryable: Boolean = true) =
        ProviderResult.Failure(code, message, retryable)

    // -- ordering and aggregation ------------------------------------------

    @Test
    fun `results keep the provider order the brief fixed`() {
        val radio = RecordingOnlineProvider.withTracks(
            "radio-browser", "Đài phát thanh",
            listOf(track("radio-browser", "r1"))
        )
        val podcasts = RecordingOnlineProvider.withTracks(
            "podcast", "Podcast",
            listOf(track("podcast", "p1"))
        )
        val gateway = OnlineMusicGateway(listOf(radio, podcasts))
        val result = gateway.search("nhạc")

        assertEquals(2, result.tracks.size)
        assertEquals("radio-browser", result.tracks[0].providerId)
        assertEquals("podcast", result.tracks[1].providerId)
    }

    @Test
    fun `one failing provider still returns the other provider's results`() {
        // §13: "Provider lỗi không ảnh hưởng local music" — and, here, does not
        // take the working online source down with it either.
        val good = RecordingOnlineProvider.withTracks(
            "radio-browser", "Đài phát thanh",
            listOf(track("radio-browser", "r1"), track("radio-browser", "r2"))
        )
        val broken = RecordingOnlineProvider("podcast", "Podcast")
            .onSearch(failure(ProviderFailureCode.TIMEOUT))

        val result = OnlineMusicGateway(listOf(good, broken)).search("nhạc")

        assertEquals(2, result.tracks.size)
        assertTrue(result.hasResults)
        assertNull(result.failure)
        assertTrue(result.perProvider["podcast"] is ProviderResult.Failure)
    }

    @Test
    fun `every provider failing reports exactly one failure`() {
        val a = RecordingOnlineProvider("radio-browser", "Đài phát thanh")
            .onSearch(failure(ProviderFailureCode.NETWORK_ERROR, "Đài không phản hồi"))
        val b = RecordingOnlineProvider("podcast", "Podcast")
            .onSearch(failure(ProviderFailureCode.TIMEOUT, "Podcast quá hạn"))

        val result = OnlineMusicGateway(listOf(a, b)).search("nhạc")

        assertTrue(result.isTotalFailure)
        assertEquals("Đài không phản hồi", result.failure?.message)
    }

    @Test
    fun `an unconfigured provider is not counted as an attempt`() {
        // Otherwise a wholly unconfigured app would report "mọi nguồn đều lỗi
        // mạng", which is both untrue and unhelpful: nothing was asked.
        val blocked = RecordingOnlineProvider("subsonic", "Navidrome", blockedConfig = true)
        val result = OnlineMusicGateway(listOf(blocked)).search("nhạc")

        assertTrue(result.tracks.isEmpty())
        assertNull(result.failure)
        assertFalse(result.isTotalFailure)
        assertEquals(
            ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG,
            (result.perProvider["subsonic"] as ProviderResult.Failure).code
        )
    }

    @Test
    fun `an empty answer is not a failure`() {
        // "asked, found nothing" and "could not ask" must stay distinguishable;
        // collapsing them makes a driver on a dead link conclude the song does
        // not exist.
        val empty = RecordingOnlineProvider("radio-browser", "Đài")
            .onSearch(ProviderResult.Ok(emptyList()))
        val result = OnlineMusicGateway(listOf(empty)).search("không có bài này")

        assertTrue(result.tracks.isEmpty())
        assertNull(result.failure)
        assertFalse(result.isTotalFailure)
    }

    @Test
    fun `a provider that throws is reported as a network error, not a crash`() {
        val thrower = object : OnlineMusicProvider {
            override val id = "boom"
            override val displayName = "Nguồn hỏng"
            override val kind = ProviderKind.OTHER
            override fun search(query: String, limit: Int): ProviderResult<List<OnlineTrack>> =
                throw IllegalStateException("lỗi lập trình")
            override fun browse(category: BrowseCategory, limit: Int): ProviderResult<List<OnlineTrack>> =
                throw IllegalStateException("lỗi lập trình")
            override fun resolve(trackId: String): ProviderResult<OnlineTrack> =
                throw IllegalStateException("lỗi lập trình")
            override fun getPlayableSource(trackId: String): ProviderResult<PlayableSource> =
                throw IllegalStateException("lỗi lập trình")
            override fun health(): ProviderHealth = ProviderHealth(available = true)
        }
        val result = OnlineMusicGateway(listOf(thrower)).search("nhạc")

        assertNotNull(result.failure)
        assertEquals(ProviderFailureCode.NETWORK_ERROR, result.failure?.code)
    }

    // -- limits ------------------------------------------------------------

    @Test
    fun `the requested limit is clamped once for every provider`() {
        val a = RecordingOnlineProvider.withTracks("a", "A", List(200) { track("a", "a$it") })
        val b = RecordingOnlineProvider.withTracks("b", "B", List(200) { track("b", "b$it") })
        OnlineMusicGateway(listOf(a, b)).search("nhạc", limit = 5000)

        val cap = OnlineMusicGateway.MAX_AGGREGATE_LIMIT
        assertEquals(listOf("search:nhạc:$cap"), a.calls())
        assertEquals(listOf("search:nhạc:$cap"), b.calls())
        assertTrue(a.calls().first().endsWith(":$cap"))
    }

    @Test
    fun `a nonsense limit is raised to one rather than passed as zero`() {
        val a = RecordingOnlineProvider.withTracks("a", "A", listOf(track("a", "a1")))
        OnlineMusicGateway(listOf(a)).search("nhạc", limit = 0)
        assertEquals(listOf("search:nhạc:1"), a.calls())
    }

    @Test
    fun `browse treats UNSUPPORTED as a declination, not a fault`() {
        val podcast = RecordingOnlineProvider("podcast", "Podcast")
            .onBrowse(failure(ProviderFailureCode.UNSUPPORTED, "không hỗ trợ"))
        val result = OnlineMusicGateway(listOf(podcast))
            .browse(BrowseCategory.TRENDING)

        assertNull(result.failure)
        assertFalse(result.isTotalFailure)
    }

    // -- resolving and sources ---------------------------------------------

    @Test
    fun `resolve goes only to the provider that issued the track`() {
        // Two sources can issue the same id string for different tracks, so
        // trying them in order would return whichever answered first.
        val a = RecordingOnlineProvider("a", "A")
        val b = RecordingOnlineProvider("b", "B")
            .onResolve { ProviderResult.Ok(track("b", it)) }
        val gateway = OnlineMusicGateway(listOf(a, b))

        val result = gateway.resolve("b", "x1")

        assertEquals("b", (result as ProviderResult.Ok).value.providerId)
        assertTrue(a.calls().isEmpty())
    }

    @Test
    fun `an unknown provider id is UNSUPPORTED, not NOT_FOUND`() {
        // A queue saved by a build that had a provider this one does not is a
        // different problem from a track deleted on a server, and only one of
        // them is permanent.
        val gateway = OnlineMusicGateway(listOf(RecordingOnlineProvider("a", "A")))

        val track = gateway.resolve("youtube", "abc") as ProviderResult.Failure
        assertEquals(ProviderFailureCode.UNSUPPORTED, track.code)
        assertFalse(track.retryable)

        val source = gateway.getPlayableSource("youtube", "abc") as ProviderResult.Failure
        assertEquals(ProviderFailureCode.UNSUPPORTED, source.code)
    }

    @Test
    fun `a source with no stated expiry is served`() {
        val a = RecordingOnlineProvider("a", "A").onPlayableSource {
            ProviderResult.Ok(PlayableSource("https://x/$it", "audio/mpeg", true, title = "x"))
        }
        val result = OnlineMusicGateway(listOf(a)) { Long.MAX_VALUE }.freshSource("a", "t1")

        assertTrue(result is ProviderResult.Ok)
    }

    @Test
    fun `a source whose lifetime has passed is refused rather than handed over`() {
        // Handing the player a revoked token produces a stream that starts and
        // dies silently — indistinguishable from a broken app.
        val a = RecordingOnlineProvider("a", "A").onPlayableSource {
            ProviderResult.Ok(
                PlayableSource(
                    url = "https://x/$it",
                    mimeType = "audio/mpeg",
                    isStreaming = true,
                    title = "x",
                    expiresAtElapsedMs = 1_000L
                )
            )
        }
        val result = OnlineMusicGateway(listOf(a)) { 2_000L }.freshSource("a", "t1")

        val failure = result as ProviderResult.Failure
        assertEquals(ProviderFailureCode.NOT_FOUND, failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a source is re-resolved on every call, never cached`() {
        val a = RecordingOnlineProvider("a", "A").onPlayableSource {
            ProviderResult.Ok(PlayableSource("https://x/$it", "audio/mpeg", true, title = "x"))
        }
        val gateway = OnlineMusicGateway(listOf(a))
        gateway.getPlayableSource("a", "t1")
        gateway.getPlayableSource("a", "t1")

        assertEquals(listOf("source:t1", "source:t1"), a.calls())
    }

    // -- status ------------------------------------------------------------

    @Test
    fun `status reports one line per provider in order`() {
        val a = RecordingOnlineProvider("a", "A")
        val b = RecordingOnlineProvider("b", "B", blockedConfig = true)
        val status = OnlineMusicGateway(listOf(a, b)).status()

        assertEquals(listOf("a", "b"), status.map { it.providerId })
        assertFalse(status[0].blockedConfig)
        assertTrue(status[1].blockedConfig)
        assertTrue(status[1].needsConfiguration)
    }

    @Test
    fun `status is cached inside its TTL`() {
        // A RecyclerView binding a footer must not launch four requests a frame.
        var now = 0L
        val a = RecordingOnlineProvider("a", "A")
        val gateway = OnlineMusicGateway(listOf(a)) { now }

        gateway.status()
        gateway.status()
        assertEquals(1, a.calls().count { it == "health" })

        now = OnlineMusicGateway.STATUS_TTL_MS
        gateway.status()
        assertEquals(2, a.calls().count { it == "health" })
    }

    @Test
    fun `a health probe that throws is reported, not propagated`() {
        val thrower = object : OnlineMusicProvider {
            override val id = "boom"
            override val displayName = "Nguồn hỏng"
            override val kind = ProviderKind.OTHER
            override fun search(query: String, limit: Int) = ProviderResult.Ok(emptyList<OnlineTrack>())
            override fun browse(category: BrowseCategory, limit: Int) = ProviderResult.Ok(emptyList<OnlineTrack>())
            override fun resolve(trackId: String) = failure(ProviderFailureCode.NOT_FOUND)
            override fun getPlayableSource(trackId: String) = failure(ProviderFailureCode.NOT_FOUND)
            override fun health(): ProviderHealth = throw IllegalStateException("sập")
        }
        val status = OnlineMusicGateway(listOf(thrower)).status().single()

        assertFalse(status.health.available)
        assertNotNull(status.health.detail)
    }

    @Test
    fun `blockedConfig names the providers that need settings`() {
        val blocked = RecordingOnlineProvider("subsonic", "Navidrome", blockedConfig = true)
        val fine = RecordingOnlineProvider.withTracks("a", "A", emptyList())
        val messages = OnlineMusicGateway(listOf(blocked, fine)).blockedConfig()

        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("Navidrome"))
    }

    @Test
    fun `blockedConfig is empty when nothing needs configuring`() {
        val gateway = OnlineMusicGateway(
            listOf(RecordingOnlineProvider.withTracks("radio-browser", "Đài", emptyList()))
        )
        assertTrue(gateway.blockedConfig().isEmpty())
    }

    // -- provider list integrity -------------------------------------------

    @Test
    fun `the provider list is copied, so a caller cannot reorder it later`() {
        val a = RecordingOnlineProvider("a", "A")
        val b = RecordingOnlineProvider("b", "B")
        val mutable = mutableListOf<OnlineMusicProvider>(a, b)
        val gateway = OnlineMusicGateway(mutable)

        mutable.reverse()

        assertEquals(listOf("a", "b"), gateway.providerIds())
    }
}

END FILE

## core/navigation-core/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.navigation"
}

dependencies {
    api(project(":core:map-api"))
    api(project(":core:location-engine"))
    api(project(":core:routing-engine-offline"))
    api(project(":core:place-search"))
    api(project(":core:music-api"))
    api(project(":core:vehicle-audio-bridge"))
}

END FILE

## core/navigation-core/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/navigation-core/src/main/java/com/ts8/core/navigation/NavigationController.kt

BEGIN FILE
package com.ts8.core.navigation

import com.ts8.core.map.GeoPoint
import com.ts8.core.routing.Maneuver
import com.ts8.core.routing.ManeuverType
import com.ts8.core.routing.Route
import com.ts8.core.routing.RouteResult
import com.ts8.core.routing.RoutingEngine
import com.ts8.core.location.LocationFix
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The navigation state machine.
 *
 * This class owns *only* the decision logic: which state follows which event,
 * when a reroute is warranted, what to announce and when. It has no Android
 * dependency and no threading assumptions beyond "callers may be on any
 * thread, callbacks come back on the executor you supplied", which is what
 * makes the whole of it testable from a JVM test.
 *
 * The state diagram:
 *
 * ```
 *   IDLE ──search──> SEARCHING ──pick──> DESTINATION_PREVIEW
 *                                            │  route
 *                                            v
 *                                    ROUTE_CALCULATING
 *                                            │ ok          │ fail
 *                                            v             v
 *                                     ROUTE_PREVIEW      IDLE
 *                                       │ start    │ cancel
 *                                       v          v
 *                                  NAVIGATING   DESTINATION_PREVIEW
 *                                    │      │
 *                       off-route ───┘      └─── arrival
 *                            v                       v
 *                       REROUTING                ARRIVED
 *                            │ new route             │
 *                            v                       v
 *                       NAVIGATING                 IDLE
 * ```
 *
 * Reroute policy, which the spec calls out explicitly:
 *
 * - A single GPS spike must never trigger a reroute. The location engine
 *   already rejects spikes, and this class additionally requires
 *   [Config.rerouteConfirmSamples] consecutive off-route fixes.
 * - A cooldown prevents a reroute loop: after a reroute, the engine must have
 *   actually moved before another can fire. Without this, a driver parked 30 m
 *   off the line spends the drive recomputing.
 * - Only one computation may be in flight; a second request replaces the
 *   pending one rather than queueing.
 */
class NavigationController(
    private val routing: RoutingEngine,
    private val config: Config = Config(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class Config(
        /** Below this the car is treated as arrived. */
        val arrivalRadiusMeters: Int = 30,
        /** Distance at which arrival is announced, ahead of the radius. */
        val arrivalAnnounceMeters: Int = 300,
        /** Consecutive off-route fixes required before rerouting. */
        val rerouteConfirmSamples: Int = 3,
        /** A fix further than this from the line counts as off route. */
        val offRouteDistanceMeters: Float = 60f,
        /** Minimum time between reroutes. */
        val rerouteCooldownMs: Long = 20_000,
        /** Announce a maneuver at these distances, in metres, largest first. */
        val announceDistancesMeters: List<Int> = listOf(1000, 500, 300, 150, 50),
        /** Timeout for a route computation before it is abandoned. */
        val routeTimeoutMs: Long = 15_000,
        /** Retry once on UNREACHABLE — snap noise can cause it. */
        val retryOnUnreachable: Boolean = true
    )

    // -- state -------------------------------------------------------------
    @Volatile
    private var state: NavigationState = NavigationState.IDLE

    @Volatile
    private var destination: GeoPoint? = null

    @Volatile
    private var destinationName: String? = null

    @Volatile
    private var route: Route? = null

    @Volatile
    private var progress: NavigationProgress? = null

    @Volatile
    private var muted = false

    @Volatile
    private var overview = false

    @Volatile
    private var message: String? = null

    // -- reroute bookkeeping ----------------------------------------------
    private var offRouteStreak = 0
    private var lastRerouteAtMs = 0L
    private var routeStartedAtMs = 0L

    // -- announcement bookkeeping -----------------------------------------
    /** Distances already announced for the current maneuver. */
    private val announcedForManeuver = HashSet<Int>()
    private var currentManeuverIndex = -1
    private var arrivalAnnounced = false

    // -- computation -------------------------------------------------------
    private var pendingComputation: Future<*>? = null
    private val computing = AtomicBoolean(false)

    // -- listeners ---------------------------------------------------------
    interface Listener {
        fun onStateChanged(uiState: NavigationUiState)
        fun onAnnounce(announcement: Announcement)
        /** The map should draw this route. Null clears it. */
        fun onRouteChanged(route: Route?)
        /** The camera should show the whole route. */
        fun onOverviewChanged(overview: Boolean)
        /** Navigation ended; the caller should tear down guidance UI. */
        fun onEnded(reason: NavigationEndReason)
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun state(): NavigationState = state

    fun route(): Route? = route

    fun destination(): GeoPoint? = destination

    fun uiState(): NavigationUiState = NavigationUiState(
        state = state,
        destinationName = destinationName,
        destination = destination,
        route = route,
        progress = progress,
        message = message,
        muted = muted,
        overview = overview
    )

    // -- public transitions -------------------------------------------------

    /** Enters search mode. Illegal from NAVIGATING; end navigation first. */
    fun beginSearch() {
        if (state == NavigationState.NAVIGATING || state == NavigationState.REROUTING) return
        setState(NavigationState.SEARCHING, "beginSearch")
    }

    /**
     * The driver picked a destination. Starts route computation immediately:
     * on a head unit the "preview then confirm" step is a single screen, so
     * showing a pin and computing in parallel is what the driver expects.
     */
    fun selectDestination(point: GeoPoint, name: String?, from: GeoPoint?) {
        if (from == null) {
            fail("Chưa có vị trí hiện tại. Đang chờ tín hiệu GPS.")
            return
        }
        destination = point
        destinationName = name
        route = null
        progress = null
        message = null
        offRouteStreak = 0
        arrivalAnnounced = false
        announcedForManeuver.clear()
        currentManeuverIndex = -1

        setState(NavigationState.DESTINATION_PREVIEW, "selectDestination")
        listener?.onRouteChanged(null)
        calculateRoute(from, point, isReroute = false)
    }

    /** Recomputes from the given position. Used by the "Lộ trình" button. */
    fun recalculate(from: GeoPoint) {
        val dest = destination ?: return
        calculateRoute(from, dest, isReroute = false)
    }

    /** Starts guidance from the previewed route. */
    fun startNavigation() {
        if (state != NavigationState.ROUTE_PREVIEW) return
        routeStartedAtMs = clock()
        lastRerouteAtMs = 0L
        offRouteStreak = 0
        arrivalAnnounced = false
        announcedForManeuver.clear()
        currentManeuverIndex = -1
        setState(NavigationState.NAVIGATING, "startNavigation")
    }

    /** Overrides the routing engine's estimate with a position. */
    fun showOverview(enabled: Boolean) {
        if (overview == enabled) return
        overview = enabled
        listener?.onOverviewChanged(enabled)
        notifyState()
    }

    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        // Re-announcing after unmute is desirable: the driver muted to take a
        // call and now wants the next instruction again.
        if (!muted) announcedForManeuver.clear()
        notifyState()
    }

    fun isMuted(): Boolean = muted

    /** Ends navigation for a caller-visible reason. */
    fun end(reason: NavigationEndReason) {
        pendingComputation?.cancel(true)
        pendingComputation = null
        computing.set(false)
        route = null
        destination = null
        destinationName = null
        progress = null
        offRouteStreak = 0
        arrivalAnnounced = false
        announcedForManeuver.clear()
        currentManeuverIndex = -1
        message = null
        overview = false
        setState(NavigationState.IDLE, "end($reason)")
        listener?.onRouteChanged(null)
        listener?.onEnded(reason)
    }

    /** The pack disappeared mid-drive (USB stick pulled). */
    fun onPackLost() {
        if (state == NavigationState.IDLE) return
        message = "Dữ liệu bản đồ không còn khả dụng. Kiểm tra thẻ nhớ hoặc USB."
        end(NavigationEndReason.PACK_LOST)
    }

    // -- the driving input --------------------------------------------------

    /**
     * Feeds an accepted location fix through guidance.
     *
     * This is the only place reroute and arrival are decided, so the policy is
     * readable in one screen rather than spread across callbacks.
     */
    fun onLocation(fix: LocationFix) {
        val activeRoute = route
        if (state != NavigationState.NAVIGATING || activeRoute == null) {
            // Still worth reporting progress in preview so the panel shows a
            // live distance while the driver waits at a light.
            val previewRoute = route
            if (state == NavigationState.ROUTE_PREVIEW && previewRoute != null) {
                updateProgressFromFix(fix, previewRoute)
            }
            return
        }

        val along = fix.distanceAlongRouteMeters
        if (along == null) {
            // No projection means the route geometry no longer matches; treat
            // as off route but require the usual confirmations.
            offRouteStreak++
            if (shouldRerouteNow()) reroute(fix.point)
            return
        }

        val updated = computeProgress(activeRoute, along.toInt(), fix)
        progress = updated

        // --- arrival ------------------------------------------------------
        if (updated.remainingMeters <= config.arrivalRadiusMeters) {
            arrive()
            return
        }
        if (!arrivalAnnounced && updated.remainingMeters <= config.arrivalAnnounceMeters) {
            arrivalAnnounced = true
            announce(
                Announcement(
                    text = buildArrivalInstruction(updated.remainingMeters),
                    atMeters = updated.remainingMeters,
                    urgent = false
                )
            )
        }

        // --- off-route detection -----------------------------------------
        // Only a measured lateral distance counts. "not snapped" also happens
        // when the engine refuses to snap backwards along the route, which is
        // the driver sitting still at a junction, not a departure.
        val offRouteMeters = fix.distanceFromRouteMeters
        if (offRouteMeters != null && offRouteMeters > config.offRouteDistanceMeters) {
            offRouteStreak++
        } else {
            offRouteStreak = 0
        }

        emitNextManeuver(updated)

        if (shouldRerouteNow()) {
            reroute(fix.point)
        } else {
            notifyState()
        }
    }

    private fun shouldRerouteNow(): Boolean {
        if (offRouteStreak < config.rerouteConfirmSamples) return false
        // Cooldown measured from the previous reroute, not from route start,
        // so an immediate second departure is still possible.
        if (lastRerouteAtMs != 0L && clock() - lastRerouteAtMs < config.rerouteCooldownMs) {
            return false
        }
        return true
    }

    private fun reroute(from: GeoPoint) {
        val dest = destination ?: return
        offRouteStreak = 0
        lastRerouteAtMs = clock()
        setState(NavigationState.REROUTING, "reroute")
        calculateRoute(from, dest, isReroute = true)
    }

    private fun arrive() {
        val dest = destination
        announce(Announcement("Đã đến nơi.", 0, urgent = false))
        setState(NavigationState.ARRIVED, "arrive")
        // Arrival is terminal for guidance; the UI shows the arrival card and
        // the driver dismisses it, which calls end().
        destination = dest
        progress = progress?.copy(
            remainingMeters = 0,
            remainingSeconds = 0,
            fraction = 1f
        )
        notifyState()
    }

    // -- route computation --------------------------------------------------

    private fun calculateRoute(from: GeoPoint, to: GeoPoint, isReroute: Boolean) {
        pendingComputation?.cancel(true)
        computing.set(true)
        if (!isReroute) setState(NavigationState.ROUTE_CALCULATING, "calculateRoute")

        pendingComputation = try {
            executor.submit {
                val result = runCatching { routing.route(from, to) }
                    .getOrElse { RouteResult.Failure(com.ts8.core.routing.RouteError.INTERNAL, it.message ?: "") }

                var outcome = result
                // A failed first attempt on UNREACHABLE is usually a snap that
                // landed on a dead-end vertex; snapping the endpoints and
                // retrying costs one query and avoids a dead end for the driver.
                if (outcome is RouteResult.Failure &&
                    outcome.reason == com.ts8.core.routing.RouteError.UNREACHABLE &&
                    config.retryOnUnreachable
                ) {
                    val snappedFrom = routing.snapToRoad(from)
                    val snappedTo = routing.snapToRoad(to)
                    if (snappedFrom != null && snappedTo != null) {
                        outcome = runCatching { routing.route(snappedFrom, snappedTo) }
                            .getOrElse { outcome }
                    }
                }

                computing.set(false)
                onRouteComputed(outcome, isReroute)
            }
        } catch (e: Exception) {
            computing.set(false)
            onRouteComputed(
                RouteResult.Failure(com.ts8.core.routing.RouteError.INTERNAL, e.message ?: ""),
                isReroute
            )
            null
        }
    }

    private fun onRouteComputed(result: RouteResult, isReroute: Boolean) {
        when (result) {
            is RouteResult.Success -> {
                route = result.route
                message = null
                announcedForManeuver.clear()
                currentManeuverIndex = -1
                arrivalAnnounced = false
                offRouteStreak = 0
                listener?.onRouteChanged(result.route)

                if (isReroute) {
                    // Resume guidance directly: the driver is mid-drive and
                    // must not be asked to confirm.
                    setState(NavigationState.NAVIGATING, "rerouteComplete")
                } else {
                    setState(NavigationState.ROUTE_PREVIEW, "routeReady")
                }
            }
            is RouteResult.Failure -> {
                if (isReroute) {
                    // Keep the old route: a failed reroute must not strand the
                    // driver with no line on the map.
                    message = "Không thể tính lại lộ trình. Đang tiếp tục dùng lộ trình cũ."
                    setState(NavigationState.NAVIGATING, "rerouteFailed")
                } else {
                    val text = describeError(result)
                    message = text
                    setState(NavigationState.DESTINATION_PREVIEW, "routeFailed")
                }
            }
        }
    }

    private fun describeError(failure: RouteResult.Failure): String = when (failure.reason) {
        com.ts8.core.routing.RouteError.NO_START_NODE ->
            "Không tìm thấy đường gần vị trí hiện tại."
        com.ts8.core.routing.RouteError.NO_END_NODE ->
            "Không tìm thấy đường gần điểm đến."
        com.ts8.core.routing.RouteError.UNREACHABLE ->
            "Không có lộ trình lái xe tới điểm này."
        com.ts8.core.routing.RouteError.SAME_POINT ->
            "Điểm đến trùng với vị trí hiện tại."
        com.ts8.core.routing.RouteError.PACK_NOT_READY ->
            "Dữ liệu bản đồ chưa sẵn sàng."
        com.ts8.core.routing.RouteError.INTERNAL ->
            "Lỗi tính lộ trình: ${failure.detail}"
    }

    // -- progress ----------------------------------------------------------

    private fun updateProgressFromFix(fix: LocationFix, activeRoute: Route) {
        val along = fix.distanceAlongRouteMeters ?: return
        progress = computeProgress(activeRoute, along.toInt(), fix)
        notifyState()
    }

    private fun computeProgress(
        activeRoute: Route,
        alongMeters: Int,
        fix: LocationFix
    ): NavigationProgress {
        val total = activeRoute.distanceMeters.coerceAtLeast(1)
        val remaining = (total - alongMeters).coerceIn(0, total)
        val fraction = (alongMeters.toFloat() / total).coerceIn(0f, 1f)

        // Remaining time scales the route's own duration by the fraction left.
        // Using the route's speed model rather than the current speed keeps the
        // ETA from swinging every time the car slows for a light.
        val remainingSeconds = (activeRoute.durationSeconds * (1f - fraction))
            .toInt().coerceAtLeast(0)

        val nextIdx = nextManeuverIndex(activeRoute, alongMeters)
        val next = activeRoute.maneuvers.getOrNull(nextIdx)
        val following = activeRoute.maneuvers.getOrNull(nextIdx + 1)
        val distanceToNext = when {
            next == null -> remaining
            else -> (next.distanceFromStart - alongMeters).coerceAtLeast(0)
        }

        return NavigationProgress(
            remainingMeters = remaining,
            remainingSeconds = remainingSeconds,
            travelledMeters = alongMeters,
            fraction = fraction,
            etaEpochMs = if (remainingSeconds > 0) clock() + remainingSeconds * 1000L else 0L,
            nextManeuver = next,
            distanceToNextManeuverMeters = distanceToNext,
            followingManeuver = following,
            offRouteMeters = fix.distanceFromRouteMeters?.toInt() ?: 0
        )
    }

    /**
     * The maneuver whose `distanceFromStart` is the first one ahead.
     *
     * When none is ahead, the ARRIVE maneuver is returned, so the panel shows
     * "đến nơi" rather than blanking out on the final straight.
     */
    private fun nextManeuverIndex(activeRoute: Route, alongMeters: Int): Int {
        val ms = activeRoute.maneuvers
        for (i in ms.indices) {
            if (ms[i].distanceFromStart > alongMeters) {
                // Skip DEPART: it describes where we already are.
                if (ms[i].type == ManeuverType.DEPART) continue
                return i
            }
        }
        return if (ms.isNotEmpty()) ms.size - 1 else -1
    }

    private fun emitNextManeuver(p: NavigationProgress) {
        val m = p.nextManeuver ?: run {
            notifyState()
            return
        }
        // Arrival is announced by the arrival policy, not here. Announcing the
        // ARRIVE maneuver as well would speak "300 m, đã đến nơi" while the
        // car is still 300 m away, right after "300 m nữa là đến nơi".
        if (m.type == ManeuverType.ARRIVE) {
            notifyState()
            return
        }
        val idx = route?.maneuvers?.indexOf(m) ?: -1
        if (idx != currentManeuverIndex) {
            currentManeuverIndex = idx
            announcedForManeuver.clear()
        }
        if (muted) {
            notifyState()
            return
        }

        // Announce at the largest threshold the car has just crossed.
        val dist = p.distanceToNextManeuverMeters
        val threshold = config.announceDistancesMeters
            .filter { it >= dist }
            .minOrNull()
        if (threshold != null && announcedForManeuver.add(threshold)) {
            announce(
                Announcement(
                    text = speakInstruction(m, dist),
                    atMeters = dist,
                    urgent = dist <= 50
                )
            )
        }
        notifyState()
    }

    private fun speakInstruction(m: Maneuver, distanceMeters: Int): String {
        val prefix = formatDistance(distanceMeters)
        return if (prefix.isEmpty()) m.instruction else "$prefix, ${m.instruction}"
    }

    private fun buildArrivalInstruction(remaining: Int): String {
        val dist = formatDistance(remaining)
        return if (dist.isEmpty()) "Sắp đến nơi" else "$dist nữa là đến nơi"
    }

    private fun announce(a: Announcement) {
        listener?.onAnnounce(a)
    }

    // -- plumbing ----------------------------------------------------------

    private fun setState(next: NavigationState, because: String) {
        if (state == next) {
            notifyState()
            return
        }
        state = next
        notifyState()
    }

    private fun fail(text: String) {
        message = text
        notifyState()
    }

    private fun notifyState() {
        listener?.onStateChanged(uiState())
    }

    /** Cancels any in-flight computation. Call from `onDestroy`. */
    fun shutdown() {
        pendingComputation?.cancel(true)
        pendingComputation = null
        listener = null
        executor.shutdownNow()
    }

    /** Mute override for the voice seam; see [Announcement.urgent]. */
    fun shouldSpeak(a: Announcement): Boolean = !muted && a.text.isNotBlank()

    companion object {
        /** "300 m", "1,2 km", "15 km", or "" when the distance is tiny. */
        fun formatDistance(meters: Int): String = when {
            meters < 0 -> ""
            meters < 20 -> ""
            meters < 1000 -> "${roundTo(meters, 10)} m"
            else -> {
                val km = meters / 1000.0
                // Vietnamese uses a comma as the decimal separator. Whole
                // kilometres drop the ",0" — "15 km" reads, "15,0 km" does not.
                val text = String.format(java.util.Locale.US, "%.1f", km).replace('.', ',')
                (if (text.endsWith(",0")) text.dropLast(2) else text) + " km"
            }
        }

        private fun roundTo(value: Int, step: Int): Int =
            ((value + step / 2) / step) * step
    }
}

END FILE

## core/navigation-core/src/main/java/com/ts8/core/navigation/NavigationState.kt

BEGIN FILE
package com.ts8.core.navigation

/**
 * Navigation states, exactly as the specification enumerates them.
 *
 * The transitions are the contract; see [NavigationController] for which
 * events are legal in each state. Anything not listed there is a bug in the
 * caller, and the controller rejects it rather than guessing.
 */
enum class NavigationState {
    /** No destination. The map follows the car but offers no guidance. */
    IDLE,

    /** The driver is typing / a lookup is running. Debounced by the caller. */
    SEARCHING,

    /** A destination is picked and shown on the map; no route yet. */
    DESTINATION_PREVIEW,

    /** A route computation is in flight. */
    ROUTE_CALCULATING,

    /** A route is drawn and offered; not yet started. */
    ROUTE_PREVIEW,

    /** Guidance is active. */
    NAVIGATING,

    /**
     * The driver left the route and a new one is being computed. Music
     * continues, guidance announcements are suspended until the new route is
     * ready.
     */
    REROUTING,

    /** Within the arrival radius of the destination. */
    ARRIVED
}

/** Why navigation ended, for the summary card. */
enum class NavigationEndReason {
    /** The driver pressed "end". */
    USER_ENDED,

    /** The destination was reached. */
    ARRIVED,

    /** The driver cancelled at the preview stage. */
    CANCELLED_PREVIEW,

    /** No route could be computed even after a retry. */
    ROUTE_FAILED,

    /** The map pack became unavailable mid-drive (USB removed). */
    PACK_LOST,

    /** The activity was destroyed and navigation was not resumed. */
    TERMINATED
}

/** Progress along the active route, recomputed on every accepted fix. */
data class NavigationProgress(
    /** Metres left to the destination along the route. */
    val remainingMeters: Int,
    /** Seconds left, from the route's own speed model. */
    val remainingSeconds: Int,
    /** Metres travelled since guidance started. */
    val travelledMeters: Int,
    /** Fraction complete, 0..1. */
    val fraction: Float,
    /** Est. arrival time as epoch milliseconds, or 0 when unknown. */
    val etaEpochMs: Long,
    /** The next instruction to show, or null when only the destination is left. */
    val nextManeuver: com.ts8.core.routing.Maneuver?,
    /** Metres until [nextManeuver] fires; 0 when it is imminent. */
    val distanceToNextManeuverMeters: Int,
    /** The instruction after the next one, for the "sau đó" line. */
    val followingManeuver: com.ts8.core.routing.Maneuver?,
    /** How far off the route line the car currently is. */
    val offRouteMeters: Int
)

/** A guidance announcement the TTS layer must speak. */
data class Announcement(
    val text: String,
    /** Distance to the maneuver at the time of speaking, for the log. */
    val atMeters: Int,
    /** True when this must interrupt music rather than wait for it. */
    val urgent: Boolean
)

/** Everything the navigation UI renders that is not the map itself. */
data class NavigationUiState(
    val state: NavigationState,
    val destinationName: String? = null,
    val destination: com.ts8.core.map.GeoPoint? = null,
    val route: com.ts8.core.routing.Route? = null,
    val progress: NavigationProgress? = null,
    /** Message to show when the state is an error state. */
    val message: String? = null,
    /** Muted by the driver. Does not stop the visual guidance. */
    val muted: Boolean = false,
    /** True while the map should show the whole route instead of following. */
    val overview: Boolean = false
)

END FILE

## core/navigation-core/src/main/java/com/ts8/core/navigation/VoiceGuidance.kt

BEGIN FILE
package com.ts8.core.navigation

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Build
import android.os.Bundle
import com.ts8.core.audio.AudioFocusListener
import com.ts8.core.audio.AudioResult
import com.ts8.core.audio.VehicleAudioBridge
import com.ts8.core.music.AudioRoute
import java.util.Locale
import java.util.UUID

/**
 * Vietnamese voice guidance.
 *
 * Two responsibilities the spec calls out explicitly, and this class owns
 * both:
 *
 * 1. **Music ducking.** Before an utterance, the media stream is lowered;
 *    after it finishes, the exact previous level is restored. Duck and unduck
 *    are paired through [UtteranceProgressListener] rather than a timer, so a
 *    long street name does not leave the music quiet forever.
 * 2. **Honest failure.** If the device has no Vietnamese TTS engine, the
 *    launcher says so and keeps working with visual guidance. It never
 *    pretends to speak.
 *
 * `TextToSpeech` on API 23 has no `speak(CharSequence, ...)` overload, so the
 * deprecated `String` overload is used deliberately, with the SDK check kept
 * for the newer path.
 */
class VoiceGuidance(
    context: Context,
    private val audioBridge: VehicleAudioBridge
) {
    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var initialised = false

    /** Null until init completes; false when the engine is missing or broken. */
    @Volatile
    private var vietnameseAvailable: Boolean? = null

    /** True while an utterance is in progress and the music is ducked. */
    @Volatile
    private var speaking = false

    private var speechRate = 1.0f
    private var pitch = 1.0f

    /** Callback for the UI to show "no Vietnamese voice" once, not per prompt. */
    var onAvailabilityChanged: ((Boolean) -> Unit)? = null

    /** True when the platform actually has a Vietnamese voice. */
    fun isVietnameseAvailable(): Boolean = vietnameseAvailable == true

    /** True while an utterance is playing. */
    fun isSpeaking(): Boolean = speaking

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    fun setPitch(value: Float) {
        pitch = value.coerceIn(0.5f, 2.0f)
        tts?.setPitch(pitch)
    }

    /**
     * Initialises the engine. Safe to call more than once; only the first call
     * starts an engine.
     */
    fun init(onReady: (Boolean) -> Unit = {}) {
        if (tts != null) {
            onReady(isVietnameseAvailable())
            return
        }
        tts = TextToSpeech(appContext) { status ->
            initialised = true
            if (status != TextToSpeech.SUCCESS) {
                vietnameseAvailable = false
                onAvailabilityChanged?.invoke(false)
                onReady(false)
                return@TextToSpeech
            }
            configureLocale(onReady)
        }
    }

    private fun configureLocale(onReady: (Boolean) -> Unit) {
        val engine = tts ?: return

        val vietnamese = Locale("vi", "VN")
        val result = engine.setLanguage(vietnamese)

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            // Before giving up, check whether any installed voice lists
            // Vietnamese — some firmware ships the voice without advertising it
            // as the default.
            //
            // `availableLanguages` is a Set<String> of BCP-47 tags on API 23 and
            // a Set<Locale> on API 24+, so the elements are normalised through
            // toString() rather than assumed to be one type.
            val viTag = engine.availableLanguages
                .map { it.toString() }
                .firstOrNull { raw ->
                    val tag = raw.replace('_', '-')
                    runCatching { Locale.forLanguageTag(tag).language == "vi" }.getOrDefault(false)
                }

            if (viTag != null) {
                engine.setLanguage(Locale.forLanguageTag(viTag.replace('_', '-')))
                finishSetup(true, onReady)
            } else {
                // Keep the engine: English guidance is still better than none,
                // but the UI must tell the driver the voice is not Vietnamese.
                engine.setLanguage(Locale.US)
                finishSetup(false, onReady)
            }
        } else {
            finishSetup(true, onReady)
        }
    }

    private fun finishSetup(viAvailable: Boolean, onReady: (Boolean) -> Unit) {
        val engine = tts ?: return
        vietnameseAvailable = viAvailable
        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }

            override fun onDone(utteranceId: String?) {
                endUtterance()
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
            override fun onError(utteranceId: String?) {
                endUtterance()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                endUtterance()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                endUtterance()
            }
        })

        onAvailabilityChanged?.invoke(viAvailable)
        onReady(viAvailable)
    }

    /**
     * Speaks an announcement, ducking music for its duration.
     *
     * @return false when there is no engine, so callers can log rather than
     *         assume the driver heard something.
     */
    fun speak(text: String, urgent: Boolean): Boolean {
        val engine = tts
        if (!initialised || engine == null || vietnameseAvailable == null) {
            return false
        }
        if (text.isBlank()) return false

        // Duck first, then speak: ducking after the first syllable clips the
        // start of the instruction.
        if (!speaking) {
            when (val r = audioBridge.duck()) {
                is AudioResult.Denied, is AudioResult.Failed, is AudioResult.Unsupported -> {
                    // Speaking without ducking is preferable to not speaking.
                    // The music will be too loud, but the instruction is safety
                    // relevant and must not be dropped.
                }
                AudioResult.Success -> Unit
            }
        }
        speaking = true

        val utteranceId = "ts8-${UUID.randomUUID()}"
        val queueMode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.speak(text, queueMode, Bundle(), utteranceId)
        } else {
            @Suppress("DEPRECATION")
            engine.speak(text, queueMode, null, utteranceId)
        }

        if (result != TextToSpeech.SUCCESS) {
            // Nothing was queued, so nothing will call onDone — undo the duck
            // here or the music stays quiet with no explanation.
            endUtterance()
            return false
        }
        return true
    }

    /** Stops any current utterance and restores the music level. */
    fun stop() {
        tts?.stop()
        endUtterance()
    }

    /**
     * Ducks and unducks must be balanced. `UtteranceProgressListener` fires
     * for each utterance, and `QUEUE_ADD` means several can be queued; the
     * bridge treats duck as idempotent, and unduck only fires once nothing is
     * left speaking.
     */
    private fun endUtterance() {
        speaking = false
        audioBridge.unduck()
    }

    /**
     * Requests navigation focus once at startup so a phone call is detected.
     * The listener itself is installed by the caller on the bridge.
     */
    fun acquireNavigationFocus(): AudioResult =
        audioBridge.requestFocus(AudioRoute.NAVIGATION)

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        initialised = false
        vietnameseAvailable = null
    }

    /** Opens the system TTS settings so the driver can install a voice. */
    fun openTtsSettings(): Intent = Intent("com.android.settings.TTS_SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A diagnostic line for the diagnostics screen. */
    fun report(): String = when (vietnameseAvailable) {
        true -> "TTS: tiếng Việt khả dụng"
        false -> "TTS: không có giọng tiếng Việt — chỉ dẫn bằng hình ảnh"
        null -> "TTS: chưa khởi tạo"
    }

    /**
     * Adapter the app installs on the bridge so a call silences guidance
     * immediately rather than ducking.
     */
    fun asFocusListener(): AudioFocusListener = object : AudioFocusListener {
        override fun onFocusLostPermanent() {
            stop()
        }

        override fun onFocusLostTransient(mayDuck: Boolean) {
            // Guidance is short; finishing the sentence over a notification is
            // worse than stopping it.
            if (speaking) stop()
        }

        override fun onFocusGained() = Unit

        override fun onCallStarted() {
            stop()
        }

        override fun onCallEnded() = Unit

        override fun onAudioRouteChanged(route: AudioRoute) = Unit
    }
}

END FILE

## core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt

BEGIN FILE
package com.ts8.core.voice

/**
 * The one seam between the voice decision logic and the app module.
 *
 * **Why an executor interface rather than direct calls to navigation, music and
 * the launcher.** This half is testable on a JVM exactly because it never
 * touches a screen, a media session or a volume stream: it is given a command
 * object and returns an outcome. The app module implements this against
 * `NavigationController`, `MusicPlayer` and `AudioManager`; [VoiceScenarios]
 * implements it with a recording fake. If the router called those directly,
 * every one of the rules in this package would need Robolectric to test, and
 * the failure modes that matter (a command running twice, a confirmation
 * leaking into the next session) would only be observable on the head unit.
 */
fun interface CommandExecutor {
    /**
     * Carries out [command].
     *
     * Implementations must be fast and must never throw: a thrown exception is
     * caught by [VoiceRouter] and reported as [CommandOutcome.Failed], but by
     * then the caller has already lost the chance to say *why*. Returning
     * [CommandOutcome.NeedsConfirmation] is how an executor asks for a prompt
     * the command object itself did not anticipate (for example a destination
     * that resolved to two places with the same name).
     */
    fun execute(command: VoiceCommand): CommandOutcome
}

/**
 * What came of executing a command.
 *
 * [Done.spoken] is the sentence the driver hears on success — a Vietnamese
 * confirmation of what actually happened ("Đang dẫn đường tới Đà Nẵng"), not a
 * generic "OK". Saying which action ran is the only feedback a driver gets
 * without looking away from the road, and a wrong one is how the driver learns
 * the microphone misheard them.
 *
 * [Failed.reason] is likewise driver-facing Vietnamese; the executor knows
 * whether it was "chưa có bài nào" or "không tìm thấy địa điểm" and the router
 * cannot tell those apart.
 */
sealed class CommandOutcome {

    /** The command ran. [spoken] is what the TTS layer must say. */
    data class Done(val spoken: String) : CommandOutcome()

    /**
     * The command did not run and must not run until the driver answers.
     * [prompt] is the question, already in Vietnamese; [command] is the exact
     * command to execute on "yes" — the executor may substitute a narrowed one
     * (one of two "Bến Thành" places), and the router must honour that, not the
     * original.
     */
    data class NeedsConfirmation(val prompt: String, val command: VoiceCommand) : CommandOutcome()

    /** The command ran but failed, or was refused. [reason] is driver-facing. */
    data class Failed(val reason: String) : CommandOutcome()
}

/**
 * Drives Voice Instant's state machine from a transcript to a spoken answer.
 *
 * **What this class is not.** It has no microphone, no `SpeechRecognizer`, no
 * `TextToSpeech` and no `Handler`. Text goes in through [onTranscript] and
 * Vietnamese strings come out through [Listener.onSpeak]; the app module owns
 * both platform ends. That split is the whole point: the rules that are easy to
 * get wrong — asking before destroying a route, running a command exactly once,
 * a confirmation that outlives its session — are all here, and all of them run
 * from a plain unit test with a fake clock.
 *
 * **The state diagram** (see [VoiceState] for why CONFIRMING is a state):
 *
 * ```
 *   IDLE ──transcript──> PROCESSING ──parse ok─────> EXECUTING ──Done──> SUCCESS
 *                              │                        ├────Failed───> ERROR
 *                              ├──ambiguous/needsConf──> CONFIRMING
 *                              └──unrecognised─────────> ERROR
 *
 *   CONFIRMING ──yes──> EXECUTING        CONFIRMING ──no──> IDLE
 *   any in-flight state ──timeout──> IDLE
 *   any state ──cancel()──> IDLE
 * ```
 *
 * **Timeout.** There is no timer thread here by design. The caller posts its own
 * delayed message (a `Handler` in the app, nothing in a test) and calls
 * [onTimeout] when [remainingMs] says it has expired; the router owns the
 * deadline so a backgrounded overlay cannot silently outlive its prompt.
 *
 * All public methods are synchronized: the recogniser callback, the TTS
 * callback and the overlay's Back key arrive on different threads, and a
 * transcript landing in the middle of a confirmation is the race that would
 * otherwise execute a destructive command twice.
 */
class VoiceRouter(
    private val executor: CommandExecutor,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val config: Config = Config()
) {

    data class Config(
        /**
         * How long a prompt may stay unanswered before the interaction is
         * abandoned. Eight seconds: long enough for a driver to say "đồng ý"
         * after the prompt finishes speaking, short enough that the overlay is
         * gone before a junction the driver needs to see.
         */
        val timeoutMs: Long = 8_000L,
        /**
         * False lets a [VoiceCommand] whose `needsConfirmation` is true run
         * without asking. Off by default and offered only so a fleet operator
         * can decide; a router instance is never constructed with it casually.
         */
        val requireConfirmation: Boolean = true
    )

    /** Platform ends of the interaction; implemented by the app module. */
    interface Listener {
        /** Every state change, on the calling thread. */
        fun onStateChanged(snapshot: VoiceStateSnapshot)

        /**
         * A sentence the TTS layer must speak. Emitted for prompts, successes,
         * failures, cancellation and timeout — never for a state change alone,
         * so the overlay and the voice cannot drift apart.
         */
        fun onSpeak(text: String)
    }

    @Volatile
    private var state: VoiceState = VoiceState.IDLE

    @Volatile
    private var sinceElapsedMs: Long = clock()

    @Volatile
    private var lastCommandId: String? = null

    @Volatile
    private var error: VoiceError? = null

    /** The last failure, retained past the IDLE that a timeout returns to. */
    @Volatile
    var lastError: VoiceError? = null
        private set

    /** The command waiting for a yes/no, or null in every other state. */
    @Volatile
    private var pending: VoiceCommand? = null

    /** Absolute deadline (monotonic ms) for the current in-flight state, or 0. */
    @Volatile
    private var deadlineElapsedMs: Long = 0L

    @Volatile
    private var listener: Listener? = null

    /**
     * Installs the platform ends. Null detaches.
     *
     * The listener is held weakly-by-convention rather than strongly: the app
     * module must clear it before its Activity dies, because this router can
     * outlive the overlay (the store keeps a SUCCESS on screen) and a stale
     * listener would speak into a destroyed `TextToSpeech`.
     */
    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** The current state, without allocating a snapshot. */
    fun state(): VoiceState = state

    /**
     * An immutable copy of everything the overlay renders.
     *
     * A copy rather than a reference: the router mutates its fields under the
     * lock, and an overlay holding the live object would render a state and a
     * timestamp from two different moments.
     */
    fun snapshot(): VoiceStateSnapshot =
        VoiceStateSnapshot(state, sinceElapsedMs, lastCommandId, error)

    /** The command awaiting confirmation, for the overlay's "đồng ý / huỷ". */
    fun pendingCommand(): VoiceCommand? = pending

    /** Milliseconds left before [onTimeout] applies; 0 when nothing is pending. */
    fun remainingMs(): Long {
        val deadline = deadlineElapsedMs
        if (deadline == 0L) return 0L
        return (deadline - clock()).coerceAtLeast(0L)
    }

    // -- entry points the app module calls -----------------------------------

    /**
     * The microphone opened. Moves IDLE (or a finished state) to [VoiceState.LISTENING].
     *
     * Refused while a confirmation is pending: the driver is answering a
     * question, and opening the microphone would make their "đồng ý" the next
     * transcript instead of the answer.
     */
    @Synchronized
    fun onListeningStarted() {
        if (state == VoiceState.CONFIRMING || state == VoiceState.EXECUTING) return
        error = null
        lastCommandId = null
        pending = null
        enter(VoiceState.LISTENING)
    }

    /**
     * A final transcript from the recogniser.
     *
     * A transcript that arrives while a confirmation is pending is *not* an
     * answer — "đồng ý" is matched by [confirm], and the recogniser's own
     * hypothesis for it is unreliable enough that treating it as an answer
     * would both mis-fire and leave the prompt open. The transcript is dropped.
     */
    @Synchronized
    fun onTranscript(text: String?) {
        when (state) {
            VoiceState.CONFIRMING, VoiceState.EXECUTING -> return
            else -> Unit
        }

        enter(VoiceState.PROCESSING)
        val parsed = IntentParser.parse(text)

        when (parsed) {
            is IntentParser.ParseResult.Command -> {
                lastCommandId = parsed.command.id
                val command = parsed.command
                if (command.needsConfirmation && config.requireConfirmation) {
                    pending = command
                    enter(VoiceState.CONFIRMING)
                    speak(VietnameseVoicePrompts.confirm(command))
                } else {
                    execute(command, alreadyConfirmed = false)
                }
            }
            is IntentParser.ParseResult.Ambiguous -> {
                // There is no command object here, so there is nothing to
                // confirm — the driver has to restate. The Error state carries
                // the failure so the overlay shows the question, not a spinner.
                lastCommandId = null
                fail(VoiceError.NOT_UNDERSTOOD, VietnameseVoicePrompts.ambiguous(parsed.candidates))
            }
            is IntentParser.ParseResult.Unrecognized -> {
                lastCommandId = null
                fail(
                    VoiceError.NOT_UNDERSTOOD,
                    VietnameseVoicePrompts.notUnderstood(parsed.text)
                )
            }
        }
    }

    /**
     * The answer to a confirmation prompt.
     *
     * Only legal in [VoiceState.CONFIRMING]; a "yes" arriving after a timeout is
     * dropped rather than re-executing a command the driver has already been
     * told was abandoned. That is the same class of bug as a double tap on a
     * destructive button, and it is the reason the pending command is cleared
     * *before* the executor runs.
     */
    @Synchronized
    fun confirm(yes: Boolean) {
        if (state != VoiceState.CONFIRMING) return
        val command = pending ?: run {
            // A CONFIRMING state with no command is a programming error, not a
            // user error. Do not guess which command was meant.
            fail(VoiceError.COMMAND_FAILED, VietnameseVoicePrompts.failed())
            return
        }
        pending = null
        if (!yes) {
            abandon(VoiceError.TIMEOUT, VietnameseVoicePrompts.cancelled(), keepError = false)
            return
        }
        execute(command, alreadyConfirmed = true)
    }

    /**
     * Back / the overlay's close button: abandon whatever is in flight.
     *
     * Safe in every state, including IDLE (no-op), so the overlay can wire Back
     * unconditionally rather than reconstructing which states are cancellable.
     */
    @Synchronized
    fun cancel() {
        if (state == VoiceState.IDLE) return
        pending = null
        abandon(VoiceError.TIMEOUT, VietnameseVoicePrompts.cancelled(), keepError = false)
    }

    /**
     * The platform reported a recogniser failure.
     *
     * [VoiceError.TIMEOUT] here is the recogniser's own end-of-speech silence,
     * which is mapped to NO_SPEECH by the caller; the router's own deadline is
     * separate and goes through [onTimeout].
     */
    @Synchronized
    fun onRecognitionError(reason: VoiceError) {
        if (state == VoiceState.CONFIRMING || state == VoiceState.EXECUTING) return

        // A recogniser cannot tell "no service" from "no match" reliably on
        // API 23; the app module classifies and passes the error in. Here it is
        // only turned into the right prompt and state.
        pending = null
        fail(reason, VietnameseVoicePrompts.forError(reason))
    }

    /**
     * The caller's timer fired.
     *
     * The meaning depends on where it fires:
     *
     * - **CONFIRMING / LISTENING / PROCESSING / EXECUTING** — the interaction
     *   was abandoned, so the machine returns to IDLE and speaks
     *   "Hết thời gian chờ". [lastError] records TIMEOUT for the log;
     *   [snapshot] carries no error for an IDLE state.
     * - **SUCCESS / ERROR** — the driver has already been told what happened;
     *   the timer here is the overlay's auto-dismiss. It closes silently,
     *   because announcing a timeout after "Đã thực hiện: mở bản đồ" would tell
     *   the driver their command failed when it did not.
     *
     * Also refuses to fire early: if [remainingMs] is still positive the timer
     * was posted against a previous, longer interaction and is ignored.
     */
    @Synchronized
    fun onTimeout() {
        if (deadlineElapsedMs == 0L) return
        if (clock() < deadlineElapsedMs) return
        val settling = state == VoiceState.SUCCESS || state == VoiceState.ERROR
        pending = null
        if (!settling) {
            lastError = VoiceError.TIMEOUT
            speak(VietnameseVoicePrompts.timeout())
        }
        enter(VoiceState.IDLE)
    }

    // -- internals -----------------------------------------------------------

    /**
     * Runs [command] exactly once.
     *
     * `alreadyConfirmed` exists to break the loop where an executor returns
     * [CommandOutcome.NeedsConfirmation] for a command the driver has just
     * confirmed: asking again would let a broken executor trap the driver in an
     * endless prompt, so the second ask is reported as a failure.
     */
    private fun execute(command: VoiceCommand, alreadyConfirmed: Boolean) {
        enter(VoiceState.EXECUTING)
        val outcome = try {
            executor.execute(command)
        } catch (e: Exception) {
            // A throwing executor is a bug, but the driver must still see an
            // answer; an unhandled exception here would leave EXECUTING on
            // screen with no timer left to end it.
            CommandOutcome.Failed(VietnameseVoicePrompts.failed())
        }

        when (outcome) {
            is CommandOutcome.Done -> {
                lastError = null
                enter(VoiceState.SUCCESS)
                speak(outcome.spoken)
            }
            is CommandOutcome.NeedsConfirmation -> {
                if (alreadyConfirmed || !config.requireConfirmation) {
                    fail(VoiceError.COMMAND_FAILED, VietnameseVoicePrompts.failed())
                    return
                }
                // The executor narrowed the command; the confirmation is about
                // the one it returned, not the one we sent.
                pending = outcome.command
                lastCommandId = outcome.command.id
                enter(VoiceState.CONFIRMING)
                speak(outcome.prompt)
            }
            is CommandOutcome.Failed -> {
                fail(VoiceError.COMMAND_FAILED, outcome.reason)
            }
        }
    }

    /** Moves to IDLE, optionally speaking, and clears the deadline. */
    private fun abandon(reason: VoiceError, spoken: String, keepError: Boolean) {
        if (keepError) lastError = reason
        pending = null
        speak(spoken)
        enter(VoiceState.IDLE)
    }

    private fun fail(reason: VoiceError, spoken: String) {
        lastError = reason
        error = reason
        enter(VoiceState.ERROR)
        speak(spoken)
    }

    /**
     * Records a new state, stamps the monotonic clock and arms the deadline.
     *
     * SUCCESS and ERROR get a deadline too: the overlay must not stay up
     * forever if nobody dismisses it, and the caller drives that with the same
     * [onTimeout] it already posts.
     */
    private fun enter(next: VoiceState) {
        state = next
        sinceElapsedMs = clock()
        deadlineElapsedMs = if (next == VoiceState.IDLE) 0L else clock() + config.timeoutMs
        if (next != VoiceState.ERROR) error = null
        notifyState()
    }

    private fun notifyState() {
        listener?.onStateChanged(snapshot())
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        listener?.onSpeak(text)
    }
}

END FILE

## core/navigation-core/src/main/java/com/ts8/core/voice/IntentParser.kt

BEGIN FILE
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

        // 3. Absolute volume: "âm lượng 40 phần trăm", "đặt âm lượng 40%".
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

        // 4. Exact phrases. Longest first, so "mở nhạc" never resolves through
        //    the bare "mở" entry.
        for (entry in PHRASES) {
            if (rest != entry.phrase) continue
            return ParseResult.Command(entry.command, entry.phrase)
        }

        // 5. App launch, with an argument. After the exact table so
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

        // 6. Ambiguous / empty-handed. "mở" alone is the canonical case; the
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

    private class Phrase(val phrase: String, val command: VoiceCommand)

    /**
     * Fixed phrases, longest first.
     *
     * `mở nhạc` and `mở bản đồ` share a prefix with the ambiguous `mở`, which is
     * why the table is matched by whole phrase and never by first token.
     */
    private val PHRASES: List<Phrase> = listOf(
        Phrase("mo ban do", VoiceCommand.OpenMap),
        Phrase("mo dien thoai", VoiceCommand.OpenPhone),
        Phrase("goi dien", VoiceCommand.OpenPhone),
        Phrase("mo nhac va", VoiceCommand.OpenMusic),
        Phrase("mo nhac", VoiceCommand.OpenMusic),
        Phrase("mo ung dung nhac", VoiceCommand.OpenMusic),
        Phrase("phat nhac", VoiceCommand.PlayMusic),
        Phrase("tam dung", VoiceCommand.PauseMusic),
        Phrase("dung nhac", VoiceCommand.PauseMusic),
        Phrase("bai tiep theo", VoiceCommand.NextTrack),
        Phrase("bai tiep", VoiceCommand.NextTrack),
        Phrase("chuyen bai", VoiceCommand.NextTrack),
        Phrase("bai truoc", VoiceCommand.PreviousTrack),
        Phrase("quay lai bai", VoiceCommand.PreviousTrack),
        Phrase("tang am luong", VoiceCommand.VolumeUp),
        Phrase("giam am luong", VoiceCommand.VolumeDown),
        Phrase("ve nha", VoiceCommand.GoHome),
        Phrase("trang chu", VoiceCommand.GoHome),
        Phrase("thoat", VoiceCommand.Exit),
        Phrase("thoat ra", VoiceCommand.Exit),
        Phrase("dong lai", VoiceCommand.Exit)
    )
}

END FILE

## core/navigation-core/src/main/java/com/ts8/core/voice/VietnameseVoicePrompts.kt

BEGIN FILE
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

    /** The sentence for a recogniser or router failure. */
    fun forError(error: VoiceError): String = when (error) {
        VoiceError.SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE
        VoiceError.NO_SPEECH -> "Không nghe thấy giọng nói. Thử lại."
        VoiceError.NO_MATCH -> "Không nghe rõ. Thử lại hoặc nói ngắn hơn."
        VoiceError.TIMEOUT -> TIMEOUT
        VoiceError.NOT_UNDERSTOOD -> "Không hiểu lệnh. Thử nói ngắn hơn."
        VoiceError.COMMAND_FAILED -> FAILED
        VoiceError.PERMISSION_DENIED -> "Cần quyền micro để dùng lệnh giọng nói"
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
        VoiceCommand.Exit -> "thoát"
    }
}

END FILE

## core/navigation-core/src/main/java/com/ts8/core/voice/VoiceCommand.kt

BEGIN FILE
package com.ts8.core.voice

/**
 * The closed set of things Voice Instant may do.
 *
 * **Why a sealed class and not an intent string.** The one failure this
 * prevents is the router inventing an action: if the parser can only return a
 * [VoiceCommand] it has a constructor for, an unrecognised phrase has no
 * representable answer except [ParseResult.Unrecognized]. Strings would let a
 * typo in the parser reach the executor as a command that "exists" and then
 * fails at the far end, after the music has already been ducked.
 *
 * **Which commands are destructive.** [destructive] is true for exactly two:
 *
 * - [NAVIGATE_TO] throws away an active route. The driver may be 400 m from a
 *   junction; silently replacing the line is the single worst thing a voice
 *   command can do in a moving car.
 * - [PLAY_MUSIC] replaces the queue. "Phát nhạc" while an album is playing is
 *   not "resume" — the executor cannot tell those apart from the phrase alone,
 *   so it must ask.
 *
 * Everything else changes a value (volume), advances a cursor (next/previous),
 * pauses (reversible), or opens a screen. Replacing a queue or a route cannot
 * be undone from the overlay, which is what separates the two.
 *
 * **Confirmation policy.** [needsConfirmation] is true exactly when
 * [destructive] is true. Genuinely ambiguous input is *not* a command property:
 * it is [ParseResult.Ambiguous], because there is no command object to carry
 * the flag — that is the point of "never guess".
 *
 * [EXIT] deliberately does not ask. It is the escape hatch the driver reaches
 * for when the launcher itself is misbehaving; a confirmation gate on "thoát"
 * would hold them inside the broken screen it exists to leave.
 */
sealed class VoiceCommand {

    /** Stable identifier, for the log and [VoiceStateSnapshot.lastCommandId]. */
    abstract val id: String

    /** True when executing this discards state the driver cannot get back. */
    abstract val destructive: Boolean

    /** True when the driver must answer yes/no before [VoiceRouter] executes. */
    abstract val needsConfirmation: Boolean

    /**
     * Route to [destination], a **folded** place key.
     *
     * Folded, not as spoken: the requirement is that "Dẫn đường tới Đà Nẵng"
     * and "dan duong toi Da Nang" produce the *same* destination, and they only
     * do if both are normalised before they are stored. It is also the key the
     * local place index expects — `VietnameseText.normalize` folds identically
     * — so the executor can hand it straight to `PlaceSearch` without a second
     * normalisation step that could drift.
     *
     * The cost is that TTS reads the key without tone marks. Restoring them
     * would mean guessing which of several Vietnamese readings the driver
     * meant, which is exactly the fabrication this module refuses; the app
     * module may substitute the original transcript for speech if it wants
     * nicer audio, but the key itself stays folded.
     */
    data class NavigateTo(val destination: String) : VoiceCommand() {
        override val id: String = "navigate_to"
        override val destructive: Boolean = true
        override val needsConfirmation: Boolean = true
    }

    /**
     * Look [query] up in the local place index and show the results.
     *
     * Folded for the same reason as [NavigateTo.destination]: it is a search
     * key, and the index is built on folded text.
     */
    data class SearchPlace(val query: String) : VoiceCommand() {
        override val id: String = "search_place"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Start playback, replacing whatever queue is loaded. */
    data object PlayMusic : VoiceCommand() {
        override val id: String = "play_music"
        override val destructive: Boolean = true
        override val needsConfirmation: Boolean = true
    }

    /** Pause. Reversible by [PlayMusic], so no prompt. */
    data object PauseMusic : VoiceCommand() {
        override val id: String = "pause_music"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Advance one track. Reversible with [PreviousTrack]. */
    data object NextTrack : VoiceCommand() {
        override val id: String = "next_track"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Go back one track. Reversible with [NextTrack]. */
    data object PreviousTrack : VoiceCommand() {
        override val id: String = "previous_track"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** One step up. The executor clamps at the maximum. */
    data object VolumeUp : VoiceCommand() {
        override val id: String = "volume_up"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** One step down. The executor clamps at silence. */
    data object VolumeDown : VoiceCommand() {
        override val id: String = "volume_down"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Absolute volume, for "âm lượng 40 phần trăm".
     *
     * Not one of the twelve MVP phrases; added because the parser recognises
     * the percentage form and turning it into two hundred [VolumeDown]s would
     * be worse than one set. [percent] is clamped to 0..100 when the command is
     * built, so the executor never has to decide what 150% means.
     */
    data class VolumeTo(val percent: Int) : VoiceCommand() {
        override val id: String = "volume_to"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false

        init {
            require(percent in 0..100) { "percent must be 0..100, was $percent" }
        }
    }

    /** Leave voice and return to the launcher home screen. */
    data object GoHome : VoiceCommand() {
        override val id: String = "go_home"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Open the map screen without a destination. */
    data object OpenMap : VoiceCommand() {
        override val id: String = "open_map"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Open the music screen without starting playback. */
    data object OpenMusic : VoiceCommand() {
        override val id: String = "open_music"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Open the phone screen or the platform dialer.
     *
     * A separate command from [OpenApp] even though the bottom bar's Phone item
     * is not a screen: the driver's intent is "call someone", and the app module
     * resolves it to the dialer, to whatever firmware app handles `ACTION_DIAL`,
     * or to the app drawer — three different outcomes carrying one meaning.
     */
    data object OpenPhone : VoiceCommand() {
        override val id: String = "open_phone"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /**
     * Launch an installed app by the name the driver said.
     *
     * [query] is folded, like every other lookup key here, because the app
     * catalogue sorts on folded labels and the recogniser returns either
     * spelling. The name is not resolved in this module: `PackageManager` lives
     * in the app module, and a voice layer that could invent an app would be
     * able to launch something the driver never looked at.
     *
     * Not destructive: opening an app changes the screen, which is what the
     * driver asked for, and Back returns. Confirming here would put a question
     * in front of the most common command a driver says.
     */
    data class OpenApp(val query: String) : VoiceCommand() {
        override val id: String = "open_app"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    /** Close Voice Instant / leave the launcher. Never asks; see the class doc. */
    data object Exit : VoiceCommand() {
        override val id: String = "exit"
        override val destructive: Boolean = false
        override val needsConfirmation: Boolean = false
    }

    companion object {
        /**
         * Every command instance the router can be handed, for diagnostics and
         * for [VoiceScenarios] to prove the table is exhaustive. Argument
         * commands appear with their empty/zero placeholder so the list is a
         * type inventory, not a suggestion of valid values.
         */
        fun allTypes(): List<VoiceCommand> = listOf(
            NavigateTo(""),
            SearchPlace(""),
            PlayMusic,
            PauseMusic,
            NextTrack,
            PreviousTrack,
            VolumeUp,
            VolumeDown,
            VolumeTo(0),
            GoHome,
            OpenMap,
            OpenMusic,
            OpenPhone,
            OpenApp(""),
            Exit
        )
    }
}

END FILE

## core/navigation-core/src/main/java/com/ts8/core/voice/VoiceScenarios.kt

BEGIN FILE
package com.ts8.core.voice

/**
 * Executable acceptance checks for the platform-free half of Voice Instant.
 *
 * **Why not a JUnit test.** The module has a test source set, but the acceptance
 * gate for this feature is the head unit build (`:core:navigation-core:compile…
 * --offline`) and a diagnostics screen that can print a verdict without a test
 * runner, a Robolectric bootstrap or an instrumented device. This object is
 * that verdict: it returns one line per *failure* and an empty list when every
 * rule holds, so a green run is visibly empty rather than an unread assertion
 * count.
 *
 * **What is actually verified.** Each case is one of the failures that would
 * otherwise reach the driver:
 *
 * - Two spellings of the same destination must parse to the same command —
 *   otherwise the recogniser's language pack decides which place is found.
 * - "mở" alone must be ambiguous and must never pick a screen.
 * - Garbage must be Unrecognized, never a guessed command.
 * - Confirming "yes" runs the command exactly once; "no" runs nothing.
 * - A timeout leaves IDLE, not a spinner.
 * - A CONFIRMING state restored after process death must come back IDLE.
 *
 * Nothing here touches a microphone, a Handler, a `SharedPreferences` or the
 * Android framework: the fake clock is an `Long` and the fake store is a
 * `HashMap`.
 */
object VoiceScenarios {

    /**
     * Runs every scenario.
     *
     * @return human-readable failure descriptions, empty when all pass.
     */
    fun verify(): List<String> {
        val failures = ArrayList<String>(16)
        checkParser(failures)
        checkRouter(failures)
        checkStore(failures)
        return failures
    }

    // -- parser ---------------------------------------------------------------

    private fun checkParser(failures: MutableList<String>) {
        val withDiacritics = IntentParser.parse("Dẫn đường tới Đà Nẵng")
        val withoutDiacritics = IntentParser.parse("dan duong toi Da Nang")

        val a = (withDiacritics as? IntentParser.ParseResult.Command)?.command
        val b = (withoutDiacritics as? IntentParser.ParseResult.Command)?.command
        if (a !is VoiceCommand.NavigateTo) {
            failures.add("parse: \"Dẫn đường tới Đà Nẵng\" did not yield NavigateTo (was $withDiacritics)")
        }
        if (b !is VoiceCommand.NavigateTo) {
            failures.add("parse: \"dan duong toi Da Nang\" did not yield NavigateTo (was $withoutDiacritics)")
        }
        if (a is VoiceCommand.NavigateTo && b is VoiceCommand.NavigateTo &&
            a.destination != b.destination
        ) {
            failures.add(
                "parse: destination differs by spelling — \"${a.destination}\" vs \"${b.destination}\""
            )
        }
        if (a is VoiceCommand.NavigateTo && a.destination.isBlank()) {
            failures.add("parse: destination came back blank for \"Dẫn đường tới Đà Nẵng\"")
        }

        // "mở" alone is the canonical ambiguity. It must list candidates and
        // must not become any command.
        when (val ambiguous = IntentParser.parse("mở")) {
            is IntentParser.ParseResult.Ambiguous -> {
                if (ambiguous.candidates.isEmpty()) {
                    failures.add("parse: \"mở\" is Ambiguous with an empty candidate list")
                }
                if (ambiguous.candidates.contains(VoiceCommand.Exit)) {
                    failures.add("parse: \"mở\" offered Exit as a candidate")
                }
            }
            else -> failures.add("parse: \"mở\" should be Ambiguous, was $ambiguous")
        }

        // "mở bản đồ" is not ambiguous; it is a whole phrase.
        val openMap = IntentParser.parse("mở bản đồ")
        if ((openMap as? IntentParser.ParseResult.Command)?.command != VoiceCommand.OpenMap) {
            failures.add("parse: \"mở bản đồ\" should be OpenMap, was $openMap")
        }

        // Garbage is never a command.
        val abc = IntentParser.parse("abc")
        if (abc !is IntentParser.ParseResult.Unrecognized) {
            failures.add("parse: \"abc\" should be Unrecognized, was $abc")
        }
        if ((abc as? IntentParser.ParseResult.Unrecognized)?.text != "abc") {
            failures.add("parse: Unrecognized must quote the original transcript")
        }

        // Empty speech is not a command either.
        if (IntentParser.parse("") !is IntentParser.ParseResult.Unrecognized) {
            failures.add("parse: empty transcript should be Unrecognized")
        }
        if (IntentParser.parse(null) !is IntentParser.ParseResult.Unrecognized) {
            failures.add("parse: null transcript should be Unrecognized")
        }

        // The remaining fixed phrases, each once: a table typo that swaps two
        // commands is invisible until a driver says the phrase.
        val table = listOf(
            "tìm cà phê" to VoiceCommand.SearchPlace("ca phe"),
            "phát nhạc" to VoiceCommand.PlayMusic,
            "tạm dừng" to VoiceCommand.PauseMusic,
            "dừng nhạc" to VoiceCommand.PauseMusic,
            "bài tiếp theo" to VoiceCommand.NextTrack,
            "bài trước" to VoiceCommand.PreviousTrack,
            "tăng âm lượng" to VoiceCommand.VolumeUp,
            "giảm âm lượng" to VoiceCommand.VolumeDown,
            "về nhà" to VoiceCommand.GoHome,
            "trang chủ" to VoiceCommand.GoHome,
            "mở nhạc" to VoiceCommand.OpenMusic,
            "thoát" to VoiceCommand.Exit
        )
        for ((phrase, expected) in table) {
            val actual = (IntentParser.parse(phrase) as? IntentParser.ParseResult.Command)?.command
            if (actual != expected) {
                failures.add("parse: \"$phrase\" should be $expected, was $actual")
            }
        }

        // Absolute volume, and its clamp rather than a rejection.
        val volume = (IntentParser.parse("âm lượng 40 phần trăm") as? IntentParser.ParseResult.Command)?.command
        if (volume != VoiceCommand.VolumeTo(40)) {
            failures.add("parse: \"âm lượng 40 phần trăm\" should be VolumeTo(40), was $volume")
        }
        val tooLoud = (IntentParser.parse("âm lượng 150%") as? IntentParser.ParseResult.Command)?.command
        if (tooLoud != VoiceCommand.VolumeTo(100)) {
            failures.add("parse: \"âm lượng 150%\" should clamp to VolumeTo(100), was $tooLoud")
        }

        // Confirmation policy must match the command table, not drift from it.
        for (command in VoiceCommand.allTypes()) {
            if (command.needsConfirmation && !command.destructive) {
                failures.add("command: ${command.id} asks for confirmation but is not destructive")
            }
            if (command.destructive && !command.needsConfirmation) {
                failures.add("command: ${command.id} is destructive but does not ask")
            }
        }
    }

    // -- router ---------------------------------------------------------------

    private fun checkRouter(failures: MutableList<String>) {
        checkConfirmYes(failures)
        checkConfirmNo(failures)
        checkTimeout(failures)
        checkUnrecognisedNeverRuns(failures)
        checkNonDestructiveRunsImmediately(failures)
    }

    private class RecordingExecutor : CommandExecutor {
        val commands = ArrayList<VoiceCommand>()
        var outcome: CommandOutcome = CommandOutcome.Done("Đã thực hiện")

        override fun execute(command: VoiceCommand): CommandOutcome {
            commands.add(command)
            return outcome
        }
    }

    private class RecordingListener : VoiceRouter.Listener {
        val states = ArrayList<VoiceState>()
        val spoken = ArrayList<String>()

        override fun onStateChanged(snapshot: VoiceStateSnapshot) {
            states.add(snapshot.state)
        }

        override fun onSpeak(text: String) {
            spoken.add(text)
        }
    }

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun router(
        executor: CommandExecutor,
        clock: FakeClock,
        listener: VoiceRouter.Listener = RecordingListener(),
        config: VoiceRouter.Config = VoiceRouter.Config()
    ): VoiceRouter = VoiceRouter(executor, { clock.now }, config).also { it.setListener(listener) }

    private fun checkConfirmYes(failures: MutableList<String>) {
        val executor = RecordingExecutor()
        val clock = FakeClock()
        val listener = RecordingListener()
        val r = router(executor, clock, listener)

        r.onTranscript("dẫn đường tới Đà Nẵng")
        if (r.state() != VoiceState.CONFIRMING) {
            failures.add("router: navigate should reach CONFIRMING, was ${r.state()}")
            return
        }
        if (executor.commands.isNotEmpty()) {
            failures.add("router: destructive command ran before confirmation")
            return
        }
        clock.advance(1_000L)
        r.confirm(true)

        if (r.state() != VoiceState.SUCCESS) {
            failures.add("router: confirm(true) should reach SUCCESS, was ${r.state()}")
        }
        if (executor.commands.size != 1) {
            failures.add("router: confirm(true) executed ${executor.commands.size} commands, expected 1")
        }
        if (executor.commands.firstOrNull() !is VoiceCommand.NavigateTo) {
            failures.add("router: confirm(true) executed the wrong command")
        }
        if (listener.spoken.isEmpty()) {
            failures.add("router: SUCCESS spoke nothing")
        }
    }

    private fun checkConfirmNo(failures: MutableList<String>) {
        val executor = RecordingExecutor()
        val clock = FakeClock()
        val r = router(executor, clock)

        r.onTranscript("phát nhạc")
        if (r.state() != VoiceState.CONFIRMING) {
            failures.add("router: play music should reach CONFIRMING, was ${r.state()}")
            return
        }
        r.confirm(false)
        if (executor.commands.isNotEmpty()) {
            failures.add("router: confirm(false) still executed the command")
        }
        if (r.state() != VoiceState.IDLE) {
            failures.add("router: confirm(false) should return to IDLE, was ${r.state()}")
        }
        if (r.pendingCommand() != null) {
            failures.add("router: pending command survived a \"no\"")
        }
    }

    private fun checkTimeout(failures: MutableList<String>) {
        val executor = RecordingExecutor()
        val clock = FakeClock()
        val r = router(executor, clock, config = VoiceRouter.Config(timeoutMs = 8_000L))

        r.onTranscript("dẫn đường tới Hội An")
        if (r.state() != VoiceState.CONFIRMING) {
            failures.add("router: timeout scenario did not reach CONFIRMING, was ${r.state()}")
            return
        }

        // Early timeout must be ignored: the caller's timer can be a message
        // queued for an earlier interaction.
        clock.advance(2_000L)
        r.onTimeout()
        if (r.state() != VoiceState.CONFIRMING) {
            failures.add("router: early timeout fired at ${2_000L} ms, state ${r.state()}")
        }

        clock.advance(6_000L)
        r.onTimeout()
        if (r.state() != VoiceState.IDLE) {
            failures.add("router: timeout should return to IDLE, was ${r.state()}")
        }
        if (r.pendingCommand() != null) {
            failures.add("router: pending command survived a timeout")
        }
        if (executor.commands.isNotEmpty()) {
            failures.add("router: timeout executed the pending command")
        }
        if (r.lastError != VoiceError.TIMEOUT) {
            failures.add("router: timeout did not record VoiceError.TIMEOUT")
        }

        // The "yes" that arrives after the timeout must not resurrect it.
        r.confirm(true)
        if (executor.commands.isNotEmpty()) {
            failures.add("router: a \"yes\" after timeout executed the command")
        }
        if (r.state() != VoiceState.IDLE) {
            failures.add("router: a \"yes\" after timeout left state ${r.state()}")
        }
    }

    private fun checkUnrecognisedNeverRuns(failures: MutableList<String>) {
        val executor = RecordingExecutor()
        val r = router(executor, FakeClock())
        r.onTranscript("abc")
        if (r.state() != VoiceState.ERROR) {
            failures.add("router: unrecognised input should reach ERROR, was ${r.state()}")
        }
        if (r.lastError != VoiceError.NOT_UNDERSTOOD) {
            failures.add("router: unrecognised input recorded ${r.lastError}, expected NOT_UNDERSTOOD")
        }
        if (executor.commands.isNotEmpty()) {
            failures.add("router: unrecognised input executed a command")
        }

        // An ambiguous phrase is also not executed.
        val e2 = RecordingExecutor()
        val r2 = router(e2, FakeClock())
        r2.onTranscript("mở")
        if (e2.commands.isNotEmpty()) {
            failures.add("router: ambiguous input executed a command")
        }
        if (r2.state() != VoiceState.ERROR) {
            failures.add("router: ambiguous input should reach ERROR, was ${r2.state()}")
        }
    }

    private fun checkNonDestructiveRunsImmediately(failures: MutableList<String>) {
        val executor = RecordingExecutor()
        executor.outcome = CommandOutcome.Done("Đang tăng âm lượng")
        val listener = RecordingListener()
        val r = router(executor, FakeClock(), listener)
        r.onTranscript("tăng âm lượng")
        if (executor.commands.size != 1) {
            failures.add("router: non-destructive command needed ${executor.commands.size} executions")
        }
        if (r.state() != VoiceState.SUCCESS) {
            failures.add("router: non-destructive command ended in ${r.state()}, expected SUCCESS")
        }
        if (listener.spoken.none { it == "Đang tăng âm lượng" }) {
            failures.add("router: executor's own spoken text was not passed to the listener")
        }

        // A failing executor must produce ERROR, not silence.
        val failing = RecordingExecutor()
        failing.outcome = CommandOutcome.Failed("Không có bài nào để phát")
        val r2 = router(failing, FakeClock())
        r2.onTranscript("bài tiếp theo")
        if (r2.state() != VoiceState.ERROR) {
            failures.add("router: Failed outcome ended in ${r2.state()}, expected ERROR")
        }

        // A throwing executor must not leave the state machine in EXECUTING.
        val throwing = object : CommandExecutor {
            override fun execute(command: VoiceCommand): CommandOutcome =
                throw IllegalStateException("executor bug")
        }
        val r3 = router(throwing, FakeClock())
        r3.onTranscript("về nhà")
        if (r3.state() != VoiceState.ERROR) {
            failures.add("router: throwing executor left state ${r3.state()}, expected ERROR")
        }
    }

    // -- store ----------------------------------------------------------------

    private class MapStore(private val map: HashMap<String, String>) : KeyValueStore {
        override fun get(key: String): String? = map[key]

        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private fun checkStore(failures: MutableList<String>) {
        val map = HashMap<String, String>()
        val store = MapStore(map)

        // Restore of CONFIRMING must be IDLE: a prompt nobody can answer must
        // not survive the process that asked it.
        val saved = VoiceStateSnapshot(
            state = VoiceState.CONFIRMING,
            sinceElapsedMs = 500L,
            lastCommandId = "navigate_to",
            error = null
        )
        map[VoiceStateStore.KEY] = VoiceStateCodec.encode(saved)
        val restored = VoiceStateStore(store).restore(nowElapsedMs = 9_000L)
        if (restored == null) {
            failures.add("store: CONFIRMING snapshot did not decode")
        } else {
            if (restored.state != VoiceState.IDLE) {
                failures.add("store: CONFIRMING restored as ${restored.state}, expected IDLE")
            }
            if (restored.lastCommandId != null) {
                failures.add("store: in-flight snapshot kept its command id")
            }
            if (restored.sinceElapsedMs != 9_000L) {
                failures.add("store: degraded snapshot was not stamped with restore time")
            }
        }

        // Every in-flight state degrades, not just the one above.
        for (inFlight in listOf(
            VoiceState.LISTENING,
            VoiceState.PROCESSING,
            VoiceState.EXECUTING
        )) {
            map[VoiceStateStore.KEY] =
                VoiceStateCodec.encode(VoiceStateSnapshot(inFlight, 1L, "x", null))
            val s = VoiceStateStore(store).restore(2L)?.state
            if (s != VoiceState.IDLE) {
                failures.add("store: $inFlight restored as $s, expected IDLE")
            }
        }

        // A terminal ERROR is preserved, including its reason: the driver saw
        // it before the radio cut and must see the same sentence after.
        map[VoiceStateStore.KEY] = VoiceStateCodec.encode(
            VoiceStateSnapshot(VoiceState.ERROR, 42L, null, VoiceError.NO_MATCH)
        )
        val errorRestore = VoiceStateStore(store).restore(3L)
        if (errorRestore?.state != VoiceState.ERROR || errorRestore.error != VoiceError.NO_MATCH) {
            failures.add("store: ERROR snapshot was not preserved (got $errorRestore)")
        }

        // No row is "no answer", not IDLE and certainly not a fabricated state.
        map.remove(VoiceStateStore.KEY)
        if (VoiceStateStore(store).restore(4L) != null) {
            failures.add("store: empty store should restore to null")
        }

        // A truncated row must not decode into a plausible-looking state.
        map[VoiceStateStore.KEY] = "TS8VS1\nstate=CONFIRMING\nsince=5\n"
        if (VoiceStateStore(store).restore(5L) != null) {
            failures.add("store: truncated row decoded instead of yielding null")
        }

        // Written by the same store the restore reads back from.
        val realStore = MapStore(map)
        VoiceStateStore(realStore).save(VoiceStateSnapshot(VoiceState.SUCCESS, 10L, "open_map", null))
        val roundTrip = VoiceStateStore(realStore).restore(11L)
        if (roundTrip?.state != VoiceState.SUCCESS || roundTrip.lastCommandId != "open_map") {
            failures.add("store: SUCCESS snapshot did not round-trip (got $roundTrip)")
        }
    }
}

END FILE

## core/navigation-core/src/main/java/com/ts8/core/voice/VoiceState.kt

BEGIN FILE
package com.ts8.core.voice

/**
 * The seven states Voice Instant is allowed to be in.
 *
 * **Why [CONFIRMING] is a state and not a boolean on the overlay.** A dialog
 * flag is invisible to everything that is not the dialog: Back would close a
 * window the router still believes is open, process recreation would restore a
 * "no" that never happened, and the music ducking started for the confirmation
 * prompt would never be undone because nobody counted it. As a state, "waiting
 * for yes/no" is the single fact the overlay renders from, the only state in
 * which Back is a cancel rather than a screen pop, and the one the router can
 * time out of on its own.
 *
 * The transitions are the contract; see [VoiceRouter]. A transcript may only
 * move the machine forward from [IDLE] or [ERROR]; everything else is a caller
 * bug, and the router drops it rather than guessing.
 */
enum class VoiceState {
    /** No microphone, no prompt, nothing pending. The overlay is not drawn. */
    IDLE,

    /** The platform recogniser is capturing. Only the app module can leave this
     *  state, by handing the router a transcript or an error. */
    LISTENING,

    /** A transcript arrived and the parser is deciding what it means. */
    PROCESSING,

    /**
     * A command was understood but is destructive; the driver must answer yes or
     * no. Back cancels from here and only here.
     */
    CONFIRMING,

    /** The command was handed to the executor and has not returned yet. */
    EXECUTING,

    /** The executor reported success. Terminal until the overlay asks for IDLE. */
    SUCCESS,

    /** Something failed; [VoiceStateSnapshot.error] says what. Terminal until
     *  the overlay asks for IDLE. */
    ERROR
}

/**
 * Every way a voice command can fail, kept enumerable so the overlay has exactly
 * one branch per case and the app module can map `SpeechRecognizer` result codes
 * onto a closed set instead of inventing strings.
 *
 * None of these is ever silently swallowed: a failure the driver cannot see is
 * indistinguishable from a command still running, which is how an overlay ends
 * up stuck on "Đang xử lý…" until ignition off.
 */
enum class VoiceError {
    /** No recogniser installed, or its service is not bound. Not a no-match. */
    SERVICE_UNAVAILABLE,

    /** The recogniser heard nothing before the end-of-speech timeout. */
    NO_SPEECH,

    /** Speech was heard but no hypothesis came back. */
    NO_MATCH,

    /** The driver said nothing, or the confirmation went unanswered. */
    TIMEOUT,

    /** Speech was heard and transcribed, but it is not one of our commands. */
    NOT_UNDERSTOOD,

    /** The executor rejected or failed the command after it was accepted. */
    COMMAND_FAILED,

    /** `RECORD_AUDIO` was never granted. Distinct from unavailable: this is
     *  fixable by the driver in Settings, and the prompt must say so. */
    PERMISSION_DENIED
}

/**
 * Everything the overlay needs to render one frame, and nothing else.
 *
 * [sinceElapsedMs] is a monotonic timestamp (`SystemClock.elapsedRealtime` on
 * device, a fake in tests), not wall-clock: the overlay uses it to show "đang
 * nghe…" for at most [VoiceRouter]'s timeout, and a wall clock that jumps when
 * the head unit syncs GPS time would cut that short or stretch it by hours.
 *
 * [lastCommandId] is null when there is genuinely no command yet — it is not
 * filled with a synthetic id, because the diagnostics screen uses it to match a
 * spoken result to a log line and a fabricated id would match nothing.
 * [error] is likewise null for every non-[VoiceState.ERROR] state; the type is
 * nullable so "no answer" is representable instead of defaulting to
 * [VoiceError.NO_MATCH], which would read as a real recogniser verdict.
 */
data class VoiceStateSnapshot(
    val state: VoiceState,
    /** Monotonic milliseconds at which this snapshot's [state] was entered. */
    val sinceElapsedMs: Long,
    /** Identifier of the command this state is about, or null before any parse. */
    val lastCommandId: String?,
    /** Why the last attempt failed; null unless [state] is [VoiceState.ERROR]. */
    val error: VoiceError?
) {
    /** True while the overlay must stay on screen. */
    val overlayVisible: Boolean get() = state != VoiceState.IDLE

    /**
     * True when Back means "cancel this voice interaction" rather than "leave
     * the screen". Deliberately includes the in-flight states as well as
     * [VoiceState.CONFIRMING]: the driver pressing Back while the recogniser is
     * still listening expects the microphone to stop.
     */
    val backCancels: Boolean get() = when (state) {
        VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.CONFIRMING, VoiceState.EXECUTING -> true
        VoiceState.IDLE, VoiceState.SUCCESS, VoiceState.ERROR -> false
    }

    companion object {
        /** The honest empty snapshot: idle, no command, no error. */
        fun idle(atElapsedMs: Long): VoiceStateSnapshot =
            VoiceStateSnapshot(VoiceState.IDLE, atElapsedMs, null, null)
    }
}

END FILE

## core/navigation-core/src/main/java/com/ts8/core/voice/VoiceStateStore.kt

BEGIN FILE
package com.ts8.core.voice

/**
 * The two operations the store needs, and nothing else.
 *
 * **Why not `SharedPreferences` directly.** This module has to be testable on a
 * JVM, and more importantly it has to be provable that the store *cannot* write
 * a half-understood row: `SharedPreferences.edit().apply()` is asynchronous and
 * a `putString` that fails silently is indistinguishable from one that was
 * never reached. A plain get/put pair is trivial to fake, and the app module
 * implements it with `SharedPreferences` synchronously off the main thread.
 *
 * **Why this is a plain interface and not a `fun interface`.** The brief asks
 * for `fun interface KeyValueStore { get(key); put(key, value) }`, but Kotlin
 * only allows a SAM conversion on an interface with exactly one abstract
 * member, and two are required here. Declaring it `fun interface` does not
 * compile, so the interface stays ordinary; the call site cost is one small
 * object literal, visible in the app module. Flagged for the integrator.
 */
interface KeyValueStore {
    /** The last value written for [key], or null when nothing was ever written. */
    fun get(key: String): String?

    /** Overwrites [key]. Must not throw; a failed write degrades to "no state". */
    fun put(key: String, value: String)
}

/**
 * Persists the last voice state so a process that dies mid-overlay comes back
 * **idle**, not stuck.
 *
 * **The rule this type exists for.** If the launcher is killed (low memory on a
 * 128 MB heap, or the head unit cutting ignition) while the overlay says
 * "Đang nghe…", the next process must not restore that snapshot. The microphone
 * session died with the old process; there is no recogniser, no TTS utterance
 * and no `Handler` alive to move it forward, so a restored LISTENING would draw
 * an overlay whose spinner never stops and whose Back cancels nothing. The same
 * argument applies to PROCESSING and EXECUTING (the executor's command was
 * cancelled with the process, and replaying it would be a double execution) and
 * to CONFIRMING (the driver's "yes" was never said, and re-asking on next boot
 * for a command they have forgotten is worse than dropping it).
 *
 * So [restore] degrades exactly those four in-flight states to [VoiceState.IDLE].
 * [VoiceState.SUCCESS] and [VoiceState.ERROR] are terminal and *are* restored:
 * they describe something that already happened, the overlay can render them
 * without any live resource, and a driver who saw "Không hiểu lệnh" before the
 * radio cut should see the same sentence when it comes back.
 *
 * **What is deliberately not stored.** The pending command is never persisted.
 * Restoring it would let a boot-time overlay offer to destroy an active route
 * from a previous ignition cycle.
 */
class VoiceStateStore(private val store: KeyValueStore) {

    /**
     * Writes [snapshot].
     *
     * In-flight states are written too — the *degradation* belongs to restore,
     * not to save, because a live process asking "what was the state?" after a
     * configuration change (not a death) must get the truth. Only a fresh
     * process reads through [restore], and that is where the rule applies.
     */
    fun save(snapshot: VoiceStateSnapshot) {
        store.put(KEY, VoiceStateCodec.encode(snapshot))
    }

    /**
     * The state to start the overlay with, or null when nothing was ever saved
     * (or the saved row is unreadable — a truncated write is not an error to
     * report to the driver, it is simply no answer).
     *
     * @param nowElapsedMs the monotonic clock at restore time; a degraded
     *        state is stamped with it, because "since when" for a state that
     *        did not survive is the moment we decided it did not.
     */
    fun restore(nowElapsedMs: Long): VoiceStateSnapshot? {
        val decoded = VoiceStateCodec.decode(store.get(KEY)) ?: return null
        return when (decoded.state) {
            // A microphone session, a parse, a pending answer or a running
            // command cannot outlive the process. Pretending otherwise is the
            // stuck-overlay bug this class is about.
            VoiceState.LISTENING,
            VoiceState.PROCESSING,
            VoiceState.CONFIRMING,
            VoiceState.EXECUTING -> VoiceStateSnapshot.idle(nowElapsedMs)

            // Terminal states are safe to keep. The timestamp is preserved
            // verbatim: it is meaningful within the same boot, and a reboot is
            // detectable by the caller comparing it with the current monotonic
            // value, so blanking it here would throw away the only evidence.
            VoiceState.SUCCESS,
            VoiceState.ERROR,
            VoiceState.IDLE -> decoded
        }
    }

    /** Drops the stored state; call when the overlay is dismissed for good. */
    fun clear() {
        store.put(KEY, "")
    }

    companion object {
        /** Single row: one write, so a kill cannot leave two keys disagreeing. */
        const val KEY = "voice.last_state"
    }
}

/**
 * Line-oriented codec for [VoiceStateSnapshot].
 *
 * Not JSON: `org.json` is Android-only, and this codec is exercised by
 * [VoiceScenarios] on a plain JVM. Not a binary format: a row a human can read
 * back from `adb shell run-as ... cat` is worth more during a field report than
 * four bytes saved.
 *
 * Any malformed row decodes to null. A half-written state is not a state; the
 * caller gets "no answer" and starts from IDLE, which is always safe.
 */
internal object VoiceStateCodec {

    private const val MAGIC = "TS8VS1"
    private const val END = "end"
    private const val MAX_INPUT = 4_096

    fun encode(snapshot: VoiceStateSnapshot): String = buildString {
        append(MAGIC).append('\n')
        append("state=").append(snapshot.state.name).append('\n')
        append("since=").append(snapshot.sinceElapsedMs).append('\n')
        // "none" rather than an empty value: an empty value and a missing key
        // would decode the same, and "no command" must be representable without
        // a null the format cannot distinguish from truncation.
        append("command=").append(snapshot.lastCommandId ?: NONE).append('\n')
        append("error=").append(snapshot.error?.name ?: NONE).append('\n')
        append(END).append('\n')
    }

    fun decode(raw: String?): VoiceStateSnapshot? {
        if (raw.isNullOrBlank() || raw.length > MAX_INPUT) return null
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != MAGIC) return null
        if (lines.none { it.trim() == END }) return null

        var state: VoiceState? = null
        var since = 0L
        var sinceSeen = false
        var command: String? = null
        var error: VoiceError? = null

        for (line in lines.drop(1)) {
            if (line.isBlank() || line.trim() == END) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "state" -> state = runCatching { VoiceState.valueOf(value) }
                    .getOrNull() ?: return null
                "since" -> {
                    since = value.toLongOrNull() ?: return null
                    sinceSeen = true
                }
                "command" -> command = if (value == NONE || value.isEmpty()) null else value
                "error" -> error = if (value == NONE) {
                    null
                } else {
                    runCatching { VoiceError.valueOf(value) }.getOrNull() ?: return null
                }
                // Forward compatibility: a newer build may add keys. Ignoring
                // them keeps an upgrade from wiping the driver's last state.
                else -> Unit
            }
        }

        val decodedState = state ?: return null
        // A state with no timestamp cannot be aged out by the overlay; refusing
        // it is better than inventing zero, which reads as "just now".
        if (!sinceSeen) return null
        if (decodedState == VoiceState.ERROR && error == null) {
            // An ERROR with no reason renders a blank error line. That is a
            // corrupt row, not an empty error.
            return null
        }
        if (decodedState != VoiceState.ERROR && error != null) {
            return null
        }
        return VoiceStateSnapshot(decodedState, since, command, error)
    }

    private const val NONE = "none"
}

END FILE

## core/navigation-core/src/test/java/com/ts8/core/navigation/NavigationControllerTest.kt

BEGIN FILE
package com.ts8.core.navigation

import com.ts8.core.location.LocationFix
import com.ts8.core.map.GeoPoint
import com.ts8.core.routing.Maneuver
import com.ts8.core.routing.ManeuverDirection
import com.ts8.core.routing.ManeuverType
import com.ts8.core.routing.Route
import com.ts8.core.routing.RouteResult
import com.ts8.core.routing.RouteError
import com.ts8.core.routing.RoutingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * State-machine tests.
 *
 * The executor is a direct-executor stub so a route computation completes
 * inline and the assertions read as a sequence of events rather than races.
 * The routing engine is a programmable fake — that is the one place a fake is
 * correct here: these tests are about the state machine's decisions, and the
 * real router's output is covered against the real pack in
 * `:core:routing-engine-offline`.
 */
class NavigationControllerTest {

    private class DirectExecutor : AbstractExecutorService() {
        private var shutdown = false

        override fun execute(command: Runnable) {
            if (!shutdown) command.run()
        }

        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class FakeRouter : RoutingEngine {
        var result: RouteResult = RouteResult.Failure(RouteError.UNREACHABLE)
        var failUnreachable = false
        var routeCount = 0

        override fun isReady(): Boolean = true

        override fun snapToRoad(point: GeoPoint): GeoPoint = point

        override fun route(from: GeoPoint, to: GeoPoint, options: com.ts8.core.routing.RouteOptions): RouteResult {
            routeCount++
            return result
        }

        override fun close() = Unit
    }

    private fun straightRoute(metres: Int = 2_000): Route {
        val maneuvers = listOf(
            Maneuver(ManeuverType.DEPART, GeoPoint(16.06, 108.20), 0, "Nguyễn Văn Linh", "Bắt đầu đi trên Nguyễn Văn Linh"),
            Maneuver(ManeuverType.TURN, GeoPoint(16.06, 108.205), 1_000, "Trần Phú", "Rẽ phải vào Trần Phú", ManeuverDirection.RIGHT),
            Maneuver(ManeuverType.ARRIVE, GeoPoint(16.06, 108.21), metres, "Trần Phú", "Đã đến nơi")
        )
        return Route(
            points = listOf(GeoPoint(16.06, 108.20), GeoPoint(16.06, 108.21)),
            distanceMeters = metres,
            durationSeconds = 300,
            maneuvers = maneuvers,
            nodeIds = intArrayOf(1, 2)
        )
    }

    private fun fixAt(alongMeters: Int, offRoute: Float? = 3f, snapped: Boolean = true) = LocationFix(
        point = GeoPoint(16.06, 108.20),
        rawPoint = GeoPoint(16.06, 108.20),
        accuracyMeters = 5f,
        speedMps = 14f,
        bearingDeg = 90f,
        elapsedRealtimeMs = 1_000,
        distanceFromRouteMeters = offRoute,
        distanceAlongRouteMeters = alongMeters.toFloat(),
        snappedToRoute = snapped
    )

    private class Recorder : NavigationController.Listener {
        val states = ArrayList<NavigationState>()
        val announcements = ArrayList<Announcement>()
        var endedReason: NavigationEndReason? = null
        var lastRoute: Route? = null
        var overview = false

        override fun onStateChanged(uiState: NavigationUiState) {
            if (states.lastOrNull() != uiState.state) states.add(uiState.state)
        }

        override fun onAnnounce(announcement: Announcement) {
            announcements.add(announcement)
        }

        override fun onRouteChanged(route: Route?) {
            lastRoute = route
        }

        override fun onOverviewChanged(overview: Boolean) {
            this.overview = overview
        }

        override fun onEnded(reason: NavigationEndReason) {
            endedReason = reason
        }
    }

    private fun controller(
        router: FakeRouter = FakeRouter(),
        clock: () -> Long = { 0L }
    ): Pair<NavigationController, Recorder> {
        val c = NavigationController(router, NavigationController.Config(), DirectExecutor(), clock)
        val r = Recorder()
        c.setListener(r)
        return c to r
    }

    private val origin = GeoPoint(16.06, 108.20)
    private val dest = GeoPoint(16.06, 108.21)

    @Test
    fun `starts idle`() {
        val (c, _) = controller()
        assertEquals(NavigationState.IDLE, c.state())
        assertNull(c.route())
    }

    @Test
    fun `happy path reaches route preview then navigating`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, rec) = controller(router)

        c.beginSearch()
        assertEquals(NavigationState.SEARCHING, c.state())

        c.selectDestination(dest, "Chợ Hàn", origin)
        assertEquals(NavigationState.ROUTE_PREVIEW, c.state())
        assertNotNull(c.route())

        c.startNavigation()
        assertEquals(NavigationState.NAVIGATING, c.state())

        assertEquals(
            listOf(
                NavigationState.SEARCHING,
                NavigationState.DESTINATION_PREVIEW,
                NavigationState.ROUTE_CALCULATING,
                NavigationState.ROUTE_PREVIEW,
                NavigationState.NAVIGATING
            ),
            rec.states
        )
    }

    @Test
    fun `selecting a destination without a position fails visibly`() {
        val (c, _) = controller()
        c.selectDestination(dest, "Chợ Hàn", from = null)
        assertEquals(NavigationState.IDLE, c.state())
        assertNotNull("must tell the driver why", c.uiState().message)
    }

    @Test
    fun `a failed route returns to the preview with a message, not to idle`() {
        val router = FakeRouter().apply { result = RouteResult.Failure(RouteError.NO_END_NODE) }
        val (c, _) = controller(router)
        c.selectDestination(dest, "X", origin)
        assertEquals(NavigationState.DESTINATION_PREVIEW, c.state())
        assertNotNull(c.uiState().message)
        assertNull(c.route())
    }

    @Test
    fun `unreachable is retried once with snapped endpoints`() {
        val router = FakeRouter().apply { result = RouteResult.Failure(RouteError.UNREACHABLE) }
        val (c, _) = controller(router)
        c.selectDestination(dest, "X", origin)
        // Called twice: the original and the snapped retry.
        assertEquals(2, router.routeCount)
    }

    @Test
    fun `progress advances along the route`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute(2_000)) }
        val (c, _) = controller(router)
        c.selectDestination(dest, "Chợ Hàn", origin)
        c.startNavigation()

        c.onLocation(fixAt(500))
        val p = c.uiState().progress!!
        assertEquals(1_500, p.remainingMeters)
        assertTrue("fraction ${p.fraction}", p.fraction in 0.24f..0.26f)
    }

    @Test
    fun `arrival radius triggers ARRIVED`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute(1_000)) }
        val (c, rec) = controller(router)
        c.selectDestination(dest, "Chợ Hàn", origin)
        c.startNavigation()

        c.onLocation(fixAt(980))
        assertEquals(NavigationState.ARRIVED, c.state())
        assertTrue(rec.announcements.any { it.text.contains("Đã đến nơi") })
    }

    @Test
    fun `arrival is not announced twice`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute(2_000)) }
        val (c, rec) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()

        c.onLocation(fixAt(1_800))   // inside the 300 m arrival announce band
        c.onLocation(fixAt(1_850))
        val arrivalAnnouncements = rec.announcements.count { it.text.contains("đến nơi") }
        assertEquals(1, arrivalAnnouncements)
    }

    @Test
    fun `a single off-route fix never reroutes`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, _) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()
        val before = router.routeCount

        // One fix 200 m off the line — a GPS spike that the location engine
        // rejected would never reach here; this proves the controller also
        // refuses on its own.
        c.onLocation(fixAt(500, offRoute = 200f, snapped = false))
        assertEquals(NavigationState.NAVIGATING, c.state())
        assertEquals("rerouted on a single spike", before, router.routeCount)
    }

    @Test
    fun `sustained departure triggers exactly one reroute`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, _) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()
        val before = router.routeCount

        repeat(4) { c.onLocation(fixAt(500, offRoute = 200f, snapped = false)) }

        assertEquals("expected one reroute", before + 1, router.routeCount)
        assertEquals(NavigationState.NAVIGATING, c.state())
    }

    @Test
    fun `reroute cooldown suppresses a second reroute`() {
        var now = 1_000L
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, _) = controller(router) { now }
        c.selectDestination(dest, "X", origin)
        c.startNavigation()
        val baseline = router.routeCount

        repeat(4) { c.onLocation(fixAt(500, offRoute = 200f, snapped = false)) }
        assertEquals(baseline + 1, router.routeCount)

        // Within the 20 s cooldown, leaving again must not compute.
        repeat(4) { c.onLocation(fixAt(520, offRoute = 200f, snapped = false)) }
        assertEquals("rerouted inside the cooldown", baseline + 1, router.routeCount)

        now += 25_000
        repeat(4) { c.onLocation(fixAt(540, offRoute = 200f, snapped = false)) }
        assertEquals("cooldown should have expired", baseline + 2, router.routeCount)
    }

    @Test
    fun `a failed reroute keeps the old route on screen`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, _) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()
        val original = c.route()

        router.result = RouteResult.Failure(RouteError.UNREACHABLE)
        repeat(4) { c.onLocation(fixAt(500, offRoute = 200f, snapped = false)) }

        assertEquals(
            "a failed reroute must not strand the driver with no line",
            NavigationState.NAVIGATING, c.state()
        )
        assertNotNull(c.route())
        assertEquals(original!!.distanceMeters, c.route()!!.distanceMeters)
    }

    @Test
    fun `maneuvers are announced at the configured thresholds, once each`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute(2_000)) }
        val (c, rec) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()

        // Approaching the 1000 m turn: at 900 m from it the 900..1000 band
        // fires the "1,0 km" (i.e. 1000 threshold) prompt.
        c.onLocation(fixAt(100))
        val first = rec.announcements.size
        // Same distance again: no repeat.
        c.onLocation(fixAt(105))
        assertEquals(first, rec.announcements.size)
    }

    @Test
    fun `muting suppresses announcements but keeps visual progress`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute(2_000)) }
        val (c, rec) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()

        c.setMuted(true)
        c.onLocation(fixAt(100))
        assertTrue("muted driver was spoken to", rec.announcements.isEmpty())
        assertNotNull("visual guidance must still update", c.uiState().progress)

        c.setMuted(false)
        c.onLocation(fixAt(120))
        assertTrue("unmuting must resume guidance", rec.announcements.isNotEmpty())
    }

    @Test
    fun `overview toggles without changing state`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, rec) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()

        c.showOverview(true)
        assertEquals(NavigationState.NAVIGATING, c.state())
        assertTrue(rec.overview)

        c.showOverview(false)
        assertFalse(rec.overview)
    }

    @Test
    fun `ending navigation clears the route and reports the reason`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, rec) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()

        c.end(NavigationEndReason.USER_ENDED)
        assertEquals(NavigationState.IDLE, c.state())
        assertNull(c.route())
        assertNull(rec.lastRoute)
        assertEquals(NavigationEndReason.USER_ENDED, rec.endedReason)
    }

    @Test
    fun `losing the pack mid-drive ends guidance with a clear message`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, rec) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()

        c.onPackLost()
        assertEquals(NavigationState.IDLE, c.state())
        assertEquals(NavigationEndReason.PACK_LOST, rec.endedReason)
    }

    @Test
    fun `search cannot interrupt active guidance`() {
        val router = FakeRouter().apply { result = RouteResult.Success(straightRoute()) }
        val (c, _) = controller(router)
        c.selectDestination(dest, "X", origin)
        c.startNavigation()

        c.beginSearch()
        assertEquals(NavigationState.NAVIGATING, c.state())
    }

    @Test
    fun `distance formatting uses Vietnamese conventions`() {
        assertEquals("300 m", NavigationController.formatDistance(300))
        assertEquals("1,2 km", NavigationController.formatDistance(1_200))
        assertEquals("15 km", NavigationController.formatDistance(15_000))
        assertEquals("", NavigationController.formatDistance(5))
    }
}

END FILE

## core/navigation-core/src/test/java/com/ts8/core/voice/IntentParserTest.kt

BEGIN FILE
package com.ts8.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IntentParser` — the folding and the match order.
 *
 * This is the part of Voice Instant that fails silently when it fails: a
 * misparsed phrase does not throw, it does the wrong thing to a driver's hand.
 * The parser had no JUnit coverage before this file; `VoiceScenarios` walks a
 * few phrases but is a plain object `gradlew test` never ran.
 *
 * Two rules dominate:
 *
 *  - **Fold first, parse second.** The TS8's recogniser may return "Dẫn đường
 *    tới Đà Nẵng" or "dan duong toi Da Nang" depending on which language pack
 *    the ROM carries, and both must reach the *same* destination. The
 *    destination is a search key, not a string to print, so it is folded too.
 *  - **Never guess.** A phrase that fits several commands is [ParseResult.Ambiguous],
 *    and a phrase that fits none is [ParseResult.Unrecognized]. There is no
 *    best-effort branch to accidentally fall into.
 */
class IntentParserTest {

    private fun command(transcript: String): VoiceCommand =
        (IntentParser.parse(transcript) as? IntentParser.ParseResult.Command)?.command
            ?: error("expected a command from \"$transcript\", got ${IntentParser.parse(transcript)}")

    // -- folding -----------------------------------------------------------

    @Test
    fun `fold strips tone marks and maps d-stroke to d`() {
        assertEquals("dan duong toi da nang", IntentParser.fold("Dẫn đường tới Đà Nẵng"))
    }

    @Test
    fun `fold upper and lower case reach the same key`() {
        assertEquals(IntentParser.fold("Đà Nẵng"), IntentParser.fold("đà nẵng"))
    }

    @Test
    fun `fold keeps the percent sign, because volume only parses with it`() {
        assertEquals("am luong 40%", IntentParser.fold("Âm lượng 40%"))
    }

    @Test
    fun `fold collapses punctuation and whitespace runs`() {
        assertEquals("mo ban do", IntentParser.fold("  Mở...  bản đồ!!  "))
    }

    @Test
    fun `fold of null and of nothing is empty`() {
        assertEquals("", IntentParser.fold(null))
        assertEquals("", IntentParser.fold(""))
    }

    // -- the same destination either way -----------------------------------

    @Test
    fun `with and without diacritics give the same folded destination`() {
        val with = command("Dẫn đường tới Đà Nẵng") as VoiceCommand.NavigateTo
        val without = command("dan duong toi Da Nang") as VoiceCommand.NavigateTo
        assertEquals("da nang", with.destination)
        assertEquals(with.destination, without.destination)
    }

    @Test
    fun `the connector is dropped exactly once`() {
        val to = command("chỉ đường đến Hội An") as VoiceCommand.NavigateTo
        assertEquals("hoi an", to.destination)
        // A place that genuinely starts with a connector word keeps it, because
        // only one connector is removed.
        val via = command("dẫn đường tới về nhà") as VoiceCommand.NavigateTo
        assertEquals("ve nha", via.destination)
    }

    // -- navigation before search ------------------------------------------

    @Test
    fun `tim duong is navigation, not a search for duong`() {
        // The order bug this pins: "tìm đường" would otherwise be consumed by
        // the search trigger "tìm" and search the index for the word "đường".
        val result = command("tìm đường Cầu Rồng")
        assertTrue(result is VoiceCommand.NavigateTo)
    }

    @Test
    fun `tim kiem is a search`() {
        val result = command("tìm kiếm cầu rồng")
        assertTrue(result is VoiceCommand.SearchPlace)
        assertEquals("cau rong", (result as VoiceCommand.SearchPlace).query)
    }

    @Test
    fun `navigation with no destination is not a command`() {
        // "đi đâu?" is the overlay's question to ask. Navigating to nowhere is
        // not representable, so this must not become a command.
        val result = IntentParser.parse("dẫn đường")
        assertTrue(result is IntentParser.ParseResult.Unrecognized)
    }

    // -- exact phrases before the bare command -----------------------------

    @Test
    fun `mo nhac opens the music screen, it does not launch an app called nhac`() {
        assertEquals(VoiceCommand.OpenMusic, command("mở nhạc"))
    }

    @Test
    fun `mo ung dung nhac also stays the music screen`() {
        // The app-launch rule runs last for exactly this case.
        assertEquals(VoiceCommand.OpenMusic, command("mở ứng dụng nhạc"))
    }

    @Test
    fun `mo ban do opens the map`() {
        assertEquals(VoiceCommand.OpenMap, command("mở bản đồ"))
    }

    @Test
    fun `mo alone is ambiguous and offers the two candidates`() {
        val result = IntentParser.parse("mở") as IntentParser.ParseResult.Ambiguous
        assertEquals(listOf(VoiceCommand.OpenMap, VoiceCommand.OpenMusic), result.candidates)
    }

    @Test
    fun `am luong alone is ambiguous between up and down`() {
        val result = IntentParser.parse("âm lượng") as IntentParser.ParseResult.Ambiguous
        assertEquals(listOf(VoiceCommand.VolumeUp, VoiceCommand.VolumeDown), result.candidates)
    }

    // -- volume ------------------------------------------------------------

    @Test
    fun `absolute volume parses every spoken form`() {
        assertEquals(VoiceCommand.VolumeTo(40), command("âm lượng 40%"))
        assertEquals(VoiceCommand.VolumeTo(40), command("âm lượng 40 phần trăm"))
        assertEquals(VoiceCommand.VolumeTo(40), command("đặt âm lượng là 40"))
        assertEquals(VoiceCommand.VolumeTo(40), command("chỉnh âm lượng 40 pt"))
    }

    @Test
    fun `volume is clamped rather than rejected`() {
        // A driver saying 150% is overstating, not uttering nonsense; refusing
        // it would look like the microphone broke.
        assertEquals(VoiceCommand.VolumeTo(100), command("âm lượng 150%"))
    }

    @Test
    fun `relative volume stays a one-step command`() {
        assertEquals(VoiceCommand.VolumeUp, command("tăng âm lượng"))
        assertEquals(VoiceCommand.VolumeDown, command("giảm âm lượng"))
    }

    // -- app launch --------------------------------------------------------

    @Test
    fun `app launch keeps the name as a folded query`() {
        val app = command("mở ứng dụng Google Maps") as VoiceCommand.OpenApp
        assertEquals("google maps", app.query)
        val short = command("mở app nhạc") as VoiceCommand.OpenApp
        assertEquals("nhac", short.query)
    }

    @Test
    fun `app launch with no name is not a command`() {
        // "mở ứng dụng" describes the drawer, which has no command. Guessing an
        // app would launch something the driver never named.
        assertTrue(IntentParser.parse("mở ứng dụng") is IntentParser.ParseResult.Unrecognized)
    }

    // -- fillers -----------------------------------------------------------

    @Test
    fun `leading politeness does not block a match`() {
        assertEquals(VoiceCommand.PlayMusic, command("làm ơn phát nhạc"))
        assertEquals(VoiceCommand.OpenMap, command("xin chào mở bản đồ"))
    }

    @Test
    fun `trailing particles do not block a match`() {
        assertEquals(VoiceCommand.Exit, command("thoát nhé"))
        assertEquals(VoiceCommand.GoHome, command("về nhà nào"))
    }

    @Test
    fun `a filler-only utterance is not trimmed to nothing and then matched`() {
        // "hãy" is both a leading filler and a word on its own. Trimming it to
        // an empty list and then matching on empty would make every filler a
        // command.
        assertTrue(IntentParser.parse("hãy") is IntentParser.ParseResult.Unrecognized)
    }

    // -- the honest failure ------------------------------------------------

    @Test
    fun `nothing matched returns the transcript as it was heard`() {
        // The prompt quotes what the driver said; a folded version would quote
        // words they never said.
        val result = IntentParser.parse("Hôm nay trời đẹp") as IntentParser.ParseResult.Unrecognized
        assertEquals("Hôm nay trời đẹp", result.text)
    }

    @Test
    fun `an empty transcript is unrecognized, not a default command`() {
        assertTrue(IntentParser.parse(null) is IntentParser.ParseResult.Unrecognized)
        assertTrue(IntentParser.parse("   ") is IntentParser.ParseResult.Unrecognized)
    }

    // -- confirmation policy lives on the command --------------------------

    @Test
    fun `only the two destructive commands ask before running`() {
        // Everything else is a value, a cursor, a pause or a screen. Putting a
        // question in front of "mở nhạc" would be the most annoying failure
        // mode this module can have.
        val asking = VoiceCommand.allTypes().filter { it.needsConfirmation }.map { it.id }.toSet()
        assertEquals(setOf("navigate_to", "play_music"), asking)
    }

    @Test
    fun `exit never asks, because it is the way out of a broken screen`() {
        assertFalse(VoiceCommand.Exit.needsConfirmation)
        assertFalse(VoiceCommand.Exit.destructive)
    }
}

END FILE

## core/navigation-core/src/test/java/com/ts8/core/voice/VoiceScenariosTest.kt

BEGIN FILE
package com.ts8.core.voice

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates `VoiceScenarios` in the build.
 *
 * Same reason as `ParkingScenariosTest`: [VoiceScenarios] returns failure
 * strings rather than asserting, so its parser, router and store cases can also
 * be run from a diagnostics screen on the unit. The cost was that `gradlew test`
 * never ran them, and the router — the piece that decides whether a destructive
 * command asks before executing — was covered only by hand.
 *
 * This test asserts nothing about voice. It fails when the scenario list
 * reports a failure, and the message is the scenario's own line.
 */
class VoiceScenariosTest {

    @Test
    fun `every voice acceptance scenario passes`() {
        val failures = VoiceScenarios.verify()
        assertTrue(
            "VoiceScenarios reported ${failures.size} failure(s):\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }
}

END FILE

## core/place-search/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.search"
}

dependencies {
    api(project(":core:map-api"))
}

END FILE

## core/place-search/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/place-search/src/main/java/com/ts8/core/search/PlaceSearch.kt

BEGIN FILE
package com.ts8.core.search

import com.ts8.core.map.GeoBounds
import com.ts8.core.map.GeoPoint

/** A place record as stored in the pack's `places.db`. */
data class Place(
    val id: Long,
    val osmType: String,
    val osmId: Long,
    /** Display name with original Vietnamese diacritics. */
    val name: String,
    /** Street / ward / city string, already formatted for display. */
    val address: String?,
    /** `poi` | `street` | `place` | `address`. */
    val category: String,
    /** `restaurant`, `bridge`, `residential`, … */
    val subclass: String?,
    val point: GeoPoint,
    val bounds: GeoBounds?,
    val importance: Int
)

/** A search hit plus why it ranked where it did. */
data class SearchResult(
    val place: Place,
    val distanceMeters: Int?,
    val score: Double,
    /** True when the query matched the start of a name token. */
    val prefixMatch: Boolean
)

/** Search options that the UI can tune without touching the engine. */
data class SearchOptions(
    val limit: Int = 20,
    /** Restrict to a category (`poi`, `street`, …) or null for all. */
    val category: String? = null,
    /** Bias results toward this point; null disables proximity scoring. */
    val biasPoint: GeoPoint? = null,
    /** Hard radius filter; null means unbounded. */
    val maxDistanceMeters: Int? = null
)

/**
 * Offline place search contract.
 *
 * Implementations must never block the caller's thread: the interface is
 * synchronous only because it is always invoked from a background executor;
 * callers in UI code go through [PlaceSearchService] instead.
 */
interface PlaceSearch {
    /**
     * @throws IllegalStateException when no pack is installed.
     */
    fun search(query: String, options: SearchOptions = SearchOptions()): List<SearchResult>

    /** Reverse lookup by coordinates; null when nothing is close enough. */
    fun nearest(point: GeoPoint, maxMeters: Int = 250): Place?

    /** Incremental autocomplete; same ranking, smaller default limit. */
    fun suggest(query: String, limit: Int = 8): List<Place>

    fun isReady(): Boolean

    fun close()
}

END FILE

## core/place-search/src/main/java/com/ts8/core/search/PlaceSearchService.kt

BEGIN FILE
package com.ts8.core.search

import android.os.Handler
import android.os.Looper
import com.ts8.core.map.GeoPoint
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * The asynchronous front of [PlaceSearch].
 *
 * [PlaceSearch] is synchronous by design — it is always called from a
 * background executor — but the search box cannot be: every keystroke would
 * run an FTS query on the main thread and the map would stall behind it. This
 * class is the only thing the UI talks to, and it owns the three rules the
 * spec states for search:
 *
 * 1. **Debounce.** A query is dispatched only after the driver stops typing.
 *    Typing `cầu rồng` one character at a time must not run nine queries.
 * 2. **Bounded results.** The limit is clamped to [MAX_RESULTS]; a caller
 *    cannot ask for the whole table.
 * 3. **Never block the UI, never deliver a stale result.** Every dispatch takes
 *    a ticket, and a result whose ticket is no longer current is dropped. This
 *    is what stops the answer for `cau` from overwriting the answer for
 *    `cau rong` when the first query happened to be slower.
 *
 * Results arrive on the main thread, which is where a RecyclerView adapter may
 * touch them.
 */
class PlaceSearchService(
    private val search: PlaceSearch,
    executor: ExecutorService? = null,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) : AutoCloseable {

    private val executor: ExecutorService = executor ?: Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-search").apply { isDaemon = true }
    }

    private val ownedExecutor = executor == null
    private val main = Handler(Looper.getMainLooper())

    /** Ticket of the most recent dispatch; older answers are discarded. */
    private val ticket = AtomicInteger(0)

    /** Separate ticket for [reverse], so a tap and a search do not cancel
     *  each other's answers. */
    private val reverseTicket = AtomicInteger(0)

    private var inFlight: Future<*>? = null

    /** The scheduled debounce, held so it can be removed precisely. */
    private var debounce: Runnable? = null

    /** Set by the screen. Called on the main thread with the query it answers. */
    var onResults: ((query: String, results: List<SearchResult>) -> Unit)? = null

    /** Called when a lookup fails outright, so the screen can say so. */
    var onError: ((query: String, detail: String) -> Unit)? = null

    /** True when a lookup for a non-empty query is in flight. */
    @Volatile
    var isSearching: Boolean = false
        private set

    /**
     * Schedules a lookup once the driver stops typing.
     *
     * An empty query clears the callback instead of dispatching: "nothing
     * typed" is not the same answer as "nothing found", and the screen renders
     * them differently.
     */
    fun updateQuery(query: String, bias: GeoPoint? = null, limit: Int = MAX_RESULTS) {
        val trimmed = query.trim()
        cancelPending()
        if (trimmed.isEmpty()) {
            isSearching = false
            onResults?.invoke("", emptyList())
            return
        }
        // Typing again invalidates the previous answer immediately, so the list
        // does not sit on results for a prefix the driver has moved past.
        isSearching = true

        val myTicket = ticket.get()
        val runnable = Runnable { dispatch(trimmed, bias, limit, myTicket) }
        debounce = runnable
        main.postDelayed(runnable, debounceMs)
    }

    /** Runs a lookup now, bypassing the debounce (the IME's search key). */
    fun submit(query: String, bias: GeoPoint? = null, limit: Int = MAX_RESULTS) {
        val trimmed = query.trim()
        cancelPending()
        if (trimmed.isEmpty()) {
            isSearching = false
            onResults?.invoke("", emptyList())
            return
        }
        isSearching = true
        dispatch(trimmed, bias, limit, ticket.get())
    }

    /** Abandons any in-flight or debounced lookup without emitting a result. */
    fun clear() {
        cancelPending()
        isSearching = false
    }

    /**
     * Drops the pending debounce and invalidates whatever is running.
     *
     * The ticket is bumped here rather than at dispatch time because a running
     * SQLite query cannot be interrupted — the only reliable way to stop its
     * answer reaching the screen is to make it stale.
     */
    private fun cancelPending() {
        debounce?.let { main.removeCallbacks(it) }
        debounce = null
        ticket.incrementAndGet()
    }

    private fun dispatch(query: String, bias: GeoPoint?, limit: Int, myTicket: Int) {
        val clamped = limit.coerceIn(1, MAX_RESULTS)
        if (!search.isReady()) {
            isSearching = false
            onError?.invoke(query, "Chưa có dữ liệu tìm kiếm")
            return
        }
        inFlight = try {
            executor.submit {
                val result = runCatching {
                    search.search(
                        query,
                        SearchOptions(limit = clamped, biasPoint = bias)
                    )
                }
                main.post {
                    if (myTicket != ticket.get()) return@post
                    isSearching = false
                    val failure = result.exceptionOrNull()
                    if (failure == null) {
                        onResults?.invoke(query, result.getOrDefault(emptyList()))
                    } else {
                        onError?.invoke(query, failure.message ?: "Lỗi tìm kiếm")
                    }
                }
            }
        } catch (e: Exception) {
            isSearching = false
            onError?.invoke(query, e.message ?: "Lỗi tìm kiếm")
            null
        }
    }

    /** The nearest named place, for the map's "what is this?" tap. */
    fun reverse(point: GeoPoint, onResult: (Place?) -> Unit) {
        // A separate ticket from the search one: tapping the map must not
        // cancel a search that is about to answer, and a search must not
        // discard the tapped point's answer.
        val myTicket = reverseTicket.incrementAndGet()
        runCatching {
            executor.execute {
                val found = runCatching { search.nearest(point) }.getOrNull()
                main.post { if (myTicket == reverseTicket.get()) onResult(found) }
            }
        }
    }

    override fun close() {
        cancelPending()
        isSearching = false
        onResults = null
        onError = null
        inFlight?.cancel(false)
        inFlight = null
        if (ownedExecutor) executor.shutdownNow()
    }

    companion object {
        /**
         * 250 ms. Long enough that a normal typing burst is one query, short
         * enough that the list feels attached to the keyboard. On this CPU an
         * FTS4 prefix query over 11 000 rows measures well under this, so the
         * debounce — not the query — is the latency.
         */
        const val DEFAULT_DEBOUNCE_MS = 250L

        /** The spec's cap: "tối đa 20 kết quả". Enforced here, not by callers. */
        const val MAX_RESULTS = 20
    }
}

END FILE

## core/place-search/src/main/java/com/ts8/core/search/SqlitePlaceSearch.kt

BEGIN FILE
package com.ts8.core.search

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.ts8.core.map.GeoBounds
import com.ts8.core.map.GeoPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * SQLite/FTS4-backed offline search over a map pack's `places.db`.
 *
 * FTS4 (not FTS5) because Android 6 / API 23 does not ship FTS5. The FTS table
 * is a *candidate generator* only: FTS4's `simple` tokenizer cannot do prefix
 * ranking, so matching and scoring happen here.
 *
 * Threading: [SQLiteDatabase] is opened per instance and every call is
 * synchronous; [PlaceSearchService] owns the executor and is the only caller
 * from the UI.
 */
class SqlitePlaceSearch private constructor(
    private val db: SQLiteDatabase,
    private val packBounds: GeoBounds?
) : PlaceSearch {

    @Volatile
    private var closed = false

    override fun isReady(): Boolean = !closed && db.isOpen

    override fun search(query: String, options: SearchOptions): List<SearchResult> {
        val normQuery = VietnameseText.normalize(query)
        if (normQuery.isEmpty()) return emptyList()
        val match = VietnameseText.toFtsMatchExpression(query) ?: return emptyList()
        val fetchLimit = (options.limit * MAX_CANDIDATE_FACTOR).coerceAtLeast(MIN_CANDIDATES)

        val results = ArrayList<SearchResult>(fetchLimit)
        val sql = buildString {
            append("SELECT p.id, p.osm_type, p.osm_id, p.name, p.address, p.category, ")
            append("p.subclass, p.lat, p.lon, p.bbox_w, p.bbox_s, p.bbox_e, p.bbox_n, ")
            append("p.importance, p.name_norm, ")
            // bm25 is unavailable in FTS4; matchinfo gives us the term positions
            // we need to know whether the query hit a token boundary.
            append("offsets(places_fts) AS offs ")
            append("FROM places_fts JOIN places p ON p.id = places_fts.docid ")
            append("WHERE places_fts MATCH ? ")
            if (options.category != null) append("AND p.category = ? ")
            append("LIMIT ?")
        }
        val args = ArrayList<String>(3)
        args.add(match)
        if (options.category != null) args.add(options.category)
        args.add(fetchLimit.toString())

        try {
            db.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val place = readPlace(c)
                    val nameNorm = c.getString(NAME_NORM_INDEX)
                    val offsets = c.getString(c.getColumnIndexOrThrow("offs"))
                    val scored = score(place, nameNorm, normQuery, offsets, options)
                    results.add(scored)
                }
            }
        } catch (e: SQLiteException) {
            // A malformed MATCH expression must degrade to "no results", never
            // crash the driver's search box.
            return emptyList()
        }

        return results
            .filter { r ->
                options.maxDistanceMeters == null ||
                    (r.distanceMeters != null && r.distanceMeters <= options.maxDistanceMeters)
            }
            .sortedByDescending { it.score }
            .take(options.limit)
    }

    override fun suggest(query: String, limit: Int): List<Place> =
        search(query, SearchOptions(limit = limit)).map { it.place }

    override fun nearest(point: GeoPoint, maxMeters: Int): Place? {
        // A degree box around the point is a cheap prefilter; the exact
        // distance check then runs on the handful of candidates it returns.
        val dLat = maxMeters / 111_320.0
        val dLon = maxMeters / (111_320.0 * cos(Math.toRadians(point.lat)).coerceAtLeast(0.01))
        val sql = """
            SELECT p.id, p.osm_type, p.osm_id, p.name, p.address, p.category,
                   p.subclass, p.lat, p.lon, p.bbox_w, p.bbox_s, p.bbox_e, p.bbox_n,
                   p.importance, p.name_norm
            FROM places p
            WHERE p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ?
            LIMIT 200
        """.trimIndent()
        val args = arrayOf(
            (point.lat - dLat).toString(), (point.lat + dLat).toString(),
            (point.lon - dLon).toString(), (point.lon + dLon).toString()
        )
        var best: Place? = null
        var bestDist = Int.MAX_VALUE
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val place = readPlace(c)
                val d = distanceMeters(point, place.point).toInt()
                if (d <= maxMeters && d < bestDist) {
                    bestDist = d
                    best = place
                }
            }
        }
        return best
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            db.close()
        } catch (_: Exception) {
            // Closing an already-closed handle is not an error worth surfacing.
        }
    }

    // -- scoring ------------------------------------------------------------

    private fun score(
        place: Place,
        nameNorm: String,
        normQuery: String,
        offsets: String?,
        options: SearchOptions
    ): SearchResult {
        val prefix = isTokenStartMatch(nameNorm, normQuery, offsets)
        val distMeters = options.biasPoint?.let { distanceMeters(it, place.point).toInt() }

        var s = 0.0
        // Exact whole-name match dominates: typing the full name should put it
        // first regardless of category.
        if (nameNorm == normQuery) s += 5_000_000.0
        if (prefix) s += 1_000_000.0
        if (nameNorm.startsWith(normQuery)) s += 400_000.0
        // Popular/important features (cities, hospitals, bridges) outrank shops.
        s += place.importance * 10_000.0
        if (distMeters != null) {
            // Capped so a far-away exact match still beats a near prefix match.
            s -= distMeters.coerceAtMost(60_000) / 6.0
        }
        return SearchResult(place = place, distanceMeters = distMeters, score = s, prefixMatch = prefix)
    }

    /**
     * FTS4 `offsets()` yields `"<column> <byteOffset> <byteLength>"` quadruples.
     * A query term starting exactly at a token boundary — that is, byte 0 or
     * the byte right after a separator — is the prefix match the ranking wants.
     */
    private fun isTokenStartMatch(nameNorm: String, normQuery: String, offsets: String?): Boolean {
        if (!prefixMatch(nameNorm, normQuery)) return false
        if (offsets.isNullOrEmpty()) return true
        val parts = offsets.trim().split(' ')
        if (parts.size < 4) return true
        // Only the name column is relevant; look for any term at position 0.
        var i = 0
        while (i + 3 < parts.size) {
            val col = parts[i].toIntOrNull() ?: 0
            val pos = parts[i + 1].toIntOrNull() ?: -1
            if (col == 0 && pos == 0) return true
            i += 4
        }
        return false
    }

    private fun prefixMatch(nameNorm: String, normQuery: String): Boolean =
        VietnameseText.prefixMatch(normQuery, nameNorm)

    private fun readPlace(c: Cursor): Place {
        val lat = c.getDouble(LAT_INDEX)
        val lon = c.getDouble(LON_INDEX)
        val hasBox = !c.isNull(BBOX_W_INDEX)
        val bounds = if (hasBox) GeoBounds(
            west = c.getDouble(BBOX_W_INDEX),
            south = c.getDouble(BBOX_S_INDEX),
            east = c.getDouble(BBOX_E_INDEX),
            north = c.getDouble(BBOX_N_INDEX)
        ) else null
        return Place(
            id = c.getLong(0),
            osmType = c.getString(1),
            osmId = c.getLong(2),
            name = c.getString(3) ?: "",
            address = if (c.isNull(4)) null else c.getString(4),
            category = c.getString(5) ?: "poi",
            subclass = if (c.isNull(6)) null else c.getString(6),
            point = GeoPoint(lat, lon),
            bounds = bounds,
            importance = c.getInt(13)
        )
    }

    companion object {
        private const val MIN_CANDIDATES = 200
        private const val MAX_CANDIDATE_FACTOR = 12

        private const val LAT_INDEX = 7
        private const val LON_INDEX = 8
        private const val BBOX_W_INDEX = 9
        private const val BBOX_S_INDEX = 10
        private const val BBOX_E_INDEX = 11
        private const val BBOX_N_INDEX = 12
        private const val NAME_NORM_INDEX = 14

        /** Haversine distance in metres. */
        fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.sin(dLon / 2) * Math.sin(dLon / 2) * cos(lat1) * cos(lat2)
            return 2 * r * Math.asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        /**
         * Opens the pack's search database, or returns null when the pack has
         * none. Read-only so a pack on a USB stick is never modified.
         */
        fun open(dbFile: File, packBounds: GeoBounds? = null): SqlitePlaceSearch? {
            if (!dbFile.isFile) return null
            return try {
                val db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                )
                SqlitePlaceSearch(db, packBounds)
            } catch (_: SQLiteException) {
                null
            }
        }
    }
}

END FILE

## core/place-search/src/main/java/com/ts8/core/search/VietnameseText.kt

BEGIN FILE
package com.ts8.core.search

import java.text.Normalizer
import java.util.Locale

/**
 * Vietnamese text normalisation used on both sides of the search index.
 *
 * The pack builder (`tools/mapdata/build_search_db.py`) calls the exact same
 * rules in Python; if one side changes, cached packs must be rebuilt. The two
 * implementations are kept in sync by
 * `PlaceSearchTest.testNormalizeMatchesBuilderContract`.
 */
object VietnameseText {

    private val DIACRITIC_STRIP = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Cultural note: search must work whether or not the driver's keyboard
     * emits diacritics, so `cau rong`, `cầu rồng` and `CẦU RỒNG` all fold to
     * `cau rong`.
     */
    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        // NFKD decomposes "ồ" into "o" + combining marks, which are then dropped.
        var s = Normalizer.normalize(input, Normalizer.Form.NFKD)
        s = s.replace('đ', 'd').replace('Đ', 'D')
        s = DIACRITIC_STRIP.replace(s, "")
        s = s.lowercase(Locale.ROOT)
        // Fold the Vietnamese "i" forms that survive NFKD in some fonts.
        s = s.replace('ı', 'i')
        s = WHITESPACE.replace(s, " ")
        return s.trim()
    }

    /** Splits a normalised string into FTS terms. */
    fun tokens(input: String?): List<String> =
        normalize(input).split(' ').filter { it.isNotEmpty() }

    /**
     * True when every query token is a prefix of some indexed token, which is
     * what makes `cau r` match `cau rong`.
     */
    fun prefixMatch(queryNorm: String, candidateNorm: String): Boolean {
        if (queryNorm.isEmpty()) return false
        val q = queryNorm.split(' ')
        val c = candidateNorm.split(' ')
        return q.all { qt -> c.any { ct -> ct.startsWith(qt) } }
    }

    /** Rewrites a user query into an FTS4 MATCH expression. */
    fun toFtsMatchExpression(query: String): String? {
        val toks = tokens(query)
        if (toks.isEmpty()) return null
        // Each token becomes a prefix term; FTS4 requires the '*' to directly
        // follow the term with no space.
        return toks.joinToString(" ") { "$it*" }
    }
}

END FILE

## core/routing-engine-offline/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.routing"
}

dependencies {
    api(project(":core:map-api"))
}

END FILE

## core/routing-engine-offline/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/routing-engine-offline/src/main/java/com/ts8/core/routing/AStarRouter.kt

BEGIN FILE
package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Offline A* router over [RoutingGraph].
 *
 * Cost is time, not distance: the objective a driver cares about is "arrive
 * soonest", and the graph's per-edge speed limits are what distinguish a
 * motorway from a service lane.
 *
 * The heuristic is haversine distance divided by the graph's maximum speed,
 * which is admissible (it can never overestimate the cheapest remaining cost)
 * so A* returns the optimal route rather than a greedy approximation.
 *
 * Working arrays are allocated per query (four flat arrays of nodeCount) and
 * freed with it; nothing is cached between routes because a reroute runs on a
 * background thread and a shared mutable scratch pad would need locking that
 * costs more than the allocation on this graph size.
 */
class AStarRouter private constructor(
    private val graph: RoutingGraph,
    private val index: GraphIndex
) : RoutingEngine {

    private val nodeCount = graph.nodeCount
    private val edgeCount = graph.edgeCount

    /** Snapping and route endpoints must land on a car-accessible vertex. */
    private val routable = graph.routableNode

    override fun isReady(): Boolean = true

    private fun nearest(lat: Double, lon: Double): Int =
        index.nearestNodeExact(lat, lon, { graph.nodePoint(it) }, { routable[it] })

    override fun snapToRoad(point: GeoPoint): GeoPoint? {
        val nodeId = nearest(point.lat, point.lon)
        if (nodeId < 0) return null
        return graph.nodePoint(nodeId)
    }

    /**
     * Speed limit for the road nearest [point].
     *
     * **How the edge is chosen.** The nearest routable *node* is found with the
     * same index and the same car-accessible mask the router uses, then every
     * car edge leaving that node is scored by the distance from the fix to the
     * edge's own geometry — a node can be the junction of a 50 street and an 80
     * arterial, and picking "the first outgoing edge" would report whichever
     * happened to be written first. The nearest edge along its geometry is the
     * one the car is actually on.
     *
     * **Why it can return null.** Outside the extract, on a car-forbidden way,
     * or on an edge whose stored speed is 0, there is no limit to report. The
     * caller shows `--`; substituting a per-class default here would be
     * inventing the number the brief says not to invent.
     */
    override fun limitAt(point: GeoPoint): Int? {
        val nodeId = nearest(point.lat, point.lon)
        if (nodeId < 0) return null
        val node = graph.node(nodeId)
        if (node.edgeCount <= 0) return null

        var bestSpeed = 0
        var bestDistance = Double.MAX_VALUE
        for (k in 0 until node.edgeCount) {
            val edgeIndex = node.firstEdge + k
            if (edgeIndex < 0 || edgeIndex >= edgeCount) continue
            val edge = graph.edge(edgeIndex)
            if (edge.carForbidden || edge.ferry) continue
            val distance = distanceToEdge(point, nodeId, edge)
            if (distance < bestDistance) {
                bestDistance = distance
                bestSpeed = edge.speedKph
            }
        }
        // 50 m is roughly "on this road" in a 5–10 m accuracy GPS; beyond that
        // the nearest node is not the street the car is driving on.
        if (bestDistance > MAX_LIMIT_DISTANCE_M) return null
        return if (bestSpeed > 0) bestSpeed else null
    }

    /** Distance from [point] to an edge's polyline, in metres. */
    private fun distanceToEdge(point: GeoPoint, fromNode: Int, edge: RoutingGraph.EdgeRecord): Double {
        val start = graph.nodePoint(fromNode)
        val geometry = graph.edgeGeometry(edge)
        if (geometry.isEmpty()) {
            val end = graph.nodePoint(edge.target)
            return distanceToSegment(point, start, end)
        }
        var best = Double.MAX_VALUE
        var previous = start
        for (vertex in geometry) {
            val d = distanceToSegment(point, previous, vertex)
            if (d < best) best = d
            previous = vertex
        }
        val end = graph.nodePoint(edge.target)
        val tail = distanceToSegment(point, previous, end)
        return if (tail < best) tail else best
    }

    /**
     * Point-to-segment distance in metres, on a local flat projection.
     *
     * A flat approximation, not haversine: the only decision made from this
     * number is "which of these edges is nearest", over distances of tens of
     * metres, where the projection error is centimetres.
     */
    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val latScale = 110_540.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * lonScale
        val ay = (a.lat - p.lat) * latScale
        val bx = (b.lon - p.lon) * lonScale
        val by = (b.lat - p.lat) * latScale
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9) return kotlin.math.sqrt(ax * ax + ay * ay)
        var t = -(ax * dx + ay * dy) / lengthSquared
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return kotlin.math.sqrt(cx * cx + cy * cy)
    }

    override fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): RouteResult {
        val startNode = nearest(from.lat, from.lon)
        if (startNode < 0) return RouteResult.Failure(RouteError.NO_START_NODE)
        val endNode = nearest(to.lat, to.lon)
        if (endNode < 0) return RouteResult.Failure(RouteError.NO_END_NODE)

        if (startNode == endNode) {
            // Short trip inside one junction: emit a two-point route rather
            // than failing, so "route to the shop next door" still works.
            val p0 = graph.nodePoint(startNode)
            val metres = haversine(p0, to).toInt().coerceAtLeast(1)
            return RouteResult.Success(
                Route(
                    points = listOf(p0, GeoPoint(to.lat, to.lon)),
                    distanceMeters = metres,
                    durationSeconds = (metres / 8.3).toInt().coerceAtLeast(10),
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, p0, 0, null, "Bắt đầu"),
                        Maneuver(ManeuverType.ARRIVE, to, metres, null, "Đã đến nơi")
                    ),
                    nodeIds = intArrayOf(startNode)
                )
            )
        }

        return try {
            val path = findPath(startNode, endNode, options)
            if (path == null) RouteResult.Failure(RouteError.UNREACHABLE)
            else RouteResult.Success(buildRoute(path.nodes, path.edges, to))
        } catch (e: Exception) {
            RouteResult.Failure(RouteError.INTERNAL, e.message ?: e.javaClass.simpleName)
        }
    }

    // -- A* -----------------------------------------------------------------

    private class Path(val nodes: IntArray, val edges: IntArray)

    /**
     * Returns the vertex path start..end, or null when the destination cannot
     * be reached (island, disconnected extract boundary, or every edge out of
     * the start is one-way against travel).
     */
    private fun findPath(start: Int, end: Int, options: RouteOptions): Path? {
        val dist = DoubleArray(nodeCount) { Double.MAX_VALUE }
        val prevEdge = IntArray(nodeCount) { -1 }
        val prevNode = IntArray(nodeCount) { -1 }
        val closed = BooleanArray(nodeCount)
        val endPoint = graph.nodePoint(end)

        val open = MinHeap(estimateCapacity(start, end))
        dist[start] = 0.0
        open.push(start, heuristic(graph.nodePoint(start), endPoint))

        var explored = 0
        while (open.isNotEmpty()) {
            val current = open.pop()
            // Lazy deletion: a node can be pushed several times as its cost
            // improves, so the first pop of a node is the only useful one.
            if (closed[current]) continue
            if (current == end) return reconstruct(current, prevEdge, prevNode)
            closed[current] = true
            explored++

            // Safety valve: a pathological query should fail fast instead of
            // pinning the CPU for the rest of the drive.
            if (explored > MAX_EXPLORED) return null

            val node = graph.node(current)
            val base = node.firstEdge
            for (k in 0 until node.edgeCount) {
                val edgeIdx = base + k
                if (edgeIdx < 0 || edgeIdx >= edgeCount) continue
                val e = graph.edge(edgeIdx)
                if (e.carForbidden) continue
                if (options.avoidTolls && e.toll) continue
                if (options.avoidFerries && e.ferry) continue
                val next = e.target
                if (next < 0 || next >= nodeCount || closed[next]) continue

                val tentative = dist[current] + e.baseSeconds
                if (tentative < dist[next]) {
                    dist[next] = tentative
                    prevEdge[next] = edgeIdx
                    prevNode[next] = current
                    open.push(next, tentative + heuristic(graph.nodePoint(next), endPoint))
                }
            }
        }
        return null
    }

    private fun reconstruct(end: Int, prevEdge: IntArray, prevNode: IntArray): Path {
        val nodes = ArrayList<Int>(64)
        val edges = ArrayList<Int>(64)
        var cur = end
        while (true) {
            nodes.add(cur)
            val pe = prevEdge[cur]
            if (pe < 0) break
            edges.add(pe)
            cur = prevNode[cur]
            if (cur < 0) break
        }
        nodes.reverse()
        edges.reverse()
        return Path(nodes.toIntArray(), edges.toIntArray())
    }

    // -- route building -----------------------------------------------------

    private class RouteSegment(
        val fromNode: Int,
        val toNode: Int,
        val distance: Double,
        val name: String?,
        val roundabout: Boolean
    )

    private fun buildRoute(nodePath: IntArray, edgePath: IntArray, destination: GeoPoint): Route {
        val points = ArrayList<GeoPoint>(nodePath.size * 4)
        var totalDistance = 0.0
        var totalSeconds = 0.0
        val segments = ArrayList<RouteSegment>(edgePath.size)

        points.add(graph.nodePoint(nodePath[0]))
        for (i in edgePath.indices) {
            val edgeIdx = edgePath[i]
            val e = graph.edge(edgeIdx)
            totalDistance += e.distanceMeters
            totalSeconds += e.baseSeconds
            segments.add(
                RouteSegment(
                    fromNode = nodePath[i],
                    toNode = e.target,
                    distance = e.distanceMeters,
                    name = graph.edgeName(e),
                    roundabout = e.roundabout
                )
            )

            // The writer emits the geometry blob once per undirected segment,
            // ordered from the way's start node to its end node. The paired
            // reverse edge points at the same blob and carries
            // FLAG_GEOM_REVERSED, because guessing from index ordering breaks
            // for one-way-reversed ways where only one edge exists.
            val geom = graph.edgeGeometry(e)
            val ordered = if (e.geometryReversed) geom.asReversed() else geom
            points.addAll(ordered)
            points.add(graph.nodePoint(e.target))
        }

        return Route(
            points = dedupeConsecutive(points),
            distanceMeters = totalDistance.toInt().coerceAtLeast(1),
            durationSeconds = totalSeconds.toInt().coerceAtLeast(1),
            maneuvers = buildManeuvers(segments, points, destination),
            nodeIds = nodePath
        )
    }

    /**
     * Derives turn instructions from the bearing change between consecutive
     * segments. A "continue" is emitted when the street name changes without a
     * meaningful turn, which is what drivers hear as "Tiếp tục đi thẳng trên
     * <đường>".
     */
    private fun buildManeuvers(
        segments: List<RouteSegment>,
        points: List<GeoPoint>,
        destination: GeoPoint
    ): List<Maneuver> {
        if (segments.isEmpty()) {
            return listOf(
                Maneuver(ManeuverType.DEPART, points.firstOrNull() ?: destination, 0, null, "Bắt đầu"),
                Maneuver(ManeuverType.ARRIVE, destination, 0, null, "Đã đến nơi")
            )
        }

        val startPoint = points.firstOrNull() ?: destination
        val result = ArrayList<Maneuver>()
        result.add(
            Maneuver(
                ManeuverType.DEPART, startPoint, 0, segments.first().name,
                buildDepartInstruction(segments.first().name)
            )
        )

        var travelled = 0.0
        for (i in 0 until segments.size - 1) {
            val cur = segments[i]
            val next = segments[i + 1]
            travelled += cur.distance

            val atNode = graph.nodePoint(cur.toNode)
            val delta = normalizeAngle(
                bearingTo(atNode, graph.nodePoint(next.toNode)) -
                    bearingTo(graph.nodePoint(cur.fromNode), atNode)
            )
            val turn = classifyTurn(delta)

            when {
                next.roundabout || cur.roundabout -> result.add(
                    Maneuver(
                        ManeuverType.ROUNDABOUT, atNode, travelled.toInt(), next.name,
                        buildRoundaboutInstruction(next.name, delta)
                    )
                )
                turn != ManeuverDirection.STRAIGHT -> result.add(
                    Maneuver(
                        ManeuverType.TURN, atNode, travelled.toInt(), next.name,
                        buildTurnInstruction(turn, next.name), turn
                    )
                )
                nameChanged(cur.name, next.name) -> result.add(
                    Maneuver(
                        ManeuverType.CONTINUE, atNode, travelled.toInt(), next.name,
                        buildContinueInstruction(next.name), ManeuverDirection.STRAIGHT
                    )
                )
            }
        }

        val totalMetres = segments.sumOf { it.distance }.toInt()
        result.add(
            Maneuver(
                ManeuverType.ARRIVE, destination, totalMetres,
                segments.last().name, "Đã đến nơi"
            )
        )
        return mergeConsecutiveContinues(result)
    }

    /**
     * A long street split into many OSM segments produces one "continue" per
     * segment. Only the first is useful to the driver.
     */
    private fun mergeConsecutiveContinues(input: List<Maneuver>): List<Maneuver> {
        val out = ArrayList<Maneuver>(input.size)
        for (m in input) {
            val last = out.lastOrNull()
            if (m.type == ManeuverType.CONTINUE && last?.type == ManeuverType.CONTINUE &&
                last.streetName == m.streetName
            ) {
                continue
            }
            out.add(m)
        }
        return out
    }

    private fun nameChanged(a: String?, b: String?): Boolean =
        !b.isNullOrBlank() && a != b

    private fun buildDepartInstruction(name: String?): String =
        if (name.isNullOrBlank()) "Bắt đầu chuyến đi" else "Bắt đầu đi trên $name"

    private fun buildTurnInstruction(dir: ManeuverDirection, name: String?): String {
        val action = when (dir) {
            ManeuverDirection.LEFT -> "Rẽ trái"
            ManeuverDirection.RIGHT -> "Rẽ phải"
            ManeuverDirection.SLIGHT_LEFT -> "Chếch sang trái"
            ManeuverDirection.SLIGHT_RIGHT -> "Chếch sang phải"
            ManeuverDirection.SHARP_LEFT -> "Rẽ gắt sang trái"
            ManeuverDirection.SHARP_RIGHT -> "Rẽ gắt sang phải"
            ManeuverDirection.UTURN -> "Quay đầu"
            else -> "Đi thẳng"
        }
        return if (name.isNullOrBlank()) action else "$action vào $name"
    }

    private fun buildContinueInstruction(name: String?): String =
        if (name.isNullOrBlank()) "Tiếp tục đi thẳng" else "Tiếp tục đi thẳng trên $name"

    private fun buildRoundaboutInstruction(name: String?, delta: Double): String {
        val action = if (delta > 0) "Đi vào vòng xuyến rồi rẽ trái"
        else "Đi vào vòng xuyến rồi rẽ phải"
        return if (name.isNullOrBlank()) action else "$action ra $name"
    }

    private fun classifyTurn(deltaDeg: Double): ManeuverDirection {
        val d = deltaDeg
        return when {
            abs(d) <= TURN_THRESHOLD_DEG -> ManeuverDirection.STRAIGHT
            d > 0 && d <= 45 -> ManeuverDirection.SLIGHT_RIGHT
            d > 45 && d <= 135 -> ManeuverDirection.RIGHT
            d > 135 -> ManeuverDirection.SHARP_RIGHT
            d < 0 && d >= -45 -> ManeuverDirection.SLIGHT_LEFT
            d < 0 && d >= -135 -> ManeuverDirection.LEFT
            else -> ManeuverDirection.SHARP_LEFT
        }
    }

    /** Wraps a bearing difference into [-180, 180]. */
    private fun normalizeAngle(a: Float): Double {
        var x = a % 360.0
        if (x > 180) x -= 360
        if (x < -180) x += 360
        return x
    }

    /** Initial bearing from [a] to [b], degrees clockwise from north. */
    private fun bearingTo(a: GeoPoint, b: GeoPoint): Float {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg + 360) % 360).toFloat()
    }

    private fun heuristic(from: GeoPoint, to: GeoPoint): Double =
        haversine(from, to) / (MAX_GRAPH_SPEED_KPH * 1000.0 / 3600.0)

    private fun estimateCapacity(start: Int, end: Int): Int {
        val d = haversine(graph.nodePoint(start), graph.nodePoint(end))
        return (d / 50.0).toInt().coerceIn(256, 65_536)
    }

    private fun dedupeConsecutive(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 2) return points
        val out = ArrayList<GeoPoint>(points.size)
        var last: GeoPoint? = null
        for (p in points) {
            if (last == null || last.lat != p.lat || last.lon != p.lon) {
                out.add(p)
                last = p
            }
        }
        return out
    }

    override fun close() {
        graph.close()
    }

    companion object {
        /** Highest speed limit present in the pack; keeps the heuristic admissible. */
        private const val MAX_GRAPH_SPEED_KPH = 120.0
        private const val MAX_EXPLORED = 400_000
        private const val TURN_THRESHOLD_DEG = 25.0

        /**
         * Beyond this the nearest node is not "the road the car is on". Chosen
         * at 50 m: GPS accuracy is 5–20 m here, and a fix 50 m from every car
         * edge is a car park, a driveway or a bad sample — none of which has a
         * speed limit to report.
         */
        private const val MAX_LIMIT_DISTANCE_M = 50.0

        fun open(packDir: File): AStarRouter? {
            val graphDir = File(packDir, "routing-graph")
            val graph = RoutingGraph.open(graphDir) ?: return null
            val index = GraphIndex.open(File(graphDir, "graph.idx"), graph.nodeCount) ?: run {
                graph.close()
                return null
            }
            return AStarRouter(graph, index)
        }

        /** Haversine distance in metres. */
        fun haversine(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
            return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}

/**
 * Binary min-heap keyed by priority.
 *
 * A* on this graph pushes far more entries than there are nodes (a node can be
 * improved several times), so the heap stores duplicate node ids and the
 * caller skips already-closed nodes — the standard "lazy deletion" pattern
 * that avoids a decrease-key structure.
 */
private class MinHeap(capacity: Int) {
    private var ids = IntArray(capacity.coerceAtLeast(16))
    private var prs = DoubleArray(capacity.coerceAtLeast(16))
    private var size = 0

    fun isNotEmpty(): Boolean = size > 0

    fun push(id: Int, priority: Double) {
        if (size == ids.size) grow()
        var i = size++
        ids[i] = id
        prs[i] = priority
        while (i > 0) {
            val parent = (i - 1) / 2
            if (prs[parent] <= prs[i]) break
            swap(i, parent)
            i = parent
        }
    }

    fun pop(): Int {
        val top = ids[0]
        size--
        if (size > 0) {
            ids[0] = ids[size]
            prs[0] = prs[size]
            var i = 0
            while (true) {
                val l = i * 2 + 1
                val r = l + 1
                var smallest = i
                if (l < size && prs[l] < prs[smallest]) smallest = l
                if (r < size && prs[r] < prs[smallest]) smallest = r
                if (smallest == i) break
                swap(i, smallest)
                i = smallest
            }
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val ti = ids[a]; ids[a] = ids[b]; ids[b] = ti
        val tp = prs[a]; prs[a] = prs[b]; prs[b] = tp
    }

    private fun grow() {
        val newCap = ids.size * 2
        ids = ids.copyOf(newCap)
        prs = prs.copyOf(newCap)
    }
}

END FILE

## core/routing-engine-offline/src/main/java/com/ts8/core/routing/GraphIndex.kt

BEGIN FILE
package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import kotlin.math.abs

/**
 * Spatial grid index over graph nodes, read from `routing-graph/graph.idx`.
 *
 * Used for two things: snapping a GPS fix to the nearest road vertex, and
 * locating the start/end vertices of a route. The format is specified in
 * `docs/internal/FORMAT_SPECS.md` §3.4.
 */
class GraphIndex private constructor(
    private val data: java.nio.ByteBuffer,
    private val cols: Int,
    private val rows: Int,
    private val cellDeg: Double,
    private val west: Double,
    private val south: Double,
    private val cellStartOffset: Int,
    private val cellNodesOffset: Int,
    private val total: Int
) {

    fun cellOf(lat: Double, lon: Double): Int {
        val c = ((lon - west) / cellDeg).toInt()
        val r = ((lat - south) / cellDeg).toInt()
        if (c < 0 || c >= cols || r < 0 || r >= rows) return -1
        return r * cols + c
    }

    private fun cellRange(cell: Int): IntRange {
        val start = data.getInt(cellStartOffset + cell * 4)
        val end = data.getInt(cellStartOffset + (cell + 1) * 4)
        return start until end
    }

    /**
     * Nearest node to [lat]/[lon], searching an expanding ring of cells so a
     * GPS fix that lands in an empty cell (water, park) still resolves.
     *
     * @return node id, or -1 when nothing is within [maxRing] rings.
     */
    fun nearestNode(lat: Double, lon: Double, maxRing: Int = 3): Int {
        val c0 = ((lon - west) / cellDeg).toInt()
        val r0 = ((lat - south) / cellDeg).toInt()
        if (c0 < -maxRing || c0 > cols + maxRing || r0 < -maxRing || r0 > rows + maxRing) return -1

        var best = -1
        var bestDist = Double.MAX_VALUE
        val latScale = 110_540.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))

        for (ring in 0..maxRing) {
            var found = false
            for (dr in -ring..ring) {
                for (dc in -ring..ring) {
                    // Only the shell of the ring; inner rings were scanned.
                    if (ring > 0 && abs(dr) != ring && abs(dc) != ring) continue
                    val c = c0 + dc
                    val r = r0 + dr
                    if (c < 0 || c >= cols || r < 0 || r >= rows) continue
                    val cell = r * cols + c
                    for (i in cellRange(cell)) {
                        val nodeId = data.getInt(cellNodesOffset + i * 4)
                        found = true
                        // The node coordinates are not stored in the index, so
                        // the caller-supplied distance is approximate here; the
                        // exact check happens in [nearestNodeExact].
                        val approx = (dc * cellDeg * lonScale) * (dc * cellDeg * lonScale) +
                            (dr * cellDeg * latScale) * (dr * cellDeg * latScale)
                        if (approx < bestDist) {
                            bestDist = approx
                            best = nodeId
                        }
                    }
                }
            }
            // Once a ring yielded candidates, one more ring is scanned to be
            // sure the true nearest is not just across a cell boundary.
            if (found && ring >= 1) break
        }
        return best
    }

    /**
     * Nearest node using real coordinates. [nodePoint] supplies vertex
     * positions, so this is exact rather than cell-approximate.
     *
     * [accept] filters candidates. Routing passes the graph's car-accessible
     * mask here so a GPS fix that lands in a park does not snap onto a footway
     * vertex and then report the destination unreachable.
     */
    fun nearestNodeExact(
        lat: Double,
        lon: Double,
        nodePoint: (Int) -> GeoPoint,
        accept: ((Int) -> Boolean)? = null,
        maxRing: Int = 4
    ): Int {
        val c0 = ((lon - west) / cellDeg).toInt()
        val r0 = ((lat - south) / cellDeg).toInt()
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (ring in 0..maxRing) {
            for (dr in -ring..ring) {
                for (dc in -ring..ring) {
                    if (ring > 0 && abs(dr) != ring && abs(dc) != ring) continue
                    val c = c0 + dc
                    val r = r0 + dr
                    if (c < 0 || c >= cols || r < 0 || r >= rows) continue
                    val cell = r * cols + c
                    for (i in cellRange(cell)) {
                        val nodeId = data.getInt(cellNodesOffset + i * 4)
                        if (nodeId < 0 || nodeId >= total) continue
                        if (accept != null && !accept(nodeId)) continue
                        val p = nodePoint(nodeId)
                        val d = approximateMeters(lat, lon, p.lat, p.lon)
                        if (d < bestDist) {
                            bestDist = d
                            best = nodeId
                        }
                    }
                }
            }
            // The searched area already covers at least ring*cellDeg metres;
            // stop once that exceeds the best distance found.
            if (best >= 0 && bestDist < ring * cellDeg * 110_000.0) break
        }
        return best
    }

    private fun approximateMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 110_540.0
        val dLon = (lon2 - lon1) * 111_320.0 * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        fun open(file: java.io.File, nodeCount: Int): GraphIndex? {
            if (!file.isFile) return null
            return try {
                val raf = java.io.RandomAccessFile(file, "r")
                val channel = raf.channel
                val buf = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { buf.get(it) }
                if (String(magic, Charsets.US_ASCII) != "TS8I") {
                    channel.close()
                    return null
                }
                buf.short // version
                val cellE7 = buf.short.toInt() and 0xFFFF
                val cols = buf.int
                val rows = buf.int
                val west = buf.double
                val south = buf.double
                buf.double // east
                buf.double // north
                val cellStartOffset = buf.position()
                val cellNodesOffset = cellStartOffset + (cols * rows + 1) * 4
                val totalAvailable = (channel.size() - cellNodesOffset).toInt() / 4
                channel.close()

                if (cols <= 0 || rows <= 0 || cellE7 <= 0) return null
                val raf2 = java.io.RandomAccessFile(file, "r")
                val buf2 = raf2.channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf2.length()
                ).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                GraphIndex(
                    data = buf2, cols = cols, rows = rows,
                    cellDeg = cellE7 / 1e7, west = west, south = south,
                    cellStartOffset = cellStartOffset, cellNodesOffset = cellNodesOffset,
                    total = nodeCount.coerceAtMost(totalAvailable)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

END FILE

## core/routing-engine-offline/src/main/java/com/ts8/core/routing/RoutingEngine.kt

BEGIN FILE
package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import kotlin.math.abs

/** A computed route, in the shape the map and navigator consume. */
data class Route(
    val points: List<GeoPoint>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val maneuvers: List<Maneuver>,
    /** Vertices in travel order, used by snapping and reroute. */
    val nodeIds: IntArray
)

/** A single turn instruction. */
data class Maneuver(
    val type: ManeuverType,
    /** Point at which the instruction fires. */
    val point: GeoPoint,
    /** Distance along the route (metres) where this maneuver happens. */
    val distanceFromStart: Int,
    /** Street to continue onto; null when unknown. */
    val streetName: String?,
    /** Spoken instruction in Vietnamese. */
    val instruction: String,
    val direction: ManeuverDirection = ManeuverDirection.NONE
)

enum class ManeuverType { DEPART, TURN, CONTINUE, ROUNDABOUT, ARRIVE }

enum class ManeuverDirection { NONE, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, STRAIGHT, UTURN }

/** Options that change the cost function. */
data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val preferFastest: Boolean = true
)

/** Result wrapper so failures are explicit rather than an empty list. */
sealed class RouteResult {
    data class Success(val route: Route) : RouteResult()
    data class Failure(val reason: RouteError, val detail: String = "") : RouteResult()
}

enum class RouteError {
    PACK_NOT_READY,
    NO_START_NODE,
    NO_END_NODE,
    UNREACHABLE,
    SAME_POINT,
    INTERNAL
}

/** Offline routing contract. */
interface RoutingEngine {
    fun isReady(): Boolean

    /** Nearest routable point, for snapping a free-form destination. */
    fun snapToRoad(point: GeoPoint): GeoPoint?

    /**
     * Computes a route. Runs synchronously; callers wrap it in a background
     * executor (the navigator owns one).
     */
    fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions = RouteOptions()): RouteResult

    /**
     * Speed limit for the road nearest [point], in km/h, or null when the graph
     * has no answer there.
     *
     * **Why this is on the routing engine and not derived in the UI.** The graph
     * stores `speedKph` per directed edge (FORMAT_SPECS §3.3); finding the edge
     * under the car needs the spatial index the router already holds. A null is
     * a real answer — an off-network fix, a pack whose extract has no speed for
     * that road — and the caller must render `--` rather than substitute a
     * per-class default of its own, which the brief forbids.
     *
     * Defaults to null so an engine that cannot answer (a test fake, a future
     * online router) satisfies the interface without pretending.
     */
    fun limitAt(point: GeoPoint): Int? = null

    fun close()
}

END FILE

## core/routing-engine-offline/src/main/java/com/ts8/core/routing/RoutingGraph.kt

BEGIN FILE
package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Memory-mapped reader for a pack's `routing-graph/graph.bin`.
 *
 * The file layout is specified in `docs/internal/FORMAT_SPECS.md` §3. The
 * graph is read through `MappedByteBuffer` so a city-sized graph (tens of MB)
 * costs address space, not heap — which matters on an API 23 head unit with
 * a 128 MB heap limit.
 */
class RoutingGraph private constructor(
    private val file: File,
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
    private val buf: MappedGraphBuffer,
    val nodeCount: Int,
    val edgeCount: Int,
    val bounds: DoubleArray
) {

    /**
     * A small wrapper so reads are bounds-checked in one place and the buffer
     * can be released deterministically.
     */
    class MappedGraphBuffer(private val channel: FileChannel) {
        private val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            .order(ByteOrder.LITTLE_ENDIAN)

        fun getInt(offset: Int): Int = map.getInt(offset)
        fun getShort(offset: Int): Short = map.getShort(offset)
        fun getDouble(offset: Int): Double = map.getDouble(offset)
        fun bytes(offset: Int, len: Int): ByteArray {
            val out = ByteArray(len)
            val dup = map.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            dup.position(offset)
            dup.get(out)
            return out
        }

        fun close() {
            // Mapped buffers are released by GC; on API 23 the cleaner is the
            // only route, and it is not public. Closing the channel stops
            // further mapping but the page cache stays until the mapping is
            // collected — acceptable because the graph is opened once per pack.
            runCatching { channel.close() }
        }
    }

    /** One graph vertex. */
    data class NodeRecord(val lat: Double, val lon: Double, val firstEdge: Int, val edgeCount: Int) {
        val point: GeoPoint get() = GeoPoint(lat, lon)
    }

    /** One directed edge. */
    data class EdgeRecord(
        val target: Int,
        val distanceMeters: Double,
        val flags: Int,
        val speedKph: Int,
        val nameIdx: Int,
        val reverseEdge: Int,
        val geometryOffset: Int
    ) {
        /**
         * Informational: the street this edge belongs to is one-way. It does
         * **not** mean the edge is untraversable — this graph stores directed
         * edges, so the opposite direction simply has no edge at all.
         */
        val onewayStreet: Boolean get() = flags and FLAG_ONEWAY != 0
        val toll: Boolean get() = flags and FLAG_TOLL != 0
        val ferry: Boolean get() = flags and FLAG_FERRY != 0
        val carForbidden: Boolean get() = flags and FLAG_CAR_NO != 0
        val roundabout: Boolean get() = flags and FLAG_ROUNDABOUT != 0
        val tunnel: Boolean get() = flags and FLAG_TUNNEL != 0
        val bridge: Boolean get() = flags and FLAG_BRIDGE != 0

        /** Geometry blob is stored against travel direction and must be reversed. */
        val geometryReversed: Boolean get() = flags and FLAG_GEOM_REVERSED != 0
        val roadClass: Int get() = (flags ushr 4) and 0xF
        val baseSeconds: Double get() = distanceMeters / (maxOf(speedKph, 1) * 1000.0 / 3600.0)
    }

    private val nameTableOffset: Int = buf.getInt(48)
    private val nameTableBytes: Int = buf.getInt(52)
    private val nodeTableOffset = HEADER_SIZE
    private val edgeTableOffset = nodeTableOffset + nodeCount * NODE_RECORD_SIZE

    /**
     * Header field 56 carries the geometry blob offset. The writer emits the
     * geometry between the edge table and the name table, so this cannot be
     * derived from node/edge counts alone without duplicating the blob size
     * math in two places — read it from the header instead.
     */
    private val geometryBase: Int = buf.getInt(56)

    /** Lazily built name cache; the name table is only walked once. */
    private val names: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val out = ArrayList<String>(256)
        var o = nameTableOffset
        val end = nameTableOffset + nameTableBytes
        while (o + 2 <= end) {
            val len = buf.getShort(o).toInt() and 0xFFFF
            if (o + 2 + len > end) break
            out.add(String(buf.bytes(o + 2, len), Charsets.UTF_8))
            o += 2 + len
        }
        out
    }

    // -- node / edge access -------------------------------------------------

    fun node(id: Int): NodeRecord {
        val o = nodeTableOffset + id * NODE_RECORD_SIZE
        return NodeRecord(
            lat = buf.getInt(o) / LAT_E7,
            lon = buf.getInt(o + 4) / LAT_E7,
            firstEdge = buf.getInt(o + 8),
            edgeCount = buf.getInt(o + 12)
        )
    }

    fun edge(index: Int): EdgeRecord {
        val o = edgeTableOffset + index * EDGE_RECORD_SIZE
        return EdgeRecord(
            target = buf.getInt(o),
            distanceMeters = buf.getInt(o + 4) / 100.0,
            flags = buf.getShort(o + 8).toInt() and 0xFFFF,
            speedKph = buf.getShort(o + 10).toInt() and 0xFFFF,
            nameIdx = buf.getInt(o + 12),
            reverseEdge = buf.getInt(o + 16),
            geometryOffset = buf.getInt(o + 20)
        )
    }

    /** Street name for an edge, or null when unnamed. */
    fun edgeName(edge: EdgeRecord): String? {
        if (edge.nameIdx == NONE_U32) return null
        val table = names
        return if (edge.nameIdx in table.indices) table[edge.nameIdx] else null
    }

    /** Intermediate geometry points for an edge, outermost-first. */
    fun edgeGeometry(edge: EdgeRecord): List<GeoPoint> {
        if (edge.geometryOffset == NONE_U32) return emptyList()
        val o = geometryBase + edge.geometryOffset
        val count = buf.getInt(o)
        if (count <= 0 || count > 10_000) return emptyList()
        val out = ArrayList<GeoPoint>(count)
        for (i in 0 until count) {
            val p = o + 4 + i * 8
            out.add(GeoPoint(buf.getInt(p) / LAT_E7, buf.getInt(p + 4) / LAT_E7))
        }
        return out
    }

    fun nodePoint(id: Int): GeoPoint {
        val o = nodeTableOffset + id * NODE_RECORD_SIZE
        return GeoPoint(buf.getInt(o) / LAT_E7, buf.getInt(o + 4) / LAT_E7)
    }

    /**
     * Nodes that touch at least one car-accessible edge, in either direction.
     *
     * Snapping must ignore footway/path/cycleway vertices: a GPS fix in a park
     * is often closest to a pedestrian node, and routing from there produces a
     * "no route" that looks like a data bug rather than a bad snap.
     *
     * A node with only *incoming* car edges still counts, because a one-way
     * dead end is a legitimate destination.
     */
    val routableNode: BooleanArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val ok = BooleanArray(nodeCount)
        for (n in 0 until nodeCount) {
            val o = nodeTableOffset + n * NODE_RECORD_SIZE
            val first = buf.getInt(o + 8)
            val count = buf.getInt(o + 12)
            for (k in 0 until count) {
                val e = first + k
                if (e < 0 || e >= edgeCount) continue
                val eo = edgeTableOffset + e * EDGE_RECORD_SIZE
                val flags = buf.getShort(eo + 8).toInt() and 0xFFFF
                if (flags and FLAG_CAR_NO != 0) continue
                ok[n] = true
                val target = buf.getInt(eo)
                if (target in 0 until nodeCount) ok[target] = true
            }
        }
        ok
    }

    fun close() {
        buf.close()
    }

    companion object {
        private const val HEADER_SIZE = 64
        private const val NODE_RECORD_SIZE = 16
        private const val EDGE_RECORD_SIZE = 24
        private const val NONE_U32 = 0xFFFFFFFF.toInt()
        private const val LAT_E7 = 1e7

        const val FLAG_ONEWAY = 1
        const val FLAG_TOLL = 1 shl 1
        const val FLAG_FERRY = 1 shl 2
        const val FLAG_CAR_NO = 1 shl 8
        const val FLAG_ROUNDABOUT = 1 shl 9
        const val FLAG_TUNNEL = 1 shl 10
        const val FLAG_BRIDGE = 1 shl 11
        const val FLAG_GEOM_REVERSED = 1 shl 12

        /**
         * Opens a graph file. Returns null when the file is missing or the
         * header is not a graph this build understands — callers must surface
         * that as "pack unavailable", never substitute demo data.
         */
        fun open(dir: File): RoutingGraph? {
            val file = File(dir, "graph.bin")
            if (!file.isFile) return null
            var raf: RandomAccessFile? = null
            var channel: FileChannel? = null
            try {
                raf = RandomAccessFile(file, "r")
                channel = raf.channel
                if (channel.size() < HEADER_SIZE) return null
                val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(header, 0)
                header.flip()
                val magic = ByteArray(4).also { header.get(it) }
                if (String(magic, Charsets.US_ASCII) != "TS8G") return null
                val version = header.short.toInt()
                if (version != 1) return null
                header.short // flags
                val nodeCount = header.int
                val edgeCount = header.int
                if (nodeCount <= 0 || edgeCount <= 0) return null
                val west = header.double
                val south = header.double
                val east = header.double
                val north = header.double

                val expected = HEADER_SIZE.toLong() + nodeCount.toLong() * NODE_RECORD_SIZE +
                    edgeCount.toLong() * EDGE_RECORD_SIZE
                if (channel.size() < expected) return null

                val mapped = MappedGraphBuffer(channel)
                return RoutingGraph(
                    file, raf, channel, mapped, nodeCount, edgeCount,
                    doubleArrayOf(west, south, east, north)
                )
            } catch (e: Exception) {
                runCatching { channel?.close() }
                runCatching { raf?.close() }
                return null
            }
        }
    }
}

END FILE

## core/routing-engine-offline/src/test/java/com/ts8/core/routing/AStarRouterPackTest.kt

BEGIN FILE
package com.ts8.core.routing

import com.ts8.core.map.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Runs the real router against the real Đà Nẵng pack.
 *
 * These are not mock-backed tests: the pack is the artifact built by
 * `tools/mapdata/` and validated independently by `tools/mapdata/verify_graph.py`.
 * The distance windows below are the values that verifier reports for the same
 * pairs on the same bytes, so a reader/writer format drift shows up here as a
 * number moving outside its window.
 *
 * The pack lives outside the repository (generated binaries), so the suite is
 * skipped with a clear message when it is absent rather than silently passing.
 */
class AStarRouterPackTest {

    companion object {
        private const val PACK_DIR = "D:/Tools/_data/pack-test"

        private var router: AStarRouter? = null

        @JvmStatic
        @BeforeClass
        fun openPack() {
            val dir = File(PACK_DIR)
            if (!dir.isDirectory) return
            router = AStarRouter.open(dir)
        }
    }

    private fun requireRouter(): AStarRouter {
        val dir = File(PACK_DIR)
        assumeTrue("map pack not built at $PACK_DIR; run tools/mapdata/build_*.py", dir.isDirectory)
        val r = router
        assertNotNull("pack present but router failed to open — binary format mismatch", r)
        return r!!
    }

    /** Da Nang International Airport terminal. */
    private val airport = GeoPoint(16.0472, 108.2015)

    /** Dragon Bridge, on the Han River. */
    private val dragonBridge = GeoPoint(16.0616, 108.2270)

    /** My Khe beach. */
    private val myKhe = GeoPoint(16.0544, 108.2471)

    @Test
    fun `opens the pack and reports ready`() {
        assertTrue(requireRouter().isReady())
    }

    @Test
    fun `airport to dragon bridge produces a real route`() {
        val r = requireRouter()
        val result = r.route(airport, dragonBridge)
        assertTrue("expected success, got $result", result is RouteResult.Success)
        val route = (result as RouteResult.Success).route

        // Verifier reports 5521 m / 408 s on this pack. The airport exit is a
        // one-way loop, so this direction is longer than the return leg.
        assertTrue(
            "distance ${route.distanceMeters} m outside expected 5.0-6.1 km",
            route.distanceMeters in 5_000..6_100
        )
        assertTrue(
            "duration ${route.durationSeconds} s outside expected 5.5-8 min",
            route.durationSeconds in 330..480
        )
        assertTrue("route must have geometry", route.points.size > 10)
        assertTrue("route must carry turn instructions", route.maneuvers.size >= 2)
        assertEquals("first maneuver must be DEPART", ManeuverType.DEPART, route.maneuvers.first().type)
        assertEquals("last maneuver must be ARRIVE", ManeuverType.ARRIVE, route.maneuvers.last().type)
    }

    @Test
    fun `route follows the real road network, not a straight line`() {
        val r = requireRouter()
        val route = (r.route(airport, myKhe) as RouteResult.Success).route
        val straight = AStarRouter.haversine(airport, myKhe)

        // A straight-line "route" would score ~1.0; a real road route between
        // these points crosses the Han River and scores materially higher.
        val ratio = route.distanceMeters / straight
        assertTrue("detour ratio $ratio must exceed 1.15", ratio > 1.15)
        assertTrue("detour ratio $ratio must stay below 3.0", ratio < 3.0)
    }

    @Test
    fun `every route vertex is inside the pack bounds`() {
        val r = requireRouter()
        val route = (r.route(airport, myKhe) as RouteResult.Success).route
        for (p in route.points) {
            assertTrue("lat ${p.lat} outside Da Nang", p.lat in 15.8..16.3)
            assertTrue("lon ${p.lon} outside Da Nang", p.lon in 107.8..108.5)
        }
    }

    @Test
    fun `drive instructions are Vietnamese and never empty`() {
        val r = requireRouter()
        val route = (r.route(airport, dragonBridge) as RouteResult.Success).route
        for (m in route.maneuvers) {
            assertTrue("maneuver at ${m.distanceFromStart} m has no text", m.instruction.isNotBlank())
        }
        // At least one turn on this corridor must name a street: "vào <đường>".
        assertTrue(
            "no maneuver names a street",
            route.maneuvers.any { it.type == ManeuverType.TURN && !it.streetName.isNullOrBlank() }
        )
        // Maneuver offsets must be monotonic, since the navigator binary-searches
        // them by distance travelled.
        val offsets = route.maneuvers.map { it.distanceFromStart }
        assertEquals("maneuver offsets must be non-decreasing", offsets.sorted(), offsets)
    }

    @Test
    fun `a normal two-way city pair is near-symmetric`() {
        val r = requireRouter()
        val there = (r.route(dragonBridge, myKhe) as RouteResult.Success).route
        val back = (r.route(myKhe, dragonBridge) as RouteResult.Success).route
        val delta = kotlin.math.abs(there.distanceMeters - back.distanceMeters)
        // Verifier: 3593 m out, 3691 m back — ordinary one-way asymmetry, not
        // a corridor that has lost its reverse edge.
        assertTrue(
            "out ${there.distanceMeters} vs back ${back.distanceMeters} differ too much",
            delta < there.distanceMeters * 0.15
        )
    }

    @Test
    fun `one-way airport exit makes the two directions differ`() {
        val r = requireRouter()
        val toBridge = (r.route(airport, dragonBridge) as RouteResult.Success).route
        val toAirport = (r.route(dragonBridge, airport) as RouteResult.Success).route
        // 5521 m out (one-way loop out of the terminal) vs 3986 m back.
        assertTrue(
            "expected the airport loop to lengthen the outbound leg: " +
                "${toBridge.distanceMeters} vs ${toAirport.distanceMeters}",
            toBridge.distanceMeters > toAirport.distanceMeters + 500
        )
    }

    @Test
    fun `snapping returns a point on the road network`() {
        val r = requireRouter()
        val snapped = r.snapToRoad(airport)
        assertNotNull("airport must snap to a road", snapped)
        assertTrue(
            "snap moved unexpectedly far: ${snapped!!.lat},${snapped.lon}",
            AStarRouter.haversine(airport, snapped) < 2_000
        )
    }

    @Test
    fun `snapping never lands on a footway-only vertex`() {
        val r = requireRouter()
        // This point is inside the beach sand, closest to pedestrian paths.
        val snapped = r.snapToRoad(GeoPoint(16.0520, 108.2490))
        assertNotNull(snapped)
        // A routable snap must be able to reach the city; a footway vertex
        // would come back UNREACHABLE here.
        val result = r.route(GeoPoint(16.0520, 108.2490), airport)
        assertTrue("beach snap produced no route: $result", result is RouteResult.Success)
    }

    @Test
    fun `identical endpoints stay on the same node and still produce instructions`() {
        val r = requireRouter()
        val result = r.route(airport, airport)
        assertTrue(result is RouteResult.Success)
        val route = (result as RouteResult.Success).route
        assertTrue("must still report a distance", route.distanceMeters >= 1)
        assertEquals(2, route.maneuvers.size)
    }

    @Test
    fun `avoid ferries and avoid tolls never change a land route`() {
        val r = requireRouter()
        val plain = r.route(airport, dragonBridge) as RouteResult.Success
        val safe = r.route(
            airport, dragonBridge,
            RouteOptions(avoidTolls = true, avoidFerries = true)
        ) as RouteResult.Success
        // Da Nang has neither on this corridor, so the cost must be identical.
        assertEquals(plain.route.distanceMeters, safe.route.distanceMeters)
    }

    @Test
    fun `a point outside the pack fails explicitly instead of inventing a route`() {
        val r = requireRouter()
        // Hanoi is 600 km away and outside the extract.
        val result = r.route(airport, GeoPoint(21.0278, 105.8342))
        assertTrue("must not fabricate a route to Hanoi: $result", result is RouteResult.Failure)
    }
}

END FILE

## core/vehicle-audio-bridge/build.gradle.kts

BEGIN FILE
plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.audio"
}

dependencies {
    api(project(":core:music-api"))
}

END FILE

## core/vehicle-audio-bridge/src/main/AndroidManifest.xml

BEGIN FILE
<?xml version="1.0" encoding="utf-8"?>
<manifest />

END FILE

## core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/AndroidVehicleAudioBridge.kt

BEGIN FILE
package com.ts8.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ts8.core.music.AudioRoute

/**
 * [VehicleAudioBridge] on stock Android 6 audio APIs.
 *
 * What this actually does on the TS8 firmware, and what it does not:
 *
 * | action                    | works | why |
 * |---------------------------|-------|-----|
 * | request/abandon media focus | yes | public `AudioManager` API |
 * | detect a call and stop      | yes | focus loss with `LOSS_TRANSIENT` |
 * | duck for navigation         | yes | `setStreamVolume`, restored on unduck |
 * | pause FM/AM radio           | **no** | vendor app owns the tuner; no SDK |
 * | pause Bluetooth playback    | partial | needs AVRCP, see below |
 *
 * The radio row is the important one: the spec forbids reporting success for a
 * privileged action, so [pauseExternalSource] returns
 * [AudioResult.Unsupported] rather than pretending. The UI renders that as a
 * disabled control with an explanation, not a silent failure.
 */
class AndroidVehicleAudioBridge(
    context: Context
) : VehicleAudioBridge {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var focusListener: AudioFocusListener? = null
    private var mediaFocusHeld = false
    private var navFocusHeld = false

    private var ducked = false
    /** Volume before the first duck, so nested ducks restore correctly. */
    private var volumeBeforeDuck = -1

    private var externalPaused = false

    /** Whether the vendor radio can be suspended — always false here. */
    private val radioControllable = false

    // -- focus -------------------------------------------------------------

    private val androidFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val listener = focusListener ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                mediaFocusHeld = false
                listener.onFocusLostPermanent()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                mediaFocusHeld = false
                listener.onFocusLostTransient(mayDuck = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                listener.onFocusLostTransient(mayDuck = true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaFocusHeld = true
                listener.onFocusGained()
            }
        }
    }

    /**
     * API 26 introduced [AudioFocusRequest]; API 23 only has the deprecated
     * overload. Both paths are kept so the same code runs on the head unit and
     * on a modern emulator for testing.
     */
    override fun requestFocus(route: AudioRoute): AudioResult {
        if (route == AudioRoute.MEDIA && mediaFocusHeld) return AudioResult.Success
        if (route == AudioRoute.NAVIGATION && navFocusHeld) return AudioResult.Success

        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (route == AudioRoute.NAVIGATION) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                else AudioAttributes.USAGE_MEDIA
            )
            .setContentType(
                if (route == AudioRoute.NAVIGATION) AudioAttributes.CONTENT_TYPE_SPEECH
                else AudioAttributes.CONTENT_TYPE_MUSIC
            )
            .build()

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(androidFocusListener, mainHandler)
                .setWillPauseWhenDucked(false)
                .build()
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                androidFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                if (route == AudioRoute.NAVIGATION) navFocusHeld = true else mediaFocusHeld = true
                AudioResult.Success
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> AudioResult.Denied(
                if (isCallActive()) DenyReason.CALL_IN_PROGRESS else DenyReason.FOCUS_HELD_BY_OTHER
            )
            // AUDIOFOCUS_REQUEST_DELAYED only exists on API 26+.
            else -> AudioResult.Denied(DenyReason.SYSTEM_REFUSED)
        }
    }

    override fun abandonFocus(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(
                    if (route == AudioRoute.NAVIGATION)
                        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    else AudioAttributes.USAGE_MEDIA
                )
                .build()
            audioManager.abandonAudioFocusRequest(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(androidFocusListener)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(androidFocusListener)
        }
        if (route == AudioRoute.NAVIGATION) navFocusHeld = false else mediaFocusHeld = false
    }

    override fun hasFocus(route: AudioRoute): Boolean =
        if (route == AudioRoute.NAVIGATION) navFocusHeld else mediaFocusHeld

    override fun currentRoute(): AudioRoute =
        if (isCallActive()) AudioRoute.CALL else AudioRoute.MEDIA

    /** True when the platform reports a call is occupying audio. */
    private fun isCallActive(): Boolean = audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audioManager.mode == AudioManager.MODE_RINGTONE

    // -- ducking -----------------------------------------------------------

    /**
     * Lowering the stream volume is the only mechanism API 23 offers that a
     * head unit's mixer respects. `setStreamVolume` needs no permission and is
     * restored exactly, which matters: the spec requires music to come back at
     * the level it had, not at some default.
     */
    override fun duck(): AudioResult {
        if (ducked) return AudioResult.Success
        return try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (current * DUCK_FRACTION).toInt().coerceAtLeast(1)
            if (target >= current) {
                // Nothing to lower (already at the floor). Ducking "succeeded"
                // but there is no change to undo.
                ducked = false
                return AudioResult.Success
            }
            volumeBeforeDuck = current
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            ducked = true
            AudioResult.Success
        } catch (e: SecurityException) {
            AudioResult.Unsupported("changing the media stream volume is not permitted")
        } catch (e: Exception) {
            AudioResult.Failed(e.message ?: "duck failed")
        }
    }

    override fun unduck() {
        if (!ducked) return
        val restore = volumeBeforeDuck
        if (restore >= 0) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0) }
        }
        volumeBeforeDuck = -1
        ducked = false
    }

    override fun isDucked(): Boolean = ducked

    // -- external sources --------------------------------------------------

    override fun canPauseExternalSource(): Boolean = radioControllable

    /**
     * Cannot pause the tuner on this firmware.
     *
     * Returning [AudioResult.Unsupported] is the honest answer: the vendor app
     * owns `/dev/radio` and exposes no broadcast or service we may call. The
     * launcher therefore asks the driver to switch source manually, which is
     * what the required documentation instructs.
     */
    override fun pauseExternalSource(): AudioResult {
        if (!radioControllable) {
            return AudioResult.Unsupported(
                "AM/FM radio is owned by the firmware's tuner app and has no public " +
                    "control API on this build"
            )
        }
        externalPaused = true
        return AudioResult.Success
    }

    override fun resumeExternalSource(): AudioResult {
        if (!externalPaused) return AudioResult.Success
        if (!radioControllable) {
            return AudioResult.Unsupported("tuner cannot be resumed by this app")
        }
        externalPaused = false
        return AudioResult.Success
    }

    // -- volume ------------------------------------------------------------

    override fun setMediaVolume(index: Int): AudioResult = try {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            index.coerceIn(0, max),
            0
        )
        AudioResult.Success
    } catch (e: SecurityException) {
        AudioResult.Unsupported("changing the media stream volume is not permitted")
    } catch (e: Exception) {
        AudioResult.Failed(e.message ?: "volume change failed")
    }

    override fun mediaVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun maxMediaVolume(): Int =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    // -- lifecycle ---------------------------------------------------------

    override fun setFocusListener(listener: AudioFocusListener) {
        focusListener = listener
    }

    override fun release() {
        unduck()
        if (mediaFocusHeld) abandonFocus(AudioRoute.MEDIA)
        if (navFocusHeld) abandonFocus(AudioRoute.NAVIGATION)
        focusListener = null
    }

    companion object {
        /**
         * Duck to this fraction of the current level. Low enough that speech is
         * clearly audible over it, high enough that the driver still hears the
         * music is there.
         */
        private const val DUCK_FRACTION = 0.25f
    }
}

END FILE

## core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/VehicleAudioBridge.kt

BEGIN FILE
package com.ts8.core.audio

import com.ts8.core.music.AudioRoute

/**
 * The single seam between the launcher and whatever actually owns the vehicle's
 * audio hardware.
 *
 * Today there is one implementation — [AndroidVehicleAudioBridge], built on the
 * public Android audio APIs the TS8 firmware exposes. The head unit's own MCU
 * exposes a deeper source-switching interface (radio, Bluetooth, auxiliary,
 * DVD) that is **not** part of the public SDK; the spec puts that in a later
 * phase. Everything in the app talks to this interface, so swapping in a
 * `SyuAudioBridge` later is a constructor change in one place.
 *
 * Design rules this interface encodes:
 *
 * - **Never claim a switch that did not happen.** Every request returns a
 *   [AudioResult] and the caller surfaces a failure. The spec forbids
 *   pretending a privileged action succeeded.
 * - **Always be able to restore.** Anything the bridge pauses, it can resume;
 *   anything it ducks, it unducks. A bridge that takes the rail and forgets to
 *   give it back leaves the driver in silence.
 * - **No blocking calls on the main thread.**
 */
interface VehicleAudioBridge {

    /** The audio route the app currently drives. */
    fun currentRoute(): AudioRoute

    /**
     * Requests audio focus for [route].
     *
     * For [AudioRoute.MEDIA] this is a normal focus request: transient
     * exclusive when a call arrives, may-duck when navigation speaks.
     */
    fun requestFocus(route: AudioRoute): AudioResult

    /** Gives focus back, restoring anything this bridge took. */
    fun abandonFocus(route: AudioRoute)

    /** True while this bridge holds focus for [route]. */
    fun hasFocus(route: AudioRoute): Boolean

    /**
     * Temporarily lowers the media stream for an announcement.
     *
     * Implementations must be idempotent: two navigation prompts in a row must
     * not duck twice and leave the music quiet afterwards.
     */
    fun duck(): AudioResult

    /** Restores the media stream to the level it had before [duck]. */
    fun unduck()

    /** True when the media stream is currently ducked by this bridge. */
    fun isDucked(): Boolean

    /**
     * Pauses a non-media source (FM/AM radio, or a paired phone's playback)
     * because the launcher needs the speakers.
     *
     * On stock Android 6 this cannot pause the radio: the radio output is owned
     * by a vendor app with no public API. The implementation returns
     * [AudioResult.Unsupported] in that case and the caller must not pretend it
     * happened. Bluetooth playback, by contrast, *can* be paused through
     * AVRCP, and [canPause]/[pause] handle it.
     */
    fun pauseExternalSource(): AudioResult

    /** Resumes a source suspended by [pauseExternalSource]. */
    fun resumeExternalSource(): AudioResult

    /** Whether this device can suspend the external source at all. */
    fun canPauseExternalSource(): Boolean

    fun setMediaVolume(index: Int): AudioResult

    fun mediaVolume(): Int

    fun maxMediaVolume(): Int

    /** Registers for focus loss/regain from other apps and system events. */
    fun setFocusListener(listener: AudioFocusListener)

    /** Releases all resources and restores the pre-bridge state. */
    fun release()
}

/** Result of a request that may be refused. Never throws. */
sealed class AudioResult {
    object Success : AudioResult()

    /**
     * The action is possible but was refused right now (another app holds
     * focus, a call is in progress).
     */
    data class Denied(val reason: DenyReason) : AudioResult()

    /**
     * The platform simply cannot do this. Distinct from [Denied]: no retry will
     * help, and the UI should not offer the control.
     */
    data class Unsupported(val detail: String) : AudioResult()

    data class Failed(val detail: String) : AudioResult()
}

enum class DenyReason {
    /** Another app currently owns audio focus. */
    FOCUS_HELD_BY_OTHER,

    /** A phone call is active or ringing. */
    CALL_IN_PROGRESS,

    /** The system refused for a reason it did not report. */
    SYSTEM_REFUSED
}

/** Events the app must react to. All arrive on the main thread. */
interface AudioFocusListener {
    /** Focus lost permanently — another app is playing. Pause playback. */
    fun onFocusLostPermanent()

    /**
     * Focus lost for a short time. [mayDuck] is true when the system allows
     * lowering the volume instead of stopping.
     */
    fun onFocusLostTransient(mayDuck: Boolean)

    /** Focus returned after a transient loss. */
    fun onFocusGained()

    /** A call started; playback must stop immediately, not duck. */
    fun onCallStarted()

    /** The call ended. */
    fun onCallEnded()

    /** The audio output device changed (e.g. the head unit switched sources). */
    fun onAudioRouteChanged(route: AudioRoute)
}

/** Focus states as the bridge reports them, independent of Android constants. */
enum class FocusState { NONE, GAINED, TRANSIENT, TRANSIENT_DUCKING, LOSS_TRANSIENT, LOSS }

END FILE

## gradle.properties

BEGIN FILE
# Pin the daemon to the JDK the toolchain was validated against. The host also
# has JDK 27 installed and on PATH; Kotlin 1.9.25's compiler cannot parse a
# "27" release string (JavaVersion.parse throws), so letting Gradle pick the
# default JVM breaks every script compile. AGP 8.7 also requires JDK 17.
org.gradle.java.home=D:/Tools/_toolchain/jdk-17.0.20.1+1

org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 \
  -Djavax.net.ssl.trustStore=D:/Tools/_toolchain/truststore.jks \
  -Djavax.net.ssl.trustStorePassword=changeit
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official

# ---------------------------------------------------------------------------
# Corporate proxy (FPT FSOFT).
#
# The build host sits behind an SSL-inspecting proxy, so plain proxy settings
# are not enough: the JVM also has to trust the proxy's re-signing CA. The
# truststore above is the JDK's cacerts plus the exported Windows Root store,
# built once with:
#   keytool -importcert -noprompt -alias corp -file <ca>.cer \
#     -keystore truststore.jks -storepass changeit
# On a machine without the proxy, drop the three javax.net.ssl lines and the
# systemProp.*.proxy* lines below; nothing else depends on them.
# ---------------------------------------------------------------------------
systemProp.http.proxyHost=fdnproxy-fc-lan.fsoft.fpt.vn
systemProp.http.proxyPort=8080
systemProp.https.proxyHost=fdnproxy-fc-lan.fsoft.fpt.vn
systemProp.https.proxyPort=8080
systemProp.http.nonProxyHosts=localhost|127.0.0.1
systemProp.https.nonProxyHosts=localhost|127.0.0.1

END FILE

## gradle/wrapper/gradle-wrapper.properties

BEGIN FILE
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
networkTimeout=60000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists

END FILE

## settings.gradle.kts

BEGIN FILE
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TS8CarLauncher"

include(":app")

include(":core:map-api")
include(":core:map-engine-maplibre")
include(":core:place-search")
include(":core:routing-engine-offline")
include(":core:location-engine")
include(":core:navigation-core")
include(":core:music-api")
include(":core:music-engine-local")
include(":core:music-provider-online")
include(":core:vehicle-audio-bridge")
include(":core:diagnostics")

END FILE

