package com.liftosaur.www.twa.wear

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.liftosaur.www.twa.specs.NativeLiftosaurWatchSpec

/**
 * Android's implementation of the `LiftosaurWatch` spec — the same spec watchOS implements,
 * deliberately not a new one (all call sites already go through the `nativeWatchBridge`
 * facade, so a separate spec would buy isolation nobody uses and double the shared-file diff).
 *
 * Codegen forces every method to exist, so the split is **7 real / 4 stub** (spec §2.8):
 *
 * - Real: [sendStorageToWatch], [clearWatchStorage], [sendFinishWorkoutToWatch],
 *   [sendDiscardWorkoutToWatch], [isWatchPaired], [isWatchAppInstalled], [isWatchReachable].
 * - Stub: the three auth methods (no auth reaches the Wear app — it runs the bundle locally
 *   and never talks to the server) and [requestWatchLogs] (no reply-handler equivalent in the
 *   Data Layer; logs come off the watch via adb).
 *
 * Everything the watch needs travels in **one** DataItem. There is no `finishWorkout` message
 * on Android: the watch derives the end of a workout from `activeWorkoutStartTime` vanishing
 * from the next `/storage` put, which costs it zero JS.
 */
class LiftosaurWatchModule(reactContext: ReactApplicationContext) :
    NativeLiftosaurWatchSpec(reactContext) {

    companion object {
        private const val TAG = "LiftosaurWatch"
    }

    private val sender = WatchStorageSender(reactContext.applicationContext)
    private val nodes = WearNodes(reactContext.applicationContext)

    init {
        WatchEventDispatcher.setModule(this)
        WatchStorageSenderHolder.set(sender)
    }

    /**
     * Puts filtered storage as the `/storage` DataItem.
     *
     * Resolves as soon as the payload is *accepted for sending*, not when it lands: the put is
     * coalesced and drained off-thread, and JS has nothing useful to do with the outcome — a
     * failed put is retried by the next storage change, and there is always a next one.
     */
    override fun sendStorageToWatch(filteredStorageJson: String, promise: Promise) {
        sender.submit(filteredStorageJson)
        promise.resolve(null)
    }

    /** No auth is sent to the Wear app; see the class doc. Resolving keeps JS callers quiet. */
    override fun sendAuthToWatch(auth: ReadableMap, promise: Promise) = promise.resolve(null)

    override fun sendNoAuthToWatch(promise: Promise) = promise.resolve(null)

    override fun sendClearAuthToWatch(promise: Promise) = promise.resolve(null)

    /**
     * Deletes the `/storage` DataItem, which the watch reads as "wipe".
     *
     * Note this has no JS call sites today (`NativeWatchBridge_clearWatchStorage` is dead code
     * upstream too) — account switches are handled by `accountEpoch` inequality inside the
     * normal put, which works even when the phone never gets to run this.
     */
    override fun clearWatchStorage(promise: Promise) {
        sender.clear { promise.resolve(null) }
    }

    /**
     * **The boolean means "did the put succeed", NOT "did the watch save the workout"** —
     * a deliberate change of meaning from watchOS (spec §2.8), where the watch could own the
     * HealthKit write. The Wear app has no Health Connect integration, so the phone must
     * always do the health write itself; [com.liftosaur.www.twa.wear] callers must not treat
     * `true` as "health is handled" (the JS facade maps this to `false` for exactly that
     * reason — see `nativeWatchBridge.native.ts`).
     *
     * What awaiting buys is ordering, not health: by the time this resolves, the storage put
     * that no longer carries `activeWorkoutStartTime` has been attempted, which is what sends
     * the watch back Home.
     */
    override fun sendFinishWorkoutToWatch(saveToHealth: Boolean, promise: Promise) {
        sender.awaitIdle { succeeded -> promise.resolve(succeeded) }
    }

    /** Same mechanism as finish: the discard is already in the storage that gets put. */
    override fun sendDiscardWorkoutToWatch(promise: Promise) {
        sender.awaitIdle { promise.resolve(null) }
    }

    /**
     * Stub. WCSession's reply handler has no Data Layer equivalent, and building a
     * request/response channel to ship logs would be a lot of protocol for a debug affordance
     * that `adb logcat` already covers on a device that must be USB/wifi-attached anyway.
     */
    override fun requestWatchLogs(promise: Promise) = promise.resolve("")

    override fun isWatchPaired(): Boolean = nodes.isPaired()

    override fun isWatchAppInstalled(): Boolean = nodes.isAppInstalled()

    override fun isWatchReachable(): Boolean = nodes.isReachable()

    /**
     * Drains events that arrived before JS subscribed — see [WatchEventDispatcher] — and then
     * catches up on watch storage this process never saw at all.
     *
     * The buffer only covers events *this process* received. A phone killed while the watch was
     * logging sets has no buffer to replay, and the watch will not put again until its next
     * mutation, so the sets would sit in the Data Layer unnoticed until the user's next workout.
     * The catch-up read is what closes that, and it is the exact mirror of the watch's
     * `PhoneStorageSync.applyLatest` (ticket 05). Off-thread: Data Layer reads block.
     */
    override fun flushPendingEvents(promise: Promise) {
        WatchEventDispatcher.flushPending()
        val appContext = reactApplicationContext.applicationContext
        Thread({ WatchStorageReceiver.deliverLatest(appContext) }, "wear-storage-catchup").apply {
            isDaemon = true
        }.start()
        promise.resolve(null)
    }

    fun dispatchWatchEvent(event: WritableMap) {
        emitOnWatchEvent(event)
    }

    override fun invalidate() {
        Log.i(TAG, "module invalidated")
        WatchEventDispatcher.setModule(null)
        super.invalidate()
    }
}

/**
 * Hands the live [WatchStorageSender] to code that runs outside the React context.
 *
 * The `WearableListenerService` (ticket 06) is started by the system with the React host
 * possibly dead, so it cannot reach the module. It can still need a put — e.g. a watch that
 * reinstalled asks for storage. Null means "no React context yet", which is a legitimate
 * state, not an error.
 */
object WatchStorageSenderHolder {
    @Volatile
    private var sender: WatchStorageSender? = null

    fun set(s: WatchStorageSender?) {
        sender = s
    }

    fun get(): WatchStorageSender? = sender
}
