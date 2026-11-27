package app.styli.ink

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Where the tool rail sits (a setting; left is the default). */
enum class RailSide(val label: String) { LEFT("Left"), RIGHT("Right"), TOP("Top"), BOTTOM("Bottom") }

/** The 24-grid line icons of the mockup (Lucide style), as SVG path strings. */
object Icons24 {
    const val PEN = "M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"
    const val HIGHLIGHTER = "M9 11l-6 6v3h9l3-3M22 12l-4.6 4.6a2 2 0 01-2.8 0l-5.2-5.2a2 2 0 010-2.8L14 4"
    const val ERASER = "M7 21l-4.3-4.3a1 1 0 010-1.4l10-10a1 1 0 011.4 0l5.6 5.6a1 1 0 010 1.4L11 21M22 21H7M5 11l9 9"
    const val LINE = "M4 20L20 4"
    const val LASSO = "M7 22a5 5 0 01-2-4M3.3 14A7 7 0 013 12c0-4.4 4-8 9-8s9 3.6 9 8-4 8-9 8a10 10 0 01-3-.5M5 18a2 2 0 100-4 2 2 0 000 4z"
    const val TEXT = "M4 7V4h16v3M9 20h6M12 4v16"
    const val UNDO = "M9 14L4 9l5-5M4 9h11a5 5 0 010 10h-3"
    const val REDO = "M15 14l5-5-5-5M20 9H9a5 5 0 000 10h3"
    const val FULL = "M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7"
    const val EXIT_FULL = "M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7"
    const val BACK = "M15 18l-6-6 6-6"
    const val NEXT = "M9 18l6-6-6-6"
    const val UP = "M6 15l6-6 6 6"
    const val LOCK = "M6 11h12a2 2 0 012 2v6a2 2 0 01-2 2H6a2 2 0 01-2-2v-6a2 2 0 012-2zM8 11V7a4 4 0 018 0v4"
    const val GRID = "M4 3h5a1 1 0 011 1v7a1 1 0 01-1 1H4a1 1 0 01-1-1V4a1 1 0 011-1zM15 3h5a1 1 0 011 1v7a1 1 0 01-1 1h-5a1 1 0 01-1-1V4a1 1 0 011-1zM4 15h5a1 1 0 011 1v4a1 1 0 01-1 1H4a1 1 0 01-1-1v-4a1 1 0 011-1zM15 15h5a1 1 0 011 1v4a1 1 0 01-1 1h-5a1 1 0 01-1-1v-4a1 1 0 011-1z"
    const val SHAPES = "M12 3l5 8H7zM5 14h6v6H5zM17.5 14a3.5 3.5 0 100 7 3.5 3.5 0 000-7z"
    const val CLIPBOARD = "M9 3h6v3H9zM9 4.5H6a1 1 0 00-1 1V20a1 1 0 001 1h12a1 1 0 001-1V5.5a1 1 0 00-1-1h-3"
    const val RULER = "M3 17L17 3l4 4L7 21zM7 13l2 2M10 10l2 2M13 7l2 2"
    const val ASSIST = "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 16l.7 2.3L22 19l-2.3.7L19 22l-.7-2.3L16 19l2.3-.7z"
    const val PAGE = "M6 3h8l4 4v14H6zM14 3v4h4"
    const val SHAPE = "M3 3h10v10H3zM17 11a4 4 0 100 8 4 4 0 000-8z"
    const val TABLE = "M5 3h14a2 2 0 012 2v14a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2zM3 10h18M3 15h18M9 3v18"
    const val CONNECTOR = "M3 4h5v5H3zM16 15h5v5h-5zM8 6.5h4a4 4 0 014 4V15"
    const val BUCKET = "M19 11l-8-8-8.6 8.6a2 2 0 000 2.8l5.2 5.2a2 2 0 002.8 0zM5 2l5 5M2 13h15M22 20a2 2 0 11-4 0c0-1.6 1.7-2.4 2-4 .3 1.6 2 2.4 2 4z"
    const val CHART = "M3 3v18h18M7 16v-5M12 16V7M17 16v-3"
    const val LASER = "M10 12a2 2 0 104 0 2 2 0 10-4 0M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"
    const val IMAGE = "M5 3h14a2 2 0 012 2v14a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2zM8.5 10a1.5 1.5 0 100-3 1.5 1.5 0 000 3zM21 15l-5-5L5 21"
    const val DOTTED_PEN = "M12 20h.01M15 20h.01M18 20h.01M21 20h.01M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"
    const val DOTTED_LINE = "M4 20h.01M8 16h.01M12 12h.01M16 8h.01M20 4h.01"
    const val LINE_HIGHLIGHTER = "M5 19L19 5M3 15L15 3M9 21L21 9"
    const val ERASER_WHOLE = "M7 21l-4.3-4.3a1 1 0 010-1.4l10-10a1 1 0 011.4 0l5.6 5.6a1 1 0 010 1.4L11 21M22 21H7"
    const val MENU = "M4 6h16M4 12h16M4 18h16"
    const val DELETE = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6"
    const val CROP = "M6 2v14a2 2 0 002 2h14M2 6h14a2 2 0 012 2v14"
    const val RESET = "M3 12a9 9 0 109-9 9 9 0 00-6.7 3L3 8M3 3v5h5"
    const val LAYERS = "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5M3 17.5l9 5 9-5"
    const val PLUS = "M12 5v14M5 12h14"
    const val CLOSE = "M18 6L6 18M6 6l12 12"
    const val DOWN = "M6 9l6 6 6-6"
    const val DIVIDER = "M4 12h16"
    const val SPACER = "M12 4v16M8 8l4-4 4 4M8 16l4 4 4-4"
}

/** The rail icon of each tool. */
fun toolIcon(t: InkCanvasView.Tool): String = when (t) {
    InkCanvasView.Tool.PEN -> Icons24.PEN
    InkCanvasView.Tool.HIGHLIGHTER -> Icons24.HIGHLIGHTER
    InkCanvasView.Tool.ERASER_STROKE -> Icons24.ERASER_WHOLE
    InkCanvasView.Tool.ERASER_PART -> Icons24.ERASER
    InkCanvasView.Tool.LASSO -> Icons24.LASSO
    InkCanvasView.Tool.LINE -> Icons24.LINE
    InkCanvasView.Tool.LINE_HIGHLIGHTER -> Icons24.LINE_HIGHLIGHTER
    InkCanvasView.Tool.DOTTED -> Icons24.DOTTED_PEN
    InkCanvasView.Tool.LINE_DOTTED -> Icons24.DOTTED_LINE
    InkCanvasView.Tool.LASER -> Icons24.LASER
    InkCanvasView.Tool.TEXT -> Icons24.TEXT
    InkCanvasView.Tool.TABLE -> Icons24.TABLE
    InkCanvasView.Tool.SHAPE -> Icons24.SHAPE
    InkCanvasView.Tool.BUCKET -> Icons24.BUCKET
    InkCanvasView.Tool.CONNECTOR -> Icons24.CONNECTOR
    InkCanvasView.Tool.CHART -> Icons24.CHART
}

/** The ink colours on the rail (black, blue and red, as in the mockup). */
val RAIL_INKS = listOf(0xFF000000.toInt(), 0xFF2F4FA8.toInt(), 0xFFB3261E.toInt())

@Composable
fun SvgIcon(d: String, color: Color, size: Dp = 20.dp) {
    val path = PathParser().parsePathString(d).toPath()
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, Offset.Zero) { drawPath(path, color, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/** A 44 dp button; the chosen one has the honey tint behind it (the icon stays ink). */
@Composable
fun IconBtn(label: String, chosen: Boolean = false, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    val sty = LocalSty.current
    Box(
        Modifier.size(StySize.touch).alpha(if (enabled) 1f else 0.4f).clip(SquircleShape(StySize.radiusMd)).background(if (chosen) sty.honeyTint else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun IconBtn(label: String, d: String, chosen: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    IconBtn(label, chosen, enabled, onClick) { SvgIcon(d, LocalSty.current.ink) }

/** One thing on the rail. */
sealed class RailEntry {
    class Btn(val label: String, val icon: String, val chosen: Boolean, val key: String = label, val onClick: () -> Unit) : RailEntry()
    class Ink(val color: Int, val chosen: Boolean, val key: String, val onClick: () -> Unit) : RailEntry()
    object Sep : RailEntry()
    object Gap : RailEntry()
}

/** The tool rail: a docked bar along one edge of the page, centred, and scrolling if it does not fit. */
@Composable
fun ToolRail(side: RailSide, entries: List<RailEntry>, modifier: Modifier = Modifier, onPos: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> }) {
    val sty = LocalSty.current
    val vertical = side == RailSide.LEFT || side == RailSide.RIGHT
    val body: @Composable () -> Unit = {
        for (e in entries) when (e) {
            is RailEntry.Btn -> Box(Modifier.onGloballyPositioned { onPos(e.key, it.boundsInWindow()) }) { IconBtn(e.label, e.icon, e.chosen, onClick = e.onClick) }
            is RailEntry.Ink -> Box(Modifier.onGloballyPositioned { onPos(e.key, it.boundsInWindow()) }) {
                IconBtn("Ink colour (tap the chosen one to change it)", e.chosen, onClick = e.onClick) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Color(e.color)).border(1.dp, sty.inkMuted, CircleShape))
                }
            }
            RailEntry.Gap -> Box(if (vertical) Modifier.height(28.dp) else Modifier.width(28.dp))
            RailEntry.Sep -> Box(Modifier.padding(6.dp).background(sty.border).let { if (vertical) it.width(28.dp).height(1.dp) else it.width(1.dp).height(28.dp) })
        }
    }
    val hairline = if (vertical) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth()
    val align = when (side) { RailSide.LEFT -> Alignment.CenterEnd; RailSide.RIGHT -> Alignment.CenterStart; RailSide.TOP -> Alignment.BottomCenter; RailSide.BOTTOM -> Alignment.TopCenter }
    Box(modifier.background(sty.surface), contentAlignment = Alignment.Center) {
        if (vertical) Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { body() }
        else Row(Modifier.padding(8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { body() }
        Box(Modifier.align(align).then(hairline).background(sty.border))
    }
}

/** The bar over the page: back to the library, the note's name, and the buttons that are planned but not built. */
@Composable
fun NoteHeader(title: String, subtitle: String, onBack: () -> Unit, onFull: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val sty = LocalSty.current
    Column(modifier.background(sty.surface)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn("Back to the Tabularium", Icons24.BACK, onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text(title, color = sty.ink, fontFamily = DisplayFont, fontSize = 17.sp, maxLines = 1)
                Text(subtitle, color = sty.inkMuted, fontSize = 12.sp, maxLines = 1)
            }
            Text("Not synced yet", color = sty.inkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            IconBtn("Lock: the AI won't change this note (not built yet)", Icons24.LOCK, enabled = false, onClick = {})
            IconBtn("Full screen", Icons24.FULL, onClick = onFull)
            // The one honey button of the screen; it waits for the AI reading (phase 4).
            Box(
                Modifier.height(40.dp).alpha(0.4f).clip(SquircleShape(StySize.radiusMd)).background(sty.honey).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Convert now", color = sty.onHoney, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            IconBtn("Page settings and more", Icons24.MENU, onClick = onMenu)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(sty.border))
    }
}

/** One page on the map of the grid. */
class MapCell(val col: Int, val row: Int, val landscape: Boolean)

/** Previous page, a little map of where the pages sit on the grid (the page in view is lit; tap one to go there), next page, and all pages. */
@Composable
fun PageBar(cells: List<MapCell>, current: Int, onPick: (Int) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val sty = LocalSty.current
    val minC = cells.minOf { it.col }; val minR = cells.minOf { it.row }
    val cols = cells.maxOf { it.col } - minC + 1; val rows = cells.maxOf { it.row } - minR + 1
    val slot = 28.dp
    val k = minOf(1f, 36.dp / (slot * rows), 240.dp / (slot * cols))
    Row(modifier.fillMaxWidth().height(52.dp).background(sty.bg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconBtn("Previous page", Icons24.BACK, enabled = current > 0, onClick = onPrev)
        Canvas(
            Modifier.padding(horizontal = 12.dp).size(slot * cols * k, slot * rows * k).semantics { contentDescription = "Pages on the grid, ${cells.size} pages" }
                .pointerInput(cells) {
                    detectTapGestures { at ->
                        val s = size.width / cols.toFloat()
                        val c = (at.x / s).toInt() + minC; val r = (at.y / (size.height / rows.toFloat())).toInt() + minR
                        cells.indexOfFirst { it.col == c && it.row == r }.takeIf { it >= 0 }?.let(onPick)
                    }
                },
        ) {
            val sw = size.width / cols; val sh = size.height / rows
            for ((i, c) in cells.withIndex()) {
                val w = (if (c.landscape) 0.9f else 0.68f) * sw; val h = (if (c.landscape) 0.68f else 0.9f) * sh
                val x = (c.col - minC) * sw + (sw - w) / 2; val y = (c.row - minR) * sh + (sh - h) / 2
                val r = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                drawRoundRect(if (i == current) sty.honey else sty.surface, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), r)
                drawRoundRect(if (i == current) sty.honeyText else sty.borderStrong, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), r, style = Stroke(2f))
            }
        }
        IconBtn("Next page", Icons24.NEXT, enabled = current < cells.size - 1, onClick = onNext)
        IconBtn("All pages", Icons24.GRID, onClick = onAll)
    }
}

/** The converted text under the page; it folds down to a slim bar. Nothing fills it until the AI reading exists. */
@Composable
fun ConvertedSheet(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val sty = LocalSty.current
    Surface(
        modifier.fillMaxWidth().height(if (open) 216.dp else 56.dp), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = sty.surface, shadowElevation = 6.dp, border = BorderStroke(1.dp, sty.border),
    ) {
        Column(Modifier.padding(start = 28.dp, end = 12.dp, top = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Converted text", color = sty.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconBtn(if (open) "Shrink converted text" else "Expand converted text", onClick = onToggle) {
                    Box(Modifier.rotate(if (open) 180f else 0f)) { SvgIcon(Icons24.UP, sty.ink) }
                }
            }
            if (open) Text(
                "Nothing converted yet. When the AI reading is set up, your handwriting turns into plain Markdown here.",
                Modifier.padding(top = 4.dp), color = sty.inkMuted, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
