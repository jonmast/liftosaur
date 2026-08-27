package com.liftosaur.wear.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
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
 * The state-ownership half of spec §2.4 and §4: the cache invariant, mutation atomicity, and
 * survival across process death.
 *
 * Like [CallSurfaceTest] these run on the watch against the real bundle, because the trap
 * under test — the bundle's content-blind module-scope cache — only exists in the real thing.
 */
@RunWith(AndroidJUnit4::class)
class WatchStorageRepositoryTest {

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var fixture: ByteArray
        private lateinit var started: ByteArray

        @BeforeClass
        @JvmStatic
        fun initEngine() = runBlocking {
            withContext(EngineDispatcher.dispatcher) {
                LiftosaurEngine.initialize(context)
                fixture = context.assets.open("fixture-storage.json").use { it.readBytes() }
                WatchJs.invalidateStorageCache()
                started = (WatchJs.startWorkout(fixture, "wear-fixture") as WatchJs.MutationResult.Success).storage
            }
        }
    }

    private lateinit var repo: WatchStorageRepository

    @Before
    fun setUp() {
        File(context.filesDir, "storage.json").delete()
        repo = WatchStorageRepository(context)
    }

    // -------------------------------------------------------------------------------------
    // Test 3 — the cacheDirty invariant (spec §4.3)
    // -------------------------------------------------------------------------------------

    /**
     * Without invalidation, injecting external storage returns the *previous* storage's answer.
     *
     * This asserts the bug the invariant exists to prevent, so that the next test is
     * demonstrably testing something. The bundle's `cachedStorage` is not keyed by content:
     * `parseStorageSync` short-circuits before `JSON.parse`, so the second, different storage
     * is never looked at.
     */
    @Test
    fun withoutInvalidationTheBundleAnswersFromStaleStorage() = runBlocking {
        // Prime the bundle's cache with the started workout.
        repo.setExternal(started)
        val primed = repo.read { WatchJs.getProgress(it) }
        assertNotNull(
            "precondition: the started fixture has a workout",
            (primed as WatchJs.CallResult.Success).value,
        )

        // Now inject the *unstarted* storage while bypassing invalidation.
        repo.setExternalWithoutInvalidating(fixture)
        val stale = repo.read { WatchJs.getProgress(it) }

        assertNotNull(
            "without invalidation the bundle must still answer from the FIRST storage — " +
                "this is the trap, and it reports no error",
            (stale as WatchJs.CallResult.Success).value,
        )
    }

    /** With the invariant, the same injection is seen immediately. */
    @Test
    fun externalWritesAreSeenBecauseTheyInvalidateTheCache() = runBlocking {
        repo.setExternal(started)
        assertNotNull(
            "precondition: the started fixture has a workout",
            (repo.read { WatchJs.getProgress(it) } as WatchJs.CallResult.Success).value,
        )

        repo.setExternal(fixture)
        assertNull(
            "an external write must be visible to the very next call",
            (repo.read { WatchJs.getProgress(it) } as WatchJs.CallResult.Success).value,
        )
    }

    /**
     * The flag is checked-and-cleared, not left set.
     *
     * Clearing matters for speed rather than correctness: `invalidateStorageCache` also drops
     * the evaluated-program cache, and re-evaluating Liftoscript costs ~150ms. Leaving it set
     * would pay that on every call.
     */
    @Test
    fun theDirtyFlagIsClearedAfterOneCall() = runBlocking {
        repo.setExternal(started)
        assertTrue("an external write must mark the cache dirty", repo.isCacheDirty())

        repo.read { WatchJs.getProgress(it) }
        assertFalse("the flag must be cleared by the call that honours it", repo.isCacheDirty())
    }

    /** A JS-originated write must NOT mark the cache dirty — the bundle already has it. */
    @Test
    fun mutationsDoNotMarkTheCacheDirty() = runBlocking {
        repo.setExternal(started)
        repo.read { WatchJs.getProgress(it) }
        assertFalse("precondition", repo.isCacheDirty())

        val result = repo.mutate { storage, deviceId ->
            WatchJs.setCurrentEntryIndex(storage, deviceId, 1)
        }
        assertTrue("mutation failed: $result", result is WatchJs.MutationResult.Success)
        assertFalse(
            "a mutation's own output must not invalidate the cache — that would pay a full " +
                "re-parse and Liftoscript re-evaluation on every set logged",
            repo.isCacheDirty(),
        )

        // And the result is still correct without invalidation.
        val progress = repo.read { WatchJs.getProgress(it) }
        assertEquals(
            "the mutation must be visible to the next read",
            1,
            (progress as WatchJs.CallResult.Success).value!!.currentEntryIndex,
        )
    }

    // -------------------------------------------------------------------------------------
    // Failed mutations leave storage untouched (spec §2.3)
    // -------------------------------------------------------------------------------------

    @Test
    fun aFailedMutationLeavesMemoryAndDiskUntouched() = runBlocking {
        // The unstarted fixture has no active workout, so completeSet must fail.
        repo.setExternal(fixture)
        val before = repo.storage.value!!
        val onDiskBefore = File(context.filesDir, "storage.json").readBytes()

        // finishWorkout with no active workout: a real, reachable failure that needs no
        // synthetic index to provoke.
        val result = repo.mutate { storage, deviceId -> WatchJs.finishWorkout(storage, deviceId) }

        assertTrue("expected a failure, got: $result", result is WatchJs.MutationResult.Failure)
        assertFalse(
            "a JS-level failure is not an engine-level one",
            (result as WatchJs.MutationResult.Failure).engineLevel,
        )
        assertArrayEquals("memory must be untouched after a failed mutation", before, repo.storage.value)
        assertArrayEquals(
            "disk must be untouched after a failed mutation",
            onDiskBefore,
            File(context.filesDir, "storage.json").readBytes(),
        )
    }

    // -------------------------------------------------------------------------------------
    // Test 8 — survival across process death
    // -------------------------------------------------------------------------------------

    /**
     * A relaunch loads the last-persisted storage and treats it as external.
     *
     * `cacheDirty` on load is not belt-and-braces: the engine in the new process has evaluated
     * the bundle but never seen this storage, and — worse — a repository re-created inside a
     * *surviving* process would inherit a cache primed with someone else's storage.
     */
    @Test
    fun storageSurvivesProcessDeathAndIsTreatedAsExternal() = runBlocking {
        repo.setExternal(started)
        val persisted = repo.storage.value!!

        // A fresh repository over the same filesDir is what a relaunch produces.
        val relaunched = WatchStorageRepository(context)
        assertNull("a fresh repository starts empty", relaunched.storage.value)

        assertTrue("load must find the persisted storage", relaunched.load())
        assertArrayEquals(
            "the relaunched process must see the last-persisted storage",
            persisted,
            relaunched.storage.value,
        )
        assertTrue("storage loaded from disk is an external write", relaunched.isCacheDirty())

        // And it is actually usable, not just present.
        assertNotNull(
            "the reloaded storage must answer reads correctly",
            (relaunched.read { WatchJs.getProgress(it) } as WatchJs.CallResult.Success).value,
        )
    }

    @Test
    fun loadReportsFalseWhenNothingHasBeenPersisted() {
        // The un-paired first-run state: no storage yet is not an error.
        assertFalse("nothing persisted yet", WatchStorageRepository(context).load())
    }

    @Test
    fun readsAndMutationsFailCleanlyBeforeAnyStorageExists() = runBlocking {
        val read = repo.read { WatchJs.getProgress(it) }
        assertTrue("expected a clean failure, got: $read", read is WatchJs.CallResult.Failure)

        val mutation = repo.mutate { storage, deviceId -> WatchJs.finishWorkout(storage, deviceId) }
        assertTrue("expected a clean failure, got: $mutation", mutation is WatchJs.MutationResult.Failure)
    }

    // -------------------------------------------------------------------------------------
    // The watch→phone announcement (spec §2.5, ticket 06)
    // -------------------------------------------------------------------------------------

    /**
     * Every successful mutation announces the storage it produced.
     *
     * This is the whole watch→phone loop's trigger: a mutation that does not announce is a set
     * that exists only on the wrist until the user's *next* set, and the last set of a workout
     * would never reach the phone at all.
     */
    @Test
    fun successfulMutationsAnnounceTheStorageTheyProduced() = runBlocking {
        val announced = mutableListOf<ByteArray>()
        repo.onMutationCommitted = { announced.add(it) }

        repo.setExternal(started)
        val result = repo.mutate { storage, deviceId ->
            WatchJs.setCurrentEntryIndex(storage, deviceId, 1)
        }

        assertTrue("mutation failed: $result", result is WatchJs.MutationResult.Success)
        assertEquals("exactly one announcement per mutation", 1, announced.size)
        assertArrayEquals(
            "the announced bytes must be the storage that was persisted",
            repo.storage.value,
            announced[0],
        )
    }

    /**
     * Storage that came *from* the phone is never announced back.
     *
     * The echo would not merely be wasteful: the phone merges what it receives, and a merge
     * that changes anything triggers its own put back to the watch. Two devices each announcing
     * what the other just sent is a loop with a radio in it, and the only thing that breaks it
     * is this asymmetry — the watch announces only what it authored.
     */
    @Test
    fun externalWritesAreNeverAnnounced() = runBlocking {
        val announced = mutableListOf<ByteArray>()
        repo.onMutationCommitted = { announced.add(it) }

        repo.setExternal(started)
        repo.setExternal(fixture)
        repo.clear()
        WatchStorageRepository(context).also { it.onMutationCommitted = { b -> announced.add(b) } }.load()

        assertEquals("external writes must not announce anything", 0, announced.size)
    }

    @Test
    fun aFailedMutationAnnouncesNothing() = runBlocking {
        val announced = mutableListOf<ByteArray>()
        repo.onMutationCommitted = { announced.add(it) }

        // No active workout, so finishWorkout fails — and the phone must not be told that
        // storage changed when it did not.
        repo.setExternal(fixture)
        val result = repo.mutate { storage, deviceId -> WatchJs.finishWorkout(storage, deviceId) }

        assertTrue("expected a failure, got: $result", result is WatchJs.MutationResult.Failure)
        assertEquals(0, announced.size)
    }

    // -------------------------------------------------------------------------------------
    // deviceId (spec §2.4)
    // -------------------------------------------------------------------------------------

    @Test
    fun deviceIdIsPrefixedStableWithinAnInstallAndNotDerivedFromHardware() {
        val id = repo.deviceId
        assertTrue("must carry the wear- prefix so it cannot collide with the phone", id.startsWith("wear-"))
        assertEquals("wear- plus 8 hex characters", "wear-".length + 8, id.length)
        assertEquals("stable within a process", id, repo.deviceId)
        assertEquals(
            "stable across repository instances — it is persisted in SharedPreferences",
            id,
            WatchStorageRepository(context).deviceId,
        )
    }
}
