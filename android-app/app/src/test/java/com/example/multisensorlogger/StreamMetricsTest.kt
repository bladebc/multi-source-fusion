package com.example.multisensorlogger

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamMetricsTest {
    @Test
    fun fiftyHertzIsComputedFromActualTimestamps() {
        val metrics = StreamMetrics(100_000_000L)
        repeat(51) { index -> metrics.add(1_000_000_000L + index * 20_000_000L) }

        assertEquals(51L, metrics.count)
        assertEquals(50.0, metrics.measuredHz!!, 0.0001)
        assertEquals(20.0, metrics.maxGapMs, 0.0001)
        assertEquals(0L, metrics.longGapCount)
    }

    @Test
    fun longAndBackwardsIntervalsAreReported() {
        val metrics = StreamMetrics(100_000_000L)
        listOf(1_000_000_000L, 1_020_000_000L, 1_180_000_000L, 1_100_000_000L)
            .forEach(metrics::add)

        assertEquals(4L, metrics.count)
        assertEquals(1L, metrics.longGapCount)
        assertEquals(1L, metrics.nonMonotonicCount)
        assertEquals(160.0, metrics.maxGapMs, 0.0001)
        assertEquals(2 * 1_000_000_000.0 / 180_000_000.0, metrics.measuredHz!!, 0.0001)
    }
}
