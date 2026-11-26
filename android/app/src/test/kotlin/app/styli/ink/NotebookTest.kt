package app.styli.ink

import app.styli.ink.proto.Template
import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTest {
    private fun cell(p: PageModel) = p.page.col to p.page.row

    @Test
    fun theFirstPageIsAtTheOriginAndAddingDownAndRightFillsTheGrid() {
        val nb = Notebook()
        val first = nb.current
        assertEquals(0 to 0, cell(first))
        val below = nb.add(first, Dir.DOWN)!!
        assertEquals(1 to 0, cell(below).let { it.second to it.first }) // row 1, column 0
        assertSame(below, nb.current)
        val right = nb.add(first, Dir.RIGHT)!!
        assertEquals(1 to 0, cell(right))
        val corner = nb.add(right, Dir.DOWN)!!
        assertEquals(1 to 1, cell(corner))
        assertSame(corner, nb.neighbour(below, Dir.RIGHT)) // the grid joins up
        assertSame(first, nb.neighbour(right, Dir.LEFT))
        assertSame(right, nb.neighbour(corner, Dir.UP))
        assertNull(nb.neighbour(first, Dir.UP))
    }

    @Test
    fun aCellThatIsTakenIsRefusedAndNothingChanges() {
        val nb = Notebook()
        val first = nb.current
        val below = nb.add(first, Dir.DOWN)!!
        nb.go(first)
        assertNull(nb.add(first, Dir.DOWN))
        assertEquals(2, nb.pages.size)
        assertSame(first, nb.current)
        assertSame(below, nb.neighbour(first, Dir.DOWN))
    }

    @Test
    fun aNewPageCopiesTheSizeTemplateAndModeOfThePageItCameFrom() {
        val nb = Notebook()
        nb.current.setPage(PageSizes.withOrientation(nb.current.page, true).toBuilder().setTemplate(Template.TEMPLATE_GRID).setInfinite(true).build())
        val next = nb.add(nb.current, Dir.RIGHT)!!
        assertEquals(29700 to 21000, next.page.width to next.page.height)
        assertEquals(Template.TEMPLATE_GRID, next.page.template)
        assertTrue(next.page.infinite)
        assertFalse(next.canUndo) // the copy is the page's start, not a step
        assertTrue(next.log.isEmpty())
    }

    @Test
    fun readingGoesRowByRowAndLeftToRightWhateverTheOrderTheyWereMade() {
        val nb = Notebook()
        val a = nb.current
        val d = nb.add(a, Dir.DOWN)!!       // (0,1)
        val e = nb.add(d, Dir.RIGHT)!!      // (1,1)
        val b = nb.add(a, Dir.RIGHT)!!      // (1,0)
        val c = nb.add(b, Dir.RIGHT)!!      // (2,0)
        assertEquals(listOf(a, b, c, d, e), nb.readingOrder())
    }

    @Test
    fun onlyAnUntouchedAddedPageCanBeTakenBackAndItGoesBackToWhereItCameFrom() {
        val nb = Notebook()
        val first = nb.current
        assertFalse(nb.removeMade()) // the first page is never taken back
        val p = nb.add(first, Dir.DOWN)!!
        assertTrue(nb.removeMade())
        assertEquals(listOf(first), nb.pages)
        assertSame(first, nb.current)
        assertNull(nb.at(0, 1))
        // With ink on it, or any step on it, it stays.
        val q = nb.add(first, Dir.DOWN)!!
        q.add(Tool.TOOL_PEN, 0, 40, listOf(Pt(0f, 0f, 0, 1f), Pt(50f, 0f, 5, 1f)))
        assertFalse(nb.removeMade())
        q.undo() // back to empty, but the undo stack still has the redo; no steps left to undo
        assertTrue(nb.removeMade())
        val r = nb.add(first, Dir.DOWN)!!
        r.setPage(PageSizes.withPaper(r.page, true))
        assertFalse(nb.removeMade()) // a size change is a step
        assertSame(r, nb.current)
        assertTrue(p !== q && q !== r)
    }

    @Test
    fun goingToAPageThatIsNotInTheNotebookIsRefused() {
        val nb = Notebook()
        try { nb.go(PageModel()); throw AssertionError("accepted") } catch (e: IllegalArgumentException) { }
        try { nb.add(PageModel(), Dir.DOWN); throw AssertionError("accepted") } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun aRunIsTheUnbrokenLineOfPagesThroughAPage() {
        val nb = Notebook()
        val a = nb.pages[0]
        val b = nb.add(a, Dir.RIGHT)!!
        val c = nb.add(b, Dir.RIGHT)!!
        val d = nb.add(b, Dir.DOWN)!!
        val e = nb.add(a, Dir.UP)!! // above the first
        assertEquals(listOf(a, b, c), nb.run(b, horizontal = true))
        assertEquals(listOf(b, d), nb.run(d, horizontal = false).takeLast(2))
        assertEquals(listOf(e, a), nb.run(a, horizontal = false))
        assertEquals(listOf(d), nb.run(d, horizontal = true))
        assertEquals(c, nb.end(a, Dir.RIGHT))
        assertEquals(d, nb.end(b, Dir.DOWN))
        assertEquals(e, nb.end(a, Dir.UP))
        assertEquals(a, nb.end(a, Dir.LEFT))
    }

    @Test
    fun pagesCanBeAddedAboveAndLeftOfTheFirst() {
        val nb = Notebook()
        val a = nb.pages[0]
        val up = nb.add(a, Dir.UP)!!
        val left = nb.add(a, Dir.LEFT)!!
        assertEquals(0, up.page.col); assertEquals(-1, up.page.row)
        assertEquals(-1, left.page.col); assertEquals(0, left.page.row)
        assertEquals(listOf(up, left, a).toSet(), nb.pages.toSet())
    }

    @Test
    fun theLastAddedPageGoesEvenIfYouStayedOnAnother() {
        val nb = Notebook()
        val first = nb.current
        val a = nb.add(first, Dir.RIGHT)!!
        val b = nb.add(first, Dir.DOWN)!!
        nb.go(first) // the view stays where it was after an add
        assertTrue(nb.removeMade()) // takes back b, the last
        assertSame(first, nb.current)
        assertNull(nb.at(0, 1))
        assertSame(a, nb.at(1, 0))
        assertTrue(nb.removeMade()) // then a
        assertFalse(nb.removeMade()) // and never the first page
        assertEquals(listOf(first), nb.pages)
        assertTrue(b !== a)
    }
}
