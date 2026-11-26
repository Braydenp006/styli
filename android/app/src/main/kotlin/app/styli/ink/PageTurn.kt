package app.styli.ink

import kotlin.math.abs
import kotlin.math.sign

/**
 * How scrolling works over several pages. SEAMLESS: one surface you scroll freely across, and no pulling to add pages.
 * GRID: while a page is fitted to the screen the scroll is locked to one direction at a time and keeps to the row or
 * column of the page you are on, snapping to pages; pulling past the end of that row or column adds a page.
 */
enum class ScrollMode { SEAMLESS, GRID }

/** What a finished pull does. */
enum class Turn { NONE, ADD }

/** Pure rules for pulling past the end of a row or column of pages with a finger. */
object PageTurn {
    /** How far a pull must go: 40 mm, in page units (and then it is held for a moment: see the view). */
    const val PULL = 4000.0

    /** What happens when a pull of [pull] page units past the end of a row or column is let go: a short one nothing, a long one adds a page. */
    fun decide(pull: Double): Turn = if (pull >= PULL) Turn.ADD else Turn.NONE

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
