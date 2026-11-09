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
