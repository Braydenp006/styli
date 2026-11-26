package app.styli.ink

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Finds the closed area round a point, from the pen strokes that bound it (the paint bucket). The strokes are
 * drawn, a little thicker, onto a coarse grid; whatever the point can reach without crossing one is the area. If it
 * can reach the edge of the grid the area is not closed. The area is handed back as outlines (see [Fill]). Pure
 * maths, no Android.
 */
object RegionFill {
    /** A stroke's path and width, in page units. */
    class Path(val pts: List<Pair<Float, Float>>, val width: Float)

    /** [rings] are the outside and any holes; [area] is the area reached, in square page units. */
    class Region(val rings: List<List<Pair<Float, Float>>>, val area: Double)

    /** The finest grid cell (0.25 mm) and the most cells along a side. */
    const val MIN_CELL = 25f
    const val MAX_CELLS = 1200

    /**
     * The area round ([tapX], [tapY]) inside [bounds] bounded by [paths], or null if the point is on a line, or the
     * area is open (reaches the edge of [bounds]). Gaps up to [gap] in a line are bridged.
     */
    fun find(paths: List<Path>, tapX: Float, tapY: Float, bounds: Box, gap: Float): Region? {
        val w = bounds.r - bounds.l
        val h = bounds.b - bounds.t
        if (w <= 0 || h <= 0 || tapX < bounds.l || tapX >= bounds.r || tapY < bounds.t || tapY >= bounds.b) return null
        val cell = maxOf(MIN_CELL, (maxOf(w, h) / MAX_CELLS).toFloat())
        val bx = bounds.l.toFloat()
        val by = bounds.t.toFloat()
        val nx = ceil(w / cell).toInt()
        val ny = ceil(h / cell).toInt()
        val blocked = BooleanArray(nx * ny) // within the gap allowance of a line: the paint may not cross
        val ink = BooleanArray(nx * ny) // on the line itself (made at least a cell thick so it has no holes)
        var widest = 0f
        for (p in paths) {
            widest = maxOf(widest, p.width)
            block(blocked, nx, ny, bx, by, cell, p.pts, p.width / 2 + gap / 2)
            block(ink, nx, ny, bx, by, cell, p.pts, maxOf(p.width / 2, cell * 0.71f))
        }
        val ti = floor((tapX - bx) / cell).toInt()
        val tj = floor((tapY - by) / cell).toInt()
        if (blocked[tj * nx + ti]) return null

        // Flood from the tap.
        val filled = BooleanArray(nx * ny)
        val queue = IntArray(nx * ny)
        var head = 0
        var tail = 0
        queue[tail++] = tj * nx + ti
        filled[tj * nx + ti] = true
        while (head < tail) {
            val c = queue[head++]
            val i = c % nx
            val j = c / nx
            if (i == 0 || j == 0 || i == nx - 1 || j == ny - 1) return null // reached the edge: not closed
            for (n in intArrayOf(c - 1, c + 1, c - nx, c + nx)) {
                if (!blocked[n] && !filled[n]) { filled[n] = true; queue[tail++] = n }
            }
        }
        val area = tail.toDouble() * cell * cell

        // The flood stops a gap's allowance short of every line. Fill the space left up to the ink itself, a few cells at
        // most (so paint does not run out through a gap and along the far side), so the fill goes right under the line
        // and nothing shows beside it, and nothing spills over it.
        val mask = filled
        var front = IntArray(1024)
        var fronts = 0
        for (c in 0 until nx * ny) {
            if (!mask[c]) continue
            val i = c % nx
            val j = c / nx
            if (i == 0 || j == 0 || i == nx - 1 || j == ny - 1) continue
            if (!mask[c - 1] || !mask[c + 1] || !mask[c - nx] || !mask[c + nx]) {
                if (fronts == front.size) front = front.copyOf(fronts * 2)
                front[fronts++] = c
            }
        }
        repeat(ceil((gap / 2 + widest / 2) / cell).toInt().coerceIn(1, 8) + 1) {
            var next = IntArray(maxOf(16, fronts))
            var nexts = 0
            for (k in 0 until fronts) {
                val c = front[k]
                val i = c % nx
                val j = c / nx
                for (dj in -1..1) for (di in -1..1) {
                    if (i + di !in 0 until nx || j + dj !in 0 until ny) continue
                    val n = (j + dj) * nx + i + di
                    if (mask[n] || ink[n] || !blocked[n]) continue
                    mask[n] = true
                    if (nexts == next.size) next = next.copyOf(nexts * 2)
                    next[nexts++] = n
                }
            }
            front = next
            fronts = nexts
        }
        // The outlines are in grid cells here; three quarters of a cell is how far a corner may be pulled in to straighten the staircase.
        val rings = trace(mask, nx, ny).map { r -> simplify(r, 0.75f).map { (x, y) -> bx + x * cell to by + y * cell } }
            .filter { it.size >= 3 }.sortedByDescending { abs(areaOf(it)) }
        return if (rings.isEmpty()) null else Region(rings, area)
    }

    /** Marks the grid cells whose middle is within [r] of the path. */
    private fun block(blocked: BooleanArray, nx: Int, ny: Int, bx: Float, by: Float, cell: Float, pts: List<Pair<Float, Float>>, r: Float) {
        for (k in pts.indices) {
            val (ax, ay) = pts[k]
            val (cx, cy) = pts[if (k + 1 < pts.size) k + 1 else k]
            val i0 = floor((minOf(ax, cx) - r - bx) / cell).toInt().coerceAtLeast(0)
            val i1 = floor((maxOf(ax, cx) + r - bx) / cell).toInt().coerceAtMost(nx - 1)
            val j0 = floor((minOf(ay, cy) - r - by) / cell).toInt().coerceAtLeast(0)
            val j1 = floor((maxOf(ay, cy) + r - by) / cell).toInt().coerceAtMost(ny - 1)
            for (j in j0..j1) for (i in i0..i1) {
                if (distToSegment(bx + (i + 0.5f) * cell, by + (j + 0.5f) * cell, ax, ay, cx, cy) <= r) blocked[j * nx + i] = true
            }
        }
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** The outlines of the filled cells, as lists of grid corners (x, y), the filled side on the right of the way round. */
    private fun trace(mask: BooleanArray, nx: Int, ny: Int): List<List<Pair<Float, Float>>> {
        fun on(i: Int, j: Int) = i in 0 until nx && j in 0 until ny && mask[j * nx + i]
        val stride = nx + 1
        val out = HashMap<Int, ArrayList<Int>>() // corner -> corners the edges leaving it go to
        fun edge(x0: Int, y0: Int, x1: Int, y1: Int) { out.getOrPut(y0 * stride + x0) { ArrayList(2) }.add(y1 * stride + x1) }
        for (j in 0 until ny) for (i in 0 until nx) {
            if (!mask[j * nx + i]) continue
            if (!on(i, j - 1)) edge(i, j, i + 1, j)
            if (!on(i + 1, j)) edge(i + 1, j, i + 1, j + 1)
            if (!on(i, j + 1)) edge(i + 1, j + 1, i, j + 1)
            if (!on(i - 1, j)) edge(i, j + 1, i, j)
        }
        val rings = ArrayList<List<Pair<Float, Float>>>()
        while (out.isNotEmpty()) {
            val start = out.keys.first()
            val ring = ArrayList<Pair<Float, Float>>()
            var cur = start
            do {
                ring.add((cur % stride).toFloat() to (cur / stride).toFloat())
                val list = out[cur]!!
                val next = list.removeAt(list.size - 1)
                if (list.isEmpty()) out.remove(cur)
                cur = next
            } while (cur != start)
            rings.add(ring)
        }
        return rings
    }

    /** Fewer points for a closed outline: a point stays only if it is more than [tol] from the line its neighbours make. */
    private fun simplify(ring: List<Pair<Float, Float>>, tol: Float): List<Pair<Float, Float>> {
        // Splitting a closed outline at its two farthest-apart corners gives two open lines to simplify.
        if (ring.size < 4) return ring
        var far = 1
        var best = -1f
        for (k in 1 until ring.size) {
            val d = hypot(ring[k].first - ring[0].first, ring[k].second - ring[0].second)
            if (d > best) { best = d; far = k }
        }
        val a = douglasPeucker(ring.subList(0, far + 1), tol)
        val b = douglasPeucker(ring.subList(far, ring.size) + ring[0], tol)
        return a.dropLast(1) + b.dropLast(1)
    }

    private fun douglasPeucker(p: List<Pair<Float, Float>>, tol: Float): List<Pair<Float, Float>> {
        if (p.size < 3) return p
        var idx = 0
        var worst = -1f
        for (k in 1 until p.size - 1) {
            val d = distToSegment(p[k].first, p[k].second, p[0].first, p[0].second, p.last().first, p.last().second)
            if (d > worst) { worst = d; idx = k }
        }
        if (worst <= tol) return listOf(p.first(), p.last())
        return douglasPeucker(p.subList(0, idx + 1), tol).dropLast(1) + douglasPeucker(p.subList(idx, p.size), tol)
    }

    /** The signed area of a ring (shoelace). */
    fun areaOf(ring: List<Pair<Float, Float>>): Double {
        var s = 0.0
        for (k in ring.indices) {
            val (x0, y0) = ring[k]
            val (x1, y1) = ring[(k + 1) % ring.size]
            s += x0.toDouble() * y1 - x1.toDouble() * y0
        }
        return s / 2
    }
}
