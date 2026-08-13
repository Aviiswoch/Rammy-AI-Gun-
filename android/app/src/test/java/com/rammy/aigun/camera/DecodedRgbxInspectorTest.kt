package com.rammy.aigun.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodedRgbxInspectorTest {
    @Test
    fun reportsDecodedBufferSizeMismatch() {
        val inspection = DecodedRgbxInspector.inspect(
            buffer = ByteBuffer.allocate(31),
            width = 4,
            height = 4,
            previousTiles = null,
        )

        assertTrue(inspection.sizeMismatch)
        assertFalse(inspection.suspiciousColorBlock)
    }

    @Test
    fun flagsSuddenDominantMagentaFrameAsHeuristicOnly() {
        val normal = DecodedRgbxInspector.inspect(
            buffer = solidRgbx(width = 32, height = 18, red = 100, green = 100, blue = 100),
            width = 32,
            height = 18,
            previousTiles = null,
        )
        val magenta = DecodedRgbxInspector.inspect(
            buffer = solidRgbx(width = 32, height = 18, red = 240, green = 10, blue = 240),
            width = 32,
            height = 18,
            previousTiles = normal.tileColors,
        )

        assertTrue(magenta.suspiciousColorBlock)
        assertTrue(magenta.magentaRatio > 0.9)
    }

    @Test
    fun doesNotFlagStableColorScene() {
        val first = DecodedRgbxInspector.inspect(
            buffer = solidRgbx(width = 32, height = 18, red = 20, green = 220, blue = 20),
            width = 32,
            height = 18,
            previousTiles = null,
        )
        val second = DecodedRgbxInspector.inspect(
            buffer = solidRgbx(width = 32, height = 18, red = 20, green = 220, blue = 20),
            width = 32,
            height = 18,
            previousTiles = first.tileColors,
        )

        assertFalse(second.suspiciousColorBlock)
    }

    private fun solidRgbx(
        width: Int,
        height: Int,
        red: Int,
        green: Int,
        blue: Int,
    ): ByteBuffer = ByteBuffer.allocate(width * height * 4).apply {
        repeat(width * height) {
            put(red.toByte())
            put(green.toByte())
            put(blue.toByte())
            put(0xff.toByte())
        }
        flip()
    }
}
