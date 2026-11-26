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
import androidx.compose.material3.Button
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
            Text(latency, Modifier.padding(start = 16.dp))
        }
    }
}
