package com.liftosaur.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.liftosaur.wear.AppContainer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Receives the phone's `/storage` DataItem, **including with the app process dead** — the
 * system starts this service to deliver it.
 *
 * That is the whole reason the repository is process-scoped ([AppContainer]) rather than
 * activity-scoped: this callback and the UI must write to the same storage, or the watch ends
 * up with two copies that disagree.
 *
 * **Why `runBlocking` and not a detached coroutine.** The framework keeps the process alive
 * for the duration of [onDataChanged] and is free to kill it the moment the callback returns.
 * Launching the apply into a background scope and returning would be a race against process
 * death that gets more likely the more storage there is to merge. Blocking is what makes
 * "delivered" mean "applied". The work is bounded by [APPLY_TIMEOUT_MS], comfortably inside
 * the framework's allowance, and a timeout is not data loss: the item stays in the Data Layer
 * and [PhoneStorageSync.applyLatest] re-reads it at next app start.
 */
class PhoneStorageListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "PhoneStorageWLS"

        /** Cold start pays engine init (~1s) plus a merge (~200ms); the rest is headroom. */
        private const val APPLY_TIMEOUT_MS = 15_000L
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Only the newest matters: /storage is replace-in-place, so applying an older event in
        // the same buffer would merge storage the phone has already superseded.
        val latest = dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .filter { it.dataItem.uri.path == WearProtocol.PATH_STORAGE }
            .mapNotNull { PhoneStorageSync.parse(DataMapItem.fromDataItem(it.dataItem).dataMap) }
            .maxByOrNull { it.seq }

        val deleted = dataEvents.any {
            it.type == DataEvent.TYPE_DELETED && it.dataItem.uri.path == WearProtocol.PATH_STORAGE
        }

        if (deleted && latest == null) {
            // The phone deleted the item: an explicit account wipe (clearWatchStorage). Local
            // storage is now unowned, and keeping it would show the next user someone else's
            // program.
            Log.i(TAG, "/storage deleted by phone, wiping local storage")
            AppContainer.repository(applicationContext).clear()
            return
        }

        val payload = latest ?: return
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            runBlocking { withTimeout(APPLY_TIMEOUT_MS) { PhoneStorageSync.apply(applicationContext, payload) } }
            // Logged as elapsedRealtime deltas so the setUrgent() latency measurement the ticket
            // asks for can be read straight out of logcat against the phone's put timestamp.
            Log.i(TAG, "applied seq=${payload.seq} in ${android.os.SystemClock.elapsedRealtime() - started}ms")
        } catch (e: Exception) {
            Log.e(TAG, "failed to apply /storage seq=${payload.seq}", e)
        }
    }
}
