package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorTest {
    // Two boxes side by side: a at x 0..1000, b at x 3000..4000, both y 0..1000.
    private val a = Shape("a", ShapeKind.RECT, 0f, 0f, 1000f, 1000f)
    private val b = Shape("b", ShapeKind.RECT, 3000f, 0f, 1000f, 1000f)
    private val all = mapOf("a" to a, "b" to b)
    private fun c(from: End, to: End) = Connector("c", from, to)

    @Test
    fun autoEndsMeetTheOutlineOnTheLineBetweenTheCentres() {
        val (p, q) = c(End("a"), End("b")).resolve(all::get)
        assertEquals(1000f to 500f, p)
        assertEquals(3000f to 500f, q)
    }

    @Test
    fun aFixedSideStaysOnThatSide() {
        val (p, q) = c(End("a", 3), End("b")).resolve(all::get) // a's bottom
        assertEquals(500f, p.first, 0.01f); assertEquals(1000f, p.second, 0.01f)
        // b's auto end looks at a's bottom middle, not a's centre
        assertEquals(3000f, q.first, 0.01f)
    }

    @Test
    fun aFreeEndIsWhereItWasWritten() {
        val (p, q) = c(End("a"), End(null, 0, 5000f, 500f)).resolve(all::get)
        assertEquals(1000f to 500f, p)
        assertEquals(5000f to 500f, q)
    }

    @Test
    fun followsAShapeThatMoved() {
        val moved = mapOf("a" to a, "b" to b.copy(y = 4000f))
        val (_, q) = c(End("a"), End("b")).resolve(moved::get)
        assertEquals(4000f, q.second, 1f) // the top edge of b, now far below
    }

    @Test
    fun anEndWhoseShapeIsGoneStaysPut() {
        val (p, _) = c(End("zz", 0, 77f, 88f), End("b")).resolve(all::get)
        assertEquals(77f to 88f, p)
    }

    @Test
    fun arrowWingsSitBackFromTheTipOnBothSides() {
        val (l, r) = Arrow.wings(100f to 0f, 0f to 0f, 100f) // a head at the right end of a level line
        assertEquals(100f - 100f * Math.cos(Math.toRadians(25.0)).toFloat(), l.first, 0.01f)
        assertEquals(l.first, r.first, 0.01f)
        assertEquals(-l.second, r.second, 0.01f) // mirrored
        assertEquals(Math.abs(100f * Math.sin(Math.toRadians(25.0)).toFloat()), Math.abs(l.second), 0.01f)
        assertEquals(300f, Arrow.size(20f), 0f) // never under 3 mm
        assertEquals(400f, Arrow.size(80f), 0f)
    }

    @Test
    fun movedShapeScalesAndTurns() {
        val m = a.moved(Affine.scaleAbout(2.0, 0.0, 0.0))
        assertEquals(2000f, m.width, 0.01f)
        assertEquals(a.lineWidth, m.lineWidth, 0f)
        assertEquals(1.5f, a.moved(Affine.rotateAbout(1.5, 0.0, 0.0)).angle, 0.001f)
    }

    @Test
    fun nearIsInsideOrWithinReach() {
        assertEquals(true, a.near(1100f, 500f, 200f))
        assertEquals(false, a.near(1500f, 500f, 200f))
    }

    @Test
    fun theLassoTakesLinesMoreThanHalfInside() {
        val loop = floatArrayOf(-100f, -100f, 2000f, -100f, 2000f, 2000f, -100f, 2000f)
        val inside = "in" to listOf(100f to 100f, 900f to 100f)
        val out = "out" to listOf(3000f to 100f, 4000f to 100f)
        assertEquals(setOf("in"), LassoGeometry.selectedLines(listOf(inside, out), loop))
        assertEquals(50f, LassoGeometry.distToSegment(500f, 50f, 0f to 0f, 1000f to 0f), 0.01f)
    }
}
