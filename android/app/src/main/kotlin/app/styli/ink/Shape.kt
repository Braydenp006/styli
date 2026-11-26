package app.styli.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ShapeKind { RECT, ELLIPSE, POLYGON }

/**
 * A shape on the page. Never changes once made. It fills the box whose top-left corner is ([x], [y]), [width] by
 * [height], turned by [angle] (radians, clockwise) about that corner, like a text box. Units are page units.
 * [color] (the outline) and [fill] are 0xAARRGGBB as on a light page; a zero alpha means the page's ordinary ink
 * colour for the outline and no fill. [corner] is a rectangle's corner radius. [vertices] are a polygon's corners
 * as fractions of the box (0 to 1), clockwise. The text is centred in the box.
 */
class Shape(
    val id: String,
    val kind: ShapeKind,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val angle: Float = 0f,
    val color: Int = 0,
    val lineWidth: Float = 40f,
    val dashed: Boolean = false,
    val fill: Int = 0,
    val corner: Float = 0f,
    val vertices: List<Pair<Float, Float>> = emptyList(),
    val text: String = "",
    val textColor: Int = 0,
    val fontSize: Float = 400f,
) {
    fun copy(
        x: Float = this.x, y: Float = this.y, width: Float = this.width, height: Float = this.height, angle: Float = this.angle,
        color: Int = this.color, lineWidth: Float = this.lineWidth, dashed: Boolean = this.dashed, fill: Int = this.fill,
        corner: Float = this.corner, vertices: List<Pair<Float, Float>> = this.vertices,
        text: String = this.text, textColor: Int = this.textColor, fontSize: Float = this.fontSize,
    ) = Shape(id, kind, x, y, width, height, angle, color, lineWidth, dashed, fill, corner, vertices, text, textColor, fontSize)

    /** The shape after a move, resize or turn by [map] (a move, a uniform scale and a turn, as the lasso makes). Line widths are not scaled. */
    fun moved(map: Affine): Shape {
        val scale = sqrt(abs(map.a * map.d - map.b * map.c)).toFloat()
        val turn = kotlin.math.atan2(map.b, map.a).toFloat()
        return copy(
            x = map.x(x, y), y = map.y(x, y), width = width * scale, height = height * scale, angle = angle + turn,
            corner = corner * scale, fontSize = fontSize * scale,
        )
    }

    /** The page point of the point (lx, ly) in the shape's own frame: origin at (x, y), turned by [angle] about it. */
    fun toPage(lx: Float, ly: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        return (x + lx * c - ly * s) to (y + lx * s + ly * c)
    }

    /** The other way: where a page point is in the shape's own frame. */
    fun toLocal(px: Float, py: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        val dx = px - x; val dy = py - y
        return (dx * c + dy * s) to (-dx * s + dy * c)
    }

    /** Is the page point inside the shape or within about [tol] of it? (Sampled round the point, which is close enough for aiming.) */
    fun near(px: Float, py: Float, tol: Float): Boolean {
        if (contains(px, py)) return true
        val d = tol * 0.7f
        return listOf(tol to 0f, -tol to 0f, 0f to tol, 0f to -tol, d to d, d to -d, -d to d, -d to -d).any { contains(px + it.first, py + it.second) }
    }

    val center get() = toPage(width / 2, height / 2)

    /** The four corners of the box on the page, clockwise from the top left. */
    fun corners() = listOf(toPage(0f, 0f), toPage(width, 0f), toPage(width, height), toPage(0f, height))

    /** A polygon's corners in the shape's own frame. */
    private fun local() = vertices.map { (fx, fy) -> fx * width to fy * height }

    /** Is the page point inside the shape (its outline included)? */
    fun contains(px: Float, py: Float): Boolean {
        val (lx, ly) = toLocal(px, py)
        return when (kind) {
            ShapeKind.RECT -> lx in 0f..width && ly in 0f..height
            ShapeKind.ELLIPSE -> {
                val a = (lx - width / 2) / (width / 2); val b = (ly - height / 2) / (height / 2)
                a * a + b * b <= 1f
            }
            ShapeKind.POLYGON -> Fill.insideRing(local(), lx, ly)
        }
    }

    /**
     * Where the outline is along the line from the shape's centre towards the page point (tx, ty): the farthest
     * place the line crosses it. The centre itself if the point is the centre (or a polygon has no corners).
     */
    fun outlineToward(tx: Float, ty: Float): Pair<Float, Float> {
        val (cx, cy) = center
        val (lx, ly) = toLocal(tx, ty)
        val hw = width / 2; val hh = height / 2
        val dx = lx - hw; val dy = ly - hh
        if (dx == 0f && dy == 0f) return cx to cy
        val t = when (kind) {
            ShapeKind.RECT -> 1f / maxOf(abs(dx) / hw, abs(dy) / hh)
            ShapeKind.ELLIPSE -> 1f / sqrt((dx / hw) * (dx / hw) + (dy / hh) * (dy / hh))
            ShapeKind.POLYGON -> farthestCrossing(dx, dy) ?: return cx to cy
        }
        return toPage(hw + dx * t, hh + dy * t)
    }

    /** t so the point (hw + dx*t, hh + dy*t) is on the polygon, the largest such t, or null. */
    private fun farthestCrossing(dx: Float, dy: Float): Float? {
        val pts = local()
        val ox = width / 2; val oy = height / 2
        var best: Float? = null
        for (i in pts.indices) {
            val (ax, ay) = pts[i]
            val (bx, by) = pts[(i + 1) % pts.size]
            val ex = bx - ax; val ey = by - ay
            val den = dx * ey - dy * ex
            if (den == 0f) continue // parallel
            val t = ((ax - ox) * ey - (ay - oy) * ex) / den
            val u = ((ax - ox) * dy - (ay - oy) * dx) / den
            if (t > 0f && u in 0f..1f && (best == null || t > best)) best = t
        }
        return best
    }

    /** The nearest point of the outline to a point in the shape's own frame (the point itself if it is on it). */
    private fun nearestLocal(lx: Float, ly: Float): Pair<Float, Float> {
        val w = width; val h = height
        return when (kind) {
            ShapeKind.RECT -> {
                val cx = lx.coerceIn(0f, w); val cy = ly.coerceIn(0f, h)
                if (cx != lx || cy != ly) cx to cy // outside: the nearest point of the box is on its edge
                else when (listOf(lx, w - lx, ly, h - ly).withIndex().minByOrNull { it.value }!!.index) { 0 -> 0f to ly; 1 -> w to ly; 2 -> lx to 0f; else -> lx to h }
            }
            ShapeKind.ELLIPSE -> {
                val dx = lx - w / 2; val dy = ly - h / 2
                val k = sqrt((dx / (w / 2)) * (dx / (w / 2)) + (dy / (h / 2)) * (dy / (h / 2)))
                if (k == 0f) w / 2 to 0f else (w / 2 + dx / k) to (h / 2 + dy / k)
            }
            ShapeKind.POLYGON -> {
                val pts = local()
                if (pts.isEmpty()) return w / 2 to h / 2
                var best = pts[0]; var bestD = Float.MAX_VALUE
                for (i in pts.indices) {
                    val (ax, ay) = pts[i]; val (bx, by) = pts[(i + 1) % pts.size]
                    val dx = bx - ax; val dy = by - ay
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0f) 0f else (((lx - ax) * dx + (ly - ay) * dy) / len2).coerceIn(0f, 1f)
                    val qx = ax + t * dx; val qy = ay + t * dy
                    val d = (qx - lx) * (qx - lx) + (qy - ly) * (qy - ly)
                    if (d < bestD) { bestD = d; best = qx to qy }
                }
                best
            }
        }
    }

    /** The spot on the outline nearest the page point, as fractions of the box (0 to 1 each way). */
    fun nearestOutline(px: Float, py: Float): Pair<Float, Float> {
        val (lx, ly) = toLocal(px, py)
        val (x, y) = nearestLocal(lx, ly)
        return (if (width == 0f) 0f else x / width) to (if (height == 0f) 0f else y / height)
    }

    /** The page point at fractions ([fx], [fy]) of the box. */
    fun spot(fx: Float, fy: Float) = toPage(fx * width, fy * height)

    /** The spots on the outline that a connector snaps to, as box fractions: the middle of each side (of a polygon, each edge). */
    fun snapSpots(): List<Pair<Float, Float>> = when (kind) {
        ShapeKind.RECT, ShapeKind.ELLIPSE -> listOf(.5f to 0f, 1f to .5f, .5f to 1f, 0f to .5f)
        ShapeKind.POLYGON -> vertices.indices.map { i ->
            val (ax, ay) = vertices[i]; val (bx, by) = vertices[(i + 1) % vertices.size]
            (ax + bx) / 2 to (ay + by) / 2
        }
    }

    /** The unit direction pointing out of the shape at the outline spot nearest the page point, on the page. */
    fun outwardAt(px: Float, py: Float): Pair<Float, Float> {
        val (lx0, ly0) = toLocal(px, py)
        val (lx, ly) = nearestLocal(lx0, ly0)
        val w = width; val h = height
        var nx: Float; var ny: Float
        when (kind) {
            ShapeKind.RECT -> {
                val d = listOf(lx, w - lx, ly, h - ly)
                when (d.withIndex().minByOrNull { abs(it.value) }!!.index) { 0 -> { nx = -1f; ny = 0f }; 1 -> { nx = 1f; ny = 0f }; 2 -> { nx = 0f; ny = -1f }; else -> { nx = 0f; ny = 1f } }
            }
            ShapeKind.ELLIPSE -> {
                nx = (lx - w / 2) / ((w / 2) * (w / 2)); ny = (ly - h / 2) / ((h / 2) * (h / 2))
            }
            ShapeKind.POLYGON -> {
                val pts = local()
                nx = 0f; ny = -1f
                var bestD = Float.MAX_VALUE
                for (i in pts.indices) {
                    val (ax, ay) = pts[i]; val (bx, by) = pts[(i + 1) % pts.size]
                    val dx = bx - ax; val dy = by - ay
                    val len2 = dx * dx + dy * dy
                    if (len2 == 0f) continue
                    val t = (((lx - ax) * dx + (ly - ay) * dy) / len2).coerceIn(0f, 1f)
                    val d = (ax + t * dx - lx) * (ax + t * dx - lx) + (ay + t * dy - ly) * (ay + t * dy - ly)
                    if (d >= bestD) continue
                    bestD = d
                    val len = sqrt(len2)
                    var ex = dy / len; var ey = -dx / len
                    if (Fill.insideRing(pts, lx + ex * 2, ly + ey * 2)) { ex = -ex; ey = -ey } // point away from the inside
                    nx = ex; ny = ey
                }
            }
        }
        val len = sqrt(nx * nx + ny * ny)
        if (len == 0f) return 0f to -1f
        nx /= len; ny /= len
        val c = cos(angle); val s = sin(angle)
        return (nx * c - ny * s) to (nx * s + ny * c)
    }

    /** The middle of side [anchor] of the box (1 top, 2 right, 3 bottom, 4 left), on the page. */
    fun sideMiddle(anchor: Int): Pair<Float, Float> = when (anchor) {
        1 -> toPage(width / 2, 0f)
        2 -> toPage(width, height / 2)
        3 -> toPage(width / 2, height)
        else -> toPage(0f, height / 2)
    }

    /** Where a connector end with [anchor] (see ink.proto) sits: for 1 to 4 on the outline towards that side; otherwise null. */
    fun anchorPoint(anchor: Int): Pair<Float, Float>? =
        if (anchor in 1..4) sideMiddle(anchor).let { outlineToward(it.first, it.second) } else null
}

/** The premade shapes. Each is a kind and, for a polygon, its corners as fractions of the box. */
enum class ShapePreset(val kind: ShapeKind, val vertices: List<Pair<Float, Float>> = emptyList(), val rounded: Boolean = false) {
    RECTANGLE(ShapeKind.RECT),
    ROUNDED(ShapeKind.RECT, rounded = true),
    ELLIPSE(ShapeKind.ELLIPSE),
    TRIANGLE(ShapeKind.POLYGON, listOf(.5f to 0f, 1f to 1f, 0f to 1f)),
    DIAMOND(ShapeKind.POLYGON, listOf(.5f to 0f, 1f to .5f, .5f to 1f, 0f to .5f)),
    PARALLELOGRAM(ShapeKind.POLYGON, listOf(.25f to 0f, 1f to 0f, .75f to 1f, 0f to 1f)),
    PENTAGON(ShapeKind.POLYGON, regular(5, -PI / 2)),
    HEXAGON(ShapeKind.POLYGON, regular(6, 0.0)),
    STAR(ShapeKind.POLYGON, star()),
    ARROW(ShapeKind.POLYGON, listOf(0f to .3f, .6f to .3f, .6f to 0f, 1f to .5f, .6f to 1f, .6f to .7f, 0f to .7f));

    /** A new shape of this kind filling the box at (x, y), [width] by [height]. */
    fun make(id: String, x: Float, y: Float, width: Float, height: Float, lineWidth: Float = 40f, color: Int = 0, fill: Int = 0) =
        Shape(id, kind, x, y, width, height, 0f, color, lineWidth, false, fill, if (rounded) minOf(width, height) * 0.2f else 0f, vertices)
}

// Top-level (not in the enum's companion) so the entries above can use them while the enum is being built.

/** A regular polygon with [n] corners, starting at [start] radians, stretched to fill the box (0 to 1 each way). */
private fun regular(n: Int, start: Double) = fit((0 until n).map { k -> cos(start + 2 * PI * k / n) to sin(start + 2 * PI * k / n) })

/** A five-pointed star, point up. */
private fun star() = fit((0 until 10).map { k ->
    val r = if (k % 2 == 0) 1.0 else 0.38
    val a = -PI / 2 + PI * k / 5
    r * cos(a) to r * sin(a)
})

private fun fit(p: List<Pair<Double, Double>>): List<Pair<Float, Float>> {
    val x0 = p.minOf { it.first }; val x1 = p.maxOf { it.first }
    val y0 = p.minOf { it.second }; val y1 = p.maxOf { it.second }
    return p.map { ((it.first - x0) / (x1 - x0)).toFloat() to ((it.second - y0) / (y1 - y0)).toFloat() }
}
