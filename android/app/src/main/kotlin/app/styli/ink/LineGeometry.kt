package app.styli.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Pure maths for the straight-line tool. */
object LineGeometry {
    /** Shorter than this (0.1 mm) is a tap, not a line. */
    const val MIN_LENGTH = 10f

    /**
     * Where the line ends: at (x, y), but within [tolDeg] of horizontal or vertical it is pulled onto it,
     * keeping the end's other coordinate, so ruling a straight line by hand is easy.
     */
    fun end(sx: Float, sy: Float, x: Float, y: Float, tolDeg: Double = 3.0): Pair<Float, Float> {
        if (hypot(x - sx, y - sy) == 0f) return x to y
        val deg = Math.toDegrees(atan2((y - sy).toDouble(), (x - sx).toDouble()))
        val fromFlat = minOf(abs(deg), abs(abs(deg) - 180.0))
        val fromUpright = abs(abs(deg) - 90.0)
        return when {
            fromFlat <= tolDeg -> x to sy
            fromUpright <= tolDeg -> sx to y
            else -> x to y
        }
    }

    /** The two points of the stroke, or null if it is only a tap. */
    fun points(sx: Float, sy: Float, ex: Float, ey: Float, ms: Long): List<Pt>? =
        if (hypot(ex - sx, ey - sy) < MIN_LENGTH) null else listOf(Pt(sx, sy, 0, 1f), Pt(ex, ey, ms, 1f))
}
