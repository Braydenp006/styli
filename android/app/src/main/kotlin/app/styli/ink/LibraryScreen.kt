package app.styli.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.styli.ink.proto.Template

/** "Edited just now", "Edited 5 min ago", "Edited 3 h ago", "Edited 2 days ago". */
fun agoText(now: Long, then: Long): String {
    val min = (now - then).coerceAtLeast(0) / 60_000
    return "Edited " + when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

private sealed interface Action {
    class RenameFile(val folder: Folder, val file: NoteFile) : Action
    class MoveFile(val folder: Folder, val file: NoteFile) : Action
    class DeleteFile(val folder: Folder, val file: NoteFile) : Action
    class RenameFolder(val folder: Folder) : Action
    class DeleteFolder(val folder: Folder) : Action
    class ColourFolder(val folder: Folder) : Action
    object NewFolder : Action
}

/** Throwaway folder colours (not in `design/tokens.json` yet); null is the default. */
private val folderColours: List<Pair<String, Int?>> = listOf(
    "Default" to null, "Honey" to 0xFFC8902E.toInt(), "Red" to 0xFFC0504D.toInt(), "Orange" to 0xFFD9772B.toInt(),
    "Green" to 0xFF4F9A6B.toInt(), "Teal" to 0xFF3A9AA0.toInt(), "Blue" to 0xFF4A78C2.toInt(), "Purple" to 0xFF8A64B8.toInt(),
)

/** The first screen: a bottom bar (Library, Nexus, Trash, Settings), folders as coloured icons, recent files, search, and a plus for a new file. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(lib: Library, onOpen: (NoteFile) -> Unit) {
    val sty = LocalSty.current
    var tick by remember { mutableIntStateOf(0) } // bumped after every change so the lists redraw
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<Action?>(null) }
    var tab by remember { mutableIntStateOf(0) } // 0 Library, 1 Nexus, 2 Trash, 3 Settings
    var openFolder by remember { mutableStateOf<Folder?>(null) }
    var plusMenu by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    @Suppress("UNUSED_EXPRESSION") tick
    val inFolder = openFolder?.takeIf { it in lib.folders }
    androidx.activity.compose.BackHandler(inFolder != null && tab == 0) { openFolder = null }

    // Closing a dialog must not leave the search box focused (that raises the keyboard).
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(creating, action) { if (!creating && action == null) focus.clearFocus() }

    fun open(f: NoteFile) { lib.open(f); onOpen(f) }

    @Composable
    fun Card(folder: Folder, file: NoteFile) {
        var menu by remember { mutableStateOf(false) }
        Box {
            Column(
                Modifier.width(160.dp).clip(RoundedCornerShape(StySize.radiusLg))
                    .border(1.dp, sty.border, RoundedCornerShape(StySize.radiusLg)).background(sty.surface)
                    .combinedClickable(onClick = { open(file) }, onLongClick = { menu = true })
                    .padding(StySize.space3),
            ) {
                folder.color?.let { Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(it))) }
                Cover(file, Modifier.fillMaxWidth().height(180.dp).padding(top = StySize.space2))
                Text(file.name, Modifier.padding(top = StySize.space2), color = sty.ink, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                Text(agoText(now, file.opened), color = sty.inkMuted, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Rename") }, { menu = false; action = Action.RenameFile(folder, file) })
                DropdownMenuItem({ Text("Move to folder") }, { menu = false; action = Action.MoveFile(folder, file) })
                DropdownMenuItem({ Text("Delete") }, { menu = false; action = Action.DeleteFile(folder, file) })
            }
        }
    }

    @Composable
    fun FolderTile(folder: Folder) {
        var menu by remember(folder) { mutableStateOf(false) }
        Box {
            Column(
                Modifier.width(150.dp).clip(RoundedCornerShape(StySize.radiusLg))
                    .combinedClickable(onClick = { openFolder = folder }, onLongClick = { menu = true }).padding(StySize.space3),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FolderIcon(folder.color?.let { Color(it) } ?: sty.borderStrong, Modifier.width(110.dp).height(88.dp))
                Text(folder.name, Modifier.padding(top = StySize.space2), color = sty.ink, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                Text(if (folder.files.size == 1) "1 file" else "${folder.files.size} files", color = sty.inkMuted, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Change colour") }, { menu = false; action = Action.ColourFolder(folder) })
                DropdownMenuItem({ Text("Rename folder") }, { menu = false; action = Action.RenameFolder(folder) })
                DropdownMenuItem({ Text("Delete folder") }, { menu = false; action = Action.DeleteFolder(folder) })
            }
        }
    }

    Column(Modifier.fillMaxSize().background(sty.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = StySize.space6, vertical = StySize.space5)) {
                if (tab != 0) {
                    val (title, blurb) = when (tab) {
                        1 -> "Nexus" to "A graph of how your notes link together. It comes later."
                        2 -> "Trash" to "Deleted files will be listed here, so you can bring them back. It comes later."
                        else -> "Settings" to "Device, pens and storage settings. They come later."
                    }
                    Text(title, color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
                    Text(blurb, Modifier.padding(top = StySize.space2), color = sty.inkMuted)
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (inFolder != null) {
                            Text("Library", Modifier.clickable { openFolder = null }.padding(end = StySize.space2), color = sty.honeyText, style = MaterialTheme.typography.titleMedium)
                            Text("/", Modifier.padding(end = StySize.space2), color = sty.inkMuted, style = MaterialTheme.typography.titleMedium)
                            inFolder.color?.let { Box(Modifier.padding(end = StySize.space2).size(14.dp).clip(CircleShape).background(Color(it))) }
                            Text(inFolder.name, color = sty.ink, fontFamily = DisplayFont, maxLines = 1, style = MaterialTheme.typography.headlineMedium)
                        } else Text("Library", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
                    }
                    OutlinedTextField(query, { query = it }, Modifier.width(260.dp), singleLine = true, placeholder = { Text("Search by name") })
                    Box(Modifier.padding(start = StySize.space3)) {
                        Box(
                            Modifier.size(StySize.touch).clip(RoundedCornerShape(StySize.radiusMd)).border(1.dp, sty.borderStrong, RoundedCornerShape(StySize.radiusMd))
                                .clickable { plusMenu = true },
                            contentAlignment = Alignment.Center,
                        ) { StyIcon(Ic.PLUS, sty.ink) }
                        DropdownMenu(plusMenu, { plusMenu = false }) {
                            DropdownMenuItem({ Text("Add PDF") }, { plusMenu = false }, enabled = false)
                            DropdownMenuItem({ Text("New folder") }, { plusMenu = false; action = Action.NewFolder })
                            DropdownMenuItem({ Text("View options") }, { plusMenu = false }, enabled = false)
                        }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = StySize.space5, bottom = 96.dp)) {
                    if (query.isNotBlank()) {
                        val hits = lib.search(query)
                        if (hits.isEmpty()) Text("Nothing named like that.", color = sty.inkMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StySize.space3), verticalArrangement = Arrangement.spacedBy(StySize.space3)) {
                            hits.forEach { (f, file) -> Card(f, file) }
                        }
                    } else if (inFolder != null) {
                        if (inFolder.files.isEmpty()) Text("Empty folder. Tap the plus to add a file.", color = sty.inkMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StySize.space3), verticalArrangement = Arrangement.spacedBy(StySize.space3)) {
                            inFolder.files.toList().forEach { Card(inFolder, it) }
                        }
                    } else if (lib.folders.isEmpty()) {
                        Text("No notebooks yet", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineMedium)
                        Text("Tap the plus to make your first file, or add a folder from the plus by the search box.", color = sty.inkMuted)
                    } else {
                        val recent = lib.recent(6)
                        if (recent.isNotEmpty()) {
                            Section("Recent")
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StySize.space3)) {
                                recent.forEach { (f, file) -> Card(f, file) }
                            }
                        }
                        Box(Modifier.padding(top = StySize.space5)) { Section("Folders") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(StySize.space2), verticalArrangement = Arrangement.spacedBy(StySize.space2)) {
                            lib.folders.toList().forEach { FolderTile(it) }
                        }
                    }
                }
            }
            if (tab == 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = StySize.space6, bottom = StySize.space4).size(60.dp)
                        .clip(CircleShape).background(sty.honey).clickable { creating = true },
                    contentAlignment = Alignment.Center,
                ) { StyIcon(Ic.PLUS, sty.onHoney, 28.dp) }
            }
        }
        Row(Modifier.fillMaxWidth().background(sty.sidebar).border(1.dp, sty.border).navigationBarsPadding().height(64.dp)) {
            listOf(Ic.LIBRARY to "Library", Ic.NEXUS to "Nexus", Ic.TRASH to "Trash", Ic.SETTINGS to "Settings").forEachIndexed { i, (ic, label) ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { tab = i; if (i == 0) openFolder = null },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.clip(CircleShape).background(if (tab == i) sty.honeyTint else Color.Transparent).padding(horizontal = StySize.space4, vertical = StySize.space1)) {
                        StyIcon(ic, sty.ink)
                    }
                    Text(label, color = if (tab == i) sty.honeyText else sty.inkMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (creating) NewFileDialog(lib, inFolder?.name, onDismiss = { creating = false }) { name, folderName, look ->
        creating = false
        val folder = lib.folders.firstOrNull { it.name.equals(folderName.trim(), true) } ?: lib.newFolder(folderName)
        open(lib.newFile(folder, name, look))
        tick++
    }

    when (val a = action) {
        is Action.RenameFile -> NameDialog("Rename file", a.file.name, { action = null }) { lib.renameFile(a.folder, a.file, it); action = null; tick++ }
        is Action.RenameFolder -> NameDialog("Rename folder", a.folder.name, { action = null }) { lib.renameFolder(a.folder, it); action = null; tick++ }
        is Action.MoveFile -> AlertDialog(
            onDismissRequest = { action = null }, title = { Text("Move to which folder?") },
            text = { Column { lib.folders.filter { it !== a.folder }.forEach { to -> TextButton({ lib.moveFile(a.folder, a.file, to); action = null; tick++ }) { Text(to.name) } } } },
            confirmButton = {}, dismissButton = { TextButton({ action = null }) { Text("Cancel") } },
        )
        is Action.DeleteFile -> DeleteDialog("Delete \"${a.file.name}\"?", { action = null }) { lib.delete(a.folder, a.file); action = null; tick++ }
        is Action.DeleteFolder -> DeleteDialog("Delete the folder \"${a.folder.name}\" and its ${a.folder.files.size} file(s)?", { action = null }) { lib.deleteFolder(a.folder); action = null; tick++ }
        is Action.ColourFolder -> AlertDialog(
            onDismissRequest = { action = null }, title = { Text("Colour for \"${a.folder.name}\"") },
            text = { Swatches(a.folder.color) { a.folder.color = it; action = null; tick++ } },
            confirmButton = {}, dismissButton = { TextButton({ action = null }) { Text("Cancel") } },
        )
        Action.NewFolder -> NewFolderDialog({ action = null }) { name, color -> lib.newFolder(name, color); action = null; tick++ }
        null -> {}
    }
}

@Composable
private fun Swatches(chosen: Int?, onPick: (Int?) -> Unit) {
    val sty = LocalSty.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        folderColours.forEach { (label, c) ->
            Column(Modifier.clip(RoundedCornerShape(StySize.radiusMd)).combinedClickable(onClick = { onPick(c) }).padding(StySize.space1), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(c?.let { Color(it) } ?: sty.surface)
                        .border(if (c == chosen) 3.dp else 1.dp, if (c == chosen) sty.ink else sty.borderStrong, CircleShape),
                )
                Text(label, color = sty.inkMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onOk: (String, Int?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("New folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StySize.space2)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Swatches(color) { color = it }
            }
        },
        confirmButton = { Button({ onOk(name, color) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(text: String) =
    Text(text, color = LocalSty.current.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = StySize.space2))

/** A tiny picture of the file's first page: its shape and its template. */
@Composable
private fun Cover(file: NoteFile, modifier: Modifier) {
    val sty = LocalSty.current
    val p = file.notebook.pages.first().page
    Canvas(modifier) {
        val aspect = if (p.infinite) 1f else p.width.toFloat() / p.height
        val h = minOf(size.height, size.width / aspect)
        val w = h * aspect
        val x0 = (size.width - w) / 2; val y0 = (size.height - h) / 2
        drawRect(androidx.compose.ui.graphics.Color.White, Offset(x0, y0), androidx.compose.ui.geometry.Size(w, h))
        drawRect(sty.border, Offset(x0, y0), androidx.compose.ui.geometry.Size(w, h), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        val step = h / 14
        val ruling = androidx.compose.ui.graphics.Color(0xFFC9D3E6) // the paper is white in both themes, so its lines are too
        when (p.template) {
            Template.TEMPLATE_LINED -> { var y = y0 + step * 2; while (y < y0 + h) { drawLine(ruling, Offset(x0, y), Offset(x0 + w, y)); y += step } }
            Template.TEMPLATE_GRID -> {
                var y = y0 + step; while (y < y0 + h) { drawLine(ruling, Offset(x0, y), Offset(x0 + w, y)); y += step }
                var x = x0 + step; while (x < x0 + w) { drawLine(ruling, Offset(x, y0), Offset(x, y0 + h)); x += step }
            }
            else -> {}
        }
    }
}

@Composable
private fun NameDialog(title: String, start: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { Button({ onOk(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Deletes always say where they reach. Everywhere needs an atrium and sync, which this screen does not have yet. */
@Composable
private fun DeleteDialog(title: String, onDismiss: () -> Unit, onThisDevice: () -> Unit) {
    val sty = LocalSty.current
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { Text("It goes to the trash on this device first, so you can bring it back. \"Delete everywhere\" is not available until this device is paired with an atrium.") },
        confirmButton = { Button(onThisDevice, colors = ButtonDefaults.buttonColors(containerColor = sty.danger)) { Text("Delete from this device") } },
        dismissButton = { Row { TextButton({}, enabled = false) { Text("Delete everywhere") }; TextButton(onDismiss) { Text("Cancel") } } },
    )
}

private fun templateName(t: Template) = when (t) { Template.TEMPLATE_LINED -> "Lined"; Template.TEMPLATE_GRID -> "Grid"; else -> "Blank" }

/** A new file: name, folder, and the first page's starting look (each page can change it later). */
@Composable
private fun NewFileDialog(lib: Library, startFolder: String?, onDismiss: () -> Unit, onCreate: (String, String, NewFileLook) -> Unit) {
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(startFolder ?: lib.folders.firstOrNull()?.name ?: "Notes") }
    var look by remember { mutableStateOf(NewFileLook()) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("New file") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(StySize.space2)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder (a new name makes a new folder)") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
                    lib.folders.forEach { f -> FilterChip(f.name.equals(folder.trim(), true), { folder = f.name }, { Text(f.name) }) }
                }
                Chips("Paper", listOf("A4", "Letter"), if (look.letter) 1 else 0) { look = look.copy(letter = it == 1) }
                Chips("Orientation", listOf("Portrait", "Landscape"), if (look.landscape) 1 else 0) { look = look.copy(landscape = it == 1) }
                Chips("Pages", listOf("Fixed", "Infinite canvas"), if (look.infinite) 1 else 0) { look = look.copy(infinite = it == 1) }
                val templates = listOf(Template.TEMPLATE_BLANK, Template.TEMPLATE_LINED, Template.TEMPLATE_GRID)
                Chips("Template", templates.map(::templateName), templates.indexOf(look.template)) { look = look.copy(template = templates[it]) }
            }
        },
        confirmButton = { Button({ onCreate(name, folder, look) }, enabled = name.isNotBlank() && folder.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Chips(label: String, options: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    Text(label, color = LocalSty.current.inkMuted, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
        options.forEachIndexed { i, o -> FilterChip(i == chosen, { onPick(i) }, { Text(o) }, Modifier.heightIn(min = StySize.touch)) }
    }
}
