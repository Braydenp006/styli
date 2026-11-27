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
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.rotate
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
        Modifier.width(64.dp).alpha(if (enabled) 1f else 0.4f).clip(StyShapes.Md).background(if (chosen) sty.honeyTint else Color.Transparent)
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
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 44.dp).clip(StyShapes.Md).clickable(onClick = onToggle).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(StyShapes.Sm).background(if (checked) sty.honey else Color.Transparent).border(1.5.dp, if (checked) sty.honeyText else sty.borderStrong, StyShapes.Sm),
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
            Modifier.fillMaxWidth().height(110.dp).clip(StyShapes.Md)
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
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(StyShapes.Md).background(if (on) sty.honeyTint else Color.Transparent)
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

/**
 * A choice of one from a few, as one row you step through with the arrows either side. With more than four options it
 * becomes an accordion (the current one on a row; tap it to open the list), so nothing gets cramped.
 */
@Composable
fun <T> CycleRow(title: String, options: List<T>, current: T, label: (T) -> String, icon: (T) -> String?, onPick: (T) -> Unit) {
    val sty = LocalSty.current
    val n = options.size
    val i = options.indexOf(current).coerceAtLeast(0)
    PopHeading(title)
    if (n <= 4) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn("Previous", Icons24.BACK, onClick = { onPick(options[(i - 1 + n) % n]) })
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon(options[i])?.let { SvgIcon(it, sty.ink, 22.dp); Box(Modifier.width(8.dp)) }
            Text(label(options[i]), color = sty.ink, fontSize = 14.sp, maxLines = 1)
        }
        IconBtn("Next", Icons24.NEXT, onClick = { onPick(options[(i + 1) % n]) })
    } else {
        var open by remember { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(StyShapes.Md).background(sty.honeyTint).clickable { open = !open }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon(options[i])?.let { SvgIcon(it, sty.ink, 22.dp) }
                Text(label(options[i]), color = sty.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box(Modifier.rotate(if (open) 180f else 0f)) { SvgIcon(Icons24.DOWN, sty.ink, 20.dp) }
            }
            if (open) for (o in options) Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(StyShapes.Md).clickable { onPick(o); open = false }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon(o)?.let { SvgIcon(it, sty.ink, 22.dp) }
                Text(label(o), color = if (o == current) sty.honeyText else sty.ink, fontSize = 14.sp)
            }
        }
    }
}

/** A premade shape drawn small, with its name, for the shape menu. */
@Composable
fun ShapeTile(preset: ShapePreset, chosen: Boolean, onClick: () -> Unit) {
    val sty = LocalSty.current
    Column(
        Modifier.width(72.dp).clip(StyShapes.Md).background(if (chosen) sty.honeyTint else Color.Transparent).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(32.dp)) {
            val st = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density * 1f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val w = size.width * 0.86f; val h = if (preset.locked) w else size.height * 0.64f
            val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
            when (preset.kind) {
                ShapeKind.RECT -> drawRoundRect(sty.ink, Offset(ox, oy), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(if (preset.rounded) h * 0.28f else 0f), style = st)
                ShapeKind.ELLIPSE -> drawOval(sty.ink, Offset(ox, oy), androidx.compose.ui.geometry.Size(w, h), style = st)
                ShapeKind.POLYGON -> {
                    val box = if (preset.vertices.isNotEmpty() && preset.name in setOf("TRIANGLE", "DIAMOND", "PENTAGON", "HEXAGON", "STAR")) size.height * 0.86f else h
                    val by = (size.height - box) / 2
                    val path = androidx.compose.ui.graphics.Path()
                    preset.vertices.forEachIndexed { k, (fx, fy) -> val x = ox + fx * w; val y = by + fy * box; if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                    path.close()
                    drawPath(path, sty.ink, style = st)
                }
            }
        }
        Text(preset.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (chosen) sty.ink else sty.inkMuted, fontSize = 11.sp, maxLines = 1)
    }
}

/** Two buttons with a label between them, for a pair of opposites ("Row above / below"). */
@Composable
fun PairRow(label: String, leftLabel: String, leftIcon: String, onLeft: () -> Unit, rightLabel: String, rightIcon: String, onRight: () -> Unit, enabled: Boolean = true) {
    val sty = LocalSty.current
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(leftLabel, leftIcon, enabled = enabled, onClick = onLeft)
        Text(label, Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f), color = sty.ink, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
        IconBtn(rightLabel, rightIcon, enabled = enabled, onClick = onRight)
    }
}

/**
 * A wheel of whole numbers: scroll it, tap a number beside the middle to go to it, or tap the middle one to type a number.
 * [onChange] hears the number each time the wheel comes to rest on one (or one is typed).
 */
@Composable
fun NumberWheel(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val sty = LocalSty.current
    val itemH = 44.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemPx = with(density) { itemH.toPx() }
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (value - range.first).coerceIn(0, range.count() - 1))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val centre by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0 } }
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { if (state.isScrollInProgress) -1 else centre }.collect { if (it >= 0) onChange(range.first + it.coerceIn(0, range.count() - 1)) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = sty.inkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.width(96.dp).height(itemH * 3), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(itemH).clip(StyShapes.Md).background(sty.honeyTint))
            if (typing) {
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                var hadFocus by remember { mutableStateOf(false) } // only losing focus that was had ends the typing
                androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                fun commit() {
                    typing = false
                    text.toIntOrNull()?.let { n -> val v = n.coerceIn(range.first, range.last); onChange(v); scope.launch { state.scrollToItem(v - range.first) } }
                }
                androidx.compose.foundation.text.BasicTextField(
                    text, { text = it.filter(Char::isDigit).take(3) }, Modifier.width(72.dp).focusRequester(focus).onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus && typing) commit() },
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = sty.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(sty.honeyText),
                )
            } else androidx.compose.foundation.lazy.LazyColumn(
                state = state, flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = itemH), modifier = Modifier.fillMaxWidth(),
            ) {
                items(range.count()) { i ->
                    val on = i == centre
                    Box(
                        Modifier.fillMaxWidth().height(itemH).clickable {
                            if (on) { text = ""; typing = true } else scope.launch { state.animateScrollToItem(i) }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text("${range.first + i}", color = if (on) sty.ink else sty.inkMuted, fontSize = if (on) 24.sp else 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }
        }
        Text("tap the middle to type", color = sty.inkMuted, fontSize = 11.sp)
    }
}

/** A grab strip at the top of a menu: drag it (with the pen or a finger) to move the whole menu out of the way. */
@Composable
fun DragHandle(modifier: Modifier = Modifier, onDrag: (Offset) -> Unit) {
    val sty = LocalSty.current
    Box(
        modifier.fillMaxWidth().height(22.dp).pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); onDrag(d) } },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(sty.borderStrong)) }
}
