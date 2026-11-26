package app.styli.ink

import app.styli.ink.proto.Tool
import kotlin.math.hypot
import kotlin.math.sqrt

/** One recorded pen point, in page units. [t] is milliseconds since the stroke's first point, [p] is 0 to 1. */
data class Pt(val x: Float, val y: Float, val t: Long, val p: Float)

/** What an eraser may touch. A straight line is a stroke of exactly two points (the line tool, the ruler and auto-straightening make those). */
enum class EraseOnly {
    ALL, INK, HIGHLIGHTS, LINES;

    fun matches(item: Item) = when (this) {
        ALL -> true
        INK -> item.tool == Tool.TOOL_PEN
        HIGHLIGHTS -> item.tool == Tool.TOOL_HIGHLIGHTER
        LINES -> item.pts.size == 2
    }
}

/** Pure maths for the eraser. A "disc" is the eraser: centre (cx, cy), radius r, in page units. */
object EraseGeometry {

    /** Does the disc touch the path? */
    fun hits(pts: List<Pt>, cx: Float, cy: Float, r: Float): Boolean {
        if (pts.size == 1) return hypot(pts[0].x - cx, pts[0].y - cy) <= r
        for (i in 1 until pts.size) if (distToSegment(pts[i - 1], pts[i], cx, cy) <= r) return true
        return false
    }

    /**
     * Cuts the part of the path that is inside the disc away. Returns null when the disc does not touch the
     * path (nothing to do), otherwise the pieces that are left (maybe none). A piece is at least two points;
     * a lone leftover point is a dot nobody drew, so it is dropped. Cut ends sit exactly on the disc's edge.
     */
    fun cut(pts: List<Pt>, cx: Float, cy: Float, r: Float): List<List<Pt>>? {
        if (!hits(pts, cx, cy, r)) return null
        if (pts.size == 1) return emptyList() // a dot the disc touched
        val runs = mutableListOf<List<Pt>>()
        var cur = mutableListOf<Pt>()
        fun close() {
            if (cur.size >= 2) runs.add(cur)
            cur = mutableListOf()
        }
        if (!inside(pts[0], cx, cy, r)) cur.add(pts[0])
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val span = overlap(a, b, cx, cy, r)
            if (span == null) {
                cur.add(b) // a segment outside the disc (a was added already)
                continue
            }
            val (t0, t1) = span
            if (t0 > 0f) { cur.add(lerp(a, b, t0)); close() } // a is outside: end the piece at the disc's edge
            else close() // a is inside
            if (t1 < 1f) { cur.add(lerp(a, b, t1)); cur.add(b) } // b is outside: a new piece starts at the edge
        }
        close()
        return runs
    }

    private fun inside(p: Pt, cx: Float, cy: Float, r: Float) = hypot(p.x - cx, p.y - cy) <= r

    private fun lerp(a: Pt, b: Pt, t: Float) =
        Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.t + ((b.t - a.t) * t).toLong(), a.p + (b.p - a.p) * t)

    private fun distToSegment(a: Pt, b: Pt, cx: Float, cy: Float): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((cx - a.x) * dx + (cy - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(a.x + t * dx - cx, a.y + t * dy - cy)
    }

    /** The part of segment a-b (as t from 0 to 1) that lies inside the disc, or null if none (or a bare touch). */
    private fun overlap(a: Pt, b: Pt, cx: Float, cy: Float, r: Float): Pair<Float, Float>? {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val fx = (a.x - cx).toDouble()
        val fy = (a.y - cy).toDouble()
        val qa = dx * dx + dy * dy
        if (qa == 0.0) return if (fx * fx + fy * fy <= r.toDouble() * r) 0f to 1f else null
        val qb = 2 * (fx * dx + fy * dy)
        val qc = fx * fx + fy * fy - r.toDouble() * r
        val disc = qb * qb - 4 * qa * qc
        if (disc <= 0.0) return null
        val s = sqrt(disc)
        val t0 = ((-qb - s) / (2 * qa)).coerceAtLeast(0.0)
        val t1 = ((-qb + s) / (2 * qa)).coerceAtMost(1.0)
        return if (t0 >= t1) null else t0.toFloat() to t1.toFloat()
    }
}
