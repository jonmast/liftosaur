package com.liftosaur.wear.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * JVM tests for the watch→phone payload header.
 *
 * `activeWorkoutStartTime` is the field with a silent failure mode: it is what tells the phone
 * a workout ended without merging, so getting it wrong either strands the phone's live activity
 * on a finished workout or tears one down mid-session.
 */
class WatchOutboundStorageTest {

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }

    private fun build(json: String, deviceId: String = "wear-abc12345", seq: Long = 1) =
        WatchOutboundStorageBuilder.build(json.encodeToByteArray(), deviceId, seq)

    @Test
    fun `payload round-trips through gzip unchanged, carrying seq and deviceId`() {
        val json = """{"tempUserId":"user-1","progress":[]}"""
        val payload = build(json, deviceId = "wear-deadbeef", seq = 7)!!
        assertEquals(json, gunzip(payload.gzipped))
        assertEquals(7L, payload.seq)
        assertEquals("wear-deadbeef", payload.deviceId)
    }

    @Test
    fun `an in-progress workout reports its startTime`() {
        val payload = build("""{"progress":[{"startTime":1700000000000}]}""")!!
        assertEquals(1700000000000L, payload.activeWorkoutStartTime)
    }

    @Test
    fun `no progress means no active workout`() {
        assertNull(build("""{"progress":[]}""")!!.activeWorkoutStartTime)
        assertNull(build("""{"tempUserId":"user-1"}""")!!.activeWorkoutStartTime)
    }

    @Test
    fun `a zero or missing startTime reads as no active workout, not as 1970`() {
        // The phone tests this field for presence. A 0 would read as a workout that started at
        // the epoch and never ends, which is worse than reporting nothing.
        assertNull(build("""{"progress":[{"startTime":0}]}""")!!.activeWorkoutStartTime)
        assertNull(build("""{"progress":[{"programId":"p"}]}""")!!.activeWorkoutStartTime)
    }

    @Test
    fun `unparseable storage is not sent`() {
        assertNull(build("""{"progress":"""))
        assertNull(build(""))
    }

    @Test
    fun `the watch sends no accountEpoch`() {
        // The phone owns the account. A watch-authored epoch would be a second opinion that
        // could disagree with the phone's, and the wipe it drives is destructive.
        val fields = WatchOutboundStorage::class.java.declaredFields.map { it.name }
        assert(fields.none { it.contains("epoch", ignoreCase = true) }) {
            "watch payload should carry no account epoch, found: $fields"
        }
    }

    @Test
    fun `unicode survives the gzip round trip`() {
        val json = """{"name":"Приседания 🏋️ — 5×5"}"""
        assertEquals(json, gunzip(build(json)!!.gzipped))
    }
}
