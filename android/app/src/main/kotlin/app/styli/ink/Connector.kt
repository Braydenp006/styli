package app.styli.ink

enum class Arrowhead { NONE, OPEN, FILLED, DOT }

/**
 * One end of a connector. With a [shape] it sits on that shape's outline: where the line from the shape's centre
 * towards side [anchor] (1 top, 2 right, 3 bottom, 4 left) crosses it, or towards the connector's other end if
 * [anchor] is 0. Without one it is the free point ([x], [y]), which is also where an attached end was last
 * written.
 */
class End(val shape: String? = null, val anchor: Int = 0, val x: Float = 0f, val y: Float = 0f) {
    fun at(p: Pair<Float, Float>) = End(shape, anchor, p.first, p.second)
}

/** A straight line between two ends with an optional arrowhead at each. Never changes once made. Units are page units. */
class Connector(
    val id: String,
    val from: End,
    val to: End,
    val fromHead: Arrowhead = Arrowhead.NONE,
    val toHead: Arrowhead = Arrowhead.FILLED,
    val color: Int = 0,
    val lineWidth: Float = 40f,
    val dashed: Boolean = false,
) {
    fun copy(
        from: End = this.from, to: End = this.to, fromHead: Arrowhead = this.fromHead, toHead: Arrowhead = this.toHead,
        color: Int = this.color, lineWidth: Float = this.lineWidth, dashed: Boolean = this.dashed,
    ) = Connector(id, from, to, fromHead, toHead, color, lineWidth, dashed)

    /** The two end points on the page, following the shapes [shapeOf] finds (an end whose shape is gone stays where it was written). */
    fun resolve(shapeOf: (String) -> Shape?): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val a = from.shape?.let(shapeOf)
        val b = to.shape?.let(shapeOf)
        // What each end points at when the other one is on its outline: a fixed side, a free point, or a centre.
        fun aim(e: End, s: Shape?) = if (s == null) e.x to e.y else s.anchorPoint(e.anchor) ?: s.center
        val aimA = aim(from, a)
        val aimB = aim(to, b)
        val pa = if (a == null) from.x to from.y else a.anchorPoint(from.anchor) ?: a.outlineToward(aimB.first, aimB.second)
        val pb = if (b == null) to.x to to.y else b.anchorPoint(to.anchor) ?: b.outlineToward(aimA.first, aimA.second)
        return pa to pb
    }
}
