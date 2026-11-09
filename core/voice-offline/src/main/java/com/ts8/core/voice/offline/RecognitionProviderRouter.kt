package com.ts8.core.voice.offline

import com.ts8.core.voice.VoiceError

/**
 * Chooses the provider for one recognition request, and switches at most once.
 *
 * **The rule that shapes this class.** "Một request chỉ chuyển provider tối đa
 * một lần" is not a performance note — it is what stops two recognisers
 * ping-ponging on one utterance. Without the counter, a unit whose system
 * recogniser fails with `NETWORK_ERROR` and whose offline model then fails with
 * `INTERNAL_ERROR` would, on any symmetric policy, be tried again in the other
 * direction, and the driver would wait through two full timeouts to be told
 * nothing. The counter is on the *request*, not the provider, so the second
 * failure is final by construction.
 *
 * **Why the policy is read per request rather than stored.** The driver can
 * change it in Settings between two utterances, and a router holding the value
 * it was built with would keep using the old one until the process restarted —
 * the same class of bug as a cached `SharedPreferences` read.
 *
 * **Why the provider list is injected.** The router must not know whether a
 * Vosk model exists on this particular unit. It asks [RecognitionProvider.isUsable],
 * and the answer being false is a normal state (no model installed yet), not an
 * error. Keeping construction outside also means every branch below is
 * exercisable with two fakes and no native library.
 */
class RecognitionProviderRouter(
    private val system: RecognitionProvider,
    private val offline: RecognitionProvider,
    /** Asked at the moment of the decision, so a settings change takes effect. */
    private val policy: () -> RecognitionPolicy,
    /**
     * Whether an offline model is READY.
     *
     * Distinct from `offline.isUsable()`, which on a real unit would have to
     * open a 33 MB model to answer. The model store knows the state cheaply;
     * this is what the router consults before spending a switch on a backend
     * that has nothing to load.
     */
    private val offlineModelReady: () -> Boolean
) {

    /** One request's route. Created per utterance by the controller. */
    inner class Session(
        private val request: RecognitionRequest,
        private val callback: RecognitionCallback
    ) {
        /** How many times the provider changed for this request. Never above 1. */
        var switches: Int = 0
            private set

        /** The provider currently holding the microphone, or null. */
        var active: RecognitionProvider? = null
            private set

        /** Every failure seen, in order, for diagnostics. */
        val failures: MutableList<Pair<String, VoiceError>> = ArrayList(2)

        /** The provider's own id, for the diagnostics record. */
        var activeId: String? = null
            private set

        private var finished = false

        /**
         * The route this request took, captured exactly once by [retain].
         *
         * Captured rather than read from the live fields afterwards: the
         * diagnostics are exported long after the session is gone, and
         * [releaseActive] has already cleared [active] by then.
         */
        private var summary: RouteSummary? = null

        /**
         * Opens the first provider.
         *
         * The policy decides which one that is, and `OFFLINE_ONLY` with no
         * model is reported as `SERVICE_UNAVAILABLE` rather than silently
         * falling through to the system recogniser — the driver chose offline,
         * and sending their audio to a server behind their back is exactly the
         * thing that policy exists to prevent.
         *
         * **AUTO with a ready offline model goes straight to Vosk.** On the
         * TS8 the system recogniser never supports Vietnamese — it reports
         * `LANGUAGE_UNAVAILABLE` or `SERVICE_UNAVAILABLE` every time — so
         * trying it first wastes 1–2 seconds and surfaces a confusing error
         * if the fallback conditions are not perfectly met. When the offline
         * model is READY, there is no reason to probe a backend that is
         * guaranteed to fail on this hardware.
         */
        fun begin() {
            val diag = VoiceDiagnosticLogger.get()
            val p = policy()
            val modelReady = offlineModelReady()
            val offlineUsable = offline.isUsable()
            val systemUsable = system.isUsable()
            diag.log("Router: begin policy=$p modelReady=$modelReady offlineUsable=$offlineUsable systemUsable=$systemUsable")
            when (p) {
                RecognitionPolicy.SYSTEM_ONLY -> {
                    diag.log("Router: SYSTEM_ONLY -> startSystem")
                    startSystem()
                }

                RecognitionPolicy.OFFLINE_ONLY -> {
                    if (!modelReady || !offlineUsable) {
                        diag.log("Router: OFFLINE_ONLY but model not ready -> SERVICE_UNAVAILABLE")
                        promote(callback, VoiceError.SERVICE_UNAVAILABLE, "offline model not ready")
                    } else {
                        diag.log("Router: OFFLINE_ONLY -> startOffline")
                        startOffline()
                    }
                }

                RecognitionPolicy.AUTO -> {
                    // When the offline model is READY, prefer it directly.
                    // The system recogniser on the TS8 vendor ROM has no
                    // Vietnamese model and always fails, so probing it first
                    // only delays the driver and risks showing a confusing
                    // "không hỗ trợ" error before the fallback fires.
                    if (modelReady && offlineUsable) {
                        diag.log("Router: AUTO, model READY -> startOffline (skip system)")
                        startOffline()
                    } else if (systemUsable) {
                        diag.log("Router: AUTO, model NOT ready, system usable -> startSystem")
                        startSystem()
                    } else {
                        diag.log("Router: AUTO, neither ready -> startOfflineOrReport")
                        startOfflineOrReport()
                    }
                }
            }
        }

        /**
         * The active provider failed. Decides whether to switch for this one.
         *
         * @param from the provider that reported it, so a late error from a
         *        provider that has already been superseded is ignored instead
         *        of being routed again.
         */
        fun onProviderError(from: RecognitionProvider, error: VoiceError, detail: String?) {
            val diag = VoiceDiagnosticLogger.get()
            diag.log("Router: onProviderError from=${from.id} error=$error detail=$detail finished=$finished active=${active?.id}")
            if (finished) return
            // A stale error from a provider we already abandoned: it arrives
            // after `cancel()` on some firmware. Acting on it would make the
            // *new* provider look like it failed.
            if (active !== from) {
                diag.log("Router: ignoring stale error (active=${active?.id} != from=${from.id})")
                return
            }
            failures += (from.id to error)

            // `SYSTEM_ONLY` is the pre-pilot behaviour kept for diagnostics and
            // regression tests, and it must stay exactly that: a unit with a
            // model installed still reports the system recogniser's failure
            // rather than quietly answering from a backend the operator did not
            // select. Without the policy check here the fallback would fire for
            // `SERVICE_UNAVAILABLE` on every policy, because that error is a
            // trigger by definition.
            val canFallback = switches == 0 &&
                policy() == RecognitionPolicy.AUTO &&
                from === system &&
                error.allowsOfflineFallback()
            diag.log("Router: fallback check: canFallback=$canFallback switches=$switches allowsFallback=${error.allowsOfflineFallback()}")
            if (canFallback) {
                val modelReady = offlineModelReady()
                val offlineUsable = offline.isUsable()
                diag.log("Router: fallback conditions: modelReady=$modelReady offlineUsable=$offlineUsable")
                if (modelReady && offlineUsable) {
                    diag.log("Router: switching to offline provider")
                    releaseActive()
                    switches++
                    startOffline()
                    return
                }
                // The failure would otherwise be reported as SERVICE_UNAVAILABLE
                // by the system provider, which on a unit with no model is
                // accurate but hides *why*: the model would have helped and is
                // not installed. `LANGUAGE_UNAVAILABLE` and `NETWORK_ERROR`
                // reach the driver unchanged so the message names the real
                // remedy; `SERVICE_UNAVAILABLE` is only rewritten when the
                // policy was AUTO and a model was the missing piece.
                diag.log("Router: no fallback available, promoting error: $error")
                promote(callback, error, detail)
                return
            }

            diag.log("Router: no fallback (policy or error type), promoting error: $error")
            promote(callback, error, detail)
        }

        /** The provider produced a final transcript. Ends the request. */
        fun onProviderFinal(from: RecognitionProvider, hypotheses: List<String>, confidence: Float?) {
            val diag = VoiceDiagnosticLogger.get()
            diag.log("Router: onProviderFinal from=${from.id} hypotheses=${hypotheses.size} confidence=$confidence")
            if (finished || active !== from) return
            retain(from)
            finished = true
            releaseActive()
            callback.onFinal(hypotheses, confidence)
        }

        /** The driver left, or the controller closed the overlay. */
        fun cancel() {
            val diag = VoiceDiagnosticLogger.get()
            diag.log("Router: cancel finished=$finished active=${active?.id}")
            if (finished) return
            active?.let { retain(it) }
            finished = true
            releaseActive()
        }

        /**
         * What §11 asks the export to say about the *routing* half.
         *
         * Two facts, both only this class knows: which provider ended up with
         * the microphone, and whether the request was handed across once. The
         * trail is the ordered `provider:error` list, which is how a support
         * call tells "the system recogniser was never tried" from "it was tried
         * and refused, and the model answered".
         */
        private fun summaryOf(provider: RecognitionProvider): RouteSummary = RouteSummary(
            providerId = provider.id,
            policy = policy(),
            switches = switches,
            trail = failures.takeIf { it.isNotEmpty() }
                ?.joinToString(" -> ") { "${it.first}:${it.second}" }
        )

        /** Captures the summary for [provider], once per request. */
        private fun retain(provider: RecognitionProvider) {
            if (summary != null) return
            val captured = summaryOf(provider)
            summary = captured
            // Published for [lastDiagnostics]; a session that never ends (the
            // process died mid-utterance) leaves the previous value alone,
            // which is the honest record of the last request that finished.
            this@RecognitionProviderRouter.lastSummary = captured
        }

        // -- internals ------------------------------------------------------

        private fun startSystem() {
            val diag = VoiceDiagnosticLogger.get()
            active = system
            activeId = system.id
            diag.log("Router: startSystem calling system.start...")
            val started = runCatching {
                system.start(request, bridgeFor(system))
            }.getOrElse { e ->
                diag.error("Router: system.start threw: ${e.javaClass.simpleName}: ${e.message}", e)
                // A provider that throws instead of reporting is a bug, but the
                // driver still needs an answer; the same error path handles it.
                onProviderError(
                    system,
                    VoiceError.INTERNAL_ERROR,
                    "${e.javaClass.simpleName}: ${e.message}"
                )
                return
            }
            diag.log("Router: system.start returned started=$started")
            // A false return normally means the provider already reported
            // through its callback, so there is nothing left to do — the
            // switch decision belongs to `onProviderError` and must be counted
            // in exactly one place. The exception is a provider that returned
            // false *without* reporting, which is a contract violation; that
            // is treated as unavailable so the request does not hang.
            if (!started && active === system && !finished && failures.isEmpty()) {
                diag.log("Router: system start=false with no error reported -> SERVICE_UNAVAILABLE")
                onProviderError(system, VoiceError.SERVICE_UNAVAILABLE, "start=false, no error")
            }
        }

        private fun startOffline() {
            val diag = VoiceDiagnosticLogger.get()
            active = offline
            activeId = offline.id
            diag.log("Router: startOffline calling offline.start...")
            val started = runCatching {
                offline.start(request, bridgeFor(offline))
            }.getOrElse { e ->
                diag.error("Router: offline.start threw: ${e.javaClass.simpleName}: ${e.message}", e)
                promote(
                    callback,
                    VoiceError.INTERNAL_ERROR,
                    "offline: ${e.javaClass.simpleName}: ${e.message}"
                )
                return
            }
            diag.log("Router: offline.start returned started=$started")
            if (!started && active === offline && !finished && failures.isEmpty()) {
                diag.log("Router: offline start=false with no error reported -> INTERNAL_ERROR")
                promote(callback, VoiceError.INTERNAL_ERROR, "offline start=false, no error")
            }
        }

        /** No system recogniser at all: go straight to the model, or give up. */
        private fun startOfflineOrReport() {
            if (offlineModelReady() && offline.isUsable()) {
                // Reaching the model from a unit with no system recogniser is
                // not a "switch": nothing was ever started, so `switches`
                // stays 0 and a real failure on the model is still terminal.
                startOffline()
            } else {
                promote(callback, VoiceError.SERVICE_UNAVAILABLE, "no system recogniser, no model")
            }
        }

        /** Ends the request with [error] and releases whatever is holding the mic. */
        private fun promote(cb: RecognitionCallback, error: VoiceError, detail: String?) {
            // Retained *before* releaseActive clears `active`: the export has to
            // name the provider that actually failed, not null.
            active?.let { retain(it) }
            finished = true
            releaseActive()
            cb.onError(error, diagnosticDetail(detail))
        }

        /**
         * Adds the fallback trail to the detail string.
         *
         * Kept in the log rather than the overlay: the driver sees one sentence,
         * but a support call needs to know the system recogniser failed first
         * and *why* the switch did or did not happen.
         */
        private fun diagnosticDetail(detail: String?): String? {
            if (failures.isEmpty()) return detail
            val trail = failures.joinToString(" -> ") { "${it.first}:${it.second}" }
            val model = if (offlineModelReady()) "model=READY" else "model=NOT_READY"
            val head = "provider=$activeId switches=$switches $model trail=$trail"
            return if (detail.isNullOrBlank()) head else "$head; $detail"
        }

        private fun releaseActive() {
            val current = active ?: return
            active = null
            runCatching { current.cancel() }
        }

        /**
         * Wraps the caller's callback so every provider event is attributed to
         * the provider that produced it.
         *
         * Without the attribution a switch could not tell "the system
         * recogniser failed" from "the model failed", and the two lead to
         * different verdicts.
         */
        private fun bridgeFor(provider: RecognitionProvider): RecognitionCallback =
            object : RecognitionCallback {
                override fun onReady() {
                    if (active === provider && !finished) callback.onReady()
                }

                override fun onPartial(text: String) {
                    if (active === provider && !finished) callback.onPartial(text)
                }

                override fun onFinal(hypotheses: List<String>, confidence: Float?) {
                    onProviderFinal(provider, hypotheses, confidence)
                }

                override fun onError(error: VoiceError, detail: String?) {
                    onProviderError(provider, error, detail)
                }
            }
    }

    /**
     * The routing half of the last request, published by [Session.retain].
     *
     * Held on the router rather than handed to the caller because the caller is
     * the controller, which has no business knowing what `switches` means. The
     * provider's own [lastDiagnostics] record carries the model and audio
     * measurements; this supplies the two facts only the router has.
     */
    @Volatile
    private var lastSummary: RouteSummary? = null

    /** Starts a request. The caller owns the returned session. */
    fun newSession(
        request: RecognitionRequest,
        callback: RecognitionCallback
    ): Session = Session(request, callback)

    /**
     * Frees both providers. Called when the owning controller is torn down.
     *
     * Both are released rather than only the one that ran last: the offline
     * provider holds a loaded model when it has done nothing this session, and
     * the system provider holds a `SpeechRecognizer` until it is destroyed. A
     * release that only reached the active one would be correct exactly until
     * the first driver who pressed the microphone once.
     */
    fun release() {
        runCatching { system.release() }
        runCatching { offline.release() }
    }

    /**
     * A different model was activated; both providers drop cached state.
     *
     * Routed here rather than called on the offline provider directly because
     * the caller is a Settings screen that must not know which class implements
     * the offline backend. Cheap and idempotent: a provider with nothing cached
     * answers `Unit`.
     */
    fun onModelChanged() {
        runCatching { system.onModelChanged() }
        runCatching { offline.onModelChanged() }
    }

    /**
     * The measurements of whichever provider ran last; null when neither did.
     *
     * The router's own routing facts are merged in here rather than by each
     * provider: `provider`, `policy`, `switches` and `fallbackTrail` describe
     * the *decision*, which no single provider can see. A `SYSTEM_ONLY` run
     * that switched to the model on `NETWORK_ERROR`, and an `AUTO` run that
     * went straight to the model because no recogniser was installed, produce
     * identical provider records apart from these four keys.
     */
    fun lastDiagnostics(): VoiceDiagnostics? {
        val offlineRecord = runCatching { offline.lastDiagnostics() }.getOrNull()
        val systemRecord = runCatching { system.lastDiagnostics() }.getOrNull()
        val summary = lastSummary
        // The record that belongs to the request the summary describes wins.
        // Merely preferring the offline provider's record shows whatever the
        // model did *last time* on a run the system recogniser actually handled
        // — a diagnostics screen that names the wrong backend is worse than one
        // with a missing field.
        if (summary?.providerId != null) {
            systemRecord?.takeIf { it.providerId == summary.providerId }
                ?.let { return summary.applyTo(it) }
            offlineRecord?.takeIf { it.providerId == summary.providerId }
                ?.let { return summary.applyTo(it) }
        }
        val record = offlineRecord ?: systemRecord ?: return null
        return summary?.applyTo(record) ?: record
    }
}

/**
 * The routing facts for one request, captured before the session is released.
 *
 * **Why this is a top-level class and not nested in [RecognitionProviderRouter.Session].**
 * `Session` is an `inner` class (it publishes to the router), and Kotlin
 * forbids nesting a class inside an inner one. It is also why the record is
 * published onto the router the moment it is captured: the session is
 * short-lived and the export happens long after it is gone.
 *
 * `switches = 0` with a null [trail] is a healthy single-provider run, so a
 * request that never opened anything must not produce one — see
 * [RecognitionProviderRouter.Session.retain].
 */
internal data class RouteSummary(
    val providerId: String?,
    val policy: RecognitionPolicy,
    val switches: Int,
    val trail: String?
) {
    /**
     * Merges these facts into the provider's own record.
     *
     * The record keeps everything it already knew (model, audio, transcript);
     * only the four routing keys are written, because no single provider can
     * observe them.
     */
    fun applyTo(diagnostics: VoiceDiagnostics): VoiceDiagnostics {
        diagnostics.providerId = diagnostics.providerId ?: providerId
        diagnostics.policy = policy
        diagnostics.switches = switches
        diagnostics.failureTrail = trail
        return diagnostics
    }
}
