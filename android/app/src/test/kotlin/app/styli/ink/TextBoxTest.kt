package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBoxTest {
    private fun model() = PageModel().also { it.addText(100f, 100f, 600f, 40f, "hello") }

    private fun stroke() = listOf(Pt(0f, 0f, 0, 1f), Pt(100f, 0f, 10, 1f))

    /** The boxes a reader of the log would end up with. */
    private fun replay(log: List<Ev>): Map<String, TextBox> {
        val out = LinkedHashMap<String, TextBox>()
        for (e in log) when (e) {
            is Ev.TextSet -> out[e.box.id] = e.box
            is Ev.TextRemoved -> out.remove(e.id)
            else -> {}
        }
        return out
    }

    @Test
    fun aNewBoxIsOneUndoableStepAndAnEventThatIsWholeBox() {
        val m = model()
        val box = m.texts.single()
        assertEquals("hello", box.text)
        assertEquals(0, box.color) // no colour and no highlight until chosen
        assertEquals(0, box.highlight)
        assertEquals(listOf<Ev>(Ev.TextSet(box)), m.log)
        assertTrue(m.undo())
        assertTrue(m.texts.isEmpty())
        assertEquals(Ev.TextRemoved(box.id), m.log.last())
        assertTrue(m.redo())
        assertEquals(box.id, m.texts.single().id) // it comes back under its old ID
        assertEquals(box.id, (m.log.last() as Ev.TextSet).box.id)
    }

    @Test
    fun textColourAndHighlightEachChangeInOneStepAndUndoOneAtATime() {
        val m = model()
        val id = m.texts.single().id
        assertTrue(m.setText(m.texts.single().copy(text = "hello world")))
        assertTrue(m.setText(m.texts.single().copy(color = 0xFFC62828.toInt())))
        assertTrue(m.setText(m.texts.single().copy(highlight = 0x99FFEB3B.toInt())))
        with(m.texts.single()) { assertEquals(listOf("hello world", 0xFFC62828.toInt(), 0x99FFEB3B.toInt()), listOf(text, color, highlight)) }
        m.undo()
        with(m.texts.single()) { assertEquals(0xFFC62828.toInt(), color); assertEquals(0, highlight); assertEquals("hello world", text) }
        m.undo()
        m.undo()
        assertEquals("hello", m.texts.single().text)
        assertEquals(id, m.texts.single().id)
        assertFalse(m.setText(TextBox("0".repeat(32), 0f, 0f, 1f, 1f, "ghost"))) // no such box
    }

    @Test
    fun movingAndResizingScaleWidthAndFontSizeButNotTheTextOrColours() {
        val m = model()
        m.setText(m.texts.single().copy(color = 5, highlight = 6))
        assertTrue(m.transform(setOf(m.texts.single().id), Affine(2.0, 0.0, 0.0, 2.0, 10.0, 20.0)))
        with(m.texts.single()) {
            assertEquals(listOf(210f, 220f, 1200f, 80f), listOf(x, y, width, fontSize))
            assertEquals(listOf("hello", 5, 6), listOf(text, color, highlight))
        }
        assertTrue("only a box: no Transformed event, the box is written whole", m.log.none { it is Ev.Transformed })
        m.undo()
        assertEquals(listOf(100f, 100f, 600f, 40f), m.texts.single().let { listOf(it.x, it.y, it.width, it.fontSize) })
    }

    @Test
    fun aMoveOfStrokesAndABoxListsOnlyTheStrokesInTransformed() {
        val m = model()
        val s = m.add(Tool.TOOL_PEN, 0, 40, stroke())
        m.transform(setOf(s.id, m.texts.single().id), Affine(1.0, 0.0, 0.0, 1.0, 50.0, 0.0))
        assertEquals(listOf(s.id), m.log.filterIsInstance<Ev.Transformed>().single().ids)
        assertEquals(150f, m.texts.single().x, 0f)
        assertEquals(50f, m.items.single().pts[0].x, 0f)
        m.undo() // one step undoes both
        assertEquals(100f, m.texts.single().x, 0f)
        assertEquals(0f, m.items.single().pts[0].x, 0f)
    }

    @Test
    fun deletingStrokesAndBoxesTogetherIsOneStepAndUndoBringsBothBack() {
        val m = model()
        val s = m.add(Tool.TOOL_PEN, 0, 40, stroke())
        val box = m.texts.single()
        assertTrue(m.delete(setOf(s.id, box.id)))
        assertTrue(m.items.isEmpty() && m.texts.isEmpty())
        assertTrue(Ev.TextRemoved(box.id) in m.log)
        m.undo()
        assertEquals(s.id, m.items.single().id)
        assertEquals(box.id, m.texts.single().id)
        assertFalse(m.delete(setOf("nothing")))
    }

    @Test
    fun theLogRebuildsTheSameBoxesAfterEditsMovesDeletesAndUndos() {
        val m = PageModel()
        val a = m.addText(0f, 0f, 500f, 40f, "a")
        val b = m.addText(900f, 0f, 500f, 40f, "b")
        m.setText(m.texts.first { it.id == a.id }.copy(text = "a2", color = 7))
        m.transform(setOf(b.id), Affine(1.5, 0.0, 0.0, 1.5, 3.0, 4.0))
        m.delete(setOf(a.id))
        m.undo()
        m.setText(m.texts.first { it.id == b.id }.copy(highlight = 9))
        m.undo(); m.undo(); m.redo()
        val got = replay(m.log)
        assertEquals(m.texts.map { it.id }.toSet(), got.keys)
        for (t in m.texts) {
            val r = got.getValue(t.id)
            assertEquals(listOf(t.x, t.y, t.width, t.fontSize, t.text, t.color, t.highlight), listOf(r.x, r.y, r.width, r.fontSize, r.text, r.color, r.highlight))
        }
    }

    private val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f) // the loop: 0..100 each way

    private fun box(x: Float, y: Float, w: Float, angle: Float = 0f) = TextBox("b$x$y", x, y, w, 40f, "t", angle = angle)

    @Test
    fun aLoopTakesTheBoxesMoreThanHalfInsideByArea() {
        val boxes = listOf(
            box(10f, 10f, 80f) to 30f,
            box(40f, 10f, 90f) to 30f, // 60 of 90 wide inside
            box(50f, 10f, 100f) to 30f, // exactly half: not more than half
            box(200f, 10f, 100f) to 30f,
            box(10f, 50f, 30f) to 200f, // a quarter of its height inside
        )
        val got = LassoGeometry.selectedBoxes(boxes, square)
        assertEquals(setOf(boxes[0].first.id, boxes[1].first.id), got)
        assertEquals(emptySet<String>(), LassoGeometry.selectedBoxes(boxes, floatArrayOf(0f, 0f, 10f, 10f))) // under three points
    }

    @Test
    fun aTurnedBoxIsJudgedWhereItReallyIs() {
        // 90 degrees clockwise about (50, 0): a 80 x 20 box now stands down from there, x 30..50, y 0..80.
        val upright = box(50f, 0f, 80f, (Math.PI / 2).toFloat())
        assertEquals(setOf(upright.id), LassoGeometry.selectedBoxes(listOf(upright to 20f), square))
        // The same box with no turn runs out to x = 130: only 50 of 80 inside is more than half, so shift it.
        val flat = box(50f, 0f, 160f)
        assertEquals(emptySet<String>(), LassoGeometry.selectedBoxes(listOf(flat to 20f), square)) // 50 of 160 inside
        assertTrue(upright.contains(40f, 60f, 20f))
        assertFalse(upright.contains(60f, 60f, 20f)) // on the other side of the turn's corner
    }

    @Test
    fun aBoxFrameRoundTripsAndItsCornersTurnClockwise() {
        val b = box(100f, 50f, 40f, (Math.PI / 2).toFloat())
        val (px, py) = b.toPage(10f, 5f)
        assertEquals(95f, px, 0.001f) // x axis now points down the page, y axis points to -x
        assertEquals(60f, py, 0.001f)
        val (lx, ly) = b.toLocal(px, py)
        assertEquals(10f, lx, 0.001f); assertEquals(5f, ly, 0.001f)
        val c = b.corners(20f)
        assertEquals(listOf(100f, 50f), listOf(c[0].first, c[0].second))
        assertEquals(80f, c[2].first, 0.001f); assertEquals(90f, c[2].second, 0.001f)
    }

    @Test
    fun theBoxRoundASelectionJoinsStrokesAndTextBoxes() {
        val m = PageModel()
        val s = m.add(Tool.TOOL_PEN, 0, 40, listOf(Pt(0f, 0f, 0, 1f), Pt(100f, 0f, 10, 1f)))
        val both = LassoGeometry.bounds(listOf(s), listOf(Box(50.0, 200.0, 400.0, 300.0)))!!
        assertEquals(listOf(-20.0, -20.0, 400.0, 300.0), listOf(both.l, both.t, both.r, both.b)) // the stroke's box is padded, the text's is not
        assertEquals(listOf(50.0, 200.0, 400.0, 300.0), LassoGeometry.bounds(emptyList(), listOf(Box(50.0, 200.0, 400.0, 300.0)))!!.let { listOf(it.l, it.t, it.r, it.b) })
        assertEquals(null, LassoGeometry.bounds(emptyList()))
    }

    @Test
    fun recolouringSeveralBoxesIsOneUndoStep() {
        val m = PageModel()
        val a = m.addText(0f, 0f, 500f, 40f, "a")
        val b = m.addText(0f, 100f, 500f, 40f, "b")
        val c = m.addText(0f, 200f, 500f, 40f, "c")
        assertTrue(m.setTexts(listOf(a.copy(color = 5, highlight = 6), b.copy(color = 5, highlight = 6))))
        assertEquals(listOf(5, 5, 0), m.texts.map { it.color })
        assertEquals(listOf(a.id, b.id), m.log.filterIsInstance<Ev.TextSet>().takeLast(2).map { it.box.id })
        m.undo()
        assertEquals(listOf(0, 0, 0), m.texts.map { it.color })
        assertEquals(c.id, m.texts[2].id)
        assertFalse(m.setTexts(listOf(TextBox("0".repeat(32), 0f, 0f, 1f, 1f, "ghost")))) // none match
    }

    @Test
    fun aTurnMovesTheCornerAndAddsToTheAngleAndAUndoRestoresBoth() {
        val m = PageModel()
        val t = m.addText(100f, 0f, 400f, 40f, "t")
        val quarter = Affine.rotateAbout(Math.PI / 2, 0.0, 0.0) // a quarter turn clockwise about the origin
        assertTrue(m.transform(setOf(t.id), quarter))
        with(m.texts.single()) {
            assertEquals(0f, x, 0.001f); assertEquals(100f, y, 0.001f) // (100, 0) turned about the origin
            assertEquals(Math.PI.toFloat() / 2, angle, 0.001f)
            assertEquals(listOf(400f, 40f), listOf(width, fontSize)) // a turn does not scale
        }
        m.transform(setOf(m.texts.single().id), quarter)
        assertEquals(Math.PI.toFloat(), m.texts.single().angle, 0.001f)
        m.undo(); m.undo()
        assertEquals(listOf(100f, 0f, 0f), m.texts.single().let { listOf(it.x, it.y, it.angle) })
        assertTrue("no strokes: no Transformed event", m.log.none { it is Ev.Transformed })
    }

    @Test
    fun aBoxTurnedWithAStrokeKeepsLayingOutTheSameAsTheStrokeMoves() {
        // A stroke and a box turned together by the same map stay where they were relative to each other.
        val m = PageModel()
        val s = m.add(Tool.TOOL_PEN, 0, 40, listOf(Pt(100f, 0f, 0, 1f), Pt(500f, 0f, 10, 1f))) // along the box's top edge
        val t = m.addText(100f, 0f, 400f, 40f, "t")
        m.transform(setOf(s.id, t.id), Affine.rotateAbout(0.6, 30.0, 20.0))
        val box = m.texts.single()
        val (ex, ey) = box.toPage(box.width, 0f) // the box's top right corner
        val stroke = m.items.single().pts
        assertEquals(stroke[0].x, box.x, 0.01f); assertEquals(stroke[0].y, box.y, 0.01f)
        assertEquals(stroke[1].x, ex, 0.05f); assertEquals(stroke[1].y, ey, 0.05f)
    }
}
