package app.styli.ink

import kotlin.math.max

/**
 * Where each page of a notebook sits on one scrolling surface ("the world", in page units). Pages are in their
 * grid cells with a small [gap] between: a column is as wide as its widest page and a row as tall as its tallest,
 * and a page sits at the top left of its cell. Columns and rows that hold no page take no room. Pure Kotlin.
 */
class NotebookLayout(private val pages: List<PageModel>, private val gap: Int = GAP) {
    private val cols = pages.map { it.page.col }.distinct().sorted()
    private val rows = pages.map { it.page.row }.distinct().sorted()
    private val colW = cols.associateWith { c -> pages.filter { it.page.col == c }.maxOf { it.page.width } }
    private val rowH = rows.associateWith { r -> pages.filter { it.page.row == r }.maxOf { it.page.height } }
    private val colX = HashMap<Int, Int>().also { var x = 0; for (c in cols) { it[c] = x; x += colW.getValue(c) + gap } }
    private val rowY = HashMap<Int, Int>().also { var y = 0; for (r in rows) { it[r] = y; y += rowH.getValue(r) + gap } }

    val worldW: Int = max(1, cols.sumOf { colW.getValue(it) } + gap * (cols.size - 1))
    val worldH: Int = max(1, rows.sumOf { rowH.getValue(it) } + gap * (rows.size - 1))

    /** The width of the first and last column and the height of the first and last row: how far the ends of the surface can scroll. */
    val firstColW: Int = colW.getValue(cols.first())
    val lastColW: Int = colW.getValue(cols.last())
    val firstRowH: Int = rowH.getValue(rows.first())
    val lastRowH: Int = rowH.getValue(rows.last())

    /** Each column's left edge and width, for settling a horizontal scroll onto one. */
    val columns: List<Pair<Double, Double>> = cols.map { colX.getValue(it).toDouble() to colW.getValue(it).toDouble() }

    /** The top left corner of [p]'s sheet on the surface. */
    fun origin(p: PageModel): Pair<Double, Double> = colX.getValue(p.page.col).toDouble() to rowY.getValue(p.page.row).toDouble()

    private fun distance(p: PageModel, x: Double, y: Double): Double {
        val (ox, oy) = origin(p)
        val dx = max(max(ox - x, x - (ox + p.page.width)), 0.0)
        val dy = max(max(oy - y, y - (oy + p.page.height)), 0.0)
        return Math.hypot(dx, dy)
    }

    /** The page whose sheet holds the point, or null in a gap or an empty cell. */
    fun pageAt(x: Double, y: Double): PageModel? = pages.firstOrNull { distance(it, x, y) == 0.0 }

    /** The page whose sheet is closest to the point (the one holding it, if any). */
    fun nearest(x: Double, y: Double): PageModel = pages.minBy { distance(it, x, y) }

    /** The pages whose sheets touch the box (left, top, right, bottom), for drawing only what is on screen. */
    fun visible(l: Double, t: Double, r: Double, b: Double): List<PageModel> = pages.filter {
        val (ox, oy) = origin(it)
        ox < r && ox + it.page.width > l && oy < b && oy + it.page.height > t
    }

    private fun columnNear(x: Double): Int = cols.minBy { c ->
        val l = colX.getValue(c).toDouble()
        max(max(l - x, x - (l + colW.getValue(c))), 0.0)
    }

    private fun rowNear(y: Double): Int = rows.minBy { r ->
        val t = rowY.getValue(r).toDouble()
        max(max(t - y, y - (t + rowH.getValue(r))), 0.0)
    }

    /** The lowest page in the column at (or nearest) [x]: the one a page can be added below. */
    fun bottomOfColumnAt(x: Double): PageModel = columnNear(x).let { c -> pages.filter { it.page.col == c }.maxBy { it.page.row } }

    /** The right-most page in the row at (or nearest) [y]: the one a page can be added to the right of. */
    fun rightOfRowAt(y: Double): PageModel = rowNear(y).let { r -> pages.filter { it.page.row == r }.maxBy { it.page.col } }

    companion object {
        /** The space between pages: 8 mm. */
        const val GAP = 800
    }
}

/**
 * One page seen through the surface's [PageGeometry]: the same conversions the single-page code always used
 * (page units to and from the screen), with the page's place on the surface folded in. [PageGeometry] keeps the
 * scrolling and zooming for the whole surface.
 */
class PageView(
    private val surface: PageGeometry,
    private val ox: Double,
    private val oy: Double,
    val pageW: Int,
    val pageH: Int,
    val infinite: Boolean,
) {
    val scale get() = surface.scale
    val tx get() = surface.tx + ox * surface.scale
    val ty get() = surface.ty + oy * surface.scale

    fun toPageX(sx: Double) = surface.toPageX(sx) - ox
    fun toPageY(sy: Double) = surface.toPageY(sy) - oy
    fun toScreenX(px: Double) = surface.toScreenX(px + ox)
    fun toScreenY(py: Double) = surface.toScreenY(py + oy)

    /** What the screen shows of this page, in its own units: left, top, right, bottom. */
    fun visible(): DoubleArray = surface.visible().let { doubleArrayOf(it[0] - ox, it[1] - oy, it[2] - ox, it[3] - oy) }

    fun panBy(dx: Double, dy: Double) = surface.panBy(dx, dy)
    fun zoomBy(factor: Double, focusX: Double, focusY: Double) = surface.zoomBy(factor, focusX, focusY)
}

/** Settling a horizontal scroll onto a column, a little. */
object PageSnap {
    /** The pull of a column reaches this share of the screen's width. */
    const val RANGE = 0.18

    /** Up and down the pull reaches this share of the screen's height (a page is tall, so a little more). */
    const val RANGE_Y = 0.22

    /** A glide that would stop within this share of the screen of a page is bent to stop on it. */
    const val LAND = 0.35

    /**
     * The horizontal offset to settle at, or null to stay. A column narrower than the screen settles centred;
     * a wider one settles with its left or right edge at the [margin]. Only a column within [range] pixels
     * counts, so a scroll that ends between columns far from both is left where it is.
     */
    fun target(tx: Double, scale: Double, viewW: Int, columns: List<Pair<Double, Double>>, margin: Double, range: Double): Double? {
        var best: Double? = null
        for ((left, w) in columns) {
            val wPx = w * scale
            val l = left * scale
            val options = if (wPx + 2 * margin <= viewW) listOf((viewW - wPx) / 2 - l) else listOf(margin - l, viewW - margin - wPx - l)
            for (o in options) if (best == null || Math.abs(o - tx) < Math.abs(best - tx)) best = o
        }
        val b = best ?: return null
        val d = Math.abs(b - tx)
        return if (d < 0.5 || d > range) null else b
    }

    /**
     * The offsets a locked scroll along these [spans] (each a start and a size on the surface, along one axis) may
     * reach: from the first one at the top (or centred, if it fits) to the last one at the bottom (or centred).
     * Between them a span longer than the screen scrolls freely. Works for either axis ([viewSize] is its length).
     */
    fun range(viewSize: Int, scale: Double, spans: List<Pair<Double, Double>>, margin: Double): Pair<Double, Double> {
        if (spans.isEmpty()) return 0.0 to 0.0
        val sorted = spans.sortedBy { it.first }
        fun fits(size: Double) = size * scale + 2 * margin <= viewSize
        val (firstStart, firstSize) = sorted.first()
        val (lastStart, lastSize) = sorted.last()
        val a = if (fits(firstSize)) (viewSize - firstSize * scale) / 2 - firstStart * scale else margin - firstStart * scale
        val b = if (fits(lastSize)) (viewSize - lastSize * scale) / 2 - lastStart * scale else viewSize - margin - lastSize * scale - lastStart * scale
        return minOf(a, b) to maxOf(a, b)
    }
}
