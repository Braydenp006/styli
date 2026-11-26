package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseTest {
    // A straight line along y=0 from x=0 to x=1000, one point every 100 units.
    private fun line() = (0..10).map { Pt(it * 100f, 0f, it * 10L, 1f) }

    @Test
    fun missLeavesTheStrokeAlone() {
        assertNull(EraseGeometry.cut(line(), 500f, 300f, 50f))
        assertFalse(EraseGeometry.hits(line(), 500f, 300f, 50f))
    }

    @Test
    fun cutInTheMiddleGivesTwoPiecesWithEdgesOnTheDisc() {
        val r = EraseGeometry.cut(line(), 500f, 0f, 50f)!!
        assertEquals(2, r.size)
        assertEquals(450f, r[0].last().x, 0.01f)
        assertEquals(550f, r[1].first().x, 0.01f)
        assertEquals(0f, r[0].first().x, 0f)
        assertEquals(1000f, r[1].last().x, 0f)
    }

    @Test
    fun cutBetweenTwoPointsStillCuts() {
        // Only two points, far apart: the disc is in the middle of the segment and touches neither.
        val two = listOf(Pt(0f, 0f, 0, 1f), Pt(1000f, 0f, 100, 1f))
        val r = EraseGeometry.cut(two, 500f, 0f, 50f)!!
        assertEquals(2, r.size)
        assertEquals(450f, r[0].last().x, 0.01f)
        assertEquals(50L, ((r[0].last().t + r[1].first().t) / 2)) // times are interpolated too
    }

    @Test
    fun cutAtTheStartOrEndShortensTheStroke() {
        val start = EraseGeometry.cut(line(), 0f, 0f, 50f)!!
        assertEquals(1, start.size)
        assertEquals(50f, start[0].first().x, 0.01f)
        val end = EraseGeometry.cut(line(), 1000f, 0f, 50f)!!
        assertEquals(1, end.size)
        assertEquals(950f, end[0].last().x, 0.01f)
    }

    @Test
    fun coveringEverythingLeavesNothing() {
        assertEquals(emptyList<List<Pt>>(), EraseGeometry.cut(line(), 500f, 0f, 2000f))
    }

    @Test
    fun aSingleDotIsHitOrNot() {
        val dot = listOf(Pt(10f, 10f, 0, 1f))
        assertEquals(emptyList<List<Pt>>(), EraseGeometry.cut(dot, 12f, 10f, 5f))
        assertNull(EraseGeometry.cut(dot, 100f, 10f, 5f))
    }

    @Test
    fun aLeftoverStubOfOnePointIsDropped() {
        // The disc leaves only the very first point outside: no piece of length zero.
        val r = EraseGeometry.cut(line(), 100f, 0f, 90f)
        assertNotNull(r)
        assertTrue(r!!.none { it.size < 2 })
    }

    @Test
    fun strokeEraserTakesWholeStrokesThatAreTouched() {
        val m = PageModel()
        val a = m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, line())
        val b = m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, line().map { it.copy(y = 1000f) })
        assertTrue(m.erase(500f, 0f, 10f, partial = false))
        m.endErase()
        assertEquals(listOf(b.id), m.items.map { it.id })
        assertEquals(a.id, (m.log.filterIsInstance<Ev.Erased>().single()).id)
    }

    @Test
    fun partialEraseIsOneEraseAndNewStrokes() {
        val m = PageModel()
        val a = m.add(Tool.TOOL_HIGHLIGHTER, 0x99FFFF00.toInt(), 400, line())
        m.log.clear()
        m.erase(500f, 0f, 10f, partial = true)
        m.endErase()
        assertEquals(2, m.items.size)
        assertTrue(m.items.all { it.tool == Tool.TOOL_HIGHLIGHTER && it.width == 400 && it.id != a.id })
        assertEquals(1, m.log.count { it is Ev.Erased })
        assertEquals(2, m.log.count { it is Ev.Added })
    }

    @Test
    fun oneDragIsOneUndoStep() {
        val m = PageModel()
        m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, line())
        for (x in listOf(200f, 500f, 800f)) m.erase(x, 0f, 10f, partial = true)
        m.endErase()
        assertEquals(4, m.items.size)
        assertTrue(m.undo())
        assertEquals(1, m.items.size)
        assertTrue(m.redo())
        assertEquals(4, m.items.size)
        assertTrue(m.undo() && m.undo())
        assertEquals(0, m.items.size)
        assertFalse(m.undo())
    }

    @Test
    fun aNewActionClearsRedo() {
        val m = PageModel()
        m.add(Tool.TOOL_PEN, 0, 40, line())
        m.undo()
        assertTrue(m.canRedo)
        m.add(Tool.TOOL_PEN, 0, 40, line())
        assertFalse(m.canRedo)
    }

    @Test
    fun undoComesBackUnderTheOldIdAndTheLogReplaysToTheSameState() {
        val m = PageModel()
        val a = m.add(Tool.TOOL_PEN, 0, 40, line())
        m.erase(500f, 0f, 10f, partial = false)
        m.endErase()
        m.undo()
        assertEquals(listOf(a.id), m.items.map { it.id })
        // Replaying the log from nothing gives the model's strokes.
        val replay = LinkedHashMap<String, Item>()
        for (e in m.log) when (e) {
            is Ev.Added -> replay[e.item.id] = e.item
            is Ev.Erased -> replay.remove(e.id)
            is Ev.Transformed -> {}
        }
        assertEquals(m.items.map { it.id }, replay.keys.toList())
    }

    @Test
    fun eraserMissChangesNothing() {
        val m = PageModel()
        m.add(Tool.TOOL_PEN, 0, 40, line())
        assertFalse(m.erase(500f, 900f, 10f, partial = true))
        m.endErase()
        m.undo() // undoes the add, not a phantom erase
        assertEquals(0, m.items.size)
    }
}
