package com.liftosaur.www.twa.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Answers "is there a watch, does it have our app, is it reachable" for the three **synchronous**
 * spec methods.
 *
 * The Data Layer answers all three asynchronously (`Tasks`), but codegen types
 * `isWatchPaired` / `isWatchAppInstalled` / `isWatchReachable` as blocking `Boolean` returns —
 * they run on the JS thread. So this class never blocks: each getter returns the **last known**
 * snapshot and kicks an off-thread refresh for the next call. The staleness that buys is
 * harmless in every current call site: they gate UI affordances, not correctness, and the
 * `/storage` put is fired unconditionally regardless (a DataItem put with no connected node
 * still succeeds and is delivered when the watch reappears).
 */
class WearNodes(private val context: Context) {
    companion object {
        private const val TAG = "WearNodes"
        private const val REFRESH_TIMEOUT_SECONDS = 5L
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wear-nodes").apply { isDaemon = true }
    }

    @Volatile
    private var paired = false

    @Volatile
    private var installed = false

    @Volatile
    private var reachable = false

    init {
        refresh()
    }

    /** Any connected node — the closest Android analogue of watchOS's "paired". */
    fun isPaired(): Boolean = snapshot { paired }

    /** A node that declares our capability, whether or not it is currently connected. */
    fun isAppInstalled(): Boolean = snapshot { installed }

    /** A node that declares our capability *and* is currently connected. */
    fun isReachable(): Boolean = snapshot { reachable }

    private inline fun snapshot(read: () -> Boolean): Boolean {
        val value = read()
        refresh()
        return value
    }

    private fun refresh() {
        executor.execute {
            try {
                val capabilityClient = Wearable.getCapabilityClient(context)
                val all = Tasks.await(
                    capabilityClient.getCapability(
                        WearProtocol.CAPABILITY_WEAR_APP,
                        CapabilityClient.FILTER_ALL,
                    ),
                    REFRESH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
                val connected = Tasks.await(
                    capabilityClient.getCapability(
                        WearProtocol.CAPABILITY_WEAR_APP,
                        CapabilityClient.FILTER_REACHABLE,
                    ),
                    REFRESH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
                val nodes = Tasks.await(
                    Wearable.getNodeClient(context).connectedNodes,
                    REFRESH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
                installed = all.nodes.isNotEmpty()
                reachable = connected.nodes.isNotEmpty()
                paired = nodes.isNotEmpty() || installed
            } catch (e: Exception) {
                // Google Play services missing (an emulator without GMS, a de-Googled ROM) lands
                // here on every refresh. Not an error worth surfacing: the answer is "no watch".
                Log.i(TAG, "node refresh failed: ${e.message}")
                installed = false
                reachable = false
                paired = false
            }
        }
    }
}
