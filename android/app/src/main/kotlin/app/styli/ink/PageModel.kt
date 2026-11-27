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

/** How [PageModel.arrange] moves things in the layers. */
enum class Arrange { FRONT, BACK, FORWARD, BACKWARD }

/** What happens to the log: the same kinds the ink format has. A text box is always written whole. */
sealed interface Ev {
    data class Added(val item: Item) : Ev
    data class Erased(val id: String) : Ev
    data class TextSet(val box: TextBox) : Ev
    data class TextRemoved(val id: String) : Ev
    /** A table was added or changed (moved, resized, a line moved): the whole new state. */
    data class TableSet(val table: Table) : Ev
    data class TableRemoved(val id: String) : Ev
    /** A shape, connector or fill was added or changed: the whole new state. */
    data class ShapeSet(val shape: Shape) : Ev
    data class ShapeRemoved(val id: String) : Ev
    data class ConnectorSet(val connector: Connector) : Ev
    data class ConnectorRemoved(val id: String) : Ev
    data class FillSet(val fill: Fill) : Ev
    data class FillRemoved(val id: String) : Ev
    /** A picture was added or changed (moved, resized, turned, cropped): the whole new state. */
    data class PictureSet(val picture: Picture) : Ev
    data class PictureRemoved(val id: String) : Ev
    /** A chart was added or changed (moved, resized, new data or look): the whole new state. */
    data class ChartSet(val chart: Chart) : Ev
    data class ChartRemoved(val id: String) : Ev
    /** The page's size, template or fixed-or-infinite changed; the whole new state. */
    data class PageChanged(val page: PageSet) : Ev
    /** Object [id] now sits just above [above] in the layers, or at the very bottom if [above] is null. */
    data class ZMoved(val id: String, val above: String?) : Ev
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

    var shapes: List<Shape> = emptyList()
        private set

    var connectors: List<Connector> = emptyList()
        private set

    var fills: List<Fill> = emptyList()
        private set

    var pictures: List<Picture> = emptyList()
        private set

    var charts: List<Chart> = emptyList()
        private set

    /**
     * Every object's ID (strokes, text boxes, tables, shapes, connectors, fills), bottom layer first: the drawing
     * order. A new stroke, text box, shape or connector goes on top and a new table or fill at the bottom;
     * [arrange] changes it.
     */
    var order: List<String> = emptyList()
        private set

    private val itemIndex = Index<Item> { it.id }
    private val textIndex = Index<TextBox> { it.id }
    private val tableIndex = Index<Table> { it.id }
    fun itemOf(id: String): Item? = itemIndex.of(items)[id]
    fun textOf(id: String): TextBox? = textIndex.of(texts)[id]
    fun tableOf(id: String): Table? = tableIndex.of(tables)[id]
    private val shapeIndex = Index<Shape> { it.id }
    private val connectorIndex = Index<Connector> { it.id }
    private val fillIndex = Index<Fill> { it.id }
    private val pictureIndex = Index<Picture> { it.id }
    fun pictureOf(id: String): Picture? = pictureIndex.of(pictures)[id]
    private val chartIndex = Index<Chart> { it.id }
    fun chartOf(id: String): Chart? = chartIndex.of(charts)[id]
    fun shapeOf(id: String): Shape? = shapeIndex.of(shapes)[id]
    fun connectorOf(id: String): Connector? = connectorIndex.of(connectors)[id]
    fun fillOf(id: String): Fill? = fillIndex.of(fills)[id]

    /** ID to object, rebuilt only when the list it is for was replaced. */
    private class Index<T>(val id: (T) -> String) {
        private var src: List<T>? = null
        private var map = emptyMap<String, T>()
        fun of(list: List<T>): Map<String, T> {
            if (src !== list) { map = list.associateBy(id); src = list }
            return map
        }
    }

    /** Events not yet written anywhere. */
    val log = mutableListOf<Ev>()

    /** Goes up whenever the page changes (and stays when saving empties [log]), so a picture of it can tell when it is out of date. */
    var revision = 0
        private set

    /**
     * Sets the page to what a saved log replays to (see [InkLogReader]). Nothing is logged and there is no undo: the
     * page opens as it was left, with an empty undo history.
     */
    fun load(
        page: PageSet, items: List<Item>, texts: List<TextBox>, order: List<String>, tables: List<Table> = emptyList(),
        shapes: List<Shape> = emptyList(), connectors: List<Connector> = emptyList(), fills: List<Fill> = emptyList(),
        pictures: List<Picture> = emptyList(), charts: List<Chart> = emptyList(),
    ) {
        this.page = page; this.items = items; this.texts = texts; this.order = order; this.tables = tables
        this.shapes = shapes; this.connectors = connectors; this.fills = fills; this.pictures = pictures; this.charts = charts
        undoStack.clear(); redoStack.clear(); log.clear(); revision++
    }

    private class Tf(val ids: List<String>, val map: Affine)

    /** Everything an undo step puts back: the lists are replaced, never changed, so they can be kept as they were. */
    private data class Snap(
        val items: List<Item>, val texts: List<TextBox>, val tables: List<Table>,
        val shapes: List<Shape>, val connectors: List<Connector>, val fills: List<Fill>,
        val order: List<String>, val page: PageSet, val pictures: List<Picture>, val charts: List<Chart>,
    )

    private class Step(val before: Snap, val after: Snap, val tf: Tf? = null)

    private fun now() = Snap(items, texts, tables, shapes, connectors, fills, order, page, pictures, charts)

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var openSnap: Snap? = null // everything as it was when the eraser drag began; set while one is going on

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

    /** Puts the page in another grid cell. Not a drawing undo step: [Notebook] keeps the undo for page moves. */
    fun placeAt(col: Int, row: Int) {
        if (page.col == col && page.row == row) return
        page = page.toBuilder().setCol(col).setRow(row).build()
        log.add(Ev.PageChanged(page)); revision++
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
     * Moves the eraser to (x, y). Strokes: with [partial] the touched part of each is cut away, otherwise every touched
     * stroke goes whole (the pen's own width counts: the disc is [r] plus half the stroke's width). Everything else
     * goes whole in either case, when the disc touches it: a text box (its box; [textHeight] says how tall), a shape
     * (its outline, or inside it if it is filled), a connector (its line), a table (its lines, or a coloured cell) and
     * a fill (anywhere in the paint). [only] limits which kinds count. A connector left on a shape that went is kept,
     * its end free where it was. Call it for each point of a drag, then [endErase]. Returns true if anything changed.
     */
    fun erase(
        x: Float, y: Float, r: Float, partial: Boolean, only: Set<EraseOnly> = emptySet(),
        textHeight: (TextBox) -> Float = { it.fontSize * 1.3f },
    ): Boolean {
        val before = now()
        val out = ArrayList<Item>(items.size)
        val replaced = HashMap<String, List<String>>() // a stroke's ID to the IDs that take its layer (none if it went)
        for (it in items) {
            if (!only.matches(it)) { out.add(it); continue }
            val reach = r + it.width / 2f
            if (!partial) {
                if (!EraseGeometry.hits(it.pts, x, y, reach)) out.add(it) else replaced[it.id] = emptyList()
                continue
            }
            val pieces = EraseGeometry.cut(it.pts, x, y, reach)
            if (pieces == null) out.add(it) else {
                val made = pieces.map { p -> Item(newId(), it.tool, it.color, it.width, p) }
                out.addAll(made)
                replaced[it.id] = made.map { m -> m.id }
            }
        }
        val strokesChanged = !(out.size == items.size && out.indices.all { out[it] === items[it] })

        val goneTexts = if (only.texts) texts.filter { t -> t.distTo(x, y, textHeight(t)) <= r }.mapTo(HashSet()) { it.id } else emptySet()
        val goneShapes = if (only.shapes) shapes.filter { s -> touchesShape(s, x, y, r) }.mapTo(HashSet()) { it.id } else emptySet()
        val goneConnectors = if (only.connectors) connectors.filter { c ->
            LassoGeometry.distToPath(x, y, c.path(::shapeOf)) <= r + c.lineWidth / 2
        }.mapTo(HashSet()) { it.id } else emptySet()
        val goneTables = if (only.tables) tables.filter { t -> touchesTable(t, x, y, r) }.mapTo(HashSet()) { it.id } else emptySet()
        val goneFills = if (only.fills) fills.filter { f ->
            listOf(0f to 0f, r to 0f, -r to 0f, 0f to r, 0f to -r).any { (dx, dy) -> f.contains(x + dx, y + dy) }
        }.mapTo(HashSet()) { it.id } else emptySet()
        val gone = goneTexts + goneShapes + goneConnectors + goneTables + goneFills
        if (!strokesChanged && gone.isEmpty()) return false

        if (openSnap == null) openSnap = before
        if (strokesChanged) items = out
        if (goneTexts.isNotEmpty()) texts = texts.filter { it.id !in goneTexts }
        if (goneTables.isNotEmpty()) tables = tables.filter { it.id !in goneTables }
        if (goneFills.isNotEmpty()) fills = fills.filter { it.id !in goneFills }
        if (goneShapes.isNotEmpty() || goneConnectors.isNotEmpty()) {
            val kept = withEndsFreed(connectors.filter { it.id !in goneConnectors }, goneShapes) // while the shapes can still be found
            connectors = kept
            shapes = shapes.filter { it.id !in goneShapes }
        }
        order = order.filter { it !in gone }.flatMap { replaced[it] ?: listOf(it) } // a piece keeps the layer of the stroke it came from
        return true
    }

    /** The same, limited to one kind. */
    fun erase(x: Float, y: Float, r: Float, partial: Boolean, only: EraseOnly, textHeight: (TextBox) -> Float = { it.fontSize * 1.3f }): Boolean =
        erase(x, y, r, partial, setOf(only), textHeight)

    /** Distance from the page point to the box of a text box [height] tall (0 inside it). */
    private fun TextBox.distTo(px: Float, py: Float, height: Float): Float {
        val (lx, ly) = toLocal(px, py)
        return kotlin.math.hypot(maxOf(0f, -lx, lx - width), maxOf(0f, -ly, ly - height))
    }

    /** Does the eraser disc touch the shape's outline, or (if it has a fill) the inside? */
    private fun touchesShape(s: Shape, x: Float, y: Float, r: Float): Boolean {
        if (s.fill ushr 24 != 0 && s.contains(x, y)) return true
        val (fx, fy) = s.nearestOutline(x, y)
        val (ox, oy) = s.spot(fx, fy)
        return kotlin.math.hypot(ox - x, oy - y) <= r + s.lineWidth / 2
    }

    /** Does the disc touch a line of the table, or a part of it that has colour? */
    private fun touchesTable(t: Table, x: Float, y: Float, r: Float): Boolean {
        if (t.nearLine(x, y, r + t.lineWidth / 2)) return true
        if (!t.contains(x, y)) return false
        if (t.fill ushr 24 != 0) return true
        return t.cellAt(x, y)?.let { (row, col) -> t.cellFill(row, col) ushr 24 != 0 } == true
    }

    /** [list] with the ends that are on a shape in [goneShapes] made free, where they were. */
    private fun withEndsFreed(list: List<Connector>, goneShapes: Set<String>): List<Connector> =
        if (goneShapes.isEmpty()) list else list.map { c ->
            if (c.from.shape !in goneShapes && c.to.shape !in goneShapes) c else {
                val (a, b) = c.resolve(::shapeOf)
                c.copy(from = if (c.from.shape in goneShapes) End(null, 0, a.first, a.second) else c.from, to = if (c.to.shape in goneShapes) End(null, 0, b.first, b.second) else c.to)
            }
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
        if (map.isIdentity || (items.none { it.id in ids } && texts.none { it.id in ids } && tables.none { it.id in ids } &&
                shapes.none { it.id in ids } && connectors.none { it.id in ids } && fills.none { it.id in ids } && pictures.none { it.id in ids } && charts.none { it.id in ids })) return false
        val after = items.map { if (it.id in ids) Item(it.id, it.tool, it.color, it.width, it.pts.map(map::apply)) else it }
        val scale = sqrt(abs(map.a * map.d - map.b * map.c)).toFloat()
        val turn = atan2(map.b, map.a).toFloat()
        val textsAfter = texts.map {
            if (it.id in ids) it.copy(x = map.x(it.x, it.y), y = map.y(it.x, it.y), width = it.width * scale, fontSize = it.fontSize * scale, angle = it.angle + turn) else it
        }
        val tablesAfter = tables.map {
            if (it.id in ids) it.copy(x = map.x(it.x, it.y), y = map.y(it.x, it.y), colWidths = it.colWidths.map { w -> w * scale }, rowHeights = it.rowHeights.map { h -> h * scale }) else it
        }
        val shapesAfter = shapes.map { if (it.id in ids) it.moved(map) else it }
        // A connector's free ends move with it; an end on a shape follows the shape.
        fun end(e: End) = if (e.shape == null) End(null, 0, map.x(e.x, e.y), map.y(e.x, e.y)) else e
        val connectorsAfter = connectors.map { if (it.id in ids) it.copy(from = end(it.from), to = end(it.to), bend = it.bend * scale, via = it.via.map { (x, y) -> map.x(x, y) to map.y(x, y) }) else it }
        val fillsAfter = fills.map { if (it.id in ids) it.map(map) else it }
        val picturesAfter = pictures.map { if (it.id in ids) it.moved(map) else it }
        val chartsAfter = charts.map { if (it.id in ids) it.moved(map) else it }
        commit(
            now().copy(items = after, texts = textsAfter, tables = tablesAfter, shapes = shapesAfter, connectors = connectorsAfter, fills = fillsAfter, pictures = picturesAfter, charts = chartsAfter),
            Tf(items.filter { it.id in ids }.map { it.id }, map),
        )
        return true
    }

    /**
     * Puts a copy of [clip] on the page with its top-left corner at (x, y). Everything gets a new ID; the layers keep
     * the clip's order, all above what is already there. One undo step. Returns the new IDs (none if the clip is empty).
     */
    fun paste(clip: Clip, x: Float, y: Float): Set<String> {
        endErase()
        if (clip.isEmpty) return emptySet()
        val ids = HashMap<String, String>()
        val c = clip.moved(x, y) { ids.getOrPut(it) { newId() } }
        commit(now().copy(
            items = items + c.items, texts = texts + c.texts, tables = tables + c.tables,
            shapes = shapes + c.shapes, connectors = connectors + c.connectors, fills = fills + c.fills, pictures = pictures + c.pictures, charts = charts + c.charts, order = order + c.order,
        ))
        return ids.values.toSet()
    }

    /**
     * Deletes the strokes, text boxes, tables, shapes, connectors and fills [ids] (whole). A connector left
     * attached to a deleted shape stays, its end now free where it was. One undo step.
     */
    fun delete(ids: Set<String>): Boolean {
        endErase()
        if (items.none { it.id in ids } && texts.none { it.id in ids } && tables.none { it.id in ids } &&
            shapes.none { it.id in ids } && connectors.none { it.id in ids } && fills.none { it.id in ids } && pictures.none { it.id in ids } && charts.none { it.id in ids }) return false
        val kept = withEndsFreed(connectors.filter { it.id !in ids }, shapes.filter { it.id in ids }.mapTo(HashSet()) { it.id })
        commit(now().copy(
            items = items.filter { it.id !in ids }, texts = texts.filter { it.id !in ids }, tables = tables.filter { it.id !in ids },
            shapes = shapes.filter { it.id !in ids }, connectors = kept, fills = fills.filter { it.id !in ids },
            pictures = pictures.filter { it.id !in ids }, charts = charts.filter { it.id !in ids },
        ))
        return true
    }

    /** A new shape, made by [make] from its new ID. One undo step. */
    fun addShape(make: (String) -> Shape): Shape {
        endErase()
        val shape = make(newId())
        commit(now().copy(shapes = shapes + shape))
        return shape
    }

    /** Replaces every shape with the same ID as one in [list], all in one undo step (an edit, a restyle). False if none matches. */
    fun setShapes(list: List<Shape>): Boolean {
        endErase()
        val by = list.associateBy { it.id }
        if (shapes.none { it.id in by }) return false
        commit(now().copy(shapes = shapes.map { by[it.id] ?: it }))
        return true
    }

    fun setShape(shape: Shape) = setShapes(listOf(shape))

    /** A new picture, made by [make] from its new ID. It goes on top of the layers. One undo step. */
    fun addPicture(make: (String) -> Picture): Picture {
        endErase()
        val pic = make(newId())
        commit(now().copy(pictures = pictures + pic))
        return pic
    }

    /** Replaces every picture with the same ID as one in [list], all in one undo step (a crop, a nudge). False if none matches. */
    fun setPictures(list: List<Picture>): Boolean {
        endErase()
        val by = list.associateBy { it.id }
        if (pictures.none { it.id in by }) return false
        commit(now().copy(pictures = pictures.map { by[it.id] ?: it }))
        return true
    }

    fun setPicture(pic: Picture) = setPictures(listOf(pic))

    /** A new chart, made by [make] from its new ID. It goes on top of the layers. One undo step. */
    fun addChart(make: (String) -> Chart): Chart {
        endErase()
        val chart = make(newId())
        commit(now().copy(charts = charts + chart))
        return chart
    }

    /** Replaces every chart with the same ID as one in [list], all in one undo step (new data, a restyle). False if none matches. */
    fun setCharts(list: List<Chart>): Boolean {
        endErase()
        val by = list.associateBy { it.id }
        if (charts.none { it.id in by }) return false
        commit(now().copy(charts = charts.map { by[it.id] ?: it }))
        return true
    }

    fun setChart(chart: Chart) = setCharts(listOf(chart))

    /** A new connector, made by [make] from its new ID. One undo step. */
    fun addConnector(make: (String) -> Connector): Connector {
        endErase()
        val c = make(newId())
        commit(now().copy(connectors = connectors + c))
        return c
    }

    /** Replaces every connector with the same ID as one in [list], all in one undo step. False if none matches. */
    fun setConnectors(list: List<Connector>): Boolean {
        endErase()
        val by = list.associateBy { it.id }
        if (connectors.none { it.id in by }) return false
        commit(now().copy(connectors = connectors.map { by[it.id] ?: it }))
        return true
    }

    fun setConnector(c: Connector) = setConnectors(listOf(c))

    /** A new fill of [color] in [rings] (see [Fill]); it goes at the bottom. Null (nothing added) if there are no rings. One undo step. */
    fun addFill(color: Int, rings: List<List<Pair<Float, Float>>>): Fill? {
        endErase()
        if (rings.isEmpty() || rings.any { it.size < 3 }) return null
        val fill = Fill(newId(), color, rings)
        commit(now().copy(fills = fills + fill))
        return fill
    }

    /** Replaces every fill with the same ID as one in [list] (a new colour), all in one undo step. False if none matches. */
    fun setFills(list: List<Fill>): Boolean {
        endErase()
        val by = list.associateBy { it.id }
        if (fills.none { it.id in by }) return false
        commit(now().copy(fills = fills.map { by[it.id] ?: it }))
        return true
    }

    /** Closes the eraser drag: it becomes one undo step. */
    fun endErase() {
        val before = openSnap ?: return
        openSnap = null
        val after = now()
        // commit() sets the new state and writes the log from the difference, so put the old one back first.
        items = before.items; texts = before.texts; tables = before.tables
        shapes = before.shapes; connectors = before.connectors; fills = before.fills; order = before.order; pictures = before.pictures; charts = before.charts
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

    /**
     * Charts that follow a table (see [ChartSource]) take their numbers from its typed cells again whenever the table changed in this
     * step, so the table and its charts move together in one undo step. A chart whose table is gone keeps its numbers and lets go.
     */
    private fun relinked(before: Snap, after: Snap): Snap {
        if (after.charts.none { it.source != null }) return after
        var changed = false
        val charts = after.charts.map { c ->
            val src = c.source ?: return@map c
            val table = after.tables.firstOrNull { it.id == src.table }
            if (table == null) { changed = true; c.copy(source = null) }
            else if (before.tables.firstOrNull { it.id == src.table } === table) c
            else TableChart.fill(c, table, src).also { if (it != c) changed = true }
        }
        return if (changed) after.copy(charts = charts) else after
    }

    /** Makes [after] the state, as one new undo step (nothing happens if it is the same state). */
    private fun commit(after: Snap, tf: Tf? = null) {
        val before = now()
        val settled = relinked(before, if (after.order === before.order) after.copy(order = settle(before.order, after)) else after)
        if (before.items === settled.items && before.texts === settled.texts && before.tables === settled.tables &&
            before.shapes === settled.shapes && before.connectors === settled.connectors && before.fills === settled.fills && before.pictures === settled.pictures && before.charts === settled.charts && before.order === settled.order && before.page == settled.page) return
        undoStack.addLast(Step(before, settled, tf))
        redoStack.clear()
        move(before, settled, tf)
    }

    /** The layers after a change that did not name them: what is gone leaves, a new stroke, text box, shape or connector goes on top, a new table or fill at the bottom. */
    private fun settle(old: List<String>, to: Snap): List<String> {
        val have = HashSet<String>()
        to.items.mapTo(have) { it.id }; to.texts.mapTo(have) { it.id }; to.tables.mapTo(have) { it.id }
        to.shapes.mapTo(have) { it.id }; to.connectors.mapTo(have) { it.id }; to.fills.mapTo(have) { it.id }; to.pictures.mapTo(have) { it.id }; to.charts.mapTo(have) { it.id }
        val known = old.toHashSet()
        val kept = old.filter { it in have }
        val top = to.items.filter { it.id !in known }.map { it.id } + to.texts.filter { it.id !in known }.map { it.id } +
            to.shapes.filter { it.id !in known }.map { it.id } + to.connectors.filter { it.id !in known }.map { it.id } +
            to.pictures.filter { it.id !in known }.map { it.id } + to.charts.filter { it.id !in known }.map { it.id }
        val bottom = to.fills.filter { it.id !in known }.map { it.id } + to.tables.filter { it.id !in known }.map { it.id }
        return if (kept.size == old.size && top.isEmpty() && bottom.isEmpty()) old else bottom + kept + top
    }

    /**
     * Moves [ids] in the layers: to the front or back, or one step forward or backward past the next object that
     * overlaps it ([box] gives an object's box, null if unknown, which counts as overlapping). They keep their
     * order among themselves. One undo step; false if nothing moves.
     */
    fun arrange(ids: Set<String>, how: Arrange, box: (String) -> Box? = { null }): Boolean {
        endErase()
        val mine = order.filter { it in ids }
        if (mine.isEmpty()) return false
        val rest = order.filter { it !in ids }
        val next: List<String> = when (how) {
            Arrange.FRONT -> rest + mine
            Arrange.BACK -> mine + rest
            else -> {
                val out = order.toMutableList()
                val forward = how == Arrange.FORWARD
                fun touches(a: String, b: String): Boolean {
                    val p = box(a) ?: return true
                    val q = box(b) ?: return true
                    return p.l <= q.r && q.l <= p.r && p.t <= q.b && q.t <= p.b
                }
                // The one nearest the end it moves towards goes first, so they never pass each other.
                for (id in if (forward) mine.asReversed() else mine) {
                    val at = out.indexOf(id)
                    val range = if (forward) (at + 1 until out.size) else (at - 1 downTo 0)
                    val hit = range.firstOrNull { out[it] !in ids && touches(id, out[it]) } ?: continue
                    out.removeAt(at)
                    out.add(hit, id) // forward: just after the one it passed (the list shrank by one); backward: just before it
                }
                out
            }
        }
        if (next == order) return false
        commit(now().copy(order = next))
        return true
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
        val shapeNow = to.shapes.mapTo(HashSet()) { it.id }
        val shapeWas = from.shapes.associateBy { it.id }
        from.shapes.filter { it.id !in shapeNow }.forEach { log.add(Ev.ShapeRemoved(it.id)) }
        to.shapes.filter { shapeWas[it.id] !== it }.forEach { log.add(Ev.ShapeSet(it)) }
        val connNow = to.connectors.mapTo(HashSet()) { it.id }
        val connWas = from.connectors.associateBy { it.id }
        from.connectors.filter { it.id !in connNow }.forEach { log.add(Ev.ConnectorRemoved(it.id)) }
        to.connectors.filter { connWas[it.id] !== it }.forEach { log.add(Ev.ConnectorSet(it)) }
        val fillNow = to.fills.mapTo(HashSet()) { it.id }
        val fillWas = from.fills.associateBy { it.id }
        from.fills.filter { it.id !in fillNow }.forEach { log.add(Ev.FillRemoved(it.id)) }
        to.fills.filter { fillWas[it.id] !== it }.forEach { log.add(Ev.FillSet(it)) }
        // A drawing undo never moves the page: it keeps the cell the page is in now (page moves have their own undo).
        val toPage = to.page.toBuilder().setCol(from.page.col).setRow(from.page.row).build()
        val picNow = to.pictures.mapTo(HashSet()) { it.id }
        val picWas = from.pictures.associateBy { it.id }
        from.pictures.filter { it.id !in picNow }.forEach { log.add(Ev.PictureRemoved(it.id)) }
        to.pictures.filter { picWas[it.id] !== it }.forEach { log.add(Ev.PictureSet(it)) }
        val chartNow = to.charts.mapTo(HashSet()) { it.id }
        val chartWas = from.charts.associateBy { it.id }
        from.charts.filter { it.id !in chartNow }.forEach { log.add(Ev.ChartRemoved(it.id)) }
        to.charts.filter { chartWas[it.id] !== it }.forEach { log.add(Ev.ChartSet(it)) }
        if (toPage != from.page) log.add(Ev.PageChanged(toPage))
        logLayers(from, to)
        items = to.items
        texts = to.texts
        tables = to.tables
        shapes = to.shapes
        connectors = to.connectors
        fills = to.fills
        pictures = to.pictures
        charts = to.charts
        page = toPage
        order = to.order
        revision++
    }

    /** Writes [Ev.ZMoved] for every object whose place the format's plain rules would not give (see ink.proto). */
    private fun logLayers(from: Snap, to: Snap) {
        val have = to.order.toHashSet()
        val was = from.order.toHashSet()
        val plain = from.order.filterTo(ArrayList()) { it in have }
        to.items.filter { it.id !in was }.forEach { plain.add(it.id) }
        to.texts.filter { it.id !in was }.forEach { plain.add(it.id) }
        to.tables.filter { it.id !in was }.forEach { plain.add(0, it.id) }
        to.shapes.filter { it.id !in was }.forEach { plain.add(it.id) }
        to.connectors.filter { it.id !in was }.forEach { plain.add(it.id) }
        to.pictures.filter { it.id !in was }.forEach { plain.add(it.id) }
        to.charts.filter { it.id !in was }.forEach { plain.add(it.id) }
        to.fills.filter { it.id !in was }.forEach { plain.add(0, it.id) }
        if (plain.size != to.order.size) return
        for (i in to.order.indices) {
            if (plain[i] == to.order[i]) continue
            plain.remove(to.order[i])
            plain.add(i, to.order[i])
            log.add(Ev.ZMoved(to.order[i], if (i == 0) null else to.order[i - 1]))
        }
    }

    companion object {
        /** A new page: A4 portrait, lined, fixed. */
        val A4_LINED: PageSet = PageSet.newBuilder().setWidth(21000).setHeight(29700).setTemplate(Template.TEMPLATE_LINED).build()

        private val random = SecureRandom()
        private fun newId(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
