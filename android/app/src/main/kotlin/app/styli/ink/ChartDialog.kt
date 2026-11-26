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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** The chart being edited, as text the person can type: numbers stay strings until Done, so half-typed ones are not lost. */
private class ChartDraft(c: Chart) {
    var kind by mutableStateOf(c.kind)
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
    var gaugeMin by mutableStateOf(ChartMaths.format(c.gaugeRange.first))
    var gaugeMax by mutableStateOf(ChartMaths.format(c.gaugeRange.second))
    val cats = mutableStateListOf<String>()
    val totals = mutableStateListOf<Boolean>()
    val names = mutableStateListOf<String>()
    val colours = mutableStateListOf<Int>()
    val kinds = mutableStateListOf<ChartKind>()
    val second = mutableStateListOf<Boolean>()
    val trend = mutableStateListOf<Boolean>()
    // cells[series][category]
    val cells = mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateList<String>>()

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
        cells.clear(); cells.addAll(c.series.map { s -> mutableStateListOf(*c.categories.indices.map { i -> s.at(i)?.let(ChartMaths::format) ?: "" }.toTypedArray()) })
    }

    /** A typed number, or null if blank or not a number. */
    fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    val valid get() = cats.isNotEmpty() && names.isNotEmpty()

    /** Chooses the kind. Becoming a combo from a plain chart makes the other series lines on a second axis, so there is something to see. */
    fun pickKind(k: ChartKind) {
        if (k == ChartKind.COMBO && kind != ChartKind.COMBO && kinds.all { it == ChartKind.BAR }) for (i in 1 until kinds.size) { kinds[i] = ChartKind.LINE; second[i] = true }
        kind = k
    }

    fun addCategory() { cats.add("Category ${cats.size + 1}"); totals.add(false); cells.forEach { it.add("") } }
    fun removeCategory() { if (cats.size > 1) { cats.removeLast(); totals.removeLast(); cells.forEach { it.removeLast() } } }
    fun addSeries() { names.add("Series ${names.size + 1}"); colours.add(Chart.colourFor(names.size - 1)); kinds.add(ChartKind.LINE); second.add(false); trend.add(false); cells.add(mutableStateListOf(*cats.map { "" }.toTypedArray())) }
    fun removeSeries() { if (names.size > 1) { names.removeLast(); colours.removeLast(); kinds.removeLast(); second.removeLast(); trend.removeLast(); cells.removeLast() } }

    fun toChart(base: Chart): Chart = base.copy(
        kind = kind, title = title.trim(), xLabel = xLabel.trim(), yLabel = yLabel.trim(),
        hideLegend = !legend, hideGrid = !grid, stacked = stack != Stack.GROUPED, percent = stack == Stack.PERCENT,
        horizontal = horizontal || pyramid, pyramid = pyramid, curve = curve, hideMarkers = !markers,
        hole = hole.trim().toIntOrNull()?.coerceIn(10, 90) ?: 0, filled = filled, threeD = threeD, showValues = showValues,
        gaugeMin = Math.round((parse(gaugeMin) ?: 0.0) * 1000), gaugeMax = Math.round((parse(gaugeMax) ?: 100.0) * 1000),
        totals = totals.indices.filter { totals[it] }.toSet(),
        categories = cats.toList(),
        series = names.indices.map { s ->
            val vals = cells[s].map { parse(it) }
            ChartSeries(names[s], colours[s], vals.map { v -> if (v == null) 0L else Math.round(v * 1000) }, vals.indices.filter { vals[it] == null }.toSet(),
                kind = if (kind == ChartKind.COMBO) kinds[s] else ChartKind.BAR, secondary = kind == ChartKind.COMBO && second[s], trendline = trend[s])
        },
    )
}

private val KIND_NAMES = mapOf(
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
    val axes = ChartMaths.hasAxes(k)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Chart data")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ChartKind.entries.chunked(5).forEach { row -> Row { for (kk in row) Pick(KIND_NAMES.getValue(kk), d.kind == kk) { d.pickKind(kk) } } }
                    hint(k).takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(bottom = 4.dp)) }
                    OutlinedTextField(d.title, { d.title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                    if (axes) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(d.xLabel, { d.xLabel = it }, Modifier.weight(1f), label = { Text("Bottom axis label") }, singleLine = true)
                        OutlinedTextField(d.yLabel, { d.yLabel = it }, Modifier.weight(1f), label = { Text("Side axis label") }, singleLine = true)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        if (ChartMaths.legendNames(chart.copy(kind = k, series = listOf(ChartSeries("a", 0, emptyList())), categories = listOf("a"))).isNotEmpty()) Tick("Legend", d.legend) { d.legend = it }
                        if (axes) Tick("Gridlines", d.grid) { d.grid = it }
                        if (k != ChartKind.GAUGE && k != ChartKind.SPARKLINE) Tick("Values", d.showValues) { d.showValues = it }
                        Tick("3D", d.threeD) { d.threeD = it }
                        if (k == ChartKind.BAR) { Tick("Sideways", d.horizontal) { d.horizontal = it }; Tick("Pyramid", d.pyramid) { d.pyramid = it } }
                        if (k == ChartKind.LINE || k == ChartKind.RADAR || k == ChartKind.COMBO || k == ChartKind.SPARKLINE || k == ChartKind.AREA) Tick("Dots", d.markers) { d.markers = it }
                        if (k == ChartKind.RADAR) Tick("Filled", d.filled) { d.filled = it }
                    }
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
                    Column(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.width(150.dp))
                            for (s in d.names.indices) Column(Modifier.width(150.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.padding(end = 6.dp).size(22.dp).clip(CircleShape).background(Color(d.colours[s]))
                                            .clickable { d.colours[s] = Chart.PALETTE[(Chart.PALETTE.indexOf(d.colours[s]) + 1) % Chart.PALETTE.size] },
                                    )
                                    if (k != ChartKind.PIE && k != ChartKind.DONUT && k != ChartKind.GAUGE && k != ChartKind.CANDLESTICK && k != ChartKind.FUNNEL && k != ChartKind.POLAR && k != ChartKind.WATERFALL && k != ChartKind.SPARKLINE && k != ChartKind.RADAR)
                                        Tick("Trend", d.trend[s]) { d.trend[s] = it }
                                }
                                if (k == ChartKind.COMBO) {
                                    TextButton(onClick = { d.kinds[s] = when (d.kinds[s]) { ChartKind.BAR -> ChartKind.LINE; ChartKind.LINE -> ChartKind.AREA; else -> ChartKind.BAR } }) { Text("drawn as: " + KIND_NAMES.getValue(d.kinds[s])) }
                                    Tick("2nd axis", d.second[s]) { d.second[s] = it }
                                }
                                OutlinedTextField(d.names[s], { d.names[s] = it }, Modifier.padding(end = 6.dp, bottom = 4.dp), singleLine = true)
                            }
                        }
                        for (i in d.cats.indices) Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(d.cats[i], { d.cats[i] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true)
                            for (s in d.names.indices) {
                                val text = d.cells[s][i]
                                OutlinedTextField(
                                    text, { d.cells[s][i] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true,
                                    isError = text.isNotBlank() && d.parse(text) == null,
                                )
                            }
                            if (k == ChartKind.WATERFALL) Tick("Total", d.totals[i]) { d.totals[i] = it }
                        }
                    }
                    Row {
                        TextButton(onClick = { d.addCategory() }) { Text("Add row") }
                        TextButton(onClick = { d.removeCategory() }, enabled = d.cats.size > 1) { Text("Remove last row") }
                        TextButton(onClick = { d.addSeries() }) { Text("Add column") }
                        TextButton(onClick = { d.removeSeries() }, enabled = d.names.size > 1) { Text("Remove last column") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = { onDone(d.toChart(chart)) }, enabled = d.valid) { Text("Done") }
                }
            }
        }
    }
}
