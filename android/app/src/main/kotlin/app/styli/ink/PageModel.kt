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
    /** The page's size, template or fixed-or-infinite changed; the whole new state. */
    data class PageChanged(val page: PageSet) : Ev
    /** The strokes [ids] were moved or resized by [map]. */
    data class Transformed(val ids: List<String>, val map: Affine) : Ev
}

/**
 * The strokes on one page, with undo and redo. No Android in here, so it is tested on the JVM.
 *
 * Text boxes live beside the strokes and move through the same steps.
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

    /** Events not yet written anywhere. */
    val log = mutableListOf<Ev>()

    private class Tf(val ids: List<String>, val map: Affine)
    private class Step(
        val before: List<Item>, val after: List<Item>,
        val textsBefore: List<TextBox>, val textsAfter: List<TextBox>,
        val pageBefore: PageSet, val pageAfter: PageSet,
        val tf: Tf? = null,
    )

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var openBefore: List<Item>? = null // set while an eraser drag is going on

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun add(tool: Tool, color: Int, width: Int, pts: List<Pt>): Item {
        endErase()
        val item = Item(newId(), tool, color, width, pts)
        commit(items, items + item)
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
        commit(items, items, null, texts, p)
        return true
    }

    /** A new text box. One undo step. */
    fun addText(x: Float, y: Float, width: Float, fontSize: Float, text: String, color: Int = 0, highlight: Int = 0): TextBox {
        endErase()
        val box = TextBox(newId(), x, y, width, fontSize, text, color, highlight)
        commit(items, items, null, texts + box)
        return box
    }

    /** Replaces the box with the same ID as [box] (new text, colour, highlight, place or size). One undo step. */
    fun setText(box: TextBox): Boolean {
        endErase()
        if (texts.none { it.id == box.id }) return false
        commit(items, items, null, texts.map { if (it.id == box.id) box else it })
        return true
    }

    /** Replaces every box with the same ID as one in [boxes], all in one undo step (for recolouring a selection). */
    fun setTexts(boxes: List<TextBox>): Boolean {
        endErase()
        val by = boxes.associateBy { it.id }
        if (texts.none { it.id in by }) return false
        commit(items, items, null, texts.map { by[it.id] ?: it })
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
     * Moves, resizes or turns the strokes and text boxes [ids] by [map]. They keep their IDs. One undo step. A
     * text box's corner moves with the map, its width and font size grow by the map's scale and its angle by the
     * map's turn, so [map] must be a move, a uniform scale and a turn (as the lasso makes), with no shear.
     */
    fun transform(ids: Set<String>, map: Affine): Boolean {
        endErase()
        if (map.isIdentity || (items.none { it.id in ids } && texts.none { it.id in ids })) return false
        val after = items.map { if (it.id in ids) Item(it.id, it.tool, it.color, it.width, it.pts.map(map::apply)) else it }
        val scale = sqrt(abs(map.a * map.d - map.b * map.c)).toFloat()
        val turn = atan2(map.b, map.a).toFloat()
        val textsAfter = texts.map {
            if (it.id in ids) it.copy(x = map.x(it.x, it.y), y = map.y(it.x, it.y), width = it.width * scale, fontSize = it.fontSize * scale, angle = it.angle + turn) else it
        }
        commit(items, after, Tf(items.filter { it.id in ids }.map { it.id }, map), textsAfter)
        return true
    }

    /** Deletes the strokes and text boxes [ids] (whole). One undo step. */
    fun delete(ids: Set<String>): Boolean {
        endErase()
        if (items.none { it.id in ids } && texts.none { it.id in ids }) return false
        commit(items, items.filter { it.id !in ids }, null, texts.filter { it.id !in ids })
        return true
    }

    /** Closes the eraser drag: it becomes one undo step. */
    fun endErase() {
        val before = openBefore ?: return
        openBefore = null
        val after = items
        items = before // commit() sets it and writes the log from the difference
        commit(before, after)
    }

    fun undo(): Boolean {
        endErase()
        val s = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(s)
        move(items, s.before, texts, s.textsBefore, page, s.pageBefore, s.tf?.let { Tf(it.ids, it.map.inverse()) })
        return true
    }

    fun redo(): Boolean {
        endErase()
        val s = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(s)
        move(items, s.after, texts, s.textsAfter, page, s.pageAfter, s.tf)
        return true
    }

    private fun commit(before: List<Item>, after: List<Item>, tf: Tf? = null, textsAfter: List<TextBox> = texts, pageAfter: PageSet = page) {
        if (before === after && textsAfter === texts && pageAfter == page) return
        undoStack.addLast(Step(before, after, texts, textsAfter, page, pageAfter, tf))
        redoStack.clear()
        move(before, after, texts, textsAfter, page, pageAfter, tf)
    }

    private fun move(
        from: List<Item>, to: List<Item>, textsFrom: List<TextBox>, textsTo: List<TextBox>,
        pageFrom: PageSet, pageTo: PageSet, tf: Tf? = null,
    ) {
        val now = to.mapTo(HashSet()) { it.id }
        val was = from.mapTo(HashSet()) { it.id }
        from.filter { it.id !in now }.forEach { log.add(Ev.Erased(it.id)) }
        to.filter { it.id !in was }.forEach { log.add(Ev.Added(it)) }
        if (tf != null && tf.ids.isNotEmpty()) log.add(Ev.Transformed(tf.ids, tf.map))
        val textNow = textsTo.mapTo(HashSet()) { it.id }
        val textWas = textsFrom.associateBy { it.id }
        textsFrom.filter { it.id !in textNow }.forEach { log.add(Ev.TextRemoved(it.id)) }
        textsTo.filter { textWas[it.id] !== it }.forEach { log.add(Ev.TextSet(it)) }
        if (pageTo != pageFrom) log.add(Ev.PageChanged(pageTo))
        items = to
        texts = textsTo
        page = pageTo
    }

    companion object {
        /** A new page: A4 portrait, lined, fixed. */
        val A4_LINED: PageSet = PageSet.newBuilder().setWidth(21000).setHeight(29700).setTemplate(Template.TEMPLATE_LINED).build()

        private val random = SecureRandom()
        private fun newId(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
