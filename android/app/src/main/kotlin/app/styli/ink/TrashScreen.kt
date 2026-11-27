package app.styli.ink

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * The Trash tab: files deleted on this device, newest first, as cards like the Tabularium's. Select picks several to
 * restore at once. A file goes back to the folder it came from (made again if it is gone); deleting for good always
 * asks first.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(lib: Library, ui: LibUi, onSettings: () -> Unit) {
    val sty = LocalSty.current
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") ui.version
    var selecting by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Trashed>() }
    var ask by remember { mutableStateOf<List<Trashed>?>(null) } // what a delete-for-good dialog is about
    val now = System.currentTimeMillis()
    val items = lib.trash.sortedByDescending { it.at }

    fun stop() { selecting = false; picked.clear() }
    fun restore(list: List<Trashed>) {
        val folders = ArrayList<Folder>()
        if (!ui.attempt(context, "bring it back") { list.forEach { folders.add(lib.restore(it)) } } && folders.isEmpty()) return
        val where = folders.map { lib.path(it).joinToString(" / ").ifEmpty { "Tabularium" } }.distinct()
        Toast.makeText(context, (if (list.size == 1) "Restored" else "Restored ${list.size} files") + " to " + where.joinToString(", "), Toast.LENGTH_SHORT).show()
        stop(); ui.changed()
    }
    androidx.activity.compose.BackHandler(selecting) { stop() }

    if (selecting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
        Button({ stop() }) { Text("Done") }
        Text("${picked.size} selected", Modifier.weight(1f).padding(start = StySize.space2), color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineMedium)
        Button({ restore(picked.toList()) }, enabled = picked.isNotEmpty()) { Text("Restore") }
        OutlinedButton({ ask = picked.toList() }, enabled = picked.isNotEmpty()) { Text("Delete for good") }
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
        Text("Trash", Modifier.weight(1f), color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
        TextButton(onSettings) { Text("Trash settings") }
        OutlinedButton({ selecting = true }, enabled = items.isNotEmpty()) { Text("Select") }
        OutlinedButton({ ask = items }, enabled = items.isNotEmpty()) { Text("Empty trash") }
    }
    Text(
        if (items.isEmpty()) "Nothing in the trash." else "Deleted files stay here until you bring them back or delete them for good. Hold one for more.",
        Modifier.padding(top = StySize.space2), color = sty.inkMuted,
    )
    FlowRow(
        Modifier.verticalScroll(rememberScrollState()).padding(top = StySize.space4, bottom = StySize.space6),
        horizontalArrangement = Arrangement.spacedBy(StySize.space3), verticalArrangement = Arrangement.spacedBy(StySize.space3),
    ) {
        for (t in items) {
            var menu by remember(t) { mutableStateOf(false) }
            val from = "From " + (t.from.lastOrNull() ?: "Tabularium")
            Box {
                CardFace(
                    null, t.file, noteWidth(ui.noteSize), from + "\n" + agoText(now, t.at).replaceFirst("Edited", "Deleted"),
                    Modifier.combinedClickable(onClick = { if (selecting && !picked.remove(t)) picked.add(t) }, onLongClick = { if (!selecting) menu = true }),
                    picked = if (selecting) t in picked else null,
                )
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Restore") }, { menu = false; restore(listOf(t)) })
                    DropdownMenuItem({ Text("Select") }, { menu = false; selecting = true; picked.add(t) })
                    DropdownMenuItem({ Text("Delete for good") }, { menu = false; ask = listOf(t) })
                }
            }
        }
    }

    ask?.let { list ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(if (list.size == 1) "Delete \"${list[0].file.name}\" for good?" else "Delete ${list.size} files for good?") },
            text = { Text("This removes ${if (list.size == 1) "it" else "them"} from this device and cannot be undone. Nothing is deleted everywhere: sync does not exist yet.") },
            confirmButton = {
                Button({
                    ask = null; stop()
                    ui.attempt(context, "delete it for good") { list.forEach { if (it in lib.trash) lib.purge(it) } }
                }, colors = ButtonDefaults.buttonColors(containerColor = sty.danger)) { Text("Delete for good") }
            },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel") } },
        )
    }
}
