package app.styli.ink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The settings the Settings tab changes and the canvas follows. In memory only for now (nothing is saved to disk
 * yet), so they go back to these defaults when the app closes. Levels run 0 off, 1 low, 2 medium, 3 high.
 */
class AppSettings {
    var pLines by mutableIntStateOf(2) // penmanship: how forgiving to be about lines, shapes, and level and upright
    var pShapes by mutableIntStateOf(2)
    var pLevel by mutableIntStateOf(2)
    var pHold by mutableIntStateOf(1) // hold time index into [HOLD_MS]
    var solid by mutableStateOf(false) // selection outline: solid, or dashed
    var gridScroll by mutableStateOf(true) // scrolling over pages: locked to the grid, or seamless
    var section by mutableStateOf(SettingsSection.PENMANSHIP) // which group the Settings tab shows (the Trash screen links to one)
    // The pens: the ones to start with (set in Settings), and as last used. [pens] says which a note opens with.
    var pens by mutableIntStateOf(0) // 0 carry on from the last note, 1 each note keeps its own, 2 always the defaults
    var defPenColor by mutableIntStateOf(0xFF000000.toInt())
    var defPenWidth by mutableIntStateOf(40)
    var defHighlighterColor by mutableIntStateOf(0x99FFEB3B.toInt())
    var defHighlighterWidth by mutableIntStateOf(400)
    val sidebar = androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(SidebarItems.DEFAULT) } // what is on the tool rail, in order
    val penSaved = androidx.compose.runtime.mutableStateListOf<Int>() // colours kept for the pen
    val highSaved = androidx.compose.runtime.mutableStateListOf<Int>() // colours kept for the highlighter
    val inkSlots = androidx.compose.runtime.mutableStateListOf(*RAIL_INKS.toTypedArray()) // the three colours on the rail
    var clipSamples by mutableStateOf(false) // the test clips were added once
    var rail by mutableIntStateOf(0) // where the tool rail sits: index into [RailSide]
    var startFit by mutableStateOf(false) // open every note at the top, fitted to the page, instead of where it was left
    var penColor by mutableIntStateOf(0xFF000000.toInt())
    var penWidth by mutableIntStateOf(40)
    var highlighterColor by mutableIntStateOf(0x99FFEB3B.toInt())
    var highlighterWidth by mutableIntStateOf(400)
    var scroll by mutableIntStateOf(2) // how far a scroll carries on, index into [GLIDE]

    /** The values to keep on disk (the group the screen shows is not one of them). */
    fun snapshot(): Map<String, Any?> = mapOf("pLines" to pLines, "pShapes" to pShapes, "pLevel" to pLevel, "pHold" to pHold, "solid" to solid, "gridScroll" to gridScroll, "scroll" to scroll,
        "pens" to pens, "defPenColor" to defPenColor, "defPenWidth" to defPenWidth, "defHighlighterColor" to defHighlighterColor, "defHighlighterWidth" to defHighlighterWidth, "startFit" to startFit, "rail" to rail, "inkSlots" to inkSlots.map { it.toString() }, "clipSamples" to clipSamples, "sidebar" to sidebar.toList(), "penSaved" to penSaved.map { it.toString() }, "highSaved" to highSaved.map { it.toString() },
        "penColor" to penColor, "penWidth" to penWidth, "highlighterColor" to highlighterColor, "highlighterWidth" to highlighterWidth)

    /** Takes back what [snapshot] made; a missing or out-of-range value keeps the default. */
    fun restore(m: Map<String, Any?>) {
        fun level(k: String, size: Int = 4) = (m[k] as? Long)?.toInt()?.takeIf { it in 0 until size }
        level("pLines")?.let { pLines = it }; level("pShapes")?.let { pShapes = it }; level("pLevel")?.let { pLevel = it }
        level("pHold")?.let { pHold = it }; level("scroll")?.let { scroll = it }
        level("pens", 3)?.let { pens = it }; level("rail")?.let { rail = it }
        (m["inkSlots"] as? List<*>)?.mapNotNull { (it as? String)?.toIntOrNull() }?.takeIf { it.size == 3 }?.let { for (i in 0..2) inkSlots[i] = it[i] }
        (m["clipSamples"] as? Boolean)?.let { clipSamples = it }
        (m["sidebar"] as? List<*>)?.filterIsInstance<String>()?.filter { SidebarItems.def(it) != null }?.let { sidebar.clear(); sidebar.addAll(it) }
        (m["penSaved"] as? List<*>)?.mapNotNull { (it as? String)?.toIntOrNull() }?.let { penSaved.clear(); penSaved.addAll(it.take(MAX_SAVED)) }
        (m["highSaved"] as? List<*>)?.mapNotNull { (it as? String)?.toIntOrNull() }?.let { highSaved.clear(); highSaved.addAll(it.take(MAX_SAVED)) }
        (m["startFit"] as? Boolean)?.let { startFit = it }
        (m["defPenColor"] as? Long)?.let { defPenColor = it.toInt() }; (m["defHighlighterColor"] as? Long)?.let { defHighlighterColor = it.toInt() }
        (m["defPenWidth"] as? Long)?.toInt()?.takeIf { it in 1..4000 }?.let { defPenWidth = it }
        (m["defHighlighterWidth"] as? Long)?.toInt()?.takeIf { it in 1..4000 }?.let { defHighlighterWidth = it }
        (m["penColor"] as? Long)?.let { penColor = it.toInt() }; (m["highlighterColor"] as? Long)?.let { highlighterColor = it.toInt() }
        (m["penWidth"] as? Long)?.toInt()?.takeIf { it in 1..4000 }?.let { penWidth = it }
        (m["highlighterWidth"] as? Long)?.toInt()?.takeIf { it in 1..4000 }?.let { highlighterWidth = it }
        (m["solid"] as? Boolean)?.let { solid = it }; (m["gridScroll"] as? Boolean)?.let { gridScroll = it }
    }

    /** Gives a new canvas these settings (it starts with its own defaults). */
    fun applyTo(c: InkCanvasView, file: NoteFile? = null) {
        c.penmanship = penmanship()
        c.scrollMode = if (gridScroll) ScrollMode.GRID else ScrollMode.SEAMLESS
        c.scrollIntensity = GLIDE[scroll]
        c.solidOutline = solid
        // Which pens a note opens with: the defaults, its own (if it has any yet), or the ones last used.
        val own = if (pens == 1) file?.view else null
        fun mine(k: String) = (own?.get(k) as? Long)?.toInt()
        when {
            pens == 2 || (pens == 1 && own == null) -> { c.penColor = defPenColor; c.penWidth = defPenWidth; c.highlighterColor = defHighlighterColor; c.highlighterWidth = defHighlighterWidth }
            pens == 1 -> {
                c.penColor = mine("penColor") ?: defPenColor; c.penWidth = mine("penWidth") ?: defPenWidth
                c.highlighterColor = mine("highlighterColor") ?: defHighlighterColor; c.highlighterWidth = mine("highlighterWidth") ?: defHighlighterWidth
            }
            else -> { c.penColor = penColor; c.penWidth = penWidth; c.highlighterColor = highlighterColor; c.highlighterWidth = highlighterWidth }
        }
        // And where it was left, unless Settings say to start at the top (fitted, which is how a new view begins).
        if (!startFit) c.startView = NoteView.view(file?.view)
        NoteView.tool(file?.view)?.let { c.tool = it }
    }

    /** Takes the pens as the canvas has them now (they are changed from the tool menus), so they are kept. */
    fun captureFrom(c: InkCanvasView) {
        penColor = c.penColor; penWidth = c.penWidth; highlighterColor = c.highlighterColor; highlighterWidth = c.highlighterWidth
    }

    fun penmanship() = ShapeGeometry.Settings(STRENGTH[pLines], STRENGTH[pShapes], STRENGTH[pLevel], HOLD_MS[pHold])

    companion object {
        val STRENGTH = listOf(0f, 0.25f, 1f, 2.5f)
        val HOLD_MS = listOf(300L, 500L, 800L, 1200L)
        val GLIDE = listOf(0.0, 0.5, 1.0, 2.0)
        val LEVELS = listOf("Off", "Low", "Medium", "High")
    }
}
