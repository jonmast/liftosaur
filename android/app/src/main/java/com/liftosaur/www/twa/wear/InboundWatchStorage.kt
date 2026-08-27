package com.liftosaur.www.twa.wear

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** The watch's `/watch/storage` DataItem, decoded (spec §2.5). */
internal data class InboundWatchStorage(
    val seq: Long,
    val storageJson: String,
    val deviceId: String,
    val activeWorkoutStartTime: Long?,
)

internal object InboundWatchStorageDecoder {
    /**
     * Decodes the DataItem's fields, or null when the payload cannot be used.
     *
     * Kept apart from the `WearableListenerService` so the failure modes are reachable from a
     * JVM test: the alternative is a code path that only exists on a phone with a paired watch
     * running Play services, i.e. one that is never tested until it is broken in the field.
     *
     * **A missing `deviceId` is not fatal.** The merge does not need it — the watch's identity
     * is already inside the storage blob's `_versions`, and `Thunk_handleWatchStorageMerge`
     * merges with the *phone's* deviceId. It rides on the wire for diagnostics and for parity
     * with watchOS's event shape, so dropping a payload over it would trade real logged sets
     * for protocol tidiness.
     */
    fun decode(
        gzipped: ByteArray?,
        seq: Long,
        deviceId: String?,
        activeWorkoutStartTime: Long?,
    ): InboundWatchStorage? {
        if (gzipped == null || gzipped.isEmpty()) return null
        val json = try {
            GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes() }
        } catch (e: Exception) {
            return null
        }
        if (json.isEmpty()) return null
        return InboundWatchStorage(
            seq = seq,
            storageJson = String(json, Charsets.UTF_8),
            deviceId = deviceId ?: "",
            activeWorkoutStartTime = activeWorkoutStartTime,
        )
    }
}
