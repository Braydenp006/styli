package app.styli.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.ExperimentalInkLatencyDataApi
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor

/**
 * A white page you can draw on with a stylus.
 *
 * Two layers stack here. While the pen is down, [InProgressStrokesView] draws the live stroke with
 * the library's low-latency path. When the pen lifts, the finished stroke moves to [pageLayer],
 * which is an ordinary view that redraws every finished stroke.
 */
@SuppressLint("RestrictedApi") // the latency callback is marked library-internal, fine for a test tool
@OptIn(ExperimentalInkLatencyDataApi::class)
class InkCanvasView(context: Context) : FrameLayout(context) {

    /** Called with a short latency summary while drawing. */
    var onLatency: ((String) -> Unit)? = null

    private val finishedStrokes = mutableListOf<Stroke>()
    private val renderer = CanvasStrokeRenderer.create()
    private val brush =
        Brush.createWithColorIntArgb(StockBrushes.pressurePen(), Color.BLACK, 5f, 0.1f)

    private val pageLayer =
        object : View(context) {
            override fun onDraw(canvas: Canvas) {
                for (stroke in finishedStrokes) renderer.draw(canvas, stroke, Matrix())
            }
        }.apply { setBackgroundColor(Color.WHITE) }

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
                    finishedStrokes.addAll(strokes.values)
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
        if (finishedStrokes.removeLastOrNull() != null) pageLayer.invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        val predicted = predictor.predict()
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    requestUnbufferedDispatch(event) // deliver touch events as they happen, not once per frame
                    pointerId = event.getPointerId(0)
                    strokeId = liveLayer.startStroke(event, pointerId, brush)
                }
                MotionEvent.ACTION_MOVE -> {
                    val id = strokeId ?: return true
                    liveLayer.addToStroke(event, pointerId, id, predicted)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    val id = strokeId ?: return true
                    if (event.getPointerId(event.actionIndex) != pointerId) return true
                    liveLayer.finishStroke(event, pointerId, id)
                    strokeId = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    strokeId?.let { liveLayer.cancelStroke(it, event) }
                    strokeId = null
                }
            }
        } finally {
            predicted?.recycle()
        }
        return true
    }
}
