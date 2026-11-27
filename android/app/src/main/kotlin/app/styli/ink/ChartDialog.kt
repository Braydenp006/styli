package app.styli.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private enum class Stack { GROUPED, STACKED, PERCENT }

/** The kinds whose series can carry error bars. */
private val ERRORS = setOf(ChartKind.BAR, ChartKind.LINE, ChartKind.AREA, ChartKind.COMBO, ChartKind.SCATTER, ChartKind.BUBBLE)

/** The chart being edited, as text the person can type: numbers stay strings until Done, so half-typed ones are not lost. */
private class ChartDraft(c: Chart) {
    var kind by mutableStateOf(c.kind)
    var linked by mutableStateOf(c.source != null) // its numbers come from a table's cells
    var title by mutableStateOf(c.title)
    var xLabel by mutableStateOf(c.xLabel)
    var yLabel by mutableStateOf(c.yLabel)
    var legend by mutableStateOf(!c.hideLegend)
    var grid by mutableStateOf(!c.hideGrid)
    var stack by mutableStateOf(if (c.percent) Stack.PERCENT else if (c.stacked) Stack.STACKED else Stack.GROUPED)
    var horizontal by mutableStateOf(c.horizontal)
    var pyramid by mutableStateOf(c.pyramid)
    var curve by mutableStateOf(c.curve)
    var markers by mutableStateOf(!c.hideMarkers)
    var hole by mutableStateOf(if (c.hole > 0) c.hole.toString() else "55")
    var filled by mutableStateOf(c.filled)
    var threeD by mutableStateOf(c.threeD)
    var showValues by mutableStateOf(c.showValues)
    var dateAxis by mutableStateOf(c.dateAxis)
    var bins by mutableStateOf(if (c.bins > 0) c.bins.toString() else "")
    var binWidth by mutableStateOf(if (c.binWidth > 0) ChartMaths.format(c.binWidth / 1000.0) else "")
    var hideOutliers by mutableStateOf(c.hideOutliers)
    var showMean by mutableStateOf(c.showMean)
    var marker by mutableStateOf(if (c.marker != 0L) (if (c.dateAxis) ChartMaths.dateText(c.marker / 1000.0) else ChartMaths.format(c.marker / 1000.0)) else "")
    var xMin by mutableStateOf(if (c.xMax > c.xMin) ChartMaths.format(c.xMin / 1000.0) else "")
    var xMax by mutableStateOf(if (c.xMax > c.xMin) ChartMaths.format(c.xMax / 1000.0) else "")
    var yMin by mutableStateOf(if (c.yMax > c.yMin) ChartMaths.format(c.yMin / 1000.0) else "")
    var yMax by mutableStateOf(if (c.yMax > c.yMin) ChartMaths.format(c.yMax / 1000.0) else "")
    var gaugeMin by mutableStateOf(ChartMaths.format(c.gaugeRange.first))
    var gaugeMax by mutableStateOf(ChartMaths.format(c.gaugeRange.second))
    val cats = mutableStateListOf<String>()
    val totals = mutableStateListOf<Boolean>()
    val names = mutableStateListOf<String>()
    val colours = mutableStateListOf<Int>()
    val kinds = mutableStateListOf<ChartKind>()
    val second = mutableStateListOf<Boolean>()
    val trend = mutableStateListOf<Boolean>()
    // cells[series][category]: the numbers (y), and for scatter and bubble the x and the size of each point; groups[category] for a treemap
    val cells = mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateList<String>>()
    val xcells = mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateList<String>>()
    val scells = mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateList<String>>()
    val groups = mutableStateListOf<String>()
    val ecells = mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateList<String>>()
    val exprs = mutableStateListOf<String>()

    init { load(c) }

    /** Fills the lists from [c] (the first time, and after a Pareto rearranges the data). */
    fun load(c: Chart) {
        cats.clear(); cats.addAll(c.categories)
        totals.clear(); totals.addAll(c.categories.indices.map { it in c.totals })
        names.clear(); names.addAll(c.series.map { it.name })
        colours.clear(); colours.addAll(c.series.map { it.color })
        kinds.clear(); kinds.addAll(c.series.map { if (it.kind == ChartKind.AREA || it.kind == ChartKind.LINE) it.kind else ChartKind.BAR })
        second.clear(); second.addAll(c.series.map { it.secondary })
        trend.clear(); trend.addAll(c.series.map { it.trendline })
        val rows = maxOf(c.categories.size, c.series.maxOfOrNull { it.values.size } ?: 0)
        fun date(si: Int) = (c.kind == ChartKind.GANTT || c.kind == ChartKind.TIMELINE) && c.dateAxis && si < 2
        fun shown(v: Double?, si: Int) = v?.let { if (date(si)) ChartMaths.dateText(it) else ChartMaths.format(it) } ?: ""
        cells.clear(); cells.addAll(c.series.mapIndexed { si, s -> mutableStateListOf(*(0 until rows).map { i -> shown(s.at(i), si) }.toTypedArray()) })
        xcells.clear(); xcells.addAll(c.series.map { s -> mutableStateListOf(*(0 until rows).map { i -> s.xAt(i)?.let(ChartMaths::format) ?: "" }.toTypedArray()) })
        scells.clear(); scells.addAll(c.series.map { s -> mutableStateListOf(*(0 until rows).map { i -> s.sizeAt(i)?.let(ChartMaths::format) ?: "" }.toTypedArray()) })
        groups.clear(); groups.addAll((0 until rows).map { c.groups.getOrNull(it) ?: "" })
        ecells.clear(); ecells.addAll(c.series.map { s -> mutableStateListOf(*(0 until rows).map { i -> s.errAt(i)?.let(ChartMaths::format) ?: "" }.toTypedArray()) })
        exprs.clear(); exprs.addAll(c.series.map { it.expr })
        while (cats.size < rows) { cats.add(""); totals.add(false) }
    }

    /** A typed number, or null if blank or not a number. */
    fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    val valid get() = names.isNotEmpty() && (kind == ChartKind.FUNCTION || cats.isNotEmpty())

    /** Chooses the kind. Becoming a combo from a plain chart makes the other series lines on a second axis, so there is something to see. */
    fun pickKind(k: ChartKind) {
        if (k == ChartKind.COMBO && kind != ChartKind.COMBO && kinds.all { it == ChartKind.BAR }) for (i in 1 until kinds.size) { kinds[i] = ChartKind.LINE; second[i] = true }
        val need = when (k) { ChartKind.HEATMAP, ChartKind.GANTT -> 2; else -> 1 }
        if (k == ChartKind.FUNCTION && exprs.all { it.isBlank() }) exprs[0] = "sin(x)"
        if (k == ChartKind.TIMELINE) { listOf("When", "End").forEachIndexed { i, n -> if (i < names.size) names[i] = n } }
        while (names.size < need) addSeries()
        if (k == ChartKind.GANTT) { listOf("Start", "End", "Done %").forEachIndexed { i, n -> if (i < names.size) names[i] = n } }
        if (k == ChartKind.CANDLESTICK) { while (names.size < 4) addSeries(); listOf("Open", "High", "Low", "Close").forEachIndexed { i, n -> names[i] = n } }
        kind = k
    }

    fun addCategory() { cats.add(if (kind == ChartKind.HISTOGRAM || kind == ChartKind.BOXPLOT) "" else "Row ${cats.size + 1}"); totals.add(false); groups.add(""); cells.forEach { it.add("") }; xcells.forEach { it.add("") }; scells.forEach { it.add("") }; ecells.forEach { it.add("") } }
    fun removeCategory() { if (cats.size > 1) { cats.removeLast(); totals.removeLast(); groups.removeLast(); cells.forEach { it.removeLast() }; xcells.forEach { it.removeLast() }; scells.forEach { it.removeLast() }; ecells.forEach { it.removeLast() } } }
    fun addSeries() { names.add("Series ${names.size + 1}"); colours.add(Chart.colourFor(names.size - 1)); kinds.add(ChartKind.LINE); second.add(false); trend.add(false); cells.add(mutableStateListOf(*cats.map { "" }.toTypedArray())); xcells.add(mutableStateListOf(*cats.map { "" }.toTypedArray())); scells.add(mutableStateListOf(*cats.map { "" }.toTypedArray())); ecells.add(mutableStateListOf(*cats.map { "" }.toTypedArray())); exprs.add("") }
    fun removeSeries() { if (names.size > 1) { names.removeLast(); colours.removeLast(); kinds.removeLast(); second.removeLast(); trend.removeLast(); cells.removeLast(); xcells.removeLast(); scells.removeLast(); ecells.removeLast(); exprs.removeLast() } }

    /** A typed cell as a number: a date (for a Gantt chart's dates) or an ordinary number. */
    fun value(s: String, si: Int): Double? = if ((kind == ChartKind.GANTT || kind == ChartKind.TIMELINE) && dateAxis && si < 2) ChartMaths.parseDate(s) ?: parse(s) else parse(s)

    fun toChart(base: Chart): Chart = base.copy(
        source = if (linked) base.source else null,
        kind = kind, title = title.trim(), xLabel = xLabel.trim(), yLabel = yLabel.trim(),
        hideLegend = !legend, hideGrid = !grid, stacked = stack != Stack.GROUPED, percent = stack == Stack.PERCENT,
        horizontal = horizontal || pyramid, pyramid = pyramid, curve = curve, hideMarkers = !markers,
        hole = hole.trim().toIntOrNull()?.coerceIn(10, 90) ?: 0, filled = filled, threeD = threeD, showValues = showValues,
        gaugeMin = Math.round((parse(gaugeMin) ?: 0.0) * 1000), gaugeMax = Math.round((parse(gaugeMax) ?: 100.0) * 1000),
        totals = totals.indices.filter { totals[it] }.toSet(),
        dateAxis = dateAxis && (kind == ChartKind.GANTT || kind == ChartKind.TIMELINE), bins = bins.trim().toIntOrNull()?.coerceIn(1, 200) ?: 0,
        binWidth = parse(binWidth)?.takeIf { it > 0 }?.let { Math.round(it * 1000) } ?: 0, hideOutliers = hideOutliers, showMean = showMean,
        marker = (if ((kind == ChartKind.GANTT || kind == ChartKind.TIMELINE) && dateAxis) ChartMaths.parseDate(marker) else null).let { it ?: parse(marker) }?.let { Math.round(it * 1000) } ?: 0,
        groups = if ((kind == ChartKind.TREEMAP || kind == ChartKind.SANKEY) && groups.any { it.isNotBlank() }) groups.toList() else emptyList(),
        xMin = parse(xMin)?.let { Math.round(it * 1000) } ?: 0, xMax = parse(xMax)?.let { Math.round(it * 1000) } ?: 0,
        yMin = parse(yMin)?.let { Math.round(it * 1000) } ?: 0, yMax = parse(yMax)?.let { Math.round(it * 1000) } ?: 0,
        categories = cats.toList(),
        series = names.indices.map { s ->
            val xy = kind == ChartKind.SCATTER || kind == ChartKind.BUBBLE
            val vals = cells[s].mapIndexed { i, t -> value(t, s) }
            val xv = xcells[s].map { parse(it) }
            val sv = scells[s].map { parse(it) }
            val gone = vals.indices.filter { vals[it] == null || (xy && xv[it] == null) || (kind == ChartKind.BUBBLE && sv[it] == null) }.toSet()
            ChartSeries(names[s], colours[s], vals.map { v -> if (v == null) 0L else Math.round(v * 1000) }, gone,
                kind = if (kind == ChartKind.COMBO) kinds[s] else ChartKind.BAR, secondary = kind == ChartKind.COMBO && second[s], trendline = trend[s],
                errors = if (kind in ERRORS) ecells[s].map { t -> parse(t)?.let { e -> Math.round(Math.abs(e) * 1000) } ?: 0L }.takeIf { l -> l.any { it > 0 } } ?: emptyList() else emptyList(),
                expr = if (kind == ChartKind.FUNCTION) exprs[s].trim() else "",
                xs = if (xy) xv.map { x -> if (x == null) 0L else Math.round(x * 1000) } else emptyList(),
                sizes = if (kind == ChartKind.BUBBLE) sv.map { x -> if (x == null) 0L else Math.round(x * 1000) } else emptyList())
        },
    )
}

private val KIND_NAMES = mapOf(
    ChartKind.SANKEY to "sankey", ChartKind.SUNBURST to "sunburst", ChartKind.TIMELINE to "timeline", ChartKind.CALENDAR to "calendar", ChartKind.FUNCTION to "function",
    ChartKind.SCATTER to "scatter", ChartKind.BUBBLE to "bubble", ChartKind.HISTOGRAM to "histogram", ChartKind.BOXPLOT to "box plot",
    ChartKind.HEATMAP to "heatmap", ChartKind.TREEMAP to "treemap", ChartKind.GANTT to "gantt",
    ChartKind.BAR to "bar", ChartKind.LINE to "line", ChartKind.AREA to "area", ChartKind.PIE to "pie", ChartKind.DONUT to "donut", ChartKind.RADAR to "radar",
    ChartKind.POLAR to "polar area", ChartKind.FUNNEL to "funnel", ChartKind.WATERFALL to "waterfall", ChartKind.COMBO to "combo", ChartKind.GAUGE to "gauge",
    ChartKind.SPARKLINE to "sparkline", ChartKind.CANDLESTICK to "candlestick",
)

/** What the grid's rows and columns mean for each kind, shown under the kind choice. */
private fun hint(k: ChartKind) = when (k) {
    ChartKind.PIE, ChartKind.DONUT, ChartKind.POLAR, ChartKind.FUNNEL -> "Uses the first column only, one row per slice, wedge or stage."
    ChartKind.WATERFALL -> "The first column holds the steps (changes); tick Total on a row to show the running total there."
    ChartKind.GAUGE -> "The first row is the value, the second row (optional) the target. Set the ends of the dial below."
    ChartKind.SPARKLINE -> "Draws the first column as a small line, with no axes."
    ChartKind.CANDLESTICK -> "Four columns, in this order: open, high, low, close. One row per day."
    ChartKind.RADAR -> "One row per spoke, one column per shape."
    ChartKind.SCATTER -> "Each column group is one set of points: type each point's X and Y on a row (the row name is an optional label)."
    ChartKind.BUBBLE -> "Each column group is one set of points: X, Y and Size on a row (the row name is an optional label)."
    ChartKind.HISTOGRAM -> "Type the samples down the first column, one per row; they are counted into bins."
    ChartKind.BOXPLOT -> "One column per box, the samples down it, one per row (blanks are skipped)."
    ChartKind.HEATMAP -> "A row for each row of the map, a column for each column; the biggest number is the darkest."
    ChartKind.TREEMAP -> "The first column holds the sizes. Give rows the same Group to nest them."
    ChartKind.GANTT -> "One row per task: Start, End and (optional) Done %. Tick Dates to type them as 2026-12-01."
    ChartKind.SANKEY -> "One row per flow: where it starts, where it ends (To) and how much."
    ChartKind.SUNBURST -> "Name each row with its path, such as Food/Fruit/Apple, and give its size. A parent's size is what is inside it."
    ChartKind.TIMELINE -> "One row per event: its name, when (and, optional, when it ends). Tick Dates to type them as 2026-12-01."
    ChartKind.CALENDAR -> "One row per day: the date as 2026-12-01 and its value. Days you leave out stay empty."
    ChartKind.FUNCTION -> "Type formulas in x, such as sin(x)/x or 2x^2 - 3 (also sqrt, abs, ln, log, exp, pi, e). Leave the Y range empty to fit the curves."
    else -> ""
}


@Composable
private fun Pick(label: String, on: Boolean, click: () -> Unit) = TextButton(onClick = click) { Text((if (on) "• " else "") + label) }

@Composable
private fun Tick(label: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) { Checkbox(on, change); Text(label) }
}

/**
 * The window where a chart's data is typed: the kind and its options, the title and labels, and a grid with a column per
 * series and a row per category. A blank cell is a blank value. Throwaway looks (the real ones come with the UI
 * design). [onDone] gets the chart as typed; [onCancel] leaves everything as it was.
 */
@Composable
fun ChartDialog(chart: Chart, onDone: (Chart) -> Unit, onCancel: () -> Unit) {
    val d = remember(chart) { ChartDraft(chart) }
    val k = d.kind
    val locked = d.linked // a chart that follows a table cannot be typed over
    val axes = ChartMaths.hasAxes(k)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = StyShapes.Lg, color = LocalSty.current.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, LocalSty.current.border),
        ) {
            Column(Modifier.padding(16.dp)) {
                DialogTitle("Chart data")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ChartKind.entries.chunked(5).forEach { row -> Row { for (kk in row) Pick(KIND_NAMES.getValue(kk), d.kind == kk) { d.pickKind(kk) } } }
                    if (locked) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("This chart follows a table: its numbers come from the table's cells and change when you type there.", Modifier.weight(1f))
                        TextButton(onClick = { d.linked = false }) { Text("Unlink, keep these numbers") }
                    }
                    hint(k).takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(bottom = 4.dp)) }
                    OutlinedTextField(d.title, { d.title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                    if (axes) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(d.xLabel, { d.xLabel = it }, Modifier.weight(1f), label = { Text("Bottom axis label") }, singleLine = true)
                        OutlinedTextField(d.yLabel, { d.yLabel = it }, Modifier.weight(1f), label = { Text("Side axis label") }, singleLine = true)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        if (ChartMaths.legendNames(chart.copy(kind = k, series = listOf(ChartSeries("a", 0, emptyList())), categories = listOf("a"))).isNotEmpty()) Tick("Legend", d.legend) { d.legend = it }
                        if (axes) Tick("Gridlines", d.grid) { d.grid = it }
                        if (k != ChartKind.GAUGE && k != ChartKind.SPARKLINE && k != ChartKind.GANTT && k != ChartKind.FUNCTION) Tick("Values", d.showValues) { d.showValues = it }
                        Tick("3D", d.threeD) { d.threeD = it }
                        if (k == ChartKind.BAR) { Tick("Sideways", d.horizontal) { d.horizontal = it }; Tick("Pyramid", d.pyramid) { d.pyramid = it } }
                        if (k == ChartKind.LINE || k == ChartKind.RADAR || k == ChartKind.COMBO || k == ChartKind.SPARKLINE || k == ChartKind.AREA) Tick("Dots", d.markers) { d.markers = it }
                        if (k == ChartKind.RADAR) Tick("Filled", d.filled) { d.filled = it }
                        if (k == ChartKind.BOXPLOT) { Tick("Outliers", !d.hideOutliers) { d.hideOutliers = !it }; Tick("Mean", d.showMean) { d.showMean = it } }
                        if (k == ChartKind.GANTT || k == ChartKind.TIMELINE) Tick("Dates", d.dateAxis) { d.dateAxis = it }
                    }
                    if (k == ChartKind.HISTOGRAM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(d.bins, { d.bins = it }, Modifier.width(150.dp), label = { Text("Number of bins") }, singleLine = true)
                        OutlinedTextField(d.binWidth, { d.binWidth = it }, Modifier.width(150.dp), label = { Text("or bin width") }, singleLine = true)
                    }
                    if (k == ChartKind.GANTT || k == ChartKind.TIMELINE) OutlinedTextField(d.marker, { d.marker = it }, Modifier.width(220.dp), label = { Text(if (d.dateAxis) "Mark a date (2026-12-01)" else "Mark a value (e.g. today)") }, singleLine = true)
                    if (k == ChartKind.BAR || k == ChartKind.AREA) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bars:".takeIf { k == ChartKind.BAR } ?: "Areas:")
                        Pick(if (k == ChartKind.BAR) "side by side" else "overlapping", d.stack == Stack.GROUPED) { d.stack = Stack.GROUPED }
                        Pick("stacked", d.stack == Stack.STACKED) { d.stack = Stack.STACKED }
                        Pick("100% stacked", d.stack == Stack.PERCENT) { d.stack = Stack.PERCENT }
                    }
                    if (k == ChartKind.LINE || k == ChartKind.AREA || k == ChartKind.COMBO || k == ChartKind.SPARKLINE) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lines:")
                        Pick("straight", d.curve == Curve.STRAIGHT) { d.curve = Curve.STRAIGHT }
                        Pick("smooth", d.curve == Curve.SMOOTH) { d.curve = Curve.SMOOTH }
                        Pick("stepped", d.curve == Curve.STEP) { d.curve = Curve.STEP }
                    }
                    if (k == ChartKind.DONUT) OutlinedTextField(d.hole, { d.hole = it }, Modifier.width(180.dp), label = { Text("Hole size (10 to 90%)") }, singleLine = true)
                    if (k == ChartKind.GAUGE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(d.gaugeMin, { d.gaugeMin = it }, Modifier.width(140.dp), label = { Text("Dial starts at") }, singleLine = true)
                        OutlinedTextField(d.gaugeMax, { d.gaugeMax = it }, Modifier.width(140.dp), label = { Text("Dial ends at") }, singleLine = true)
                    }
                    if (k == ChartKind.BAR || k == ChartKind.COMBO) TextButton(onClick = { d.load(ChartMaths.pareto(d.toChart(chart))); d.kind = ChartKind.COMBO }) { Text("Make a Pareto chart (sort, add running share)") }
                    // The grid: a header row (colour, kind, name of each series), then one row per category, so rows always line up.
                    // Scatter and bubble charts give each series an X, a Y (and a Size) column; histograms and box plots have no row names.
                    val xy = k == ChartKind.SCATTER || k == ChartKind.BUBBLE
                    val hasErr = k in ERRORS
                    val base = if (k == ChartKind.BUBBLE) 3 else if (xy) 2 else 1
                    val per = base + if (hasErr) 1 else 0
                    val rowNames = k != ChartKind.HISTOGRAM && k != ChartKind.BOXPLOT
                    val shown = when (k) {
                        ChartKind.HISTOGRAM, ChartKind.SANKEY, ChartKind.SUNBURST, ChartKind.CALENDAR -> 1
                        ChartKind.GANTT -> minOf(3, d.names.size)
                        ChartKind.TIMELINE -> minOf(2, d.names.size)
                        else -> d.names.size
                    }
                    val groupCol = k == ChartKind.TREEMAP || k == ChartKind.SANKEY
                    val fixedCols = setOf(ChartKind.HISTOGRAM, ChartKind.GANTT, ChartKind.SANKEY, ChartKind.SUNBURST, ChartKind.CALENDAR, ChartKind.TIMELINE)
                    val noTrend = setOf(ChartKind.PIE, ChartKind.DONUT, ChartKind.GAUGE, ChartKind.CANDLESTICK, ChartKind.FUNNEL, ChartKind.POLAR, ChartKind.WATERFALL, ChartKind.SPARKLINE, ChartKind.RADAR,
                        ChartKind.HISTOGRAM, ChartKind.BOXPLOT, ChartKind.HEATMAP, ChartKind.TREEMAP, ChartKind.GANTT, ChartKind.SANKEY, ChartKind.SUNBURST, ChartKind.TIMELINE, ChartKind.CALENDAR, ChartKind.FUNCTION)
                    if (k == ChartKind.FUNCTION) FunctionForm(d) else Column(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            if (rowNames) Box(Modifier.width(150.dp))
                            for (s in 0 until shown) Column(Modifier.width((150 * per).dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.padding(end = 6.dp).size(22.dp).clip(CircleShape).background(Color(d.colours[s]))
                                            .clickable { d.colours[s] = Chart.PALETTE[(Chart.PALETTE.indexOf(d.colours[s]) + 1) % Chart.PALETTE.size] },
                                    )
                                    if (k !in noTrend) Tick("Trend", d.trend[s]) { d.trend[s] = it }
                                }
                                if (k == ChartKind.COMBO) {
                                    TextButton(onClick = { d.kinds[s] = when (d.kinds[s]) { ChartKind.BAR -> ChartKind.LINE; ChartKind.LINE -> ChartKind.AREA; else -> ChartKind.BAR } }) { Text("drawn as: " + KIND_NAMES.getValue(d.kinds[s])) }
                                    Tick("2nd axis", d.second[s]) { d.second[s] = it }
                                }
                                OutlinedTextField(d.names[s], { d.names[s] = it }, Modifier.width(144.dp).padding(bottom = 4.dp), singleLine = true, enabled = !locked)
                                if (xy || hasErr) Row { for (t in (if (xy) listOf("X", "Y", "Size").take(base) else listOf("Value")) + (if (hasErr) listOf("\u00B1 error") else emptyList())) Text(t, Modifier.width(150.dp).padding(start = 16.dp)) }
                            }
                            if (groupCol) Text(if (k == ChartKind.SANKEY) "To" else "Group", Modifier.width(150.dp).padding(top = 40.dp, start = 16.dp))
                        }
                        for (i in d.cats.indices) Row(verticalAlignment = Alignment.CenterVertically) {
                            if (rowNames) OutlinedTextField(d.cats[i], { d.cats[i] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true, enabled = !locked)
                            for (s in 0 until shown) {
                                if (xy) OutlinedTextField(
                                    d.xcells[s][i], { d.xcells[s][i] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true,
                                    isError = d.xcells[s][i].isNotBlank() && d.parse(d.xcells[s][i]) == null,
                                )
                                val text = d.cells[s][i]
                                OutlinedTextField(
                                    text, { d.cells[s][i] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true, enabled = !locked,
                                    isError = text.isNotBlank() && d.value(text, s) == null,
                                )
                                if (k == ChartKind.BUBBLE) OutlinedTextField(
                                    d.scells[s][i], { d.scells[s][i] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true,
                                    isError = d.scells[s][i].isNotBlank() && d.parse(d.scells[s][i]) == null,
                                )
                                if (hasErr) OutlinedTextField(
                                    d.ecells[s][i], { d.ecells[s][i] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true,
                                    isError = d.ecells[s][i].isNotBlank() && d.parse(d.ecells[s][i]) == null,
                                )
                            }
                            if (groupCol) OutlinedTextField(d.groups[i], { d.groups[i] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true)
                            if (k == ChartKind.WATERFALL) Tick("Total", d.totals[i]) { d.totals[i] = it }
                        }
                    }
                    Row {
                        if (locked) {
                            // the rows and columns belong to the table
                        } else if (k != ChartKind.FUNCTION) {
                            TextButton(onClick = { d.addCategory() }) { Text("Add row") }
                            TextButton(onClick = { d.removeCategory() }, enabled = d.cats.size > 1) { Text("Remove last row") }
                        } else {
                            TextButton(onClick = { d.addSeries(); d.exprs[d.exprs.lastIndex] = "x"; d.names[d.names.lastIndex] = "f${d.names.size}(x)" }) { Text("Add a formula") }
                            TextButton(onClick = { d.removeSeries() }, enabled = d.names.size > 1) { Text("Remove last formula") }
                        }
                        if (!locked && k !in fixedCols && k != ChartKind.FUNCTION) {
                            TextButton(onClick = { d.addSeries() }) { Text(if (xy) "Add a set of points" else "Add column") }
                            TextButton(onClick = { d.removeSeries() }, enabled = d.names.size > 1) { Text(if (xy) "Remove last set" else "Remove last column") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onDone(d.toChart(chart)) }, enabled = d.valid) { Text("Done") }
                }
            }
        }
    }
}

/** The window for a function chart: a formula for each curve, and the range drawn. */
@Composable
private fun FunctionForm(d: ChartDraft) {
    Column(Modifier.padding(top = 8.dp)) {
        for (s in d.names.indices) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.padding(end = 8.dp).size(26.dp).clip(CircleShape).background(Color(d.colours[s]))
                    .clickable { d.colours[s] = Chart.PALETTE[(Chart.PALETTE.indexOf(d.colours[s]) + 1) % Chart.PALETTE.size] },
            )
            OutlinedTextField(d.names[s], { d.names[s] = it }, Modifier.width(150.dp).padding(end = 8.dp), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(d.exprs[s], { d.exprs[s] = it }, Modifier.weight(1f).padding(end = 8.dp), label = { Text("y =") }, singleLine = true, isError = d.exprs[s].isNotBlank() && Formula.parse(d.exprs[s]) == null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(d.xMin, { d.xMin = it }, Modifier.width(120.dp), label = { Text("x from") }, singleLine = true)
            OutlinedTextField(d.xMax, { d.xMax = it }, Modifier.width(120.dp), label = { Text("x to") }, singleLine = true)
            OutlinedTextField(d.yMin, { d.yMin = it }, Modifier.width(120.dp), label = { Text("y from") }, singleLine = true)
            OutlinedTextField(d.yMax, { d.yMax = it }, Modifier.width(120.dp), label = { Text("y to") }, singleLine = true)
        }
        Text("Leave x empty for -10 to 10, y empty to fit the curves.", Modifier.padding(top = 4.dp))
    }
}
