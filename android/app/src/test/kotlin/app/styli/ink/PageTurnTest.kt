package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTurnTest {
    private val far = PageTurn.PULL
    private fun d(dir: Dir, neighbour: Boolean, flow: Flow, pull: Double = far) = PageTurn.decide(pull, dir, neighbour, flow)

    @Test
    fun aShortPullDoesNothing() {
        for (flow in Flow.entries) for (dir in Dir.entries) for (n in listOf(true, false))
            assertEquals(Turn.NONE, d(dir, n, flow, far - 1))
    }

    @Test
    fun aLongPullGoesToAnExistingPageInAnyDirectionWhateverTheFlow() {
        for (flow in Flow.entries) for (dir in Dir.entries) assertEquals(Turn.GO, d(dir, true, flow))
    }

    @Test
    fun withNoPageThereAPullAddsOnlyDownOrRightAsTheFlowAllows() {
        assertEquals(Turn.ADD, d(Dir.DOWN, false, Flow.DOWN))
        assertEquals(Turn.NONE, d(Dir.RIGHT, false, Flow.DOWN))
        assertEquals(Turn.ADD, d(Dir.RIGHT, false, Flow.RIGHT))
        assertEquals(Turn.NONE, d(Dir.DOWN, false, Flow.RIGHT))
        assertEquals(Turn.ADD, d(Dir.DOWN, false, Flow.BOTH))
        assertEquals(Turn.ADD, d(Dir.RIGHT, false, Flow.BOTH))
        for (flow in Flow.entries) { // going back never makes a page
            assertEquals(Turn.NONE, d(Dir.UP, false, flow))
            assertEquals(Turn.NONE, d(Dir.LEFT, false, flow))
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
}
