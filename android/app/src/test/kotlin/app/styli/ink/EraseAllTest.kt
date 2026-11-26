package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseAllTest {
    private val ink = 0xFF000000.toInt()

    /** One of everything, each with a line or an edge through the point (500, 500), far from the others. */
    private class Page(val m: PageModel) {
        lateinit var stroke: String; lateinit var text: String; lateinit var shape: String
        lateinit var connector: String; lateinit var table: String; lateinit var fill: String
    }

    private fun page(): Page {
        val m = PageModel()
        val p = Page(m)
        p.stroke = m.add(Tool.TOOL_PEN, ink, 40, listOf(Pt(0f, 500f, 0, 1f), Pt(1000f, 500f, 10, 1f))).id
        p.text = m.addText(400f, 400f, 600f, 300f, "hello").id
        p.shape = m.addShape { Shape(it, ShapeKind.RECT, 500f, 300f, 400f, 400f) }.id // its left edge passes (500, 500)
        p.connector = m.addConnector { Connector(it, End(null, 0, 0f, 500f), End(null, 0, 1000f, 500f)) }.id
        p.table = m.addTable(0f, 0f, 1000f, 1000f, 2, 2)!!.id // its middle lines pass through (500, 500)
        p.fill = m.addFill(0x99FFEB3B.toInt(), listOf(listOf(400f to 400f, 600f to 400f, 600f to 600f, 400f to 600f)))!!.id
        return p
    }

    private fun ids(m: PageModel) = m.order.toSet()

    @Test
    fun everythingGoesWhenTheEraserTouchesEverything() {
        val p = page()
        assertTrue(p.m.erase(500f, 500f, 20f, partial = false))
        p.m.endErase()
        assertTrue(p.m.order.isEmpty())
        assertTrue(p.m.items.isEmpty() && p.m.texts.isEmpty() && p.m.shapes.isEmpty() && p.m.connectors.isEmpty() && p.m.tables.isEmpty() && p.m.fills.isEmpty())
    }

    @Test
    fun eachFilterTakesOnlyItsOwnKind() {
        val kinds = mapOf(
            EraseOnly.INK to { p: Page -> p.stroke }, EraseOnly.TEXT_BOXES to { p: Page -> p.text }, EraseOnly.SHAPES to { p: Page -> p.shape },
            EraseOnly.CONNECTORS to { p: Page -> p.connector }, EraseOnly.TABLES to { p: Page -> p.table }, EraseOnly.FILLS to { p: Page -> p.fill },
        )
        for ((only, pick) in kinds) {
            val p = page()
            val target = pick(p)
            assertTrue("$only", p.m.erase(500f, 500f, 20f, partial = false, only = only))
            p.m.endErase()
            val left = ids(p.m)
            assertFalse("$only: its own kind is gone", target in left)
            assertEquals("$only: the rest stay", 5, left.size)
        }
    }

    @Test
    fun partOfAStrokeStillTakesTheWholeOfAnythingElse() {
        val p = page()
        p.m.erase(500f, 500f, 20f, partial = true, only = EraseOnly.ALL)
        p.m.endErase()
        assertNull(p.m.shapeOf(p.shape)); assertNull(p.m.tableOf(p.table)); assertNull(p.m.textOf(p.text))
        assertTrue(p.m.items.size == 2) // the stroke was cut in two, not removed
    }

    @Test
    fun anUnfilledShapeIsOnlyTouchedOnItsOutlineButAFilledOneInside() {
        val m = PageModel()
        val hollow = m.addShape { Shape(it, ShapeKind.RECT, 0f, 0f, 2000f, 2000f) }.id
        val solid = m.addShape { Shape(it, ShapeKind.RECT, 3000f, 0f, 2000f, 2000f, fill = 0xFF00FF00.toInt()) }.id
        assertFalse(m.erase(1000f, 1000f, 20f, partial = false, only = EraseOnly.SHAPES)) // inside the hollow one: nothing
        assertTrue(m.erase(4000f, 1000f, 20f, partial = false, only = EraseOnly.SHAPES)) // inside the filled one
        m.endErase()
        assertNotNull(m.shapeOf(hollow)); assertNull(m.shapeOf(solid))
        assertTrue(m.erase(0f, 1000f, 20f, partial = false, only = EraseOnly.SHAPES)) // the hollow one's edge
    }

    @Test
    fun erasingATableLeavesWhatIsWrittenInIt() {
        val m = PageModel()
        val table = m.addTable(0f, 0f, 2000f, 2000f, 2, 2)!!.id
        val writing = m.add(Tool.TOOL_PEN, ink, 40, listOf(Pt(200f, 300f, 0, 1f), Pt(600f, 300f, 5, 1f))).id
        assertTrue(m.erase(0f, 1000f, 20f, partial = false, only = EraseOnly.TABLES)) // on the table's edge
        m.endErase()
        assertNull(m.tableOf(table)); assertNotNull(m.itemOf(writing))
    }

    @Test
    fun aConnectorLeftBehindKeepsItsEndWhereItWas() {
        val m = PageModel()
        val a = m.addShape { Shape(it, ShapeKind.RECT, 0f, 0f, 1000f, 1000f) }.id
        val b = m.addShape { Shape(it, ShapeKind.RECT, 3000f, 0f, 1000f, 1000f) }.id
        val c = m.addConnector { Connector(it, End(a, End.SPOT, 0f, 0f, 1f, .5f), End(b, End.SPOT, 0f, 0f, 0f, .5f)) }.id
        assertTrue(m.erase(0f, 500f, 20f, partial = false, only = EraseOnly.SHAPES)) // a's left edge
        m.endErase()
        val kept = m.connectorOf(c)!!
        assertNull(kept.from.shape)
        assertEquals(1000f, kept.from.x, 0.01f); assertEquals(500f, kept.from.y, 0.01f)
        assertEquals(b, kept.to.shape)
        assertEquals(listOf(b, c), m.order)
    }

    @Test
    fun oneDragIsOneUndoStepForEverythingItTook() {
        val p = page()
        val order = p.m.order
        p.m.erase(500f, 500f, 20f, partial = false)
        p.m.erase(800f, 500f, 20f, partial = false) // a second point of the same drag
        p.m.endErase()
        assertTrue(p.m.order.isEmpty())
        assertTrue(p.m.undo())
        assertEquals(order, p.m.order)
        assertEquals(6, p.m.items.size + p.m.texts.size + p.m.shapes.size + p.m.connectors.size + p.m.tables.size + p.m.fills.size)
        assertTrue(p.m.redo())
        assertTrue(p.m.order.isEmpty())
    }

    @Test
    fun theLogSaysWhatWasRemoved() {
        val p = page()
        p.m.log.clear()
        p.m.erase(500f, 500f, 20f, partial = false)
        p.m.endErase()
        val log = p.m.log
        assertTrue(log.any { it is Ev.Erased } && log.any { it is Ev.TextRemoved } && log.any { it is Ev.ShapeRemoved })
        assertTrue(log.any { it is Ev.ConnectorRemoved } && log.any { it is Ev.TableRemoved } && log.any { it is Ev.FillRemoved })
    }

    @Test
    fun missingEverythingChangesNothing() {
        val p = page()
        assertFalse(p.m.erase(5000f, 5000f, 20f, partial = false))
        p.m.endErase()
        assertEquals(6, p.m.order.size)
    }
}
