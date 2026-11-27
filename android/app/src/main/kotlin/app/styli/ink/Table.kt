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
    val cellFills: List<Int> = emptyList(),
    val cellText: List<String> = emptyList(),
    val fontSize: Float = 0f,
    val textColor: Int = 0,
    val headerRow: Boolean = false,
    val colAlign: List<Int> = emptyList(),
) {
    val cols get() = colWidths.size
    val rows get() = rowHeights.size
    val width get() = colWidths.sum()
    val height get() = rowHeights.sum()

    fun copy(
        x: Float = this.x, y: Float = this.y, colWidths: List<Float> = this.colWidths, rowHeights: List<Float> = this.rowHeights,
        lineWidth: Float = this.lineWidth, color: Int = this.color, fill: Int = this.fill, cellFills: List<Int> = this.cellFills,
        cellText: List<String> = this.cellText, fontSize: Float = this.fontSize, textColor: Int = this.textColor,
        headerRow: Boolean = this.headerRow, colAlign: List<Int> = this.colAlign,
    ) = Table(id, x, y, colWidths, rowHeights, lineWidth, color, fill, cellFills, cellText, fontSize, textColor, headerRow, colAlign)

    /** The same table under another ID (a pasted copy). */
    fun withId(newId: String) = Table(newId, x, y, colWidths, rowHeights, lineWidth, color, fill, cellFills, cellText, fontSize, textColor, headerRow, colAlign)

    /** The typed text of the cell at ([row], [col]), empty for none. */
    fun cellTextAt(row: Int, col: Int) = cellText.getOrElse(row * cols + col) { "" }

    /** Where the text of column [col] sits: 0 left, 1 middle, 2 right. */
    fun alignOf(col: Int) = colAlign.getOrElse(col) { 0 }

    /** Is there any typed text at all? */
    val hasText get() = cellText.any { it.isNotEmpty() }

    /** The cells' text as rows of strings, every row [cols] long. */
    fun textGrid(): List<List<String>> = List(rows) { r -> List(cols) { c -> cellTextAt(r, c) } }

    /** The table with at least [minRows] rows and [minCols] columns: a new row or column is as big as the last one. */
    fun grown(minRows: Int, minCols: Int): Table {
        var t = this
        while (t.cols < minCols && t.cols < MAX_LINES) t = t.inserted(true, t.cols, t.colWidths.last())
        while (t.rows < minRows && t.rows < MAX_LINES) t = t.inserted(false, t.rows, t.rowHeights.last())
        return t
    }

    /**
     * The table with its typed text replaced by [grid] (rows of strings; it grows to hold them, up to [MAX_LINES] each way,
     * and cells the grid does not reach are empty), the header row on or off, and the column alignments. Ink is untouched.
     */
    fun withData(grid: List<List<String>>, header: Boolean, align: List<Int>): Table {
        val t = grown(grid.size, grid.maxOfOrNull { it.size } ?: 0)
        val texts = List(t.rows * t.cols) { grid.getOrNull(it / t.cols)?.getOrNull(it % t.cols) ?: "" }
        val aligns = List(t.cols) { align.getOrElse(it) { 0 } }
        return t.copy(cellText = if (texts.all { it.isEmpty() }) emptyList() else texts, headerRow = header, colAlign = if (aligns.all { it == 0 }) emptyList() else aligns)
    }

    /** The colour of the cell at ([row], [col]), row by row; a zero alpha (or no list at all) means none. */
    fun cellFill(row: Int, col: Int) = cellFills.getOrElse(row * cols + col) { 0 }

    /** The table with that cell's colour set (zero for none). */
    fun withCellFill(row: Int, col: Int, color: Int): Table {
        require(row in 0 until rows && col in 0 until cols) { "no such cell" }
        val all = List(rows * cols) { cellFill(it / cols, it % cols) }.toMutableList()
        all[row * cols + col] = color
        return copy(cellFills = if (all.all { it == 0 }) emptyList() else all)
    }

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
        // The new cells have no colour; the others keep theirs.
        val cells = if (cellFills.isEmpty()) cellFills else if (vertical) {
            List(rows) { r -> List(cols + 1) { c -> if (c == at) 0 else cellFill(r, if (c > at) c - 1 else c) } }.flatten()
        } else {
            List(rows + 1) { r -> List(cols) { c -> if (r == at) 0 else cellFill(if (r > at) r - 1 else r, c) } }.flatten()
        }
        val texts = if (cellText.isEmpty()) cellText else if (vertical) {
            List(rows) { r -> List(cols + 1) { c -> if (c == at) "" else cellTextAt(r, if (c > at) c - 1 else c) } }.flatten()
        } else {
            List(rows + 1) { r -> List(cols) { c -> if (r == at) "" else cellTextAt(if (r > at) r - 1 else r, c) } }.flatten()
        }
        val aligns = if (vertical && colAlign.isNotEmpty()) List(cols + 1) { c -> if (c == at) 0 else alignOf(if (c > at) c - 1 else c) } else colAlign
        return if (vertical) copy(colWidths = sizes, cellFills = cells, cellText = texts, colAlign = aligns) else copy(rowHeights = sizes, cellFills = cells, cellText = texts)
    }

    /** The table without row (or column, if [vertical]) number [index]. The last one cannot go. */
    fun deleted(vertical: Boolean, index: Int): Table {
        val sizes = (if (vertical) colWidths else rowHeights).toMutableList()
        require(sizes.size > 1 && index in sizes.indices) { "no such row or column, or the last one" }
        sizes.removeAt(index)
        val cells = if (cellFills.isEmpty()) cellFills else if (vertical) {
            List(rows) { r -> List(cols - 1) { c -> cellFill(r, if (c >= index) c + 1 else c) } }.flatten()
        } else {
            List(rows - 1) { r -> List(cols) { c -> cellFill(if (r >= index) r + 1 else r, c) } }.flatten()
        }
        val texts = if (cellText.isEmpty()) cellText else if (vertical) {
            List(rows) { r -> List(cols - 1) { c -> cellTextAt(r, if (c >= index) c + 1 else c) } }.flatten()
        } else {
            List(rows - 1) { r -> List(cols) { c -> cellTextAt(if (r >= index) r + 1 else r, c) } }.flatten()
        }
        val aligns = if (vertical && colAlign.isNotEmpty()) List(cols - 1) { c -> alignOf(if (c >= index) c + 1 else c) } else colAlign
        return if (vertical) copy(colWidths = sizes, cellFills = cells, cellText = texts, colAlign = aligns) else copy(rowHeights = sizes, cellFills = cells, cellText = texts)
    }

    companion object {
        /** Can a [rows] by [cols] table fill a [width] by [height] rectangle with every cell at least [MIN_CELL]? */
        fun fits(width: Float, height: Float, rows: Int, cols: Int) =
            rows in 1..MAX_LINES && cols in 1..MAX_LINES && width / cols >= MIN_CELL && height / rows >= MIN_CELL

        /** The smallest a cell may be: 5 mm. */
        const val MIN_CELL = 500f

        /** The most rows or columns. */
        const val MAX_LINES = 100
    }
}
