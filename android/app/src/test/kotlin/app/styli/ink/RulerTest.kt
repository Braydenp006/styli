package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulerTest {
    // Flat ruler centred at (500, 300), 400 long, 100 thick: its top edge is y=250, from x=300 to x=700.
    private fun flat() = Ruler(500f, 300f, 0f, 400f, 100f)

    @Test
    fun snapsToTheNearEdgeAndKeepsAlong() {
        val r = flat()
        assertEquals(-1, r.edgeNear(450f, 258f, 20f)) // just inside the top edge
        assertEquals(-1, r.edgeNear(450f, 240f, 20f)) // just outside it
        assertEquals(1, r.edgeNear(450f, 345f, 20f))
        assertEquals(0, r.edgeNear(450f, 300f, 20f)) // middle of the body
        assertEquals(0, r.edgeNear(900f, 250f, 20f)) // far past the end
        val (x, y) = r.onEdge(450f, 258f, -1)
        assertEquals(450f, x, 0.01f)
        assertEquals(250f, y, 0.01f)
    }

    @Test
    fun theLineStopsAtTheRulersEnd() {
        val (x, y) = flat().onEdge(900f, 255f, -1)
        assertEquals(700f, x, 0.01f)
        assertEquals(250f, y, 0.01f)
    }

    @Test
    fun aTurnedRulerSnapsAlongItsOwnEdge() {
        val r = Ruler(500f, 300f, (Math.PI / 2).toFloat(), 400f, 100f) // upright: top edge is now the right side (x=550)
        val (x, y) = r.onEdge(540f, 400f, -1)
        assertEquals(550f, x, 0.01f)
        assertEquals(400f, y, 0.01f)
    }

    @Test
    fun containsAndMove() {
        val r = flat()
        assertTrue(r.contains(500f, 300f))
        assertFalse(r.contains(500f, 400f))
        r.move(0f, 100f)
        assertTrue(r.contains(500f, 400f))
    }

    @Test
    fun twoFingersTurnItAndKeepTheMiddleUnderThem() {
        val r = Ruler(0f, 0f, 0f, 400f, 100f)
        // Fingers at (-100,0) and (100,0) turn a quarter turn to (0,-100) and (0,100).
        r.follow(-100f, 0f, 100f, 0f, 0f, -100f, 0f, 100f)
        assertEquals((Math.PI / 2).toFloat(), r.angle, 0.001f)
        assertEquals(0f, r.cx, 0.01f)
        assertEquals(0f, r.cy, 0.01f)
    }

    @Test
    fun metricTicksFollowTheZoom() {
        // 0.05 px per unit: a cm is 50 px, a mm 5 px, so every mm shows; the cm ticks are numbered.
        val m = RulerScale.marks(false, 520f, 0.05)
        assertEquals(105, m.size)
        assertEquals(listOf("0", "1", "2"), m.filter { it.label != null }.take(3).map { it.label })
        assertEquals(5f, m[1].along, 0.001f)
        assertEquals(1, m[5].rank) // 5 mm
        // Zoomed out to 0.02 px per unit: a mm is 2 px, too close, so only the 5 mm and cm ticks stay (10 px, 20 px).
        assertTrue(RulerScale.marks(false, 520f, 0.02).none { it.rank == 2 })
        // Far out, nothing at all.
        assertTrue(RulerScale.marks(false, 520f, 0.001).isEmpty())
    }

    @Test
    fun inchTicksAreSixteenthsWithNumberedInches() {
        // 0.1 px per unit: an inch is 254 px, a sixteenth 15.9 px.
        val m = RulerScale.marks(true, 600f, 0.1)
        assertEquals(19, m.count { it.rank == 4 }) // the odd sixteenths in 37 steps
        assertEquals(listOf("0", "1", "2"), m.filter { it.label != null }.map { it.label })
        assertEquals(0, m.first { it.label == "1" }.rank)
        assertEquals(254f, m.first { it.label == "1" }.along, 0.01f)
    }
}
