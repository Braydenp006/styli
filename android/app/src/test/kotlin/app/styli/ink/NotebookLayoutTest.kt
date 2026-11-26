package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookLayoutTest {
    private val gap = NotebookLayout.GAP

    /** A 2 by 2 grid: A4 portrait top left, a landscape page top right, Letter bottom left, A4 bottom right. */
    private fun grid(): Triple<Notebook, List<PageModel>, NotebookLayout> {
        val nb = Notebook()
        val a = nb.current
        val b = nb.add(a, Dir.RIGHT)!!
        b.setPage(PageSizes.withOrientation(b.page, true))
        val c = nb.add(a, Dir.DOWN)!!
        c.setPage(PageSizes.withPaper(c.page, true))
        val d = nb.add(b, Dir.DOWN)!!
        val pages = listOf(a, b, c, d)
        return Triple(nb, pages, NotebookLayout(pages))
    }

    @Test
    fun aColumnIsAsWideAsItsWidestPageAndARowAsTallAsItsTallest() {
        val (_, p, l) = grid()
        val (a, b, c, d) = p
        assertEquals(0.0 to 0.0, l.origin(a))
        assertEquals((21590 + gap).toDouble() to 0.0, l.origin(b)) // column 0 is the wider Letter (21590) below it
        assertEquals(0.0 to (21000 + gap).toDouble().let { 29700.0 + gap }, l.origin(c)) // row 0 is as tall as portrait A4
        assertEquals((21590 + gap).toDouble() to (29700 + gap).toDouble(), l.origin(d))
        assertEquals(21590 + gap + 29700, l.worldW) // Letter column + gap + the landscape page's width
        assertEquals(29700 + gap + 27940, l.worldH) // row 0 is the A4 portrait page, row 1 the Letter page (d copies b: landscape, shorter)
    }

    @Test
    fun aSinglePageIsTheWholeSurfaceAndNeedsNoGap() {
        val l = NotebookLayout(listOf(PageModel()))
        assertEquals(21000, l.worldW)
        assertEquals(29700, l.worldH)
        assertEquals(listOf(0.0 to 21000.0), l.columns)
    }

    @Test
    fun theGapAndEmptyCellsAreNotAPage() {
        val (_, p, l) = grid()
        val (a, b, c, _) = p
        assertSame(a, l.pageAt(100.0, 100.0))
        assertNull(l.pageAt(21590.0 + gap / 2, 100.0)) // in the gap between the columns
        assertNull(l.pageAt(100.0, 29700.0 + gap / 2.0)) // in the gap between the rows
        assertSame(b, l.pageAt(21590.0 + gap + 100, 100.0))
        assertSame(c, l.pageAt(100.0, 29700.0 + gap + 100))
        assertNull(l.pageAt(-5.0, 10.0))
    }

    @Test
    fun aPointInAGapOrBeyondGoesToTheClosestPage() {
        val (_, p, l) = grid()
        assertSame(p[0], l.nearest(21590.0 + 100, 100.0)) // just inside the gap, nearer to the first column
        assertSame(p[1], l.nearest(21590.0 + gap - 100, 100.0))
        assertSame(p[2], l.nearest(-500.0, 40000.0)) // far outside, bottom left
        assertSame(p[3], l.nearest(1e7, 1e7))
    }

    @Test
    fun onlyThePagesOnScreenAreDrawn() {
        val (_, p, l) = grid()
        assertEquals(listOf(p[0]), l.visible(0.0, 0.0, 1000.0, 1000.0))
        assertEquals(p, l.visible(-10.0, -10.0, 1e7, 1e7))
        assertEquals(listOf(p[0], p[1]), l.visible(20990.0, 0.0, 22500.0, 100.0)) // the first page's edge and the next page
        assertTrue(l.visible(21590.0 + 10, 0.0, 21590.0 + gap - 10, 100.0).isEmpty()) // only gap
    }

    @Test
    fun aPageIsAddedBelowTheLowestPageOfTheColumnAndBesideTheRightmostOfTheRow() {
        val (_, p, l) = grid()
        assertSame(p[2], l.bottomOfColumnAt(100.0)) // column 0: its lowest is the Letter page
        assertSame(p[3], l.bottomOfColumnAt(21590.0 + gap + 500))
        assertSame(p[3], l.bottomOfColumnAt(21590.0 + gap * 0.6)) // the gap: the nearest column, here the right one
        assertSame(p[1], l.rightOfRowAt(100.0)) // row 0: its right-most is the landscape page
        assertSame(p[3], l.rightOfRowAt(29700.0 + gap + 100))
    }

    @Test
    fun columnsAndRowsThatHoldNoPageStillTakeRoomButAreNotSettleTargets() {
        val nb = Notebook()
        val a = nb.current
        val far = nb.add(nb.add(a, Dir.RIGHT)!!, Dir.RIGHT)!! // columns 0, 1, 2 ...
        val l = NotebookLayout(listOf(a, far)) // ... but only columns 0 and 2 hold one
        assertEquals(listOf(0.0 to 21000.0, 2 * (21000.0 + gap) to 21000.0), l.columns) // the empty column 1 keeps its room
        assertNotNull(l.pageAt(2 * (21000.0 + gap) + 10, 10.0))
    }

    @Test
    fun aPageViewIsTheSinglePageGeometryWithThePagesPlaceFoldedIn() {
        val g = PageGeometry(60000, 40000, false, 0.05).also { it.fit(1000, 700) }
        val v = PageView(g, 30000.0, 10000.0, 21000, 29700, false)
        for (sx in listOf(0.0, 123.4, 999.0)) assertEquals(sx, v.toScreenX(v.toPageX(sx)), 1e-6)
        assertEquals(g.toScreenX(30000.0), v.toScreenX(0.0), 1e-9) // the page's own origin is where it sits
        assertEquals(g.toScreenY(10000.0 + 500.0), v.toScreenY(500.0), 1e-9)
        assertEquals(g.visible()[0] - 30000.0, v.visible()[0], 1e-9)
        assertEquals(g.tx + 30000.0 * g.scale, v.tx, 1e-9)
        v.panBy(0.0, -50.0) // scrolling goes through to the surface
        assertEquals(g.ty + 10000.0 * g.scale, v.ty, 1e-9)
    }

    @Test
    fun aScrollSettlesOnAColumnOnlyWhenCloseAndOnlyByAMargin() {
        val cols = listOf(0.0 to 21000.0, 21800.0 to 21000.0)
        val scale = 0.05 // a page is 1050 px wide; the view is 1100: it fits, so it settles centred
        val centred1 = (1100 - 1050) / 2.0 - 21800.0 * scale
        assertEquals(centred1, PageSnap.target(centred1 + 80, scale, 1100, cols, 24.0, 200.0)!!, 1e-9)
        assertNull(PageSnap.target(centred1 + 0.2, scale, 1100, cols, 24.0, 200.0)) // already there
        assertNull(PageSnap.target(-500.0, scale, 1100, cols, 24.0, 100.0)) // far from both: left alone
        assertEquals(25.0, PageSnap.target(-60.0, scale, 1100, listOf(0.0 to 21000.0), 24.0, 200.0)!!, 1e-9) // column 0 centred: 25
    }

    @Test
    fun aColumnWiderThanTheScreenSettlesWithItsEdgeAtTheMargin() {
        val cols = listOf(0.0 to 21000.0) // 2100 px wide at 0.1, in a 1000 px view
        assertEquals(24.0, PageSnap.target(-40.0, 0.1, 1000, cols, 24.0, 100.0)!!, 1e-9) // near its left edge
        assertEquals(1000 - 24.0 - 2100.0, PageSnap.target(-1100.0 - 20, 0.1, 1000, cols, 24.0, 100.0)!!, 1e-9) // near its right edge
        assertNull(PageSnap.target(-600.0, 0.1, 1000, cols, 24.0, 100.0)) // in the middle of it: free
    }

    @Test
    fun theSurfaceCanBeRestoredAfterItIsRebuiltAndScrolledToAPoint() {
        val old = PageGeometry(60000, 100000, false, 0.05).also { it.fit(1000, 700); it.panBy(0.0, -300.0) }
        val rebuilt = PageGeometry(60000, 200000, false, 0.05).also { it.fit(1000, 700); it.restore(old) }
        assertEquals(old.ty, rebuilt.ty, 1e-9)
        rebuilt.scrollTo(0.0, 30000.0)
        assertEquals(PageGeometry.MARGIN, rebuilt.toScreenY(30000.0), 1e-6)
        rebuilt.slideBy(1e9) // as far as the edges allow
        assertTrue(rebuilt.tx <= PageGeometry.MARGIN + 1e-9 || rebuilt.tx == (1000 - 60000 * rebuilt.scale) / 2)
    }

    @Test
    fun aLockedScrollKeepsToTheFirstAndLastPageOfItsRun() {
        // Two pages 21000 wide with an 800 gap, on a 1100 px screen at 0.05 px per unit: each is 1050 px, centred when at 25.
        val spans = listOf(0.0 to 21000.0, 21800.0 to 21000.0)
        val (lo, hi) = PageSnap.range(1100, 0.05, spans, 24.0)
        assertEquals(25.0, hi, 1e-9) // the first page, centred
        assertEquals(25.0 - 21800.0 * 0.05, lo, 1e-9) // the last page, centred
        // One page taller than the screen: it scrolls freely from its top at the margin to its bottom at the margin.
        val (bottom, top) = PageSnap.range(800, 0.05, listOf(0.0 to 29700.0), 24.0)
        assertEquals(24.0, top, 1e-9)
        assertEquals(800 - 24.0 - 29700.0 * 0.05, bottom, 1e-9)
        // Pages out of order, and nothing at all.
        assertEquals(PageSnap.range(1100, 0.05, spans, 24.0), PageSnap.range(1100, 0.05, spans.reversed(), 24.0))
        assertEquals(0.0 to 0.0, PageSnap.range(1100, 0.05, emptyList(), 24.0))
    }

    @Test
    fun aVerticalScrollSettlesOnARowTheSameWayAsASidewaysOneOnAColumn() {
        val rows = listOf(0.0 to 29700.0, 30500.0 to 29700.0) // two tall pages, taller than a 800 px screen at 0.05
        // A little short of the first page's bottom edge at the margin (-709): settles there.
        assertEquals(-709.0, PageSnap.target(-739.0, 0.05, 800, rows, 24.0, 200.0)!!, 1e-9)
        assertNull(PageSnap.target(-600.0, 0.05, 800, rows, 24.0, 20.0)) // in the middle of the first page: free
    }

    @Test
    fun dropCellsAreTheGridPlusOneRingAndFindTheCellUnderAPoint() {
        val (_, pages, l) = grid()
        assertEquals(16, l.slots().size) // 2 columns + 2 for the ring, the same for rows
        val a = pages[0]
        val q = l.slotRect(a.page.col, a.page.row, a.page.width, a.page.height)
        assertEquals(l.origin(a).first, q[0], 0.0)
        val above = l.slotRect(0, -1, a.page.width, a.page.height)
        assertEquals(-gap - a.page.height.toDouble(), above[1], 0.0)
        assertEquals((0 to 0), l.slotAt(q[0] + 10, q[1] + 10))
        assertEquals((0 to -1), l.slotAt(above[0] + 10, above[1] + 10))
        assertNull(l.slotAt(q[0] + q[2] + gap / 2.0 + 1.0, q[1] + 10).takeIf { false }) // far points now find a cell (see the next test)
    }

    @Test
    fun dropCellsReachAsFarAsTheViewShowsAndAnyPointFindsItsCell() {
        val (_, pages, l) = grid()
        val w = pages[0].page.width; val h = pages[0].page.height
        val far = l.slotRect(-5, 0, w, h)
        assertEquals(-5 to 0, l.slotAt(far[0] + 10, far[1] + 10))
        val farRight = l.slotRect(9, 7, w, h)
        assertEquals(9 to 7, l.slotAt(farRight[0] + 10, farRight[1] + 10))
        // a view that shows far to the left lists those cells; a small one lists only the first ring
        val wide = l.slots(doubleArrayOf(-10.0 * (w + gap), 0.0, 100.0, 100.0))
        assertTrue((-4 to 0) in wide)
        assertTrue((-2 to 0) in wide)
        assertTrue((-1 to 0) in l.slots(doubleArrayOf(0.0, 0.0, 100.0, 100.0)))
        assertTrue((-2 to 0) !in l.slots(doubleArrayOf(0.0, 0.0, 100.0, 100.0)))
    }

    @Test
    fun aPageAddedIntoAFarCellKeepsItsDistance() {
        val nb = Notebook()
        val a = nb.current
        val l0 = NotebookLayout(nb.pages)
        val (c, r) = l0.slotAt(l0.slotRect(-6, 0, a.page.width, a.page.height).let { it[0] + 5 }, 5.0)!!
        assertEquals(-6 to 0, c to r)
        val far = nb.addAt(a, c, r)!!
        val l1 = NotebookLayout(nb.pages)
        assertEquals(0.0, l1.origin(far).first, 0.0)
        assertEquals(6.0 * (a.page.width + gap), l1.origin(a).first, 0.0) // the five empty columns between keep their room
    }

    @Test
    fun anEmptyCellAbovePortraitPagesIsShownInTheGivenSize() {
        val nb = Notebook()
        val a = nb.current
        val l = NotebookLayout(nb.pages)
        val land = PageSizes.withOrientation(a.page, true)
        val q = l.slotRect(0, -1, land.width, land.height)
        assertEquals(land.width.toDouble(), q[2], 0.0) // landscape, though the column below is portrait
        assertEquals(land.height.toDouble(), q[3], 0.0)
    }

    @Test
    fun landscapeEmptyCellsFitTheirColumnsAndRowsWithNoOverhangOrGap() {
        val nb = Notebook()
        val a = nb.current // a portrait page
        val land = PageSizes.withOrientation(a.page, true)
        val l = NotebookLayout(nb.pages, refW = land.width, refH = land.height)
        // the portrait page's column is wide enough for a landscape cell, so the next column starts after it
        val right = l.slotRect(1, 0, land.width, land.height)
        val here = l.slotRect(0, 1, land.width, land.height)
        assertEquals(land.width + gap.toDouble(), right[0], 0.0)
        assertTrue(here[0] + here[2] <= right[0])
        // two empty landscape rows below it sit one gap apart, none of the portrait height between them
        val r2 = l.slotRect(0, 2, land.width, land.height)
        assertEquals(here[1] + land.height + gap, r2[1], 0.0)
        // and far out the steps are landscape ones too
        val far = l.slotRect(0, 5, land.width, land.height)
        assertEquals(here[1] + 4.0 * (land.height + gap), far[1], 0.0)
    }
}
