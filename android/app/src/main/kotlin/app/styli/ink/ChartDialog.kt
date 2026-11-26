package app.styli.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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

/** The chart being edited, as text the person can type: numbers stay strings until Done, so half-typed ones are not lost. */
private class ChartDraft(c: Chart) {
    var kind by mutableStateOf(c.kind)
    var title by mutableStateOf(c.title)
    var xLabel by mutableStateOf(c.xLabel)
    var yLabel by mutableStateOf(c.yLabel)
    var legend by mutableStateOf(!c.hideLegend)
    var grid by mutableStateOf(!c.hideGrid)
    var stacked by mutableStateOf(c.stacked)
    val cats = mutableStateListOf(*c.categories.toTypedArray())
    val names = mutableStateListOf(*c.series.map { it.name }.toTypedArray())
    val colours = mutableStateListOf(*c.series.map { it.color }.toTypedArray())
    // cells[series][category]
    val cells = mutableStateListOf(*c.series.map { s -> mutableStateListOf(*c.categories.indices.map { i -> s.at(i)?.let(ChartMaths::format) ?: "" }.toTypedArray()) }.toTypedArray())

    /** A typed number, or null if blank or not a number. */
    fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    val valid get() = cats.isNotEmpty() && names.isNotEmpty()

    fun addCategory() { cats.add("Category ${cats.size + 1}"); cells.forEach { it.add("") } }
    fun removeCategory() { if (cats.size > 1) { cats.removeLast(); cells.forEach { it.removeLast() } } }
    fun addSeries() { names.add("Series ${names.size + 1}"); colours.add(Chart.colourFor(names.size - 1)); cells.add(mutableStateListOf(*cats.map { "" }.toTypedArray())) }
    fun removeSeries() { if (names.size > 1) { names.removeLast(); colours.removeLast(); cells.removeLast() } }

    fun toChart(base: Chart): Chart = base.copy(
        kind = kind, title = title.trim(), xLabel = xLabel.trim(), yLabel = yLabel.trim(),
        hideLegend = !legend, hideGrid = !grid, stacked = stacked,
        categories = cats.toList(),
        series = names.indices.map { s ->
            val vals = cells[s].map { parse(it) }
            ChartSeries(names[s], colours[s], vals.map { v -> if (v == null) 0L else Math.round(v * 1000) }, vals.indices.filter { vals[it] == null }.toSet())
        },
    )
}

/**
 * The window where a chart's data is typed: the kind, the title and labels, and a grid with a column per series and a
 * row per category. A blank cell is a blank value. Throwaway looks (the real ones come with the UI design). [onDone]
 * gets the chart as typed; [onCancel] leaves everything as it was.
 */
@Composable
fun ChartDialog(chart: Chart, onDone: (Chart) -> Unit, onCancel: () -> Unit) {
    val d = remember(chart) { ChartDraft(chart) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Chart data")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (k in ChartKind.entries) TextButton(onClick = { d.kind = k }) { Text((if (d.kind == k) "• " else "") + k.name.lowercase()) }
                    }
                    OutlinedTextField(d.title, { d.title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                    if (d.kind != ChartKind.PIE) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(d.xLabel, { d.xLabel = it }, Modifier.weight(1f), label = { Text("Bottom axis label") }, singleLine = true)
                        OutlinedTextField(d.yLabel, { d.yLabel = it }, Modifier.weight(1f), label = { Text("Side axis label") }, singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(d.legend, { d.legend = it }); Text("Legend")
                        if (d.kind != ChartKind.PIE) { Checkbox(d.grid, { d.grid = it }); Text("Gridlines") }
                        if (d.kind == ChartKind.BAR) { Checkbox(d.stacked, { d.stacked = it }); Text("Stacked") }
                    }
                    if (d.kind == ChartKind.PIE) Text("A pie shows the first series, and only its positive numbers.")
                    // The grid: category names down the left, one column of numbers for each series.
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        Column(Modifier.width(120.dp)) {
                            Text("", Modifier.padding(top = 24.dp, bottom = 8.dp))
                            for (i in d.cats.indices) OutlinedTextField(d.cats[i], { d.cats[i] = it }, Modifier.padding(end = 6.dp, bottom = 4.dp), singleLine = true)
                        }
                        for (s in d.names.indices) Column(Modifier.width(110.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("", Modifier.size(0.dp))
                                androidx.compose.foundation.layout.Box(
                                    Modifier.padding(end = 6.dp).size(22.dp).clip(CircleShape).background(Color(d.colours[s]))
                                        .clickable { d.colours[s] = Chart.PALETTE[(Chart.PALETTE.indexOf(d.colours[s]) + 1) % Chart.PALETTE.size] },
                                )
                                Text("colour", Modifier.padding(end = 4.dp))
                            }
                            OutlinedTextField(d.names[s], { d.names[s] = it }, Modifier.padding(end = 6.dp, bottom = 4.dp), singleLine = true)
                            for (i in d.cats.indices) {
                                val text = d.cells[s][i]
                                OutlinedTextField(
                                    text, { d.cells[s][i] = it }, Modifier.padding(end = 6.dp, bottom = 4.dp), singleLine = true,
                                    isError = text.isNotBlank() && d.parse(text) == null,
                                )
                            }
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
