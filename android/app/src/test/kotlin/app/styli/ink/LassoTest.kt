package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LassoTest {
    private fun stroke(x: Float, y: Float, n: Int = 5) = (0 until n).map { Pt(x + it * 10f, y, it * 10L, 1f) }
    private val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)

    @Test
    fun polygonInsideAndOutside() {
        assertTrue(LassoGeometry.contains(square, 50f, 50f))
        assertFalse(LassoGeometry.contains(square, 150f, 50f))
        assertFalse(LassoGeometry.contains(square, 50f, -1f))
        // A concave "C" shape: the notch is outside.
        val c = floatArrayOf(0f, 0f, 100f, 0f, 100f, 30f, 30f, 30f, 30f, 70f, 100f, 70f, 100f, 100f, 0f, 100f)
        assertTrue(LassoGeometry.contains(c, 10f, 50f))
        assertFalse(LassoGeometry.contains(c, 60f, 50f))
    }

    @Test
    fun moreThanHalfOfThePointsMustBeInside() {
        val m = PageModel()
        val inside = m.add(Tool.TOOL_PEN, 0, 40, stroke(10f, 50f))       // x 10..50, all inside
        val half = m.add(Tool.TOOL_PEN, 0, 40, stroke(80f, 50f, 4))     // x 80,90,100,110: two of four in
        val most = m.add(Tool.TOOL_PEN, 0, 40, stroke(70f, 20f, 4))     // x 70,80,90,100: all on or inside... 100 is the edge
        val out = m.add(Tool.TOOL_PEN, 0, 40, stroke(200f, 50f))
        val got = LassoGeometry.selected(m.items, square)
        assertTrue(inside.id in got)
        assertFalse("exactly half is not more than half", half.id in got)
        assertFalse(out.id in got)
        assertNotNull(most)
        assertEquals(emptySet<String>(), LassoGeometry.selected(m.items, floatArrayOf(0f, 0f, 10f, 10f))) // under three points
    }

    @Test
    fun boundsAreRoundTheInkIncludingThePen() {
        val m = PageModel()
        m.add(Tool.TOOL_PEN, 0, 40, stroke(10f, 50f))
        val b = LassoGeometry.bounds(m.items)!!
        assertEquals(-10.0, b.l, 1e-9)
        assertEquals(30.0, b.t, 1e-9)
        assertEquals(70.0, b.r, 1e-9)
        assertEquals(70.0, b.b, 1e-9)
        assertNull(LassoGeometry.bounds(emptyList()))
    }

    @Test
    fun affineInverseUndoesIt() {
        val m = Affine(2.0, 0.3, -0.1, 1.5, 40.0, -7.0)
        val p = Pt(12f, 34f, 0, 1f)
        val back = m.inverse().apply(m.apply(p))
        assertEquals(12f, back.x, 0.001f)
        assertEquals(34f, back.y, 0.001f)
    }

    @Test
    fun resizingByACornerKeepsTheOppositeCornerAndTheShape() {
        val box = Box(0.0, 0.0, 100.0, 50.0)
        // Pull the bottom right corner (3) to twice the distance along the diagonal.
        val m = LassoGeometry.scaleFromCorner(box, 3, 200.0, 100.0)
        assertEquals(0f, m.x(0f, 0f), 1e-4f)
        assertEquals(0f, m.y(0f, 0f), 1e-4f)
        assertEquals(200f, m.x(100f, 50f), 1e-3f)
        assertEquals(100f, m.y(100f, 50f), 1e-3f)
        // Pulling the top left corner (0) keeps the bottom right one fixed.
        val n = LassoGeometry.scaleFromCorner(box, 0, 50.0, 25.0)
        assertEquals(100f, n.x(100f, 50f), 1e-3f)
        assertEquals(50f, n.y(100f, 50f), 1e-3f)
        assertEquals(50f, n.x(0f, 0f), 1e-3f)
    }

    @Test
    fun resizeStaysInRangeAndSurvivesAFlatBox() {
        val box = Box(0.0, 0.0, 100.0, 50.0)
        assertEquals(0.05, LassoGeometry.scaleFromCorner(box, 3, -500.0, -500.0).a, 1e-9) // never flips or vanishes
        assertEquals(20.0, LassoGeometry.scaleFromCorner(box, 3, 1e6, 1e6).a, 1e-9)
        val flat = Box(0.0, 10.0, 100.0, 10.0) // a straight horizontal line
        assertEquals(2.0, LassoGeometry.scaleFromCorner(flat, 3, 200.0, 10.0).a, 1e-9)
        assertTrue(LassoGeometry.scaleFromCorner(Box(5.0, 5.0, 5.0, 5.0), 3, 9.0, 9.0).isIdentity) // a dot
    }

    @Test
    fun transformMovesOnlyTheChosenStrokesAndKeepsTheirIds() {
        val m = PageModel()
        val a = m.add(Tool.TOOL_PEN, 0, 40, stroke(0f, 0f))
        val b = m.add(Tool.TOOL_PEN, 0, 40, stroke(0f, 100f))
        assertTrue(m.transform(setOf(a.id), Affine.translate(100.0, 50.0)))
        assertEquals(listOf(a.id, b.id), m.items.map { it.id })
        assertEquals(100f, m.items[0].pts[0].x, 0f)
        assertEquals(50f, m.items[0].pts[0].y, 0f)
        assertSame(b, m.items[1])
        assertEquals(40, m.items[0].width) // the pen does not get wider
        assertFalse(m.transform(setOf(a.id), Affine.IDENTITY))
        assertFalse(m.transform(setOf("nope"), Affine.translate(1.0, 1.0)))
    }

    @Test
    fun transformUndoRedoGivesTheOldPointsBackExactly() {
        val m = PageModel()
        val a = m.add(Tool.TOOL_PEN, 0, 40, stroke(0f, 0f))
        m.transform(setOf(a.id), Affine.scaleAbout(1.7, 3.0, 4.0))
        assertTrue(m.undo())
        assertSame(a, m.items[0]) // the very same stroke, so no rounding drift from undo
        assertTrue(m.redo())
        assertEquals(a.pts[4].x * 1.7f + (3.0 - 1.7 * 3.0).toFloat(), m.items[0].pts[4].x, 1e-3f)
    }

    @Test
    fun deleteIsOneUndoStepAndAnEraseInTheLog() {
        val m = PageModel()
        val a = m.add(Tool.TOOL_PEN, 0, 40, stroke(0f, 0f))
        val b = m.add(Tool.TOOL_PEN, 0, 40, stroke(0f, 100f))
        m.log.clear()
        assertTrue(m.delete(setOf(a.id, b.id)))
        assertEquals(0, m.items.size)
        assertEquals(setOf(a.id, b.id), m.log.filterIsInstance<Ev.Erased>().map { it.id }.toSet())
        assertTrue(m.undo())
        assertEquals(listOf(a.id, b.id), m.items.map { it.id })
        assertFalse(m.delete(setOf("nope")))
    }

    @Test
    fun theLogReplaysToTheSameStrokesAfterMovesDeletesAndUndos() {
        val m = PageModel()
        val a = m.add(Tool.TOOL_PEN, 0, 40, stroke(0f, 0f))
        val b = m.add(Tool.TOOL_PEN, 0, 40, stroke(0f, 100f))
        m.transform(setOf(a.id, b.id), Affine.translate(30.0, 10.0))
        m.transform(setOf(a.id), Affine.scaleAbout(2.0, 0.0, 0.0))
        m.undo()
        m.delete(setOf(b.id))
        m.undo()
        m.undo()
        m.redo()
        val replay = LinkedHashMap<String, Item>()
        for (e in m.log) when (e) {
            is Ev.Added -> replay[e.item.id] = e.item
            is Ev.Erased -> replay.remove(e.id)
            is Ev.Transformed -> for (id in e.ids) replay[id]?.let { replay[id] = Item(it.id, it.tool, it.color, it.width, it.pts.map(e.map::apply)) }
        }
        assertEquals(m.items.map { it.id }, replay.keys.toList())
        for (it in m.items) {
            val r = replay.getValue(it.id)
            it.pts.indices.forEach { k ->
                assertEquals(it.pts[k].x, r.pts[k].x, 0.01f)
                assertEquals(it.pts[k].y, r.pts[k].y, 0.01f)
            }
        }
    }
}
