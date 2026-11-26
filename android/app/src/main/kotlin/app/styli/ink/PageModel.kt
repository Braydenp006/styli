package app.styli.ink

import app.styli.ink.proto.Tool
import java.security.SecureRandom

/** A finished stroke. [id] is 32 hex characters (16 random bytes, as the log wants). Never changes once made. */
class Item(val id: String, val tool: Tool, val color: Int, val width: Int, val pts: List<Pt>)

/** What happens to the log: the same two kinds the ink format has for strokes. */
sealed interface Ev {
    data class Added(val item: Item) : Ev
    data class Erased(val id: String) : Ev
    /** The strokes [ids] were moved or resized by [map]. */
    data class Transformed(val ids: List<String>, val map: Affine) : Ev
}

/**
 * The strokes on one page, with undo and redo. No Android in here, so it is tested on the JVM.
 *
 * Every change (a stroke drawn, or one eraser drag) is one step: the list of strokes before and after.
 * [log] gets the events that step is in the ink log, so a future log writer only has to drain it. An undo is
 * written as the opposite events, never as a deletion, because the log only grows. Erased strokes come back
 * under their old ID.
 */
class PageModel {
    var items: List<Item> = emptyList()
        private set

    /** Events not yet written anywhere. */
    val log = mutableListOf<Ev>()

    private class Tf(val ids: List<String>, val map: Affine)
    private class Step(val before: List<Item>, val after: List<Item>, val tf: Tf? = null)

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

    /** Moves or resizes the strokes [ids] by [map]. They keep their IDs. One undo step. */
    fun transform(ids: Set<String>, map: Affine): Boolean {
        endErase()
        if (map.isIdentity || items.none { it.id in ids }) return false
        val after = items.map { if (it.id in ids) Item(it.id, it.tool, it.color, it.width, it.pts.map(map::apply)) else it }
        commit(items, after, Tf(items.filter { it.id in ids }.map { it.id }, map))
        return true
    }

    /** Deletes the strokes [ids] (whole). One undo step. */
    fun delete(ids: Set<String>): Boolean {
        endErase()
        if (items.none { it.id in ids }) return false
        commit(items, items.filter { it.id !in ids })
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
        move(items, s.before, s.tf?.let { Tf(it.ids, it.map.inverse()) })
        return true
    }

    fun redo(): Boolean {
        endErase()
        val s = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(s)
        move(items, s.after, s.tf)
        return true
    }

    private fun commit(before: List<Item>, after: List<Item>, tf: Tf? = null) {
        if (before === after) return
        undoStack.addLast(Step(before, after, tf))
        redoStack.clear()
        move(before, after, tf)
    }

    private fun move(from: List<Item>, to: List<Item>, tf: Tf? = null) {
        val now = to.mapTo(HashSet()) { it.id }
        val was = from.mapTo(HashSet()) { it.id }
        from.filter { it.id !in now }.forEach { log.add(Ev.Erased(it.id)) }
        to.filter { it.id !in was }.forEach { log.add(Ev.Added(it)) }
        if (tf != null) log.add(Ev.Transformed(tf.ids, tf.map))
        items = to
    }

    private companion object {
        val random = SecureRandom()
        fun newId(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
