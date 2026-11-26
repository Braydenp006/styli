package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeEditTest {
    private val tri = ShapePreset.TRIANGLE.make("t", 1000f, 1000f, 2000f, 2000f) // (.5,0) (1,1) (0,1)

    @Test
    fun aCornerFollowsThePenInsideTheBox() {
        val e = ShapeEdit.moveVertex(tri, 0, 1900f, 1500f, 10f) // fractions (.45, .25): nowhere near a snap
        assertEquals(.45f, e.shape.vertices[0].first, 0.001f)
        assertEquals(.25f, e.shape.vertices[0].second, 0.001f)
        assertEquals(tri.vertices[1], e.shape.vertices[1]) // the others stay
        assertTrue(e.guides.isEmpty())
    }

    @Test
    fun aCornerCanGoPastTheBoxAndTheBoxGrowsToTakeItIn() {
        // Pulled out to the left and up from the box at (1000, 1000) 2000 by 2000.
        val e = ShapeEdit.moveVertex(tri, 0, 400f, 200f, 0f)
        val s = e.shape
        assertEquals(400f, s.x, 0.01f); assertEquals(200f, s.y, 0.01f) // the box now starts at the corner
        assertEquals(2600f, s.width, 0.01f); assertEquals(2800f, s.height, 0.01f)
        assertEquals(0f to 0f, s.vertices[0])
        // The other corners have not moved on the page.
        for (k in 1..2) {
            val before = tri.spot(tri.vertices[k].first, tri.vertices[k].second)
            val after = s.spot(s.vertices[k].first, s.vertices[k].second)
            assertEquals(before.first, after.first, 0.01f); assertEquals(before.second, after.second, 0.01f)
        }
        // Inside the box nothing grows, and a corner pulled in does not shrink it.
        val inside = ShapeEdit.moveVertex(tri, 0, 1900f, 1500f, 0f).shape
        assertEquals(tri.width, inside.width, 0f)
    }

    @Test
    fun aTurnedShapeGrowsInItsOwnFrame() {
        val turned = tri.copy(angle = (Math.PI / 2).toFloat())
        val target = turned.spot(1.5f, -.25f) // past the right and the top, in its own frame
        val e = ShapeEdit.moveVertex(turned, 0, target.first, target.second, 0f).shape
        assertEquals(turned.angle, e.angle, 0f)
        val at = e.spot(e.vertices[0].first, e.vertices[0].second)
        assertEquals(target.first, at.first, 0.01f); assertEquals(target.second, at.second, 0.01f)
        assertEquals(1.5f * turned.width, e.width, 0.01f) // 3000 wide: 0 to 1.5 of 2000
    }

    @Test
    fun aCornerSnapsToTheSidesAndToTheOtherCorners() {
        // Near x of the second corner (1.0) and the middle line y (.5): both snap, each with a guide.
        val e = ShapeEdit.moveVertex(tri, 0, 1000f + 2000f * .98f, 1000f + 2000f * .49f, 100f)
        assertEquals(1f to .5f, e.shape.vertices[0])
        assertEquals(2, e.guides.size)
        // Near the other corner's y (1.0) lines up with it, and the guide runs to that corner.
        val f = ShapeEdit.moveVertex(tri, 0, 1000f + 2000f * .3f, 1000f + 2000f * .985f, 100f)
        assertEquals(1f, f.shape.vertices[0].second, 0.001f)
        val g = f.guides.single()
        assertEquals(g.y1, g.y2, 0.01f) // level
    }

    @Test
    fun aTurnedShapesCornersAreDraggedInItsOwnFrame() {
        val turned = tri.copy(angle = (Math.PI / 2).toFloat())
        val p = turned.spot(.3f, .7f)
        val e = ShapeEdit.moveVertex(turned, 0, p.first, p.second, 0f)
        assertEquals(.3f, e.shape.vertices[0].first, 0.001f)
        assertEquals(.7f, e.shape.vertices[0].second, 0.001f)
    }

    @Test
    fun theCornerHandleDragsTheRadius() {
        val r = Shape("r", ShapeKind.RECT, 0f, 0f, 4000f, 2000f, corner = 0f)
        val inset = 300f
        val want = 600f
        val at = ShapeEdit.cornerHandle(r.copy(corner = want), inset)
        assertEquals(want, ShapeEdit.setCorner(r, at.first, at.second, inset, 10f).corner, 1f)
        assertEquals(1000f, ShapeEdit.setCorner(r, 9999f, 9999f, inset, 10f).corner, 0.01f) // at most half the shorter side
        assertEquals(0f, ShapeEdit.setCorner(r, 0f, 0f, inset, 50f).corner, 0f) // back to square
        assertEquals(r, ShapeEdit.setCorner(tri, 5f, 5f, inset, 1f).let { r }) // only rectangles have one
    }

    @Test
    fun squareAndCircleArePresets() {
        assertTrue(ShapePreset.SQUARE.locked && ShapePreset.CIRCLE.locked && !ShapePreset.RECTANGLE.locked)
        assertEquals(ShapeKind.RECT, ShapePreset.SQUARE.kind)
        assertEquals(ShapeKind.ELLIPSE, ShapePreset.CIRCLE.kind)
        val sq = ShapePreset.SQUARE.make("s", 0f, 0f, 700f, 700f)
        assertEquals(sq.width, sq.height, 0f)
    }
}
