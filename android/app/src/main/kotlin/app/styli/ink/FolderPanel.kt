package app.styli.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The folder panel on the left: the whole tree like a file manager. Tap a folder to go there (the arrow beside it folds
 * or unfolds it), tap a file to open it. Press and hold a folder or file to drag it onto a folder, or onto the bin;
 * held and let go without moving, it opens its menu (rename, move, colour, pin, delete). This is where folders are managed.
 */
@Composable
internal fun FolderPanel(
    lib: Library, ui: LibUi, drag: DragState, hover: Folder?, here: Folder,
    onOpen: (NoteFile) -> Unit, onAction: (Action) -> Unit, onTrash: (Dragged) -> Unit,
) {
    val sty = LocalSty.current
    val context = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_EXPRESSION") ui.version
    fun dropped(item: Dragged, to: Folder) = ui.attempt(context, "move it") {
        if (!drop(lib, item, to)) android.widget.Toast.makeText(context, "A folder cannot go inside itself", android.widget.Toast.LENGTH_SHORT).show()
    }

    // Whatever folder is open shows unfolded, with the ones above it.
    androidx.compose.runtime.LaunchedEffect(here) { generateSequence(here) { it.parent }.forEach { ui.expanded[it] = true } }

    @Composable
    fun FileRow(folder: Folder, file: NoteFile, depth: Int) {
        var menu by remember(file) { mutableStateOf(false) }
        val item = Dragged(null, file, folder, file.name)
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = StySize.touch).clip(StyShapes.Md)
                    .dragSource(drag, item, { onOpen(file) }, { menu = true }, { to -> dropped(item, to) }, onTrash = { onTrash(item) })
                    .padding(start = (depth * 20 + 40).dp, end = StySize.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(file.name, Modifier.weight(1f), color = sty.ink, maxLines = 1)
                if (file.pinned) StyIcon(Ic.PIN, sty.honey, 20.dp)
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
        val open = ui.expanded[folder] == true
        val item = Dragged(folder, null, null, folder.name)
        val has = folder.folders.isNotEmpty() || folder.files.isNotEmpty()
        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, label = "folder arrow")
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = StySize.touch).clip(StyShapes.Md)
                    .background(if (hover === folder) sty.honeyTint else if (here === folder) sty.honeyTint.copy(alpha = 0.5f) else Color.Transparent)
                    .dropTarget(drag, folder, key = "panel" to folder)
                    .dragSource(drag, item, { ui.openFolder = folder; ui.expanded[folder] = true }, { menu = true }, { to -> dropped(item, to) }, onTrash = { onTrash(item) })
                    .padding(start = (depth * 20).dp, end = StySize.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The arrow folds and unfolds without going there.
                Box(
                    Modifier.size(StySize.touch).clip(StyShapes.Md).clickable(enabled = has) { ui.expanded[folder] = !open }.rotate(turn),
                    contentAlignment = Alignment.Center,
                ) { if (has) StyIcon(Ic.CHEVRON, sty.inkMuted, 18.dp) }
                FolderIcon(folder.color?.let { Color(it) } ?: sty.borderStrong, Modifier.size(width = 24.dp, height = 19.dp))
                Text(folder.name, Modifier.padding(start = StySize.space2).weight(1f), color = if (here === folder) sty.honeyText else sty.ink, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                if (folder.pinned) StyIcon(Ic.PIN, sty.honey, 20.dp)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (folder.pinned) "Unpin" else "Pin") }, { menu = false; lib.setPinned(folder, !folder.pinned); ui.changed() })
                DropdownMenuItem({ Text("New folder inside") }, { menu = false; ui.expanded[folder] = true; onAction(Action.NewFolder(folder)) })
                DropdownMenuItem({ Text("Change colour") }, { menu = false; onAction(Action.ColourFolder(folder)) })
                DropdownMenuItem({ Text("Rename folder") }, { menu = false; onAction(Action.RenameFolder(folder)) })
                DropdownMenuItem({ Text("Move to folder") }, { menu = false; onAction(Action.MoveFolder(folder)) })
                DropdownMenuItem({ Text("Delete folder") }, { menu = false; onAction(Action.DeleteFolder(folder)) })
            }
        }
        Reveal(open) {
            folder.folders.sortedBy { it.name.lowercase() }.forEach { Branch(it, depth + 1) }
            lib.sorted(folder.files, ui.sort).forEach { FileRow(folder, it, depth) }
        }
    }

    Column(Modifier.width(300.dp).fillMaxHeight().background(sty.sidebar).border(1.dp, sty.border).statusBarsPadding().padding(vertical = StySize.space3, horizontal = StySize.space2)) {
        Text("Folders", Modifier.padding(horizontal = StySize.space2, vertical = StySize.space2), color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            // The top level: tap to go there, and a place to drop things too.
            Row(
                Modifier.fillMaxWidth().heightIn(min = StySize.touch).clip(StyShapes.Md)
                    .background(if (hover === lib.root) sty.honeyTint else if (here === lib.root) sty.honeyTint.copy(alpha = 0.5f) else Color.Transparent)
                    .dropTarget(drag, lib.root, key = "panel" to lib.root).clickable { ui.openFolder = null }.padding(start = StySize.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("Tabularium", color = if (here === lib.root) sty.honeyText else sty.ink, style = MaterialTheme.typography.bodyLarge) }
            lib.root.folders.sortedBy { it.name.lowercase() }.forEach { Branch(it, 0) }
            lib.sorted(lib.root.files, ui.sort).forEach { FileRow(lib.root, it, -1) }
            androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 88.dp)) // room for the arrow button that sits over the panel's corner
            Text(
                "Tap a folder to go there. Hold and drag a folder or file onto a folder to move it, or onto the bin to delete it. Let go without dragging for more.",
                Modifier.padding(StySize.space3), color = sty.inkMuted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
