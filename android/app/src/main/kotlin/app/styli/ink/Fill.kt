package app.styli.ink

/**
 * Paint poured into a closed area: filled [rings] (the first is the outside, any others are holes; a point is
 * inside if it is inside an odd number of rings). Only colour: it does not follow the lines that closed it.
 * [color] is 0xAARRGGBB as on a light page. Never changes once made. Units are page units.
 */
class Fill(val id: String, val color: Int, val rings: List<List<Pair<Float, Float>>>) {
    fun copy(color: Int = this.color) = Fill(id, color, rings)

    fun map(m: Affine) = Fill(id, color, rings.map { r -> r.map { (x, y) -> m.x(x, y) to m.y(x, y) } })

    fun contains(px: Float, py: Float) = rings.count { insideRing(it, px, py) } % 2 == 1

    /** The box round all the rings. */
    fun box(): Box? {
        val all = rings.flatten()
        if (all.isEmpty()) return null
        return Box(all.minOf { it.first }.toDouble(), all.minOf { it.second }.toDouble(), all.maxOf { it.first }.toDouble(), all.maxOf { it.second }.toDouble())
    }

    companion object {
        /** Is (px, py) inside the closed ring (the last point joins the first)? */
        fun insideRing(ring: List<Pair<Float, Float>>, px: Float, py: Float): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val (xi, yi) = ring[i]
                val (xj, yj) = ring[j]
                if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
