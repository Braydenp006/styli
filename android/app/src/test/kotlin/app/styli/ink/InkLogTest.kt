package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Writes a page's log with InkLogWriter and reads it back with InkLogReader: the page must come back the same.
class InkLogTest {
    private fun pts(vararg xy: Int) = xy.toList().chunked(2).mapIndexed { i, (x, y) -> Pt(x.toFloat(), y.toFloat(), i * 10L, 0.5f + i * 0.1f) }

    private fun save(page: PageModel, device: String = "george"): ByteArray {
        val w = InkLogWriter(device, clock = { 1000L })
        return w.header() + w.encode(page.log)
    }

    private fun assertSame(a: PageModel, b: PageModel) {
        assertEquals(a.page, b.page)
        assertEquals(a.order, b.order)
        assertEquals(a.items.map { it.id }, b.items.map { it.id })
        for ((x, y) in a.items.zip(b.items)) {
            assertEquals(x.tool, y.tool); assertEquals(x.color, y.color); assertEquals(x.width, y.width)
            assertEquals(x.pts.map { it.x to it.y }, y.pts.map { it.x to it.y })
            assertEquals(x.pts.map { it.t }, y.pts.map { it.t })
            // pressure is stored in 256 steps
            for ((p, q) in x.pts.zip(y.pts)) assertEquals(p.p.toDouble(), q.p.toDouble(), 1.0 / 255)
        }
        assertEquals(a.texts.size, b.texts.size)
        for ((x, y) in a.texts.zip(b.texts)) {
            assertEquals(x.id, y.id); assertEquals(x.text, y.text); assertEquals(x.x, y.x); assertEquals(x.y, y.y)
            assertEquals(x.width, y.width); assertEquals(x.fontSize, y.fontSize); assertEquals(x.color, y.color); assertEquals(x.highlight, y.highlight)
            assertEquals(x.angle.toDouble(), y.angle.toDouble(), 0.0002) // stored in 0.01 degrees
        }
    }

    @Test fun strokesTextAndLayersComeBackTheSame() {
        val m = PageModel()
        val s1 = m.add(Tool.TOOL_PEN, 0xFF112233.toInt(), 40, pts(100, 200, 150, 260, 140, 300))
        val s2 = m.add(Tool.TOOL_HIGHLIGHTER, 0x99FFEB3B.toInt(), 300, pts(10, 10, 5000, 12))
        val t = m.addText(300f, 400f, 5000f, 1800f, "héllo\nworld", color = 0xFF990000.toInt(), highlight = 0x55FFFF00)
        m.setText(t.copy(x = 320f, angle = 0.5f))
        m.add(Tool.TOOL_DOTTED, 0xFF000000.toInt(), 40, pts(0, 0, 10, 10))
        m.transform(setOf(s1.id), Affine(a = 2.0, d = 2.0, e = 10.0, f = -5.0))
        m.arrange(setOf(s1.id), Arrange.FRONT)
        m.delete(setOf(s2.id))
        m.setPage(m.page.toBuilder().setWidth(29700).setHeight(21000).build())
        val back = InkLogReader.read(save(m)).page
        assertSame(m, back)
    }

    @Test fun anUndoIsSavedAsOppositeEventsAndStillReadsRight() {
        val m = PageModel()
        m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 2, 2))
        val b = m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(5, 5, 6, 6))
        m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(9, 9, 8, 8))
        m.delete(setOf(b.id)); m.undo() // the stroke comes back under its old ID, in its old place
        m.undo()
        m.redo()
        assertSame(m, InkLogReader.read(save(m)).page)
    }

    @Test fun aCutOffLastRecordIsIgnored() {
        val m = PageModel()
        m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 2, 2))
        val first = save(m)
        m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(7, 7, 9, 9))
        val both = save(m)
        for (cut in first.size + 1 until both.size) {
            val got = InkLogReader.read(both.copyOf(cut)).page // every way a crash could stop the write
            assertEquals(1, got.items.size)
        }
        assertEquals(2, InkLogReader.read(both).page.items.size)
    }

    @Test fun theLastNumberPerDeviceIsReported() {
        val m = PageModel()
        m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 2, 2))
        m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(3, 3, 4, 4))
        val w = InkLogWriter("george", seq = 5)
        val got = InkLogReader.read(w.header() + w.encode(m.log))
        assertEquals(mapOf("george" to 6L), got.lastSeq)
        assertEquals(7L, w.nextSeq)
    }

    @Test fun aNewerVersionIsRefusedAndAMissingHeaderToo() {
        val newer = com.google.protobuf.ByteString.EMPTY.let {
            java.io.ByteArrayOutputStream().also { o ->
                app.styli.ink.proto.Record.newBuilder().setHeader(app.styli.ink.proto.Header.newBuilder().setVersion(99)).build().writeDelimitedTo(o)
            }.toByteArray()
        }
        try { InkLogReader.read(newer); fail() } catch (_: IllegalArgumentException) {}
        try { InkLogReader.read(InkLogWriter("g").encode(emptyList()) + byteArrayOf(2, 18, 0)); fail() } catch (_: IllegalArgumentException) {}
    }

    private fun everyKind(): PageModel {
        val m = PageModel()
        val t = m.addTable(100f, 200f, 6000f, 3000f, 3, 4)!!
        m.setTable(t.withCellFill(0, 1, 0xFFFFCC00.toInt()).copy(fill = 0x22000000, color = 0xFF112233.toInt(), headerRow = true, fontSize = 300f, textColor = 0xFF333333.toInt(), colAlign = listOf(0, 1, 2, 0))
            .withData(listOf(listOf("Month", "Sales"), listOf("Jan", "12.5")), true, listOf(0, 2)))
        val sh = m.addShape { Shape(it, ShapeKind.POLYGON, 500f, 600f, 2000f, 1000f, angle = 0.5f, color = 0xFF008800.toInt(), lineWidth = 60f, dashed = true, fill = 0x3300FF00, corner = 0f,
            vertices = listOf(0.5f to 0f, 1f to 1f, 0f to 1f), text = "Tri ✓", textColor = 0xFF010203.toInt(), fontSize = 350f) }
        val sh2 = m.addShape { Shape(it, ShapeKind.RECT, 4000f, 600f, 1000f, 1000f, corner = 120f) }
        m.addConnector { Connector(it, End(sh.id, End.SPOT, 10f, 20f, 0.25f, 0.75f), End(sh2.id, 2, 4000f, 1100f), Arrowhead.OPEN, Arrowhead.DOT, 0xFF990000.toInt(), 50f, true, Route.ELBOW, 77f, listOf(2500f to 100f, 3000f to 900f)) }
        m.addFill(0x80FF0000.toInt(), listOf(listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f), listOf(25f to 25f, 75f to 25f, 50f to 75f)))
        m.addPicture { Picture(it, 100f, 100f, 800f, 600f, "ab.jpg", "ab".repeat(32), 1600, 1200, angle = 0.25f, cropX = 0.1f, cropY = 0.2f, cropW = 0.5f, cropH = 0.6f) }
        val series = listOf(
            ChartSeries("A", 0xFF112233.toInt(), listOf(1000, -2500, 3000), setOf(1), ChartKind.LINE, true, true, listOf(1L, 2L, 3L), listOf(5L, 6L, 7L), listOf(100L, 0L, 0L), "sin(x)"),
            ChartSeries("B", 0xFF445566.toInt(), listOf(4, 5, 6)),
        )
        m.addChart { Chart(it, ChartKind.COMBO, 1000f, 2000f, 6000f, 4000f, "Sales", listOf("Jan", "Feb", "Mar"), series, "x", "y", 0.25f, true, true, true, 0xFF0000FF.toInt(), 300f,
            ChartSource(t.id, 1, 2, 3, 4, true), true, true, Curve.SMOOTH, true, 40, true, true, setOf(1, 2), true, -5000, 9000, true, true, listOf("g1", "g2", "g1"), 7, 2500, true, true, 8000,
            0xFF111111.toInt(), 0xFF222222.toInt(), -10000, 10000, -1, 1) }
        return m
    }

    @Test fun everyKindOfObjectComesBackTheSame() {
        val m = everyKind()
        val back = InkLogReader.read(save(m)).page
        assertEquals(m.order, back.order)
        val (a, b) = m.tables.single() to back.tables.single()
        assertEquals(listOf(a.id, a.x, a.y, a.colWidths, a.rowHeights, a.lineWidth, a.color, a.fill, a.cellFills, a.cellText, a.fontSize, a.textColor, a.headerRow, a.colAlign),
            listOf(b.id, b.x, b.y, b.colWidths, b.rowHeights, b.lineWidth, b.color, b.fill, b.cellFills, b.cellText, b.fontSize, b.textColor, b.headerRow, b.colAlign))
        for ((x, y) in m.shapes.zip(back.shapes)) {
            assertEquals(listOf(x.id, x.kind, x.x, x.y, x.width, x.height, x.color, x.lineWidth, x.dashed, x.fill, x.corner, x.vertices, x.text, x.textColor, x.fontSize),
                listOf(y.id, y.kind, y.x, y.y, y.width, y.height, y.color, y.lineWidth, y.dashed, y.fill, y.corner, y.vertices, y.text, y.textColor, y.fontSize))
            assertEquals(x.angle.toDouble(), y.angle.toDouble(), 0.0002)
        }
        assertEquals(2, back.shapes.size)
        val (c, d) = m.connectors.single() to back.connectors.single()
        assertEquals(listOf(c.id, c.fromHead, c.toHead, c.color, c.lineWidth, c.dashed, c.route, c.bend, c.via), listOf(d.id, d.fromHead, d.toHead, d.color, d.lineWidth, d.dashed, d.route, d.bend, d.via))
        for ((e, f) in listOf(c.from to d.from, c.to to d.to)) assertEquals(listOf(e.shape, e.anchor, e.x, e.y, e.fx, e.fy), listOf(f.shape, f.anchor, f.x, f.y, f.fx, f.fy))
        assertEquals(m.fills.single().rings, back.fills.single().rings); assertEquals(m.fills.single().color, back.fills.single().color)
        val (p, q) = m.pictures.single() to back.pictures.single()
        assertEquals(p.copy(angle = 0f), q.copy(angle = 0f)); assertEquals(p.angle.toDouble(), q.angle.toDouble(), 0.0002)
        val (g, h) = m.charts.single() to back.charts.single()
        assertEquals(g.copy(angle = 0f), h.copy(angle = 0f)); assertEquals(g.angle.toDouble(), h.angle.toDouble(), 0.0002)
    }

    @Test fun removalsAndNewLayersReplayRight() {
        val m = everyKind()
        m.delete(setOf(m.shapes[0].id, m.charts.single().id)) // a shape, and a chart that follows a table
        m.arrange(setOf(m.tables.single().id), Arrange.FRONT)
        m.undo(); m.undo()
        val back = InkLogReader.read(save(m)).page
        assertEquals(m.order, back.order)
        // The order inside each list means nothing (the layers are `order`), and an undo may put a thing back elsewhere in its list.
        assertEquals(m.shapes.map { it.id }.toSet(), back.shapes.map { it.id }.toSet()); assertEquals(m.charts.map { it.id }.toSet(), back.charts.map { it.id }.toSet())
        assertEquals(m.tables.map { it.id }.toSet(), back.tables.map { it.id }.toSet()); assertEquals(m.fills.map { it.id }.toSet(), back.fills.map { it.id }.toSet())
        assertEquals(m.connectors.map { it.id }.toSet(), back.connectors.map { it.id }.toSet())
    }

    @Test fun anEmptyOrCorruptLogIsAnErrorNotAnEmptyPage() {
        for (bytes in listOf(ByteArray(0), byteArrayOf(1, 2, 3, 4))) {
            try { InkLogReader.read(bytes); fail() } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun readsTheFileTheGoCoreWrote() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sample.stylog")!!.readBytes()
        val got = InkLogReader.read(bytes)
        assertEquals(21000, got.page.page.width)
        assertTrue(got.page.items.isEmpty()) // the sample draws a stroke then erases it
    }
}
