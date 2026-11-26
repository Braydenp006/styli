package app.styli.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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
    enum class Tool { PEN, HIGHLIGHTER, ERASER_STROKE, ERASER_PART, LASSO, LINE }

    private val model = PageModel()
    private val placed = HashMap<String, Placed>() // drawn strokes by ID, built once (pruned after every change)
    private val renderer = CanvasStrokeRenderer.create()

    var tool = Tool.PEN
        set(v) {
            field = v
            selection.clear() // a selection only lives while the lasso is the tool
            pageLayer.invalidate()
        }
    var penColor = Color.BLACK
    var penWidth = 40 // 0.4 mm, in page units
    var highlighterColor = 0x99FFEB3B.toInt() // a see-through yellow, so the writing shows through
    var highlighterWidth = 400 // 4 mm

    /**
     * Hold the pen still at the end of a pen stroke and a line or shape is drawn clean. 0 is off; 1 is normal,
     * less is stricter about what counts as a line or shape, more is more forgiving (see [ShapeGeometry.snap]).
     */
    var straightenStrength = 1f
    private val autoStraighten get() = straightenStrength > 0f

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
                canvas.restore()
                drawLassoOverlay(canvas)
                drawLinePreview(canvas)
                drawSnapPreview(canvas)
                canvas.restore()
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

    /** Shows this page: its size, template and whether it is fixed or an infinite canvas. */
    fun setPage(page: PageSet) {
        geometry = PageGeometry(page.width, page.height, page.infinite, pxPerUnit)
        template = page.template
        geometry.fit(width, height)
        pageLayer.invalidate()
    }

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

    fun undo() = changed(model.undo())
    fun redo() = changed(model.redo())

    private fun changed(did: Boolean) {
        if (!did) return
        val ids = model.items.mapTo(HashSet()) { it.id }
        placed.keys.retainAll(ids)
        selection.retainAll(ids)
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
        val clean = ShapeGeometry.snap(drawn, straightenStrength) ?: return@Runnable
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
                ShapeGeometry.snap(drawn, straightenStrength)?.let { snapped = it } // moved on: the clean shape follows
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
            if (isPen(event.getToolType(i)) && strokeId == null && snapped == null && !erasing && !lassoing && !lining) {
                gestureActive = false // a pen landing after a palm takes over
                requestUnbufferedDispatch(event) // deliver touch events as they happen, not once per frame
                pointerId = event.getPointerId(i)
                // The back end of the S Pen always erases whole strokes.
                eraserEnd = event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER
                if (tool == Tool.ERASER_STROKE || tool == Tool.ERASER_PART || eraserEnd) {
                    erasing = true
                    eraseTo(event, i)
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
                if (autoStraighten && liveTool == StrokeTool.TOOL_PEN) postDelayed(holdCheck, ShapeGeometry.HOLD_MS)
                return true
            }
            if (!isPen(event.getToolType(i)) && strokeId == null && (action == MotionEvent.ACTION_DOWN || gestureActive)) {
                gestureActive = true
            }
        }
        return if (erasing) erase(event) else if (lassoing) lasso(event) else if (lining) line(event) else if (snapped != null) snapping(event) else if (strokeId != null) drawing(event) else moving(event)
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
    private var dragBox: Box? = null // the box when the drag began
    private var dragMap: Affine? = null // the move or resize so far; the strokes are only changed when the pen lifts
    private val dashed = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF1565C0.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val handle = Paint().apply { color = 0xFF1565C0.toInt() }

    fun deleteSelection() {
        if (model.delete(selection)) {
            selection.clear()
            changed(true)
        }
    }

    private fun selectionBox(): Box? {
        val box = LassoGeometry.bounds(model.items.filter { it.id in selection }) ?: return null
        val m = dragMap?.takeIf { drag != Drag.ROTATE } ?: return box // a turned box is drawn tilted, see the overlay
        return Box(m.x(box.l.toFloat(), box.t.toFloat()).toDouble(), m.y(box.l.toFloat(), box.t.toFloat()).toDouble(),
            m.x(box.r.toFloat(), box.b.toFloat()).toDouble(), m.y(box.r.toFloat(), box.b.toFloat()).toDouble())
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
        val box = selectionBox() ?: return
        val turn = dragMap?.takeIf { drag == Drag.ROTATE }
        if (turn != null) {
            val path = android.graphics.Path()
            for ((k, c) in listOf(0, 1, 3, 2).withIndex()) {
                val (hx, hy) = LassoGeometry.corner(box, c)
                val x = g.toScreenX(turn.x(hx.toFloat(), hy.toFloat()).toDouble()).toFloat()
                val y = g.toScreenY(turn.y(hx.toFloat(), hy.toFloat()).toDouble()).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, dashed)
            return
        }
        canvas.drawRect(g.toScreenX(box.l).toFloat(), g.toScreenY(box.t).toFloat(), g.toScreenX(box.r).toFloat(), g.toScreenY(box.b).toFloat(), dashed)
        val (rx, ry) = rotateHandle(box)
        canvas.drawLine(g.toScreenX((box.l + box.r) / 2).toFloat(), g.toScreenY(box.t).toFloat(), g.toScreenX(rx).toFloat(), g.toScreenY(ry).toFloat(), dashed)
        canvas.drawCircle(g.toScreenX(rx).toFloat(), g.toScreenY(ry).toFloat(), 12f, handle)
        for (c in 0..3) {
            val (hx, hy) = LassoGeometry.corner(box, c)
            canvas.drawCircle(g.toScreenX(hx).toFloat(), g.toScreenY(hy).toFloat(), 9f, handle)
        }
    }

    /** The round handle above the middle of the top edge, a fixed distance on screen away from it. */
    private fun rotateHandle(box: Box) = (box.l + box.r) / 2 to box.t - ROTATE_STEM_PX / geometry.scale

    private fun startLasso(event: MotionEvent, i: Int) {
        val g = geometry
        val sx = event.getX(i)
        val sy = event.getY(i)
        val px = g.toPageX(sx.toDouble())
        val py = g.toPageY(sy.toDouble())
        val box = selectionBox()
        dragMap = null
        if (box != null) {
            val (rx, ry) = rotateHandle(box)
            if (kotlin.math.hypot(g.toScreenX(rx) - sx, g.toScreenY(ry) - sy) < HANDLE_PX) {
                drag = Drag.ROTATE; dragBox = box; dragStartX = px; dragStartY = py
                return
            }
            // Corners first (a fingertip-sized target), then anywhere inside the box.
            for (c in 0..3) {
                val (hx, hy) = LassoGeometry.corner(box, c)
                if (kotlin.math.hypot(g.toScreenX(hx) - sx, g.toScreenY(hy) - sy) < HANDLE_PX) {
                    drag = Drag.RESIZE; dragCorner = c; dragBox = box
                    return
                }
            }
            if (px in box.l..box.r && py in box.t..box.b) {
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
                            val b = dragBox!!
                            val cx = (b.l + b.r) / 2
                            val cy = (b.t + b.b) / 2
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
                    Drag.LOOP -> if (!cancelled) selection.addAll(LassoGeometry.selected(model.items, loop.toFloatArray()))
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
                        if (autoStraighten && liveTool == StrokeTool.TOOL_PEN) postDelayed(holdCheck, ShapeGeometry.HOLD_MS)
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
            geometry.panBy((cx - lastX).toDouble(), (cy - lastY).toDouble())
            pageLayer.invalidate()
        }
        lastX = cx
        lastY = cy
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gestureActive = false
        return true
    }

    private companion object {
        const val LINED_TOP = 1600 // 16 mm
        const val LINED_STEP = 800 // 8 mm
        const val GRID_STEP = 500 // 5 mm
        const val HANDLE_PX = 36f
        const val ROTATE_STEM_PX = 56f
    }
}
