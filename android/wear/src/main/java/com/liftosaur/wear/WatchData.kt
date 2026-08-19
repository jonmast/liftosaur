package com.liftosaur.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SetStatus { SUCCESS, IN_RANGE, FAILED, NOT_FINISHED }

/**
 * Position of a set inside [WatchEntry.sets]. Constructed only by [WatchEntry], because
 * [WatchSet.index] restarts at 0 at the warmup/work boundary and would silently address
 * the wrong set.
 */
@JvmInline
value class GlobalSetIndex private constructor(val pos: Int) {
    internal companion object {
        fun fromArrayPosition(pos: Int) = GlobalSetIndex(pos)
    }
}

data class WatchSet(
    val index: Int,
    val reps: Int?,
    val minReps: Int?,
    val weight: Double?,
    val plates: String?,
    val isAmrap: Boolean,
    val isWarmup: Boolean,
    val isUnilateral: Boolean,
    val label: String?,
    val isCompleted: Boolean,
    val completedReps: Int?,
    val status: SetStatus,
)

data class PositionedSet(val at: GlobalSetIndex, val set: WatchSet, val workNumber: Int?)

data class WatchEntry(val id: String, val name: String, val sets: List<WatchSet>) {
    fun positioned(): List<PositionedSet> {
        var work = 0
        return sets.mapIndexed { pos, set ->
            val number = if (set.isWarmup) null else ++work
            PositionedSet(GlobalSetIndex.fromArrayPosition(pos), set, number)
        }
    }

    val workSetCount: Int get() = sets.count { !it.isWarmup }

    fun setAt(at: GlobalSetIndex): WatchSet = sets[at.pos]

    fun nextUnfinished(): PositionedSet? = positioned().firstOrNull { !it.set.isCompleted }

    fun completedCount(): Int = sets.count { it.isCompleted }

    fun replacing(at: GlobalSetIndex, transform: (WatchSet) -> WatchSet): WatchEntry =
        copy(sets = sets.mapIndexed { pos, set -> if (pos == at.pos) transform(set) else set })
}

data class WatchWorkout(
    val dayName: String,
    val programName: String,
    val exercises: List<WatchEntry>,
    val currentEntryIndex: Int,
)

fun formatWeight(weight: Double?): String {
    if (weight == null) return "-"
    return if (weight % 1.0 == 0.0) weight.toInt().toString() else weight.toString()
}

private fun warmupSet(weight: Double, index: Int = 0, reps: Int = 5) = WatchSet(
    index = index,
    reps = reps,
    minReps = null,
    weight = weight,
    plates = "",
    isAmrap = false,
    isWarmup = true,
    isUnilateral = false,
    label = "Warmup",
    isCompleted = false,
    completedReps = null,
    status = SetStatus.NOT_FINISHED,
)

private fun workSet(
    index: Int,
    weight: Double,
    plates: String,
    isAmrap: Boolean = false,
    reps: Int = 5,
) = WatchSet(
    index = index,
    reps = reps,
    minReps = null,
    weight = weight,
    plates = plates,
    isAmrap = isAmrap,
    isWarmup = false,
    isUnilateral = false,
    label = null,
    isCompleted = false,
    completedReps = null,
    status = SetStatus.NOT_FINISHED,
)

private fun completed(set: WatchSet, reps: Int) =
    set.copy(isCompleted = true, completedReps = reps, status = SetStatus.SUCCESS)

fun stubWorkout(): WatchWorkout {
    val row = WatchEntry(
        id = "bentOverRow",
        name = "Bent Over Row",
        sets = listOf(
            completed(warmupSet(45.0), 5),
            completed(workSet(0, 97.5, "25"), 5),
            workSet(1, 97.5, "25"),
            workSet(2, 97.5, "25"),
        ),
    )
    val bench = WatchEntry(
        id = "benchPress",
        name = "Bench Press",
        sets = listOf(
            warmupSet(45.0),
            workSet(0, 47.5, "1.25"),
            workSet(1, 47.5, "1.25"),
            workSet(2, 47.5, "1.25", isAmrap = true),
        ),
    )
    val squat = WatchEntry(
        id = "squat",
        name = "Squat",
        sets = listOf(
            warmupSet(45.0),
            workSet(0, 50.0, "2.5"),
            workSet(1, 50.0, "2.5"),
            workSet(2, 50.0, "2.5"),
        ),
    )
    val lateralRaise = WatchEntry(
        id = "lateralRaise",
        name = "Lateral Raise",
        sets = List(2) { i -> warmupSet(10.0, index = i, reps = 12) } +
            List(8) { i -> workSet(i, 15.0, "", reps = 12) },
    )
    return WatchWorkout(
        dayName = "Workout A",
        programName = "Basic Beginner Routine",
        exercises = listOf(row, bench, squat, lateralRaise),
        currentEntryIndex = 0,
    )
}

object PrototypeStore {
    var workout by mutableStateOf(stubWorkout())
        private set

    fun entry(entryIndex: Int): WatchEntry = workout.exercises[entryIndex]

    fun completeSet(
        entryIndex: Int,
        at: GlobalSetIndex,
        completedReps: Int? = null,
        weight: Double? = null,
    ) {
        val entry = workout.exercises[entryIndex]
        val updated = entry.replacing(at) { set ->
            val reps = completedReps ?: set.reps
            set.copy(
                isCompleted = true,
                completedReps = reps,
                weight = weight ?: set.weight,
                status = statusFor(set, reps),
            )
        }
        workout = workout.copy(
            exercises = workout.exercises.toMutableList().also { it[entryIndex] = updated },
        )
    }

    private fun statusFor(set: WatchSet, reps: Int?): SetStatus {
        val target = set.reps ?: return SetStatus.SUCCESS
        val done = reps ?: return SetStatus.NOT_FINISHED
        return when {
            done >= target -> SetStatus.SUCCESS
            set.minReps != null && done >= set.minReps -> SetStatus.IN_RANGE
            else -> SetStatus.FAILED
        }
    }

    fun reset() {
        workout = stubWorkout()
    }
}

enum class PromptLayout { PAGED, FORM }

enum class DetailLayout { HERO, ANCHORED, ROW }

object PrototypeVariants {
    var promptLayout by mutableStateOf(PromptLayout.PAGED)
    var detailLayout by mutableStateOf(DetailLayout.HERO)
    var worstCase by mutableStateOf(false)

    fun togglePromptLayout() {
        promptLayout = if (promptLayout == PromptLayout.PAGED) PromptLayout.FORM else PromptLayout.PAGED
    }

    fun cycleDetailLayout() {
        detailLayout = when (detailLayout) {
            DetailLayout.HERO -> DetailLayout.ANCHORED
            DetailLayout.ANCHORED -> DetailLayout.ROW
            DetailLayout.ROW -> DetailLayout.HERO
        }
    }

    val detailLabel: String
        get() = when (detailLayout) {
            DetailLayout.HERO -> "Hero card"
            DetailLayout.ANCHORED -> "Anchored list"
            DetailLayout.ROW -> "Button on each row"
        }

    val badge: String
        get() {
            val prompt = if (promptLayout == PromptLayout.PAGED) "PAGED" else "FORM"
            val detail = when (detailLayout) {
                DetailLayout.HERO -> "HERO"
                DetailLayout.ANCHORED -> "ANCH"
                DetailLayout.ROW -> "ROW"
            }
            val worst = if (worstCase) "5F" else "1F"
            return "$prompt/$detail/$worst"
        }
}

data class PromptField(
    val key: String,
    val label: String,
    val options: List<String>,
    val initialIndex: Int,
    val suffix: String? = null,
)

private fun repsOptions() = (0..30).map { it.toString() }

private fun weightOptions(): List<String> {
    val values = generateSequence(45.0) { it + 2.5 }.takeWhile { it <= 185.0 }.toList()
    return values.map { formatWeight(it) }
}

private fun rpeOptions(): List<String> =
    generateSequence(5.0) { it + 0.5 }.takeWhile { it <= 10.0 }.map { formatWeight(it) }.toList()

fun promptFieldsFor(set: WatchSet, worstCase: Boolean): List<PromptField> {
    val fields = mutableListOf<PromptField>()
    if (worstCase || set.isAmrap) {
        fields += PromptField("reps", "Reps", repsOptions(), 5)
    }
    if (worstCase || set.isUnilateral) {
        fields += PromptField("repsLeft", "Reps left", repsOptions(), 5)
    }
    if (worstCase) {
        val weights = weightOptions()
        fields += PromptField("weight", "Weight", weights, weights.indexOf("95"), suffix = "lb")
        val rpe = rpeOptions()
        fields += PromptField("rpe", "RPE", rpe, rpe.indexOf("8"))
        fields += PromptField("rpeTarget", "rpeTarget", (5..10).map { it.toString() }, 3)
        fields += PromptField("tempo", "tempo", (0..10).map { it.toString() }, 2)
    }
    return fields
}
