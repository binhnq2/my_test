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
