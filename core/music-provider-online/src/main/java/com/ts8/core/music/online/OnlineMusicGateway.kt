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
