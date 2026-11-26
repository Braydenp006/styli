package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartTest {
    private fun series(name: String, vararg v: Double, missing: Set<Int> = emptySet()) =
        ChartSeries(name, Chart.colourFor(0), v.map { Math.round(it * 1000) }, missing)

    private fun chart(kind: ChartKind = ChartKind.BAR, vararg s: ChartSeries, stacked: Boolean = false) =
        Chart("c", kind, 100f, 200f, 6000f, 4000f, "Sales", listOf("Jan", "Feb", "Mar"), s.toList(), stacked = stacked)

    @Test
    fun valuesAreThousandthsAndMissingOnesAreNull() {
        val s = series("A", 12.5, 3.0, 9.0, missing = setOf(1))
        assertEquals(12500L, s.values[0])
        assertEquals(12.5, s.at(0)!!, 1e-9)
        assertNull(s.at(1))
        assertNull(s.at(7))
    }

    @Test
    fun niceScalesUseRoundSteps() {
        val a = ChartMaths.niceScale(0.0, 87.0)
        assertEquals(0.0, a.lo, 0.0); assertEquals(100.0, a.hi, 0.0); assertEquals(20.0, a.step, 0.0)
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), a.ticks)
        val b = ChartMaths.niceScale(3.2, 9.7, zero = false)
        assertTrue(b.lo <= 3.2 && b.hi >= 9.7)
        assertEquals(2.0, b.step, 0.0); assertEquals(2.0, b.lo, 0.0); assertEquals(10.0, b.hi, 0.0)
        val z = ChartMaths.niceScale(5.0, 9.0) // bars include zero
        assertEquals(0.0, z.lo, 0.0)
        val same = ChartMaths.niceScale(4.0, 4.0, zero = false) // all the same: room round it
        assertTrue(same.lo < 4.0 && same.hi > 4.0)
        val neg = ChartMaths.niceScale(-30.0, 12.0)
        assertTrue(neg.lo <= -30.0 && neg.hi >= 12.0)
        assertEquals(0.0, neg.ticks.minBy { Math.abs(it) }, 1e-9) // zero is a tick
        val small = ChartMaths.niceScale(0.0, 0.0042)
        assertEquals(0.001, small.step, 1e-12)
    }

    @Test
    fun theRangeCountsStackedBarsAsSums() {
        val c = chart(ChartKind.BAR, series("A", 3.0, -2.0, 1.0), series("B", 4.0, -1.0, 2.0), stacked = true)
        assertEquals(-3.0 to 7.0, ChartMaths.valueRange(c))
        assertEquals(-2.0 to 4.0, ChartMaths.valueRange(c.copy(stacked = false)))
        assertNull(ChartMaths.valueRange(chart(ChartKind.BAR)))
        assertEquals(0.0 to 1.0, ChartMaths.scaleFor(chart(ChartKind.BAR)).let { it.lo to it.hi }) // no data: a plain 0 to 1 scale
    }

    @Test
    fun theLayoutKeepsEverythingInsideTheBoxAndMakesRoomForWhatIsShown() {
        val full = chart(ChartKind.BAR, series("A", 1.0, 2.0, 3.0)).copy(xLabel = "Month", yLabel = "Units")
        val l = ChartMaths.layout(full)
        val p = l.plot
        assertTrue(p.l > 0 && p.t > 0 && p.r < full.width && p.b < full.height)
        assertTrue(l.title!!.b <= p.t + 1e-6)
        assertTrue(l.legend!!.t >= p.b - 1e-6 && l.xLabel != null && l.yLabel != null)
        val bare = full.copy(title = "", xLabel = "", yLabel = "", hideLegend = true)
        val q = ChartMaths.layout(bare).plot
        assertTrue(q.r - q.l > p.r - p.l && q.b - q.t > p.b - p.t) // nothing to show, more room to plot
        assertNull(ChartMaths.layout(bare).title)
        assertNull(ChartMaths.layout(bare).legend)
    }

    @Test
    fun barsSitSideBySideOnTheZeroLineAndSkipMissingValues() {
        val c = chart(ChartKind.BAR, series("A", 10.0, 0.0, -5.0), series("B", 5.0, 5.0, 5.0, missing = setOf(2)))
        val plot = Box(0.0, 0.0, 300.0, 100.0)
        val scale = ChartMaths.Scale(-10.0, 10.0, 5.0) // zero is the middle, y 50
        val bars = ChartMaths.bars(c, plot, scale)
        assertNull(bars[1][2]) // no value, no bar
        val a0 = bars[0][0]!!; val b0 = bars[1][0]!!
        assertEquals(0.0, a0.t, 1e-9); assertEquals(50.0, a0.b, 1e-9) // 10 is the top of the plot
        assertTrue(a0.r <= b0.l + 1e-9) // side by side, not overlapping
        assertEquals(25.0, b0.t, 1e-9) // 5 is a quarter of the way up from the middle
        val neg = bars[0][2]!!
        assertEquals(50.0, neg.t, 1e-9); assertEquals(75.0, neg.b, 1e-9) // -5 hangs down from zero
        assertEquals(50.0, bars[0][1]!!.t, 1e-9); assertEquals(50.0, bars[0][1]!!.b, 1e-9) // zero is a flat bar
        // each category has its own third of the width
        assertTrue(a0.l >= 0.0 && a0.r <= 100.0 && bars[0][1]!!.l >= 100.0 && bars[0][2]!!.r <= 300.0)
    }

    @Test
    fun stackedBarsGrowFromZeroPositivesUpAndNegativesDown() {
        val c = chart(ChartKind.BAR, series("A", 3.0, -2.0, 1.0), series("B", 4.0, -1.0, 2.0), stacked = true)
        val plot = Box(0.0, 0.0, 300.0, 100.0)
        val scale = ChartMaths.Scale(-10.0, 10.0, 5.0)
        val bars = ChartMaths.bars(c, plot, scale)
        // category 0: A from 0 to 3, B from 3 to 7, one bar wide
        assertEquals(bars[0][0]!!.l, bars[1][0]!!.l, 1e-9)
        assertEquals(bars[0][0]!!.t, bars[1][0]!!.b, 1e-9)
        assertEquals(50 - 7 * 5.0, bars[1][0]!!.t, 1e-9)
        // category 1: both negative, stacked downwards
        assertEquals(bars[0][1]!!.b, bars[1][1]!!.t, 1e-9)
        assertEquals(50 + 3 * 5.0, bars[1][1]!!.b, 1e-9)
    }

    @Test
    fun lineNodesAreInTheMiddleOfTheirSlotsAndGapsAreNull() {
        val c = chart(ChartKind.LINE, series("A", 0.0, 5.0, 10.0, missing = setOf(1)))
        val pts = ChartMaths.linePoints(c, Box(0.0, 0.0, 300.0, 100.0), ChartMaths.Scale(0.0, 10.0, 5.0))
        assertEquals(50.0, pts[0][0]!!.first, 1e-9); assertEquals(100.0, pts[0][0]!!.second, 1e-9)
        assertNull(pts[0][1])
        assertEquals(250.0, pts[0][2]!!.first, 1e-9); assertEquals(0.0, pts[0][2]!!.second, 1e-9)
    }

    @Test
    fun aPieSharesTheCirclePositiveValuesOnlyStartingAtTheTop() {
        val c = chart(ChartKind.PIE, series("A", 1.0, 3.0, -4.0))
        val sl = ChartMaths.pie(c)
        assertEquals(2, sl.size) // the negative one has no slice
        assertEquals(0.25, sl[0].share, 1e-9); assertEquals(0.75, sl[1].share, 1e-9)
        assertEquals(-Math.PI / 2, sl[0].start, 1e-9)
        assertEquals(sl[0].start + sl[0].sweep, sl[1].start, 1e-9)
        assertEquals(2 * Math.PI, sl.sumOf { it.sweep }, 1e-9)
        assertTrue(ChartMaths.pie(chart(ChartKind.PIE, series("A", 0.0, 0.0, 0.0))).isEmpty())
        assertTrue(ChartMaths.pie(chart(ChartKind.PIE)).isEmpty())
    }

    @Test
    fun numbersShowWithoutTrailingZeros() {
        assertEquals("12", ChartMaths.format(12.0))
        assertEquals("12.5", ChartMaths.format(12.5))
        assertEquals("0.125", ChartMaths.format(0.1254))
        assertEquals("-3", ChartMaths.format(-3.0))
    }

    @Test
    fun aChartOnAPageAddsGoesOnTopLogsAndUndoes() {
        val m = PageModel()
        val s = m.add(app.styli.ink.proto.Tool.TOOL_PEN, 0, 40, listOf(Pt(0f, 0f, 0, 1f), Pt(100f, 0f, 5, 1f))).id
        val c = m.addChart { chart(ChartKind.BAR, series("A", 1.0, 2.0, 3.0)).copy(id = it) }
        assertEquals(listOf(s, c.id), m.order)
        assertNotNull(m.chartOf(c.id))
        assertTrue(m.log.any { it is Ev.ChartSet && it.chart.id == c.id })
        assertTrue(m.log.none { it is Ev.ZMoved })
        m.log.clear()
        assertTrue(m.undo())
        assertNull(m.chartOf(c.id))
        assertEquals(listOf<Ev>(Ev.ChartRemoved(c.id)), m.log)
        assertTrue(m.redo())
        assertNotNull(m.chartOf(c.id))
    }

    @Test
    fun movingResizingAndTurningScaleTheTextToo() {
        val m = PageModel()
        val c = m.addChart { chart(ChartKind.BAR, series("A", 1.0)).copy(id = it, fontSize = 200f) }
        assertTrue(m.transform(setOf(c.id), Affine(2.0, 0.0, 0.0, 2.0, 100.0, 0.0)))
        val d = m.chartOf(c.id)!!
        assertEquals(12000f, d.width, 0.01f); assertEquals(8000f, d.height, 0.01f)
        assertEquals(400f, d.fontSize, 0.01f)
        assertEquals(2 * c.x + 100, d.x, 0.01f)
        assertTrue(m.undo())
        assertEquals(c, m.chartOf(c.id))
    }

    @Test
    fun anEditIsOneUndoStepAndDeleteTakesTheChartOffTheLayers() {
        val m = PageModel()
        val c = m.addChart { chart(ChartKind.LINE, series("A", 1.0, 2.0, 3.0)).copy(id = it) }
        assertTrue(m.setChart(c.copy(title = "New")))
        assertEquals("New", m.chartOf(c.id)!!.title)
        assertTrue(m.undo())
        assertEquals("Sales", m.chartOf(c.id)!!.title)
        assertFalse(m.setChart(c.copy(id = "nope")))
        assertTrue(m.delete(setOf(c.id)))
        assertTrue(m.charts.isEmpty() && m.order.isEmpty())
        assertTrue(m.log.last() is Ev.ChartRemoved)
        assertTrue(m.undo())
        assertEquals(listOf(c.id), m.order)
    }

    @Test
    fun theEraserNeverTouchesAChart() {
        val m = PageModel()
        val c = m.addChart { chart(ChartKind.BAR, series("A", 1.0)).copy(id = it) }
        val at = c.toPage(c.width / 2, c.height / 2)
        m.erase(at.first, at.second, 5000f, partial = false)
        m.endErase()
        assertEquals(listOf(c), m.charts)
    }

    @Test
    fun aLoopOrATapSelectsAChartAndItsClipKeepsItWithNewIds() {
        val c = chart(ChartKind.BAR, series("A", 1.0, 2.0, 3.0)).copy(x = 1000f, y = 1000f, width = 2000f, height = 1000f)
        val round = floatArrayOf(900f, 900f, 3100f, 900f, 3100f, 2100f, 900f, 2100f)
        assertEquals(setOf("c"), LassoGeometry.selectedCharts(listOf(c), round))
        assertTrue(LassoGeometry.selectedCharts(listOf(c), floatArrayOf(900f, 900f, 1900f, 900f, 1900f, 2100f, 900f, 2100f)).isEmpty())
        assertTrue(c.contains(2000f, 1500f)); assertFalse(c.contains(500f, 500f))
        val m = PageModel()
        val made = m.addChart { c.copy(id = it) }
        val clip = Clip.of("k", m, setOf(made.id)) { id -> m.chartOf(id)?.corners()?.let { k -> Box(k.minOf { it.first }.toDouble(), k.minOf { it.second }.toDouble(), k.maxOf { it.first }.toDouble(), k.maxOf { it.second }.toDouble()) } }!!
        assertEquals(1, clip.charts.size)
        assertEquals(0f, clip.charts[0].x, 0.01f)
        val n = PageModel()
        val ids = n.paste(clip, 700f, 800f)
        val q = n.charts.single()
        assertEquals(setOf(q.id), ids)
        assertTrue(q.id != made.id)
        assertEquals(made.series, q.series); assertEquals(made.categories, q.categories)
        assertEquals(700f, q.x, 0.01f); assertEquals(800f, q.y, 0.01f)
        assertEquals(made.width, q.width, 0.01f)
    }
}
