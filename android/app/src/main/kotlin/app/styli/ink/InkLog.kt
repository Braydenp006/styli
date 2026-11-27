package app.styli.ink

import app.styli.ink.proto.Event
import app.styli.ink.proto.Header
import app.styli.ink.proto.PageSet
import app.styli.ink.proto.Record
import app.styli.ink.proto.StrokeAdded
import app.styli.ink.proto.StrokeErased
import app.styli.ink.proto.TextBoxRemoved
import app.styli.ink.proto.TextBoxSet
import app.styli.ink.proto.Transformed
import app.styli.ink.proto.ZMoved
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt

/** Where a picture's file sits, relative to the page's log (see [Vault]). */
internal const val PICTURES = "pictures/"

/** The format version this code writes and the newest it reads (see `proto/ink.proto`). */
private const val FORMAT_VERSION = 1

private fun idBytes(id: String): ByteString {
    require(id.length % 2 == 0 && id.all { it in "0123456789abcdef" }) { "an ID must be hex (16 random bytes), not \"$id\"" }
    return ByteString.copyFrom(ByteArray(id.length / 2) { id.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}

private fun idString(b: ByteString) = b.toByteArray().joinToString("") { "%02x".format(it) }

/** Writes a page's events as the ink log: records, each a varint length then a Record message. [device] and a counter go on every event. */
class InkLogWriter(private val device: String, private var seq: Long = 0, private val clock: () -> Long = System::currentTimeMillis) {
    /** The next number this writer will use. Keep it with the device's files so numbers never repeat. */
    val nextSeq get() = seq

    /** The first record of a new log. */
    fun header(): ByteArray = delimited(Record.newBuilder().setHeader(Header.newBuilder().setVersion(FORMAT_VERSION)).build())

    /** [events], as records to append to a log. */
    fun encode(events: List<Ev>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in events) out.write(delimited(Record.newBuilder().setEvent(event(e)).build()))
        return out.toByteArray()
    }

    private fun delimited(r: Record): ByteArray = ByteArrayOutputStream().also { r.writeDelimitedTo(it) }.toByteArray()

    private fun event(e: Ev): Event {
        val b = Event.newBuilder().setDevice(device).setSeq(seq++).setAtMs(clock())
        when (e) {
            is Ev.Added -> b.setId(idBytes(e.item.id)).setStrokeAdded(stroke(e.item))
            is Ev.Erased -> b.setId(idBytes(e.id)).setStrokeErased(StrokeErased.getDefaultInstance())
            is Ev.TextSet -> b.setId(idBytes(e.box.id)).setTextBoxSet(text(e.box))
            is Ev.TextRemoved -> b.setId(idBytes(e.id)).setTextBoxRemoved(TextBoxRemoved.getDefaultInstance())
            is Ev.PageChanged -> b.setPageSet(e.page)
            is Ev.ZMoved -> b.setId(idBytes(e.id)).setZMoved(ZMoved.newBuilder().apply { e.above?.let { setAbove(idBytes(it)) } })
            is Ev.Transformed -> b.setTransformed(
                Transformed.newBuilder().addAllIds(e.ids.map(::idBytes))
                    .setA(e.map.a).setB(e.map.b).setC(e.map.c).setD(e.map.d).setE(e.map.e).setF(e.map.f),
            )
            is Ev.TableSet -> b.setId(idBytes(e.table.id)).setTableSet(table(e.table))
            is Ev.TableRemoved -> b.setId(idBytes(e.id)).setTableRemoved(app.styli.ink.proto.TableRemoved.getDefaultInstance())
            is Ev.ShapeSet -> b.setId(idBytes(e.shape.id)).setShapeSet(shape(e.shape))
            is Ev.ShapeRemoved -> b.setId(idBytes(e.id)).setShapeRemoved(app.styli.ink.proto.ShapeRemoved.getDefaultInstance())
            is Ev.ConnectorSet -> b.setId(idBytes(e.connector.id)).setConnectorSet(connector(e.connector))
            is Ev.ConnectorRemoved -> b.setId(idBytes(e.id)).setConnectorRemoved(app.styli.ink.proto.ConnectorRemoved.getDefaultInstance())
            is Ev.FillSet -> b.setId(idBytes(e.fill.id)).setFillSet(fill(e.fill))
            is Ev.FillRemoved -> b.setId(idBytes(e.id)).setFillRemoved(app.styli.ink.proto.FillRemoved.getDefaultInstance())
            is Ev.PictureSet -> b.setId(idBytes(e.picture.id)).setImageSet(picture(e.picture))
            is Ev.PictureRemoved -> b.setId(idBytes(e.id)).setImageRemoved(app.styli.ink.proto.ImageRemoved.getDefaultInstance())
            is Ev.ChartSet -> b.setId(idBytes(e.chart.id)).setChartSet(chart(e.chart))
            is Ev.ChartRemoved -> b.setId(idBytes(e.id)).setChartRemoved(app.styli.ink.proto.ChartRemoved.getDefaultInstance())
        }
        return b.build()
    }

    private fun stroke(item: Item): StrokeAdded {
        val b = StrokeAdded.newBuilder().setToolValue(item.tool.number).setColor(item.color).setWidth(item.width)
        val xs = item.pts.map { it.x.roundToInt() }; val ys = item.pts.map { it.y.roundToInt() }
        b.setX0(xs[0]).setY0(ys[0])
        for (i in 1 until xs.size) { b.addDx(xs[i] - xs[i - 1]); b.addDy(ys[i] - ys[i - 1]); b.addDt((item.pts[i].t - item.pts[i - 1].t).coerceAtLeast(0).toInt()) }
        b.setPressure(ByteString.copyFrom(ByteArray(item.pts.size) { (item.pts[it].p.coerceIn(0f, 1f) * 255f).roundToInt().toByte() }))
        return b.build()
    }

    private fun table(t: Table) = app.styli.ink.proto.TableSet.newBuilder()
        .setX(t.x.roundToInt()).setY(t.y.roundToInt()).addAllColWidths(t.colWidths.map { it.roundToInt() }).addAllRowHeights(t.rowHeights.map { it.roundToInt() })
        .setLineWidth(t.lineWidth.roundToInt()).setColor(t.color).setFill(t.fill).addAllCellFills(t.cellFills).addAllCellText(t.cellText)
        .setFontSize(t.fontSize.roundToInt()).setTextColor(t.textColor).setHeaderRow(t.headerRow).addAllColAlign(t.colAlign).build()

    private fun shape(s: Shape) = app.styli.ink.proto.ShapeSet.newBuilder()
        .setKindValue(s.kind.ordinal).setX(s.x.roundToInt()).setY(s.y.roundToInt()).setWidth(s.width.roundToInt()).setHeight(s.height.roundToInt())
        .setAngle(deg100(s.angle)).setColor(s.color).setLineWidth(s.lineWidth.roundToInt())
        .setLineStyle(if (s.dashed) app.styli.ink.proto.LineStyle.LINE_DASHED else app.styli.ink.proto.LineStyle.LINE_SOLID)
        .setFill(s.fill).setCorner(s.corner.roundToInt())
        .addAllVertices(s.vertices.flatMap { listOf(frac(it.first), frac(it.second)) })
        .setText(s.text).setTextColor(s.textColor).setFontSize(s.fontSize.roundToInt()).build()

    private fun end(e: End) = app.styli.ink.proto.ConnectorEnd.newBuilder().apply {
        e.shape?.let { setShape(idBytes(it)) }
        setAnchor(e.anchor).setX(e.x.roundToInt()).setY(e.y.roundToInt()).setFx(frac(e.fx)).setFy(frac(e.fy))
    }.build()

    private fun connector(c: Connector) = app.styli.ink.proto.ConnectorSet.newBuilder()
        .setFrom(end(c.from)).setTo(end(c.to)).setFromHeadValue(c.fromHead.ordinal).setToHeadValue(c.toHead.ordinal)
        .setColor(c.color).setLineWidth(c.lineWidth.roundToInt())
        .setLineStyle(if (c.dashed) app.styli.ink.proto.LineStyle.LINE_DASHED else app.styli.ink.proto.LineStyle.LINE_SOLID)
        .setRouteValue(c.route.ordinal).setBend(c.bend.roundToInt())
        .addAllVia(c.via.flatMap { listOf(it.first.roundToInt(), it.second.roundToInt()) }).build()

    private fun fill(f: Fill) = app.styli.ink.proto.FillSet.newBuilder().setColor(f.color).addAllRings(f.rings.map { ring ->
        val xs = ring.map { it.first.roundToInt() }; val ys = ring.map { it.second.roundToInt() }
        app.styli.ink.proto.Ring.newBuilder().apply {
            if (xs.isNotEmpty()) setX0(xs[0]).setY0(ys[0])
            for (i in 1 until xs.size) { addDx(xs[i] - xs[i - 1]); addDy(ys[i] - ys[i - 1]) }
        }.build()
    }).build()

    private fun picture(p: Picture) = app.styli.ink.proto.ImageSet.newBuilder()
        .setX(p.x.roundToInt()).setY(p.y.roundToInt()).setWidth(p.width.roundToInt()).setHeight(p.height.roundToInt()).setAngle(deg100(p.angle))
        .setCropX(frac(p.cropX)).setCropY(frac(p.cropY)).setCropW(frac(p.cropW)).setCropH(frac(p.cropH))
        .setFile(PICTURES + p.file).setSha256(idBytes(p.sha)).setSrcWidth(p.srcWidth).setSrcHeight(p.srcHeight).build()

    private fun series(s: ChartSeries) = app.styli.ink.proto.ChartSeries.newBuilder()
        .setName(s.name).setColor(s.color).addAllValues(s.values).addAllMissing(s.missing.sorted()).setKindValue(s.kind.ordinal)
        .setSecondary(s.secondary).setTrendline(s.trendline).addAllXs(s.xs).addAllSizes(s.sizes).addAllErrors(s.errors).setExpr(s.expr).build()

    private fun chart(c: Chart) = app.styli.ink.proto.ChartSet.newBuilder().apply {
        setKindValue(c.kind.ordinal).setX(c.x.roundToInt()).setY(c.y.roundToInt()).setWidth(c.width.roundToInt()).setHeight(c.height.roundToInt())
        setAngle(deg100(c.angle)).setTitle(c.title).addAllCategories(c.categories).addAllSeries(c.series.map(::series))
        setXLabel(c.xLabel).setYLabel(c.yLabel).setHideLegend(c.hideLegend).setHideGrid(c.hideGrid).setStacked(c.stacked)
        setTextColor(c.textColor).setFontSize(c.fontSize.roundToInt())
        c.source?.let { setSourceTable(idBytes(it.table)).setSrcRow(it.row).setSrcCol(it.col).setSrcRows(it.rows).setSrcCols(it.cols).setSeriesInRows(it.seriesInRows) }
        setHorizontal(c.horizontal).setPercent(c.percent).setCurveValue(c.curve.ordinal).setHideMarkers(c.hideMarkers).setHole(c.hole)
        setFilled(c.filled).setThreeD(c.threeD).addAllTotals(c.totals.sorted()).setShowValues(c.showValues)
        setGaugeMin(c.gaugeMin).setGaugeMax(c.gaugeMax).setPyramid(c.pyramid).setDateAxis(c.dateAxis).addAllGroups(c.groups)
        setBins(c.bins).setBinWidth(c.binWidth).setHideOutliers(c.hideOutliers).setShowMean(c.showMean).setMarker(c.marker)
        setHeatLow(c.heatLow).setHeatHigh(c.heatHigh).setXMin(c.xMin).setXMax(c.xMax).setYMin(c.yMin).setYMax(c.yMax)
    }.build()

    private fun deg100(radians: Float) = (radians * 18000.0 / PI).roundToInt()
    private fun frac(f: Float) = (f * 10000f).roundToInt().coerceAtLeast(0)

    private fun text(t: TextBox) = TextBoxSet.newBuilder()
        .setX(t.x.roundToInt()).setY(t.y.roundToInt()).setWidth(t.width.roundToInt()).setFontSize(t.fontSize.roundToInt())
        .setText(t.text).setColor(t.color).setHighlight(t.highlight)
        .setAngle(deg100(t.angle)) // radians to 0.01 degrees
        .build()
}

/** Reads an ink log back into a page. A cut-off last record is ignored; a newer format version is refused. */
object InkLogReader {
    /** What was read: the page, and the highest event number seen for [device] (so a writer carries on after it). */
    class Result(val page: PageModel, val lastSeq: Map<String, Long>)

    fun read(bytes: ByteArray): Result {
        var at = 0
        var page = PageModel.A4_LINED
        val items = LinkedHashMap<String, Item>()
        val texts = LinkedHashMap<String, TextBox>()
        val order = ArrayList<String>()
        val tables = LinkedHashMap<String, Table>(); val shapes = LinkedHashMap<String, Shape>(); val connectors = LinkedHashMap<String, Connector>()
        val fills = LinkedHashMap<String, Fill>(); val pictures = LinkedHashMap<String, Picture>(); val charts = LinkedHashMap<String, Chart>()
        // A new object takes its place by the format's plain rules: tables and fills at the bottom, everything else on top.
        fun place(id: String, known: Boolean, bottom: Boolean) { if (!known) { if (bottom) order.add(0, id) else order.add(id) } }
        fun drop(id: String, from: MutableMap<String, *>) { from.remove(id); order.remove(id) }
        val last = HashMap<String, Long>()
        var first = true
        while (true) {
            // Read the length by hand: a record cut short by a crash must be dropped whole. The protobuf reader would
            // take the half that is there as a (wrong) shorter message, so check the bytes are all present first.
            var len = 0; var shift = 0; var pos = at
            while (true) {
                if (pos >= bytes.size) break
                val b = bytes[pos++].toInt()
                len = len or ((b and 0x7F) shl shift); shift += 7
                if (b and 0x80 == 0) { shift = -1; break }
                if (shift > 28) break
            }
            if (shift != -1 || pos + len > bytes.size) break // the length itself, or the record, is cut short (a crash while writing)
            val rec = try { Record.parseFrom(bytes.copyOfRange(pos, pos + len)) } catch (e: com.google.protobuf.InvalidProtocolBufferException) { throw IllegalArgumentException("a record in the log is damaged", e) }
            at = pos + len
            if (first) {
                first = false
                require(rec.hasHeader()) { "not an ink log: it does not start with a header" }
                require(rec.header.version <= FORMAT_VERSION) { "this log is a newer format (${rec.header.version})" }
                continue
            }
            if (!rec.hasEvent()) continue
            val e = rec.event
            if (e.device.isNotEmpty()) last[e.device] = maxOf(last[e.device] ?: -1, e.seq)
            val id = idString(e.id)
            when (e.kindCase) {
                Event.KindCase.STROKE_ADDED -> {
                    val s = e.strokeAdded
                    var x = s.x0; var y = s.y0; var t = 0L
                    val pressure = s.pressure.toByteArray()
                    fun p(i: Int) = if (i < pressure.size) (pressure[i].toInt() and 0xFF) / 255f else 1f
                    val pts = ArrayList<Pt>(s.dxCount + 1)
                    pts.add(Pt(x.toFloat(), y.toFloat(), 0, p(0)))
                    for (i in 0 until s.dxCount) {
                        x += s.getDx(i); y += s.getDy(i); if (i < s.dtCount) t += s.getDt(i)
                        pts.add(Pt(x.toFloat(), y.toFloat(), t, p(i + 1)))
                    }
                    if (id !in items) order.add(id)
                    items[id] = Item(id, app.styli.ink.proto.Tool.forNumber(s.toolValue) ?: app.styli.ink.proto.Tool.TOOL_PEN, s.color, s.width, pts)
                }
                Event.KindCase.STROKE_ERASED -> { items.remove(id); order.remove(id) }
                Event.KindCase.TEXT_BOX_SET -> {
                    val t = e.textBoxSet
                    if (id !in texts) order.add(id)
                    texts[id] = TextBox(id, t.x.toFloat(), t.y.toFloat(), t.width.toFloat(), t.fontSize.toFloat(), t.text, t.color, t.highlight, (t.angle * PI / 18000.0).toFloat())
                }
                Event.KindCase.TEXT_BOX_REMOVED -> { texts.remove(id); order.remove(id) }
                Event.KindCase.TABLE_SET -> { place(id, id in tables, bottom = true); tables[id] = table(id, e.tableSet) }
                Event.KindCase.TABLE_REMOVED -> drop(id, tables)
                Event.KindCase.SHAPE_SET -> { place(id, id in shapes, false); shapes[id] = shape(id, e.shapeSet) }
                Event.KindCase.SHAPE_REMOVED -> drop(id, shapes)
                Event.KindCase.CONNECTOR_SET -> { place(id, id in connectors, false); connectors[id] = connector(id, e.connectorSet) }
                Event.KindCase.CONNECTOR_REMOVED -> drop(id, connectors)
                Event.KindCase.FILL_SET -> { place(id, id in fills, bottom = true); fills[id] = fill(id, e.fillSet) }
                Event.KindCase.FILL_REMOVED -> drop(id, fills)
                Event.KindCase.IMAGE_SET -> { place(id, id in pictures, false); pictures[id] = picture(id, e.imageSet) }
                Event.KindCase.IMAGE_REMOVED -> drop(id, pictures)
                Event.KindCase.CHART_SET -> { place(id, id in charts, false); charts[id] = chart(id, e.chartSet) }
                Event.KindCase.CHART_REMOVED -> drop(id, charts)
                Event.KindCase.PAGE_SET -> page = e.pageSet
                Event.KindCase.Z_MOVED -> {
                    order.remove(id)
                    val above = e.zMoved.above
                    val at = if (above.isEmpty) 0 else order.indexOf(idString(above)) + 1
                    order.add(at, id)
                }
                // A move or resize: whole text boxes, tables and the rest are written again with it, so only strokes change here.
                Event.KindCase.TRANSFORMED -> {
                    val m = e.transformed
                    val map = Affine(m.a, m.b, m.c, m.d, m.e, m.f)
                    for (target in m.idsList) { val k = idString(target); items[k]?.let { it2 -> items[k] = Item(k, it2.tool, it2.color, it2.width, it2.pts.map(map::apply)) } }
                }
                else -> {} // a kind this reader does not know is skipped, as the format says
            }
        }
        require(!first) { "not an ink log: it has no header" } // nothing at all, or only a damaged start
        val model = PageModel(page)
        model.load(page, items.values.toList(), texts.values.toList(), order, tables.values.toList(), shapes.values.toList(), connectors.values.toList(), fills.values.toList(), pictures.values.toList(), charts.values.toList())
        return Result(model, last)
    }

    private fun angle(a: Int) = (a * PI / 18000.0).toFloat()
    private fun frac(v: Int) = v / 10000f

    private fun table(id: String, t: app.styli.ink.proto.TableSet) = Table(
        id, t.x.toFloat(), t.y.toFloat(), t.colWidthsList.map { it.toFloat() }, t.rowHeightsList.map { it.toFloat() }, t.lineWidth.toFloat(),
        t.color, t.fill, t.cellFillsList.toList(), t.cellTextList.toList(), t.fontSize.toFloat(), t.textColor, t.headerRow, t.colAlignList.toList(),
    )

    private fun shape(id: String, s: app.styli.ink.proto.ShapeSet) = Shape(
        id, ShapeKind.entries.getOrElse(s.kindValue) { ShapeKind.RECT }, s.x.toFloat(), s.y.toFloat(), s.width.toFloat(), s.height.toFloat(), angle(s.angle),
        s.color, s.lineWidth.toFloat(), s.lineStyle == app.styli.ink.proto.LineStyle.LINE_DASHED, s.fill, s.corner.toFloat(),
        s.verticesList.chunked(2).filter { it.size == 2 }.map { frac(it[0]) to frac(it[1]) }, s.text, s.textColor, s.fontSize.toFloat(),
    )

    private fun end(e: app.styli.ink.proto.ConnectorEnd) =
        End(if (e.shape.isEmpty) null else idString(e.shape), e.anchor, e.x.toFloat(), e.y.toFloat(), frac(e.fx), frac(e.fy))

    private fun connector(id: String, c: app.styli.ink.proto.ConnectorSet) = Connector(
        id, end(c.from), end(c.to), Arrowhead.entries.getOrElse(c.fromHeadValue) { Arrowhead.NONE }, Arrowhead.entries.getOrElse(c.toHeadValue) { Arrowhead.NONE },
        c.color, c.lineWidth.toFloat(), c.lineStyle == app.styli.ink.proto.LineStyle.LINE_DASHED, Route.entries.getOrElse(c.routeValue) { Route.STRAIGHT }, c.bend.toFloat(),
        c.viaList.chunked(2).filter { it.size == 2 }.map { it[0].toFloat() to it[1].toFloat() },
    )

    private fun fill(id: String, f: app.styli.ink.proto.FillSet) = Fill(id, f.color, f.ringsList.map { r ->
        var x = r.x0; var y = r.y0
        val out = ArrayList<Pair<Float, Float>>(); out.add(x.toFloat() to y.toFloat())
        for (i in 0 until r.dxCount) { x += r.getDx(i); y += r.getDy(i); out.add(x.toFloat() to y.toFloat()) }
        out
    })

    private fun picture(id: String, p: app.styli.ink.proto.ImageSet) = Picture(
        id, p.x.toFloat(), p.y.toFloat(), p.width.toFloat(), p.height.toFloat(), p.file.removePrefix(PICTURES), idString(p.sha256), p.srcWidth, p.srcHeight, angle(p.angle),
        frac(p.cropX), frac(p.cropY), if (p.cropW == 0) 1f else frac(p.cropW), if (p.cropH == 0) 1f else frac(p.cropH), // a zero size means the whole file
    )

    private fun chart(id: String, c: app.styli.ink.proto.ChartSet) = Chart(
        id, ChartKind.entries.getOrElse(c.kindValue) { ChartKind.BAR }, c.x.toFloat(), c.y.toFloat(), c.width.toFloat(), c.height.toFloat(), c.title,
        c.categoriesList.toList(),
        c.seriesList.map { s ->
            ChartSeries(
                s.name, s.color, s.valuesList.toList(), s.missingList.toSet(), ChartKind.entries.getOrElse(s.kindValue) { ChartKind.BAR }, s.secondary, s.trendline,
                s.xsList.toList(), s.sizesList.toList(), s.errorsList.toList(), s.expr,
            )
        },
        c.xLabel, c.yLabel, angle(c.angle), c.hideLegend, c.hideGrid, c.stacked, c.textColor, c.fontSize.toFloat(),
        if (c.sourceTable.isEmpty) null else ChartSource(idString(c.sourceTable), c.srcRow, c.srcCol, c.srcRows, c.srcCols, c.seriesInRows),
        c.horizontal, c.percent, Curve.entries.getOrElse(c.curveValue) { Curve.STRAIGHT }, c.hideMarkers, c.hole, c.filled, c.threeD, c.totalsList.toSet(), c.showValues,
        c.gaugeMin, c.gaugeMax, c.pyramid, c.dateAxis, c.groupsList.toList(), c.bins, c.binWidth, c.hideOutliers, c.showMean, c.marker,
        c.heatLow, c.heatHigh, c.xMin, c.xMax, c.yMin, c.yMax,
    )
}
