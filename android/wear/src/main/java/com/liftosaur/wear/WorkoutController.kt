package com.liftosaur.wear

import android.content.Context
import android.util.Log
import com.liftosaur.wear.engine.GlobalSetIndex
import com.liftosaur.wear.engine.LiftosaurEngine
import com.liftosaur.wear.engine.WatchAmrapModal
import com.liftosaur.wear.engine.WatchJs
import com.liftosaur.wear.engine.WatchStorageRepository
import com.liftosaur.wear.engine.WatchWorkout
import com.liftosaur.wear.sync.PhoneStorageSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.liftosaur.wear.engine.EngineDispatcher

/**
 * What the screens render, derived entirely from storage.
 *
 * There is no UI-owned copy of workout state — every field here is the answer the bundle gave
 * to the last read. The screens are a projection of storage, so a mutation that failed cannot
 * leave the UI showing a set as logged.
 */
data class WorkoutUiState(
    val loading: Boolean = true,
    val hasProgram: Boolean = false,
    /** The active workout, or null when none is in progress. */
    val progress: WatchWorkout? = null,
    /** What `startWorkout` would begin. Shown on Home when [progress] is null. */
    val next: WatchWorkout? = null,
    val busy: Boolean = false,
    /** The pending set-completion prompt. Non-null means the prompt route must be shown. */
    val prompt: WatchAmrapModal? = null,
    /** Transient, user-facing, cleared on the next successful action. */
    val error: String? = null,
    /** True before the phone has ever synced — the first-run empty state. */
    val empty: Boolean = false,
    /**
     * Bumped only when *this watch* started a workout, so Home can follow the user in.
     *
     * A counter rather than a boolean derived from [progress], because "a workout is active"
     * is also true when the app opens onto one the phone started, and navigating on that would
     * yank the user past Home on every launch.
     */
    val startedNonce: Int = 0,
) {
    val workout: WatchWorkout? get() = progress ?: next
    val isWorkoutActive: Boolean get() = progress != null
}

/**
 * The one place that talks to the engine, and the owner of the two-step logging protocol.
 *
 * **Why a controller rather than per-screen calls:** the prompt is not a property of a screen,
 * it is a property of *storage* — `completeSet` on a prompt-requiring set attaches a modal and
 * leaves the set alone. Any code path that logs a set must drain that modal, and a second
 * `completeSet` silently discards it. Centralizing means there is exactly one place that can
 * get the sequence wrong, and it is [logSet].
 *
 * **Serialization:** every mutation runs under [mutationJob], and [busy] is true while one is
 * in flight. This is not just a spinner — it is what makes "no out-of-order logging" (spec
 * §2.7) structural. Two taps racing would issue two `completeSet` calls against the *same*
 * pre-mutation storage, and the second would overwrite the first's result.
 */
class WorkoutController(
    private val context: Context,
    private val repository: WatchStorageRepository,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "WorkoutController"
    }

    private val _state = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = _state.asStateFlow()

    private var mutationJob: Job? = null

    fun start() {
        if (!_state.value.loading) return
        scope.launch {
            withContext(EngineDispatcher.dispatcher) { LiftosaurEngine.initialize(context) }
            val hasStorage = repository.load() || seedFixtureIfDebug()
            observeExternalStorage()
            // Catch up on a phone put this app was not alive for (onDataChanged fires once).
            // Launched rather than awaited so the UI paints from disk instead of waiting on the
            // radio; when it lands it arrives through observeExternalStorage like any other
            // external change.
            scope.launch { PhoneStorageSync.applyLatest(context) }
            if (!hasStorage) {
                _state.value = WorkoutUiState(loading = false, empty = true)
                return@launch
            }
            refresh()
        }
    }

    /**
     * Follows storage that was written from outside the UI — the phone's `/storage` DataItem,
     * applied by `PhoneStorageListenerService` while this screen is open.
     *
     * Started before the empty-state early return on purpose: the very first phone sync arrives
     * *into* the empty state, and a collector started only on the has-storage path would leave
     * a freshly-paired watch showing "no program" until it was relaunched.
     *
     * Skipping while a mutation is in flight is not a dropped update — [mutation] refreshes
     * unconditionally when it finishes, and that refresh reads whatever storage is current by
     * then.
     */
    private fun observeExternalStorage() {
        scope.launch {
            repository.externalRevision.drop(1).collect {
                if (mutationJob?.isActive == true) return@collect
                Log.i(TAG, "external storage change, re-deriving UI")
                refresh()
            }
        }
    }

    /**
     * Debug-only seeding, until the phone bridge (ticket 05) can deliver real storage.
     *
     * Release builds deliberately fall through to the empty state instead: shipping a build
     * that invents a workout out of a bundled asset would make "the watch is showing the wrong
     * thing" unfalsifiable, which is the exact ambiguity the build-identity screen exists to
     * remove.
     */
    private fun seedFixtureIfDebug(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return try {
            val bytes = context.assets.open("fixture-storage.json").use { it.readBytes() }
            repository.setExternal(bytes)
            Log.i(TAG, "seeded ${bytes.size}B fixture storage (debug build)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not seed fixture storage", e)
            false
        }
    }

    /**
     * Re-reads everything the UI shows from storage.
     *
     * Three reads rather than a cached projection: they are ~15ms warm, and the alternative —
     * deriving new state from old state plus the mutation — is how a UI drifts from the
     * storage that the phone will eventually merge.
     */
    private suspend fun refresh(errorOverride: String? = null, startedNonceBump: Int = 0) {
        val progress = repository.read { WatchJs.getProgress(it) }
        val active = (progress as? WatchJs.CallResult.Success)?.value

        val next = if (active == null) {
            (repository.read { WatchJs.getNextHistoryRecord(it) } as? WatchJs.CallResult.Success)?.value
        } else {
            null
        }

        // Only poll for a modal when a workout is active — with no progress the bundle has
        // nowhere to hang one, and the call is pure cost.
        val prompt = if (active != null) {
            (repository.read { WatchJs.getAmrapModal(it) } as? WatchJs.CallResult.Success)?.value
        } else {
            null
        }

        _state.value = WorkoutUiState(
            loading = false,
            hasProgram = active != null || next != null,
            progress = active,
            next = next,
            busy = false,
            prompt = prompt,
            error = errorOverride ?: (progress as? WatchJs.CallResult.Failure)?.error,
            empty = false,
            startedNonce = _state.value.startedNonce + startedNonceBump,
        )
    }

    /** Runs one mutation to completion, then re-derives state. Ignored while another is running. */
    private fun mutation(bumpsStartedNonce: Boolean = false, block: suspend () -> WatchJs.MutationResult) {
        if (mutationJob?.isActive == true) return
        _state.value = _state.value.copy(busy = true, error = null)
        mutationJob = scope.launch {
            val result = block()
            val error = (result as? WatchJs.MutationResult.Failure)?.error
            refresh(
                errorOverride = error,
                startedNonceBump = if (bumpsStartedNonce && error == null) 1 else 0,
            )
        }
    }

    fun startWorkout() = mutation(bumpsStartedNonce = true) {
        repository.mutate { storage, deviceId -> WatchJs.startWorkout(storage, deviceId) }
    }

    /**
     * Logs the set at [at] — step one of the two-step protocol.
     *
     * `completeSet` returning success does **not** mean the set was logged. If it needs a
     * prompt, the set is untouched and a modal is now attached to storage; [refresh] polls
     * `getAmrapModal` and the resulting non-null `prompt` is what routes the UI to
     * [PromptScreen]. Skipping that poll would not just miss the prompt — the *next*
     * `completeSet` would clear the pending modal and the user's set would be silently lost.
     */
    fun logSet(entryIndex: Int, at: GlobalSetIndex) {
        // Refuse to log while a prompt is pending, because `completeSet` would *clear* it —
        // discarding a set the user already answered for, while returning success. The UI
        // routes on `prompt` being non-null, so dropping the tap re-asserts the prompt rather
        // than stranding the user (spec §2.3).
        if (_state.value.prompt != null) {
            Log.w(TAG, "ignoring logSet with a prompt pending")
            return
        }
        mutation {
            repository.mutate { storage, deviceId ->
                WatchJs.completeSet(storage, deviceId, entryIndex, at)
            }
        }
    }

    /** Step two: resolves the pending prompt. Takes no index — the bundle reads its own modal. */
    fun submitPrompt(answers: PromptAnswers) = mutation {
        repository.mutate { storage, deviceId ->
            WatchJs.completeSetWithAmrap(
                storage = storage,
                deviceId = deviceId,
                completedReps = answers.reps,
                completedRepsLeft = answers.repsLeft,
                completedWeight = answers.weight,
                completedRpe = answers.rpe,
                userPromptedVarsJson = answers.userVarsJson,
            )
        }
    }

    /**
     * Dismisses the prompt without logging the set.
     *
     * `completeSetWithAmrap` with every field null is the bundle's own cancel path: with no
     * values and no user vars it drops `amrapModal` and returns storage unchanged otherwise
     * (`Progress_changeAmrapAction`'s early return). Doing it this way rather than leaving the
     * modal in place matters — a lingering modal would be re-polled on the next refresh and
     * the user could never leave the prompt.
     */
    fun cancelPrompt() = mutation {
        repository.mutate { storage, deviceId ->
            WatchJs.completeSetWithAmrap(storage, deviceId)
        }
    }

    /** Keeps storage's notion of the shown exercise in step with UI navigation. */
    fun setCurrentEntryIndex(entryIndex: Int) {
        if (_state.value.progress?.currentEntryIndex == entryIndex) return
        mutation {
            repository.mutate { storage, deviceId ->
                WatchJs.setCurrentEntryIndex(storage, deviceId, entryIndex)
            }
        }
    }

    fun finishWorkout() = mutation {
        repository.mutate { storage, deviceId -> WatchJs.finishWorkout(storage, deviceId) }
    }

    fun discardWorkout() = mutation {
        repository.mutate { storage, deviceId -> WatchJs.discardWorkout(storage, deviceId) }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Debug-only: resets to the bundled fixture, so a workout can be re-run without a phone. */
    fun reseedFixture() {
        if (!BuildConfig.DEBUG) return
        scope.launch {
            seedFixtureIfDebug()
            refresh()
        }
    }
}
