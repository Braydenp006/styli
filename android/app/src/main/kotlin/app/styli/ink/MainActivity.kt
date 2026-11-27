package app.styli.ink

import android.os.Bundle
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlinx.coroutines.delay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import androidx.compose.animation.togetherWith
import app.styli.ink.proto.PageSet
import app.styli.ink.proto.Template

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StyliTheme { Root() } }
    }

    // The pen's side button is read here, before any view sees the event, so it works while the pen hovers too.
    // The pen button's own events (and key codes) are kept from the system, so Android does not pop up its own pen menu over the page.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean { PenButton.track(ev); return super.dispatchTouchEvent(ev) }
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean = if (PenButton.track(ev)) true else super.dispatchGenericMotionEvent(ev)
    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean = if (PenButton.isStylusKey(ev.keyCode)) true else super.dispatchKeyEvent(ev)
}

/** The Library first; opening a file shows its pages on the canvas, and Back returns to the Library. */
@Composable
private fun Root() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var allowed by remember { mutableStateOf(Vault.hasAccess()) }
    // Coming back from the system's permission screen: look again.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = Vault.hasAccess() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (!allowed) { AccessScreen(); return }
    // Where the notes live: chosen at the first start, and changeable in Settings. [picked] is a folder just chosen that waits for a yes.
    var rootDir by remember { mutableStateOf(Storage.saved(context)) }
    var picked by remember { mutableStateOf<File?>(null) }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val dir = Storage.fromTree(uri)
            when {
                dir == null -> Toast.makeText(context, "Pick a folder on this device or its SD card (not a cloud drive)", Toast.LENGTH_LONG).show()
                rootDir == null -> { Storage.save(context, dir); rootDir = dir } // the first start: just use it
                else -> picked = dir
            }
        }
    }
    val root = rootDir ?: run {
        StartScreen(onDefault = { Storage.default().also { Storage.save(context, it); rootDir = it } }, onChoose = { pickFolder.launch(null) })
        return
    }
    val vault = remember(root) { Vault(root) }
    val library = remember(vault) {
        val fresh = !root.exists()
        vault.load().also {
            // The very first vault, in the default place, gets some made-up folders and notes to try things with (throwaway, like SampleLibrary).
            if (fresh && root == Storage.default() && it.root.folders.isEmpty() && it.root.files.isEmpty() && it.trash.isEmpty() && vault.problems.isEmpty()) { it.addSamples(); vault.save(it) }
        }
    }
    val settings = remember(vault) { AppSettings().also { s -> vault.readSettings()?.let(s::restore) } }
    val shelf = remember(vault) { vault.loadClips() }
    // Save about a second after anything changes, and always when the app is left.
    androidx.compose.runtime.LaunchedEffect(library) {
        var told = emptyList<String>()
        var lastSettings = settings.snapshot()
        var lastShelf = shelf.version
        while (true) {
            kotlinx.coroutines.delay(1000)
            settings.snapshot().let { if (it != lastSettings) { vault.writeSettings(it); lastSettings = it } }
            if (shelf.version != lastShelf) { vault.saveClips(shelf); lastShelf = shelf.version }
            if (vault.dirty(library)) {
                val now = vault.save(library)
                if (now.isNotEmpty() && now != told) android.widget.Toast.makeText(context, "Not saved: " + now.first(), android.widget.Toast.LENGTH_LONG).show()
                told = now
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(owner, library) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { vault.save(library); vault.writeSettings(settings.snapshot()); vault.saveClips(shelf) } }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val ui = remember { LibUi() }
    var open by remember(vault) { mutableStateOf<NoteFile?>(null) }
    picked?.let { dest ->
        val fresh = !dest.exists() || dest.list()?.isEmpty() == true
        val bad = Storage.overlaps(dest, root)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(if (bad) "That folder will not work" else "Use ${Storage.shortName(dest)}?") },
            text = {
                Text(
                    when {
                        dest == root -> "That is the folder already in use."
                        bad -> "The new folder cannot be inside the current notes folder, or hold it."
                        fresh -> "It is empty. You can move your notes there (the old folder is kept as it is, so nothing is lost), or just start using the empty folder."
                        else -> "It already has files, so your notes are not copied into it. Styli will open what is in it. Your current notes stay where they are."
                    },
                )
            },
            confirmButton = {
                if (!bad && dest != root) {
                    if (fresh) Button({ switchFolder(context, vault, library, settings, shelf, dest, true) { rootDir = dest; picked = null } }) { Text("Move my notes there") }
                    else Button({ switchFolder(context, vault, library, settings, shelf, dest, false) { rootDir = dest; picked = null } }) { Text("Open that folder") }
                }
            },
            dismissButton = {
                Row {
                    if (!bad && dest != root && fresh) TextButton({ switchFolder(context, vault, library, settings, shelf, dest, false) { rootDir = dest; picked = null } }) { Text("Start with an empty folder") }
                    TextButton({ picked = null }) { Text("Cancel") }
                }
            },
        )
    }
    // Opening a note grows it in from slightly smaller; closing it shrinks away and the Tabularium settles back in.
    androidx.compose.animation.AnimatedContent(
        targetState = open,
        transitionSpec = {
            val ease = androidx.compose.animation.core.FastOutSlowInEasing
            if (targetState != null) {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280, 60, ease)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(340, 0, ease), initialScale = 0.92f)) togetherWith
                    (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220, 0, ease)) + androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(340, 0, ease), targetScale = 1.05f))
            } else {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280, 60, ease)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(340, 0, ease), initialScale = 1.05f)) togetherWith
                    (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220, 0, ease)) + androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(340, 0, ease), targetScale = 0.92f))
            }
        },
        label = "open or close a note",
    ) { file ->
        if (file == null) LibraryScreen(
            library, ui, settings,
            StorageControls(Storage.shortName(root), root == Storage.default(), { pickFolder.launch(null) }, { if (root != Storage.default()) picked = Storage.default() }),
        ) { open = it }
        else {
            androidx.activity.compose.BackHandler { open = null }
            // A new canvas view for each file, so it starts on that file's own pages.
            androidx.compose.runtime.key(file) { InkScreen(file, settings, shelf) { open = null } }
        }
    }
}

/** Moves to [dest]: everything is saved first, the notes are copied if asked (the old folder stays as it was), then the app opens the new folder. */
private fun switchFolder(context: android.content.Context, vault: Vault, library: Library, settings: AppSettings, shelf: ClipShelf, dest: File, move: Boolean, done: () -> Unit) {
    vault.save(library); vault.writeSettings(settings.snapshot()); vault.saveClips(shelf)
    if (move) vault.copyTo(dest)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); return }
    Storage.save(context, dest)
    done()
}

/** The first start: where should the notes go? */
@Composable
private fun StartScreen(onDefault: () -> Unit, onChoose: () -> Unit) {
    val sty = LocalSty.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(sty.bg).padding(StySize.space6), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text("Where should your notes be kept?", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your notes are ordinary files, so any app or computer can open and copy them, and they stay if Styli is removed. " +
                "You can pick another folder later, in Settings.",
            Modifier.padding(vertical = StySize.space4), color = sty.inkMuted,
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StySize.space3)) {
            Button(onDefault) { Text("Use Documents/Tabularium") }
            OutlinedButton(onChoose) { Text("Choose another folder") }
        }
    }
}

/** Shown until the app may keep its notes in Documents/Tabularium (the notes must stay if the app is removed). */
@Composable
private fun AccessScreen() {
    val sty = LocalSty.current
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(sty.bg).padding(StySize.space6), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text("Where your notes live", color = sty.ink, fontFamily = DisplayFont, style = MaterialTheme.typography.headlineLarge)
        Text(
            "Styli keeps your notes as ordinary files in Documents/Tabularium, so any app or computer can open and copy them, and they stay if Styli is removed. " +
                "To do that it needs the \"all files\" permission. Styli only ever writes inside that one folder.",
            Modifier.padding(vertical = StySize.space4), color = sty.inkMuted,
        )
        Button({ Vault.openAccessSettings(context) }) { Text("Allow access to files") }
    }
}

// Phase 0a prototype only: not styled with the Styli design system on purpose.
@Composable
private fun InkScreen(file: NoteFile, settings: AppSettings, shelf: ClipShelf, onLibrary: () -> Unit) {
    val notebook = file.notebook!!
    var latency by remember { mutableStateOf("Draw to measure latency") }
    var canvas by remember { mutableStateOf<InkCanvasView?>(null) }
    var infinite by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(Template.TEMPLATE_LINED) }
    var letter by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(NoteView.tool(file.view) ?: InkCanvasView.Tool.PEN) } // the tool this note was left on
    var units by remember { mutableStateOf(RulerUnits.METRIC) }
    var erasing by remember { mutableStateOf(emptySet<EraseOnly>()) }
    var shade by remember { mutableStateOf(0) }
    var thick by remember { mutableStateOf(0) }
    // Penmanship, each 0 off, 1 low, 2 medium, 3 high (hold time: 0.3, 0.5, 0.8, 1.2 s)
    var pLines by settings::pLines
    var pShapes by settings::pShapes
    var pLevel by settings::pLevel
    var pHold by settings::pHold
    var solid by settings::solid
    var gridScroll by settings::gridScroll
    var tableDialog by remember { mutableStateOf(false) }
    var tRows by remember { mutableStateOf(3) }
    var tCols by remember { mutableStateOf(3) }
    var tColor by remember { mutableStateOf(0) }
    var tFill by remember { mutableStateOf(0) }
    var tLine by remember { mutableStateOf(1) }
    var sColor by remember { mutableStateOf(0) }
    var sFill by remember { mutableStateOf(0) }
    var sLine by remember { mutableStateOf(1) }
    var sDashed by remember { mutableStateOf(false) }
    var bColor by remember { mutableStateOf(0x99FFEB3B.toInt()) }
    var barDrag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) } // where the selection bar was dragged to
    var popDrag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) } // and the rail's popout
    var viewLocked by remember { mutableStateOf(false) } // fingers do not scroll or zoom while on
    var open by remember { mutableStateOf<String?>(null) } // the rail's popout menu that is showing
    var sheetOpen by remember { mutableStateOf(false) } // the converted text under the page
    var full by remember { mutableStateOf(false) } // full screen: only the page, and a button to come back
    var ink by remember { mutableStateOf(settings.penColor) }
    var hlInk by remember { mutableStateOf(settings.highlighterColor) }
    var penW by remember { mutableStateOf(settings.penWidth) }
    var hlW by remember { mutableStateOf(settings.highlighterWidth) }
    var selPanel by remember { mutableStateOf<String?>(null) } // which settings panel is open under the selection
    var sel by remember { mutableStateOf<InkCanvasView.SelectionInfo?>(null) } // what the lasso holds, for the bar under it
    val hereState = remember { mutableStateOf<PageModel?>(null) } // the page in the middle of the screen (read only by the page bar, so scrolling does not redo this whole screen)
    val pagesState = remember { mutableStateOf(emptyList<PageModel>()) } // the pages in reading order
    val zoomState = remember { androidx.compose.runtime.mutableIntStateOf(100) } // read only by the zoom pill, so a zoom does not redo this whole screen
    var angleOn by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf(Route.STRAIGHT) }
    var auto by remember { mutableStateOf(false) }
    var startHead by remember { mutableStateOf(Arrowhead.NONE) }
    var endHead by remember { mutableStateOf(Arrowhead.FILLED) }
    var preset by remember { mutableStateOf(ShapePreset.RECTANGLE) }
    var scroll by settings::scroll
    var cell by remember { mutableStateOf(0 to 0) } // the page's (column, row)
    var picking by remember { mutableStateOf(0) } // 0 none, 1 text colour, 2 highlight
    var textColor by remember { mutableStateOf(0) }
    var textHighlight by remember { mutableStateOf(0) }
    var clips by remember { mutableStateOf(shelf.clips) }
    var placing by remember { mutableStateOf<Clip?>(null) } // the clip waiting for a tap
    var naming by remember { mutableStateOf<Clip?>(null) } // the clip being named
    var nameText by remember { mutableStateOf("") }

    val context = LocalContext.current
    var tableEdit by remember { mutableStateOf<Table?>(null) } // the table whose cells are being typed
    var chartEdit by remember { mutableStateOf<Pair<Chart, Boolean>?>(null) } // the chart whose data is being typed, and whether it is new
    // The system photo picker: no storage permission is needed.
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val data = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(PictureStore.MAX_BYTES + 1) }
            val ok = data != null && data.size <= PictureStore.MAX_BYTES &&
                canvas?.insertPicture(data, PictureDecoder.extension(context.contentResolver.getType(uri))) == true
            if (!ok) Toast.makeText(context, if (data != null && data.size > PictureStore.MAX_BYTES) "That picture is over 25 MB, so it was not added" else "That file could not be read as a picture", Toast.LENGTH_LONG).show()
        }
    }

    // The camera: the phone's own camera app takes the photo into a file made for it, which is read and then deleted.
    var photoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = photoUri
        photoUri = null
        if (uri != null) {
            if (taken) {
                val data = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(PictureStore.MAX_BYTES + 1) }
                if (data == null || data.size > PictureStore.MAX_BYTES || canvas?.insertPicture(data, "jpg") != true)
                    Toast.makeText(context, "That photo could not be added", Toast.LENGTH_LONG).show()
            }
            context.contentResolver.delete(uri, null, null)
        }
    }
    fun openCamera() {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "styli-photo-${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) Toast.makeText(context, "The camera could not be opened", Toast.LENGTH_LONG).show()
        else { photoUri = uri; takePhoto.launch(uri) }
    }

    fun apply() {
        val (w, h) = PageSizes.of(letter, landscape)
        canvas?.setPage(PageSet.newBuilder().setWidth(w).setHeight(h).setTemplate(template).setInfinite(infinite).build())
    }

    Box(Modifier.fillMaxSize()) {
        val canvasView: @Composable (Modifier) -> Unit = { mod -> AndroidView(
            modifier = mod,
            factory = { context ->
                InkCanvasView(context, notebook).also {
                    it.shelf = shelf
                    settings.applyTo(it, file)
                    it.onLatency = { text -> latency = text }
                    it.onZoom = { percent -> zoomState.intValue = percent }
                    it.onPlacing = { c -> placing = c }
                    it.onToolChanged = { t -> tool = t }
                    it.onChartEdit = { c, isNew -> chartEdit = c to isNew }
                    it.onTableEdit = { t -> tableEdit = t }
                    it.onPageChanged = {
                        // The page buttons show the page that is now current.
                        val p = it.page
                        infinite = p.infinite; template = p.template; letter = PageSizes.isLetter(p) == true; landscape = PageSizes.isLandscape(p)
                        cell = p.col to p.row
                    }
                    canvas = it
                    // Show the opened file's own first page in the page buttons (the file already has its look).
                    val p = it.page
                    infinite = p.infinite; template = p.template; letter = PageSizes.isLetter(p) == true; landscape = PageSizes.isLandscape(p)
                    cell = p.col to p.row
                }
            },
        ) }
        val gap = Modifier.padding(start = 8.dp)
        @Composable fun ColumnScope.toolsMenu() {
            Menu("Tool: " + toolName(tool)) { close ->
                for (t in InkCanvasView.Tool.entries) Choice(toolName(t), t == tool) {
                    tool = t
                    canvas?.tool = t
                    if (t == InkCanvasView.Tool.TABLE) tableDialog = true // ask for the rows and columns first
                    close()
                }
            }
        }
        @Composable fun ColumnScope.toolOptions() {
            if (tool == InkCanvasView.Tool.ERASER_STROKE || tool == InkCanvasView.Tool.ERASER_PART) {
                Menu("Erase only") { _ ->
                    CheckRow("Everything", erasing.isEmpty()) { erasing = emptySet(); canvas?.eraseOnly = erasing }
                    for (e in EraseOnly.entries.drop(1)) CheckRow(eraseLabel(e), e in erasing) {
                        erasing = if (e in erasing) erasing - e else erasing + e
                        canvas?.eraseOnly = erasing
                    }
                }
            }
            if (tool == InkCanvasView.Tool.SHAPE) {
                PopHeading("Shape")
                for (row in ShapePreset.entries.chunked(4)) Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (p in row) ShapeTile(p, p == preset) { preset = p; canvas?.shapePreset = p; open = null }
                }
            }
            if (tool == InkCanvasView.Tool.TABLE) {
                Item("Table: $tRows rows, $tCols columns") { tableDialog = true }
            }
            if (tool == InkCanvasView.Tool.CONNECTOR) {
                Menu("Route: " + routeLabel(route)) { close ->
                    for (r in Route.entries) Choice(routeLabel(r), r == route) { route = r; canvas?.connectorRoute = r; close() }
                }
                Menu("Attach: " + if (auto) "aim at other end" else "exact spot") { close ->
                    Choice("exact spot (snaps to side middles)", !auto) { auto = false; canvas?.connectAuto = false; close() }
                    Choice("aim at the other end", auto) { auto = true; canvas?.connectAuto = true; close() }
                }
                Menu("Start: " + startHead.name.lowercase()) { close ->
                    for (h in Arrowhead.entries) Choice(h.name.lowercase(), h == startHead) { startHead = h; canvas?.connectorFromHead = h; close() }
                }
                Menu("End: " + endHead.name.lowercase()) { close ->
                    for (h in Arrowhead.entries) Choice(h.name.lowercase(), h == endHead) { endHead = h; canvas?.connectorToHead = h; close() }
                }
            }
            if (tool == InkCanvasView.Tool.BUCKET) {
                Item("Fill colour") { picking = 7 }
            }
            if (tool == InkCanvasView.Tool.TEXT) {
                Item("Text colour") { picking = 1 }
                Item("Highlight") { picking = 2 }
                Item("Done") { canvas?.finishText() }
            }


        }
        @Composable fun ColumnScope.inkMenu() {
            Menu("Ink") { close ->
                Heading("Colour")
                Item("Pen colour…") { picking = 8; close() } // any colour, with the eyedropper
                Item("Highlighter colour…") { picking = 9; close() }
                for (k in 0..2) Choice("Colour ${k + 1}", shade == k) {
                    shade = k
                    canvas?.apply {
                        penColor = listOf(0xFF000000.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt())[k]
                        highlighterColor = listOf(0x99FFEB3B.toInt(), 0x9966BB6A.toInt(), 0x99F06292.toInt())[k]
                    }
                    close()
                }
                Heading("Width")
                for (k in 0..2) Choice(listOf("Fine", "Medium", "Broad")[k], thick == k) {
                    thick = k
                    canvas?.apply {
                        penWidth = listOf(40, 80, 160)[k]
                        highlighterWidth = listOf(400, 700, 1000)[k]
                    }
                    close()
                }
            }

        }
        // What the lasso holds: each of these shows in a panel under the selection, only when it applies.
        @Composable fun ColumnScope.layersMenu() {
            // Two by two: forward and to front above, backward and to back below.
            val close = LocalClose.current
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                OptionChip("To front", Icons24.TO_FRONT, false) { canvas?.arrange(Arrange.FRONT) }
                OptionChip("Forward", Icons24.FORWARD, false) { canvas?.arrange(Arrange.FORWARD) }
            }
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                OptionChip("Backward", Icons24.BACKWARD, false) { canvas?.arrange(Arrange.BACKWARD) }
                OptionChip("To back", Icons24.TO_BACK, false) { canvas?.arrange(Arrange.BACK) }
            }
            close.hashCode()
        }
        @Composable fun ColumnScope.textMenu() {
            Menu("Text") { _ ->
                Item("Text colour") { picking = 1 }
                Item("Highlight") { picking = 2 }
            }
        }
        @Composable fun ColumnScope.shapeMenu() {
            val weights = listOf("Thin", "Normal", "Thick", "Bold")
            CycleRow("Line weight", listOf(0, 1, 2, 3), sLine, { weights[it] }, { null }) { sLine = it; canvas?.setShapeStyle(null, null, listOf(20f, 40f, 80f, 160f)[it], null) }
            CycleRow("Line style", listOf(false, true), sDashed, { if (it) "Dashed" else "Solid" }, { null }) { sDashed = it; canvas?.setShapeStyle(null, null, null, it) }
            Item("Outline colour") { picking = 5 } // the fill is the paint bucket's job
        }
        @Composable fun ColumnScope.connectorMenu() {
            val info = sel
            CycleRow("Line route", Route.entries, info?.route ?: Route.STRAIGHT, { routeLabel(it).replaceFirstChar { c -> c.uppercase() } }, { routeIcon(it) }) { canvas?.routeSelected(it) }
            CycleRow("Start of the line", Arrowhead.entries, info?.fromHead ?: Arrowhead.NONE, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { headIcon(it) }) { canvas?.setConnectorHeads(it, null) }
            CycleRow("End of the line", Arrowhead.entries, info?.toHead ?: Arrowhead.FILLED, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { headIcon(it) }) { canvas?.setConnectorHeads(null, it) }
            if (info?.route != null) { // one connector on its own: its bend points
                PopHeading("Bend points")
                Item("Add a bend point (then tap the line)") { canvas?.addBendPoint() }
                if (info.hasVia) Item("Remove the last bend point") { canvas?.removeBendPoint() }
            }
        }
        @Composable fun ColumnScope.tableMenu() {
            val weights = listOf("Thin", "Normal", "Thick", "Bold")
            PopHeading("Rows and columns (tap a cell first)")
            PairRow("Row above / below", "Row above", Icons24.UP, { canvas?.insertRow(false) }, "Row below", Icons24.DOWN, { canvas?.insertRow(true) })
            PairRow("Column left / right", "Column left", Icons24.BACK, { canvas?.insertColumn(false) }, "Column right", Icons24.NEXT, { canvas?.insertColumn(true) })
            PairRow("Delete row / column", "Delete row", Icons24.DELETE, { canvas?.deleteRow() }, "Delete column", Icons24.DELETE, { canvas?.deleteColumn() }, enabled = sel?.cell == true)
            if (sel?.cell != true) Text("Tap a cell with the pen to choose which row and column to delete.", Modifier.padding(horizontal = 16.dp), color = LocalSty.current.inkMuted, fontSize = 12.sp)
            CycleRow("Line weight", listOf(0, 1, 2, 3), tLine, { weights[it] }, { null }) { tLine = it; canvas?.setTableStyle(null, null, listOf(20f, 40f, 80f, 160f)[it]) }
            Item("Lines colour") { picking = 3 }
            Item("Fill colour (whole table)") { picking = 4 }
        }
        @Composable fun ColumnScope.clipMenu() {
            // Saved groups of things: save what the lasso has selected, then put a copy on any page.
            val close = LocalClose.current
            var actionFor by remember { mutableStateOf<Clip?>(null) }
            PopHeading("Clipboard")
            Item("Save the selection as a clip") {
                canvas?.saveSelection()?.let { clips = canvas!!.shelf.clips; naming = it; nameText = it.name }
                close()
            }
            PopHeading(if (clips.isEmpty()) "Nothing saved yet. Lasso something, then save it." else "Tap a clip, then tap the page with the pen. Hold one to rename or delete it.")
            for (row in clips.chunked(3)) Row(Modifier.padding(horizontal = 12.dp)) {
                for (c in row) ClipTile(c, { sha -> canvas?.pictureThumb(sha) }, chosen = actionFor === c, onTap = { canvas?.placeClip(c); close() }, onHold = { actionFor = c })
            }
            actionFor?.let { c ->
                PopHeading(c.name)
                Item("Rename") { naming = c; nameText = c.name; close() }
                Item("Delete this clip") { canvas?.shelf?.remove(c); clips = canvas!!.shelf.clips; actionFor = null }
            }
        }
        @Composable fun ColumnScope.rulerMenu() {
            Menu("Ruler") { close ->
                Choice(if (canvas?.ruler != null) "Hide ruler" else "Show ruler", false) { canvas?.toggleRuler(); close() }
                Choice("Angle shown on lines", angleOn) { angleOn = !angleOn; canvas?.lineAngle = angleOn; close() }
                Heading("Units")
                for (u in RulerUnits.entries) Choice(u.name.lowercase(), u == units) { units = u; canvas?.rulerUnits = u; close() }
            }

        }
        @Composable fun ColumnScope.assistMenu() {
            // Hold the pen still at the end of a stroke and a line or shape is drawn clean. These stay open to change several.
            Menu("Pen assist") { _ ->
                val strengths = listOf(0f, 0.25f, 1f, 2.5f)
                val names = listOf("off", "low", "medium", "high")
                fun penmanship() {
                    canvas?.penmanship = ShapeGeometry.Settings(
                        strengths[pLines], strengths[pShapes], strengths[pLevel], listOf(300L, 500L, 800L, 1200L)[pHold],
                    )
                }
                Item("Lines: ${names[pLines]}") { pLines = (pLines + 1) % 4; penmanship() }
                Item("Shapes: ${names[pShapes]}") { pShapes = (pShapes + 1) % 4; penmanship() }
                Item("Level and upright: ${names[pLevel]}") { pLevel = (pLevel + 1) % 4; penmanship() }
                Item("Hold: ${listOf("0.3", "0.5", "0.8", "1.2")[pHold]} s") { pHold = (pHold + 1) % 4; penmanship() }
            }

        }
        @Composable fun ColumnScope.pageMenu() {
            Item("Undo page change") { canvas?.undoPage() }
            Item("Redo page change") { canvas?.redoPage() }
            Menu("Page") { _ ->
                Item(if (infinite) "Canvas (tap for page)" else "Page (tap for canvas)") { infinite = !infinite; apply() }
                Item("Paper: " + template.name.removePrefix("TEMPLATE_").lowercase()) {
                    template = when (template) {
                        Template.TEMPLATE_BLANK -> Template.TEMPLATE_LINED
                        Template.TEMPLATE_LINED -> Template.TEMPLATE_GRID
                        else -> Template.TEMPLATE_BLANK
                    }
                    apply()
                }
                Item("Size: " + if (letter) "Letter" else "A4") { letter = !letter; apply() }
                Item("Orientation: " + if (landscape) "landscape" else "portrait") { landscape = !landscape; apply() }
                Item("Scrolling: " + if (gridScroll) "grid (pull past the end to add a page)" else "seamless (no adding pages)") {
                    gridScroll = !gridScroll; canvas?.scrollMode = if (gridScroll) ScrollMode.GRID else ScrollMode.SEAMLESS
                }
                Item("Scroll glide: " + listOf("off", "low", "medium", "high")[scroll]) {
                    scroll = (scroll + 1) % 4; canvas?.scrollIntensity = listOf(0.0, 0.5, 1.0, 2.0)[scroll]
                }
                Item("Selection outline: " + if (solid) "solid" else "dashed") { solid = !solid; canvas?.solidOutline = solid }
            }
        }
        val sty = LocalSty.current
        val side = RailSide.entries[settings.rail]
        val btnRects = remember { HashMap<String, androidx.compose.ui.geometry.Rect>() } // where each rail button is on screen, so a popout can come out of its own button
        var boxRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        val groups = mapOf(
            "pen" to listOf(Tool.PEN, Tool.DOTTED), "highlighter" to listOf(Tool.HIGHLIGHTER, Tool.LINE_HIGHLIGHTER),
            "eraser" to listOf(Tool.ERASER_PART, Tool.ERASER_STROKE), "line" to listOf(Tool.LINE, Tool.LINE_DOTTED),
            "lasso" to listOf(Tool.LASSO), "text" to listOf(Tool.TEXT), "shape" to listOf(Tool.SHAPE), "table" to listOf(Tool.TABLE),
            "connector" to listOf(Tool.CONNECTOR), "bucket" to listOf(Tool.BUCKET), "chart" to listOf(Tool.CHART), "laser" to listOf(Tool.LASER),
        )
        val noPopout = setOf("chart", "laser")
        fun choose(t: InkCanvasView.Tool) {
            tool = t; canvas?.tool = t
            if (t == Tool.TABLE) tableDialog = true // ask for the rows and columns first
        }
        // The pen's side button: a click does one thing, holding it makes the pen another tool until it is let go.
        val penClickAction = PenAction.entries[settings.penClick]
        val penHoldAction = PenAction.entries[settings.penHold]
        val buttonState = remember { longArrayOf(0L, -1L) } // when it went down; the tool to give back (ordinal), or -1
        androidx.compose.runtime.SideEffect {
            PenButton.listener = { down ->
                val now = android.os.SystemClock.uptimeMillis()
                if (down) {
                    buttonState[0] = now
                    penHoldAction.tool?.let { buttonState[1] = tool.ordinal.toLong(); choose(it) }
                } else {
                    if (buttonState[1] >= 0) { choose(InkCanvasView.Tool.entries[buttonState[1].toInt()]); buttonState[1] = -1 }
                    if (now - buttonState[0] < 450) when (penClickAction) {
                        PenAction.NONE -> {}
                        PenAction.PREVIOUS -> canvas?.previousTool?.let { choose(it) }
                        PenAction.UNDO -> canvas?.undo()
                        PenAction.REDO -> canvas?.redo()
                        else -> penClickAction.tool?.let { choose(it) }
                    }
                }
            }
        }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { PenButton.listener = null } }
        fun setInk(highlight: Boolean, argb: Int) {
            shade = -1
            if (highlight) { hlInk = (argb and 0xFFFFFF) or 0x99000000.toInt(); canvas?.highlighterColor = hlInk } // see-through, so the writing shows
            else { ink = argb; canvas?.penColor = argb }
        }
        // A tool button: another tool is chosen; the one already chosen opens (or closes) its popout.
        fun toolButton(key: String, d: SidebarItems.Def): RailEntry {
            val group = groups.getValue(key)
            return RailEntry.Btn(d.label, d.icon, tool in group, key) {
                if (tool in group && key !in noPopout) open = if (open == key) null else key
                else { if (tool !in group) choose(group[0]); open = if (key == "shape") key else null } // the shape tool asks which shape at once
            }
        }
        fun menuButton(label: String, icon: String, key: String, chosen: Boolean = false) = RailEntry.Btn(label, icon, chosen || open == key, key) { open = if (open == key) null else key }
        val entries = settings.sidebar.mapNotNull { key ->
            val d = SidebarItems.def(key) ?: return@mapNotNull null
            when {
                key == "sep" -> RailEntry.Sep
                key == "gap" -> RailEntry.Gap
                key in groups -> toolButton(key, d)
                key.startsWith("ink") -> settings.inkSlots[key.removePrefix("ink").toInt()].let { c ->
                    // Tap one to use it; tap the one in use to change what colour it is.
                    RailEntry.Ink(c, ink == c, key) {
                        if (ink == c) open = if (open == key) null else key
                        else { setInk(false, c); if (tool == Tool.HIGHLIGHTER) setInk(true, c); open = null }
                    }
                }
                key == "undo" -> RailEntry.Btn(d.label, d.icon, false) { canvas?.undo() }
                key == "redo" -> RailEntry.Btn(d.label, d.icon, false) { canvas?.redo() }
                key == "lock" -> RailEntry.Btn(d.label, d.icon, viewLocked) { viewLocked = !viewLocked; canvas?.viewLocked = viewLocked }
                key == "image" -> RailEntry.Btn(d.label, d.icon, false) { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                key == "more" -> menuButton(d.label, d.icon, "more", tool in listOf(Tool.TABLE, Tool.SHAPE, Tool.BUCKET, Tool.CONNECTOR, Tool.CHART, Tool.LASER))
                else -> menuButton(d.label, d.icon, key)
            }
        }
        // The popout, coming out of the button that opened it.
        // Small menus are only as wide as they need; the big ones have room.
        fun popWidth(which: String): Modifier = when (which) {
            "eraser" -> Modifier.width(200.dp)
            "text" -> Modifier.width(190.dp)
            "shape" -> Modifier.width(312.dp)
            "table", "connector", "bucket", "lasso" -> Modifier.width(240.dp)
            else -> Modifier.widthIn(min = 240.dp, max = 340.dp)
        }
        val popout: @Composable (String) -> Unit = { which ->
            androidx.compose.ui.layout.Layout(
                content = {
                    Surface(
                        Modifier.offset { androidx.compose.ui.unit.IntOffset(popDrag.x.toInt(), popDrag.y.toInt()) }.pointerInput(Unit) { detectTapGestures { } }.then(popWidth(which)).heightIn(max = 760.dp),
                        shape = StyShapes.Lg, color = sty.surface, shadowElevation = 8.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, sty.border),
                    ) {
                        Box {
                            androidx.compose.runtime.CompositionLocalProvider(LocalClose provides { open = null }) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 8.dp)) {
                                when (which) {
                                    "pen" -> InkPopout(
                                        listOf(Tool.PEN, Tool.DOTTED), tool, { choose(it) },
                                        ink, { setInk(false, it) }, settings.penSaved, { canvas?.startEyedropper { c -> setInk(false, c) } },
                                        penW, listOf(15, 25, 40, 80, 160), 400, { penW = it; canvas?.penWidth = it },
                                    )
                                    "line" -> InkPopout(
                                        listOf(Tool.LINE, Tool.LINE_DOTTED), tool, { choose(it) },
                                        ink, { setInk(false, it) }, settings.penSaved, { canvas?.startEyedropper { c -> setInk(false, c) } },
                                        penW, listOf(15, 25, 40, 80, 160), 400, { penW = it; canvas?.penWidth = it },
                                        extra = { CheckRow("Show the angle (no snapping)", angleOn) { angleOn = !angleOn; canvas?.lineAngle = angleOn } },
                                    )
                                    "highlighter" -> InkPopout(
                                        listOf(Tool.HIGHLIGHTER, Tool.LINE_HIGHLIGHTER), tool, { choose(it) },
                                        hlInk, { setInk(true, it) }, settings.highSaved, { canvas?.startEyedropper { c -> setInk(true, c) } },
                                        hlW, listOf(300, 400, 600, 1000), 1200, { hlW = it; canvas?.highlighterWidth = it },
                                        extra = { if (tool == Tool.LINE_HIGHLIGHTER) CheckRow("Show the angle (no snapping)", angleOn) { angleOn = !angleOn; canvas?.lineAngle = angleOn } },
                                    )
                                    "ink0", "ink1", "ink2" -> {
                                        val i = which.removePrefix("ink").toInt()
                                        Column(Modifier.width(312.dp).padding(vertical = 8.dp)) {
                                            PopHeading("Colour of this swatch")
                                            ColorPicker(settings.inkSlots[i], { settings.inkSlots[i] = it; setInk(false, it) }, { canvas?.startEyedropper { c -> settings.inkSlots[i] = c; setInk(false, c) } })
                                            PopHeading("Saved colours")
                                            SavedColours(settings.penSaved, settings.inkSlots[i]) { settings.inkSlots[i] = it; setInk(false, it) }
                                        }
                                    }
                                    "eraser" -> {
                                        PopHeading("Eraser")
                                        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            for (t in listOf(Tool.ERASER_PART, Tool.ERASER_STROKE)) OptionChip(toolLabel(t), toolIcon(t), t == tool) { choose(t) }
                                        }
                                        toolOptions()
                                    }
                                    "lasso" -> {
                                        Menu("Selection outline") { _ ->
                                            Choice("Dashed", !solid) { solid = false; canvas?.solidOutline = false }
                                            Choice("Solid", solid) { solid = true; canvas?.solidOutline = true }
                                        }
                                        toolOptions()
                                        Text("Settings for what you select show in a bar under it.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = sty.inkMuted, fontSize = 12.sp)
                                    }
                                    "more" -> {
                                        // Only the tools that are not already on the sidebar.
                                        val onRail = settings.sidebar.flatMap { groups[it] ?: emptyList() }.toSet()
                                        val rest = InkCanvasView.Tool.entries.filter { it !in onRail }
                                        PopHeading("Other tools")
                                        if (rest.isEmpty()) Text("Every tool is on the sidebar.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = sty.inkMuted, fontSize = 13.sp)
                                        for (row in rest.chunked(4)) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            for (t in row) OptionChip(toolLabel(t), toolIcon(t), t == tool) { choose(t); open = if (t == Tool.SHAPE) "shape" else null }
                                        }
                                    }
                                    "clip" -> clipMenu()
                                    "ruler" -> rulerMenu()
                                    "assist" -> assistMenu()
                                    else -> toolOptions() // text, shape, table, connector, bucket
                                }
                            }
                        }
                            DragHandle(Modifier.align(Alignment.TopCenter)) { popDrag += it }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { ms, c ->
                val p = ms[0].measure(c.copy(minWidth = 0, minHeight = 0))
                val pad = 8.dp.roundToPx()
                val br = btnRects[which] ?: androidx.compose.ui.geometry.Rect.Zero
                layout(c.maxWidth, c.maxHeight) {
                    val x = when (side) {
                        RailSide.LEFT -> pad
                        RailSide.RIGHT -> c.maxWidth - p.width - pad
                        else -> (br.left - boxRect.left).toInt().coerceIn(pad, maxOf(pad, c.maxWidth - p.width - pad))
                    }
                    val y = when (side) {
                        RailSide.TOP -> pad
                        RailSide.BOTTOM -> c.maxHeight - p.height - pad
                        else -> (br.top - boxRect.top).toInt().coerceIn(pad, maxOf(pad, c.maxHeight - p.height - pad))
                    }
                    p.place(x, y)
                }
            }
        }
        val canvasBox: @Composable (Modifier) -> Unit = { mod ->
            Box(mod.onGloballyPositioned { boxRect = it.boundsInWindow() }) {
                canvasView(Modifier.fillMaxSize())
                val which = open
                if (which != null && which != "page" && !full) {
                    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { open = null })
                    popout(which)
                }
                val info = sel
                if (info != null && open == null && !full) androidx.compose.ui.layout.Layout(
                    content = {
                        val chipX = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() } // where each panel's button is along the bar
                        var barW by remember { mutableStateOf(0) }
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        @Composable fun PanelChip(key: String, label: String, icon: String) {
                            Box(Modifier.onGloballyPositioned { chipX[key] = it.positionInParent().x.toInt() }) {
                                OptionChip(label, icon, selPanel == key) { selPanel = if (selPanel == key) null else key }
                            }
                        }
                        Column(Modifier.offset { androidx.compose.ui.unit.IntOffset(barDrag.x.toInt(), barDrag.y.toInt()) }, horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                Modifier.pointerInput(Unit) { detectTapGestures { } }.onSizeChanged { barW = it.width }, shape = StyShapes.Lg, color = sty.surface, shadowElevation = 8.dp,
                                border = androidx.compose.foundation.BorderStroke(1.dp, sty.border),
                            ) {
                                Column {
                                    DragHandle { barDrag += it }
                                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    OptionChip("Delete", Icons24.DELETE, false) { canvas?.deleteSelection() }
                                    OptionChip("Save clip", Icons24.CLIPBOARD, false) { canvas?.saveSelection()?.let { clips = canvas!!.shelf.clips; naming = it; nameText = it.name } }
                                    PanelChip("layers", "Layers", Icons24.LAYERS)
                                    if (info.text) PanelChip("text", "Text", Icons24.TEXT)
                                    if (info.shape) PanelChip("shape", "Shape", Icons24.SHAPE)
                                    if (info.connector) PanelChip("connector", "Connector", Icons24.CONNECTOR)
                                    if (info.table) PanelChip("table", "Table", Icons24.TABLE)
                                    if (info.table) OptionChip("Table data", Icons24.TABLE, false) { canvas?.editTableData() }
                                    if (info.chart) OptionChip("Edit chart", Icons24.CHART, false) { canvas?.editChart() }
                                    if (info.picture) OptionChip("Crop", Icons24.CROP, false) { canvas?.toggleCrop() }
                                    if (info.cropped) OptionChip("Reset crop", Icons24.RESET, false) { canvas?.resetCrop() }
                                }
                                }
                            }
                            val panel = selPanel
                            if (panel != null) {
                                val panelW = if (panel == "layers") 148.dp else 250.dp
                                // The panel opens under its own button (kept inside the bar's width).
                                val x = (chipX[panel] ?: 0).coerceAtMost(maxOf(0, barW - with(density) { panelW.roundToPx() }))
                                Surface(
                                    Modifier.offset { androidx.compose.ui.unit.IntOffset(x, 0) }.pointerInput(Unit) { detectTapGestures { } }.width(panelW).heightIn(max = 380.dp),
                                    shape = StyShapes.Lg, color = sty.surface, shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, sty.border),
                                ) {
                                    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                                        DragHandle { barDrag += it }
                                        when (panel) { "layers" -> layersMenu(); "text" -> textMenu(); "shape" -> shapeMenu(); "connector" -> connectorMenu(); "table" -> tableMenu() }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { ms, c ->
                    val pad = 8.dp.roundToPx(); val gapPx = 14.dp.roundToPx()
                    val p = ms[0].measure(c.copy(minWidth = 0, minHeight = 0, maxWidth = c.maxWidth - 2 * pad))
                    layout(c.maxWidth, c.maxHeight) {
                        val x = (info.rect.centerX() - p.width / 2f).toInt().coerceIn(pad, maxOf(pad, c.maxWidth - p.width - pad))
                        var y = info.rect.bottom.toInt() + gapPx
                        if (y + p.height > c.maxHeight - pad) y = info.rect.top.toInt() - gapPx - p.height // no room below: above it
                        p.place(x, y.coerceIn(pad, maxOf(pad, c.maxHeight - p.height - pad)))
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().background(sty.bg).statusBarsPadding().navigationBarsPadding()) {
            if (!full) NoteHeader(file.name, latency, onLibrary, { full = true }, { open = if (open == "page") null else "page" }, { open = if (open == "add") null else "add" })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val rail: @Composable (Modifier) -> Unit = { m -> ToolRail(side, entries, m) { k, r -> btnRects[k] = r } }
                if (full) canvasBox(Modifier.fillMaxSize())
                else when (side) {
                    RailSide.LEFT -> Row(Modifier.fillMaxSize()) { rail(Modifier.fillMaxHeight()); canvasBox(Modifier.weight(1f).fillMaxHeight()) }
                    RailSide.RIGHT -> Row(Modifier.fillMaxSize()) { canvasBox(Modifier.weight(1f).fillMaxHeight()); rail(Modifier.fillMaxHeight()) }
                    RailSide.TOP -> Column(Modifier.fillMaxSize()) { rail(Modifier.fillMaxWidth()); canvasBox(Modifier.weight(1f).fillMaxWidth()) }
                    RailSide.BOTTOM -> Column(Modifier.fillMaxSize()) { canvasBox(Modifier.weight(1f).fillMaxWidth()); rail(Modifier.fillMaxWidth()) }
                }
            }
            if (!full) {
                PageBarHost(pagesState, hereState, infinite, canvas)
                ConvertedSheet(sheetOpen, { sheetOpen = !sheetOpen })
            }
        }
        // Adding things (a picture, a photo), from the plus button in the header.
        if (open == "add" && !full) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { open = null })
            Surface(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 8.dp).pointerInput(Unit) { detectTapGestures { } }.width(250.dp),
                shape = StyShapes.Lg, color = sty.surface, shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, sty.border),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    PopHeading("Add to this page")
                    PopIconRow("Image", Icons24.IMAGE) { open = null; pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    PopIconRow("Camera", Icons24.CAMERA) { open = null; openCamera() }
                    PopIconRow("PDF (coming later)", Icons24.PAGE, enabled = false) { }
                }
            }
        }
        // The page settings, from the hamburger button in the header.
        if (open == "page" && !full) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { open = null })
            Surface(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 8.dp).pointerInput(Unit) { detectTapGestures { } }
                    .widthIn(min = 260.dp, max = 380.dp).heightIn(max = 720.dp),
                shape = StyShapes.Lg, color = sty.surface, shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, sty.border),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalClose provides { open = null }) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        pageMenu()
                        CycleRow("Toolbar position", RailSide.entries, RailSide.entries[settings.rail], { it.label }, { null }) { settings.rail = it.ordinal }
                        PopHeading("For testing")
                        Item("Add chart samples to this note") { canvas?.addChartSamples(); open = null }
                    }
                }
            }
        }
        // The zoom, as a percentage of a page fitted to the screen, with a button to fit it. It shows while the zoom
        // changes and a moment after, and stays while it is not 100.
        // The pens are changed from the tool menus, so look at them now and then (and on the way out) to keep them.
        // The note's own tool, zoom and scroll are kept the same way.
        fun keep(c: InkCanvasView) { settings.captureFrom(c); file.view = NoteView.capture(c) }
        LaunchedEffect(sel == null) { if (sel == null) { selPanel = null; barDrag = androidx.compose.ui.geometry.Offset.Zero } }
        LaunchedEffect(open) { popDrag = androidx.compose.ui.geometry.Offset.Zero }
        LaunchedEffect(canvas) { while (true) { kotlinx.coroutines.delay(1000); canvas?.let { keep(it); ink = it.penColor; hlInk = it.highlighterColor; penW = it.penWidth; hlW = it.highlighterWidth } } }
        LaunchedEffect(Unit) { // a few test clips, once
            if (!settings.clipSamples && shelf.clips.isEmpty()) { sampleClips().forEach { shelf.add(it) }; clips = shelf.clips }
            settings.clipSamples = true
        }
        LaunchedEffect(canvas) {
            while (true) {
                canvas?.let { c -> val o = c.readingOrder(); if (o != pagesState.value) pagesState.value = o; val h = c.centrePage() ?: c.workPage; if (h !== hereState.value) hereState.value = h; val si = c.selectionInfo(); if (si != sel) sel = si }
                kotlinx.coroutines.delay(200)
            }
        }
        androidx.compose.runtime.DisposableEffect(canvas) { onDispose { canvas?.let(::keep) } }
        ZoomPill(zoomState, full, sheetOpen, { canvas?.fitToScreen() }, { canvas?.overview() })
        if (full) Surface(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp), shape = StyShapes.Md,
            color = LocalSty.current.surface, shadowElevation = 4.dp, border = androidx.compose.foundation.BorderStroke(1.dp, LocalSty.current.border),
        ) { IconBtn("Exit full screen", Icons24.EXIT_FULL, onClick = { full = false }) }
        placing?.let { c ->
            Surface(Modifier.align(Alignment.TopCenter).padding(16.dp), shape = StyShapes.Xl, tonalElevation = 6.dp, shadowElevation = 4.dp) {
                Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tap where \"${c.name}\" goes")
                    TextButton(onClick = { canvas?.placeClip(null) }, Modifier.padding(start = 8.dp)) { Text("Cancel") }
                }
            }
        }
        if (tableDialog) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { tableDialog = false }) {
                Surface(shape = StyShapes.Lg, color = sty.surface, border = androidx.compose.foundation.BorderStroke(1.dp, sty.border)) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        DialogTitle("Table")
                        Text("Pick how many, then drag on the page to draw it.", color = sty.inkMuted, fontSize = 13.sp)
                        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            NumberWheel("Rows", tRows, 1..Table.MAX_LINES) { tRows = it; canvas?.tableRows = it }
                            NumberWheel("Columns", tCols, 1..Table.MAX_LINES) { tCols = it; canvas?.tableCols = it }
                        }
                        Button(onClick = { tableDialog = false }) { Text("Done") }
                    }
                }
            }
        }
        naming?.let { clip ->
            val done = { naming = null }
            AlertDialog(
                onDismissRequest = done,
                confirmButton = { TextButton(onClick = { canvas?.shelf?.rename(clip, nameText); clips = canvas!!.shelf.clips; done() }) { Text("Save") } },
                dismissButton = { TextButton(onClick = done) { Text("Cancel") } },
                title = { Text("Clip name") },
                text = { OutlinedTextField(nameText, { nameText = it }, singleLine = true) },
            )
        }
        tableEdit?.let { t ->
            TableDialog(
                t,
                onDone = { grid, header, align -> canvas?.commitTableData(t.id, grid, header, align); tableEdit = null },
                onChart = { grid, header, align, row, col, rows, cols, inRows ->
                    canvas?.commitTableData(t.id, grid, header, align)
                    canvas?.chartFromTable(t.id, row, col, rows, cols, inRows)?.let { chartEdit = it to true }
                    tableEdit = null
                },
                onCancel = { tableEdit = null },
            )
        }
        chartEdit?.let { (c, isNew) ->
            ChartDialog(c, onDone = { done -> canvas?.commitChart(done, isNew); chartEdit = null }, onCancel = { chartEdit = null })
        }
        if (picking != 0) {
            val text = picking == 1 || picking == 3 || picking == 5 // the palette that starts with the default ink colour, not "none"
            val palette = when (picking) { 1, 3, 5 -> TEXT_COLOURS; 2 -> HIGHLIGHTS; 8 -> TEXT_COLOURS.drop(1); 9 -> HIGHLIGHTS.drop(1); else -> FILLS }
            // Gives the colour [argb] to whatever the picker was opened for (the same for a swatch and for the eyedropper).
            fun applyColour(code: Int, argb: Int) {
                when (code) {
                    1 -> { textColor = argb; canvas?.setTextStyle(textColor, textHighlight) }
                    2 -> { textHighlight = argb; canvas?.setTextStyle(textColor, textHighlight) }
                    3 -> { tColor = argb; canvas?.setTableStyle(argb, null, null) }
                    5 -> { sColor = argb; canvas?.setShapeStyle(argb, null, null, null) }
                    6 -> { sFill = argb; canvas?.setShapeStyle(null, argb, null, null) }
                    7 -> { bColor = argb; canvas?.bucketColor = argb }
                    8 -> { shade = -1; canvas?.penColor = argb }
                    9 -> { shade = -1; canvas?.highlighterColor = (argb and 0xFFFFFF) or 0x99000000.toInt() } // see-through, so the writing shows
                    else -> { tFill = argb; canvas?.setTableStyle(null, argb, null) }
                }
            }
            AlertDialog(
                onDismissRequest = { picking = 0 },
                confirmButton = { TextButton(onClick = { picking = 0 }) { Text("Close") } },
                dismissButton = { TextButton(onClick = { val code = picking; picking = 0; canvas?.startEyedropper { applyColour(code, it) } }) { Text("Eyedropper") } },
                title = { Text(when (picking) { 1 -> "Text colour"; 2 -> "Highlight"; 3 -> "Table lines colour"; 5 -> "Shape outline"; 6 -> "Shape fill"; 7 -> "Fill colour"; 8 -> "Pen colour"; 9 -> "Highlighter colour"; else -> "Table fill" }) },
                text = {
                    Column {
                        palette.chunked(5).forEach { row ->
                            Row(Modifier.padding(bottom = 8.dp)) {
                                row.forEach { argb ->
                                    val chosen = argb == when (picking) { 1 -> textColor; 2 -> textHighlight; 3 -> tColor; 5 -> sColor; 6 -> sFill; 7 -> bColor; 8 -> canvas?.penColor; 9 -> canvas?.highlighterColor; else -> tFill }
                                    Box(
                                        Modifier.padding(end = 8.dp).size(44.dp).clip(CircleShape)
                                            .background(if (argb ushr 24 == 0) Color.White else Color(argb))
                                            .border(if (chosen) 3.dp else 1.dp, if (chosen) Color.Black else Color.Gray, CircleShape)
                                            .clickable {
                                                applyColour(picking, argb)
                                                picking = 0
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) { if (argb ushr 24 == 0) Text(if (text) "A" else "none", softWrap = false) }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

// Throwaway swatches for the text tool; the real picker comes with the tool rail. Zero alpha = the default.
private val TEXT_COLOURS = listOf(
    0, 0xFF000000, 0xFF616161, 0xFFC62828, 0xFFEF6C00, 0xFFF9A825, 0xFF2E7D32, 0xFF00838F, 0xFF1565C0, 0xFF4527A0, 0xFF7B1FA2, 0xFFAD1457,
).map { it.toInt() }

private val HIGHLIGHTS = listOf(
    0, 0x99FFEB3B, 0x9966BB6A, 0x99F06292, 0x994FC3F7, 0x99FFB74D, 0x99BA68C8, 0x994DB6AC, 0x99EF5350, 0x99BDBDBD,
).map { it.toInt() }

// Colours for fills: none, the see-through highlights, then some solid soft ones.
private val FILLS = listOf(
    0, 0x99FFEB3B, 0x9966BB6A, 0x99F06292, 0x994FC3F7, 0x99FFB74D,
    0xFFFFF59D, 0xFFA5D6A7, 0xFFF8BBD0, 0xFF81D4FA, 0xFFFFCC80, 0xFFE1BEE7, 0xFFFFFFFF, 0xFFBDBDBD, 0xFF616161,
).map { it.toInt() }

private fun toolName(t: InkCanvasView.Tool) = when (t) {
    InkCanvasView.Tool.PEN -> "Pen"
    InkCanvasView.Tool.HIGHLIGHTER -> "Highlighter"
    InkCanvasView.Tool.ERASER_STROKE -> "Eraser (whole stroke)"
    InkCanvasView.Tool.ERASER_PART -> "Eraser (part of a stroke)"
    InkCanvasView.Tool.LASSO -> "Lasso"
    InkCanvasView.Tool.LINE -> "Line"
    InkCanvasView.Tool.LINE_HIGHLIGHTER -> "Line highlighter"
    InkCanvasView.Tool.DOTTED -> "Dotted pen"
    InkCanvasView.Tool.LINE_DOTTED -> "Dotted line"
    InkCanvasView.Tool.LASER -> "Laser pointer"
    InkCanvasView.Tool.TEXT -> "Text"
    InkCanvasView.Tool.TABLE -> "Table"
    InkCanvasView.Tool.SHAPE -> "Shape"
    InkCanvasView.Tool.BUCKET -> "Paint bucket"
    InkCanvasView.Tool.CONNECTOR -> "Connector"
    InkCanvasView.Tool.CHART -> "Chart"
}

private typealias Tool = InkCanvasView.Tool

/** Closes the popout the menu items are in. */
private val LocalClose = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** A titled group of items inside a popout. [content] gets a function that closes the popout (some items leave it open). */
@Composable
private fun Menu(label: String, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    Column { Heading(label); content(LocalClose.current) }
}

@Composable
private fun Item(text: String, onClick: () -> Unit) = PopRow(text, false, onClick)

/** A menu item that says if it is the one in use. */
@Composable
private fun Choice(text: String, chosen: Boolean, onClick: () -> Unit) = PopRow(text, chosen, onClick)

@Composable
private fun PopRow(text: String, chosen: Boolean, onClick: () -> Unit) {
    val sty = LocalSty.current
    Text(
        text, Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 44.dp).clip(StyShapes.Md)
            .background(if (chosen) sty.honeyTint else androidx.compose.ui.graphics.Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        color = sty.ink, fontSize = 14.sp,
    )
}

@Composable
private fun Heading(text: String) = PopHeading(text)

private fun routeIcon(r: Route) = when (r) { Route.STRAIGHT -> Icons24.ROUTE_STRAIGHT; Route.ELBOW -> Icons24.ROUTE_ELBOW; Route.CURVE -> Icons24.ROUTE_CURVE }

private fun headIcon(h: Arrowhead) = when (h) { Arrowhead.NONE -> Icons24.HEAD_NONE; Arrowhead.OPEN -> Icons24.HEAD_OPEN; Arrowhead.FILLED -> Icons24.HEAD_FILLED; Arrowhead.DOT -> Icons24.HEAD_DOT }

private fun routeLabel(r: Route) = when (r) { Route.STRAIGHT -> "straight"; Route.ELBOW -> "right angles"; Route.CURVE -> "rounded" }

private fun eraseLabel(e: EraseOnly) = when (e) {
    EraseOnly.ALL -> "Everything"
    EraseOnly.INK -> "Ink"
    EraseOnly.HIGHLIGHTS -> "Highlights"
    EraseOnly.LINES -> "Straight lines"
    EraseOnly.TEXT_BOXES -> "Text boxes"
    EraseOnly.SHAPES -> "Shapes"
    EraseOnly.CONNECTORS -> "Connectors"
    EraseOnly.TABLES -> "Tables"
    EraseOnly.FILLS -> "Fills"
}

/**
 * A small picture of a clip: a rough one (strokes as lines, boxes, tables and shapes as outlines), not the real
 * drawing, which is tied to the page view. Enough to tell clips apart.
 */
@Composable
private fun ClipPreview(c: Clip, dim: androidx.compose.ui.unit.Dp = 48.dp, thumb: (String) -> android.graphics.Bitmap?) {
    val ink = LocalSty.current.ink
    Canvas(Modifier.size(dim)) {
        val k = minOf(size.width / maxOf(c.width, 1f), size.height / maxOf(c.height, 1f))
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        fun poly(pts: List<Pair<Float, Float>>, closed: Boolean) {
            for (i in 0 until pts.size - (if (closed) 0 else 1)) {
                val (ax, ay) = pts[i]; val (bx, by) = pts[(i + 1) % pts.size]
                drawLine(ink, p(ax, ay), p(bx, by), 2f)
            }
        }
        for (pic in c.pictures) { // the picture itself, cropped and turned as it is
            val bmp = thumb(pic.sha)
            if (bmp == null) poly(pic.corners(), true)
            else drawIntoCanvas { cv ->
                val n = cv.nativeCanvas
                n.save()
                n.translate(pic.x * k, pic.y * k)
                n.rotate(Math.toDegrees(pic.angle.toDouble()).toFloat())
                val src = android.graphics.Rect(
                    Math.round(pic.cropX * bmp.width), Math.round(pic.cropY * bmp.height),
                    Math.round((pic.cropX + pic.cropW) * bmp.width), Math.round((pic.cropY + pic.cropH) * bmp.height),
                )
                n.drawBitmap(bmp, src, android.graphics.RectF(0f, 0f, pic.width * k, pic.height * k), null)
                n.restore()
            }
        }
        for (i in c.items) poly(i.pts.map { it.x to it.y }, false)
        for (t in c.tables) poly(listOf(t.x to t.y, t.x + t.width to t.y, t.x + t.width to t.y + t.height, t.x to t.y + t.height), true)
        for (s in c.shapes) poly(s.corners(), true)
        for (p in c.pictures) poly(p.corners(), true)
        for (ch in c.charts) poly(ch.corners(), true)
        for (t in c.texts) poly(t.corners(t.fontSize * 1.3f), true)
        for (f in c.fills) for (r in f.rings) poly(r, true)
        for (n in c.connectors) poly(n.path { id -> c.shapes.firstOrNull { it.id == id } }, false)
    }
}

/** One clip in the clipboard grid: a picture of it and its name. */
@Composable
private fun ClipTile(c: Clip, thumb: (String) -> android.graphics.Bitmap?, chosen: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val sty = LocalSty.current
    Column(
        Modifier.width(96.dp).padding(2.dp).clip(StyShapes.Md).background(if (chosen) sty.honeyTint else androidx.compose.ui.graphics.Color.Transparent)
            .pointerInput(c) { detectTapGestures(onTap = { onTap() }, onLongPress = { onHold() }) }.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(76.dp).clip(StyShapes.Md).background(sty.bg).border(1.dp, sty.border, StyShapes.Md), contentAlignment = Alignment.Center) {
            ClipPreview(c, 60.dp, thumb)
        }
        Text(c.name, color = sty.ink, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The zoom, as a percentage of a page fitted to the screen, with buttons to fit it or see everything. It shows while the zoom
 * changes and a moment after, and stays while it is not 100. Its own composable, so the zoom changing every frame redoes only this.
 */
@Composable
private fun BoxScope.ZoomPill(zoomState: androidx.compose.runtime.MutableIntState, full: Boolean, sheetOpen: Boolean, onFit: () -> Unit, onOverview: () -> Unit) {
    val zoom = zoomState.intValue
    var shown by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(zoom) {
        if (first) { first = false; return@LaunchedEffect }
        shown = true
        delay(2500)
        shown = false
    }
    if (shown || zoom != 100) {
        Surface(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).padding(bottom = if (full) 0.dp else if (sheetOpen) 268.dp else 108.dp),
            shape = StyShapes.Xl, tonalElevation = 6.dp, shadowElevation = 4.dp,
        ) {
            Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$zoom%")
                TextButton(onClick = onFit, Modifier.padding(start = 8.dp)) { Text("Fit to screen") }
                TextButton(onClick = onOverview) { Text("Overview") }
            }
        }
    }
}

/** The page bar. It reads the pages and the page in view itself, so those changing redoes only this. */
@Composable
private fun PageBarHost(pagesState: androidx.compose.runtime.State<List<PageModel>>, hereState: androidx.compose.runtime.State<PageModel?>, infinite: Boolean, canvas: InkCanvasView?) {
    val pages = pagesState.value
    val cells = (if (infinite) listOfNotNull(canvas?.workPage) else pages).map { MapCell(it.page.col, it.page.row, PageSizes.isLandscape(it.page)) }
    if (cells.size <= 1) return
    val index = pages.indexOf(hereState.value).coerceAtLeast(0)
    PageBar(
        cells, index,
        onPick = { i -> pages.getOrNull(i)?.let { canvas?.showPage(it) } },
        onPrev = { pages.getOrNull(index - 1)?.let { canvas?.showPage(it) } },
        onNext = { pages.getOrNull(index + 1)?.let { canvas?.showPage(it) } },
        onAll = { canvas?.overview() },
    )
}

/** A row in a popout with an icon in front, for choices that are not a setting. */
@Composable
private fun PopIconRow(text: String, icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    val sty = LocalSty.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 48.dp).clip(StyShapes.Md).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.alpha(if (enabled) 1f else 0.4f)) { SvgIcon(icon, sty.ink, 22.dp) }
        Text(text, color = if (enabled) sty.ink else sty.inkMuted, fontSize = 14.sp)
    }
}
