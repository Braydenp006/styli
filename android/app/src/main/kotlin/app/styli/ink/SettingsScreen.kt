package app.styli.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** The groups on the Settings sidebar. New settings go in a group (or a new one); the list can keep growing. */
enum class SettingsSection(val label: String) {
    PENMANSHIP("Penmanship"), PAGES("Pages and scrolling"), SELECTION("Selection"), TRASH("Trash"), LATER("Coming later"),
}

/** The Settings tab: a sidebar of groups on the left, the chosen group's settings on the right. */
@Composable
fun SettingsScreen(s: AppSettings, lib: Library, ui: LibUi) {
    val sty = LocalSty.current
    Text("Settings", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
    Text("Changes apply at once and are kept for next time.", Modifier.padding(top = StySize.space2), color = sty.inkMuted)
    Row(Modifier.fillMaxWidth().padding(top = StySize.space4), horizontalArrangement = Arrangement.spacedBy(StySize.space5)) {
        Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(StySize.space1)) {
            for (g in SettingsSection.entries) {
                val on = g == s.section
                Text(
                    g.label, Modifier.fillMaxWidth().heightIn(min = StySize.touch).clip(SquircleShape(StySize.radiusMd))
                        .background(if (on) sty.honeyTint else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { s.section = g }.padding(horizontal = StySize.space3, vertical = StySize.space3),
                    color = if (on) sty.honeyText else sty.ink, style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = StySize.space6), verticalArrangement = Arrangement.spacedBy(StySize.space3)) {
            Text(s.section.label, color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineSmall)
            when (s.section) {
                SettingsSection.PENMANSHIP -> {
                    Note("A line or shape is drawn clean when you hold the pen still at the end of a stroke.")
                    Chips("Lines", AppSettings.LEVELS, s.pLines) { s.pLines = it }
                    Chips("Shapes", AppSettings.LEVELS, s.pShapes) { s.pShapes = it }
                    Chips("Snap to level and upright", AppSettings.LEVELS, s.pLevel) { s.pLevel = it }
                    Chips("Hold time", listOf("0.3 s", "0.5 s", "0.8 s", "1.2 s"), s.pHold) { s.pHold = it }
                    Text("Pens", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = StySize.space3))
                    Chips("When a note opens, its pens are", listOf("The ones I used last", "Its own (starting from the defaults)", "Always the defaults"), s.pens) { s.pens = it }
                    Note("Defaults. Choosing one here also makes it the pen you used last.")
                    Swatches("Pen colour", PEN_COLOURS, s.defPenColor) { s.defPenColor = it; s.penColor = it }
                    Chips("Pen width", PEN_WIDTHS.map { it.first }, PEN_WIDTHS.indexOfFirst { it.second == s.defPenWidth }) { s.defPenWidth = PEN_WIDTHS[it].second; s.penWidth = PEN_WIDTHS[it].second }
                    Swatches("Highlighter colour", HIGHLIGHT_COLOURS, s.defHighlighterColor) { s.defHighlighterColor = it; s.highlighterColor = it }
                    Chips("Highlighter width", HIGHLIGHT_WIDTHS.map { it.first }, HIGHLIGHT_WIDTHS.indexOfFirst { it.second == s.defHighlighterWidth }) { s.defHighlighterWidth = HIGHLIGHT_WIDTHS[it].second; s.highlighterWidth = HIGHLIGHT_WIDTHS[it].second }
                }
                SettingsSection.PAGES -> {
                    Note("Important: seamless scrolling cannot add pages.", special = true)
                    Chips("Scrolling over pages", listOf("Grid (pull past the end to add a page)", "Seamless"), if (s.gridScroll) 0 else 1, special = true) { s.gridScroll = it == 0 }
                    Chips("Scroll glide", AppSettings.LEVELS, s.scroll) { s.scroll = it }
                    Chips("When a note opens", listOf("Where I left off", "At the top, fitted to the page"), if (s.startFit) 1 else 0) { s.startFit = it == 1 }
                }
                SettingsSection.SELECTION -> Chips("Outline", listOf("Dashed", "Solid"), if (s.solid) 1 else 0) { s.solid = it == 1 }
                SettingsSection.TRASH -> {
                    Note("Deleted files stay in the trash until you bring them back or delete them for good. Keeping them for a set time and then clearing them out is not built yet.")
                    Text("${lib.trash.size} file(s) in the trash", color = sty.ink)
                    // A destructive action: red, so it reads as special.
                    Button(
                        { lib.emptyTrash(); ui.changed() }, enabled = lib.trash.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = sty.danger),
                    ) { Text("Empty the trash for good") }
                }
                SettingsSection.LATER -> Note("Device, storage per notebook, pen and palm rejection, tool rail position and pairing.")
            }
        }
    }
}

/** A line of explanation. [special] makes it honey, like the important settings it sits above. */
@Composable
private fun Note(text: String, special: Boolean = false) {
    val sty = LocalSty.current
    Text(text, color = if (special) sty.honeyText else sty.inkMuted, style = MaterialTheme.typography.bodyMedium)
}

private val PEN_COLOURS = listOf("Black" to 0xFF000000.toInt(), "Blue" to 0xFF1B3A8A.toInt(), "Red" to 0xFFC0392B.toInt(), "Green" to 0xFF2E7D32.toInt(), "Orange" to 0xFFD9772B.toInt(), "Purple" to 0xFF6A3FA0.toInt())
private val PEN_WIDTHS = listOf("Fine 0.25 mm" to 25, "Medium 0.4 mm" to 40, "Bold 0.6 mm" to 60, "Heavy 1 mm" to 100)
private val HIGHLIGHT_COLOURS = listOf("Yellow" to 0x99FFEB3B.toInt(), "Green" to 0x9969F0AE.toInt(), "Pink" to 0x99FF80AB.toInt(), "Blue" to 0x9980D8FF.toInt(), "Orange" to 0x99FFB74D.toInt())
private val HIGHLIGHT_WIDTHS = listOf("3 mm" to 300, "4 mm" to 400, "6 mm" to 600)

/** A row of colour dots to pick from; the chosen one has a thick ring. */
@Composable
private fun Swatches(label: String, colours: List<Pair<String, Int>>, chosen: Int, onPick: (Int) -> Unit) {
    val sty = LocalSty.current
    Text(label, color = sty.inkMuted, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
        for ((name, c) in colours) {
            val on = c == chosen
            Column(Modifier.clip(SquircleShape(StySize.radiusMd)).clickable { onPick(c) }.padding(StySize.space1), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(c))
                        .border(if (on) 3.dp else 1.dp, if (on) sty.ink else sty.borderStrong, androidx.compose.foundation.shape.CircleShape),
                )
                Text(name, color = if (on) sty.ink else sty.inkMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
