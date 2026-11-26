package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTurnTest {
    private val far = PageTurn.PULL
    private fun d(dir: Dir, flow: Flow, pull: Double = far) = PageTurn.decide(pull, dir, flow)

    @Test
    fun aShortPullDoesNothing() {
        for (flow in Flow.entries) for (dir in Dir.entries) assertEquals(Turn.NONE, d(dir, flow, far - 1))
    }

    @Test
    fun aLongPullAddsOnlyDownOrRightAsTheFlowAllows() {
        assertEquals(Turn.ADD, d(Dir.DOWN, Flow.DOWN))
        assertEquals(Turn.NONE, d(Dir.RIGHT, Flow.DOWN))
        assertEquals(Turn.ADD, d(Dir.RIGHT, Flow.RIGHT))
        assertEquals(Turn.NONE, d(Dir.DOWN, Flow.RIGHT))
        assertEquals(Turn.ADD, d(Dir.DOWN, Flow.BOTH))
        assertEquals(Turn.ADD, d(Dir.RIGHT, Flow.BOTH))
        for (flow in Flow.entries) { // the top and left of the surface never add
            assertEquals(Turn.NONE, d(Dir.UP, flow))
            assertEquals(Turn.NONE, d(Dir.LEFT, flow))
        }
    }

    @Test
    fun theLongerAxisDecidesAndTheFingerDirectionIsTheOppositeOfThePage() {
        assertEquals(Dir.DOWN to 30.0, PageTurn.direction(5.0, -30.0)) // finger up: the page below
        assertEquals(Dir.UP to 30.0, PageTurn.direction(5.0, 30.0))
        assertEquals(Dir.RIGHT to 40.0, PageTurn.direction(-40.0, 10.0)) // finger left: the page on the right
        assertEquals(Dir.LEFT to 40.0, PageTurn.direction(40.0, 10.0))
        assertEquals(Dir.DOWN to 20.0, PageTurn.direction(-20.0, -20.0)) // a tie goes to the vertical
        assertNull(PageTurn.direction(0.0, 0.0))
    }

    @Test
    fun theLeftoverAddsToThePullAndMovingBackEasesItOffButNeverPastZero() {
        var p = 0.0
        p = PageTurn.pull(p, -10.0, -10.0) // dragging up at the bottom edge: nothing moved, all of it is pull
        p = PageTurn.pull(p, -15.0, -15.0)
        assertEquals(-25.0, p, 0.0)
        p = PageTurn.pull(p, 10.0, 0.0) // back the other way: the page moves, the pull shrinks
        assertEquals(-15.0, p, 0.0)
        p = PageTurn.pull(p, 40.0, 0.0) // more than the pull: stops at zero
        assertEquals(0.0, p, 0.0)
        assertEquals(0.0, PageTurn.pull(0.0, 5.0, 0.0), 0.0) // an ordinary pan with no pull stays at zero
    }

    @Test
    fun onlyTheLongerAxisOfAPullCounts() {
        assertEquals(0.0 to -40.0, PageTurn.dominant(6.0, -40.0)) // a vertical scroll with sideways drift: the drift is dropped
        assertEquals(30.0 to 0.0, PageTurn.dominant(30.0, -5.0))
        assertEquals(0.0 to 12.0, PageTurn.dominant(-12.0, 12.0)) // a tie goes to the vertical, as direction() does
        assertEquals(0.0 to 0.0, PageTurn.dominant(0.0, 0.0))
    }
}
