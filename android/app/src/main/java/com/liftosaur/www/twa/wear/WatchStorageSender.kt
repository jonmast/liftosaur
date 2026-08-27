package com.liftosaur.www.twa.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the phone→watch `/storage` put: filtered storage in, urgent DataItem out (spec §2.5).
 *
 * **Coalescing, not queueing.** The put fires on every storage change with no debounce, but
 * only the *latest* payload matters — the DataItem is replace-in-place at a fixed path, so
 * shipping an intermediate state the watch would immediately overwrite is pure radio cost.
 * [submit] stores the newest JSON in [latest]; the single-threaded [executor] drains it, and
 * anything submitted while a put is in flight collapses into one send.
 *
 * **Parsing and gzipping happen on the drain thread, and only for payloads that actually
 * ship.** A coalesced payload is never parsed at all, and the JS thread never pays for either.
 *
 * The payload is always `WatchStorageFilter_filter` output — the JS side filters before calling
 * the bridge — and it is always gzipped, with no size threshold: a threshold would mean two
 * wire formats, one of which is exercised only by big accounts, i.e. only in production.
 */
class WatchStorageSender(private val context: Context) {
    companion object {
        private const val TAG = "WatchStorageSender"
        private const val PUT_TIMEOUT_SECONDS = 10L
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wear-storage-put").apply { isDaemon = true }
    }

    /** The newest filtered-storage JSON not yet shipped. Writers replace; the drain takes. */
    private val latest = AtomicReference<String?>(null)

    /**
     * Whether the most recent attempted put succeeded.
     *
     * Starts true so that "nothing has been sent yet" doesn't read as failure. Note this is
     * about the *put*, not about delivery: a put with no watch in range still succeeds, and the
     * item is delivered when the watch reappears. False means the Data Layer itself refused.
     */
    @Volatile
    private var lastPutSucceeded = true

    /** Enqueues [filteredStorageJson] as the newest payload and kicks the drain. */
    fun submit(filteredStorageJson: String) {
        latest.set(filteredStorageJson)
        executor.execute { drain() }
    }

    /**
     * Runs [onIdle] once every put submitted so far has been attempted.
     *
     * FIFO on the single drain thread is the entire mechanism — by the time this task runs, the
     * drains queued ahead of it have finished. Used by finish/discard, whose storage change was
     * already submitted by the JS storage effect before they were called.
     */
    fun awaitIdle(onIdle: (lastPutSucceeded: Boolean) -> Unit) {
        executor.execute { onIdle(lastPutSucceeded) }
    }

    /**
     * Deletes the `/storage` DataItem on every node; the watch reads that as "wipe".
     *
     * Runs on the drain thread so it cannot race a put and lose, and clears [latest] so a
     * payload queued behind it cannot resurrect the storage it just deleted.
     */
    fun clear(onDone: (succeeded: Boolean) -> Unit) {
        executor.execute {
            latest.set(null)
            val ok = try {
                Tasks.await(
                    Wearable.getDataClient(context)
                        .deleteDataItems(Uri.parse("wear://*${WearProtocol.PATH_STORAGE}")),
                    PUT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "deleteDataItems failed", e)
                false
            }
            onDone(ok)
        }
    }

    private fun drain() {
        val json = latest.getAndSet(null) ?: return // coalesced into an earlier drain
        try {
            // Timestamp-as-seq. Its only job is to make the bytes differ when the storage
            // itself did not: re-putting byte-identical content fires no onDataChanged on the
            // peer, so without it a reinstalled watch whose phone storage never changed would
            // sit empty forever. The watch compares epochs for inequality and never orders by
            // seq, so a clock that jumps backwards costs nothing.
            val payload = OutboundStorageBuilder.build(json, System.currentTimeMillis())
            if (payload == null) {
                Log.i(TAG, "not sending: admin debug sandbox account")
                return
            }

            val request = PutDataMapRequest.create(WearProtocol.PATH_STORAGE).apply {
                dataMap.putLong(WearProtocol.KEY_SEQ, payload.seq)
                dataMap.putByteArray(WearProtocol.KEY_Z, payload.gzipped)
                dataMap.putString(WearProtocol.KEY_ACCOUNT_EPOCH, payload.accountEpoch)
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
            lastPutSucceeded = true
            // The pair of timestamps this and the watch's "applied seq=" line print is the
            // setUrgent() delivery latency the port has never measured (spec §4).
            Log.i(
                TAG,
                "put seq=${payload.seq} ${payload.gzipped.size}B in " +
                    "${android.os.SystemClock.elapsedRealtime() - started}ms",
            )
        } catch (e: Exception) {
            Log.e(TAG, "put failed", e)
            lastPutSucceeded = false
        }
    }
}
