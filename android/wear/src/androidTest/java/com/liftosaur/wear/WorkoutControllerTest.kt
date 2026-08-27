package com.liftosaur.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liftosaur.wear.engine.EngineDispatcher
import com.liftosaur.wear.engine.LiftosaurEngine
import com.liftosaur.wear.engine.WatchStorageRepository
import com.liftosaur.wear.sync.PhoneStorageSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The logging loop as the UI actually drives it (spec §2.3, §2.7).
 *
 * [WorkoutController] is where the two-step prompt protocol lives, and the protocol's failure
 * mode is silent: `completeSet` reports success on a prompt set it did not complete, and a
 * second `completeSet` *clears* the pending modal, discarding the user's answer with no error
 * anywhere. Nothing below can be checked by looking at the screen — screenshots of this app
 * are impossible (spec §3.5) — so the state flow is asserted directly.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutControllerTest {

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun initEngine() = runBlocking {
            withContext(EngineDispatcher.dispatcher) { LiftosaurEngine.initialize(context) }
        }
    }

    private lateinit var repo: WatchStorageRepository
    private lateinit var controller: WorkoutController

    @Before
    fun setUp() {
        File(context.filesDir, "storage.json").delete()
        repo = WatchStorageRepository(context)
        controller = WorkoutController(context, repo, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    /** Waits for the controller to settle, so assertions never race a mutation in flight. */
    private fun settled(): WorkoutUiState = runBlocking {
        withTimeout(30_000) {
            controller.state.first { !it.busy && !it.loading }
        }
    }

    private fun seed(asset: String) {
        repo.setExternal(context.assets.open(asset).use { it.readBytes() })
        controller.start()
        settled()
    }

    private fun startWorkout(): WorkoutUiState {
        controller.startWorkout()
        val state = settled()
        assertTrue("startWorkout failed: ${state.error}", state.isWorkoutActive)
        return state
    }

    // -------------------------------------------------------------------------------------
    // The full loop
    // -------------------------------------------------------------------------------------

    /**
     * Every set in an exercise logs, in order, including the warmups.
     *
     * The warmups are the point: they occupy the array positions ahead of the work sets, so an
     * implementation that keyed off `WatchSet.index` (which restarts at 0 at the boundary)
     * would log the wrong ones while reporting success.
     */
    @Test
    fun everySetInAnExerciseLogsInOrder() {
        seed("fixture-storage.json")
        var state = startWorkout()

        val entryIndex = state.progress!!.exercises.indexOfFirst { entry ->
            entry.sets.any { it.isWarmup } && entry.sets.none { it.isAmrap }
        }.takeIf { it >= 0 } ?: state.progress!!.exercises.indexOfFirst { entry ->
            entry.sets.any { it.isWarmup }
        }
        assertTrue("the fixture must carry an exercise with warmups", entryIndex >= 0)

        val total = state.progress!!.exercises[entryIndex].sets.size
        var logged = 0
        while (logged < total) {
            val entry = state.progress!!.exercises[entryIndex]
            val next = entry.nextUnfinished() ?: break

            // A prompt set is completed by the prompt, not by another completeSet — that is
            // the next test's subject, so stop here rather than corrupting this one.
            controller.logSet(entryIndex, next.at)
            state = settled()
            if (state.prompt != null) break

            val after = state.progress!!.exercises[entryIndex]
            assertTrue(
                "set at ${next.at} must be completed after logging it",
                after.sets[next.at.pos].isCompleted,
            )
            logged += 1
            assertEquals(
                "exactly one set may complete per tap",
                logged,
                after.sets.count { it.isCompleted },
            )
        }
        assertTrue("at least the warmups and a work set should have logged", logged >= 2)
    }

    /**
     * A prompt set: the tap opens the prompt, and only the prompt completes it.
     *
     * This is the ticket's central acceptance. `completeSet` returns success and the state
     * must *not* show the set as done — if it did, the user would see a logged set that
     * storage disagrees about, and the phone would eventually overwrite it.
     */
    @Test
    fun aPromptSetOpensThePromptAndCompletesOnlyThroughIt() {
        seed("fixture-storage.json")
        var state = startWorkout()

        val entryIndex = state.progress!!.exercises.indexOfFirst { e -> e.sets.any { it.isAmrap } }
        assertTrue("the fixture must carry a prompt-requiring set", entryIndex >= 0)
        val amrap = state.progress!!.exercises[entryIndex].positionedSets().first { it.set.isAmrap }

        // Log everything before it, so the prompt set is genuinely next.
        while (true) {
            val next = state.progress!!.exercises[entryIndex].nextUnfinished() ?: break
            if (next.at.pos >= amrap.at.pos) break
            controller.logSet(entryIndex, next.at)
            state = settled()
        }

        controller.logSet(entryIndex, amrap.at)
        state = settled()

        assertNotNull("logging a prompt set must raise the prompt", state.prompt)
        assertFalse(
            "and must NOT complete the set — completeSet reports success without doing so",
            state.progress!!.exercises[entryIndex].sets[amrap.at.pos].isCompleted,
        )

        val fields = promptFieldsFor(state.prompt!!)
        assertTrue("the prompt must have something to ask", fields.isNotEmpty())
        controller.submitPrompt(buildAnswers(fields, fields.map { it.initialIndex }))
        state = settled()

        assertNull("the prompt must close once answered", state.prompt)
        assertTrue(
            "and the set must now be completed",
            state.progress!!.exercises[entryIndex].sets[amrap.at.pos].isCompleted,
        )
    }

    /** Cancelling the prompt closes it and leaves the set unlogged. */
    @Test
    fun cancellingThePromptLeavesTheSetUnlogged() {
        seed("fixture-worstcase.json")
        var state = startWorkout()

        val entryIndex = state.progress!!.exercises.indexOfFirst { e -> e.sets.any { it.isAmrap } }
        val amrap = state.progress!!.exercises[entryIndex].positionedSets().first { it.set.isAmrap }
        while (true) {
            val next = state.progress!!.exercises[entryIndex].nextUnfinished() ?: break
            if (next.at.pos >= amrap.at.pos) break
            controller.logSet(entryIndex, next.at)
            state = settled()
        }

        controller.logSet(entryIndex, amrap.at)
        state = settled()
        assertNotNull("expected a prompt", state.prompt)

        controller.cancelPrompt()
        state = settled()

        assertNull("cancelling must close the prompt", state.prompt)
        assertFalse(
            "and must leave the set unlogged",
            state.progress!!.exercises[entryIndex].sets[amrap.at.pos].isCompleted,
        )
    }

    /**
     * A pending prompt blocks further logging, instead of being silently thrown away.
     *
     * This is the port's nastiest trap: `completeSet` *clears* a pending modal and returns
     * `success:true`. So a stray "Log" tap while the prompt is up — reachable by swiping the
     * prompt away, which Wear's swipe-to-dismiss makes a one-gesture accident — would discard
     * the set the user was answering for, with no error anywhere and a green dot to match.
     */
    @Test
    fun loggingIsRefusedWhileAPromptIsPending() {
        seed("fixture-storage.json")
        var state = startWorkout()

        val entryIndex = state.progress!!.exercises.indexOfFirst { e -> e.sets.any { it.isAmrap } }
        val amrap = state.progress!!.exercises[entryIndex].positionedSets().first { it.set.isAmrap }
        while (true) {
            val next = state.progress!!.exercises[entryIndex].nextUnfinished() ?: break
            if (next.at.pos >= amrap.at.pos) break
            controller.logSet(entryIndex, next.at)
            state = settled()
        }

        controller.logSet(entryIndex, amrap.at)
        state = settled()
        assertNotNull("expected a prompt", state.prompt)
        val completedBefore = state.progress!!.completedSetCount()

        // The stray tap must target a *different* set to reproduce the trap: re-tapping the
        // same one clears and immediately re-raises the modal, which looks like a no-op and
        // would make this test pass with the guard removed. Verified against the real bundle.
        val other = state.progress!!.exercises[entryIndex]
            .positionedSets()
            .first { !it.set.isCompleted && it.at.pos != amrap.at.pos }
        controller.logSet(entryIndex, other.at)
        state = settled()

        assertNotNull(
            "the prompt must survive a stray log tap — completeSet would silently clear it",
            state.prompt,
        )
        assertEquals(
            "and nothing may have been logged",
            completedBefore,
            state.progress!!.completedSetCount(),
        )

        // And the prompt is still answerable afterwards.
        val fields = promptFieldsFor(state.prompt!!)
        controller.submitPrompt(buildAnswers(fields, fields.map { it.initialIndex }))
        state = settled()
        assertNull("the prompt must still resolve", state.prompt)
        assertTrue(
            "and the set finally completes",
            state.progress!!.exercises[entryIndex].sets[amrap.at.pos].isCompleted,
        )
    }

    // -------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------

    /** Finishing ends the workout; the UI's "is a workout active" flag is what routes Home. */
    @Test
    fun finishingEndsTheWorkout() {
        seed("fixture-storage.json")
        startWorkout()

        controller.finishWorkout()
        val state = settled()

        assertFalse("finishing must end the workout", state.isWorkoutActive)
        assertNull("and clear progress", state.progress)
        assertNotNull("while still offering the next workout", state.next)
    }

    @Test
    fun discardingEndsTheWorkout() {
        seed("fixture-storage.json")
        startWorkout()

        controller.discardWorkout()
        val state = settled()

        assertFalse("discarding must end the workout", state.isWorkoutActive)
        assertNull("and clear progress", state.progress)
    }

    /** Navigating between exercises writes through to storage, so the phone sees the same one. */
    @Test
    fun openingAnExerciseMovesStoragesCurrentEntryIndex() {
        seed("fixture-storage.json")
        val state = startWorkout()
        assertTrue("need at least two exercises", state.progress!!.exercises.size >= 2)

        controller.setCurrentEntryIndex(1)
        assertEquals("storage must follow UI navigation", 1, settled().progress!!.currentEntryIndex)

        controller.setCurrentEntryIndex(0)
        assertEquals(0, settled().progress!!.currentEntryIndex)
    }

    /**
     * A failed mutation surfaces as an error and leaves storage untouched.
     *
     * Forced by asking the bundle to complete a set in an entry that does not exist, which is
     * the shape of every real failure: an error envelope with no storage in it. The assertion
     * that matters is the *second* one — a UI that showed the set as logged anyway would be
     * lying about data the phone will later contradict.
     */
    @Test
    fun aFailedMutationShowsAnErrorAndChangesNothing() {
        seed("fixture-storage.json")
        val before = startWorkout()
        val completedBefore = before.progress!!.completedSetCount()
        val storageBefore = repo.storage.value!!.decodeToString()

        val at = before.progress!!.exercises[0].positionedSets().first().at
        controller.logSet(entryIndex = 99, at = at)
        val after = settled()

        assertNotNull("a failed mutation must surface an error", after.error)
        assertEquals(
            "and must not complete anything",
            completedBefore,
            after.progress!!.completedSetCount(),
        )
        assertEquals(
            "persisted storage must be byte-identical after a failure",
            storageBefore,
            repo.storage.value!!.decodeToString(),
        )
    }

    /**
     * A second tap while a mutation is in flight is dropped, not queued.
     *
     * Both taps would otherwise run against the same pre-mutation storage and the second would
     * overwrite the first's result — losing a set while reporting success twice. This is what
     * makes "no out-of-order logging" structural rather than a UI convention.
     */
    @Test
    fun concurrentTapsCannotDoubleLog() {
        seed("fixture-storage.json")
        var state = startWorkout()

        val entry = state.progress!!.exercises[0]
        val first = entry.positionedSets()[0].at
        val second = entry.positionedSets()[1].at

        controller.logSet(0, first)
        controller.logSet(0, second)
        state = settled()

        assertEquals(
            "the second tap must be dropped while the first is in flight",
            1,
            state.progress!!.exercises[0].sets.count { it.isCompleted },
        )
        assertTrue(
            "and the one that landed must be the first",
            state.progress!!.exercises[0].sets[first.pos].isCompleted,
        )
    }

    // -------------------------------------------------------------------------------------
    // The phone ending the workout (spec §2.5, ticket 06)
    // -------------------------------------------------------------------------------------

    /**
     * Finish or discard on the phone sends the wrist back Home, without any JS running.
     *
     * The merge that follows would usually clear `progress` and produce the same navigation —
     * but only if it succeeds. A merge that fails keeps local storage on purpose (it is
     * protecting sets logged on the wrist), and without this edge the watch would sit on a
     * workout the phone has already finished.
     */
    @Test
    fun thePhoneEndingTheWorkoutBumpsTheEndedNonce() {
        PhoneStorageSync.publishPhoneActiveWorkoutStartTime(null)
        seed("fixture-storage.json")
        startWorkout()
        val before = controller.state.value.endedNonce

        PhoneStorageSync.publishPhoneActiveWorkoutStartTime(1_700_000_000_000)
        PhoneStorageSync.publishPhoneActiveWorkoutStartTime(null)

        val ended = runBlocking {
            withTimeout(10_000) { controller.state.first { it.endedNonce > before } }
        }
        assertEquals(before + 1, ended.endedNonce)
    }

    /**
     * A workout started on the wrist is not cancelled by the phone's silence.
     *
     * The phone's header says "no workout" for a full round trip after the watch starts one —
     * it does not know yet. Treating that as state rather than as an edge would throw the user
     * out of the workout they just started, every time.
     */
    @Test
    fun aPhoneHeaderThatWasAlreadyEmptyDoesNotEndAWatchStartedWorkout() {
        PhoneStorageSync.publishPhoneActiveWorkoutStartTime(null)
        seed("fixture-storage.json")
        startWorkout()
        val before = controller.state.value.endedNonce

        PhoneStorageSync.publishPhoneActiveWorkoutStartTime(null)
        Thread.sleep(500)

        assertEquals(
            "null → null is not an edge and must not end the workout",
            before,
            controller.state.value.endedNonce,
        )
        assertTrue("the watch-started workout must still be active", controller.state.value.isWorkoutActive)
    }
}
