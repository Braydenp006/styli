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
    var scroll by mutableIntStateOf(2) // how far a scroll carries on, index into [GLIDE]

    /** Gives a new canvas these settings (it starts with its own defaults). */
    fun applyTo(c: InkCanvasView) {
        c.penmanship = penmanship()
        c.scrollMode = if (gridScroll) ScrollMode.GRID else ScrollMode.SEAMLESS
        c.scrollIntensity = GLIDE[scroll]
        c.solidOutline = solid
    }

    fun penmanship() = ShapeGeometry.Settings(STRENGTH[pLines], STRENGTH[pShapes], STRENGTH[pLevel], HOLD_MS[pHold])

    companion object {
        val STRENGTH = listOf(0f, 0.25f, 1f, 2.5f)
        val HOLD_MS = listOf(300L, 500L, 800L, 1200L)
        val GLIDE = listOf(0.0, 0.5, 1.0, 2.0)
        val LEVELS = listOf("Off", "Low", "Medium", "High")
    }
}
