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
}
