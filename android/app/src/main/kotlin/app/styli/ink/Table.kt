package app.styli.ink

/**
 * A ruled grid on a page. Never changes once made. The columns run left to right from the top-left corner
 * ([x], [y]) and the rows top to bottom, so the table is as wide as its column widths add up. Units are page
 * units. [color] (the lines) and [fill] (behind the whole table) are 0xAARRGGBB as on a light page; a zero alpha
 * means the page's ordinary ink colour for the lines and no fill. A table has no angle (the format has none).
 */
class Table(
    val id: String,
    val x: Float,
    val y: Float,
    val colWidths: List<Float>,
    val rowHeights: List<Float>,
    val lineWidth: Float,
    val color: Int = 0,
    val fill: Int = 0,
) {
    val cols get() = colWidths.size
    val rows get() = rowHeights.size
    val width get() = colWidths.sum()
    val height get() = rowHeights.sum()

    fun copy(
        x: Float = this.x, y: Float = this.y, colWidths: List<Float> = this.colWidths, rowHeights: List<Float> = this.rowHeights,
        lineWidth: Float = this.lineWidth, color: Int = this.color, fill: Int = this.fill,
    ) = Table(id, x, y, colWidths, rowHeights, lineWidth, color, fill)

    /** Where vertical line [i] (0 is the left edge, [cols] the right edge) is on the page. */
    fun lineX(i: Int): Float = x + colWidths.take(i).sum()

    /** Where horizontal line [i] (0 is the top edge, [rows] the bottom edge) is on the page. */
    fun lineY(i: Int): Float = y + rowHeights.take(i).sum()

    /** A handle for dragging one line: [vertical] picks the kind, [index] the line, (x, y) is where it sits. */
    data class LineHandle(val vertical: Boolean, val index: Int, val x: Float, val y: Float)

    /**
     * One handle per line. A vertical line's is in the middle of the top row and a horizontal line's in the middle
     * of the first column, which keeps every handle clear of every other and of the table's lines' crossings (a
     * handle in the middle of the table would meet another there for an even number of rows and columns).
     *
     * While a line is being dragged, pass the table as it was when the drag began as [anchor]: the handles then
     * keep their other coordinate from it, so only the line being moved (and the lines it pushes) slide, and no
     * handle drifts along its line as the first row or column changes size.
     */
    fun lineHandles(anchor: Table = this): List<LineHandle> {
        val topRow = anchor.y + anchor.rowHeights[0] / 2
        val firstCol = anchor.x + anchor.colWidths[0] / 2
        return (0..cols).map { LineHandle(true, it, lineX(it), topRow) } + (0..rows).map { LineHandle(false, it, firstCol, lineY(it)) }
    }

    /** Is the page point within [tol] of one of the table's lines (the outline included)? */
    fun nearLine(px: Float, py: Float, tol: Float): Boolean {
        if (px < x - tol || px > x + width + tol || py < y - tol || py > y + height + tol) return false
        return (0..cols).any { Math.abs(px - lineX(it)) <= tol } || (0..rows).any { Math.abs(py - lineY(it)) <= tol }
    }

    /** The (row, column) of the cell holding the page point, or null outside the table. A line belongs to the cell after it. */
    fun cellAt(px: Float, py: Float): Pair<Int, Int>? {
        if (!contains(px, py)) return null
        val col = (0 until cols).lastOrNull { px >= lineX(it) } ?: 0
        val row = (0 until rows).lastOrNull { py >= lineY(it) } ?: 0
        return row to col
    }

    /** Is the page point inside the table (the outline included)? */
    fun contains(px: Float, py: Float) = px >= x && px <= x + width && py >= y && py <= y + height

    /**
     * Moves one line by [d] (page units, to the right or down) and returns the new table; [vertical] picks vertical
     * lines (numbered 0..[cols] from the left) or horizontal ones (0..[rows] from the top). An inner line makes
     * the two cells beside it trade size; an outer edge stretches or shrinks the first or last cell, the table's
     * other edge staying where it is. No cell is allowed under [MIN_CELL], so the move stops short of that; the
     * same table comes back when the line cannot move that way at all.
     */
    fun moveLine(vertical: Boolean, index: Int, d: Float): Table {
        val sizes = (if (vertical) colWidths else rowHeights).toMutableList()
        require(index in 0..sizes.size) { "no such line" }
        val last = sizes.size
        var delta = d
        var shift = 0f // how far the table's own corner moves (only the left or top edge moves it)
        when (index) {
            0 -> { delta = delta.coerceAtMost(sizes[0] - MIN_CELL); sizes[0] -= delta; shift = delta }
            last -> { delta = delta.coerceAtLeast(MIN_CELL - sizes[last - 1]); sizes[last - 1] += delta }
            else -> {
                delta = delta.coerceIn(MIN_CELL - sizes[index - 1], sizes[index] - MIN_CELL)
                sizes[index - 1] += delta
                sizes[index] -= delta
            }
        }
        if (delta == 0f) return this
        return if (vertical) copy(x = x + shift, colWidths = sizes) else copy(y = y + shift, rowHeights = sizes)
    }

    /** The table with a new row (or column, if [vertical]) of [size] as number [at] (0 to [rows] or [cols]; the last puts it after). */
    fun inserted(vertical: Boolean, at: Int, size: Float): Table {
        val sizes = (if (vertical) colWidths else rowHeights).toMutableList()
        require(at in 0..sizes.size) { "no such place" }
        sizes.add(at, size)
        return if (vertical) copy(colWidths = sizes) else copy(rowHeights = sizes)
    }

    /** The table without row (or column, if [vertical]) number [index]. The last one cannot go. */
    fun deleted(vertical: Boolean, index: Int): Table {
        val sizes = (if (vertical) colWidths else rowHeights).toMutableList()
        require(sizes.size > 1 && index in sizes.indices) { "no such row or column, or the last one" }
        sizes.removeAt(index)
        return if (vertical) copy(colWidths = sizes) else copy(rowHeights = sizes)
    }

    companion object {
        /** Can a [rows] by [cols] table fill a [width] by [height] rectangle with every cell at least [MIN_CELL]? */
        fun fits(width: Float, height: Float, rows: Int, cols: Int) =
            rows in 1..MAX_LINES && cols in 1..MAX_LINES && width / cols >= MIN_CELL && height / rows >= MIN_CELL

        /** The smallest a cell may be: 5 mm. */
        const val MIN_CELL = 500f

        /** The most rows or columns. */
        const val MAX_LINES = 20
    }
}
