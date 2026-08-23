package com.liftosaur.wear.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The typed read models — the *only* JSON the Kotlin side parses.
 *
 * Storage itself is never parsed here. It is 19-135KB, is only ever handed straight back to
 * the bundle, and parsing it would buy nothing but a drift surface against upstream's
 * `IStorage` (spec §2.3). These four types are the read models the UI actually renders,
 * mirroring `IWatchHistoryRecord` / `IWatchHistoryEntry` / `IWatchSet` / `IWatchAmrapModal`
 * in `src/watch/index.ts`.
 *
 * `ignoreUnknownKeys` is load-bearing rather than lax: this is a fork tracking an upstream
 * that adds fields to these models freely, and a new `IWatchSet` field upstream must not
 * break the watch.
 */
val WatchJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class WatchWeight(
    val value: Double,
    val unit: String,
)

@Serializable
enum class WatchSetStatus {
    @SerialName("success")
    SUCCESS,

    @SerialName("in-range")
    IN_RANGE,

    @SerialName("failed")
    FAILED,

    @SerialName("not-finished")
    NOT_FINISHED,
}

/**
 * One set as the watch displays it.
 *
 * ⚠️ [index] is display metadata ("set 2 of 3"), NOT an identifier. It is copied from the
 * per-array index in the bundle, so it **restarts at 0** at the warmup/work boundary: an entry
 * with 1 warmup and 3 work sets has indices 0, 0, 1, 2. Addressing a set by it completes the
 * wrong one while returning `success:true`. Use [WatchEntry.positionedSets] to get a
 * [GlobalSetIndex] instead (spec §2.3).
 */
@Serializable
data class WatchSet(
    val index: Int,
    val reps: Int? = null,
    val minReps: Int? = null,
    val weight: WatchWeight? = null,
    val isAmrap: Boolean = false,
    val askWeight: Boolean = false,
    val rpe: Double? = null,
    val label: String? = null,
    val isCompleted: Boolean = false,
    val completedReps: Int? = null,
    val completedRepsLeft: Int? = null,
    val completedWeight: WatchWeight? = null,
    val completedRpe: Double? = null,
    val status: WatchSetStatus = WatchSetStatus.NOT_FINISHED,
    val plates: String? = null,
    val isWarmup: Boolean = false,
    val isUnilateral: Boolean = false,
)

/**
 * Position of a set within [WatchEntry.sets] — the flattened `[...warmupSets, ...sets]` array.
 *
 * This is the ONLY index space `completeSet` accepts, and the type exists so it cannot be
 * confused with the two spaces that look identical at a call site: [WatchSet.index] (display
 * metadata, restarts at the warmup boundary) and [WatchAmrapModal.setIndex] (work-set-only).
 * Confusing them logs the wrong set and still reports success.
 *
 * The constructor is private so a raw Int cannot be laundered into one; positions come from
 * [WatchEntry.positionedSets] or [WatchEntry.workSetPosition].
 */
@JvmInline
value class GlobalSetIndex private constructor(val pos: Int) {
    override fun toString(): String = "pos=$pos"

    companion object {
        internal fun fromArrayPosition(pos: Int) = GlobalSetIndex(pos)
    }
}

/** A set paired with its position, plus its 1-based work-set number (null for warmups). */
data class PositionedSet(
    val at: GlobalSetIndex,
    val set: WatchSet,
    val workNumber: Int?,
)

@Serializable
data class WatchEntry(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val sets: List<WatchSet> = emptyList(),
) {
    /** Every set with its array position — the only sanctioned source of [GlobalSetIndex]. */
    fun positionedSets(): List<PositionedSet> {
        var work = 0
        return sets.mapIndexed { pos, set ->
            PositionedSet(
                at = GlobalSetIndex.fromArrayPosition(pos),
                set = set,
                workNumber = if (set.isWarmup) null else ++work,
            )
        }
    }

    val warmupCount: Int get() = sets.count { it.isWarmup }

    val workSetCount: Int get() = sets.count { !it.isWarmup }

    fun setAt(at: GlobalSetIndex): WatchSet = sets[at.pos]

    fun nextUnfinished(): PositionedSet? = positionedSets().firstOrNull { !it.set.isCompleted }

    /**
     * Converts a work-set-space index — notably [WatchAmrapModal.setIndex] — into a position.
     *
     * Warmups are stored ahead of work sets in the flattened array, so the conversion is a
     * shift by [warmupCount]. Feeding a work-set index to `completeSet` unconverted silently
     * targets a warmup set (or an earlier work set) instead.
     */
    fun workSetPosition(workSetIndex: Int): GlobalSetIndex =
        GlobalSetIndex.fromArrayPosition(warmupCount + workSetIndex)
}

@Serializable
data class WatchWorkout(
    val dayName: String = "",
    val programName: String = "",
    val exercises: List<WatchEntry> = emptyList(),
    val currentEntryIndex: Int = 0,
)

@Serializable
data class WatchUserPromptedStateVar(
    val name: String,
    val value: Double,
    val unit: String? = null,
)

/**
 * The set-completion prompt, when one is pending.
 *
 * ⚠️ [setIndex] is in **work-set space** (it indexes `entry.sets`, excluding warmups) and must
 * never be handed back to `completeSet`. Convert it with [WatchEntry.workSetPosition], or —
 * better — resolve the prompt with `completeSetWithAmrap`, which reads the pending modal out
 * of storage itself and takes no index at all.
 *
 * [validWeights]/[validWeightIndex] are populated inline only when [askWeight] is true, which
 * is why the weight picker needs no extra JS call (spec §2.7).
 */
@Serializable
data class WatchAmrapModal(
    val entryIndex: Int,
    val setIndex: Int,
    val isAmrap: Boolean = false,
    val logRpe: Boolean = false,
    val askWeight: Boolean = false,
    val hasUserVars: Boolean = false,
    val isUnilateral: Boolean = false,
    val initialReps: Int? = null,
    val initialRepsLeft: Int? = null,
    val initialWeight: Double? = null,
    val weightUnit: String = "lb",
    val initialRpe: Double? = null,
    val validWeights: List<Double>? = null,
    val validWeightIndex: Int? = null,
    val userPromptedVars: List<WatchUserPromptedStateVar> = emptyList(),
)

@Serializable
data class HasProgram(val hasProgram: Boolean)
