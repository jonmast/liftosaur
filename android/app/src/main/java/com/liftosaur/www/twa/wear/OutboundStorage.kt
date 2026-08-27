package com.liftosaur.www.twa.wear

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * What goes on the wire, minus the wire.
 *
 * Separated from [WatchStorageSender] so the parts that are easy to get quietly wrong — the
 * account epoch's *source field*, the debug-account guard, whether a workout looks active —
 * are decided by a pure function that a JVM test can pin down. The alternative is that all of
 * it is only observable through Google Play services on a physical watch, which is precisely
 * the code that then never gets tested.
 */
internal data class OutboundStorage(
    val seq: Long,
    val gzipped: ByteArray,
    val accountEpoch: String,
    val activeWorkoutStartTime: Long?,
) {
    // Generated equals/hashCode would compare the ByteArray by identity, which makes every
    // assertion in a test silently pass-by-luck. Only the tests care, but wrong-by-default is
    // not worth keeping.
    override fun equals(other: Any?): Boolean =
        other is OutboundStorage &&
            seq == other.seq &&
            gzipped.contentEquals(other.gzipped) &&
            accountEpoch == other.accountEpoch &&
            activeWorkoutStartTime == other.activeWorkoutStartTime

    override fun hashCode(): Int =
        (((seq.hashCode() * 31 + gzipped.contentHashCode()) * 31) + accountEpoch.hashCode()) * 31 +
            (activeWorkoutStartTime?.hashCode() ?: 0)
}

internal object OutboundStorageBuilder {
    /** Mirrors `adminDebugPrefix` in `src/models/adminDebug.ts`. */
    const val ADMIN_DEBUG_PREFIX = "debug_"

    /**
     * Builds the payload, or returns null when this storage must not leave the phone.
     *
     * Null today means one thing: an admin debug sandbox. The JS side already refuses to call
     * the bridge for those (`App.native.tsx`), and this is the second lock on the same door —
     * mirroring a support session onto the developer's own watch would copy a *stranger's*
     * training data onto a physical device and persist it there, and no single guard should be
     * the only thing standing between a support tool and that.
     *
     * [filteredStorageJson] must already be `WatchStorageFilter_filter` output. This function
     * cannot verify that and deliberately does not try: a heuristic that half-detects
     * unfiltered storage would be a check people trust more than it deserves.
     */
    fun build(filteredStorageJson: String, seq: Long): OutboundStorage? {
        val obj = JSONObject(filteredStorageJson)
        val tempUserId = obj.optString("tempUserId")
        if (tempUserId.startsWith(ADMIN_DEBUG_PREFIX)) {
            return null
        }
        return OutboundStorage(
            seq = seq,
            gzipped = gzip(filteredStorageJson),
            accountEpoch = accountEpoch(tempUserId),
            activeWorkoutStartTime = activeWorkoutStartTime(obj),
        )
    }

    /**
     * `progress` holds in-flight workouts; a non-empty array means one is running.
     *
     * Sent in plaintext beside the blob so the watch can react to a workout starting or ending
     * without unzipping, parsing, or calling JS at all (spec §2.5). A zero or missing
     * `startTime` is reported as "no active workout" rather than as `0` — the watch compares
     * for presence, and a 1970 timestamp would read as an active workout that never ends.
     */
    private fun activeWorkoutStartTime(storage: JSONObject): Long? {
        val progress = storage.optJSONArray("progress") ?: return null
        if (progress.length() == 0) return null
        val startTime = progress.optJSONObject(0)?.optLong("startTime", 0L) ?: 0L
        return if (startTime > 0) startTime else null
    }

    /**
     * SHA-256 of `tempUserId`, truncated — an identity marker, not a secret, and inequality is
     * the only question ever asked of it.
     *
     * **Never derive this from `originalId`.** That field is a creation timestamp reduced with
     * `Math.max` on merge (`src/models/storage.ts`), so it is wrong in both directions:
     * switching to an older account leaves it unchanged (no wipe, and the watch quietly keeps
     * the previous account's data), while an unrelated merge can bump it (a spurious wipe that
     * destroys watch-local sets). `tempUserId` is the actual account identity — it keys service
     * calls and is overwritten with the server's user id on sync.
     */
    private fun accountEpoch(tempUserId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(tempUserId.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun gzip(json: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.encodeToByteArray()) }
        return out.toByteArray()
    }
}
