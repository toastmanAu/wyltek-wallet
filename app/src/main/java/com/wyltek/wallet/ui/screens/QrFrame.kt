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

    class Frame(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * Some cameras ignore the requested analysis resolution and deliver full-sensor frames
     * (3456x3456 on the SM-A536E); decoding those takes seconds per frame. Subsample by an
     * integer step so the longest side is at most [maxDim]; QR modules survive this easily.
     */
    fun downscale(lum: ByteArray, width: Int, height: Int, maxDim: Int = 1280): Frame {
        val longest = maxOf(width, height)
        if (longest <= maxDim) return Frame(lum, width, height)
        val step = (longest + maxDim - 1) / maxDim
        val w = width / step
        val h = height / step
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val src = y * step * width
            val dst = y * w
            for (x in 0 until w) out[dst + x] = lum[src + x * step]
        }
        return Frame(out, w, h)
    }
}
