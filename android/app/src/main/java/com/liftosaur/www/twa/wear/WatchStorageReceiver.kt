package com.liftosaur.www.twa.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * The phone's half of the watch→phone loop: `/watch/storage` in, a JS merge event out.
 *
 * **The phone does not merge here.** The `_versions` vector clock lives in TypeScript
 * (`Storage_mergeStorage`, plus the `forceUpdateEntryIndex` handling in
 * `Thunk_handleWatchStorageMerge`), and re-implementing any of it in Kotlin would be a second
 * merge implementation for the same data — the one thing a CRDT cannot survive. So this class
 * unzips, and hands the bytes to the same `watchStorageMerge` event watchOS already raises.
 *
 * Two pieces of state, both **in-memory and process-scoped**:
 * - [lastSeq] suppresses a duplicate merge when the cold-start catch-up read finds the same
 *   item the listener service just delivered. It is deliberately not persisted: after a process
 *   restart, re-merging once is cheap and idempotent, while a persisted seq that got ahead of
 *   an actually-applied merge would drop watch-logged sets permanently.
 * - [lastActiveWorkoutStartTime] is what makes `endWorkout` derivable without a merge, and
 *   likewise only claims to know about edges seen in this process.
 */
object WatchStorageReceiver {
    private const val TAG = "WatchStorageReceiver"
    private const val READ_TIMEOUT_SECONDS = 10L

    @Volatile
    private var lastSeq: Long? = null

    @Volatile
    private var lastActiveWorkoutStartTime: Long? = null

    internal fun parse(dataMap: DataMap): InboundWatchStorage? = InboundWatchStorageDecoder.decode(
        gzipped = dataMap.getByteArray(WearProtocol.KEY_Z),
        seq = dataMap.getLong(WearProtocol.KEY_SEQ, 0L),
        deviceId = dataMap.getString(WearProtocol.KEY_DEVICE_ID),
        activeWorkoutStartTime =
        if (dataMap.containsKey(WearProtocol.KEY_ACTIVE_WORKOUT_START_TIME)) {
            dataMap.getLong(WearProtocol.KEY_ACTIVE_WORKOUT_START_TIME)
        } else {
            null
        },
    )

    /**
     * Raises the merge event for [inbound] (and `endWorkout` when the watch ended the workout).
     *
     * Emission — not application: [WatchEventDispatcher] buffers when JS is not subscribed, so
     * an item delivered to a dead React context is replayed rather than lost. Which is also why
     * ordering matters here: the merge goes first, so that by the time JS tears down the live
     * activity, the storage that says the workout is over has already been merged.
     */
    internal fun deliver(inbound: InboundWatchStorage) {
        if (lastSeq == inbound.seq) {
            Log.i(TAG, "skipping already-delivered seq=${inbound.seq}")
            return
        }
        val previousStartTime = lastActiveWorkoutStartTime
        lastSeq = inbound.seq
        lastActiveWorkoutStartTime = inbound.activeWorkoutStartTime

        // `forceUpdateEntryIndex` is false because the phone decides it, not the watch:
        // `Thunk_handleWatchStorageMerge` flips it when the merge actually moved the shown
        // exercise, which is knowable only after merging. The field stays on the event because
        // watchOS populates it; on Android nothing reads it.
        WatchEventDispatcher.emitStorageMerge(
            storageJson = inbound.storageJson,
            deviceId = inbound.deviceId,
            forceUpdateEntryIndex = false,
        )
        // Pairs with the watch's "put seq=N …" line: subtracting the two elapsedRealtime stamps
        // is the watch→phone half of the setUrgent() delivery latency (spec §4).
        Log.i(
            TAG,
            "delivered seq=${inbound.seq} ${inbound.storageJson.length}B from " +
                "${inbound.deviceId} at ${android.os.SystemClock.elapsedRealtime()}ms",
        )

        if (previousStartTime != null && inbound.activeWorkoutStartTime == null) {
            // The workout ended on the wrist. The merge above already carries that fact, but
            // the phone's *native* state — live activity, rest-timer notification, reminder —
            // is not derived from storage, so it needs telling. Zero JS to detect: the header
            // is plaintext outside the blob (spec §2.5).
            Log.i(TAG, "watch ended the workout")
            WatchEventDispatcher.emitEndWorkout()
        }
    }

    /**
     * Reads whatever `/watch/storage` currently holds and delivers it. Called when JS subscribes.
     *
     * `onDataChanged` fires once per change and only to a process the system chose to start, so
     * a phone that was killed while the watch was logging sets would otherwise never see them —
     * the watch has no reason to put again until the *next* mutation, which may be tomorrow.
     * Blocking Data Layer calls, so never call this on the main thread.
     */
    fun deliverLatest(context: Context) {
        val inbound = try {
            val uri = Uri.parse("wear://*${WearProtocol.PATH_WATCH_STORAGE}")
            val buffer = Tasks.await(
                Wearable.getDataClient(context).getDataItems(uri),
                READ_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            try {
                buffer
                    .mapNotNull { parse(DataMapItem.fromDataItem(it).dataMap) }
                    .maxByOrNull { it.seq }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            Log.i(TAG, "catch-up /watch/storage read failed: ${e.message}")
            null
        } ?: return
        deliver(inbound)
    }
}
