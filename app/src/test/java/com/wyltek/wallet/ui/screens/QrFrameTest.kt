package com.wyltek.wallet.ui.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class QrFrameTest {
    @Test fun `padded rows are compacted to width bytes per row`() {
        // 3x2 image with a row stride of 5: two padding bytes per row
        val plane = byteArrayOf(1, 2, 3, 9, 9, 4, 5, 6, 9, 9)
        val out = QrFrame.luminance(plane, width = 3, height = 2, rowStride = 5)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out)
    }
    @Test fun `unpadded rows pass through`() {
        val plane = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(plane, QrFrame.luminance(plane, 3, 2, 3))
    }
    @Test fun `last row may be shorter than the stride`() {
        // Android often omits the trailing padding of the final row
        val plane = byteArrayOf(1, 2, 3, 9, 4, 5, 6)
        assertEquals(6, QrFrame.luminance(plane, 3, 2, 4).size)
    }
}

class QrFrameDownscaleTest {
    @Test fun `small frames are untouched`() {
        val f = QrFrame.downscale(ByteArray(640 * 480) { 7 }, 640, 480, maxDim = 1280)
        assertEquals(640, f.width); assertEquals(480, f.height)
    }
    @Test fun `a 12 MP frame is reduced to at most maxDim on its longest side`() {
        val f = QrFrame.downscale(ByteArray(3456 * 3456), 3456, 3456, maxDim = 1280)
        assertEquals(1152, f.width); assertEquals(1152, f.height)   // step 3
        assertEquals(1152 * 1152, f.bytes.size)
    }
    @Test fun `downscale samples every step-th pixel`() {
        // 4x2 frame, maxDim 2 -> step 2 -> keeps columns 0,2 of rows 0
        val src = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val f = QrFrame.downscale(src, 4, 2, maxDim = 2)
        assertEquals(2, f.width); assertEquals(1, f.height)
        assertArrayEquals(byteArrayOf(1, 3), f.bytes)
    }
}
