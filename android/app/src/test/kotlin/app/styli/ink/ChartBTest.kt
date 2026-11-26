package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartBTest {
    private fun th(vararg v: Double) = v.map { Math.round(it * 1000) }
    private fun chart(kind: ChartKind, vararg s: ChartSeries, cats: List<String> = emptyList()) =
        Chart("c", kind, 0f, 0f, 6000f, 4000f, categories = cats, series = s.toList())

    private val plot = Box(0.0, 0.0, 300.0, 100.0)

    @Test
    fun scatterPointsLandWhereTheirValuesSayAndIncompleteOnesAreNull() {
        val s = ChartSeries("A", 0, th(0.0, 5.0, 10.0), xs = th(0.0, 5.0, 10.0), missing = setOf(1))
        val c = chart(ChartKind.SCATTER, s)
        val (sx, sy) = ChartMaths.scatterScales(c)
        assertTrue(sx.lo <= 0.0 && sx.hi >= 10.0 && sy.lo <= 0.0 && sy.hi >= 10.0)
        val d = ChartMaths.dots(c, plot, ChartMaths.Scale(0.0, 10.0, 5.0), ChartMaths.Scale(0.0, 10.0, 5.0))[0]
        assertEquals(0.0, d[0]!!.x, 1e-9); assertEquals(100.0, d[0]!!.y, 1e-9) // the origin is the bottom left
        assertNull(d[1]) // a missing point
        assertEquals(300.0, d[2]!!.x, 1e-9); assertEquals(0.0, d[2]!!.y, 1e-9)
        assertEquals(0.0, d[0]!!.r, 0.0) // plain points have no size of their own
        assertNull(ChartSeries("B", 0, th(1.0, 2.0)).xAt(0)) // no x given: no point
    }

    @Test
    fun aBubblesAreaFollowsItsSize() {
        val s = ChartSeries("A", 0, th(1.0, 2.0), xs = th(1.0, 2.0), sizes = th(100.0, 25.0))
        val d = ChartMaths.dots(chart(ChartKind.BUBBLE, s), plot, ChartMaths.Scale(0.0, 2.0, 1.0), ChartMaths.Scale(0.0, 2.0, 1.0))[0]
        assertEquals(2.0, d[0]!!.r / d[1]!!.r, 1e-9) // a quarter of the size is half the radius
        assertTrue(d[0]!!.r > 0 && d[0]!!.r <= 100 * 0.09 + 1e-9)
    }

    @Test
    fun theScatterTrendlineIsTheFitWithItsRSquared() {
        val s = ChartSeries("A", 0, th(3.0, 5.0, 7.0, 9.0), xs = th(0.0, 1.0, 2.0, 3.0))
        val (m, b, r2) = ChartMaths.trendXY(s)!!
        assertEquals(2.0, m, 1e-9); assertEquals(3.0, b, 1e-9); assertEquals(1.0, r2, 1e-9)
        val noisy = ChartSeries("A", 0, th(1.0, 3.0, 2.0, 4.0), xs = th(0.0, 1.0, 2.0, 3.0))
        assertTrue(ChartMaths.trendXY(noisy)!!.third < 1.0)
        assertNull(ChartMaths.trendXY(ChartSeries("A", 0, th(1.0, 2.0), xs = th(4.0, 4.0)))) // no spread along x: no line
        val ends = ChartMaths.trendXYEnds(s, plot, ChartMaths.Scale(0.0, 3.0, 1.0), ChartMaths.Scale(0.0, 10.0, 5.0))!!
        assertEquals(0.0, ends.first.first, 1e-9); assertEquals(300.0, ends.second.first, 1e-9) // across the series' own span
    }

    @Test
    fun theHistogramCountsEverySampleOnceInEqualBins() {
        val s = ChartSeries("A", 0, th(1.0, 2.0, 2.0, 3.0, 3.0, 3.0, 4.0, 10.0))
        val h = ChartMaths.histogram(chart(ChartKind.HISTOGRAM, s).copy(bins = 3))!!
        assertEquals(3, h.counts.size); assertEquals(4, h.edges.size)
        assertEquals(8, h.counts.sum())
        assertEquals(1.0, h.edges.first(), 1e-9); assertEquals(10.0, h.edges.last(), 1e-9)
        assertEquals(listOf(6, 1, 1), h.counts) // [1,4) has six, [4,7) the 4, and the last bin takes the 10
        val w = ChartMaths.histogram(chart(ChartKind.HISTOGRAM, s).copy(binWidth = 2000))!!
        assertEquals(0.0, w.edges.first() % 2.0, 1e-9) // bins start on a multiple of the width
        assertEquals(8, w.counts.sum())
        assertEquals(5, w.counts.size) // 0 to 10 in twos: the 10 stays in the last bin, no sixth bin for it
        val auto = ChartMaths.histogram(chart(ChartKind.HISTOGRAM, s))!!
        assertEquals(8, auto.counts.sum())
        val step = auto.edges[1] - auto.edges[0]
        assertTrue(listOf(1.0, 2.0, 5.0, 10.0).any { Math.abs(step - it) < 1e-9 }) // round edges
        assertEquals(0.0, auto.edges.first() % step, 1e-9)
        assertEquals("0.33", ChartMaths.formatShort(1.0 / 3))
        assertEquals(1, ChartMaths.histogram(chart(ChartKind.HISTOGRAM, ChartSeries("A", 0, th(5.0, 5.0))))!!.counts.size) // all equal: one bin
        assertNull(ChartMaths.histogram(chart(ChartKind.HISTOGRAM, ChartSeries("A", 0, th(1.0), missing = setOf(0)))))
    }

    @Test
    fun aBoxPlotHasQuartilesWhiskersAndOutliers() {
        val s = ChartSeries("A", 0, th(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 50.0))
        val b = ChartMaths.boxStats(s)!!
        assertEquals(10, b.n)
        assertEquals(3.25, b.q1, 1e-9); assertEquals(5.5, b.median, 1e-9); assertEquals(7.75, b.q3, 1e-9)
        assertEquals(1.0, b.lowWhisker, 1e-9); assertEquals(9.0, b.highWhisker, 1e-9) // 50 is past the fence
        assertEquals(listOf(50.0), b.outliers)
        assertEquals(9.5, b.mean, 1e-9)
        assertEquals(1, ChartMaths.boxStats(ChartSeries("A", 0, th(4.0)))!!.n) // one sample is a flat box
        assertNull(ChartMaths.boxStats(ChartSeries("A", 0, emptyList())))
        val c = chart(ChartKind.BOXPLOT, s, ChartSeries("B", 0, th(10.0, 20.0, 30.0)))
        val shapes = ChartMaths.boxes(c, plot, ChartMaths.boxScale(c))
        assertEquals(2, shapes.size)
        assertTrue(shapes[0]!!.q3 < shapes[0]!!.q1 && shapes[0]!!.median < shapes[0]!!.q1) // higher is further up the screen
        assertEquals(75.0, shapes[0]!!.x - 0.0, 75.0 + 1e-9) // the first box is in the first half of the plot
        assertTrue(ChartMaths.boxScale(c).hi >= 50.0) // the axis reaches the outlier
    }

    @Test
    fun heatCellsShadeFromTheSmallestToTheLargest() {
        val c = chart(ChartKind.HEATMAP, ChartSeries("Mon", 0, th(1.0, 5.0)), ChartSeries("Tue", 0, th(3.0, 9.0)), cats = listOf("am", "pm"))
        val cells = ChartMaths.heatCells(c, plot)
        assertEquals(4, cells.size)
        assertEquals(0.0, cells.first { it.row == 0 && it.col == 0 }.t, 1e-9) // 1 is the smallest
        assertEquals(1.0, cells.first { it.row == 1 && it.col == 1 }.t, 1e-9) // 9 the largest
        assertEquals(0.25, cells.first { it.row == 0 && it.col == 1 }.t, 1e-9) // (3 - 1) / 8
        val a = cells.first { it.row == 0 && it.col == 0 }.box; val b = cells.first { it.row == 0 && it.col == 1 }.box
        assertTrue(a.r < b.l) // columns run left to right
        assertTrue(cells.all { it.box.l >= 0 && it.box.r <= 300 && it.box.t >= 0 && it.box.b <= 100 })
        assertEquals(0xFF808080.toInt(), ChartMaths.lerp(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5).let { (it and 0xFF000000.toInt()) or 0x808080 })
        assertEquals(0xFF0D47A1.toInt(), ChartMaths.heatColour(c, 1.0))
        assertEquals(0xFF112233.toInt(), ChartMaths.heatColour(c.copy(heatHigh = 0xFF112233.toInt()), 1.0))
        assertNull(chart(ChartKind.HEATMAP, ChartSeries("A", 0, th(1.0), missing = setOf(0)), cats = listOf("x")).let { ChartMaths.heatCells(it, plot).first().value })
    }

    @Test
    fun treemapTilesFillTheBoxInProportionAndGroupsNest() {
        val c = chart(ChartKind.TREEMAP, ChartSeries("A", 0, th(6.0, 3.0, 2.0, 1.0, 0.0)), cats = listOf("a", "b", "c", "d", "zero"))
        val tiles = ChartMaths.treemap(c, plot)
        assertEquals(4, tiles.size) // a zero size has no tile
        val area = { t: ChartMaths.Tile -> (t.box.r - t.box.l) * (t.box.b - t.box.t) }
        assertEquals(300.0 * 100.0, tiles.sumOf(area), 1e-6)
        assertEquals(6.0 / 12 * 30000, area(tiles.first { it.index == 0 }), 1e-6)
        assertEquals(1.0 / 12 * 30000, area(tiles.first { it.index == 3 }), 1e-6)
        assertTrue(tiles.all { it.box.l >= -1e-9 && it.box.r <= 300 + 1e-9 && it.box.t >= -1e-9 && it.box.b <= 100 + 1e-9 })
        for (i in tiles.indices) for (j in i + 1 until tiles.size) { // no two overlap
            val a = tiles[i].box; val b = tiles[j].box
            assertFalse(a.l < b.r - 1e-6 && b.l < a.r - 1e-6 && a.t < b.b - 1e-6 && b.t < a.b - 1e-6)
        }
        val g = ChartMaths.treemap(c.copy(groups = listOf("x", "x", "y", "y", "y")), plot)
        assertEquals(4, g.size)
        assertEquals(setOf(0, 1), g.map { it.group }.toSet())
        val x = g.filter { it.group == g.first { t -> t.index == 0 }.group }
        assertEquals(setOf(0, 1), x.map { it.index }.toSet()) // members stay together
        val inGroup = { grp: Int -> g.filter { it.group == grp }.map { it.box } }
        val gx0 = inGroup(g.first { it.index == 0 }.group); val gy = inGroup(g.first { it.index == 2 }.group)
        assertTrue(gx0.maxOf { it.r } <= gy.minOf { it.l } + 1e-6 || gy.maxOf { it.r } <= gx0.minOf { it.l } + 1e-6 || gx0.maxOf { it.b } <= gy.minOf { it.t } + 1e-6 || gy.maxOf { it.b } <= gx0.minOf { it.t } + 1e-6) // the groups are apart
        assertEquals(listOf("x", "y"), ChartMaths.legendNames(c.copy(groups = listOf("x", "x", "y", "y", "y"))))
        assertTrue(ChartMaths.treemap(chart(ChartKind.TREEMAP), plot).isEmpty())
    }

    @Test
    fun ganttBarsRunFromStartToEndTopTaskFirstAndShowProgress() {
        val c = chart(ChartKind.GANTT, ChartSeries("Start", 0, th(0.0, 5.0, 10.0)), ChartSeries("End", 0, th(5.0, 10.0, 20.0)), ChartSeries("Done %", 0, th(100.0, 50.0, 0.0)), cats = listOf("a", "b", "c")).copy(marker = 7000)
        val sc = ChartMaths.ganttScale(c)
        assertTrue(sc.lo <= 0.0 && sc.hi >= 20.0)
        val bars = ChartMaths.gantt(c, plot, ChartMaths.Scale(0.0, 20.0, 5.0))
        assertEquals(0.0, bars[0]!!.box.l, 1e-9); assertEquals(75.0, bars[0]!!.box.r, 1e-9)
        assertEquals(75.0, bars[1]!!.box.l, 1e-9); assertEquals(150.0, bars[1]!!.box.r, 1e-9)
        assertTrue(bars[0]!!.box.b <= bars[1]!!.box.t) // the first task is above the second
        assertEquals(1.0, bars[0]!!.done, 0.0); assertEquals(0.5, bars[1]!!.done, 0.0)
        val rev = chart(ChartKind.GANTT, ChartSeries("S", 0, th(10.0)), ChartSeries("E", 0, th(5.0)), cats = listOf("x"))
        assertTrue(ChartMaths.gantt(rev, plot, ChartMaths.Scale(0.0, 20.0, 5.0))[0]!!.box.r > ChartMaths.gantt(rev, plot, ChartMaths.Scale(0.0, 20.0, 5.0))[0]!!.box.l) // end before start is still a bar
        assertNull(ChartMaths.gantt(chart(ChartKind.GANTT, ChartSeries("S", 0, th(1.0), missing = setOf(0)), ChartSeries("E", 0, th(2.0)), cats = listOf("x")), plot, sc)[0])
        assertTrue(ChartMaths.ganttScale(c.copy(marker = 99000)).hi >= 99.0) // the marker is on the axis
    }

    @Test
    fun datesAreDayNumbersAndRoundTrip() {
        val d = ChartMaths.parseDate("2026-12-01")!!
        assertEquals("2026-12-01", ChartMaths.dateText(d))
        assertEquals("Dec 1", ChartMaths.dateLabel(d))
        assertEquals("Dec 2", ChartMaths.dateLabel(d + 1.4))
        assertNull(ChartMaths.parseDate("tomorrow"))
        assertNull(ChartMaths.parseDate("2026-13-01"))
        assertEquals(0.0, ChartMaths.parseDate("1970-01-01")!!, 0.0)
    }

    @Test
    fun theLayoutReservesRoomForEveryNewKindWithinTheBox() {
        for (k in listOf(ChartKind.SCATTER, ChartKind.BUBBLE, ChartKind.HISTOGRAM, ChartKind.BOXPLOT, ChartKind.HEATMAP, ChartKind.TREEMAP, ChartKind.GANTT)) {
            for (d3 in listOf(false, true)) {
                val l = ChartMaths.layout(chart(k, ChartSeries("A", 0, th(1.0)), cats = listOf("a long name")).copy(title = "T", threeD = d3)).plot
                assertTrue("$k", l.l >= 0 && l.t >= 0 && l.r <= 6000.0 && l.b <= 4000.0 && l.r > l.l && l.b > l.t)
            }
        }
        val gantt = chart(ChartKind.GANTT, ChartSeries("S", 0, th(1.0)), cats = listOf("a very long task name"))
        assertTrue(ChartMaths.layout(gantt).plot.l > ChartMaths.layout(gantt.copy(categories = listOf("a"))).plot.l) // room for the names down the side
    }
}
