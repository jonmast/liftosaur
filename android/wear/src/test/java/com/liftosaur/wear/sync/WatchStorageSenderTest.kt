package com.liftosaur.wear.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * JVM tests for the watch→phone put loop.
 *
 * Coalescing is the part that can lose data if it is subtly wrong: dropping the *latest*
 * payload instead of an intermediate one would leave the phone permanently one set behind, and
 * on a real watch that looks like flaky sync rather than like a bug.
 */
class WatchStorageSenderTest {

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }

    private fun storage(marker: String) = """{"progress":[],"marker":"$marker"}""".encodeToByteArray()

    /** Blocks until every task queued on the sender's drain thread has run. */
    private fun awaitIdle(sender: WatchStorageSender): Boolean {
        val done = CountDownLatch(1)
        var succeeded = false
        sender.awaitIdle {
            succeeded = it
            done.countDown()
        }
        assertTrue("sender never went idle", done.await(5, TimeUnit.SECONDS))
        return succeeded
    }

    @Test
    fun `submissions made while a put is in flight collapse into one, keeping the latest`() {
        val sent = CopyOnWriteArrayList<String>()
        val firstPutStarted = CountDownLatch(1)
        val releaseFirstPut = CountDownLatch(1)
        val seq = AtomicLong(0)

        val sender = WatchStorageSender(
            deviceId = "wear-test",
            clock = { seq.incrementAndGet() },
            put = { payload ->
                sent.add(gunzip(payload.gzipped))
                if (sent.size == 1) {
                    firstPutStarted.countDown()
                    // Holds the drain thread the way a real radio put does.
                    releaseFirstPut.await(5, TimeUnit.SECONDS)
                }
                true
            },
        )

        sender.submit(storage("a"))
        assertTrue(firstPutStarted.await(5, TimeUnit.SECONDS))
        sender.submit(storage("b"))
        sender.submit(storage("c"))
        releaseFirstPut.countDown()
        awaitIdle(sender)

        assertEquals(2, sent.size)
        assertTrue("first put ships the storage that triggered it", sent[0].contains("\"a\""))
        assertTrue("the coalesced put ships the newest storage", sent[1].contains("\"c\""))
        assertFalse("the superseded payload is never sent", sent.any { it.contains("\"b\"") })
    }

    @Test
    fun `every mutation is sent when puts keep up`() {
        val sent = CopyOnWriteArrayList<String>()
        val seq = AtomicLong(0)
        val sender = WatchStorageSender(
            deviceId = "wear-test",
            clock = { seq.incrementAndGet() },
            put = { sent.add(gunzip(it.gzipped)); true },
        )

        sender.submit(storage("a"))
        awaitIdle(sender)
        sender.submit(storage("b"))
        awaitIdle(sender)

        assertEquals(2, sent.size)
        assertTrue(sent[0].contains("\"a\""))
        assertTrue(sent[1].contains("\"b\""))
    }

    @Test
    fun `each put carries a distinct seq so identical storage still fires onDataChanged`() {
        val seqs = CopyOnWriteArrayList<Long>()
        // A clock that never moves: two mutations inside the same millisecond. The phone
        // dedupes on seq, so a repeat would silently drop the second mutation — and the likely
        // victim is the last set of a workout, logged just before the user hits finish.
        val sender = WatchStorageSender(
            deviceId = "wear-test",
            clock = { 1_700_000_000_000 },
            put = { seqs.add(it.seq); true },
        )

        val identical = storage("same")
        sender.submit(identical)
        awaitIdle(sender)
        sender.submit(identical)
        awaitIdle(sender)

        assertEquals(2, seqs.size)
        assertTrue("byte-identical storage must still differ on the wire", seqs[0] != seqs[1])
        assertTrue("and seq must increase, not just differ", seqs[1] > seqs[0])
    }

    @Test
    fun `seq tracks the clock when the clock moves`() {
        val seqs = CopyOnWriteArrayList<Long>()
        val clock = AtomicLong(1_700_000_000_000)
        val sender = WatchStorageSender(
            deviceId = "wear-test",
            clock = { clock.addAndGet(1_000) },
            put = { seqs.add(it.seq); true },
        )

        sender.submit(storage("a"))
        awaitIdle(sender)
        sender.submit(storage("b"))
        awaitIdle(sender)

        assertEquals(listOf(1_700_000_001_000L, 1_700_000_002_000L), seqs.toList())
    }

    @Test
    fun `unparseable storage is never put, and is reported as a failure`() {
        val sent = CopyOnWriteArrayList<WatchOutboundStorage>()
        val sender = WatchStorageSender(
            deviceId = "wear-test",
            clock = { 1L },
            put = { sent.add(it); true },
        )

        sender.submit("{not json".encodeToByteArray())
        val succeeded = awaitIdle(sender)

        assertEquals(0, sent.size)
        assertFalse(succeeded)
    }

    @Test
    fun `a failed put is reported, and the next submission still sends`() {
        val sent = CopyOnWriteArrayList<String>()
        var succeed = false
        val sender = WatchStorageSender(
            deviceId = "wear-test",
            clock = { 1L },
            put = { sent.add(gunzip(it.gzipped)); succeed },
        )

        sender.submit(storage("a"))
        assertFalse(awaitIdle(sender))

        succeed = true
        sender.submit(storage("b"))
        assertTrue(awaitIdle(sender))
        assertEquals(2, sent.size)
    }
}
