package app.styli.ink

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.WeakHashMap

/**
 * Small pictures of each ink file's first page, for the Library covers. A picture is made once with an off-screen
 * [InkCanvasView] and kept until the page changes (its event log grows, so the log's length says when).
 */
object Covers {
    /** The picture at several sizes, each half the one before, so a cover can use the one just bigger than itself. */
    class Pyramid(val levels: List<ImageBitmap>) {
        /** The smallest level at least [px] wide (or the biggest, if none is). Never shrinks one by more than 2 times. */
        fun pick(px: Int): ImageBitmap = levels.lastOrNull { it.width >= px } ?: levels.first()
    }

    private class Made(val version: Int, val pyramid: Pyramid)
    private val made = WeakHashMap<NoteFile, Made>()

    /** What the first page's picture is based on; it changes whenever the page does. */
    fun version(file: NoteFile): Int = file.notebook?.pages?.first()?.log?.size ?: 0

    /** The pictures of [file]'s first page, made now if they are missing or out of date. Call on the main thread. */
    fun of(context: Context, file: NoteFile): Pyramid? {
        val nb = file.notebook ?: return null
        val v = version(file)
        made[file]?.takeIf { it.version == v }?.let { return it.pyramid }
        if (v == 0) return null // a blank page needs no picture: the cover draws its template itself
        val first = nb.pages.first()
        // A hairline stroke is under a pixel wide at cover size, so shrinking it in one big step breaks it into dots.
        // Draw it large (a notebook of just this page, so the user's own place in the file is untouched), then halve it
        // again and again with smoothing. The pen lines are also drawn 2.5 times wider, as a thumbnail would, so they stay solid.
        // (each halving averages 2 by 2 pixels).
        val big = InkCanvasView(context, Notebook(first)).snapshot(1200, thicken = 2.5f)
        val levels = ArrayList<ImageBitmap>()
        var bmp = big
        repeat(4) {
            bmp = android.graphics.Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            levels.add(bmp.asImageBitmap()) // kept: each level is shown, so none of these is recycled
        }
        big.recycle()
        return Pyramid(levels).also { made[file] = Made(v, it) }
    }
}
