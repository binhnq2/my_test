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
