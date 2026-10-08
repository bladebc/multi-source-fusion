package com.example.multisensorlogger.pdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
class ResamplerSafetyTest {
    @Test fun rejectsGapInsteadOfInterpolatingAcrossIt() {
        var emitted = 0
        val r = Resampler(50.0) { emitted++ }
        for (kind in 0..2) r.add(kind, 0L, 0.0, 0.0, 1.0)
        assertThrows(IllegalArgumentException::class.java) { r.add(Resampler.ACC, 101_000_000L, 0.0, 0.0, 1.0) }
        assertEquals(1, emitted)
    }
    @Test fun missingStreamCannotAccumulateIndefinitely() {
        val r = Resampler(50.0) {}
        assertThrows(IllegalArgumentException::class.java) {
            for (i in 0..251) {
                r.add(Resampler.ACC, i * 20_000_000L, 0.0, 0.0, 1.0)
                r.add(Resampler.GYR, i * 20_000_000L, 0.0, 0.0, 1.0)
            }
        }
    }
    @Test fun rejectsNonFiniteSamples() {
        val r = Resampler(50.0) {}
        assertThrows(IllegalArgumentException::class.java) { r.add(Resampler.ACC, 0L, Double.NaN, 0.0, 1.0) }
    }
}
