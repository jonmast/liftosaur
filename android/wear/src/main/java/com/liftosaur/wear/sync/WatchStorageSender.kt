package com.liftosaur.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the watch→phone `/watch/storage` put: storage in, urgent DataItem out (spec §2.5).
 *
 * **Every mutation puts, with no debounce.** A debounce would be a window in which a logged set
 * exists only on the wrist, and the wrist is the device that suspends, drops off the network,
 * and gets taken off at the end of a workout. The radio cost it would save is ~8KB gzipped per
 * set (ticket 03), which is not worth owning that window.
 *
 * **Coalescing, not queueing.** The DataItem is replace-in-place at a fixed path, so an
 * intermediate state the phone would immediately overwrite is pure radio cost. [submit] keeps
 * only the newest storage in [latest]; the single-threaded [executor] drains it, and everything
 * submitted while a put is in flight collapses into one send. Full state each time, never a
 * delta — a fixed-path item is overwritten, so a missed delta would be permanent loss while a
 * missed full state is self-healing (ticket 08).
 *
 * [put] is injected so the coalescing and header logic can be tested without Google Play
 * services; [forContext] builds the real one.
 */
class WatchStorageSender internal constructor(
    private val deviceId: String,
    private val clock: () -> Long,
    private val put: (WatchOutboundStorage) -> Boolean,
) {
    companion object {
        private const val TAG = "WatchStorageSender"
        private const val PUT_TIMEOUT_SECONDS = 10L

        fun forContext(context: Context, deviceId: String): WatchStorageSender {
            val appContext = context.applicationContext
            return WatchStorageSender(
                deviceId = deviceId,
                clock = System::currentTimeMillis,
                put = { payload -> putDataItem(appContext, payload) },
            )
        }

        private fun putDataItem(context: Context, payload: WatchOutboundStorage): Boolean = try {
            val request = PutDataMapRequest.create(WearProtocol.PATH_WATCH_STORAGE).apply {
                dataMap.putLong(WearProtocol.KEY_SEQ, payload.seq)
                dataMap.putByteArray(WearProtocol.KEY_Z, payload.gzipped)
                dataMap.putString(WearProtocol.KEY_DEVICE_ID, payload.deviceId)
                payload.activeWorkoutStartTime?.let {
                    dataMap.putLong(WearProtocol.KEY_ACTIVE_WORKOUT_START_TIME, it)
                }
            }.asPutDataRequest().setUrgent()

            val started = android.os.SystemClock.elapsedRealtime()
            Tasks.await(
                Wearable.getDataClient(context).putDataItem(request),
                PUT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            // Pairs with the phone's "delivered seq=" line: the two elapsedRealtime
            // stamps subtracted are the watch→phone half of the setUrgent() latency the port
            // has never had a figure for (spec §4).
            Log.i(
                TAG,
                "put seq=${payload.seq} ${payload.gzipped.size}B in " +
                    "${android.os.SystemClock.elapsedRealtime() - started}ms",
            )
            true
        } catch (e: Exception) {
            // Not retried here on purpose. A put that fails because the phone is out of range
            // is not lost work — the next mutation puts the same storage plus the new change,
            // and storage is full state, so one send makes every earlier failure irrelevant.
            Log.e(TAG, "put failed", e)
            false
        }
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wear-watch-storage-put").apply { isDaemon = true }
    }

    /** The newest storage not yet shipped. Writers replace; the drain takes. */
    private val latest = AtomicReference<ByteArray?>(null)

    @Volatile
    private var lastPutSucceeded = true

    private val lastSeq = AtomicLong(0)

    /**
     * Enqueues [storageJson] as the newest payload and kicks the drain.
     *
     * Called from the mutation funnel ([com.liftosaur.wear.engine.WatchStorageRepository]) and
     * **only** for storage the bundle just produced. Storage that arrived *from* the phone must
     * never be submitted here: it would be echoed straight back, and the echo would arrive as a
     * change the phone then merges and re-puts, forever.
     */
    fun submit(storageJson: ByteArray) {
        latest.set(storageJson)
        executor.execute { drain() }
    }

    /**
     * Runs [onIdle] once every put submitted so far has been attempted. FIFO does the work.
     *
     * Internal because nothing in the app needs it — the phone's copy of this class has
     * finish/discard waiting on it, the watch's has no equivalent. It exists so the tests can
     * synchronise on the drain thread rather than sleep.
     */
    internal fun awaitIdle(onIdle: (lastPutSucceeded: Boolean) -> Unit) {
        executor.execute { onIdle(lastPutSucceeded) }
    }

    private fun drain() {
        val json = latest.getAndSet(null) ?: return // coalesced into an earlier drain
        val payload = WatchOutboundStorageBuilder.build(json, deviceId, nextSeq())
        if (payload == null) {
            Log.e(TAG, "not sending: local storage is not parseable JSON (${json.size}B)")
            lastPutSucceeded = false
            return
        }
        lastPutSucceeded = put(payload)
    }

    /**
     * A wall-clock timestamp, forced to strictly increase within the process.
     *
     * The timestamp part is what makes the bytes differ when the storage did not: a
     * byte-identical re-put fires no `onDataChanged` on the peer, so a reinstalled *phone*
     * against unchanged watch storage would never see it.
     *
     * The forcing is what stops two mutations inside the same millisecond from sharing a seq.
     * The phone dedupes the catch-up read against the last seq it delivered, so a repeated seq
     * would be read as "already have this" and the second mutation would be dropped — and the
     * one most likely to be dropped is the last set of a workout, logged right before the user
     * finishes. Nothing orders by seq, so jumping ahead of the clock costs nothing.
     */
    private fun nextSeq(): Long {
        while (true) {
            val previous = lastSeq.get()
            val next = maxOf(clock(), previous + 1)
            if (lastSeq.compareAndSet(previous, next)) return next
        }
    }
}
