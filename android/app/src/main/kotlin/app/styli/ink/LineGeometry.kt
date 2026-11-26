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

    /** The line's angle in degrees, from 0 up to (not including) 360, counterclockwise from pointing right: straight up is 90. A point is 0. */
    fun angleDeg(sx: Float, sy: Float, ex: Float, ey: Float): Double {
        if (ex == sx && ey == sy) return 0.0
        return (Math.toDegrees(atan2(-(ey - sy).toDouble(), (ex - sx).toDouble())) + 360.0) % 360.0
    }

    /** The angle as shown, one decimal: "37.5°". */
    fun angleLabel(deg: Double) = String.format(java.util.Locale.US, "%.1f\u00B0", deg)
}

/** How a dotted stroke is drawn: round dots along the path. Sizes are in terms of the pen width. */
object Dotted {
    /** The dot's diameter. */
    fun dot(width: Float) = width * 1.5f

    /** From one dot's middle to the next. */
    fun spacing(width: Float) = width * 3.5f

    /** [pts] without the points closer than [minGap] to the last one kept (the last point always stays), so a long drag is not thousands of points. */
    fun thinned(pts: List<Pt>, minGap: Float = 15f): List<Pt> {
        if (pts.size < 3) return pts
        val out = ArrayList<Pt>()
        out.add(pts[0])
        for (k in 1 until pts.size - 1) if (Math.hypot((pts[k].x - out.last().x).toDouble(), (pts[k].y - out.last().y).toDouble()) >= minGap) out.add(pts[k])
        out.add(pts.last())
        return out
    }
}
