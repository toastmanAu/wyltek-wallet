package com.wyltek.wallet.ui.screens

/**
 * Camera Y planes are often row-padded (rowStride > width), and ZXing's luminance source
 * expects tightly packed rows; feeding the padded plane skews every row and nothing decodes.
 */
object QrFrame {
    fun luminance(plane: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
        if (rowStride == width && plane.size >= width * height) return plane.copyOf(width * height)
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            System.arraycopy(plane, row * rowStride, out, row * width, width)
        }
        return out
    }
}
