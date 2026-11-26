package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableCellFillTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    // 2 rows by 3 columns, red at (0, 1) and blue at (1, 2).
    private fun table() = Table("t", 0f, 0f, listOf(1000f, 1000f, 1000f), listOf(500f, 500f), 40f)
        .withCellFill(0, 1, red).withCellFill(1, 2, blue)

    @Test
    fun cellsKeepTheirOwnColour() {
        val t = table()
        assertEquals(red, t.cellFill(0, 1))
        assertEquals(blue, t.cellFill(1, 2))
        assertEquals(0, t.cellFill(0, 0))
        assertEquals(6, t.cellFills.size)
    }

    @Test
    fun clearingTheLastColourDropsTheList() {
        val t = Table("t", 0f, 0f, listOf(1000f), listOf(500f), 40f).withCellFill(0, 0, red).withCellFill(0, 0, 0)
        assertTrue(t.cellFills.isEmpty())
    }

    @Test
    fun anInsertedRowOrColumnIsEmptyAndTheRestStayPut() {
        val col = table().inserted(true, 1, 1000f) // a column between the first and the red one
        assertEquals(8, col.cellFills.size)
        assertEquals(red, col.cellFill(0, 2))
        assertEquals(blue, col.cellFill(1, 3))
        assertEquals(0, col.cellFill(0, 1))
        val row = table().inserted(false, 0, 500f) // a row on top
        assertEquals(red, row.cellFill(1, 1))
        assertEquals(blue, row.cellFill(2, 2))
        assertEquals(0, row.cellFill(0, 1))
    }

    @Test
    fun deletingARowOrColumnTakesItsColoursAway() {
        val col = table().deleted(true, 1) // the red column
        assertEquals(4, col.cellFills.size)
        assertEquals(blue, col.cellFill(1, 1))
        assertEquals(0, col.cellFill(0, 1))
        val row = table().deleted(false, 1) // the blue row
        assertEquals(red, row.cellFill(0, 1))
        assertEquals(3, row.cellFills.size)
    }

    @Test
    fun aTableWithoutCellColoursStaysWithout() {
        val t = Table("t", 0f, 0f, listOf(1000f, 1000f), listOf(500f), 40f)
        assertTrue(t.inserted(true, 0, 1000f).cellFills.isEmpty())
        assertTrue(t.deleted(true, 0).cellFills.isEmpty())
    }
}
