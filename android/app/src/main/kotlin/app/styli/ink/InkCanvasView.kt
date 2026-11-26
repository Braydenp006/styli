package app.styli.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.text.StaticLayout
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.ExperimentalInkHandoffApi
import androidx.ink.authoring.ExperimentalInkLatencyDataApi
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import app.styli.ink.proto.PageSet
import app.styli.ink.proto.Template
import app.styli.ink.proto.Tool as StrokeTool

/**
 * A page you can draw on with a stylus and move around with your fingers (drag to pan, pinch to zoom).
 *
 * Two layers stack here. While the pen is down, [InProgressStrokesView] draws the live stroke with
 * the library's low-latency path. When the pen lifts, the finished stroke moves to [pageLayer],
 * which is an ordinary view that redraws the page, its template and every finished stroke that is on
 * screen. Strokes are kept in page units (0.01 mm), see [PageGeometry].
 */
@SuppressLint("RestrictedApi") // the latency callback is marked library-internal, fine for a test tool
@OptIn(ExperimentalInkLatencyDataApi::class, ExperimentalInkHandoffApi::class)
class InkCanvasView(context: Context) : FrameLayout(context) {

    /** Called with a short latency summary while drawing. */
    var onLatency: ((String) -> Unit)? = null

    /** A finished stroke and its box in page units, so a redraw can skip the ones off screen. */
    private class Placed(val item: Item, val stroke: Stroke, val l: Float, val t: Float, val r: Float, val b: Float)

    /** What the pen in your hand does. */
    enum class Tool { PEN, HIGHLIGHTER, ERASER_STROKE, ERASER_PART, LASSO, LINE, TEXT }

    /** The pages of this notebook (in memory only for now); the view shows [Notebook.current]. */
    val notebook = Notebook()
    private val model get() = notebook.current

    /** The page being shown. */
    val page get() = model.page

    /** Called when another page is shown (turned to, added, or taken back by an undo). */
    var onPageChanged: (() -> Unit)? = null

    /** Which finger pulls past a page's edge may add a page (see [PageTurn]). */
    var flow = Flow.DOWN
    private val placed = HashMap<String, Placed>() // drawn strokes by ID, built once (pruned after every change)
    private val renderer = CanvasStrokeRenderer.create()

    var tool = Tool.PEN
        set(v) {
            if (v != Tool.TEXT) finishText()
            field = v
            selection.clear() // a selection only lives while the lasso is the tool
            pageLayer.invalidate()
        }
    var penColor = Color.BLACK
    var penWidth = 40 // 0.4 mm, in page units
    var highlighterColor = 0x99FFEB3B.toInt() // a see-through yellow, so the writing shows through
    var highlighterWidth = 400 // 4 mm

    /** Hold the pen still at the end of a pen stroke and a line or shape is drawn clean (see [ShapeGeometry.Settings]). */
    var penmanship = ShapeGeometry.Settings()
    private val autoStraighten get() = penmanship.lines > 0f || penmanship.shapes > 0f

    val canUndo get() = model.canUndo
    val canRedo get() = model.canRedo

    // Sizes are in page units; strokes are simplified to within 0.02 mm.
    private val penFamily = StockBrushes.pressurePen()
    private val highlighterFamily = StockBrushes.highlighter()
    private fun brushFor(family: BrushFamily, color: Int, width: Int) =
        Brush.createWithColorIntArgb(family, color, width.toFloat(), 2f)
    private val brush get() =
        if (tool == Tool.HIGHLIGHTER) brushFor(highlighterFamily, highlighterColor, highlighterWidth)
        else brushFor(penFamily, penColor, penWidth)

    /** The eraser's reach on screen, so it feels the same at any zoom. */
    private val eraserPx = 10f

    private val pxPerUnit = resources.displayMetrics.xdpi / 2540.0
    private var geometry = PageGeometry(21000, 29700, false, pxPerUnit)
    private var template = Template.TEMPLATE_BLANK
    private val toScreen = Matrix()
    private val line = Paint().apply { strokeWidth = 1f }
    private val sheetEdge = Paint().apply { color = 0xFFAAAAAA.toInt(); style = Paint.Style.STROKE }

    private val pageLayer =
        object : View(context) {
            override fun onDraw(canvas: Canvas) {
                val g = geometry
                positionEditor()
                canvas.save()
                canvas.translate((pullX * PULL_SHIFT).toFloat(), (pullY * PULL_SHIFT).toFloat()) // the page follows a pull a little
                canvas.drawColor(if (g.infinite) Color.WHITE else 0xFFD9D9D9.toInt())
                canvas.save()
                if (!g.infinite) {
                    val sheet = RectF(
                        g.toScreenX(0.0).toFloat(), g.toScreenY(0.0).toFloat(),
                        g.toScreenX(g.pageW.toDouble()).toFloat(), g.toScreenY(g.pageH.toDouble()).toFloat(),
                    )
                    canvas.drawRect(sheet, Paint().apply { color = Color.WHITE })
                    canvas.drawRect(sheet, sheetEdge)
                    canvas.clipRect(sheet)
                }
                drawTemplate(canvas)
                toScreen.setScale(g.scale.toFloat(), g.scale.toFloat())
                toScreen.postTranslate(g.tx.toFloat(), g.ty.toFloat())
                val (vl, vt, vr, vb) = g.visible().map { it.toFloat() }
                // The renderer does not apply the matrix, it only uses it to judge quality: apply it here too.
                canvas.save()
                canvas.concat(toScreen)
                for (item in model.items) {
                    // A transform makes a new Item under the same ID, so an old picture is not reused.
                    val p = placed[item.id]?.takeIf { it.item === item } ?: place(buildStroke(item), item).also { placed[item.id] = it }
                    val moved = dragMap?.takeIf { item.id in selection }
                    if (moved == null && (p.r < vl || p.l > vr || p.b < vt || p.t > vb)) continue
                    canvas.save()
                    if (moved != null) canvas.concat(Matrix().apply { setValues(floatArrayOf(moved.a.toFloat(), moved.c.toFloat(), moved.e.toFloat(), moved.b.toFloat(), moved.d.toFloat(), moved.f.toFloat(), 0f, 0f, 1f)) })
                    renderer.draw(canvas, p.stroke, toScreen)
                    canvas.restore()
                }
                for (box in model.texts) {
                    if (box.id == editBox?.id) continue // the editor shows it
                    val moved = dragMap?.takeIf { box.id in selection }
                    canvas.save()
                    if (moved != null) canvas.concat(Matrix().apply { setValues(floatArrayOf(moved.a.toFloat(), moved.c.toFloat(), moved.e.toFloat(), moved.b.toFloat(), moved.d.toFloat(), moved.f.toFloat(), 0f, 0f, 1f)) })
                    TextRender.draw(canvas, box, layoutOf(box))
                    canvas.restore()
                }
                canvas.restore()
                drawLassoOverlay(canvas)
                drawLinePreview(canvas)
                drawSnapPreview(canvas)
                drawTextDragPreview(canvas)
                canvas.restore()
                canvas.restore() // the pull's shift
                drawPullHint(canvas)
            }
        }

    private fun drawTemplate(canvas: Canvas) {
        val g = geometry
        val (vl, vt, vr, vb) = g.visible()
        // A fixed page has no lines outside the sheet (the clip covers the sides; the top margin is the offset).
        val top = if (g.infinite) vt else maxOf(vt, 0.0)
        val bottom = if (g.infinite) vb else minOf(vb, g.pageH.toDouble())
        val left = if (g.infinite) vl else maxOf(vl, 0.0)
        val right = if (g.infinite) vr else minOf(vr, g.pageW.toDouble())
        when (template) {
            Template.TEMPLATE_LINED -> {
                line.color = 0xFFC3D3EA.toInt()
                val first = if (g.infinite) Int.MIN_VALUE.toDouble() else LINED_TOP.toDouble()
                for (y in PageGeometry.lines(maxOf(top, first), bottom, LINED_STEP, LINED_TOP, g.scale)) {
                    val sy = g.toScreenY(y.toDouble()).toFloat()
                    canvas.drawLine(0f, sy, width.toFloat(), sy, line)
                }
            }
            Template.TEMPLATE_GRID -> {
                line.color = 0xFFD5D5D5.toInt()
                for (y in PageGeometry.lines(top, bottom, GRID_STEP, 0, g.scale)) {
                    val sy = g.toScreenY(y.toDouble()).toFloat()
                    canvas.drawLine(0f, sy, width.toFloat(), sy, line)
                }
                for (x in PageGeometry.lines(left, right, GRID_STEP, 0, g.scale)) {
                    val sx = g.toScreenX(x.toDouble()).toFloat()
                    canvas.drawLine(sx, 0f, sx, height.toFloat(), line)
                }
            }
            else -> {}
        }
    }

    private var shown: PageSet? = null

    /** Changes this page's size, template or fixed-or-infinite (one undo step) and shows it. */
    fun setPage(page: PageSet) {
        // A page's place in the grid is not part of its size and look: keep it.
        if (model.setPage(page.toBuilder().setCol(model.page.col).setRow(model.page.row).build())) showPage()
    }

    /** Draws the model's page: its size, template and whether it is fixed or an infinite canvas. */
    private fun showPage() {
        val page = model.page
        shown = page
        geometry = PageGeometry(page.width, page.height, page.infinite, pxPerUnit)
        template = page.template
        geometry.fit(width, height)
        pageLayer.invalidate()
    }

    init { showPage() } // the model starts on its own default page

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry.fit(w, h)
    }

    private val liveLayer = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(this)

    // shortcut: tracks one pen pointer only, so a second finger is ignored. Palm rejection comes later.
    private var strokeId: InProgressStrokeId? = null
    private var pointerId = -1

    private val latencySamplesMs = ArrayDeque<Double>()

    init {
        addView(pageLayer)
        addView(liveLayer)

        liveLayer.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    strokes.values.forEach {
                        val item = model.add(liveTool, liveColor, liveWidth, pointsOf(it))
                        placed[item.id] = place(it, item)
                    }
                    pageLayer.invalidate()
                    // Only stop drawing the live copy after the page layer has the stroke, to avoid a flicker.
                    liveLayer.removeFinishedStrokes(strokes.keys)
                }
            }
        )

        // Library timestamps: pen touching the glass to the app finishing its draw calls. Some devices (this
        // tablet) never fill in the "pixels visible" time, so fall back to the draw-finished time, which
        // leaves out the display's own delay (up to one frame).
        liveLayer.setLatencyDataCallback { data ->
            if (!data.isOsDetectsEventSet) return@setLatencyDataCallback
            val none = Long.MIN_VALUE
            val end =
                listOf(
                        data.estimatedPixelPresentationTime,
                        data.canvasFrontBufferStrokesRenderHelperData.finishesDrawCalls,
                        data.hwuiInProgressStrokesRenderHelperData.finishesDrawCalls,
                    )
                    .firstOrNull { it != none } ?: return@setLatencyDataCallback
            val ms = (end - data.osDetectsEvent) / 1e6
            if (ms !in 0.0..200.0) return@setLatencyDataCallback // predicted points can give odd values
            latencySamplesMs.addLast(ms)
            if (latencySamplesMs.size > 60) latencySamplesMs.removeFirst()
            if (latencySamplesMs.size % 10 == 0) {
                onLatency?.invoke(
                    "Input to drawn: avg %.1f ms, max %.1f ms (last %d)".format(
                        latencySamplesMs.average(),
                        latencySamplesMs.max(),
                        latencySamplesMs.size,
                    )
                )
            }
        }
    }

    fun undo() {
        finishText()
        // A page added by a pull and left untouched goes away with the undo; anything else undoes a step.
        if (!model.canUndo && notebook.removeMade()) pageChanged() else changed(model.undo())
    }
    fun redo() { finishText(); changed(model.redo()) }

    private fun changed(did: Boolean) {
        if (!did) return
        val ids = model.items.mapTo(HashSet()) { it.id }
        val textIds = model.texts.mapTo(HashSet()) { it.id }
        placed.keys.retainAll(ids)
        layouts.keys.retainAll(textIds)
        selection.retainAll(ids + textIds)
        if (model.page != shown) showPage() // an undo or redo of a page change
        pageLayer.invalidate()
    }

    // What the stroke being drawn was made with, in case the tool is changed before the pen lifts.
    private var liveTool = StrokeTool.TOOL_PEN
    private var liveColor = Color.BLACK
    private var liveWidth = 40

    // Auto-straightening. The pen's path so far (page units), and where it is being held still. After the hold
    // the live stroke is cancelled and [snapped], the clean line or shape, is drawn on the page layer instead;
    // it follows the pen if it moves on, and becomes the stroke when the pen lifts.
    private val drawn = ArrayList<Pt>()
    private var drawStartMs = 0L
    private val hold = ShapeGeometry.HoldTracker()
    private var snapped: List<Pt>? = null

    private val holdCheck = Runnable {
        val id = strokeId ?: return@Runnable
        if (!autoStraighten || liveTool != StrokeTool.TOOL_PEN) return@Runnable
        val clean = ShapeGeometry.snap(drawn, penmanship) ?: return@Runnable
        liveLayer.cancelStroke(id, null)
        strokeId = null
        snapped = clean
        pageLayer.invalidate()
    }

    /** Adds the pen's points from [event] (including the ones batched in it); true if the pen moved on. */
    private fun collect(event: MotionEvent, i: Int): Boolean {
        var moved = false
        fun add(sx: Float, sy: Float, t: Long) {
            val x = geometry.toPageX(sx.toDouble()).toFloat()
            val y = geometry.toPageY(sy.toDouble()).toFloat()
            drawn.add(Pt(x, y, t - drawStartMs, 1f))
            if (hold.move(x, y)) moved = true
        }
        for (h in 0 until event.historySize) add(event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalEventTime(h))
        add(event.getX(i), event.getY(i), event.eventTime)
        return moved
    }

    private fun drawSnapPreview(canvas: Canvas) {
        val pts = snapped ?: return
        val g = geometry
        linePaint.color = liveColor
        linePaint.strokeWidth = maxOf(1.5f, (liveWidth * g.scale).toFloat())
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeJoin = Paint.Join.ROUND
        val path = android.graphics.Path()
        pts.forEachIndexed { k, p ->
            val x = g.toScreenX(p.x.toDouble()).toFloat()
            val y = g.toScreenY(p.y.toDouble()).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun snapping(event: MotionEvent): Boolean {
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0 && collect(event, i)) {
                ShapeGeometry.snap(drawn, penmanship)?.let { snapped = it } // moved on: the clean shape follows
                pageLayer.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                val pts = snapped
                snapped = null
                if (pts != null && event.actionMasked != MotionEvent.ACTION_CANCEL) model.add(liveTool, liveColor, liveWidth, pts)
                changed(true)
                pageLayer.invalidate()
            }
        }
        return true
    }

    private fun pointsOf(stroke: Stroke): List<Pt> =
        (0 until stroke.inputs.size).map {
            val i = stroke.inputs[it]
            Pt(i.x, i.y, i.elapsedTimeMillis, if (i.hasPressure) i.pressure else 1f)
        }

    /** A stroke the library can draw, rebuilt from recorded points (after an undo or a partial erase). */
    private fun buildStroke(item: Item): Stroke {
        val batch = MutableStrokeInputBatch()
        for (p in item.pts) batch.add(InputToolType.STYLUS, p.x, p.y, p.t, StrokeInput.NO_STROKE_UNIT_LENGTH, p.p)
        val hl = item.tool == StrokeTool.TOOL_HIGHLIGHTER
        return Stroke(brushFor(if (hl) highlighterFamily else penFamily, item.color, item.width), batch)
    }

    private fun place(stroke: Stroke, item: Item): Placed {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until stroke.inputs.size) {
            val p = stroke.inputs[i]
            l = minOf(l, p.x); r = maxOf(r, p.x); t = minOf(t, p.y); b = maxOf(b, p.y)
        }
        val pad = stroke.brush.size // the pen is wider than the path it follows
        return Placed(item, stroke, l - pad, t - pad, r + pad, b + pad)
    }

    // shortcut: the pen draws and fingers move the page; a finger resting on the glass while the pen draws is
    // ignored but not told apart from a real touch by size. Proper palm rejection comes later.
    private var gestureActive = false
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    geometry.zoomBy(d.scaleFactor.toDouble(), d.focusX.toDouble(), d.focusY.toDouble())
                    pageLayer.invalidate()
                    return true
                }
            },
        )

    private fun isPen(toolType: Int) = toolType != MotionEvent.TOOL_TYPE_FINGER

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = event.actionIndex
            if (isPen(event.getToolType(i)) && strokeId == null && snapped == null && !erasing && !lassoing && !lining && !textTapping) {
                gestureActive = false // a pen landing after a palm takes over
                pullX = 0.0; pullY = 0.0
                requestUnbufferedDispatch(event) // deliver touch events as they happen, not once per frame
                pointerId = event.getPointerId(i)
                // The back end of the S Pen always erases whole strokes.
                eraserEnd = event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER
                if (tool == Tool.ERASER_STROKE || tool == Tool.ERASER_PART || eraserEnd) {
                    erasing = true
                    eraseTo(event, i)
                    return true
                }
                if (tool == Tool.TEXT) {
                    textTapping = true
                    textDown(geometry.toPageX(event.getX(i).toDouble()).toFloat(), geometry.toPageY(event.getY(i).toDouble()).toFloat())
                    return true
                }
                if (tool == Tool.LINE) {
                    lining = true
                    lineStartX = geometry.toPageX(event.getX(i).toDouble()).toFloat()
                    lineStartY = geometry.toPageY(event.getY(i).toDouble()).toFloat()
                    lineStartMs = event.eventTime
                    lineEnd = lineStartX to lineStartY
                    return true
                }
                if (tool == Tool.LASSO) {
                    lassoing = true
                    startLasso(event, i)
                    return true
                }
                val hl = tool == Tool.HIGHLIGHTER
                liveTool = if (hl) StrokeTool.TOOL_HIGHLIGHTER else StrokeTool.TOOL_PEN
                liveColor = if (hl) highlighterColor else penColor
                liveWidth = if (hl) highlighterWidth else penWidth
                strokeId = liveLayer.startStroke(event, pointerId, brush, screenToPage())
                drawn.clear()
                drawStartMs = event.eventTime
                hold.start(geometry.toPageX(event.getX(i).toDouble()).toFloat(), geometry.toPageY(event.getY(i).toDouble()).toFloat())
                collect(event, i)
                removeCallbacks(holdCheck)
                if (autoStraighten && liveTool == StrokeTool.TOOL_PEN) postDelayed(holdCheck, penmanship.holdMs)
                return true
            }
            if (!isPen(event.getToolType(i)) && strokeId == null && (action == MotionEvent.ACTION_DOWN || gestureActive)) {
                gestureActive = true
            }
        }
        if (textTapping) return textGesture(event)
        return if (erasing) erase(event) else if (lassoing) lasso(event) else if (lining) line(event) else if (snapped != null) snapping(event) else if (strokeId != null) drawing(event) else moving(event)
    }

    // ---- Text boxes: tap the page with the text tool to make a box or edit one. An EditText sits over the box
    // while it is edited, so the keyboard, selection and spell-check are the system's. ----

    /** What the next new box gets, and what the picker changes. Zero alpha: ordinary ink, no highlight. */
    var textColor = 0
        private set
    var textHighlight = 0
        private set

    private var textTapping = false
    private var editor: EditText? = null
    private var editBox: TextBox? = null // the box as edited; its ID is empty until it is added
    private var editScale = 1.0 // the page's scale when the editor was made; it is zoomed by the ratio since
    private val layouts = HashMap<String, Pair<TextBox, StaticLayout>>()

    val editingText get() = editor != null

    private fun layoutOf(box: TextBox): StaticLayout {
        val c = layouts[box.id]
        if (c != null && c.first === box) return c.second
        return TextRender.layout(box).also { layouts[box.id] = box to it }
    }

    // A pen gesture with the text tool: a tap on a box edits it, a drag over empty page draws the new box's
    // width (and where it starts), a tap on empty page does nothing, and anything while editing only finishes.
    private var textHit: TextBox? = null // the box the pen went down on, if any
    private var textDragging = false // going to make a new box
    private var textFromX = 0f
    private var textFromY = 0f
    private var textToX = 0f
    private var textToY = 0f

    private fun textDown(px: Float, py: Float) {
        textHit = null
        textDragging = false
        if (editor != null) { finishText(); return } // tapping away finishes; it does not also start another
        textHit = model.texts.lastOrNull { it.contains(px, py, layoutOf(it).height.toFloat()) }
        if (textHit != null) return
        textDragging = true
        textFromX = px; textFromY = py; textToX = px; textToY = py
    }

    private fun textGesture(event: MotionEvent): Boolean {
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0 && textDragging) {
                textToX = geometry.toPageX(event.getX(i).toDouble()).toFloat()
                textToY = geometry.toPageY(event.getY(i).toDouble()).toFloat()
                pageLayer.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                textTapping = false
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                val hit = textHit
                textHit = null
                if (!cancelled && hit != null) startEdit(hit, false)
                if (textDragging) {
                    textDragging = false
                    pageLayer.invalidate()
                    if (!cancelled) newTextBox()
                }
            }
        }
        return true
    }

    /** The dragged rectangle becomes a box: its left edge, top and width; the height follows the text. */
    private fun newTextBox() {
        var left = minOf(textFromX, textToX)
        var right = maxOf(textFromX, textToX)
        if (!geometry.infinite) { left = left.coerceIn(0f, geometry.pageW.toFloat()); right = right.coerceIn(0f, geometry.pageW.toFloat()) }
        if (right - left < TEXT_MIN_WIDTH) return // a tap or a sliver: not a box
        startEdit(TextBox("", left, minOf(textFromY, textToY), right - left, TEXT_SIZE, "", textColor, textHighlight), true)
    }

    private val dragDash = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    private val dragPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF6750A4.toInt(); isAntiAlias = true
        pathEffect = dragDash
    }

    private fun drawTextDragPreview(canvas: Canvas) {
        if (!textDragging) return
        val g = geometry
        canvas.drawRect(
            g.toScreenX(minOf(textFromX, textToX).toDouble()).toFloat(), g.toScreenY(minOf(textFromY, textToY).toDouble()).toFloat(),
            g.toScreenX(maxOf(textFromX, textToX).toDouble()).toFloat(), g.toScreenY(maxOf(textFromY, textToY).toDouble()).toFloat(), dragPaint,
        )
    }

    private fun startEdit(box: TextBox, isNew: Boolean) {
        val s = geometry.scale
        editScale = s
        editBox = box
        editIsNew = isNew
        val ed = EditText(context).apply {
            setText(box.text)
            setSelection(box.text.length)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            typeface = Typeface.SANS_SERIF
            TextRender.smooth(paint)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (box.fontSize * s).toFloat())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            pivotX = 0f
            pivotY = 0f
            layoutParams = LayoutParams((box.width * s).toInt().coerceAtLeast(1), LayoutParams.WRAP_CONTENT)
        }
        editor = ed
        styleEditor()
        addView(ed)
        ed.requestFocus()
        ed.post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(ed, 0) }
        pageLayer.invalidate()
    }

    private var editIsNew = false

    private fun styleEditor() {
        val ed = editor ?: return
        val box = editBox ?: return
        ed.setTextColor(TextRender.ink(box.color))
        ed.setBackgroundColor(if (TextRender.hasHighlight(box.highlight)) box.highlight else Color.TRANSPARENT)
    }

    /** Puts the editor over the box, following pan and zoom without laying it out again. */
    private fun positionEditor() {
        val ed = editor ?: return
        val box = editBox ?: return
        val g = geometry
        ed.translationX = g.toScreenX(box.x.toDouble()).toFloat()
        ed.translationY = g.toScreenY(box.y.toDouble()).toFloat()
        ed.scaleX = (g.scale / editScale).toFloat()
        ed.scaleY = ed.scaleX
        ed.rotation = Math.toDegrees(box.angle.toDouble()).toFloat() // about the top left, the pivot
    }

    /** Sets the colours for new boxes and for the box being edited (they are saved with the text). */
    fun setTextStyle(color: Int, highlight: Int) {
        textColor = color
        textHighlight = highlight
        editBox = editBox?.copy(color = color, highlight = highlight)
        styleEditor()
        // Not editing: the boxes the lasso holds take the colours, all in one undo step.
        if (editor == null && model.setTexts(model.texts.filter { it.id in selection }.map { it.copy(color = color, highlight = highlight) })) changed(true)
    }

    /** Ends editing: the box is added or changed (one undo step), or removed if its text is empty. */
    fun finishText() {
        val ed = editor ?: return
        val box = editBox ?: return
        val text = ed.text.toString()
        editor = null
        editBox = null
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(ed.windowToken, 0)
        removeView(ed)
        val now = box.copy(text = text)
        when {
            text.isBlank() -> if (!editIsNew) model.delete(setOf(box.id))
            editIsNew -> model.addText(box.x, box.y, box.width, box.fontSize, text, box.color, box.highlight)
            else -> model.texts.firstOrNull { it.id == box.id }?.let {
                if (it.text != text || it.color != box.color || it.highlight != box.highlight) model.setText(now)
            }
        }
        changed(true)
        pageLayer.invalidate()
    }

    // ---- Lasso: draw a loop round strokes, then drag inside the box to move or a corner to resize. ----

    private val selection = HashSet<String>()
    private var lassoing = false
    private enum class Drag { LOOP, MOVE, RESIZE, ROTATE }
    private var drag = Drag.LOOP
    private val loop = ArrayList<Float>() // page units: x0, y0, x1, y1, ...
    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private var dragCorner = 0
    private var dragBox: Quad? = null // the selection's frame when the drag began
    private var dragMap: Affine? = null // the move or resize so far; the strokes are only changed when the pen lifts
    private val dashEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    private val dashed = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF1565C0.toInt()
        pathEffect = dashEffect
    }

    /** Draw the selection's outline, the lasso loop and the text box being dragged out as solid lines, not dashed. */
    var solidOutline = false
        set(v) {
            field = v
            dashed.pathEffect = if (v) null else dashEffect
            dragPaint.pathEffect = if (v) null else dragDash
            pageLayer.invalidate()
        }
    private val handle = Paint().apply { color = 0xFF1565C0.toInt() }

    fun deleteSelection() {
        if (model.delete(selection)) {
            selection.clear()
            changed(true)
        }
    }

    /** The box round a text box on the page (round its corners, so a turned one is boxed loosely). */
    private fun textRect(b: TextBox): Box {
        val c = b.corners(layoutOf(b).height.toFloat())
        return Box(c.minOf { it.first }.toDouble(), c.minOf { it.second }.toDouble(), c.maxOf { it.first }.toDouble(), c.maxOf { it.second }.toDouble())
    }

    /**
     * The frame round the selection, with the move, resize or turn being dragged applied. Only text boxes that
     * share an angle get a turned frame (it hugs them); strokes, or a mix, get a level one.
     */
    private fun selectionQuad(): Quad? {
        val strokes = model.items.filter { it.id in selection }
        val boxes = model.texts.filter { it.id in selection }
        val base = if (strokes.isEmpty() && boxes.isNotEmpty() && boxes.all { LassoGeometry.sameTurn(it.angle, boxes[0].angle) }) {
            LassoGeometry.orientedQuad(boxes.flatMap { it.corners(layoutOf(it).height.toFloat()) }, boxes[0].angle)
        } else {
            Quad.of(LassoGeometry.bounds(strokes, boxes.map(::textRect)) ?: return null)
        }
        return dragMap?.let { base.map(it) } ?: base
    }

    private fun drawLassoOverlay(canvas: Canvas) {
        val g = geometry
        if (loop.size >= 4) {
            val path = android.graphics.Path()
            for (k in 0 until loop.size / 2) {
                val x = g.toScreenX(loop[2 * k].toDouble()).toFloat()
                val y = g.toScreenY(loop[2 * k + 1].toDouble()).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, dashed)
        }
        val q = selectionQuad() ?: return
        val outline = android.graphics.Path()
        for ((k, c) in listOf(0, 1, 3, 2).withIndex()) {
            val (hx, hy) = q.corner(c)
            val x = g.toScreenX(hx).toFloat()
            val y = g.toScreenY(hy).toFloat()
            if (k == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        }
        outline.close()
        canvas.drawPath(outline, dashed)
        if (drag == Drag.ROTATE && dragMap != null) return // only the turned outline while turning
        val (rx, ry) = q.handle(ROTATE_STEM_PX / g.scale)
        val (tx, ty) = q.topMiddle
        canvas.drawLine(g.toScreenX(tx).toFloat(), g.toScreenY(ty).toFloat(), g.toScreenX(rx).toFloat(), g.toScreenY(ry).toFloat(), dashed)
        canvas.drawCircle(g.toScreenX(rx).toFloat(), g.toScreenY(ry).toFloat(), 12f, handle)
        for (c in 0..3) {
            val (hx, hy) = q.corner(c)
            canvas.drawCircle(g.toScreenX(hx).toFloat(), g.toScreenY(hy).toFloat(), 9f, handle)
        }
    }

    private fun startLasso(event: MotionEvent, i: Int) {
        val g = geometry
        val sx = event.getX(i)
        val sy = event.getY(i)
        val px = g.toPageX(sx.toDouble())
        val py = g.toPageY(sy.toDouble())
        val box = selectionQuad()
        dragMap = null
        if (box != null) {
            val (rx, ry) = box.handle(ROTATE_STEM_PX / g.scale)
            if (kotlin.math.hypot(g.toScreenX(rx) - sx, g.toScreenY(ry) - sy) < HANDLE_PX) {
                drag = Drag.ROTATE; dragBox = box; dragStartX = px; dragStartY = py
                return
            }
            // Corners first (a fingertip-sized target), then anywhere inside the box.
            for (c in 0..3) {
                val (hx, hy) = box.corner(c)
                if (kotlin.math.hypot(g.toScreenX(hx) - sx, g.toScreenY(hy) - sy) < HANDLE_PX) {
                    drag = Drag.RESIZE; dragCorner = c; dragBox = box
                    return
                }
            }
            if (box.contains(px, py)) {
                drag = Drag.MOVE; dragStartX = px; dragStartY = py
                return
            }
        }
        selection.clear() // touching outside lets go of the selection and starts a new loop
        drag = Drag.LOOP
        loop.clear()
        loop.add(px.toFloat()); loop.add(py.toFloat())
        pageLayer.invalidate()
    }

    private fun lasso(event: MotionEvent): Boolean {
        val g = geometry
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) {
                fun at(sx: Float, sy: Float) {
                    val px = g.toPageX(sx.toDouble())
                    val py = g.toPageY(sy.toDouble())
                    when (drag) {
                        Drag.LOOP -> { loop.add(px.toFloat()); loop.add(py.toFloat()) }
                        Drag.MOVE -> dragMap = Affine.translate(px - dragStartX, py - dragStartY)
                        Drag.RESIZE -> dragMap = LassoGeometry.scaleFromCorner(dragBox!!, dragCorner, px, py)
                        Drag.ROTATE -> {
                            val (cx, cy) = dragBox!!.centre
                            val turn = LassoGeometry.snap(LassoGeometry.turn(cx, cy, dragStartX, dragStartY, px, py))
                            dragMap = Affine.rotateAbout(turn, cx, cy)
                        }
                    }
                }
                for (h in 0 until event.historySize) at(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                at(event.getX(i), event.getY(i))
                pageLayer.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when (drag) {
                    Drag.LOOP -> if (!cancelled) {
                        selection.addAll(LassoGeometry.selected(model.items, loop.toFloatArray()))
                        selection.addAll(LassoGeometry.selectedBoxes(model.texts.map { it to layoutOf(it).height.toFloat() }, loop.toFloatArray()))
                    }
                    else -> dragMap?.takeIf { !cancelled }?.let { model.transform(selection, it) }
                }
                loop.clear()
                dragMap = null
                lassoing = false
                changed(true)
            }
        }
        return true
    }

    // ---- Straight line: the pen drags out a line from where it landed; it is stored when the pen lifts. ----

    private var lining = false
    private var lineStartX = 0f
    private var lineStartY = 0f
    private var lineStartMs = 0L
    private var lineEnd = 0f to 0f // page units, after snapping
    private val linePaint = Paint().apply { strokeCap = Paint.Cap.ROUND; isAntiAlias = true }

    private fun drawLinePreview(canvas: Canvas) {
        if (!lining) return
        val g = geometry
        linePaint.color = penColor
        linePaint.strokeWidth = maxOf(1.5f, (penWidth * g.scale).toFloat())
        canvas.drawLine(
            g.toScreenX(lineStartX.toDouble()).toFloat(), g.toScreenY(lineStartY.toDouble()).toFloat(),
            g.toScreenX(lineEnd.first.toDouble()).toFloat(), g.toScreenY(lineEnd.second.toDouble()).toFloat(), linePaint,
        )
    }

    private fun line(event: MotionEvent): Boolean {
        val g = geometry
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) {
                lineEnd = LineGeometry.end(lineStartX, lineStartY, g.toPageX(event.getX(i).toDouble()).toFloat(), g.toPageY(event.getY(i).toDouble()).toFloat())
                pageLayer.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                val pts = if (event.actionMasked == MotionEvent.ACTION_CANCEL) null
                    else LineGeometry.points(lineStartX, lineStartY, lineEnd.first, lineEnd.second, event.eventTime - lineStartMs)
                lining = false
                if (pts != null) model.add(StrokeTool.TOOL_PEN, penColor, penWidth, pts)
                changed(true)
            }
        }
        return true
    }

    private var erasing = false
    private var eraserEnd = false

    /** Follows the pen across the page; every point it passed since the last event counts, so a fast drag leaves no gaps. */
    private fun erase(event: MotionEvent): Boolean {
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) {
                for (h in 0 until event.historySize) eraseAt(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                eraseTo(event, i)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                model.endErase()
                erasing = false
                changed(true)
            }
        }
        return true
    }

    private fun eraseTo(event: MotionEvent, i: Int) = eraseAt(event.getX(i), event.getY(i))

    private fun eraseAt(sx: Float, sy: Float) {
        val g = geometry
        val partial = tool == Tool.ERASER_PART && !eraserEnd
        if (model.erase(g.toPageX(sx.toDouble()).toFloat(), g.toPageY(sy.toDouble()).toFloat(), (eraserPx / g.scale).toFloat(), partial)) {
            pageLayer.invalidate()
        }
    }

    // The finished stroke is handed to pageLayer a moment after the pen lifts, but on the SM-P620 nothing redrew the
    // screen until the next touch. Asking for a few frames in a row makes sure that hand-off gets drawn.
    private fun keepDrawing(frames: Int) {
        if (frames <= 0) return
        pageLayer.invalidate()
        postOnAnimation { keepDrawing(frames - 1) }
    }

    private fun screenToPage(): Matrix {
        val g = geometry
        return Matrix().apply {
            setTranslate(-g.tx.toFloat(), -g.ty.toFloat())
            postScale((1 / g.scale).toFloat(), (1 / g.scale).toFloat())
        }
    }

    private fun drawing(event: MotionEvent): Boolean {
        predictor.record(event)
        val predicted = predictor.predict()
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val id = strokeId ?: return true
                    val k = event.findPointerIndex(pointerId)
                    if (k >= 0 && collect(event, k)) { // moved on: the hold starts again
                        removeCallbacks(holdCheck)
                        if (autoStraighten && liveTool == StrokeTool.TOOL_PEN) postDelayed(holdCheck, penmanship.holdMs)
                    }
                    liveLayer.addToStroke(event, pointerId, id, predicted)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    val id = strokeId ?: return true
                    if (event.getPointerId(event.actionIndex) != pointerId) return true
                    removeCallbacks(holdCheck)
                    liveLayer.finishStroke(event, pointerId, id)
                    strokeId = null
                    // Without this, a fresh launch kept the stroke in the live layer and it vanished (seen on the SM-P620).
                    liveLayer.requestHandoff()
                    keepDrawing(30)
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(holdCheck)
                    strokeId?.let { liveLayer.cancelStroke(it, event) }
                    strokeId = null
                }
            }
        } finally {
            predicted?.recycle()
        }
        return true
    }

    /** Two fingers pan and zoom; one finger pans. */
    private fun moving(event: MotionEvent): Boolean {
        if (!gestureActive) return true
        scaleDetector.onTouchEvent(event)
        val action = event.actionMasked
        val skip = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var cx = 0f
        var cy = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            cx += event.getX(i); cy += event.getY(i); n++
        }
        if (n > 0) { cx /= n; cy /= n }
        if (action == MotionEvent.ACTION_MOVE) {
            val dx = (cx - lastX).toDouble()
            val dy = (cy - lastY).toDouble()
            val (leftX, leftY) = geometry.panBy(dx, dy)
            if (!geometry.infinite && n == 1) { // one finger pulls at an edge; two fingers pinch
                pullX = PageTurn.pull(pullX, dx, leftX)
                pullY = PageTurn.pull(pullY, dy, leftY)
            }
            pageLayer.invalidate()
        }
        lastX = cx
        lastY = cy
        if (action == MotionEvent.ACTION_UP) endPull()
        if (action == MotionEvent.ACTION_CANCEL) { pullX = 0.0; pullY = 0.0; pageLayer.invalidate() }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gestureActive = false
        return true
    }

    // ---- Pages: pull a fixed page past its edge with a finger to go to the page there, or add one. ----

    private var pullX = 0.0 // how far the finger has pulled past an edge, in screen pixels
    private var pullY = 0.0

    private fun endPull() {
        val pull = PageTurn.direction(pullX, pullY)
        pullX = 0.0
        pullY = 0.0
        if (pull != null) {
            val (dir, px) = pull
            val next = notebook.neighbour(model, dir)
            when (PageTurn.decide(px / pxPerUnit, dir, next != null, flow)) {
                Turn.GO -> { finishText(); notebook.go(next!!); pageChanged() }
                Turn.ADD -> { finishText(); notebook.add(model, dir); pageChanged() }
                Turn.NONE -> {}
            }
        }
        pageLayer.invalidate()
    }

    /** Another page is current: drop what belonged to the old one and show the new one. */
    private fun pageChanged() {
        selection.clear()
        loop.clear()
        dragMap = null
        placed.clear()
        layouts.clear()
        showPage()
        onPageChanged?.invoke()
    }

    private val hintBg = Paint().apply { color = 0xE6322B3A.toInt(); isAntiAlias = true }
    private val hintText = Paint().apply { color = Color.WHITE; textSize = 40f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

    /** Once the pull is long enough to count, say what letting go will do. */
    private fun drawPullHint(canvas: Canvas) {
        val (dir, px) = PageTurn.direction(pullX, pullY) ?: return
        val next = notebook.neighbour(model, dir)
        val text = when (PageTurn.decide(px / pxPerUnit, dir, next != null, flow)) {
            Turn.ADD -> "Release to add a page"
            Turn.GO -> if (dir == Dir.UP || dir == Dir.LEFT) "Release for the previous page" else "Release for the next page"
            Turn.NONE -> return
        }
        val w = hintText.measureText(text) + 56f
        val h = 80f
        val cx = when (dir) { Dir.LEFT -> w / 2 + 16f; Dir.RIGHT -> width - w / 2 - 16f; else -> width / 2f }
        // Clear of the system bars (the view runs under them); the top also has the throwaway button row.
        @Suppress("DEPRECATION") val bottom = rootWindowInsets?.systemWindowInsetBottom ?: 0
        @Suppress("DEPRECATION") val top = rootWindowInsets?.systemWindowInsetTop ?: 0
        val cy = when (dir) { Dir.UP -> top + 200f + h / 2; Dir.DOWN -> height - bottom - h / 2 - 24f; else -> height / 2f }
        canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 40f, 40f, hintBg)
        canvas.drawText(text, cx, cy + 14f, hintText)
    }

    private companion object {
        const val LINED_TOP = 1600 // 16 mm
        const val LINED_STEP = 800 // 8 mm
        const val GRID_STEP = 500 // 5 mm
        const val HANDLE_PX = 36f
        const val ROTATE_STEM_PX = 56f
        const val PULL_SHIFT = 0.4f // how much of a pull the page follows
        const val TEXT_MIN_WIDTH = 1000f // a dragged box narrower than 10 mm is not a box
        const val TEXT_SIZE = 400f // text 4 mm high
    }
}
