package com.liftosaur.wear.engine

import android.content.Context
import android.util.Log
import com.liftosaur.wear.BuildIdentity
import org.json.JSONObject

/**
 * Ticket 02's acceptance evidence, run on the device and reported to logcat.
 *
 * This exists because the budgets in spec §4 are regression ceilings, not one-time
 * measurements — they need to be re-runnable on a real build, on the real watch, whenever the
 * engine or bundle moves. Screenshots of the watch are impossible (spec §3.5), so the
 * evidence has to be textual.
 *
 * Scope: this covers the *engine* — cold start, a warm read, the standing memory cost, and the
 * clean-OOM property. The full spec §4 table, measured against the real synced storage rather
 * than a fixture, is [com.liftosaur.wear.PreShipBench] (ticket 07).
 *
 * Timing note: every duration here is **CPU time** ([CpuTime]), not wall clock.
 */
object EngineSelfTest {
    private const val TAG = "EngineSelfTest"

    data class Result(
        val bundleSha: String,
        val commitHash: String,
        /** Init + first call, both recorded where they happened. -1 when unavailable. */
        val coldStartCpuMs: Double,
        val warmCallCpuMs: Double,
        val engineInitAnonKb: Long,
        val mallocSizeBytes: Long,
        val storageBytes: Int,
        val usedRealStorage: Boolean,
        val firstCallOk: Boolean,
        val memoryLimitTripOk: Boolean,
        val failure: String?,
    )

    /**
     * @param storage the app's real storage, or null to fall back to the bundled fixture. Real
     *   is strongly preferred: the fixture is 34KB of one contrived program, while the numbers
     *   that matter are the ones a real account produces.
     */
    fun run(context: Context, storage: ByteArray? = null): Result {
        val usedRealStorage = storage != null
        val payload = storage ?: context.assets.open("fixture-storage.json").use { it.readBytes() }

        var failure: String? = null
        var firstCallOk = false
        var warmCallCpuMs = -1.0

        // Not measured here — read from where it happened. The engine is initialized by
        // WorkoutController at app launch, so `initialize` is an idempotent early return by the
        // time anything can ask about it, and timing that call would report ~0ms and read as a
        // comfortable pass (see LiftosaurEngine.ColdStart).
        try {
            LiftosaurEngine.initialize(context)
        } catch (e: Throwable) {
            Log.e(TAG, "engine init failed", e)
            return Result(
                bundleSha = BuildIdentity.WATCH_BUNDLE_SHA_SHORT,
                commitHash = BuildIdentity.WATCH_BUNDLE_COMMIT_HASH,
                coldStartCpuMs = -1.0,
                warmCallCpuMs = -1.0,
                engineInitAnonKb = -1,
                mallocSizeBytes = -1,
                storageBytes = payload.size,
                usedRealStorage = usedRealStorage,
                firstCallOk = false,
                memoryLimitTripOk = false,
                failure = "init: ${e.message}",
            )
        }

        // The bundle caches parsed storage at module scope and does NOT key it by content, so a
        // call with storage it has not seen returns the *previous* storage. Everything below
        // reads, and a read against the wrong document is a silently wrong measurement.
        WatchJs.invalidateStorageCache()

        try {
            val raw = LiftosaurEngine.call("getNextHistoryRecord", payload)
            val envelope = JSONObject(String(raw, Charsets.UTF_8))
            firstCallOk = envelope.optBoolean("success", false)
            if (!firstCallOk) failure = "getNextHistoryRecord: ${envelope.opt("error")}"
        } catch (e: Throwable) {
            Log.e(TAG, "first call failed", e)
            failure = "call: ${e.message}"
        }

        if (firstCallOk) {
            // Warm read: same call again, engine hot and storage already parsed and cached.
            val warm0 = CpuTime.nanos()
            runCatching { LiftosaurEngine.call("getNextHistoryRecord", payload) }
                .onFailure { failure = failure ?: "warm call: ${it.message}" }
            warmCallCpuMs = CpuTime.msOf(CpuTime.nanos() - warm0)
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
            val tripped = runCatching { LiftosaurEngine.call("getNextHistoryRecord", payload) }
                .fold(
                    onSuccess = { String(it, Charsets.UTF_8).contains("\"success\":false") },
                    onFailure = { true }, // surfaced as a thrown JS error — also clean
                )
            LiftosaurEngine.setMemoryLimit(LiftosaurEngine.MEMORY_LIMIT_BYTES)

            // The runtime must still work after the trip, or "clean" is a lie.
            val recovered = runCatching {
                val raw = LiftosaurEngine.call("getNextHistoryRecord", payload)
                JSONObject(String(raw, Charsets.UTF_8)).optBoolean("success", false)
            }.getOrDefault(false)

            memoryLimitTripOk = tripped && recovered
            if (!memoryLimitTripOk) {
                failure = failure ?: "memory limit: tripped=$tripped recovered=$recovered"
            }
        }

        val cold = LiftosaurEngine.coldStart
        val result = Result(
            bundleSha = BuildIdentity.WATCH_BUNDLE_SHA_SHORT,
            commitHash = BuildIdentity.WATCH_BUNDLE_COMMIT_HASH,
            coldStartCpuMs = cold.totalCpuMs,
            warmCallCpuMs = warmCallCpuMs,
            engineInitAnonKb = cold.initAnonKb,
            mallocSizeBytes = LiftosaurEngine.mallocSize(),
            storageBytes = payload.size,
            usedRealStorage = usedRealStorage,
            firstCallOk = firstCallOk,
            memoryLimitTripOk = memoryLimitTripOk,
            failure = failure,
        )

        Log.i(TAG, "=== ticket 02 acceptance ===")
        Log.i(TAG, "bundle       ${result.bundleSha} commit ${result.commitHash}")
        Log.i(TAG, "storage      ${result.storageBytes}B ${if (usedRealStorage) "(real)" else "(FIXTURE)"}")
        Log.i(
            TAG,
            "cold start   ${result.coldStartCpuMs}ms CPU   (budget 1500)" +
                "  [init ${cold.initCpuMs} + first call ${cold.firstCallCpuMs} via ${cold.method}]",
        )
        Log.i(TAG, "warm call    ${result.warmCallCpuMs}ms CPU   (budget 50)")
        Log.i(TAG, "engine anon  ${result.engineInitAnonKb}KB       (budget 8192)")
        Log.i(TAG, "malloc_size  ${result.mallocSizeBytes} bytes")
        Log.i(TAG, "first call   ${if (result.firstCallOk) "OK" else "FAILED"}")
        Log.i(TAG, "mem limit    ${if (result.memoryLimitTripOk) "clean trip + recovery" else "FAILED"}")
        result.failure?.let { Log.e(TAG, "failure      $it") }

        // Leave the bundle cache empty rather than holding whatever this test last passed in.
        // The next repository call re-parses the storage it supplies, which is always the
        // truth; leaving a populated cache behind would make that call's answer depend on
        // whether a human happened to open this screen.
        WatchJs.invalidateStorageCache()

        return result
    }
}
