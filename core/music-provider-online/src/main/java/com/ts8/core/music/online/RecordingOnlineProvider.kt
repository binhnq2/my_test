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
