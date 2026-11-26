package app.styli.ink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.styli.ink.proto.PageSet
import app.styli.ink.proto.Template

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { InkScreen() } }
    }
}

// Phase 0a prototype only: not styled with the Styli design system on purpose.
@Composable
private fun InkScreen() {
    var latency by remember { mutableStateOf("Draw to measure latency") }
    var canvas by remember { mutableStateOf<InkCanvasView?>(null) }
    var infinite by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(Template.TEMPLATE_LINED) }
    var letter by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(InkCanvasView.Tool.PEN) }
    var units by remember { mutableStateOf(RulerUnits.METRIC) }
    var erasing by remember { mutableStateOf(EraseOnly.ALL) }
    var shade by remember { mutableStateOf(0) }
    var thick by remember { mutableStateOf(0) }
    // Penmanship, each 0 off, 1 low, 2 medium, 3 high (hold time: 0.3, 0.5, 0.8, 1.2 s)
    var pLines by remember { mutableStateOf(2) }
    var pShapes by remember { mutableStateOf(2) }
    var pLevel by remember { mutableStateOf(2) }
    var pHold by remember { mutableStateOf(1) }
    var solid by remember { mutableStateOf(false) }
    var gridScroll by remember { mutableStateOf(true) } // scrolling over pages: locked to the grid, or seamless
    var tableDialog by remember { mutableStateOf(false) }
    var tRows by remember { mutableStateOf(3) }
    var tCols by remember { mutableStateOf(3) }
    var tColor by remember { mutableStateOf(0) }
    var tFill by remember { mutableStateOf(0) }
    var tLine by remember { mutableStateOf(1) }
    var sColor by remember { mutableStateOf(0) }
    var sFill by remember { mutableStateOf(0) }
    var sLine by remember { mutableStateOf(1) }
    var sDashed by remember { mutableStateOf(false) }
    var bColor by remember { mutableStateOf(0x99FFEB3B.toInt()) }
    var zoom by remember { mutableStateOf(100) }
    var zoomShown by remember { mutableStateOf(false) }
    var firstZoom by remember { mutableStateOf(true) }
    var angleOn by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf(Route.STRAIGHT) }
    var auto by remember { mutableStateOf(false) }
    var startHead by remember { mutableStateOf(Arrowhead.NONE) }
    var endHead by remember { mutableStateOf(Arrowhead.FILLED) }
    var preset by remember { mutableStateOf(ShapePreset.RECTANGLE) }
    var scroll by remember { mutableStateOf(2) } // how far a scroll carries on: off, low, medium, high
    var cell by remember { mutableStateOf(0 to 0) } // the page's (column, row)
    var picking by remember { mutableStateOf(0) } // 0 none, 1 text colour, 2 highlight
    var textColor by remember { mutableStateOf(0) }
    var textHighlight by remember { mutableStateOf(0) }

    fun apply() {
        val (w, h) = PageSizes.of(letter, landscape)
        canvas?.setPage(PageSet.newBuilder().setWidth(w).setHeight(h).setTemplate(template).setInfinite(infinite).build())
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                InkCanvasView(context).also {
                    it.onLatency = { text -> latency = text }
                    it.onZoom = { percent -> zoom = percent }
                    it.onPageChanged = {
                        // The page buttons show the page that is now current.
                        val p = it.page
                        infinite = p.infinite; template = p.template; letter = PageSizes.isLetter(p) == true; landscape = PageSizes.isLandscape(p)
                        cell = p.col to p.row
                    }
                    canvas = it
                    apply()
                }
            },
        )
        Row(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val gap = Modifier.padding(start = 8.dp)
            Button(onClick = { canvas?.undo() }) { Text("Undo") }
            Button(onClick = { canvas?.redo() }, gap) { Text("Redo") }
            Button(onClick = { canvas?.deleteSelection() }, gap) { Text("Delete") }

            // The tool, then whatever that tool needs.
            Menu("Tool: " + toolName(tool)) { close ->
                for (t in InkCanvasView.Tool.entries) Choice(toolName(t), t == tool) {
                    tool = t
                    canvas?.tool = t
                    if (t == InkCanvasView.Tool.TABLE) tableDialog = true // ask for the rows and columns first
                    close()
                }
            }
            if (tool == InkCanvasView.Tool.ERASER_STROKE || tool == InkCanvasView.Tool.ERASER_PART) {
                Menu("Erases: " + eraseLabel(erasing)) { close ->
                    for (e in EraseOnly.entries) Choice(eraseLabel(e), e == erasing) { erasing = e; canvas?.eraseOnly = e; close() }
                }
            }
            if (tool == InkCanvasView.Tool.SHAPE) {
                Menu("Shape: " + preset.name.lowercase()) { close ->
                    for (p in ShapePreset.entries) Choice(p.name.lowercase(), p == preset) { preset = p; canvas?.shapePreset = p; close() }
                }
            }
            if (tool == InkCanvasView.Tool.TABLE) {
                Button(onClick = { tableDialog = true }, gap) { Text("Table: $tRows rows, $tCols columns", softWrap = false) }
            }
            if (tool == InkCanvasView.Tool.LINE || tool == InkCanvasView.Tool.LINE_HIGHLIGHTER || tool == InkCanvasView.Tool.LINE_DOTTED) {
                Button(onClick = { angleOn = !angleOn; canvas?.lineAngle = angleOn }, gap) { Text("Angle: " + if (angleOn) "shown, no snap" else "off", softWrap = false) }
            }
            if (tool == InkCanvasView.Tool.CONNECTOR) {
                Menu("Route: " + routeLabel(route)) { close ->
                    for (r in Route.entries) Choice(routeLabel(r), r == route) { route = r; canvas?.connectorRoute = r; close() }
                }
                Menu("Attach: " + if (auto) "aim at other end" else "exact spot") { close ->
                    Choice("exact spot (snaps to side middles)", !auto) { auto = false; canvas?.connectAuto = false; close() }
                    Choice("aim at the other end", auto) { auto = true; canvas?.connectAuto = true; close() }
                }
                Menu("Start: " + startHead.name.lowercase()) { close ->
                    for (h in Arrowhead.entries) Choice(h.name.lowercase(), h == startHead) { startHead = h; canvas?.connectorFromHead = h; close() }
                }
                Menu("End: " + endHead.name.lowercase()) { close ->
                    for (h in Arrowhead.entries) Choice(h.name.lowercase(), h == endHead) { endHead = h; canvas?.connectorToHead = h; close() }
                }
            }
            if (tool == InkCanvasView.Tool.BUCKET) {
                Button(onClick = { picking = 7 }, gap) { Text("Fill colour", softWrap = false) }
            }
            if (tool == InkCanvasView.Tool.TEXT) {
                Button(onClick = { picking = 1 }, gap) { Text("Text colour", softWrap = false) }
                Button(onClick = { picking = 2 }, gap) { Text("Highlight", softWrap = false) }
                Button(onClick = { canvas?.finishText() }, gap) { Text("Done") }
            }

            if (tool == InkCanvasView.Tool.LASSO) {
                Button(onClick = { canvas?.addBendPoint() }, gap) { Text("Add bend point", softWrap = false) }
            }

            Menu("Ink") { close ->
                Heading("Colour")
                for (k in 0..2) Choice("Colour ${k + 1}", shade == k) {
                    shade = k
                    canvas?.apply {
                        penColor = listOf(0xFF000000.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt())[k]
                        highlighterColor = listOf(0x99FFEB3B.toInt(), 0x9966BB6A.toInt(), 0x99F06292.toInt())[k]
                    }
                    close()
                }
                Heading("Width")
                for (k in 0..2) Choice(listOf("Fine", "Medium", "Broad")[k], thick == k) {
                    thick = k
                    canvas?.apply {
                        penWidth = listOf(40, 80, 160)[k]
                        highlighterWidth = listOf(400, 700, 1000)[k]
                    }
                    close()
                }
            }

            // What the lasso has selected: layers, then the settings of each kind of thing.
            Menu("Selection") { close ->
                Heading("Layers")
                Item("To front") { canvas?.arrange(Arrange.FRONT); close() }
                Item("Forward") { canvas?.arrange(Arrange.FORWARD); close() }
                Item("Backward") { canvas?.arrange(Arrange.BACKWARD); close() }
                Item("To back") { canvas?.arrange(Arrange.BACK); close() }
                Heading("Text")
                Item("Text colour") { picking = 1; close() }
                Item("Highlight") { picking = 2; close() }
                Heading("Shape and connector lines")
                Item("Outline colour") { picking = 5; close() }
                Item("Fill colour (shapes)") { picking = 6; close() }
                for (k in 0..3) Choice("Line: " + listOf("thin", "normal", "thick", "bold")[k], sLine == k) {
                    sLine = k; canvas?.setShapeStyle(null, null, listOf(20f, 40f, 80f, 160f)[k], null); close()
                }
                Choice("Line: solid", !sDashed) { sDashed = false; canvas?.setShapeStyle(null, null, null, false); close() }
                Choice("Line: dashed", sDashed) { sDashed = true; canvas?.setShapeStyle(null, null, null, true); close() }
                Heading("Connector (select it alone: drag its handles to bend or re-attach)")
                for (r in Route.entries) Item("Route: " + routeLabel(r)) { canvas?.routeSelected(r); close() }
                Item("Add bend point (then tap the line)") { canvas?.addBendPoint(); close() }
                Item("Remove bend point") { canvas?.removeBendPoint(); close() }
                Heading("Connector arrowheads")
                for (h in Arrowhead.entries) Item("Start: " + h.name.lowercase()) { canvas?.setConnectorHeads(h, null); close() }
                for (h in Arrowhead.entries) Item("End: " + h.name.lowercase()) { canvas?.setConnectorHeads(null, h); close() }
                Heading("Table (tap a cell first for rows and columns)")
                Item("Row above") { canvas?.insertRow(false); close() }
                Item("Row below") { canvas?.insertRow(true); close() }
                Item("Column left") { canvas?.insertColumn(false); close() }
                Item("Column right") { canvas?.insertColumn(true); close() }
                Item("Delete row") { canvas?.deleteRow(); close() }
                Item("Delete column") { canvas?.deleteColumn(); close() }
                Item("Lines colour") { picking = 3; close() }
                Item("Fill colour (whole table)") { picking = 4; close() }
                for (k in 0..3) Choice("Lines: " + listOf("thin", "normal", "thick", "bold")[k], tLine == k) {
                    tLine = k; canvas?.setTableStyle(null, null, listOf(20f, 40f, 80f, 160f)[k]); close()
                }
            }

            Menu("Ruler") { close ->
                Choice(if (canvas?.ruler != null) "Hide ruler" else "Show ruler", false) { canvas?.toggleRuler(); close() }
                Choice("Angle shown on lines", angleOn) { angleOn = !angleOn; canvas?.lineAngle = angleOn; close() }
                Heading("Units")
                for (u in RulerUnits.entries) Choice(u.name.lowercase(), u == units) { units = u; canvas?.rulerUnits = u; close() }
            }

            // Hold the pen still at the end of a stroke and a line or shape is drawn clean. These stay open to change several.
            Menu("Pen assist") { _ ->
                val strengths = listOf(0f, 0.25f, 1f, 2.5f)
                val names = listOf("off", "low", "medium", "high")
                fun penmanship() {
                    canvas?.penmanship = ShapeGeometry.Settings(
                        strengths[pLines], strengths[pShapes], strengths[pLevel], listOf(300L, 500L, 800L, 1200L)[pHold],
                    )
                }
                Item("Lines: ${names[pLines]}") { pLines = (pLines + 1) % 4; penmanship() }
                Item("Shapes: ${names[pShapes]}") { pShapes = (pShapes + 1) % 4; penmanship() }
                Item("Level and upright: ${names[pLevel]}") { pLevel = (pLevel + 1) % 4; penmanship() }
                Item("Hold: ${listOf("0.3", "0.5", "0.8", "1.2")[pHold]} s") { pHold = (pHold + 1) % 4; penmanship() }
            }

            Menu("Page") { _ ->
                Item(if (infinite) "Canvas (tap for page)" else "Page (tap for canvas)") { infinite = !infinite; apply() }
                Item("Paper: " + template.name.removePrefix("TEMPLATE_").lowercase()) {
                    template = when (template) {
                        Template.TEMPLATE_BLANK -> Template.TEMPLATE_LINED
                        Template.TEMPLATE_LINED -> Template.TEMPLATE_GRID
                        else -> Template.TEMPLATE_BLANK
                    }
                    apply()
                }
                Item("Size: " + if (letter) "Letter" else "A4") { letter = !letter; apply() }
                Item("Orientation: " + if (landscape) "landscape" else "portrait") { landscape = !landscape; apply() }
                Item("Scrolling: " + if (gridScroll) "grid (pull past the end to add a page)" else "seamless (no adding pages)") {
                    gridScroll = !gridScroll; canvas?.scrollMode = if (gridScroll) ScrollMode.GRID else ScrollMode.SEAMLESS
                }
                Item("Scroll glide: " + listOf("off", "low", "medium", "high")[scroll]) {
                    scroll = (scroll + 1) % 4; canvas?.scrollIntensity = listOf(0.0, 0.5, 1.0, 2.0)[scroll]
                }
                Item("Selection outline: " + if (solid) "solid" else "dashed") { solid = !solid; canvas?.solidOutline = solid }
            }
            Text("Page row ${cell.second + 1}, column ${cell.first + 1} of ${canvas?.notebook?.pages?.size ?: 1}", Modifier.padding(start = 16.dp))
            Text(latency, Modifier.padding(start = 16.dp))
        }
        // The zoom, as a percentage of a page fitted to the screen, with a button to fit it. It shows while the zoom
        // changes and a moment after, and stays while it is not 100.
        LaunchedEffect(zoom) {
            if (firstZoom) { firstZoom = false; return@LaunchedEffect }
            zoomShown = true
            delay(2500)
            zoomShown = false
        }
        if (zoomShown || zoom != 100) {
            Surface(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 4.dp,
            ) {
                Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$zoom%")
                    TextButton(onClick = { canvas?.fitToScreen() }, Modifier.padding(start = 8.dp)) { Text("Fit to screen") }
                }
            }
        }
        if (tableDialog) {
            AlertDialog(
                onDismissRequest = { tableDialog = false },
                confirmButton = { TextButton(onClick = { tableDialog = false }) { Text("Done") } },
                title = { Text("Table") },
                text = {
                    Column {
                        Text("Then drag on the page to draw it.")
                        for ((label, value, set) in listOf(
                            Triple("Rows", tRows) { v: Int -> tRows = v; canvas?.tableRows = v },
                            Triple("Columns", tCols) { v: Int -> tCols = v; canvas?.tableCols = v },
                        )) {
                            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.padding(end = 16.dp))
                                Button(onClick = { set((value - 1).coerceAtLeast(1)) }) { Text("-") }
                                Text("$value", Modifier.padding(horizontal = 16.dp))
                                Button(onClick = { set((value + 1).coerceAtMost(Table.MAX_LINES)) }) { Text("+") }
                            }
                        }
                    }
                },
            )
        }
        if (picking != 0) {
            val text = picking == 1 || picking == 3 || picking == 5 // the palette that starts with the default ink colour, not "none"
            val palette = when (picking) { 1, 3, 5 -> TEXT_COLOURS; 2 -> HIGHLIGHTS; else -> FILLS }
            AlertDialog(
                onDismissRequest = { picking = 0 },
                confirmButton = { TextButton(onClick = { picking = 0 }) { Text("Close") } },
                title = { Text(when (picking) { 1 -> "Text colour"; 2 -> "Highlight"; 3 -> "Table lines colour"; 5 -> "Shape outline"; 6 -> "Shape fill"; 7 -> "Fill colour"; else -> "Table fill" }) },
                text = {
                    Column {
                        palette.chunked(5).forEach { row ->
                            Row(Modifier.padding(bottom = 8.dp)) {
                                row.forEach { argb ->
                                    val chosen = argb == when (picking) { 1 -> textColor; 2 -> textHighlight; 3 -> tColor; 5 -> sColor; 6 -> sFill; 7 -> bColor; else -> tFill }
                                    Box(
                                        Modifier.padding(end = 8.dp).size(44.dp).clip(CircleShape)
                                            .background(if (argb ushr 24 == 0) Color.White else Color(argb))
                                            .border(if (chosen) 3.dp else 1.dp, if (chosen) Color.Black else Color.Gray, CircleShape)
                                            .clickable {
                                                when (picking) {
                                                    1 -> { textColor = argb; canvas?.setTextStyle(textColor, textHighlight) }
                                                    2 -> { textHighlight = argb; canvas?.setTextStyle(textColor, textHighlight) }
                                                    3 -> { tColor = argb; canvas?.setTableStyle(argb, null, null) }
                                                    5 -> { sColor = argb; canvas?.setShapeStyle(argb, null, null, null) }
                                                    6 -> { sFill = argb; canvas?.setShapeStyle(null, argb, null, null) }
                                                    7 -> { bColor = argb; canvas?.bucketColor = argb }
                                                    else -> { tFill = argb; canvas?.setTableStyle(null, argb, null) }
                                                }
                                                picking = 0
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) { if (argb ushr 24 == 0) Text(if (text) "A" else "none", softWrap = false) }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

// Throwaway swatches for the text tool; the real picker comes with the tool rail. Zero alpha = the default.
private val TEXT_COLOURS = listOf(
    0, 0xFF000000, 0xFF616161, 0xFFC62828, 0xFFEF6C00, 0xFFF9A825, 0xFF2E7D32, 0xFF00838F, 0xFF1565C0, 0xFF4527A0, 0xFF7B1FA2, 0xFFAD1457,
).map { it.toInt() }

private val HIGHLIGHTS = listOf(
    0, 0x99FFEB3B, 0x9966BB6A, 0x99F06292, 0x994FC3F7, 0x99FFB74D, 0x99BA68C8, 0x994DB6AC, 0x99EF5350, 0x99BDBDBD,
).map { it.toInt() }

// Colours for fills: none, the see-through highlights, then some solid soft ones.
private val FILLS = listOf(
    0, 0x99FFEB3B, 0x9966BB6A, 0x99F06292, 0x994FC3F7, 0x99FFB74D,
    0xFFFFF59D, 0xFFA5D6A7, 0xFFF8BBD0, 0xFF81D4FA, 0xFFFFCC80, 0xFFE1BEE7, 0xFFFFFFFF, 0xFFBDBDBD, 0xFF616161,
).map { it.toInt() }

private fun toolName(t: InkCanvasView.Tool) = when (t) {
    InkCanvasView.Tool.PEN -> "Pen"
    InkCanvasView.Tool.HIGHLIGHTER -> "Highlighter"
    InkCanvasView.Tool.ERASER_STROKE -> "Eraser (whole stroke)"
    InkCanvasView.Tool.ERASER_PART -> "Eraser (part of a stroke)"
    InkCanvasView.Tool.LASSO -> "Lasso"
    InkCanvasView.Tool.LINE -> "Line"
    InkCanvasView.Tool.LINE_HIGHLIGHTER -> "Line highlighter"
    InkCanvasView.Tool.DOTTED -> "Dotted pen"
    InkCanvasView.Tool.LINE_DOTTED -> "Dotted line"
    InkCanvasView.Tool.LASER -> "Laser pointer"
    InkCanvasView.Tool.TEXT -> "Text"
    InkCanvasView.Tool.TABLE -> "Table"
    InkCanvasView.Tool.SHAPE -> "Shape"
    InkCanvasView.Tool.BUCKET -> "Paint bucket"
    InkCanvasView.Tool.CONNECTOR -> "Connector"
}

/** A button that opens a menu. [content] gets a function that closes it (some items leave it open). */
@Composable
private fun Menu(label: String, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 8.dp)) {
        Button(onClick = { open = true }) { Text("$label \u25BE", softWrap = false) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

@Composable
private fun Item(text: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = onClick)

/** A menu item that says if it is the one in use. */
@Composable
private fun Choice(text: String, chosen: Boolean, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(if (chosen) "\u2022 $text" else "   $text") }, onClick = onClick)

@Composable
private fun Heading(text: String) = Text(text, Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium)

private fun routeLabel(r: Route) = when (r) { Route.STRAIGHT -> "straight"; Route.ELBOW -> "right angles"; Route.CURVE -> "rounded" }

private fun eraseLabel(e: EraseOnly) = when (e) {
    EraseOnly.ALL -> "everything"
    EraseOnly.INK -> "ink only"
    EraseOnly.HIGHLIGHTS -> "highlights only"
    EraseOnly.LINES -> "straight lines only"
    EraseOnly.TEXT_BOXES -> "text boxes only"
    EraseOnly.SHAPES -> "shapes only"
    EraseOnly.CONNECTORS -> "connectors only"
    EraseOnly.TABLES -> "tables only"
    EraseOnly.FILLS -> "fills only"
}
