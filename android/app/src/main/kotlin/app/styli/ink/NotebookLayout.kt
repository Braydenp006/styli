package app.styli.ink

import kotlin.math.max

/**
 * Where each page of a notebook sits on one scrolling surface ("the world", in page units). Pages are in their
 * grid cells with a small [gap] between: a column is as wide as its widest page and a row as tall as its tallest,
 * and a page sits at the top left of its cell. Columns and rows that hold no page still take room, as wide or
 * tall as the biggest page, so a page put far from the others stays far. Pure Kotlin.
 */
class NotebookLayout(private val pages: List<PageModel>, private val gap: Int = GAP, refW: Int = 0, refH: Int = 0) {
    private val cols = pages.map { it.page.col }.distinct().sorted() // columns and rows that hold a page
    private val rows = pages.map { it.page.row }.distinct().sorted()

    // Every column and row from the first to the last takes room, empty ones too (as wide or tall as the biggest
    // page), so a page put far away stays far away.
    // With a reference size ([refW] by [refH], what an empty cell shows) they are that size, and no column or row is
    // smaller, so an empty cell never overhangs its neighbours; without one, the biggest page's size.
    private val emptyW = if (refW > 0) refW else pages.maxOf { it.page.width }
    private val emptyH = if (refH > 0) refH else pages.maxOf { it.page.height }
    private val allCols = (cols.first()..cols.last()).toList()
    private val allRows = (rows.first()..rows.last()).toList()
    private val colW = allCols.associateWith { c -> max(pages.filter { it.page.col == c }.maxOfOrNull { it.page.width } ?: 0, if (refW > 0) refW else 0).takeIf { it > 0 } ?: emptyW }
    private val rowH = allRows.associateWith { r -> max(pages.filter { it.page.row == r }.maxOfOrNull { it.page.height } ?: 0, if (refH > 0) refH else 0).takeIf { it > 0 } ?: emptyH }
    private val colX = HashMap<Int, Int>().also { var x = 0; for (c in allCols) { it[c] = x; x += colW.getValue(c) + gap } }
    private val rowY = HashMap<Int, Int>().also { var y = 0; for (r in allRows) { it[r] = y; y += rowH.getValue(r) + gap } }

    val worldW: Int = max(1, allCols.sumOf { colW.getValue(it) } + gap * (allCols.size - 1))
    val worldH: Int = max(1, allRows.sumOf { rowH.getValue(it) } + gap * (allRows.size - 1))

    /** The width of the first and last column and the height of the first and last row: how far the ends of the surface can scroll. */
    val firstColW: Int = colW.getValue(cols.first())
    val lastColW: Int = colW.getValue(cols.last())
    val firstRowH: Int = rowH.getValue(rows.first())
    val lastRowH: Int = rowH.getValue(rows.last())

    /** Each column's left edge and width, for settling a horizontal scroll onto one (only columns that hold a page). */
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
    /** The one of [candidates] nearest the surface point ([x], [y]); null if there are none. */
    fun nearestOf(candidates: Collection<PageModel>, x: Double, y: Double): PageModel? = candidates.minByOrNull { distance(it, x, y) }

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

    /**
     * Where a page can be dropped or added when rearranging: every cell from the first column and row to the last,
     * and the cells outside them, as many rings as the view [view] (left, top, right, bottom on the surface) shows,
     * and at least one ring. Each is (col, row).
     */
    fun slots(view: DoubleArray? = null): List<Pair<Int, Int>> {
        fun reach(first: Int, last: Int, start: Double, end: Double, step: Double, lo: Double, hi: Double): Pair<Int, Int> {
            var a = first - 1; var b = last + 1
            if (view != null) {
                while (first - a < MAX_RINGS && start - (first - a) * step > lo) a--
                while (b - last < MAX_RINGS && end + (b - last) * step < hi) b++
            }
            return a to b
        }
        val (c0, c1) = reach(allCols.first(), allCols.last(), 0.0, worldW.toDouble(), emptyW + gap.toDouble(), view?.get(0) ?: 0.0, view?.get(2) ?: 0.0)
        val (r0, r1) = reach(allRows.first(), allRows.last(), 0.0, worldH.toDouble(), emptyH + gap.toDouble(), view?.get(1) ?: 0.0, view?.get(3) ?: 0.0)
        return (c0..c1).flatMap { c -> (r0..r1).map { r -> c to r } }
    }

    /**
     * The sheet-sized rectangle (x, y, width, height, in surface units) of a cell from [slots]: the size of the page
     * in it, or [refW] by [refH] if it is empty (so empty cells show the paper and orientation a page there would
     * get), at the top left of its cell. Outside the grid, cells sit where a page added there would.
     */
    fun slotRect(col: Int, row: Int, refW: Int, refH: Int): DoubleArray {
        val here = pages.firstOrNull { it.page.col == col && it.page.row == row }
        val w = here?.page?.width ?: refW
        val h = here?.page?.height ?: refH
        val x = colX[col]?.toDouble() ?: if (col < allCols.first()) -(allCols.first() - col) * (emptyW + gap).toDouble()
            else worldW + gap + (col - allCols.last() - 1) * (emptyW + gap).toDouble()
        val y = rowY[row]?.toDouble() ?: if (row < allRows.first()) -(allRows.first() - row) * (emptyH + gap).toDouble()
            else worldH + gap + (row - allRows.last() - 1) * (emptyH + gap).toDouble()
        return doubleArrayOf(x, y, w.toDouble(), h.toDouble())
    }

    /** The column (or row) at [v] along one axis: one inside the grid by its span (grown by half a gap), or one outside by steps of [step]. */
    private fun axisCell(v: Double, ids: List<Int>, start: (Int) -> Double, size: (Int) -> Double, step: Double): Int {
        val m = gap / 2.0
        ids.firstOrNull { v >= start(it) - m && v <= start(it) + size(it) + m }?.let { return it }
        val first = ids.first(); val last = ids.last()
        val lo = start(first) - m
        if (v < lo) return first - Math.ceil((lo - v) / step).toInt()
        val hi = start(last) + size(last) + m
        return last + Math.ceil((v - hi) / step).toInt()
    }

    /** The cell at the surface point ([x], [y]), anywhere, however far outside the grid. */
    fun slotAt(x: Double, y: Double): Pair<Int, Int> =
        axisCell(x, allCols, { colX.getValue(it).toDouble() }, { colW.getValue(it).toDouble() }, emptyW + gap.toDouble()) to
            axisCell(y, allRows, { rowY.getValue(it).toDouble() }, { rowH.getValue(it).toDouble() }, emptyH + gap.toDouble())

    companion object {
        /** The space between pages: 8 mm. */
        const val GAP = 800

        /** The most rings of empty cells shown past an edge. */
        const val MAX_RINGS = 40
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
