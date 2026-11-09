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
