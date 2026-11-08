package com.lithic.gallery.core.ai.easyocr

import com.lithic.gallery.core.image.CvOps
import com.lithic.gallery.core.image.GrayImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A text line's image cut out for the recognizer, and where it came from. */
class Crop(
    /** The source corners, top-left clockwise: (x0, y0, x1, y1, x2, y2, x3, y3). */
    val box: DoubleArray,
    /** The cut-out, scaled to the recognizer's 64-pixel height (or width, for vertical text). */
    val image: GrayImage,
    /** Its long side over its short side, as EasyOCR's `calculate_ratio` gives it. */
    val ratio: Double,
)

/** EasyOCR's `utils.get_image_list` and the helpers it uses. */
object Crops {
    const val MODEL_HEIGHT = 64

    /**
     * `four_point_transform`: the quadrilateral [rect] (tl, tr, br, bl as float32 x, y pairs)
     * straightened into an upright image.
     */
    fun fourPointTransform(img: GrayImage, rect: FloatArray): GrayImage {
        fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            // numpy on float32 points: differences, squares, sum and square root all stay float32
            val dx = ax - bx
            val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }
        val (tlx, tly) = rect[0] to rect[1]
        val (trx, try_) = rect[2] to rect[3]
        val (brx, bry) = rect[4] to rect[5]
        val (blx, bly) = rect[6] to rect[7]
        val maxWidth = max(dist(brx, bry, blx, bly).toInt(), dist(trx, try_, tlx, tly).toInt())
        val maxHeight = max(dist(trx, try_, brx, bry).toInt(), dist(tlx, tly, blx, bly).toInt())
        val dst = floatArrayOf(
            0f, 0f, (maxWidth - 1).toFloat(), 0f,
            (maxWidth - 1).toFloat(), (maxHeight - 1).toFloat(), 0f, (maxHeight - 1).toFloat(),
        )
        val m = CvOps.perspectiveTransform(rect, dst)
        return CvOps.warpPerspective(img, m, maxWidth, maxHeight)
    }

    /**
     * `calculate_ratio` (width/height, or its inverse when below 1) and `compute_ratio_and_resize`,
     * whose LANCZOS flag OpenCV reads as INTER_LINEAR. The ratio is at least 1, so EasyOCR's
     * `new_width == 0` check never fires: there is none here.
     */
    private fun scaleToModel(img: GrayImage): Pair<GrayImage, Double> {
        val r = img.width.toDouble() / img.height
        return if (r < 1.0) {
            val ratio = 1.0 / r
            CvOps.resizeLinear(img, MODEL_HEIGHT, (MODEL_HEIGHT * ratio).toInt()) to ratio
        } else {
            CvOps.resizeLinear(img, (MODEL_HEIGHT * r).toInt(), MODEL_HEIGHT) to r
        }
    }

    /**
     * `get_image_list` for one box at a time, as EasyOCR's readtext feeds it with batch_size 1:
     * the crop of a horizontal box ([x_min, x_max, y_min, y_max]), or null if no part of it lies
     * inside the image.
     */
    fun horizontal(img: GrayImage, box: IntArray): Crop? {
        val xMin = max(0, box[0])
        val xMax = min(box[1], img.width)
        val yMin = max(0, box[2])
        val yMax = min(box[3], img.height)
        if (xMax <= xMin || yMax <= yMin) return null
        val (scaled, ratio) = scaleToModel(img.crop(xMin, yMin, xMax, yMax))
        val corners = doubleArrayOf(
            xMin.toDouble(), yMin.toDouble(), xMax.toDouble(), yMin.toDouble(),
            xMax.toDouble(), yMax.toDouble(), xMin.toDouble(), yMax.toDouble(),
        )
        return Crop(corners, scaled, ratio)
    }

    /** `get_image_list` for a rotated box (four corners, tl clockwise). */
    fun free(img: GrayImage, box: DoubleArray): Crop {
        val rect = FloatArray(8) { box[it].toFloat() }
        val (scaled, ratio) = scaleToModel(fourPointTransform(img, rect))
        return Crop(box, scaled, ratio)
    }

    /** The recognizer's input width for a crop: `ceil(max_ratio) * 64` with max_ratio at least 1. */
    fun maxWidth(crop: Crop): Int = ceil(max(ceil(crop.ratio), 1.0)).toInt() * MODEL_HEIGHT
}
