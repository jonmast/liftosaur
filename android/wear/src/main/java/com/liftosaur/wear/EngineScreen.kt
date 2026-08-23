package com.liftosaur.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.liftosaur.wear.engine.EngineDispatcher
import com.liftosaur.wear.engine.EngineSelfTest
import kotlinx.coroutines.withContext

/**
 * Build identity + engine acceptance numbers, on a reachable screen.
 *
 * The identity half is not cosmetic. `versionCode` is pinned at 1 forever (the upgrade
 * channel is `adb install -r`, not a store listing) and the watch is a passive mirror of
 * phone state — so without a visible build fingerprint, "the watch is showing the wrong
 * thing" is ambiguous between stale data and stale code (ticket 10).
 */
@Composable
fun EngineScreen() {
    val context = LocalContext.current
    var result by remember { mutableStateOf<EngineSelfTest.Result?>(null) }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // Every engine touch happens on the engine thread — it owns the runtime and context
        // for the process lifetime, and nothing else may enter them.
        result = withContext(EngineDispatcher.dispatcher) { EngineSelfTest.run(context) }
        running = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Engine",
            style = MaterialTheme.typography.titleMedium,
            color = LiftosaurColor.textPrimary,
        )

        val r = result
        if (running || r == null) {
            Text(
                text = "running…",
                style = MaterialTheme.typography.bodySmall,
                color = LiftosaurColor.textSecondary,
            )
            return@Column
        }

        Row2("bundle", r.bundleSha)
        Row2("commit", r.commitHash)
        Row2("cold", "${r.coldStartCpuMs}ms", budgetOk = r.coldStartCpuMs in 0..1500)
        Row2("warm", "${r.warmCallCpuMs}ms", budgetOk = r.warmCallCpuMs in 0..50)
        Row2("anon", "${r.engineInitAnonKb}KB", budgetOk = r.engineInitAnonKb in 0..8192)
        Row2("malloc", "${r.mallocSizeBytes / 1024}KB")
        Row2(
            "call",
            if (r.firstCallOk) "OK" else "FAIL",
            budgetOk = r.firstCallOk,
        )
        Row2(
            "oom",
            if (r.memoryLimitTripOk) "clean" else "FAIL",
            budgetOk = r.memoryLimitTripOk,
        )

        r.failure?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = LiftosaurColor.red400,
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun Row2(label: String, value: String, budgetOk: Boolean? = null) {
    Text(
        text = "$label  $value",
        style = MaterialTheme.typography.bodySmall,
        color = when (budgetOk) {
            true -> LiftosaurColor.green400
            false -> LiftosaurColor.red400
            null -> LiftosaurColor.textSecondary
        },
        fontSize = 12.sp,
    )
}
