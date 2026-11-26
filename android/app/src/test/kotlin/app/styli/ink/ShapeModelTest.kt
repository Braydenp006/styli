package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeModelTest {
    private fun PageModel.rect(x: Float = 0f) = addShape { ShapePreset.RECTANGLE.make(it, x, 0f, 1000f, 1000f) }
    private fun PageModel.stroke() = add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, listOf(Pt(0f, 0f, 0, 1f), Pt(100f, 0f, 5, 1f))).id
    private val ring = listOf(0f to 0f, 100f to 0f, 100f to 100f)

    @Test
    fun layersPlaceNewThingsByKind() {
        val m = PageModel()
        val s = m.stroke()
        val sh = m.rect()
        val f = m.addFill(0x99FFEB3B.toInt(), listOf(ring))!!
        val c = m.addConnector { Connector(it, End(sh.id), End(null, 0, 5000f, 0f)) }
        assertEquals(listOf(f.id, s, sh.id, c.id), m.order)
        assertTrue(m.log.none { it is Ev.ZMoved }) // the plain rules give exactly that
    }

    @Test
    fun addEditDeleteUndo() {
        val m = PageModel()
        val sh = m.rect()
        assertTrue(m.setShape(sh.copy(fill = 0xFF00FF00.toInt(), text = "hi")))
        assertEquals("hi", m.shapeOf(sh.id)!!.text)
        assertEquals(2, m.log.filterIsInstance<Ev.ShapeSet>().size)
        assertTrue(m.delete(setOf(sh.id)))
        assertNull(m.shapeOf(sh.id))
        assertTrue(m.log.last() is Ev.ShapeRemoved)
        m.undo()
        assertEquals("hi", m.shapeOf(sh.id)!!.text)
        assertEquals(listOf(sh.id), m.order)
        m.undo(); m.undo()
        assertTrue(m.shapes.isEmpty() && m.order.isEmpty())
        assertFalse(m.setShape(sh)) // gone
    }

    @Test
    fun transformMovesScalesAndTurnsShapes() {
        val m = PageModel()
        val sh = m.rect()
        val f = m.addFill(0xFF000000.toInt(), listOf(ring))!!
        assertTrue(m.transform(setOf(sh.id, f.id), Affine.scaleAbout(2.0, 0.0, 0.0)))
        val s = m.shapeOf(sh.id)!!
        assertEquals(2000f, s.width, 0.01f)
        assertEquals(40f, s.lineWidth, 0f) // line widths are not scaled
        assertEquals(200f, m.fillOf(f.id)!!.rings[0][1].first, 0.01f)
        assertTrue(m.transform(setOf(sh.id), Affine.rotateAbout(Math.PI / 2, 0.0, 0.0)))
        assertEquals((Math.PI / 2).toFloat(), m.shapeOf(sh.id)!!.angle, 0.001f)
    }

    @Test
    fun aFreeConnectorEndMovesButAnAttachedOneFollowsItsShape() {
        val m = PageModel()
        val sh = m.rect()
        val c = m.addConnector { Connector(it, End(sh.id), End(null, 0, 3000f, 0f)) }
        m.transform(setOf(c.id), Affine.translate(0.0, 500.0))
        val after = m.connectorOf(c.id)!!
        assertEquals(sh.id, after.from.shape)
        assertEquals(500f, after.to.y, 0.01f)
    }

    @Test
    fun deletingAShapeFreesTheConnectorsEnd() {
        val m = PageModel()
        val a = m.rect(0f)
        val b = m.rect(3000f)
        val c = m.addConnector { Connector(it, End(a.id), End(b.id)) }
        val (before, _) = m.connectorOf(c.id)!!.resolve(m::shapeOf)
        assertTrue(m.delete(setOf(a.id)))
        val now = m.connectorOf(c.id)!!
        assertNull(now.from.shape)
        assertEquals(before, now.from.x to now.from.y) // it stayed where it was
        assertEquals(b.id, now.to.shape)
        m.undo()
        assertEquals(a.id, m.connectorOf(c.id)!!.from.shape)
        assertNotNull(m.shapeOf(a.id))
    }

    @Test
    fun deletingAConnectorAndAFillWorks() {
        val m = PageModel()
        val c = m.addConnector { Connector(it, End(null, 0, 0f, 0f), End(null, 0, 10f, 10f)) }
        val f = m.addFill(1, listOf(ring))!!
        assertTrue(m.delete(setOf(c.id, f.id)))
        assertTrue(m.connectors.isEmpty() && m.fills.isEmpty() && m.order.isEmpty())
        assertTrue(m.log.any { it is Ev.ConnectorRemoved } && m.log.any { it is Ev.FillRemoved })
    }

    @Test
    fun fillsAreRecolouredWholeAndNeedARing() {
        val m = PageModel()
        assertNull(m.addFill(1, emptyList()))
        assertNull(m.addFill(1, listOf(listOf(0f to 0f, 1f to 1f)))) // two points is not an area
        val f = m.addFill(1, listOf(ring))!!
        assertTrue(m.setFills(listOf(f.copy(color = 2))))
        assertEquals(2, m.fillOf(f.id)!!.color)
    }

    @Test
    fun theLayerCommandsReachShapes() {
        val m = PageModel()
        val a = m.rect(); val s = m.stroke()
        assertTrue(m.arrange(setOf(a.id), Arrange.FRONT))
        assertEquals(listOf(s, a.id), m.order)
        assertTrue(m.log.any { it is Ev.ZMoved })
    }

    @Test
    fun scalingAConnectorScalesItsBend() {
        val m = PageModel()
        val c = m.addConnector { Connector(it, End(null, 0, 0f, 0f), End(null, 0, 1000f, 0f), route = Route.CURVE, bend = 100f) }
        m.transform(setOf(c.id), Affine.scaleAbout(2.0, 0.0, 0.0))
        assertEquals(200f, m.connectorOf(c.id)!!.bend, 0.01f)
        assertEquals(2000f, m.connectorOf(c.id)!!.to.x, 0.01f)
    }

    @Test
    fun movingAConnectorMovesItsBendPoints() {
        val m = PageModel()
        val c = m.addConnector { Connector(it, End(null, 0, 0f, 0f), End(null, 0, 1000f, 0f), via = listOf(500f to 300f)) }
        m.transform(setOf(c.id), Affine.translate(100.0, 50.0))
        assertEquals(600f to 350f, m.connectorOf(c.id)!!.via[0])
    }
}
