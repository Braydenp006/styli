package app.styli.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The whole tree at once, like a file manager: tap a folder to unfold or fold it, press and hold a folder or a file
 * and drag it onto a folder to move it. Held and let go without moving, a row opens its menu.
 */
@Composable
internal fun ColumnScope.ManageFolders(
    lib: Library, ui: LibUi, drag: DragState, hover: Folder?,
    onOpen: (NoteFile) -> Unit, onAction: (Action) -> Unit, onDone: () -> Unit,
) {
    val sty = LocalSty.current
    val expanded = remember { mutableStateMapOf<Folder, Boolean>() }
    @Suppress("UNUSED_EXPRESSION") ui.version

    @Composable
    fun FileRow(folder: Folder, file: NoteFile, depth: Int) {
        var menu by remember(file) { mutableStateOf(false) }
        val item = Dragged(null, file, folder, file.name)
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = StySize.touch).clip(SquircleShape(StySize.radiusMd))
                    .dragSource(drag, item, { onOpen(file) }, { menu = true }, { to -> drop(lib, item, to); ui.changed() })
                    .padding(start = (depth * 24 + 28).dp, end = StySize.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(file.name, Modifier.weight(1f), color = sty.ink, maxLines = 1)
                if (file.pinned) StyIcon(Ic.PIN, sty.honey, 22.dp)
                Text(kindLabel(file.kind), Modifier.padding(start = StySize.space3), color = sty.inkMuted, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (file.pinned) "Unpin" else "Pin") }, { menu = false; lib.setPinned(file, !file.pinned); ui.changed() })
                DropdownMenuItem({ Text("Rename") }, { menu = false; onAction(Action.RenameFile(folder, file)) })
                DropdownMenuItem({ Text("Move to folder") }, { menu = false; onAction(Action.MoveFile(folder, file)) })
                DropdownMenuItem({ Text("Delete") }, { menu = false; onAction(Action.DeleteFile(folder, file)) })
            }
        }
    }

    @Composable
    fun Branch(folder: Folder, depth: Int) {
        var menu by remember(folder) { mutableStateOf(false) }
        val open = expanded[folder] == true
        val item = Dragged(folder, null, null, folder.name)
        val total = folder.allFiles().size
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = StySize.touch).clip(SquircleShape(StySize.radiusMd))
                    .background(if (hover === folder) sty.honeyTint else Color.Transparent)
                    .dropTarget(drag, folder)
                    .dragSource(drag, item, { expanded[folder] = !open }, { menu = true }, { to -> drop(lib, item, to); ui.changed() })
                    .padding(start = (depth * 24).dp, end = StySize.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).rotate(if (open) 90f else 0f), contentAlignment = Alignment.Center) {
                    if (folder.folders.isNotEmpty() || folder.files.isNotEmpty()) StyIcon(Ic.CHEVRON, sty.inkMuted, 18.dp)
                }
                FolderIcon(folder.color?.let { Color(it) } ?: sty.borderStrong, Modifier.size(width = 26.dp, height = 21.dp))
                Text(folder.name, Modifier.padding(start = StySize.space3).weight(1f), color = sty.ink, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                if (folder.pinned) StyIcon(Ic.PIN, sty.honey, 22.dp)
                Text(if (total == 1) "1 file" else "$total files", Modifier.padding(start = StySize.space3), color = sty.inkMuted, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (folder.pinned) "Unpin" else "Pin") }, { menu = false; lib.setPinned(folder, !folder.pinned); ui.changed() })
                DropdownMenuItem({ Text("New folder inside") }, { menu = false; expanded[folder] = true; onAction(Action.NewFolder(folder)) })
                DropdownMenuItem({ Text("Change colour") }, { menu = false; onAction(Action.ColourFolder(folder)) })
                DropdownMenuItem({ Text("Rename folder") }, { menu = false; onAction(Action.RenameFolder(folder)) })
                DropdownMenuItem({ Text("Move to folder") }, { menu = false; onAction(Action.MoveFolder(folder)) })
                DropdownMenuItem({ Text("Delete folder") }, { menu = false; onAction(Action.DeleteFolder(folder)) })
            }
        }
        if (open) {
            folder.folders.sortedBy { it.name.lowercase() }.forEach { Branch(it, depth + 1) }
            lib.sorted(folder.files, ui.sort).forEach { FileRow(folder, it, depth) }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Manage folders", Modifier.weight(1f), color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
        TextButton(onDone, Modifier.heightIn(min = StySize.touch)) { Text("Done", color = sty.honeyText) }
    }
    Text(
        "Tap a folder to unfold it. Press and hold a folder or file, then drag it onto a folder to move it. Let go without dragging for more options.",
        Modifier.padding(top = StySize.space2, bottom = StySize.space3), color = sty.inkMuted, style = MaterialTheme.typography.bodySmall,
    )
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = StySize.space5)) {
        // The top level is a place to drop things too.
        Row(
            Modifier.fillMaxWidth().heightIn(min = StySize.touch).clip(SquircleShape(StySize.radiusMd))
                .background(if (hover === lib.root) sty.honeyTint else Color.Transparent).dropTarget(drag, lib.root),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text("Tabularium (top level)", Modifier.padding(start = StySize.space2), color = sty.inkMuted, style = MaterialTheme.typography.bodyLarge) }
        lib.root.folders.sortedBy { it.name.lowercase() }.forEach { Branch(it, 0) }
        lib.sorted(lib.root.files, ui.sort).forEach { FileRow(lib.root, it, -1) }
    }
}
