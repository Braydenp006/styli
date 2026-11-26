package app.styli.ink

import app.styli.ink.proto.PageSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Page sizes in the format's unit, 0.01 mm. */
object PageSizes {
    private const val A4_SHORT = 21000
    private const val A4_LONG = 29700
    private const val LETTER_SHORT = 21590
    private const val LETTER_LONG = 27940

    fun of(letter: Boolean, landscape: Boolean): Pair<Int, Int> {
        val (w, h) = if (letter) LETTER_SHORT to LETTER_LONG else A4_SHORT to A4_LONG
        return if (landscape) h to w else w to h
    }

    /** A page's orientation is not stored: it is whether the width is larger than the height (a square is portrait). */
    fun isLandscape(p: PageSet) = p.width > p.height

    /** Letter, A4, or null for any other size (a PDF's own, say). */
    fun isLetter(p: PageSet): Boolean? = when (minOf(p.width, p.height) to maxOf(p.width, p.height)) {
        LETTER_SHORT to LETTER_LONG -> true
        A4_SHORT to A4_LONG -> false
        else -> null
    }

    /** The same page turned to [landscape] or portrait: the sides swap, the paper stays the same. */
    fun withOrientation(p: PageSet, landscape: Boolean): PageSet =
        if (isLandscape(p) == landscape) p else p.toBuilder().setWidth(p.height).setHeight(p.width).build()

    /** The same page on Letter or A4 paper, keeping its orientation. */
    fun withPaper(p: PageSet, letter: Boolean): PageSet {
        val (w, h) = of(letter, isLandscape(p))
        return p.toBuilder().setWidth(w).setHeight(h).build()
    }
}

/**
 * Where the page is on the screen. Everything on the page is in units of 0.01 mm; the screen shows
 * `screen = page * scale + (tx, ty)`. No Android types, so it can be unit tested.
 *
 * A fixed page keeps its sheet on the screen (it can be panned only as far as its edges); an infinite
 * canvas has no edges, only a limit so coordinates stay far inside the format's 32-bit range.
 */
class PageGeometry(
    val pageW: Int,
    val pageH: Int,
    val infinite: Boolean,
    /** Screen pixels per unit when the page is shown at its real size. */
    private val oneToOne: Double,
) {
    var scale = oneToOne
        private set
    var tx = 0.0
        private set
    var ty = 0.0
        private set
    private var viewW = 0
    private var viewH = 0

    private val minScale: Double
        get() = if (infinite) oneToOne * 0.1 else min(wholePageScale(), widthScale()) * 0.8
    private val maxScale: Double
        get() = max(oneToOne * 8, minScale)

    private fun widthScale() = (viewW - 2 * MARGIN) / pageW
    private fun wholePageScale() = min(widthScale(), (viewH - 2 * MARGIN) / pageH)

    private fun widthScale(fitW: Int) = (viewW - 2 * MARGIN) / fitW

    /**
     * Start position for a view of this size: a fixed page fits the width, a canvas starts at real size. When
     * this is a whole notebook's surface, [fitW] is the width of the page to fit (not the surface's).
     */
    fun fit(viewW: Int, viewH: Int, fitW: Int = pageW) {
        this.viewW = viewW
        this.viewH = viewH
        if (viewW <= 0 || viewH <= 0) return
        if (infinite) {
            scale = oneToOne
            tx = MARGIN * 2
            ty = MARGIN * 2
        } else {
            scale = widthScale(fitW)
            tx = MARGIN
            ty = MARGIN
        }
        clamp()
    }

    /** Takes the zoom and position of [other] (the same view of a surface that has since changed). */
    fun restore(other: PageGeometry) {
        scale = other.scale
        tx = other.tx
        ty = other.ty
        clamp()
    }

    /** Scrolls so the point (x, y) of the surface is at the top left of the screen, as far as the edges allow. */
    fun scrollTo(x: Double, y: Double) {
        tx = MARGIN - x * scale
        ty = MARGIN - y * scale
        clamp()
    }

    /** Moves by [dx] pixels sideways only, as far as the edges allow (the settling of a scroll). */
    fun slideBy(dx: Double) {
        tx += dx
        clamp()
    }

    fun zoomBy(factor: Double, focusX: Double, focusY: Double) {
        val next = (scale * factor).coerceIn(minScale, maxScale)
        val f = next / scale
        tx = focusX - (focusX - tx) * f
        ty = focusY - (focusY - ty) * f
        scale = next
        clamp()
    }

    /** Pans, as far as the page's edges allow; returns the part of (dx, dy) that could not be applied. */
    fun panBy(dx: Double, dy: Double): Pair<Double, Double> {
        val x0 = tx
        val y0 = ty
        tx += dx
        ty += dy
        clamp()
        return (dx - (tx - x0)) to (dy - (ty - y0))
    }

    private fun clamp() {
        scale = scale.coerceIn(minScale, max(maxScale, minScale))
        if (infinite) {
            // The view's top left corner may not go further than this from the origin.
            tx = tx.coerceIn(-LIMIT * scale, LIMIT * scale)
            ty = ty.coerceIn(-LIMIT * scale, LIMIT * scale)
        } else {
            tx = clampAxis(tx, pageW * scale, viewW)
            ty = clampAxis(ty, pageH * scale, viewH)
        }
    }

    private fun clampAxis(t: Double, size: Double, view: Int): Double =
        if (size + 2 * MARGIN <= view) (view - size) / 2 else t.coerceIn(view - size - MARGIN, MARGIN)

    fun toPageX(sx: Double) = (sx - tx) / scale
    fun toPageY(sy: Double) = (sy - ty) / scale
    fun toScreenX(px: Double) = px * scale + tx
    fun toScreenY(py: Double) = py * scale + ty

    /** What the view shows, in page units: left, top, right, bottom. */
    fun visible(): DoubleArray = doubleArrayOf(toPageX(0.0), toPageY(0.0), toPageX(viewW.toDouble()), toPageY(viewH.toDouble()))

    companion object {
        const val MARGIN = 24.0
        const val LIMIT = 100_000_000.0 // 1 km
        const val MIN_GAP_PX = 6.0

        /**
         * The template lines between [from] and [to] (page units): `offset + k * step`. Nothing when
         * they would sit closer than a few pixels, which would only be a grey smear.
         */
        fun lines(from: Double, to: Double, step: Int, offset: Int, scale: Double): List<Int> {
            if (step * scale < MIN_GAP_PX || to < from) return emptyList()
            val first = ceil((from - offset) / step).toLong()
            val last = floor((to - offset) / step).toLong()
            return (first..last).map { (offset + it * step).toInt() }
        }
    }
}
