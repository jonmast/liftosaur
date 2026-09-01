package com.liftosaur.wear

import android.util.Log
import com.liftosaur.wear.engine.CpuTime
import com.liftosaur.wear.engine.EngineDispatcher
import com.liftosaur.wear.engine.LiftosaurEngine
import com.liftosaur.wear.engine.WatchAmrapModal
import com.liftosaur.wear.engine.WatchJs
import com.liftosaur.wear.engine.WatchStorageRepository
import com.liftosaur.wear.sync.WatchOutboundStorageBuilder
import kotlinx.coroutines.withContext

/**
 * The spec §4 budget table, re-measured on the finished build against the **real** synced
 * storage — ticket 07's evidence.
 *
 * ### Why this is not the self-test
 * [com.liftosaur.wear.engine.EngineSelfTest] measures the engine in isolation against a 34KB
 * bundled fixture. Every number that turned out to matter is a function of the *account*:
 * mutation cost is dominated by the bundle's `JSON.parse` of whole storage, and the developer's
 * own filtered payload is ~142KB against the fixture's 34KB. A budget table built from the
 * fixture would be measuring a workload nobody has.
 *
 * ### It simulates a whole workout, and never persists any of it
 * The interesting numbers — mutation latency, the three-call prompt interaction, peak session
 * memory — only exist across a *sequence* of mutations. So this walks the real storage through
 * `startWorkout` → `completeSet` per set (answering prompts as a user accepting the defaults
 * would) → `finishWorkout`, threading each call's result into the next exactly as
 * [WatchStorageRepository.mutate] does.
 *
 * Nothing is written: this never touches the repository's mutation funnel, so nothing is
 * persisted to `storage.json` and — importantly — nothing is announced to the phone. The
 * simulated workout exists only in the bundle's module-scope cache and in local variables, and
 * the cache is dropped on the way out.
 *
 * ### The `deviceId` is deliberately fake
 * A simulated session bumps `_versions` counters. Those documents are discarded, but if one
 * ever did escape, `wear-bench` in a vector clock is an immediately diagnosable bug rather
 * than a real device's counter running mysteriously ahead.
 */
object PreShipBench {
    private const val TAG = "PreShipBench"

    /** Never used for anything that is persisted or sent. See the class docs. */
    private const val BENCH_DEVICE_ID = "wear-bench"

    // spec §4 budgets. Stated here rather than in the UI so the pass/fail verdict and the
    // logged evidence cannot disagree about what the ceiling was.
    const val COLD_START_BUDGET_MS = 1500.0
    const val WARM_READ_BUDGET_MS = 50.0
    const val MUTATION_BUDGET_MS = 1000.0
    const val ENGINE_ANON_BUDGET_KB = 8L * 1024
    const val SESSION_ANON_BUDGET_KB = 64L * 1024

    private const val WARM_READ_SAMPLES = 20
    private const val MALLOC_TREND_CALLS = 50
    private const val OUTBOUND_SAMPLES = 10

    /** A long workout is ~30 sets; the cap stops a pathological program running for minutes. */
    private const val MAX_SETS = 40

    data class Result(
        val storageBytes: Int,
        val usedRealStorage: Boolean,
        val coldStart: LiftosaurEngine.ColdStart,
        val warmReadMedianMs: Double,
        val warmReadMaxMs: Double,
        val mutationMedianMs: Double,
        val mutationMaxMs: Double,
        /** completeSet + getAmrapModal + completeSetWithAmrap — what one prompt set costs. */
        val promptInteractionMs: Double,
        val finishWorkoutMs: Double,
        /** `org.json` parse + gzip per watch→phone put, on the sender's thread (ticket 06). */
        val outboundBuildMedianMs: Double,
        val gzippedBytes: Int,
        val sessionPeakAnonKb: Long,
        val sessionAnonGrowthKb: Long,
        val mallocBeforeBytes: Long,
        val mallocAfterBytes: Long,
        val setsLogged: Int,
        val promptsAnswered: Int,
        val failure: String?,
    ) {
        val coldStartOk get() = coldStart.totalCpuMs in 0.0..COLD_START_BUDGET_MS
        val warmReadOk get() = warmReadMedianMs in 0.0..WARM_READ_BUDGET_MS
        val mutationOk get() = mutationMedianMs in 0.0..MUTATION_BUDGET_MS
        val engineAnonOk get() = coldStart.initAnonKb in 0..ENGINE_ANON_BUDGET_KB
        val sessionAnonOk get() = sessionPeakAnonKb in 0..SESSION_ANON_BUDGET_KB

        /** The `malloc_size` budget is a *trend*, not a ceiling: constant payload, constant size. */
        val mallocTrendOk get() = mallocAfterBytes <= mallocBeforeBytes

        val allOk
            get() = coldStartOk && warmReadOk && mutationOk && engineAnonOk &&
                sessionAnonOk && mallocTrendOk && failure == null
    }

    suspend fun run(repository: WatchStorageRepository): Result =
        withContext(EngineDispatcher.dispatcher) { runOnEngineThread(repository) }

    private fun runOnEngineThread(repository: WatchStorageRepository): Result {
        val base = repository.storage.value
            ?: return empty("no storage yet — pair the phone and sync before benchmarking")

        var failure: String? = null
        val anonAtStart = CpuTime.anonRssKb()
        var peakAnon = anonAtStart

        fun sampleAnon() {
            val now = CpuTime.anonRssKb()
            if (now > peakAnon) peakAnon = now
        }

        // The bundle's cache is not keyed by content, so a call with storage it has not parsed
        // returns the *previous* document. Everything below depends on measuring against `base`.
        WatchJs.invalidateStorageCache()

        // --- warm reads -------------------------------------------------------------------
        // The first call after invalidation pays the parse, so it is deliberately not a sample:
        // it is the cold path, and the cold path is already in the cold-start number.
        WatchJs.getProgress(base)
        val warmReads = mutableListOf<Long>()
        repeat(WARM_READ_SAMPLES) {
            val t0 = CpuTime.nanos()
            WatchJs.getProgress(base)
            warmReads += CpuTime.nanos() - t0
        }
        sampleAnon()

        // --- a whole simulated workout ----------------------------------------------------
        val mutations = mutableListOf<Long>()
        var promptInteractionNanos = -1L
        var finishNanos = -1L
        var setsLogged = 0
        var promptsAnswered = 0
        var storage = base

        val active = (WatchJs.getProgress(storage) as? WatchJs.CallResult.Success)?.value
        if (active == null) {
            val t0 = CpuTime.nanos()
            when (val started = WatchJs.startWorkout(storage, BENCH_DEVICE_ID)) {
                is WatchJs.MutationResult.Success -> {
                    mutations += CpuTime.nanos() - t0
                    storage = started.storage
                }
                is WatchJs.MutationResult.Failure -> failure = "startWorkout: ${started.error}"
            }
        }

        var sets = 0
        var lastTarget: Pair<Int, Int>? = null
        while (failure == null && sets < MAX_SETS) {
            val workout = (WatchJs.getProgress(storage) as? WatchJs.CallResult.Success)?.value
                ?: break
            val entryIndex = workout.nextUnfinishedEntryIndex()
            val entry = workout.exercises.getOrNull(entryIndex) ?: break
            val next = entry.nextUnfinished() ?: break

            // A modal with no answerable field decodes to all-null answers, which is the
            // bundle's *cancel* path — the set stays open and the loop would grind the same set
            // 40 times, reporting a plausible-looking median for a workout that never happened.
            // Stopping on a repeat makes that visible as a short session instead.
            val target = entryIndex to next.at.pos
            if (target == lastTarget) {
                failure = "stalled on entry=$entryIndex ${next.at} — set did not complete"
                break
            }
            lastTarget = target

            val interaction0 = CpuTime.nanos()
            val t0 = CpuTime.nanos()
            val logged = WatchJs.completeSet(storage, BENCH_DEVICE_ID, entryIndex, next.at)
            val loggedNanos = CpuTime.nanos() - t0
            if (logged !is WatchJs.MutationResult.Success) {
                failure = "completeSet(entry=$entryIndex, ${next.at}): " +
                    (logged as WatchJs.MutationResult.Failure).error
                break
            }
            mutations += loggedNanos
            storage = logged.storage
            sets++
            setsLogged++

            // The two-step protocol: a prompt set is NOT completed by completeSet. Skipping the
            // drain here would not just miss the prompt — the next completeSet would clear it,
            // and the loop would spin on a set that never completes.
            val modal = (WatchJs.getAmrapModal(storage) as? WatchJs.CallResult.Success)?.value
            if (modal != null) {
                val answers = defaultAnswers(modal)
                val p0 = CpuTime.nanos()
                val resolved = WatchJs.completeSetWithAmrap(
                    storage = storage,
                    deviceId = BENCH_DEVICE_ID,
                    completedReps = answers.reps,
                    completedRepsLeft = answers.repsLeft,
                    completedWeight = answers.weight,
                    completedRpe = answers.rpe,
                    userPromptedVarsJson = answers.userVarsJson,
                )
                val resolvedNanos = CpuTime.nanos() - p0
                if (resolved !is WatchJs.MutationResult.Success) {
                    failure = "completeSetWithAmrap: ${(resolved as WatchJs.MutationResult.Failure).error}"
                    break
                }
                mutations += resolvedNanos
                storage = resolved.storage
                promptsAnswered++
                // Only the first is kept: this is the "is a three-call prompt interaction
                // acceptable?" figure, and it is answered by what the user waits through once,
                // not by an average over a workout.
                if (promptInteractionNanos < 0) {
                    promptInteractionNanos = CpuTime.nanos() - interaction0
                }
            }
            sampleAnon()
        }

        if (failure == null) {
            val t0 = CpuTime.nanos()
            when (val finished = WatchJs.finishWorkout(storage, BENCH_DEVICE_ID)) {
                is WatchJs.MutationResult.Success -> {
                    finishNanos = CpuTime.nanos() - t0
                    storage = finished.storage
                }
                is WatchJs.MutationResult.Failure -> failure = "finishWorkout: ${finished.error}"
            }
        }
        sampleAnon()

        // --- the watch→phone put's own cost (ticket 06's note) ----------------------------
        // One org.json parse of whole storage plus a gzip, per mutation. It is off the engine
        // thread in production, so it never delays a tap — but it is per *set logged*, and at
        // 142KB that is not obviously free.
        val outbound = mutableListOf<Long>()
        var gzippedBytes = -1
        repeat(OUTBOUND_SAMPLES) { i ->
            val t0 = CpuTime.nanos()
            val payload = WatchOutboundStorageBuilder.build(base, BENCH_DEVICE_ID, i.toLong())
            outbound += CpuTime.nanos() - t0
            if (payload != null) gzippedBytes = payload.gzipped.size
        }
        sampleAnon()

        // --- malloc trend on a constant payload -------------------------------------------
        // The budget is that this does NOT rise. Anon RSS rising is expected and is libc arena
        // behaviour; JS-side allocated bytes rising across identical calls is a real leak.
        WatchJs.invalidateStorageCache()
        WatchJs.getProgress(base)
        val mallocBefore = LiftosaurEngine.mallocSize()
        repeat(MALLOC_TREND_CALLS) { WatchJs.getProgress(base) }
        val mallocAfter = LiftosaurEngine.mallocSize()
        sampleAnon()

        // The simulated workout is still sitting in the bundle's cache. Dropping it is what
        // makes this safe to run in a shipped build: the next repository call re-parses the
        // real storage it passes in, so the UI cannot be left showing a benchmark.
        WatchJs.invalidateStorageCache()

        val result = Result(
            storageBytes = base.size,
            usedRealStorage = true,
            coldStart = LiftosaurEngine.coldStart,
            warmReadMedianMs = CpuTime.medianMs(warmReads),
            warmReadMaxMs = CpuTime.maxMs(warmReads),
            mutationMedianMs = CpuTime.medianMs(mutations),
            mutationMaxMs = CpuTime.maxMs(mutations),
            promptInteractionMs = if (promptInteractionNanos < 0) -1.0 else CpuTime.msOf(promptInteractionNanos),
            finishWorkoutMs = if (finishNanos < 0) -1.0 else CpuTime.msOf(finishNanos),
            outboundBuildMedianMs = CpuTime.medianMs(outbound),
            gzippedBytes = gzippedBytes,
            sessionPeakAnonKb = peakAnon,
            sessionAnonGrowthKb = peakAnon - anonAtStart,
            mallocBeforeBytes = mallocBefore,
            mallocAfterBytes = mallocAfter,
            setsLogged = setsLogged,
            promptsAnswered = promptsAnswered,
            failure = failure,
        )
        report(result)
        return result
    }

    /**
     * What the prompt would submit if the user accepted every default.
     *
     * Goes through the real [promptFieldsFor]/[buildAnswers] path rather than reading the modal
     * directly, so the benchmark exercises the same argument shaping the UI does — including
     * the user-var JSON encoding, which is the fiddliest part of the call and the one most
     * likely to make the bundle take a different branch.
     */
    private fun defaultAnswers(modal: WatchAmrapModal): PromptAnswers =
        buildAnswers(promptFieldsFor(modal), emptyList())

    private fun report(r: Result) {
        Log.i(TAG, "=== ticket 07 pre-ship budgets (spec §4) ===")
        Log.i(TAG, "storage      ${r.storageBytes}B real, gzipped ${r.gzippedBytes}B")
        Log.i(TAG, "session      ${r.setsLogged} sets, ${r.promptsAnswered} prompts")
        line("cold start", r.coldStart.totalCpuMs, "ms", COLD_START_BUDGET_MS, r.coldStartOk)
        Log.i(TAG, "  init ${r.coldStart.initCpuMs}ms + first call ${r.coldStart.firstCallCpuMs}ms (${r.coldStart.method})")
        line("warm read", r.warmReadMedianMs, "ms", WARM_READ_BUDGET_MS, r.warmReadOk)
        Log.i(TAG, "  max ${r.warmReadMaxMs}ms over $WARM_READ_SAMPLES samples")
        line("mutation", r.mutationMedianMs, "ms", MUTATION_BUDGET_MS, r.mutationOk)
        Log.i(TAG, "  max ${r.mutationMaxMs}ms, finishWorkout ${r.finishWorkoutMs}ms")
        Log.i(TAG, "prompt       ${r.promptInteractionMs}ms CPU for the full three-call interaction")
        Log.i(TAG, "outbound     ${r.outboundBuildMedianMs}ms CPU per put (org.json parse + gzip)")
        line("engine anon", r.coldStart.initAnonKb.toDouble(), "KB", ENGINE_ANON_BUDGET_KB.toDouble(), r.engineAnonOk)
        line("session anon", r.sessionPeakAnonKb.toDouble(), "KB", SESSION_ANON_BUDGET_KB.toDouble(), r.sessionAnonOk)
        Log.i(TAG, "  grew ${r.sessionAnonGrowthKb}KB across the simulated session")
        Log.i(
            TAG,
            "malloc       ${r.mallocBeforeBytes} -> ${r.mallocAfterBytes} bytes over " +
                "$MALLOC_TREND_CALLS constant-payload calls  ${if (r.mallocTrendOk) "OK" else "RISING"}",
        )
        r.failure?.let { Log.e(TAG, "failure      $it") }

        // One machine-readable line, so the host script does not have to parse the pretty
        // ones above and drift from them.
        Log.i(
            TAG,
            "PRESHIP verdict=${if (r.allOk) "PASS" else "FAIL"} storageBytes=${r.storageBytes} " +
                "gzippedBytes=${r.gzippedBytes} coldStartMs=${r.coldStart.totalCpuMs} " +
                "warmReadMs=${r.warmReadMedianMs} mutationMs=${r.mutationMedianMs} " +
                "mutationMaxMs=${r.mutationMaxMs} promptMs=${r.promptInteractionMs} " +
                "finishMs=${r.finishWorkoutMs} outboundMs=${r.outboundBuildMedianMs} " +
                "engineAnonKb=${r.coldStart.initAnonKb} sessionAnonKb=${r.sessionPeakAnonKb} " +
                "mallocBefore=${r.mallocBeforeBytes} mallocAfter=${r.mallocAfterBytes} " +
                "sets=${r.setsLogged} prompts=${r.promptsAnswered} failure=${r.failure ?: "none"}",
        )
    }

    private fun line(label: String, value: Double, unit: String, budget: Double, ok: Boolean) {
        Log.i(TAG, "${label.padEnd(12)} $value$unit  (budget $budget$unit)  ${if (ok) "OK" else "OVER"}")
    }

    private fun empty(failure: String) = Result(
        storageBytes = 0,
        usedRealStorage = false,
        coldStart = LiftosaurEngine.coldStart,
        warmReadMedianMs = -1.0,
        warmReadMaxMs = -1.0,
        mutationMedianMs = -1.0,
        mutationMaxMs = -1.0,
        promptInteractionMs = -1.0,
        finishWorkoutMs = -1.0,
        outboundBuildMedianMs = -1.0,
        gzippedBytes = -1,
        sessionPeakAnonKb = -1,
        sessionAnonGrowthKb = -1,
        mallocBeforeBytes = -1,
        mallocAfterBytes = -1,
        setsLogged = 0,
        promptsAnswered = 0,
        failure = failure,
    ).also { Log.e(TAG, "PRESHIP verdict=FAIL failure=$failure") }
}
