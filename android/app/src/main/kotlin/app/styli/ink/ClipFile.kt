package app.styli.ink

/** A saved clip as an ink log (the same format as a page, so one writer and reader do both), and back. */
object ClipFile {
    /** The log of [clip]: every object as its own event, then a layer move for each so the order comes back exactly. */
    fun encode(clip: Clip, writer: InkLogWriter): ByteArray {
        val events = ArrayList<Ev>()
        clip.items.forEach { events.add(Ev.Added(it)) }
        clip.texts.forEach { events.add(Ev.TextSet(it)) }
        clip.tables.forEach { events.add(Ev.TableSet(it)) }
        clip.shapes.forEach { events.add(Ev.ShapeSet(it)) }
        clip.connectors.forEach { events.add(Ev.ConnectorSet(it)) }
        clip.fills.forEach { events.add(Ev.FillSet(it)) }
        clip.pictures.forEach { events.add(Ev.PictureSet(it)) }
        clip.charts.forEach { events.add(Ev.ChartSet(it)) }
        clip.order.forEachIndexed { i, id -> events.add(Ev.ZMoved(id, if (i == 0) null else clip.order[i - 1])) }
        return writer.header() + writer.encode(events)
    }

    fun decode(name: String, width: Float, height: Float, bytes: ByteArray): Clip {
        val p = InkLogReader.read(bytes).page
        return Clip(name, width, height, p.items, p.texts, p.tables, p.shapes, p.connectors, p.fills, p.order, p.pictures, p.charts)
    }
}
