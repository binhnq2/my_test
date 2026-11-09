package com.ts8.launcher.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ts8.core.music.PlaybackError
import com.ts8.core.music.StreamResolution
import com.ts8.core.music.StreamResolver
import com.ts8.core.music.Track
import com.ts8.core.music.online.BrowseCategory
import com.ts8.core.music.online.NavidromeProvider
import com.ts8.core.music.online.OnlineMusicGateway
import com.ts8.core.music.online.OnlineTrack
import com.ts8.core.music.online.PlayableSource
import com.ts8.core.music.online.PodcastRssProvider
import com.ts8.core.music.online.ProviderFailureCode
import com.ts8.core.music.online.ProviderKind
import com.ts8.core.music.online.ProviderResult
import com.ts8.core.music.online.RadioBrowserProvider
import com.ts8.core.music.online.SearchAggregate
import com.ts8.launcher.di.AppGraph
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's front end to online music (§12–13).
 *
 * **What this class is for.** [OnlineMusicGateway] is deliberately synchronous
 * and knows nothing about Android: every provider call blocks, which is correct
 * for a background pool and fatal for a search box. This class is the single
 * place in the app that (a) builds the gateway from settings, (b) moves its
 * calls off the main thread and posts the answer back, and (c) implements
 * [StreamResolver] so the local player can open a stream without the local
 * engine having to depend on any online provider.
 *
 * **Why the gateway is rebuilt on every settings change.** A driver who has
 * just typed a server address expects the next search to use it, not the next
 * launch. Providers are cheap objects (a base URL and a circuit breaker), so
 * rebuilding costs nothing; caching one across a settings change is how a
 * screen ends up asking a host the driver has already replaced. The generation
 * counter below drops an in-flight answer that was produced against the old
 * configuration, so a slow DNS lookup cannot render the previous server's rows
 * under the new one's name.
 *
 * **Why `BLOCKED_EXTERNAL_CONFIG` is a first-class result here.** §13 is
 * explicit: an unconfigured provider must be reported as unconfigured, never as
 * "no results". [status] carries the gateway's own [OnlineMusicGateway.blockedConfig]
 * sentences verbatim, so the screen has nothing to invent.
 *
 * **What is never here.** No caching of stream URLs, no downloading of audio,
 * no extraction from a service without a public playback API. A resolved URL is
 * handed to `MediaPlayer` once and dropped; the gateway re-resolves on the next
 * play, which is what keeps an expired token from producing a stream that dies
 * three seconds in.
 *
 * **When online is off.** [AppSettings.onlineEnabled] `false` makes [search]
 * return an empty aggregate and [status] an empty list, and the player's
 * resolver refuses — local music is completely unaffected, which is §13's
 * "provider lỗi không ảnh hưởng local music" taken one step further.
 */
class OnlineMusicRuntime(
    context: Context,
    private val settings: AppSettings,
    private val graph: AppGraph
) {

    /** One search's worth of results plus what each source said about it. */
    data class Snapshot(
        /** The query or category this answers, for the stale-answer check. */
        val ticket: Int,
        /** Merged results, in the brief's provider order. */
        val tracks: List<OnlineTrack>,
        /** One line per provider: how many it returned, or why it could not. */
        val lines: List<ProviderLine>,
        /** Set only when nothing worked; Vietnamese and shown as-is. */
        val failure: String?
    ) {
        val isTotalFailure: Boolean get() = tracks.isEmpty() && failure != null
    }

    /** A provider's contribution, as the search screen draws it. */
    data class ProviderLine(
        val providerId: String,
        val displayName: String,
        val kind: ProviderKind,
        /** `null` when the provider answered; the Vietnamese reason when it did not. */
        val failure: String?,
        /** True when the provider needs settings the driver has not entered. */
        val blockedConfig: Boolean,
        val resultCount: Int
    )

    /**
     * One row of the music screen's `Trực tuyến` source list.
     *
     * Built from configuration alone — no request, no health probe. That is
     * the point: §7 requires every provider, configured or not, to be visible
     * with a `Thiết lập` CTA. A list assembled from a network call would show
     * nothing at all on a unit with no configuration, which is exactly the
     * silent disappearance the brief forbids.
     */
    data class ProviderInfo(
        val providerId: String,
        val displayName: String,
        val kind: ProviderKind,
        /** True when the provider has the settings it needs. */
        val configured: Boolean,
        /** The setting this provider is missing, in Vietnamese; null when it has them. */
        val missing: String?
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = graph.io()

    /** Bumped on every request so a late answer cannot paint over a new query. */
    private val ticket = AtomicInteger(0)

    /**
     * The gateway, rebuilt when the configuration it was built from changes.
     *
     * Keyed by [configKey] rather than invalidated by a callback: settings are
     * a `SharedPreferences` file other code writes to directly, so the reliable
     * moment to notice a change is the moment the gateway is asked for.
     */
    @Volatile
    private var gateway: OnlineMusicGateway? = null

    @Volatile
    private var gatewayKey: String = ""

    private val lock = Any()

    // -- gateway -----------------------------------------------------------

    /** The current gateway, or null when online music is switched off. */
    fun gateway(): OnlineMusicGateway? {
        if (!settings.onlineEnabled) return null
        val key = configKey()
        gateway?.let { if (key == gatewayKey) return it }
        synchronized(lock) {
            gateway?.let { if (key == gatewayKey) return it }
            val built = build()
            gateway = built
            gatewayKey = key
            return built
        }
    }

    /**
     * Drops the built gateway so the next call names a freshly built one.
     *
     * Called by the settings screen after a save. It exists because the
     * password is not in [configKey] — see there — and a provider captures its
     * credentials at construction, so a corrected password would otherwise
     * keep failing until the process restarted. Invalidating is cheap: the
     * providers are a base URL, a circuit breaker and a feed address.
     */
    fun invalidate() {
        synchronized(lock) {
            gateway = null
            gatewayKey = ""
        }
    }

    /**
     * Everything the providers were built from, as one string.
     *
     * The password is deliberately **not** part of the key. It is not needed to
     * decide whether the provider set changed (the username and the host are),
     * and a key built from it would put a credential in a field that exists to
     * be compared. A changed password therefore does not change this key — see
     * [invalidate], which the settings screen calls after it saves, because a
     * provider holds its password from construction and cannot see a later
     * edit on its own.
     */
    private fun configKey(): String = buildString {
        append(settings.onlineServerUrl)
        append('|')
        append(settings.onlineUser)
        append('|')
        append(settings.onlineServerKind)
        append('|')
        append(settings.onlineFeedUrl)
    }

    /**
     * Builds the provider list in the order §12 fixes.
     *
     * Radio first: it is the only provider that needs no configuration at all,
     * so it is the one guaranteed to return something on a unit whose driver
     * has not set anything up. Podcast and Navidrome follow because both need
     * the driver to have entered an address — and when they have not, they
     * answer `BLOCKED_EXTERNAL_CONFIG` and the screen says so.
     *
     * The Subsonic provider is **always** constructed, even with an empty base
     * URL: that is what makes `blockedConfig()` able to name it. Omitting it
     * when unconfigured would produce the exact failure §13 forbids — an empty
     * result that reads as "no such song".
     */
    private fun build(): OnlineMusicGateway {
        val providers = ArrayList<com.ts8.core.music.online.OnlineMusicProvider>(3)
        providers.add(RadioBrowserProvider())

        val feed = settings.onlineFeedUrl.takeIf { it.isNotBlank() }
        providers.add(PodcastRssProvider(feedUrl = feed))

        providers.add(
            NavidromeProvider(
                baseUrl = settings.onlineServerUrl.takeIf { it.isNotBlank() },
                username = settings.onlineUser.takeIf { it.isNotBlank() },
                password = settings.onlineToken.takeIf { it.isNotBlank() },
                kind = serverKind()
            )
        )
        return OnlineMusicGateway(providers, clock = { SystemClock.elapsedRealtime() })
    }
    /**
     * The stored server family, or Navidrome when the stored value is unknown.
     *
     * Falling back rather than throwing: a preferences file written by a
     * build that had a provider this one does not is a routine downgrade, and
     * the Subsonic API is identical enough that the wrong label costs less than
     * an online screen that refuses to open.
     */
    private fun serverKind(): ProviderKind {
        val stored = settings.onlineServerKind
        return runCatching { ProviderKind.valueOf(stored) }
            .getOrDefault(ProviderKind.NAVIDROME)
            .let { if (it == ProviderKind.NAVIDROME || it == ProviderKind.JELLYFIN) it else ProviderKind.NAVIDROME }
    }

    // -- search ------------------------------------------------------------

    /**
     * Searches every configured provider, off the main thread.
     *
     * The callback always runs on the main thread and is always called exactly
     * once — including when online music is off, where it receives an empty
     * snapshot rather than silence. A screen waiting for a callback that never
     * comes shows a spinner forever, which is indistinguishable from a hang.
     */
    fun search(
        query: String,
        limit: Int = OnlineMusicGateway.MAX_AGGREGATE_LIMIT,
        onDone: (Snapshot) -> Unit
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onDone(Snapshot(ticket.incrementAndGet(), emptyList(), emptyList(), null))
            return
        }
        dispatch(onDone) { gw -> gw.search(trimmed, limit) }
    }

    /** Browses a shelf; same contract as [search]. */
    fun browse(
        category: BrowseCategory,
        limit: Int = OnlineMusicGateway.MAX_AGGREGATE_LIMIT,
        onDone: (Snapshot) -> Unit
    ) {
        dispatch(onDone) { gw -> gw.browse(category, limit) }
    }

    /**
     * Runs one aggregate call and folds it into a [Snapshot].
     *
     * The ticket check is why the counter is shared: the driver types `c`,
     * `ca`, `cau` and three searches are in flight over a phone hotspot.
     * Without it the slowest answer wins, which for a search box means the list
     * flickers back to a query the driver already deleted.
     */
    private fun dispatch(
        onDone: (Snapshot) -> Unit,
        call: (OnlineMusicGateway) -> SearchAggregate
    ) {
        val id = ticket.incrementAndGet()
        val gw = gateway()
        if (gw == null) {
            // Online is off. Answer, do not stay silent: an empty list plus a
            // sentence is the truth, and a never-called callback is a spinner.
            onDone(
                Snapshot(
                    id,
                    emptyList(),
                    emptyList(),
                    "Nhạc trực tuyến đang tắt trong Cài đặt"
                )
            )
            return
        }
        executor.execute {
            val snapshot = runCatching { toSnapshot(id, call(gw)) }
                .getOrElse { failure ->
                    Log.w(TAG, "online call failed: ${failure.message}")
                    Snapshot(id, emptyList(), emptyList(), "Không hỏi được nguồn trực tuyến")
                }
            main.post {
                // A newer query has been issued; this answer describes a list
                // the driver is no longer looking at.
                if (id != ticket.get()) return@post
                onDone(snapshot)
            }
        }
    }

    private fun toSnapshot(id: Int, aggregate: SearchAggregate): Snapshot {
        val lines = ArrayList<ProviderLine>(aggregate.perProvider.size)
        for ((providerId, result) in aggregate.perProvider) {
            when (result) {
                is ProviderResult.Ok -> lines.add(
                    ProviderLine(
                        providerId = providerId,
                        displayName = providerLabel(providerId),
                        kind = providerKind(providerId),
                        failure = null,
                        blockedConfig = false,
                        resultCount = result.value.size
                    )
                )
                // The failure's own message is used rather than a sentence
                // invented here: the provider knows whether it is a timeout, a
                // 429, a bad token or a missing address, and §13's
                // BLOCKED_EXTERNAL_CONFIG is exactly the code whose message
                // names the setting to enter.
                is ProviderResult.Failure -> lines.add(
                    ProviderLine(
                        providerId = providerId,
                        displayName = providerLabel(providerId),
                        kind = providerKind(providerId),
                        failure = result.message,
                        blockedConfig = result.code == ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG,
                        resultCount = 0
                    )
                )
            }
        }
        return Snapshot(
            ticket = id,
            tracks = aggregate.tracks,
            lines = lines,
            failure = aggregate.failure?.message
        )
    }

    private fun providerKind(providerId: String): ProviderKind = when (providerId) {
        "radio-browser" -> ProviderKind.INTERNET_RADIO
        "podcast" -> ProviderKind.PODCAST_RSS
        "subsonic" -> if (serverKind() == ProviderKind.JELLYFIN) ProviderKind.JELLYFIN
        else ProviderKind.NAVIDROME
        else -> ProviderKind.OTHER
    }

    /**
     * A human label for a provider id.
     *
     * The gateway exposes ids, not display names, on [SearchAggregate]'s map —
     * a deliberate choice on its side (the map is keyed by the stable id so a
     * saved queue can refer to it). The three ids below are the ones this app
     * constructs; an unknown id falls back to itself rather than to a
     * fabricated sentence.
     */
    private fun providerLabel(providerId: String): String = when (providerId) {
        "radio-browser" -> "Đài phát thanh"
        "podcast" -> "Podcast"
        "subsonic" -> if (serverKind() == ProviderKind.JELLYFIN) "Jellyfin" else "Navidrome"
        else -> providerId
    }

    // -- playback ----------------------------------------------------------

    /**
     * The player's [StreamResolver].
     *
     * Installed on the local player by [com.ts8.launcher.service.MusicPlaybackService]
     * so an online track in the queue opens like any other. Called off the main
     * thread by the player, blocks on the provider, and answers with a reason
     * rather than throwing.
     *
     * **Why the queue holds a resolved URL and not a provider id.** The player
     * opens a track with a single string, and re-resolving on every `next()`
     * would be one network request per track on a connection that may be a
     * phone hotspot. `toTrack` below resolves once, when the row is tapped, and
     * the URL is what enters the queue — the same lifetime the gateway's own
     * docs prescribe, because nothing caches it beyond that one open.
     */
    fun streamResolver(): StreamResolver = StreamResolver { track ->
        // Already a URL: nothing to resolve. This is the normal path, because
        // `toTrack` resolved before queueing.
        if (track.source.startsWith("http", ignoreCase = true)) {
            return@StreamResolver StreamResolution.Playable(
                url = track.source,
                mimeType = track.mimeType,
                durationMs = track.durationMs,
                isLive = track.durationMs <= 0L
            )
        }
        val providerId = track.providerId
            ?: return@StreamResolver StreamResolution.Unavailable("Bài này không còn nguồn phát", false)
        val source = resolveSource(providerId, track.source)
        when (source) {
            is ProviderResult.Ok -> StreamResolution.Playable(
                url = source.value.url,
                mimeType = source.value.mimeType,
                durationMs = track.durationMs,
                isLive = source.value.isStreaming
            )
            is ProviderResult.Failure -> StreamResolution.Unavailable(
                source.message,
                retryable = source.retryable
            )
        }
    }

    /**
     * Resolves a stream URL for a track, synchronously.
     *
     * Called from [streamResolver] (off the main thread, by the player) and
     * from [toTrack] (on the IO pool). Never from a UI callback directly.
     */
    fun resolveSource(providerId: String, trackId: String): ProviderResult<PlayableSource> {
        val gw = gateway()
            ?: return ProviderResult.Failure(
                ProviderFailureCode.BLOCKED_EXTERNAL_CONFIG,
                "Nhạc trực tuyến đang tắt trong Cài đặt",
                retryable = false
            )
        return gw.freshSource(providerId, trackId)
    }

    /**
     * Turns a search result into a queueable [Track] with a real URL.
     *
     * **Why resolution happens here, at tap time.** A search result carries
     * only a hint that a URL exists; the gateway's contract is that a real URL
     * must be re-issued before playback, because a station can move between the
     * search and the tap. Resolving at the moment of the tap is the earliest
     * point at which the answer is still true, and the latest point before the
     * player needs it.
     *
     * Returns null when no URL could be produced; the caller shows the reason
     * it already has from the same failure.
     */
    fun toTrack(online: OnlineTrack): Track? {
        val source = resolveSource(online.providerId, online.id)
        val url = (source as? ProviderResult.Ok)?.value?.url
        if (url.isNullOrBlank()) return null
        return toTrack(online, url)
    }

    /**
     * The same conversion with a URL already in hand.
     *
     * **Why the id is a hash, and not `OnlineTrack.id`.** A `Track.id` is a
     * `Long` in the library's id space, and an online item is not in the
     * library. A hash that sets a bit no library id can carry keeps the two
     * spaces disjoint, exactly as `TrackIds` does for USB. Two providers may
     * also issue the same id string — the provider id is part of the hash, so
     * they cannot collide.
     */
    fun toTrack(online: OnlineTrack, url: String): Track = Track(
        id = onlineTrackId(online.providerId, online.id),
        source = url,
        title = online.title,
        artist = online.artist,
        album = online.album,
        albumId = null,
        artistId = null,
        durationMs = online.durationMs,
        folder = null,
        trackNumber = 0,
        year = 0,
        mimeType = null,
        sizeBytes = 0,
        modifiedSeconds = 0,
        sourceKind = Track.SourceKind.ONLINE,
        providerId = online.providerId
    )

    // -- status ------------------------------------------------------------

    /**
     * One line per provider for the search screen's header, off the main thread.
     *
     * Uses the gateway's throttled [OnlineMusicGateway.status], so opening the
     * screen repeatedly does not probe the network each time. The unconfigured
     * sentences are appended verbatim from [OnlineMusicGateway.blockedConfig]
     * — §13's `BLOCKED_EXTERNAL_CONFIG` requirement, unmet by inventing a
     * message here.
     */
    fun status(onDone: (List<String>) -> Unit) {
        val gw = gateway()
        if (gw == null) {
            main.post { onDone(emptyList()) }
            return
        }
        executor.execute {
            val lines = runCatching {
                val blocked = gw.blockedConfig()
                if (blocked.isEmpty()) {
                    gw.status().map { status ->
                        val state = if (status.health.available) "sẵn sàng"
                        else status.health.detail ?: "không phản hồi"
                        "${status.displayName}: $state"
                    }
                } else {
                    blocked
                }
            }.getOrElse { emptyList() }
            main.post { onDone(lines) }
        }
    }

    /** True when nothing is configured, so the screen can say it once up front. */
    fun hasAnyProvider(): Boolean = gateway() != null

    /**
     * The three providers this build ships, with their configuration state.
     *
     * **Why this does not go through [gateway].** The gateway is `null` when
     * online music is switched off, and a source list built from it would then
     * be empty — a `Trực tuyến` screen that shows nothing at all, which reads
     * as "this app has no online music" rather than "online music is off".
     * §7 requires the entry to be discoverable and each unconfigured provider
     * to carry its own CTA, so the list is derived from settings, which is the
     * one thing that is always readable.
     *
     * **Why the order is fixed here and not from the providers themselves.**
     * The brief fixes it — Radio, Podcast, Navidrome/Jellyfin — and it must
     * match [build]'s order so the fourth row can never appear that the search
     * does not also try.
     *
     * No request is made: this is a settings read, safe on the main thread.
     */
    fun providers(): List<ProviderInfo> = buildProviders(
        feedConfigured = settings.onlineFeedUrl.isNotBlank(),
        serverConfigured = settings.onlineServerUrl.isNotBlank() &&
            settings.onlineUser.isNotBlank() &&
            settings.onlineToken.isNotBlank(),
        jellyfin = serverKind() == ProviderKind.JELLYFIN
    )

    /** True when the driver has switched online music off in Settings. */
    fun onlineEnabled(): Boolean = settings.onlineEnabled

    companion object {
        private const val TAG = "Ts8OnlineMusic"

        /**
         * The three providers this build ships, built from configuration alone.
         *
         * **Why this is a companion function and not only an instance method.**
         * §12's seventh regression test — "online source entry luôn
         * discoverable" — has to prove that a provider with a missing setting
         * still produces a row with a `Thiết lập` CTA. Doing that through
         * [OnlineMusicRuntime] would need an Android `Context`, a settings store
         * and an IO executor; doing it here needs four booleans. The instance
         * method [providers] is then a two-line reading of the driver's actual
         * settings and cannot drift from what the test covers.
         *
         * **Why every provider is always present.** An unconfigured source that
         * vanished would leave the driver unable to discover that the launcher
         * could play podcasts at all — the silent disappearance §7 forbids. The
         * `missing` field is what turns the row into a CTA rather than a dead
         * entry, so it must name a real setting, never a generic sentence.
         *
         * **Why Radio is unconditionally configured.** Internet radio needs no
         * credentials: the provider is a public directory. Reporting it as
         * unconfigured would put a `Thiết lập` button on the one source a brand
         * new unit can play immediately.
         */
        fun buildProviders(
            feedConfigured: Boolean,
            serverConfigured: Boolean,
            jellyfin: Boolean
        ): List<ProviderInfo> = listOf(
            ProviderInfo(
                providerId = "radio-browser",
                displayName = "Đài phát thanh",
                kind = ProviderKind.INTERNET_RADIO,
                configured = true,
                missing = null
            ),
            ProviderInfo(
                providerId = "podcast",
                displayName = "Podcast",
                kind = ProviderKind.PODCAST_RSS,
                configured = feedConfigured,
                missing = if (feedConfigured) null else "địa chỉ RSS"
            ),
            ProviderInfo(
                providerId = "subsonic",
                displayName = if (jellyfin) "Jellyfin" else "Navidrome",
                kind = if (jellyfin) ProviderKind.JELLYFIN else ProviderKind.NAVIDROME,
                configured = serverConfigured,
                missing = if (serverConfigured) null else "địa chỉ máy chủ và tài khoản"
            )
        )

        /**
         * Bit marking an id as an online track rather than a library row.
         *
         * Bit 61, not 62: 62 is what `TrackIds` uses for a USB path hash, and
         * reusing it would let an online id and a USB id collide — the two
         * spaces must stay disjoint for the same reason described on
         * `TrackIds.forUsbPath`.
         */
        private const val ONLINE_BIT = 1L shl 61

        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 0x100000001b3L

        /**
         * A stable `Long` id for an online track.
         *
         * Deterministic on `(providerId, trackId)`, so the same station gets the
         * same id across searches — which is what lets the queue highlight it
         * and what keeps two providers' identical id strings apart.
         */
        fun onlineTrackId(providerId: String, trackId: String): Long {
            var hash = FNV_OFFSET
            for (ch in "$providerId\u0000$trackId") {
                hash = hash xor (ch.code.toLong() and 0xFFFF)
                hash *= FNV_PRIME
            }
            return (hash and (ONLINE_BIT - 1)) or ONLINE_BIT
        }
    }
}
