package com.liftosaur.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liftosaur.wear.engine.EngineDispatcher
import com.liftosaur.wear.engine.LiftosaurEngine
import com.liftosaur.wear.engine.WatchAmrapModal
import com.liftosaur.wear.engine.WatchJs
import com.liftosaur.wear.engine.WatchWorkout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The prompt's page set, checked against a real modal from the real bundle.
 *
 * The fixture here is the synthesised five-field worst case
 * (`.scratch/wearos-port/spike/make-worstcase-fixture.ts`), because **no built-in program
 * produces one** — every modal the ordinary fixture raises has a single field, so page
 * ordering, the unilateral left/right split and the user-var encoding would all be untested
 * against their real consumer. Ticket 09 established on the wrist that five fields is exactly
 * where the rejected FORM layout fails, so this is the case the PAGED decision rests on.
 */
@RunWith(AndroidJUnit4::class)
class PromptFieldsTest {

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private const val DEVICE_ID = "wear-prompttest"

        /** Storage sitting on the pending five-field modal. */
        private lateinit var atModal: ByteArray
        private lateinit var modal: WatchAmrapModal

        @BeforeClass
        @JvmStatic
        fun raiseTheModal() = runBlocking {
            withContext(EngineDispatcher.dispatcher) {
                LiftosaurEngine.initialize(context)
                val fixture = context.assets.open("fixture-worstcase.json").use { it.readBytes() }

                WatchJs.invalidateStorageCache()
                val started = (WatchJs.startWorkout(fixture, DEVICE_ID) as WatchJs.MutationResult.Success).storage

                WatchJs.invalidateStorageCache()
                val entry = (WatchJs.getProgress(started) as WatchJs.CallResult.Success).value!!.exercises[0]
                val amrap = entry.positionedSets().first { it.set.isAmrap }

                WatchJs.invalidateStorageCache()
                atModal = (WatchJs.completeSet(started, DEVICE_ID, 0, amrap.at) as WatchJs.MutationResult.Success).storage

                WatchJs.invalidateStorageCache()
                modal = (WatchJs.getAmrapModal(atModal) as WatchJs.CallResult.Success).value!!
            }
        }

        private fun <T> onEngineFresh(block: () -> T): T = runBlocking {
            withContext(EngineDispatcher.dispatcher) {
                WatchJs.invalidateStorageCache()
                block()
            }
        }
    }

    /**
     * The fixture must actually raise all five kinds, or every assertion below is vacuous.
     *
     * Asserted explicitly because a fixture regeneration that quietly loses, say,
     * `isUnilateral` (it is keyed by `${id}_${equipment}`, and a bare `squat` key silently
     * misses) would leave this file green while testing four fields.
     */
    @Test
    fun fixtureRaisesEveryPromptFieldKind() {
        assertTrue("expected isAmrap", modal.isAmrap)
        assertTrue("expected isUnilateral", modal.isUnilateral)
        assertTrue("expected askWeight", modal.askWeight)
        assertTrue("expected logRpe", modal.logRpe)
        assertTrue("expected user vars", modal.userPromptedVars.isNotEmpty())
    }

    /** Every asked-for field gets a page, in the order watchOS presents them. */
    @Test
    fun worstCaseProducesOnePageEachInOrder() {
        val fields = promptFieldsFor(modal)

        assertEquals(
            "one page per asked-for field, plus one per user var",
            4 + modal.userPromptedVars.size,
            fields.size,
        )
        assertEquals(
            listOf(
                PromptField.Kind.REPS_LEFT,
                PromptField.Kind.REPS,
                PromptField.Kind.WEIGHT,
                PromptField.Kind.RPE,
            ),
            fields.take(4).map { it.kind },
        )
        assertTrue(
            "user vars come last, one page each",
            fields.drop(4).all { it.kind == PromptField.Kind.USER_VAR },
        )
        assertEquals(
            "user var pages are named after the vars",
            modal.userPromptedVars.map { it.name },
            fields.drop(4).map { it.label },
        )

        // Every page must be crown-completable on its own: an empty option list is a page the
        // user cannot answer and cannot skip.
        assertTrue("no page may be empty", fields.all { it.options.isNotEmpty() })
        assertTrue(
            "every page's initial selection must be in range",
            fields.all { it.initialIndex in it.options.indices },
        )
    }

    /**
     * The weight page comes from the modal's own `validWeights`, not from a computed range.
     *
     * These are plate-legal for this exercise and gym; an arithmetic range would offer weights
     * the user cannot physically load, and it is the reason no second JS call is needed here.
     */
    @Test
    fun weightPageUsesTheModalsValidWeights() {
        val weight = promptFieldsFor(modal).first { it.kind == PromptField.Kind.WEIGHT }

        assertEquals("options must be the modal's own weights", modal.validWeights, weight.values)
        assertEquals("and start on the modal's index", modal.validWeightIndex, weight.initialIndex)
        assertEquals(
            "the initially selected weight must be the set's current one",
            modal.initialWeight!!,
            weight.values[weight.initialIndex],
            0.001,
        )
        assertTrue(
            "the unit must be shown, or a number alone is ambiguous",
            weight.options[weight.initialIndex].endsWith(modal.weightUnit),
        )
    }

    /**
     * The answers the pages produce actually complete the set — the end of the two-step.
     *
     * Driven through the same `buildAnswers` the UI calls, with a non-default selection on
     * every page, so a field that silently failed to reach the bundle shows up as a value that
     * did not change rather than as a passing test.
     */
    @Test
    fun answersFromThePagesCompleteTheSet() {
        val fields = promptFieldsFor(modal)
        val selections = fields.map { (it.initialIndex + 2).coerceAtMost(it.options.lastIndex) }
        val answers = buildAnswers(fields, selections)

        val result = onEngineFresh {
            WatchJs.completeSetWithAmrap(
                storage = atModal,
                deviceId = DEVICE_ID,
                completedReps = answers.reps,
                completedRepsLeft = answers.repsLeft,
                completedWeight = answers.weight,
                completedRpe = answers.rpe,
                userPromptedVarsJson = answers.userVarsJson,
            )
        }
        assertTrue("completeSetWithAmrap failed: $result", result is WatchJs.MutationResult.Success)
        val done = (result as WatchJs.MutationResult.Success).storage

        val workout = (onEngineFresh { WatchJs.getProgress(done) } as WatchJs.CallResult.Success).value!!
        val set = workout.exercises[0].sets.first { it.isAmrap }

        assertTrue("the set must be completed", set.isCompleted)
        assertEquals("reps must be what the page produced", answers.reps, set.completedReps)
        assertEquals("reps-left must survive too", answers.repsLeft, set.completedRepsLeft)
        assertEquals(
            "the chosen weight must be recorded",
            answers.weight!!,
            set.completedWeight!!.value,
            0.001,
        )
        assertEquals("and the RPE", answers.rpe!!, set.completedRpe!!, 0.001)

        assertNull(
            "the modal must be gone once resolved",
            (onEngineFresh { WatchJs.getAmrapModal(done) } as WatchJs.CallResult.Success).value,
        )
    }

    /**
     * User vars reach the bundle in the shape it parses.
     *
     * `completeSetWithAmrap` takes them as a JSON *string* it parses itself, and it swallows
     * parse errors silently — a malformed payload loses the user's answers with no error
     * anywhere. The only way to know the encoding is right is to read the values back out.
     */
    @Test
    fun userVarsRoundTripThroughTheirJsonEncoding() {
        val fields = promptFieldsFor(modal)
        val varFields = fields.filter { it.kind == PromptField.Kind.USER_VAR }
        assertTrue("the fixture must carry user vars", varFields.isNotEmpty())

        val selections = fields.map { field ->
            if (field.kind == PromptField.Kind.USER_VAR) field.initialIndex + 3 else field.initialIndex
        }
        val answers = buildAnswers(fields, selections)
        val json = answers.userVarsJson
        assertNotNull("user vars must be encoded", json)

        for (field in varFields) {
            val expected = formatNumber(field.values[field.initialIndex + 3])
            assertTrue(
                "the payload must carry ${field.varName}: $json",
                json!!.contains("\"${field.varName}\""),
            )
            // A unit-carrying var must be an object; a bare one must be a naked number. The
            // bundle branches on exactly this (Weight_is / Weight_isPct), and the wrong shape
            // is dropped rather than rejected.
            if (field.varUnit == null) {
                assertTrue(
                    "unitless var ${field.varName} must be a bare number: $json",
                    json.contains("\"${field.varName}\":$expected"),
                )
            } else {
                assertTrue(
                    "var ${field.varName} with unit ${field.varUnit} must be an object: $json",
                    json.contains("\"${field.varName}\":{"),
                )
            }
        }

        val result = onEngineFresh {
            WatchJs.completeSetWithAmrap(
                storage = atModal,
                deviceId = DEVICE_ID,
                completedReps = answers.reps,
                completedRepsLeft = answers.repsLeft,
                completedWeight = answers.weight,
                completedRpe = answers.rpe,
                userPromptedVarsJson = answers.userVarsJson,
            )
        }
        assertTrue("completeSetWithAmrap failed: $result", result is WatchJs.MutationResult.Success)

        val storedText = (result as WatchJs.MutationResult.Success).storage.decodeToString()
        for (field in varFields) {
            val expected = formatNumber(field.values[field.initialIndex + 3])
            assertTrue(
                "${field.varName} must be persisted with the value the user picked ($expected)",
                storedText.contains("\"${field.varName}\":$expected") ||
                    storedText.contains("\"${field.varName}\":{\"value\":$expected"),
            )
        }
    }

    /**
     * Cancelling drops the modal and leaves the set alone.
     *
     * This is the bundle's own early return — every field null and no user vars means "clear
     * the modal, change nothing". It matters that the modal really does go: the UI routes on
     * its *presence*, so one that survived a cancel would trap the user on the prompt.
     */
    @Test
    fun cancellingClearsTheModalWithoutCompletingTheSet() {
        val result = onEngineFresh { WatchJs.completeSetWithAmrap(atModal, DEVICE_ID) }
        assertTrue("cancel failed: $result", result is WatchJs.MutationResult.Success)
        val after = (result as WatchJs.MutationResult.Success).storage

        assertNull(
            "the modal must be gone, or the user can never leave the prompt",
            (onEngineFresh { WatchJs.getAmrapModal(after) } as WatchJs.CallResult.Success).value,
        )

        val workout = (onEngineFresh { WatchJs.getProgress(after) } as WatchJs.CallResult.Success).value!!
        assertTrue(
            "the set must remain unlogged",
            !workout.exercises[0].sets.first { it.isAmrap }.isCompleted,
        )
    }

    /** A one-field modal produces exactly one page — the common case, not the worst one. */
    @Test
    fun ordinaryModalProducesASinglePage() {
        val fixture = context.assets.open("fixture-storage.json").use { it.readBytes() }
        val started = (onEngineFresh { WatchJs.startWorkout(fixture, DEVICE_ID) } as WatchJs.MutationResult.Success).storage
        val workout: WatchWorkout =
            (onEngineFresh { WatchJs.getProgress(started) } as WatchJs.CallResult.Success).value!!

        val entryIndex = workout.exercises.indexOfFirst { entry -> entry.sets.any { it.isAmrap } }
        assertTrue("the fixture must carry an AMRAP set", entryIndex >= 0)
        val amrap = workout.exercises[entryIndex].positionedSets().first { it.set.isAmrap }

        val raised = (onEngineFresh {
            WatchJs.completeSet(started, DEVICE_ID, entryIndex, amrap.at)
        } as WatchJs.MutationResult.Success).storage
        val single = (onEngineFresh { WatchJs.getAmrapModal(raised) } as WatchJs.CallResult.Success).value!!

        val fields = promptFieldsFor(single)
        assertEquals("a plain AMRAP set asks for reps only", 1, fields.size)
        assertEquals(PromptField.Kind.REPS, fields[0].kind)
        assertEquals(
            "and starts on the set's own target reps",
            single.initialReps,
            fields[0].values[fields[0].initialIndex].toInt(),
        )
    }
}
