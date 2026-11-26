package app.styli.ink

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A ruler lying on the screen (not on the page, and never saved). Units are screen pixels; [angle] is in
 * radians. The pen snaps to its two long edges. [u] runs along the ruler from its centre, [v] across it.
 */
class Ruler(var cx: Float, var cy: Float, var angle: Float, val length: Float, val thickness: Float) {

    private fun u(x: Float, y: Float) = (x - cx) * cos(angle) + (y - cy) * sin(angle)
    private fun v(x: Float, y: Float) = -(x - cx) * sin(angle) + (y - cy) * cos(angle)
    private fun at(u: Float, v: Float) = (cx + u * cos(angle) - v * sin(angle)) to (cy + u * sin(angle) + v * cos(angle))

    fun contains(x: Float, y: Float) = Math.abs(u(x, y)) <= length / 2 && Math.abs(v(x, y)) <= thickness / 2

    /** Which long edge (-1 or 1) a pen at (x, y) is within [reach] of, or 0 if neither (or it is past the ends). */
    fun edgeNear(x: Float, y: Float, reach: Float): Int {
        if (Math.abs(u(x, y)) > length / 2 + reach) return 0
        val v = v(x, y)
        for (side in intArrayOf(-1, 1)) if (Math.abs(v - side * thickness / 2) <= reach) return side
        return 0
    }

    /** The point on the long edge [side] nearest to (x, y), kept within the ruler's length. */
    fun onEdge(x: Float, y: Float, side: Int): Pair<Float, Float> =
        at(u(x, y).coerceIn(-length / 2, length / 2), side * thickness / 2)

    /** The corner points of the body, for drawing. */
    fun corners() = listOf(at(-length / 2, -thickness / 2), at(length / 2, -thickness / 2), at(length / 2, thickness / 2), at(-length / 2, thickness / 2))

    /** Where along the edge the ruler's mark [along] is (0 at the left end), and which way across is "up", for ticks. */
    fun edgePoint(along: Float, side: Int) = at(-length / 2 + along, side * thickness / 2)

    fun move(dx: Float, dy: Float) { cx += dx; cy += dy }

    /** Two fingers moved from ([ax], [ay]) and ([bx], [by]) to ([ax2], [ay2]) and ([bx2], [by2]): it follows both. */
    fun follow(ax: Float, ay: Float, bx: Float, by: Float, ax2: Float, ay2: Float, bx2: Float, by2: Float) {
        move((ax2 + bx2 - ax - bx) / 2, (ay2 + by2 - ay - by) / 2)
        // Turn about the fingers' middle, so the ruler stays under them.
        val turn = atan2(by2 - ay2, bx2 - ax2) - atan2(by - ay, bx - ax)
        val mx = (ax2 + bx2) / 2
        val my = (ay2 + by2) / 2
        val dx = cx - mx
        val dy = cy - my
        cx = mx + dx * cos(turn) - dy * sin(turn)
        cy = my + dx * sin(turn) + dy * cos(turn)
        angle += turn
    }
}

/** What the ruler's edges measure: metric on the top edge, inches on the top edge, or metric above and inches below. */
enum class RulerUnits { METRIC, INCHES, BOTH }

/** One tick along the ruler: [along] pixels from its left end, [rank] 0 for the longest, a [label] on the numbered ones. */
data class Mark(val along: Float, val rank: Int, val label: String?)

/** Pure maths for the ruler's ticks. They measure the page, so they follow the zoom. */
object RulerScale {
    /** Ticks closer than this (pixels) are left out, finest first. */
    const val MIN_GAP_PX = 4f

    /** A number is written only where the numbered ticks are at least this far apart. */
    const val MIN_LABEL_GAP_PX = 30f

    private const val INCH = 2540.0 // page units (0.01 mm)

    /**
     * The ticks for a ruler [lengthPx] long when one page unit is [pxPerUnit] pixels. Metric: every mm, with longer
     * ticks at 5 mm and cm (numbered). Inches: every 1/16, longer at 1/8, 1/4, 1/2 and the inch (numbered).
     */
    fun marks(imperial: Boolean, lengthPx: Float, pxPerUnit: Double): List<Mark> {
        val step = (if (imperial) INCH / 16 else 100.0) * pxPerUnit // one finest tick, in pixels
        if (step <= 0.0 || step * 10 < MIN_GAP_PX) return emptyList()
        val perMajor = if (imperial) 16 else 10
        val out = ArrayList<Mark>()
        for (k in 0..(lengthPx / step).toInt()) {
            val rank = if (imperial) when { k % 16 == 0 -> 0; k % 8 == 0 -> 1; k % 4 == 0 -> 2; k % 2 == 0 -> 3; else -> 4 }
                else when { k % 10 == 0 -> 0; k % 5 == 0 -> 1; else -> 2 }
            val gap = step * (if (imperial) intArrayOf(16, 8, 4, 2, 1)[rank] else intArrayOf(10, 5, 1)[rank]) // to the next tick this long or longer
            if (gap < MIN_GAP_PX) continue
            out.add(Mark((k * step).toFloat(), rank, if (rank == 0 && step * perMajor >= MIN_LABEL_GAP_PX) (k / perMajor).toString() else null))
        }
        return out
    }
}
