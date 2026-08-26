package com.liftosaur.wear

import com.liftosaur.wear.engine.PositionedSet
import com.liftosaur.wear.engine.WatchEntry
import com.liftosaur.wear.engine.WatchSet
import com.liftosaur.wear.engine.WatchWorkout

/**
 * Display helpers over the engine's read models.
 *
 * The prototype's parallel `WatchSet`/`WatchEntry`/`SetStatus` types are gone — they were stub
 * shapes that happened to resemble the bundle's, and keeping both would mean a translation
 * layer whose only job is to be wrong eventually. The screens render
 * `com.liftosaur.wear.engine.*` directly.
 */

fun WatchWorkout.completedSetCount(): Int = exercises.sumOf { entry -> entry.sets.count { it.isCompleted } }

fun WatchWorkout.totalSetCount(): Int = exercises.sumOf { it.sets.size }

fun WatchEntry.completedCount(): Int = sets.count { it.isCompleted }

/**
 * The first unlogged set across the whole workout, starting at the shown exercise.
 *
 * Used to decide where "Start"/"Continue" lands. It wraps back to the beginning so an exercise
 * left half-done earlier is still reachable without the user hunting for it.
 */
fun WatchWorkout.nextUnfinishedEntryIndex(): Int {
    val ordered = exercises.indices.sortedBy { (it - currentEntryIndex + exercises.size) % exercises.size }
    return ordered.firstOrNull { exercises[it].nextUnfinished() != null } ?: currentEntryIndex
}

fun formatWeight(weight: com.liftosaur.wear.engine.WatchWeight?): String =
    if (weight == null) "-" else formatNumber(weight.value)

fun setTitle(positioned: PositionedSet): String {
    val set = positioned.set
    val reps = if (set.isAmrap) "${set.reps ?: 0}+" else "${set.reps ?: 0}"
    return "$reps x ${formatWeight(set.weight)}"
}

fun setCaption(positioned: PositionedSet, total: Int): String {
    val set = positioned.set
    return if (set.isWarmup) "Warmup" else "Set ${positioned.workNumber} of $total"
}

/** What a completed set actually recorded, which can differ from what it prescribed. */
fun completedSummary(set: WatchSet): String? {
    if (!set.isCompleted) return null
    val reps = set.completedReps ?: return null
    val weight = set.completedWeight ?: set.weight
    return "$reps x ${formatWeight(weight)}"
}
