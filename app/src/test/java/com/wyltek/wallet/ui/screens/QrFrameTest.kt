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
