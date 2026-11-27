package app.styli.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small labelled choice with an icon (a pen type, an eraser kind). */
@Composable
fun OptionChip(label: String, icon: String?, chosen: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val sty = LocalSty.current
    Column(
        Modifier.width(64.dp).alpha(if (enabled) 1f else 0.4f).clip(SquircleShape(StySize.radiusMd)).background(if (chosen) sty.honeyTint else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) SvgIcon(icon, sty.ink, 24.dp)
        Text(label, color = if (chosen) sty.ink else sty.inkMuted, fontSize = 11.sp, maxLines = 1)
    }
}

/** A row with a tick box, for filters where several can be on at once. */
@Composable
fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val sty = LocalSty.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 44.dp).clip(SquircleShape(StySize.radiusMd)).clickable(onClick = onToggle).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(SquircleShape(6.dp)).background(if (checked) sty.honey else Color.Transparent).border(1.5.dp, if (checked) sty.honeyText else sty.borderStrong, SquircleShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) SvgIcon("M20 6L9 17l-5-5", sty.onHoney, 16.dp) }
        Text(label, color = sty.ink, fontSize = 14.sp)
    }
}

/** The title of a block inside a popout. */
@Composable
fun PopHeading(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), color = LocalSty.current.inkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

/** A colour dot. */
@Composable
fun Dot(color: Int, chosen: Boolean, size: androidx.compose.ui.unit.Dp = 32.dp) {
    val sty = LocalSty.current
    Box(Modifier.size(size).clip(CircleShape).background(Color(color).copy(alpha = 1f)).border(if (chosen) 3.dp else 1.dp, if (chosen) sty.honeyText else sty.borderStrong, CircleShape))
}

/**
 * A colour picker: a square for how strong and how light, a bar for the hue, the colour so far, and an eyedropper.
 * [onChange] gets an opaque colour.
 */
@Composable
fun ColorPicker(color: Int, onChange: (Int) -> Unit, onEyedropper: () -> Unit) {
    val sty = LocalSty.current
    var h by remember { mutableFloatStateOf(0f) }
    var s by remember { mutableFloatStateOf(0f) }
    var v by remember { mutableFloatStateOf(0f) }
    fun rgb() = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    // Follow a colour set from outside (a saved colour, the eyedropper), but not our own drags (they lose the hue near black and white).
    LaunchedEffect(color) {
        if (rgb() != (color or 0xFF000000.toInt())) {
            val a = FloatArray(3); android.graphics.Color.colorToHSV(color, a); h = a[0]; s = a[1]; v = a[2]
        }
    }
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(110.dp).clip(SquircleShape(StySize.radiusMd))
                .pointerInput(Unit) {
                    fun set(p: Offset) { s = (p.x / size.width).coerceIn(0f, 1f); v = 1f - (p.y / size.height).coerceIn(0f, 1f); onChange(rgb()) }
                    detectDragGestures(onDragStart = { set(it) }) { c, _ -> set(c.position) }
                }
                .pointerInput(Unit) { detectTapGestures { p -> s = (p.x / size.width).coerceIn(0f, 1f); v = 1f - (p.y / size.height).coerceIn(0f, 1f); onChange(rgb()) } },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(s * size.width, (1 - v) * size.height)
            drawCircle(Color.White, 9f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f)); drawCircle(Color.Black, 10.5f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
        Canvas(
            Modifier.fillMaxWidth().height(22.dp).clip(CircleShape)
                .pointerInput(Unit) {
                    fun set(p: Offset) { h = (p.x / size.width).coerceIn(0f, 1f) * 360f; onChange(rgb()) }
                    detectDragGestures(onDragStart = { set(it) }) { c, _ -> set(c.position) }
                }
                .pointerInput(Unit) { detectTapGestures { p -> h = (p.x / size.width).coerceIn(0f, 1f) * 360f; onChange(rgb()) } },
        ) {
            drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
            val x = h / 360f * size.width
            drawCircle(Color.White, size.height / 2, Offset(x, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(color, false, 28.dp)
            Text("#%06X".format(color and 0xFFFFFF), color = sty.inkMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            OptionChip("Eyedropper", Icons24.LASER, false, onClick = onEyedropper)
        }
    }
}

/** The colours the user has kept (one list for the pen, one for the highlighter). Tap to use, hold to remove, plus to keep the current one. */
@Composable
fun SavedColours(saved: SnapshotStateList<Int>, current: Int, onPick: (Int) -> Unit) {
    val sty = LocalSty.current
    val cur = current or 0xFF000000.toInt()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val cells: List<Int?> = saved.toList() + (if (saved.size < MAX_SAVED && saved.none { (it or 0xFF000000.toInt()) == cur }) listOf(null) else emptyList())
        if (saved.isEmpty()) Text("Tap + to keep this colour here. Hold a saved colour to remove it.", color = sty.inkMuted, fontSize = 12.sp)
        for (row in cells.chunked(7)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in row) {
                if (c == null) Box(
                    Modifier.size(32.dp).clip(CircleShape).border(1.dp, sty.borderStrong, CircleShape).clickable { saved.add(cur) },
                    contentAlignment = Alignment.Center,
                ) { SvgIcon(Icons24.PLUS, sty.ink, 16.dp) }
                else Box(Modifier.pointerInput(c) { detectTapGestures(onTap = { onPick(c) }, onLongPress = { saved.remove(c) }) }) { Dot(c, (c or 0xFF000000.toInt()) == cur) }
            }
        }
    }
}

const val MAX_SAVED = 14

/** A line drawn at [units] (hundredths of a millimetre) as it is on paper. */
@Composable
fun StrokeSample(units: Int, color: Int, modifier: Modifier = Modifier) {
    val mmPx = LocalContext.current.resources.displayMetrics.xdpi / 25.4f
    Canvas(modifier) {
        val w = (units / 100f * mmPx).coerceIn(1f, size.height)
        drawLine(Color(color).copy(alpha = Color(color).alpha.coerceAtLeast(0.35f)), Offset(w / 2, size.height / 2), Offset(size.width - w / 2, size.height / 2), w, StrokeCap.Round)
    }
}

/** Widths as real samples: a few presets, and a slider with a sample line that follows it. */
@Composable
fun WidthPicker(width: Int, presets: List<Int>, max: Int, color: Int, onChange: (Int) -> Unit) {
    val sty = LocalSty.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (w in presets) {
            val on = w == width
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(SquircleShape(StySize.radiusMd)).background(if (on) sty.honeyTint else Color.Transparent)
                    .clickable { onChange(w) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StrokeSample(w, color, Modifier.weight(1f).height(40.dp))
                Text("%.2g mm".format(w / 100f), color = sty.inkMuted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
            }
        }
        Slider(value = width.toFloat(), onValueChange = { onChange(it.toInt().coerceAtLeast(5)) }, valueRange = 5f..max.toFloat())
    }
}

/** The pen or highlighter popout: type, colour, saved colours and width. */
@Composable
fun InkPopout(
    types: List<InkCanvasView.Tool>, tool: InkCanvasView.Tool, onTool: (InkCanvasView.Tool) -> Unit,
    color: Int, onColor: (Int) -> Unit, saved: SnapshotStateList<Int>, onEyedropper: () -> Unit,
    width: Int, presets: List<Int>, maxWidth: Int, onWidth: (Int) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(Modifier.width(312.dp).padding(vertical = 8.dp)) {
        PopHeading("Type")
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (t in types) OptionChip(toolLabel(t), toolIcon(t), t == tool) { onTool(t) }
        }
        extra()
        PopHeading("Colour")
        ColorPicker(color, onColor, onEyedropper)
        PopHeading("Saved colours")
        SavedColours(saved, color, onColor)
        PopHeading("Width")
        WidthPicker(width, presets, maxWidth, color, onWidth)
    }
}

fun toolLabel(t: InkCanvasView.Tool) = when (t) {
    InkCanvasView.Tool.PEN -> "Pen"
    InkCanvasView.Tool.HIGHLIGHTER -> "Highlighter"
    InkCanvasView.Tool.ERASER_STROKE -> "Whole eraser"
    InkCanvasView.Tool.ERASER_PART -> "Part eraser"
    InkCanvasView.Tool.LASSO -> "Lasso"
    InkCanvasView.Tool.LINE -> "Line"
    InkCanvasView.Tool.LINE_HIGHLIGHTER -> "Line marker"
    InkCanvasView.Tool.DOTTED -> "Dotted pen"
    InkCanvasView.Tool.LINE_DOTTED -> "Dotted line"
    InkCanvasView.Tool.LASER -> "Laser"
    InkCanvasView.Tool.TEXT -> "Text"
    InkCanvasView.Tool.TABLE -> "Table"
    InkCanvasView.Tool.SHAPE -> "Shape"
    InkCanvasView.Tool.BUCKET -> "Bucket"
    InkCanvasView.Tool.CONNECTOR -> "Connector"
    InkCanvasView.Tool.CHART -> "Chart"
}
