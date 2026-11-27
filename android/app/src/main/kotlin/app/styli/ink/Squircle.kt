package app.styli.ink

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Styli's box shape: a "squircle". Each corner eases into the straight edge instead of meeting it at a circle's
 * edge, so it looks a little softer than [androidx.compose.foundation.shape.RoundedCornerShape] but stays gentle.
 * Use it for every rounded box (circles, like colour dots, stay circles).
 */
class SquircleShape(private val radius: Dp) : CornerBasedShape(CornerSize(radius), CornerSize(radius), CornerSize(radius), CornerSize(radius)) {
    // Material needs a corner-based shape for its buttons and dialogs; every corner here is the same size.
    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize) = this

    override fun createOutline(size: Size, topStart: Float, topEnd: Float, bottomEnd: Float, bottomStart: Float, layoutDirection: LayoutDirection): Outline {
        val w = size.width; val h = size.height
        val s = minOf(topStart * 1.15f, w / 2, h / 2) // where the curve starts, along each edge
        val c = s * 0.32f // the control points sit this far from the corner; smaller is squarer
        val p = Path().apply {
            moveTo(s, 0f); lineTo(w - s, 0f); cubicTo(w - c, 0f, w, c, w, s)
            lineTo(w, h - s); cubicTo(w, h - c, w - c, h, w - s, h)
            lineTo(s, h); cubicTo(c, h, 0f, h - c, 0f, h - s)
            lineTo(0f, s); cubicTo(0f, c, c, 0f, s, 0f); close()
        }
        return Outline.Generic(p)
    }
}
