package app.styli.ink

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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

/**
 * The frame round a selection: four corners in page units, 0 top left, 1 top right, 2 bottom left, 3 bottom
 * right as seen in the frame's own turn. Level for strokes; turned with the text when only text boxes that share
 * an angle are selected, so the outline hugs them instead of growing as they turn.
 */
class Quad(val pts: List<Pair<Double, Double>>) {
    fun corner(i: Int) = pts[i]

    val centre get() = ((pts[0].first + pts[3].first) / 2) to ((pts[0].second + pts[3].second) / 2)

    fun map(m: Affine) = Quad(pts.map { (x, y) -> (m.a * x + m.c * y + m.e) to (m.b * x + m.d * y + m.f) })

    /** Is the page point inside (the outline included)? */
    fun contains(x: Double, y: Double) =
        LassoGeometry.contains(floatArrayOf(
            pts[0].first.toFloat(), pts[0].second.toFloat(), pts[1].first.toFloat(), pts[1].second.toFloat(),
            pts[3].first.toFloat(), pts[3].second.toFloat(), pts[2].first.toFloat(), pts[2].second.toFloat(),
        ), x.toFloat(), y.toFloat())

    /** The middle of the top edge. */
    val topMiddle get() = ((pts[0].first + pts[1].first) / 2) to ((pts[0].second + pts[1].second) / 2)

    /** The rotate handle: [stem] beyond the middle of the top edge, straight out from the centre. */
    fun handle(stem: Double): Pair<Double, Double> {
        val (mx, my) = topMiddle
        val (cx, cy) = centre
        val len = hypot(mx - cx, my - cy)
        val (ux, uy) = if (len == 0.0) 0.0 to -1.0 else ((mx - cx) / len) to ((my - cy) / len)
        return (mx + ux * stem) to (my + uy * stem)
    }

    companion object {
        fun of(b: Box) = Quad(listOf(b.l to b.t, b.r to b.t, b.l to b.b, b.r to b.b))
    }
}

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

    /**
     * The strokes with more than half of their length inside the loop (so a straight line, which is only two
     * points, works like any other). A loop under three points catches nothing. The length is judged by
     * testing the middle of every piece of path up to [STEP] long.
     */
    fun selected(items: List<Item>, poly: FloatArray): Set<String> {
        if (poly.size < 6) return emptySet()
        return items.filter { insideShare(it.pts, poly) > 0.5 }.mapTo(HashSet()) { it.id }
    }

    /** 0 to 1: how much of the path's length is inside the loop. A dot counts as all or nothing. */
    fun insideShare(pts: List<Pt>, poly: FloatArray): Double {
        var total = 0.0
        var inside = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val len = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            val n = kotlin.math.ceil(len / STEP).toInt().coerceIn(1, 4000)
            for (k in 0 until n) {
                val t = (k + 0.5f) / n
                total += len / n
                if (contains(poly, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)) inside += len / n
            }
        }
        if (total == 0.0) return if (pts.isNotEmpty() && contains(poly, pts[0].x, pts[0].y)) 1.0 else 0.0
        return inside / total
    }

    private const val STEP = 50.0 // 0.5 mm

    /**
     * The text boxes (each with its laid-out height) that are more than half inside the loop by area. Judged by
     * testing the middle of a [GRID] by [GRID] grid of cells in the box's own frame, so a turned, tall or wide box
     * is fair too.
     */
    fun selectedBoxes(boxes: List<Pair<TextBox, Float>>, poly: FloatArray): Set<String> {
        if (poly.size < 6) return emptySet()
        return boxes.filter { (b, h) ->
            var inside = 0
            for (i in 0 until GRID) for (j in 0 until GRID) {
                val (x, y) = b.toPage(b.width * (i + 0.5f) / GRID, h * (j + 0.5f) / GRID)
                if (contains(poly, x, y)) inside++
            }
            inside * 2 > GRID * GRID
        }.mapTo(HashSet()) { it.first.id }
    }

    private const val GRID = 10

    /** The tables more than half inside the loop by area, judged like text boxes. */
    fun selectedTables(tables: List<Table>, poly: FloatArray): Set<String> {
        if (poly.size < 6) return emptySet()
        return tables.filter { t ->
            var inside = 0
            for (i in 0 until GRID) for (j in 0 until GRID) {
                if (contains(poly, t.x + t.width * (i + 0.5f) / GRID, t.y + t.height * (j + 0.5f) / GRID)) inside++
            }
            inside * 2 > GRID * GRID
        }.mapTo(HashSet()) { it.id }
    }

    /** Do two angles (radians) point the same way? */
    fun sameTurn(a: Float, b: Float) = kotlin.math.abs(sin((a - b).toDouble())) < 1e-3 && cos((a - b).toDouble()) > 0

    /**
     * The smallest frame turned by [angle] that holds all of [corners] (page points): the points are seen in a
     * frame turned back by the angle, boxed, and the box's corners are turned forward again.
     */
    fun orientedQuad(corners: List<Pair<Float, Float>>, angle: Float): Quad {
        val c = cos(angle.toDouble()); val s = sin(angle.toDouble())
        val us = corners.map { it.first * c + it.second * s }
        val vs = corners.map { -it.first * s + it.second * c }
        val (u0, u1, v0, v1) = listOf(us.min(), us.max(), vs.min(), vs.max())
        fun back(u: Double, v: Double) = (u * c - v * s) to (u * s + v * c)
        return Quad(listOf(back(u0, v0), back(u1, v0), back(u0, v1), back(u1, v1)))
    }

    /**
     * The box round these strokes, padded by half the widest pen so the ink is inside it, joined with the boxes
     * of any text boxes ([texts], not padded). Null if there is nothing.
     */
    fun bounds(items: List<Item>, texts: List<Box> = emptyList()): Box? {
        if (items.isEmpty() && texts.isEmpty()) return null
        var l = Double.MAX_VALUE; var t = Double.MAX_VALUE; var r = -Double.MAX_VALUE; var b = -Double.MAX_VALUE
        var pad = 0.0
        for (it in items) {
            pad = max(pad, it.width / 2.0)
            for (p in it.pts) { l = minOf(l, p.x.toDouble()); r = maxOf(r, p.x.toDouble()); t = minOf(t, p.y.toDouble()); b = maxOf(b, p.y.toDouble()) }
        }
        if (items.isNotEmpty()) { l -= pad; t -= pad; r += pad; b += pad }
        for (x in texts) { l = minOf(l, x.l); t = minOf(t, x.t); r = maxOf(r, x.r); b = maxOf(b, x.b) }
        return Box(l, t, r, b)
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
    fun scaleFromCorner(box: Box, corner: Int, px: Double, py: Double): Affine = scaleFromCorner(Quad.of(box), corner, px, py)

    fun scaleFromCorner(frame: Quad, corner: Int, px: Double, py: Double): Affine {
        val (hx, hy) = frame.corner(corner)
        val (ax, ay) = frame.corner(3 - corner)
        val dx = hx - ax
        val dy = hy - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return Affine.IDENTITY
        val s = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.05, 20.0)
        return Affine.scaleAbout(s, ax, ay)
    }
}
