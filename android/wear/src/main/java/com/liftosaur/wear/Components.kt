package com.liftosaur.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Text
import com.liftosaur.wear.engine.WatchSet

private const val MAX_DOTS = 8

@Composable
fun SetStatusDots(sets: List<WatchSet>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sets.take(MAX_DOTS).forEach { set ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor(set.status, set.isWarmup)),
            )
        }
        if (sets.size > MAX_DOTS) {
            Text(
                text = "+${sets.size - MAX_DOTS}",
                color = LiftosaurColor.textSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Sizes itself down as the set count grows so a 10-set exercise still fits the ~136dp of usable
 * width at 192dp instead of clipping the last dots. Overflows above ~16 sets — a known ceiling.
 */
@Composable
fun SetProgressStrip(
    sets: List<WatchSet>,
    currentPos: Int?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val count = sets.size
    val dot = when {
        count <= 6 -> 8.dp
        count <= 10 -> 6.dp
        else -> 5.dp
    }
    val gap = when {
        count <= 6 -> 4.dp
        count <= 10 -> 3.dp
        else -> 2.dp
    }
    val ring = dot + 8.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sets.forEachIndexed { pos, set ->
                val color = statusColor(set.status, set.isWarmup)
                if (pos == currentPos) {
                    Box(
                        modifier = Modifier
                            .size(ring)
                            .border(2.dp, LiftosaurColor.purple400, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(dot - 1.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(dot)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = LiftosaurColor.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun TapRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = LiftosaurColor.backgroundCard,
    minHeight: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(minHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/**
 * A failed JS call, shown in place rather than as a dialog.
 *
 * Transient by construction: the controller clears it on the next successful action, and
 * storage is untouched, so there is nothing for the user to undo — only to retry.
 */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        color = LiftosaurColor.red400,
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun CenteredMessage(title: String, detail: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = LiftosaurColor.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            detail?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 4.dp),
                    color = LiftosaurColor.textSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                )
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}
