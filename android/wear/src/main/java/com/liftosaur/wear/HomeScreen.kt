package com.liftosaur.wear

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun HomeScreen(
    state: WorkoutUiState,
    onStart: () -> Unit,
    onOpenWorkout: () -> Unit,
    onOpenEngine: () -> Unit,
) {
    ScreenScaffold {
        when {
            state.loading -> LoadingScreen()

            state.empty -> CenteredMessage(
                title = "No workout yet",
                detail = "Open Liftosaur on your phone to sync.",
            )

            !state.hasProgram -> CenteredMessage(
                title = "No program",
                detail = "Pick a program on your phone.",
            )

            else -> HomeContent(
                state = state,
                onStart = onStart,
                onOpenWorkout = onOpenWorkout,
                onOpenEngine = onOpenEngine,
            )
        }
    }
}

@Composable
private fun HomeContent(
    state: WorkoutUiState,
    onStart: () -> Unit,
    onOpenWorkout: () -> Unit,
    onOpenEngine: () -> Unit,
) {
    val workout = state.workout ?: return
    val done = workout.completedSetCount()
    val total = workout.totalSetCount()
    val active = state.isWorkoutActive

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = workout.programName,
                color = LiftosaurColor.textSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = workout.dayName,
                modifier = Modifier.padding(top = 1.dp),
                color = LiftosaurColor.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (active) "$done of $total sets" else "${workout.exercises.size} exercises",
                modifier = Modifier.padding(top = 1.dp),
                color = LiftosaurColor.textSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Button(
                onClick = if (active) onOpenWorkout else onStart,
                enabled = !state.busy,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LiftosaurColor.buttonPrimary,
                    contentColor = LiftosaurColor.buttonPrimaryLabel,
                ),
            ) {
                if (state.busy) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                } else {
                    Text(
                        text = if (active) "Continue" else "Start",
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
            state.error?.let { ErrorBanner(it, modifier = Modifier.padding(top = 4.dp)) }
            BuildIdentityLine(onClick = onOpenEngine, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * The build fingerprint, tappable through to the engine screen.
 *
 * Not cosmetic: `versionCode` is pinned at 1 forever and the watch is a passive mirror of
 * phone state, so without a visible bundle SHA "the watch is showing the wrong thing" is
 * ambiguous between stale data and stale code (spec §2.6).
 */
@Composable
private fun BuildIdentityLine(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = BuildIdentity.WATCH_BUNDLE_SHA_SHORT,
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .clickable(onClick = onClick),
        color = LiftosaurColor.lightgray500,
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}
