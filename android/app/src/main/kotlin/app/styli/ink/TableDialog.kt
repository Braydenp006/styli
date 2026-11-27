package app.styli.ink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The table being edited as text: the whole grid, the header row and each column's alignment. */
private class TableDraft(t: Table) {
    var header by mutableStateOf(t.headerRow)
    val align = mutableStateListOf<Int>().also { l -> repeat(t.cols) { l.add(t.alignOf(it)) } }
    val cells = mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateList<String>>().also { g ->
        for (r in 0 until t.rows) g.add(mutableStateListOf(*(0 until t.cols).map { t.cellTextAt(r, it) }.toTypedArray()))
    }
    var fromRow by mutableStateOf("1"); var toRow by mutableStateOf(t.rows.toString())
    var fromCol by mutableStateOf("1"); var toCol by mutableStateOf(t.cols.toString())
    var inRows by mutableStateOf(false)

    val rows get() = cells.size
    val cols get() = align.size

    fun grid(): List<List<String>> = cells.map { it.toList() }

    fun addRow() { if (rows < Table.MAX_LINES) cells.add(mutableStateListOf(*Array(cols) { "" })) }
    fun addCol() { if (cols < Table.MAX_LINES) { align.add(0); cells.forEach { it.add("") } } }

    /** Puts [block] (rows of text from a spreadsheet) in at the top left, growing the grid to hold it. */
    fun paste(block: List<List<String>>) {
        while (rows < minOf(block.size, Table.MAX_LINES)) addRow()
        while (cols < minOf(block.maxOfOrNull { it.size } ?: 0, Table.MAX_LINES)) addCol()
        for ((r, line) in block.take(Table.MAX_LINES).withIndex()) for ((c, v) in line.take(Table.MAX_LINES).withIndex()) cells[r][c] = v
    }

    /** The block of cells named by the four numbers (counted from 1) as (row, col, rows, cols) from 0, or null if they are not a block of at least 2 by 2. */
    fun block(): List<Int>? {
        val r0 = fromRow.trim().toIntOrNull() ?: return null; val r1 = toRow.trim().toIntOrNull() ?: return null
        val c0 = fromCol.trim().toIntOrNull() ?: return null; val c1 = toCol.trim().toIntOrNull() ?: return null
        if (r0 < 1 || c0 < 1 || r1 > rows || c1 > cols || r1 - r0 < 1 || c1 - c0 < 1) return null
        return listOf(r0 - 1, c0 - 1, r1 - r0 + 1, c1 - c0 + 1)
    }
}

/**
 * The window where a table's typed text is entered: the whole table as a grid of fields, a header row tick, an alignment for
 * each column (left, middle, right), paste from and copy to a spreadsheet, and "Make chart", which draws a chart that follows
 * a block of the cells. Throwaway looks. [onDone] gets the grid, the header tick and the alignments.
 */
@Composable
fun TableDialog(
    table: Table,
    onDone: (grid: List<List<String>>, header: Boolean, align: List<Int>) -> Unit,
    onChart: (grid: List<List<String>>, header: Boolean, align: List<Int>, row: Int, col: Int, rows: Int, cols: Int, seriesInRows: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val d = remember(table) { TableDraft(table) }
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = StyShapes.Lg, color = LocalSty.current.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, LocalSty.current.border),
        ) {
            Column(Modifier.padding(16.dp)) {
                DialogTitle("Table data")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(d.header, { d.header = it }); Text("Header row")
                        TextButton(onClick = {
                            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            d.paste(TableChart.parseDelimited(text))
                        }) { Text("Paste from clipboard") }
                        TextButton(onClick = {
                            val grid = d.grid()
                            clipboard.setPrimaryClip(ClipData.newPlainText("table", grid.joinToString("\n") { r -> r.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } }))
                        }) { Text("Copy as text") }
                    }
                    Column(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        Row { // each column's alignment: tap to change
                            for (c in 0 until d.cols) TextButton(onClick = { d.align[c] = (d.align[c] + 1) % 3 }, modifier = Modifier.width(150.dp)) { Text("Column ${c + 1}: " + listOf("left", "middle", "right")[d.align[c]]) }
                        }
                        for (r in 0 until d.rows) Row(verticalAlignment = Alignment.CenterVertically) {
                            for (c in 0 until d.cols) OutlinedTextField(d.cells[r][c], { d.cells[r][c] = it }, Modifier.width(150.dp).padding(end = 6.dp, bottom = 4.dp), singleLine = true)
                        }
                    }
                    Row {
                        TextButton(onClick = { d.addRow() }, enabled = d.rows < Table.MAX_LINES) { Text("Add row") }
                        TextButton(onClick = { d.addCol() }, enabled = d.cols < Table.MAX_LINES) { Text("Add column") }
                    }
                    Text("Chart from cells", Modifier.padding(top = 12.dp), color = LocalSty.current.ink, fontFamily = DisplayFont, fontSize = 17.sp)
                    Text("Draws a chart that follows these cells: the first row and column are the names, the rest the numbers.", Modifier.padding(bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(d.fromRow, { d.fromRow = it }, Modifier.width(110.dp), label = { Text("First row") }, singleLine = true)
                        OutlinedTextField(d.toRow, { d.toRow = it }, Modifier.width(110.dp), label = { Text("Last row") }, singleLine = true)
                        OutlinedTextField(d.fromCol, { d.fromCol = it }, Modifier.width(110.dp), label = { Text("First column") }, singleLine = true)
                        OutlinedTextField(d.toCol, { d.toCol = it }, Modifier.width(110.dp), label = { Text("Last column") }, singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(d.inRows, { d.inRows = it }); Text("Each row is a series (otherwise each column)")
                    }
                    TextButton(onClick = { d.block()?.let { (r, c, rs, cs) -> onChart(d.grid(), d.header, d.align.toList(), r, c, rs, cs, d.inRows) } }, enabled = d.block() != null) { Text("Make chart") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onDone(d.grid(), d.header, d.align.toList()) }) { Text("Done") }
                }
            }
        }
    }
}
