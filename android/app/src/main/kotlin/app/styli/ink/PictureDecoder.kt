package app.styli.ink

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.nio.ByteBuffer

/** Turns a picked file's bytes into something to draw. Android only; the maths lives in [Picture]. */
object PictureDecoder {
    /** A picture is drawn from a copy no larger than this on its longer side; the original file is never touched. */
    const val MAX_SIDE = 2048

    /** [bitmap] is upright (the file's rotation metadata applied) and at most [MAX_SIDE] across; [width] and [height] are the file's own size, also upright. */
    class Decoded(val bitmap: Bitmap, val width: Int, val height: Int)

    /** Null if the bytes are not a picture this device can read. */
    fun decode(data: ByteArray, maxSide: Int = MAX_SIDE): Decoded? = try {
        var fileW = 0; var fileH = 0
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
            fileW = info.size.width; fileH = info.size.height
            val big = maxOf(fileW, fileH)
            if (big > maxSide) decoder.setTargetSize(maxOf(1, fileW * maxSide / big), maxOf(1, fileH * maxSide / big))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // Whether the header's size already counted the file's rotation is not something to rely on: the bitmap is upright.
        if ((fileW > fileH) != (bmp.width > bmp.height) && fileW != fileH) { val t = fileW; fileW = fileH; fileH = t }
        Decoded(bmp, fileW, fileH)
    } catch (e: Exception) {
        null
    }

    /** The file name's ending for a picture of this type, for the attached file's name. */
    fun extension(mime: String?): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "img"
    }
}
