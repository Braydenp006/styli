package app.styli.ink

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A picture on a page. The pixels are not here: [sha] (SHA-256 of the picked file's bytes, as 64 hex characters)
 * names the file in the [PictureStore], and [file] is its name as an attached file (`<sha>.<ext>`). The box
 * (x, y, width, height, in page units, turned by [angle] radians about its top left corner) shows the part of the
 * file picked out by the crop ([cropX], [cropY], [cropW], [cropH]: fractions of the file's width and height, 0 to 1).
 * Cropping hides the rest and loses nothing. Never changes once made; edits make a copy.
 */
data class Picture(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val file: String,
    val sha: String,
    val srcWidth: Int,
    val srcHeight: Int,
    val angle: Float = 0f,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropW: Float = 1f,
    val cropH: Float = 1f,
) {
    /** The page point of the point (lx, ly) in the box's own frame: origin at (x, y), turned by [angle] about it. */
    fun toPage(lx: Float, ly: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        return (x + lx * c - ly * s) to (y + lx * s + ly * c)
    }

    /** The other way: where a page point is in the box's own frame. */
    fun toLocal(px: Float, py: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        val dx = px - x; val dy = py - y
        return (dx * c + dy * s) to (-dx * s + dy * c)
    }

    fun contains(px: Float, py: Float): Boolean = toLocal(px, py).let { (lx, ly) -> lx in 0f..width && ly in 0f..height }

    /** The four corners on the page, clockwise from the top left. */
    fun corners() = listOf(toPage(0f, 0f), toPage(width, 0f), toPage(width, height), toPage(0f, height))

    /** The picture after a move, resize or turn by [map] (a move, a uniform scale and a turn, as the lasso makes). */
    fun moved(map: Affine): Picture {
        val scale = sqrt(abs(map.a * map.d - map.b * map.c)).toFloat()
        val turn = kotlin.math.atan2(map.b, map.a).toFloat()
        return copy(x = map.x(x, y), y = map.y(x, y), width = width * scale, height = height * scale, angle = angle + turn)
    }

    /** Page units for one pixel of the file at this size (the same both ways, as a crop never stretches). */
    val unitsPerPixel: Float get() = width / (cropW * srcWidth)

    /**
     * The picture showing the part ([cx], [cy], [cw], [ch]) of the file instead, at the same size per pixel and with
     * what stays visible exactly where it was (so dragging an edge in moves only that edge, and widening the crop
     * gives back what was hidden). The part is kept inside the file and at least [MIN_CROP] across.
     */
    fun cropped(cx: Float, cy: Float, cw: Float, ch: Float): Picture {
        val w = cw.coerceIn(MIN_CROP, 1f)
        val h = ch.coerceIn(MIN_CROP, 1f)
        val nx = cx.coerceIn(0f, 1f - w)
        val ny = cy.coerceIn(0f, 1f - h)
        val s = unitsPerPixel
        // How far the new top left is from the old one, in the box's own frame.
        val lx = (nx - cropX) * srcWidth * s
        val ly = (ny - cropY) * srcHeight * s
        val (px, py) = toPage(lx, ly)
        return copy(x = px, y = py, width = w * srcWidth * s, height = h * srcHeight * s, cropX = nx, cropY = ny, cropW = w, cropH = h)
    }

    /**
     * The picture with the crop handle [handle] dragged to the page point ([px], [py]): 0 to 3 are the corners from the
     * top left clockwise, 4 to 7 the top, right, bottom and left middles. The part is kept inside the file and at
     * least [MIN_CROP] across; the opposite edges do not move.
     */
    fun cropDragged(handle: Int, px: Float, py: Float): Picture {
        val (lx, ly) = toLocal(px, py)
        val s = unitsPerPixel
        val fx = cropX + lx / (srcWidth * s) // where in the file the pen is
        val fy = cropY + ly / (srcHeight * s)
        var l = cropX; var r = cropX + cropW; var t = cropY; var b = cropY + cropH
        if (handle == 0 || handle == 3 || handle == 7) l = fx.coerceIn(0f, r - MIN_CROP)
        if (handle == 1 || handle == 2 || handle == 5) r = fx.coerceIn(l + MIN_CROP, 1f)
        if (handle == 0 || handle == 1 || handle == 4) t = fy.coerceIn(0f, b - MIN_CROP)
        if (handle == 2 || handle == 3 || handle == 6) b = fy.coerceIn(t + MIN_CROP, 1f)
        return cropped(l, t, r - l, b - t)
    }

    /** Shows the whole file again, with what was visible kept where it was. */
    fun uncropped() = cropped(0f, 0f, 1f, 1f)

    val isCropped: Boolean get() = cropX != 0f || cropY != 0f || cropW != 1f || cropH != 1f

    companion object {
        /** The smallest part of the file a crop may leave, as a fraction of its width or height. */
        const val MIN_CROP = 0.02f

        /**
         * A new picture of a [srcWidth] by [srcHeight] file, centred on a page of [pageW] by [pageH]: [fraction] of the
         * page's width across, but no taller than [fraction] of the page's height.
         */
        fun placed(id: String, file: String, sha: String, srcWidth: Int, srcHeight: Int, pageW: Float, pageH: Float, fraction: Float = 0.6f): Picture {
            require(srcWidth > 0 && srcHeight > 0) { "an empty picture" }
            val w = min(pageW * fraction, pageH * fraction * srcWidth / srcHeight)
            val h = w * srcHeight / srcWidth
            return Picture(id, (pageW - w) / 2, (pageH - h) / 2, w, h, file, sha, srcWidth, srcHeight)
        }
    }
}

/** The picked files' bytes, by hash, for as long as the app runs (saving them with the notebook comes with saving to disk). */
class PictureStore {
    private val bytes = HashMap<String, ByteArray>()

    /** Keeps [data] and returns its SHA-256 as 64 hex characters; the same bytes twice are kept once. */
    fun put(data: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        bytes.getOrPut(sha) { data }
        return sha
    }

    fun get(sha: String): ByteArray? = bytes[sha]

    val size: Int get() = bytes.size

    companion object {
        /** Files over this are refused (25 MB). */
        const val MAX_BYTES = 25 * 1024 * 1024
    }
}
