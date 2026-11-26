package app.styli.ink

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class ChartKind { BAR, LINE, PIE, AREA, DONUT, RADAR, POLAR, FUNNEL, WATERFALL, COMBO, GAUGE, SPARKLINE, CANDLESTICK, SCATTER, BUBBLE, HISTOGRAM, BOXPLOT, HEATMAP, TREEMAP, GANTT }

/** How a line (or the top of an area) joins its points. */
enum class Curve { STRAIGHT, SMOOTH, STEP }

/**
 * One series: a name, a colour (0xAARRGGBB) and one value per category, in thousandths (12.5 is 12500). [missing] are
 * the categories it has no value for. In a combo chart [kind] says how it is drawn (bar, line or area) and [secondary]
 * puts it on the second axis; [trendline] draws a straight line of best fit through it.
 */
data class ChartSeries(
    val name: String, val color: Int, val values: List<Long>, val missing: Set<Int> = emptySet(),
    val kind: ChartKind = ChartKind.BAR, val secondary: Boolean = false, val trendline: Boolean = false,
    val xs: List<Long> = emptyList(), val sizes: List<Long> = emptyList(),
) {
    /** Scatter and bubble: where point [i] is along the bottom, or null if it has none (or no value). */
    fun xAt(i: Int): Double? = if (i in missing || i !in xs.indices) null else xs[i] / 1000.0

    /** Bubble: the size of point [i], or null. */
    fun sizeAt(i: Int): Double? = if (i in missing || i !in sizes.indices) null else sizes[i] / 1000.0

    /** The value in category [i] as a number, or null if it has none. */
    fun at(i: Int): Double? = if (i in missing || i !in values.indices) null else values[i] / 1000.0
}

/** Where a chart's data came from, if it follows a table (see the format): a block of the table's cells. */
data class ChartSource(val table: String, val row: Int, val col: Int, val rows: Int, val cols: Int, val seriesInRows: Boolean)

/**
 * A chart on a page, drawn from its data. Like a shape it is one whole object (box, angle, data, look) that never
 * changes once made; an edit makes a copy. The box is in page units, turned by [angle] radians about its top left
 * corner. A [fontSize] of zero means "a size that suits the box" (see [textSize]). The options that only some kinds
 * use ([horizontal], [percent], [curve], [hideMarkers], [hole], [filled], [threeD], [totals], [showValues], the gauge
 * range, [pyramid]) are ignored by the others.
 */
data class Chart(
    val id: String,
    val kind: ChartKind,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val title: String = "",
    val categories: List<String> = emptyList(),
    val series: List<ChartSeries> = emptyList(),
    val xLabel: String = "",
    val yLabel: String = "",
    val angle: Float = 0f,
    val hideLegend: Boolean = false,
    val hideGrid: Boolean = false,
    val stacked: Boolean = false,
    val textColor: Int = 0,
    val fontSize: Float = 0f,
    val source: ChartSource? = null,
    val horizontal: Boolean = false,
    val percent: Boolean = false,
    val curve: Curve = Curve.STRAIGHT,
    val hideMarkers: Boolean = false,
    val hole: Int = 0,
    val filled: Boolean = false,
    val threeD: Boolean = false,
    val totals: Set<Int> = emptySet(),
    val showValues: Boolean = false,
    val gaugeMin: Long = 0,
    val gaugeMax: Long = 0,
    val pyramid: Boolean = false,
    val dateAxis: Boolean = false,
    val groups: List<String> = emptyList(),
    val bins: Int = 0,
    val binWidth: Long = 0,
    val hideOutliers: Boolean = false,
    val showMean: Boolean = false,
    val marker: Long = 0,
    val heatLow: Int = 0,
    val heatHigh: Int = 0,
) {
    /** The base text size: [fontSize], or a twenty-fourth of the box's height. */
    val textSize: Float get() = if (fontSize > 0f) fontSize else height / 24f

    /** The donut's hole as a fraction of the radius. */
    val holeFraction: Double get() = (if (hole in 1..90) hole else 55) / 100.0

    /** The gauge's dial range (0 to 100 unless set sensibly). */
    val gaugeRange: Pair<Double, Double> get() = if (gaugeMax > gaugeMin) gaugeMin / 1000.0 to gaugeMax / 1000.0 else 0.0 to 100.0

    fun toPage(lx: Float, ly: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        return (x + lx * c - ly * s) to (y + lx * s + ly * c)
    }

    fun toLocal(px: Float, py: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        val dx = px - x; val dy = py - y
        return (dx * c + dy * s) to (-dx * s + dy * c)
    }

    fun contains(px: Float, py: Float): Boolean = toLocal(px, py).let { (lx, ly) -> lx in 0f..width && ly in 0f..height }

    fun corners() = listOf(toPage(0f, 0f), toPage(width, 0f), toPage(width, height), toPage(0f, height))

    /** The chart after a move, resize or turn by [map] (a move, a uniform scale and a turn, as the lasso makes); its text grows with it. */
    fun moved(map: Affine): Chart {
        val scale = sqrt(abs(map.a * map.d - map.b * map.c)).toFloat()
        val turn = kotlin.math.atan2(map.b, map.a).toFloat()
        return copy(x = map.x(x, y), y = map.y(x, y), width = width * scale, height = height * scale, angle = angle + turn, fontSize = fontSize * scale)
    }

    /** How series [s] is drawn: its own kind in a combo, otherwise what the chart is. */
    fun kindOf(s: ChartSeries): ChartKind = if (kind == ChartKind.COMBO) s.kind else kind

    companion object {
        /** Throwaway colours until the design system has a chart palette. */
        val PALETTE = listOf(0xFF1565C0, 0xFFC62828, 0xFF2E7D32, 0xFFEF6C00, 0xFF4527A0, 0xFF00838F, 0xFFAD1457, 0xFF616161).map { it.toInt() }

        /** The colour for series (or pie slice) number [i] when none was chosen. */
        fun colourFor(i: Int) = PALETTE[i % PALETTE.size]

        /** Rising and falling (waterfall steps, candles), and totals. */
        const val UP = 0xFF2E7D32.toInt()
        const val DOWN = 0xFFC62828.toInt()
        const val TOTAL = 0xFF1565C0.toInt()
    }
}

/** The numbers behind drawing a chart: axis scales, where bars, points, slices and the rest go. No Android, so it is tested on the JVM. */
object ChartMaths {
    /** An axis from [lo] to [hi] in steps of [step]. */
    data class Scale(val lo: Double, val hi: Double, val step: Double) {
        val ticks: List<Double> get() = (0..Math.round((hi - lo) / step).toInt()).map { lo + it * step }
        fun fraction(v: Double) = if (hi == lo) 0.0 else (v - lo) / (hi - lo)
    }

    /** The "nice" number at or above [x]: 1, 2, 5 or 10 times a power of ten. */
    private fun nice(x: Double): Double {
        val e = floor(log10(x))
        val f = x / 10.0.pow(e)
        return (if (f <= 1.0) 1.0 else if (f <= 2.0) 2.0 else if (f <= 5.0) 5.0 else 10.0) * 10.0.pow(e)
    }

    /** A scale that covers [min] to [max] with round steps and about [maxTicks] marks; [zero] makes it include zero (bars do). */
    fun niceScale(min: Double, max: Double, maxTicks: Int = 6, zero: Boolean = true): Scale {
        var lo = if (zero) min(min, 0.0) else min
        var hi = if (zero) max(max, 0.0) else max
        if (hi - lo < 1e-12) { lo -= 1.0; hi += 1.0 } // all the same number (or none): a scale with room round it
        val step = nice((hi - lo) / max(1, maxTicks - 1))
        return Scale(floor(lo / step + 1e-9) * step, ceil(hi / step - 1e-9) * step, step)
    }

    /** Kinds drawn against a value axis (with numbers down one side and category names along the other). */
    fun hasAxes(k: ChartKind) = k != ChartKind.PIE && k != ChartKind.DONUT && k != ChartKind.RADAR && k != ChartKind.POLAR && k != ChartKind.FUNNEL && k != ChartKind.GAUGE && k != ChartKind.SPARKLINE && k != ChartKind.TREEMAP

    /**
     * The chart as its values are drawn: a 100% stack turns each category's values into shares of 100, and a population
     * pyramid puts the first series to the left of zero. Everything else is as it was.
     */
    fun effective(c: Chart): Chart {
        var e = c
        if (c.percent && (c.kind == ChartKind.BAR || c.kind == ChartKind.AREA)) {
            val n = c.categories.size
            val totals = (0 until n).map { i -> c.series.sumOf { s -> s.at(i)?.let { v -> abs(v) } ?: 0.0 } }
            e = e.copy(stacked = true, series = c.series.map { s ->
                s.copy(values = (0 until n).map { i -> if (totals[i] == 0.0) 0L else Math.round((s.at(i) ?: 0.0) / totals[i] * 100000.0) })
            })
        }
        if (c.pyramid && c.kind == ChartKind.BAR) {
            e = e.copy(horizontal = true, stacked = true, series = e.series.mapIndexed { i, s -> if (i == 0) s.copy(values = s.values.map { -abs(it) }) else s.copy(values = s.values.map { abs(it) }) })
        }
        return e
    }

    private fun stackable(c: Chart, s: ChartSeries) = c.stacked && (c.kindOf(s) == ChartKind.BAR || c.kindOf(s) == ChartKind.AREA) &&
        !(c.kind == ChartKind.COMBO && c.series.any { it.kind == ChartKind.AREA } && c.series.any { it.kind == ChartKind.BAR })

    /** The running totals of a waterfall: for each category the level before and after it (a total starts at zero and ends at the running level). */
    fun waterfallLevels(c: Chart): List<Pair<Double, Double>> {
        val s = c.series.firstOrNull() ?: return emptyList()
        var run = 0.0
        return (0 until c.categories.size).map { i ->
            if (i in c.totals) 0.0 to run
            else { val from = run; run += s.at(i) ?: 0.0; from to run }
        }
    }

    /** The smallest and largest value the (primary or [secondary]) axis must show. Null if there is no value at all. */
    fun valueRange(c0: Chart, secondary: Boolean = false): Pair<Double, Double>? {
        val c = effective(c0)
        val n = c.categories.size
        if (c.kind == ChartKind.WATERFALL) {
            val lv = waterfallLevels(c)
            return if (lv.isEmpty()) null else min(0.0, lv.minOf { min(it.first, it.second) }) to max(0.0, lv.maxOf { max(it.first, it.second) })
        }
        val ser = c.series.filter { c.kind != ChartKind.COMBO || it.secondary == secondary }
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; var any = false
        for (i in 0 until n) {
            var pos = 0.0; var neg = 0.0; var stack = false
            for (s in ser) {
                val v = s.at(i) ?: continue
                any = true
                if (stackable(c, s)) { stack = true; if (v >= 0) pos += v else neg += v } else { lo = min(lo, v); hi = max(hi, v) }
            }
            if (stack) { lo = min(lo, min(neg, 0.0)); hi = max(hi, max(pos, 0.0)) }
        }
        return if (any) lo to hi else null
    }

    /** The axis for [c] (bars, areas and waterfalls always include zero, lines need not); a 100% stack runs 0 to 100. */
    fun scaleFor(c: Chart, secondary: Boolean = false): Scale {
        if (c.percent && (c.kind == ChartKind.BAR || c.kind == ChartKind.AREA)) return niceScale(0.0, 100.0)
        val (lo, hi) = valueRange(c, secondary) ?: (0.0 to 1.0)
        val zero = when (c.kind) {
            ChartKind.BAR, ChartKind.AREA, ChartKind.WATERFALL -> true
            ChartKind.COMBO -> true // both axes start at zero, so a line is not made to look bigger than it is
            else -> false
        }
        return niceScale(lo, hi, zero = zero)
    }

    /** Where things sit inside the chart's box, in its own frame (origin at the box's top left). */
    class Layout(val plot: Box, val title: Box?, val legend: Box?, val xLabel: Box?, val yLabel: Box?)

    /** The names the legend lists (empty for the kinds that have none). */
    fun legendNames(c: Chart): List<String> = when (c.kind) {
        ChartKind.PIE, ChartKind.DONUT, ChartKind.POLAR, ChartKind.FUNNEL -> c.categories
        ChartKind.GAUGE, ChartKind.WATERFALL, ChartKind.CANDLESTICK, ChartKind.SPARKLINE, ChartKind.HISTOGRAM, ChartKind.BOXPLOT, ChartKind.HEATMAP, ChartKind.GANTT -> emptyList()
        ChartKind.TREEMAP -> c.groups.filter { it.isNotBlank() }.distinct()
        else -> c.series.map { it.name }
    }

    /** Lays the parts out for a box of [w] by [h] with text [font] high: a title on top, the legend and axis labels underneath, numbers down the sides. */
    fun layout(c: Chart, w: Double = c.width.toDouble(), h: Double = c.height.toDouble()): Layout {
        val f = c.textSize.toDouble()
        val pad = f * 0.6
        val d3 = if (c.threeD) f * 0.9 else 0.0 // room for the depth drawn up and to the right
        if (c.kind == ChartKind.SPARKLINE) return Layout(Box(pad, pad + d3, max(pad + 1, w - pad - d3), max(pad + 1, h - pad)), null, null, null, null)
        var top = pad; var bottom = h - pad; var left = pad; var right = w - pad
        val title = if (c.title.isNotBlank()) Box(pad, top, right, top + f * 1.8).also { top += f * 2.0 } else null
        val showLegend = !c.hideLegend && legendNames(c).isNotEmpty()
        val legend = if (showLegend) Box(pad, bottom - f * 1.6, right, bottom).also { bottom -= f * 1.9 } else null
        val axes = hasAxes(c.kind)
        val xLabel = if (axes && c.xLabel.isNotBlank()) Box(left, bottom - f * 1.4, right, bottom).also { bottom -= f * 1.6 } else null
        val yLabel = if (axes && c.yLabel.isNotBlank()) Box(pad, top, pad + f * 1.4, bottom).also { left += f * 1.6 } else null
        if (c.threeD && (axes || c.kind == ChartKind.RADAR || c.kind == ChartKind.FUNNEL || c.kind == ChartKind.TREEMAP)) { top += d3; right -= d3 }
        if (axes) {
            val sideBars = (c.horizontal && c.kind == ChartKind.BAR) || c.kind == ChartKind.GANTT || c.kind == ChartKind.HEATMAP
            left += if (sideBars) min(w * 0.3, (c.categories.maxOfOrNull { it.length } ?: 1) * f * 0.5 + f * 0.6) else f * 3.6
            bottom -= f * 1.6 // the category names (or, for sideways bars, the numbers) underneath
            if (c.kind == ChartKind.COMBO && c.series.any { it.secondary }) right -= f * 3.6
        }
        return Layout(Box(left, top, max(left + 1.0, right), max(top + 1.0, bottom)), title, legend, xLabel, yLabel)
    }

    /** y in the box's frame for the value [v] on [scale], with the plot [p] (higher values are further up). */
    fun yOf(p: Box, scale: Scale, v: Double) = p.b - scale.fraction(v) * (p.b - p.t)

    /** x for the value [v] when the value axis runs sideways. */
    fun xOf(p: Box, scale: Scale, v: Double) = p.l + scale.fraction(v) * (p.r - p.l)

    /**
     * For each series and category, the bar's box in the chart's frame (null where the series has no value or is not a bar).
     * Side by side, or stacked from zero (negatives down, or to the left); sideways bars put the first category at the top.
     * A combo's bars on the second axis use [scale2].
     */
    fun bars(c0: Chart, p: Box, scale: Scale, scale2: Scale = scale): List<List<Box?>> {
        val c = effective(c0)
        val n = c.categories.size
        val m = c.series.size
        val out = List(m) { arrayOfNulls<Box>(n) }
        val barIdx = (0 until m).filter { c.kindOf(c.series[it]) == ChartKind.BAR }
        if (n == 0 || barIdx.isEmpty()) return out.map { it.toList() }
        val side = c.horizontal && c.kind == ChartKind.BAR
        val slot = (if (side) p.b - p.t else p.r - p.l) / n
        val inner = slot * 0.8
        val stack = c.stacked && !(c.kind == ChartKind.COMBO && c.series.any { it.kind == ChartKind.AREA })
        fun pos(sc: Scale, v: Double) = if (side) xOf(p, sc, v) else yOf(p, sc, v)
        fun rect(a0: Double, a1: Double, va: Double, vb: Double) =
            if (side) Box(min(va, vb), a0, max(va, vb), a1) else Box(a0, min(va, vb), a1, max(va, vb))
        for (i in 0 until n) {
            val start = (if (side) p.t else p.l) + slot * i + (slot - inner) / 2
            var posSum = 0.0; var negSum = 0.0
            for ((k, j) in barIdx.withIndex()) {
                val s = c.series[j]
                val sc = if (s.secondary) scale2 else scale
                val v = s.at(i) ?: continue
                if (stack) {
                    val from = if (v >= 0) posSum else negSum
                    val to = from + v
                    if (v >= 0) posSum = to else negSum = to
                    out[j][i] = rect(start, start + inner, pos(sc, from), pos(sc, to))
                } else {
                    val w = inner / barIdx.size
                    out[j][i] = rect(start + w * k, start + w * (k + 1), pos(sc, 0.0), pos(sc, v))
                }
            }
        }
        return out.map { it.toList() }
    }

    /** One waterfall bar: its box, and whether it goes up, goes down or is a total. */
    data class StepBar(val box: Box, val kind: Int, val level: Double) { companion object { const val UP = 0; const val DOWN = 1; const val TOTAL = 2 } }

    /** The waterfall's bars in category order: steps float from the running level, totals stand on zero. */
    fun waterfall(c: Chart, p: Box, scale: Scale): List<StepBar> {
        val n = c.categories.size
        if (n == 0) return emptyList()
        val slot = (p.r - p.l) / n
        val inner = slot * 0.8
        val s = c.series.firstOrNull()
        return waterfallLevels(c).mapIndexed { i, (from, to) ->
            val left = p.l + slot * i + (slot - inner) / 2
            val ya = yOf(p, scale, from); val yb = yOf(p, scale, to)
            val kind = if (i in c.totals) StepBar.TOTAL else if ((s?.at(i) ?: 0.0) >= 0) StepBar.UP else StepBar.DOWN
            StepBar(Box(left, min(ya, yb), left + inner, max(ya, yb)), kind, to)
        }
    }

    /** For each series, its point in every category (the middle of the slot, null where it has no value), in the chart's frame. */
    fun linePoints(c0: Chart, p: Box, scale: Scale, scale2: Scale = scale): List<List<Pair<Double, Double>?>> {
        val c = effective(c0)
        val n = c.categories.size
        val slot = if (n == 0) 0.0 else (p.r - p.l) / n
        return c.series.map { s ->
            val sc = if (s.secondary) scale2 else scale
            (0 until n).map { i -> s.at(i)?.let { (p.l + slot * (i + 0.5)) to yOf(p, sc, it) } }
        }
    }

    /** The shape of an area: its top edge and the edge it sits on, one point per category (null where there is none). */
    class Band(val top: List<Pair<Double, Double>?>, val bottom: List<Pair<Double, Double>?>)

    /** For each series drawn as an area, its band: from zero, or stacked on the series below it. Other series get null. */
    fun areas(c0: Chart, p: Box, scale: Scale, scale2: Scale = scale): List<Band?> {
        val c = effective(c0)
        val n = c.categories.size
        val slot = if (n == 0) 0.0 else (p.r - p.l) / n
        val stack = c.stacked && c.series.count { c.kindOf(it) == ChartKind.AREA } > 0 && !(c.kind == ChartKind.COMBO)
        val base = DoubleArray(n)
        return c.series.map { s ->
            if (c.kindOf(s) != ChartKind.AREA) return@map null
            val sc = if (s.secondary) scale2 else scale
            val top = ArrayList<Pair<Double, Double>?>(); val bottom = ArrayList<Pair<Double, Double>?>()
            for (i in 0 until n) {
                val x = p.l + slot * (i + 0.5)
                val v = s.at(i)
                if (stack) {
                    val v0 = v ?: 0.0
                    bottom.add(x to yOf(p, sc, base[i])); base[i] += v0; top.add(x to yOf(p, sc, base[i]))
                } else {
                    bottom.add(if (v == null) null else x to yOf(p, sc, 0.0)); top.add(if (v == null) null else x to yOf(p, sc, v))
                }
            }
            Band(top, bottom)
        }
    }

    /** A cubic piece of a smooth curve. */
    data class Seg(val x0: Double, val y0: Double, val c1x: Double, val c1y: Double, val c2x: Double, val c2y: Double, val x1: Double, val y1: Double)

    /** A smooth curve through [pts] (Catmull-Rom, so it passes through every point and does not loop back on itself). */
    fun smooth(pts: List<Pair<Double, Double>>): List<Seg> = (0 until pts.size - 1).map { i ->
        val p0 = pts[max(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[min(pts.size - 1, i + 2)]
        Seg(p1.first, p1.second, p1.first + (p2.first - p0.first) / 6, p1.second + (p2.second - p0.second) / 6,
            p2.first - (p3.first - p1.first) / 6, p2.second - (p3.second - p1.second) / 6, p2.first, p2.second)
    }

    /** [pts] as a stepped line: across to the next x at the old height, then up or down. */
    fun steps(pts: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        pts.flatMapIndexed { i, q -> if (i == 0) listOf(q) else listOf(q.first to pts[i - 1].second, q) }

    /** The straight line of best fit through the series' values against the category numbers 0, 1, 2 ...: (slope, intercept). Null with fewer than two values. */
    fun trend(s: ChartSeries, n: Int): Pair<Double, Double>? {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in 0 until n) s.at(i)?.let { xs.add(i.toDouble()); ys.add(it) }
        if (xs.size < 2) return null
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        return slope to (my - slope * mx)
    }

    /** The two ends of the trendline of series [s] across the plot, in the chart's frame. */
    fun trendEnds(c: Chart, s: ChartSeries, p: Box, scale: Scale): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        val n = c.categories.size
        val (m, b) = trend(s, n) ?: return null
        val slot = (p.r - p.l) / n
        return (p.l + slot * 0.5 to yOf(p, scale, b)) to (p.l + slot * (n - 0.5) to yOf(p, scale, b + m * (n - 1)))
    }

    /** A candle: where its middle is, the open, close, high and low heights, and whether it closed at or above where it opened. */
    data class Candle(val x: Double, val open: Double, val high: Double, val low: Double, val close: Double, val up: Boolean, val width: Double)

    /** The candles from the series in order open, high, low, close; a category missing any of the four has none. */
    fun candles(c: Chart, p: Box, scale: Scale): List<Candle?> {
        val n = c.categories.size
        if (c.series.size < 4 || n == 0) return List(n) { null }
        val slot = (p.r - p.l) / n
        return (0 until n).map { i ->
            val v = (0..3).map { c.series[it].at(i) }
            if (v.any { it == null }) null
            else Candle(p.l + slot * (i + 0.5), yOf(p, scale, v[0]!!), yOf(p, scale, v[1]!!), yOf(p, scale, v[2]!!), yOf(p, scale, v[3]!!), v[3]!! >= v[0]!!, slot * 0.5)
        }
    }

    /** A pie slice: the category it is, where it starts and how far it sweeps (radians clockwise from the top), and its share of the whole. */
    data class Slice(val index: Int, val start: Double, val sweep: Double, val share: Double)

    /** The slices of the first series: only positive values count; blank and negative ones get no slice. Empty if the total is zero. */
    fun pie(c: Chart): List<Slice> {
        val s = c.series.firstOrNull() ?: return emptyList()
        val vals = (0 until c.categories.size).map { s.at(it)?.takeIf { v -> v > 0 } ?: 0.0 }
        val total = vals.sum()
        if (total <= 0.0) return emptyList()
        var a = -Math.PI / 2
        return vals.mapIndexedNotNull { i, v ->
            if (v <= 0.0) null else Slice(i, a, v / total * 2 * Math.PI, v / total).also { a += it.sweep }
        }
    }

    /** A polar-area wedge: category [index], its start and sweep (every wedge the same), and its radius as a fraction of the biggest. */
    data class Wedge(val index: Int, val start: Double, val sweep: Double, val radius: Double)

    fun polar(c: Chart): List<Wedge> {
        val s = c.series.firstOrNull() ?: return emptyList()
        val n = c.categories.size
        if (n == 0) return emptyList()
        val big = (0 until n).maxOfOrNull { s.at(it) ?: 0.0 } ?: 0.0
        if (big <= 0.0) return emptyList()
        val sweep = 2 * Math.PI / n
        return (0 until n).map { i -> Wedge(i, -Math.PI / 2 + sweep * i, sweep, max(0.0, (s.at(i) ?: 0.0)) / big) }
    }

    /** A radar's points: for each series, a point on every category's spoke at the value's distance from the middle ([r] is the rim). Missing values sit at the middle. */
    fun radar(c: Chart, cx: Double, cy: Double, r: Double): Pair<Scale, List<List<Pair<Double, Double>>>> {
        val n = c.categories.size
        val (lo, hi) = valueRange(c) ?: (0.0 to 1.0)
        val scale = niceScale(lo, hi, 5, zero = true)
        val pts = c.series.map { s ->
            (0 until n).map { i ->
                val a = -Math.PI / 2 + 2 * Math.PI * i / n
                val d = r * scale.fraction(s.at(i) ?: scale.lo)
                (cx + d * cos(a)) to (cy + d * sin(a))
            }
        }
        return scale to pts
    }

    /** One funnel stage: its category, the vertical span, and its width at the top and the bottom (the next stage's width). */
    data class Stage(val index: Int, val top: Double, val bottom: Double, val topW: Double, val bottomW: Double)

    fun funnel(c: Chart, p: Box): List<Stage> {
        val s = c.series.firstOrNull() ?: return emptyList()
        val n = c.categories.size
        val vals = (0 until n).map { max(0.0, s.at(it) ?: 0.0) }
        val big = vals.maxOrNull() ?: 0.0
        if (n == 0 || big <= 0.0) return emptyList()
        val w = p.r - p.l
        val h = (p.b - p.t) / n
        return vals.mapIndexed { i, v ->
            val next = if (i + 1 < n) vals[i + 1] else v * 0.7
            Stage(i, p.t + h * i, p.t + h * (i + 1) - h * 0.06, w * v / big, w * next / big)
        }
    }

    /** The gauge: the value, where it sits on the dial (0 to 1), and where the target is (null if there is none). */
    data class Dial(val value: Double, val fraction: Double, val target: Double?)

    fun gauge(c: Chart): Dial? {
        val s = c.series.firstOrNull() ?: return null
        val v = s.at(0) ?: return null
        val (lo, hi) = c.gaugeRange
        val f = { x: Double -> ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0) }
        return Dial(v, f(v), s.at(1)?.let(f))
    }

    /** A Pareto chart from [c]: the categories sorted biggest first, the first series as bars and a line of the running share (0 to 100%) on the second axis. */
    fun pareto(c: Chart): Chart {
        val s = c.series.firstOrNull() ?: return c
        val n = c.categories.size
        val order = (0 until n).sortedByDescending { s.at(it) ?: 0.0 }
        val sorted = order.map { s.at(it) ?: 0.0 }
        val total = sorted.sum()
        var run = 0.0
        val cum = sorted.map { run += it; if (total == 0.0) 0L else Math.round(run / total * 100000.0) }
        return c.copy(
            kind = ChartKind.COMBO, categories = order.map { c.categories[it] },
            series = listOf(
                s.copy(values = sorted.map { Math.round(it * 1000) }, missing = emptySet(), kind = ChartKind.BAR, secondary = false),
                ChartSeries("Running share (%)", Chart.colourFor(1), cum, kind = ChartKind.LINE, secondary = true),
            ),
        )
    }

    // ---- scatter and bubble ----

    /** A point of a scatter or bubble chart in the chart's frame: where it is, how big it is drawn, and which point of its series it is. */
    data class Dot(val x: Double, val y: Double, val r: Double, val index: Int)

    /** The scales along the bottom and up the side for the points (neither needs to include zero). */
    fun scatterScales(c: Chart): Pair<Scale, Scale> {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (s in c.series) for (i in s.values.indices) { val x = s.xAt(i); val y = s.at(i); if (x != null && y != null) { xs.add(x); ys.add(y) } }
        if (xs.isEmpty()) return niceScale(0.0, 1.0, zero = false) to niceScale(0.0, 1.0, zero = false)
        // a little room round the points, so none sits on an axis
        fun padded(v: List<Double>): Scale { val pad = (v.max() - v.min()).takeIf { it > 0 }?.times(0.08) ?: 1.0; return niceScale(v.min() - pad, v.max() + pad, zero = false) }
        return padded(xs) to padded(ys)
    }

    /** For each series, its points (null where one is incomplete). A bubble's radius follows the square root of its size, so the area follows the size. */
    fun dots(c: Chart, p: Box, sx: Scale, sy: Scale): List<List<Dot?>> {
        val big = if (c.kind == ChartKind.BUBBLE) c.series.flatMap { s -> s.values.indices.mapNotNull { s.sizeAt(it) } }.maxOrNull()?.takeIf { it > 0 } else null
        val maxR = min(p.r - p.l, p.b - p.t) * 0.09
        return c.series.map { s ->
            s.values.indices.map { i ->
                val x = s.xAt(i); val y = s.at(i)
                if (x == null || y == null) null else {
                    val r = if (big == null) 0.0 else maxR * sqrt(max(0.0, s.sizeAt(i) ?: 0.0) / big)
                    Dot(p.l + sx.fraction(x) * (p.r - p.l), yOf(p, sy, y), r, i)
                }
            }
        }
    }

    /** The line of best fit through a series' points, y = slope * x + intercept, and how well it fits (r squared, 0 to 1). Null with fewer than two points or no spread along x. */
    fun trendXY(s: ChartSeries): Triple<Double, Double, Double>? {
        val pts = s.values.indices.mapNotNull { i -> val x = s.xAt(i); val y = s.at(i); if (x != null && y != null) x to y else null }
        if (pts.size < 2) return null
        val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx == 0.0) return null
        val sxy = pts.sumOf { (it.first - mx) * (it.second - my) }
        val syy = pts.sumOf { (it.second - my) * (it.second - my) }
        val slope = sxy / sxx
        return Triple(slope, my - slope * mx, if (syy == 0.0) 1.0 else sxy * sxy / (sxx * syy))
    }

    /** The two ends of a scatter series' trendline across the span of its own points. */
    fun trendXYEnds(s: ChartSeries, p: Box, sx: Scale, sy: Scale): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        val (m, b, _) = trendXY(s) ?: return null
        val xs = s.values.indices.mapNotNull { i -> if (s.at(i) != null) s.xAt(i) else null }
        val x0 = xs.min(); val x1 = xs.max()
        fun pt(x: Double) = (p.l + sx.fraction(x) * (p.r - p.l)) to yOf(p, sy, m * x + b)
        return pt(x0) to pt(x1)
    }

    // ---- histogram ----

    /** The bin edges (one more than the counts) and how many samples fall in each bin. */
    class Hist(val edges: List<Double>, val counts: List<Int>)

    /** The first series' samples counted into equal bins: a set number, a set width, or (neither) about log2(n)+1 bins. The last bin includes the largest sample. Null with no samples. */
    fun histogram(c: Chart): Hist? {
        val s = c.series.firstOrNull() ?: return null
        val v = s.values.indices.mapNotNull { s.at(it) }
        if (v.isEmpty()) return null
        val lo = v.min(); val hi = v.max()
        if (hi == lo) return Hist(listOf(lo - 0.5, lo + 0.5), listOf(v.size))
        val start: Double; val w: Double; val n: Int
        if (c.binWidth > 0) {
            w = c.binWidth / 1000.0
            start = floor(lo / w) * w
            n = min(200, max(1, ceil((hi - start) / w - 1e-9).toInt()))
        } else if (c.bins > 0) { // the number asked for: equal bins from the smallest sample to the largest
            n = c.bins.coerceIn(1, 200)
            start = lo
            w = (hi - lo) / n
        } else { // about log2(n)+1 bins, on round edges so the axis reads easily
            w = nice((hi - lo) / ceil(log2(v.size.toDouble()) + 1))
            start = floor(lo / w + 1e-9) * w
            n = min(200, max(1, ceil((hi - start) / w - 1e-9).toInt()))
        }
        val counts = IntArray(n)
        for (x in v) counts[min(n - 1, floor((x - start) / w).toInt().coerceAtLeast(0))]++
        return Hist(List(n + 1) { start + w * it }, counts.toList())
    }

    // ---- box plot ----

    /** The numbers a box plot draws for one set of samples. */
    data class BoxStats(val n: Int, val q1: Double, val median: Double, val q3: Double, val lowWhisker: Double, val highWhisker: Double, val mean: Double, val outliers: List<Double>)

    /** The value at fraction [t] (0 to 1) of the way through the sorted samples, interpolating between neighbours. */
    private fun quantile(sorted: List<Double>, t: Double): Double {
        val pos = t * (sorted.size - 1)
        val i = floor(pos).toInt()
        return if (i + 1 >= sorted.size) sorted.last() else sorted[i] + (pos - i) * (sorted[i + 1] - sorted[i])
    }

    /** Quartiles by interpolation; whiskers reach the furthest sample within 1.5 box lengths of the box, the rest are outliers. Null with no samples. */
    fun boxStats(s: ChartSeries): BoxStats? {
        val v = s.values.indices.mapNotNull { s.at(it) }.sorted()
        if (v.isEmpty()) return null
        val q1 = quantile(v, 0.25); val q3 = quantile(v, 0.75)
        val fence = 1.5 * (q3 - q1)
        val inside = v.filter { it >= q1 - fence && it <= q3 + fence }
        return BoxStats(v.size, q1, quantile(v, 0.5), q3, inside.min(), inside.max(), v.average(), v.filter { it < q1 - fence || it > q3 + fence })
    }

    /** The axis for a box plot: all the samples, outliers too. */
    fun boxScale(c: Chart): Scale {
        val all = c.series.flatMap { s -> s.values.indices.mapNotNull { s.at(it) } }
        return if (all.isEmpty()) niceScale(0.0, 1.0, zero = false) else niceScale(all.min(), all.max(), zero = false)
    }

    /** One drawn box: its middle and width, and the heights of the whiskers, quartiles, median and mean, and each outlier. */
    class BoxShape(val x: Double, val w: Double, val low: Double, val q1: Double, val median: Double, val q3: Double, val high: Double, val mean: Double, val outliers: List<Double>)

    fun boxes(c: Chart, p: Box, scale: Scale): List<BoxShape?> {
        val m = c.series.size
        if (m == 0) return emptyList()
        val slot = (p.r - p.l) / m
        return c.series.mapIndexed { j, s ->
            val b = boxStats(s) ?: return@mapIndexed null
            BoxShape(p.l + slot * (j + 0.5), slot * 0.4, yOf(p, scale, b.lowWhisker), yOf(p, scale, b.q1), yOf(p, scale, b.median), yOf(p, scale, b.q3),
                yOf(p, scale, b.highWhisker), yOf(p, scale, b.mean), b.outliers.map { yOf(p, scale, it) })
        }
    }

    // ---- heatmap ----

    private const val HEAT_LOW = 0xFFE3F2FD.toInt()
    private const val HEAT_HIGH = 0xFF0D47A1.toInt()

    /** The colour [t] (0 to 1) of the way from [a] to [b], channel by channel. */
    fun lerp(a: Int, b: Int, t: Double): Int {
        fun ch(sh: Int) = Math.round(((a ushr sh) and 0xFF) + (((b ushr sh) and 0xFF) - ((a ushr sh) and 0xFF)) * t.coerceIn(0.0, 1.0)).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun heatColour(c: Chart, t: Double) = lerp(if (c.heatLow ushr 24 == 0) HEAT_LOW else c.heatLow, if (c.heatHigh ushr 24 == 0) HEAT_HIGH else c.heatHigh, t)

    /** A heatmap cell: where it is, its value (null if blank) and how far along the colour ramp it is (0 to 1). */
    class HeatCell(val row: Int, val col: Int, val box: Box, val value: Double?, val t: Double)

    /** The cells: a row for each category, a column for each series, with the smallest value at one end of the ramp and the largest at the other. */
    fun heatCells(c: Chart, p: Box): List<HeatCell> {
        val rows = c.categories.size; val cols = c.series.size
        if (rows == 0 || cols == 0) return emptyList()
        val all = c.series.flatMap { s -> (0 until rows).mapNotNull { s.at(it) } }
        val lo = all.minOrNull() ?: 0.0; val hi = all.maxOrNull() ?: 0.0
        val cw = (p.r - p.l) / cols; val ch = (p.b - p.t) / rows
        val gap = min(cw, ch) * 0.04
        return (0 until rows).flatMap { r -> (0 until cols).map { k ->
            val v = c.series[k].at(r)
            HeatCell(r, k, Box(p.l + cw * k + gap, p.t + ch * r + gap, p.l + cw * (k + 1) - gap, p.t + ch * (r + 1) - gap), v, if (v == null || hi == lo) 0.0 else (v - lo) / (hi - lo))
        } }
    }

    // ---- treemap ----

    /** One tile: which category it is, where, and which group (index in order of first appearance, or -1 for none). */
    class Tile(val index: Int, val box: Box, val group: Int)

    /** Squarified layout: [items] (index, size) biggest first into [box], rows laid along the shorter side so the tiles stay close to square. Tile areas are in proportion to the sizes. */
    private fun squarify(items: List<Pair<Int, Double>>, box: Box): List<Pair<Int, Box>> {
        val total = items.sumOf { it.second }
        if (items.isEmpty() || total <= 0.0) return emptyList()
        val out = ArrayList<Pair<Int, Box>>()
        var rem = box
        val unit = (box.r - box.l) * (box.b - box.t) / total // area per unit of size
        var k = 0
        fun worst(row: List<Pair<Int, Double>>, side: Double): Double {
            val a = row.sumOf { it.second } * unit
            return row.maxOf { max(side * side * it.second * unit / (a * a), a * a / (side * side * it.second * unit)) }
        }
        while (k < items.size) {
            val w = rem.r - rem.l; val h = rem.b - rem.t
            val side = min(w, h)
            val row = ArrayList<Pair<Int, Double>>()
            while (k < items.size && (row.isEmpty() || worst(row + items[k], side) <= worst(row, side))) row.add(items[k++])
            val a = row.sumOf { it.second } * unit
            if (w >= h) { // a column on the left
                val cw = if (h > 0) a / h else 0.0
                var y = rem.t
                for ((i, sz) in row) { val rh = if (a > 0) h * sz * unit / a else 0.0; out.add(i to Box(rem.l, y, rem.l + cw, y + rh)); y += rh }
                rem = Box(rem.l + cw, rem.t, rem.r, rem.b)
            } else { // a strip along the top
                val rh = if (w > 0) a / w else 0.0
                var x = rem.l
                for ((i, sz) in row) { val rw = if (a > 0) w * sz * unit / a else 0.0; out.add(i to Box(x, rem.t, x + rw, rem.t + rh)); x += rw }
                rem = Box(rem.l, rem.t + rh, rem.r, rem.b)
            }
        }
        return out
    }

    /** The tiles of the first series (positive sizes only), grouped if the chart has groups: the groups are squarified first, then the members inside each. */
    fun treemap(c: Chart, p: Box): List<Tile> {
        val s = c.series.firstOrNull() ?: return emptyList()
        val items = (0 until c.categories.size).mapNotNull { i -> s.at(i)?.takeIf { it > 0 }?.let { i to it } }
        if (items.isEmpty()) return emptyList()
        val names = c.groups.filter { it.isNotBlank() }.distinct()
        fun groupOf(i: Int) = names.indexOf(c.groups.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "").let { it }
        if (names.isEmpty()) return squarify(items.sortedByDescending { it.second }, p).map { Tile(it.first, it.second, -1) }
        val byGroup = items.groupBy { groupOf(it.first) }
        val groupTiles = squarify(byGroup.map { (g, v) -> g to v.sumOf { it.second } }.sortedByDescending { it.second }, p)
        return groupTiles.flatMap { (g, box) ->
            val pad = min(box.r - box.l, box.b - box.t) * 0.03
            squarify(byGroup.getValue(g).sortedByDescending { it.second }, Box(box.l + pad, box.t + pad, box.r - pad, box.b - pad)).map { Tile(it.first, it.second, g) }
        }
    }

    // ---- Gantt ----

    /** A task's bar (the whole span), and how much of it is done (0 to 1). */
    class GanttBar(val index: Int, val box: Box, val done: Double)

    /** The time axis from the earliest start to the latest end. */
    fun ganttScale(c: Chart): Scale {
        val a = c.series.getOrNull(0); val b = c.series.getOrNull(1)
        val all = (0 until c.categories.size).flatMap { i -> listOfNotNull(a?.at(i), b?.at(i)) } + (if (c.marker != 0L) listOf(c.marker / 1000.0) else emptyList())
        return if (all.isEmpty()) niceScale(0.0, 1.0, zero = false) else niceScale(all.min(), all.max(), zero = false)
    }

    /** The task bars, the first task at the top; a task with no start or no end has none. A bar whose end is before its start is drawn from the end. */
    fun gantt(c: Chart, p: Box, scale: Scale): List<GanttBar?> {
        val n = c.categories.size
        if (n == 0) return emptyList()
        val slot = (p.b - p.t) / n
        return (0 until n).map { i ->
            val s = c.series.getOrNull(0)?.at(i); val e = c.series.getOrNull(1)?.at(i)
            if (s == null || e == null) null else {
                val a = xOf(p, scale, min(s, e)); val b = xOf(p, scale, max(s, e))
                GanttBar(i, Box(a, p.t + slot * i + slot * 0.2, max(b, a + 1.0), p.t + slot * (i + 1) - slot * 0.2), ((c.series.getOrNull(2)?.at(i) ?: 0.0) / 100.0).coerceIn(0.0, 1.0))
            }
        }
    }

    private val monthDay = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)

    /** A day number (days since 1970-01-01) as "Dec 1". */
    fun dateLabel(days: Double): String = java.time.LocalDate.ofEpochDay(floor(days).toLong()).format(monthDay)

    /** "2026-12-01" as a day number, or null if it is not a date. */
    fun parseDate(s: String): Double? = try { java.time.LocalDate.parse(s.trim()).toEpochDay().toDouble() } catch (e: java.time.format.DateTimeParseException) { null }

    /** A day number as "2026-12-01", for typing back in. */
    fun dateText(days: Double): String = java.time.LocalDate.ofEpochDay(floor(days).toLong()).toString()

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** A number with at most two decimals, for crowded axes. */
    fun formatShort(v: Double): String = format(Math.round(v * 100) / 100.0)

    /** A number as the axis shows it: no trailing zeros ("12", "12.5"), at most 3 decimals. */
    fun format(v: Double): String {
        val r = Math.round(v * 1000) / 1000.0
        return if (r == Math.rint(r)) r.toLong().toString() else r.toString().trimEnd('0')
    }
}
