package app.styli.ink

import android.view.MotionEvent

/** What the pen's side button can do. A click runs any of these; holding it makes the pen another tool for as long as it is held (tools only). */
enum class PenAction(val label: String, val tool: InkCanvasView.Tool? = null) {
    NONE("Nothing"),
    PEN("Pen", InkCanvasView.Tool.PEN),
    HIGHLIGHTER("Highlighter", InkCanvasView.Tool.HIGHLIGHTER),
    ERASER_PART("Eraser (part)", InkCanvasView.Tool.ERASER_PART),
    ERASER_STROKE("Eraser (whole)", InkCanvasView.Tool.ERASER_STROKE),
    LASSO("Lasso", InkCanvasView.Tool.LASSO),
    LASER("Laser pointer", InkCanvasView.Tool.LASER),
    PREVIOUS("Previous tool"),
    UNDO("Undo"),
    REDO("Redo");

    companion object {
        /** The actions that make sense while the button is held: a tool to be, or nothing. */
        val HOLD = entries.filter { it == NONE || it.tool != null }
    }
}

/**
 * Watches the stylus's side button on every pen event (the activity hands them over before any view sees them, so it
 * works while the pen hovers as well as while it touches). [listener] hears true when the button goes down and false when it comes up.
 */
object PenButton {
    var listener: ((Boolean) -> Unit)? = null
    private var down = false

    fun track(e: MotionEvent) {
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        // The button is reported as the stylus primary button, and on some pens as the secondary button too.
        val pressed = stylus && e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0 &&
            e.actionMasked != MotionEvent.ACTION_HOVER_EXIT && e.actionMasked != MotionEvent.ACTION_CANCEL
        if (pressed != down) { down = pressed; listener?.invoke(pressed) }
    }
}
