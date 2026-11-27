package app.styli.ink

/** The things that can go on the tool rail, in the order the Settings list offers them. "sep" (a line) and "gap" (a space) can be added any number of times. */
object SidebarItems {
    class Def(val key: String, val label: String, val icon: String)

    val all = listOf(
        Def("pen", "Pen", Icons24.PEN), Def("highlighter", "Highlighter", Icons24.HIGHLIGHTER), Def("eraser", "Eraser", Icons24.ERASER),
        Def("line", "Line", Icons24.LINE), Def("lasso", "Lasso", Icons24.LASSO), Def("text", "Text box", Icons24.TEXT),
        Def("shape", "Shape", Icons24.SHAPE), Def("table", "Table", Icons24.TABLE), Def("connector", "Connector", Icons24.CONNECTOR),
        Def("bucket", "Paint bucket", Icons24.BUCKET), Def("chart", "Chart", Icons24.CHART), Def("laser", "Laser pointer", Icons24.LASER),
        Def("image", "Add image", Icons24.IMAGE), Def("more", "More tools", Icons24.SHAPES),
        Def("ink0", "Black ink", Icons24.PEN), Def("ink1", "Blue ink", Icons24.PEN), Def("ink2", "Red ink", Icons24.PEN),
        Def("undo", "Undo", Icons24.UNDO), Def("redo", "Redo", Icons24.REDO),
        Def("clip", "Clipboard", Icons24.CLIPBOARD), Def("ruler", "Ruler", Icons24.RULER), Def("assist", "Pen assist", Icons24.ASSIST),
    )
    val sep = Def("sep", "Divider line", Icons24.DIVIDER)
    val gap = Def("gap", "Space", Icons24.SPACER)

    fun def(key: String): Def? = all.firstOrNull { it.key == key } ?: listOf(sep, gap).firstOrNull { it.key == key }

    val DEFAULT = listOf(
        "pen", "highlighter", "eraser", "line", "lasso", "text", "more", "sep", "ink0", "ink1", "ink2", "sep", "undo", "redo", "sep", "clip", "ruler", "assist",
    )
}
