package com.liftosaur.wear.engine

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * The clock every budget in spec §4 is stated in, and the memory reading they use.
 *
 * **Wall clock is not usable here.** The watch suspends mid-call — measured at 0.7-5.4s inside
 * a single bundle evaluation — which inflates wall-clock deltas by up to 6.5x. A budget
 * measured that way fails on a device that is behaving perfectly, and passes on one that
 * happened not to doze. Every duration reported to a human by this fork is CPU time of the
 * thread that did the work (tickets 04, 07, 12).
 *
 * This is deliberately shared rather than duplicated per-measurement-site: two measurement
 * sites disagreeing about what "ms" means is exactly how a budget table stops being evidence.
 */
internal object CpuTime {
    /**
     * Running CPU time of the *calling* thread, in nanoseconds.
     *
     * Nanoseconds rather than [SystemClock.currentThreadTimeMillis]'s milliseconds because a
     * warm read is ~15ms and the malloc-trend loop needs per-call figures — at ms resolution a
     * 20-sample median is quantised into uselessness.
     *
     * Falls back to the millisecond clock if the platform declines to answer, which it does on
     * some ABIs; the fallback is scaled so callers never have to know which one they got.
     */
    fun nanos(): Long {
        val n = Debug.threadCpuTimeNanos()
        return if (n >= 0L) n else SystemClock.currentThreadTimeMillis() * 1_000_000L
    }

    /**
     * Anonymous RSS for this process, in KB — the number the memory budgets are stated in.
     *
     * Total RSS is misleading on Android: an idle process shows ~108MB of which ~79MB is
     * `RssFile`, zygote-shared bootclasspath that nobody pays for. Anon is what this app
     * actually costs (ticket 07).
     */
    fun anonRssKb(): Long =
        runCatching {
            File("/proc/${Process.myPid()}/status").readLines()
                .firstOrNull { it.startsWith("RssAnon:") }
                ?.filter { it.isDigit() }
                ?.toLongOrNull() ?: -1L
        }.getOrDefault(-1L)

    /** Nanoseconds to milliseconds, one decimal place — the unit every budget is written in. */
    fun msOf(nanos: Long): Double = nanos / 1_000_000.0

    /** Median rather than mean: one suspend-contaminated sample would drag a mean anywhere. */
    fun medianMs(samples: List<Long>): Double {
        if (samples.isEmpty()) return -1.0
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        val nanos = if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
        return msOf(nanos)
    }

    fun maxMs(samples: List<Long>): Double = if (samples.isEmpty()) -1.0 else msOf(samples.max())
}
