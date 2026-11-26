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
import android.view.VelocityTracker
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.ExperimentalInkHandoffApi
import androidx.ink.authoring.ExperimentalInkLatencyDataApi
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import kotlin.math.cos
import kotlin.math.sin
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
    enum class Tool { PEN, HIGHLIGHTER, ERASER_STROKE, ERASER_PART, LASSO, LINE, LINE_HIGHLIGHTER, DOTTED, LINE_DOTTED, LASER, TEXT, TABLE, SHAPE, BUCKET, CONNECTOR }

    /** The pages of this notebook (in memory only for now); the view shows [Notebook.current]. */
    val notebook = Notebook()

    /** The page the drawing and editing code is working on: the one being drawn, or the one under the pen. */
    private var active: PageModel = notebook.current
    private val model get() = active

    /** The page the pen last went down on; every gesture belongs to it until the pen goes down on another. */
    private var work: PageModel = notebook.current

    /** The paper and orientation last chosen: empty cells are shown in it, and a page added or dropped there gets it. */
    private var emptyLook: PageSet = notebook.current.page

    /** The page being worked on. */
    val page get() = work.page

    /** Called when the page being worked on changes (the pen went down on another, or one was added or taken back). */
    var onPageChanged: (() -> Unit)? = null

    /** Called with the zoom as a percentage of a page fitted to the screen (100 is fitted) whenever it changes. */
    var onZoom: ((Int) -> Unit)? = null
    private var shownZoom = -1

    /** Flies into [p]: fitted to the screen and in the middle of it, as one camera move. It becomes the page being worked on. */
    private fun zoomIntoPage(p: PageModel) {
        if (p !== work) becomeWork(p)
        flyTo(p)
    }

    /**
     * Moves the view to show [p] fitted to the width of the screen, in one smooth "fly" (the zoom changes by the same
     * factor each moment, so it feels even, while the page glides to its place and lands with a small overshoot). A
     * page shorter than the screen is centred up and down; a taller one has its top at the margin.
     */
    private fun flyTo(p: PageModel) {
        val l = layout ?: return
        stopGlide()
        val s1 = vp.fitScale(maxOf(1, p.page.width))
        val (ox, oy) = l.origin(p)
        val pw = p.page.width.toDouble(); val ph = p.page.height.toDouble()
        fun place(size: Double, view: Int, origin: Double): Double =
            if (size * s1 + 2 * PageGeometry.MARGIN <= view) (view - size * s1) / 2 - origin * s1 else PageGeometry.MARGIN - origin * s1
        val tx1 = place(pw, width, ox)
        val ty1 = place(ph, height, oy)
        // The page's own middle travels from where it is on the screen to the middle of where it will be.
        val cx = ox + pw / 2; val cy = oy + ph / 2
        val s0 = vp.scale
        val px0 = vp.tx + cx * s0; val py0 = vp.ty + cy * s0
        val px1 = tx1 + cx * s1; val py1 = ty1 + cy * s1
        val ease = android.view.animation.AccelerateDecelerateInterpolator()
        val land = android.view.animation.OvershootInterpolator(0.9f)
        snapAnim?.cancel()
        snapAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                val f = it.animatedFraction
                val e = land.getInterpolation(ease.getInterpolation(f)).toDouble()
                val s = s0 * Math.pow(s1 / s0, e)
                vp.viewTo(s, px0 + (px1 - px0) * e - cx * s, py0 + (py1 - py0) * e - cy * s)
                pageLayer.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { vp.viewTo(s1, tx1, ty1); pageLayer.invalidate() }
            })
            start()
        }
    }

    /** Zooms out, centred, until every page is in view: one smooth move, with no page becoming the working page. */
    fun overview() {
        val l = layout ?: return
        stopGlide()
        val s1 = vp.overviewScale()
        val cx = l.worldW / 2.0; val cy = l.worldH / 2.0
        val s0 = vp.scale
        val px0 = vp.tx + cx * s0; val py0 = vp.ty + cy * s0
        val ease = android.view.animation.AccelerateDecelerateInterpolator()
        snapAnim?.cancel()
        snapAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                val e = ease.getInterpolation(it.animatedFraction).toDouble()
                val s = s0 * Math.pow(s1 / s0, e)
                vp.viewTo(s, px0 + (width / 2.0 - px0) * e - cx * s, py0 + (height / 2.0 - py0) * e - cy * s)
                pageLayer.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { vp.viewTo(s1, (width - l.worldW * s1) / 2, (height - l.worldH * s1) / 2); pageLayer.invalidate() }
            })
            start()
        }
    }

    /**
     * Fits the page in the middle of the screen to its width and centres it, in one smooth fly (a canvas goes back to
     * real size, about the middle of the screen). It is the page you are looking at, not the one last tapped or drawn on.
     */
    fun fitToScreen() {
        stopGlide()
        if (vp.infinite) { vp.zoomBy(vp.fitScale() / vp.scale, width / 2.0, height / 2.0); pageLayer.invalidate(); return }
        val p = centrePage() ?: return
        if (p !== work) becomeWork(p)
        flyTo(p)
    }

    /** How scrolling goes over several pages: free across them, or locked to the grid (see [ScrollMode]). */
    var scrollMode = ScrollMode.GRID
        set(v) { field = v; stopGlide(); gridCell = null; settle(); pageLayer.invalidate() }

    /** How far a scroll carries on after the finger lets go: 1 is normal, 0 is not at all (see [Fling.start]). */
    var scrollIntensity = 1.0
    private val placed = HashMap<String, Placed>() // drawn strokes by ID, built once (pruned after every change)
    private val renderer = CanvasStrokeRenderer.create()

    var tool = Tool.PEN
        set(v) {
            if (v != Tool.TEXT) finishText()
            tabling = false
            addingBend = false
            field = v
            selection.clear() // a selection only lives while the lasso is the tool
            if (pendingClip != null) { pendingClip = null; onPlacing?.invoke(null) }
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

    /** What the erasers may touch (all, ink only, highlights only or straight lines only). */
    var eraseOnly = EraseOnly.ALL

    /** The eraser's reach on screen, so it feels the same at any zoom. */
    private val eraserPx = 10f

    private val pxPerUnit = resources.displayMetrics.xdpi / 2540.0

    // All the pages are on one scrolling surface. [vp] is the surface (scroll, zoom, edges); [geometry] is the
    // page being drawn or worked on seen through it, so the code that works on a page needs no idea where it is.
    private var vp = PageGeometry(21000, 29700, false, pxPerUnit)
    private var layout: NotebookLayout? = null // null while a notebook holds an infinite canvas: that page alone
    private var geometry = PageView(vp, 0.0, 0.0, 21000, 29700, false)
    private var shownKey: List<List<Int>> = emptyList()
    private val toScreen = Matrix()
    private val line = Paint().apply { strokeWidth = 1f }
    private val sheetEdge = Paint().apply { color = 0xFFAAAAAA.toInt(); style = Paint.Style.STROKE }

    private val pageLayer =
        object : View(context) {
            override fun onDraw(canvas: Canvas) {
                activate(work)
                vp.percent(maxOf(1, work.page.width)).let { if (it != shownZoom) { shownZoom = it; onZoom?.invoke(it) } }
                positionEditor()
                canvas.save()
                canvas.translate((pullDrawX * PULL_SHIFT).toFloat(), (pullDrawY * PULL_SHIFT).toFloat()) // the surface follows a pull a little
                canvas.drawColor(if (vp.infinite) Color.WHITE else 0xFFD9D9D9.toInt())
                val l = layout
                val pages = if (l == null) listOf(work) else vp.visible().let { l.visible(it[0], it[1], it[2], it[3]) }
                for (p in pages) {
                    activate(p)
                    drawPage(canvas)
                }
                activate(work)
                drawCarried(canvas)
                drawLassoOverlay(canvas)
                drawGuides(canvas)
                drawLinePreview(canvas)
                drawDottedPreview(canvas)
                drawSnapPreview(canvas)
                drawTextDragPreview(canvas)
                drawShapeDragPreview(canvas)
                drawConnectorPreview(canvas)
                drawTableDragPreview(canvas)
                canvas.restore() // the pull's shift
                drawPullHint(canvas)
                drawCarry(canvas)
                drawPlusCells(canvas)
                drawBendHint(canvas)
                drawRuler(canvas)
                drawLaser(canvas)
            }
        }

    /** Draws the active page: its sheet, template, strokes and text boxes, clipped to the sheet. */
    private fun drawPage(canvas: Canvas) {
        val g = geometry
        canvas.save()
        if (!g.infinite) {
            val sheet = RectF(
                g.toScreenX(0.0).toFloat(), g.toScreenY(0.0).toFloat(),
                g.toScreenX(g.pageW.toDouble()).toFloat(), g.toScreenY(g.pageH.toDouble()).toFloat(),
            )
            canvas.drawRect(sheet, sheetFill)
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
        val carried = dragMap != null && drag == Drag.MOVE // a dragged selection is drawn last, over every page (see [drawCarried])
        for (id in model.order) if (!(carried && id in selection)) drawObject(canvas, id, vl, vt, vr, vb) // bottom layer first
        canvas.restore()
        canvas.restore()
    }

    /** Draws one thing of the active page (in page units: the canvas already has the page's matrix). */
    private fun drawObject(canvas: Canvas, id: String, vl: Float, vt: Float, vr: Float, vb: Float) {
        val table = model.tableOf(id)
        if (table != null) { drawTable(canvas, linePreview?.takeIf { it.id == table.id } ?: table, vl, vt, vr, vb); return }
        val item = model.itemOf(id)
        if (item != null && item.tool == StrokeTool.TOOL_DOTTED) { drawDotted(canvas, item, vl, vt, vr, vb); return }
        if (item != null) {
            // A transform makes a new Item under the same ID, so an old picture is not reused.
            val p = placed[item.id]?.takeIf { it.item === item } ?: place(buildStroke(item), item).also { placed[item.id] = it }
            val moved = dragMap?.takeIf { item.id in selection }
            if (moved == null && (p.r < vl || p.l > vr || p.b < vt || p.t > vb)) return
            canvas.save()
            if (moved != null) canvas.concat(Matrix().apply { setValues(floatArrayOf(moved.a.toFloat(), moved.c.toFloat(), moved.e.toFloat(), moved.b.toFloat(), moved.d.toFloat(), moved.f.toFloat(), 0f, 0f, 1f)) })
            renderer.draw(canvas, p.stroke, toScreen)
            canvas.restore()
            return
        }
        val fill = model.fillOf(id)
        if (fill != null) { drawFill(canvas, fill); return }
        val shape = model.shapeOf(id)
        if (shape != null) { drawShape(canvas, shape); return }
        val conn = model.connectorOf(id)
        if (conn != null) { drawConnector(canvas, conn); return }
        val box = model.textOf(id) ?: return
        if (box.id == editBox?.id) return // the editor shows it
        val moved = dragMap?.takeIf { box.id in selection }
        canvas.save()
        if (moved != null) canvas.concat(Matrix().apply { setValues(floatArrayOf(moved.a.toFloat(), moved.c.toFloat(), moved.e.toFloat(), moved.b.toFloat(), moved.d.toFloat(), moved.f.toFloat(), 0f, 0f, 1f)) })
        TextRender.draw(canvas, box, layoutOf(box))
        canvas.restore()
    }

    /** The selection being dragged with the lasso, drawn over all the pages and not cut off at its own sheet, so it can be carried to another page. */
    private fun drawCarried(canvas: Canvas) {
        if (dragMap == null || drag != Drag.MOVE || selection.isEmpty()) return
        val g = geometry
        toScreen.setScale(g.scale.toFloat(), g.scale.toFloat())
        toScreen.postTranslate(g.tx.toFloat(), g.ty.toFloat())
        val (vl, vt, vr, vb) = g.visible().map { it.toFloat() }
        canvas.save()
        canvas.concat(toScreen)
        for (id in model.order) if (id in selection) drawObject(canvas, id, vl, vt, vr, vb)
        canvas.restore()
    }

    private val sheetFill = Paint().apply { color = Color.WHITE }

    private fun drawTemplate(canvas: Canvas) {
        val g = geometry
        val (vl, vt, vr, vb) = g.visible()
        // A fixed page has no lines outside the sheet (the clip covers the sides; the top margin is the offset).
        val top = if (g.infinite) vt else maxOf(vt, 0.0)
        val bottom = if (g.infinite) vb else minOf(vb, g.pageH.toDouble())
        val left = if (g.infinite) vl else maxOf(vl, 0.0)
        val right = if (g.infinite) vr else minOf(vr, g.pageW.toDouble())
        when (model.page.template) {
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

    /** Changes the working page's size, template or fixed-or-infinite (one undo step). */
    fun setPage(page: PageSet) {
        // A page's place in the grid is not part of its size and look: keep it.
        work.setPage(page.toBuilder().setCol(work.page.col).setRow(work.page.row).build())
        if (!page.infinite) emptyLook = page
        rebuildLayout(false)
    }

    private fun layoutKey() = notebook.pages.map { listOf(it.page.col, it.page.row, it.page.width, it.page.height, if (it.page.infinite) 1 else 0) }

    /** Makes [p] the page the code works on, with its place on the surface. */
    private fun activate(p: PageModel) {
        active = p
        val (ox, oy) = layout?.origin(p) ?: (0.0 to 0.0)
        geometry = PageView(vp, ox, oy, p.page.width, p.page.height, p.page.infinite)
    }

    /**
     * Lays the pages out again (a page was added, taken back, or changed size). The view stays where it was
     * unless [reset] (the screen's size changed): then the working page is fitted to the width.
     */
    private fun rebuildLayout(reset: Boolean) {
        stopGlide()
        val old = vp
        val pages = notebook.pages
        if (pages.any { it.page.infinite }) {
            // An infinite canvas has no edges to lay pages beside: it is shown alone.
            layout = null
            vp = PageGeometry(work.page.width, work.page.height, work.page.infinite, pxPerUnit)
        } else {
            val l = NotebookLayout(pages, refW = emptyLook.width, refH = emptyLook.height)
            layout = l
            vp = PageGeometry(l.worldW, l.worldH, false, pxPerUnit, l.firstColW, l.lastColW, l.firstRowH, l.lastRowH)
        }
        vp.fit(width, height, maxOf(1, work.page.width))
        if (!reset && old.infinite == vp.infinite) vp.restore(old)
        shownKey = layoutKey()
        activate(work)
        pageLayer.invalidate()
    }

    init { rebuildLayout(true) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildLayout(true)
    }

    /** The pen went down on [p]: it is the working page now, and what belonged to the last one is let go. */
    private fun becomeWork(p: PageModel) {
        selection.clear()
        cellSel = null
        loop.clear()
        dragMap = null
        work = p
        notebook.go(p)
        if (layoutKey() != shownKey) rebuildLayout(false) else activate(p) // a page was added or taken back
        onPageChanged?.invoke()
    }

    /** The page under the pen (or the closest one, in a gap) becomes the working page, unless text is being edited. */
    private fun pickPage(sx: Float, sy: Float) {
        val l = layout ?: return
        val wx = vp.toPageX(sx.toDouble())
        val wy = vp.toPageY(sy.toDouble())
        val p = l.pageAt(wx, wy) ?: l.nearest(wx, wy)
        if (p !== work) becomeWork(p)
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
                    strokes.forEach { (id, it) ->
                        // The stroke belongs to the page it started on, even if the pen has gone to another since.
                        val item = (strokePage.remove(id) ?: model).add(liveTool, liveColor, liveWidth, pointsOf(it))
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
        val keep = centrePage()
        val before = keep?.let { layout?.origin(it) }
        val (tx, ty) = vp.tx to vp.ty
        if (!model.canUndo && notebook.removeMade()) {
            if (work !in notebook.pages) { becomeWork(notebook.current); layout?.origin(work)?.let { vp.scrollTo(it.first, it.second) } }
            else { notebook.go(work); rebuildLayout(false); keepView(keep, before, tx, ty) }
            pageLayer.invalidate()
        } else changed(model.undo())
    }

    /** After pages came or went, puts the view back where [keep] was on the screen ([before] is its place on the old surface, ([tx], [ty]) the old offset). */
    private fun keepView(keep: PageModel?, before: Pair<Double, Double>?, tx: Double, ty: Double, extraX: Double = 0.0, extraY: Double = 0.0) {
        val after = keep?.takeIf { it in notebook.pages }?.let { layout?.origin(it) }
        if (before == null || after == null) return
        vp.offsetTo(tx - (after.first - before.first) * vp.scale + extraX, ty - (after.second - before.second) * vp.scale + extraY)
    }
    fun redo() { finishText(); changed(model.redo()) }

    private fun changed(did: Boolean) {
        if (!did) return
        val all = notebook.pages
        dottedPaths.keys.retainAll(all.flatMapTo(HashSet()) { p -> p.items.map { it.id } })
        placed.keys.retainAll(all.flatMapTo(HashSet()) { p -> p.items.map { it.id } })
        layouts.keys.retainAll(all.flatMapTo(HashSet()) { p -> p.texts.map { it.id } })
        selection.retainAll(model.items.mapTo(HashSet<String>()) { it.id } + model.texts.map { it.id } + model.tables.map { it.id } + model.shapes.map { it.id } + model.fills.map { it.id } + model.connectors.map { it.id })
        fillPaths.keys.retainAll(all.flatMapTo(HashSet()) { p -> p.fills.map { it.id } })
        shapeLayouts.keys.retainAll(all.flatMapTo(HashSet()) { p -> p.shapes.map { it.id } })
        cellSel = cellSel?.takeIf { (r, c) -> lineTable()?.let { r < it.rows && c < it.cols } == true }
        if (layoutKey() != shownKey) rebuildLayout(false) // an undo or redo of a size change
        pageLayer.invalidate()
    }

    // What the stroke being drawn was made with, in case the tool is changed before the pen lifts.
    private val strokePage = HashMap<InProgressStrokeId, PageModel>()
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
        strokePage.remove(id)
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
        if (action == MotionEvent.ACTION_DOWN) stopGlide() // a touch catches the page
        if (rulerGrab(event)) return true
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = event.actionIndex
            if (isPen(event.getToolType(i)) && strokeId == null && snapped == null && !erasing && !lassoing && !lining && !lasering && !shaping && !bucketing && !connecting && !dotting && !textTapping && !tabling) {
                gestureActive = false // a pen landing after a palm takes over
                pullX = 0.0; pullY = 0.0
                requestUnbufferedDispatch(event) // deliver touch events as they happen, not once per frame
                pointerId = event.getPointerId(i)
                if (editor == null) pickPage(event.getX(i), event.getY(i)) // else the pen finishes the text on its page first
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
                if (tool == Tool.CONNECTOR) {
                    connecting = true
                    connectFrom = geometry.toPageX(event.getX(i).toDouble()).toFloat() to geometry.toPageY(event.getY(i).toDouble()).toFloat()
                    connectTo = connectFrom
                    return true
                }
                if (tool == Tool.BUCKET || pendingClip != null) { // a waiting clip uses the bucket's tap
                    bucketing = true
                    bucketX = geometry.toPageX(event.getX(i).toDouble()).toFloat()
                    bucketY = geometry.toPageY(event.getY(i).toDouble()).toFloat()
                    return true
                }
                if (tool == Tool.SHAPE) {
                    shaping = true
                    val (x, y) = onPage(geometry.toPageX(event.getX(i).toDouble()).toFloat(), geometry.toPageY(event.getY(i).toDouble()).toFloat())
                    shapeFromX = x; shapeFromY = y; shapeToX = x; shapeToY = y
                    return true
                }
                if (tool == Tool.TABLE) {
                    tabling = true
                    val (x, y) = onPage(geometry.toPageX(event.getX(i).toDouble()).toFloat(), geometry.toPageY(event.getY(i).toDouble()).toFloat())
                    tableFromX = x; tableFromY = y; tableToX = x; tableToY = y
                    return true
                }
                if (tool == Tool.LASER) {
                    lasering = true
                    laserTo(event, i)
                    return true
                }
                val rl = ruler
                if (rl != null && (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.DOTTED)) {
                    val side = rl.edgeNear(event.getX(i), event.getY(i), rulerReachPx)
                    if (side != 0) { // the pen is at the ruler's edge: it draws a line along it
                        val (sx, sy) = rl.onEdge(event.getX(i), event.getY(i), side)
                        ruling = true
                        rulerSide = side
                        lineHl = tool == Tool.HIGHLIGHTER
                        lineDotted = tool == Tool.DOTTED
                        lining = true
                        lineStartX = geometry.toPageX(sx.toDouble()).toFloat()
                        lineStartY = geometry.toPageY(sy.toDouble()).toFloat()
                        lineStartMs = event.eventTime
                        lineEnd = lineStartX to lineStartY
                        return true
                    }
                }
                if (tool == Tool.DOTTED) {
                    dotting = true
                    drawn.clear()
                    drawStartMs = event.eventTime
                    collect(event, i)
                    pageLayer.invalidate()
                    return true
                }
                if (tool == Tool.LINE || tool == Tool.LINE_HIGHLIGHTER || tool == Tool.LINE_DOTTED) {
                    lineHl = tool == Tool.LINE_HIGHLIGHTER
                    lineDotted = tool == Tool.LINE_DOTTED
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
                strokeId?.let { strokePage[it] = work }
                drawn.clear()
                drawStartMs = event.eventTime
                hold.start(geometry.toPageX(event.getX(i).toDouble()).toFloat(), geometry.toPageY(event.getY(i).toDouble()).toFloat())
                collect(event, i)
                removeCallbacks(holdCheck)
                if (autoStraighten && liveTool == StrokeTool.TOOL_PEN) postDelayed(holdCheck, penmanship.holdMs)
                return true
            }
            if (!isPen(event.getToolType(i)) && strokeId == null && (action == MotionEvent.ACTION_DOWN || gestureActive)) {
                if (action == MotionEvent.ACTION_DOWN) { slid = 0.0; moveTotal = 0.0; pinched = false; velocity?.recycle(); velocity = VelocityTracker.obtain(); beginGrid(); armCarry(event.getX(i), event.getY(i)) }
                gestureActive = true
            }
        }
        if (rulerFingers(event)) return true
        if (lasering) return laser(event)
        if (textTapping) return textGesture(event)
        if (tabling) return tableGesture(event)
        if (shaping) return shapeGesture(event)
        if (bucketing) return bucketGesture(event)
        if (connecting) return connectGesture(event)
        if (dotting) return dotGesture(event)
        return if (erasing) erase(event) else if (lassoing) lasso(event) else if (lining) line(event) else if (snapped != null) snapping(event) else if (strokeId != null) drawing(event) else moving(event)
    }

    // ---- Shapes: pick a premade shape, drag out its box; the lasso then moves, resizes, turns and styles it, and the
    // text tool types inside it. ----

    /** Which premade shape the next one is. */
    var shapePreset = ShapePreset.RECTANGLE

    private var shaping = false
    private var shapeFromX = 0f
    private var shapeFromY = 0f
    private var shapeToX = 0f
    private var shapeToY = 0f
    private val shapePath = android.graphics.Path()
    private val shapeFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val shapeLinePaint = Paint().apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val shapeLayouts = HashMap<String, Pair<Shape, StaticLayout>>()
    private var editShape: Shape? = null // the shape whose text is being typed

    /** The shape being reshaped by a handle, as it would be now (saved when the pen lifts), and as it was when the drag began. */
    private var shapePreview: Shape? = null
    private var shapeBase: Shape? = null
    private var geoIndex = 0 // which corner a drag has hold of

    /** Lines to draw while something snaps (page units); empty otherwise. */
    private var guides: List<ShapeEdit.Guide> = emptyList()
    private val guidePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFFE91E63.toInt(); isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private fun drawGuides(canvas: Canvas) {
        val g = geometry
        for (l in guides) canvas.drawLine(
            g.toScreenX(l.x1.toDouble()).toFloat(), g.toScreenY(l.y1.toDouble()).toFloat(),
            g.toScreenX(l.x2.toDouble()).toFloat(), g.toScreenY(l.y2.toDouble()).toFloat(), guidePaint,
        )
    }

    private fun guide(a: Pair<Float, Float>, b: Pair<Float, Float>) = ShapeEdit.Guide(a.first, a.second, b.first, b.second)

    /** The shape that is selected on its own (as it would be now): its corners or corner radius get handles. */
    private fun shapeAlone(): Shape? {
        if (selection.size != 1) return null
        val s = model.shapeOf(selection.first()) ?: return null
        return shapePreview?.takeIf { it.id == s.id } ?: s
    }

    /** The handles that edit a shape's geometry: (drag, index, where on the page). */
    private fun geometryHandles(s: Shape): List<Triple<Drag, Int, Pair<Float, Float>>> = when (s.kind) {
        ShapeKind.POLYGON -> s.vertices.mapIndexed { k, (fx, fy) -> Triple(Drag.SHAPE_VERTEX, k, s.spot(fx, fy)) }
        ShapeKind.RECT -> listOf(Triple(Drag.SHAPE_CORNER, 0, ShapeEdit.cornerHandle(s, CORNER_INSET_PX / geometry.scale.toFloat())))
        ShapeKind.ELLIPSE -> emptyList()
    }

    private fun shapeGesture(event: MotionEvent): Boolean {
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) {
                val (x, y) = onPage(geometry.toPageX(event.getX(i).toDouble()).toFloat(), geometry.toPageY(event.getY(i).toDouble()).toFloat())
                shapeToX = x; shapeToY = y
                pageLayer.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                shaping = false
                val (l, t, w, h) = draggedBox()
                if (event.actionMasked != MotionEvent.ACTION_CANCEL && w >= SHAPE_MIN && h >= SHAPE_MIN) { // a tap or a sliver makes nothing
                    model.addShape { shapePreset.make(it, l, t, w, h, penWidth.toFloat(), penColor) }
                    changed(true)
                }
                pageLayer.invalidate()
            }
        }
        return true
    }

    /** The box dragged out so far: left, top, width, height. A square or circle takes the longer side both ways, towards where the pen is. */
    private fun draggedBox(): List<Float> {
        var w = Math.abs(shapeToX - shapeFromX); var h = Math.abs(shapeToY - shapeFromY)
        if (shapePreset.locked) { val d = maxOf(w, h); w = d; h = d }
        return listOf(if (shapeToX >= shapeFromX) shapeFromX else shapeFromX - w, if (shapeToY >= shapeFromY) shapeFromY else shapeFromY - h, w, h)
    }

    /** The shape being dragged out, in the drag colour. */
    private fun drawShapeDragPreview(canvas: Canvas) {
        if (!shaping) return
        val g = geometry
        val (l, t, w, h) = draggedBox()
        if (w < 1f || h < 1f) return
        canvas.save()
        canvas.translate(g.toScreenX(l.toDouble()).toFloat(), g.toScreenY(t.toDouble()).toFloat())
        canvas.scale(g.scale.toFloat(), g.scale.toFloat())
        buildShapePath(shapePreset.make("", 0f, 0f, w, h))
        dragPaint.strokeWidth = 2f / g.scale.toFloat()
        canvas.drawPath(shapePath, dragPaint)
        dragPaint.strokeWidth = 2f
        canvas.restore()
    }

    /** The outline of [s] in its own frame (the corner at 0, 0) into [shapePath]. */
    private fun buildShapePath(s: Shape) {
        shapePath.reset()
        when (s.kind) {
            ShapeKind.RECT -> shapePath.addRoundRect(android.graphics.RectF(0f, 0f, s.width, s.height), s.corner, s.corner, android.graphics.Path.Direction.CW)
            ShapeKind.ELLIPSE -> shapePath.addOval(android.graphics.RectF(0f, 0f, s.width, s.height), android.graphics.Path.Direction.CW)
            ShapeKind.POLYGON -> {
                s.vertices.forEachIndexed { k, (fx, fy) -> if (k == 0) shapePath.moveTo(fx * s.width, fy * s.height) else shapePath.lineTo(fx * s.width, fy * s.height) }
                shapePath.close()
            }
        }
    }

    private fun shapeLayout(s: Shape): StaticLayout {
        val c = shapeLayouts[s.id]
        if (c != null && c.first === s) return c.second
        return TextRender.layout(TextBox("", 0f, 0f, maxOf(1f, s.width - 2 * SHAPE_PAD), s.fontSize, s.text, s.textColor), android.text.Layout.Alignment.ALIGN_CENTER)
            .also { shapeLayouts[s.id] = s to it }
    }

    /** Draws a shape (in page units; the canvas already has the page matrix): fill, outline, then its text. */
    private fun drawShape(canvas: Canvas, s0: Shape) {
        val s = shapePreview?.takeIf { it.id == s0.id } ?: s0
        val moved = dragMap?.takeIf { s.id in selection }
        canvas.save()
        if (moved != null) canvas.concat(Matrix().apply { setValues(floatArrayOf(moved.a.toFloat(), moved.c.toFloat(), moved.e.toFloat(), moved.b.toFloat(), moved.d.toFloat(), moved.f.toFloat(), 0f, 0f, 1f)) })
        canvas.translate(s.x, s.y)
        canvas.rotate(Math.toDegrees(s.angle.toDouble()).toFloat()) // clockwise on screen, about the corner
        buildShapePath(s)
        if (s.fill ushr 24 != 0) { shapeFillPaint.color = s.fill; canvas.drawPath(shapePath, shapeFillPaint) }
        val w = maxOf(s.lineWidth, (1 / geometry.scale).toFloat())
        shapeLinePaint.color = TextRender.ink(s.color)
        shapeLinePaint.strokeWidth = w
        shapeLinePaint.pathEffect = if (s.dashed) android.graphics.DashPathEffect(floatArrayOf(w * 4, w * 3), 0f) else null
        canvas.drawPath(shapePath, shapeLinePaint)
        if (s.text.isNotBlank() && s.id != editShape?.id) {
            val l = shapeLayout(s)
            canvas.translate(SHAPE_PAD, (s.height - l.height) / 2f)
            l.draw(canvas)
        }
        canvas.restore()
    }

    /** The box round a shape's corners on the page (a turned shape is boxed loosely). */
    private fun shapeRect(s: Shape): Box {
        val c = s.corners()
        return Box(c.minOf { it.first }.toDouble(), c.minOf { it.second }.toDouble(), c.maxOf { it.first }.toDouble(), c.maxOf { it.second }.toDouble())
    }

    /** Outline colour, fill, line width and dashing for the selected shapes (null leaves one as it is): one undo step. */
    fun setShapeStyle(color: Int?, fill: Int?, lineWidth: Float?, dashed: Boolean?) {
        val picked = model.shapes.filter { it.id in selection }
        val lines = model.connectors.filter { it.id in selection }
        if (picked.isEmpty() && lines.isEmpty()) return
        val a = model.setShapes(picked.map { it.copy(color = color ?: it.color, fill = fill ?: it.fill, lineWidth = lineWidth ?: it.lineWidth, dashed = dashed ?: it.dashed) })
        val b = model.setConnectors(lines.map { it.copy(color = color ?: it.color, lineWidth = lineWidth ?: it.lineWidth, dashed = dashed ?: it.dashed) })
        if (a || b) changed(true)
    }

    /** Types in a shape: an editor over its box, text centred. Ends like any text (see [finishText]). */
    private fun startShapeEdit(s: Shape) {
        val (x, y) = s.toPage(SHAPE_PAD, 0f)
        editShape = s
        startEdit(TextBox("", x, y, maxOf(1f, s.width - 2 * SHAPE_PAD), s.fontSize, s.text, s.textColor, 0, s.angle), false, s.height)
    }

    // ---- Connectors: drag from one place to another; an end that starts or lands on a shape sticks to it (near the
    // middle of a side it sticks to that side, elsewhere it aims at the other end) and follows it when it moves. ----

    /** The heads of the next connector. */
    var connectorFromHead = Arrowhead.NONE
    var connectorToHead = Arrowhead.FILLED

    private var connecting = false
    private var connectFrom = 0f to 0f
    private var connectTo = 0f to 0f
    private val connectPaint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val connectFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    /** How the next connector runs, and whether its ends stick to an exact spot on a shape (false) or aim at the other end (true). */
    var connectorRoute = Route.STRAIGHT
    var connectAuto = false

    /**
     * The end for a pen at the page point: on a shape, or just by one, it sticks to the nearest spot of the outline,
     * snapping to the middle of a side when that is within 5 mm; on nothing it is free.
     */
    /** A free end of a right-angle connector lines up with [other] (the point next to it) when within about 5 mm on screen. */
    private fun snapFree(e: End, route: Route, other: Pair<Float, Float>): End {
        if (e.shape != null || route != Route.ELBOW) return e
        val s = Routes.snapPoint(e.x to e.y, listOf(other), SNAP_PX / geometry.scale.toFloat())
        return End(null, 0, s.first, s.second)
    }

    /** Guide lines from a free end to [other] for the ways it lines up with it. */
    private fun freeGuides(e: End, other: Pair<Float, Float>): List<ShapeEdit.Guide> = when {
        e.shape != null -> emptyList()
        e.x == other.first && e.y == other.second -> emptyList()
        else -> listOfNotNull(
            if (e.x == other.first) guide(e.x to e.y, other) else null,
            if (e.y == other.second) guide(e.x to e.y, other) else null,
        )
    }

    private fun endAt(p: Pair<Float, Float>): End {
        val shape = model.order.asReversed().firstNotNullOfOrNull { model.shapeOf(it)?.takeIf { s -> s.near(p.first, p.second, CONNECT_REACH) } }
            ?: return End(null, 0, p.first, p.second)
        if (connectAuto) return End(shape.id, 0, p.first, p.second)
        var (fx, fy) = shape.nearestOutline(p.first, p.second)
        val (ox, oy) = shape.spot(fx, fy)
        shape.snapSpots().minByOrNull { (sx, sy) -> shape.spot(sx, sy).let { Math.hypot((it.first - ox).toDouble(), (it.second - oy).toDouble()) } }?.let { (sx, sy) ->
            val q = shape.spot(sx, sy)
            if (Math.hypot((q.first - ox).toDouble(), (q.second - oy).toDouble()) <= CONNECT_SNAP) { fx = sx; fy = sy }
        }
        val at = shape.spot(fx, fy)
        return End(shape.id, End.SPOT, at.first, at.second, fx, fy)
    }

    private fun connectGesture(event: MotionEvent): Boolean {
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) {
                connectTo = geometry.toPageX(event.getX(i).toDouble()).toFloat() to geometry.toPageY(event.getY(i).toDouble()).toFloat()
                pageLayer.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                val c = if (event.actionMasked == MotionEvent.ACTION_CANCEL) null else previewConnector("")
                connecting = false
                guides = emptyList()
                if (c != null) {
                    val (a, b) = c.resolve(model::shapeOf)
                    // Too short to be a line: a tap makes nothing.
                    if (Math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) >= SHAPE_MIN) {
                        model.addConnector { id -> c.copy(from = c.from.at(a), to = c.to.at(b)).let { n -> Connector(id, n.from, n.to, n.fromHead, n.toHead, n.color, n.lineWidth, n.dashed, n.route, n.bend) } }
                        changed(true)
                    }
                }
                pageLayer.invalidate()
            }
        }
        return true
    }

    /** The connector the drag so far would make (its ends not yet fixed up to where they land). */
    private fun previewConnector(id: String): Connector {
        val from = endAt(connectFrom)
        val to = endAt(connectTo)
        // Drawing a right-angle connector, a free end lines up with the start.
        val snapped = snapFree(to, connectorRoute, connectFrom)
        if (connecting) guides = freeGuides(snapped, connectFrom)
        return Connector(id, from, snapped, connectorFromHead, connectorToHead, penColor, penWidth.toFloat(), false, connectorRoute)
    }

    /** The shape as the lasso is moving it, so what is joined to it follows. */
    private fun shapeNow(id: String): Shape? {
        val s = model.shapeOf(id) ?: return null
        val m = dragMap
        return if (m != null && id in selection) s.moved(m) else s
    }

    private fun drawConnectorPreview(canvas: Canvas) {
        if (!connecting) return
        val g = geometry
        val c = previewConnector("")
        val e = c.ends(model::shapeOf)
        canvas.save()
        canvas.translate(g.tx.toFloat(), g.ty.toFloat())
        canvas.scale(g.scale.toFloat(), g.scale.toFloat())
        drawConnectorLine(canvas, c.path(model::shapeOf), c)
        // Ends that will stick to a shape get a ring.
        for ((end, p) in listOf(c.from to e.a, c.to to e.b)) if (end.shape != null) {
            dragPaint.strokeWidth = 2f / g.scale.toFloat()
            canvas.drawCircle(p.first, p.second, 8f / g.scale.toFloat(), dragPaint)
            dragPaint.strokeWidth = 2f
        }
        canvas.restore()
    }

    /** The connector being reshaped by a handle, as it would be now (saved when the pen lifts). */
    private var connPreview: Connector? = null
    private var connBase: Connector? = null
    private var connViaIndex = 0 // which bend point a drag has hold of
    private var lastVia = 0 // the bend point last touched or added, which "remove" takes away

    /** Draws a connector in page units (the canvas has the page matrix); the lasso's moves and handle drags show as they go. */
    private fun drawConnector(canvas: Canvas, c0: Connector) {
        val c = connPreview?.takeIf { it.id == c0.id } ?: c0
        val m = dragMap?.takeIf { c.id in selection }
        fun e(x: End) = if (m != null && x.shape == null) End(null, 0, m.x(x.x, x.y), m.y(x.x, x.y)) else x
        val live = if (m != null) c.copy(from = e(c.from), to = e(c.to)) else c
        drawConnectorLine(canvas, live.path(::shapeNow), live)
    }

    private fun drawConnectorLine(canvas: Canvas, path: List<Pair<Float, Float>>, c: Connector) {
        if (path.size < 2) return
        val color = TextRender.ink(c.color)
        val w = maxOf(c.lineWidth, (1 / geometry.scale).toFloat())
        val size = Arrow.size(c.lineWidth)
        connectPaint.color = color; connectPaint.strokeWidth = w
        connectPaint.pathEffect = if (c.dashed) android.graphics.DashPathEffect(floatArrayOf(w * 4, w * 3), 0f) else null
        connectFillPaint.color = color
        val a = path.first(); val afterA = path[1]
        val b = path.last(); val beforeB = path[path.size - 2]
        // A solid head covers the end of the line, so the line stops at its base.
        fun base(tip: Pair<Float, Float>, other: Pair<Float, Float>, head: Arrowhead): Pair<Float, Float> {
            if (head != Arrowhead.FILLED) return tip
            val (l, r) = Arrow.wings(tip, other, size)
            return (l.first + r.first) / 2 to (l.second + r.second) / 2
        }
        val line = android.graphics.Path()
        val start = base(a, afterA, c.fromHead)
        line.moveTo(start.first, start.second)
        for (k in 1 until path.size - 1) line.lineTo(path[k].first, path[k].second)
        val end = base(b, beforeB, c.toHead)
        line.lineTo(end.first, end.second)
        canvas.drawPath(line, connectPaint)
        connectPaint.pathEffect = null
        for ((tip, other, head) in listOf(Triple(a, afterA, c.fromHead), Triple(b, beforeB, c.toHead))) when (head) {
            Arrowhead.NONE -> {}
            Arrowhead.OPEN -> {
                val (l, r) = Arrow.wings(tip, other, size)
                canvas.drawLine(tip.first, tip.second, l.first, l.second, connectPaint)
                canvas.drawLine(tip.first, tip.second, r.first, r.second, connectPaint)
            }
            Arrowhead.FILLED -> {
                val (l, r) = Arrow.wings(tip, other, size)
                val p = android.graphics.Path().apply { moveTo(tip.first, tip.second); lineTo(l.first, l.second); lineTo(r.first, r.second); close() }
                canvas.drawPath(p, connectFillPaint)
            }
            Arrowhead.DOT -> canvas.drawCircle(tip.first, tip.second, size * 0.3f, connectFillPaint)
        }
    }

    /** The connector that is selected on its own (as it would be now): it gets handles on its ends and middle instead of a frame. */
    private fun connectorAlone(): Connector? {
        if (selection.size != 1) return null
        val c = model.connectorOf(selection.first()) ?: return null
        return connPreview?.takeIf { it.id == c.id } ?: c
    }

    /** Is the next tap on the connector (selected on its own) going to add a bend point there? */
    private var addingBend = false

    /** Asks for a bend point: tap the line of the selected connector where it should go. A tap well away from it cancels. */
    fun addBendPoint() {
        addingBend = connectorAlone() != null
        pageLayer.invalidate()
    }

    private fun addBendAt(x: Float, y: Float) {
        val c = connectorAlone()
        addingBend = false
        pageLayer.invalidate()
        if (c == null) return
        if (LassoGeometry.distToPath(x, y, c.path(model::shapeOf)) * geometry.scale > BEND_REACH_PX) return
        val (n, k) = c.withPointAt(model::shapeOf, x to y)
        if (model.setConnector(n)) { lastVia = k; changed(true) }
    }

    private fun drawBendHint(canvas: Canvas) {
        if (!addingBend) return
        val text = "Tap the line where the bend point goes"
        val w = hintText.measureText(text) + 56f
        @Suppress("DEPRECATION") val top = rootWindowInsets?.systemWindowInsetTop ?: 0
        val cy = top + 200f
        canvas.drawRoundRect(width / 2f - w / 2, cy - 40f, width / 2f + w / 2, cy + 40f, 40f, 40f, hintBg)
        canvas.drawText(text, width / 2f, cy + 14f, hintText)
    }

    /** Takes away the bend point last touched or added. */
    fun removeBendPoint() {
        val c = connectorAlone()?.takeIf { it.via.isNotEmpty() } ?: return
        val i = lastVia.coerceIn(0, c.via.lastIndex)
        if (model.setConnector(c.withoutPoint(i))) { lastVia = maxOf(0, i - 1); changed(true) }
    }

    /** The route for the next connector and the selected ones (a changed route starts with no bend). */
    fun routeSelected(route: Route) {
        val picked = model.connectors.filter { it.id in selection }
        if (picked.isEmpty()) return
        if (model.setConnectors(picked.map { it.copy(route = route, bend = 0f) })) changed(true)
    }

    /** Heads for the selected connectors (null leaves one as it is): one undo step. */
    fun setConnectorHeads(from: Arrowhead?, to: Arrowhead?) {
        val picked = model.connectors.filter { it.id in selection }
        if (picked.isEmpty()) return
        if (model.setConnectors(picked.map { it.copy(fromHead = from ?: it.fromHead, toHead = to ?: it.toHead) })) changed(true)
    }

    // ---- Paint bucket: tap to colour the innermost thing there: a text box (its highlight), a shape, a table cell, a
    // fill made before, or the area closed in by pen strokes. ----

    /** The colour poured; zero alpha empties what is tapped. */
    var bucketColor = 0x99FFEB3B.toInt()

    /** Gaps up to this big (page units, 1 mm) in the lines round an area do not let the paint out. */
    var bucketGap = 100f

    private var bucketing = false
    private var bucketX = 0f
    private var bucketY = 0f
    private val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val fillPaths = HashMap<String, Pair<Fill, android.graphics.Path>>()

    private fun bucketGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                bucketing = false
                if (event.actionMasked != MotionEvent.ACTION_CANCEL) { if (pendingClip != null) placeAt(bucketX, bucketY) else bucketTap(bucketX, bucketY) }
            }
        }
        return true
    }

    /** One thing a tap could colour: how big it is round the tap (the smallest wins), and doing it. */
    private class Pour(val area: Double, val apply: () -> Boolean)

    private fun bucketTap(px: Float, py: Float) {
        val color = bucketColor
        val empty = color ushr 24 == 0
        val options = ArrayList<Pour>()
        model.texts.lastOrNull { it.contains(px, py, layoutOf(it).height.toFloat()) }?.let { t ->
            options.add(Pour(t.width.toDouble() * layoutOf(t).height) { model.setText(t.copy(highlight = color)) })
        }
        for (s in model.shapes) if (s.contains(px, py)) options.add(Pour(shapeArea(s)) { model.setShape(s.copy(fill = color)) })
        model.tables.lastOrNull { it.contains(px, py) }?.let { t ->
            t.cellAt(px, py)?.let { (r, c) -> options.add(Pour(t.colWidths[c].toDouble() * t.rowHeights[r]) { model.setTable(t.withCellFill(r, c, color)) }) }
        }
        // A fill poured before is recoloured rather than covered; counted a little smaller so it wins over the area it fills.
        for (f in model.fills) if (f.contains(px, py)) {
            val area = Math.abs(f.rings.sumOf { RegionFill.areaOf(it) })
            options.add(Pour(area * 0.8) { if (empty) model.delete(setOf(f.id)) else model.setFills(listOf(f.copy(color = color))) })
        }
        if (!empty) {
            val pens = model.items.filter { it.tool == StrokeTool.TOOL_PEN }
            val bounds = if (!geometry.infinite) Box(0.0, 0.0, geometry.pageW.toDouble(), geometry.pageH.toDouble()) else {
                val l = minOf(px.toDouble(), pens.minOfOrNull { i -> i.pts.minOf { it.x }.toDouble() } ?: px.toDouble()) - 1000
                val t = minOf(py.toDouble(), pens.minOfOrNull { i -> i.pts.minOf { it.y }.toDouble() } ?: py.toDouble()) - 1000
                val r = maxOf(px.toDouble(), pens.maxOfOrNull { i -> i.pts.maxOf { it.x }.toDouble() } ?: px.toDouble()) + 1000
                val b = maxOf(py.toDouble(), pens.maxOfOrNull { i -> i.pts.maxOf { it.y }.toDouble() } ?: py.toDouble()) + 1000
                Box(l, t, r, b)
            }
            RegionFill.find(pens.map { i -> RegionFill.Path(i.pts.map { it.x to it.y }, i.width.toFloat()) }, px, py, bounds, bucketGap)?.let { region ->
                options.add(Pour(region.area) { model.addFill(color, region.rings) != null })
            }
        }
        val best = options.minByOrNull { it.area } ?: return
        if (best.apply()) changed(true)
    }

    private fun shapeArea(s: Shape): Double = when (s.kind) {
        ShapeKind.RECT -> s.width.toDouble() * s.height
        ShapeKind.ELLIPSE -> Math.PI / 4 * s.width * s.height
        ShapeKind.POLYGON -> Math.abs(RegionFill.areaOf(s.vertices.map { it.first * s.width to it.second * s.height }))
    }

    /** Draws a fill (page units, the canvas has the page matrix): its rings, even-odd, so holes stay empty. */
    private fun drawFill(canvas: Canvas, f: Fill) {
        val moved = dragMap?.takeIf { f.id in selection }
        val path = fillPaths[f.id]?.takeIf { it.first === f }?.second ?: android.graphics.Path().also { p ->
            p.fillType = android.graphics.Path.FillType.EVEN_ODD
            for (ring in f.rings) {
                ring.forEachIndexed { k, (x, y) -> if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                p.close()
            }
            fillPaths[f.id] = f to p
        }
        canvas.save()
        if (moved != null) canvas.concat(Matrix().apply { setValues(floatArrayOf(moved.a.toFloat(), moved.c.toFloat(), moved.e.toFloat(), moved.b.toFloat(), moved.d.toFloat(), moved.f.toFloat(), 0f, 0f, 1f)) })
        fillPaint.color = f.color
        canvas.drawPath(path, fillPaint)
        canvas.restore()
    }

    // ---- Tables: choose rows and columns, then drag out the grid like a text box. ----

    /** How many rows and columns the next table has (the dialog sets them; 1 to [Table.MAX_LINES]). */
    var tableRows = 3
        set(v) { field = v.coerceIn(1, Table.MAX_LINES) }
    var tableCols = 3
        set(v) { field = v.coerceIn(1, Table.MAX_LINES) }

    private var tabling = false
    private var tableFromX = 0f
    private var tableFromY = 0f
    private var tableToX = 0f
    private var tableToY = 0f
    private val tablePaint = Paint().apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER; isAntiAlias = true }
    private val tableFill = Paint().apply { style = Paint.Style.FILL }
    private val cellPaint = Paint().apply { style = Paint.Style.FILL; color = 0x331565C0 }

    /** A point kept on a fixed page (on an infinite canvas it is left alone). */
    private fun onPage(x: Float, y: Float): Pair<Float, Float> =
        if (geometry.infinite) x to y else x.coerceIn(0f, geometry.pageW.toFloat()) to y.coerceIn(0f, geometry.pageH.toFloat())

    private fun tableGesture(event: MotionEvent): Boolean {
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) {
                val (x, y) = onPage(geometry.toPageX(event.getX(i).toDouble()).toFloat(), geometry.toPageY(event.getY(i).toDouble()).toFloat())
                tableToX = x; tableToY = y
                pageLayer.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                tabling = false
                if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    // Too small a drag for that many cells makes nothing; the preview was red.
                    model.addTable(
                        minOf(tableFromX, tableToX), minOf(tableFromY, tableToY),
                        Math.abs(tableToX - tableFromX), Math.abs(tableToY - tableFromY), tableRows, tableCols, penWidth.toFloat(), penColor,
                    )
                    changed(true)
                }
                pageLayer.invalidate()
            }
        }
        return true
    }

    /** The grid being dragged out: dashed, with its cell lines, red if the cells would be too small. */
    private fun drawTableDragPreview(canvas: Canvas) {
        if (!tabling) return
        val g = geometry
        val l = minOf(tableFromX, tableToX); val r = maxOf(tableFromX, tableToX)
        val t = minOf(tableFromY, tableToY); val b = maxOf(tableFromY, tableToY)
        val ok = Table.fits(r - l, b - t, tableRows, tableCols)
        dragPaint.color = if (ok) 0xFF6750A4.toInt() else 0xFFC62828.toInt()
        val sl = g.toScreenX(l.toDouble()).toFloat(); val sr = g.toScreenX(r.toDouble()).toFloat()
        val st = g.toScreenY(t.toDouble()).toFloat(); val sb = g.toScreenY(b.toDouble()).toFloat()
        canvas.drawRect(sl, st, sr, sb, dragPaint)
        for (c in 1 until tableCols) { val x = sl + (sr - sl) * c / tableCols; canvas.drawLine(x, st, x, sb, dragPaint) }
        for (rw in 1 until tableRows) { val y = st + (sb - st) * rw / tableRows; canvas.drawLine(sl, y, sr, y, dragPaint) }
        dragPaint.color = 0xFF6750A4.toInt()
    }

    /** Draws a table's grid in page units (the canvas already has the page matrix), a line never thinner than a pixel. */
    private fun drawTable(canvas: Canvas, table: Table, vl: Float, vt: Float, vr: Float, vb: Float) {
        val moved = dragMap?.takeIf { table.id in selection }
        if (moved == null && (table.x > vr || table.x + table.width < vl || table.y > vb || table.y + table.height < vt)) return
        canvas.save()
        if (moved != null) canvas.concat(Matrix().apply { setValues(floatArrayOf(moved.a.toFloat(), moved.c.toFloat(), moved.e.toFloat(), moved.b.toFloat(), moved.d.toFloat(), moved.f.toFloat(), 0f, 0f, 1f)) })
        if (table.fill ushr 24 != 0) {
            tableFill.color = table.fill
            canvas.drawRect(table.x, table.y, table.x + table.width, table.y + table.height, tableFill)
        }
        if (table.cellFills.isNotEmpty()) {
            for (r in 0 until table.rows) for (c in 0 until table.cols) {
                val col = table.cellFill(r, c)
                if (col ushr 24 == 0) continue
                tableFill.color = col
                canvas.drawRect(table.lineX(c), table.lineY(r), table.lineX(c + 1), table.lineY(r + 1), tableFill)
            }
        }
        tablePaint.color = TextRender.ink(table.color)
        tablePaint.strokeWidth = maxOf(table.lineWidth, (1 / geometry.scale).toFloat())
        canvas.drawRect(table.x, table.y, table.x + table.width, table.y + table.height, tablePaint)
        for (c in 1 until table.cols) canvas.drawLine(table.lineX(c), table.y, table.lineX(c), table.y + table.height, tablePaint)
        for (r in 1 until table.rows) canvas.drawLine(table.x, table.lineY(r), table.x + table.width, table.lineY(r), tablePaint)
        canvas.restore()
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
    private var shapeHit: Shape? = null // or the shape, if no box
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
        shapeHit = model.order.asReversed().firstNotNullOfOrNull { model.shapeOf(it)?.takeIf { s -> s.contains(px, py) } }
        if (shapeHit != null) return // a tap in a shape types in it
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
                val shape = shapeHit
                shapeHit = null
                if (!cancelled && hit != null) startEdit(hit, false)
                else if (!cancelled && shape != null) startShapeEdit(shape)
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

    private fun startEdit(box: TextBox, isNew: Boolean, fixedHeight: Float? = null) {
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
            gravity = if (fixedHeight != null) Gravity.CENTER else Gravity.TOP or Gravity.START // a shape's text is centred
            pivotX = 0f
            pivotY = 0f
            layoutParams = LayoutParams((box.width * s).toInt().coerceAtLeast(1), if (fixedHeight != null) (fixedHeight * s).toInt().coerceAtLeast(1) else LayoutParams.WRAP_CONTENT)
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
        if (editor == null && model.setShapes(model.shapes.filter { it.id in selection }.map { it.copy(textColor = color) })) changed(true) // and the text in selected shapes
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
        val shape = editShape
        editShape = null
        if (shape != null) { // typing in a shape: blank clears its text
            model.shapeOf(shape.id)?.let { if (it.text != text || it.textColor != box.color) model.setShape(it.copy(text = text, textColor = box.color)) }
            changed(true)
            pageLayer.invalidate()
            return
        }
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
    private enum class Drag { LOOP, MOVE, RESIZE, ROTATE, LINE, CONN_FROM, CONN_TO, CONN_BEND, CONN_VIA, ADD, SHAPE_VERTEX, SHAPE_CORNER }
    private var drag = Drag.LOOP
    private val loop = ArrayList<Float>() // page units: x0, y0, x1, y1, ...
    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private var dragCorner = 0
    private var dragBox: Quad? = null // the selection's frame when the drag began
    private var dragMap: Affine? = null // the move or resize so far; the strokes are only changed when the pen lifts
    private var lineBase: Table? = null // the table whose line is being dragged, as it was when the drag began
    private var linePreview: Table? = null // and as it would be now (it is only saved when the pen lifts)
    private var lineVertical = false
    private var lineIndex = 0
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
    private val geoHandle = Paint().apply { color = 0xFFEF6C00.toInt(); isAntiAlias = true } // geometry handles are orange, frame handles blue

    fun deleteSelection() {
        if (model.delete(selection)) {
            selection.clear()
            changed(true)
        }
    }

    /** The clips saved so far (in memory only for now). */
    val shelf = ClipShelf()

    /** Saves the selection as a clip; false if nothing is selected. */
    fun saveSelection(): Clip? {
        val c = Clip.of("", model, selection, ::boxOf) ?: return null
        return shelf.add(c)
    }

    /** The clip waiting for a tap to say where it goes (see [placeClip]); [onPlacing] tells the screen. */
    var pendingClip: Clip? = null
        private set
    var onPlacing: ((Clip?) -> Unit)? = null

    /** Gets ready to put [clip] down: the next pen tap, on any page, is where its middle goes. */
    fun placeClip(clip: Clip?) {
        tool = Tool.LASSO // a selection only lives while the lasso is the tool; this also cancels an earlier wait
        pendingClip = clip
        onPlacing?.invoke(clip)
    }

    /** Puts the waiting clip on the working page with its middle at the tapped point (kept on the sheet), selected so it can be moved at once. One undo step. */
    private fun placeAt(px: Float, py: Float) {
        val clip = pendingClip ?: return
        placeClip(null)
        val g = geometry
        var x = px - clip.width / 2
        var y = py - clip.height / 2
        if (!g.infinite) {
            x = x.coerceIn(0f, maxOf(0f, g.pageW - clip.width))
            y = y.coerceIn(0f, maxOf(0f, g.pageH - clip.height))
        }
        val ids = model.paste(clip, x, y)
        selection.addAll(ids)
        changed(ids.isNotEmpty())
    }

    /** Moves the selected things in the layers (front, back, or one step). */
    fun arrange(how: Arrange) {
        if (model.arrange(selection, how, ::boxOf)) changed(true)
    }

    /** The box round a stroke, text box or table on the page, for telling what overlaps. */
    private fun boxOf(id: String): Box? {
        model.itemOf(id)?.let { i ->
            val h = i.width / 2.0
            return Box(i.pts.minOf { it.x } - h, i.pts.minOf { it.y } - h, i.pts.maxOf { it.x } + h, i.pts.maxOf { it.y } + h)
        }
        model.textOf(id)?.let { return textRect(it) }
        model.tableOf(id)?.let { return Box(it.x.toDouble(), it.y.toDouble(), (it.x + it.width).toDouble(), (it.y + it.height).toDouble()) }
        model.shapeOf(id)?.let { return shapeRect(it) }
        model.fillOf(id)?.let { return it.box() }
        model.connectorOf(id)?.let { c ->
            val p = c.path(model::shapeOf)
            return Box(p.minOf { it.first }.toDouble(), p.minOf { it.second }.toDouble(), p.maxOf { it.first }.toDouble(), p.maxOf { it.second }.toDouble())
        }
        return null
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
        val tables = model.tables.filter { it.id in selection }.map { linePreview?.takeIf { p -> p.id == it.id } ?: it }
        val shapes = model.shapes.filter { it.id in selection }
        val fills = model.fills.filter { it.id in selection }
        val conns = model.connectors.filter { it.id in selection }
        val angles = boxes.map { it.angle } + shapes.map { it.angle }
        val base = if (strokes.isEmpty() && tables.isEmpty() && fills.isEmpty() && conns.isEmpty() && angles.isNotEmpty() && angles.all { LassoGeometry.sameTurn(it, angles[0]) }) {
            LassoGeometry.orientedQuad(boxes.flatMap { it.corners(layoutOf(it).height.toFloat()) } + shapes.flatMap { it.corners() }, angles[0])
        } else {
            Quad.of(LassoGeometry.bounds(strokes, boxes.map(::textRect) + shapes.map(::shapeRect) + fills.mapNotNull { it.box() } + conns.mapNotNull { boxOf(it.id) } + tables.map { Box(it.x.toDouble(), it.y.toDouble(), (it.x + it.width).toDouble(), (it.y + it.height).toDouble()) }) ?: return null)
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
        connectorAlone()?.let { c ->
            val e = c.ends(::shapeNow)
            val path = c.path(::shapeNow)
            fun sx(p: Pair<Float, Float>) = g.toScreenX(p.first.toDouble()).toFloat()
            fun sy(p: Pair<Float, Float>) = g.toScreenY(p.second.toDouble()).toFloat()
            for (p in listOf(e.a, e.b)) canvas.drawCircle(sx(p), sy(p), 12f, handle) // ends: drag to move or re-attach
            // Bend points (or, with none, the middle) are squares: drag to bend.
            for (m in if (c.via.isEmpty()) listOf(Routes.middle(path)) else c.via) canvas.drawRect(sx(m) - 10f, sy(m) - 10f, sx(m) + 10f, sy(m) + 10f, handle)
            return
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
        if (canRotate()) { // a table has no angle, so no turning while one is selected
            val (rx, ry) = q.handle(ROTATE_STEM_PX / g.scale)
            val (tx, ty) = q.topMiddle
            canvas.drawLine(g.toScreenX(tx).toFloat(), g.toScreenY(ty).toFloat(), g.toScreenX(rx).toFloat(), g.toScreenY(ry).toFloat(), dashed)
            canvas.drawCircle(g.toScreenX(rx).toFloat(), g.toScreenY(ry).toFloat(), 12f, handle)
        }
        for (c in 0..3) {
            val (hx, hy) = q.corner(c)
            canvas.drawCircle(g.toScreenX(hx).toFloat(), g.toScreenY(hy).toFloat(), 9f, handle)
        }
        // A shape on its own: diamonds on a polygon's corners, a ring for a rectangle's corner radius.
        shapeAlone()?.takeIf { dragMap == null }?.let { s ->
            for ((d, _, p) in geometryHandles(s)) {
                val x = g.toScreenX(p.first.toDouble()).toFloat(); val y = g.toScreenY(p.second.toDouble()).toFloat()
                if (d == Drag.SHAPE_CORNER) canvas.drawCircle(x, y, 11f, geoHandle)
                else canvas.save().also { canvas.rotate(45f, x, y); canvas.drawRect(x - 8f, y - 8f, x + 8f, y + 8f, geoHandle); canvas.restore() }
            }
        }
        // The cell that was tapped (for the row and column buttons).
        val tbl = lineTable()
        val cell = cellSel
        // While the whole table is being moved or resized, what is drawn for it follows the drag, like the frame does.
        fun sx(x: Float, y: Float) = g.toScreenX((dragMap?.x(x, y) ?: x).toDouble()).toFloat()
        fun sy(x: Float, y: Float) = g.toScreenY((dragMap?.y(x, y) ?: y).toDouble()).toFloat()
        if (tbl != null && cell != null && cell.first < tbl.rows && cell.second < tbl.cols) {
            val l = tbl.lineX(cell.second); val t = tbl.lineY(cell.first)
            val r = tbl.lineX(cell.second + 1); val b = tbl.lineY(cell.first + 1)
            canvas.drawRect(sx(l, t), sy(l, t), sx(r, b), sy(r, b), cellPaint)
        }
        // A table on its own also has a square handle on each of its lines.
        lineTable()?.let { t -> t.lineHandles(anchor = lineBase ?: t) }?.forEach {
            val x = sx(it.x, it.y)
            val y = sy(it.x, it.y)
            canvas.drawRect(x - 9f, y - 9f, x + 9f, y + 9f, handle)
        }
    }

    /** Did the loop that was just drawn stay within a few pixels (a tap)? */
    private fun loopIsTap(): Boolean {
        if (loop.size < 2) return false
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in 0 until loop.size / 2) { l = minOf(l, loop[2 * k]); r = maxOf(r, loop[2 * k]); t = minOf(t, loop[2 * k + 1]); b = maxOf(b, loop[2 * k + 1]) }
        return maxOf(r - l, b - t) * geometry.scale < TAP_PX
    }

    /** The cell last tapped on a table that is selected on its own: (row, column). */
    private var cellSel: Pair<Int, Int>? = null

    /** Adds a row above or below the tapped cell (the top or the bottom of the table if none is tapped). */
    fun insertRow(below: Boolean) {
        val t = lineTable() ?: return
        val r = cellSel?.first
        val at = if (r == null) (if (below) t.rows else 0) else if (below) r + 1 else r
        if (model.insertLine(t.id, false, at)) { cellSel = at to (cellSel?.second ?: 0); changed(true) }
    }

    /** Adds a column left or right of the tapped cell (the left or right of the table if none is tapped). */
    fun insertColumn(right: Boolean) {
        val t = lineTable() ?: return
        val c = cellSel?.second
        val at = if (c == null) (if (right) t.cols else 0) else if (right) c + 1 else c
        if (model.insertLine(t.id, true, at)) { cellSel = (cellSel?.first ?: 0) to at; changed(true) }
    }

    /** Deletes the tapped cell's row, with what is written in it. */
    fun deleteRow() {
        val t = lineTable() ?: return
        val (r, c) = cellSel ?: return
        if (model.deleteLine(t.id, false, r)) { cellSel = minOf(r, t.rows - 2) to c; changed(true) }
    }

    /** Deletes the tapped cell's column, with what is written in it. */
    fun deleteColumn() {
        val t = lineTable() ?: return
        val (r, c) = cellSel ?: return
        if (model.deleteLine(t.id, true, c)) { cellSel = r to minOf(c, t.cols - 2); changed(true) }
    }

    /** Colours or thickness for the selected tables (null leaves one as it is): one undo step. */
    fun setTableStyle(color: Int?, fill: Int?, lineWidth: Float?) {
        val picked = model.tables.filter { it.id in selection }
        if (picked.isEmpty()) return
        if (model.setTables(picked.map { it.copy(color = color ?: it.color, fill = fill ?: it.fill, lineWidth = lineWidth ?: it.lineWidth) })) changed(true)
    }

    /** A turn needs strokes and text boxes only. */
    private fun canRotate() = model.tables.none { it.id in selection }

    /** The table that is selected on its own (as it would be now), whose lines can be dragged; null otherwise. */
    private fun lineTable(): Table? {
        if (model.items.any { it.id in selection } || model.texts.any { it.id in selection } || model.shapes.any { it.id in selection } || model.fills.any { it.id in selection } || model.connectors.any { it.id in selection }) return null
        val t = model.tables.filter { it.id in selection }.singleOrNull() ?: return null
        return linePreview?.takeIf { it.id == t.id } ?: t
    }

    private fun startLasso(event: MotionEvent, i: Int) {
        val g = geometry
        val sx = event.getX(i)
        val sy = event.getY(i)
        val px = g.toPageX(sx.toDouble())
        val py = g.toPageY(sy.toDouble())
        val box = selectionQuad()
        dragMap = null
        linePreview = null
        connPreview = null
        connBase = null
        shapePreview = null
        shapeBase = null
        guides = emptyList()
        val conn = connectorAlone()
        if (conn == null) addingBend = false
        if (conn != null && addingBend) { drag = Drag.ADD; dragStartX = px; dragStartY = py; return } // the tap says where
        if (conn != null) { // a connector on its own: handles on its ends and middle, or drag the line itself
            val e = conn.ends(model::shapeOf)
            val path = conn.path(model::shapeOf)
            // The ends, then each bend point; a connector with none has one handle in the middle to bend it.
            val handles = listOf(Triple(Drag.CONN_FROM, e.a, 0), Triple(Drag.CONN_TO, e.b, 0)) +
                (if (conn.via.isEmpty()) listOf(Triple(Drag.CONN_BEND, Routes.middle(path), 0)) else conn.via.mapIndexed { k, p -> Triple(Drag.CONN_VIA, p, k) })
            val hit = handles.map { (d, p, k) -> listOf<Any>(d, k, kotlin.math.hypot(g.toScreenX(p.first.toDouble()) - sx, g.toScreenY(p.second.toDouble()) - sy)) }
                .filter { (it[2] as Double) < HANDLE_PX }.minByOrNull { it[2] as Double }
            if (hit != null) {
                drag = hit[0] as Drag; connViaIndex = hit[1] as Int; connBase = conn; connPreview = conn
                if (drag == Drag.CONN_VIA) lastVia = connViaIndex
                return
            }
            if (LassoGeometry.distToPath(px.toFloat(), py.toFloat(), path) * g.scale < HANDLE_PX) { drag = Drag.MOVE; dragStartX = px; dragStartY = py; return }
        } else if (box != null) {
            // A shape on its own has handles on its corners (a polygon) or its corner radius (a rectangle). One that is
            // nearer than every frame corner wins (a tie too), so a polygon's corner on the frame's corner can still be had.
            val shape = shapeAlone()
            if (shape != null) {
                val near = geometryHandles(shape)
                    .map { (d, k, p) -> Triple(d, k, kotlin.math.hypot(g.toScreenX(p.first.toDouble()) - sx, g.toScreenY(p.second.toDouble()) - sy)) }
                    .filter { it.third < HANDLE_PX }.minByOrNull { it.third }
                val frame = (0..3).minOf { c -> box.corner(c).let { kotlin.math.hypot(g.toScreenX(it.first) - sx, g.toScreenY(it.second) - sy) } }
                if (near != null && near.third <= frame) { drag = near.first; geoIndex = near.second; shapeBase = shape; shapePreview = shape; return }
            }
            // A line's handle wins over a corner's only if it is nearer, so neither can hide the other.
            val table = lineTable()
            val lineHit = table?.lineHandles()
                ?.map { it to kotlin.math.hypot(g.toScreenX(it.x.toDouble()) - sx, g.toScreenY(it.y.toDouble()) - sy) }
                ?.filter { it.second < HANDLE_PX }?.minByOrNull { it.second }
            val cornerDist = (0..3).minOf { c -> box.corner(c).let { kotlin.math.hypot(g.toScreenX(it.first) - sx, g.toScreenY(it.second) - sy) } }
            if (table != null && lineHit != null && lineHit.second < cornerDist) {
                drag = Drag.LINE; lineBase = table; lineVertical = lineHit.first.vertical; lineIndex = lineHit.first.index
                dragStartX = px; dragStartY = py
                return
            }
            val (rx, ry) = box.handle(ROTATE_STEM_PX / g.scale)
            if (canRotate() && kotlin.math.hypot(g.toScreenX(rx) - sx, g.toScreenY(ry) - sy) < HANDLE_PX) {
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
        cellSel = null
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
                        Drag.LINE -> linePreview = lineBase!!.moveLine(lineVertical, lineIndex, (if (lineVertical) px - dragStartX else py - dragStartY).toFloat())
                        Drag.CONN_FROM -> connPreview = connBase!!.let { c ->
                            val other = c.via.firstOrNull() ?: c.ends(model::shapeOf).b
                            c.copy(from = snapFree(endAt(px.toFloat() to py.toFloat()), c.route, other)).also { guides = freeGuides(it.from, other) }
                        }
                        Drag.CONN_TO -> connPreview = connBase!!.let { c ->
                            val other = c.via.lastOrNull() ?: c.ends(model::shapeOf).a
                            c.copy(to = snapFree(endAt(px.toFloat() to py.toFloat()), c.route, other)).also { guides = freeGuides(it.to, other) }
                        }
                        Drag.ADD -> {}
                        Drag.SHAPE_VERTEX -> shapeBase?.let { b ->
                            val e = ShapeEdit.moveVertex(b, geoIndex, px.toFloat(), py.toFloat(), SNAP_PX / geometry.scale.toFloat())
                            shapePreview = e.shape; guides = e.guides
                        }
                        Drag.SHAPE_CORNER -> shapeBase?.let { b ->
                            shapePreview = ShapeEdit.setCorner(b, px.toFloat(), py.toFloat(), CORNER_INSET_PX / geometry.scale.toFloat(), SNAP_PX / geometry.scale.toFloat())
                        }
                        Drag.CONN_VIA -> connPreview = connBase!!.let { c ->
                            var at = px.toFloat() to py.toFloat()
                            if (c.route == Route.ELBOW) { // right angles line up with the points on either side
                                val e = c.ends(model::shapeOf)
                                val chain = listOf(e.a) + c.via + listOf(e.b) // only the two points either side of this one count
                                val sn = Routes.snapPointWith(at, listOf(chain[connViaIndex], chain[connViaIndex + 2]), SNAP_PX / geometry.scale.toFloat())
                                at = sn.p
                                guides = listOfNotNull(sn.xFrom?.let { guide(at, it) }, sn.yFrom?.let { guide(at, it) })
                            }
                            c.copy(via = c.via.toMutableList().also { it[connViaIndex] = at })
                        }
                        Drag.CONN_BEND -> {
                            val c = connBase!!
                            val e = c.ends(model::shapeOf)
                            val route = if (c.route == Route.STRAIGHT) Route.CURVE else c.route // pulling a straight line bends it into a curve
                            val bend = Routes.bendFor(route, e.a, e.da, e.b, e.db, px.toFloat() to py.toFloat())
                            val (snapped, at) = Routes.snappedBend(route, e.a, e.da, e.b, e.db, bend, SNAP_PX / geometry.scale.toFloat())
                            connPreview = c.copy(route = route, bend = snapped)
                            // The line it lined up on, along the span between the ends.
                            guides = if (at == null) emptyList() else {
                                val vertical = Routes.middle(Routes.path(route, e.a, e.da, e.b, e.db, snapped)).let { m -> Math.abs(m.first - at) < Math.abs(m.second - at) }
                                listOf(if (vertical) guide(at to e.a.second, at to e.b.second) else guide(e.a.first to at, e.b.first to at))
                            }
                        }
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
                    Drag.LOOP -> if (!cancelled && loopIsTap()) {
                        // A tap on a table's line picks the table alone (a loop round it takes the ink in it too).
                        val tol = (TAP_PX * 2 / geometry.scale).toFloat()
                        val table = model.tables.lastOrNull { it.nearLine(loop[0], loop[1], tol) }
                        if (table != null) selection.add(table.id)
                        else model.order.asReversed().firstOrNull { // a tap in a shape or a fill picks it, the topmost
                            model.shapeOf(it)?.contains(loop[0], loop[1]) == true || model.fillOf(it)?.contains(loop[0], loop[1]) == true ||
                                model.connectorOf(it)?.let { c -> LassoGeometry.distToPath(loop[0], loop[1], c.path(model::shapeOf)) <= tol } == true
                        }?.let { selection.add(it) }
                    } else if (!cancelled) {
                        selection.addAll(LassoGeometry.selected(model.items, loop.toFloatArray()))
                        selection.addAll(LassoGeometry.selectedBoxes(model.texts.map { it to layoutOf(it).height.toFloat() }, loop.toFloatArray()))
                        selection.addAll(LassoGeometry.selectedTables(model.tables, loop.toFloatArray()))
                        selection.addAll(LassoGeometry.selectedShapes(model.shapes, loop.toFloatArray()))
                        selection.addAll(LassoGeometry.selectedFills(model.fills, loop.toFloatArray()))
                        selection.addAll(LassoGeometry.selectedLines(model.connectors.map { c -> c.id to c.path(model::shapeOf) }, loop.toFloatArray()))
                    }
                    Drag.LINE -> if (!cancelled) linePreview?.takeIf { it !== lineBase }?.let { model.setTable(it) }
                    Drag.SHAPE_VERTEX, Drag.SHAPE_CORNER -> if (!cancelled) shapePreview?.takeIf { it !== shapeBase }?.let { model.setShape(it) }
                    Drag.ADD -> if (!cancelled) addBendAt(dragStartX.toFloat(), dragStartY.toFloat()) else addingBend = false
                    Drag.CONN_FROM, Drag.CONN_TO, Drag.CONN_BEND, Drag.CONN_VIA -> if (!cancelled) connPreview?.takeIf { it !== connBase }?.let { c ->
                        val (a, b) = c.resolve(model::shapeOf) // the ends remember where they landed
                        model.setConnector(c.copy(from = c.from.at(a), to = c.to.at(b)))
                    }
                    else -> {
                        val moved = dragMap?.takeIf { !cancelled }
                        // A touch that hardly moved is a tap: on a lone table it picks the cell; it never nudges anything.
                        val tap = drag == Drag.MOVE && (moved == null || Math.hypot(moved.e, moved.f) * geometry.scale < TAP_PX)
                        if (tap) {
                            if (!cancelled) lineTable()?.cellAt(dragStartX.toFloat(), dragStartY.toFloat())?.let { cellSel = it }
                        } else moved?.let { if (drag != Drag.MOVE || !moveToOtherPage(it)) model.transform(selection, it) }
                    }
                }
                loop.clear()
                dragMap = null
                linePreview = null
                lineBase = null
                connPreview = null
                connBase = null
                shapePreview = null
                shapeBase = null
                guides = emptyList()
                lassoing = false
                changed(true)
            }
        }
        return true
    }

    /**
     * A selection moved by [m] (a plain move) that ends up mostly over another page goes onto that page: copied there
     * first and only then taken off this one, so a failure cannot lose it. Returns false if it stays on this page.
     * The two pages have their own undo steps.
     */
    private fun moveToOtherPage(m: Affine): Boolean {
        val l = layout ?: return false
        val src = model
        val boxes = src.order.filter { it in selection }.mapNotNull(::boxOf)
        if (boxes.isEmpty()) return false
        val left = boxes.minOf { it.l }; val top = boxes.minOf { it.t }
        val (ox, oy) = l.origin(src)
        val cx = ox + (left + boxes.maxOf { it.r }) / 2 + m.e
        val cy = oy + (top + boxes.maxOf { it.b }) / 2 + m.f
        val dst = l.pageAt(cx, cy)?.takeIf { it !== src } ?: return false
        val clip = Clip.of("", src, selection, ::boxOf) ?: return false
        val (dx, dy) = l.origin(dst)
        val ids = dst.paste(clip, (ox + left + m.e - dx).toFloat(), (oy + top + m.f - dy).toFloat())
        if (ids.isEmpty()) return false
        src.delete(selection)
        becomeWork(dst) // clears the selection and makes [dst] the page the code works on
        selection.addAll(ids)
        return true
    }

    // ---- Straight line: the pen drags out a line from where it landed; it is stored when the pen lifts. ----

    private var lining = false
    private var lineHl = false // the line being dragged is a highlighter stroke
    private var lineDotted = false // ...or a dotted one
    private var ruling = false // ...and it runs along the ruler's edge
    private var rulerSide = 0
    private var lineStartX = 0f
    private var lineStartY = 0f
    private var lineStartMs = 0L
    private var lineEnd = 0f to 0f // page units, after snapping
    private val linePaint = Paint().apply { strokeCap = Paint.Cap.ROUND; isAntiAlias = true }

    /**
     * Show the line's angle live while it is drawn (with the line tools and along the ruler). While it is on the line is
     * free: it is not pulled onto level or upright, so the angle can be set exactly.
     */
    var lineAngle = false
    private val angleArc = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF6750A4.toInt(); isAntiAlias = true }
    private val angleText = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

    /** A level reference line, the arc up to the line, and the angle in a pill by the pen. */
    private fun drawAngle(canvas: Canvas, sx: Float, sy: Float, ex: Float, ey: Float) {
        val len = Math.hypot((ex - sx).toDouble(), (ey - sy).toDouble()).toFloat()
        if (len < 16f) return
        val deg = LineGeometry.angleDeg(sx, sy, ex, ey)
        val r = minOf(70f, len * 0.6f)
        canvas.drawLine(sx, sy, sx + maxOf(r * 1.5f, 60f), sy, dragPaint) // the level line the angle is measured from
        canvas.drawArc(android.graphics.RectF(sx - r, sy - r, sx + r, sy + r), 0f, -deg.toFloat(), false, angleArc) // counterclockwise
        val label = LineGeometry.angleLabel(deg)
        val w = angleText.measureText(label) + 28f
        val cx = (ex + 24f + w / 2).coerceIn(w / 2 + 8f, width - w / 2 - 8f)
        val cy = (ey - 36f).coerceAtLeast(40f)
        canvas.drawRoundRect(cx - w / 2, cy - 28f, cx + w / 2, cy + 22f, 25f, 25f, hintBg)
        canvas.drawText(label, cx, cy + 12f, angleText)
    }

    private fun drawLinePreview(canvas: Canvas) {
        if (!lining) return
        val g = geometry
        linePaint.color = if (lineHl) highlighterColor else penColor
        linePaint.strokeWidth = maxOf(1.5f, ((if (lineHl) highlighterWidth else penWidth) * g.scale).toFloat())
        linePaint.pathEffect = null
        if (lineDotted && !lineHl) {
            linePaint.strokeWidth = maxOf(1.5f, (Dotted.dot(penWidth.toFloat()) * g.scale).toFloat())
            linePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.01f, (Dotted.spacing(penWidth.toFloat()) * g.scale).toFloat()), 0f)
        }
        canvas.drawLine(
            g.toScreenX(lineStartX.toDouble()).toFloat(), g.toScreenY(lineStartY.toDouble()).toFloat(),
            g.toScreenX(lineEnd.first.toDouble()).toFloat(), g.toScreenY(lineEnd.second.toDouble()).toFloat(), linePaint,
        )
        if (lineAngle) drawAngle(
            canvas, g.toScreenX(lineStartX.toDouble()).toFloat(), g.toScreenY(lineStartY.toDouble()).toFloat(),
            g.toScreenX(lineEnd.first.toDouble()).toFloat(), g.toScreenY(lineEnd.second.toDouble()).toFloat(),
        )
    }

    private fun line(event: MotionEvent): Boolean {
        val g = geometry
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) {
                val rl = ruler
                lineEnd = if (ruling && rl != null) {
                    val (ex, ey) = rl.onEdge(event.getX(i), event.getY(i), rulerSide) // along the edge, never snapped to level
                    g.toPageX(ex.toDouble()).toFloat() to g.toPageY(ey.toDouble()).toFloat()
                } else if (lineAngle) { // the angle is on show, so the line goes exactly where the pen is
                    g.toPageX(event.getX(i).toDouble()).toFloat() to g.toPageY(event.getY(i).toDouble()).toFloat()
                } else LineGeometry.end(lineStartX, lineStartY, g.toPageX(event.getX(i).toDouble()).toFloat(), g.toPageY(event.getY(i).toDouble()).toFloat())
                pageLayer.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                val pts = if (event.actionMasked == MotionEvent.ACTION_CANCEL) null
                    else LineGeometry.points(lineStartX, lineStartY, lineEnd.first, lineEnd.second, event.eventTime - lineStartMs)
                lining = false
                ruling = false
                if (pts != null) {
                    if (lineHl) model.add(StrokeTool.TOOL_HIGHLIGHTER, highlighterColor, highlighterWidth, pts)
                    else model.add(if (lineDotted) StrokeTool.TOOL_DOTTED else StrokeTool.TOOL_PEN, penColor, penWidth, pts)
                }
                changed(true)
            }
        }
        return true
    }

    // ---- Dotted pen: the pen draws a row of dots. It is drawn here (not by the ink library's live layer) and stored as
    // an ordinary stroke with the dotted tool when the pen lifts. ----

    private var dotting = false
    private val dotPaint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
    private val dotFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val dottedPaths = HashMap<String, Triple<Item, android.graphics.Path, FloatArray>>() // each finished one's path and its box

    private fun dotGesture(event: MotionEvent): Boolean {
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) { collect(event, i); pageLayer.invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                dotting = false
                if (event.actionMasked != MotionEvent.ACTION_CANCEL && drawn.isNotEmpty()) model.add(StrokeTool.TOOL_DOTTED, penColor, penWidth, Dotted.thinned(drawn.toList()))
                drawn.clear()
                changed(true)
            }
        }
        return true
    }

    /** The dots as the pen draws them, on the screen. */
    private fun drawDottedPreview(canvas: Canvas) {
        if (!dotting || drawn.isEmpty()) return
        val g = geometry
        val w = penWidth.toFloat()
        dotPaint.color = penColor
        dotPaint.strokeWidth = maxOf(1.5f, (Dotted.dot(w) * g.scale).toFloat())
        dotPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.01f, (Dotted.spacing(w) * g.scale).toFloat()), 0f)
        val path = android.graphics.Path()
        drawn.forEachIndexed { k, p ->
            val x = g.toScreenX(p.x.toDouble()).toFloat(); val y = g.toScreenY(p.y.toDouble()).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (drawn.size == 1) {
            dotFillPaint.color = penColor
            canvas.drawCircle(g.toScreenX(drawn[0].x.toDouble()).toFloat(), g.toScreenY(drawn[0].y.toDouble()).toFloat(), dotPaint.strokeWidth / 2, dotFillPaint)
        } else canvas.drawPath(path, dotPaint)
    }

    /** Draws a finished dotted stroke in page units (the canvas has the page matrix). */
    private fun drawDotted(canvas: Canvas, item: Item, vl: Float, vt: Float, vr: Float, vb: Float) {
        val moved = dragMap?.takeIf { item.id in selection }
        val cached = dottedPaths[item.id]?.takeIf { it.first === item } ?: run {
            val p = android.graphics.Path()
            item.pts.forEachIndexed { k, pt -> if (k == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y) }
            val pad = Dotted.dot(item.width.toFloat())
            Triple(item, p, floatArrayOf(item.pts.minOf { it.x } - pad, item.pts.minOf { it.y } - pad, item.pts.maxOf { it.x } + pad, item.pts.maxOf { it.y } + pad))
                .also { dottedPaths[item.id] = it }
        }
        val (l, t, r, b) = cached.third.toList()
        if (moved == null && (r < vl || l > vr || b < vt || t > vb)) return
        canvas.save()
        if (moved != null) canvas.concat(Matrix().apply { setValues(floatArrayOf(moved.a.toFloat(), moved.c.toFloat(), moved.e.toFloat(), moved.b.toFloat(), moved.d.toFloat(), moved.f.toFloat(), 0f, 0f, 1f)) })
        val w = item.width.toFloat()
        val dot = maxOf(Dotted.dot(w), (1.5 / geometry.scale).toFloat())
        if (item.pts.size == 1) {
            dotFillPaint.color = item.color
            canvas.drawCircle(item.pts[0].x, item.pts[0].y, dot / 2, dotFillPaint)
        } else {
            dotPaint.color = item.color
            dotPaint.strokeWidth = dot
            dotPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.01f, Dotted.spacing(w)), 0f)
            canvas.drawPath(cached.second, dotPaint)
        }
        canvas.restore()
    }

    // ---- Laser pointer: a red dot that follows the pen and fades. Nothing is stored. ----

    private var lasering = false
    private val laserTrail = LaserTrail()
    private val laserPaint = Paint().apply { color = Color.RED; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }

    private fun laserTo(event: MotionEvent, i: Int) {
        for (h in 0 until event.historySize) laserTrail.add(event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalEventTime(h))
        laserTrail.add(event.getX(i), event.getY(i), event.eventTime)
        pageLayer.invalidate()
    }

    private fun laser(event: MotionEvent): Boolean {
        val i = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) laserTo(event, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) != pointerId) return true
                lasering = false
                laserTrail.lift()
            }
        }
        return true
    }

    private fun drawLaser(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        laserTrail.prune(now)
        if (laserTrail.isEmpty) return
        var prev: LaserDot? = null
        for (d in laserTrail.alive(now)) {
            laserPaint.alpha = (d.alpha * 255).toInt()
            laserPaint.strokeWidth = 8f * d.alpha + 2f
            val p = prev
            if (d.joined && p != null) canvas.drawLine(p.x, p.y, d.x, d.y, laserPaint) else canvas.drawPoint(d.x, d.y, laserPaint)
            prev = d
        }
        pageLayer.postInvalidateOnAnimation() // keeps fading until it is gone
    }

    // ---- Ruler: lies on the screen; a pen stroke that starts at its edge runs straight along it. ----

    /** The ruler, or null when it is hidden. It is not saved and not synced. */
    var ruler: Ruler? = null
        private set

    /** How close (on screen) the pen must land to a ruler edge to be drawn along it: 8 mm. */
    private val rulerReachPx get() = resources.displayMetrics.xdpi / 25.4f * 8f

    fun toggleRuler() {
        ruler = if (ruler != null) null else {
            val perCm = resources.displayMetrics.xdpi / 2.54f
            Ruler(width / 2f, height / 3f, 0f, minOf(20f * perCm, width * 0.9f), 3f * perCm)
        }
        rulerIds.clear()
        pageLayer.invalidate()
    }

    private val rulerIds = ArrayList<Int>() // the fingers holding the ruler (up to two)
    private val rulerLast = HashMap<Int, Pair<Float, Float>>()

    private val penBusy get() = strokeId != null || lining || erasing || lassoing || lasering || snapped != null || textTapping || tabling

    /** A finger landing on the ruler takes hold of it (instead of scrolling the page). True if it did. */
    private fun rulerGrab(event: MotionEvent): Boolean {
        val r = ruler ?: return false
        val a = event.actionMasked
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_POINTER_DOWN) return false
        val i = event.actionIndex
        if (isPen(event.getToolType(i)) || gestureActive || rulerIds.size >= 2 || !r.contains(event.getX(i), event.getY(i))) return false
        val id = event.getPointerId(i)
        rulerIds.add(id)
        rulerLast[id] = event.getX(i) to event.getY(i)
        return true
    }

    /** Moves and turns the ruler with the fingers holding it. True if the event was only theirs (the pen is not busy). */
    private fun rulerFingers(event: MotionEvent): Boolean {
        val r = ruler
        if (r == null || rulerIds.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val now = rulerIds.map { id -> event.findPointerIndex(id).let { if (it < 0) null else event.getX(it) to event.getY(it) } }
                if (now.all { it != null }) {
                    val old = rulerIds.map { rulerLast.getValue(it) }
                    if (now.size == 2) r.follow(old[0].first, old[0].second, old[1].first, old[1].second, now[0]!!.first, now[0]!!.second, now[1]!!.first, now[1]!!.second)
                    else r.move(now[0]!!.first - old[0].first, now[0]!!.second - old[0].second)
                    rulerIds.forEachIndexed { k, id -> rulerLast[id] = now[k]!! }
                    pageLayer.invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (rulerIds.remove(id)) rulerLast.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> { rulerIds.clear(); rulerLast.clear() }
            else -> return false // a pen landing is the pen's
        }
        return !penBusy
    }

    private val rulerFill = Paint().apply { color = 0x66B0BEC5; style = Paint.Style.FILL }
    private val rulerEdge = Paint().apply { color = 0xFF546E7A.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }

    private fun drawRuler(canvas: Canvas) {
        val r = ruler ?: return
        val c = r.corners()
        val body = android.graphics.Path().apply { moveTo(c[0].first, c[0].second); for (k in 1..3) lineTo(c[k].first, c[k].second); close() }
        canvas.drawPath(body, rulerFill)
        canvas.drawPath(body, rulerEdge)
        // Metric ticks go on the top edge, inches on the top edge or (with both) on the bottom one.
        if (rulerUnits != RulerUnits.INCHES) drawTicks(canvas, r, false, -1)
        if (rulerUnits != RulerUnits.METRIC) drawTicks(canvas, r, true, if (rulerUnits == RulerUnits.BOTH) 1 else -1)
    }

    private val rulerText = Paint().apply { color = 0xFF37474F.toInt(); textSize = 24f; isAntiAlias = true }

    /** The ticks of one scale along long edge [side] (-1 top, 1 bottom), pointing inwards, numbers beside the long ones. */
    private fun drawTicks(canvas: Canvas, r: Ruler, imperial: Boolean, side: Int) {
        val inward = (-sin(r.angle) to cos(r.angle)) // across the ruler, from the top edge to the bottom one
        val sign = -side // from the edge a tick starts on, inwards
        for (m in RulerScale.marks(imperial, r.length, geometry.scale)) {
            val len = r.thickness * listOf(0.3f, 0.22f, 0.17f, 0.13f, 0.1f)[if (imperial) m.rank else listOf(0, 1, 4)[m.rank]]
            val (x, y) = r.edgePoint(m.along, side)
            canvas.drawLine(x, y, x + inward.first * len * sign, y + inward.second * len * sign, rulerEdge)
            val label = m.label ?: continue
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(Math.toDegrees(r.angle.toDouble()).toFloat())
            canvas.drawText(label, 4f, if (sign > 0) len + rulerText.textSize else -len - 4f, rulerText)
            canvas.restore()
        }
    }

    /** What the ruler's edges measure. */
    var rulerUnits = RulerUnits.METRIC
        set(v) { field = v; pageLayer.invalidate() }

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
        if (model.erase(g.toPageX(sx.toDouble()).toFloat(), g.toPageY(sy.toDouble()).toFloat(), (eraserPx / g.scale).toFloat(), partial, eraseOnly) { layoutOf(it).height.toFloat() }) {
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
                    strokeId?.let { liveLayer.cancelStroke(it, event); strokePage.remove(it) }
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
        if (carryPage != null) return carry(event)
        scaleDetector.onTouchEvent(event)
        velocity?.addMovement(event)
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
            var dx = (cx - lastX).toDouble()
            var dy = (cy - lastY).toDouble()
            moveTotal += Math.hypot(dx, dy)
            if (n > 1) { pinched = true; gridCell = null } // two fingers pinch: the grid's lock is for one
            var held = false
            if (gridCell != null && gridAxis == null) { // locked scrolling picks its direction from the first few pixels
                gridDx += dx; gridDy += dy
                if (maxOf(Math.abs(gridDx), Math.abs(gridDy)) < AXIS_SLOP_PX) held = true
                else { gridAxis = if (Math.abs(gridDy) >= Math.abs(gridDx)) Axis.V else Axis.H; dx = gridDx; dy = gridDy }
            }
            if (!held) {
                val (leftX, leftY) = pan(dx, dy)
                slid += Math.abs(dx) + (if (gridCell != null) Math.abs(dy) else 0.0)
                if (gridCell != null && n == 1) { // one finger pulls at the end of a row or column
                    pullX = PageTurn.pull(pullX, dx, leftX)
                    pullY = PageTurn.pull(pullY, dy, leftY)
                    PageTurn.dominant(pullX, pullY).let { pullDrawX = it.first; pullDrawY = it.second }
                    // A pull counts only once it has gone far enough and been held there for a moment.
                    val reached = PageTurn.direction(pullX, pullY)?.let { (_, px) -> PageTurn.decide(px / pxPerUnit) == Turn.ADD } == true
                    if (!reached) pullReachedAt = 0L
                    else if (pullReachedAt == 0L) { pullReachedAt = android.os.SystemClock.uptimeMillis(); postDelayed({ pageLayer.invalidate() }, PULL_HOLD_MS + 20) }
                }
            }
            pageLayer.invalidate()
        }
        lastX = cx
        lastY = cy
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) removeCallbacks(carryCheck)
        if (action == MotionEvent.ACTION_UP) {
            val pulled = endPull()
            val tracker = velocity
            velocity = null
            var glides = false
            if (!pulled && !pinched && tracker != null) {
                tracker.computeCurrentVelocity(1000)
                val id = event.getPointerId(0)
                glides = startGlide(tracker.getXVelocity(id).toDouble(), tracker.getYVelocity(id).toDouble())
            }
            tracker?.recycle()
            if (!glides && !pulled && !pinched && slid > SLIDE_MIN_PX) settle() // otherwise it settles when the glide ends
            // A tap on a page, seen from afar, zooms into it.
            if (!pinched && moveTotal < TAP_PX * 1.5 && layout != null && !vp.infinite && vp.percent(maxOf(1, work.page.width)) < ZOOMED_OUT_PERCENT) {
                val wx = vp.toPageX(lastX.toDouble()); val wy = vp.toPageY(lastY.toDouble())
                val hit = layout?.pageAt(wx, wy)
                if (hit != null) zoomIntoPage(hit)
                else layout?.slotAt(wx, wy)?.takeIf { (c, r) -> notebook.at(c, r) == null }?.let { (c, r) ->
                    pagesChanged { notebook.addAt(work, c, r, emptyLook) != null } // the cell is shown in the chosen size
                }
            }
        }
        if (action == MotionEvent.ACTION_CANCEL) { pullX = 0.0; pullY = 0.0; pullDrawX = 0.0; pullDrawY = 0.0; pullReachedAt = 0L; velocity?.recycle(); velocity = null; pageLayer.invalidate() }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gestureActive = false
        return true
    }

    // ---- Rearranging pages: seen from afar, hold a finger on a page to lift it, drag it to an empty cell (it moves),
    // another page (the two swap) or the bin (it is deleted), and let go. Page changes have their own undo. ----

    private var carryPage: PageModel? = null // the page lifted, while one is
    private var carryX = 0f // where the finger is
    private var carryY = 0f
    private var carryDownX = 0f
    private var carryDownY = 0f
    private val carryCheck = Runnable { startCarry() }
    private val carryDash = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF1A73E8.toInt(); isAntiAlias = true; pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f) }
    private val carryHot = Paint().apply { style = Paint.Style.FILL; color = 0x331A73E8; isAntiAlias = true }
    private val carryEdge = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = 0xFF1A73E8.toInt(); isAntiAlias = true }
    private val carryGhost = Paint().apply { style = Paint.Style.FILL; color = 0xCCFFFFFF.toInt() }
    private val binFill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val binLine = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true; color = Color.WHITE }

    private var carryTick = 0L

    /** While a page is carried, a finger near the edge of the screen scrolls the view that way (not near the bin). */
    private val carryScroll = object : Runnable {
        override fun run() {
            if (carryPage == null) return
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - carryTick).coerceIn(0L, 50L) / 1000.0
            carryTick = now
            val d = resources.displayMetrics.density
            val zone = 96 * d
            val top = 120 * d // the button row at the top is not a place to push against
            val bin = carryBin()
            fun push(pos: Float, size: Float, from: Float): Double =
                if (pos < from + zone) (1 - (pos - from) / zone).coerceIn(0f, 1f).toDouble() else if (pos > size - zone) -(1 - (size - pos) / zone).coerceIn(0f, 1f).toDouble() else 0.0
            val dx = push(carryX, width.toFloat(), 0f)
            val nearBin = carryX > bin.left - bin.width() && carryX < bin.right + bin.width()
            val dy = if (carryY > height - zone && nearBin) 0.0 else push(carryY, height.toFloat(), top - zone)
            if (dx != 0.0 || dy != 0.0) { vp.panBy(dx * 900 * d * dt, dy * 900 * d * dt); pageLayer.invalidate() }
            postOnAnimation(this)
        }
    }

    /** A finger went down: if the pages are seen from afar and it stays put for a moment, the page under it is lifted. */
    private fun armCarry(x: Float, y: Float) {
        carryDownX = x; carryDownY = y
        removeCallbacks(carryCheck)
        postDelayed(carryCheck, CARRY_HOLD_MS)
    }

    private fun startCarry() {
        val l = layout ?: return
        if (!gestureActive || pinched || carryPage != null || moveTotal >= TAP_PX * 1.5) return
        if (vp.infinite || vp.percent(maxOf(1, work.page.width)) >= ZOOMED_OUT_PERCENT) return
        val p = l.pageAt(vp.toPageX(carryDownX.toDouble()), vp.toPageY(carryDownY.toDouble())) ?: return
        finishText()
        stopGlide()
        gridCell = null
        velocity?.recycle(); velocity = null
        carryPage = p
        carryX = lastX; carryY = lastY
        carryTick = android.os.SystemClock.uptimeMillis()
        postOnAnimation(carryScroll)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        pageLayer.invalidate()
    }

    private fun carry(event: MotionEvent): Boolean {
        val p = carryPage ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { carryX = event.getX(0); carryY = event.getY(0); pageLayer.invalidate() }
            MotionEvent.ACTION_UP -> { carryX = event.getX(0); carryY = event.getY(0); dropCarry(p) }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            carryPage = null
            gestureActive = false
            pageLayer.invalidate()
        }
        return true
    }

    /** The bin, at the bottom middle of the screen (the same on screen whatever the view). */
    private fun carryBin(): RectF {
        @Suppress("DEPRECATION") val bottom = rootWindowInsets?.systemWindowInsetBottom ?: 0
        val d = resources.displayMetrics.density
        val size = 88 * d
        return RectF(width / 2f - size / 2, height - bottom - 24 * d - size, width / 2f + size / 2, height - bottom - 24 * d)
    }

    private fun dropCarry(p: PageModel) {
        val l = layout ?: return
        if (carryBin().contains(carryX, carryY)) { pagesChanged { notebook.deletePage(p) }; return }
        val (c, r) = l.slotAt(vp.toPageX(carryX.toDouble()), vp.toPageY(carryY.toDouble()))
        pagesChanged { notebook.movePage(p, c, r, emptyLook) }
    }

    /**
     * Runs [change] (it changes the notebook's pages; true if it did) and lays the pages out again. The view stays
     * where it was, held by the page nearest the middle of the screen that did not change cell, and a working page is kept.
     */
    private fun pagesChanged(change: () -> Boolean) {
        finishText()
        val old = layout
        val cells = notebook.pages.associateWith { it.page.col to it.page.row }
        val (tx, ty) = vp.tx to vp.ty
        val mx = vp.toPageX(width / 2.0); val my = vp.toPageY(height / 2.0)
        if (!change()) return
        // Looked up in the old layout, which is the one that still matches the cells recorded above.
        val keep = old?.nearestOf(notebook.pages.filter { cells[it] == it.page.col to it.page.row }, mx, my)
        val before = keep?.let { old.origin(it) }
        if (work !in notebook.pages) { selection.clear(); cellSel = null; loop.clear(); dragMap = null; work = notebook.current } else notebook.go(work)
        rebuildLayout(false)
        keepView(keep, before, tx, ty)
        onPageChanged?.invoke()
        pageLayer.invalidate()
    }

    /** Takes back the last page change (a move, swap or delete); drawing is undone by [undo]. */
    fun undoPage() = pagesChanged { notebook.undoPage() }
    fun redoPage() = pagesChanged { notebook.redoPage() }

    private val plusDash = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFB0B0B0.toInt(); isAntiAlias = true; pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f) }
    private val plusPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true; color = 0xFF8A8A8A.toInt() }

    /** Seen from afar, every empty cell (and the ring round the grid) shows a plus: tap it to add a page there. */
    private fun drawPlusCells(canvas: Canvas) {
        val l = layout ?: return
        if (carryPage != null || vp.infinite || vp.percent(maxOf(1, work.page.width)) >= ZOOMED_OUT_PERCENT) return
        for ((c, r) in l.slots(vp.visible())) {
            if (notebook.at(c, r) != null) continue
            val q = l.slotRect(c, r, emptyLook.width, emptyLook.height)
            val x0 = vp.toScreenX(q[0]).toFloat(); val y0 = vp.toScreenY(q[1]).toFloat()
            val x1 = vp.toScreenX(q[0] + q[2]).toFloat(); val y1 = vp.toScreenY(q[1] + q[3]).toFloat()
            if (x1 < 0 || y1 < 0 || x0 > width || y0 > height) continue
            canvas.drawRect(x0, y0, x1, y1, plusDash)
            val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
            val arm = minOf(x1 - x0, y1 - y0) * 0.15f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plusPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plusPaint)
        }
    }

    private fun drawCarry(canvas: Canvas) {
        val p = carryPage ?: return
        val l = layout ?: return
        val w = p.page.width; val h = p.page.height
        val bin = carryBin()
        val overBin = bin.contains(carryX, carryY)
        val target = if (overBin) null else l.slotAt(vp.toPageX(carryX.toDouble()), vp.toPageY(carryY.toDouble()))
        for ((c, r) in l.slots(vp.visible())) {
            val q = l.slotRect(c, r, emptyLook.width, emptyLook.height)
            val rect = RectF(vp.toScreenX(q[0]).toFloat(), vp.toScreenY(q[1]).toFloat(), vp.toScreenX(q[0] + q[2]).toFloat(), vp.toScreenY(q[1] + q[3]).toFloat())
            val here = notebook.at(c, r)
            val hot = target == c to r
            if (here == null || here === p) { if (hot) canvas.drawRect(rect, carryHot); canvas.drawRect(rect, carryDash) }
            else if (hot) canvas.drawRect(rect, carryEdge) // a page there: the two swap
            if (here === p) canvas.drawRect(rect, carryGhost) // the lifted page leaves a pale sheet behind
        }
        val gw = (w * vp.scale).toFloat(); val gh = (h * vp.scale).toFloat()
        canvas.drawRect(carryX - gw / 2, carryY - gh / 2, carryX + gw / 2, carryY + gh / 2, carryGhost)
        canvas.drawRect(carryX - gw / 2, carryY - gh / 2, carryX + gw / 2, carryY + gh / 2, sheetEdge)
        val last = notebook.pages.size == 1 // the last page cannot be deleted: the bin is pale
        binFill.color = if (last) 0x66B3261E else if (overBin) 0xFFB3261E.toInt() else 0xCCB3261E.toInt()
        canvas.drawRoundRect(bin, 24f, 24f, binFill)
        val cx = bin.centerX(); val cy = bin.centerY()
        canvas.drawRect(cx - 16f, cy - 8f, cx + 16f, cy + 26f, binLine) // the can
        canvas.drawLine(cx - 24f, cy - 12f, cx + 24f, cy - 12f, binLine) // the lid
        canvas.drawLine(cx - 8f, cy - 20f, cx + 8f, cy - 20f, binLine)
    }

    // ---- Pages: all on one surface. Scroll across them; pull past the surface's end to add a page there. ----

    private var pullX = 0.0 // how far the finger has pulled past an edge, in screen pixels
    private var pullY = 0.0
    private var pullDrawX = 0.0 // how far the surface is drawn displaced by the pull (it eases back when the finger lets go)
    private var pullDrawY = 0.0
    private var pullAnim: android.animation.ValueAnimator? = null
    private var pullReachedAt = 0L // when the pull got far enough to add a page (0 if it has not)

    /** Has the pull gone far enough, and been held there long enough, to add a page on letting go? */
    private fun pullArmed() = pullReachedAt != 0L && android.os.SystemClock.uptimeMillis() - pullReachedAt >= PULL_HOLD_MS
    private var slid = 0.0 // how far this finger gesture moved sideways, in pixels
    private var moveTotal = 0.0 // how far the finger has moved in all, in pixels (a tap barely moves)
    private var pinched = false // this gesture used two fingers
    private var snapAnim: android.animation.ValueAnimator? = null

    /**
     * Lets go of a pull: adds a page if it was long enough, beside the last page of the row or column being scrolled,
     * on the side that was pulled. True if there was a pull.
     */
    private fun endPull(): Boolean {
        val pull = PageTurn.direction(pullX, pullY)
        val (shiftX, shiftY) = PageTurn.dominant(pullX, pullY)
        pullX = 0.0
        pullY = 0.0
        var added = false
        val cell = gridCell
        val armed = pullArmed()
        pullReachedAt = 0L
        if (pull != null && cell != null && layout != null) {
            val (dir, px) = pull
            if (armed && PageTurn.decide(px / pxPerUnit) == Turn.ADD) {
                finishText()
                val before = layout?.origin(cell)
                val (tx, ty) = vp.tx to vp.ty
                val current = notebook.current
                val made = notebook.add(notebook.end(cell, dir), dir)
                if (made != null) {
                    // The view stays on the page it was on, as the pull left it, then slides to show the edge of the new one.
                    notebook.go(current)
                    rebuildLayout(false)
                    keepView(cell, before, tx, ty, shiftX * PULL_SHIFT, shiftY * PULL_SHIFT)
                    peek(cell, before, tx, ty, dir)
                    added = true
                }
            }
        }
        // The displacement the pull showed goes away smoothly (when a page was added it moved into the scroll itself).
        pullAnim?.cancel()
        if (added || (pullDrawX == 0.0 && pullDrawY == 0.0)) { pullDrawX = 0.0; pullDrawY = 0.0 }
        else {
            val fx = pullDrawX; val fy = pullDrawY
            pullAnim = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 220
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { pullDrawX = fx * it.animatedValue as Float; pullDrawY = fy * it.animatedValue as Float; pageLayer.invalidate() }
                start()
            }
        }
        pageLayer.invalidate()
        // Only a real pull (as long as it needs to be to add a page) ends the scroll there and then; the little a
        // finger drifts sideways during a vertical flick is not one, and must not stop the glide.
        return pull != null && pull.second / pxPerUnit >= PageTurn.PULL
    }

    /** Slides the view a little towards the page just added in direction [dir], so the edge of it shows. [cell] is the page the view is on. */
    private fun peek(cell: PageModel, before: Pair<Double, Double>?, tx: Double, ty: Double, dir: Dir) {
        val after = layout?.origin(cell) ?: return
        if (before == null) return
        val s = vp.scale
        // Where the page's own spot on the screen is now (the pull's shift already applied by the caller).
        val baseX = tx - (after.first - before.first) * s
        val baseY = ty - (after.second - before.second) * s
        val gap = NotebookLayout.GAP * s
        val toX = baseX - dir.dCol * (gap + PEEK * width)
        val toY = baseY - dir.dRow * (gap + PEEK * height)
        val fromX = vp.tx
        val fromY = vp.ty
        snapAnim?.cancel()
        snapAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                vp.offsetTo(fromX + (toX - fromX) * f, fromY + (toY - fromY) * f)
                pageLayer.invalidate()
            }
            start()
        }
    }

    // ---- Grid scrolling: while a page is fitted to the screen, a drag goes one way only (the first few pixels say
    // which), keeps to the row or column of the page it started on, and settles onto pages. ----

    private enum class Axis { H, V }

    private var gridCell: PageModel? = null // the page nearest the middle when the finger came down; null when scrolling is free
    private var gridAxis: Axis? = null
    private var gridDx = 0.0
    private var gridDy = 0.0

    /** Is locked scrolling in force: the grid mode, several pages, and zoomed out to about a fitted page. */
    private fun gridOn() = scrollMode == ScrollMode.GRID && layout != null && !vp.infinite &&
        vp.percent(maxOf(1, work.page.width)) in GRID_MIN_PERCENT..GRID_MAX_PERCENT

    /** The page nearest the middle of the screen. */
    private fun centrePage(): PageModel? = layout?.nearest(vp.toPageX(width / 2.0), vp.toPageY(height / 2.0))

    private fun beginGrid() {
        pullAnim?.cancel()
        pullDrawX = 0.0; pullDrawY = 0.0
        pullReachedAt = 0L
        gridCell = if (gridOn()) centrePage() else null
        gridAxis = null
        gridDx = 0.0
        gridDy = 0.0
    }

    /** How far the surface may be scrolled (its offset) along an axis for the row or column through [cell]. */
    private fun gridRange(cell: PageModel, horizontal: Boolean): Pair<Double, Double> {
        val l = layout ?: return 0.0 to 0.0
        val spans = notebook.run(cell, horizontal).map { p ->
            l.origin(p).let { o -> if (horizontal) o.first to p.page.width.toDouble() else o.second to p.page.height.toDouble() }
        }
        return PageSnap.range(if (horizontal) width else height, vp.scale, spans, PageGeometry.MARGIN)
    }

    /**
     * [t] moved by [d] but kept within [lo] to [hi]. If it is already outside (the view is between rows, say), it is
     * never pulled back in with a jump: it may only move towards the range, and no further out.
     */
    private fun softClamp(t: Double, d: Double, lo: Double, hi: Double): Double = when {
        t < lo -> if (d > 0) minOf(t + d, hi) else t
        t > hi -> if (d < 0) maxOf(t + d, lo) else t
        else -> (t + d).coerceIn(lo, hi)
    }

    /** Scrolls by (dx, dy) pixels and returns what could not be applied. Locked: one direction, within the row or column. */
    private fun pan(dx: Double, dy: Double): Pair<Double, Double> {
        val cell = gridCell ?: return geometry.panBy(dx, dy)
        val axis = gridAxis ?: return 0.0 to 0.0
        val ax = if (axis == Axis.H) dx else 0.0
        val ay = if (axis == Axis.V) dy else 0.0
        val (x0, x1) = gridRange(cell, true)
        val (y0, y1) = gridRange(cell, false)
        val nx = softClamp(vp.tx, ax, x0, x1)
        val ny = softClamp(vp.ty, ay, y0, y1)
        val fromX = vp.tx
        val fromY = vp.ty
        vp.slideBy(nx - fromX, ny - fromY)
        // What is left over is what the surface really did not move: its own edges can be tighter than the row's or
        // column's range (a page shorter than the screen cannot be centred at the very end), and that counts as a pull too.
        return (ax - (vp.tx - fromX)) to (ay - (vp.ty - fromY))
    }

    private var velocity: VelocityTracker? = null
    private var glide: Fling? = null
    private var glideAt = 0L

    private val glideStep = object : Runnable {
        override fun run() {
            val f = glide ?: return
            val now = System.nanoTime()
            val dt = ((now - glideAt) / 1e9).coerceIn(0.0, 0.1)
            glideAt = now
            val (dx, dy) = f.step(dt)
            val (leftX, leftY) = pan(dx, dy)
            if (Math.abs(leftX) > Math.abs(dx) * 0.5 + 0.01) f.stopX() // the page met an edge (or has no room that way)
            if (Math.abs(leftY) > Math.abs(dy) * 0.5 + 0.01) f.stopY()
            pageLayer.invalidate()
            if (f.done) {
                glide = null
                if (slid > SLIDE_MIN_PX && !pinched && !glideLanded) settle()
            } else postOnAnimation(this)
        }
    }

    /** Lets the scroll carry on after the finger lets go, at the speed it had. False if it was too slow. */
    private var glideLanded = false // the glide was bent to end on a page, so nothing needs to settle it after

    private fun startGlide(vx0: Double, vy0: Double): Boolean {
        var vx = vx0; var vy = vy0
        glideLanded = false
        val cell = gridCell
        if (cell != null) { // locked: it carries on in the direction it was going, and only that, and comes to rest on a page
            val l = layout
            val tau = Fling.TAU * scrollIntensity
            when (gridAxis) {
                Axis.H -> {
                    vy = 0.0
                    if (l != null) {
                        val spans = notebook.run(centrePage() ?: cell, true).map { p -> l.origin(p).first to p.page.width.toDouble() }
                        val to = PageSnap.target(vp.tx + vx * tau, vp.scale, width, spans, PageGeometry.MARGIN, PageSnap.LAND * width)
                        vx = Fling.landing(vp.tx, vx, tau, to).also { glideLanded = it != vx }
                    }
                }
                Axis.V -> {
                    vx = 0.0
                    if (l != null) {
                        val spans = notebook.run(centrePage() ?: cell, false).map { p -> l.origin(p).second to p.page.height.toDouble() }
                        val to = PageSnap.target(vp.ty + vy * tau, vp.scale, height, spans, PageGeometry.MARGIN, PageSnap.LAND * height)
                        vy = Fling.landing(vp.ty, vy, tau, to).also { glideLanded = it != vy }
                    }
                }
                null -> return false
            }
        }
        val f = Fling.start(vx, vy, scrollIntensity) ?: return false
        glide = f
        glideAt = System.nanoTime()
        postOnAnimation(glideStep)
        return true
    }

    /** Stops any glide or settle in progress (a touch catches the page; a new layout makes them stale). */
    private fun stopGlide() {
        glide = null
        removeCallbacks(glideStep)
        snapAnim?.cancel()
    }

    /**
     * After a scroll, a page slides the last bit into place if one is close (see [PageSnap]): sideways in either mode
     * (with the grid, along the row of the page in the middle), and up or down too with the grid.
     */
    private fun settle() {
        val l = layout ?: return
        if (vp.percent(maxOf(1, work.page.width)) < GRID_MIN_PERCENT) return // zoomed out to see several pages: nothing to snap to, the scroll is free
        val surface = vp
        val cell = if (gridOn()) centrePage() else null
        val columns = if (cell != null) notebook.run(cell, true).map { p -> l.origin(p).first to p.page.width.toDouble() } else l.columns
        val toX = PageSnap.target(surface.tx, surface.scale, width, columns, PageGeometry.MARGIN, PageSnap.RANGE * width)
        val toY = if (cell == null) null else PageSnap.target(
            surface.ty, surface.scale, height, notebook.run(cell, false).map { p -> l.origin(p).second to p.page.height.toDouble() },
            PageGeometry.MARGIN, PageSnap.RANGE_Y * height,
        )
        if (toX == null && toY == null) return
        val fromX = surface.tx
        val fromY = surface.ty
        snapAnim?.cancel()
        snapAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                surface.slideBy(if (toX == null) 0.0 else fromX + (toX - fromX) * f - surface.tx, if (toY == null) 0.0 else fromY + (toY - fromY) * f - surface.ty)
                pageLayer.invalidate()
            }
            start()
        }
    }

    private val hintBg = Paint().apply { color = 0xE6322B3A.toInt(); isAntiAlias = true }
    private val hintText = Paint().apply { color = Color.WHITE; textSize = 40f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

    /** Once the pull is long enough to count, say what letting go will do. */
    private fun drawPullHint(canvas: Canvas) {
        val (dir, px) = PageTurn.direction(pullX, pullY) ?: return
        if (layout == null || gridCell == null || PageTurn.decide(px / pxPerUnit) != Turn.ADD) return
        val text = if (pullArmed()) "Release to add a page" else "Keep holding to add a page"
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
        const val PULL_SHIFT = 0.4f // how much of a pull the surface follows
        const val CARRY_HOLD_MS = 500L // a finger held this long on a page, seen from afar, lifts it
        const val TAP_PX = 10.0 // a touch that moved less than this on screen is a tap
        const val PULL_HOLD_MS = 400L // a pull must be held this long past its distance before letting go adds a page
        const val PEEK = 0.12 // after a page is added the view slides this share of the screen past the gap towards it
        const val AXIS_SLOP_PX = 6.0 // a locked drag picks its direction once it has moved this far
        const val GRID_MIN_PERCENT = 90 // locked scrolling is for a page about fitted to the screen (90 to 105%); zoomed out or in further, the scroll is free
        const val GRID_MAX_PERCENT = 105
        const val ZOOMED_OUT_PERCENT = 90 // below this the pages are seen from afar, and a tap on one zooms into it
        const val SLIDE_MIN_PX = 8.0 // a finger gesture that moved sideways less than this does not settle onto a page
        const val TEXT_MIN_WIDTH = 1000f // a dragged box narrower than 10 mm is not a box
        const val TEXT_SIZE = 400f // text 4 mm high
        const val CORNER_INSET_PX = 26f // a rectangle's corner-radius handle sits this far in from the corner, clear of the resize handle
        const val SNAP_PX = 24f // right-angle connectors line up with their neighbours within this many screen pixels
        const val BEND_REACH_PX = 80f // a tap this close (on screen) to a connector's line adds a bend point on it
        const val CONNECT_SNAP = 500f // a connector end this close (5 mm) to the middle of a side lands on it
        const val CONNECT_REACH = 600f // a pen this close (6 mm) to a shape aims a connector at it, and to a side's middle picks that side
        const val SHAPE_MIN = 300f // a dragged shape smaller than 3 mm either way is not one
        const val SHAPE_PAD = 200f // room between a shape's edge and its text, 2 mm
    }
}
