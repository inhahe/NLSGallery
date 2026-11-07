package com.lithic.gallery.core.image

/**
 * An 8-bit RGB image: [data] holds width*height*3 bytes, rows top to bottom, each pixel R, G, B.
 */
class RgbImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height * 3)) {
    init {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        require(data.size == width * height * 3) { "${data.size} bytes for a ${width}x$height RGB image" }
    }

    /** Channel [c] of the pixel at ([x], [y]), 0..255. */
    fun get(x: Int, y: Int, c: Int): Int = data[(y * width + x) * 3 + c].toInt() and 0xFF

    companion object {
        /** From Android/AWT packed ARGB pixels (alpha ignored, as PIL's convert("RGB") does). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): RgbImage {
            val out = ByteArray(width * height * 3)
            for (i in 0 until width * height) {
                val p = argb[i]
                out[i * 3] = (p shr 16).toByte()
                out[i * 3 + 1] = (p shr 8).toByte()
                out[i * 3 + 2] = p.toByte()
            }
            return RgbImage(width, height, out)
        }
    }
}

/** An 8-bit greyscale image: [data] holds width*height bytes, rows top to bottom. */
class GrayImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        require(data.size == width * height) { "${data.size} bytes for a ${width}x$height grey image" }
    }

    fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    /** The rectangle [x0, x1) × [y0, y1), which must lie inside the image. */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): GrayImage {
        require(x0 in 0 until x1 && x1 <= width && y0 in 0 until y1 && y1 <= height) {
            "crop [$x0,$x1)x[$y0,$y1) of ${width}x$height"
        }
        val w = x1 - x0
        val out = ByteArray(w * (y1 - y0))
        for (y in y0 until y1) System.arraycopy(data, y * width + x0, out, (y - y0) * w, w)
        return GrayImage(w, y1 - y0, out)
    }
}
