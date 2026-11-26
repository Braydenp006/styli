package app.styli.ink

import kotlin.math.abs
import kotlin.math.sign

/** Which pulls past the end of the surface may make a new page (a setting). */
enum class Flow { DOWN, RIGHT, BOTH }

/** What a finished pull does. */
enum class Turn { NONE, ADD }

/** Pure rules for pulling the surface past its end with a finger. */
object PageTurn {
    /** How far a pull must go: 25 mm, in page units. */
    const val PULL = 2500.0

    /**
     * What happens when a pull of [pull] page units past the end of the surface in direction [dir] is let go. A
     * short pull does nothing. A long one adds a page, but only below or to the right and only if [flow] allows that
     * direction (the surface's top and left have no room for one).
     */
    fun decide(pull: Double, dir: Dir, flow: Flow): Turn = when {
        pull < PULL -> Turn.NONE
        dir == Dir.DOWN && flow != Flow.RIGHT -> Turn.ADD
        dir == Dir.RIGHT && flow != Flow.DOWN -> Turn.ADD
        else -> Turn.NONE
    }

    /**
     * The pull on one axis after a finger move of [d] along it, of which [leftover] could not move the page (it
     * was at its edge). Moving back eases the pull off, and never past zero.
     */
    fun pull(old: Double, d: Double, leftover: Double): Double {
        if (old != 0.0 && sign(d) != sign(old)) {
            val v = old + d
            return if (sign(v) == sign(old)) v else 0.0
        }
        return old + leftover
    }

    /**
     * The part of a pull to show and to act on: only the longer axis. A little sideways drift in a vertical scroll
     * (a finger is never straight) must neither move the page sideways nor count as a pull.
     */
    fun dominant(x: Double, y: Double): Pair<Double, Double> = if (abs(y) >= abs(x)) 0.0 to y else x to 0.0

    /**
     * Which page a pull is for and how far: the finger offsets (x, y) past the edges, as screen distances. The
     * longer axis wins. A finger dragged up is for the page below, left for the page to the right, down for
     * the page above, right for the page to the left. Null when there is no pull.
     */
    fun direction(x: Double, y: Double): Pair<Dir, Double>? = when {
        x == 0.0 && y == 0.0 -> null
        abs(y) >= abs(x) -> (if (y < 0) Dir.DOWN else Dir.UP) to abs(y)
        else -> (if (x < 0) Dir.RIGHT else Dir.LEFT) to abs(x)
    }
}
