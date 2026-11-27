package app.styli.ink

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small pictures of each ink file's first page, for the Library covers. A picture is made once with an off-screen
 * [InkCanvasView] and kept until the page changes (its revision number goes up, which says when).
 */
object Covers {
    /** The picture at sizes that cover every card size, each half the one before. */
    class Pyramid(val levels: List<ImageBitmap>) {
        /** The smallest level at least [px] wide (or the biggest, if none is). Never shrinks one by more than 2 times. */
        fun pick(px: Int): ImageBitmap = levels.lastOrNull { it.width >= px } ?: levels.first()
    }

    private const val SNAPSHOT_PX = 1200
    private const val THICKEN = 2.5f
    private const val MAX_LEVEL = 600
    private const val CACHE_BYTES = 24 * 1024 * 1024

    // Identity matters here: NoteFile has no equals override, and a file can appear in several lists at once.
    private data class Key(val file: NoteFile, val version: Int, val bucket: Int)
    private class Made(val bytes: Int, val pyramid: Pyramid)

    // A bounded cache: a WeakHashMap let covers live for the process and had no byte limit. Entries are not recycled
    // on eviction, because a visible card can still be holding a pyramid until its next composition.
    private val made = object : LruCache<Key, Made>(CACHE_BYTES) {
        override fun sizeOf(key: Key, value: Made) = value.bytes
    }

    // One render per file, version and size bucket. The job lives outside a card's composition, so scrolling a card
    // away cannot cancel a cover that another card is waiting for.
    private val pending = HashMap<Key, CompletableDeferred<Pyramid?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the first page's picture is based on; it changes whenever the page does. */
    fun version(file: NoteFile): Int = file.notebook?.pages?.first()?.let { if (it.order.isEmpty()) 0 else it.revision + 1 } ?: 0

    /** The stored level that covers a [px]-wide drawing: the same choice [Pyramid.pick] makes when it draws. */
    fun bucket(px: Int): Int = when {
        px <= 75 -> 75
        px <= 150 -> 150
        px <= 300 -> 300
        else -> MAX_LEVEL
    }

    /** A synchronous cache hit, so changing the card size does not flash a placeholder for one frame. Call on main. */
    fun peek(file: NoteFile, bucket: Int, v: Int = version(file)): Pyramid? = made[Key(file, v, bucket)]?.pyramid

    /**
     * The picture of [file]'s first page, made now if it is missing or out of date. Call on the main thread; the
     * off-screen View snapshot stays there, while the bitmap halvings run on a background dispatcher.
     */
    suspend fun of(context: Context, file: NoteFile, targetPx: Int): Pyramid? {
        val nb = file.notebook ?: return null
        val v = version(file)
        if (v == 0) return null // a blank page needs no picture: the cover draws its template itself
        val key = Key(file, v, bucket(targetPx))
        made[key]?.let { return it.pyramid }
        pending[key]?.let { return it.await() }

        val deferred = CompletableDeferred<Pyramid?>()
        pending[key] = deferred
        scope.launch {
            try {
                val first = nb.pages.first()
                // A hairline stroke is under a pixel wide at cover size, so shrinking it in one big step breaks it
                // into dots. Draw it large (a notebook of just this page, so the user's own place in the file is
                // untouched), then halve it with smoothing. The pen lines are also drawn 2.5 times wider, as a
                // thumbnail would, so they stay solid.
                val big = InkCanvasView(context, Notebook(first)).snapshot(SNAPSHOT_PX, THICKEN)
                val levels = withContext(Dispatchers.Default) {
                    val out = ArrayList<ImageBitmap>()
                    var current = big
                    try {
                        // Keep only the levels this card size can actually pick. Bigger cards still get 600 px;
                        // smaller ones get the shorter ladder down to their own level.
                        while (current.width / 2 >= key.bucket) {
                            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
                            out.add(current.asImageBitmap())
                        }
                    } finally {
                        if (!big.isRecycled) big.recycle()
                    }
                    out
                }
                val bytes = levels.sumOf { it.width * it.height * 4 } // ARGB_8888
                val pyramid = Pyramid(levels)
                made.put(key, Made(bytes, pyramid))
                deferred.complete(pyramid)
            } catch (t: Throwable) {
                deferred.complete(null)
            } finally {
                pending.remove(key)
            }
        }
        return deferred.await()
    }
}
