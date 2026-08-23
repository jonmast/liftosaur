package com.liftosaur.wear.engine

import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * The v1 JS call surface — 15 of the bundle's 42 methods, typed (spec §2.3).
 *
 * Every method here must be called on [EngineDispatcher]; [WatchStorageRepository] is what
 * enforces that, and is the only thing that should call this class in production. The split
 * is deliberate: this file knows the *bundle's* contract (argument order, envelope shape,
 * index spaces) and nothing about state; the repository owns state and knows nothing about
 * envelopes.
 *
 * ### Storage is opaque
 * Storage crosses as [ByteArray] and is never parsed here. It is handed back to the bundle
 * verbatim, so parsing it would be pure cost plus a drift surface against upstream's
 * `IStorage`.
 *
 * ### Errors are data, not exceptions
 * All 42 bundle methods wrap their body in try/catch and stringify failures into the envelope,
 * so a JS-level failure arrives as [CallResult.Failure], not a throw. Only an *engine*-level
 * failure (OOM, stack overflow) throws across JNI, and that is fatal-to-the-call, never
 * fatal-to-the-process — see [callRaw].
 */
object WatchJs {
    private const val TAG = "WatchJs"

    /**
     * The outcome of a JS call.
     *
     * [Failure] covers both `{"success":false}` envelopes and engine-level exceptions, because
     * the caller's obligation is identical for both: don't persist, tell the user. Which one
     * happened is in the log, and in [Failure.engineLevel] for the tests that care.
     */
    sealed interface CallResult<out T> {
        data class Success<T>(val value: T) : CallResult<T>
        data class Failure(val error: String, val engineLevel: Boolean = false) :
            CallResult<Nothing>
    }

    /** A mutation's outcome: the new storage to persist, or a failure that must not be. */
    sealed interface MutationResult {
        @JvmInline
        value class Success(val storage: ByteArray) : MutationResult
        data class Failure(val error: String, val engineLevel: Boolean = false) : MutationResult
    }

    // ---------------------------------------------------------------------------------------
    // Reads (4)
    // ---------------------------------------------------------------------------------------

    /** The workout that *would* start from the current program. Read-only; starts nothing. */
    fun getNextHistoryRecord(storage: ByteArray): CallResult<WatchWorkout?> =
        decodeEnvelope("getNextHistoryRecord", WatchWorkout.serializer()) {
            callRaw("getNextHistoryRecord", storage)
        }

    /**
     * The in-progress workout, or null when none is active.
     *
     * Null here is `{"success":true}` with no `data` key — "nothing to show", not an error.
     * `JSON.stringify` drops `data: undefined`, so the key is simply absent (spec §2.3).
     */
    fun getProgress(storage: ByteArray): CallResult<WatchWorkout?> =
        decodeEnvelope("getProgress", WatchWorkout.serializer()) { callRaw("getProgress", storage) }

    fun hasProgram(storage: ByteArray): CallResult<Boolean> =
        when (val r = decodeEnvelope("hasProgram", HasProgram.serializer()) { callRaw("hasProgram", storage) }) {
            is CallResult.Success -> CallResult.Success(r.value?.hasProgram ?: false)
            is CallResult.Failure -> r
        }

    /**
     * The pending set-completion prompt, or null when none is pending.
     *
     * ⚠️ **Poll this after every [completeSet].** A prompt-requiring set is not completed by
     * `completeSet` — it returns `success:true`, leaves the set untouched, and attaches the
     * modal to storage. Only [completeSetWithAmrap] completes it. And because `completeSet`
     * *clears* any pending modal, calling it again without draining this first silently
     * discards the user's prompt (spec §2.3).
     */
    fun getAmrapModal(storage: ByteArray): CallResult<WatchAmrapModal?> =
        decodeEnvelope("getAmrapModal", WatchAmrapModal.serializer()) { callRaw("getAmrapModal", storage) }

    // ---------------------------------------------------------------------------------------
    // Mutations (6) — each returns new storage the caller must persist only on success
    // ---------------------------------------------------------------------------------------

    fun startWorkout(storage: ByteArray, deviceId: String): MutationResult =
        mutate("startWorkout", storage, jsonArgs(deviceId))

    /**
     * Completes the set at [at] within entry [entryIndex].
     *
     * [at] is the position in the flattened `[...warmupSets, ...sets]` array — the type exists
     * to stop [WatchSet.index] or a work-set index being passed here, both of which complete a
     * different set and still return success.
     *
     * A `Success` does **not** mean the set was completed: if it requires a prompt, the set is
     * untouched and a modal is now attached. Always follow with [getAmrapModal].
     */
    fun completeSet(
        storage: ByteArray,
        deviceId: String,
        entryIndex: Int,
        at: GlobalSetIndex,
    ): MutationResult = mutate("completeSet", storage, jsonArgs(deviceId, entryIndex, at.pos))

    /**
     * Resolves the pending prompt with the user's answers.
     *
     * Takes no index: it reads the pending modal out of storage, which is why the work-set/
     * array-position distinction cannot bite here. Nulls mean "field not asked for".
     */
    fun completeSetWithAmrap(
        storage: ByteArray,
        deviceId: String,
        completedReps: Int? = null,
        completedRepsLeft: Int? = null,
        completedWeight: Double? = null,
        completedRpe: Double? = null,
        userPromptedVarsJson: String? = null,
    ): MutationResult = mutate(
        "completeSetWithAmrap",
        storage,
        jsonArgs(
            deviceId,
            completedReps,
            completedRepsLeft,
            completedWeight,
            completedRpe,
            userPromptedVarsJson,
        ),
    )

    /** Moves the shown exercise. A no-op in the bundle when unchanged, so it is safe to spam. */
    fun setCurrentEntryIndex(storage: ByteArray, deviceId: String, entryIndex: Int): MutationResult =
        mutate("setCurrentEntryIndex", storage, jsonArgs(deviceId, entryIndex))

    fun finishWorkout(storage: ByteArray, deviceId: String): MutationResult =
        mutate("finishWorkout", storage, jsonArgs(deviceId))

    fun discardWorkout(storage: ByteArray, deviceId: String): MutationResult =
        mutate("discardWorkout", storage, jsonArgs(deviceId))

    // ---------------------------------------------------------------------------------------
    // Sync / lifecycle (5) — four of these break the standard envelope
    // ---------------------------------------------------------------------------------------

    /**
     * Change *detector* only: returns a raw `IStorageUpdate2`, with **no envelope**.
     *
     * The sync layer sends full filtered storage regardless (spec §2.5); this exists to answer
     * "did anything change?" without diffing 34KB in Kotlin. Failure is signalled by an
     * `error` key rather than `success:false`.
     */
    fun prepareSync(
        storage: ByteArray,
        lastSyncedStorage: ByteArray,
        deviceId: String,
    ): CallResult<ByteArray> = rawEnvelopeless("prepareSync", storage, lastSyncedStorage, deviceId)

    /**
     * Vector-clock merge. Returns a raw `IStorage`, with **no envelope**.
     *
     * Neither side is authoritative — `_versions` decides, and the Wear deviceId is just
     * another device (spec §2.5).
     */
    fun mergeStorage(
        storage: ByteArray,
        incomingStorage: ByteArray,
        deviceId: String,
    ): CallResult<ByteArray> = rawEnvelopeless("mergeStorage", storage, incomingStorage, deviceId)

    /**
     * Runs pending migrations.
     *
     * Enveloped, but `data: null` means **"no migration needed"** — a success, not an error.
     * Returns null in that case; a non-null result is new storage that must be persisted, and
     * is an *external* write as far as the bundle's cache is concerned.
     */
    fun runMigrations(storage: ByteArray): CallResult<ByteArray?> {
        val raw = try {
            callRaw("runMigrations", storage)
        } catch (e: Throwable) {
            return engineFailure("runMigrations", e)
        }
        return try {
            val obj = WatchJson.parseToJsonElement(raw.decodeToString()) as JsonObject
            if ((obj["success"] as? JsonPrimitive)?.content != "true") {
                CallResult.Failure((obj["error"] as? JsonPrimitive)?.content ?: "unknown error")
            } else {
                val data = obj["data"]
                if (data == null || data is JsonNull) {
                    CallResult.Success(null)
                } else {
                    CallResult.Success(data.toString().encodeToByteArray())
                }
            }
        } catch (e: Exception) {
            CallResult.Failure("runMigrations: unparseable result: ${e.message}")
        }
    }

    /** Returns a **bare string**, not an envelope, and not JSON. */
    fun getLatestMigrationVersion(): CallResult<String> = try {
        CallResult.Success(callRaw("getLatestMigrationVersion", EMPTY).decodeToString())
    } catch (e: Throwable) {
        engineFailure("getLatestMigrationVersion", e)
    }

    /**
     * Clears the bundle's module-scope caches. Returns nothing at all.
     *
     * Must be called before any call whose storage did not come out of the bundle itself. The
     * bundle's `cachedStorage` is **not keyed by content**, so passing different storage
     * without this silently returns the *first* storage — see [WatchStorageRepository], which
     * makes calling it structural rather than a rule to remember.
     */
    fun invalidateStorageCache() {
        try {
            callRaw("invalidateStorageCache", EMPTY)
        } catch (e: Throwable) {
            Log.e(TAG, "invalidateStorageCache failed", e)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private val EMPTY = ByteArray(0)

    private fun callRaw(
        method: String,
        storage: ByteArray,
        storage2: ByteArray? = null,
        extraArgsJson: String? = null,
    ): ByteArray = LiftosaurEngine.call(method, storage, storage2, extraArgsJson)

    private fun <T> engineFailure(method: String, e: Throwable): CallResult<T> {
        // An engine-level exception — OOM, stack overflow, a missing global. Fatal to this
        // call only: the runtime unwinds an OOM as an ordinary JS exception and stays usable
        // (ticket 12), so killing the process here would turn a recoverable call into a crash.
        Log.e(TAG, "$method: engine-level failure", e)
        return CallResult.Failure(e.message ?: e.toString(), engineLevel = true)
    }

    /**
     * Parses a standard `{success, data?, error?}` envelope.
     *
     * A missing `data` key on success decodes to null rather than failing — that is the
     * bundle's way of saying "nothing to show" and is indistinguishable from `data: undefined`
     * after `JSON.stringify`.
     *
     * The call is taken as a lambda so that an engine-level throw is caught here too, rather
     * than escaping past every read site. It is run in its own try: JNI raises a plain
     * RuntimeException, so folding it into the parse's `catch (Exception)` would report an
     * engine failure as a malformed envelope and lose [CallResult.Failure.engineLevel].
     */
    private fun <T> decodeEnvelope(
        method: String,
        serializer: KSerializer<T>,
        call: () -> ByteArray,
    ): CallResult<T?> {
        val raw = try {
            call()
        } catch (e: Throwable) {
            return engineFailure(method, e)
        }
        return try {
            val obj = WatchJson.parseToJsonElement(raw.decodeToString()) as JsonObject
            val success = (obj["success"] as? JsonPrimitive)?.content == "true"
            if (!success) {
                val error = (obj["error"] as? JsonPrimitive)?.content ?: "unknown error"
                Log.e(TAG, "$method failed: $error")
                CallResult.Failure(error)
            } else {
                val data: JsonElement? = obj["data"]
                if (data == null || data is JsonNull) {
                    CallResult.Success(null)
                } else {
                    CallResult.Success(WatchJson.decodeFromJsonElement(serializer, data))
                }
            }
        } catch (e: SerializationException) {
            Log.e(TAG, "$method: could not decode result", e)
            CallResult.Failure("$method: could not decode result: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "$method: unexpected result shape", e)
            CallResult.Failure("$method: unexpected result shape: ${e.message}")
        }
    }

    private fun mutate(method: String, storage: ByteArray, extraArgsJson: String): MutationResult {
        val raw = try {
            callRaw(method, storage, extraArgsJson = extraArgsJson)
        } catch (e: Throwable) {
            Log.e(TAG, "$method: engine-level failure", e)
            return MutationResult.Failure(e.message ?: e.toString(), engineLevel = true)
        }
        return try {
            val obj = WatchJson.parseToJsonElement(raw.decodeToString()) as JsonObject
            if ((obj["success"] as? JsonPrimitive)?.content != "true") {
                val error = (obj["error"] as? JsonPrimitive)?.content ?: "unknown error"
                Log.e(TAG, "$method failed: $error")
                // Deliberately no storage on this path: a failed mutation must leave the
                // persisted storage untouched (spec §2.3).
                MutationResult.Failure(error)
            } else {
                val data = obj["data"]
                    ?: return MutationResult.Failure("$method returned success with no storage")
                MutationResult.Success(data.toString().encodeToByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "$method: unexpected result shape", e)
            MutationResult.Failure("$method: unexpected result shape: ${e.message}")
        }
    }

    /** For the two sync methods that return a bare document with an `error` key on failure. */
    private fun rawEnvelopeless(
        method: String,
        storage: ByteArray,
        storage2: ByteArray,
        deviceId: String,
    ): CallResult<ByteArray> {
        val raw = try {
            callRaw(method, storage, storage2, jsonArgs(deviceId))
        } catch (e: Throwable) {
            return engineFailure(method, e)
        }
        return try {
            val obj = WatchJson.parseToJsonElement(raw.decodeToString()) as JsonObject
            val error = (obj["error"] as? JsonPrimitive)?.content
            if (error != null) {
                Log.e(TAG, "$method failed: $error")
                CallResult.Failure(error)
            } else {
                CallResult.Success(raw)
            }
        } catch (e: Exception) {
            CallResult.Failure("$method: unexpected result shape: ${e.message}")
        }
    }

    /**
     * Builds the JSON array spread into argv after storage.
     *
     * Trailing nulls are kept rather than trimmed: the bundle's optional parameters are
     * positional, so dropping a null would shift every later argument left.
     */
    private fun jsonArgs(vararg args: Any?): String = buildJsonArray {
        for (arg in args) {
            add(
                when (arg) {
                    null -> JsonNull
                    is String -> JsonPrimitive(arg)
                    is Int -> JsonPrimitive(arg)
                    is Long -> JsonPrimitive(arg)
                    is Double -> JsonPrimitive(arg)
                    is Boolean -> JsonPrimitive(arg)
                    else -> error("unsupported JS argument type: ${arg::class.simpleName}")
                },
            )
        }
    }.toString()
}
