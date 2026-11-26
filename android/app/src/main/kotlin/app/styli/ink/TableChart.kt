package app.styli.ink

/** Numbers and charts from the text typed in a table's cells, and text to and from a spreadsheet. */
object TableChart {
    /**
     * A cell's text as a number, or null if it is not one. Spaces, currency signs and a percent sign are ignored, brackets
     * mean negative ("(12)"), and both "1,234.5" and "1.234,5" (and a lone "12,5") are understood.
     */
    fun number(text: String): Double? {
        var s = text.trim().replace('\u2212', '-').filter { !it.isWhitespace() && it !in "\u00A3$\u20AC\u00A5%" }
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        val comma = s.lastIndexOf(','); val dot = s.lastIndexOf('.')
        s = when {
            comma >= 0 && dot >= 0 -> if (comma > dot) s.replace(".", "").replace(',', '.') else s.replace(",", "") // the later mark is the decimal point
            comma >= 0 -> if (Regex("^[+-]?\\d{1,3}(,\\d{3})+$").matches(s)) s.replace(",", "") else s.replace(',', '.') // 1,234 is a thousand and more, 12,5 twelve and a half
            else -> s
        }
        val v = s.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return if (negative) -v else v
    }

    /**
     * Rows of cells from text copied out of a spreadsheet or a file: tabs, or else semicolons or commas (whichever the first
     * line has more of) between cells, a new line between rows, and "quoted" cells that may hold the separator or a new line.
     * A blank last line is dropped; short rows stay short.
     */
    fun parseDelimited(text: String): List<List<String>> {
        val src = text.replace("\r\n", "\n").replace('\r', '\n')
        if (src.isEmpty()) return emptyList()
        // the separator is the one most used outside quotes on the first row
        var tabs = 0; var semis = 0; var commas = 0; var inQ = false
        for (ch in src) { if (ch == '"') inQ = !inQ else if (!inQ) { if (ch == '\n') break; if (ch == '\t') tabs++ else if (ch == ';') semis++ else if (ch == ',') commas++ } }
        val sep = when { tabs > 0 -> '\t'; semis > commas -> ';'; commas > 0 -> ','; else -> '\u0000' }
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
        var i = 0; var quoted = false; var wasQuoted = false
        fun endCell() { row.add(if (wasQuoted) cell.toString() else cell.toString().trim()); cell.setLength(0); wasQuoted = false }
        while (i < src.length) {
            val ch = src[i]
            if (quoted) {
                if (ch == '"') { if (src.getOrNull(i + 1) == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(ch)
            } else if (ch == '"' && cell.isBlank()) { quoted = true; wasQuoted = true; cell.setLength(0) }
            else if (ch == sep) endCell()
            else if (ch == '\n') { endCell(); rows.add(row); row = ArrayList() }
            else cell.append(ch)
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty() || wasQuoted) { endCell(); rows.add(row) }
        while (rows.isNotEmpty() && rows.last().all { it.isEmpty() }) rows.removeAt(rows.size - 1)
        return rows
    }

    /** The table's typed text as tab-separated rows, to paste into a spreadsheet. */
    fun toText(t: Table): String = t.textGrid().joinToString("\n") { r -> r.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } }

    /**
     * [base] with its categories and series read from the block of [t] that [src] names, the first row and column being the
     * labels: with `seriesInRows` false each column after the first is a series (its header the name, the first column the
     * categories), otherwise each row after the first. Cells that are not numbers become blanks. Each series keeps the look it
     * had (colour, kind, second axis, trendline) by its number. A block under 2 by 2 once cut to the table leaves [base] as it is.
     */
    fun fill(base: Chart, t: Table, src: ChartSource): Chart {
        val r0 = src.row.coerceIn(0, t.rows - 1); val c0 = src.col.coerceIn(0, t.cols - 1)
        val rows = minOf(src.rows, t.rows - r0); val cols = minOf(src.cols, t.cols - c0)
        if (rows < 2 || cols < 2) return base
        fun text(r: Int, c: Int) = t.cellTextAt(r0 + r, c0 + c).trim()
        val cats: List<String>; val names: List<String>; val grid: List<List<Double?>> // grid[series][category]
        if (!src.seriesInRows) {
            cats = (1 until rows).map { text(it, 0) }
            names = (1 until cols).map { text(0, it) }
            grid = (1 until cols).map { c -> (1 until rows).map { r -> number(text(r, c)) } }
        } else {
            cats = (1 until cols).map { text(0, it) }
            names = (1 until rows).map { text(it, 0) }
            grid = (1 until rows).map { r -> (1 until cols).map { c -> number(text(r, c)) } }
        }
        val series = grid.mapIndexed { i, vals ->
            val old = base.series.getOrNull(i)
            ChartSeries(names[i].ifEmpty { "Series ${i + 1}" }, old?.color ?: Chart.colourFor(i), vals.map { v -> if (v == null) 0L else Math.round(v * 1000) },
                vals.indices.filter { vals[it] == null }.toSet(), old?.kind ?: ChartKind.BAR, old?.secondary ?: false, old?.trendline ?: false)
        }
        return base.copy(categories = cats, series = series, source = src.copy(row = r0, col = c0, rows = rows, cols = cols))
    }
}
