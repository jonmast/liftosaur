package com.liftosaur.wear.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.liftosaur.wear.AppContainer
import com.liftosaur.wear.engine.EngineDispatcher
import com.liftosaur.wear.engine.LiftosaurEngine
import com.liftosaur.wear.engine.WatchJs
import com.liftosaur.wear.engine.WatchStorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Applies the phone's `/storage` DataItem to watch storage (spec §2.5).
 *
 * The interesting decisions live here rather than in the service, because there are two ways
 * in: an `onDataChanged` callback, and a cold-start catch-up read ([applyLatest]). They must
 * behave identically, and the catch-up path is not optional — a DataItem fires `onDataChanged`
 * **once**, so a process killed mid-apply, or an app installed after the phone's last put,
 * would otherwise never see storage that is sitting right there in the Data Layer.
 */
object PhoneStorageSync {
    private const val TAG = "PhoneStorageSync"
    private const val PREFS = "wear-sync"
    private const val KEY_APPLIED_EPOCH = "appliedAccountEpoch"
    private const val READ_TIMEOUT_SECONDS = 10L

    private val _phoneActiveWorkoutStartTime = MutableStateFlow<Long?>(null)

    /**
     * The phone's `activeWorkoutStartTime`, as of the last applied `/storage` (spec §2.5).
     *
     * Published straight off the plaintext header, before any merge runs, so the watch can
     * react to the phone finishing or discarding a workout **with zero JS on the path** — no
     * gunzip, no `JSON.parse`, no bundle call. The merge answers the same question a few
     * hundred milliseconds later and would usually be enough; this exists for the cases where
     * it isn't — a merge that fails keeps local storage, which would leave the wrist showing a
     * workout the phone has already ended.
     *
     * Null-to-non-null carries no meaning here: a workout the *watch* started is active long
     * before the phone has merged it and put a header saying so.
     */
    val phoneActiveWorkoutStartTime: StateFlow<Long?> = _phoneActiveWorkoutStartTime.asStateFlow()

    /**
     * Publishes a header value without a DataItem — for tests only.
     *
     * The alternative is driving [apply] with a synthetic payload, which pulls in the process
     * -wide [AppContainer] repository and the engine, and would test the merge rather than the
     * signal.
     */
    internal fun publishPhoneActiveWorkoutStartTime(value: Long?) {
        _phoneActiveWorkoutStartTime.value = value
    }

    /** The parsed DataItem: the header stays plaintext so this costs no JS. */
    data class Payload(
        val seq: Long,
        val storageJson: ByteArray,
        val accountEpoch: String,
        val activeWorkoutStartTime: Long?,
    )

    fun parse(dataMap: DataMap): Payload? {
        val gzipped = dataMap.getByteArray(WearProtocol.KEY_Z) ?: run {
            Log.e(TAG, "/storage without a payload")
            return null
        }
        val json = try {
            GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "could not gunzip /storage", e)
            return null
        }
        return Payload(
            seq = dataMap.getLong(WearProtocol.KEY_SEQ, 0L),
            storageJson = json,
            accountEpoch = dataMap.getString(WearProtocol.KEY_ACCOUNT_EPOCH) ?: "",
            activeWorkoutStartTime =
            if (dataMap.containsKey(WearProtocol.KEY_ACTIVE_WORKOUT_START_TIME)) {
                dataMap.getLong(WearProtocol.KEY_ACTIVE_WORKOUT_START_TIME)
            } else {
                null
            },
        )
    }

    /**
     * Applies [payload], merging with local storage unless the account changed.
     *
     * Must run off the main thread; the JS calls inside are dispatched to the engine thread.
     */
    suspend fun apply(context: Context, payload: Payload) {
        // First, and outside the try/merge path: this is the zero-JS signal, and it is most
        // useful exactly when the expensive part below fails.
        _phoneActiveWorkoutStartTime.value = payload.activeWorkoutStartTime

        val repository = AppContainer.repository(context)
        withContext(EngineDispatcher.dispatcher) { LiftosaurEngine.initialize(context) }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val appliedEpoch = prefs.getString(KEY_APPLIED_EPOCH, null)

        // Inequality, never ordering: the epoch is a hash, so "newer" is not a question it can
        // answer. An empty epoch (a phone that somehow sent none) is treated as "same account"
        // rather than as a wipe -- destroying watch-local sets on a malformed payload is the
        // worse of the two failures.
        if (payload.accountEpoch.isNotEmpty() && appliedEpoch != null && appliedEpoch != payload.accountEpoch) {
            Log.i(TAG, "account changed ($appliedEpoch -> ${payload.accountEpoch}), wiping before apply")
            repository.clear()
        }

        // Loading first matters on the cold-start path: the process may have been started by the
        // listener service with nothing in memory, and merging against an empty repository would
        // silently drop sets logged on the wrist since the last sync.
        if (repository.storage.value == null) {
            repository.load()
        }

        val local = repository.storage.value
        if (local == null) {
            Log.i(TAG, "first storage from phone: ${payload.storageJson.size}B, seq=${payload.seq}")
            repository.setExternal(payload.storageJson)
        } else {
            merge(repository, payload)
        }

        if (payload.accountEpoch.isNotEmpty() && appliedEpoch != payload.accountEpoch) {
            // Written only after a successful apply, and read back on cold start, so re-reading
            // the same DataItem after a restart compares equal and cannot re-wipe.
            prefs.edit().putString(KEY_APPLIED_EPOCH, payload.accountEpoch).apply()
        }

        migrate(repository)
    }

    /**
     * Merges through the `_versions` vector clock — never a replace.
     *
     * Neither side is authoritative (spec §2.5): a set logged on the wrist while the phone was
     * editing the program must survive, and "absence is not deletion" means the watch never
     * authors a tombstone by dropping something it didn't know about.
     *
     * The re-merge is the iOS trap ported: a `completeSet` can land while the ~200ms merge is
     * running, and blindly storing the merge result would discard it. Comparing the storage we
     * merged *from* against what is current is what detects it.
     */
    private suspend fun merge(repository: WatchStorageRepository, payload: Payload) {
        var attempts = 0
        while (attempts < 3) {
            attempts++
            val before = repository.storage.value ?: return
            val merged = repository.read { current ->
                WatchJs.mergeStorage(current, payload.storageJson, repository.deviceId)
            }
            when (merged) {
                is WatchJs.CallResult.Success -> {
                    if (repository.storage.value !== before) {
                        Log.i(TAG, "storage changed during merge, re-merging (attempt $attempts)")
                        continue
                    }
                    Log.i(TAG, "merged ${payload.storageJson.size}B from phone, seq=${payload.seq}")
                    repository.setExternal(merged.value)
                    return
                }
                is WatchJs.CallResult.Failure -> {
                    // Deliberately keeps local storage. A failed merge that fell back to the
                    // incoming copy would delete every set logged on the wrist since the last
                    // sync -- exactly the data the merge exists to protect.
                    Log.e(TAG, "merge failed, keeping local storage: ${merged.error}")
                    return
                }
            }
        }
        Log.e(TAG, "gave up re-merging after $attempts attempts; local storage kept")
    }

    /**
     * Runs migrations after applying, matching watchOS.
     *
     * The phone may be on an older schema than this build (they update independently), and the
     * bundle's readers assume the latest. A null result means "nothing to migrate", which is
     * the common case and costs one cheap JS call.
     */
    private suspend fun migrate(repository: WatchStorageRepository) {
        val result = repository.read { WatchJs.runMigrations(it) }
        when (result) {
            is WatchJs.CallResult.Success -> result.value?.let {
                Log.i(TAG, "applied migrations (${it.size}B)")
                repository.setExternal(it)
            }
            is WatchJs.CallResult.Failure -> Log.e(TAG, "migrations failed: ${result.error}")
        }
    }

    /**
     * Reads whatever `/storage` currently holds and applies it. Call at app start.
     *
     * This is the recovery path for every missed event: `onDataChanged` fires once per change,
     * so anything the watch was not alive and healthy for is only recoverable by asking the
     * Data Layer what the current item is. It is also why there is no "request storage" message
     * in this protocol -- the phone's latest put is always still there to be read.
     */
    suspend fun applyLatest(context: Context) {
        val payload = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val uri = Uri.parse("wear://*${WearProtocol.PATH_STORAGE}")
                val buffer = Tasks.await(
                    Wearable.getDataClient(context).getDataItems(uri),
                    READ_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
                try {
                    buffer.maxByOrNull { DataMapItem.fromDataItem(it).dataMap.getLong(WearProtocol.KEY_SEQ, 0L) }
                        ?.let { parse(DataMapItem.fromDataItem(it).dataMap) }
                } finally {
                    buffer.release()
                }
            } catch (e: Exception) {
                Log.i(TAG, "cold-start /storage read failed: ${e.message}")
                null
            }
        } ?: return
        apply(context, payload)
    }
}
