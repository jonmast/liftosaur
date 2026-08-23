package com.liftosaur.wear.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The correctness tests spec §4 requires the implementation to carry.
 *
 * These run **on the watch**, against the real baked bundle in the real QuickJS runtime on the
 * real 32-bit ABI, because every behaviour under test is a property of that bundle rather than
 * of the Kotlin wrapper — a JVM test with a hand-written fake would assert only that the fake
 * matches my beliefs, which is exactly the failure mode these tests exist to catch.
 *
 *   ./gradlew :wear:connectedDebugAndroidTest
 *
 * The fixture is a real filtered 5/3/1 storage and **carries warmup sets**. That is
 * load-bearing: with no warmups the three index spaces coincide numerically and the
 * index-space bug is invisible (spec §2.3).
 */
@RunWith(AndroidJUnit4::class)
class CallSurfaceTest {

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        /** Storage with no active workout — the fixture as the phone would send it. */
        private lateinit var fixture: ByteArray

        /** Storage with an active workout, so the mutation surface has something to act on. */
        private lateinit var started: ByteArray

        private const val DEVICE_ID = "wear-testdev"

        /** The exercise these tests act on. Entry 0 of the fixture carries warmups + an AMRAP. */
        private const val ENTRY = 0

        @BeforeClass
        @JvmStatic
        fun initEngine() = runBlocking {
            withContext(EngineDispatcher.dispatcher) {
                LiftosaurEngine.initialize(context)
                fixture = context.assets.open("fixture-storage.json").use { it.readBytes() }

                // Every fixture handed to the bundle here is external storage the bundle has
                // not seen, so the cache must be dropped first or the second differing call
                // silently answers from the first.
                WatchJs.invalidateStorageCache()
                val result = WatchJs.startWorkout(fixture, DEVICE_ID)
                assertTrue("startWorkout failed: $result", result is WatchJs.MutationResult.Success)
                started = (result as WatchJs.MutationResult.Success).storage
            }
        }

        /** Runs a bundle call on the engine thread against storage the bundle has not seen. */
        private fun <T> onEngineFresh(block: () -> T): T = runBlocking {
            withContext(EngineDispatcher.dispatcher) {
                WatchJs.invalidateStorageCache()
                block()
            }
        }
    }

    private fun progressOf(storage: ByteArray): WatchWorkout =
        onEngineFresh { WatchJs.getProgress(storage) }.let {
            assertTrue("getProgress failed: $it", it is WatchJs.CallResult.Success)
            assertNotNull("expected an active workout", (it as WatchJs.CallResult.Success).value)
            it.value!!
        }

    // -------------------------------------------------------------------------------------
    // Test 1 — index-space regression (spec §4.1)
    // -------------------------------------------------------------------------------------

    /**
     * The fixture must carry warmups, or the rest of this file proves nothing.
     *
     * Asserted explicitly rather than assumed: if a future fixture regeneration drops warmup
     * sets, the index-space tests below would still pass while testing nothing at all.
     */
    @Test
    fun fixtureCarriesWarmupSetsAndRestartingIndices() {
        val entry = progressOf(started).exercises[ENTRY]
        assertTrue("fixture has no warmup sets — index-space tests would be vacuous", entry.warmupCount > 0)

        // The heart of the trap: set.index restarts at 0 at the warmup/work boundary, so it
        // does not identify a set.
        val indices = entry.sets.map { it.index }
        assertEquals("warmup indices should restart", 0, indices[0])
        assertEquals("first work set index should restart to 0", 0, indices[entry.warmupCount])
        assertTrue(
            "set.index must be ambiguous across the boundary, else the spaces coincide",
            indices.count { it == 0 } > 1,
        )
    }

    /**
     * Completing by array position hits the intended set; completing by `set.index` hits a
     * different one and still reports success.
     *
     * This is the finding that most endangers the port, so it is asserted in both directions —
     * that the right call works, and that the plausible wrong call silently misfires.
     */
    @Test
    fun completeSetUsesArrayPositionNotSetIndex() {
        val entry = progressOf(started).exercises[ENTRY]

        // A plain work set: no prompt, so "did it complete?" has an unambiguous answer. Being
        // a work set, its display index is shifted from its position by the warmup count.
        val target = entry.positionedSets().last { !it.set.isWarmup && !it.set.isAmrap }
        val setIndexValue = target.set.index
        assertTrue(
            "the display index must differ from the position for this test to bite",
            setIndexValue != target.at.pos,
        )

        // Correct: the flattened array position.
        val right = onEngineFresh { WatchJs.completeSet(started, DEVICE_ID, ENTRY, target.at) }
        assertTrue("completeSet by position failed: $right", right is WatchJs.MutationResult.Success)
        val rightEntry = progressOf((right as WatchJs.MutationResult.Success).storage).exercises[ENTRY]
        assertTrue(
            "the position-keyed call must complete the intended set",
            rightEntry.sets[target.at.pos].isCompleted,
        )

        // Wrong: set.index reinterpreted as a position — the mistake the value class prevents.
        // The bundle accepts it happily and reports success.
        val wrongAt = entry.positionedSets()[setIndexValue].at
        val wrong = onEngineFresh { WatchJs.completeSet(started, DEVICE_ID, ENTRY, wrongAt) }
        assertTrue("the set.index-keyed call still reports success: $wrong", wrong is WatchJs.MutationResult.Success)

        val wrongEntry = progressOf((wrong as WatchJs.MutationResult.Success).storage).exercises[ENTRY]
        assertTrue(
            "the set.index-keyed call must have completed a DIFFERENT set — that is the bug",
            wrongEntry.sets[setIndexValue].isCompleted,
        )
        assertFalse(
            "and must NOT have completed the intended one",
            wrongEntry.sets[target.at.pos].isCompleted,
        )
    }

    // -------------------------------------------------------------------------------------
    // Test 2 — prompt ordering (spec §4.2)
    // -------------------------------------------------------------------------------------

    /**
     * `completeSet` on a prompt set returns success while leaving the set untouched;
     * `completeSetWithAmrap` is what actually completes it.
     *
     * A port that trusts `success:true` from `completeSet` shows a set that never completes.
     */
    @Test
    fun promptSetIsCompletedOnlyByCompleteSetWithAmrap() {
        val entry = progressOf(started).exercises[ENTRY]
        val amrap = entry.positionedSets().first { it.set.isAmrap }

        val stepOne = onEngineFresh { WatchJs.completeSet(started, DEVICE_ID, ENTRY, amrap.at) }
        assertTrue("completeSet failed: $stepOne", stepOne is WatchJs.MutationResult.Success)
        val afterCompleteSet = (stepOne as WatchJs.MutationResult.Success).storage

        // Success, but the set is NOT completed.
        assertFalse(
            "completeSet must leave a prompt-requiring set untouched",
            progressOf(afterCompleteSet).exercises[ENTRY].sets[amrap.at.pos].isCompleted,
        )

        // And a modal is now pending.
        val modal = onEngineFresh { WatchJs.getAmrapModal(afterCompleteSet) }
        assertTrue("getAmrapModal failed: $modal", modal is WatchJs.CallResult.Success)
        val pending = (modal as WatchJs.CallResult.Success).value
        assertNotNull("a modal must be pending after completeSet on a prompt set", pending)

        // getAmrapModal's setIndex is work-set space; converting it must land on the position
        // we asked for. Feeding it back raw would target a different set.
        val entryAfter = progressOf(afterCompleteSet).exercises[pending!!.entryIndex]
        assertEquals(
            "work-set index must convert back to the position we completed",
            amrap.at.pos,
            entryAfter.workSetPosition(pending.setIndex).pos,
        )
        assertTrue(
            "and the raw work-set index must NOT equal the position, or the conversion is untested",
            pending.setIndex != amrap.at.pos,
        )

        // Step two completes it.
        val stepTwo = onEngineFresh {
            WatchJs.completeSetWithAmrap(afterCompleteSet, DEVICE_ID, completedReps = 8)
        }
        assertTrue("completeSetWithAmrap failed: $stepTwo", stepTwo is WatchJs.MutationResult.Success)
        val done = (stepTwo as WatchJs.MutationResult.Success).storage

        val completedSet = progressOf(done).exercises[ENTRY].sets[amrap.at.pos]
        assertTrue("completeSetWithAmrap must complete the set", completedSet.isCompleted)
        assertEquals("with the reps the user entered", 8, completedSet.completedReps)

        // Modal gone afterwards.
        val after = onEngineFresh { WatchJs.getAmrapModal(done) }
        assertNull(
            "the modal must be gone after it is resolved",
            (after as WatchJs.CallResult.Success).value,
        )
    }

    /**
     * A non-prompt set completes in one step and raises no modal — the other half of the
     * ordering rule, and what makes the polling loop terminate.
     */
    @Test
    fun ordinarySetCompletesWithoutAModal() {
        val entry = progressOf(started).exercises[ENTRY]
        val plain = entry.positionedSets().first { !it.set.isWarmup && !it.set.isAmrap }

        val result = onEngineFresh { WatchJs.completeSet(started, DEVICE_ID, ENTRY, plain.at) }
        assertTrue("completeSet failed: $result", result is WatchJs.MutationResult.Success)
        val storage = (result as WatchJs.MutationResult.Success).storage

        assertTrue(
            "an ordinary set must complete in one step",
            progressOf(storage).exercises[ENTRY].sets[plain.at.pos].isCompleted,
        )
        assertNull(
            "and must raise no modal",
            (onEngineFresh { WatchJs.getAmrapModal(storage) } as WatchJs.CallResult.Success).value,
        )
    }

    // -------------------------------------------------------------------------------------
    // Test 6 — envelope shapes (spec §4.6)
    // -------------------------------------------------------------------------------------

    /** `getLatestMigrationVersion` returns a bare string — not JSON, not an envelope. */
    @Test
    fun getLatestMigrationVersionReturnsABareString() {
        val result = onEngineFresh { WatchJs.getLatestMigrationVersion() }
        assertTrue("failed: $result", result is WatchJs.CallResult.Success)
        val version = (result as WatchJs.CallResult.Success).value
        assertTrue("expected a bare version string, got '$version'", version.isNotEmpty())
        assertFalse("must not be an envelope", version.startsWith("{"))
        assertTrue("expected digits, got '$version'", version.all { it.isDigit() })
    }

    /** `runMigrations` on current-version storage: `data: null` means "none needed", a success. */
    @Test
    fun runMigrationsReturnsNullDataWhenNoneNeeded() {
        val result = onEngineFresh { WatchJs.runMigrations(fixture) }
        assertTrue("runMigrations failed: $result", result is WatchJs.CallResult.Success)
        assertNull(
            "null data means 'no migration needed' and must not read as an error",
            (result as WatchJs.CallResult.Success).value,
        )
    }

    /** `prepareSync` returns a raw `IStorageUpdate2` with no envelope around it. */
    @Test
    fun prepareSyncReturnsRawUpdateWithoutAnEnvelope() {
        val result = onEngineFresh { WatchJs.prepareSync(fixture, fixture, DEVICE_ID) }
        assertTrue("prepareSync failed: $result", result is WatchJs.CallResult.Success)
        val json = (result as WatchJs.CallResult.Success).value.decodeToString()
        assertFalse("prepareSync must not be enveloped", json.contains("\"success\""))
        assertTrue("expected an IStorageUpdate2 document, got: ${json.take(120)}", json.startsWith("{"))
    }

    /** `mergeStorage` returns a raw `IStorage` with no envelope around it. */
    @Test
    fun mergeStorageReturnsRawStorageWithoutAnEnvelope() {
        val result = onEngineFresh { WatchJs.mergeStorage(fixture, fixture, DEVICE_ID) }
        assertTrue("mergeStorage failed: $result", result is WatchJs.CallResult.Success)
        val json = (result as WatchJs.CallResult.Success).value.decodeToString()
        assertFalse("mergeStorage must not be enveloped", json.contains("\"success\":true"))
        assertTrue("merging storage with itself must yield storage", json.contains("\"settings\""))
        assertTrue("and must carry the vector clock", json.contains("\"_versions\""))
    }

    /**
     * `{"success":true}` with no `data` key means "nothing to show", not an error.
     *
     * `JSON.stringify` drops `data: undefined` entirely, so the key is absent rather than
     * null — a decoder that treats a missing key as a failure rejects a perfectly good result.
     */
    @Test
    fun successWithNoDataKeyMeansEmptyNotError() {
        // The unstarted fixture has no progress and no modal: both take this path.
        val progress = onEngineFresh { WatchJs.getProgress(fixture) }
        assertTrue("getProgress must succeed with no workout: $progress", progress is WatchJs.CallResult.Success)
        assertNull("no active workout means null, not an error", (progress as WatchJs.CallResult.Success).value)

        val modal = onEngineFresh { WatchJs.getAmrapModal(fixture) }
        assertTrue("getAmrapModal must succeed with no modal: $modal", modal is WatchJs.CallResult.Success)
        assertNull("no pending modal means null, not an error", (modal as WatchJs.CallResult.Success).value)
    }

    /** The remaining reads, so all 15 methods are exercised somewhere in this file. */
    @Test
    fun readsDecodeIntoTypedModels() {
        val hasProgram = onEngineFresh { WatchJs.hasProgram(fixture) }
        assertTrue("hasProgram failed: $hasProgram", hasProgram is WatchJs.CallResult.Success)
        assertTrue("the fixture has a current program", (hasProgram as WatchJs.CallResult.Success).value)

        val next = onEngineFresh { WatchJs.getNextHistoryRecord(fixture) }
        assertTrue("getNextHistoryRecord failed: $next", next is WatchJs.CallResult.Success)
        val workout = (next as WatchJs.CallResult.Success).value
        assertNotNull("expected a next workout", workout)
        assertTrue("expected exercises", workout!!.exercises.isNotEmpty())
        assertTrue("expected a program name", workout.programName.isNotEmpty())
        assertTrue("expected sets on the first exercise", workout.exercises[0].sets.isNotEmpty())
    }

    /** The mutations not covered by the index-space and prompt tests. */
    @Test
    fun remainingMutationsSucceed() {
        val moved = onEngineFresh { WatchJs.setCurrentEntryIndex(started, DEVICE_ID, 1) }
        assertTrue("setCurrentEntryIndex failed: $moved", moved is WatchJs.MutationResult.Success)
        assertEquals(
            "the shown exercise must move",
            1,
            progressOf((moved as WatchJs.MutationResult.Success).storage).currentEntryIndex,
        )

        val finished = onEngineFresh { WatchJs.finishWorkout(started, DEVICE_ID) }
        assertTrue("finishWorkout failed: $finished", finished is WatchJs.MutationResult.Success)
        assertNull(
            "finishing must clear progress",
            (onEngineFresh {
                WatchJs.getProgress((finished as WatchJs.MutationResult.Success).storage)
            } as WatchJs.CallResult.Success).value,
        )

        val discarded = onEngineFresh { WatchJs.discardWorkout(started, DEVICE_ID) }
        assertTrue("discardWorkout failed: $discarded", discarded is WatchJs.MutationResult.Success)
        assertNull(
            "discarding must clear progress",
            (onEngineFresh {
                WatchJs.getProgress((discarded as WatchJs.MutationResult.Success).storage)
            } as WatchJs.CallResult.Success).value,
        )
    }

    // -------------------------------------------------------------------------------------
    // Test 7 — NUL round-trip (spec §4.7)
    // -------------------------------------------------------------------------------------

    /**
     * Content adjacent to `\u0000` survives the byte[] boundary byte-exact.
     *
     * This is the whole justification for marshalling as `byte[]` rather than `jstring`: ART's
     * jstring path substitutes U+FFFD outbound and **truncates** inbound at the NUL, so `a\u0000b`
     * comes back as `a`. It is latent today (the storage serializer escapes NUL as `\u0000`),
     * but that is a property of the current serializer, not a guarantee — and the failure is
     * silent.
     */
    @Test
    fun nulSurvivesTheByteArrayBoundary() {
        val payload = "a\u0000b — \"quoted\" 💪 tail".toByteArray(Charsets.UTF_8)
        assertTrue("the probe must actually contain a NUL", payload.contains(0))

        val echoed = runBlocking {
            withContext(EngineDispatcher.dispatcher) { LiftosaurEngine.echo(payload) }
        }

        assertArrayEquals(
            "bytes around a NUL must survive the boundary unchanged",
            payload,
            echoed,
        )
        assertTrue("the NUL itself must survive, not terminate", echoed.contains(0))
        assertEquals("nothing may be truncated at the NUL", payload.size, echoed.size)
    }

    /**
     * Storage carrying an escaped NUL round-trips through a real call without corruption.
     *
     * Injected into the program name — a real, user-authored string field that survives the
     * filter — rather than a synthetic key, so the bundle actually carries it through parse,
     * merge and re-serialize instead of dropping it as unknown.
     */
    @Test
    fun storageContainingEscapedNulRoundTripsThroughACall() {
        val original = "\"name\":\"5/3/1 For Beginners\""
        val nulBearing = "\"name\":\"a\\u0000b 5/3/1 For Beginners\""
        val text = fixture.decodeToString()
        assertTrue(
            "the fixture must contain the field being tampered with, or this test is vacuous",
            text.contains(original),
        )
        val tampered = text.replace(original, nulBearing).encodeToByteArray()

        val result = onEngineFresh { WatchJs.mergeStorage(tampered, tampered, DEVICE_ID) }
        assertTrue("mergeStorage on NUL-carrying storage failed: $result", result is WatchJs.CallResult.Success)
        val merged = (result as WatchJs.CallResult.Success).value.decodeToString()
        assertTrue("the escaped NUL must survive the round trip", merged.contains("\\u0000"))
        assertTrue("with its surrounding content intact", merged.contains("a\\u0000b 5/3/1 For Beginners"))
    }
}
