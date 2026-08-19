package com.liftosaur.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun VariantsScreen() {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                ScreenTitle("Variants", modifier = Modifier.padding(bottom = 2.dp))
            }
            item {
                VariantChoice(
                    title = "Prompt",
                    value = if (PrototypeVariants.promptLayout == PromptLayout.PAGED) {
                        "One field per screen"
                    } else {
                        "All fields in a list"
                    },
                    onClick = { PrototypeVariants.togglePromptLayout() },
                )
            }
            item {
                VariantChoice(
                    title = "Set screen",
                    value = PrototypeVariants.detailLabel,
                    onClick = { PrototypeVariants.cycleDetailLayout() },
                )
            }
            item {
                VariantChoice(
                    title = "Prompt fields",
                    value = if (PrototypeVariants.worstCase) "5 fields" else "Only what the set asks",
                    onClick = { PrototypeVariants.worstCase = !PrototypeVariants.worstCase },
                )
            }
            item {
                VariantChoice(
                    title = "Workout",
                    value = "Reset sets",
                    onClick = { PrototypeStore.reset() },
                )
            }
        }
    }
}

@Composable
private fun VariantChoice(title: String, value: String, onClick: () -> Unit) {
    TapRow(onClick = onClick, minHeight = 48.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                color = LiftosaurColor.textSecondary,
                fontSize = 10.sp,
                maxLines = 1,
            )
            Text(
                text = value,
                color = LiftosaurColor.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
        }
    }
}
