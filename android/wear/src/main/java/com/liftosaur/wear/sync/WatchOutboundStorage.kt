package com.liftosaur.wear.sync

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * What the watch puts on `/watch/storage`, minus the wire.
 *
 * The mirror image of the phone's `OutboundStorageBuilder`, and separated for the same reason:
 * the header fields are the part that is easy to get quietly wrong, and the only alternative
 * place to observe them is a physical watch talking to Google Play services.
 *
 * Two fields the phone sends are deliberately absent:
 * - **no `accountEpoch`** — the epoch exists so the *watch* can detect that the phone switched
 *   accounts and wipe before applying. The phone is the account's owner; it has nothing to
 *   learn from the watch about which account it is on, and a watch-authored epoch would just
 *   be a second opinion that could disagree.
 * - **no filtering** — the watch only ever received `WatchStorageFilter_filter` output, so what
 *   it holds is already filtered. Re-filtering would need the filter in Kotlin, and prunes
 *   nothing.
 */
internal data class WatchOutboundStorage(
    val seq: Long,
    val gzipped: ByteArray,
    val deviceId: String,
    val activeWorkoutStartTime: Long?,
) {
    // Generated equals/hashCode compare ByteArray by identity, which makes assertions pass by
    // luck. Only tests care, but wrong-by-default is not worth keeping.
    override fun equals(other: Any?): Boolean =
        other is WatchOutboundStorage &&
            seq == other.seq &&
            gzipped.contentEquals(other.gzipped) &&
            deviceId == other.deviceId &&
            activeWorkoutStartTime == other.activeWorkoutStartTime

    override fun hashCode(): Int =
        (((seq.hashCode() * 31 + gzipped.contentHashCode()) * 31) + deviceId.hashCode()) * 31 +
            (activeWorkoutStartTime?.hashCode() ?: 0)
}

internal object WatchOutboundStorageBuilder {
    /**
     * Builds the payload for [storageJson], or null when it cannot be sent.
     *
     * Null means the storage is not parseable as JSON — which on this side means local storage
     * is corrupt, not that the payload is unwanted. Sending it anyway would hand the phone
     * something its merge would reject, and would do it on every mutation.
     *
     * **This parses storage, which spec §2.3 otherwise forbids in Kotlin.** The rule is about
     * the engine call surface — storage crosses JNI opaque, and nothing here feeds the parse
     * back into a bundle call. The parse buys the plaintext header, which is what lets the
     * phone see a workout end without unzipping, merging, or running JS. It costs one
     * `org.json` parse per put, on the sender's own thread, never on the engine thread and
     * never on the UI thread.
     */
    fun build(storageJson: ByteArray, deviceId: String, seq: Long): WatchOutboundStorage? {
        val startTime = try {
            activeWorkoutStartTime(JSONObject(String(storageJson, Charsets.UTF_8)))
        } catch (e: Exception) {
            return null
        }
        return WatchOutboundStorage(
            seq = seq,
            gzipped = gzip(storageJson),
            deviceId = deviceId,
            activeWorkoutStartTime = startTime,
        )
    }

    /**
     * `progress` holds in-flight workouts; a non-empty array means one is running.
     *
     * A zero or missing `startTime` reads as "no active workout" rather than as `0`: presence
     * is the only question asked of this field, and a 1970 timestamp would look like a workout
     * that never ends. Identical rule to the phone's copy, deliberately.
     */
    private fun activeWorkoutStartTime(storage: JSONObject): Long? {
        val progress = storage.optJSONArray("progress") ?: return null
        if (progress.length() == 0) return null
        val startTime = progress.optJSONObject(0)?.optLong("startTime", 0L) ?: 0L
        return if (startTime > 0) startTime else null
    }

    private fun gzip(json: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json) }
        return out.toByteArray()
    }
}
