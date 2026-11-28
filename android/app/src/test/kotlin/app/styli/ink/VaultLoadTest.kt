package app.styli.ink

import app.styli.ink.proto.Tool
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// The app reads the library without the ink first (quick), then each note's pages in the background. A note whose pages
// are not read yet, or could not be read, is never written: its page files stay exactly as they are.
class VaultLoadTest {
    private lateinit var dir: File
    private var now = 1_000L
    private fun vault() = Vault(dir, clock = { now++ })

    @Before fun setUp() { dir = Files.createTempDirectory("load").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun pts(vararg xy: Int) = xy.toList().chunked(2).mapIndexed { i, (x, y) -> Pt(x.toFloat(), y.toFloat(), i * 10L, 0.5f) }
    private fun ink(f: NoteFile, k: Int = 1) = f.notebook!!.current.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(k, k, k + 4, k + 4))

    /** A library on disk: folder F with note N (two strokes), and note Loose at the top. */
    private fun made() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F"); val n = l.newFile(f, "N"); ink(n); ink(n, 9)
        ink(l.newFile(l.root, "Loose"))
        assertTrue(v.save(l).isEmpty())
    }

    @Test fun theQuickLoadReadsNoInkThenEachNotesPagesArriveIntact() {
        made()
        val v = vault(); val l = v.load(pages = false)
        val n = l.root.folders.single().files.single()
        assertNull(n.notebook); assertEquals("N", n.name)
        assertTrue(v.attachPages(n, n.disk!!, v.readPages(n.disk!!)))
        assertEquals(2, n.notebook!!.current.items.size)
        assertNull(v.autosave(l, null)) // pages arriving is not a change to write
    }

    @Test fun aNoteWithUnreadablePagesStillListsWhenLoadedQuickly() {
        made()
        File(dir, "Loose.ink/page-001.stylog").writeBytes(byteArrayOf(1, 2, 3))
        val l = vault().load(pages = false)
        assertEquals(setOf("Loose"), l.root.files.map { it.name }.toSet()) // the full load would leave it out
    }

    @Test fun aNoteNotReadYetIsRenamedMovedTrashedAndRestoredWithoutItsPagesChanging() {
        made()
        val before = File(dir, "F/N.ink/page-001.stylog").readBytes()
        val v = vault(); val l = v.load(pages = false)
        val f = l.root.folders.single(); val n = f.files.single()
        l.renameFile(f, n, "N2"); v.autosave(l, null)
        l.moveFile(f, n, l.root); v.autosave(l, null)
        l.open(n); v.autosave(l, n); v.autosave(l, null) // opened (its info changes) and left
        l.delete(l.root, n); v.save(l)
        l.restore(l.trash.single()); v.save(l)
        assertArrayEquals(before, File(dir, "N2.ink/page-001.stylog").readBytes())
        val back = vault().load()
        assertEquals(2, back.root.files.first { it.name == "N2" }.notebook!!.current.items.size)
    }

    @Test fun aNoteWhosePagesCannotBeReadIsNeverWritten() {
        made()
        val bad = File(dir, "Loose.ink/page-001.stylog"); bad.writeBytes(byteArrayOf(1, 2, 3))
        val v = vault(); val l = v.load(pages = false)
        val loose = l.root.files.single()
        try { v.readPages(loose.disk!!); fail("the broken page should not read") } catch (e: IOException) { v.pagesFailed(loose, loose.disk!!, e.message) }
        assertNotNull(loose.loadError); assertNull(loose.notebook)
        l.open(loose); l.setPinned(loose, true); l.renameFile(l.root, loose, "Still loose")
        assertTrue(v.save(l).isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, "Still loose.ink/page-001.stylog").readBytes())
        assertEquals(1, File(dir, "Still loose.ink").list()!!.count { it.endsWith(".stylog") })
    }

    @Test fun pagesReadFromWhereANoteWasBeforeItMovedAreNotHandedOver() {
        made()
        val v = vault(); val l = v.load(pages = false)
        val loose = l.root.files.single(); val was = loose.disk!!
        val pages = v.readPages(was)
        l.renameFile(l.root, loose, "Renamed") // moved while its pages were being read
        assertFalse(v.attachPages(loose, was, pages))
        assertNull(loose.notebook)
        v.pagesFailed(loose, was, "gone") // a failure from the old place does not count either
        assertNull(loose.loadError)
        assertTrue(v.attachPages(loose, loose.disk!!, v.readPages(loose.disk!!)))
        assertEquals(1, loose.notebook!!.current.items.size)
    }

    @Test fun inkDrawnAfterThePagesArriveIsSaved() {
        made()
        val v = vault(); val l = v.load(pages = false)
        val loose = l.root.files.single()
        v.attachPages(loose, loose.disk!!, v.readPages(loose.disk!!))
        ink(loose, 20)
        assertTrue(v.save(l).isEmpty())
        assertEquals(2, vault().load().root.files.single().notebook!!.current.items.size)
    }

    @Test fun theTrashIsReadQuicklyTooAndARestoredNoteStillHasItsInk() {
        made()
        run { val v = vault(); val l = v.load(); l.delete(l.root, l.root.files.single()) }
        val v = vault(); val l = v.load(pages = false)
        val t = l.trash.single(); assertNull(t.file.notebook)
        l.restore(t); v.save(l)
        val back = vault().load()
        assertEquals(1, back.root.files.single().notebook!!.current.items.size)
    }
}
