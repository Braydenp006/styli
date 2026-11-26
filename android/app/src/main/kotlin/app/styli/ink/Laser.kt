package app.styli.ink

/** One dot of the laser's trail. [joined] means a line runs to it from the dot before (same drag). [alpha] is 0 to 1. */
data class LaserDot(val x: Float, val y: Float, val alpha: Float, val joined: Boolean)

/** The laser pointer's trail: points that fade out after [FADE_MS]. Nothing here is saved. */
class LaserTrail {
    private class P(val x: Float, val y: Float, val t: Long, val joined: Boolean)
    private val pts = ArrayList<P>()
    private var fresh = true // the next point starts a new drag

    fun add(x: Float, y: Float, now: Long) { pts.add(P(x, y, now, !fresh)); fresh = false }

    /** The pen lifted: the next point is not joined to the last. */
    fun lift() { fresh = true }

    /** Drops the points that have faded out. */
    fun prune(now: Long) { pts.removeAll { now - it.t >= FADE_MS } }

    val isEmpty get() = pts.isEmpty()

    fun alive(now: Long): List<LaserDot> =
        pts.filter { now - it.t < FADE_MS }.map { LaserDot(it.x, it.y, 1f - (now - it.t).toFloat() / FADE_MS, it.joined) }

    companion object { const val FADE_MS = 1000L }
}
