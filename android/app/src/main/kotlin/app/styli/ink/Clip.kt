package app.styli.ink

/**
 * A saved group of things from a page, to put down again later. Everything is kept as the page's own kinds
 * (strokes, text boxes, tables, shapes, connectors, fills), moved so the group's top-left corner is (0, 0).
 * [order] is the layers, bottom first. Never changes once made. In memory only for now.
 */
class Clip(
    val name: String,
    val width: Float,
    val height: Float,
    val items: List<Item>,
    val texts: List<TextBox>,
    val tables: List<Table>,
    val shapes: List<Shape>,
    val connectors: List<Connector>,
    val fills: List<Fill>,
    val order: List<String>,
) {
    val isEmpty get() = order.isEmpty()

    fun named(n: String) = Clip(n, width, height, items, texts, tables, shapes, connectors, fills, order)

    /**
     * The same group moved by (dx, dy), every ID changed by [rename] (the same old ID must give the same new one,
     * so a connector still finds its shape). A connector's end on a shape in the group follows that shape.
     */
    fun moved(dx: Float, dy: Float, rename: (String) -> String): Clip {
        val m = Affine.translate(dx.toDouble(), dy.toDouble())
        fun end(e: End) = End(e.shape?.let(rename), e.anchor, m.x(e.x, e.y), m.y(e.x, e.y), e.fx, e.fy)
        return Clip(
            name, width, height,
            items.map { Item(rename(it.id), it.tool, it.color, it.width, it.pts.map(m::apply)) },
            texts.map { TextBox(rename(it.id), it.x + dx, it.y + dy, it.width, it.fontSize, it.text, it.color, it.highlight, it.angle) },
            tables.map { Table(rename(it.id), it.x + dx, it.y + dy, it.colWidths, it.rowHeights, it.lineWidth, it.color, it.fill, it.cellFills) },
            shapes.map { s -> s.moved(m).let { Shape(rename(s.id), it.kind, it.x, it.y, it.width, it.height, it.angle, it.color, it.lineWidth, it.dashed, it.fill, it.corner, it.vertices, it.text, it.textColor, it.fontSize) } },
            connectors.map { Connector(rename(it.id), end(it.from), end(it.to), it.fromHead, it.toHead, it.color, it.lineWidth, it.dashed, it.route, it.bend, it.via.map { (x, y) -> m.x(x, y) to m.y(x, y) }) },
            fills.map { f -> f.map(m).let { Fill(rename(f.id), it.color, it.rings) } },
            order.map(rename),
        )
    }

    companion object {
        /**
         * The things [ids] on [model] as a clip. [box] gives each one's box on the page (the clip's corner is the top
         * left of them all); nothing (null) if none of them is on the page. A connector end that sits on a shape
         * that is not in [ids] becomes a free end where it was, because the shape would not be there to hold it.
         */
        fun of(name: String, model: PageModel, ids: Set<String>, box: (String) -> Box?): Clip? {
            val boxes = model.order.filter { it in ids }.mapNotNull(box)
            if (boxes.isEmpty()) return null
            val l = boxes.minOf { it.l }; val t = boxes.minOf { it.t }
            val shapeOf = model::shapeOf
            val conns = model.connectors.filter { it.id in ids }.map { c ->
                val (a, b) = c.resolve(shapeOf)
                c.copy(from = c.from.at(a).let { if (it.shape in ids) it else End(null, 0, a.first, a.second) },
                    to = c.to.at(b).let { if (it.shape in ids) it else End(null, 0, b.first, b.second) })
            }
            return Clip(
                name, (boxes.maxOf { it.r } - l).toFloat(), (boxes.maxOf { it.b } - t).toFloat(),
                model.items.filter { it.id in ids }, model.texts.filter { it.id in ids }, model.tables.filter { it.id in ids },
                model.shapes.filter { it.id in ids }, conns, model.fills.filter { it.id in ids },
                model.order.filter { it in ids },
            ).moved(-l.toFloat(), -t.toFloat()) { it }
        }
    }
}

/** The clips the user has saved, in the order saved. In memory only for now. */
class ClipShelf {
    var clips: List<Clip> = emptyList()
        private set

    /** Saves [clip] (if it has no name, "Clip 1", "Clip 2" and so on). */
    fun add(clip: Clip): Clip {
        val c = if (clip.name.isBlank()) clip.named("Clip ${clips.size + 1}") else clip
        clips = clips + c
        return c
    }

    fun rename(clip: Clip, name: String) { clips = clips.map { if (it === clip && name.isNotBlank()) it.named(name.trim()) else it } }

    fun remove(clip: Clip) { clips = clips.filter { it !== clip } }
}
