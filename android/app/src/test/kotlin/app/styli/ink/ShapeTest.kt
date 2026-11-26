package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTest {
    private fun rect(angle: Float = 0f) = Shape("r", ShapeKind.RECT, 1000f, 1000f, 2000f, 1000f, angle)
    private fun ellipse() = Shape("e", ShapeKind.ELLIPSE, 0f, 0f, 2000f, 1000f)
    private fun poly(p: ShapePreset) = p.make("p", 0f, 0f, 1000f, 1000f)

    @Test
    fun containsFollowsTheKind() {
        assertTrue(rect().contains(1500f, 1500f))
        assertFalse(rect().contains(500f, 1500f))
        assertTrue(ellipse().contains(1000f, 500f)) // the middle
        assertFalse(ellipse().contains(50f, 50f)) // a corner of the box is outside an ellipse
        assertTrue(poly(ShapePreset.TRIANGLE).contains(500f, 800f))
        assertFalse(poly(ShapePreset.TRIANGLE).contains(50f, 100f)) // beside the top point
        assertTrue(poly(ShapePreset.STAR).contains(500f, 520f)) // the star's middle is inside
    }

    @Test
    fun aTurnedShapeIsHitWhereItIs() {
        val q = rect((Math.PI / 2).toFloat()) // a quarter turn clockwise about (1000, 1000): the box now hangs below-left
        assertTrue(q.contains(500f, 2000f))
        assertFalse(q.contains(2000f, 1500f)) // where it was before the turn
        val (cx, cy) = q.center
        assertEquals(500f, cx, 0.01f)
        assertEquals(2000f, cy, 0.01f)
    }

    @Test
    fun outlineTowardGivesTheEdgeAlongTheLineFromTheCentre() {
        val r = rect() // centre (2000, 1500), half 1000 by 500
        val right = r.outlineToward(9000f, 1500f)
        assertEquals(3000f, right.first, 0.01f); assertEquals(1500f, right.second, 0.01f)
        val diag = r.outlineToward(2000f + 1000f, 1500f + 1000f) // steeper than the box: leaves through the bottom
        assertEquals(2500f, diag.first, 0.01f); assertEquals(2000f, diag.second, 0.01f)
        val e = ellipse().outlineToward(5000f, 500f)
        assertEquals(2000f, e.first, 0.01f)
        val tri = poly(ShapePreset.TRIANGLE).outlineToward(500f, -5000f) // straight up: the top point
        assertEquals(500f, tri.first, 0.5f); assertEquals(0f, tri.second, 0.5f)
        assertEquals(r.center, r.outlineToward(2000f, 1500f)) // aimed at the centre: stays there
    }

    @Test
    fun aStarsOutlineIsTheFarthestCrossing() {
        val s = poly(ShapePreset.STAR)
        val up = s.outlineToward(500f, -9000f)
        assertEquals(0f, up.second, 1f) // the tip, not the notch behind it
    }

    @Test
    fun sidesAndAnchors() {
        val r = rect()
        assertEquals(2000f to 1000f, r.sideMiddle(1))
        assertEquals(3000f to 1500f, r.sideMiddle(2))
        assertNull(r.anchorPoint(0))
        val a = r.anchorPoint(4)!!
        assertEquals(1000f, a.first, 0.01f); assertEquals(1500f, a.second, 0.01f)
    }

    @Test
    fun everyPresetIsAUsableShape() {
        for (p in ShapePreset.entries) {
            val s = p.make("x", 0f, 0f, 1000f, 1000f)
            assertEquals(p.kind, s.kind)
            if (p.kind == ShapeKind.POLYGON) {
                assertTrue("$p", s.vertices.size >= 3)
                assertTrue("$p", s.vertices.all { it.first in 0f..1f && it.second in 0f..1f })
                assertEquals("$p fills the box", 0f, s.vertices.minOf { it.first }, 0.001f)
                assertEquals("$p fills the box", 1f, s.vertices.maxOf { it.second }, 0.001f)
                assertTrue("$p centre", s.contains(500f, 500f) || p == ShapePreset.STAR)
            }
        }
        assertTrue(ShapePreset.ROUNDED.make("x", 0f, 0f, 1000f, 500f).corner > 0f)
        assertEquals(0f, ShapePreset.RECTANGLE.make("x", 0f, 0f, 1000f, 500f).corner, 0f)
    }

    @Test
    fun fillsAreEvenOdd() {
        val outer = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        val hole = listOf(40f to 40f, 60f to 40f, 60f to 60f, 40f to 60f)
        val f = Fill("f", 0xFF00FF00.toInt(), listOf(outer, hole))
        assertTrue(f.contains(10f, 10f))
        assertFalse(f.contains(50f, 50f))
        assertFalse(f.contains(200f, 50f))
        assertNotNull(f.box())
        assertEquals(100.0, f.box()!!.r, 0.0)
        val moved = f.map(Affine.translate(10.0, 0.0))
        assertTrue(moved.contains(105f, 10f))
        assertEquals(f.id, moved.id)
    }

    @Test
    fun theLassoTakesShapesMoreThanHalfInside() {
        val a = Shape("a", ShapeKind.RECT, 0f, 0f, 1000f, 1000f)
        val b = Shape("b", ShapeKind.RECT, 5000f, 0f, 1000f, 1000f)
        val c = Shape("c", ShapeKind.RECT, 800f, 0f, 1000f, 1000f) // about 20% inside the loop
        val loop = floatArrayOf(-100f, -100f, 1000f, -100f, 1000f, 1100f, -100f, 1100f)
        assertEquals(setOf("a"), LassoGeometry.selectedShapes(listOf(a, b, c), loop))
        assertTrue(LassoGeometry.selectedShapes(listOf(a), floatArrayOf(0f, 0f, 10f, 10f)).isEmpty()) // not a loop
    }

    @Test
    fun theLassoTakesFillsMoreThanHalfInside() {
        val sq = listOf(0f to 0f, 1000f to 0f, 1000f to 1000f, 0f to 1000f)
        val a = Fill("a", 1, listOf(sq))
        val b = Fill("b", 1, listOf(sq.map { it.first + 5000f to it.second }))
        val loop = floatArrayOf(-100f, -100f, 1100f, -100f, 1100f, 1100f, -100f, 1100f)
        assertEquals(setOf("a"), LassoGeometry.selectedFills(listOf(a, b), loop))
    }
}
