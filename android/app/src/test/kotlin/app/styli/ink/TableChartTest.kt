package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableChartTest {
    private val sample = listOf(listOf("Item", "Jan", "Feb"), listOf("Tea", "2.5", "3"), listOf("Cake", "1,200", "n/a"))

    private fun page(): Pair<PageModel, Table> {
        val m = PageModel()
        val t = m.addTable(1000f, 1000f, 6000f, 3000f, 3, 3)!!
        m.setTable(t.withData(sample, true, listOf(0, 2, 2)))
        return m to m.tables.single()
    }

    @Test
    fun numbersAreReadLeniently() {
        assertEquals(12.5, TableChart.number("12.5")!!, 0.0)
        assertEquals(1234.5, TableChart.number("1,234.5")!!, 1e-9)
        assertEquals(1234.5, TableChart.number("1.234,5")!!, 1e-9)
        assertEquals(12.5, TableChart.number("12,5")!!, 1e-9)
        assertEquals(1200.0, TableChart.number("1,200")!!, 1e-9)
        assertEquals(1234567.0, TableChart.number("1,234,567")!!, 1e-9)
        assertEquals(-12.0, TableChart.number("(12)")!!, 0.0)
        assertEquals(-3.0, TableChart.number("−3")!!, 0.0)
        assertEquals(9.99, TableChart.number("£ 9.99")!!, 1e-9)
        assertEquals(45.0, TableChart.number("45%")!!, 0.0)
        assertEquals(7.0, TableChart.number("  7  ")!!, 0.0)
        for (bad in listOf("", "  ", "n/a", "abc", "1.2.3", "--4", "12 apples", "NaN", "Infinity")) assertNull(bad, TableChart.number(bad))
    }

    @Test
    fun textFromASpreadsheetBecomesRows() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), TableChart.parseDelimited("a\tb\n1\t2\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), TableChart.parseDelimited("a,b\r\n1,2"))
        assertEquals(listOf(listOf("a", "b"), listOf("1,5", "2")), TableChart.parseDelimited("a;b\n1,5;2")) // semicolons when there are more of them
        assertEquals(listOf(listOf("x, y", "say \"hi\""), listOf("1", "")), TableChart.parseDelimited("\"x, y\",\"say \"\"hi\"\"\"\n1,"))
        assertEquals(listOf(listOf("two\nlines", "b")), TableChart.parseDelimited("\"two\nlines\",b")) // a quoted cell may hold a new line
        assertEquals(listOf(listOf("one"), listOf("two")), TableChart.parseDelimited("one\ntwo")) // no separator: one column
        assertEquals(listOf(listOf("a", "b"), listOf("1")), TableChart.parseDelimited("a,b\n1")) // short rows stay short
        assertEquals(listOf(listOf("a", "b")), TableChart.parseDelimited("a, b \n\n\n")) // cells trimmed, blank end dropped
        assertTrue(TableChart.parseDelimited("").isEmpty())
        assertEquals(listOf(listOf("", "x")), TableChart.parseDelimited("\tx"))
    }

    @Test
    fun aTableHoldsTypedTextAndGrowsToFitAGrid() {
        val (_, t) = page()
        assertEquals("Tea", t.cellTextAt(1, 0)); assertEquals("", t.cellTextAt(9, 0)) // out of range reads as none
        assertTrue(t.headerRow); assertEquals(2, t.alignOf(1)); assertEquals(0, t.alignOf(0))
        val big = t.withData(listOf(listOf("a", "b", "c", "d"), listOf("e"), listOf("f", "g"), listOf("h"), listOf("i")), false, emptyList())
        assertEquals(5, big.rows); assertEquals(4, big.cols)
        assertEquals("d", big.cellTextAt(0, 3)); assertEquals("", big.cellTextAt(1, 2)); assertEquals("i", big.cellTextAt(4, 0))
        assertEquals(t.colWidths.last(), big.colWidths.last(), 0f) // a new column is as wide as the last
        assertTrue(big.colAlign.isEmpty()) // all left: nothing stored
        assertTrue(t.withData(listOf(listOf("", "")), false, emptyList()).cellText.isEmpty()) // all empty: nothing stored
        val tooBig = t.withData(List(30) { listOf("x") }, false, emptyList())
        assertEquals(Table.MAX_LINES, tooBig.rows)
        assertEquals(sample, t.textGrid())
    }

    @Test
    fun insertingAndDeletingKeepTheTextAndAlignmentWithTheirCells() {
        val (_, t) = page()
        val withRow = t.inserted(false, 1, 500f)
        assertEquals("Item", withRow.cellTextAt(0, 0)); assertEquals("", withRow.cellTextAt(1, 1)); assertEquals("Tea", withRow.cellTextAt(2, 0))
        val withCol = t.inserted(true, 1, 500f)
        assertEquals("Item", withCol.cellTextAt(0, 0)); assertEquals("", withCol.cellTextAt(0, 1)); assertEquals("Jan", withCol.cellTextAt(0, 2))
        assertEquals(listOf(0, 0, 2, 2), withCol.colAlign) // the new column is left, the others keep theirs
        val noRow = t.deleted(false, 1)
        assertEquals("Cake", noRow.cellTextAt(1, 0)); assertEquals(2, noRow.rows)
        val noCol = t.deleted(true, 0)
        assertEquals("Jan", noCol.cellTextAt(0, 0)); assertEquals("n/a", noCol.cellTextAt(2, 1)); assertEquals(listOf(2, 2), noCol.colAlign)
        assertTrue(t.copy(cellText = emptyList()).inserted(false, 0, 500f).cellText.isEmpty()) // no text stays no text
    }

    private fun src(row: Int = 0, col: Int = 0, rows: Int = 3, cols: Int = 3, inRows: Boolean = false) = ChartSource("t", row, col, rows, cols, inRows)

    @Test
    fun aBlockOfCellsBecomesCategoriesAndSeries() {
        val (_, t) = page()
        val base = Chart("c", ChartKind.BAR, 0f, 0f, 100f, 100f)
        val byCol = TableChart.fill(base, t, src().copy(table = t.id))
        assertEquals(listOf("Tea", "Cake"), byCol.categories)
        assertEquals(listOf("Jan", "Feb"), byCol.series.map { it.name })
        assertEquals(listOf(2500L, 1200000L), byCol.series[0].values) // "1,200" is twelve hundred
        assertEquals(setOf(1), byCol.series[1].missing) // n/a is a blank
        assertEquals(3000L, byCol.series[1].values[0])
        val byRow = TableChart.fill(base, t, src(inRows = true).copy(table = t.id))
        assertEquals(listOf("Jan", "Feb"), byRow.categories)
        assertEquals(listOf("Tea", "Cake"), byRow.series.map { it.name })
        assertEquals(setOf(1), byRow.series[1].missing) // Cake's Feb
        val part = TableChart.fill(base, t, src(row = 1, col = 1, rows = 2, cols = 2).copy(table = t.id)) // rows 1-2, columns 1-2: labels are the first of those
        assertEquals(listOf("1,200"), part.categories); assertEquals(listOf("3"), part.series.map { it.name })
        assertEquals(base, TableChart.fill(base, t, src(rows = 1)))  // under 2 by 2: unchanged
        assertEquals(2, TableChart.fill(base, t, src(rows = 9, cols = 9).copy(table = t.id)).source!!.rows - 1) // cut to the table
    }

    @Test
    fun theLookOfAChartIsKeptWhenItsNumbersChange() {
        val (_, t) = page()
        val look = Chart("c", ChartKind.COMBO, 0f, 0f, 100f, 100f, series = listOf(
            ChartSeries("x", 0xFF123456.toInt(), emptyList(), kind = ChartKind.LINE, secondary = true, trendline = true), ChartSeries("y", 0xFF654321.toInt(), emptyList())), title = "Mine")
        val c = TableChart.fill(look, t, src().copy(table = t.id))
        assertEquals(0xFF123456.toInt(), c.series[0].color); assertEquals(ChartKind.LINE, c.series[0].kind); assertTrue(c.series[0].secondary && c.series[0].trendline)
        assertEquals(0xFF654321.toInt(), c.series[1].color); assertEquals("Mine", c.title); assertEquals(ChartKind.COMBO, c.kind)
    }

    @Test
    fun aLinkedChartFollowsItsTableInTheSameUndoStepAndLetsGoWhenItIsGone() {
        val (m, t) = page()
        val chart = m.addChart { TableChart.fill(Chart(it, ChartKind.BAR, 0f, 3000f, 6000f, 4000f), t, src().copy(table = t.id)) }
        assertEquals(listOf("Tea", "Cake"), m.chartOf(chart.id)!!.categories)
        // typing in the table changes the chart, as one step
        val steps = m.log.size
        assertTrue(m.setTable(m.tables.single().withData(sample.map { it.toMutableList() }.also { it[1][1] = "10" }, true, emptyList())))
        assertEquals(10000L, m.chartOf(chart.id)!!.series[0].values[0])
        assertTrue(m.log.drop(steps).any { it is Ev.ChartSet } && m.log.drop(steps).any { it is Ev.TableSet })
        assertTrue(m.undo()) // one undo takes back both
        assertEquals(2500L, m.chartOf(chart.id)!!.series[0].values[0]); assertEquals("2.5", m.tables.single().cellTextAt(1, 1))
        assertTrue(m.redo()); assertEquals(10000L, m.chartOf(chart.id)!!.series[0].values[0])
        // a row added to the table inside the block (the block is the old size) leaves the chart as it is unless the text moved
        assertTrue(m.setTable(m.tables.single().inserted(false, 1, 500f)))
        assertEquals(2, m.chartOf(chart.id)!!.categories.size)
        // the table goes: the chart keeps its numbers and lets go
        val before = m.chartOf(chart.id)!!.series
        assertTrue(m.delete(setOf(t.id)))
        assertNull(m.chartOf(chart.id)!!.source); assertEquals(before, m.chartOf(chart.id)!!.series)
        assertTrue(m.undo()); assertNotNull(m.chartOf(chart.id)!!.source) // and undo brings the link back
    }

    @Test
    fun aChartMovedAwayWithoutItsTableLetsGoButOneCopiedWithItKeepsFollowing() {
        val (m, t) = page()
        val chart = m.addChart { TableChart.fill(Chart(it, ChartKind.BAR, 0f, 3000f, 6000f, 4000f), t, src().copy(table = t.id)) }
        val box = { id: String -> Box(0.0, 0.0, 10.0, 10.0).takeIf { id.isNotEmpty() } }
        val alone = Clip.of("a", m, setOf(chart.id), box)!!
        assertNull(alone.charts.single().source) // its table was left behind
        val both = Clip.of("b", m, setOf(chart.id, t.id), box)!!
        val n = PageModel()
        n.paste(both, 100f, 100f)
        val tn = n.tables.single(); val cn = n.charts.single()
        assertTrue(tn.id != t.id)
        assertEquals(tn.id, cn.source!!.table) // follows the copy
        assertTrue(n.setTable(tn.withData(sample.map { it.toMutableList() }.also { it[1][1] = "99" }, true, emptyList())))
        assertEquals(99000L, n.charts.single().series[0].values[0])
        assertEquals(2500L, m.charts.single().series[0].values[0]) // the original is not touched
    }

    @Test
    fun theTableAsTextRoundTripsThroughASpreadsheet() {
        val (_, t) = page()
        val text = TableChart.toText(t)
        assertEquals("Item\tJan\tFeb\nTea\t2.5\t3\nCake\t1,200\tn/a", text)
        assertEquals(sample, TableChart.parseDelimited(text).map { it })
        assertFalse(TableChart.toText(t.copy(cellText = listOf("a\tb", "c\nd", "", "", "", "", "", "", ""))).contains("\u0009\u0009\u0009")) // a tab or new line inside a cell would break the rows
    }
}
