package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayersTest {
    private fun pts(y: Float) = listOf(Pt(0f, y, 0, 1f), Pt(1000f, y, 10, 1f))
    private fun PageModel.stroke(y: Float = 0f) = add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(y)).id

    @Test
    fun newThingsGoOnTopButTablesGoToTheBottom() {
        val m = PageModel()
        val a = m.stroke()
        val t = m.addText(0f, 0f, 1000f, 100f, "hi")
        val table = m.addTable(0f, 0f, 3000f, 1000f, 2, 2)!!
        val b = m.stroke()
        assertEquals(listOf(table.id, a, t.id, b), m.order)
    }

    @Test
    fun toFrontAndToBackKeepTheOrderAmongThemselves() {
        val m = PageModel()
        val (a, b, c, d) = List(4) { m.stroke(it * 100f) }
        assertTrue(m.arrange(setOf(a, c), Arrange.FRONT))
        assertEquals(listOf(b, d, a, c), m.order)
        assertTrue(m.arrange(setOf(d, c), Arrange.BACK))
        assertEquals(listOf(d, c, b, a), m.order)
        assertFalse(m.arrange(setOf(d), Arrange.BACK)) // already there
    }

    @Test
    fun oneStepPassesOnlyWhatOverlaps() {
        val m = PageModel()
        val (a, b, c) = List(3) { m.stroke() }
        val away = mapOf(a to Box(0.0, 0.0, 10.0, 10.0), b to Box(500.0, 500.0, 510.0, 510.0), c to Box(5.0, 5.0, 20.0, 20.0))
        assertTrue(m.arrange(setOf(a), Arrange.FORWARD) { away[it] }) // b is elsewhere, so a goes past c
        assertEquals(listOf(b, c, a), m.order)
        assertTrue(m.arrange(setOf(a), Arrange.BACKWARD) { away[it] })
        assertEquals(listOf(b, a, c), m.order)
        assertFalse(m.arrange(setOf(c), Arrange.FORWARD) { away[it] }) // nothing above it
    }

    @Test
    fun undoAndRedoBringTheOrderBack() {
        val m = PageModel()
        val (a, b) = List(2) { m.stroke() }
        m.arrange(setOf(a), Arrange.FRONT)
        assertEquals(listOf(b, a), m.order)
        m.undo()
        assertEquals(listOf(a, b), m.order)
        m.redo()
        assertEquals(listOf(b, a), m.order)
    }

    @Test
    fun partsOfAnErasedStrokeKeepItsLayer() {
        val m = PageModel()
        val a = m.stroke(3000f)
        val long = m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, (0..10).map { Pt(it * 100f, 0f, it * 10L, 1f) }).id
        val c = m.stroke(5000f)
        m.erase(500f, 0f, 10f, partial = true)
        m.endErase()
        assertEquals(4, m.order.size)
        assertEquals(a, m.order[0]) // the pieces sit where the long stroke was, below c
        assertEquals(c, m.order[3])
        assertFalse(long in m.order)
        m.undo()
        assertEquals(listOf(a, long, c), m.order)
    }

    @Test
    fun theLogCarriesWhatThePlainRulesWouldNotGive() {
        val m = PageModel()
        val (a, b) = List(2) { m.stroke() }
        m.log.clear()
        m.arrange(setOf(a), Arrange.FRONT)
        assertEquals(listOf<Ev>(Ev.ZMoved(b, null), Ev.ZMoved(a, b)).size, m.log.filterIsInstance<Ev.ZMoved>().size.coerceAtLeast(2))
        // replay: [a, b] with the events applied must give [b, a]
        val replay = mutableListOf(a, b)
        for (e in m.log.filterIsInstance<Ev.ZMoved>()) {
            replay.remove(e.id)
            replay.add(if (e.above == null) 0 else replay.indexOf(e.above) + 1, e.id)
        }
        assertEquals(m.order, replay)
    }

    @Test
    fun aPlainDrawWritesNoLayerEvent() {
        val m = PageModel()
        m.stroke(); m.addTable(0f, 0f, 3000f, 1000f, 2, 2); m.stroke()
        assertTrue(m.log.none { it is Ev.ZMoved })
    }
}
