package app.styli.ink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
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

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                InkCanvasView(context).also {
                    it.onLatency = { text -> latency = text }
                    canvas = it
                }
            },
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { canvas?.undo() }) { Text("Undo") }
            Text(latency, Modifier.padding(start = 16.dp))
        }
    }
}
