package app.styli.ink

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTest {
    private companion object { const val WOBBLE = 0.40f }

    private val rnd = Random(7)

    /** A hand-drawn stroke: points about every 0.5 mm along [path], each off by up to 0.15 mm. */
    private fun trace(path: List<Pair<Float, Float>>): List<Pt> {
        val out = ArrayList<Pt>()
        for (i in 0 until path.size - 1) {
            val (ax, ay) = path[i]
            val (bx, by) = path[i + 1]
            val n = maxOf(1, (hypot(bx - ax, by - ay) / 50f).toInt())
            for (k in 0 until n) {
                val f = k / n.toFloat()
                out.add(Pt(ax + (bx - ax) * f + jitter(), ay + (by - ay) * f + jitter(), out.size * 8L, 0.7f))
            }
        }
        out.add(Pt(path.last().first, path.last().second, out.size * 8L, 0.7f))
        return out
    }

    private fun jitter() = (rnd.nextFloat() - 0.5f) * 30f

    private fun turned(pts: List<Pair<Float, Float>>, deg: Double) = pts.map {
        val c = cos(Math.toRadians(deg)).toFloat(); val s = sin(Math.toRadians(deg)).toFloat()
        (it.first * c - it.second * s) to (it.first * s + it.second * c)
    }

    private fun ellipse(a: Float, b: Float, deg: Double = 0.0, over: Int = 3) =
        turned((0..72 + over).map { val t = 2 * PI * it / 72; (a * cos(t)).toFloat() to (b * sin(t)).toFloat() }, deg)

    private fun box(w: Float, h: Float) = listOf(0f to 0f, w to 0f, w to h, 0f to h, 0f to 20f)

    @Test
    fun aWobblyLineBecomesTwoPointsAndLevelWhenNearlyLevel() {
        val out = ShapeGeometry.snap(trace(listOf(0f to 0f, 5000f to 80f)))!! // about 1 degree
        assertEquals(2, out.size)
        assertEquals(out[0].y, out[1].y, 0f)
        assertTrue(abs(out[1].x - 5000f) < 40f)
    }

    @Test
    fun aSlantedLineKeepsItsSlant() {
        val out = ShapeGeometry.snap(trace(listOf(0f to 0f, 4000f to 1500f)))!!
        assertEquals(2, out.size)
        assertTrue(abs(out[1].y - 1500f) < 40f)
    }

    @Test
    fun aCurveAShortStrokeAndAZigzagStayAsDrawn() {
        val bow = (0..40).map { it * 125f to (400f * sin(PI * it / 40)).toFloat() } // bends 8% of its length
        assertNull(ShapeGeometry.snap(trace(bow)))
        assertNull(ShapeGeometry.snap(trace(listOf(0f to 0f, 300f to 10f)))) // 3 mm: writing
        assertNull(ShapeGeometry.snap(trace(listOf(0f to 0f, 3000f to 0f, 0f to 20f, 3000f to 20f)))) // scribbled over
    }

    @Test
    fun aCircleBecomesARound72PointClosedShape() {
        val out = ShapeGeometry.snap(trace(ellipse(2000f, 2000f)))!!
        assertEquals(73, out.size)
        assertEquals(out.first().x, out.last().x, 0f)
        val (cx, cy) = out.dropLast(1).let { o -> o.map { it.x }.average().toFloat() to o.map { it.y }.average().toFloat() }
        for (p in out) assertEquals(2000f, hypot(p.x - cx, p.y - cy), 80f)
    }

    @Test
    fun aSlightlyOvalLoopIsMadeRound() {
        val out = ShapeGeometry.snap(trace(ellipse(2000f, 1850f)))!!
        assertEquals(out.maxOf { it.x } - out.minOf { it.x }, out.maxOf { it.y } - out.minOf { it.y }, 5f)
    }

    @Test
    fun anEllipseStaysAnEllipseAndLevelWhenNearlyLevel() {
        val out = ShapeGeometry.snap(trace(ellipse(3000f, 1500f, 2.0)))!!
        assertEquals(6000f, out.maxOf { it.x } - out.minOf { it.x }, 150f)
        assertEquals(3000f, out.maxOf { it.y } - out.minOf { it.y }, 150f)
    }

    @Test
    fun aTiltedEllipseKeepsItsTilt() {
        val out = ShapeGeometry.snap(trace(ellipse(3000f, 1000f, 30.0)))!!
        // Level, its height would be 2000; at 30 degrees it is about 3200.
        assertTrue(out.maxOf { it.y } - out.minOf { it.y } > 2800f)
    }

    @Test
    fun aRoughRectangleBecomesFourLevelCorners() {
        val out = ShapeGeometry.snap(trace(turned(box(5000f, 3000f), 2.0)))!!
        assertEquals(5, out.size)
        assertEquals(out.first().x, out.last().x, 0f)
        assertEquals(2, out.dropLast(1).map { Math.round(it.x) }.distinct().size)
        assertEquals(2, out.dropLast(1).map { Math.round(it.y) }.distinct().size)
        assertEquals(5000f, out.maxOf { it.x } - out.minOf { it.x }, 150f)
    }

    @Test
    fun aRectangleTiltedWellOffLevelStaysTilted() {
        val out = ShapeGeometry.snap(trace(turned(box(5000f, 3000f), 30.0)))!!
        assertEquals(5, out.size)
        assertTrue(out.dropLast(1).map { Math.round(it.x) }.distinct().size == 4)
    }

    @Test
    fun aTriangleBecomesThreeCorners() {
        val out = ShapeGeometry.snap(trace(listOf(0f to 0f, 5000f to 300f, 1800f to -4000f, 0f to -30f)))!!
        assertEquals(4, out.size)
        assertEquals(out.first().x, out.last().x, 0f)
        // Each corner of the drawing is near one of the three points.
        for ((x, y) in listOf(0f to 0f, 5000f to 300f, 1800f to -4000f)) assertTrue(out.any { hypot(it.x - x, it.y - y) < 400f })
    }

    @Test
    fun aStarAClosedScribbleAndATinyLoopAreLeftAlone() {
        val star = (0..10).map { val r = if (it % 2 == 0) 3000f else 1200f; val t = PI * it / 5; (r * cos(t)).toFloat() to (r * sin(t)).toFloat() }
        assertNull(ShapeGeometry.snap(trace(star)))
        assertNull(ShapeGeometry.snap(trace(ellipse(150f, 150f)))) // 3 mm across: a handwritten o
        assertNull(ShapeGeometry.snap(trace(listOf(0f to 0f, 4000f to 600f, 0f to 1200f, 4000f to 1800f, 0f to 2400f, 4000f to 3000f, 0f to 20f)))) // zigzag back to the start
    }

    @Test
    fun theNewPointsRunOverTheSameTime() {
        val raw = trace(ellipse(2000f, 2000f))
        val out = ShapeGeometry.snap(raw)!!
        assertEquals(0L, out.first().t)
        assertEquals(raw.last().t, out.last().t)
    }

    @Test
    fun aRectangleTiltedEitherWayKeepsItsWidthAndHeight() {
        for (tilt in listOf(-7.0, -2.0, 2.0, 7.0)) {
            val out = ShapeGeometry.snap(trace(turned(box(6000f, 2500f), tilt)))!!
            assertEquals("tilt $tilt", 6000f, out.maxOf { it.x } - out.minOf { it.x }, 250f)
            assertEquals("tilt $tilt", 2500f, out.maxOf { it.y } - out.minOf { it.y }, 250f)
        }
    }

    @Test
    fun aSloppyCircleAndASloppyLineStillSnap() {
        // Radius wobbles by 6% round the loop, and the ends miss each other.
        val loop = (0..60).map { val t = 2 * PI * it / 66; val r = 2000f * (1 + 0.06f * sin(3 * t).toFloat()); (r * cos(t)).toFloat() to (r * sin(t)).toFloat() }
        assertNotNull(ShapeGeometry.snap(trace(loop)))
        val bendy = (0..20).map { it * 250f to (5000f * 0.05f * sin(PI * it / 20)).toFloat() } // bows 5% of its length
        assertNotNull(ShapeGeometry.snap(trace(bendy)))
    }

    @Test
    fun strengthDecidesHowSloppyIsStillCorrected() {
        val loop = (0..60).map { val t = 2 * PI * it / 66; val r = 2000f * (1 + 0.06f * sin(3 * t).toFloat()); (r * cos(t)).toFloat() to (r * sin(t)).toFloat() }
        val bendy = (0..20).map { it * 250f to (5000f * 0.05f * sin(PI * it / 20)).toFloat() }
        assertNull(ShapeGeometry.snap(trace(bendy), 0.5f))
        assertNotNull(ShapeGeometry.snap(trace(bendy), 1f))
        assertNull(ShapeGeometry.snap(trace(loop), 0.3f))
        assertNotNull(ShapeGeometry.snap(trace(loop), 1.6f))
        // A bow of 12% is a curve even at the highest strength.
        assertNull(ShapeGeometry.snap(trace((0..40).map { it * 125f to (600f * sin(PI * it / 40)).toFloat() }), 1.6f))
    }

    @Test
    fun strengthAlsoWidensHowMuchAShapeMayWobble() {
        // A closed loop whose radius swings by 40% (five lobes): too rough for normal, fine for high.
        val loop = (0..66).map { val t = 2 * PI * it / 66; val r = 2000f * (1 + WOBBLE * sin(5 * t).toFloat()); (r * cos(t)).toFloat() to (r * sin(t)).toFloat() }
        assertNull(ShapeGeometry.snap(trace(loop), 1f))
        assertNotNull(ShapeGeometry.snap(trace(loop), 1.6f))
    }

    @Test
    fun linesShapesAndLevelAreSeparateSettings() {
        val line = trace(listOf(0f to 0f, 5000f to 80f))
        val circle = trace(ellipse(2000f, 2000f))
        assertNull(ShapeGeometry.snap(line, ShapeGeometry.Settings(lines = 0f)))
        assertNotNull(ShapeGeometry.snap(circle, ShapeGeometry.Settings(lines = 0f)))
        assertNull(ShapeGeometry.snap(circle, ShapeGeometry.Settings(shapes = 0f)))
        assertNotNull(ShapeGeometry.snap(line, ShapeGeometry.Settings(shapes = 0f)))
        // Level off: the 1 degree slope stays; level on: it is made flat.
        val slope = ShapeGeometry.snap(line, ShapeGeometry.Settings(level = 0f))!!
        assertTrue(abs(slope[1].y - slope[0].y) > 50f)
        // A rectangle 2 degrees off level keeps its tilt with level off.
        val tilted = ShapeGeometry.snap(trace(turned(box(5000f, 3000f), 2.0)), ShapeGeometry.Settings(level = 0f))!!
        assertTrue(tilted.dropLast(1).map { Math.round(it.x) }.distinct().size == 4)
    }

    private fun lobed(wobble: Float) = (0..66).map { val t = 2 * PI * it / 66; val r = 2000f * (1 + wobble * sin(5 * t).toFloat()); (r * cos(t)).toFloat() to (r * sin(t)).toFloat() }

    @Test
    fun lowMediumAndHighReallyDiffer() {
        val low = 0.25f; val high = 2.5f
        // An ordinary hand: a line bowed 3% and a circle that wobbles 12%.
        val bowed = trace((0..20).map { it * 250f to (5000f * 0.03f * sin(PI * it / 20)).toFloat() })
        assertNull(ShapeGeometry.snap(bowed, ShapeGeometry.Settings(lines = low)))
        assertNotNull(ShapeGeometry.snap(bowed, ShapeGeometry.Settings(lines = 1f)))
        val loop = trace(lobed(0.12f))
        assertNull(ShapeGeometry.snap(loop, ShapeGeometry.Settings(shapes = low)))
        assertNotNull(ShapeGeometry.snap(loop, ShapeGeometry.Settings(shapes = 1f)))
        // A rough one: a line bowed 12% and a circle that wobbles 60%, taken only at high.
        val rough = trace((0..20).map { it * 250f to (5000f * 0.12f * sin(PI * it / 20)).toFloat() })
        assertNull(ShapeGeometry.snap(rough, ShapeGeometry.Settings(lines = 1f)))
        assertNotNull(ShapeGeometry.snap(rough, ShapeGeometry.Settings(lines = high)))
        val sloppy = trace(lobed(0.6f))
        assertNull(ShapeGeometry.snap(sloppy, ShapeGeometry.Settings(shapes = 1f)))
        assertNotNull(ShapeGeometry.snap(sloppy, ShapeGeometry.Settings(shapes = high)))
        // Level: a line 4 degrees off level is made level at medium, not at low.
        val slant = trace(listOf(0f to 0f, 5000f to 350f))
        val medium = ShapeGeometry.snap(slant, ShapeGeometry.Settings(level = 1f))!!
        val strict = ShapeGeometry.snap(slant, ShapeGeometry.Settings(level = low))!!
        assertEquals(medium[0].y, medium[1].y, 0f)
        assertTrue(abs(strict[1].y - strict[0].y) > 100f)
    }

    @Test
    fun holdStartsAgainOnlyWhenThePenMovesMoreThanOneMillimetre() {
        val h = ShapeGeometry.HoldTracker()
        h.start(0f, 0f)
        assertFalse(h.move(40f, 30f)) // 0.5 mm of shake
        assertTrue(h.move(300f, 0f)) // 3 mm: moved on
        assertFalse(h.move(330f, 20f)) // measured from where it stopped last, not from the start
        assertTrue(h.move(500f, 0f))
    }
}
