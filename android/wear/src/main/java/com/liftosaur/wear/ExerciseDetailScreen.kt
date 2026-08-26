package com.liftosaur.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.liftosaur.wear.engine.PositionedSet
import com.liftosaur.wear.engine.WatchEntry

/**
 * HERO: one current set, large, pinned outside any scrollable area.
 *
 * The pinning is the whole point of the layout. As item 0 of a lazy list the hero card scrolls
 * away on a 10-set exercise, which defeats it — the invariant that survived the on-wrist
 * review is *the set being logged is visible at any list length without scrolling*, and only a
 * fixed slot gives that structurally (ticket 09). The review list below it was dropped as
 * useless at 192dp; the dot strip is the only overview.
 */
@Composable
fun ExerciseDetailScreen(
    state: WorkoutUiState,
    entryIndex: Int,
    onLog: (Int, com.liftosaur.wear.engine.GlobalSetIndex) -> Unit,
    onShown: (Int) -> Unit,
) {
    val entry = state.workout?.exercises?.getOrNull(entryIndex)

    // Keeps storage's currentEntryIndex in step with what the user is looking at, so the phone
    // sees the same exercise after a merge (spec §2.7).
    LaunchedEffect(entryIndex, state.isWorkoutActive) {
        if (state.isWorkoutActive) onShown(entryIndex)
    }

    val next = entry?.nextUnfinished()

    ScreenScaffold {
        if (entry == null) {
            CenteredMessage(title = "No exercise")
            return@ScreenScaffold
        }
        Box(modifier = Modifier.fillMaxSize()) {
            HeroContent(entry = entry, next = next, error = state.error)
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                LogEdgeButton(
                    enabled = next != null && !state.busy && state.isWorkoutActive,
                    isBusy = state.busy,
                    hasNext = next != null,
                    onClick = { next?.let { onLog(entryIndex, it.at) } },
                )
            }
        }
    }
}

@Composable
private fun LogEdgeButton(
    enabled: Boolean,
    isBusy: Boolean,
    hasNext: Boolean,
    onClick: () -> Unit,
) {
    EdgeButton(
        onClick = onClick,
        // Never Large: 96dp is half the screen height, and the crescent tapers so hard that
        // even a short label truncates in it (ticket 09).
        buttonSize = EdgeButtonSize.Medium,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = LiftosaurColor.buttonPrimary,
            contentColor = LiftosaurColor.buttonPrimaryLabel,
        ),
    ) {
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
        } else {
            Text(
                text = if (hasNext) "Log" else "Done",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroContent(entry: WatchEntry, next: PositionedSet?, error: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.height(20.dp))
        HeroCard(exerciseName = entry.name, next = next, total = entry.workSetCount)
        SetProgressStrip(sets = entry.sets, currentPos = next?.at?.pos)
        error?.let { ErrorBanner(it, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun HeroCard(exerciseName: String, next: PositionedSet?, total: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = exerciseName,
            modifier = Modifier.fillMaxWidth(),
            color = LiftosaurColor.textSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (next == null) {
            Text(
                text = "All sets done",
                modifier = Modifier.fillMaxWidth(),
                color = LiftosaurColor.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            return@Column
        }
        Text(
            text = setTitle(next),
            modifier = Modifier.fillMaxWidth(),
            // Warmups render at half alpha, carrying WatchModels.swift's convention into the
            // hero so the user can tell a warmup from a work set at a glance.
            color = LiftosaurColor.textPrimary.copy(alpha = if (next.set.isWarmup) 0.55f else 1f),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = heroCaption(next, total),
            modifier = Modifier.fillMaxWidth(),
            color = LiftosaurColor.textSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun heroCaption(positioned: PositionedSet, total: Int): String {
    val base = setCaption(positioned, total)
    val plates = positioned.set.plates
    return if (!plates.isNullOrEmpty()) "$base  ·  $plates" else base
}




