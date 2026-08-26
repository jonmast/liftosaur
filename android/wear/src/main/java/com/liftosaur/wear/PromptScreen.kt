package com.liftosaur.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Picker
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberPickerState
import com.liftosaur.wear.engine.WatchAmrapModal

/**
 * PAGED: one field per screen, crown-driven, confirm advances, Done on the last.
 *
 * watchOS stacks every field in one scrolling form; at 192dp that needs ~250dp for the
 * five-field worst case, and because each chip's picker claims the crown the list itself
 * cannot be crown-scrolled — Done ends up below the fold and reachable only by touch drag.
 * Giving each field the whole screen also makes the unbounded `userPromptedVars` list a
 * non-problem: more vars is just more pages (ticket 09).
 */
@Composable
fun PromptScreen(
    modal: WatchAmrapModal,
    busy: Boolean,
    error: String?,
    onSubmit: (PromptAnswers) -> Unit,
    onCancel: () -> Unit,
) {
    val fields = remember(modal) { promptFieldsFor(modal) }
    val selections = remember(modal) { mutableStateListOf<Int>().apply { addAll(fields.map { it.initialIndex }) } }
    var fieldIndex by remember(modal) { mutableIntStateOf(0) }

    // A modal with no fields at all is not reachable from the bundle (it raises one only when
    // it has something to ask), but if it ever were, the prompt would be an inescapable blank
    // screen. Resolving it immediately is the safe reading.
    if (fields.isEmpty()) {
        LaunchedEffect(modal) { onSubmit(PromptAnswers()) }
        return
    }

    val field = fields[fieldIndex]
    val isLast = fieldIndex == fields.lastIndex

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = field.label,
                    color = LiftosaurColor.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fields.size > 1) {
                    Text(
                        text = "Field ${fieldIndex + 1} of ${fields.size}",
                        color = LiftosaurColor.textSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }

                // Keyed by page so each field gets its own PickerState — a shared one would
                // carry the previous field's scroll position into the next.
                key(fieldIndex) {
                    val pickerState = rememberPickerState(
                        initialNumberOfOptions = field.options.size,
                        initiallySelectedIndex = selections[fieldIndex],
                    )
                    LaunchedEffect(pickerState.selectedOptionIndex) {
                        selections[fieldIndex] = pickerState.selectedOptionIndex
                    }
                    Picker(
                        state = pickerState,
                        contentDescription = { field.label },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .padding(vertical = 2.dp),
                    ) { optionIndex ->
                        Text(
                            text = field.options[optionIndex],
                            color = LiftosaurColor.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }

                error?.let { ErrorBanner(it) }

                // Extra inset because this row sits at the bottom of the centered stack, where
                // a 192dp circle is only ~133dp wide — at the outer 18dp padding the button
                // corners fall outside the bezel (ticket 09).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(LiftosaurColor.buttonSecondary)
                            .clickable(enabled = !busy) {
                                if (fieldIndex == 0) onCancel() else fieldIndex -= 1
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (fieldIndex == 0) "Cancel" else "Back",
                            color = LiftosaurColor.textSecondary,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                    Button(
                        onClick = {
                            if (isLast) onSubmit(buildAnswers(fields, selections)) else fieldIndex += 1
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LiftosaurColor.buttonPrimary,
                            contentColor = LiftosaurColor.buttonPrimaryLabel,
                        ),
                    ) {
                        if (busy) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        } else {
                            Text(
                                text = if (isLast) "Done" else "Next",
                                modifier = Modifier.fillMaxWidth(),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
