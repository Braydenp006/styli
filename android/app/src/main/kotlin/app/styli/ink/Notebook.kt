package app.styli.ink

import app.styli.ink.proto.PageSet
import kotlin.math.abs

/** A way to step from one page to the next on the notebook's grid. DOWN is the page below, RIGHT the page to the right. */
enum class Dir(val dCol: Int, val dRow: Int) { UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0) }

/**
 * The pages of one notebook, each in a cell of a grid (column and row, kept in the page's own `PageSet`).
 * Pure Kotlin, in memory only for now. [current] is the page being shown.
 */
class Notebook(first: PageModel = PageModel()) {
    private val all = mutableListOf(first)
    private val made = HashSet<PageModel>() // pages added by [add], which [removeMade] may take back
    private val madeOrder = ArrayList<PageModel>() // in the order they were added
    private val from = HashMap<PageModel, PageModel>() // the page each one was added from

    /** The bytes of every picture on any page of this notebook, by hash (pictures move between pages by their hash). */
    val pictureFiles = PictureStore()

    var current: PageModel = first
        private set

    val pages: List<PageModel> get() = all.toList()

    /** Adds a page that was loaded from disk, keeping its own place in the grid. */
    internal fun adopt(p: PageModel) { all.add(p) }

    fun at(col: Int, row: Int): PageModel? = all.firstOrNull { it.page.col == col && it.page.row == row }

    /** A page next to the cell ([col], [row]) on any side: the one a page added there takes its look from. Null if none. */
    fun neighbourOfCell(col: Int, row: Int): PageModel? = Dir.entries.firstNotNullOfOrNull { at(col + it.dCol, row + it.dRow) }

    fun neighbour(p: PageModel, d: Dir): PageModel? = at(p.page.col + d.dCol, p.page.row + d.dRow)

    /**
     * The pages in a straight line with [p] on the grid with no empty cell between, in order: its row, left to right,
     * or (if not [horizontal]) its column, top to bottom. [p] is in it. This is how far a locked scroll may travel.
     */
    fun run(p: PageModel, horizontal: Boolean): List<PageModel> {
        val back = if (horizontal) Dir.LEFT else Dir.UP
        val forward = if (horizontal) Dir.RIGHT else Dir.DOWN
        val out = ArrayDeque<PageModel>()
        out.add(p)
        var q = p
        while (true) { q = neighbour(q, back) ?: break; out.addFirst(q) }
        q = p
        while (true) { q = neighbour(q, forward) ?: break; out.addLast(q) }
        return out.toList()
    }

    /** The last page of the run from [p] in direction [d]: the one a page can be added beside on that side. */
    fun end(p: PageModel, d: Dir): PageModel {
        var q = p
        while (true) q = neighbour(q, d) ?: return q
    }

    fun go(p: PageModel) {
        require(p in all) { "not a page of this notebook" }
        current = p
    }

    /**
     * Adds a page next to [p] in direction [d] and makes it current. It starts with [p]'s size, template and
     * fixed-or-infinite setting. Null (and nothing added) if that cell already holds a page.
     */
    fun add(p: PageModel, d: Dir): PageModel? {
        require(p in all) { "not a page of this notebook" }
        return addAt(p, p.page.col + d.dCol, p.page.row + d.dRow)
    }

    /** Like [add], but into any empty cell ([col], [row]), with [p]'s template and fixed-or-infinite setting and the size of [look] (or [p]'s). Null if the cell is taken. */
    fun addAt(p: PageModel, col: Int, row: Int, look: PageSet? = null): PageModel? {
        require(p in all) { "not a page of this notebook" }
        if (at(col, row) != null) return null
        val b = p.page.toBuilder().setCol(col).setRow(row)
        if (look != null && look.width > 0 && look.height > 0) b.setWidth(look.width).setHeight(look.height)
        val page = PageModel(b.build())
        all.add(page)
        made.add(page)
        madeOrder.add(page)
        from[page] = p
        pageUndo.clear(); pageRedo.clear() // a new page starts the page history afresh, so no step can land on its cell
        current = page
        return page
    }

    /**
     * Takes back the page added last if nothing has been done on it (no strokes, text, or any undo step). If it was
     * the current page, goes back to the page it was added from. Anything else is refused: a page with content is
     * never removed here.
     */
    fun removeMade(): Boolean {
        val p = madeOrder.lastOrNull() ?: return false
        if (p.canUndo || p.items.isNotEmpty() || p.texts.isNotEmpty()) return false
        all.remove(p)
        made.remove(p)
        madeOrder.removeAt(madeOrder.lastIndex)
        val back = from.remove(p)?.takeIf { it in all }
        if (current === p) current = back ?: all.first()
        pageUndo.clear(); pageRedo.clear() // they may name this page
        return true
    }

    // Page changes (moves, swaps, deletes) have their own undo, apart from each page's drawing undo.
    private class Change(val before: Map<PageModel, Pair<Int, Int>>, val after: Map<PageModel, Pair<Int, Int>>, val deleted: PageModel? = null)
    private val pageUndo = ArrayDeque<Change>()
    private val pageRedo = ArrayDeque<Change>()

    /** Pages deleted and not (yet) put back, newest last. In memory only. */
    val trash: List<PageModel> get() = pageUndo.mapNotNull { it.deleted }.filter { it !in all }

    val canUndoPage get() = pageUndo.isNotEmpty()
    val canRedoPage get() = pageRedo.isNotEmpty()

    private fun cellOf(p: PageModel) = p.page.col to p.page.row

    /**
     * Moves [p] to the cell ([col], [row]); if a page is there the two swap. False (nothing changes) if [p] is
     * already there. One page-change undo step. Into an empty cell, the page takes the size of [look] (the paper and
     * orientation the empty cells are shown in) if it differs; that is a drawing undo step of the page, like turning it by hand.
     */
    fun movePage(p: PageModel, col: Int, row: Int, look: PageSet? = null): Boolean {
        require(p in all) { "not a page of this notebook" }
        val to = col to row
        if (cellOf(p) == to) return false
        val other = at(col, row)
        if (other == null && look != null && (look.width != p.page.width || look.height != p.page.height)) {
            p.setPage(p.page.toBuilder().setWidth(look.width).setHeight(look.height).build())
        }
        val before = buildMap { put(p, cellOf(p)); if (other != null) put(other, to) }
        val after = buildMap { put(p, to); if (other != null) put(other, cellOf(p)) }
        pageUndo.addLast(Change(before, after)); pageRedo.clear()
        place(after)
        return true
    }

    /**
     * Takes [p] out of the notebook, into the trash (see [trash]); a later [undoPage] puts it back. The last page
     * is refused (false). If [p] was the current page, the nearest remaining one becomes current.
     */
    fun deletePage(p: PageModel): Boolean {
        require(p in all) { "not a page of this notebook" }
        if (all.size == 1) return false
        pageUndo.addLast(Change(mapOf(p to cellOf(p)), emptyMap(), deleted = p)); pageRedo.clear()
        takeOut(p)
        return true
    }

    /** Takes back the last page change (not drawing: that is [PageModel.undo]). False if there is none. */
    fun undoPage(): Boolean {
        val c = pageUndo.removeLastOrNull() ?: return false
        pageRedo.addLast(c)
        if (c.deleted != null) bringBack(c.deleted, c.before.getValue(c.deleted)) else place(c.before)
        return true
    }

    fun redoPage(): Boolean {
        val c = pageRedo.removeLastOrNull() ?: return false
        pageUndo.addLast(c)
        if (c.deleted != null) takeOut(c.deleted) else place(c.after)
        return true
    }

    private fun place(cells: Map<PageModel, Pair<Int, Int>>) = cells.forEach { (p, c) -> p.placeAt(c.first, c.second) }

    private fun takeOut(p: PageModel) {
        val c = cellOf(p)
        all.remove(p); made.remove(p); madeOrder.remove(p); from.remove(p)
        if (current === p) current = all.minBy { abs(it.page.col - c.first) + abs(it.page.row - c.second) }
    }

    /** Puts [p] back at [cell], or at the nearest empty cell if something has taken it since. */
    private fun bringBack(p: PageModel, cell: Pair<Int, Int>) {
        var r = 0
        while (true) {
            val free = (-r..r).flatMap { dc -> (-r..r).map { dr -> cell.first + dc to cell.second + dr } }
                .filter { abs(it.first - cell.first) + abs(it.second - cell.second) == r && at(it.first, it.second) == null }
                .minWithOrNull(compareBy({ it.second }, { it.first }))
            if (free != null) { p.placeAt(free.first, free.second); all.add(p); return }
            r++
        }
    }

    /** Row by row from the top, left to right in a row: the order the pages are read in. */
    fun readingOrder(): List<PageModel> = all.sortedWith(compareBy({ it.page.row }, { it.page.col }))
}
