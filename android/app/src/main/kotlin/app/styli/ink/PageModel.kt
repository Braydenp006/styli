package app.styli.ink

import app.styli.ink.proto.PageSet
import app.styli.ink.proto.Template
import app.styli.ink.proto.Tool
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A finished stroke. [id] is 32 hex characters (16 random bytes, as the log wants). Never changes once made. */
class Item(val id: String, val tool: Tool, val color: Int, val width: Int, val pts: List<Pt>)

/**
 * A typed text box. Never changes once made. Units are page units; the height is not stored, it comes from
 * laying the text out. [color] and [highlight] are 0xAARRGGBB as on a light page; a zero alpha means the page's
 * ordinary ink colour and no highlight (the format's rule).
 */
class TextBox(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val fontSize: Float,
    val text: String,
    val color: Int = 0,
    val highlight: Int = 0,
    val angle: Float = 0f,
) {
    fun copy(
        x: Float = this.x, y: Float = this.y, width: Float = this.width, fontSize: Float = this.fontSize,
        text: String = this.text, color: Int = this.color, highlight: Int = this.highlight, angle: Float = this.angle,
    ) = TextBox(id, x, y, width, fontSize, text, color, highlight, angle)

    /** The page point of the point (lx, ly) in the box's own frame: origin at (x, y), turned by [angle] about it. */
    fun toPage(lx: Float, ly: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        return (x + lx * c - ly * s) to (y + lx * s + ly * c)
    }

    /** The other way: where a page point is in the box's own frame. */
    fun toLocal(px: Float, py: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        val dx = px - x; val dy = py - y
        return (dx * c + dy * s) to (-dx * s + dy * c)
    }

    /** Is the page point inside the box, given the text's laid-out [height]? */
    fun contains(px: Float, py: Float, height: Float): Boolean {
        val (lx, ly) = toLocal(px, py)
        return lx in 0f..width && ly in 0f..height
    }

    /** The four corners on the page, clockwise from the top left. */
    fun corners(height: Float) = listOf(toPage(0f, 0f), toPage(width, 0f), toPage(width, height), toPage(0f, height))
}

/** What happens to the log: the same kinds the ink format has. A text box is always written whole. */
sealed interface Ev {
    data class Added(val item: Item) : Ev
    data class Erased(val id: String) : Ev
    data class TextSet(val box: TextBox) : Ev
    data class TextRemoved(val id: String) : Ev
    /** A table was added or changed (moved, resized, a line moved): the whole new state. */
    data class TableSet(val table: Table) : Ev
    data class TableRemoved(val id: String) : Ev
    /** The page's size, template or fixed-or-infinite changed; the whole new state. */
    data class PageChanged(val page: PageSet) : Ev
    /** The strokes [ids] were moved or resized by [map]. */
    data class Transformed(val ids: List<String>, val map: Affine) : Ev
}

/**
 * The strokes on one page, with undo and redo. No Android in here, so it is tested on the JVM.
 *
 * Text boxes and tables live beside the strokes and move through the same steps.
 *
 * Every change (a stroke drawn, or one eraser drag) is one step: the list of strokes before and after.
 * [log] gets the events that step is in the ink log, so a future log writer only has to drain it. An undo is
 * written as the opposite events, never as a deletion, because the log only grows. Erased strokes come back
 * under their old ID.
 */
class PageModel(page: PageSet = A4_LINED) {
    /** The page's size and look. Changing it is one undo step; the strokes and text boxes are not touched. */
    var page: PageSet = page
        private set

    var items: List<Item> = emptyList()
        private set

    var texts: List<TextBox> = emptyList()
        private set

    var tables: List<Table> = emptyList()
        private set

    /** Events not yet written anywhere. */
    val log = mutableListOf<Ev>()

    private class Tf(val ids: List<String>, val map: Affine)

    /** Everything an undo step puts back: the lists are replaced, never changed, so they can be kept as they were. */
    private data class Snap(val items: List<Item>, val texts: List<TextBox>, val tables: List<Table>, val page: PageSet)

    private class Step(val before: Snap, val after: Snap, val tf: Tf? = null)

    private fun now() = Snap(items, texts, tables, page)

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var openBefore: List<Item>? = null // set while an eraser drag is going on

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun add(tool: Tool, color: Int, width: Int, pts: List<Pt>): Item {
        endErase()
        val item = Item(newId(), tool, color, width, pts)
        commit(now().copy(items = items + item))
        return item
    }

    /**
     * Sets the page's size, template or fixed-or-infinite. One undo step; false if nothing changes or the size is
     * not a real one (a side of zero). Ink and text boxes stay where they are, even if they now fall off the
     * sheet: nothing is lost, and the old size brings them back.
     */
    fun setPage(p: PageSet): Boolean {
        endErase()
        if (p.width == 0 || p.height == 0 || p == page) return false
        commit(now().copy(page = p))
        return true
    }

    /** A new text box. One undo step. */
    fun addText(x: Float, y: Float, width: Float, fontSize: Float, text: String, color: Int = 0, highlight: Int = 0): TextBox {
        endErase()
        val box = TextBox(newId(), x, y, width, fontSize, text, color, highlight)
        commit(now().copy(texts = texts + box))
        return box
    }

    /** Replaces the box with the same ID as [box] (new text, colour, highlight, place or size). One undo step. */
    fun setText(box: TextBox): Boolean {
        endErase()
        if (texts.none { it.id == box.id }) return false
        commit(now().copy(texts = texts.map { if (it.id == box.id) box else it }))
        return true
    }

    /** Replaces every box with the same ID as one in [boxes], all in one undo step (for recolouring a selection). */
    fun setTexts(boxes: List<TextBox>): Boolean {
        endErase()
        val by = boxes.associateBy { it.id }
        if (texts.none { it.id in by }) return false
        commit(now().copy(texts = texts.map { by[it.id] ?: it }))
        return true
    }

    /**
     * A new table of [rows] by [cols] equal cells filling the rectangle from (x, y), [width] wide and [height]
     * tall. One undo step. Null (and nothing added) if rows or columns are not 1 to [Table.MAX_LINES] or a cell
     * would be under [Table.MIN_CELL].
     */
    fun addTable(x: Float, y: Float, width: Float, height: Float, rows: Int, cols: Int, lineWidth: Float = 40f, color: Int = 0): Table? {
        endErase()
        if (!Table.fits(width, height, rows, cols)) return null
        val table = Table(newId(), x, y, List(cols) { width / cols }, List(rows) { height / rows }, lineWidth, color)
        commit(now().copy(tables = tables + table))
        return table
    }

    /** Replaces the table with the same ID as [table] (a line moved, a new colour or line width). One undo step. */
    fun setTable(table: Table): Boolean {
        endErase()
        if (tables.none { it.id == table.id }) return false
        commit(now().copy(tables = tables.map { if (it.id == table.id) table else it }))
        return true
    }

    /** Replaces every table with the same ID as one in [list], all in one undo step (for colouring a selection). */
    fun setTables(list: List<Table>): Boolean {
        endErase()
        val by = list.associateBy { it.id }
        if (tables.none { it.id in by }) return false
        commit(now().copy(tables = tables.map { by[it.id] ?: it }))
        return true
    }

    /**
     * Adds a row (or a column, if [vertical]) to table [tableId] as number [at] (0 to the count; the count puts it
     * last). It is as big as the row or column it goes next to, so the table grows. The strokes and text boxes
     * inside the table that are at or beyond the new line move along by its size so they stay in their cells (a
     * stroke is judged by the middle of its box, a text box by the middle of its top edge). One undo step. False if
     * there is no such table or place, or the table already has [Table.MAX_LINES] of them.
     */
    fun insertLine(tableId: String, vertical: Boolean, at: Int): Boolean {
        endErase()
        val t = tables.firstOrNull { it.id == tableId } ?: return false
        val sizes = if (vertical) t.colWidths else t.rowHeights
        if (sizes.size >= Table.MAX_LINES || at !in 0..sizes.size) return false
        val size = sizes[minOf(at, sizes.size - 1)]
        val edge = if (vertical) t.lineX(at) else t.lineY(at)
        val shift = contentIn(t).filter { (if (vertical) it.second.first else it.second.second) >= edge }.map { it.first }.toSet()
        return editTable(t, t.inserted(vertical, at, size), shift, if (vertical) size else 0f, if (vertical) 0f else size, emptySet())
    }

    /**
     * Deletes row (or column, if [vertical]) number [index] of table [tableId], and with it the strokes and text
     * boxes inside it; what is inside the table beyond it moves back by its size. One undo step, so all of it
     * comes back with an undo. False if there is no such row or column, or it is the table's last.
     */
    fun deleteLine(tableId: String, vertical: Boolean, index: Int): Boolean {
        endErase()
        val t = tables.firstOrNull { it.id == tableId } ?: return false
        val sizes = if (vertical) t.colWidths else t.rowHeights
        if (sizes.size <= 1 || index !in sizes.indices) return false
        val from = if (vertical) t.lineX(index) else t.lineY(index)
        val to = if (vertical) t.lineX(index + 1) else t.lineY(index + 1)
        val here = contentIn(t).map { it.first to (if (vertical) it.second.first else it.second.second) }
        val gone = here.filter { it.second >= from && it.second < to }.map { it.first }.toSet()
        val shift = here.filter { it.second >= to }.map { it.first }.toSet()
        val size = sizes[index]
        return editTable(t, t.deleted(vertical, index), shift, if (vertical) -size else 0f, if (vertical) 0f else -size, gone)
    }

    /** The strokes and text boxes inside [t] with the point each is judged by: (ID, (x, y)). */
    private fun contentIn(t: Table): List<Pair<String, Pair<Float, Float>>> =
        items.map { it.id to ((it.pts.minOf { p -> p.x } + it.pts.maxOf { p -> p.x }) / 2 to (it.pts.minOf { p -> p.y } + it.pts.maxOf { p -> p.y }) / 2) }
            .plus(texts.map { it.id to (it.x + it.width / 2 to it.y) })
            .filter { t.contains(it.second.first, it.second.second) }

    /** One step that swaps the table, moves [shift] by (dx, dy) and deletes [gone]. */
    private fun editTable(old: Table, new: Table, shift: Set<String>, dx: Float, dy: Float, gone: Set<String>): Boolean {
        val map = Affine.translate(dx.toDouble(), dy.toDouble())
        val itemsAfter = items.filter { it.id !in gone }.map { if (it.id in shift) Item(it.id, it.tool, it.color, it.width, it.pts.map(map::apply)) else it }
        val textsAfter = texts.filter { it.id !in gone }.map { if (it.id in shift) it.copy(x = it.x + dx, y = it.y + dy) else it }
        val tablesAfter = tables.map { if (it.id == old.id) new else it }
        commit(now().copy(items = itemsAfter, texts = textsAfter, tables = tablesAfter), Tf(items.filter { it.id in shift }.map { it.id }, map))
        return true
    }

    /**
     * Moves the eraser to (x, y): with [partial] the touched part of every stroke is cut away, otherwise
     * every touched stroke goes whole. Call it for each point of a drag, then [endErase]. The pen's own
     * width counts: the disc is [r] plus half the stroke's width. Returns true if anything changed.
     */
    fun erase(x: Float, y: Float, r: Float, partial: Boolean): Boolean {
        val before = items
        val out = ArrayList<Item>(before.size)
        for (it in before) {
            val reach = r + it.width / 2f
            if (!partial) {
                if (!EraseGeometry.hits(it.pts, x, y, reach)) out.add(it)
                continue
            }
            val pieces = EraseGeometry.cut(it.pts, x, y, reach)
            if (pieces == null) out.add(it) else pieces.forEach { p -> out.add(Item(newId(), it.tool, it.color, it.width, p)) }
        }
        if (out.size == before.size && out.indices.all { out[it] === before[it] }) return false
        if (openBefore == null) openBefore = before
        items = out
        return true
    }

    /**
     * Moves, resizes or turns the strokes, text boxes and tables [ids] by [map]. They keep their IDs. One undo
     * step. A text box's corner moves with the map, its width and font size grow by the map's scale and its angle
     * by the map's turn, so [map] must be a move, a uniform scale and a turn (as the lasso makes), with no shear.
     * A table's corner moves and its cells grow by the scale, but it has no angle: a map with a turn in it is not
     * for tables (the caller leaves tables out of a turn). Pen and line widths are not scaled.
     */
    fun transform(ids: Set<String>, map: Affine): Boolean {
        endErase()
        if (map.isIdentity || (items.none { it.id in ids } && texts.none { it.id in ids } && tables.none { it.id in ids })) return false
        val after = items.map { if (it.id in ids) Item(it.id, it.tool, it.color, it.width, it.pts.map(map::apply)) else it }
        val scale = sqrt(abs(map.a * map.d - map.b * map.c)).toFloat()
        val turn = atan2(map.b, map.a).toFloat()
        val textsAfter = texts.map {
            if (it.id in ids) it.copy(x = map.x(it.x, it.y), y = map.y(it.x, it.y), width = it.width * scale, fontSize = it.fontSize * scale, angle = it.angle + turn) else it
        }
        val tablesAfter = tables.map {
            if (it.id in ids) it.copy(x = map.x(it.x, it.y), y = map.y(it.x, it.y), colWidths = it.colWidths.map { w -> w * scale }, rowHeights = it.rowHeights.map { h -> h * scale }) else it
        }
        commit(now().copy(items = after, texts = textsAfter, tables = tablesAfter), Tf(items.filter { it.id in ids }.map { it.id }, map))
        return true
    }

    /** Deletes the strokes, text boxes and tables [ids] (whole). One undo step. */
    fun delete(ids: Set<String>): Boolean {
        endErase()
        if (items.none { it.id in ids } && texts.none { it.id in ids } && tables.none { it.id in ids }) return false
        commit(now().copy(items = items.filter { it.id !in ids }, texts = texts.filter { it.id !in ids }, tables = tables.filter { it.id !in ids }))
        return true
    }

    /** Closes the eraser drag: it becomes one undo step. */
    fun endErase() {
        val before = openBefore ?: return
        openBefore = null
        val after = now()
        items = before // commit() sets it and writes the log from the difference
        commit(after)
    }

    fun undo(): Boolean {
        endErase()
        val s = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(s)
        move(now(), s.before, s.tf?.let { Tf(it.ids, it.map.inverse()) })
        return true
    }

    fun redo(): Boolean {
        endErase()
        val s = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(s)
        move(now(), s.after, s.tf)
        return true
    }

    /** Makes [after] the state, as one new undo step (nothing happens if it is the same state). */
    private fun commit(after: Snap, tf: Tf? = null) {
        val before = now()
        if (before.items === after.items && before.texts === after.texts && before.tables === after.tables && before.page == after.page) return
        undoStack.addLast(Step(before, after, tf))
        redoStack.clear()
        move(before, after, tf)
    }

    /** Goes from [from] to [to] and writes what that means to the log. */
    private fun move(from: Snap, to: Snap, tf: Tf? = null) {
        val now = to.items.mapTo(HashSet()) { it.id }
        val was = from.items.mapTo(HashSet()) { it.id }
        from.items.filter { it.id !in now }.forEach { log.add(Ev.Erased(it.id)) }
        to.items.filter { it.id !in was }.forEach { log.add(Ev.Added(it)) }
        if (tf != null && tf.ids.isNotEmpty()) log.add(Ev.Transformed(tf.ids, tf.map))
        val textNow = to.texts.mapTo(HashSet()) { it.id }
        val textWas = from.texts.associateBy { it.id }
        from.texts.filter { it.id !in textNow }.forEach { log.add(Ev.TextRemoved(it.id)) }
        to.texts.filter { textWas[it.id] !== it }.forEach { log.add(Ev.TextSet(it)) }
        val tableNow = to.tables.mapTo(HashSet()) { it.id }
        val tableWas = from.tables.associateBy { it.id }
        from.tables.filter { it.id !in tableNow }.forEach { log.add(Ev.TableRemoved(it.id)) }
        to.tables.filter { tableWas[it.id] !== it }.forEach { log.add(Ev.TableSet(it)) }
        if (to.page != from.page) log.add(Ev.PageChanged(to.page))
        items = to.items
        texts = to.texts
        tables = to.tables
        page = to.page
    }

    companion object {
        /** A new page: A4 portrait, lined, fixed. */
        val A4_LINED: PageSet = PageSet.newBuilder().setWidth(21000).setHeight(29700).setTemplate(Template.TEMPLATE_LINED).build()

        private val random = SecureRandom()
        private fun newId(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
