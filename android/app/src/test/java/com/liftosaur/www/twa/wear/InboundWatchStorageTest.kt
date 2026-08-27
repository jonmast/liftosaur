package com.liftosaur.www.twa.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * JVM tests for the watch→phone payload.
 *
 * The decode path is where a malformed item from the wrist turns into either a merge or a
 * silent drop, and both of those failures are invisible in production: a dropped item looks
 * like "the watch didn't sync", and a merge with a missing `deviceId` corrupts the vector clock
 * in a way no later sync repairs.
 */
class InboundWatchStorageTest {

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun `decodes a gzipped payload back to the original json`() {
        val json = """{"tempUserId":"user-1","progress":[{"startTime":1234}]}"""
        val decoded = InboundWatchStorageDecoder.decode(
            gzipped = gzip(json),
            seq = 42,
            deviceId = "wear-abc12345",
            activeWorkoutStartTime = 1234,
        )!!
        assertEquals(json, decoded.storageJson)
        assertEquals(42L, decoded.seq)
        assertEquals("wear-abc12345", decoded.deviceId)
        assertEquals(1234L, decoded.activeWorkoutStartTime)
    }

    @Test
    fun `a payload without a deviceId is still merged`() {
        // The id is diagnostic here: the merge uses the phone's deviceId, and the watch's own
        // identity is already inside the blob's _versions. Sets must not be dropped over it.
        val gz = gzip("""{"tempUserId":"user-1"}""")
        assertEquals(
            "",
            InboundWatchStorageDecoder.decode(gz, seq = 1, deviceId = null, activeWorkoutStartTime = null)!!.deviceId,
        )
    }

    @Test
    fun `a missing or empty blob is dropped`() {
        assertNull(InboundWatchStorageDecoder.decode(null, seq = 1, deviceId = "wear-1", activeWorkoutStartTime = null))
        assertNull(
            InboundWatchStorageDecoder.decode(ByteArray(0), seq = 1, deviceId = "wear-1", activeWorkoutStartTime = null)
        )
    }

    @Test
    fun `a corrupt blob is dropped rather than thrown`() {
        val notGzip = "plain text, definitely not gzip".encodeToByteArray()
        assertNull(
            InboundWatchStorageDecoder.decode(notGzip, seq = 1, deviceId = "wear-1", activeWorkoutStartTime = null)
        )
    }

    @Test
    fun `an absent activeWorkoutStartTime stays absent`() {
        val decoded = InboundWatchStorageDecoder.decode(
            gzipped = gzip("""{"progress":[]}"""),
            seq = 1,
            deviceId = "wear-1",
            activeWorkoutStartTime = null,
        )!!
        assertNull(decoded.activeWorkoutStartTime)
    }

    @Test
    fun `unicode survives the gzip round trip`() {
        // Storage carries user-authored exercise and program names, so a byte-level mistake
        // here would show up as mojibake in the phone app after a watch sync.
        val json = """{"name":"Приседания 🏋️ — 5×5"}"""
        val decoded = InboundWatchStorageDecoder.decode(
            gzipped = gzip(json),
            seq = 1,
            deviceId = "wear-1",
            activeWorkoutStartTime = null,
        )!!
        assertEquals(json, decoded.storageJson)
    }
}
