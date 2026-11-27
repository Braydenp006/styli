package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TableTest {
    private fun model() = PageModel()
    private fun stroke() = listOf(Pt(0f, 0f, 0, 1f), Pt(100f, 0f, 10, 1f))

    private fun replay(log: List<Ev>): Map<String, Table> {
        val out = LinkedHashMap<String, Table>()
        for (e in log) when (e) {
            is Ev.TableSet -> out[e.table.id] = e.table
            is Ev.TableRemoved -> out.remove(e.id)
            else -> {}
        }
        return out
    }

    @Test
    fun aNewTableDividesTheRectangleIntoEqualCellsAsOneUndoableStep() {
        val m = model()
        val t = m.addTable(100f, 200f, 6000f, 2000f, rows = 2, cols = 3)!!
        assertEquals(listOf(2000f, 2000f, 2000f), t.colWidths)
        assertEquals(listOf(1000f, 1000f), t.rowHeights)
        assertEquals(3, t.cols); assertEquals(2, t.rows)
        assertEquals(6000f, t.width, 0.01f); assertEquals(2000f, t.height, 0.01f)
        assertEquals(0, t.color)
        assertEquals(listOf<Ev>(Ev.TableSet(t)), m.log)
        assertTrue(m.undo())
        assertTrue(m.tables.isEmpty())
        assertEquals(Ev.TableRemoved(t.id), m.log.last())
        assertTrue(m.redo())
        assertEquals(t.id, m.tables.single().id) // back under its old ID
    }

    @Test
    fun aTableNeedsRealSizesAndASaneCount() {
        val m = model()
        assertNull(m.addTable(0f, 0f, 6000f, 2000f, rows = 0, cols = 3))
        assertNull(m.addTable(0f, 0f, 6000f, 2000f, rows = 2, cols = 0))
        assertNull(m.addTable(0f, 0f, 60000f, 20000f, rows = 21, cols = 3))
        assertNull(m.addTable(0f, 0f, 60000f, 20000f, rows = 3, cols = 21))
        assertNull(m.addTable(0f, 0f, 1000f, 2000f, rows = 1, cols = 3)) // 3.3 mm wide cells: under 5 mm
        assertNull(m.addTable(0f, 0f, 6000f, 1000f, rows = 3, cols = 1)) // 3.3 mm tall cells
        assertFalse(m.canUndo)
        assertTrue(m.log.isEmpty())
        assertNotNull(m.addTable(0f, 0f, 1500f, 500f, rows = 1, cols = 3)) // exactly 5 mm cells
        assertNotNull(m.addTable(0f, 0f, 10000f, 10000f, rows = 20, cols = 20))
    }

    @Test
    fun aMovedInnerLineMakesTheTwoCellsBesideItTradeSize() {
        val t = model().addTable(0f, 0f, 6000f, 3000f, rows = 3, cols = 3)!!
        val moved = t.moveLine(vertical = true, index = 1, d = 500f)
        assertEquals(listOf(2500f, 1500f, 2000f), moved.colWidths)
        assertEquals(t.width, moved.width, 0.01f) // the table's size does not change
        assertEquals(t.x, moved.x, 0f)
        assertEquals(t.rowHeights, moved.rowHeights)
        val up = t.moveLine(vertical = false, index = 2, d = -300f)
        assertEquals(listOf(1000f, 700f, 1300f), up.rowHeights)
        assertEquals(t.colWidths, up.colWidths)
        assertEquals(t.id, moved.id)
    }

    @Test
    fun anOuterEdgeStretchesTheTableAndTheOtherEdgeStaysPut() {
        val t = model().addTable(100f, 200f, 6000f, 3000f, rows = 3, cols = 3)!!
        val right = t.moveLine(true, 3, 700f)
        assertEquals(listOf(2000f, 2000f, 2700f), right.colWidths)
        assertEquals(t.x, right.x, 0f)
        val left = t.moveLine(true, 0, -700f) // the left edge goes left: the first column grows, the corner moves with it
        assertEquals(listOf(2700f, 2000f, 2000f), left.colWidths)
        assertEquals(-600f, left.x, 0.01f)
        assertEquals(t.lineX(3), left.lineX(3), 0.01f) // the right edge did not move
        val top = t.moveLine(false, 0, 400f) // the top edge goes down: the first row shrinks
        assertEquals(listOf(600f, 1000f, 1000f), top.rowHeights)
        assertEquals(600f, top.y, 0.01f)
        assertEquals(t.lineY(3), top.lineY(3), 0.01f)
        val bottom = t.moveLine(false, 3, -400f)
        assertEquals(listOf(1000f, 1000f, 600f), bottom.rowHeights)
    }

    @Test
    fun noCellIsSqueezedUnderTheMinimumAndAnImpossibleMoveChangesNothing() {
        val t = model().addTable(0f, 0f, 3000f, 3000f, rows = 2, cols = 2)!! // 1500 cells
        val squeezed = t.moveLine(true, 1, 99999f) // as far right as the second column allows
        assertEquals(listOf(2500f, Table.MIN_CELL), squeezed.colWidths)
        assertEquals(listOf(Table.MIN_CELL, 2500f), t.moveLine(true, 1, -99999f).colWidths)
        assertEquals(listOf(Table.MIN_CELL, 1500f), t.moveLine(true, 0, 99999f).colWidths.let { listOf(it[0], it[1]) }) // edge in as far as it can
        assertEquals(Table.MIN_CELL, t.moveLine(true, 2, -99999f).colWidths[1], 0f)
        assertSame(squeezed, squeezed.moveLine(true, 1, 10f)) // already as far as it goes that way: the same table
        assertSame(t, t.moveLine(true, 1, 0f))
        try { t.moveLine(true, 3, 1f); throw AssertionError("accepted a line that is not there") } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun movingALineIsOneStepAndTheLogHoldsTheWholeTable() {
        val m = model()
        val t = m.addTable(0f, 0f, 6000f, 3000f, rows = 3, cols = 3)!!
        assertTrue(m.setTable(t.moveLine(true, 1, 400f)))
        assertEquals(listOf(2400f, 1600f, 2000f), m.tables.single().colWidths)
        assertTrue(m.undo())
        assertEquals(listOf(2000f, 2000f, 2000f), m.tables.single().colWidths)
        assertEquals(listOf(2000f, 2000f, 2000f), (m.log.last() as Ev.TableSet).table.colWidths) // undo writes the old state whole
        assertFalse(m.setTable(Table("0".repeat(32), 0f, 0f, listOf(1f), listOf(1f), 1f))) // no such table
    }

    @Test
    fun movingAndResizingMoveTheCornerAndScaleTheCellsButNotTheLineWidth() {
        val m = model()
        val t = m.addTable(100f, 100f, 4000f, 2000f, rows = 2, cols = 2, lineWidth = 40f)!!
        assertTrue(m.transform(setOf(t.id), Affine(2.0, 0.0, 0.0, 2.0, 10.0, 20.0)))
        with(m.tables.single()) {
            assertEquals(listOf(210f, 220f), listOf(x, y))
            assertEquals(listOf(4000f, 4000f), colWidths)
            assertEquals(listOf(2000f, 2000f), rowHeights)
            assertEquals(40f, lineWidth, 0f)
        }
        assertTrue("only a table: no Transformed event, it is written whole", m.log.none { it is Ev.Transformed })
        m.undo()
        assertEquals(listOf(100f, 100f), m.tables.single().let { listOf(it.x, it.y) })
        assertEquals(listOf(2000f, 2000f), m.tables.single().colWidths)
    }

    @Test
    fun aMixedMoveAndDeleteTakeStrokesTextBoxesAndTablesInOneStep() {
        val m = model()
        val s = m.add(Tool.TOOL_PEN, 0, 40, stroke())
        val b = m.addText(500f, 0f, 400f, 40f, "t")
        val t = m.addTable(0f, 600f, 3000f, 1000f, rows = 1, cols = 2)!!
        m.transform(setOf(s.id, b.id, t.id), Affine(1.0, 0.0, 0.0, 1.0, 50.0, 0.0))
        assertEquals(listOf(s.id), m.log.filterIsInstance<Ev.Transformed>().single().ids) // only strokes ride in Transformed
        assertEquals(50f, m.tables.single().x, 0f)
        assertEquals(550f, m.texts.single().x, 0f)
        m.undo()
        assertEquals(0f, m.tables.single().x, 0f)
        assertEquals(500f, m.texts.single().x, 0f)
        assertTrue(m.delete(setOf(s.id, b.id, t.id)))
        assertTrue(m.items.isEmpty() && m.texts.isEmpty() && m.tables.isEmpty())
        assertTrue(Ev.TableRemoved(t.id) in m.log)
        m.undo()
        assertEquals(t.id, m.tables.single().id)
        assertEquals(b.id, m.texts.single().id)
        assertEquals(s.id, m.items.single().id)
        assertFalse(m.delete(setOf("nothing")))
    }

    @Test
    fun theLogRebuildsTheSameTablesAfterEditsMovesDeletesAndUndos() {
        val m = model()
        val a = m.addTable(0f, 0f, 3000f, 1000f, rows = 1, cols = 2)!!
        val b = m.addTable(0f, 2000f, 3000f, 1000f, rows = 1, cols = 3)!!
        m.setTable(m.tables.first { it.id == a.id }.moveLine(true, 1, 300f))
        m.transform(setOf(b.id), Affine(1.5, 0.0, 0.0, 1.5, 3.0, 4.0))
        m.delete(setOf(a.id))
        m.undo()
        m.setTable(m.tables.first { it.id == b.id }.copy(lineWidth = 80f, color = 0xFFC62828.toInt()))
        m.undo(); m.undo(); m.redo()
        val got = replay(m.log)
        assertEquals(m.tables.map { it.id }.toSet(), got.keys)
        for (t in m.tables) {
            val r = got.getValue(t.id)
            assertEquals(listOf(t.x, t.y, t.colWidths, t.rowHeights, t.lineWidth, t.color), listOf(r.x, r.y, r.colWidths, r.rowHeights, r.lineWidth, r.color))
        }
    }

    @Test
    fun aTableAndAPageChangeSitInTheSameUndoHistory() {
        val m = model()
        val t = m.addTable(0f, 0f, 3000f, 1000f, rows = 1, cols = 2)!!
        m.setPage(PageSizes.withOrientation(m.page, true))
        m.undo() // the page
        assertEquals(t.id, m.tables.single().id)
        assertFalse(PageSizes.isLandscape(m.page))
        m.undo() // the table
        assertTrue(m.tables.isEmpty())
        m.redo(); m.redo()
        assertEquals(t.id, m.tables.single().id)
        assertTrue(PageSizes.isLandscape(m.page))
    }

    @Test
    fun theTableKnowsWhereItsLinesAreAndWhatIsInsideIt() {
        val t = model().addTable(100f, 200f, 6000f, 3000f, rows = 3, cols = 3)!!
        assertEquals(listOf(100f, 2100f, 4100f, 6100f), (0..3).map { t.lineX(it) })
        assertEquals(listOf(200f, 1200f, 2200f, 3200f), (0..3).map { t.lineY(it) })
        assertTrue(t.contains(100f, 200f) && t.contains(6100f, 3200f) && t.contains(3000f, 1000f))
        assertFalse(t.contains(99f, 1000f)); assertFalse(t.contains(3000f, 3201f))
    }

    @Test
    fun theSizeRuleIsOneFunctionThePreviewAndTheModelShare() {
        assertTrue(Table.fits(1500f, 500f, rows = 1, cols = 3)) // exactly 5 mm cells
        assertFalse(Table.fits(1499f, 500f, rows = 1, cols = 3))
        assertFalse(Table.fits(1500f, 499f, rows = 1, cols = 3))
        assertTrue(Table.fits(10000f, 10000f, rows = 20, cols = 20))
        assertFalse(Table.fits(99999f, 99999f, rows = 21, cols = 1))
        assertFalse(Table.fits(99999f, 99999f, rows = 1, cols = 0))
        assertFalse(Table.fits(0f, 0f, rows = 1, cols = 1)) // a tap
    }

    private val loop = floatArrayOf(0f, 0f, 1000f, 0f, 1000f, 1000f, 0f, 1000f) // 0..1000 each way

    private fun tableAt(x: Float, y: Float, w: Float, h: Float): Table = Table("t$x$y", x, y, listOf(w / 2, w / 2), listOf(h), 40f)

    @Test
    fun aLoopTakesTheTablesMoreThanHalfInsideByArea() {
        val inside = tableAt(100f, 100f, 600f, 300f)
        val mostly = tableAt(500f, 100f, 800f, 300f) // 500 of 800 wide inside
        val half = tableAt(500f, 150f, 1000f, 300f) // exactly half (its own spot, so its own ID)
        val outside = tableAt(2000f, 100f, 600f, 300f)
        val got = LassoGeometry.selectedTables(listOf(inside, mostly, half, outside), loop)
        assertEquals(4, listOf(inside, mostly, half, outside).map { it.id }.toSet().size)
        assertEquals(setOf(inside.id, mostly.id), got)
        assertEquals(emptySet<String>(), LassoGeometry.selectedTables(listOf(inside), floatArrayOf(0f, 0f, 10f, 10f))) // under three points
    }

    @Test
    fun everyLineHasOneHandleOnItAndNoTwoHandlesMeetForAnyTableShape() {
        val t = model().addTable(100f, 200f, 6000f, 3000f, rows = 3, cols = 3)!!
        val h = t.lineHandles()
        assertEquals(8, h.size) // 4 vertical lines and 4 horizontal ones
        assertEquals(listOf(100f, 2100f, 4100f, 6100f), h.filter { it.vertical }.map { it.x }) // each on its own line
        assertTrue(h.filter { it.vertical }.all { it.y == 200f + 500f }) // in the middle of the top row
        assertEquals(listOf(200f, 1200f, 2200f, 3200f), h.filter { !it.vertical }.map { it.y })
        assertTrue(h.filter { !it.vertical }.all { it.x == 100f + 1000f }) // in the middle of the first column
        assertEquals((0..3).toList(), h.filter { it.vertical }.map { it.index })
        // For every size from 1 by 1 to 20 by 20, no two handles share a spot.
        for (rows in 1..20) for (cols in 1..20) {
            val tt = Table("t", 0f, 0f, List(cols) { 500f }, List(rows) { 500f }, 40f)
            val pts = tt.lineHandles().map { it.x to it.y }
            assertEquals("$rows x $cols", pts.size, pts.toSet().size)
        }
    }

    @Test
    fun theHandlesFollowATableThatHasBeenResized() {
        val t = model().addTable(0f, 0f, 4000f, 2000f, rows = 2, cols = 2)!!
        val moved = t.moveLine(true, 1, 600f) // the middle vertical line goes right
        val v = moved.lineHandles().first { it.vertical && it.index == 1 }
        assertEquals(2600f, v.x, 0.01f)
        assertEquals(500f, v.y, 0.01f)
        val firstColumn = moved.lineHandles().first { !it.vertical && it.index == 1 }
        assertEquals(1300f, firstColumn.x, 0.01f) // the middle of the (now wider) first column
    }

    // A 3 by 3 table of 1000 by 500 cells at (0, 0): x 0..3000, y 0..1500. Strokes and a text box sit in its cells.
    private fun filled(): Triple<PageModel, Table, Map<String, String>> {
        val m = model()
        val t = m.addTable(0f, 0f, 3000f, 1500f, rows = 3, cols = 3)!!
        fun dot(x: Float, y: Float) = m.add(Tool.TOOL_PEN, 0, 40, listOf(Pt(x - 10, y, 0, 1f), Pt(x + 10, y, 5, 1f))).id
        val ids = mapOf(
            "r0" to dot(500f, 250f), // row 0, column 0
            "r1" to dot(500f, 750f), // row 1, column 0
            "r2" to dot(1500f, 1250f), // row 2, column 1
            "out" to dot(500f, 1800f), // below the table
            "box" to m.addText(2100f, 800f, 700f, 40f, "x").id, // row 1, column 2 (judged by the top middle: 2450, 800)
        )
        return Triple(m, t, ids)
    }

    private fun y(m: PageModel, id: String) = m.items.first { it.id == id }.pts[0].y

    @Test
    fun aTableInsertsAndDeletesALineAndKeepsItsCorner() {
        val t = model().addTable(10f, 20f, 3000f, 1500f, rows = 3, cols = 3)!!
        val row = t.inserted(false, 1, 500f)
        assertEquals(listOf(500f, 500f, 500f, 500f), row.rowHeights)
        assertEquals(t.colWidths, row.colWidths)
        assertEquals(10f to 20f, row.x to row.y)
        assertEquals(listOf(1000f, 1000f, 1000f, 700f), t.inserted(true, 3, 700f).colWidths) // at the end
        assertEquals(listOf(700f, 1000f, 1000f, 1000f), t.inserted(true, 0, 700f).colWidths) // at the start
        assertEquals(listOf(500f, 500f), t.deleted(false, 1).rowHeights)
        assertEquals(t.id, t.deleted(true, 0).id)
        try { t.inserted(false, 4, 1f); throw AssertionError("accepted") } catch (e: IllegalArgumentException) { }
        try { Table("t", 0f, 0f, listOf(1f), listOf(1f), 1f).deleted(true, 0); throw AssertionError("deleted the last column") } catch (e: IllegalArgumentException) { }
        assertEquals(0x99FFEB3B.toInt(), t.copy(fill = 0x99FFEB3B.toInt()).fill)
        assertEquals(0, t.fill)
    }

    @Test
    fun insertingARowMovesWhatIsInsideTheTableBelowItAndNothingElse() {
        val (m, t, ids) = filled()
        assertTrue(m.insertLine(t.id, vertical = false, at = 1)) // a new row between row 0 and row 1
        assertEquals(listOf(500f, 500f, 500f, 500f), m.tables.single().rowHeights) // as big as the row it went next to
        assertEquals(250f, y(m, ids.getValue("r0")), 0f) // above: stays
        assertEquals(1250f, y(m, ids.getValue("r1")), 0f) // moved down by the new row's 500
        assertEquals(1750f, y(m, ids.getValue("r2")), 0f)
        assertEquals(1800f, y(m, ids.getValue("out")), 0f) // outside the table: stays
        assertEquals(1300f, m.texts.single().y, 0f)
        assertEquals(500f, m.items.first { it.id == ids.getValue("r1") }.pts[0].x + 10f, 0f) // sideways untouched
    }

    @Test
    fun aRowOrColumnAddedAboveOrLeftGrowsTheTableBackwardsAndTheRestStaysPut() {
        val m = model()
        val t = m.addTable(1500f, 2000f, 3000f, 1500f, rows = 3, cols = 3)!!
        val r0 = m.add(Tool.TOOL_PEN, 0, 40, listOf(Pt(1990f, 2250f, 0, 1f), Pt(2010f, 2250f, 5, 1f))).id // row 0
        val r1 = m.add(Tool.TOOL_PEN, 0, 40, listOf(Pt(1990f, 2750f, 0, 1f), Pt(2010f, 2750f, 5, 1f))).id // row 1
        assertTrue(m.insertLine(t.id, vertical = false, at = 1, growBack = true)) // above row 1
        assertEquals(1500f, m.tables.single().y, 0f) // the top moved up by the new row
        assertEquals(4, m.tables.single().rows)
        assertEquals(1750f, y(m, r0), 0f) // before the new row: moved up with the table's top
        assertEquals(2750f, y(m, r1), 0f) // from the new row on: where it was
        assertTrue(m.insertLine(t.id, vertical = true, at = 0, growBack = true)) // a column on the left
        assertEquals(500f, m.tables.single().x, 0f) // 1500 - 1000
        assertTrue(m.undo()); assertTrue(m.undo())
        assertEquals(2000f, m.tables.single().y, 0f)
        assertEquals(2250f, y(m, r0), 0f)
        // No room above the sheet's top: it grows downwards as before.
        val m2 = model(); val t2 = m2.addTable(0f, 0f, 3000f, 1500f, rows = 3, cols = 3)!!
        assertTrue(m2.insertLine(t2.id, false, 0, growBack = true))
        assertEquals(0f, m2.tables.single().y, 0f)
        assertEquals(4, m2.tables.single().rows)
    }

    @Test
    fun anInsertIsOneStepAndUndoPutsEverythingBack() {
        val (m, t, ids) = filled()
        m.insertLine(t.id, false, 1)
        assertEquals(1, m.log.filterIsInstance<Ev.Transformed>().size)
        assertEquals(setOf(ids.getValue("r1"), ids.getValue("r2")), m.log.filterIsInstance<Ev.Transformed>().single().ids.toSet())
        assertTrue(m.undo())
        assertEquals(listOf(500f, 500f, 500f), m.tables.single().rowHeights)
        assertEquals(750f, y(m, ids.getValue("r1")), 0.001f)
        assertEquals(800f, m.texts.single().y, 0f)
        assertTrue(m.redo())
        assertEquals(1250f, y(m, ids.getValue("r1")), 0.001f)
    }

    @Test
    fun insertingAColumnAtTheEndAndAtTheStartAndTheLimits() {
        val (m, t, ids) = filled()
        assertTrue(m.insertLine(t.id, vertical = true, at = 0)) // a column on the left: everything moves right
        assertEquals(4, m.tables.single().cols)
        assertEquals(1500f, m.items.first { it.id == ids.getValue("r0") }.pts[0].x + 10f, 0.001f)
        assertEquals(3100f, m.texts.single().x, 0f)
        assertTrue(m.insertLine(m.tables.single().id, true, 4)) // after the last: nothing to move
        assertEquals(5, m.tables.single().cols)
        assertFalse(m.insertLine(t.id, true, 99)) // no such place
        assertFalse(m.insertLine("0".repeat(32), true, 0)) // no such table
        val big = model().addTable(0f, 0f, 20000f, 10000f, rows = 20, cols = 20)!!
        val m2 = model(); m2.addTable(0f, 0f, 20000f, 10000f, rows = 20, cols = 20)
        assertFalse(m2.insertLine(m2.tables.single().id, false, 0)) // already 20 rows
        assertEquals(20, big.rows)
    }

    @Test
    fun deletingARowRemovesItsContentAndPullsTheRestBackAsOneStep() {
        val (m, t, ids) = filled()
        assertTrue(m.deleteLine(t.id, vertical = false, index = 1)) // row 1
        assertEquals(listOf(500f, 500f), m.tables.single().rowHeights)
        assertTrue("the stroke in the row is gone", m.items.none { it.id == ids.getValue("r1") })
        assertTrue("so is the text box in it", m.texts.isEmpty())
        assertEquals(250f, y(m, ids.getValue("r0")), 0f)
        assertEquals(750f, y(m, ids.getValue("r2")), 0f) // pulled up by the row's 500
        assertEquals(1800f, y(m, ids.getValue("out")), 0f)
        assertTrue(Ev.Erased(ids.getValue("r1")) in m.log)
        assertTrue(m.undo())
        assertEquals(listOf(500f, 500f, 500f), m.tables.single().rowHeights)
        assertEquals(ids.getValue("r1"), m.items.first { it.id == ids.getValue("r1") }.id) // back under its own ID
        assertEquals(750f, y(m, ids.getValue("r1")), 0f)
        assertEquals(1250f, y(m, ids.getValue("r2")), 0f)
        assertEquals(1, m.texts.size)
    }

    @Test
    fun deletingAColumnWorksSidewaysAndTheLastLineCannotGo() {
        val (m, t, ids) = filled()
        assertTrue(m.deleteLine(t.id, vertical = true, index = 0))
        assertTrue(m.items.none { it.id == ids.getValue("r0") || it.id == ids.getValue("r1") }) // both were in column 0
        assertEquals(500f, m.items.first { it.id == ids.getValue("r2") }.pts[0].x + 10f, 0.001f) // column 1 is column 0 now
        assertEquals(1100f, m.texts.single().x, 0f) // 2100 less the column's 1000
        assertFalse(m.deleteLine(t.id, true, 5))
        val one = model(); val tb = one.addTable(0f, 0f, 1000f, 500f, rows = 1, cols = 1)!!
        assertFalse(one.deleteLine(tb.id, true, 0)) // the last column
        assertFalse(one.deleteLine(tb.id, false, 0)) // the last row
        assertEquals(1, one.tables.single().cols)
    }

    @Test
    fun severalTablesAreRecolouredInOneStep() {
        val m = model()
        val a = m.addTable(0f, 0f, 1500f, 500f, rows = 1, cols = 3)!!
        val b = m.addTable(0f, 1000f, 1500f, 500f, rows = 1, cols = 3)!!
        assertTrue(m.setTables(listOf(a.copy(color = 0xFFC62828.toInt(), fill = 0x99FFEB3B.toInt()), b.copy(color = 0xFFC62828.toInt(), lineWidth = 160f))))
        assertEquals(listOf(0xFFC62828.toInt(), 0xFFC62828.toInt()), m.tables.map { it.color })
        assertEquals(listOf(0x99FFEB3B.toInt(), 0), m.tables.map { it.fill })
        m.undo()
        assertEquals(listOf(0, 0), m.tables.map { it.color })
        assertFalse(m.setTables(listOf(Table("0".repeat(32), 0f, 0f, listOf(1f), listOf(1f), 1f))))
    }

    @Test
    fun handlesKeepTheirOtherCoordinateFromTheTableAsItWasWhenADragBegan() {
        val t = model().addTable(0f, 0f, 3000f, 1500f, rows = 3, cols = 3)!!
        val dragged = t.moveLine(true, 1, 400f) // the first column grows: its middle moves, so loose handles would drift
        val loose = dragged.lineHandles().first { !it.vertical && it.index == 1 }
        val steady = dragged.lineHandles(anchor = t).first { !it.vertical && it.index == 1 }
        assertEquals(700f, loose.x, 0.01f)
        assertEquals(500f, steady.x, 0.01f) // stays at the middle of the first column as it was
        val line = dragged.lineHandles(anchor = t).first { it.vertical && it.index == 1 }
        assertEquals(1400f, line.x, 0.01f) // the line being moved goes where it really is
        assertEquals(250f, line.y, 0.01f)
    }

    @Test
    fun aNewLineIsAsBigAsTheOneItGoesNextToOrTheLastWhenItGoesAtTheEnd() {
        val m = model()
        val t = m.addTable(0f, 0f, 3000f, 2000f, rows = 2, cols = 3)!!
        m.setTable(t.moveLine(false, 1, 300f)) // rows are now 1300 and 700
        val id = t.id
        m.insertLine(id, false, 1) // goes next to row 1 (700 tall): takes that size
        assertEquals(listOf(1300f, 700f, 700f), m.tables.single().rowHeights)
        m.insertLine(id, false, 0) // at the top: next to row 0
        assertEquals(listOf(1300f, 1300f, 700f, 700f), m.tables.single().rowHeights)
        m.insertLine(id, false, 4) // at the end: the same as the last row
        assertEquals(listOf(1300f, 1300f, 700f, 700f, 700f), m.tables.single().rowHeights)
    }

    @Test
    fun handlesOfVerticalLinesStayPutWhenARowChangesSizeDuringADrag() {
        val t = model().addTable(0f, 0f, 3000f, 3000f, rows = 3, cols = 3)!!
        val dragged = t.moveLine(false, 1, 200f) // the first row grows from 1000 to 1200
        val loose = dragged.lineHandles().first { it.vertical && it.index == 1 }.y
        val steady = dragged.lineHandles(anchor = t).first { it.vertical && it.index == 1 }.y
        assertEquals(600f, loose, 0.01f)
        assertEquals(500f, steady, 0.01f)
    }

    @Test
    fun aPointIsInTheCellItIsInAndALineBelongsToTheCellAfterIt() {
        val t = model().addTable(100f, 200f, 3000f, 1500f, rows = 3, cols = 3)!! // cells 1000 by 500 from (100, 200)
        assertEquals(0 to 0, t.cellAt(150f, 250f))
        assertEquals(1 to 2, t.cellAt(2500f, 800f))
        assertEquals(2 to 1, t.cellAt(1500f, 1600f))
        assertEquals(1 to 1, t.cellAt(1100f, 700f)) // exactly on the line between columns 0 and 1, rows 0 and 1: the later cell
        assertEquals(2 to 2, t.cellAt(3100f, 1700f)) // the bottom right corner is still the last cell
        assertNull(t.cellAt(99f, 250f))
        assertNull(t.cellAt(500f, 1701f))
    }

    @Test
    fun aTapNearALinePicksTheTableButNotTheMiddleOfACell() {
        val t = model().addTable(100f, 200f, 3000f, 1500f, rows = 3, cols = 3)!! // vertical lines at 100, 1100, 2100, 3100; horizontal at 200, 700, 1200, 1700
        assertTrue(t.nearLine(1105f, 900f, 20f)) // on an inner vertical line
        assertTrue(t.nearLine(600f, 705f, 20f)) // on an inner horizontal line
        assertTrue(t.nearLine(95f, 900f, 20f)) // just outside the outline still counts
        assertTrue(t.nearLine(3115f, 1700f, 20f)) // near a corner
        assertFalse(t.nearLine(600f, 450f, 20f)) // the middle of a cell
        assertFalse(t.nearLine(1105f, 1800f, 20f)) // beside the line but past the table's end
        assertFalse(t.nearLine(-500f, -500f, 20f))
    }
}
