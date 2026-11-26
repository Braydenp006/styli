package app.styli.ink

import app.styli.ink.proto.Tool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureTest {
    private val sha = "ab".repeat(32)
    private fun PageModel.pic(srcW: Int = 4000, srcH: Int = 3000) =
        addPicture { Picture.placed(it, "$sha.jpg", sha, srcW, srcH, page.width.toFloat(), page.height.toFloat()) }
    private fun PageModel.stroke() = add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, listOf(Pt(0f, 0f, 0, 1f), Pt(100f, 0f, 5, 1f))).id

    @Test
    fun aNewPictureIsCentredSixtyPercentAcrossAndKeepsItsShape() {
        val p = Picture.placed("i", "f.jpg", sha, 4000, 3000, 21000f, 29700f)
        assertEquals(12600f, p.width, 0.01f)
        assertEquals(9450f, p.height, 0.01f)
        assertEquals((21000f - p.width) / 2, p.x, 0.01f)
        assertEquals((29700f - p.height) / 2, p.y, 0.01f)
        // a tall picture is limited by the page's height instead
        val t = Picture.placed("i", "f.jpg", sha, 1000, 8000, 21000f, 29700f)
        assertEquals(29700f * 0.6f, t.height, 0.01f)
        assertEquals(t.height / 8, t.width, 0.01f)
    }

    @Test
    fun addGoesOnTopLogsOnceAndUndoRemovesIt() {
        val m = PageModel()
        val s = m.stroke()
        val p = m.pic()
        assertEquals(listOf(s, p.id), m.order)
        assertEquals(p, m.pictureOf(p.id))
        assertTrue(m.log.any { it is Ev.PictureSet && it.picture.id == p.id })
        assertTrue(m.log.none { it is Ev.ZMoved }) // the plain rules give exactly that
        m.log.clear()
        assertTrue(m.undo())
        assertNull(m.pictureOf(p.id))
        assertEquals(listOf(s), m.order)
        assertEquals(listOf<Ev>(Ev.PictureRemoved(p.id)), m.log) // an undo is written as the opposite event
        assertTrue(m.redo())
        assertNotNull(m.pictureOf(p.id))
    }

    @Test
    fun movingResizingAndTurningTransformsTheBox() {
        val m = PageModel()
        val p = m.pic()
        val map = Affine.translate(100.0, 200.0)
        assertTrue(m.transform(setOf(p.id), map))
        val q = m.pictureOf(p.id)!!
        assertEquals(p.x + 100, q.x, 0.01f)
        assertEquals(p.y + 200, q.y, 0.01f)
        assertEquals(p.width, q.width, 0.001f)
        val big = Affine(2.0, 0.0, 0.0, 2.0, 0.0, 0.0)
        m.transform(setOf(p.id), big)
        assertEquals(p.width * 2, m.pictureOf(p.id)!!.width, 0.01f)
        assertEquals(p.height * 2, m.pictureOf(p.id)!!.height, 0.01f)
        assertTrue(m.undo()); assertTrue(m.undo())
        assertEquals(p, m.pictureOf(p.id))
    }

    @Test
    fun deleteAndUndo() {
        val m = PageModel()
        val p = m.pic()
        assertTrue(m.delete(setOf(p.id)))
        assertTrue(m.pictures.isEmpty())
        assertTrue(m.order.isEmpty())
        assertTrue(m.log.last() is Ev.PictureRemoved)
        assertTrue(m.undo())
        assertEquals(p, m.pictureOf(p.id))
        assertEquals(listOf(p.id), m.order)
    }

    @Test
    fun theEraserNeverTouchesAPicture() {
        val m = PageModel()
        val p = m.pic()
        val at = p.toPage(p.width / 2, p.height / 2)
        m.erase(at.first, at.second, 5000f, partial = false)
        m.endErase()
        assertEquals(listOf(p), m.pictures)
    }

    @Test
    fun aCropHidesPartOfTheFileAndKeepsWhatShowsWhereItWas() {
        val p = Picture("i", 1000f, 2000f, 4000f, 3000f, "f.jpg", sha, 400, 300)
        val c = p.cropped(0.25f, 0.5f, 0.5f, 0.5f)
        assertEquals(p.unitsPerPixel, c.unitsPerPixel, 1e-4f) // never stretched
        assertEquals(2000f, c.width, 0.01f)
        assertEquals(1500f, c.height, 0.01f)
        // the crop's top left corner is where that spot of the file was
        assertEquals(1000f + 0.25f * 4000f, c.x, 0.01f)
        assertEquals(2000f + 0.5f * 3000f, c.y, 0.01f)
        assertTrue(c.isCropped)
        // widening again gives back what was hidden, exactly where it was
        val back = c.uncropped()
        assertEquals(p.x, back.x, 0.01f); assertEquals(p.y, back.y, 0.01f)
        assertEquals(p.width, back.width, 0.01f); assertEquals(p.height, back.height, 0.01f)
        assertFalse(back.isCropped)
    }

    @Test
    fun aCropOfATurnedPictureMovesAlongItsOwnSides() {
        val p = Picture("i", 0f, 0f, 1000f, 1000f, "f.jpg", sha, 100, 100, angle = (Math.PI / 2).toFloat())
        val c = p.cropped(0.5f, 0f, 0.5f, 1f) // the left half is hidden
        // turned a quarter, the picture's left edge is its top; its own x axis points down the page
        assertEquals(0f, c.x, 0.5f)
        assertEquals(500f, c.y, 0.5f)
    }

    @Test
    fun aCropIsKeptInsideTheFileAndNeverTooSmall() {
        val p = Picture("i", 0f, 0f, 1000f, 1000f, "f.jpg", sha, 100, 100)
        val a = p.cropped(0.9f, 0.9f, 0.5f, 0.5f) // would run off the file: slides back in
        assertEquals(0.5f, a.cropX, 1e-6f); assertEquals(0.5f, a.cropW, 1e-6f)
        val b = p.cropped(0f, 0f, 0f, 0f)
        assertEquals(Picture.MIN_CROP, b.cropW, 1e-6f)
    }

    @Test
    fun theStoreKeepsEachFileOnceByItsHash() {
        val s = PictureStore()
        val a = s.put(byteArrayOf(1, 2, 3))
        assertEquals(64, a.length)
        assertEquals(a, s.put(byteArrayOf(1, 2, 3)))
        assertEquals(1, s.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.get(a))
        assertNull(s.get("00"))
        // the well-known SHA-256 of "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", s.put("abc".toByteArray()))
    }

    @Test
    fun aLoopSelectsAPictureMostlyInsideItAndATapInsideItPicks() {
        val p = Picture("i", 1000f, 1000f, 2000f, 1000f, "f.jpg", sha, 200, 100)
        val round = floatArrayOf(900f, 900f, 3100f, 900f, 3100f, 2100f, 900f, 2100f)
        assertEquals(setOf("i"), LassoGeometry.selectedPictures(listOf(p), round))
        val half = floatArrayOf(900f, 900f, 1900f, 900f, 1900f, 2100f, 900f, 2100f) // under half of it
        assertTrue(LassoGeometry.selectedPictures(listOf(p), half).isEmpty())
        assertTrue(p.contains(2000f, 1500f)); assertFalse(p.contains(500f, 500f))
        val turned = p.copy(angle = (Math.PI / 2).toFloat())
        assertTrue(turned.contains(500f, 1500f)) // turned a quarter about its top left: it hangs down on the left
    }

    @Test
    fun aClipCarriesPicturesAndPastingGivesThemNewIds() {
        val m = PageModel()
        val p = m.pic()
        val clip = Clip.of("c", m, setOf(p.id)) { id -> m.pictureOf(id)?.corners()?.let { c -> Box(c.minOf { it.first }.toDouble(), c.minOf { it.second }.toDouble(), c.maxOf { it.first }.toDouble(), c.maxOf { it.second }.toDouble()) } }!!
        assertEquals(1, clip.pictures.size)
        assertEquals(0f, clip.pictures[0].x, 0.01f) // moved so the group's corner is the origin
        val n = PageModel()
        val ids = n.paste(clip, 500f, 600f)
        assertEquals(1, ids.size)
        val q = n.pictures.single()
        assertTrue(q.id != p.id)
        assertEquals(p.sha, q.sha)
        assertEquals(500f, q.x, 0.01f); assertEquals(600f, q.y, 0.01f)
        assertEquals(p.width, q.width, 0.01f)
        assertEquals(listOf(q.id), n.order)
    }

    @Test
    fun draggingAHandleMovesOnlyThatEdgeAndTheRestStaysPut() {
        val p = Picture("i", 1000f, 1000f, 4000f, 2000f, "f.jpg", sha, 400, 200)
        // the right middle handle dragged in to a quarter of the width
        val a = p.cropDragged(5, 2000f, 1500f)
        assertEquals(0f, a.cropX, 1e-6f); assertEquals(0.25f, a.cropW, 1e-6f)
        assertEquals(1000f, a.x, 0.01f); assertEquals(1000f, a.y, 0.01f) // the top left did not move
        assertEquals(1000f, a.width, 0.01f); assertEquals(2000f, a.height, 0.01f)
        // then the top left corner dragged in: both its edges move, the bottom right stays where it is on the page
        val b = a.cropDragged(0, 1500f, 1500f)
        assertEquals(0.125f, b.cropX, 1e-6f); assertEquals(0.25f, b.cropY, 1e-6f)
        assertEquals(a.toPage(a.width, a.height).first, b.toPage(b.width, b.height).first, 0.01f)
        assertEquals(a.toPage(a.width, a.height).second, b.toPage(b.width, b.height).second, 0.01f)
        // dragging past the opposite edge stops a little short of it; past the file stops at the file
        val c = p.cropDragged(7, 99999f, 0f)
        assertEquals(1f - Picture.MIN_CROP, c.cropX, 1e-6f)
        val d = p.cropDragged(4, 0f, -99999f)
        assertEquals(0f, d.cropY, 1e-6f); assertEquals(1f, d.cropH, 1e-6f)
    }

    @Test
    fun aCropIsOneUndoStepAndWritesTheWholePicture() {
        val m = PageModel()
        val p = m.pic()
        m.log.clear()
        assertTrue(m.setPicture(p.cropped(0.1f, 0.1f, 0.5f, 0.5f)))
        assertEquals(1, m.log.count { it is Ev.PictureSet })
        assertTrue(m.undo())
        assertEquals(p, m.pictureOf(p.id))
        assertTrue(m.redo())
        assertEquals(0.5f, m.pictureOf(p.id)!!.cropW, 1e-6f)
    }
}
