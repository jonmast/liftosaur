package com.liftosaur.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun HomeScreen(onStart: () -> Unit, onOpenVariants: () -> Unit) {
    val workout = PrototypeStore.workout
    val totalSets = workout.exercises.sumOf { it.sets.size }
    val doneSets = workout.exercises.sumOf { entry -> entry.completedCount() }
    val label = if (doneSets > 0) "Continue" else "Start"

    ScreenScaffold {
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
                    text = "$doneSets of $totalSets sets",
                    modifier = Modifier.padding(top = 1.dp),
                    color = LiftosaurColor.textSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LiftosaurColor.buttonPrimary,
                        contentColor = LiftosaurColor.buttonPrimaryLabel,
                    ),
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                ToggleLine(
                    label = "Variants",
                    value = PrototypeVariants.badge,
                    onClick = onOpenVariants,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
