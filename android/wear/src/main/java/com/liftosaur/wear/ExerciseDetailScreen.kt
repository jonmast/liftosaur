package com.liftosaur.wear

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

const val LOG_LATENCY_MS = 200L

private const val REASSERT_IDLE_MS = 600L

@Composable
fun ExerciseDetailScreen(
    entryIndex: Int,
    onNeedsPrompt: (GlobalSetIndex) -> Unit,
) {
    val entry = PrototypeStore.entry(entryIndex)
    val listState = rememberTransformingLazyColumnState()
    val scope = rememberCoroutineScope()
    var pendingAt by remember { mutableStateOf<GlobalSetIndex?>(null) }

    val positionedSets = entry.positioned()
    val next = entry.nextUnfinished()
    val total = entry.workSetCount
    val isBusy = pendingAt != null
    val layout = PrototypeVariants.detailLayout

    fun log(at: GlobalSetIndex) {
        if (isBusy) return
        pendingAt = at
        scope.launch {
            delay(LOG_LATENCY_MS)
            val set = PrototypeStore.entry(entryIndex).setAt(at)
            val fields = promptFieldsFor(set, PrototypeVariants.worstCase)
            if (fields.isEmpty()) {
                PrototypeStore.completeSet(entryIndex, at)
            } else {
                onNeedsPrompt(at)
            }
            pendingAt = null
        }
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (layout != DetailLayout.ROW) {
                LogEdgeButton(
                    buttonSize = if (layout == DetailLayout.HERO) {
                        EdgeButtonSize.Small
                    } else {
                        EdgeButtonSize.Medium
                    },
                    enabled = next != null && !isBusy,
                    isBusy = isBusy,
                    hasNext = next != null,
                    onClick = { next?.let { log(it.at) } },
                )
            }
        },
    ) { contentPadding ->
        when (layout) {
            DetailLayout.HERO -> HeroContent(
                entry = entry,
                positionedSets = positionedSets,
                next = next,
                total = total,
                pendingAt = pendingAt,
                listState = listState,
                contentPadding = contentPadding,
                scope = scope,
            )

            DetailLayout.ANCHORED -> AnchoredContent(
                entry = entry,
                positionedSets = positionedSets,
                next = next,
                total = total,
                pendingAt = pendingAt,
                listState = listState,
                contentPadding = contentPadding,
            )

            DetailLayout.ROW -> RowControlContent(
                entry = entry,
                positionedSets = positionedSets,
                next = next,
                total = total,
                pendingAt = pendingAt,
                listState = listState,
                contentPadding = contentPadding,
                onLog = { at -> log(at) },
            )
        }
    }
}

@Composable
private fun LogEdgeButton(
    buttonSize: EdgeButtonSize,
    enabled: Boolean,
    isBusy: Boolean,
    hasNext: Boolean,
    onClick: () -> Unit,
) {
    EdgeButton(
        onClick = onClick,
        buttonSize = buttonSize,
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

/**
 * The hero card lives outside the lazy list on purpose: as item 0 it scrolled away on long
 * exercises, which defeated the entire point of the variant.
 */
@Composable
private fun HeroContent(
    entry: WatchEntry,
    positionedSets: List<PositionedSet>,
    next: PositionedSet?,
    total: Int,
    pendingAt: GlobalSetIndex?,
    listState: TransformingLazyColumnState,
    contentPadding: PaddingValues,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val spec = rememberTransformationSpec()
    val nextListIndex = next?.let { it.at.pos + 1 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.height(12.dp))
        HeroCard(exerciseName = entry.name, next = next, total = total)
        SetProgressStrip(
            sets = entry.sets,
            currentPos = next?.at?.pos,
            onClick = {
                if (nextListIndex != null) {
                    scope.launch { listState.animateScrollToItem(nextListIndex) }
                }
            },
        )
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(positionedSets, key = { it.at.pos }) { positioned ->
                SetRow(
                    positioned = positioned,
                    total = total,
                    isNext = next?.at?.pos == positioned.at.pos,
                    isPending = pendingAt?.pos == positioned.at.pos,
                    showRowButton = false,
                    compact = true,
                    spec = spec,
                    itemScope = this,
                    onLog = {},
                )
            }
            // Last, not first: the review strip is only ~25-35dp tall, so a 22dp badge at the
            // top would fill it entirely and the upcoming sets would never be visible.
            item {
                VariantBadge(
                    text = PrototypeVariants.badge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec),
                )
            }
        }
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
            color = LiftosaurColor.textPrimary,
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

/**
 * Keeps the plain list, but re-asserts the scroll once the user settles so the target row
 * cannot be parked off-screen.
 */
@Composable
private fun AnchoredContent(
    entry: WatchEntry,
    positionedSets: List<PositionedSet>,
    next: PositionedSet?,
    total: Int,
    pendingAt: GlobalSetIndex?,
    listState: TransformingLazyColumnState,
    contentPadding: PaddingValues,
) {
    val spec = rememberTransformationSpec()
    val flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(state = listState)
    val rotaryBehavior = RotaryScrollableDefaults.snapBehavior(listState)
    val targetIndex = next?.let { it.at.pos + 1 }

    LaunchedEffect(listState, targetIndex) {
        if (targetIndex == null) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) {
                delay(REASSERT_IDLE_MS)
                val visible = listState.layoutInfo.visibleItems.any { it.index == targetIndex }
                if (!visible) {
                    listState.animateScrollToItem(targetIndex)
                }
            }
        }
    }

    TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        flingBehavior = flingBehavior,
        rotaryScrollableBehavior = rotaryBehavior,
    ) {
        item {
            DetailHeader(name = entry.name, modifier = Modifier.transformedHeight(this, spec))
        }
        items(positionedSets, key = { it.at.pos }) { positioned ->
            SetRow(
                positioned = positioned,
                total = total,
                isNext = next?.at?.pos == positioned.at.pos,
                isPending = pendingAt?.pos == positioned.at.pos,
                showRowButton = false,
                compact = false,
                spec = spec,
                itemScope = this,
                onLog = {},
            )
        }
    }
}

@Composable
private fun RowControlContent(
    entry: WatchEntry,
    positionedSets: List<PositionedSet>,
    next: PositionedSet?,
    total: Int,
    pendingAt: GlobalSetIndex?,
    listState: TransformingLazyColumnState,
    contentPadding: PaddingValues,
    onLog: (GlobalSetIndex) -> Unit,
) {
    val spec = rememberTransformationSpec()
    val flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(state = listState)
    val rotaryBehavior = RotaryScrollableDefaults.snapBehavior(listState)

    TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        flingBehavior = flingBehavior,
        rotaryScrollableBehavior = rotaryBehavior,
    ) {
        item {
            DetailHeader(name = entry.name, modifier = Modifier.transformedHeight(this, spec))
        }
        items(positionedSets, key = { it.at.pos }) { positioned ->
            SetRow(
                positioned = positioned,
                total = total,
                isNext = next?.at?.pos == positioned.at.pos,
                isPending = pendingAt?.pos == positioned.at.pos,
                showRowButton = true,
                compact = false,
                spec = spec,
                itemScope = this,
                onLog = { onLog(positioned.at) },
            )
        }
    }
}

@Composable
private fun DetailHeader(name: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = name,
            modifier = Modifier.fillMaxWidth(),
            color = LiftosaurColor.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        VariantBadge(
            text = PrototypeVariants.badge,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetRow(
    positioned: PositionedSet,
    total: Int,
    isNext: Boolean,
    isPending: Boolean,
    showRowButton: Boolean,
    compact: Boolean,
    spec: TransformationSpec,
    itemScope: TransformingLazyColumnItemScope,
    onLog: () -> Unit,
) {
    val set = positioned.set
    val container = when {
        isNext -> LiftosaurColor.backgroundCardSelected
        set.isWarmup -> LiftosaurColor.backgroundSubtle
        else -> LiftosaurColor.backgroundSet
    }
    val shape = RoundedCornerShape(14.dp)
    val alpha = if (set.isWarmup) 0.55f else 1f

    Card(
        onClick = onLog,
        enabled = showRowButton && !set.isCompleted && !isPending,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = LiftosaurColor.textPrimary,
        ),
        border = if (isNext) BorderStroke(2.dp, LiftosaurColor.purple400) else null,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        transformation = itemScope.SurfaceTransformation(spec),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 34.dp else 48.dp)
            .transformedHeight(itemScope, spec),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = setTitle(positioned),
                    color = LiftosaurColor.textPrimary.copy(alpha = alpha),
                    fontSize = if (set.isWarmup) 12.sp else if (compact) 13.sp else 15.sp,
                    fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Text(
                        text = setDetailCaption(positioned, total),
                        color = LiftosaurColor.textSecondary.copy(alpha = alpha),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showRowButton && !set.isCompleted) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isPending, onClick = onLog),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPending) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(LiftosaurColor.buttonPrimary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✓",
                                color = LiftosaurColor.buttonPrimaryLabel,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(if (compact) 14.dp else 20.dp)
                        .clip(CircleShape)
                        .background(statusColor(set.status, set.isWarmup)),
                )
            }
        }
    }
}

private fun setDetailCaption(positioned: PositionedSet, total: Int): String {
    val set = positioned.set
    val base = setCaption(positioned, total)
    val plates = set.plates
    return if (!plates.isNullOrEmpty()) "$base  $plates" else base
}
