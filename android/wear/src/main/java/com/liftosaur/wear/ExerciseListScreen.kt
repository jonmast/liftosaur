package com.liftosaur.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun ExerciseListScreen(
    state: WorkoutUiState,
    onOpenExercise: (Int) -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
) {
    val workout = state.workout
    val listState = rememberTransformingLazyColumnState()
    var confirming by remember { mutableStateOf<Confirm?>(null) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        if (workout == null) {
            CenteredMessage(title = "No workout")
            return@ScreenScaffold
        }

        val pending = confirming
        if (pending != null) {
            ConfirmRow(
                confirm = pending,
                onConfirm = {
                    confirming = null
                    if (pending == Confirm.FINISH) onFinish() else onDiscard()
                },
                onCancel = { confirming = null },
            )
            return@ScreenScaffold
        }

        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                ScreenTitle(workout.dayName, modifier = Modifier.padding(bottom = 2.dp))
            }
            state.error?.let { message -> item { ErrorBanner(message) } }
            workout.exercises.forEachIndexed { index, entry ->
                item(key = entry.id + index) {
                    TapRow(onClick = { onOpenExercise(index) }, minHeight = 52.dp) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = entry.name,
                                color = LiftosaurColor.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (index == workout.currentEntryIndex) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Medium
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SetStatusDots(
                                sets = entry.sets,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            if (state.isWorkoutActive) {
                item {
                    TapRow(
                        onClick = { confirming = Confirm.FINISH },
                        minHeight = 48.dp,
                        background = LiftosaurColor.buttonPrimary,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = "Finish workout",
                            color = LiftosaurColor.buttonPrimaryLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
                item {
                    TapRow(
                        onClick = { confirming = Confirm.DISCARD },
                        minHeight = 48.dp,
                        background = LiftosaurColor.backgroundSet,
                    ) {
                        Text(
                            text = "Discard",
                            color = LiftosaurColor.red400,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private enum class Confirm { FINISH, DISCARD }

/**
 * Confirmation for the two irreversible actions, inline rather than as a dialog.
 *
 * Both destroy the in-progress workout and there is no undo anywhere in the app, so a
 * mis-tap on a 192dp screen would cost the whole session.
 */
@Composable
private fun ConfirmRow(confirm: Confirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (confirm == Confirm.FINISH) "Finish workout?" else "Discard workout?",
            color = LiftosaurColor.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, start = 6.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TapRow(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                background = LiftosaurColor.buttonSecondary,
            ) {
                Text(text = "No", color = LiftosaurColor.textSecondary, fontSize = 13.sp, maxLines = 1)
            }
            TapRow(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                background = if (confirm == Confirm.FINISH) {
                    LiftosaurColor.buttonPrimary
                } else {
                    LiftosaurColor.red500
                },
            ) {
                Text(
                    text = "Yes",
                    color = LiftosaurColor.buttonPrimaryLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
