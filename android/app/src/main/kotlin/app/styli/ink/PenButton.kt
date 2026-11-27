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
    private var pendingUp: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun pressedState(e: MotionEvent): Boolean? {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return null
        // The pen's own announcements are the surest word: the button went down, or came up.
        if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && e.actionButton and BUTTONS != 0) return true
        if (e.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE && e.actionButton and BUTTONS != 0) return false
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT || e.actionMasked == MotionEvent.ACTION_CANCEL) return false
        return e.buttonState and BUTTONS != 0 // the stylus primary button, and on some pens the secondary one as well
    }

    /** Hands each pen event over. True when the event was only the button (the caller then keeps it from the system). */
    fun track(e: MotionEvent): Boolean {
        val pressed = pressedState(e) ?: return false
        if (pressed) {
            // A button that blinks off for a moment (between touching and hovering) does not count as let go.
            pendingUp?.let { handler.removeCallbacks(it) }; pendingUp = null
            if (!down) { down = true; listener?.invoke(true) }
        } else if (down && pendingUp == null) {
            val up = Runnable { pendingUp = null; if (down) { down = false; listener?.invoke(false) } }
            pendingUp = up
            handler.postDelayed(up, RELEASE_GRACE_MS)
        }
        return e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || e.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    }

    /** The key codes a stylus button can also send (Android 14 on): taken so the system does not act on them. */
    fun isStylusKey(code: Int) = code in 308..311 // KEYCODE_STYLUS_BUTTON_PRIMARY, SECONDARY, TERTIARY and TAIL

    private const val BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY
    private const val RELEASE_GRACE_MS = 90L
}
