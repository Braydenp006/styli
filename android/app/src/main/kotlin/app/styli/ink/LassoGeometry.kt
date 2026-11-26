package app.styli.ink

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

/** The map x' = a*x + c*y + e, y' = b*x + d*y + f, as in the ink format's Transformed event. */
data class Affine(
    val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0,
) {
    val isIdentity get() = this == IDENTITY

    fun x(px: Float, py: Float) = (a * px + c * py + e).toFloat()
    fun y(px: Float, py: Float) = (b * px + d * py + f).toFloat()

    fun apply(p: Pt) = p.copy(x = x(p.x, p.y), y = y(p.x, p.y))

    /** The map that undoes this one (for the log; an undo in memory restores the old points instead). */
    fun inverse(): Affine {
        val det = a * d - c * b
        require(det != 0.0) { "a flattened map cannot be undone" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    companion object {
        val IDENTITY = Affine()
        fun translate(dx: Double, dy: Double) = Affine(e = dx, f = dy)
        /** Turns by [angle] radians (positive is clockwise on screen, where y points down) round (ox, oy). */
        fun rotateAbout(angle: Double, ox: Double, oy: Double): Affine {
            val co = cos(angle)
            val si = sin(angle)
            return Affine(co, si, -si, co, ox - (co * ox - si * oy), oy - (si * ox + co * oy))
        }

        fun scaleAbout(s: Double, ox: Double, oy: Double) = Affine(s, 0.0, 0.0, s, ox - s * ox, oy - s * oy)
    }
}

/** A box in page units. */
data class Box(val l: Double, val t: Double, val r: Double, val b: Double)

/** Pure maths for the lasso: which strokes a loop catches, the box round a selection, and resizing by a corner. */
object LassoGeometry {

    /** Is (x, y) inside the polygon [poly] (x0, y0, x1, y1, ...)? Even-odd rule; the loop closes itself. */
    fun contains(poly: FloatArray, x: Float, y: Float): Boolean {
        val n = poly.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]
            val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** The strokes with more than half of their points inside the loop. A loop under three points catches nothing. */
    fun selected(items: List<Item>, poly: FloatArray): Set<String> {
        if (poly.size < 6) return emptySet()
        return items.filter { item -> item.pts.count { contains(poly, it.x, it.y) } * 2 > item.pts.size }.mapTo(HashSet()) { it.id }
    }

    /** The box round these strokes, padded by half the widest pen so the ink is inside it. Null if there are none. */
    fun bounds(items: List<Item>): Box? {
        if (items.isEmpty()) return null
        var l = Double.MAX_VALUE; var t = Double.MAX_VALUE; var r = -Double.MAX_VALUE; var b = -Double.MAX_VALUE
        var pad = 0.0
        for (it in items) {
            pad = max(pad, it.width / 2.0)
            for (p in it.pts) { l = minOf(l, p.x.toDouble()); r = maxOf(r, p.x.toDouble()); t = minOf(t, p.y.toDouble()); b = maxOf(b, p.y.toDouble()) }
        }
        return Box(l - pad, t - pad, r + pad, b + pad)
    }

    /** The angle (radians, from -pi to pi) that takes the line centre-to-start round to the line centre-to-now. */
    fun turn(cx: Double, cy: Double, startX: Double, startY: Double, x: Double, y: Double): Double {
        var d = atan2(y - cy, x - cx) - atan2(startY - cy, startX - cx)
        while (d > PI) d -= 2 * PI
        while (d <= -PI) d += 2 * PI
        return d
    }

    /** Within [tolDeg] of a multiple of [stepDeg], the angle jumps onto it, so a turn is easy to put back to straight. */
    fun snap(angle: Double, stepDeg: Double = 15.0, tolDeg: Double = 3.0): Double {
        val step = Math.toRadians(stepDeg)
        val near = round(angle / step) * step
        return if (kotlin.math.abs(angle - near) <= Math.toRadians(tolDeg)) near else angle
    }

    /** The corner's position: 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
    fun corner(box: Box, i: Int): Pair<Double, Double> = (if (i % 2 == 0) box.l else box.r) to (if (i < 2) box.t else box.b)

    /**
     * Resizing by a corner: the opposite corner stays put and the shape keeps its proportions. The scale is
     * where the pointer's pull along the diagonal puts the corner (so a flat box, with no width or no height,
     * still works), kept between 5% and 20 times.
     */
    fun scaleFromCorner(box: Box, corner: Int, px: Double, py: Double): Affine {
        val (hx, hy) = corner(box, corner)
        val (ax, ay) = corner(box, 3 - corner)
        val dx = hx - ax
        val dy = hy - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return Affine.IDENTITY
        val s = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.05, 20.0)
        return Affine.scaleAbout(s, ax, ay)
    }
}
