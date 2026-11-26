package app.styli.ink

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadTest {
    private fun box(x: Float, y: Float, w: Float, angle: Float) = TextBox("b", x, y, w, 40f, "t", angle = angle)

    private fun near(a: Pair<Double, Double>, x: Double, y: Double) {
        assertEquals(x, a.first, 0.01); assertEquals(y, a.second, 0.01)
    }

    @Test
    fun aTurnedBoxGetsAFrameThatIsItsOwnShapeNotAGrowingBox() {
        val angle = (PI / 6).toFloat() // 30 degrees
        val b = box(100f, 100f, 400f, angle)
        val q = LassoGeometry.orientedQuad(b.corners(20f), angle)
        for (i in 0..3) {
            val own = b.corners(20f)[listOf(0, 1, 3, 2)[i]] // the box's corners clockwise from the top left: 0 1 2 3
            near(q.corner(i), own.first.toDouble(), own.second.toDouble())
        }
        // The same box seen level and at 30 degrees has the same frame size; the axis box round it is far bigger.
        val side = Math.hypot(q.corner(1).first - q.corner(0).first, q.corner(1).second - q.corner(0).second)
        assertEquals(400.0, side, 0.01)
        val level = LassoGeometry.orientedQuad(box(100f, 100f, 400f, 0f).corners(20f), 0f)
        assertEquals(400.0, level.corner(1).first - level.corner(0).first, 0.01)
    }

    @Test
    fun boxesThatShareAnAngleShareOneTurnedFrame() {
        val a = (PI / 2).toFloat()
        val two = listOf(box(0f, 0f, 100f, a), box(0f, 300f, 100f, a)) // a quarter turn: they run down the page
        val q = LassoGeometry.orientedQuad(two.flatMap { it.corners(20f) }, a)
        assertEquals(20.0, Math.hypot(q.corner(2).first - q.corner(0).first, q.corner(2).second - q.corner(0).second), 0.01) // its short side, 20 tall in the box's frame, is now the width on the page
        assertTrue(LassoGeometry.sameTurn(a, a + 2 * PI.toFloat()))
        assertFalse(LassoGeometry.sameTurn(a, 0f))
        assertFalse(LassoGeometry.sameTurn(0f, PI.toFloat())) // upside down is a different angle
    }

    @Test
    fun theHandleStandsStraightOutFromTheTopEdgeAndInsideIsTheTurnedShape() {
        val angle = (PI / 2).toFloat() // clockwise: the box's top edge now faces right
        val b = box(100f, 100f, 400f, angle)
        val q = LassoGeometry.orientedQuad(b.corners(20f), angle)
        val (hx, hy) = q.handle(50.0)
        val (mx, my) = q.topMiddle
        assertEquals(50.0, Math.hypot(hx - mx, hy - my), 0.01)
        assertTrue("out to the right of the page, away from the box", hx > mx && Math.abs(hy - my) < 0.01)
        assertTrue(q.contains(90.0, 300.0)) // inside the box now standing down the page, x 80..100, y 100..500
        assertFalse(q.contains(150.0, 300.0))
        assertFalse(q.contains(90.0, 600.0))
    }

    @Test
    fun resizingATurnedFrameScalesAboutTheOppositeCorner() {
        val angle = (PI / 2).toFloat()
        val q = LassoGeometry.orientedQuad(box(100f, 100f, 400f, angle).corners(20f), angle)
        val (ox, oy) = q.corner(3) // the corner opposite the top left
        val (cx, cy) = q.corner(0)
        // Drag the top-left corner out to twice as far from the opposite corner along the diagonal.
        val m = LassoGeometry.scaleFromCorner(q, 0, ox + 2 * (cx - ox), oy + 2 * (cy - oy))
        assertEquals(2.0, m.a, 1e-6)
        near(m.x(ox.toFloat(), oy.toFloat()).let { it.toDouble() to m.y(ox.toFloat(), oy.toFloat()).toDouble() }, ox, oy)
    }

    @Test
    fun aMapMovesAllFourCornersSoATurnPreviewIsTheTurnedOutline() {
        val q = Quad.of(Box(0.0, 0.0, 100.0, 40.0)).map(Affine.rotateAbout(PI / 2, 50.0, 20.0))
        near(q.corner(0), 70.0, -30.0) // top left goes to the top right of the turned box
        near(q.corner(3), 30.0, 70.0)
        near(q.centre, 50.0, 20.0)
    }
}
