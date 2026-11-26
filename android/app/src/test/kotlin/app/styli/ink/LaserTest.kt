package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaserTest {
    @Test
    fun dotsFadeAndGo() {
        val t = LaserTrail()
        t.add(1f, 1f, 0)
        assertEquals(1f, t.alive(0)[0].alpha, 0.001f)
        assertEquals(0.5f, t.alive(LaserTrail.FADE_MS / 2)[0].alpha, 0.001f)
        assertTrue(t.alive(LaserTrail.FADE_MS).isEmpty())
        t.prune(LaserTrail.FADE_MS)
        assertTrue(t.isEmpty)
    }

    @Test
    fun aNewDragIsNotJoinedToTheLastOne() {
        val t = LaserTrail()
        t.add(0f, 0f, 0); t.add(1f, 1f, 1); t.lift(); t.add(5f, 5f, 2)
        assertEquals(listOf(false, true, false), t.alive(2).map { it.joined })
        assertFalse(t.isEmpty)
    }
}
