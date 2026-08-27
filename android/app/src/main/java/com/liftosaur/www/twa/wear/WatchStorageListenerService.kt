package com.liftosaur.www.twa.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the watch's `/watch/storage` DataItem — including with the phone app backgrounded
 * or its process dead, which the system starts this service to handle.
 *
 * Unlike the watch's mirror of this service, the work here is **emission, not application**:
 * merging is JS's job and JS may not be running. [WatchEventDispatcher] buffers the event and
 * replays it the moment the React context subscribes, so a callback that returns immediately
 * still cannot lose the sets it carried. That also keeps the callback far inside the 20s
 * WearableListenerService allowance.
 */
class WatchStorageListenerService : WearableListenerService() {
    companion object {
        private const val TAG = "WatchStorageWLS"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Only the newest matters: /watch/storage is replace-in-place and carries full state,
        // so an older item in the same buffer is a strictly worse copy of the newest one.
        val latest = dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .filter { it.dataItem.uri.path == WearProtocol.PATH_WATCH_STORAGE }
            .mapNotNull { WatchStorageReceiver.parse(DataMapItem.fromDataItem(it.dataItem).dataMap) }
            .maxByOrNull { it.seq }

        if (latest == null) {
            Log.i(TAG, "no usable /watch/storage in ${dataEvents.count} event(s)")
            return
        }
        WatchStorageReceiver.deliver(latest)
    }
}
