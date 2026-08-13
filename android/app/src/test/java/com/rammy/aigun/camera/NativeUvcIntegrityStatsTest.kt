package com.rammy.aigun.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeUvcIntegrityStatsTest {
    @Test
    fun mapsNativeCounterOrderWithoutLosingValues() {
        val stats = NativeUvcIntegrityStats.from(longArrayOf(100, 2, 90, 1, 3, 3, 4, 82, 8))

        assertEquals(100, stats.uvcPacketsTotal)
        assertEquals(2, stats.uvcPacketsErr)
        assertEquals(90, stats.framesCompletedWithEof)
        assertEquals(1, stats.framesDroppedErr)
        assertEquals(3, stats.framesDroppedMissingEof)
        assertEquals(3, stats.framesDroppedFidTransition)
        assertEquals(4, stats.framesDroppedSizeMismatch)
        assertEquals(82, stats.rawYuy2FramesAccepted)
        assertEquals(8, stats.rawYuy2FramesDropped)
    }

    @Test
    fun toleratesOlderOrUnavailableNativeCounterArrays() {
        val stats = NativeUvcIntegrityStats.from(longArrayOf(7))

        assertEquals(7, stats.uvcPacketsTotal)
        assertEquals(0, stats.rawYuy2FramesDropped)
    }
}
