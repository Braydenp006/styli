package app.styli.ink

/** A way to step from one page to the next on the notebook's grid. DOWN is the page below, RIGHT the page to the right. */
enum class Dir(val dCol: Int, val dRow: Int) { UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0) }

/**
 * The pages of one notebook, each in a cell of a grid (column and row, kept in the page's own `PageSet`).
 * Pure Kotlin, in memory only for now. [current] is the page being shown.
 */
class Notebook(first: PageModel = PageModel()) {
    private val all = mutableListOf(first)
    private val made = HashSet<PageModel>() // pages added by [add], which [removeMade] may take back
    private val from = HashMap<PageModel, PageModel>() // the page each one was added from

    var current: PageModel = first
        private set

    val pages: List<PageModel> get() = all.toList()

    fun at(col: Int, row: Int): PageModel? = all.firstOrNull { it.page.col == col && it.page.row == row }

    fun neighbour(p: PageModel, d: Dir): PageModel? = at(p.page.col + d.dCol, p.page.row + d.dRow)

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
        val col = p.page.col + d.dCol
        val row = p.page.row + d.dRow
        if (at(col, row) != null) return null
        val page = PageModel(p.page.toBuilder().setCol(col).setRow(row).build())
        all.add(page)
        made.add(page)
        from[page] = p
        current = page
        return page
    }

    /**
     * Takes back the current page if it was added and nothing has been done on it (no strokes, text, or any
     * undo step), and goes back to the page it was added from. Anything else is refused: a page with content is
     * never removed here.
     */
    fun removeMade(): Boolean {
        val p = current
        if (p !in made || p.canUndo || p.items.isNotEmpty() || p.texts.isNotEmpty()) return false
        all.remove(p)
        made.remove(p)
        current = from.remove(p) ?: all.first()
        return true
    }

    /** Row by row from the top, left to right in a row: the order the pages are read in. */
    fun readingOrder(): List<PageModel> = all.sortedWith(compareBy({ it.page.row }, { it.page.col }))
}
