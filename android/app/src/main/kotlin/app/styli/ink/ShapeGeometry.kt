package app.styli.ink

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private typealias P = Pair<Float, Float>

/**
 * Pure maths for auto-straightening. The pen is held still at the end of a stroke ([HoldTracker]); if the stroke
 * then looks like a straight line, an ellipse, a rectangle or a triangle, [snap] gives clean points to draw
 * instead. Units are page units (0.01 mm).
 */
object ShapeGeometry {
    const val HOLD_MS = 500L

    /** The pen counts as still while it stays within 1 mm of where it stopped. */
    const val HOLD_RADIUS = 100f

    /** A line shorter than this, or a shape smaller than this across, is writing, not a drawing (5 mm). */
    const val MIN_SIZE = 500f

    private const val LINE_TOL = 0.06f // furthest point from the straight line, as a share of its length
    private const val SHAPE_TOL = 0.07f // average distance from the outline, as a share of the shape's diagonal
    private const val CLOSE_GAP = 0.2f // the ends of a closed shape are at most this share of the path apart
    private const val LEVEL_DEG = 5.0 // a line this close to level or upright is made so
    private const val SAMPLES = 64
    private const val ELLIPSE_POINTS = 72
    private const val SNAP_DEG = 8.0 // a shape this close to level is made level
    private const val CIRCLE_RATIO = 0.1f // an ellipse this close to round is made round

    /** Follows where the pen stopped. The caller waits [HOLD_MS] and starts the wait again whenever [move] says so. */
    class HoldTracker {
        private var ax = 0f
        private var ay = 0f

        fun start(x: Float, y: Float) { ax = x; ay = y }

        /** True if the pen is now more than [HOLD_RADIUS] from where it stopped (and that is its new stopping place). */
        fun move(x: Float, y: Float): Boolean {
            if (hypot(x - ax, y - ay) <= HOLD_RADIUS) return false
            start(x, y)
            return true
        }
    }

    /**
     * The person's penmanship settings, each separate. [lines] and [shapes] say how forgiving to be about what
     * counts as a line or a shape (0 turns it off, 1 is normal, less asks for a neater drawing, more takes a
     * sloppier one); [level] the same for snapping to level and upright; [holdMs] how long the pen is held.
     */
    data class Settings(val lines: Float = 1f, val shapes: Float = 1f, val level: Float = 1f, val holdMs: Long = HOLD_MS)

    /** The clean points for [pts], or null if the stroke is not a line or a shape (or is too small). */
    fun snap(pts: List<Pt>, k: Float): List<Pt>? = snap(pts, Settings(k, k, k))

    fun snap(pts: List<Pt>, s: Settings = Settings()): List<Pt>? {
        if (pts.size < 3) return null
        val poly = pts.map { it.x to it.y }
        val path = length(poly)
        val gap = hypot(poly.last().first - poly.first().first, poly.last().second - poly.first().second)
        val closed = gap <= min(CLOSE_GAP * s.shapes, 0.35f) * path
        val xy = (if (closed) shape(poly, s) else line(poly, path, s)) ?: return null
        val end = pts.last().t
        return xy.mapIndexed { i, p -> Pt(p.first, p.second, end * i / max(1, xy.size - 1), 1f) }
    }

    private fun line(poly: List<P>, path: Float, s: Settings): List<P>? {
        val k = s.lines
        if (k <= 0f) return null
        val (sx, sy) = poly.first()
        val (ex, ey) = poly.last()
        val chord = hypot(ex - sx, ey - sy)
        if (chord < MIN_SIZE || path > chord * (1 + 0.4f * k)) return null
        for ((x, y) in poly) if (abs((ex - sx) * (y - sy) - (ey - sy) * (x - sx)) / chord > LINE_TOL * k * chord) return null
        return listOf(sx to sy, LineGeometry.end(sx, sy, ex, ey, LEVEL_DEG * s.level))
    }

    private fun shape(poly: List<P>, set: Settings): List<P>? {
        val k = set.shapes
        if (k <= 0f) return null
        val s = resample(poly + poly.first(), SAMPLES)
        val w = s.maxOf { it.first } - s.minOf { it.first }
        val h = s.maxOf { it.second } - s.minOf { it.second }
        val diag = hypot(w, h)
        if (diag < MIN_SIZE) return null
        val candidates = listOfNotNull(ellipse(s, k, set.level), rectangle(s, set.level), triangle(s))
        val best = candidates.minBy { it.first }
        return if (best.first / s.size / diag <= SHAPE_TOL * k) best.second else null
    }

    /** Samples seen in a frame turned by [deg]: u and v, the box round them and its middle. */
    private class Frame(val us: FloatArray, val vs: FloatArray, val cu: Float, val cv: Float, val a: Float, val b: Float)

    private fun frame(s: List<P>, deg: Int): Frame {
        val c = cos(Math.toRadians(deg.toDouble())).toFloat()
        val n = sin(Math.toRadians(deg.toDouble())).toFloat()
        val us = FloatArray(s.size) { s[it].first * c + s[it].second * n }
        val vs = FloatArray(s.size) { -s[it].first * n + s[it].second * c }
        val a = (us.max() - us.min()) / 2
        val b = (vs.max() - vs.min()) / 2
        return Frame(us, vs, (us.max() + us.min()) / 2, (vs.max() + vs.min()) / 2, a, b)
    }

    /** Back from a frame turned by [deg] to the page. */
    private fun unturn(u: Float, v: Float, deg: Int): P {
        val c = cos(Math.toRadians(deg.toDouble())).toFloat()
        val n = sin(Math.toRadians(deg.toDouble())).toFloat()
        return (u * c - v * n) to (u * n + v * c)
    }

    /**
     * Level, or upright, if within [SNAP_DEG] of it. Not wrapped (90 stays 90, 180 stays 180): the box's two
     * half-sizes belong to the frame's own axes, so snapping 88 to 0 would swap width and height.
     */
    private fun levelled(deg: Int, level: Float): Int {
        val r = deg % 90
        return if (min(r, 90 - r) <= SNAP_DEG * level) Math.round(deg / 90.0).toInt() * 90 else deg
    }

    private fun ellipse(s: List<P>, k: Float, level: Float): Pair<Float, List<P>>? {
        var best: Triple<Float, Int, Frame>? = null
        for (deg in 0 until 180 step 3) {
            val f = frame(s, deg)
            if (f.a < 1f || f.b < 1f) continue
            var sum = 0f
            for (i in s.indices) {
                val du = f.us[i] - f.cu
                val dv = f.vs[i] - f.cv
                val r = hypot(du / f.a, dv / f.b)
                sum += hypot(du, dv) * abs(1 - 1 / max(r, 1e-3f))
            }
            if (best == null || sum < best.first) best = Triple(sum, deg, f)
        }
        val (sum, deg0, f) = best ?: return null
        var a = f.a
        var b = f.b
        var deg = deg0
        if (abs(a - b) <= CIRCLE_RATIO * k * max(a, b)) { a = (a + b) / 2; b = a } else deg = levelled(deg, level)
        // Turn the box's middle with the new angle: the middle is fixed in the page, not in the frame.
        val mid = unturn(f.cu, f.cv, deg0)
        val pts = (0 until ELLIPSE_POINTS).map {
            val t = 2 * Math.PI * it / ELLIPSE_POINTS
            val (x, y) = unturn((a * cos(t)).toFloat(), (b * sin(t)).toFloat(), deg)
            (mid.first + x) to (mid.second + y)
        }
        return sum to pts + pts.first()
    }

    private fun rectangle(s: List<P>, level: Float): Pair<Float, List<P>>? {
        var best: Triple<Float, Int, Frame>? = null
        for (deg in 0 until 90 step 3) {
            val f = frame(s, deg)
            if (f.a < 1f || f.b < 1f) continue
            var sum = 0f
            for (i in s.indices) {
                val dx = abs(f.us[i] - f.cu) - f.a
                val dy = abs(f.vs[i] - f.cv) - f.b
                sum += if (dx <= 0 && dy <= 0) -max(dx, dy) else hypot(max(dx, 0f), max(dy, 0f))
            }
            if (best == null || sum < best.first) best = Triple(sum, deg, f)
        }
        val (sum, deg0, f) = best ?: return null
        val deg = levelled(deg0, level)
        val mid = unturn(f.cu, f.cv, deg0)
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (sx, sy) ->
            val (x, y) = unturn(sx * f.a, sy * f.b, deg)
            (mid.first + x) to (mid.second + y)
        }
        return sum to corners + corners.first()
    }

    private fun triangle(s: List<P>): Pair<Float, List<P>>? {
        val hull = hull(s).toMutableList()
        if (hull.size < 3) return null
        // Drop the corner that matters least until three are left.
        while (hull.size > 3) {
            val i = hull.indices.minBy { area(hull[(it + hull.size - 1) % hull.size], hull[it], hull[(it + 1) % hull.size]) }
            hull.removeAt(i)
        }
        val closed = hull + hull.first()
        val sum = s.sumOf { p -> (0 until 3).minOf { toSegment(p, closed[it], closed[it + 1]).toDouble() } }.toFloat()
        return sum to closed
    }

    private fun length(poly: List<P>) = (1 until poly.size).sumOf { seg(poly, it - 1).toDouble() }.toFloat()

    private fun seg(poly: List<P>, i: Int) = hypot(poly[i + 1].first - poly[i].first, poly[i + 1].second - poly[i].second)

    /** [n] points spread evenly along the polyline (the end is not repeated). */
    private fun resample(poly: List<P>, n: Int): List<P> {
        val total = length(poly)
        var at = 0
        var start = 0f
        return (0 until n).map { k ->
            val target = total * k / n
            while (at < poly.size - 2 && start + seg(poly, at) < target) { start += seg(poly, at); at++ }
            val l = seg(poly, at)
            val f = if (l == 0f) 0f else ((target - start) / l).coerceIn(0f, 1f)
            val (ax, ay) = poly[at]
            val (bx, by) = poly[at + 1]
            (ax + (bx - ax) * f) to (ay + (by - ay) * f)
        }
    }

    /** Convex hull, Andrew's monotone chain. */
    private fun hull(points: List<P>): List<P> {
        val p = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (p.size < 3) return p
        fun cross(o: P, a: P, b: P) = (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        fun half(src: List<P>): List<P> {
            val h = ArrayList<P>()
            for (q in src) {
                while (h.size >= 2 && cross(h[h.size - 2], h[h.size - 1], q) <= 0) h.removeAt(h.size - 1)
                h.add(q)
            }
            h.removeAt(h.size - 1)
            return h
        }
        return half(p) + half(p.reversed())
    }

    private fun area(a: P, b: P, c: P) = abs((b.first - a.first) * (c.second - a.second) - (c.first - a.first) * (b.second - a.second)) / 2

    private fun toSegment(p: P, a: P, b: P): Float {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val l2 = dx * dx + dy * dy
        val f = if (l2 == 0f) 0f else (((p.first - a.first) * dx + (p.second - a.second) * dy) / l2).coerceIn(0f, 1f)
        return hypot(p.first - (a.first + f * dx), p.second - (a.second + f * dy))
    }
}
