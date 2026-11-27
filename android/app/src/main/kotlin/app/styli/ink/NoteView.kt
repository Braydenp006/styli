package app.styli.ink

/** Where a note was left: the zoom (as a multiple of the page fitted to the width) and the surface point at the top left of the screen. */
class ViewState(val zoom: Double, val left: Double, val top: Double)

/**
 * What is kept for each note beside its pages, in `view.json`: the tool it was left on, where it was scrolled and zoomed to,
 * and the pens in it (used when the Settings say each note keeps its own pens). Plain numbers and words, like the other small files.
 */
object NoteView {
    fun capture(c: InkCanvasView): Map<String, Any?> = buildMap {
        put("tool", c.tool.name)
        c.viewState()?.let { put("zoom", Math.round(it.zoom * 1000)); put("left", Math.round(it.left)); put("top", Math.round(it.top)) }
        put("penColor", c.penColor); put("penWidth", c.penWidth); put("highlighterColor", c.highlighterColor); put("highlighterWidth", c.highlighterWidth)
    }

    fun tool(m: Map<String, Any?>?): InkCanvasView.Tool? = (m?.get("tool") as? String)?.let { n -> InkCanvasView.Tool.entries.firstOrNull { it.name == n } }

    fun view(m: Map<String, Any?>?): ViewState? {
        val z = m?.get("zoom") as? Long ?: return null
        val l = m["left"] as? Long ?: return null
        val t = m["top"] as? Long ?: return null
        return if (z > 0) ViewState(z / 1000.0, l.toDouble(), t.toDouble()) else null
    }
}
