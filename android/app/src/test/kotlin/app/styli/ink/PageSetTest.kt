package app.styli.ink

import app.styli.ink.proto.PageSet
import app.styli.ink.proto.Template
import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSetTest {
    private val a4 = PageModel.A4_LINED
    private fun stroke() = listOf(Pt(100f, 28000f, 0, 1f), Pt(900f, 28000f, 10, 1f)) // low on an A4 page

    @Test
    fun orientationIsWidthLargerThanHeight() {
        assertFalse(PageSizes.isLandscape(a4))
        assertTrue(PageSizes.isLandscape(PageSizes.withOrientation(a4, true)))
        assertFalse(PageSizes.isLandscape(a4.toBuilder().setWidth(21000).setHeight(21000).build())) // a square is portrait
    }

    @Test
    fun turningThePageSwapsTheSidesAndKeepsThePaperAndTheOtherSettings() {
        val land = PageSizes.withOrientation(a4, true)
        assertEquals(29700 to 21000, land.width to land.height)
        assertEquals(Template.TEMPLATE_LINED, land.template)
        assertEquals(false, PageSizes.isLetter(land))
        assertSame(land, PageSizes.withOrientation(land, true)) // already landscape: nothing to do
        assertEquals(a4, PageSizes.withOrientation(land, false)) // and back
    }

    @Test
    fun changingPaperKeepsTheOrientation() {
        val letterLand = PageSizes.withPaper(PageSizes.withOrientation(a4, true), true)
        assertEquals(27940 to 21590, letterLand.width to letterLand.height)
        assertEquals(true, PageSizes.isLetter(letterLand))
        assertEquals(21590 to 27940, PageSizes.withPaper(a4, true).let { it.width to it.height })
        assertNull(PageSizes.isLetter(a4.toBuilder().setWidth(10000).setHeight(15000).build())) // some other paper
    }

    @Test
    fun aSizeChangeIsOneUndoStepAndALogEvent() {
        val m = PageModel()
        val land = PageSizes.withOrientation(a4, true)
        assertTrue(m.setPage(land))
        assertEquals(land, m.page)
        assertEquals(listOf<Ev>(Ev.PageChanged(land)), m.log)
        assertTrue(m.undo())
        assertEquals(a4, m.page)
        assertEquals(Ev.PageChanged(a4), m.log.last())
        assertTrue(m.redo())
        assertEquals(land, m.page)
        assertFalse(m.undo() && m.undo()) // only one step to undo
    }

    @Test
    fun nothingHappensForTheSamePageOrAPageWithNoSize() {
        val m = PageModel()
        assertFalse(m.setPage(a4))
        assertFalse(m.setPage(a4.toBuilder().setWidth(0).build()))
        assertFalse(m.setPage(a4.toBuilder().setHeight(0).build()))
        assertEquals(a4, m.page)
        assertFalse(m.canUndo)
        assertTrue(m.log.isEmpty())
    }

    @Test
    fun inkAndTextStayWhereTheyAreWhenThePageChangesShapeAndComeBackWithTheOldSize() {
        val m = PageModel()
        val s = m.add(Tool.TOOL_PEN, 0, 40, stroke())
        val t = m.addText(100f, 27000f, 5000f, 400f, "low down")
        m.setPage(PageSizes.withOrientation(a4, true)) // 21 cm tall now: both are off the sheet
        assertSame(s, m.items.single())
        assertSame(t, m.texts.single())
        assertEquals(28000f, m.items.single().pts[0].y, 0f)
        m.undo()
        assertEquals(a4, m.page)
        assertEquals(2, listOf(m.items.size, m.texts.size).sum())
    }

    @Test
    fun pageChangesInterleaveWithStrokesInTheUndoOrder() {
        val m = PageModel()
        val s1 = m.add(Tool.TOOL_PEN, 0, 40, stroke())
        m.setPage(PageSizes.withPaper(a4, true))
        val s2 = m.add(Tool.TOOL_PEN, 0, 40, stroke())
        m.undo() // the second stroke
        assertEquals(listOf(s1.id), m.items.map { it.id })
        assertEquals(true, PageSizes.isLetter(m.page))
        m.undo() // the paper
        assertEquals(a4, m.page)
        m.undo() // the first stroke
        assertTrue(m.items.isEmpty())
        m.redo(); m.redo(); m.redo()
        assertEquals(listOf(s1.id, s2.id), m.items.map { it.id })
        assertEquals(true, PageSizes.isLetter(m.page))
    }

    @Test
    fun theLogRebuildsThePageAfterChangesAndUndos() {
        val m = PageModel()
        m.setPage(PageSizes.withOrientation(a4, true))
        m.setPage(PageSizes.withPaper(m.page, true))
        m.setPage(m.page.toBuilder().setTemplate(Template.TEMPLATE_GRID).setInfinite(true).build())
        m.undo()
        m.undo()
        m.redo()
        var rebuilt: PageSet = a4
        for (e in m.log) if (e is Ev.PageChanged) rebuilt = e.page
        assertEquals(m.page, rebuilt)
    }

    @Test
    fun eachPageHasItsOwnSize() {
        val a = PageModel()
        val b = PageModel()
        a.setPage(PageSizes.withOrientation(a4, true))
        assertNotEquals(a.page, b.page)
        assertEquals(a4, b.page)
        assertTrue(b.log.isEmpty())
    }
}
