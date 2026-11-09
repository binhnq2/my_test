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
 * from a previous ignition cycle. The **transcript** is not stored either: the
 * codec below has no key for it, so a restored
 * [VoiceStateSnapshot.transcript] is always null. A spoken sentence belongs to
 * the interaction that heard it; quoting yesterday's "dẫn đường tới Đà Nẵng"
 * as "Bạn nói" on a cold start would be a fabricated record of speech nobody
 * just made, and the overlay shows exactly that line.
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
