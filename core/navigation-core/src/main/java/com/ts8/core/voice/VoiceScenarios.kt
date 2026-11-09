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
 * **What changed when the prompt was removed.** Two cases here used to assert
 * that "dẫn đường tới Đà Nẵng" and "phát nhạc" stopped at [VoiceState.CONFIRMING]
 * and waited for a tap. They now assert the opposite — the command runs on the
 * transcript alone — and a new case asserts that the transcript reaches the
 * snapshot, because that line is what replaced the question. The confirming
 * path is still exercised, through the one thing that legitimately asks:
 * an executor returning [CommandOutcome.NeedsConfirmation] to disambiguate.
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
        checkDestructiveRunsWithoutPrompt(failures)
        checkTranscriptIsQuoted(failures)
        checkExecutorDisambiguationStillAsks(failures)
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

    /**
     * The destructive commands run on the transcript, with no yes/no bar.
     *
     * **This replaces the old `checkConfirmYes` / `checkConfirmNo` pair.** Those
     * asserted the opposite, and they were right for the build they were
     * written for: a route being torn down by a misheard phrase is a real
     * failure. The prompt was removed because it cost the driver a second
     * interaction — and a tap — for a feature whose whole point is that they do
     * not have to reach for the screen. What makes that safe is
     * [checkTranscriptIsQuoted]: the words the recogniser produced are on
     * screen, so a wrong one is visible and can be corrected by saying it again.
     *
     * Both commands are checked, because "dẫn đường" and "phát nhạc" are the
     * only two that used to stop, and a change that made one of them behave
     * differently from the other is exactly the drift this would catch.
     */
    private fun checkDestructiveRunsWithoutPrompt(failures: MutableList<String>) {
        for (phrase in listOf("dẫn đường tới Đà Nẵng", "phát nhạc")) {
            val executor = RecordingExecutor()
            executor.outcome = CommandOutcome.Done("Đã thực hiện")
            val r = router(executor, FakeClock())

            r.onTranscript(phrase)

            if (r.state() != VoiceState.SUCCESS) {
                failures.add(
                    "router: \"$phrase\" ended in ${r.state()}, expected SUCCESS — " +
                        "the confirmation prompt must not gate a destructive command"
                )
            }
            if (executor.commands.size != 1) {
                failures.add(
                    "router: \"$phrase\" executed ${executor.commands.size} commands, expected exactly 1"
                )
            }
            if (r.pendingCommand() != null) {
                failures.add("router: \"$phrase\" left a pending command after running")
            }
        }

        // The command table still has to name which commands are destructive —
        // `checkParser` asserts the table agrees with itself, and this asserts
        // the table is not simply empty, which would make that check vacuous.
        if (VoiceCommand.allTypes().none { it.needsConfirmation }) {
            failures.add("command: no command is flagged destructive — the table lost its meaning")
        }
    }

    /**
     * The transcript reaches the snapshot, which is what the overlay renders.
     *
     * This is the whole replacement for the prompt, so it is asserted on the
     * three outcomes that can follow a transcript — success, failure and
     * unrecognised — rather than only on the happy path. The unrecognised case
     * matters most: it is the one where the driver most needs to see what was
     * heard, and the one where nothing acted on it.
     */
    private fun checkTranscriptIsQuoted(failures: MutableList<String>) {
        val spoken = "  dẫn đường tới Cầu Rồng  "

        val ok = router(RecordingExecutor(), FakeClock())
        ok.onTranscript(spoken)
        if (ok.snapshot().transcript != "dẫn đường tới Cầu Rồng") {
            failures.add(
                "router: snapshot transcript was ${ok.snapshot().transcript}, " +
                    "expected the trimmed sentence the recogniser produced"
            )
        }

        val unrecognised = router(RecordingExecutor(), FakeClock())
        unrecognised.onTranscript("abc")
        if (unrecognised.snapshot().transcript != "abc") {
            failures.add("router: an unrecognised sentence was not quoted back")
        }
        if (unrecognised.snapshot().state != VoiceState.ERROR) {
            failures.add("router: unrecognised sentence ended in ${unrecognised.snapshot().state}")
        }

        // A new interaction starts with no transcript: quoting the previous
        // session's sentence would be a record of speech nobody just made.
        unrecognised.onListeningStarted()
        if (unrecognised.snapshot().transcript != null) {
            failures.add("router: the previous transcript survived a new interaction")
        }

        // Blank speech is "nothing was heard", not an empty quote.
        val blank = router(RecordingExecutor(), FakeClock())
        blank.onTranscript("   ")
        if (blank.snapshot().transcript != null) {
            failures.add("router: a blank transcript was recorded as text")
        }
    }

    /**
     * The one path that still asks: an executor that cannot choose.
     *
     * "Bến Thành" names two places, and the executor answers with a narrowed
     * command plus a question. There is no safe default for that — running
     * either one picks a destination the driver did not say — so CONFIRMING
     * has to keep working, including its "yes" and its "no".
     */
    private fun checkExecutorDisambiguationStillAsks(failures: MutableList<String>) {
        val asked = VoiceCommand.NavigateTo("Bến Thành (Quận 1)")
        val executor = object : CommandExecutor {
            val commands = ArrayList<VoiceCommand>()

            override fun execute(command: VoiceCommand): CommandOutcome {
                commands.add(command)
                return if (commands.size == 1) {
                    CommandOutcome.NeedsConfirmation(
                        "Có hai địa điểm tên Bến Thành. Dùng Bến Thành (Quận 1)?",
                        asked
                    )
                } else {
                    CommandOutcome.Done("Đang dẫn đường tới ${asked.destination}")
                }
            }
        }
        val r = router(executor, FakeClock(), config = VoiceRouter.Config())

        r.onTranscript("dẫn đường tới Bến Thành")
        if (r.state() != VoiceState.CONFIRMING) {
            failures.add(
                "router: an executor disambiguation did not reach CONFIRMING, " +
                    "was ${r.state()}"
            )
            return
        }
        if (r.pendingCommand() != asked) {
            failures.add("router: the pending command is not the one the executor narrowed to")
        }

        r.confirm(true)
        if (r.state() != VoiceState.SUCCESS) {
            failures.add("router: confirm(true) after a disambiguation ended in ${r.state()}")
        }
        if (executor.commands.lastOrNull() != asked) {
            failures.add("router: confirm(true) executed the original command, not the narrowed one")
        }

        // A second ask would trap the driver in a prompt loop; it is a failure.
        val looping = object : CommandExecutor {
            var calls = 0

            override fun execute(command: VoiceCommand): CommandOutcome {
                calls++
                return CommandOutcome.NeedsConfirmation("Lại xác nhận?", command)
            }
        }
        val r2 = router(looping, FakeClock())
        r2.onTranscript("dẫn đường tới Bến Thành")
        if (r2.state() != VoiceState.CONFIRMING) {
            failures.add("router: the loop fixture did not reach CONFIRMING")
            return
        }
        r2.confirm(true)
        if (r2.state() != VoiceState.ERROR) {
            failures.add("router: a second ask after a yes ended in ${r2.state()}, expected ERROR")
        }

        // "No" on a disambiguation must run nothing.
        val refusing = RecordingExecutor()
        refusing.outcome = CommandOutcome.NeedsConfirmation("Chọn một?", asked)
        val r3 = router(refusing, FakeClock())
        r3.onTranscript("dẫn đường tới Bến Thành")
        r3.confirm(false)
        if (refusing.commands.size != 1) {
            // The first call is the one that asked; a second would be the bug.
            failures.add("router: a \"no\" re-executed the command (${refusing.commands.size} calls)")
        }
        if (r3.state() != VoiceState.IDLE) {
            failures.add("router: a \"no\" ended in ${r3.state()}, expected IDLE")
        }
    }

    private fun checkTimeout(failures: MutableList<String>) {
        val executor = RecordingExecutor()
        val clock = FakeClock()
        val asked = VoiceCommand.NavigateTo("Hội An")
        executor.outcome = CommandOutcome.NeedsConfirmation("Dùng Hội An?", asked)
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
        if (executor.commands.size != 1) {
            failures.add("router: timeout re-executed the pending command")
        }
        if (r.lastError != VoiceError.TIMEOUT) {
            failures.add("router: timeout did not record VoiceError.TIMEOUT")
        }

        // The "yes" that arrives after the timeout must not resurrect it.
        r.confirm(true)
        if (executor.commands.size != 1) {
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
