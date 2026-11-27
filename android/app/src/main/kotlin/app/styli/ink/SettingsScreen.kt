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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** What the Storage settings show and do (the folder picker lives with the app's start-up code). */
class StorageControls(val path: String, val isDefault: Boolean, val onChoose: () -> Unit, val onDefault: () -> Unit)

/** The groups on the Settings sidebar. New settings go in a group (or a new one); the list can keep growing. */
enum class SettingsSection(val label: String) {
    PENMANSHIP("Penmanship"), PAGES("Pages and scrolling"), SIDEBAR("Toolbar"), SELECTION("Selection"), TRASH("Trash"), STORAGE("Storage"), LATER("Coming later"),
}

/** The Settings tab: a sidebar of groups on the left, the chosen group's settings on the right. */
@Composable
fun SettingsScreen(s: AppSettings, lib: Library, ui: LibUi, storage: StorageControls) {
    SettingsHeading()
    Row(Modifier.fillMaxWidth().padding(top = StySize.space4), horizontalArrangement = Arrangement.spacedBy(StySize.space5)) {
        SettingsSidebar(s)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = StySize.space6), verticalArrangement = Arrangement.spacedBy(StySize.space3)) {
            SettingsContent(s, lib, ui, storage)
        }
    }
}

@Composable
private fun SettingsHeading() {
    val sty = LocalSty.current
    Text("Settings", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
    Text("Changes apply at once and are kept for next time.", Modifier.padding(top = StySize.space2), color = sty.inkMuted)
}

/** Only the sidebar reads the chosen section, so changing a setting does not rebuild the heading or the sidebar. */
@Composable
private fun SettingsSidebar(s: AppSettings) {
    val sty = LocalSty.current
    Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(StySize.space1)) {
        for (g in SettingsSection.entries) {
            val on = g == s.section
            key(g) {
                Text(
                    g.label, Modifier.fillMaxWidth().heightIn(min = StySize.touch).clip(StyShapes.Md)
                        .background(if (on) sty.honeyTint else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { s.section = g }.padding(horizontal = StySize.space3, vertical = StySize.space3),
                    color = if (on) sty.honeyText else sty.ink, style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Each group is its own composable: a change in one group cannot invalidate the others. */
@Composable
private fun SettingsContent(s: AppSettings, lib: Library, ui: LibUi, storage: StorageControls) {
    val sty = LocalSty.current
    Text(s.section.label, color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineSmall)
    when (s.section) {
        SettingsSection.PENMANSHIP -> PenmanshipSettings(s)
        SettingsSection.PAGES -> PageSettings(s)
        SettingsSection.SIDEBAR -> SidebarEditor(s)
        SettingsSection.SELECTION -> SelectionSettings(s)
        SettingsSection.TRASH -> TrashSettings(lib, ui)
        SettingsSection.STORAGE -> StorageSettings(storage)
        SettingsSection.LATER -> Note("Device, storage per notebook, pen and palm rejection, tool rail position and pairing.")
    }
}

@Composable
private fun PenmanshipSettings(s: AppSettings) {
    Note("A line or shape is drawn clean when you hold the pen still at the end of a stroke.")
    Chips("Lines", AppSettings.LEVELS, s.pLines) { s.pLines = it }
    Chips("Shapes", AppSettings.LEVELS, s.pShapes) { s.pShapes = it }
    Chips("Snap to level and upright", AppSettings.LEVELS, s.pLevel) { s.pLevel = it }
    Chips("Hold time", listOf("0.3 s", "0.5 s", "0.8 s", "1.2 s"), s.pHold) { s.pHold = it }
    PenButtonSettings(s)
    PensSettings(s)
}

@Composable
private fun PenButtonSettings(s: AppSettings) {
    val sty = LocalSty.current
    Text("Pen button", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = StySize.space3))
    Note("The button on the side of the pen. A click is a quick press while the pen hovers or touches; hold it to turn the pen into another tool for as long as you hold it.")
    Chips("When I click it", PenAction.entries.map { it.label }, s.penClick) { s.penClick = it }
    Chips("While I hold it, the pen is", PenAction.HOLD.map { it.label }, PenAction.HOLD.indexOf(PenAction.entries[s.penHold]).coerceAtLeast(0)) { s.penHold = PenAction.HOLD[it].ordinal }
}

@Composable
private fun PensSettings(s: AppSettings) {
    val sty = LocalSty.current
    Text("Pens", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = StySize.space3))
    Chips("When a note opens, its pens are", listOf("The ones I used last", "Its own (starting from the defaults)", "Always the defaults"), s.pens) { s.pens = it }
    Note("Defaults. Choosing one here also makes it the pen you used last.")
    Swatches("Pen colour", PEN_COLOURS, s.defPenColor) { s.defPenColor = it; s.penColor = it }
    Chips("Pen width", PEN_WIDTHS.map { it.first }, PEN_WIDTHS.indexOfFirst { it.second == s.defPenWidth }) { s.defPenWidth = PEN_WIDTHS[it].second; s.penWidth = PEN_WIDTHS[it].second }
    Swatches("Highlighter colour", HIGHLIGHT_COLOURS, s.defHighlighterColor) { s.defHighlighterColor = it; s.highlighterColor = it }
    Chips("Highlighter width", HIGHLIGHT_WIDTHS.map { it.first }, HIGHLIGHT_WIDTHS.indexOfFirst { it.second == s.defHighlighterWidth }) { s.defHighlighterWidth = HIGHLIGHT_WIDTHS[it].second; s.highlighterWidth = HIGHLIGHT_WIDTHS[it].second }
}

@Composable
private fun PageSettings(s: AppSettings) {
    Note("Important: seamless scrolling cannot add pages.", special = true)
    Chips("Scrolling over pages", listOf("Grid (pull past the end to add a page)", "Seamless"), if (s.gridScroll) 0 else 1, special = true) { s.gridScroll = it == 0 }
    Chips("Scroll glide", AppSettings.LEVELS, s.scroll) { s.scroll = it }
    Chips("When a note opens", listOf("Where I left off", "At the top, fitted to the page"), if (s.startFit) 1 else 0) { s.startFit = it == 1 }
}

@Composable
private fun SelectionSettings(s: AppSettings) =
    Chips("Outline", listOf("Dashed", "Solid"), if (s.solid) 1 else 0) { s.solid = it == 1 }

@Composable
private fun TrashSettings(lib: Library, ui: LibUi) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sty = LocalSty.current
    Note("Deleted files stay in the trash until you bring them back or delete them for good. Keeping them for a set time and then clearing them out is not built yet.")
    Text("${lib.trash.size} file(s) in the trash", color = sty.ink)
    // A destructive action: red, so it reads as special.
    Button(
        { ui.attempt(context, "empty the trash") { lib.emptyTrash() } }, enabled = lib.trash.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = sty.danger),
    ) { Text("Empty the trash for good") }
}

@Composable
private fun StorageSettings(storage: StorageControls) {
    val sty = LocalSty.current
    Note("Your notes are ordinary files in this folder, so any app or computer can open and copy them.")
    Text(storage.path, color = sty.ink, style = MaterialTheme.typography.titleMedium)
    Text(if (storage.isDefault) "This is the default folder." else "This is not the default folder.", color = sty.inkMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(StySize.space3)) {
        Button(storage.onChoose) { Text("Choose another folder") }
        OutlinedButton(storage.onDefault, enabled = !storage.isDefault) { Text("Use the default folder") }
    }
    Note("Changing folder asks first, and can move your notes across. The old folder is never deleted.")
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
            Column(Modifier.clip(StyShapes.Md).clickable { onPick(c) }.padding(StySize.space1), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(c))
                        .border(if (on) 3.dp else 1.dp, if (on) sty.ink else sty.borderStrong, androidx.compose.foundation.shape.CircleShape),
                )
                Text(name, color = if (on) sty.ink else sty.inkMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Where the tool rail sits and what is on it: reorder, remove, add, with dividers and spaces. */
@Composable
private fun SidebarEditor(s: AppSettings) {
    val sty = LocalSty.current
    Note("The toolbar beside the page: which side it sits on, and what is on it. Use the arrows to reorder; add dividers and spaces to group things.")
    Chips("Position", RailSide.entries.map { it.label }, s.rail) { s.rail = it }
    Text("On the rail", color = sty.inkMuted, style = MaterialTheme.typography.labelMedium)
    // Dividers and spaces can appear more than once, so count each occurrence to give every row its own key.
    val seen = HashMap<String, Int>()
    for ((i, entryKey) in s.sidebar.withIndex()) {
        val d = SidebarItems.def(entryKey) ?: continue
        val n = seen[entryKey] ?: 0
        seen[entryKey] = n + 1
        key("$entryKey#$n") {
            Row(Modifier.fillMaxWidth().clip(StyShapes.Md).background(sty.surface).border(1.dp, sty.border, StyShapes.Md), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.padding(start = StySize.space3).weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StySize.space3)) {
                    SvgIcon(d.icon, sty.ink, 24.dp)
                    Text(d.label, color = sty.ink)
                }
                IconBtn("Move up", Icons24.UP, enabled = i > 0, onClick = { val k = s.sidebar.removeAt(i); s.sidebar.add(i - 1, k) })
                IconBtn("Move down", Icons24.DOWN, enabled = i < s.sidebar.size - 1, onClick = { val k = s.sidebar.removeAt(i); s.sidebar.add(i + 1, k) })
                IconBtn("Remove from the rail", Icons24.CLOSE, onClick = { s.sidebar.removeAt(i) })
            }
        }
    }
    Text("Add", color = sty.inkMuted, style = MaterialTheme.typography.labelMedium)
    val free = SidebarItems.all.filter { it.key !in s.sidebar } + listOf(SidebarItems.sep, SidebarItems.gap)
    for (row in free.chunked(6)) Row(horizontalArrangement = Arrangement.spacedBy(StySize.space1)) {
        for (d in row) key("add-${d.key}") { OptionChip(d.label, d.icon, false) { s.sidebar.add(d.key) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
        TextButton(onClick = { s.sidebar.clear(); s.sidebar.addAll(SidebarItems.DEFAULT) }) { Text("Reset to the standard layout") }
    }
    Note("Planned: keep this layout the same on all your devices. For now it is kept on this device only.")
}
