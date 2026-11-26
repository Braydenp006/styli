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
    /**
     * For a surface of several pages: the width (or height) of the page at the start and at the end along each axis, so
     * the first and last page can scroll to the middle of the screen. A single page is both.
     */
    private val startW: Int = pageW,
    private val endW: Int = pageW,
    private val startH: Int = pageH,
    private val endH: Int = pageH,
) {
    var scale = oneToOne
        private set
    var tx = 0.0
        private set
    var ty = 0.0
        private set
    private var viewW = 0
    private var viewH = 0
    private var fitWidth = pageW // the width of the page [fit] fitted, which says what "zoomed out" means

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
        this.fitWidth = fitW
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

    /** The scale that [fit] gives: 100% in the zoom indicator. A fixed page fits its width, a canvas shows real size. */
    fun fitScale(fitW: Int = pageW): Double = if (infinite) oneToOne else widthScale(fitW)

    /** How far in the view is, as a percentage of [fitScale]: 100 is the page fitted to the width of the screen. */
    fun percent(fitW: Int = pageW): Int {
        if (viewW <= 0 || viewH <= 0) return 100
        val ref = fitScale(fitW)
        return if (ref <= 0.0) 100 else Math.round(scale / ref * 100).toInt()
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

    /** Sets the zoom and offset together, as far as the zoom limits and the edges allow (the zoom into a page animates through this). */
    fun viewTo(scale: Double, tx: Double, ty: Double) {
        this.scale = scale
        this.tx = tx
        this.ty = ty
        clamp()
    }

    /** Puts the surface's offset at ([tx], [ty]), as far as the edges allow (keeping a place on the screen when pages come or go). */
    fun offsetTo(tx: Double, ty: Double) {
        this.tx = tx
        this.ty = ty
        clamp()
    }

    /** Moves by [dx] pixels sideways and [dy] down, as far as the edges allow (the settling of a scroll). */
    fun slideBy(dx: Double, dy: Double = 0.0) {
        tx += dx
        ty += dy
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
            val free = freedom()
            tx = clampAxis(tx, pageW * scale, viewW, startW * scale, endW * scale, free)
            ty = clampAxis(ty, pageH * scale, viewH, startH * scale, endH * scale, free)
        }
    }

    /**
     * How loose the edges are: 0 for a page about fitted to the screen (90% or more of the zoom indicator) up to 1 when
     * zoomed out to 82% or less, changing smoothly between so nothing jumps while pinching.
     */
    private fun freedom(): Double {
        val ref = widthScale(fitWidth)
        return if (ref <= 0.0) 0.0 else ((0.9 - scale / ref) / 0.08).coerceIn(0.0, 1.0)
    }

    /**
     * Where the offset may be. Normally: a surface that fits the screen is centred; a bigger one scrolls until its first
     * (or last) page is at the margin, or in the middle if it is smaller than the screen. Zoomed out ([free] towards 1)
     * it may go on until only the first (or last) page is left, wholly on the screen at the far edge: one page always
     * stays in view, and there is room to move the pages about.
     */
    private fun clampAxis(t: Double, size: Double, view: Int, startPx: Double, endPx: Double, free: Double): Double {
        val lo: Double
        val hi: Double
        if (size + 2 * MARGIN <= view) { lo = (view - size) / 2; hi = lo } else {
            lo = view - size - max(MARGIN, (view - endPx) / 2); hi = max(MARGIN, (view - startPx) / 2)
        }
        val a = endPx - size
        val b = view - startPx
        return t.coerceIn(lo + (min(a, b) - lo) * free, hi + (max(a, b) - hi) * free)
    }

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
