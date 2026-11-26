package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTest {
    private fun PageModel.stroke(x: Float, y: Float) =
        add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, listOf(Pt(x, y, 0, 1f), Pt(x + 1000f, y + 500f, 10, 1f))).id

    private fun PageModel.rect(x: Float, y: Float) = addShape { Shape(it, ShapeKind.RECT, x, y, 2000f, 1000f) }

    /** Boxes the way the canvas does it, enough for strokes and shapes. */
    private fun boxIn(m: PageModel): (String) -> Box? = { id -> m.boxOf(id) }

    private fun PageModel.boxOf(id: String): Box? =
        itemOf(id)?.let { i -> Box(i.pts.minOf { it.x }.toDouble(), i.pts.minOf { it.y }.toDouble(), i.pts.maxOf { it.x }.toDouble(), i.pts.maxOf { it.y }.toDouble()) }
            ?: shapeOf(id)?.let { Box(it.x.toDouble(), it.y.toDouble(), (it.x + it.width).toDouble(), (it.y + it.height).toDouble()) }

    @Test
    fun aClipStartsAtItsTopLeftCornerAndPastesWhereAsked() {
        val m = PageModel()
        val a = m.stroke(5000f, 6000f)
        val clip = Clip.of("a", m, setOf(a), boxIn(m))!!
        assertEquals(1000f, clip.width, 0f)
        assertEquals(0f, clip.items[0].pts[0].x, 0f)
        val ids = m.paste(clip, 100f, 200f)
        assertEquals(1, ids.size)
        val copy = m.items.single { it.id in ids }
        assertEquals(100f, copy.pts[0].x, 0f)
        assertEquals(700f, copy.pts[1].y, 0f)
        assertEquals(2, m.items.size) // the original stays
    }

    @Test
    fun everyPasteGetsNewIdsAndIsOneUndoStep() {
        val m = PageModel()
        val clip = Clip.of("a", m, setOf(m.stroke(0f, 0f), m.rect(0f, 0f).id), boxIn(m))!!
        val first = m.paste(clip, 0f, 0f)
        val second = m.paste(clip, 0f, 0f)
        assertTrue(first.intersect(second).isEmpty())
        assertEquals(6, m.order.size)
        assertEquals(6, m.order.toSet().size)
        assertTrue(m.undo())
        assertEquals(4, m.order.size)
        assertTrue(m.undo())
        assertEquals(2, m.order.size)
    }

    @Test
    fun layersKeepTheClipsOrder() {
        val m = PageModel()
        val s = m.rect(0f, 0f).id
        val a = m.stroke(0f, 0f)
        val clip = Clip.of("a", m, setOf(s, a), boxIn(m))!!
        val ids = m.paste(clip, 0f, 0f)
        val top = m.order.takeLast(2)
        assertEquals(ids, top.toSet())
        assertNotNull(m.shapeOf(top[0])) // the shape was under the stroke
        assertNotNull(m.itemOf(top[1]))
    }

    @Test
    fun aConnectorFollowsTheCopiedShapesAndFreesEndsOnShapesLeftOut() {
        val m = PageModel()
        val s1 = m.rect(0f, 0f)
        val s2 = m.rect(5000f, 0f)
        val c = m.addConnector { Connector(it, End(s1.id, End.SPOT, 0f, 0f, 1f, 0.5f), End(s2.id, End.SPOT, 0f, 0f, 0f, 0.5f)) }
        val both = Clip.of("a", m, setOf(s1.id, s2.id, c.id), boxIn(m))!!
        val ids = m.paste(both, 0f, 3000f)
        val copy = m.connectors.single { it.id in ids }
        val newShapes = m.shapes.filter { it.id in ids }.map { it.id }.toSet()
        assertTrue(copy.from.shape in newShapes && copy.to.shape in newShapes)
        assertNotEquals(c.from.shape, copy.from.shape)
        // Only one shape in the clip: the other end is free, where it was.
        val one = Clip.of("b", m, setOf(s1.id, c.id), boxIn(m))!!
        val free = one.connectors.single().to
        assertNull(free.shape)
        assertEquals(m.connectors[0].resolve(m::shapeOf).second.first - s1.x, free.x, 1f)
    }

    @Test
    fun emptyOrOffPageSelectionsMakeNoClip() {
        val m = PageModel()
        assertNull(Clip.of("a", m, setOf("nothing"), boxIn(m)))
        assertTrue(m.paste(Clip("a", 0f, 0f, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), 0f, 0f).isEmpty())
    }

    @Test
    fun theShelfNamesRenamesAndRemoves() {
        val m = PageModel()
        val clip = Clip.of("", m, setOf(m.stroke(0f, 0f)), boxIn(m))!!
        val shelf = ClipShelf()
        val saved = shelf.add(clip)
        assertEquals("Clip 1", saved.name)
        shelf.rename(saved, "  Arrow  ")
        assertEquals("Arrow", shelf.clips[0].name)
        shelf.rename(shelf.clips[0], " ") // a blank name is ignored
        assertEquals("Arrow", shelf.clips[0].name)
        shelf.remove(shelf.clips[0])
        assertTrue(shelf.clips.isEmpty())
    }
}
