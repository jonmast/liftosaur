package com.liftosaur.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Prototype-only: lets the variants be driven over adb instead of by tapping a 192dp screen.
 * Nothing here ships — the whole file goes away with the stub data.
 */
object PrototypeRemote {
    var nonce by mutableIntStateOf(0)
        private set

    private var pendingRoute: String? = null
    private var pendingEntryIndex: Int? = null

    fun request(route: String?, entryIndex: Int?) {
        pendingRoute = route
        pendingEntryIndex = entryIndex
        nonce += 1
    }

    fun consumeEntryIndex(): Int? {
        val value = pendingEntryIndex
        pendingEntryIndex = null
        return value
    }

    fun consumeRoute(): String? {
        val value = pendingRoute
        pendingRoute = null
        return value
    }
}

/**
 * Targeted explicitly by component name (`am broadcast -n ...`), so it is exempt from the
 * implicit-broadcast restrictions that would otherwise drop this in the background.
 */
class RemoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("detail")?.let { value ->
            PrototypeVariants.detailLayout = when (value.lowercase()) {
                "hero" -> DetailLayout.HERO
                "anchored", "anch" -> DetailLayout.ANCHORED
                "row" -> DetailLayout.ROW
                else -> PrototypeVariants.detailLayout
            }
        }
        intent.getStringExtra("prompt")?.let { value ->
            PrototypeVariants.promptLayout = when (value.lowercase()) {
                "paged" -> PromptLayout.PAGED
                "form" -> PromptLayout.FORM
                else -> PrototypeVariants.promptLayout
            }
        }
        intent.getStringExtra("worst")?.let { value ->
            PrototypeVariants.worstCase = value == "1" || value.equals("true", ignoreCase = true)
        }
        if (intent.getStringExtra("reset") != null) {
            PrototypeStore.reset()
        }

        val entryIndex = intent.getStringExtra("entry")?.toIntOrNull()
        val route = intent.getStringExtra("screen")
        if (route != null || entryIndex != null) {
            PrototypeRemote.request(route, entryIndex)
        }
    }
}
