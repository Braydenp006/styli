package app.styli.ink

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class ChartKind { BAR, LINE, PIE }

/** One series: a name, a colour (0xAARRGGBB) and one value per category, in thousandths (12.5 is 12500). [missing] are the categories it has no value for. */
data class ChartSeries(val name: String, val color: Int, val values: List<Long>, val missing: Set<Int> = emptySet()) {
    /** The value in category [i] as a number, or null if it has none. */
    fun at(i: Int): Double? = if (i in missing || i !in values.indices) null else values[i] / 1000.0
}

/** Where a chart's data came from, if it follows a table (see the format): a block of the table's cells. */
data class ChartSource(val table: String, val row: Int, val col: Int, val rows: Int, val cols: Int, val seriesInRows: Boolean)

/**
 * A chart on a page, drawn from its data. Like a shape it is one whole object (box, angle, data, look) that never
 * changes once made; an edit makes a copy. The box is in page units, turned by [angle] radians about its top left
 * corner. A [fontSize] of zero means "a size that suits the box" (see [textSize]).
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
) {
    /** The base text size: [fontSize], or a twenty-fourth of the box's height. */
    val textSize: Float get() = if (fontSize > 0f) fontSize else height / 24f

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

    companion object {
        /** Throwaway colours until the design system has a chart palette. */
        val PALETTE = listOf(0xFF1565C0, 0xFFC62828, 0xFF2E7D32, 0xFFEF6C00, 0xFF4527A0, 0xFF00838F, 0xFFAD1457, 0xFF616161).map { it.toInt() }
        /** The colour for series (or pie slice) number [i] when none was chosen. */
        fun colourFor(i: Int) = PALETTE[i % PALETTE.size]
    }
}

/** The numbers behind drawing a chart: axis scales, where the bars, line points and pie slices go. No Android, so it is tested on the JVM. */
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

    /** The smallest and largest value the axis must show: stacked bars add up (negatives and positives apart). Null if there is no value at all. */
    fun valueRange(c: Chart): Pair<Double, Double>? {
        val n = c.categories.size
        if (c.kind == ChartKind.BAR && c.stacked) {
            var lo = 0.0; var hi = 0.0; var any = false
            for (i in 0 until n) {
                var pos = 0.0; var neg = 0.0
                for (s in c.series) s.at(i)?.let { any = true; if (it >= 0) pos += it else neg += it }
                lo = min(lo, neg); hi = max(hi, pos)
            }
            return if (any) lo to hi else null
        }
        val all = c.series.flatMap { s -> (0 until n).mapNotNull { s.at(it) } }
        return if (all.isEmpty()) null else all.min() to all.max()
    }

    /** The axis for [c] (bars always include zero, lines need not). */
    fun scaleFor(c: Chart): Scale {
        val (lo, hi) = valueRange(c) ?: (0.0 to 1.0)
        return niceScale(lo, hi, zero = c.kind == ChartKind.BAR)
    }

    /** Where things sit inside the chart's box, in its own frame (origin at the box's top left). */
    class Layout(val plot: Box, val title: Box?, val legend: Box?, val xLabel: Box?, val yLabel: Box?)

    /** Lays the parts out for a box of [w] by [h] with text [font] high: a title on top, the legend and axis labels underneath, numbers down the left. */
    fun layout(c: Chart, w: Double = c.width.toDouble(), h: Double = c.height.toDouble()): Layout {
        val f = c.textSize.toDouble()
        val pad = f * 0.6
        var top = pad; var bottom = h - pad; var left = pad; val right = w - pad
        val title = if (c.title.isNotBlank()) Box(pad, top, right, top + f * 1.8).also { top += f * 2.0 } else null
        val showLegend = !c.hideLegend && (if (c.kind == ChartKind.PIE) c.categories.isNotEmpty() else c.series.isNotEmpty())
        val legend = if (showLegend) Box(pad, bottom - f * 1.6, right, bottom).also { bottom -= f * 1.9 } else null
        val xLabel = if (c.kind != ChartKind.PIE && c.xLabel.isNotBlank()) Box(left, bottom - f * 1.4, right, bottom).also { bottom -= f * 1.6 } else null
        val yLabel = if (c.kind != ChartKind.PIE && c.yLabel.isNotBlank()) Box(pad, top, pad + f * 1.4, bottom).also { left += f * 1.6 } else null
        if (c.kind != ChartKind.PIE) { left += f * 3.6; bottom -= f * 1.6 } // the numbers down the side and the category names underneath
        val plot = Box(left, top, max(left + 1.0, right), max(top + 1.0, bottom))
        return Layout(plot, title, legend, xLabel, yLabel)
    }

    /** y in the box's frame for the value [v] on [scale], with the plot [p] (higher values are further up). */
    fun yOf(p: Box, scale: Scale, v: Double) = p.b - scale.fraction(v) * (p.b - p.t)

    /** For each series and category, the bar's box in the chart's frame (null where the series has no value). Side by side, or stacked from zero (negatives down). */
    fun bars(c: Chart, p: Box, scale: Scale): List<List<Box?>> {
        val n = c.categories.size
        val m = c.series.size
        if (n == 0 || m == 0) return List(m) { emptyList() }
        val slot = (p.r - p.l) / n
        val inner = slot * 0.8
        val zero = yOf(p, scale, 0.0)
        val out = List(m) { arrayOfNulls<Box>(n) }
        for (i in 0 until n) {
            val left = p.l + slot * i + (slot - inner) / 2
            var pos = 0.0; var neg = 0.0
            for (j in 0 until m) {
                val v = c.series[j].at(i) ?: continue
                if (c.stacked) {
                    val from = if (v >= 0) pos else neg
                    val to = from + v
                    if (v >= 0) pos = to else neg = to
                    val ya = yOf(p, scale, from); val yb = yOf(p, scale, to)
                    out[j][i] = Box(left, min(ya, yb), left + inner, max(ya, yb))
                } else {
                    val w = inner / m
                    val y = yOf(p, scale, v)
                    out[j][i] = Box(left + w * j, min(y, zero), left + w * (j + 1), max(y, zero))
                }
            }
        }
        return out.map { it.toList() }
    }

    /** For each series, its point in every category (the middle of the slot, null where it has no value), in the chart's frame. */
    fun linePoints(c: Chart, p: Box, scale: Scale): List<List<Pair<Double, Double>?>> {
        val n = c.categories.size
        val slot = if (n == 0) 0.0 else (p.r - p.l) / n
        return c.series.map { s -> (0 until n).map { i -> s.at(i)?.let { (p.l + slot * (i + 0.5)) to yOf(p, scale, it) } } }
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

    /** A number as the axis shows it: no trailing zeros ("12", "12.5"), at most 3 decimals. */
    fun format(v: Double): String {
        val r = Math.round(v * 1000) / 1000.0
        return if (r == Math.rint(r)) r.toLong().toString() else r.toString().trimEnd('0')
    }
}
