package app.styli.ink

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

enum class Size3(val label: String) { SMALL("Small"), MEDIUM("Medium"), LARGE("Large") }

/** What the Tabularium screens share: view options, the kind filter, the folder being looked at, and a change counter. */
class LibUi {
    var noteSize by mutableStateOf(Size3.MEDIUM)
    var folderSize by mutableStateOf(Size3.MEDIUM)
    var sort by mutableStateOf(Sort.EDITED)
    var filter by mutableStateOf<FileKind?>(null) // null shows every kind
    var openFolder by mutableStateOf<Folder?>(null) // null is the top level
    private var tick by mutableIntStateOf(0)

    /** Call after changing the library, so lists redraw. Read [version] in a composable to depend on it. */
    fun changed() { tick++ }
    val version: Int get() = tick
}

/** What is being dragged: a folder, or a file with the folder it is in. */
class Dragged(val folder: Folder?, val file: NoteFile?, val from: Folder?, val label: String)

/** One drag at a time, across the whole screen: where the finger is and which folders could take the drop. */
class DragState {
    var item by mutableStateOf<Dragged?>(null)
    var pos by mutableStateOf(Offset.Zero) // in the root's coordinates
    val targets = mutableStateMapOf<Folder, Rect>()

    /** The smallest folder box under the finger, never the folder being dragged. */
    fun over(): Folder? {
        if (item == null) return null
        return targets.entries.filter { pos in it.value && it.key !== item?.folder }.minByOrNull { it.value.width * it.value.height }?.key
    }
}

/** Moves what was dragged into [to]. False if that is not allowed (a folder into itself or below itself). */
fun drop(lib: Library, d: Dragged, to: Folder): Boolean = when {
    d.file != null -> { lib.moveFile(d.from!!, d.file, to); true }
    d.folder != null -> lib.moveFolder(d.folder, to)
    else -> false
}

/** Makes this a place a drag can end. */
fun Modifier.dropTarget(drag: DragState, folder: Folder): Modifier = composed {
    DisposableEffect(folder) { onDispose { drag.targets.remove(folder) } }
    onGloballyPositioned { drag.targets[folder] = it.boundsInRoot() }
}

/**
 * A tile or row that is tapped to open, or pressed and held to be dragged. Held and let go without moving, it asks
 * for its menu instead (so one gesture does both).
 */
fun Modifier.dragSource(drag: DragState, item: Dragged, onTap: () -> Unit, onMenu: () -> Unit, onDrop: (Folder) -> Unit): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val tap = rememberUpdatedState(onTap); val menu = rememberUpdatedState(onMenu); val dropped = rememberUpdatedState(onDrop)
    val dragged = rememberUpdatedState(item)
    onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(Unit) { detectTapGestures(onTap = { tap.value() }) }
        .pointerInput(Unit) {
            var travel = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { off -> travel = 0f; drag.item = dragged.value; drag.pos = origin + off },
                onDrag = { change, amount -> change.consume(); drag.pos += amount; travel += amount.getDistance() },
                onDragEnd = {
                    val target = drag.over()
                    drag.item = null
                    if (travel < 24f) menu.value() else if (target != null) dropped.value(target)
                },
                onDragCancel = { drag.item = null },
            )
        }
}
