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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Picker
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberPickerState

@Composable
fun PromptScreen(
    entryIndex: Int,
    at: GlobalSetIndex,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val set = PrototypeStore.entry(entryIndex).setAt(at)
    val worstCase = PrototypeVariants.worstCase
    val fields = remember(at.pos, worstCase) { promptFieldsFor(set, worstCase) }

    if (fields.isEmpty()) {
        LaunchedEffect(at.pos) {
            PrototypeStore.completeSet(entryIndex, at)
            onDone()
        }
        return
    }

    val selections: List<MutableIntState> = remember(fields) {
        fields.map { mutableIntStateOf(it.initialIndex) }
    }

    fun commit() {
        val repsIndex = fields.indexOfFirst { it.key == "reps" }
        val weightIndex = fields.indexOfFirst { it.key == "weight" }
        PrototypeStore.completeSet(
            entryIndex = entryIndex,
            at = at,
            completedReps = if (repsIndex >= 0) {
                fields[repsIndex].options[selections[repsIndex].intValue].toIntOrNull()
            } else {
                null
            },
            weight = if (weightIndex >= 0) {
                fields[weightIndex].options[selections[weightIndex].intValue].toDoubleOrNull()
            } else {
                null
            },
        )
        onDone()
    }

    if (PrototypeVariants.promptLayout == PromptLayout.PAGED) {
        PagedPrompt(fields, selections, ::commit, onCancel)
    } else {
        FormPrompt(fields, selections, ::commit, onCancel)
    }
}

@Composable
private fun PagedPrompt(
    fields: List<PromptField>,
    selections: List<MutableIntState>,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    var fieldIndex by remember { mutableIntStateOf(0) }
    val field = fields[fieldIndex]
    val selection = selections[fieldIndex]
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
                key(field.key) {
                    val pickerState = rememberPickerState(
                        initialNumberOfOptions = field.options.size,
                        initiallySelectedIndex = selection.intValue,
                    )
                    LaunchedEffect(pickerState.selectedOptionIndex) {
                        selection.intValue = pickerState.selectedOptionIndex
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
                            text = optionText(field, optionIndex),
                            color = LiftosaurColor.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
                // Extra inset because this row sits at the bottom of the centered stack, where
                // a 192dp circle is only ~133dp wide — at the outer 18dp padding the button
                // corners fall outside the bezel.
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
                            .clickable { if (fieldIndex == 0) onCancel() else fieldIndex -= 1 },
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
                        onClick = { if (isLast) onCommit() else fieldIndex += 1 },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LiftosaurColor.buttonPrimary,
                            contentColor = LiftosaurColor.buttonPrimaryLabel,
                        ),
                    ) {
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

@Composable
private fun FormPrompt(
    fields: List<PromptField>,
    selections: List<MutableIntState>,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    var focusedIndex by remember { mutableIntStateOf(0) }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            rotaryScrollableBehavior = null,
        ) {
            item {
                ScreenTitle("Log set")
            }
            fields.forEachIndexed { index, field ->
                item(key = field.key) {
                    FieldChip(
                        field = field,
                        selection = selections[index],
                        isFocused = focusedIndex == index,
                        onFocus = { focusedIndex = index },
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(LiftosaurColor.buttonSecondary)
                            .clickable(onClick = onCancel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Cancel",
                            color = LiftosaurColor.textSecondary,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                    Button(
                        onClick = onCommit,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LiftosaurColor.buttonPrimary,
                            contentColor = LiftosaurColor.buttonPrimaryLabel,
                        ),
                    ) {
                        Text(
                            text = "Done",
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

@Composable
private fun FieldChip(
    field: PromptField,
    selection: MutableIntState,
    isFocused: Boolean,
    onFocus: () -> Unit,
) {
    val pickerState = rememberPickerState(
        initialNumberOfOptions = field.options.size,
        initiallySelectedIndex = selection.intValue,
    )
    LaunchedEffect(pickerState.selectedOptionIndex) {
        selection.intValue = pickerState.selectedOptionIndex
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isFocused) LiftosaurColor.backgroundCardSelected else LiftosaurColor.backgroundSet,
            )
            .clickable(onClick = onFocus)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = field.label,
            modifier = Modifier.weight(1f),
            color = LiftosaurColor.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Picker(
            state = pickerState,
            contentDescription = { field.label },
            modifier = Modifier
                .width(66.dp)
                .height(34.dp)
                .hierarchicalFocusGroup(active = isFocused),
            userScrollEnabled = isFocused,
        ) { optionIndex ->
            Text(
                text = optionText(field, optionIndex),
                color = if (isFocused) LiftosaurColor.textPrimary else LiftosaurColor.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private fun optionText(field: PromptField, optionIndex: Int): String {
    val raw = field.options[optionIndex]
    return field.suffix?.let { "$raw $it" } ?: raw
}
