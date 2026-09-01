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
    var bench by remember { mutableStateOf<PreShipBench.Result?>(null) }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val repository = AppContainer.repository(context)
        // Every engine touch happens on the engine thread — it owns the runtime and context
        // for the process lifetime, and nothing else may enter them.
        //
        // The self-test is given the app's *real* storage rather than the bundled fixture: the
        // fixture is one contrived 34KB program, and every number that turned out to matter is
        // a function of the account's actual size (ticket 07).
        result = withContext(EngineDispatcher.dispatcher) {
            EngineSelfTest.run(context, repository.storage.value)
        }
        // The full spec §4 table — a whole simulated workout, none of it persisted or sent.
        bench = PreShipBench.run(repository)
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
        Row2(
            "data",
            "${r.storageBytes / 1024}KB ${if (r.usedRealStorage) "real" else "fixture"}",
            // A fixture-based run is not evidence about this account — flagged rather than
            // silently reported as if it were (ticket 07).
            budgetOk = r.usedRealStorage,
        )
        Row2("call", if (r.firstCallOk) "OK" else "FAIL", budgetOk = r.firstCallOk)
        Row2("oom", if (r.memoryLimitTripOk) "clean" else "FAIL", budgetOk = r.memoryLimitTripOk)

        r.failure?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = LiftosaurColor.red400,
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
            )
        }

        val b = bench
        if (b == null) {
            Text(
                text = "benchmarking…",
                style = MaterialTheme.typography.bodySmall,
                color = LiftosaurColor.textSecondary,
                fontSize = 10.sp,
            )
            return@Column
        }

        Row2("cold", "${b.coldStart.totalCpuMs.round1()}ms", budgetOk = b.coldStartOk)
        Row2("warm", "${b.warmReadMedianMs.round1()}ms", budgetOk = b.warmReadOk)
        Row2("mutate", "${b.mutationMedianMs.round1()}ms", budgetOk = b.mutationOk)
        Row2("prompt", "${b.promptInteractionMs.round1()}ms")
        Row2("put", "${b.outboundBuildMedianMs.round1()}ms")
        Row2("anon", "${b.coldStart.initAnonKb}KB", budgetOk = b.engineAnonOk)
        Row2("peak", "${b.sessionPeakAnonKb / 1024}MB", budgetOk = b.sessionAnonOk)
        Row2("malloc", if (b.mallocTrendOk) "flat" else "RISING", budgetOk = b.mallocTrendOk)
        Row2("session", "${b.setsLogged} sets ${b.promptsAnswered} prompts")

        b.failure?.let {
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

/** Watch-screen width is the constraint, not precision — the log line carries the full value. */
private fun Double.round1(): String = if (this < 0) "n/a" else String.format("%.1f", this)

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
