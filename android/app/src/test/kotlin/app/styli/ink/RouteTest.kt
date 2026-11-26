package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private val right = 1f to 0f
    private val left = -1f to 0f
    private val down = 0f to 1f
    private val up = 0f to -1f

    private fun axisAligned(p: List<Pair<Float, Float>>) = (1 until p.size).all { Math.abs(p[it].first - p[it - 1].first) < 0.01f || Math.abs(p[it].second - p[it - 1].second) < 0.01f }

    @Test
    fun straightIsTheTwoEnds() {
        assertEquals(listOf(0f to 0f, 100f to 50f), Routes.path(Route.STRAIGHT, 0f to 0f, right, 100f to 50f, left, 300f))
    }

    @Test
    fun aCurveStartsAndEndsOnTheEndsAndLeavesAtRightAngles() {
        val p = Routes.path(Route.CURVE, 0f to 0f, right, 4000f to 2000f, left, 0f)
        assertEquals(0f to 0f, p.first())
        assertEquals(4000f, p.last().first, 0.01f); assertEquals(2000f, p.last().second, 0.01f)
        assertTrue(p[1].first > 0f && Math.abs(p[1].second) < p[1].first * 0.3f) // heads off to the right
        assertEquals(49, p.size)
    }

    @Test
    fun bendPushesTheMiddleSidewaysByThatMuch() {
        val flat = Routes.path(Route.CURVE, 0f to 0f, right, 4000f to 0f, left, 0f)[24]
        val bent = Routes.path(Route.CURVE, 0f to 0f, right, 4000f to 0f, left, 500f)[24]
        assertEquals(flat.first, bent.first, 0.5f)
        assertEquals(500f, Math.abs(bent.second - flat.second), 1f)
    }

    @Test
    fun dragTheMiddleAndTheBendComesBack() {
        val a = 0f to 0f; val b = 4000f to 1000f
        val want = 700f
        val mid = Routes.path(Route.CURVE, a, right, b, left, want)[24]
        assertEquals(want, Routes.bendFor(Route.CURVE, a, right, b, left, mid), 1f)
        val e = Routes.path(Route.ELBOW, a, right, b, left, 300f)
        val x = e.first { it.first > 1000f }.first
        assertEquals(300f, Routes.bendFor(Route.ELBOW, a, right, b, left, x to 500f), 1f)
    }

    @Test
    fun anElbowFacingEachOtherIsAZWithRightAngles() {
        val p = Routes.path(Route.ELBOW, 0f to 0f, right, 4000f to 1000f, left, 0f)
        assertTrue(axisAligned(p))
        assertEquals(0f to 0f, p.first())
        assertEquals(4000f to 1000f, p.last())
        assertEquals(4, p.size) // out and over, down, over and in (the stubs run on into the long runs)
        assertEquals(2000f, p.map { it.first }.filter { it > 500f && it < 3500f }.first(), 0.01f) // the middle is halfway
    }

    @Test
    fun anElbowFromSideToTopIsAnL() {
        val p = Routes.path(Route.ELBOW, 0f to 0f, right, 3000f to 3000f, up, 0f)
        assertTrue(axisAligned(p))
        assertEquals(0f to 0f, p.first())
        assertEquals(3000f to 3000f, p.last())
    }

    @Test
    fun bothLeavingTheSameWayGoRoundBeyondBoth() {
        val p = Routes.path(Route.ELBOW, 0f to 0f, right, 0f to 2000f, right, 0f)
        assertTrue(axisAligned(p))
        assertTrue(p.maxOf { it.first } >= 299f) // out past the stubs, not through the shapes
    }

    @Test
    fun aFreeEndLeavesTowardsTheOtherOne() {
        val p = Routes.path(Route.ELBOW, 0f to 0f, null, 3000f to 100f, null, 0f)
        assertTrue(axisAligned(p))
        assertTrue(p[1].first > 0f)
    }

    @Test
    fun theMiddleByLength() {
        assertEquals(50f to 0f, Routes.middle(listOf(0f to 0f, 100f to 0f)))
        assertEquals(100f to 50f, Routes.middle(listOf(0f to 0f, 100f to 0f, 100f to 200f)))
    }

    // ---- shapes: spots on the outline ----

    private val box = Shape("a", ShapeKind.RECT, 0f, 0f, 1000f, 600f)

    @Test
    fun nearestOutlineOfARectangle() {
        assertEquals(.5f to 0f, box.nearestOutline(500f, -300f)) // above: the top edge
        assertEquals(1f, box.nearestOutline(1500f, 300f).first, 0.001f)
        val inside = box.nearestOutline(900f, 300f) // inside, nearest the right edge
        assertEquals(1f, inside.first, 0.001f)
        assertEquals(0.5f, inside.second, 0.001f)
    }

    @Test
    fun nearestOutlineOfAnEllipseAndAPolygon() {
        val e = Shape("e", ShapeKind.ELLIPSE, 0f, 0f, 1000f, 1000f)
        val (fx, fy) = e.nearestOutline(5000f, 500f)
        assertEquals(1f, fx, 0.001f); assertEquals(0.5f, fy, 0.001f)
        val tri = ShapePreset.TRIANGLE.make("t", 0f, 0f, 1000f, 1000f)
        val s = tri.spot(tri.nearestOutline(500f, 5000f).first, tri.nearestOutline(500f, 5000f).second)
        assertEquals(500f, s.first, 0.5f); assertEquals(1000f, s.second, 0.5f) // straight down: the base
    }

    @Test
    fun snapSpotsAreTheSideMiddles() {
        assertEquals(4, box.snapSpots().size)
        assertTrue(box.snapSpots().contains(1f to .5f))
        assertEquals(3, ShapePreset.TRIANGLE.make("t", 0f, 0f, 1000f, 1000f).snapSpots().size)
        assertTrue(ShapePreset.TRIANGLE.make("t", 0f, 0f, 1000f, 1000f).snapSpots().contains(.5f to 1f)) // middle of the base
    }

    @Test
    fun outwardPointsAwayFromTheShape() {
        assertEquals(1f to 0f, box.outwardAt(1000f, 300f))
        assertEquals(0f to -1f, box.outwardAt(500f, 0f))
        val tri = ShapePreset.TRIANGLE.make("t", 0f, 0f, 1000f, 1000f)
        val d = tri.outwardAt(500f, 1000f) // the base: straight down
        assertEquals(0f, d.first, 0.01f); assertEquals(1f, d.second, 0.01f)
        val turned = box.copy(angle = (Math.PI / 2).toFloat()) // a quarter turn: its right side now faces down
        val t = turned.outwardAt(turned.spot(1f, .5f).first, turned.spot(1f, .5f).second)
        assertEquals(0f, t.first, 0.01f); assertEquals(1f, t.second, 0.01f)
    }

    // ---- connectors on those spots ----

    @Test
    fun anEndOnASpotKeepsItsPlaceOnTheShapeAsItMoves() {
        val c = Connector("c", End("a", End.SPOT, 0f, 0f, 1f, .5f), End(null, 0, 5000f, 300f))
        val here = c.ends(mapOf("a" to box)::get)
        assertEquals(1000f to 300f, here.a)
        assertEquals(1f to 0f, here.da)
        val moved = box.copy(x = 200f, y = 100f, width = 2000f)
        assertEquals(2200f to 400f, c.ends(mapOf("a" to moved)::get).a)
    }

    @Test
    fun theOldSideAnchorsAndAutoStillWork() {
        val side = Connector("c", End("a", 2), End(null, 0, 5000f, 300f)).ends(mapOf("a" to box)::get)
        assertEquals(1000f to 300f, side.a)
        val auto = Connector("c", End("a", 0), End(null, 0, 5000f, 300f)).ends(mapOf("a" to box)::get)
        assertEquals(1000f, auto.a.first, 0.01f)
    }

    @Test
    fun theConnectorsPathUsesItsRouteAndShapes() {
        val c = Connector("c", End("a", End.SPOT, 0f, 0f, 1f, .5f), End(null, 0, 3000f, 2000f), route = Route.ELBOW)
        val p = c.path(mapOf("a" to box)::get)
        assertTrue(axisAligned(p))
        assertEquals(1000f to 300f, p.first())
        assertFalse(p.size < 3)
    }

    // ---- bend points ----

    private fun passesThrough(path: List<Pair<Float, Float>>, p: Pair<Float, Float>) =
        Routes.nearestOn(path, p).let { Math.hypot((it.first - p.first).toDouble(), (it.second - p.second).toDouble()) < 1.0 }

    @Test
    fun aStraightRouteBendsAtItsPoints() {
        val p = Routes.path(Route.STRAIGHT, 0f to 0f, null, 3000f to 0f, null, 0f, listOf(1000f to 800f, 2000f to -800f))
        assertEquals(listOf(0f to 0f, 1000f to 800f, 2000f to -800f, 3000f to 0f), p)
    }

    @Test
    fun aCurveRunsSmoothlyThroughItsPoints() {
        val via = listOf(1000f to 800f, 2000f to -800f)
        val p = Routes.path(Route.CURVE, 0f to 0f, right, 3000f to 0f, left, 0f, via)
        assertEquals(0f to 0f, p.first())
        assertEquals(3000f, p.last().first, 0.01f)
        via.forEach { assertTrue("passes $it", passesThrough(p, it)) }
        // Leaves the first end along its direction.
        assertTrue(p[1].first > 0f && Math.abs(p[1].second) < p[1].first)
    }

    @Test
    fun anElbowTurnsAtRightAnglesThroughItsPoints() {
        val via = listOf(1500f to 1200f)
        val p = Routes.path(Route.ELBOW, 0f to 0f, right, 3000f to 0f, left, 0f, via)
        assertTrue(axisAligned(p))
        assertTrue("the bend point is a corner", p.contains(via[0]))
        assertEquals(0f to 0f, p.first())
        assertEquals(3000f to 0f, p.last())
    }

    @Test
    fun everyBendPointOfAnElbowIsACornerNoMatterHowManyThereAre() {
        val via = listOf(1000f to 600f, 2000f to 1500f, 2800f to 400f)
        val p = Routes.path(Route.ELBOW, 0f to 0f, right, 4000f to 0f, left, 0f, via)
        assertTrue(axisAligned(p))
        via.forEach { assertTrue("corner at $it", p.contains(it)) }
        assertEquals(4000f to 0f, p.last())
    }

    @Test
    fun addingASecondPointKeepsTheFirstOnARightAngleRoute() {
        val c = connector(Route.ELBOW)
        val one = c.withPointAt(shapes::get, 2500f to 900f).first
        val two = one.withPointAt(shapes::get, 4200f to 2000f).first
        assertEquals(2, two.via.size)
        assertEquals(one.via[0], two.via[0])
        val path = two.path(shapes::get)
        assertTrue(axisAligned(path))
        two.via.forEach { assertTrue("corner at $it", path.contains(it)) }
    }

    private val shapes = mapOf("a" to Shape("a", ShapeKind.RECT, 0f, 0f, 1000f, 600f), "b" to Shape("b", ShapeKind.RECT, 5000f, 2000f, 1000f, 600f))
    private fun connector(route: Route) = Connector("c", End("a", End.SPOT, 0f, 0f, 1f, .5f), End("b", End.SPOT, 0f, 0f, 0f, .5f), route = route)

    @Test
    fun aPointGoesWhereYouTapOnTheLine() {
        for (route in listOf(Route.STRAIGHT, Route.ELBOW, Route.CURVE)) {
            val c = connector(route)
            val before = c.path(shapes::get)
            val tap = Routes.middle(before).let { it.first + 30f to it.second - 20f } // a little off the line
            val (n, k) = c.withPointAt(shapes::get, tap)
            assertEquals(1, n.via.size)
            assertEquals(0, k)
            assertTrue("$route: on the old line", passesThrough(before, n.via[0]))
            assertTrue("$route: near the tap", Math.hypot((n.via[0].first - tap.first).toDouble(), (n.via[0].second - tap.second).toDouble()) < 60.0)
        }
    }

    @Test
    fun theNewPointTakesItsPlaceInOrderAlongTheLine() {
        val c = connector(Route.STRAIGHT) // from (1000, 300) to (5000, 2300)
        val one = c.withPointAt(shapes::get, 4000f to 1800f).first // near the far end
        val (two, k) = one.withPointAt(shapes::get, 2000f to 800f) // nearer the start: goes first
        assertEquals(0, k)
        assertEquals(2, two.via.size)
        assertTrue(two.via[0].first < two.via[1].first)
        val (three, k3) = two.withPointAt(shapes::get, 3000f to 1300f) // between them
        assertEquals(1, k3)
        assertTrue(three.via[0].first < three.via[1].first && three.via[1].first < three.via[2].first)
    }

    @Test
    fun arcAtCountsLengthAlongTheLine() {
        val p = listOf(0f to 0f, 100f to 0f, 100f to 100f)
        assertEquals(0f, Routes.arcAt(p, 0f to 0f), 0.01f)
        assertEquals(150f, Routes.arcAt(p, 100f to 50f), 0.01f)
        assertEquals(200f, Routes.arcAt(p, 100f to 100f), 0.01f)
    }

    @Test
    fun aPointComesOffAgain() {
        val (two, _) = connector(Route.CURVE).withPointAt(shapes::get, 2000f to 800f).first.withPointAt(shapes::get, 4000f to 1800f)
        assertEquals(2, two.via.size)
        val one = two.withoutPoint(0)
        assertEquals(listOf(two.via[1]), one.via)
        assertTrue(one.withoutPoint(0).via.isEmpty())
    }

    // ---- snapping ----

    @Test
    fun aPointLinesUpWithItsNeighboursWithinReach() {
        val others = listOf(0f to 0f, 3000f to 1000f)
        assertEquals(3000f to 40f, Routes.snapPoint(2980f to 40f, others, 50f).let { it.first to 40f }) // x pulled in
        assertEquals(0f to 1000f, Routes.snapPoint(30f to 990f, others, 50f)) // both
        assertEquals(1500f to 500f, Routes.snapPoint(1500f to 500f, others, 50f)) // far from both: left alone
    }

    @Test
    fun anElbowsBendSnapsToCentreOrToAnEnd() {
        val a = 0f to 0f; val b = 4000f to 1000f
        assertEquals(0f, Routes.snappedBend(Route.ELBOW, a, right, b, left, 20f, 40f).first, 0.01f) // back to the middle
        assertEquals(300f, Routes.snappedBend(Route.ELBOW, a, right, b, left, 300f, 40f).first, 0.01f) // far from a snap: as dragged
        assertEquals(-2000f, Routes.snappedBend(Route.ELBOW, a, right, b, left, -1985f, 40f).first, 0.01f) // in line with the first end (x = 0)
        assertEquals(123f, Routes.snappedBend(Route.CURVE, a, right, b, left, 123f, 40f).first, 0f) // other routes untouched
    }

    @Test
    fun snapsSayWhatTheyLinedUpWith() {
        val sn = Routes.snapPointWith(2980f to 40f, listOf(0f to 0f, 3000f to 1000f), 50f)
        assertEquals(3000f to 1000f, sn.xFrom)
        assertEquals(0f to 0f, sn.yFrom)
        assertEquals(3000f to 0f, sn.p)
        assertEquals(null, Routes.snapPointWith(1500f to 500f, listOf(0f to 0f), 50f).xFrom)
        assertEquals(0f, Routes.snappedBend(Route.ELBOW, 0f to 0f, right, 4000f to 1000f, left, 20f, 40f).first, 0.01f)
        assertEquals(2000f, Routes.snappedBend(Route.ELBOW, 0f to 0f, right, 4000f to 1000f, left, 20f, 40f).second!!, 0.01f) // the centre line
        assertEquals(null, Routes.snappedBend(Route.ELBOW, 0f to 0f, right, 4000f to 1000f, left, 300f, 40f).second)
    }
}
