package app.styli.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line icons on a 24 grid, 1.5 stroke, rounded caps and joins (the design system's style; Lucide is not bundled yet). */
enum class Ic { LIBRARY, NEXUS, ITINERARIUM, NEWNOTE, TRASH, SETTINGS, PLUS, PIN, CHEVRON }

@Composable
fun StyIcon(ic: Ic, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val st = Stroke(1.5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        fun line(a: Offset, b: Offset) = drawLine(color, a, b, st.width, StrokeCap.Round)
        when (ic) {
            Ic.PIN -> { // a pushpin
                drawRoundRect(color, p(8f, 3f), Size(8f * k, 8f * k), CornerRadius(2f * k), style = st)
                line(p(6f, 11f), p(18f, 11f)); line(p(12f, 11f), p(12f, 21f))
            }
            Ic.CHEVRON -> { line(p(9f, 6f), p(15f, 12f)); line(p(15f, 12f), p(9f, 18f)) } // points right
            Ic.PLUS -> { line(p(12f, 5f), p(12f, 19f)); line(p(5f, 12f), p(19f, 12f)) }
            Ic.LIBRARY -> { // a book
                drawRoundRect(color, p(5f, 4f), Size(14f * k, 16f * k), CornerRadius(2f * k), style = st)
                line(p(9f, 4f), p(9f, 20f)); line(p(12f, 8f), p(16f, 8f))
            }
            Ic.NEXUS -> { // three linked nodes
                line(p(7f, 16f), p(12f, 7f)); line(p(12f, 7f), p(17f, 16f)); line(p(7f, 16f), p(17f, 16f))
                for (c in listOf(p(12f, 6f), p(6f, 17f), p(18f, 17f))) drawCircle(color, 2.5f * k, c, style = st)
            }
            Ic.ITINERARIUM -> { // a winding path between two stops
                val route = Path().apply { moveTo(7f * k, 18f * k); cubicTo(18f * k, 18f * k, 6f * k, 11f * k, 17f * k, 6f * k) }
                drawPath(route, color, style = st)
                drawCircle(color, 2f * k, p(6f, 18f), style = st); drawCircle(color, 2f * k, p(18f, 6f), style = st)
            }
            Ic.NEWNOTE -> { // a sheet of paper with a pen across its corner
                val sheet = Path().apply { moveTo(14f * k, 4f * k); lineTo(6f * k, 4f * k); lineTo(6f * k, 20f * k); lineTo(18f * k, 20f * k); lineTo(18f * k, 11f * k) }
                drawPath(sheet, color, style = st)
                line(p(9f, 9f), p(12f, 9f)); line(p(9f, 13f), p(15f, 13f))
                line(p(20f, 4f), p(13f, 11f)); line(p(13f, 11f), p(12.5f, 12.5f)); line(p(12.5f, 12.5f), p(14f, 12f)); line(p(14f, 12f), p(21f, 5f))
            }
            Ic.TRASH -> {
                line(p(4f, 7f), p(20f, 7f)); line(p(9f, 7f), p(9f, 4f)); line(p(9f, 4f), p(15f, 4f)); line(p(15f, 4f), p(15f, 7f))
                val body = Path().apply { moveTo(6f * k, 7f * k); lineTo(7f * k, 20f * k); lineTo(17f * k, 20f * k); lineTo(18f * k, 7f * k) }
                drawPath(body, color, style = st)
            }
            Ic.SETTINGS -> { // three sliders
                for ((y, x) in listOf(6f to 9f, 12f to 15f, 18f to 8f)) {
                    line(p(4f, y), p(20f, y)); drawCircle(color, 2.5f * k, p(x, y), style = st)
                }
            }
        }
    }
}

/** A filled folder in [color]: a back with a tab, and a lighter front. */
@Composable
fun FolderIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val r = CornerRadius(h * 0.1f)
        val back = lerp(color, Color.Black, 0.25f)
        drawRoundRect(back, Offset(0f, 0f), Size(w * 0.42f, h * 0.3f), r)
        drawRoundRect(back, Offset(0f, h * 0.12f), Size(w, h * 0.88f), r)
        drawRoundRect(color, Offset(0f, h * 0.3f), Size(w, h * 0.7f), r)
    }
}

/** The two-line menu icon; when [open] the lines swing across each other into an X. */
@Composable
fun MenuIcon(open: Boolean, color: Color, size: Dp = 24.dp) {
    val t by androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f, androidx.compose.animation.core.tween(220), label = "menu")
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        for (sign in listOf(-1f, 1f)) { // the upper line swings one way, the lower the other
            val cy = (12f + sign * 3f * (1f - t)) * k
            val a = Math.toRadians((sign * -45.0 * t)).toFloat()
            val dx = 8f * k * kotlin.math.cos(a); val dy = 8f * k * kotlin.math.sin(a)
            drawLine(color, Offset(12f * k - dx, cy - dy), Offset(12f * k + dx, cy + dy), 1.5f * k, StrokeCap.Round)
        }
    }
}
