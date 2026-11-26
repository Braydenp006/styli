package app.styli.ink

/**
 * Throwaway: sample charts of every kind and subtype, for the Chart samples button (to look through them on pages).
 * Remove with the button when the UI work replaces it.
 */
object ChartDemo {
    private fun s(name: String, col: Int, vararg v: Double) = ChartSeries(name, Chart.colourFor(col), v.map { Math.round(it * 1000) })
    private fun th(vararg v: Double) = v.map { Math.round(it * 1000) }
    private val q = listOf("Q1", "Q2", "Q3", "Q4", "Q5")
    private fun c(kind: ChartKind, title: String, vararg sr: ChartSeries, cats: List<String> = q) = Chart("", kind, 0f, 0f, 1f, 1f, title, cats, sr.toList())
    private val a = s("North", 0, 12.0, 19.0, 8.0, 15.0, 22.0)
    private val b = s("South", 1, 8.0, 11.0, 14.0, 9.0, 13.0)
    private val ohlc = listOf(s("Open", 0, 10.0, 12.0, 11.0, 14.0, 13.0), s("High", 0, 13.0, 15.0, 14.0, 16.0, 15.0), s("Low", 0, 9.0, 11.0, 10.0, 12.0, 11.0), s("Close", 0, 12.0, 11.0, 13.0, 13.0, 14.0))
    private val steps = listOf("Start", "Sales", "Costs", "Tax", "Net")
    private val stages = listOf("Visits", "Sign-ups", "Trials", "Quotes", "Sales")
    private val faults = c(ChartKind.BAR, "Pareto", s("Faults", 0, 5.0, 40.0, 15.0, 8.0, 2.0), cats = listOf("Scratch", "Dent", "Crack", "Stain", "Other"))
    private val combo = Chart("", ChartKind.COMBO, 0f, 0f, 1f, 1f, "Combo", q, listOf(a, ChartSeries("Growth %", Chart.colourFor(1), th(5.0, 8.0, -2.0, 6.0, 9.0), kind = ChartKind.LINE, secondary = true)))

    // Scatter: two clouds, one with a clear trend
    private val xa = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)
    private val scatter = listOf(
        ChartSeries("Trend", Chart.colourFor(0), th(2.1, 3.9, 6.2, 7.8, 10.1, 12.2, 13.8, 16.1), xs = th(*xa.toDoubleArray()), trendline = true),
        ChartSeries("Noise", Chart.colourFor(1), th(9.0, 4.0, 12.0, 6.0, 3.0, 11.0, 5.0, 8.0), xs = th(1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5)),
    )
    private val bubble = listOf(ChartSeries("Cities", Chart.colourFor(0), th(3.0, 7.0, 5.0, 9.0, 2.0), xs = th(1.0, 3.0, 5.0, 7.0, 9.0), sizes = th(10.0, 40.0, 90.0, 20.0, 60.0)))
    private val samples = ChartSeries("Heights", Chart.colourFor(0), th(150.0, 152.0, 155.0, 158.0, 160.0, 160.0, 161.0, 163.0, 165.0, 165.0, 166.0, 168.0, 170.0, 172.0, 175.0, 178.0, 181.0, 190.0, 158.0, 162.0, 164.0, 167.0, 169.0))
    private val boxes = listOf(
        ChartSeries("A", Chart.colourFor(0), th(5.0, 6.0, 7.0, 7.5, 8.0, 8.5, 9.0, 9.5, 10.0, 22.0)),
        ChartSeries("B", Chart.colourFor(1), th(3.0, 4.0, 5.0, 6.0, 6.5, 7.0, 7.5, 8.0, 9.0)),
        ChartSeries("C", Chart.colourFor(2), th(8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 1.0)),
    )
    private val heat = listOf(s("Mon", 0, 2.0, 8.0, 5.0, 1.0), s("Tue", 0, 3.0, 9.0, 6.0, 2.0), s("Wed", 0, 1.0, 7.0, 9.0, 3.0), s("Thu", 0, 4.0, 6.0, 5.0, 8.0), s("Fri", 0, 6.0, 2.0, 3.0, 9.0))
    private val hours = listOf("9am", "12pm", "3pm", "6pm")
    private val tiles = s("Size", 0, 40.0, 25.0, 15.0, 10.0, 6.0, 4.0)
    private val tileNames = listOf("Rent", "Food", "Travel", "Fun", "Books", "Misc")
    private val day = ChartMaths.parseDate("2026-12-01")!!
    private val gantt = listOf(
        ChartSeries("Start", Chart.colourFor(0), th(day, day + 3, day + 10, day + 14, day + 20)),
        ChartSeries("End", Chart.colourFor(0), th(day + 4, day + 12, day + 18, day + 24, day + 28)),
        ChartSeries("Done %", Chart.colourFor(0), th(100.0, 70.0, 40.0, 10.0, 0.0)),
    )
    private val tasks = listOf("Plan", "Design", "Build", "Test", "Ship")

    // third batch
    private val flowFrom = listOf("Wages", "Gifts", "Budget", "Budget", "Budget", "Food", "Food", "Rent")
    private val flowTo = listOf("Budget", "Budget", "Rent", "Food", "Savings", "Groceries", "Eating out", "Rent costs")
    private val flowVal = ChartSeries("Flow", Chart.colourFor(0), th(100.0, 20.0, 60.0, 40.0, 20.0, 28.0, 12.0, 0.0), missing = setOf(7))
    private val sankey = Chart("", ChartKind.SANKEY, 0f, 0f, 1f, 1f, "Where the money goes", flowFrom, listOf(flowVal), groups = flowTo, showValues = true)
    private val foodPaths = listOf("Food/Fruit/Apple", "Food/Fruit/Pear", "Food/Veg/Kale", "Food/Veg/Leek", "Food/Bread", "Drink/Tea", "Drink/Coffee", "Drink/Water", "Fuel")
    private val sunburst = Chart("", ChartKind.SUNBURST, 0f, 0f, 1f, 1f, "Spending", foodPaths, listOf(s("Spend", 0, 6.0, 4.0, 5.0, 3.0, 8.0, 3.0, 4.0, 1.0, 9.0)))
    private val d0 = ChartMaths.parseDate("2026-01-01")!!
    private val events = listOf("Founded", "First hire", "Seed round", "Launch", "v2", "Series A", "Offices", "Profit")
    private val timeline = Chart("", ChartKind.TIMELINE, 0f, 0f, 1f, 1f, "Company history", events,
        listOf(ChartSeries("When", Chart.colourFor(0), th(d0, d0 + 40, d0 + 75, d0 + 110, d0 + 118, d0 + 190, d0 + 230, d0 + 300)),
            ChartSeries("End", Chart.colourFor(0), th(0.0, 0.0, d0 + 90, 0.0, 0.0, 0.0, d0 + 270, 0.0), missing = setOf(0, 1, 3, 4, 5, 7))), dateAxis = true, showValues = true, marker = Math.round((d0 + 160) * 1000))
    private val calDays = (0 until 75).map { ChartMaths.dateText(ChartMaths.parseDate("2026-10-05")!! + it) }
    private val calendar = Chart("", ChartKind.CALENDAR, 0f, 0f, 1f, 1f, "Runs per day", calDays,
        listOf(ChartSeries("Runs", Chart.colourFor(0), th(*DoubleArray(75) { i -> if (i % 7 == 6 || i % 11 == 4) Double.NaN else ((i * 37) % 9 + 1).toDouble() }.map { if (it.isNaN()) 0.0 else it }.toDoubleArray()),
            missing = (0 until 75).filter { it % 7 == 6 || it % 11 == 4 }.toSet())))
    private fun fn(title: String, vararg e: String) = Chart("", ChartKind.FUNCTION, 0f, 0f, 1f, 1f, title, emptyList(), e.mapIndexed { i, x -> ChartSeries(x, Chart.colourFor(i), emptyList(), expr = x) }, xMin = -6283, xMax = 6283)
    private val err = ChartSeries("Mean", Chart.colourFor(0), th(12.0, 19.0, 8.0, 15.0, 22.0), errors = th(2.0, 3.0, 1.5, 4.0, 2.5))

    fun all(): List<Chart> = listOf(
        c(ChartKind.BAR, "Bar", a, b).copy(xLabel = "Quarter", yLabel = "Sales"),
        c(ChartKind.BAR, "Bar 3D", a, b).copy(threeD = true, showValues = true),
        c(ChartKind.BAR, "Sideways stacked", a, b).copy(horizontal = true, stacked = true),
        c(ChartKind.BAR, "100% stacked", a, b).copy(stacked = true, percent = true),
        c(ChartKind.BAR, "Pyramid", s("Men", 0, 4.0, 6.0, 8.0, 5.0, 3.0), s("Women", 1, 5.0, 6.0, 7.0, 6.0, 4.0), cats = listOf("0-9", "10-19", "20-39", "40-59", "60+")).copy(pyramid = true),
        c(ChartKind.LINE, "Line smooth + trend", a, b.copy(trendline = true)).copy(curve = Curve.SMOOTH),
        c(ChartKind.LINE, "Line stepped 3D", a, b).copy(curve = Curve.STEP, threeD = true),
        c(ChartKind.AREA, "Area", a, b),
        c(ChartKind.AREA, "Area stacked 3D", a, b).copy(stacked = true, threeD = true),
        c(ChartKind.PIE, "Pie", a), c(ChartKind.PIE, "Pie 3D", a).copy(threeD = true),
        c(ChartKind.DONUT, "Donut", a).copy(hole = 60), c(ChartKind.DONUT, "Donut 3D", a).copy(threeD = true, showValues = true),
        c(ChartKind.RADAR, "Radar", a, b).copy(filled = true), c(ChartKind.RADAR, "Radar 3D", a, b).copy(filled = true, threeD = true),
        c(ChartKind.POLAR, "Polar area", a), c(ChartKind.POLAR, "Polar area 3D", a).copy(threeD = true),
        c(ChartKind.FUNNEL, "Funnel", s("L", 0, 1000.0, 620.0, 300.0, 120.0, 45.0), cats = stages),
        c(ChartKind.FUNNEL, "Funnel 3D", s("L", 0, 1000.0, 620.0, 300.0, 120.0, 45.0), cats = stages).copy(threeD = true),
        c(ChartKind.WATERFALL, "Waterfall", s("", 0, 100.0, 30.0, -20.0, -15.0, 0.0), cats = steps).copy(totals = setOf(4), showValues = true),
        c(ChartKind.WATERFALL, "Waterfall 3D", s("", 0, 100.0, 30.0, -20.0, -15.0, 0.0), cats = steps).copy(totals = setOf(4), threeD = true),
        combo, combo.copy(title = "Combo 3D", threeD = true), ChartMaths.pareto(faults),
        c(ChartKind.GAUGE, "Gauge", s("", 0, 72.0, 90.0), cats = listOf("Value", "Target")),
        c(ChartKind.GAUGE, "Gauge 3D", s("", 0, 72.0, 90.0), cats = listOf("Value", "Target")).copy(threeD = true),
        c(ChartKind.SPARKLINE, "", a).copy(curve = Curve.SMOOTH), c(ChartKind.SPARKLINE, "", a).copy(curve = Curve.SMOOTH, threeD = true),
        c(ChartKind.CANDLESTICK, "Candlestick", *ohlc.toTypedArray()), c(ChartKind.CANDLESTICK, "Candlestick 3D", *ohlc.toTypedArray()).copy(threeD = true),
        // second batch
        Chart("", ChartKind.SCATTER, 0f, 0f, 1f, 1f, "Scatter + trend", emptyList(), scatter, xLabel = "x", yLabel = "y", showValues = true),
        Chart("", ChartKind.SCATTER, 0f, 0f, 1f, 1f, "Scatter 3D", emptyList(), scatter, threeD = true),
        Chart("", ChartKind.BUBBLE, 0f, 0f, 1f, 1f, "Bubble", listOf("Ayr", "Bath", "Cork", "Deal", "Eton"), bubble, showValues = true),
        Chart("", ChartKind.BUBBLE, 0f, 0f, 1f, 1f, "Bubble 3D", listOf("Ayr", "Bath", "Cork", "Deal", "Eton"), bubble, threeD = true),
        Chart("", ChartKind.HISTOGRAM, 0f, 0f, 1f, 1f, "Histogram", List(samples.values.size) { "" }, listOf(samples), bins = 6, showValues = true),
        Chart("", ChartKind.HISTOGRAM, 0f, 0f, 1f, 1f, "Histogram 3D", List(samples.values.size) { "" }, listOf(samples), threeD = true),
        Chart("", ChartKind.BOXPLOT, 0f, 0f, 1f, 1f, "Box plot", emptyList(), boxes, showMean = true),
        Chart("", ChartKind.BOXPLOT, 0f, 0f, 1f, 1f, "Box plot 3D", emptyList(), boxes, threeD = true),
        Chart("", ChartKind.HEATMAP, 0f, 0f, 1f, 1f, "Heatmap", hours, heat, showValues = true),
        Chart("", ChartKind.HEATMAP, 0f, 0f, 1f, 1f, "Heatmap 3D", hours, heat, threeD = true),
        Chart("", ChartKind.TREEMAP, 0f, 0f, 1f, 1f, "Treemap", tileNames, listOf(tiles)),
        Chart("", ChartKind.TREEMAP, 0f, 0f, 1f, 1f, "Treemap grouped 3D", tileNames, listOf(tiles), groups = listOf("Home", "Home", "Out", "Out", "Home", "Out"), threeD = true),
        Chart("", ChartKind.GANTT, 0f, 0f, 1f, 1f, "Gantt", tasks, gantt, dateAxis = true, marker = Math.round((day + 13) * 1000)),
        Chart("", ChartKind.GANTT, 0f, 0f, 1f, 1f, "Gantt 3D", tasks, gantt, dateAxis = true, threeD = true),
        // third batch
        sankey, sankey.copy(title = "Sankey 3D", threeD = true),
        sunburst, sunburst.copy(title = "Sunburst 3D", threeD = true),
        timeline, timeline.copy(title = "Timeline 3D", threeD = true),
        calendar.copy(showValues = true), calendar.copy(title = "Calendar 3D", threeD = true),
        fn("sin and cos", "sin(x)", "cos(x)"), fn("Functions 3D", "sin(x)/x", "x^2/10 - 2").copy(threeD = true),
        fn("tan(x) breaks at its asymptotes", "tan(x)").copy(yMin = -5000, yMax = 5000),
        c(ChartKind.BAR, "Bar with error bars", err).copy(xLabel = "Quarter"), c(ChartKind.LINE, "Line with error bars", err).copy(curve = Curve.SMOOTH),
        c(ChartKind.BAR, "Bar with error bars 3D", err).copy(threeD = true),
        Chart("", ChartKind.SCATTER, 0f, 0f, 1f, 1f, "Scatter with error bars", emptyList(), listOf(ChartSeries("Trend", Chart.colourFor(0), th(2.1, 3.9, 6.2, 7.8, 10.1, 12.2), xs = th(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), errors = th(1.0, 0.5, 1.5, 0.8, 1.2, 1.0))))
    )
}
