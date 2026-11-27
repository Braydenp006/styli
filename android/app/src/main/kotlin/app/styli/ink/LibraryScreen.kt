package app.styli.ink

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.styli.ink.proto.Template
import kotlin.math.roundToInt

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

/** Something that needs a dialog. Shared with the Manage folders screen. */
internal sealed interface Action {
    class RenameFile(val folder: Folder, val file: NoteFile) : Action
    class MoveFile(val folder: Folder, val file: NoteFile) : Action
    class DeleteFile(val folder: Folder, val file: NoteFile) : Action
    class RenameFolder(val folder: Folder) : Action
    class MoveFolder(val folder: Folder) : Action
    class DeleteFolder(val folder: Folder) : Action
    class ColourFolder(val folder: Folder) : Action
    class NewFolder(val parent: Folder) : Action
    object ViewOptions : Action
    object MoveSelected : Action
    object DeleteSelected : Action
}

/** Throwaway folder colours (not in `design/tokens.json` yet); null is the default. */
private val folderColours: List<Pair<String, Int?>> = listOf(
    "Default" to null, "Honey" to 0xFFC8902E.toInt(), "Red" to 0xFFC0504D.toInt(), "Orange" to 0xFFD9772B.toInt(),
    "Green" to 0xFF4F9A6B.toInt(), "Teal" to 0xFF3A9AA0.toInt(), "Blue" to 0xFF4A78C2.toInt(), "Purple" to 0xFF8A64B8.toInt(),
)

internal fun noteWidth(s: Size3): Dp = when (s) { Size3.SMALL -> 96.dp; Size3.MEDIUM -> 132.dp; Size3.LARGE -> 180.dp }
private fun folderWidth(s: Size3): Dp = when (s) { Size3.SMALL -> 88.dp; Size3.MEDIUM -> 120.dp; Size3.LARGE -> 168.dp }
internal fun kindLabel(k: FileKind) = when (k) { FileKind.INK -> "Ink"; FileKind.MARKDOWN -> "Markdown"; FileKind.PDF -> "PDF" }

/** Every folder of the library as a choice, indented by depth, leaving out [exclude] and what is inside it. */
internal fun folderChoices(lib: Library, exclude: Folder? = null): List<Pair<Folder, String>> {
    val skip = exclude?.allFolders() ?: emptyList()
    fun walk(f: Folder, depth: Int): List<Pair<Folder, String>> =
        if (f in skip) emptyList()
        else listOf(f to ("    ".repeat(depth) + if (f === lib.root) "Tabularium (top level)" else f.name)) + f.folders.flatMap { walk(it, depth + 1) }
    return walk(lib.root, 0)
}

/**
 * The Tabularium: a bottom bar (Tabularium, Nexus, Itinerarium, Trash, Settings), folders as coloured icons, files as covers,
 * pinned and recent files, a kind filter, search, and drag and drop to move things.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(lib: Library, ui: LibUi, settings: AppSettings, storage: StorageControls, onOpen: (NoteFile) -> Unit) {
    val sty = LocalSty.current
    val context = LocalContext.current
    val drag = remember { DragState() }
    val hover by remember { derivedStateOf { drag.over() } }
    @Suppress("UNUSED_EXPRESSION") ui.version
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<Action?>(null) }
    var tab by remember { mutableIntStateOf(0) } // 0 Tabularium, 1 Nexus, 2 Itinerarium, 3 Trash, 4 Settings
    var menuOpen by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val inFolder = ui.openFolder?.takeIf { lib.contains(it) }
    val here = inFolder ?: lib.root

    androidx.activity.compose.BackHandler(tab == 0 && ui.selecting) { ui.stopSelect() }
    androidx.activity.compose.BackHandler(tab == 0 && !ui.selecting && inFolder != null) {
        ui.openFolder = inFolder?.parent?.takeIf { it !== lib.root }
    }

    // Closing a dialog must not leave the search box focused (that raises the keyboard).
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(creating, action) { if (!creating && action == null) focus.clearFocus() }

    fun open(f: NoteFile) {
        if (f.kind != FileKind.INK) { Toast.makeText(context, "${kindLabel(f.kind)} files open here later", Toast.LENGTH_SHORT).show(); return }
        lib.open(f); onOpen(f)
    }
    fun moved(d: Dragged, to: Folder) {
        if (!drop(lib, d, to)) Toast.makeText(context, "A folder cannot go inside itself", Toast.LENGTH_SHORT).show()
        ui.changed()
    }
    fun parentOf(f: NoteFile): Folder? = lib.root.allFiles().firstOrNull { it.second === f }?.first
    fun trashed(d: Dragged) {
        val file = d.file
        if (file != null && d.from != null) {
            lib.delete(d.from, file)
            Toast.makeText(context, "\"${file.name}\" moved to the trash on this device", Toast.LENGTH_SHORT).show()
            ui.changed()
        } else if (d.folder != null) action = Action.DeleteFolder(d.folder) // a folder takes many files with it, so it asks
    }
    fun visible(files: List<NoteFile>) = lib.sorted(files.filter { ui.filter == null || it.kind == ui.filter }, ui.sort)

    @Composable
    fun Card(folder: Folder, file: NoteFile) {
        var menu by remember { mutableStateOf(false) }
        val w = noteWidth(ui.noteSize)
        val item = Dragged(null, file, folder, file.name, { CardFace(folder.color, file, w, cardSub(file, now), Modifier) })
        Box {
            CardFace(
                folder.color, file, w, cardSub(file, now),
                Modifier.alpha(if (drag.item?.file === file) 0.3f else 1f) // the one being carried leaves a faint ghost behind
                    .dragSource(drag, item, { if (ui.selecting) ui.toggle(file) else open(file) }, { menu = true }, { moved(item, it) }, enabled = !ui.selecting, onTrash = { trashed(item) }),
                picked = if (ui.selecting) file in ui.selected else null,
            )
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Select") }, { menu = false; ui.startSelect(file) })
                DropdownMenuItem({ Text(if (file.pinned) "Unpin" else "Pin") }, { menu = false; lib.setPinned(file, !file.pinned); ui.changed() })
                DropdownMenuItem({ Text("Rename") }, { menu = false; action = Action.RenameFile(folder, file) })
                DropdownMenuItem({ Text("Move to folder") }, { menu = false; action = Action.MoveFile(folder, file) })
                DropdownMenuItem({ Text("Delete") }, { menu = false; action = Action.DeleteFile(folder, file) })
            }
        }
    }

    @Composable
    fun FolderTile(folder: Folder) {
        var menu by remember(folder) { mutableStateOf(false) }
        val w = folderWidth(ui.folderSize)
        val total = folder.allFiles().size
        val item = Dragged(folder, null, null, folder.name, { FolderFace(folder, w, total, false, Modifier) })
        val grow by androidx.compose.animation.core.animateFloatAsState(if (hover === folder) 1.1f else 1f, label = "folder grows under a drag")
        Box {
            FolderFace(
                folder, w, total, hover === folder,
                Modifier.alpha(if (drag.item?.folder === folder) 0.3f else 1f).graphicsLayer { scaleX = grow; scaleY = grow }
                    .dropTarget(drag, folder)
                    .dragSource(drag, item, { if (ui.selecting) ui.toggle(folder) else ui.openFolder = folder }, { menu = true }, { moved(item, it) }, enabled = !ui.selecting, onTrash = { trashed(item) }),
                picked = if (ui.selecting) folder in ui.selected else null,
            )
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Select") }, { menu = false; ui.startSelect(folder) })
                DropdownMenuItem({ Text(if (folder.pinned) "Unpin" else "Pin") }, { menu = false; lib.setPinned(folder, !folder.pinned); ui.changed() })
                DropdownMenuItem({ Text("Change colour") }, { menu = false; action = Action.ColourFolder(folder) })
                DropdownMenuItem({ Text("Rename folder") }, { menu = false; action = Action.RenameFolder(folder) })
                DropdownMenuItem({ Text("Move to folder") }, { menu = false; action = Action.MoveFolder(folder) })
                DropdownMenuItem({ Text("Delete folder") }, { menu = false; action = Action.DeleteFolder(folder) })
            }
        }
    }

    @Composable
    fun Cards(items: List<Pair<Folder, NoteFile>>) = FlowRow(horizontalArrangement = Arrangement.spacedBy(StySize.space3), verticalArrangement = Arrangement.spacedBy(StySize.space3)) {
        items.forEach { (f, file) -> Card(f, file) }
    }

    @Composable
    fun Tiles(items: List<Folder>) = FlowRow(horizontalArrangement = Arrangement.spacedBy(StySize.space2), verticalArrangement = Arrangement.spacedBy(StySize.space2)) {
        items.forEach { FolderTile(it) }
    }

    Box(Modifier.fillMaxSize().background(sty.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
            androidx.compose.animation.AnimatedVisibility(
                tab == 0 && ui.panelOpen,
                enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
            ) { FolderPanel(lib, ui, drag, hover, here, onOpen = ::open, onAction = { action = it }, onTrash = ::trashed) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = StySize.space6, vertical = StySize.space5)) {
                    if (tab != 0) {
                        when (tab) {
                            3 -> TrashScreen(lib, ui) { settings.section = SettingsSection.TRASH; tab = 4 }
                            4 -> SettingsScreen(settings, lib, ui, storage)
                            else -> {
                                val (title, blurb) = if (tab == 1) "Nexus" to "A graph of how your notes link together. It comes later."
                                else "Itinerarium" to "Paths you choose through your notes, in an order you set. It comes later."
                                Text(title, color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
                                Text(blurb, Modifier.padding(top = StySize.space2), color = sty.inkMuted)
                            }
                        }
                    } else {
                        if (ui.selecting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
                            Button({ ui.stopSelect() }) { Text("Done") }
                            Text("${ui.selected.size} selected", Modifier.weight(1f).padding(start = StySize.space2), color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineMedium)
                            val any = ui.selected.isNotEmpty()
                            val allPinned = ui.selected.all { (it as? NoteFile)?.pinned ?: (it as Folder).pinned }
                            OutlinedButton({ ui.selected.forEach { if (it is NoteFile) lib.setPinned(it, !allPinned) else lib.setPinned(it as Folder, !allPinned) }; ui.changed() }, enabled = any) { Text(if (allPinned && any) "Unpin" else "Pin") }
                            OutlinedButton({ action = Action.MoveSelected }, enabled = any) { Text("Move") }
                            Button({ action = Action.DeleteSelected }, enabled = any, colors = ButtonDefaults.buttonColors(containerColor = sty.danger)) { Text("Delete") }
                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                            if (inFolder != null) Box(
                                Modifier.padding(end = StySize.space3).size(StySize.touch).clip(SquircleShape(14.dp)).background(sty.honeyTint)
                                    .border(2.dp, sty.honey, SquircleShape(14.dp)).clickable { ui.openFolder = inFolder.parent?.takeIf { it !== lib.root } },
                                contentAlignment = Alignment.Center,
                            ) { Box(Modifier.rotate(180f)) { StyIcon(Ic.CHEVRON, sty.ink, 24.dp) } }
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                // The way back up: each folder above this one is a link, and a place to drop things.
                                val trail = generateSequence(inFolder) { it.parent }.takeWhile { it !== lib.root }.toList().reversed()
                                if (inFolder == null) Text("Tabularium", color = sty.ink, fontFamily = DisplayFont, maxLines = 1, softWrap = false, style = MaterialTheme.typography.headlineLarge)
                                else {
                                    // With the folder panel open the path is already on show there, so only the folder's own name is kept.
                                    if (!ui.panelOpen) Crumb("Tabularium", hover === lib.root, Modifier.dropTarget(drag, lib.root)) { ui.openFolder = null }
                                    trail.forEachIndexed { i, f ->
                                        if (ui.panelOpen && i != trail.lastIndex) return@forEachIndexed
                                        if (!ui.panelOpen) Text("/", Modifier.padding(horizontal = StySize.space1), color = sty.inkMuted, style = MaterialTheme.typography.titleMedium)
                                        if (i == trail.lastIndex) {
                                            f.color?.let { Box(Modifier.padding(end = StySize.space2).size(14.dp).clip(CircleShape).background(Color(it))) }
                                            Text(f.name, color = sty.ink, fontFamily = DisplayFont, maxLines = 1, style = MaterialTheme.typography.headlineMedium)
                                        } else Crumb(f.name, hover === f, Modifier.dropTarget(drag, f)) { ui.openFolder = f }
                                    }
                                }
                            }
                            OutlinedTextField(query, { query = it }, Modifier.width(if (ui.panelOpen) 170.dp else 260.dp), singleLine = true, placeholder = { Text(if (ui.panelOpen) "Search" else "Search by name") })
                            Box(Modifier.padding(start = StySize.space3)) {
                                Box(
                                    Modifier.size(StySize.touch).clip(SquircleShape(StySize.radiusMd)).border(1.dp, sty.borderStrong, SquircleShape(StySize.radiusMd))
                                        .clickable { menuOpen = !menuOpen },
                                    contentAlignment = Alignment.Center,
                                ) { MenuIcon(menuOpen, sty.ink) }
                                DropdownMenu(menuOpen, { menuOpen = false }) {
                                    DropdownMenuItem({ Text("Select") }, { menuOpen = false; ui.startSelect() })
                                    DropdownMenuItem({ Text("View options") }, { menuOpen = false; action = Action.ViewOptions })
                                }
                            }
                        }
                        Row(Modifier.padding(top = StySize.space3).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
                            FilterChip(ui.filter == null, { ui.filter = null }, { Text("All") }, Modifier.heightIn(min = StySize.touch))
                            for (k in FileKind.entries) FilterChip(ui.filter == k, { ui.filter = k }, { Text(kindLabel(k)) }, Modifier.heightIn(min = StySize.touch))
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = StySize.space4, bottom = 96.dp)) {
                            if (query.isNotBlank()) {
                                val hits = lib.search(query, ui.filter)
                                if (hits.isEmpty()) Text("Nothing named like that.", color = sty.inkMuted)
                                Cards(hits)
                            } else if (inFolder != null) {
                                if (inFolder.folders.isNotEmpty()) Tiles(inFolder.folders.sortedBy { it.name.lowercase() })
                                val files = visible(inFolder.files)
                                if (inFolder.folders.isEmpty() && inFolder.files.isEmpty()) Text("Empty folder. Tap the pen and paper to add a note.", color = sty.inkMuted)
                                else if (files.isEmpty() && inFolder.files.isNotEmpty()) Text("No ${ui.filter?.let(::kindLabel)} files here.", color = sty.inkMuted)
                                Box(Modifier.padding(top = StySize.space3)) { Cards(files.map { inFolder to it }) }
                            } else if (lib.root.allFolders().size == 1 && lib.root.files.isEmpty()) {
                                Text("Nothing here yet", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineMedium)
                                Text("Tap the pen and paper to write your first note, or the plus to add a folder.", color = sty.inkMuted)
                            } else {
                                val pinnedFolders = lib.pinnedFolders()
                                val pinnedFiles = lib.pinnedFiles().filter { ui.filter == null || it.second.kind == ui.filter }
                                if (pinnedFolders.isNotEmpty() || pinnedFiles.isNotEmpty()) {
                                    Section("Pinned", ui.pinnedOpen) { ui.pinnedOpen = !ui.pinnedOpen }
                                    Reveal(ui.pinnedOpen) {
                                        Column {
                                            if (pinnedFolders.isNotEmpty()) { SubHeading("Pinned folders"); Tiles(pinnedFolders) }
                                            if (pinnedFiles.isNotEmpty()) {
                                                Box(Modifier.padding(top = if (pinnedFolders.isNotEmpty()) StySize.space4 else 0.dp)) { SubHeading("Pinned notes") }
                                                Cards(pinnedFiles)
                                            }
                                        }
                                    }
                                }
                                val recent = lib.recent(6, ui.filter)
                                if (recent.isNotEmpty()) {
                                    Box(Modifier.padding(top = StySize.space5)) { Section("Recent", ui.recentOpen) { ui.recentOpen = !ui.recentOpen } }
                                    Reveal(ui.recentOpen) {
                                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StySize.space3)) {
                                            recent.forEach { (f, file) -> Card(f, file) }
                                        }
                                    }
                                }
                                if (lib.root.folders.isNotEmpty()) {
                                    Box(Modifier.padding(top = StySize.space5)) { Section("Folders") }
                                    Tiles(lib.root.folders.sortedBy { it.name.lowercase() })
                                }
                                val loose = visible(lib.root.files)
                                if (loose.isNotEmpty()) {
                                    Box(Modifier.padding(top = StySize.space5)) { Section("Files") }
                                    Cards(loose.map { lib.root to it })
                                }
                            }
                        }
                    }
                }
                if (tab == 0) {
                    Row(Modifier.align(Alignment.BottomEnd).padding(end = StySize.space6, bottom = StySize.space4), horizontalArrangement = Arrangement.spacedBy(StySize.space3)) {
                        Box {
                            Box(
                                Modifier.size(60.dp).shadow(8.dp, SquircleShape(20.dp)).clip(SquircleShape(20.dp)).background(sty.bg.copy(alpha = 0.7f)).background(sty.honeyTint).border(2.dp, sty.honey, SquircleShape(20.dp)).clickable { plusOpen = true },
                                contentAlignment = Alignment.Center,
                            ) { StyIcon(Ic.PLUS, sty.ink, 28.dp) }
                            DropdownMenu(plusOpen, { plusOpen = false }) {
                                DropdownMenuItem({ Text("New folder") }, { plusOpen = false; action = Action.NewFolder(here) })
                                DropdownMenuItem({ Text("Add a file (PDF, Markdown): later") }, { plusOpen = false }, enabled = false)
                                DropdownMenuItem({ Text("Import notes from other services: later") }, { plusOpen = false }, enabled = false)
                            }
                        }
                        Box(
                            Modifier.size(60.dp).clip(SquircleShape(20.dp)).background(sty.honey).clickable { creating = true },
                            contentAlignment = Alignment.Center,
                        ) { StyIcon(Ic.NEWNOTE, sty.onHoney, 28.dp) }
                    }
                }
            }
            }
            // The arrow stays in the bottom left corner of the screen, over the panel when it is open (not pushed by it).
            if (tab == 0) {
                Row(Modifier.align(Alignment.BottomStart).padding(start = StySize.space6, bottom = StySize.space4), horizontalArrangement = Arrangement.spacedBy(StySize.space3)) {
                    // The arrow opens and closes the folder panel (same look as the plus; it points back while the panel is open).
                    Box(
                        Modifier.size(60.dp).shadow(8.dp, SquircleShape(20.dp)).clip(SquircleShape(20.dp)).background(sty.bg.copy(alpha = 0.7f)).background(sty.honeyTint)
                            .border(2.dp, sty.honey, SquircleShape(20.dp)).clickable { ui.panelOpen = !ui.panelOpen },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.rotate(if (ui.panelOpen) 180f else 0f)) { StyIcon(Ic.CHEVRON, sty.ink, 28.dp) } }
                    // While something is dragged, the bin shows beside it: let go of a file or folder on it to send it to the trash.
                    androidx.compose.animation.AnimatedVisibility(
                        drag.item != null,
                        enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                        exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally(),
                    ) {
                        val over = drag.overTrash()
                        val grow by androidx.compose.animation.core.animateFloatAsState(if (over) 1.15f else 1f, label = "bin grows")
                        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { drag.trashBox = null } }
                        Box(
                            Modifier.size(60.dp).graphicsLayer { scaleX = grow; scaleY = grow }.shadow(8.dp, SquircleShape(20.dp)).clip(SquircleShape(20.dp))
                                .background(sty.bg.copy(alpha = 0.7f)).background(if (over) sty.danger.copy(alpha = 0.4f) else sty.danger.copy(alpha = 0.22f)).border(2.dp, sty.danger, SquircleShape(20.dp))
                                .onGloballyPositioned { drag.trashBox = it.boundsInRoot() },
                            contentAlignment = Alignment.Center,
                        ) { StyIcon(Ic.TRASH, sty.ink, 28.dp) }
                    }
                }
            }
            }
            // One honey squircle slides under whichever tab is chosen (it springs a little as it lands).
            val spots = remember { androidx.compose.runtime.mutableStateMapOf<Int, androidx.compose.ui.unit.IntOffset>() }
            var spotSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            var barAt by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier.fillMaxWidth().background(sty.sidebar).border(1.dp, sty.border).navigationBarsPadding().height(64.dp)
                    .onGloballyPositioned { barAt = it.positionInRoot() },
            ) {
                spots[tab]?.let { target ->
                    val at by androidx.compose.animation.core.animateIntOffsetAsState(
                        target, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 380f), label = "tab highlight",
                    )
                    val d = androidx.compose.ui.platform.LocalDensity.current
                    Box(
                        Modifier.offset { at }.size(with(d) { spotSize.width.toDp() }, with(d) { spotSize.height.toDp() })
                            .clip(SquircleShape(14.dp)).background(sty.honeyTint),
                    )
                }
                Row(Modifier.fillMaxSize()) {
                    listOf(Ic.LIBRARY to "Tabularium", Ic.NEXUS to "Nexus", Ic.ITINERARIUM to "Itinerarium", Ic.TRASH to "Trash", Ic.SETTINGS to "Settings").forEachIndexed { i, (ic, label) ->
                        val labelColour by androidx.compose.animation.animateColorAsState(if (tab == i) sty.honeyText else sty.inkMuted, label = "tab label")
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clickable { tab = i; if (i == 0) ui.openFolder = null },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                // Measured before the padding, so the highlight covers the icon and its breathing room.
                                Modifier.onGloballyPositioned {
                                    spots[i] = (it.positionInRoot() - barAt).let { p -> androidx.compose.ui.unit.IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                                    spotSize = it.size
                                }.padding(horizontal = StySize.space4, vertical = StySize.space1),
                            ) { StyIcon(ic, sty.ink) }
                            Text(label, color = labelColour, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        // What is being dragged follows the finger.
        drag.item?.let { d -> DragGhost(drag, d) }
    }

    if (creating) NewFileDialog(lib, here, onDismiss = { creating = false }) { name, folder, look ->
        creating = false
        open(lib.newFile(folder, name, look))
        ui.changed()
    }

    when (val a = action) {
        is Action.RenameFile -> NameDialog("Rename file", a.file.name, { action = null }) { lib.renameFile(a.folder, a.file, it); action = null; ui.changed() }
        is Action.RenameFolder -> NameDialog("Rename folder", a.folder.name, { action = null }) { lib.renameFolder(a.folder, it); action = null; ui.changed() }
        is Action.MoveFile -> MoveDialog(lib, null, { action = null }) { lib.moveFile(a.folder, a.file, it); action = null; ui.changed() }
        is Action.MoveFolder -> MoveDialog(lib, a.folder, { action = null }) { lib.moveFolder(a.folder, it); action = null; ui.changed() }
        is Action.DeleteFile -> DeleteDialog("Delete \"${a.file.name}\"?", { action = null }) { lib.delete(a.folder, a.file); action = null; ui.changed() }
        is Action.DeleteFolder -> DeleteDialog(
            "Delete the folder \"${a.folder.name}\" and everything in it (${a.folder.allFiles().size} file(s))?", { action = null },
        ) { lib.deleteFolder(a.folder); action = null; ui.changed() }
        is Action.ColourFolder -> AlertDialog(
            onDismissRequest = { action = null }, title = { Text("Colour for \"${a.folder.name}\"") },
            text = { Swatches(a.folder.color) { a.folder.color = it; action = null; ui.changed() } },
            confirmButton = {}, dismissButton = { TextButton({ action = null }) { Text("Cancel") } },
        )
        is Action.NewFolder -> NewFolderDialog({ action = null }) { name, color -> lib.newFolder(name, color, a.parent); action = null; ui.changed() }
        Action.ViewOptions -> ViewOptionsDialog(ui) { action = null }
        Action.MoveSelected -> MoveDialog(lib, null, { action = null }) { to ->
            val items = ui.selected.toList()
            var refused = 0
            items.filterIsInstance<NoteFile>().forEach { f -> parentOf(f)?.let { lib.moveFile(it, f, to) } }
            items.filterIsInstance<Folder>().forEach { if (!lib.moveFolder(it, to)) refused++ }
            if (refused > 0) Toast.makeText(context, "A folder cannot go inside itself", Toast.LENGTH_SHORT).show()
            action = null; ui.stopSelect(); ui.changed()
        }
        Action.DeleteSelected -> DeleteDialog("Delete ${ui.selected.size} selected item(s)?", { action = null }) {
            val items = ui.selected.toList()
            val parents = items.filterIsInstance<NoteFile>().associateWith { parentOf(it) } // look up before any folder goes
            parents.forEach { (f, p) -> if (p != null) lib.delete(p, f) }
            items.filterIsInstance<Folder>().forEach { if (lib.contains(it)) lib.deleteFolder(it) }
            action = null; ui.stopSelect(); ui.changed()
        }
        null -> {}
    }
}

@Composable
private fun Crumb(text: String, hovered: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sty = LocalSty.current
    Text(
        text, modifier.clip(SquircleShape(StySize.radiusMd)).background(if (hovered) sty.honeyTint else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = StySize.space2, vertical = StySize.space1),
        color = sty.honeyText, style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun Swatches(chosen: Int?, onPick: (Int?) -> Unit) {
    val sty = LocalSty.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        folderColours.forEach { (label, c) ->
            Column(Modifier.clip(SquircleShape(StySize.radiusMd)).clickable { onPick(c) }.padding(StySize.space1), horizontalAlignment = Alignment.CenterHorizontally) {
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

/** A heading. With [open] set it is an accordion: tap to fold the section up or down. */
@Composable
private fun Section(text: String, open: Boolean? = null, onToggle: () -> Unit = {}) {
    val sty = LocalSty.current
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open == true) 90f else 0f, label = "chevron")
    Row(
        Modifier.then(if (open != null) Modifier.clip(SquircleShape(StySize.radiusMd)).clickable(onClick = onToggle) else Modifier).padding(bottom = StySize.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (open != null) Box(Modifier.rotate(turn)) { StyIcon(Ic.CHEVRON, sty.inkMuted, 22.dp) }
        Text(text, color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SubHeading(text: String) =
    Text(text, color = LocalSty.current.inkMuted, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = StySize.space2, bottom = StySize.space2))

/** The pin mark: a honey pin on a small badge, so a pinned file or folder is easy to spot. */
@Composable
private fun PinBadge() {
    val sty = LocalSty.current
    Box(Modifier.size(28.dp).clip(SquircleShape(9.dp)).background(sty.surface).border(1.dp, sty.honey, SquircleShape(9.dp)), contentAlignment = Alignment.Center) {
        StyIcon(Ic.PIN, sty.honey, 20.dp)
    }
}

private fun cardSub(file: NoteFile, now: Long) = if (file.kind == FileKind.INK) agoText(now, file.opened) else kindLabel(file.kind)

/** A file as a card (cover, name, when it was opened). Also what is shown lifted while it is dragged. */
@Composable
internal fun CardFace(color: Int?, file: NoteFile, w: Dp, sub: String, modifier: Modifier, picked: Boolean? = null) {
    val sty = LocalSty.current
    Box(Modifier.width(w)) {
    Column(
        Modifier.fillMaxWidth().clip(SquircleShape(StySize.radiusLg)).border(if (picked == true) 2.dp else 1.dp, if (picked == true) sty.honey else sty.border, SquircleShape(StySize.radiusLg))
            .background(sty.surface).then(modifier).padding(StySize.space3),
    ) {
        color?.let { Box(Modifier.fillMaxWidth().height(4.dp).clip(SquircleShape(2.dp)).background(Color(it))) }
        Box {
            Cover(file, Modifier.fillMaxWidth().height(w * 1.1f).padding(top = StySize.space2))
            if (file.pinned) Box(Modifier.align(Alignment.TopEnd).padding(StySize.space1)) { PinBadge() }
        }
        Text(file.name, Modifier.padding(top = StySize.space2), color = sty.ink, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
        Text(sub, color = sty.inkMuted, style = MaterialTheme.typography.bodySmall)
    }
    picked?.let { SelectTab(it, Modifier.align(Alignment.TopCenter)) }
    }
}

/** A folder tile: coloured icon, name, file count. Also what is shown lifted while it is dragged. */
@Composable
private fun FolderFace(folder: Folder, w: Dp, total: Int, hovered: Boolean, modifier: Modifier, picked: Boolean? = null) {
    val sty = LocalSty.current
    Box(Modifier.width(w)) {
    Column(
        Modifier.fillMaxWidth().clip(SquircleShape(StySize.radiusLg)).background(if (hovered || picked == true) sty.honeyTint else if (picked == false) sty.surface else Color.Transparent)
            .then(if (picked != null) Modifier.border(if (picked) 2.dp else 1.dp, if (picked) sty.honey else sty.border, SquircleShape(StySize.radiusLg)) else Modifier)
            .then(modifier).padding(start = StySize.space3, end = StySize.space3, bottom = StySize.space3, top = if (picked != null) StySize.space6 else StySize.space3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            FolderIcon(folder.color?.let { Color(it) } ?: sty.borderStrong, Modifier.width(w * 0.73f).height(w * 0.59f))
            if (folder.pinned) Box(Modifier.align(Alignment.TopEnd)) { PinBadge() }
        }
        Text(folder.name, Modifier.padding(top = StySize.space2), color = sty.ink, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
        Text(if (total == 1) "1 file" else "$total files", color = sty.inkMuted, style = MaterialTheme.typography.bodySmall)
    }
    picked?.let { SelectTab(it, Modifier.align(Alignment.TopCenter)) }
    }
}

/** The tab hung from the top edge of a note in select mode: an angular notch, filled honey (with a tick) once picked. */
@Composable
private fun SelectTab(picked: Boolean, modifier: Modifier) {
    val sty = LocalSty.current
    Canvas(modifier.size(width = 76.dp, height = 16.dp)) {
        val w = size.width; val h = size.height; val slant = h * 0.9f; val r = h * 0.45f
        // The two lower corners are rounded: stop short of each, then curve through it.
        val tab = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(slant - r * 0.6f, h - r * 0.7f); quadraticTo(slant, h, slant + r, h)
            lineTo(w - slant - r, h); quadraticTo(w - slant, h, w - slant + r * 0.6f, h - r * 0.7f); lineTo(w, 0f); close()
        }
        drawPath(tab, if (picked) sty.honey else sty.surface)
        drawPath(tab, if (picked) sty.honey else sty.borderStrong, style = Stroke(2.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        if (picked) {
            val c = Offset(w / 2, h * 0.5f); val u = h * 0.22f
            drawLine(sty.onHoney, Offset(c.x - 2 * u, c.y), Offset(c.x - 0.5f * u, c.y + 1.5f * u), 2.5.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(sty.onHoney, Offset(c.x - 0.5f * u, c.y + 1.5f * u), Offset(c.x + 2.2f * u, c.y - 1.5f * u), 2.5.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/**
 * The thing being dragged, picked up like an icon on a phone: it grows a little, throws a shadow, and follows the
 * finger on a spring so it trails behind and swings a few degrees in the direction of travel.
 */
@Composable
private fun DragGhost(drag: DragState, d: Dragged) {
    val sty = LocalSty.current
    val target = drag.pos - drag.grab
    val at by androidx.compose.animation.core.animateOffsetAsState(target, androidx.compose.animation.core.spring(0.65f, 220f), label = "trail")
    var lifted by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { lifted = true }
    val grow by androidx.compose.animation.core.animateFloatAsState(
        if (lifted) 1.1f else 1f, androidx.compose.animation.core.spring(0.5f, 400f), label = "lift",
    )
    val swing = ((target.x - at.x) * 0.1f).coerceIn(-12f, 12f) // it lags behind, so this leans into the move
    val width = with(androidx.compose.ui.platform.LocalDensity.current) { drag.size.width.toDp() }
    Box(
        Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.width(width)
            .graphicsLayer { scaleX = grow; scaleY = grow; rotationZ = swing; shadowElevation = 36f; shape = SquircleShape(StySize.radiusLg); clip = false },
    ) {
        val look = d.preview
        if (look != null) look()
        else Text(d.label, Modifier.clip(SquircleShape(StySize.radiusMd)).background(sty.honey).padding(horizontal = StySize.space3, vertical = StySize.space2), color = sty.onHoney, maxLines = 1)
    }
}

/** A tiny picture of the file: its first page for ink, text lines for Markdown, a page with a tag for a PDF. */
@Composable
internal fun Cover(file: NoteFile, modifier: Modifier) {
    val sty = LocalSty.current
    val p = file.notebook?.pages?.first()?.page
    val context = LocalContext.current
    val version = Covers.version(file)
    // Made after the cover first appears (a blank page needs none), then kept until the page changes.
    val picture by produceState<Covers.Pyramid?>(null, file, version) { value = Covers.of(context, file) }
    Canvas(modifier) {
        val aspect = if (p == null) 0.75f else if (p.infinite) 1f else p.width.toFloat() / p.height
        val h = minOf(size.height, size.width / aspect)
        val w = h * aspect
        val x0 = (size.width - w) / 2; val y0 = (size.height - h) / 2
        drawRect(Color.White, Offset(x0, y0), androidx.compose.ui.geometry.Size(w, h))
        drawRect(sty.border, Offset(x0, y0), androidx.compose.ui.geometry.Size(w, h), style = Stroke(1f))
        val pic = picture?.pick(w.roundToInt())
        if (pic != null) {
            drawImage(pic, dstOffset = androidx.compose.ui.unit.IntOffset(x0.roundToInt(), y0.roundToInt()), dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt(), h.roundToInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
            drawRect(sty.border, Offset(x0, y0), androidx.compose.ui.geometry.Size(w, h), style = Stroke(1f))
            return@Canvas
        }
        val step = h / 14
        val ruling = Color(0xFFC9D3E6) // the paper is white in both themes, so its lines are too
        when {
            p == null -> { // Markdown or PDF: grey bars like a page of text
                var y = y0 + step * 2; var i = 0
                while (y < y0 + h - step) { drawLine(Color(0xFFB8B8B8), Offset(x0 + step, y), Offset(x0 + w - step * (if (i % 3 == 2) 4 else 1), y), 3f); y += step; i++ }
            }
            p.template == Template.TEMPLATE_LINED -> { var y = y0 + step * 2; while (y < y0 + h) { drawLine(ruling, Offset(x0, y), Offset(x0 + w, y)); y += step } }
            p.template == Template.TEMPLATE_GRID -> {
                var y = y0 + step; while (y < y0 + h) { drawLine(ruling, Offset(x0, y), Offset(x0 + w, y)); y += step }
                var x = x0 + step; while (x < x0 + w) { drawLine(ruling, Offset(x, y0), Offset(x, y0 + h)); x += step }
            }
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

/** Pick a folder; [exclude] and what is inside it are left out (a folder cannot move into itself). */
@Composable
private fun MoveDialog(lib: Library, exclude: Folder?, onDismiss: () -> Unit, onPick: (Folder) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Move to which folder?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { folderChoices(lib, exclude).forEach { (f, label) -> TextButton({ onPick(f) }) { Text(label.replace("    ", " ")) } } } },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ViewOptionsDialog(ui: LibUi, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone, title = { Text("View options") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StySize.space2)) {
                Chips("File size", Size3.entries.map { it.label }, ui.noteSize.ordinal) { ui.noteSize = Size3.entries[it] }
                Chips("Folder size", Size3.entries.map { it.label }, ui.folderSize.ordinal) { ui.folderSize = Size3.entries[it] }
                Chips("Sort files by", listOf("Name", "Last opened", "Created"), ui.sort.ordinal) { ui.sort = Sort.entries[it] }
            }
        },
        confirmButton = { Button(onDone) { Text("Done") } },
    )
}

private fun templateName(t: Template) = when (t) { Template.TEMPLATE_LINED -> "Lined"; Template.TEMPLATE_GRID -> "Grid"; else -> "Blank" }

/** A new ink file: name, folder, and the first page's starting look (each page can change it later). */
@Composable
private fun NewFileDialog(lib: Library, start: Folder, onDismiss: () -> Unit, onCreate: (String, Folder, NewFileLook) -> Unit) {
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(start) }
    var picking by remember { mutableStateOf(false) }
    var look by remember { mutableStateOf(NewFileLook()) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("New file") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(StySize.space2)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Folder", color = LocalSty.current.inkMuted, style = MaterialTheme.typography.labelMedium)
                Box {
                    TextButton({ picking = true }) { Text(lib.path(folder).joinToString(" / ").ifEmpty { "Tabularium (top level)" }) }
                    DropdownMenu(picking, { picking = false }) {
                        folderChoices(lib).forEach { (f, label) -> DropdownMenuItem({ Text(label.replace("    ", " ")) }, { folder = f; picking = false }) }
                    }
                }
                Chips("Paper", listOf("A4", "Letter"), if (look.letter) 1 else 0) { look = look.copy(letter = it == 1) }
                Chips("Orientation", listOf("Portrait", "Landscape"), if (look.landscape) 1 else 0) { look = look.copy(landscape = it == 1) }
                Chips("Pages", listOf("Fixed", "Infinite canvas"), if (look.infinite) 1 else 0) { look = look.copy(infinite = it == 1) }
                val templates = listOf(Template.TEMPLATE_BLANK, Template.TEMPLATE_LINED, Template.TEMPLATE_GRID)
                Chips("Template", templates.map(::templateName), templates.indexOf(look.template)) { look = look.copy(template = templates[it]) }
            }
        },
        confirmButton = { Button({ onCreate(name, folder, look) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun Chips(label: String, options: List<String>, chosen: Int, special: Boolean = false, onPick: (Int) -> Unit) {
    val sty = LocalSty.current
    // A special (important) setting picks in solid honey, so it stands out from the ordinary ones.
    val colours = if (special) FilterChipDefaults.filterChipColors(selectedContainerColor = sty.honey, selectedLabelColor = sty.onHoney) else FilterChipDefaults.filterChipColors()
    Text(label, color = LocalSty.current.inkMuted, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StySize.space2)) {
        options.forEachIndexed { i, o -> FilterChip(i == chosen, { onPick(i) }, { Text(o) }, Modifier.heightIn(min = StySize.touch), colors = colours) }
    }
}
