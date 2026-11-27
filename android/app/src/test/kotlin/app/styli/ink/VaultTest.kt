package app.styli.ink

import app.styli.ink.proto.Tool
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Saves a library to a temporary folder, reads it back with a new Vault (as if the app restarted), and checks the files on disk.
class VaultTest {
    private lateinit var dir: File
    private var now = 1_000L
    private fun lib() = Library { now++ }
    private fun vault() = Vault(dir, clock = { now++ })

    @Before fun setUp() { dir = Files.createTempDirectory("vault").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun pts(vararg xy: Int) = xy.toList().chunked(2).mapIndexed { i, (x, y) -> Pt(x.toFloat(), y.toFloat(), i * 10L, 0.5f) }

    private fun reload(): Library = vault().load()

    @Test fun foldersFilesAndTheirDetailsSurviveARestart() {
        val l = lib(); val v = vault()
        val physics = l.newFolder("Physics", 0xFF4A78C2.toInt()); val labs = l.newFolder("Labs", parent = physics)
        val a = l.newFile(physics, "Lecture 1"); val b = l.newFile(labs, "Pendulum"); val c = l.newFile(l.root, "Loose")
        l.setPinned(a, true); l.setPinned(physics, true)
        a.notebook!!.current.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(100, 100, 200, 220))
        assertTrue(v.save(l).isEmpty())
        val back = reload()
        assertEquals(listOf("Physics"), back.root.folders.map { it.name })
        val p = back.root.folders.single()
        assertEquals(0xFF4A78C2.toInt(), p.color); assertTrue(p.pinned)
        assertEquals(listOf("Labs"), p.folders.map { it.name })
        assertEquals(listOf("Lecture 1"), p.files.map { it.name }); assertTrue(p.files.single().pinned)
        assertEquals(1, p.files.single().notebook!!.current.items.size)
        assertEquals(listOf("Pendulum"), p.folders.single().files.map { it.name })
        assertEquals(listOf("Loose"), back.root.files.map { it.name })
        assertEquals(a.created, p.files.single().created)
    }

    @Test fun editsAreAppendedNotRewrittenAndUndoneStrokesStayUndone() {
        val l = lib(); val v = vault()
        val f = l.newFile(l.root, "N"); val page = f.notebook!!.current
        page.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 5, 5))
        v.save(l)
        val log = File(dir, "N.ink/page-001.stylog")
        val size1 = log.length()
        val s = page.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(9, 9, 12, 12))
        v.save(l)
        assertTrue(log.length() > size1)
        page.undo(); v.save(l) // the undo is written as an erase, the log only grows
        val got = reload().root.files.single().notebook!!.current
        assertEquals(1, got.items.size); assertFalse(got.items.any { it.id == s.id })
    }

    @Test fun renamingAndMovingMoveTheFilesOnDisk() {
        val l = lib(); val v = vault()
        val a = l.newFolder("A"); val b = l.newFolder("B")
        val f = l.newFile(a, "Note"); f.notebook!!.current.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 5, 5))
        v.save(l)
        l.renameFile(a, f, "Renamed"); l.moveFile(a, f, b); l.renameFolder(b, "B2")
        v.save(l)
        assertFalse(File(dir, "A/Note.ink").exists()); assertTrue(File(dir, "B2/Renamed.ink/page-001.stylog").exists())
        assertEquals(listOf("Renamed"), reload().root.folders.first { it.name == "B2" }.files.map { it.name })
        l.moveFolder(b, a); v.save(l) // a folder with a file in it moves as one
        assertTrue(File(dir, "A/B2/Renamed.ink/page-001.stylog").exists())
        l.renameFile(b, f, "Again"); v.save(l) // and what is inside still follows
        assertTrue(File(dir, "A/B2/Again.ink").isDirectory)
    }

    @Test fun deletingMovesToTrashAndRestoreAndPurgeWork() {
        val l = lib(); val v = vault()
        val a = l.newFolder("A"); val keep = l.newFile(a, "Keep"); val gone = l.newFile(a, "Gone")
        keep.notebook!!.current.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 5, 5))
        v.save(l)
        l.delete(a, keep); l.deleteFolder(a) // the folder goes too, with Gone inside it
        assertTrue(v.save(l).isEmpty())
        assertFalse(File(dir, "A").exists())
        assertEquals(2, File(dir, ".trash").listFiles()!!.size)
        val back = reload()
        assertEquals(setOf("Keep", "Gone"), back.trash.map { it.file.name }.toSet())
        assertEquals(listOf("A"), back.trash.first { it.file.name == "Keep" }.from)
        val vv = vault(); val l2 = vv.load()
        l2.restore(l2.trash.first { it.file.name == "Keep" }); vv.save(l2)
        assertTrue(File(dir, "A/Keep.ink/page-001.stylog").exists()); assertFalse(File(dir, "A/Keep.ink/trash.json").exists())
        assertEquals(1, l2.trash.size)
        l2.purge(l2.trash.single()); vv.save(l2)
        assertEquals(0, File(dir, ".trash").listFiles()!!.size)
        assertEquals(1, vault().load().root.folders.single().files.size) // Keep is still there
    }

    @Test fun aDeletedPageKeepsItsFileAndAnUndoBringsItBack() {
        val l = lib(); val v = vault()
        val f = l.newFile(l.root, "N"); val nb = f.notebook!!
        val p1 = nb.current; p1.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 5, 5))
        val p2 = nb.add(p1, Dir.RIGHT)!!; p2.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(7, 7, 9, 9))
        v.save(l)
        assertEquals(2, reload().root.files.single().notebook!!.pages.size)
        nb.deletePage(p2); v.save(l)
        assertTrue(File(dir, "N.ink/.deleted/page-002.stylog").exists())
        assertEquals(1, reload().root.files.single().notebook!!.pages.size)
        nb.undoPage(); v.save(l)
        assertEquals(2, reload().root.files.single().notebook!!.pages.size)
    }

    @Test fun oddNamesAreSafeAndNeverClash() {
        val l = lib(); val v = vault()
        l.newFile(l.root, "a/b"); l.newFile(l.root, "a_b"); l.newFile(l.root, ".hidden")
        assertTrue(v.save(l).isEmpty())
        val back = reload()
        assertEquals(setOf("a/b", "a_b", ".hidden"), back.root.files.map { it.name }.toSet())
        assertEquals(3, back.root.files.size)
    }

    @Test fun anUnreadableFileIsLeftAloneAndReported() {
        val l = lib(); val v = vault()
        l.newFile(l.root, "Fine"); l.newFile(l.root, "Broken")
        v.save(l)
        val broken = File(dir, "Broken.ink/page-001.stylog")
        broken.writeBytes(byteArrayOf(1, 2, 3, 4))
        val v2 = vault(); val back = v2.load()
        assertEquals(listOf("Fine"), back.root.files.map { it.name })
        assertEquals(1, v2.problems.size)
        assertTrue(broken.exists()) // never touched
    }

    @Test fun cleanSavesChangeNothingAndNumbersNeverRepeat() {
        val l = lib(); val v = vault()
        val f = l.newFile(l.root, "N"); f.notebook!!.current.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 5, 5))
        v.save(l); assertFalse(v.dirty(l))
        val seq1 = File(dir, ".styli/seq").readText().toLong()
        val l2 = vault().load(); val v2 = vault()
        l2.root.files.single().notebook!!.current.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(2, 2, 6, 6))
        v2.save(l2)
        val numbers = File(dir, "N.ink/page-001.stylog").readBytes().let { InkLogReader.read(it).lastSeq["george"] }
        assertNotNull(numbers); assertTrue(numbers!! >= seq1) // the second run carried on after the first one's numbers
    }

    @Test fun anEmptyOrMissingFolderLoadsAsAnEmptyLibrary() {
        val l = Vault(File(dir, "nothing-here")).load()
        assertTrue(l.root.folders.isEmpty() && l.root.files.isEmpty() && l.trash.isEmpty())
        assertNull(l.root.disk)
    }

    @Test fun aPictureAndItsFileComeBack() {
        val l = lib(); val v = vault()
        val f = l.newFile(l.root, "Pics"); val nb = f.notebook!!
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        val sha = nb.pictureFiles.put(bytes)
        nb.current.addPicture { Picture(it, 0f, 0f, 500f, 400f, "$sha.jpg", sha, 50, 40) }
        assertTrue(v.save(l).isEmpty())
        assertTrue(File(dir, "Pics.ink/pictures/$sha.jpg").exists())
        val back = reload().root.files.single().notebook!!
        assertEquals(sha, back.current.pictures.single().sha)
        assertTrue(bytes.contentEquals(back.pictureFiles.get(sha)!!))
    }

    @Test fun aPictureWhoseFileIsMissingIsReportedAndStaysUnsaved() {
        val l = lib(); val v = vault()
        val f = l.newFile(l.root, "Pics")
        f.notebook!!.current.addPicture { Picture(it, 0f, 0f, 500f, 400f, "zz.jpg", "zz".repeat(32), 50, 40) } // its bytes were never stored
        assertEquals(1, v.save(l).size)
        assertTrue(v.dirty(l)) // still waiting, tried again next time
    }

    @Test fun settingsSurviveARestartAndBadValuesAreIgnored() {
        val v = vault(); val s = AppSettings()
        s.pLines = 0; s.pHold = 3; s.solid = true; s.gridScroll = false; s.scroll = 1
        v.writeSettings(s.snapshot())
        val back = AppSettings().also { b -> vault().readSettings()?.let(b::restore) }
        assertEquals(s.snapshot(), back.snapshot())
        val odd = AppSettings(); odd.restore(mapOf("pLines" to 9L, "pHold" to -1L, "solid" to "yes", "scroll" to 3L))
        assertEquals(2, odd.pLines); assertEquals(1, odd.pHold); assertFalse(odd.solid); assertEquals(3, odd.scroll)
        assertNull(vault().let { File(dir, ".styli/settings.json").delete(); it.readSettings() })
    }

    @Test fun clipsAndTheirPicturesSurviveARestartInOrder() {
        val m = PageModel()
        val a = m.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(100, 100, 200, 220))
        val t = m.addText(300f, 400f, 2000f, 400f, "hello")
        val tb = m.addTable(500f, 500f, 6000f, 3000f, 3, 4)!!
        val pic = m.addPicture { Picture(it, 10f, 10f, 800f, 600f, "ab.jpg", "ab".repeat(32), 80, 60) }
        m.arrange(setOf(a.id), Arrange.FRONT)
        val clip = Clip.of("My clip", m, setOf(a.id, t.id, tb.id, pic.id)) { Box(0.0, 0.0, 1000.0, 800.0) }!!
        val shelf = ClipShelf(); val added = shelf.add(clip); shelf.pictures.put(byteArrayOf(1, 2, 3))
        val v = vault()
        v.saveClips(shelf)
        val back = vault().loadClips()
        assertEquals(1, back.clips.size)
        val c = back.clips.single()
        assertEquals("My clip", c.name); assertEquals(added.order, c.order)
        assertEquals(added.items.map { it.id }, c.items.map { it.id }); assertEquals(added.tables.map { it.id }, c.tables.map { it.id })
        assertEquals(added.width, c.width); assertEquals(added.height, c.height)
        // renaming and deleting rewrite the files: no stale clip is left to come back
        shelf.rename(shelf.clips.single(), "Renamed"); v.saveClips(shelf)
        assertEquals("Renamed", vault().loadClips().clips.single().name)
        shelf.remove(shelf.clips.single()); v.saveClips(shelf)
        assertEquals(0, vault().loadClips().clips.size)
        assertEquals(emptyList<String>(), File(dir, ".styli/clips").list { _, n -> n.startsWith("clip-") }!!.toList())
    }

    @Test fun penSettingsAreKept() {
        val s = AppSettings(); s.penColor = 0xFFAA0000.toInt(); s.penWidth = 80; s.highlighterColor = 0x66112233; s.highlighterWidth = 600
        vault().writeSettings(s.snapshot())
        val b = AppSettings().also { x -> vault().readSettings()?.let(x::restore) }
        assertEquals(s.snapshot(), b.snapshot())
        val odd = AppSettings(); odd.restore(mapOf("penWidth" to 0L, "highlighterWidth" to 99999L))
        assertEquals(40, odd.penWidth); assertEquals(400, odd.highlighterWidth)
    }

    @Test fun aNotesOwnToolViewAndPensAreKeptWithIt() {
        val l = lib(); val v = vault()
        val f = l.newFile(l.root, "N"); l.newFile(l.root, "Other")
        f.view = mapOf("tool" to "LASSO", "zoom" to 1500L, "left" to -300L, "top" to 12000L, "penColor" to 0xFFAA0000.toInt(), "penWidth" to 60)
        assertTrue(v.dirty(l)); v.save(l); assertFalse(v.dirty(l))
        val back = reload().root.files
        val n = back.first { it.name == "N" }; val o = back.first { it.name == "Other" }
        assertEquals(InkCanvasView.Tool.LASSO, NoteView.tool(n.view))
        val vs = NoteView.view(n.view)!!
        assertEquals(1.5, vs.zoom, 0.0); assertEquals(-300.0, vs.left, 0.0); assertEquals(12000.0, vs.top, 0.0)
        assertEquals(0xFFAA0000.toInt(), (n.view!!["penColor"] as Long).toInt())
        assertNull(o.view); assertNull(NoteView.tool(o.view)); assertNull(NoteView.view(o.view)) // a note never left has nothing kept
        assertNull(NoteView.view(mapOf("zoom" to 0L, "left" to 0L, "top" to 0L))) // a zoom of nothing is ignored
        assertNull(NoteView.tool(mapOf("tool" to "NOT_A_TOOL")))
        f.view = f.view!! + ("top" to 13000L); assertTrue(v.dirty(l)) // moving the view makes it save again
    }

    @Test fun penDefaultsAndStartingViewSettingsAreKept() {
        val s = AppSettings(); s.pens = 1; s.defPenColor = 0xFF1B3A8A.toInt(); s.defPenWidth = 60; s.defHighlighterColor = 0x9969F0AE.toInt(); s.defHighlighterWidth = 600; s.startFit = true
        vault().writeSettings(s.snapshot())
        val b = AppSettings().also { x -> vault().readSettings()?.let(x::restore) }
        assertEquals(s.snapshot(), b.snapshot())
        val odd = AppSettings(); odd.restore(mapOf("pens" to 7L, "defPenWidth" to 0L))
        assertEquals(0, odd.pens); assertEquals(40, odd.defPenWidth)
    }

    @Test fun aPickedFolderBecomesAPathAndOddOnesAreRefused() {
        val primary = File("/storage/emulated/0")
        assertEquals(File(primary, "Documents/Notes"), Storage.treeIdToFile("primary:Documents/Notes", primary))
        assertEquals(primary, Storage.treeIdToFile("primary:", primary))
        assertEquals(File("/storage/1A2B-3C4D/Notes"), Storage.treeIdToFile("1A2B-3C4D:Notes", primary))
        assertNull(Storage.treeIdToFile("raw:/data/x", primary)) // not a storage volume
        assertNull(Storage.treeIdToFile("primary:a/../../etc", primary))
        assertNull(Storage.treeIdToFile("nocolon", primary))
        assertTrue(Storage.overlaps(File(dir, "a"), File(dir, "a/b"))); assertTrue(Storage.overlaps(File(dir, "a/b"), File(dir, "a")))
        assertFalse(Storage.overlaps(File(dir, "a"), File(dir, "ab"))) // a name that only starts the same is another folder
    }

    @Test fun movingCopiesEverythingAndLeavesTheOldFolderAlone() {
        val l = lib(); val v = vault()
        val f = l.newFile(l.newFolder("A"), "N"); f.notebook!!.current.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(1, 1, 5, 5))
        l.delete(l.root.folders.single(), f)
        v.save(l); v.writeSettings(AppSettings().snapshot())
        val dest = File(Files.createTempDirectory("moved").toFile(), "Notes")
        try {
            assertNull(v.copyTo(dest))
            val back = Vault(dest).load()
            assertEquals(1, back.trash.size); assertEquals(1, back.trash.single().file.notebook!!.current.items.size)
            assertNotNull(Vault(dest).readSettings())
            assertTrue(File(dir, ".trash").list()!!.isNotEmpty()) // the old folder is untouched
            assertNotNull(v.copyTo(dest)) // a folder that now has notes in it is refused, not merged
        } finally { dest.parentFile.deleteRecursively() }
    }

    @Test fun smallJsonRoundTrips() {
        val m = mapOf("name" to "A \"q\"\n\\ é", "n" to 5_000_000_000L, "b" to true, "x" to null, "l" to listOf("a", "b c"), "e" to emptyList<String>())
        assertEquals(m, MiniJson.parse(MiniJson.write(m)))
    }
}
