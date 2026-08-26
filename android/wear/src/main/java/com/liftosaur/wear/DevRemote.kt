package com.liftosaur.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Drives the app over adb, because the usual review loop does not exist here.
 *
 * Screenshots of this app are impossible — the watch dozes within seconds and the dream stays
 * composited above the window, so `screencap` returns the watch face and `uiautomator dump`
 * sees no Compose nodes (spec §3.5). Every visual check has to be made by a human looking at
 * their wrist, which makes "tap through four screens to reach the thing I changed" the
 * bottleneck. This lets the screen be selected from the host instead.
 */
object DevRemote {
    var nonce by mutableIntStateOf(0)
        private set

    private var pendingRoute: String? = null
    private var pendingEntryIndex: Int? = null
    private var pendingReseed = false

    fun request(route: String?, entryIndex: Int?, reseed: Boolean) {
        pendingRoute = route
        pendingEntryIndex = entryIndex
        pendingReseed = reseed
        nonce += 1
    }

    fun consumeRoute(): String? = pendingRoute.also { pendingRoute = null }

    fun consumeEntryIndex(): Int? = pendingEntryIndex.also { pendingEntryIndex = null }

    fun consumeReseed(): Boolean = pendingReseed.also { pendingReseed = false }
}

/**
 * Targeted explicitly by component name (`am broadcast -n ...`), so it is exempt from the
 * implicit-broadcast restrictions that would otherwise drop this while backgrounded.
 */
class RemoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        DevRemote.request(
            route = intent.getStringExtra("screen"),
            entryIndex = intent.getStringExtra("entry")?.toIntOrNull(),
            reseed = intent.getStringExtra("reseed") != null,
        )
    }
}
