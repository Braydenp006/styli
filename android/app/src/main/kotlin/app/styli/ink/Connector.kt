package app.styli.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Arrowhead { NONE, OPEN, FILLED, DOT }

enum class Route { STRAIGHT, ELBOW, CURVE }

/**
 * One end of a connector. With a [shape] it sits on that shape's outline: at the spot ([fx], [fy]), fractions of the
 * shape's box, when [anchor] is [SPOT]; where the line from the shape's centre towards side [anchor] (1 top, 2 right,
 * 3 bottom, 4 left) crosses it; or, with 0, towards the connector's other end. Without a shape it is the free point
 * ([x], [y]), which is also where an attached end was last written.
 */
class End(val shape: String? = null, val anchor: Int = 0, val x: Float = 0f, val y: Float = 0f, val fx: Float = 0f, val fy: Float = 0f) {
    fun at(p: Pair<Float, Float>) = End(shape, anchor, p.first, p.second, fx, fy)

    companion object { const val SPOT = 5 }
}

/** Where a connector's two ends are on the page, and the direction each leaves its shape (null for a free end). */
class Ends(val a: Pair<Float, Float>, val b: Pair<Float, Float>, val da: Pair<Float, Float>?, val db: Pair<Float, Float>?)

/** A line between two ends with an optional arrowhead at each, straight, with right angles or curved. Never changes once made. Units are page units. */
class Connector(
    val id: String,
    val from: End,
    val to: End,
    val fromHead: Arrowhead = Arrowhead.NONE,
    val toHead: Arrowhead = Arrowhead.FILLED,
    val color: Int = 0,
    val lineWidth: Float = 40f,
    val dashed: Boolean = false,
    val route: Route = Route.STRAIGHT,
    val bend: Float = 0f,
    val via: List<Pair<Float, Float>> = emptyList(),
) {
    fun copy(
        from: End = this.from, to: End = this.to, fromHead: Arrowhead = this.fromHead, toHead: Arrowhead = this.toHead,
        color: Int = this.color, lineWidth: Float = this.lineWidth, dashed: Boolean = this.dashed, route: Route = this.route, bend: Float = this.bend,
        via: List<Pair<Float, Float>> = this.via,
    ) = Connector(id, from, to, fromHead, toHead, color, lineWidth, dashed, route, bend, via)

    /** The ends, following the shapes [shapeOf] finds (an end whose shape is gone stays where it was written). */
    fun ends(shapeOf: (String) -> Shape?): Ends {
        val sa = from.shape?.let(shapeOf)
        val sb = to.shape?.let(shapeOf)
        // The ends that do not depend on the other one.
        fun fixed(e: End, s: Shape?): Pair<Float, Float>? = when {
            s == null -> e.x to e.y
            e.anchor == End.SPOT -> s.spot(e.fx, e.fy)
            else -> s.anchorPoint(e.anchor)
        }
        val fa = fixed(from, sa)
        val fb = fixed(to, sb)
        val aimA = fa ?: sa!!.center
        val aimB = fb ?: sb!!.center
        val pa = fa ?: sa!!.outlineToward(aimB.first, aimB.second)
        val pb = fb ?: sb!!.outlineToward(aimA.first, aimA.second)
        return Ends(pa, pb, sa?.outwardAt(pa.first, pa.second), sb?.outwardAt(pb.first, pb.second))
    }

    /** The two end points on the page. */
    fun resolve(shapeOf: (String) -> Shape?): Pair<Pair<Float, Float>, Pair<Float, Float>> = ends(shapeOf).let { it.a to it.b }

    /** The route as a polyline from the first end to the second (a curve is sampled finely). */
    fun path(shapeOf: (String) -> Shape?): List<Pair<Float, Float>> = ends(shapeOf).let { Routes.path(route, it.a, it.da, it.b, it.db, bend, via) }

    /**
     * The connector with one more bend point where [p] is, put on the nearest spot of the line, and which number it is
     * (they run in order from the first end). The old single bend is used up by it.
     */
    fun withPointAt(shapeOf: (String) -> Shape?, p: Pair<Float, Float>): Pair<Connector, Int> {
        val e = ends(shapeOf)
        val route = Routes.path(this.route, e.a, e.da, e.b, e.db, bend, via)
        val at = Routes.nearestOn(route, p)
        val s = Routes.arcAt(route, at)
        // Where it goes among the points there already: after the ones that lie earlier along the line.
        val k = via.count { Routes.arcAt(route, Routes.nearestOn(route, it)) < s }
        return copy(via = via.toMutableList().apply { add(k, at) }, bend = 0f) to k
    }

    /** The connector without bend point number [index]. */
    fun withoutPoint(index: Int): Connector = copy(via = via.filterIndexed { i, _ -> i != index }, bend = 0f)
}

/** The maths of arrowheads. */
object Arrow {
    /** How big a head is for a line [lineWidth] wide: five line widths, never under 3 mm. */
    fun size(lineWidth: Float) = maxOf(lineWidth * 5, 300f)

    /** The two back corners of a head at [tip] on a line coming from [other]: [size] back, 25 degrees to either side. */
    fun wings(tip: Pair<Float, Float>, other: Pair<Float, Float>, size: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val dx = other.first - tip.first
        val dy = other.second - tip.second
        val len = hypot(dx, dy)
        if (len == 0f) return tip to tip
        val ux = dx / len; val uy = dy / len // from the tip back along the line
        val c = kotlin.math.cos(Math.toRadians(25.0)).toFloat(); val s = kotlin.math.sin(Math.toRadians(25.0)).toFloat()
        fun turned(sign: Float) = (tip.first + size * (ux * c - sign * uy * s)) to (tip.second + size * (sign * ux * s + uy * c))
        return turned(1f) to turned(-1f)
    }
}

/**
 * How a connector gets from one end to the other. Each end has a direction it leaves its shape in (null for a free
 * end, which leaves towards the other end). [bend] is how far the middle of the route is pushed from where it
 * would be: sideways for a curve, along the span for the middle segment of an elbow.
 */
object Routes {
    /** How far an elbow runs straight out of a shape before it may turn (3 mm, less for a short span). */
    private const val STUB = 300f
    private const val CURVE_STEPS = 48
    private const val LEG_STEPS = 24

    private typealias P = Pair<Float, Float>

    private fun unit(from: P, to: P): P {
        val d = hypot(to.first - from.first, to.second - from.second)
        return if (d == 0f) 1f to 0f else ((to.first - from.first) / d) to ((to.second - from.second) / d)
    }

    /** The nearest of right, left, down and up. */
    private fun axis(d: P): P = if (abs(d.first) >= abs(d.second)) (if (d.first >= 0) 1f else -1f) to 0f else 0f to (if (d.second >= 0) 1f else -1f)

    fun path(route: Route, a: P, da: P?, b: P, db: P?, bend: Float, via: List<P> = emptyList()): List<P> = when {
        via.isNotEmpty() -> when (route) {
            Route.STRAIGHT -> listOf(a) + via + listOf(b)
            Route.CURVE -> curveVia(listOf(a) + via + listOf(b), da, db)
            Route.ELBOW -> elbowVia(listOf(a) + via + listOf(b), da, db)
        }
        route == Route.STRAIGHT -> listOf(a, b)
        route == Route.CURVE -> curve(a, da, b, db, bend)
        else -> elbow(a, da, b, db, bend)
    }

    /** A smooth curve through every point of [q]: it leaves the first and enters the last along their directions. */
    private fun curveVia(q: List<P>, da: P?, db: P?): List<P> {
        val n = q.size
        fun dist(i: Int, j: Int) = hypot(q[j].first - q[i].first, q[j].second - q[i].second)
        val ua = da ?: unit(q[0], q[1])
        val ub = db ?: unit(q[n - 1], q[n - 2])
        val m = ArrayList<P>(n)
        m.add(ua.first * 1.2f * dist(0, 1) to ua.second * 1.2f * dist(0, 1))
        for (i in 1 until n - 1) m.add(0.5f * (q[i + 1].first - q[i - 1].first) to 0.5f * (q[i + 1].second - q[i - 1].second))
        m.add(-ub.first * 1.2f * dist(n - 2, n - 1) to -ub.second * 1.2f * dist(n - 2, n - 1))
        val out = ArrayList<P>()
        out.add(q[0])
        for (i in 0 until n - 1) for (s in 1..LEG_STEPS) {
            val t = s.toFloat() / LEG_STEPS; val t2 = t * t; val t3 = t2 * t
            val h00 = 2 * t3 - 3 * t2 + 1; val h10 = t3 - 2 * t2 + t; val h01 = -2 * t3 + 3 * t2; val h11 = t3 - t2
            out.add((h00 * q[i].first + h10 * m[i].first + h01 * q[i + 1].first + h11 * m[i + 1].first) to
                (h00 * q[i].second + h10 * m[i].second + h01 * q[i + 1].second + h11 * m[i + 1].second))
        }
        return out
    }

    /**
     * Right angles through the points of [q]: each bend point is a corner of the route. It leaves the first end
     * straight out (a stub), then runs along that same direction to line up with the next point and turns to reach it,
     * and so on, so the route is a staircase of corners and no bend point lies in the middle of a straight run. The
     * last stretch comes in to the other end the way it faces.
     */
    private fun elbowVia(q: List<P>, da: P?, db: P?): List<P> {
        val a = q.first(); val b = q.last()
        val qa = axis(da ?: unit(a, q[1]))
        val alongX = qa.first != 0f // the direction every run starts in
        fun stub(from: P, to: P) = min(STUB, max(hypot(to.first - from.first, to.second - from.second) * 0.25f, 100f))
        val s0 = stub(a, q[1])
        var cur: P = (a.first + qa.first * s0) to (a.second + qa.second * s0)
        val pts = ArrayList<P>()
        pts.add(a); pts.add(cur)
        for (i in 1 until q.size - 1) {
            val v = q[i]
            pts.add(if (alongX) v.first to cur.second else cur.first to v.second) // along, to line up with it...
            pts.add(v) // ...then across to it: a corner
            cur = v
        }
        val qb = axis(db ?: unit(b, cur))
        val s1 = stub(cur, b)
        val b1 = (b.first + qb.first * s1) to (b.second + qb.second * s1)
        if ((qb.first != 0f) != alongX) { // it faces across our runs: along to line up, then straight in
            pts.add(if (alongX) b1.first to cur.second else cur.first to b1.second)
        } else if (alongX) { // it faces along them: along, across, along
            val xm = (cur.first + b1.first) / 2
            pts.add(xm to cur.second); pts.add(xm to b1.second)
        } else {
            val ym = (cur.second + b1.second) / 2
            pts.add(cur.first to ym); pts.add(b1.first to ym)
        }
        pts.add(b1); pts.add(b)
        return tidy(pts)
    }

    /** [p] with its x pulled onto the x of any of [others] within [tol], and likewise its y: so right-angle runs line up. */
    fun snapPoint(p: P, others: List<P>, tol: Float): P {
        val x = others.map { it.first }.minByOrNull { abs(it - p.first) }?.takeIf { abs(it - p.first) <= tol } ?: p.first
        val y = others.map { it.second }.minByOrNull { abs(it - p.second) }?.takeIf { abs(it - p.second) <= tol } ?: p.second
        return x to y
    }

    /** An elbow's [bend] pulled to centre the middle run, or to line it up with either end, when within [tol]. Other routes are left alone. */
    fun snappedBend(route: Route, a: P, da: P?, b: P, db: P?, bend: Float, tol: Float): Float {
        if (route != Route.ELBOW) return bend
        val p = plan(a, da, b, db)
        val onX = p.middleOnX ?: return bend
        val targets = if (onX) listOf(p.base, a.first, b.first) else listOf(p.base, a.second, b.second)
        val t = targets.minByOrNull { abs(p.base + bend - it) }!!
        return if (abs(p.base + bend - t) <= tol) t - p.base else bend
    }

    /** How far along [path] (its length up to there) the point [at], taken to be on it, is. */
    fun arcAt(path: List<P>, at: P): Float {
        var best = Float.MAX_VALUE; var along = 0f; var run = 0f
        for (i in 1 until path.size) {
            val (ax, ay) = path[i - 1]; val (bx, by) = path[i]
            val dx = bx - ax; val dy = by - ay
            val len = hypot(dx, dy)
            val t = if (len == 0f) 0f else (((at.first - ax) * dx + (at.second - ay) * dy) / (len * len)).coerceIn(0f, 1f)
            val d = hypot(ax + t * dx - at.first, ay + t * dy - at.second)
            if (d < best) { best = d; along = run + t * len }
            run += len
        }
        return along
    }

    /** The point of [path] nearest to [to]. */
    fun nearestOn(path: List<P>, to: P): P {
        var best = path.first(); var bestD = Float.MAX_VALUE
        for (i in 1 until path.size) {
            val (ax, ay) = path[i - 1]; val (bx, by) = path[i]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((to.first - ax) * dx + (to.second - ay) * dy) / len2).coerceIn(0f, 1f)
            val q = (ax + t * dx) to (ay + t * dy)
            val d = hypot(q.first - to.first, q.second - to.second)
            if (d < bestD) { bestD = d; best = q }
        }
        return best
    }

    private fun curve(a: P, da: P?, b: P, db: P?, bend: Float): List<P> {
        val dist = hypot(b.first - a.first, b.second - a.second)
        if (dist == 0f) return listOf(a, b)
        val ua = da ?: unit(a, b)
        val ub = db ?: unit(b, a)
        val k = dist * 0.4f
        val px = -(b.second - a.second) / dist; val py = (b.first - a.first) / dist // across the chord
        val h = bend * 4f / 3f // a bend of h on both control points moves the middle of the curve by 3/4 of it
        val c1 = (a.first + ua.first * k + px * h) to (a.second + ua.second * k + py * h)
        val c2 = (b.first + ub.first * k + px * h) to (b.second + ub.second * k + py * h)
        return (0..CURVE_STEPS).map { i ->
            val t = i.toFloat() / CURVE_STEPS; val u = 1 - t
            val w0 = u * u * u; val w1 = 3 * u * u * t; val w2 = 3 * u * t * t; val w3 = t * t * t
            (w0 * a.first + w1 * c1.first + w2 * c2.first + w3 * b.first) to (w0 * a.second + w1 * c1.second + w2 * c2.second + w3 * b.second)
        }
    }

    /** What an elbow is made of: the points after the stubs, the directions they leave in, and where the middle segment would sit. */
    private class Plan(val qa: P, val qb: P, val a1: P, val b1: P, val middleOnX: Boolean?, val base: Float)

    private fun plan(a: P, da: P?, b: P, db: P?, stubA: Boolean = true, stubB: Boolean = true): Plan {
        val qa = axis(da ?: unit(a, b))
        val qb = axis(db ?: unit(b, a))
        val stub = min(STUB, max(hypot(b.first - a.first, b.second - a.second) * 0.25f, 100f))
        val a1 = if (stubA) (a.first + qa.first * stub) to (a.second + qa.second * stub) else a
        val b1 = if (stubB) (b.first + qb.first * stub) to (b.second + qb.second * stub) else b
        val aH = qa.first != 0f
        val bH = qb.first != 0f
        return when {
            aH && bH -> Plan(qa, qb, a1, b1, true, if (qa.first == qb.first) (if (qa.first > 0) max(a1.first, b1.first) else min(a1.first, b1.first)) else (a1.first + b1.first) / 2)
            !aH && !bH -> Plan(qa, qb, a1, b1, false, if (qa.second == qb.second) (if (qa.second > 0) max(a1.second, b1.second) else min(a1.second, b1.second)) else (a1.second + b1.second) / 2)
            else -> Plan(qa, qb, a1, b1, null, 0f)
        }
    }

    private fun elbow(a: P, da: P?, b: P, db: P?, bend: Float, stubA: Boolean = true, stubB: Boolean = true): List<P> {
        val p = plan(a, da, b, db, stubA, stubB)
        val pts = ArrayList<P>()
        pts.add(a); pts.add(p.a1)
        when (p.middleOnX) {
            true -> { val x = p.base + bend; pts.add(x to p.a1.second); pts.add(x to p.b1.second) }
            false -> { val y = p.base + bend; pts.add(p.a1.first to y); pts.add(p.b1.first to y) }
            null -> pts.add(if (p.qa.first != 0f) p.b1.first to p.a1.second else p.a1.first to p.b1.second) // one turn
        }
        pts.add(p.b1); pts.add(b)
        return tidy(pts)
    }

    /** Without repeated points or points in the middle of a straight run. */
    private fun tidy(p: List<P>): List<P> {
        val out = ArrayList<P>()
        for (q in p) if (out.isEmpty() || hypot(q.first - out.last().first, q.second - out.last().second) > 0.5f) out.add(q)
        var i = 1
        while (i < out.size - 1) {
            val (ax, ay) = out[i - 1]; val (bx, by) = out[i]; val (cx, cy) = out[i + 1]
            if (abs((bx - ax) * (cy - by) - (by - ay) * (cx - bx)) < 1f) out.removeAt(i) else i++
        }
        return out
    }

    /** The middle of a route by length: where the bend handle sits. */
    fun middle(path: List<P>): P {
        if (path.size < 2) return path.firstOrNull() ?: (0f to 0f)
        val total = (1 until path.size).sumOf { hypot(path[it].first - path[it - 1].first, path[it].second - path[it - 1].second).toDouble() }.toFloat()
        var left = total / 2
        for (i in 1 until path.size) {
            val len = hypot(path[i].first - path[i - 1].first, path[i].second - path[i - 1].second)
            if (len >= left && len > 0) { val t = left / len; return (path[i - 1].first + (path[i].first - path[i - 1].first) * t) to (path[i - 1].second + (path[i].second - path[i - 1].second) * t) }
            left -= len
        }
        return path.last()
    }

    /** The [bend] that puts the middle of the route at the dragged point [to] (0 for a straight line, or an elbow with one turn). */
    fun bendFor(route: Route, a: P, da: P?, b: P, db: P?, to: P): Float = when (route) {
        Route.STRAIGHT -> 0f
        Route.CURVE -> {
            val dist = hypot(b.first - a.first, b.second - a.second)
            if (dist == 0f) 0f else {
                val flat = curve(a, da, b, db, 0f)[CURVE_STEPS / 2] // the middle with no bend
                val px = -(b.second - a.second) / dist; val py = (b.first - a.first) / dist
                (to.first - flat.first) * px + (to.second - flat.second) * py
            }
        }
        Route.ELBOW -> plan(a, da, b, db).let { p -> when (p.middleOnX) { true -> to.first - p.base; false -> to.second - p.base; null -> 0f } }
    }
}
