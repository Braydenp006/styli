package app.styli.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Draws a chart in its own frame: the canvas is already at the chart's top left, turned, in page units. What goes where
 * comes from [ChartMaths]; this only paints it. A "3D" chart is the same drawing with depth added by an oblique
 * extrusion (bars, areas and lines) or a tilted disc with a rim (pies and donuts): still a flat picture.
 */
object ChartDraw {
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun shade(color: Int, k: Float): Int =
        Color.argb(Color.alpha(color), (Color.red(color) * k).toInt().coerceIn(0, 255), (Color.green(color) * k).toInt().coerceIn(0, 255), (Color.blue(color) * k).toInt().coerceIn(0, 255))

    private fun label(canvas: Canvas, s: String, x: Double, y: Double, size: Float, align: Paint.Align, color: Int, bold: Boolean = false) {
        text.textSize = size; text.textAlign = align; text.isFakeBoldText = bold; text.color = color
        canvas.drawText(s, x.toFloat(), y.toFloat(), text)
    }

    /** [s] cut short with an ellipsis so that it is no wider than [max] at text size [size]. */
    private fun fitted(s: String, max: Double, size: Float): String {
        text.textSize = size; text.isFakeBoldText = false
        if (text.measureText(s) <= max) return s
        var n = s.length
        while (n > 1 && text.measureText(s.substring(0, n) + "\u2026") > max) n--
        return s.substring(0, n).trimEnd() + "\u2026"
    }

    /** Like [label], with a white outline so the text reads over ribbons or fills. */
    private fun haloLabel(canvas: Canvas, s: String, x: Double, y: Double, size: Float, align: Paint.Align, color: Int, bold: Boolean = false) {
        text.textSize = size; text.textAlign = align; text.isFakeBoldText = bold
        text.style = Paint.Style.STROKE; text.strokeWidth = size * 0.28f; text.strokeJoin = Paint.Join.ROUND; text.color = 0xE6FFFFFF.toInt()
        canvas.drawText(s, x.toFloat(), y.toFloat(), text)
        text.style = Paint.Style.FILL; text.strokeWidth = 0f
        text.color = color
        canvas.drawText(s, x.toFloat(), y.toFloat(), text)
    }

    /** [px] is how long about one screen pixel is in page units (for hairlines). */
    fun draw(canvas: Canvas, c: Chart, px: Float) {
        val f = c.textSize
        val ink = if (c.textColor ushr 24 == 0) Color.BLACK else c.textColor
        val lay = ChartMaths.layout(c)
        fill.color = Color.WHITE
        canvas.drawRect(0f, 0f, c.width, c.height, fill)
        if (c.kind != ChartKind.SPARKLINE) {
            line.strokeWidth = px; line.color = 0xFFBDBDBD.toInt()
            canvas.drawRect(0f, 0f, c.width, c.height, line)
        }
        lay.title?.let { label(canvas, c.title, (it.l + it.r) / 2, it.b - f * 0.4, f * 1.2f, Paint.Align.CENTER, ink, true) }
        val p = lay.plot
        when (c.kind) {
            ChartKind.PIE, ChartKind.DONUT -> pie(canvas, c, p, ink)
            ChartKind.RADAR -> radar(canvas, c, p, ink, px)
            ChartKind.POLAR -> polar(canvas, c, p, ink, px)
            ChartKind.FUNNEL -> funnel(canvas, c, p)
            ChartKind.GAUGE -> gauge(canvas, c, p, ink)
            ChartKind.SPARKLINE -> sparkline(canvas, c, p, px)
            ChartKind.SCATTER, ChartKind.BUBBLE -> scatter(canvas, c, p, ink, px)
            ChartKind.HISTOGRAM -> histogram(canvas, c, p, ink, px)
            ChartKind.BOXPLOT -> boxplot(canvas, c, p, ink, px)
            ChartKind.HEATMAP -> heatmap(canvas, c, p, ink)
            ChartKind.TREEMAP -> treemap(canvas, c, p)
            ChartKind.GANTT -> gantt(canvas, c, p, ink, px)
            ChartKind.SANKEY -> sankey(canvas, c, p, ink)
            ChartKind.SUNBURST -> sunburst(canvas, c, p, ink)
            ChartKind.TIMELINE -> timeline(canvas, c, p, ink, px)
            ChartKind.CALENDAR -> calendar(canvas, c, p, ink)
            ChartKind.FUNCTION -> function(canvas, c, p, ink, px)
            else -> axesChart(canvas, c, lay, ink, px)
        }
        lay.legend?.let { legend(canvas, c, it, ink) }
        lay.xLabel?.let { label(canvas, c.xLabel, (it.l + it.r) / 2, it.b - f * 0.3, f, Paint.Align.CENTER, ink) }
        lay.yLabel?.let {
            canvas.save()
            canvas.rotate(-90f, ((it.l + it.r) / 2).toFloat(), ((it.t + it.b) / 2).toFloat())
            label(canvas, c.yLabel, (it.l + it.r) / 2, (it.t + it.b) / 2 + f * 0.35, f, Paint.Align.CENTER, ink)
            canvas.restore()
        }
    }

    // ---- the charts with a value axis: bar, line, area, waterfall, combo, candlestick ----

    private fun axesChart(canvas: Canvas, c0: Chart, lay: ChartMaths.Layout, ink: Int, px: Float) {
        val c = ChartMaths.effective(c0)
        val f = c.textSize
        val p = lay.plot
        val scale = ChartMaths.scaleFor(c0)
        val has2 = c.kind == ChartKind.COMBO && c.series.any { it.secondary }
        val scale2 = if (has2) ChartMaths.scaleFor(c0, secondary = true) else scale
        val side = c.horizontal && c.kind == ChartKind.BAR
        val d = if (c.threeD) f * 0.9 else 0.0
        val n = c.categories.size
        // grid, numbers and names
        val comboLeft = if (c.kind == ChartKind.COMBO && has2) c.series.firstOrNull { !it.secondary }?.color else null
        val comboRight = if (has2) c.series.firstOrNull { it.secondary }?.color else null
        for (t in scale.ticks) {
            val shown = ChartMaths.format(if (c0.pyramid) Math.abs(t) else t)
            if (side) {
                val x = ChartMaths.xOf(p, scale, t)
                if (!c.hideGrid) { line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt(); canvas.drawLine(x.toFloat(), p.t.toFloat(), x.toFloat(), p.b.toFloat(), line) }
                label(canvas, shown, x, p.b + f * 1.2, f * 0.8f, Paint.Align.CENTER, ink)
            } else {
                val y = ChartMaths.yOf(p, scale, t)
                if (!c.hideGrid) { line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt(); canvas.drawLine(p.l.toFloat(), y.toFloat(), p.r.toFloat(), y.toFloat(), line) }
                label(canvas, shown, p.l - f * 0.4, y + f * 0.3, f * 0.8f, Paint.Align.RIGHT, comboLeft ?: ink)
            }
        }
        if (has2) for (t in scale2.ticks) label(canvas, ChartMaths.format(t), p.r + f * 0.4, ChartMaths.yOf(p, scale2, t) + f * 0.3, f * 0.8f, Paint.Align.LEFT, comboRight ?: ink)
        if (side) {
            val slot = (p.b - p.t) / maxOf(1, n)
            for ((i, name) in c.categories.withIndex()) label(canvas, name, p.l - f * 0.4, p.t + slot * (i + 0.5) + f * 0.3, f * 0.8f, Paint.Align.RIGHT, ink)
        } else {
            val slot = (p.r - p.l) / maxOf(1, n)
            for ((i, name) in c.categories.withIndex()) label(canvas, fitted(name, slot * 0.95, f * 0.8f), p.l + slot * (i + 0.5), p.b + f * 1.2, f * 0.8f, Paint.Align.CENTER, ink)
        }
        when (c.kind) {
            ChartKind.WATERFALL -> waterfall(canvas, c, p, scale, d, ink)
            ChartKind.CANDLESTICK -> candles(canvas, c, p, scale, px, ink)
            else -> {
                areas(canvas, c, p, scale, scale2, d)
                bars(canvas, c, p, scale, scale2, d, ink, side)
                lines(canvas, c, p, scale, scale2, d, ink)
                errorBars(canvas, c0, p, scale, scale2, ink, px)
            }
        }
        line.strokeWidth = px * 1.5f; line.color = ink
        if (side) {
            val zero = ChartMaths.xOf(p, scale, 0.0).coerceIn(p.l, p.r).toFloat()
            canvas.drawLine(zero, p.t.toFloat(), zero, p.b.toFloat(), line)
            canvas.drawLine(p.l.toFloat(), p.b.toFloat(), p.r.toFloat(), p.b.toFloat(), line)
        } else {
            val zero = ChartMaths.yOf(p, scale, 0.0).coerceIn(p.t, p.b).toFloat()
            canvas.drawLine(p.l.toFloat(), zero, p.r.toFloat(), zero, line)
            canvas.drawLine(p.l.toFloat(), p.t.toFloat(), p.l.toFloat(), p.b.toFloat(), line)
            if (has2) canvas.drawLine(p.r.toFloat(), p.t.toFloat(), p.r.toFloat(), p.b.toFloat(), line)
        }
        if (c.kind != ChartKind.CANDLESTICK && c.kind != ChartKind.WATERFALL && !side) trendlines(canvas, c, p, scale, scale2, px)
    }

    private fun box3d(canvas: Canvas, b: Box, color: Int, d: Double) {
        val l = b.l.toFloat(); val t = b.t.toFloat(); val r = b.r.toFloat(); val bt = b.b.toFloat()
        solid.color = color
        canvas.drawRect(l, t, r, bt, solid)
        if (d > 0) {
            val o = (d * 0.6).toFloat(); val u = (d * 0.6).toFloat()
            solid.color = shade(color, 0.7f) // the side
            path.reset(); path.moveTo(r, t); path.lineTo(r + o, t - u); path.lineTo(r + o, bt - u); path.lineTo(r, bt); path.close(); canvas.drawPath(path, solid)
            solid.color = shade(color, 1.2f) // the top
            path.reset(); path.moveTo(l, t); path.lineTo(l + o, t - u); path.lineTo(r + o, t - u); path.lineTo(r, t); path.close(); canvas.drawPath(path, solid)
        }
    }

    private fun bars(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, scale2: ChartMaths.Scale, d: Double, ink: Int, side: Boolean) {
        val f = c.textSize
        val rows = ChartMaths.bars(c, p, scale, scale2)
        // far bars first, so a nearer one's side does not hide a farther one's front
        for ((j, row) in rows.withIndex()) {
            val colour = c.series[j].color
            for ((i, b) in row.withIndex()) {
                if (b == null) continue
                box3d(canvas, b, colour, d)
                if (c.showValues) {
                    val v = c.series[j].at(i) ?: continue
                    if (side) label(canvas, ChartMaths.format(v), if (v >= 0) b.r + f * 0.2 else b.l - f * 0.2, (b.t + b.b) / 2 + f * 0.3, f * 0.7f, if (v >= 0) Paint.Align.LEFT else Paint.Align.RIGHT, ink)
                    else label(canvas, ChartMaths.format(v), (b.l + b.r) / 2, if (v >= 0) b.t - f * 0.2 - d * 0.6 else b.b + f * 0.9, f * 0.7f, Paint.Align.CENTER, ink)
                }
            }
        }
    }

    /** The points of a line as it is drawn: the curve sampled for a smooth one, the corners added for a stepped one. */
    private fun polyline(pts: List<Pair<Double, Double>>, curve: Curve): List<Pair<Double, Double>> = when (curve) {
        Curve.SMOOTH -> if (pts.size < 2) pts else listOf(pts[0]) + ChartMaths.smooth(pts).flatMap { g ->
            (1..10).map { k ->
                val t = k / 10.0; val u = 1 - t
                (u * u * u * g.x0 + 3 * u * u * t * g.c1x + 3 * u * t * t * g.c2x + t * t * t * g.x1) to
                    (u * u * u * g.y0 + 3 * u * u * t * g.c1y + 3 * u * t * t * g.c2y + t * t * t * g.y1)
            }
        }
        Curve.STEP -> ChartMaths.steps(pts)
        else -> pts
    }

    /** One piece of a ribbon: the segment from [ax],[ay] to [bx],[by] swept by ([o], -[o]), drawn with a thin edge in its own colour so no seam shows between pieces. */
    private fun ribbonQuad(canvas: Canvas, ax: Float, ay: Float, bx: Float, by: Float, o: Float, colour: Int, seam: Float) {
        path.reset(); path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(bx + o, by - o); path.lineTo(ax + o, ay - o); path.close()
        solid.color = colour; canvas.drawPath(path, solid)
        line.strokeWidth = seam; line.color = colour; canvas.drawPath(path, line)
    }

    private fun addCurve(path: Path, pts: List<Pair<Double, Double>>, curve: Curve) {
        if (pts.isEmpty()) return
        when (curve) {
            Curve.SMOOTH -> { path.moveTo(pts[0].first.toFloat(), pts[0].second.toFloat()); for (s in ChartMaths.smooth(pts)) path.cubicTo(s.c1x.toFloat(), s.c1y.toFloat(), s.c2x.toFloat(), s.c2y.toFloat(), s.x1.toFloat(), s.y1.toFloat()) }
            else -> { val q = if (curve == Curve.STEP) ChartMaths.steps(pts) else pts; path.moveTo(q[0].first.toFloat(), q[0].second.toFloat()); for (k in 1 until q.size) path.lineTo(q[k].first.toFloat(), q[k].second.toFloat()) }
        }
    }

    /** The runs of consecutive points that exist (a blank value breaks a line or area). */
    private fun <T : Any> runs(pts: List<T?>): List<List<Pair<Int, T>>> {
        val out = ArrayList<List<Pair<Int, T>>>(); var cur = ArrayList<Pair<Int, T>>()
        for ((i, q) in pts.withIndex()) { if (q != null) cur.add(i to q) else if (cur.isNotEmpty()) { out.add(cur); cur = ArrayList() } }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun areas(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, scale2: ChartMaths.Scale, d: Double) {
        val bands = ChartMaths.areas(c, p, scale, scale2)
        for ((j, band) in bands.withIndex()) {
            band ?: continue
            val colour = c.series[j].color
            val o = (d * 0.6).toFloat()
            for (run in runs(band.top)) {
                val top = run.map { it.second }
                val bottom = run.map { band.bottom[it.first]!! }
                if (d > 0) { // a ribbon behind, so the area looks like a slab
                    solid.color = shade(colour, 0.6f)
                    val edge = polyline(top, c.curve)
                    for (k in 0 until edge.size - 1) ribbonQuad(canvas, edge[k].first.toFloat(), edge[k].second.toFloat(), edge[k + 1].first.toFloat(), edge[k + 1].second.toFloat(), o, solid.color, c.textSize * 0.04f)
                }
                path.reset()
                addCurve(path, top, c.curve)
                val tail = bottom.reversed()
                path.lineTo(tail[0].first.toFloat(), tail[0].second.toFloat())
                for (q in tail) path.lineTo(q.first.toFloat(), q.second.toFloat())
                path.close()
                solid.color = (colour and 0x00FFFFFF) or 0xB3000000.toInt() // a little see-through, so stacked areas can be told apart
                canvas.drawPath(path, solid)
            }
        }
    }

    private fun lines(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, scale2: ChartMaths.Scale, d: Double, ink: Int) {
        val f = c.textSize
        val all = ChartMaths.linePoints(c, p, scale, scale2)
        for ((j, pts) in all.withIndex()) {
            val kind = c.kindOf(c.series[j])
            if (kind != ChartKind.LINE) continue
            val colour = c.series[j].color
            val o = (d * 0.6).toFloat()
            for (run in runs(pts)) {
                val q = run.map { it.second }
                val edge = polyline(q, c.curve)
                if (d > 0 && edge.size > 1) { // a ribbon: the line with depth behind it
                    solid.color = shade(colour, 0.6f)
                    for (k in 0 until edge.size - 1) ribbonQuad(canvas, edge[k].first.toFloat(), edge[k].second.toFloat(), edge[k + 1].first.toFloat(), edge[k + 1].second.toFloat(), o, solid.color, c.textSize * 0.04f)
                }
                line.strokeWidth = f * 0.2f; line.color = colour
                path.reset(); addCurve(path, q, c.curve); canvas.drawPath(path, line)
            }
            solid.color = colour
            for ((i, pt) in pts.withIndex()) {
                if (pt == null) continue
                if (!c.hideMarkers) canvas.drawCircle(pt.first.toFloat(), pt.second.toFloat(), f * 0.3f, solid)
                if (c.showValues) c.series[j].at(i)?.let { label(canvas, ChartMaths.format(it), pt.first, pt.second - f * 0.5, f * 0.7f, Paint.Align.CENTER, ink) }
            }
        }
    }

    private fun trendlines(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, scale2: ChartMaths.Scale, px: Float) {
        for (s in c.series) {
            if (!s.trendline) continue
            val ends = ChartMaths.trendEnds(c, s, p, if (s.secondary) scale2 else scale) ?: continue
            line.strokeWidth = px * 2f; line.color = s.color
            line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(c.textSize * 0.6f, c.textSize * 0.4f), 0f)
            canvas.drawLine(ends.first.first.toFloat(), ends.first.second.toFloat(), ends.second.first.toFloat(), ends.second.second.toFloat(), line)
            line.pathEffect = null
        }
    }

    private fun waterfall(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, d: Double, ink: Int) {
        val f = c.textSize
        val bars = ChartMaths.waterfall(c, p, scale)
        var prev: ChartMaths.StepBar? = null
        for ((i, b) in bars.withIndex()) {
            val colour = when (b.kind) { ChartMaths.StepBar.UP -> Chart.UP; ChartMaths.StepBar.DOWN -> Chart.DOWN; else -> Chart.TOTAL }
            prev?.let { // the thin link from the last level to this bar
                val y = ChartMaths.yOf(p, scale, it.level).toFloat()
                line.strokeWidth = f * 0.05f; line.color = 0xFF9E9E9E.toInt()
                canvas.drawLine(it.box.r.toFloat(), y, b.box.l.toFloat(), y, line)
            }
            box3d(canvas, b.box, colour, d)
            if (c.showValues) label(canvas, ChartMaths.format(b.level), (b.box.l + b.box.r) / 2, b.box.t - f * 0.2, f * 0.7f, Paint.Align.CENTER, ink)
            prev = b
            if (i == bars.lastIndex) break
        }
    }

    private fun candles(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, px: Float, ink: Int) {
        for (k in ChartMaths.candles(c, p, scale)) {
            k ?: continue
            val colour = if (k.up) Chart.UP else Chart.DOWN
            line.strokeWidth = maxOf(px * 1.5f, c.textSize * 0.1f); line.color = colour
            canvas.drawLine(k.x.toFloat(), k.high.toFloat(), k.x.toFloat(), k.low.toFloat(), line)
            solid.color = colour
            box3d(canvas, Box(k.x - k.width / 2, minOf(k.open, k.close), k.x + k.width / 2, maxOf(k.open, k.close).coerceAtLeast(minOf(k.open, k.close) + px)), colour, if (c.threeD) c.textSize * 0.9 else 0.0)
        }
    }

    // ---- the value axes the second batch share: numbers up the side (and, where there are two, along the bottom) ----

    private fun yAxis(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, ink: Int, px: Float) {
        val f = c.textSize
        for (t in scale.ticks) {
            val y = ChartMaths.yOf(p, scale, t)
            if (!c.hideGrid) { line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt(); canvas.drawLine(p.l.toFloat(), y.toFloat(), p.r.toFloat(), y.toFloat(), line) }
            label(canvas, ChartMaths.format(t), p.l - f * 0.4, y + f * 0.3, f * 0.8f, Paint.Align.RIGHT, ink)
        }
    }

    private fun xAxis(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, ink: Int, px: Float, date: Boolean = false) {
        val f = c.textSize
        for (t in scale.ticks) {
            val x = ChartMaths.xOf(p, scale, t)
            if (!c.hideGrid) { line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt(); canvas.drawLine(x.toFloat(), p.t.toFloat(), x.toFloat(), p.b.toFloat(), line) }
            label(canvas, if (date) ChartMaths.dateLabel(t) else ChartMaths.format(t), x, p.b + f * 1.2, f * 0.8f, if (x > p.r - f * 1.5) Paint.Align.RIGHT else Paint.Align.CENTER, ink)
        }
    }

    private fun frame(canvas: Canvas, p: Box, ink: Int, px: Float) {
        line.strokeWidth = px * 1.5f; line.color = ink
        canvas.drawLine(p.l.toFloat(), p.b.toFloat(), p.r.toFloat(), p.b.toFloat(), line)
        canvas.drawLine(p.l.toFloat(), p.t.toFloat(), p.l.toFloat(), p.b.toFloat(), line)
    }

    // ---- scatter and bubble ----

    private fun lighter(color: Int) = shade(color, 1.35f)

    private fun scatter(canvas: Canvas, c: Chart, p: Box, ink: Int, px: Float) {
        val f = c.textSize
        val (sx, sy) = ChartMaths.scatterScales(c)
        yAxis(canvas, c, p, sy, ink, px); xAxis(canvas, c, p, sx, ink, px); frame(canvas, p, ink, px)
        val bubble = c.kind == ChartKind.BUBBLE
        val all = ChartMaths.dots(c, p, sx, sy)
        for ((j, s) in c.series.withIndex()) {
            if (s.trendline) ChartMaths.trendXYEnds(s, p, sx, sy)?.let { e ->
                line.strokeWidth = px * 2f; line.color = s.color
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(f * 0.6f, f * 0.4f), 0f)
                canvas.drawLine(e.first.first.toFloat(), e.first.second.toFloat(), e.second.first.toFloat(), e.second.second.toFloat(), line)
                line.pathEffect = null
                if (c.showValues) ChartMaths.trendXY(s)?.let { (_, _, r2) -> label(canvas, "R\u00B2 " + ChartMaths.format(Math.round(r2 * 100) / 100.0), e.second.first, e.second.second - f * 0.5, f * 0.8f, Paint.Align.RIGHT, s.color) }
            }
            line.strokeWidth = maxOf(px * 1.5f, f * 0.08f); line.color = ink
            for (d in all[j]) { // error bars sit behind the points
                d ?: continue
                val e = s.errAt(d.index) ?: continue
                val v = s.at(d.index) ?: continue
                val yt = ChartMaths.yOf(p, sy, v + e).toFloat(); val yb = ChartMaths.yOf(p, sy, v - e).toFloat()
                canvas.drawLine(d.x.toFloat(), yt, d.x.toFloat(), yb, line); cap(canvas, d.x.toFloat(), yt, f * 0.3f); cap(canvas, d.x.toFloat(), yb, f * 0.3f)
            }
            for (d in all[j]) {
                d ?: continue
                val r = (if (bubble) d.r.coerceAtLeast(f * 0.2) else f * 0.35).toFloat()
                val x = d.x.toFloat(); val y = d.y.toFloat()
                if (c.threeD) ball(canvas, x, y, r, s.color, if (bubble) 0xE6 else 0xFF)
                else {
                    solid.color = if (bubble) (s.color and 0x00FFFFFF) or 0xA6000000.toInt() else s.color
                    canvas.drawCircle(x, y, r, solid)
                    if (bubble) { line.strokeWidth = px; line.color = s.color; canvas.drawCircle(x, y, r, line) }
                }
                if (c.showValues) c.categories.getOrNull(d.index)?.takeIf { it.isNotBlank() }?.let { label(canvas, it, d.x + r + f * 0.2, d.y + f * 0.3, f * 0.7f, Paint.Align.LEFT, ink) }
            }
        }
    }

    /** A ball standing on the plot: a flat shadow down and to the right, then a lit sphere with a bright spot. [alpha] below 255 lets the page show through a little. */
    private fun ball(canvas: Canvas, x: Float, y: Float, r: Float, colour: Int, alpha: Int = 0xFF) {
        solid.shader = null
        solid.color = 0x30000000
        canvas.drawOval(x - r * 0.4f, y + r * 0.75f, x + r * 1.4f, y + r * 1.25f, solid)
        val body = (colour and 0x00FFFFFF) or (alpha shl 24)
        solid.color = Color.BLACK // the paint's own colour must be opaque, or it fades the whole shader
        solid.shader = android.graphics.RadialGradient(x - r * 0.35f, y - r * 0.4f, r * 1.45f, intArrayOf(shade(body, 1.35f), body, shade(body, 0.55f)), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, solid); solid.shader = null
        solid.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(x - r * 0.38f, y - r * 0.42f, r * 0.2f, solid)
    }

    // ---- error bars ----

    private fun cap(canvas: Canvas, x: Float, y: Float, half: Float) = canvas.drawLine(x - half, y, x + half, y, line)

    private fun errorBars(canvas: Canvas, c: Chart, p: Box, scale: ChartMaths.Scale, scale2: ChartMaths.Scale, ink: Int, px: Float) {
        val f = c.textSize
        line.strokeWidth = maxOf(px * 1.5f, f * 0.08f); line.color = ink
        for (spans in ChartMaths.errorBars(c, p, scale, scale2)) for (e in spans) {
            e ?: continue
            canvas.drawLine(e.x.toFloat(), e.top.toFloat(), e.x.toFloat(), e.bottom.toFloat(), line)
            cap(canvas, e.x.toFloat(), e.top.toFloat(), f * 0.3f); cap(canvas, e.x.toFloat(), e.bottom.toFloat(), f * 0.3f)
        }
    }

    // ---- histogram ----

    private fun histogram(canvas: Canvas, c: Chart, p: Box, ink: Int, px: Float) {
        val f = c.textSize
        val h = ChartMaths.histogram(c) ?: return
        val sy = ChartMaths.niceScale(0.0, (h.counts.maxOrNull() ?: 1).toDouble())
        yAxis(canvas, c, p, sy, ink, px)
        val e0 = h.edges.first(); val e1 = h.edges.last()
        fun x(e: Double) = p.l + (e - e0) / (e1 - e0) * (p.r - p.l)
        val d = if (c.threeD) f * 0.9 else 0.0
        val colour = c.series.firstOrNull()?.color ?: Chart.colourFor(0)
        for ((k, n) in h.counts.withIndex()) {
            box3d(canvas, Box(x(h.edges[k]) + px, ChartMaths.yOf(p, sy, n.toDouble()), x(h.edges[k + 1]) - px, ChartMaths.yOf(p, sy, 0.0)), colour, d)
            if (c.showValues && n > 0) label(canvas, n.toString(), (x(h.edges[k]) + x(h.edges[k + 1])) / 2, ChartMaths.yOf(p, sy, n.toDouble()) - f * 0.2 - d * 0.6, f * 0.7f, Paint.Align.CENTER, ink)
        }
        val every = maxOf(1, Math.ceil(h.edges.size / 8.0).toInt())
        for ((k, e) in h.edges.withIndex()) if (k % every == 0) label(canvas, ChartMaths.formatShort(e), x(e), p.b + f * 1.2, f * 0.8f, Paint.Align.CENTER, ink)
        frame(canvas, p, ink, px)
    }

    // ---- box plot ----

    private fun boxplot(canvas: Canvas, c: Chart, p: Box, ink: Int, px: Float) {
        val f = c.textSize
        val sy = ChartMaths.boxScale(c)
        yAxis(canvas, c, p, sy, ink, px)
        val d = if (c.threeD) f * 0.9 else 0.0
        for ((j, b) in ChartMaths.boxes(c, p, sy).withIndex()) {
            val colour = c.series[j].color
            label(canvas, c.series[j].name, if (b != null) b.x else p.l + (p.r - p.l) / (2 * c.series.size) * (2 * j + 1), p.b + f * 1.2, f * 0.8f, Paint.Align.CENTER, ink)
            b ?: continue
            line.strokeWidth = f * 0.12f; line.color = ink
            canvas.drawLine(b.x.toFloat(), b.low.toFloat(), b.x.toFloat(), b.q1.toFloat(), line); canvas.drawLine(b.x.toFloat(), b.q3.toFloat(), b.x.toFloat(), b.high.toFloat(), line)
            canvas.drawLine((b.x - b.w / 4).toFloat(), b.low.toFloat(), (b.x + b.w / 4).toFloat(), b.low.toFloat(), line); canvas.drawLine((b.x - b.w / 4).toFloat(), b.high.toFloat(), (b.x + b.w / 4).toFloat(), b.high.toFloat(), line)
            box3d(canvas, Box(b.x - b.w / 2, b.q3, b.x + b.w / 2, b.q1), colour, d)
            line.strokeWidth = f * 0.22f; line.color = Color.WHITE
            canvas.drawLine((b.x - b.w / 2).toFloat(), b.median.toFloat(), (b.x + b.w / 2).toFloat(), b.median.toFloat(), line)
            if (c.showMean) { solid.color = ink; canvas.drawCircle(b.x.toFloat(), b.mean.toFloat(), f * 0.28f, solid); solid.color = Color.WHITE; canvas.drawCircle(b.x.toFloat(), b.mean.toFloat(), f * 0.14f, solid) }
            if (!c.hideOutliers) { line.strokeWidth = f * 0.1f; line.color = colour; for (o in b.outliers) canvas.drawCircle(b.x.toFloat(), o.toFloat(), f * 0.3f, line) }
        }
        frame(canvas, p, ink, px)
    }

    // ---- heatmap ----

    private fun heatmap(canvas: Canvas, c: Chart, p: Box, ink: Int) {
        val f = c.textSize
        val cells = ChartMaths.heatCells(c, p)
        val d = if (c.threeD) f * 0.9 else 0.0
        for (cell in cells) {
            val b = cell.box
            if (cell.value == null) { solid.color = 0xFFF0F0F0.toInt(); canvas.drawRect(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat(), solid); continue }
            box3d(canvas, b, ChartMaths.heatColour(c, cell.t), d * (0.25 + 0.75 * cell.t)) // a hotter cell stands higher
            if (c.showValues) label(canvas, ChartMaths.format(cell.value), (b.l + b.r) / 2, (b.t + b.b) / 2 + f * 0.3, f * 0.8f, Paint.Align.CENTER, if (cell.t > 0.55) Color.WHITE else Color.BLACK)
        }
        val rows = c.categories.size; val cols = c.series.size
        for (r in 0 until rows) label(canvas, c.categories[r], p.l - f * 0.4, p.t + (p.b - p.t) / rows * (r + 0.5) + f * 0.3, f * 0.8f, Paint.Align.RIGHT, ink)
        for (k in 0 until cols) label(canvas, c.series[k].name, p.l + (p.r - p.l) / cols * (k + 0.5), p.b + f * 1.2, f * 0.8f, Paint.Align.CENTER, ink)
    }

    // ---- treemap ----

    private fun treemap(canvas: Canvas, c: Chart, p: Box) {
        val f = c.textSize
        val d = if (c.threeD) f * 0.5 else 0.0
        val s0 = c.series.firstOrNull()
        for (t in ChartMaths.treemap(c, p)) {
            val colour = Chart.colourFor(if (t.group >= 0) t.group else t.index)
            val b = Box(t.box.l + f * 0.1, t.box.t + f * 0.1, t.box.r - f * 0.1 - d * 0.6, t.box.b - f * 0.1)
            if (b.r <= b.l || b.b <= b.t) continue
            box3d(canvas, b, if (t.group >= 0) shade(colour, 0.85f + 0.3f * (t.index % 4) / 4f) else colour, d)
            val name = c.categories[t.index] + "  " + ChartMaths.format(s0?.at(t.index) ?: 0.0)
            text.textSize = f * 0.85f; text.isFakeBoldText = true
            if (text.measureText(name) <= (b.r - b.l) - f * 0.4 && b.b - b.t > f * 1.4) label(canvas, name, b.l + f * 0.3, b.t + f * 1.1, f * 0.85f, Paint.Align.LEFT, Color.WHITE, true)
        }
    }

    // ---- Gantt ----

    private fun gantt(canvas: Canvas, c: Chart, p: Box, ink: Int, px: Float) {
        val f = c.textSize
        val scale = ChartMaths.ganttScale(c)
        xAxis(canvas, c, p, scale, ink, px, c.dateAxis)
        val n = c.categories.size
        val slot = (p.b - p.t) / maxOf(1, n)
        for (i in 0 until n) label(canvas, c.categories[i], p.l - f * 0.4, p.t + slot * (i + 0.5) + f * 0.3, f * 0.8f, Paint.Align.RIGHT, ink)
        val colour = c.series.firstOrNull()?.color ?: Chart.colourFor(0)
        val d = if (c.threeD) f * 0.9 else 0.0
        for (b in ChartMaths.gantt(c, p, scale)) {
            b ?: continue
            solid.color = (colour and 0x00FFFFFF) or 0x55000000; canvas.drawRect(b.box.l.toFloat(), b.box.t.toFloat(), b.box.r.toFloat(), b.box.b.toFloat(), solid) // the whole span, faint
            val done = Box(b.box.l, b.box.t, b.box.l + (b.box.r - b.box.l) * b.done, b.box.b)
            if (b.done > 0.0) box3d(canvas, done, colour, d) else if (d > 0) box3d(canvas, Box(b.box.l, b.box.t, b.box.l, b.box.b), colour, 0.0)
            line.strokeWidth = px; line.color = colour; canvas.drawRect(b.box.l.toFloat(), b.box.t.toFloat(), b.box.r.toFloat(), b.box.b.toFloat(), line)
        }
        if (c.marker != 0L) {
            val v = c.marker / 1000.0
            val x = ChartMaths.xOf(p, scale, v).toFloat()
            line.strokeWidth = f * 0.12f; line.color = Chart.DOWN
            line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(f * 0.5f, f * 0.3f), 0f)
            canvas.drawLine(x, p.t.toFloat(), x, p.b.toFloat(), line); line.pathEffect = null
            label(canvas, if (c.dateAxis) ChartMaths.dateLabel(v) else ChartMaths.format(v), x + f * 0.25, p.t + f * 0.9, f * 0.75f, Paint.Align.LEFT, Chart.DOWN)
        }
        frame(canvas, p, ink, px)
    }

    // ---- Sankey ----

    private fun sankey(canvas: Canvas, c: Chart, p: Box, ink: Int) {
        val f = c.textSize
        val lay = ChartMaths.sankey(c, p) ?: return
        val d = if (c.threeD) f * 0.5 else 0.0
        val last = lay.nodes.maxOf { it.level }
        for (l in lay.links) { // the ribbons first, under the nodes
            val from = lay.nodes[l.from]
            val colour = Chart.colourFor(l.from)
            val mx = ((l.x0 + l.x1) / 2).toFloat()
            fun ribbon(dx: Float, dy: Float, argb: Int) {
                path.reset()
                path.moveTo(l.x0.toFloat() + dx, l.y0.toFloat() + dy)
                path.cubicTo(mx + dx, l.y0.toFloat() + dy, mx + dx, l.y1.toFloat() + dy, l.x1.toFloat() + dx, l.y1.toFloat() + dy)
                path.lineTo(l.x1.toFloat() + dx, (l.y1 + l.thickness).toFloat() + dy)
                path.cubicTo(mx + dx, (l.y1 + l.thickness).toFloat() + dy, mx + dx, (l.y0 + l.thickness).toFloat() + dy, l.x0.toFloat() + dx, (l.y0 + l.thickness).toFloat() + dy)
                path.close(); solid.color = argb; canvas.drawPath(path, solid)
            }
            if (d > 0) ribbon((d * 0.6).toFloat(), -(d * 0.6).toFloat(), (shade(colour, 0.6f) and 0x00FFFFFF) or 0x66000000)
            ribbon(0f, 0f, (colour and 0x00FFFFFF) or 0x80000000.toInt())
            if (c.showValues && l.thickness > f * 0.9) haloLabel(canvas, ChartMaths.format(l.value), (l.x0 + l.x1) / 2, (l.y0 + l.y1 + l.thickness) / 2 + f * 0.3, f * 0.7f, Paint.Align.CENTER, ink)
        }
        for ((i, n) in lay.nodes.withIndex()) {
            box3d(canvas, n.box, Chart.colourFor(i), d)
            val name = n.name
            if (n.level == last && lay.nodes.any { it.level != last }) haloLabel(canvas, name, n.box.l - f * 0.3, (n.box.t + n.box.b) / 2 + f * 0.3, f * 0.85f, Paint.Align.RIGHT, ink, true)
            else haloLabel(canvas, name, n.box.r + f * 0.3 + d * 0.6, (n.box.t + n.box.b) / 2 + f * 0.3, f * 0.85f, Paint.Align.LEFT, ink, true)
        }
    }

    // ---- sunburst ----

    private fun sunburst(canvas: Canvas, c: Chart, p: Box, ink: Int) {
        val f = c.textSize
        val arcs = ChartMaths.sunburst(c)
        if (arcs.isEmpty()) return
        val rings = arcs.maxOf { it.depth } + 1
        val tilt = if (c.threeD) 0.6f else 1f
        val depth = if (c.threeD) f * 1.2f else 0f
        val r = (minOf(p.r - p.l, (p.b - p.t - depth) / tilt) / 2 * 0.95).toFloat()
        val cx = ((p.l + p.r) / 2).toFloat(); val cy = ((p.t + p.b - depth) / 2).toFloat()
        val r0 = r * 0.16f
        val rw = (r - r0) / rings
        fun colour(a: ChartMaths.Arc) = shade(Chart.colourFor(a.top), 1f + 0.14f * a.depth)
        fun draw(shadeBy: Float, dy: Float) {
            canvas.save(); canvas.translate(0f, dy)
            for (a in arcs) {
                annulus(path, cx, cy, r0 + rw * (a.depth + 1) - rw * 0.04f, r0 + rw * a.depth, Math.toDegrees(a.start).toFloat(), Math.toDegrees(a.sweep).toFloat())
                solid.color = shade(colour(a), shadeBy); canvas.drawPath(path, solid)
            }
            canvas.restore()
        }
        canvas.save()
        canvas.scale(1f, tilt, cx, cy)
        if (depth > 0f) for (k in 14 downTo 1) draw(0.65f, depth / tilt * k / 14)
        draw(1f, 0f)
        canvas.restore()
        for (a in arcs) {
            val mid = a.start + a.sweep / 2
            val rr = r0 + rw * (a.depth + 0.5f)
            val name = a.path.last()
            text.textSize = f * 0.75f; text.isFakeBoldText = false
            val fits = a.sweep * rr > text.measureText(name) * 1.15 && rw > f * 0.9
            if (!fits || (tilt < 1f && a.depth > 0)) continue
            val x = cx + rr * Math.cos(mid).toFloat(); val y = cy + rr * Math.sin(mid).toFloat() * tilt
            canvas.save()
            if (tilt < 1f) { canvas.translate(x, y); label(canvas, name, 0.0, f * 0.25, f * 0.75f, Paint.Align.CENTER, Color.WHITE, true) }
            else { canvas.translate(x, y); canvas.rotate((if (Math.sin(mid) > 0) Math.toDegrees(mid) - 90 else Math.toDegrees(mid) + 90).toFloat()); label(canvas, name, 0.0, f * 0.25, f * 0.75f, Paint.Align.CENTER, Color.WHITE, true) }
            canvas.restore()
        }
    }

    // ---- timeline ----

    private fun timeline(canvas: Canvas, c: Chart, p: Box, ink: Int, px: Float) {
        val f = c.textSize
        val scale = ChartMaths.timelineScale(c)
        val axisY = (p.t + p.b) / 2
        text.textSize = f * 0.85f; text.isFakeBoldText = false
        val events = ChartMaths.timeline(c, p, scale) { text.measureText(it) + f * 0.6 }
        line.strokeWidth = f * 0.14f; line.color = ink
        canvas.drawLine(p.l.toFloat(), axisY.toFloat(), p.r.toFloat(), axisY.toFloat(), line)
        for (t in ChartMaths.axisTicks(scale.lo, scale.hi, 8)) {
            val x = p.l + scale.fraction(t) * (p.r - p.l)
            if (!c.hideGrid) { line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt(); canvas.drawLine(x.toFloat(), p.t.toFloat(), x.toFloat(), p.b.toFloat(), line) }
            line.strokeWidth = px * 1.5f; line.color = ink; canvas.drawLine(x.toFloat(), (axisY - f * 0.2).toFloat(), x.toFloat(), (axisY + f * 0.2).toFloat(), line)
            label(canvas, if (c.dateAxis) ChartMaths.dateLabel(t) else ChartMaths.format(t), x, p.b + f * 1.2, f * 0.8f, if (x > p.r - f * 1.5) Paint.Align.RIGHT else Paint.Align.CENTER, ink)
        }
        line.strokeWidth = f * 0.14f; line.color = ink
        canvas.drawLine(p.l.toFloat(), axisY.toFloat(), p.r.toFloat(), axisY.toFloat(), line)
        val d = c.threeD
        for (e in events) {
            e ?: continue
            val colour = Chart.colourFor(e.index)
            val reach = f * 1.4 + e.lane * f * 1.9
            val y = if (e.above) axisY - reach else axisY + reach
            e.x2?.let { x2 -> solid.color = (colour and 0x00FFFFFF) or 0xAA000000.toInt(); canvas.drawRect(e.x.toFloat(), (axisY - f * 0.25).toFloat(), maxOf(x2, e.x + 2).toFloat(), (axisY + f * 0.25).toFloat(), solid) }
            line.strokeWidth = px; line.color = colour
            canvas.drawLine(e.x.toFloat(), axisY.toFloat(), e.x.toFloat(), y.toFloat(), line)
            if (d) ball(canvas, e.x.toFloat(), axisY.toFloat(), f * 0.4f, colour) else { solid.color = colour; canvas.drawCircle(e.x.toFloat(), axisY.toFloat(), f * 0.35f, solid); line.strokeWidth = px * 1.5f; line.color = Color.WHITE; canvas.drawCircle(e.x.toFloat(), axisY.toFloat(), f * 0.35f, line) }
            label(canvas, c.categories[e.index], e.x, if (e.above) y - f * 0.25 else y + f * 0.95, f * 0.85f, Paint.Align.CENTER, ink)
            if (c.showValues) c.series.getOrNull(0)?.at(e.index)?.let { label(canvas, if (c.dateAxis) ChartMaths.dateLabel(it) else ChartMaths.format(it), e.x, if (e.above) y - f * 1.1 else y + f * 1.75, f * 0.65f, Paint.Align.CENTER, 0xFF757575.toInt()) }
        }
        if (c.marker != 0L) {
            val v = c.marker / 1000.0
            val x = (p.l + scale.fraction(v) * (p.r - p.l)).toFloat()
            line.strokeWidth = f * 0.12f; line.color = Chart.DOWN
            line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(f * 0.5f, f * 0.3f), 0f)
            canvas.drawLine(x, p.t.toFloat(), x, p.b.toFloat(), line); line.pathEffect = null
            label(canvas, if (c.dateAxis) ChartMaths.dateLabel(v) else ChartMaths.format(v), x + f * 0.25, p.t + f * 0.9, f * 0.75f, Paint.Align.LEFT, Chart.DOWN)
        }
    }

    // ---- calendar heatmap ----

    private fun calendar(canvas: Canvas, c: Chart, p: Box, ink: Int) {
        val f = c.textSize
        val cal = ChartMaths.calendar(c, p) ?: return
        val left = p.l + f * 2.6; val top = p.t + f * 1.6
        for ((row, name) in listOf(0 to "Mon", 2 to "Wed", 4 to "Fri")) label(canvas, name, left - f * 0.3, top + cal.cell * (row + 0.5) + f * 0.25, f * 0.65f, Paint.Align.RIGHT, 0xFF757575.toInt())
        for (m in cal.months) label(canvas, m.name, left + cal.cell * m.col, top - f * 0.35, f * 0.75f, Paint.Align.LEFT, ink)
        val d = if (c.threeD) minOf(f * 0.9, cal.cell * 0.5) else 0.0
        for (day in cal.days) {
            val b = day.box
            if (day.value == null) { solid.color = 0xFFF0F0F0.toInt(); canvas.drawRect(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat(), solid); continue }
            box3d(canvas, b, ChartMaths.heatColour(c, day.t), d * (0.25 + 0.75 * day.t))
            if (c.showValues && cal.cell > f * 1.6) label(canvas, ChartMaths.format(day.value), (b.l + b.r) / 2, (b.t + b.b) / 2 + f * 0.25, f * 0.6f, Paint.Align.CENTER, if (day.t > 0.55) Color.WHITE else Color.BLACK)
        }
    }

    // ---- function graph ----

    private fun function(canvas: Canvas, c: Chart, p: Box, ink: Int, px: Float) {
        val f = c.textSize
        val (x0, x1) = ChartMaths.functionX(c); val (y0, y1) = ChartMaths.functionY(c)
        fun sx(x: Double) = p.l + (x - x0) / (x1 - x0) * (p.r - p.l)
        fun sy(y: Double) = p.b - (y - y0) / (y1 - y0) * (p.b - p.t)
        val xAxisY = if (y0 <= 0.0 && y1 >= 0.0) sy(0.0) else p.b // axes cross at zero when it is on show, otherwise sit on the edge
        val yAxisX = if (x0 <= 0.0 && x1 >= 0.0) sx(0.0) else p.l
        for (t in ChartMaths.axisTicks(x0, x1)) {
            if (!c.hideGrid) { line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt(); canvas.drawLine(sx(t).toFloat(), p.t.toFloat(), sx(t).toFloat(), p.b.toFloat(), line) }
            if (t != 0.0 || yAxisX == p.l) label(canvas, ChartMaths.format(t), sx(t), xAxisY + f * 1.1, f * 0.7f, Paint.Align.CENTER, ink)
        }
        for (t in ChartMaths.axisTicks(y0, y1)) {
            if (!c.hideGrid) { line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt(); canvas.drawLine(p.l.toFloat(), sy(t).toFloat(), p.r.toFloat(), sy(t).toFloat(), line) }
            if (t != 0.0 || xAxisY == p.b) label(canvas, ChartMaths.format(t), yAxisX - f * 0.3, sy(t) + f * 0.25, f * 0.7f, Paint.Align.RIGHT, ink)
        }
        line.strokeWidth = px * 1.5f; line.color = ink
        canvas.drawLine(p.l.toFloat(), xAxisY.toFloat(), p.r.toFloat(), xAxisY.toFloat(), line)
        canvas.drawLine(yAxisX.toFloat(), p.t.toFloat(), yAxisX.toFloat(), p.b.toFloat(), line)
        canvas.save()
        canvas.clipRect(p.l.toFloat(), p.t.toFloat(), p.r.toFloat() + if (c.threeD) f else 0f, p.b.toFloat())
        val o = if (c.threeD) (f * 0.5 * 0.6).toFloat() else 0f
        for ((j, pieces) in ChartMaths.curves(c, p).withIndex()) {
            val colour = c.series[j].color
            for (piece in pieces) {
                if (o > 0f) {
                    solid.color = shade(colour, 0.6f)
                    for (k in 0 until piece.size - 1) ribbonQuad(canvas, piece[k].first.toFloat(), piece[k].second.toFloat(), piece[k + 1].first.toFloat(), piece[k + 1].second.toFloat(), o, solid.color, c.textSize * 0.04f)
                }
                path.reset(); piece.forEachIndexed { k, q -> if (k == 0) path.moveTo(q.first.toFloat(), q.second.toFloat()) else path.lineTo(q.first.toFloat(), q.second.toFloat()) }
                line.strokeWidth = f * 0.2f; line.color = colour; canvas.drawPath(path, line)
            }
        }
        canvas.restore()
    }

    // ---- pie and donut ----

    private fun annulus(path: Path, cx: Float, cy: Float, r: Float, hole: Float, startDeg: Float, sweepDeg: Float) {
        path.reset()
        val outer = RectF(cx - r, cy - r, cx + r, cy + r)
        if (hole <= 0f) { path.moveTo(cx, cy); path.arcTo(outer, startDeg, sweepDeg); path.close(); return }
        val inner = RectF(cx - hole, cy - hole, cx + hole, cy + hole)
        path.arcTo(outer, startDeg, sweepDeg); path.arcTo(inner, startDeg + sweepDeg, -sweepDeg); path.close()
    }

    private fun pie(canvas: Canvas, c: Chart, p: Box, ink: Int) {
        val f = c.textSize
        val tilt = if (c.threeD) 0.6f else 1f
        val depth = if (c.threeD) f * 1.2f else 0f
        val r = (minOf(p.r - p.l, (p.b - p.t - depth) / tilt) / 2 * 0.95).toFloat()
        val cx = ((p.l + p.r) / 2).toFloat(); val cy = ((p.t + p.b - depth) / 2).toFloat()
        val hole = if (c.kind == ChartKind.DONUT) (r * c.holeFraction).toFloat() else 0f
        val slices = ChartMaths.pie(c)
        canvas.save()
        canvas.scale(1f, tilt, cx, cy)
        if (depth > 0f) { // the rim: the same slices stacked downwards, darker
            val layers = 14
            for (k in layers downTo 1) {
                canvas.save(); canvas.translate(0f, depth / tilt * k / layers)
                for (s in slices) { annulus(path, cx, cy, r, hole, Math.toDegrees(s.start).toFloat(), Math.toDegrees(s.sweep).toFloat()); solid.color = shade(Chart.colourFor(s.index), 0.65f); canvas.drawPath(path, solid) }
                canvas.restore()
            }
        }
        for (s in slices) { annulus(path, cx, cy, r, hole, Math.toDegrees(s.start).toFloat(), Math.toDegrees(s.sweep).toFloat()); solid.color = Chart.colourFor(s.index); canvas.drawPath(path, solid) }
        canvas.restore()
        val s0 = c.series.firstOrNull()
        for (s in slices) {
            if (s.share < 0.05 && !c.showValues) continue
            val mid = s.start + s.sweep / 2
            val rr = if (hole > 0f) (r + hole) / 2 else r * 0.65f
            val shown = if (c.showValues) ChartMaths.format(s0?.at(s.index) ?: 0.0) else "${Math.round(s.share * 100)}%"
            label(canvas, shown, cx + rr * Math.cos(mid), cy + rr * Math.sin(mid) * tilt + f * 0.3, f * 0.9f, Paint.Align.CENTER, Color.WHITE, true)
        }
    }

    // ---- radar, polar area, funnel, gauge, sparkline ----

    private fun radar(canvas: Canvas, c: Chart, p: Box, ink: Int, px: Float) {
        val n = c.categories.size
        if (n < 3) return
        val f = c.textSize
        val r = (minOf(p.r - p.l, p.b - p.t) / 2 - f * 1.6)
        val cx = (p.l + p.r) / 2; val cy = (p.t + p.b) / 2
        val (scale, series) = ChartMaths.radar(c, cx, cy, r)
        line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt()
        for (t in scale.ticks.drop(1)) { // the rings
            val rr = r * scale.fraction(t)
            path.reset()
            for (i in 0 until n) { val a = -Math.PI / 2 + 2 * Math.PI * i / n; val x = (cx + rr * Math.cos(a)).toFloat(); val y = (cy + rr * Math.sin(a)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            path.close(); canvas.drawPath(path, line)
        }
        for (i in 0 until n) {
            val a = -Math.PI / 2 + 2 * Math.PI * i / n
            canvas.drawLine(cx.toFloat(), cy.toFloat(), (cx + r * Math.cos(a)).toFloat(), (cy + r * Math.sin(a)).toFloat(), line)
            val align = if (Math.cos(a) > 0.3) Paint.Align.LEFT else if (Math.cos(a) < -0.3) Paint.Align.RIGHT else Paint.Align.CENTER
            label(canvas, c.categories[i], cx + (r + f * 0.5) * Math.cos(a), cy + (r + f * 0.5) * Math.sin(a) + f * 0.3, f * 0.8f, align, ink)
        }
        val o = if (c.threeD) (f * 0.9 * 0.6).toFloat() else 0f
        for ((j, pts) in series.withIndex()) {
            val colour = c.series[j].color
            if (o > 0f) { // the walls of the slab, behind the front face
                solid.color = shade(colour, 0.65f)
                for (i in pts.indices) {
                    val a = pts[i]; val b = pts[(i + 1) % pts.size]
                    path.reset(); path.moveTo(a.first.toFloat(), a.second.toFloat()); path.lineTo(b.first.toFloat(), b.second.toFloat())
                    path.lineTo(b.first.toFloat() + o, b.second.toFloat() - o); path.lineTo(a.first.toFloat() + o, a.second.toFloat() - o); path.close(); canvas.drawPath(path, solid)
                }
            }
            path.reset()
            for ((i, q) in pts.withIndex()) if (i == 0) path.moveTo(q.first.toFloat(), q.second.toFloat()) else path.lineTo(q.first.toFloat(), q.second.toFloat())
            path.close()
            if (c.filled) { solid.color = (colour and 0x00FFFFFF) or 0x66000000; canvas.drawPath(path, solid) }
            line.strokeWidth = f * 0.18f; line.color = colour
            canvas.drawPath(path, line)
            if (!c.hideMarkers) { solid.color = colour; for (q in pts) canvas.drawCircle(q.first.toFloat(), q.second.toFloat(), f * 0.28f, solid) }
        }
    }

    private fun polar(canvas: Canvas, c: Chart, p: Box, ink: Int, px: Float) {
        val f = c.textSize
        val tilt = if (c.threeD) 0.6f else 1f
        val depth = if (c.threeD) f * 1.2f else 0f
        val r = (minOf(p.r - p.l, (p.b - p.t - depth) / tilt) / 2 - f * 1.4).toFloat()
        val cx = ((p.l + p.r) / 2).toFloat(); val cy = ((p.t + p.b - depth) / 2).toFloat()
        val wedges = ChartMaths.polar(c)
        canvas.save()
        canvas.scale(1f, tilt, cx, cy)
        if (depth > 0f) { // the rim: the same wedges stacked downwards, darker
            val layers = 14
            for (k in layers downTo 1) {
                canvas.save(); canvas.translate(0f, depth / tilt * k / layers)
                for (w in wedges) { annulus(path, cx, cy, r * w.radius.toFloat(), 0f, Math.toDegrees(w.start).toFloat(), Math.toDegrees(w.sweep).toFloat()); solid.color = shade(Chart.colourFor(w.index), 0.65f); canvas.drawPath(path, solid) }
                canvas.restore()
            }
        }
        line.strokeWidth = px; line.color = 0xFFE0E0E0.toInt()
        for (k in 1..4) canvas.drawCircle(cx, cy, r * k / 4, line)
        for (w in wedges) { annulus(path, cx, cy, r * w.radius.toFloat(), 0f, Math.toDegrees(w.start).toFloat(), Math.toDegrees(w.sweep).toFloat()); solid.color = Chart.colourFor(w.index); canvas.drawPath(path, solid) }
        canvas.restore()
        for (w in wedges) {
            val mid = w.start + w.sweep / 2
            label(canvas, c.categories[w.index], cx + (r + f * 0.6) * Math.cos(mid), cy + (r + f * 0.6) * Math.sin(mid) * tilt + f * 0.3, f * 0.8f, Paint.Align.CENTER, ink)
        }
    }

    private fun funnel(canvas: Canvas, c: Chart, p: Box) {
        val f = c.textSize
        val cx = (p.l + p.r) / 2
        val s0 = c.series.firstOrNull()
        for (st in ChartMaths.funnel(c, p)) {
            path.reset()
            path.moveTo((cx - st.topW / 2).toFloat(), st.top.toFloat()); path.lineTo((cx + st.topW / 2).toFloat(), st.top.toFloat())
            path.lineTo((cx + st.bottomW / 2).toFloat(), st.bottom.toFloat()); path.lineTo((cx - st.bottomW / 2).toFloat(), st.bottom.toFloat()); path.close()
            solid.color = Chart.colourFor(st.index); canvas.drawPath(path, solid)
            if (c.threeD) { // the right side and the top of each stage
                val o = (f * 0.9 * 0.6).toFloat()
                val tr = (cx + st.topW / 2).toFloat(); val br = (cx + st.bottomW / 2).toFloat(); val tl = (cx - st.topW / 2).toFloat()
                solid.color = shade(Chart.colourFor(st.index), 0.7f)
                path.reset(); path.moveTo(tr, st.top.toFloat()); path.lineTo(tr + o, st.top.toFloat() - o); path.lineTo(br + o, st.bottom.toFloat() - o); path.lineTo(br, st.bottom.toFloat()); path.close(); canvas.drawPath(path, solid)
                solid.color = shade(Chart.colourFor(st.index), 1.2f)
                path.reset(); path.moveTo(tl, st.top.toFloat()); path.lineTo(tl + o, st.top.toFloat() - o); path.lineTo(tr + o, st.top.toFloat() - o); path.lineTo(tr, st.top.toFloat()); path.close(); canvas.drawPath(path, solid)
            }
            val name = c.categories[st.index] + "  " + ChartMaths.format(s0?.at(st.index) ?: 0.0)
            text.textSize = f * 0.85f; text.isFakeBoldText = true
            val narrow = text.measureText(name) > minOf(st.topW, st.bottomW) * 0.9 // too tight to read inside: put it beside, in ink
            if (narrow) label(canvas, name, cx + maxOf(st.topW, st.bottomW) / 2 + f * 0.4, (st.top + st.bottom) / 2 + f * 0.3, f * 0.85f, Paint.Align.LEFT, Color.DKGRAY, true)
            else label(canvas, name, cx, (st.top + st.bottom) / 2 + f * 0.3, f * 0.85f, Paint.Align.CENTER, Color.WHITE, true)
        }
    }

    private fun gauge(canvas: Canvas, c: Chart, p: Box, ink: Int) {
        val f = c.textSize
        val dial = ChartMaths.gauge(c) ?: return
        val depth = if (c.threeD) f * 1.0f else 0f
        val r = minOf((p.r - p.l) / 2, (p.b - p.t - depth) * 0.9).toFloat()
        val cx = ((p.l + p.r) / 2).toFloat(); val cy = (((p.t + p.b - depth) / 2) + r / 2).toFloat()
        val w = r * 0.22f
        val oval = RectF(cx - r + w / 2, cy - r + w / 2, cx + r - w / 2, cy + r - w / 2)
        val valueColour = c.series.firstOrNull()?.color ?: Chart.colourFor(0)
        line.strokeCap = Paint.Cap.BUTT; line.strokeWidth = w
        if (depth > 0f) { // the thickness of the dial: the arcs again, lower down and darker
            for (k in 30 downTo 1) {
                canvas.save(); canvas.translate(0f, depth * k / 30)
                line.color = shade(0xFFE0E0E0.toInt(), 0.7f); canvas.drawArc(oval, 180f, 180f, false, line)
                line.color = shade(valueColour, 0.65f); canvas.drawArc(oval, 180f, (180 * dial.fraction).toFloat(), false, line)
                canvas.restore()
            }
        }
        line.color = 0xFFE0E0E0.toInt(); canvas.drawArc(oval, 180f, 180f, false, line)
        line.color = valueColour; canvas.drawArc(oval, 180f, (180 * dial.fraction).toFloat(), false, line)
        line.strokeCap = Paint.Cap.ROUND
        dial.target?.let {
            val a = Math.PI + Math.PI * it
            line.strokeWidth = f * 0.2f; line.color = ink
            canvas.drawLine((cx + (r - w * 1.1f) * Math.cos(a)).toFloat(), (cy + (r - w * 1.1f) * Math.sin(a)).toFloat(), (cx + (r + w * 0.1f) * Math.cos(a)).toFloat(), (cy + (r + w * 0.1f) * Math.sin(a)).toFloat(), line)
        }
        label(canvas, ChartMaths.format(dial.value), cx.toDouble(), cy - r * 0.15, f * 2f, Paint.Align.CENTER, ink, true)
        val (lo, hi) = c.gaugeRange
        label(canvas, ChartMaths.format(lo), (cx - r + w / 2).toDouble(), cy + f * 1.2, f * 0.8f, Paint.Align.CENTER, ink)
        label(canvas, ChartMaths.format(hi), (cx + r - w / 2).toDouble(), cy + f * 1.2, f * 0.8f, Paint.Align.CENTER, ink)
    }

    private fun sparkline(canvas: Canvas, c: Chart, p: Box, px: Float) {
        val f = c.textSize
        val s = c.series.firstOrNull() ?: return
        val one = c.copy(series = listOf(s))
        val (lo, hi) = ChartMaths.valueRange(one) ?: return
        val scale = ChartMaths.niceScale(lo, hi, 3, zero = false)
        val pts = ChartMaths.linePoints(one, p, scale)[0]
        val colour = s.color
        val o = if (c.threeD) (f * 0.9 * 0.6).toFloat() else 0f
        for (run in runs(pts)) {
            val edge = polyline(run.map { it.second }, c.curve)
            if (o > 0f && edge.size > 1) {
                solid.color = shade(colour, 0.6f)
                for (k in 0 until edge.size - 1) ribbonQuad(canvas, edge[k].first.toFloat(), edge[k].second.toFloat(), edge[k + 1].first.toFloat(), edge[k + 1].second.toFloat(), o, solid.color, c.textSize * 0.04f)
            }
            line.strokeWidth = f * 0.25f; line.color = colour; path.reset(); addCurve(path, run.map { it.second }, c.curve); canvas.drawPath(path, line)
        }
        solid.color = colour
        val have = pts.withIndex().filter { it.value != null }
        if (have.isNotEmpty() && !c.hideMarkers) {
            val last = have.last().value!!; canvas.drawCircle(last.first.toFloat(), last.second.toFloat(), f * 0.4f, solid)
            solid.color = Chart.DOWN; have.minByOrNull { it.value!!.second * -1 }?.value?.let { canvas.drawCircle(it.first.toFloat(), it.second.toFloat(), f * 0.28f, solid) }
            solid.color = Chart.UP; have.minByOrNull { it.value!!.second }?.value?.let { canvas.drawCircle(it.first.toFloat(), it.second.toFloat(), f * 0.28f, solid) }
        }
    }

    private fun legend(canvas: Canvas, c: Chart, box: Box, ink: Int) {
        val f = c.textSize
        val names = ChartMaths.legendNames(c)
        val colours = when (c.kind) {
            ChartKind.PIE, ChartKind.DONUT, ChartKind.POLAR, ChartKind.FUNNEL -> c.categories.indices.map { Chart.colourFor(it) }
            ChartKind.TREEMAP -> names.indices.map { Chart.colourFor(it) } // one colour for each group, in the order the groups first appear
            else -> c.series.map { it.color }
        }
        text.textSize = f * 0.9f; text.textAlign = Paint.Align.LEFT; text.isFakeBoldText = false
        val sw = f * 0.8f
        val widths = names.map { sw + f * 0.4f + text.measureText(it) + f * 1.2f }
        var x = (((box.l + box.r) / 2) - widths.sum() / 2).coerceAtLeast(box.l).toFloat()
        val y = ((box.t + box.b) / 2).toFloat()
        for ((k, name) in names.withIndex()) {
            if (x + widths[k] > box.r + f) break // no room for the rest
            solid.color = colours[k]
            canvas.drawRect(x, y - sw / 2, x + sw, y + sw / 2, solid)
            text.color = ink
            canvas.drawText(name, x + sw + f * 0.4f, y + f * 0.3f, text)
            x += widths[k]
        }
    }
}
