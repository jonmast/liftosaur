package com.liftosaur.www.twa.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * JVM tests for the phone→watch payload.
 *
 * These are the parts of the protocol whose failures are silent: an epoch taken from the wrong
 * field wipes (or fails to wipe) watch storage, and a debug account that slips through copies a
 * stranger's data onto a physical device. Everything else in the send path is Google Play
 * services and only answers on real hardware.
 */
class OutboundStorageTest {

    private fun storage(
        tempUserId: String = "user-1",
        progress: String = "[]",
        originalId: Long = 111,
    ): String = """{"tempUserId":"$tempUserId","originalId":$originalId,"progress":$progress}"""

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }

    @Test
    fun `payload round-trips through gzip unchanged`() {
        val json = storage()
        val payload = OutboundStorageBuilder.build(json, seq = 7)!!
        assertEquals(json, gunzip(payload.gzipped))
        assertEquals(7L, payload.seq)
    }

    @Test
    fun `admin debug accounts are not sent at all`() {
        assertNull(OutboundStorageBuilder.build(storage(tempUserId = "debug_12345"), seq = 1))
    }

    @Test
    fun `a real account whose id merely contains debug_ is still sent`() {
        // The guard is a prefix test, matching AdminDebug_isDebugAccountId. A contains() check
        // would refuse to sync a legitimate user forever, with no error anywhere.
        assertTrue(OutboundStorageBuilder.build(storage(tempUserId = "xdebug_1"), seq = 1) != null)
    }

    @Test
    fun `account epoch follows tempUserId and ignores originalId`() {
        val a = OutboundStorageBuilder.build(storage(tempUserId = "user-1", originalId = 111), 1)!!
        val sameUserNewerOriginalId =
            OutboundStorageBuilder.build(storage(tempUserId = "user-1", originalId = 999), 2)!!
        val otherUserSameOriginalId =
            OutboundStorageBuilder.build(storage(tempUserId = "user-2", originalId = 111), 3)!!

        // originalId is a creation timestamp reduced with Math.max on merge, so it moves without
        // an account switch. If the epoch tracked it, this pair would fire a spurious wipe and
        // destroy sets logged on the watch.
        assertEquals(a.accountEpoch, sameUserNewerOriginalId.accountEpoch)
        // ...and an account switch to an account created earlier would not change originalId at
        // all, leaving the previous account's data on the watch. tempUserId catches it.
        assertNotEquals(a.accountEpoch, otherUserSameOriginalId.accountEpoch)
    }

    @Test
    fun `account epoch is stable across calls`() {
        // The watch persists this and compares it after a reboot; a per-run salt or a hashCode
        // would re-wipe storage on every cold start.
        assertEquals(
            OutboundStorageBuilder.build(storage(), 1)!!.accountEpoch,
            OutboundStorageBuilder.build(storage(), 2)!!.accountEpoch,
        )
    }

    @Test
    fun `no active workout when progress is empty`() {
        assertNull(OutboundStorageBuilder.build(storage(progress = "[]"), 1)!!.activeWorkoutStartTime)
    }

    @Test
    fun `active workout start time is read from the first progress entry`() {
        val payload = OutboundStorageBuilder.build(
            storage(progress = """[{"id":1,"startTime":1750000000000}]"""),
            seq = 1,
        )!!
        assertEquals(1750000000000L, payload.activeWorkoutStartTime)
    }

    @Test
    fun `a missing or zero start time reads as no active workout`() {
        // Reporting 0 would present as a workout that started in 1970 and never ends, and the
        // watch would refuse to leave the workout screen.
        assertNull(OutboundStorageBuilder.build(storage(progress = """[{"id":1}]"""), 1)!!.activeWorkoutStartTime)
        assertNull(
            OutboundStorageBuilder.build(storage(progress = """[{"id":1,"startTime":0}]"""), 1)!!
                .activeWorkoutStartTime,
        )
    }
}
