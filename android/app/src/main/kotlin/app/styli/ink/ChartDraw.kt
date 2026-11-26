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
            for ((i, name) in c.categories.withIndex()) label(canvas, name, p.l + slot * (i + 0.5), p.b + f * 1.2, f * 0.8f, Paint.Align.CENTER, ink)
        }
        when (c.kind) {
            ChartKind.WATERFALL -> waterfall(canvas, c, p, scale, d, ink)
            ChartKind.CANDLESTICK -> candles(canvas, c, p, scale, px, ink)
            else -> {
                areas(canvas, c, p, scale, scale2, d)
                bars(canvas, c, p, scale, scale2, d, ink, side)
                lines(canvas, c, p, scale, scale2, d, ink)
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
                    for (k in 0 until edge.size - 1) {
                        path.reset(); path.moveTo(edge[k].first.toFloat(), edge[k].second.toFloat()); path.lineTo(edge[k + 1].first.toFloat(), edge[k + 1].second.toFloat())
                        path.lineTo(edge[k + 1].first.toFloat() + o, edge[k + 1].second.toFloat() - o); path.lineTo(edge[k].first.toFloat() + o, edge[k].second.toFloat() - o); path.close(); canvas.drawPath(path, solid)
                    }
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
                    for (k in 0 until edge.size - 1) {
                        path.reset(); path.moveTo(edge[k].first.toFloat(), edge[k].second.toFloat()); path.lineTo(edge[k + 1].first.toFloat(), edge[k + 1].second.toFloat())
                        path.lineTo(edge[k + 1].first.toFloat() + o, edge[k + 1].second.toFloat() - o); path.lineTo(edge[k].first.toFloat() + o, edge[k].second.toFloat() - o); path.close(); canvas.drawPath(path, solid)
                    }
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
                for (k in 0 until edge.size - 1) {
                    path.reset(); path.moveTo(edge[k].first.toFloat(), edge[k].second.toFloat()); path.lineTo(edge[k + 1].first.toFloat(), edge[k + 1].second.toFloat())
                    path.lineTo(edge[k + 1].first.toFloat() + o, edge[k + 1].second.toFloat() - o); path.lineTo(edge[k].first.toFloat() + o, edge[k].second.toFloat() - o); path.close(); canvas.drawPath(path, solid)
                }
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
