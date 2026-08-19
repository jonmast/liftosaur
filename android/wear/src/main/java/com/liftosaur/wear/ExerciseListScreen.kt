package com.liftosaur.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun ExerciseListScreen(onOpenExercise: (Int) -> Unit) {
    val workout = PrototypeStore.workout
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                ScreenTitle(workout.dayName, modifier = Modifier.padding(bottom = 2.dp))
            }
            itemsIndexed(workout.exercises) { index, entry ->
                TapRow(onClick = { onOpenExercise(index) }, minHeight = 52.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = entry.name,
                            color = LiftosaurColor.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
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
            item {
                VariantBadge(
                    text = PrototypeVariants.badge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
