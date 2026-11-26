package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class FlingTest {
    private fun total(vx: Double, vy: Double, dt: Double, seconds: Double): Pair<Double, Double> {
        val f = Fling.start(vx, vy)!!
        var x = 0.0; var y = 0.0
        var t = 0.0
        while (t < seconds - 1e-9) { val (dx, dy) = f.step(dt); x += dx; y += dy; t += dt }
        return x to y
    }

    @Test
    fun aSlowReleaseDoesNotGlideAndAFastOneDoes() {
        assertNull(Fling.start(100.0, 50.0))
        assertNull(Fling.start(0.0, 0.0))
        assertNotNull(Fling.start(0.0, -150.0))
        assertNotNull(Fling.start(-200.0, 100.0)) // either way, any direction
    }

    @Test
    fun theDistanceIsTheSameWhateverTheFrameRate() {
        val expected = 2000.0 * Fling.TAU * (1 - exp(-1.0 / Fling.TAU)) // after one second
        for (dt in listOf(0.001, 1.0 / 60, 1.0 / 120, 0.05, 0.25))
            assertEquals("dt=$dt", expected, total(2000.0, 0.0, dt, 1.0).first, 1e-6)
    }

    @Test
    fun itGoesFurtherForAFasterReleaseAndKeepsItsDirection() {
        val (slow, _) = total(0.0 + 500.0, 0.0, 1.0 / 60, 3.0)
        val (fast, _) = total(1500.0, 0.0, 1.0 / 60, 3.0)
        assertEquals(3.0, fast / slow, 1e-6)
        val (dx, dy) = total(-600.0, 800.0, 1.0 / 60, 3.0)
        assertTrue(dx < 0 && dy > 0)
        assertEquals(-0.75, dx / dy, 1e-9) // the direction of the flick is kept
        assertEquals(1000.0 * Fling.TAU * (1 - exp(-3.0 / Fling.TAU)), Math.hypot(dx, dy), 1e-6)
    }

    @Test
    fun itEndsWhenItIsSlowEnough() {
        val f = Fling.start(0.0, -3000.0)!!
        assertFalse(f.done)
        var t = 0.0
        while (!f.done && t < 10.0) { f.step(1.0 / 60); t += 1.0 / 60 }
        assertTrue(f.done)
        assertTrue("it should take a few seconds, not milliseconds or forever: $t", t in 1.0..4.0)
    }

    @Test
    fun hittingAnEdgeStopsOnlyThatDirection() {
        val f = Fling.start(900.0, 900.0)!!
        f.step(0.1)
        f.stopX()
        val (dx, dy) = f.step(0.1)
        assertEquals(0.0, dx, 0.0)
        assertTrue(dy > 0)
        f.stopY()
        assertTrue(f.done)
    }

    @Test
    fun intensityScalesHowFarItCarriesAndZeroDoesNotGlide() {
        val normal = total(1000.0, 0.0, 1.0 / 60, 20.0).first // long enough for every glide to have finished
        fun far(intensity: Double): Double {
            val f = Fling.start(1000.0, 0.0, intensity)!!
            var x = 0.0
            repeat(1200) { x += f.step(1.0 / 60).first }
            return x
        }
        assertEquals(normal, far(1.0), 1e-9)
        assertEquals(2.0, far(2.0) / normal, 1e-3) // twice the intensity, twice the distance
        assertEquals(0.5, far(0.5) / normal, 1e-3)
        assertNull(Fling.start(5000.0, 0.0, 0.0))
        assertNull(Fling.start(5000.0, 0.0, -1.0))
    }
}
