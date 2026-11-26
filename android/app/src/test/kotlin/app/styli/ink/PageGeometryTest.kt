package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGeometryTest {
    private val px = 0.05 // pixels per unit at real size (about 127 dpi)

    private fun a4() = PageGeometry(21000, 29700, false, px).also { it.fit(1000, 700) }
    private fun canvas() = PageGeometry(21000, 29700, true, px).also { it.fit(1000, 700) }

    @Test
    fun pageSizes() {
        assertEquals(21000 to 29700, PageSizes.of(letter = false, landscape = false))
        assertEquals(29700 to 21000, PageSizes.of(letter = false, landscape = true))
        assertEquals(21590 to 27940, PageSizes.of(letter = true, landscape = false))
        assertEquals(27940 to 21590, PageSizes.of(letter = true, landscape = true))
    }

    @Test
    fun fixedPageStartsFittedToTheWidth() {
        val g = a4()
        assertEquals(24.0, g.toScreenX(0.0), 1e-9)
        assertEquals(976.0, g.toScreenX(21000.0), 1e-9)
        assertEquals(24.0, g.toScreenY(0.0), 1e-9)
    }

    @Test
    fun screenAndPageRoundTrip() {
        val g = a4()
        g.zoomBy(1.7, 300.0, 200.0)
        g.panBy(-40.0, -90.0)
        assertEquals(1234.5, g.toPageX(g.toScreenX(1234.5)), 1e-6)
        assertEquals(-77.0, g.toPageY(g.toScreenY(-77.0)), 1e-6)
    }

    @Test
    fun zoomKeepsThePointUnderTheFingerStill() {
        for (g in listOf(a4(), canvas())) {
            g.panBy(0.0, -50.0)
            val page = g.toPageY(300.0)
            g.zoomBy(1.5, 400.0, 300.0)
            assertEquals(page, g.toPageY(300.0), 1e-6)
        }
    }

    @Test
    fun zoomStopsAtItsLimits() {
        val g = canvas()
        g.zoomBy(1e6, 0.0, 0.0)
        assertEquals(px * 8, g.scale, 1e-12)
        g.zoomBy(1e-9, 0.0, 0.0)
        assertEquals(px * 0.1, g.scale, 1e-12)
        val f = a4()
        f.zoomBy(1e-9, 0.0, 0.0)
        assertTrue(f.scale > 0)
        // You can zoom out until the whole page is visible, and a little more.
        assertTrue(f.pageH * f.scale <= 700)
    }

    @Test
    fun fixedPageCannotBePannedOffTheScreen() {
        val g = a4()
        g.panBy(5000.0, 5000.0)
        assertEquals(24.0, g.toScreenX(0.0), 1e-9)
        assertEquals(24.0, g.toScreenY(0.0), 1e-9)
        g.panBy(-1e9, -1e9)
        assertEquals(700.0 - 24.0, g.toScreenY(29700.0), 1e-6)
        assertEquals(976.0, g.toScreenX(21000.0), 1e-6) // width still fits: only up and down
    }

    @Test
    fun smallPageIsCentred() {
        val g = a4()
        g.zoomBy(1e-9, 500.0, 350.0)
        assertEquals(1000.0 / 2, (g.toScreenX(0.0) + g.toScreenX(21000.0)) / 2, 1e-6)
        assertEquals(700.0 / 2, (g.toScreenY(0.0) + g.toScreenY(29700.0)) / 2, 1e-6)
    }

    @Test
    fun canvasPansFarButHasALimit() {
        val g = canvas()
        g.panBy(-1e15, 1e15)
        assertTrue(g.toPageX(0.0) <= PageGeometry.LIMIT + 1)
        assertTrue(g.toPageY(0.0) >= -PageGeometry.LIMIT - 1)
        assertTrue(g.toPageX(0.0) < 2.0e9) // far inside a 32-bit coordinate
    }

    @Test
    fun linesFallOnTheStepsInsideTheRange() {
        assertEquals(listOf(1600, 2400, 3200), PageGeometry.lines(1000.0, 3300.0, 800, 1600, 0.05))
        assertEquals(listOf(-800, 0, 800), PageGeometry.lines(-800.0, 800.0, 800, 0, 0.05)) // ends included
        assertEquals(emptyList<Int>(), PageGeometry.lines(10.0, 20.0, 800, 0, 0.05))
        assertEquals(emptyList<Int>(), PageGeometry.lines(5.0, 1.0, 800, 0, 0.05))
    }

    @Test
    fun linesVanishWhenTooDense() {
        assertTrue(PageGeometry.lines(0.0, 100000.0, 500, 0, 0.001).isEmpty()) // 0.5 px apart
        assertEquals(201, PageGeometry.lines(0.0, 100000.0, 500, 0, 0.05).size)
    }

    @Test
    fun linesOfAHugeRangeAreBoundedByTheScreen() {
        val g = canvas()
        val (l, t, r, b) = g.visible().toList()
        assertTrue(PageGeometry.lines(t, b, 500, 0, g.scale).size < 700 / 6 + 2)
        assertTrue(PageGeometry.lines(l, r, 500, 0, g.scale).size < 1000 / 6 + 2)
    }

    @Test
    fun panByReportsWhatTheEdgesWouldNotLetMove() {
        val g = a4() // fitted to the width, at the top: the sides have no room, the top is at its limit
        val (leftX, leftY) = g.panBy(30.0, 100.0)
        assertEquals(30.0, leftX, 1e-9) // no room sideways: all of it is left over
        assertEquals(100.0, leftY, 1e-9) // already at the top edge
        val ty = g.ty
        val (_, within) = g.panBy(0.0, -50.0) // down the page is allowed
        assertEquals(0.0, within, 1e-9)
        assertEquals(ty - 50.0, g.ty, 1e-9)
        val (_, partly) = g.panBy(0.0, 80.0) // 50 back up to the top, 30 beyond it
        assertEquals(30.0, partly, 1e-9)
    }

    @Test
    fun zoomIsAPercentageOfTheFittedPage() {
        val g = PageGeometry(21000, 29700, false, 0.1)
        assertEquals(100, g.percent()) // nothing laid out yet
        g.fit(1000, 1600)
        assertEquals(100, g.percent())
        g.zoomBy(2.0, 500.0, 800.0)
        assertEquals(200, g.percent())
        g.zoomBy(0.5, 500.0, 800.0)
        assertEquals(100, g.percent())
        g.zoomBy(0.1, 500.0, 800.0) // the zoom-out limit: a bit under the whole page in view
        assertEquals(80, g.percent())
    }

    @Test
    fun aSurfaceIsMeasuredAgainstThePageToFit() {
        // The surface is wide (two pages and a gap); 100% is still one page's width across the screen.
        val g = PageGeometry(50000, 29700, false, 0.1)
        g.fit(1000, 1600, 21000)
        assertEquals(100, g.percent(21000))
        assertTrue(g.percent(50000) != 100) // measured against the whole surface it would not be
    }

    @Test
    fun aCanvasIsAtOneHundredAtRealSize() {
        val g = PageGeometry(21000, 29700, true, 0.1)
        g.fit(1000, 1600)
        assertEquals(100, g.percent())
        g.zoomBy(3.0, 500.0, 800.0)
        assertEquals(300, g.percent())
    }

    @Test
    fun theViewCanBeSetToAZoomAndOffsetInOneGo() {
        val g = PageGeometry(21000, 60200, false, 0.1) // two pages tall
        g.fit(1000, 1600, 21000)
        val fit = g.fitScale(21000)
        g.zoomBy(0.8, 500.0, 800.0) // seen from afar
        assertTrue(g.percent(21000) < 90)
        // Into the first page: fitted, its top left at the corner.
        g.viewTo(fit, PageGeometry.MARGIN, PageGeometry.MARGIN)
        assertEquals(100, g.percent(21000))
        assertEquals(PageGeometry.MARGIN, g.toScreenY(0.0), 1e-6)
        // Into the last page, which is shorter than the screen: the surface's own end keeps it from sitting at the top.
        g.viewTo(fit, PageGeometry.MARGIN, PageGeometry.MARGIN - 30500 * fit)
        assertEquals(1600 - PageGeometry.MARGIN, g.toScreenY(60200.0), 1e-6) // its bottom at the margin
        // The zoom limit still applies.
        g.viewTo(1e-9, 0.0, 0.0)
        assertTrue(g.scale > 1e-9)
    }
}
