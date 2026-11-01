package io.github.brunoccst.anothermacro

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A normalized point: x and y are fractions (0..1) of the screen width and height. */
data class Point(val x: Double, val y: Double) {
    fun toPx(width: Int, height: Int): Pair<Float, Float> = Pair((x * width).toFloat(), (y * height).toFloat())
}

/** A normalized rectangle (top-left x, y plus width and height, all 0..1). */
data class Region(val x: Double, val y: Double, val w: Double, val h: Double) {
    val center: Point get() = Point(x + w / 2, y + h / 2)

    /** Pixel box (left, top, right, bottom), clamped to the image. */
    fun toPx(width: Int, height: Int): IntArray {
        val left = (x * width).roundToInt().coerceIn(0, width)
        val top = (y * height).roundToInt().coerceIn(0, height)
        val right = max(left, min(width, ((x + w) * width).roundToInt()))
        val bottom = max(top, min(height, ((y + h) * height).roundToInt()))
        return intArrayOf(left, top, right, bottom)
    }

    companion object {
        val FULL = Region(0.0, 0.0, 1.0, 1.0)

        fun parse(value: Any?): Region {
            val list = value as? List<*> ?: return FULL
            val v = list.map { (it as Number).toDouble() }
            return Region(v[0], v[1], v[2], v[3])
        }
    }
}
