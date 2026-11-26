package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LineTest {
    private fun end(x: Float, y: Float) = LineGeometry.end(100f, 100f, x, y)

    @Test
    fun nearlyFlatSnapsFlatInBothDirections() {
        assertEquals(100f to 100f, end(100f, 100f)) // a zero-length line stays put
        assertEquals(500f to 100f, end(500f, 110f)) // about 1.4 degrees
        assertEquals(-300f to 100f, end(-300f, 90f)) // pointing left
    }

    @Test
    fun nearlyUprightSnapsUprightUpAndDown() {
        assertEquals(100f to 600f, end(110f, 600f))
        assertEquals(100f to -400f, end(90f, -400f))
    }

    @Test
    fun anythingElseIsLeftAlone() {
        assertEquals(500f to 200f, end(500f, 200f)) // about 14 degrees
        assertEquals(300f to 300f, end(300f, 300f)) // 45
        assertEquals(500f to 130f, end(500f, 130f)) // about 4 degrees: outside 3
    }

    @Test
    fun aTapIsNotALine() {
        assertNull(LineGeometry.points(0f, 0f, 3f, 4f, 20))
        val p = LineGeometry.points(0f, 0f, 300f, 400f, 120)!!
        assertEquals(2, p.size)
        assertEquals(500f, kotlin.math.hypot(p[1].x - p[0].x, p[1].y - p[0].y), 1e-3f)
        assertEquals(120L, p[1].t)
        assertNotNull(p)
    }
}
