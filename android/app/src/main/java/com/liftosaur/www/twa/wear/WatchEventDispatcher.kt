package com.liftosaur.www.twa.wear

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * Buffers watch events raised while JS is not yet subscribed, then replays them.
 *
 * Same shape as `EventReporterDispatcher`: the `WearableListenerService` (ticket 06) can
 * receive `/watch/storage` with the React context dead or the JS side not yet subscribed;
 * dropping those events would lose watch-logged sets until the next storage change. JS calls
 * `flushPendingEvents()` right after subscribing (see `nativeWatchBridge`), which drains the
 * buffer in order.
 */
object WatchEventDispatcher {
    private const val MAX_PENDING = 64
    private var module: LiftosaurWatchModule? = null
    private var jsSubscribed = false
    private val pending = mutableListOf<WritableMap>()

    @Synchronized
    fun setModule(m: LiftosaurWatchModule?) {
        module = m
        if (m == null) jsSubscribed = false
    }

    @Synchronized
    fun flushPending() {
        jsSubscribed = true
        val m = module ?: return
        if (pending.isEmpty()) return
        val snapshot = pending.toList()
        pending.clear()
        for (e in snapshot) m.dispatchWatchEvent(e)
    }

    /** Emits a `watchStorageMerge` event carrying the watch's storage JSON (ticket 06). */
    @Synchronized
    fun emitStorageMerge(storageJson: String, deviceId: String, forceUpdateEntryIndex: Boolean) {
        emit(
            Arguments.createMap().apply {
                putString("type", "watchStorageMerge")
                putString("storage", storageJson)
                putString("deviceId", deviceId)
                putBoolean("forceUpdateEntryIndex", forceUpdateEntryIndex)
            }
        )
    }

    @Synchronized
    private fun emit(event: WritableMap) {
        val m = module
        if (jsSubscribed && m != null) {
            m.dispatchWatchEvent(event)
        } else {
            pending.add(event)
            if (pending.size > MAX_PENDING) {
                pending.subList(0, pending.size - MAX_PENDING).clear()
            }
        }
    }
}
