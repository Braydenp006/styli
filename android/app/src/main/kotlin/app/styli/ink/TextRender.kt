package app.styli.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Draws a [TextBox] in page units. The editor ([InkCanvasView]'s `EditText`) is set up to look the same. */
object TextRender {
    /** A zero-alpha colour means the page's ordinary ink colour (the format's rule). Dark mode will map it later. */
    fun ink(color: Int) = if (color ushr 24 == 0) Color.BLACK else color

    /** Highlight is behind the text; zero alpha means none. */
    fun hasHighlight(highlight: Int) = highlight ushr 24 != 0

    /** Same flags for the saved text and the editor, so glyphs are not rounded to whole pixels at one size only. */
    fun smooth(paint: Paint) { paint.flags = paint.flags or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG }

    /** The text wrapped to the box's width, in page units. Its height is the box's height. */
    fun layout(box: TextBox): StaticLayout {
        val paint = TextPaint().apply {
            smooth(this)
            textSize = box.fontSize
            color = ink(box.color)
            typeface = Typeface.SANS_SERIF
        }
        return StaticLayout.Builder.obtain(box.text, 0, box.text.length, paint, box.width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private val fill = Paint()

    fun draw(canvas: Canvas, box: TextBox, layout: StaticLayout) {
        canvas.save()
        canvas.translate(box.x, box.y)
        canvas.rotate(Math.toDegrees(box.angle.toDouble()).toFloat()) // clockwise on screen, about the top left
        if (hasHighlight(box.highlight)) {
            fill.color = box.highlight
            canvas.drawRect(0f, 0f, box.width, layout.height.toFloat(), fill)
        }
        layout.draw(canvas)
        canvas.restore()
    }
}
