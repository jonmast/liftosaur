package com.liftosaur.wear.engine

import android.content.Context
import android.os.Process
import android.util.Log
import com.liftosaur.wear.BuildIdentity
import org.json.JSONObject
import java.io.File

/**
 * Ticket 02's acceptance evidence, run on the device and reported to logcat.
 *
 * This exists because the budgets in spec §4 are regression ceilings, not one-time
 * measurements — they need to be re-runnable on a real build, on the real watch, whenever the
 * engine or bundle moves. Screenshots of the watch are impossible (spec §3.5), so the
 * evidence has to be textual.
 *
 * Timing note: every duration here is **CPU time**, not wall clock. The watch suspends mid
 * call — measured at 0.7-5.4s inside a single bundle evaluation with the screen off — which
 * inflates wall-clock deltas by up to 6.5x and makes them useless as budget evidence
 * (tickets 04, 12).
 */
object EngineSelfTest {
    private const val TAG = "EngineSelfTest"

    data class Result(
        val bundleSha: String,
        val commitHash: String,
        val coldStartCpuMs: Long,
        val warmCallCpuMs: Long,
        val engineInitAnonKb: Long,
        val mallocSizeBytes: Long,
        val firstCallOk: Boolean,
        val memoryLimitTripOk: Boolean,
        val failure: String?,
    )

    private fun cpuTimeMs(): Long {
        // Running CPU time of the current thread — excludes time the device spent suspended,
        // unlike System.currentTimeMillis() or Date.now() in JS. (Os.clock_gettime with
        // CLOCK_THREAD_CPUTIME_ID would be the same reading, but is not public SDK.)
        return android.os.SystemClock.currentThreadTimeMillis()
    }

    /** Anonymous RSS for this process — the number the memory budgets are stated in. */
    private fun anonRssKb(): Long =
        runCatching {
            File("/proc/${Process.myPid()}/status").readLines()
                .firstOrNull { it.startsWith("RssAnon:") }
                ?.filter { it.isDigit() }
                ?.toLongOrNull() ?: -1L
        }.getOrDefault(-1L)

    fun run(context: Context): Result {
        val fixture = context.assets.open("fixture-storage.json").use { it.readBytes() }
        val anonBefore = anonRssKb()

        var failure: String? = null
        var firstCallOk = false
        var warmCallCpuMs = -1L

        val coldStart0 = cpuTimeMs()
        try {
            LiftosaurEngine.initialize(context)
        } catch (e: Throwable) {
            Log.e(TAG, "engine init failed", e)
            return Result(
                bundleSha = BuildIdentity.WATCH_BUNDLE_SHA_SHORT,
                commitHash = BuildIdentity.WATCH_BUNDLE_COMMIT_HASH,
                coldStartCpuMs = cpuTimeMs() - coldStart0,
                warmCallCpuMs = -1,
                engineInitAnonKb = anonRssKb() - anonBefore,
                mallocSizeBytes = -1,
                firstCallOk = false,
                memoryLimitTripOk = false,
                failure = "init: ${e.message}",
            )
        }

        // First real call, included in cold start: an engine that evaluates but cannot answer
        // is not a working engine.
        try {
            val raw = LiftosaurEngine.call("getNextHistoryRecord", fixture)
            val envelope = JSONObject(String(raw, Charsets.UTF_8))
            firstCallOk = envelope.optBoolean("success", false)
            if (!firstCallOk) failure = "getNextHistoryRecord: ${envelope.opt("error")}"
        } catch (e: Throwable) {
            Log.e(TAG, "first call failed", e)
            failure = "call: ${e.message}"
        }
        val coldStartCpuMs = cpuTimeMs() - coldStart0
        val engineInitAnonKb = anonRssKb() - anonBefore

        if (firstCallOk) {
            // Warm read: same call again, engine already hot.
            val warm0 = cpuTimeMs()
            runCatching { LiftosaurEngine.call("getNextHistoryRecord", fixture) }
                .onFailure { failure = failure ?: "warm call: ${it.message}" }
            warmCallCpuMs = cpuTimeMs() - warm0
        }

        // Prove a runaway surfaces as a caught error rather than an OOM kill: squeeze the
        // limit below what a call needs, confirm the call fails, then restore it.
        //
        // Safe to do in-process because QuickJS unwinds an OOM as an ordinary JS exception —
        // ticket 12 measured it landing as {"success":false,"error":"InternalError: out of
        // memory"} with no crash and a still-usable runtime. The final assertion here is that
        // the engine still answers correctly afterwards.
        var memoryLimitTripOk = false
        if (firstCallOk) {
            LiftosaurEngine.setMemoryLimit(LiftosaurEngine.mallocSize() + 64 * 1024)
            val tripped = runCatching { LiftosaurEngine.call("getNextHistoryRecord", fixture) }
                .fold(
                    onSuccess = { String(it, Charsets.UTF_8).contains("\"success\":false") },
                    onFailure = { true }, // surfaced as a thrown JS error — also clean
                )
            LiftosaurEngine.setMemoryLimit(LiftosaurEngine.MEMORY_LIMIT_BYTES)

            // The runtime must still work after the trip, or "clean" is a lie.
            val recovered = runCatching {
                val raw = LiftosaurEngine.call("getNextHistoryRecord", fixture)
                JSONObject(String(raw, Charsets.UTF_8)).optBoolean("success", false)
            }.getOrDefault(false)

            memoryLimitTripOk = tripped && recovered
            if (!memoryLimitTripOk) {
                failure = failure ?: "memory limit: tripped=$tripped recovered=$recovered"
            }
        }

        val result = Result(
            bundleSha = BuildIdentity.WATCH_BUNDLE_SHA_SHORT,
            commitHash = BuildIdentity.WATCH_BUNDLE_COMMIT_HASH,
            coldStartCpuMs = coldStartCpuMs,
            warmCallCpuMs = warmCallCpuMs,
            engineInitAnonKb = engineInitAnonKb,
            mallocSizeBytes = LiftosaurEngine.mallocSize(),
            firstCallOk = firstCallOk,
            memoryLimitTripOk = memoryLimitTripOk,
            failure = failure,
        )

        Log.i(TAG, "=== ticket 02 acceptance ===")
        Log.i(TAG, "bundle       ${result.bundleSha} commit ${result.commitHash}")
        Log.i(TAG, "cold start   ${result.coldStartCpuMs}ms CPU   (budget 1500)")
        Log.i(TAG, "warm call    ${result.warmCallCpuMs}ms CPU   (budget 50)")
        Log.i(TAG, "engine anon  ${result.engineInitAnonKb}KB       (budget 8192)")
        Log.i(TAG, "malloc_size  ${result.mallocSizeBytes} bytes")
        Log.i(TAG, "first call   ${if (result.firstCallOk) "OK" else "FAILED"}")
        Log.i(TAG, "mem limit    ${if (result.memoryLimitTripOk) "clean trip + recovery" else "FAILED"}")
        result.failure?.let { Log.e(TAG, "failure      $it") }

        return result
    }
}
