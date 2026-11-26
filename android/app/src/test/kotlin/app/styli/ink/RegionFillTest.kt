package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionFillTest {
    private val page = Box(0.0, 0.0, 10000.0, 10000.0)
    private fun line(vararg p: Float) = RegionFill.Path(p.toList().chunked(2).map { it[0] to it[1] }, 40f)

    // A square from (2000, 2000) to (6000, 6000).
    private val square = listOf(
        line(2000f, 2000f, 6000f, 2000f), line(6000f, 2000f, 6000f, 6000f), line(6000f, 6000f, 2000f, 6000f), line(2000f, 6000f, 2000f, 2000f),
    )

    private fun inside(r: RegionFill.Region, x: Float, y: Float) = Fill("f", 1, r.rings).contains(x, y)

    @Test
    fun aClosedSquareIsFilledToItsLines() {
        val r = RegionFill.find(square, 4000f, 4000f, page, 100f)!!
        assertEquals(1, r.rings.size)
        assertTrue(inside(r, 4000f, 4000f))
        assertTrue(inside(r, 2100f, 2100f)) // reaches the lines
        assertTrue("goes right up to the ink", inside(r, 2030f, 4000f))
        assertFalse("and does not spill over it", inside(r, 1975f, 4000f))
        assertFalse(inside(r, 6025f, 4000f))
        assertFalse(inside(r, 4000f, 1975f))
        assertFalse(inside(r, 1500f, 4000f))
        assertFalse(inside(r, 6500f, 4000f))
        assertEquals(3800.0 * 3800.0, r.area, 3800.0 * 3800.0 * 0.08)
    }

    @Test
    fun anOpenShapeIsNotFilled() {
        val open = square.drop(1) // the top side is missing
        assertNull(RegionFill.find(open, 4000f, 4000f, page, 100f))
    }

    @Test
    fun aSmallGapIsBridgedAndABigOneIsNot() {
        fun withGap(g: Float) = listOf(
            line(2000f, 2000f, 4000f - g / 2, 2000f), line(4000f + g / 2, 2000f, 6000f, 2000f),
        ) + square.drop(1)
        assertNotNull(RegionFill.find(withGap(60f), 4000f, 4000f, page, 100f))
        assertNull(RegionFill.find(withGap(600f), 4000f, 4000f, page, 100f))
    }

    @Test
    fun aTapOnALineFillsNothing() {
        assertNull(RegionFill.find(square, 4000f, 2005f, page, 100f))
    }

    @Test
    fun aLoopInsideLeavesAHole() {
        val inner = listOf(
            line(3500f, 3500f, 4500f, 3500f), line(4500f, 3500f, 4500f, 4500f), line(4500f, 4500f, 3500f, 4500f), line(3500f, 4500f, 3500f, 3500f),
        )
        val r = RegionFill.find(square + inner, 2500f, 2500f, page, 100f)!!
        assertEquals(2, r.rings.size) // the outside and the hole
        assertTrue(inside(r, 2500f, 2500f))
        assertFalse(inside(r, 4000f, 4000f)) // inside the inner loop
        // Tapping inside the inner loop fills just that.
        val small = RegionFill.find(square + inner, 4000f, 4000f, page, 100f)!!
        assertTrue(small.area < r.area / 3)
        assertTrue(inside(small, 4000f, 4000f))
    }

    @Test
    fun aTapOutsideTheBoundsOrInTheOpenFillsNothing() {
        assertNull(RegionFill.find(square, 8000f, 8000f, page, 100f)) // outside the square it reaches the edge
        assertNull(RegionFill.find(square, -5f, 100f, page, 100f))
    }

    @Test
    fun aSlantedClosedShapeWorks() {
        val tri = listOf(line(2000f, 6000f, 6000f, 6000f), line(6000f, 6000f, 4000f, 2000f), line(4000f, 2000f, 2000f, 6000f))
        val r = RegionFill.find(tri, 4000f, 5000f, page, 100f)!!
        assertTrue(inside(r, 4000f, 5000f))
        assertFalse(inside(r, 2500f, 2500f))
        assertTrue("far fewer points than the raw staircase", r.rings[0].size < 500)
    }
}
