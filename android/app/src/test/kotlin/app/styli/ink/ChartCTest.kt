package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartCTest {
    private fun th(vararg v: Double) = v.map { Math.round(it * 1000) }
    private fun chart(kind: ChartKind, cats: List<String>, vararg s: ChartSeries) = Chart("c", kind, 0f, 0f, 6000f, 4000f, categories = cats, series = s.toList(), fontSize = 5f) // small text, as the plots here are only 300 wide
    private val plot = Box(0.0, 0.0, 300.0, 100.0)

    // ---- Sankey ----

    private fun flows(vararg f: Triple<String, String, Double>) =
        chart(ChartKind.SANKEY, f.map { it.first }, ChartSeries("Flow", 0, th(*f.map { it.third }.toDoubleArray()))).copy(groups = f.map { it.second })

    @Test
    fun sankeyNodesSitInColumnsByDistanceAndAreAsTallAsTheirBusierSide() {
        val c = flows(Triple("Wages", "Budget", 100.0), Triple("Gifts", "Budget", 20.0), Triple("Budget", "Rent", 60.0), Triple("Budget", "Food", 40.0), Triple("Budget", "Savings", 20.0))
        val s = ChartMaths.sankey(c, plot)!!
        val by = s.nodes.associateBy { it.name }
        assertEquals(0, by.getValue("Wages").level); assertEquals(1, by.getValue("Budget").level); assertEquals(2, by.getValue("Rent").level)
        assertEquals(120.0, by.getValue("Budget").total, 1e-9) // 120 in, 120 out
        assertTrue("columns", by.getValue("Wages").box.r <= by.getValue("Budget").box.l && by.getValue("Budget").box.r <= by.getValue("Rent").box.l) // columns run left to right
        val unit = (by.getValue("Budget").box.b - by.getValue("Budget").box.t) / 120.0
        assertEquals("height", 60.0 * unit, by.getValue("Rent").box.b - by.getValue("Rent").box.t, 1e-6) // a node's height follows its flow
        // the ribbons leaving a node fill it from the top down, and never leave it
        for (n in s.nodes) {
            val out = s.links.filter { s.nodes[it.from] === n }
            assertTrue("out ${n.name}", out.sumOf { it.thickness } <= n.box.b - n.box.t + 1e-6)
            for (l in out) assertTrue("y0 ${n.name}", l.y0 >= n.box.t - 1e-6 && l.y0 + l.thickness <= n.box.b + 1e-6)
            for (l in s.links.filter { s.nodes[it.to] === n }) assertTrue(l.y1 >= n.box.t - 1e-6 && l.y1 + l.thickness <= n.box.b + 1e-6)
        }
        assertTrue("inside ${s.nodes.map { it.box }}", s.nodes.all { it.box.t >= -1e-6 && it.box.b <= 100 + 1e-6 && it.box.l >= -1e-6 && it.box.r <= 300 + 1e-6 })
        // nodes in a column do not overlap
        for (k in 0..2) { val col = s.nodes.filter { it.level == k }.sortedBy { it.box.t }; for (i in 0 until col.size - 1) assertTrue(col[i].box.b <= col[i + 1].box.t + 1e-6) }
        assertEquals(5, s.links.size); assertEquals(100.0, s.links[0].value, 0.0)
    }

    @Test
    fun sankeySkipsBadRowsAndSurvivesALoop() {
        val c = flows(Triple("A", "B", 5.0), Triple("B", "C", 5.0), Triple("C", "A", 3.0), Triple("X", "X", 9.0), Triple("", "B", 2.0), Triple("B", "D", -4.0), Triple("B", "E", 0.0))
        val s = ChartMaths.sankey(c, plot)!!
        assertEquals(3, s.links.size) // the loop's three flows only
        assertTrue(s.nodes.all { it.box.b.isFinite() && it.box.t.isFinite() })
        assertNull(ChartMaths.sankey(flows(Triple("A", "A", 1.0)), plot))
        assertNull(ChartMaths.sankey(chart(ChartKind.SANKEY, emptyList()), plot))
        val one = ChartMaths.sankey(flows(Triple("A", "B", 1.0)), plot)!!
        assertTrue(one.nodes[0].box.r <= one.nodes[1].box.l)
    }

    // ---- sunburst ----

    @Test
    fun sunburstRingsNestAndShareTheCircleInProportion() {
        val c = chart(ChartKind.SUNBURST, listOf("Food/Fruit/Apple", "Food/Fruit/Pear", "Food/Veg", "Drink", "Drink/Tea"),
            ChartSeries("v", 0, th(3.0, 1.0, 4.0, 2.0, 5.0)))
        val arcs = ChartMaths.sunburst(c)
        val by = arcs.associateBy { it.path.joinToString("/") }
        assertEquals(8.0, by.getValue("Food").value, 1e-9) // 3 + 1 + 4
        assertEquals(5.0, by.getValue("Drink").value, 1e-9) // its tea (5) is more than its own 2
        assertEquals(0, by.getValue("Food").depth); assertEquals(1, by.getValue("Food/Fruit").depth); assertEquals(2, by.getValue("Food/Fruit/Apple").depth)
        assertEquals(2 * Math.PI, arcs.filter { it.depth == 0 }.sumOf { it.sweep }, 1e-9)
        assertEquals(8.0 / 13 * 2 * Math.PI, by.getValue("Food").sweep, 1e-9)
        // children lie inside their parent
        val food = by.getValue("Food"); val fruit = by.getValue("Food/Fruit"); val apple = by.getValue("Food/Fruit/Apple")
        assertTrue(fruit.start >= food.start - 1e-9 && fruit.start + fruit.sweep <= food.start + food.sweep + 1e-9)
        assertTrue(apple.start >= fruit.start - 1e-9 && apple.start + apple.sweep <= fruit.start + fruit.sweep + 1e-9)
        assertEquals(by.getValue("Food").top, by.getValue("Food/Fruit/Apple").top) // a ring segment is coloured as the top item it belongs to
        assertTrue(by.getValue("Drink").top != by.getValue("Food").top)
        assertEquals(-Math.PI / 2, food.start, 1e-9) // starts at the top
        assertTrue(ChartMaths.sunburst(chart(ChartKind.SUNBURST, listOf("/", " > "), ChartSeries("v", 0, th(1.0, 2.0)))).isEmpty()) // no real path
        assertEquals(listOf("A", "B"), ChartMaths.sunburst(chart(ChartKind.SUNBURST, listOf("A > B"), ChartSeries("v", 0, th(1.0)))).map { it.path.last() }) // ">" splits too
    }

    // ---- timeline ----

    @Test
    fun timelineEventsAreOrderedByWhenAndLabelsDoNotOverlapInALane() {
        val c = chart(ChartKind.TIMELINE, listOf("Start", "Close one", "Close two", "Close three", "End", "Undated"),
            ChartSeries("When", 0, th(0.0, 50.0, 52.0, 54.0, 100.0, 0.0), missing = setOf(5)))
        val sc = ChartMaths.timelineScale(c)
        assertTrue(sc.lo <= 0.0 && sc.hi >= 100.0)
        val ev = ChartMaths.timeline(c, plot, sc) { 60.0 }
        assertNull(ev[5]) // no date: no event
        assertTrue(ev[0]!!.x < ev[1]!!.x && ev[1]!!.x < ev[4]!!.x)
        // labels that would overlap are put in different lanes or on different sides
        val near = listOf(ev[1]!!, ev[2]!!, ev[3]!!)
        for (a in near) for (b in near) if (a !== b && a.above == b.above) assertTrue(a.lane != b.lane)
        // in every lane on a side, label boxes (60 wide) are apart
        for (side in listOf(true, false)) for (lane in 0..5) {
            val inLane = ev.filterNotNull().filter { it.above == side && it.lane == lane }.sortedBy { it.x }
            for (i in 0 until inLane.size - 1) assertTrue(inLane[i].x + 30 <= inLane[i + 1].x - 30 + 1e-6)
        }
        assertTrue(ev.filterNotNull().all { it.x in 0.0..300.0 })
        assertEquals(setOf(true, false), ev.filterNotNull().map { it.above }.toSet()) // both sides are used
    }

    @Test
    fun aTimelineSpanHasAnEnd() {
        val c = chart(ChartKind.TIMELINE, listOf("War", "Peace"), ChartSeries("Start", 0, th(10.0, 60.0)), ChartSeries("End", 0, th(40.0, 0.0), missing = setOf(1)))
        val ev = ChartMaths.timeline(c, plot, ChartMaths.Scale(0.0, 100.0, 20.0)) { 20.0 }
        assertEquals(90.0, ev[0]!!.x2!! - ev[0]!!.x, 1e-9) // 30 of 100 across 300 wide is 90
        assertNull(ev[1]!!.x2)
    }

    // ---- calendar ----

    @Test
    fun theCalendarFillsWholeWeeksMondayFirstAndShadesByValue() {
        val dates = listOf("2026-12-01", "2026-12-02", "2026-12-10", "2026-12-20") // Tuesday, Wednesday, Thursday, Sunday
        val c = chart(ChartKind.CALENDAR, dates, ChartSeries("v", 0, th(1.0, 5.0, 3.0, 9.0)))
        val cal = ChartMaths.calendar(c, plot)!!
        assertEquals(3, cal.weeks) // Nov 30 (Monday) to Dec 20 (Sunday)
        assertEquals(21, cal.days.size)
        val tue = cal.days.first { it.value == 1.0 }
        assertEquals(0, tue.col); assertEquals(1, tue.row) // Tuesday is the second row
        assertEquals(ChartMaths.parseDate("2026-12-01")!!.toLong(), tue.day)
        val sun = cal.days.first { it.value == 9.0 }
        assertEquals(2, sun.col); assertEquals(6, sun.row) // Sunday is the last row, in the last week
        assertEquals(0.0, tue.t, 1e-9); assertEquals(1.0, sun.t, 1e-9); assertEquals(0.5, cal.days.first { it.value == 5.0 }.t, 1e-9)
        assertNull(cal.days.first { it.value == null }.value) // days between have no value
        assertEquals(17, cal.days.count { it.value == null })
        assertTrue(cal.days.all { it.box.l >= 0 && it.box.r <= 300 + 1e-6 && it.box.t >= 0 && it.box.b <= 100 + 1e-6 })
        assertTrue(cal.days.first { it.col == 0 && it.row == 0 }.box.r <= cal.days.first { it.col == 1 && it.row == 0 }.box.l) // weeks run left to right
        assertEquals(listOf(0 to "Dec"), cal.months.map { it.col to it.name }.take(1)) // December starts in the first week, so it names it, not November
        assertNull(ChartMaths.calendar(chart(ChartKind.CALENDAR, listOf("soon"), ChartSeries("v", 0, th(1.0))), plot))
        val sameDay = ChartMaths.calendar(chart(ChartKind.CALENDAR, listOf("2026-12-01", "2026-12-01"), ChartSeries("v", 0, th(2.0, 3.0))), plot)!!
        assertEquals(5.0, sameDay.days.first { it.value != null }.value!!, 1e-9) // two rows for one day add up
    }

    // ---- error bars ----

    @Test
    fun errorBarsSitOnTheValueAndTheAxisReachesThem() {
        val s = ChartSeries("A", 0, th(10.0, 20.0, 30.0), errors = th(2.0, 0.0, 5.0))
        val c = chart(ChartKind.BAR, listOf("a", "b", "c"), s)
        assertEquals(8.0 to 35.0, ChartMaths.valueRange(c)) // the first bar's 10 - 2 to the last bar's 30 + 5
        assertEquals(0.0, ChartMaths.scaleFor(c).lo, 0.0) // and bars always reach down to zero
        val sc = ChartMaths.Scale(0.0, 40.0, 10.0)
        val bars = ChartMaths.bars(c, plot, sc)
        val e = ChartMaths.errorBars(c, plot, sc)[0]
        assertEquals((bars[0][0]!!.l + bars[0][0]!!.r) / 2, e[0]!!.x, 1e-9) // on the middle of the bar
        assertEquals(100.0 - 12.0 / 40 * 100, e[0]!!.top, 1e-9); assertEquals(100.0 - 8.0 / 40 * 100, e[0]!!.bottom, 1e-9)
        assertNull(e[1]) // zero error: none
        assertTrue(e[2]!!.top < e[2]!!.bottom) // higher is further up the screen
        val line = ChartMaths.errorBars(c.copy(kind = ChartKind.LINE), plot, sc)[0]
        assertEquals(50.0, line[0]!!.x, 1e-9) // a line's point is the middle of the slot
        assertTrue(ChartMaths.errorBars(c.copy(stacked = true), plot, sc)[0].isEmpty()) // stacked bars have none
        assertTrue(ChartMaths.errorBars(chart(ChartKind.BAR, listOf("a"), ChartSeries("B", 0, th(1.0))), plot, sc)[0].isEmpty()) // none given
        val sc2 = chart(ChartKind.SCATTER, emptyList(), ChartSeries("P", 0, th(5.0), xs = th(1.0), errors = th(4.0)))
        assertTrue(ChartMaths.scatterScales(sc2).second.lo < 1.0) // the axis reaches 5 - 4
    }

    // ---- function graphs ----

    @Test
    fun axisTicksAreRoundNumbersInsideTheRange() {
        assertEquals(listOf(-10.0, -5.0, 0.0, 5.0, 10.0), ChartMaths.axisTicks(-10.0, 10.0, 5))
        val t = ChartMaths.axisTicks(-6.283, 6.283)
        assertTrue(t.all { it >= -6.283 && it <= 6.283 } && t.contains(0.0) && t.size in 4..9)
        assertEquals(listOf(3.0), ChartMaths.axisTicks(3.0, 3.0))
        assertTrue(ChartMaths.axisTicks(0.001, 0.005).all { it in 0.001..0.005 })
    }

    @Test
    fun aFunctionIsSampledAcrossItsRangeAndTheYRangeFitsTheCurve() {
        val c = chart(ChartKind.FUNCTION, emptyList(), ChartSeries("f", 0, emptyList(), expr = "x^2")).copy(xMin = -3000, xMax = 3000)
        val smp = ChartMaths.functionSamples(c, 6)[0]
        assertEquals(7, smp.size); assertEquals(-3.0, smp[0].first, 1e-9); assertEquals(9.0, smp[0].second, 1e-9); assertEquals(0.0, smp[3].second, 1e-9)
        val (lo, hi) = ChartMaths.functionY(c)
        assertTrue(lo <= 0.0 && hi >= 8.0 && hi < 15.0) // about 0 to 9 with a margin
        assertEquals(-10.0 to 10.0, ChartMaths.functionX(c.copy(xMin = 0, xMax = 0))) // no range: -10 to 10
        assertEquals(-2.0 to 5.0, ChartMaths.functionY(c.copy(yMin = -2000, yMax = 5000))) // a set range is kept
        assertEquals(-1.0 to 1.0, ChartMaths.functionY(chart(ChartKind.FUNCTION, emptyList(), ChartSeries("f", 0, emptyList(), expr = "1/0"))))
        assertTrue(ChartMaths.functionSamples(chart(ChartKind.FUNCTION, emptyList(), ChartSeries("f", 0, emptyList(), expr = "((")))[0].all { it.second.isNaN() }) // unreadable: nothing drawn
    }

    @Test
    fun aCurveIsBrokenWhereTheFormulaHasNoValueOrJumpsAcrossThePicture() {
        val p = Box(0.0, 0.0, 400.0, 100.0)
        val sqrt = chart(ChartKind.FUNCTION, emptyList(), ChartSeries("f", 0, emptyList(), expr = "sqrt(x)")).copy(xMin = -4000, xMax = 4000, yMin = -1000, yMax = 3000)
        val pieces = ChartMaths.curves(sqrt, p)[0]
        assertEquals(1, pieces.size) // only the right half exists
        assertTrue(pieces[0].all { it.first >= 200.0 - 1e-6 })
        val tan = chart(ChartKind.FUNCTION, emptyList(), ChartSeries("f", 0, emptyList(), expr = "tan(x)")).copy(xMin = -7000, xMax = 7000, yMin = -5000, yMax = 5000)
        assertTrue(ChartMaths.curves(tan, p)[0].size >= 3) // pieces between the asymptotes, none joined across them
        for (piece in ChartMaths.curves(tan, p)[0]) for (i in 0 until piece.size - 1) assertTrue(Math.abs(piece[i + 1].second - piece[i].second) < 100.0 * 4)
        val line = chart(ChartKind.FUNCTION, emptyList(), ChartSeries("f", 0, emptyList(), expr = "2x+1")).copy(xMin = -10000, xMax = 10000, yMin = -10000, yMax = 10000)
        val pts = ChartMaths.curves(line, p)[0].single()
        assertEquals(0.0, pts.first().first, 1e-9); assertEquals(400.0, pts.last().first, 1e-9)
        assertEquals(45.0, pts.first { Math.abs(it.first - 200.0) < 1e-6 }.second, 1e-6) // 2*0+1 = 1 on a -10 to 10 axis: just above the middle
    }

    @Test
    fun theLayoutFitsTheNewKinds() {
        for (k in listOf(ChartKind.SANKEY, ChartKind.SUNBURST, ChartKind.TIMELINE, ChartKind.CALENDAR, ChartKind.FUNCTION)) for (d3 in listOf(false, true)) {
            val l = ChartMaths.layout(chart(k, listOf("a"), ChartSeries("A", 0, th(1.0))).copy(title = "T", threeD = d3)).plot
            assertTrue("$k", l.l >= 0 && l.t >= 0 && l.r <= 6000.0 && l.b <= 4000.0 && l.r > l.l && l.b > l.t)
        }
        assertTrue(ChartMaths.legendNames(chart(ChartKind.SANKEY, listOf("a"), ChartSeries("A", 0, th(1.0)))).isEmpty())
        assertEquals(listOf("A"), ChartMaths.legendNames(chart(ChartKind.FUNCTION, emptyList(), ChartSeries("A", 0, emptyList(), expr = "x"))))
        assertFalse(ChartMaths.hasAxes(ChartKind.SANKEY) || ChartMaths.hasAxes(ChartKind.SUNBURST) || ChartMaths.hasAxes(ChartKind.CALENDAR))
        assertTrue(ChartMaths.hasAxes(ChartKind.TIMELINE) && ChartMaths.hasAxes(ChartKind.FUNCTION))
    }
}
