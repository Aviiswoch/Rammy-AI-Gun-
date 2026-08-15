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

    @Test
    fun mapsNativePreviewPerformanceCounterOrder() {
        val stats = NativePreviewPerformanceStats.from(LongArray(19) { (it + 1).toLong() })

        assertEquals(1, stats.rawFramesReceived)
        assertEquals(2, stats.rawFramesAccepted)
        assertEquals(4, stats.stalePreviewFramesDropped)
        assertEquals(5, stats.decodedFrames)
        assertEquals(11, stats.queueDepth)
        assertEquals(13, stats.bufferPoolAvailable)
        assertEquals(19, stats.lastConversionNs)
    }

    @Test
    fun previewPerformanceMappingToleratesUnavailableNativeValues() {
        val stats = NativePreviewPerformanceStats.from(longArrayOf(12, 9))

        assertEquals(12, stats.rawFramesReceived)
        assertEquals(9, stats.rawFramesAccepted)
        assertEquals(0, stats.decodedFrames)
        assertEquals(0, stats.queueDepth)
    }
}
