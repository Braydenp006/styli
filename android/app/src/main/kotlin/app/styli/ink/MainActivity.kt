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
import androidx.compose.material3.Button
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
    var shade by remember { mutableStateOf(0) }
    var thick by remember { mutableStateOf(0) }
    // Penmanship, each 0 off, 1 low, 2 medium, 3 high (hold time: 0.3, 0.5, 0.8, 1.2 s)
    var pLines by remember { mutableStateOf(2) }
    var pShapes by remember { mutableStateOf(2) }
    var pLevel by remember { mutableStateOf(2) }
    var pHold by remember { mutableStateOf(1) }
    var solid by remember { mutableStateOf(false) }
    var flow by remember { mutableStateOf(0) } // which pulls add pages: down, right, both
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
            Button(onClick = { canvas?.undo() }) { Text("Undo") }
            Button(onClick = { canvas?.redo() }, Modifier.padding(start = 8.dp)) { Text("Redo") }
            Button(onClick = { canvas?.deleteSelection() }, Modifier.padding(start = 8.dp)) { Text("Delete") }
            Button(
                onClick = {
                    tool = InkCanvasView.Tool.entries[(tool.ordinal + 1) % InkCanvasView.Tool.entries.size]
                    canvas?.tool = tool
                },
                Modifier.padding(start = 8.dp),
            ) { Text(tool.name.lowercase().replace('_', ' ')) }
            Button(
                onClick = {
                    shade = (shade + 1) % 3
                    canvas?.apply {
                        penColor = listOf(0xFF000000.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt())[shade]
                        highlighterColor = listOf(0x99FFEB3B.toInt(), 0x9966BB6A.toInt(), 0x99F06292.toInt())[shade]
                    }
                },
                Modifier.padding(start = 8.dp),
            ) { Text("Colour ${shade + 1}") }
            Button(
                onClick = {
                    thick = (thick + 1) % 3
                    canvas?.apply {
                        penWidth = listOf(40, 80, 160)[thick]
                        highlighterWidth = listOf(400, 700, 1000)[thick]
                    }
                },
                Modifier.padding(start = 8.dp),
            ) { Text("Width ${thick + 1}") }
            val strengths = listOf(0f, 0.25f, 1f, 2.5f)
            val names = listOf("off", "low", "medium", "high")
            fun penmanship() {
                canvas?.penmanship = ShapeGeometry.Settings(
                    strengths[pLines], strengths[pShapes], strengths[pLevel], listOf(300L, 500L, 800L, 1200L)[pHold],
                )
            }
            Button(onClick = { pLines = (pLines + 1) % 4; penmanship() }, Modifier.padding(start = 8.dp)) { Text("Lines: ${names[pLines]}", softWrap = false) }
            Button(onClick = { pShapes = (pShapes + 1) % 4; penmanship() }, Modifier.padding(start = 8.dp)) { Text("Shapes: ${names[pShapes]}", softWrap = false) }
            Button(onClick = { pLevel = (pLevel + 1) % 4; penmanship() }, Modifier.padding(start = 8.dp)) { Text("Level: ${names[pLevel]}", softWrap = false) }
            Button(onClick = { pHold = (pHold + 1) % 4; penmanship() }, Modifier.padding(start = 8.dp)) {
                Text("Hold: ${listOf("0.3", "0.5", "0.8", "1.2")[pHold]} s", softWrap = false)
            }
            if (tool == InkCanvasView.Tool.TEXT || tool == InkCanvasView.Tool.LASSO) {
                Button(onClick = { picking = 1 }, Modifier.padding(start = 8.dp)) { Text("Text colour") }
                Button(onClick = { picking = 2 }, Modifier.padding(start = 8.dp)) { Text("Highlight") }
                if (tool == InkCanvasView.Tool.TEXT) Button(onClick = { canvas?.finishText() }, Modifier.padding(start = 8.dp)) { Text("Done") }
            }
            Button(onClick = { solid = !solid; canvas?.solidOutline = solid }, Modifier.padding(start = 8.dp)) {
                Text("Outline: " + if (solid) "solid" else "dashed", softWrap = false)
            }
            Button(onClick = { infinite = !infinite; apply() }, Modifier.padding(start = 8.dp)) {
                Text(if (infinite) "Canvas" else "Page")
            }
            Button(
                onClick = {
                    template = when (template) {
                        Template.TEMPLATE_BLANK -> Template.TEMPLATE_LINED
                        Template.TEMPLATE_LINED -> Template.TEMPLATE_GRID
                        else -> Template.TEMPLATE_BLANK
                    }
                    apply()
                },
                Modifier.padding(start = 8.dp),
            ) { Text(template.name.removePrefix("TEMPLATE_").lowercase()) }
            Button(onClick = { letter = !letter; apply() }, Modifier.padding(start = 8.dp)) { Text(if (letter) "Letter" else "A4") }
            Button(onClick = { landscape = !landscape; apply() }, Modifier.padding(start = 8.dp)) {
                Text(if (landscape) "Landscape" else "Portrait", softWrap = false)
            }
            // The rest of the page settings: which pulls add pages, and how far a scroll carries on.
            Button(onClick = { flow = (flow + 1) % 3; canvas?.flow = Flow.entries[flow] }, Modifier.padding(start = 8.dp)) {
                Text("Pages: " + listOf("down", "right", "both")[flow], softWrap = false)
            }
            Button(onClick = { scroll = (scroll + 1) % 4; canvas?.scrollIntensity = listOf(0.0, 0.5, 1.0, 2.0)[scroll] }, Modifier.padding(start = 8.dp)) {
                Text("Scroll: " + listOf("off", "low", "medium", "high")[scroll], softWrap = false)
            }
            Text("Page row ${cell.second + 1}, column ${cell.first + 1} of ${canvas?.notebook?.pages?.size ?: 1}", Modifier.padding(start = 16.dp))
            Text(latency, Modifier.padding(start = 16.dp))
        }
        if (picking != 0) {
            val text = picking == 1
            AlertDialog(
                onDismissRequest = { picking = 0 },
                confirmButton = { TextButton(onClick = { picking = 0 }) { Text("Close") } },
                title = { Text(if (text) "Text colour" else "Highlight") },
                text = {
                    Column {
                        (if (text) TEXT_COLOURS else HIGHLIGHTS).chunked(5).forEach { row ->
                            Row(Modifier.padding(bottom = 8.dp)) {
                                row.forEach { argb ->
                                    val chosen = argb == (if (text) textColor else textHighlight)
                                    Box(
                                        Modifier.padding(end = 8.dp).size(44.dp).clip(CircleShape)
                                            .background(if (argb ushr 24 == 0) Color.White else Color(argb))
                                            .border(if (chosen) 3.dp else 1.dp, if (chosen) Color.Black else Color.Gray, CircleShape)
                                            .clickable {
                                                if (text) textColor = argb else textHighlight = argb
                                                canvas?.setTextStyle(textColor, textHighlight)
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
