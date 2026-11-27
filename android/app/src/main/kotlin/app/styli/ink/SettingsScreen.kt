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
    Text("Changes apply at once. They are not saved yet, so they reset when the app closes.", Modifier.padding(top = StySize.space2), color = sty.inkMuted)
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
                }
                SettingsSection.PAGES -> {
                    Note("Important: seamless scrolling cannot add pages.", special = true)
                    Chips("Scrolling over pages", listOf("Grid (pull past the end to add a page)", "Seamless"), if (s.gridScroll) 0 else 1, special = true) { s.gridScroll = it == 0 }
                    Chips("Scroll glide", AppSettings.LEVELS, s.scroll) { s.scroll = it }
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
