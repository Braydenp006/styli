package app.styli.ink

import app.styli.ink.proto.Tool
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Folders and notes change on disk the moment they are asked to (Vault as the Library's Store), and memory only after.
class VaultStoreTest {
    private lateinit var dir: File
    private var now = 1_000L
    private fun vault() = Vault(dir, clock = { now++ })

    @Before fun setUp() { dir = Files.createTempDirectory("store").toFile() }
    @After fun tearDown() { dir.walkTopDown().forEach { it.setWritable(true) }; dir.deleteRecursively() }

    private fun pts(vararg xy: Int) = xy.toList().chunked(2).mapIndexed { i, (x, y) -> Pt(x.toFloat(), y.toFloat(), i * 10L, 0.5f) }
    private fun ink(f: NoteFile, k: Int = 1) = f.notebook!!.current.add(Tool.TOOL_PEN, 0xFF000000.toInt(), 40, pts(k, k, k + 4, k + 4))
    private fun reload() = vault().load()

    @Test fun makingRenamingAndMovingHappenOnDiskAtOnce() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F")
        val n = l.newFile(f, "N")
        assertTrue(File(dir, "F/N.ink/file.json").exists()) // no save was asked for
        l.renameFile(f, n, "N2")
        assertTrue(File(dir, "F/N2.ink").isDirectory); assertFalse(File(dir, "F/N.ink").exists())
        l.renameFolder(f, "G")
        assertTrue(File(dir, "G/N2.ink").isDirectory); assertFalse(File(dir, "F").exists())
        l.moveFile(f, n, l.root)
        assertTrue(File(dir, "N2.ink").isDirectory)
        v.autosave(l, null); v.flush() // a note is listed once its first page is written
        val back = reload()
        assertEquals(listOf("N2"), back.root.files.map { it.name })
        assertEquals(listOf("G"), back.root.folders.map { it.name }); assertTrue(back.root.folders.single().files.isEmpty())
    }

    @Test fun inkFollowsANoteThatWasRenamedOrMoved() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F"); val n = l.newFile(f, "N")
        ink(n); assertTrue(v.autosave(l, null)!!.isEmpty()); v.flush()
        ink(n, 9) // not saved yet when it moves
        l.renameFolder(f, "G"); l.moveFile(f, n, l.root); l.renameFile(l.root, n, "Moved")
        v.autosave(l, null); v.flush()
        assertEquals(2, reload().root.files.single().notebook!!.current.items.size)
    }

    @Test fun aRefusedChangeLeavesMemoryAndDiskAsTheyWere() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F"); val n = l.newFile(f, "N")
        File(dir, "F").setWritable(false) // nothing inside can be renamed
        try { l.renameFile(f, n, "X"); fail("the rename should be refused") } catch (_: IOException) {}
        try { l.moveFile(f, n, l.root); fail("the move should be refused") } catch (_: IOException) {}
        File(dir, "F").setWritable(true)
        assertEquals("N", n.name); assertEquals(listOf(n), f.files)
        assertTrue(File(dir, "F/N.ink").isDirectory)
        v.autosave(l, null); v.flush()
        assertEquals(listOf("N"), reload().root.folders.single().files.map { it.name })
    }

    @Test fun aDeletedNoteGoesToTheTrashWithItsUnsavedInkAndComesBack() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F"); val n = l.newFile(f, "N")
        ink(n) // drawn a moment ago: autosave has not written it
        l.delete(f, n)
        assertFalse(File(dir, "F/N.ink").exists())
        val back = reload()
        assertEquals(listOf("N"), back.trash.map { it.file.name }); assertEquals(listOf("F"), back.trash.single().from)
        assertEquals(1, back.trash.single().file.notebook!!.current.items.size)
        v.autosave(l, null); v.save(l) // neither may drop the trash's note of where it came from
        assertEquals(1, reload().trash.size)
        l.restore(l.trash.single())
        assertTrue(File(dir, "F/N.ink/file.json").exists()); assertFalse(File(dir, "F/N.ink/trash.json").exists())
        val again = reload()
        assertTrue(again.trash.isEmpty())
        assertEquals(1, again.root.folders.single().files.single().notebook!!.current.items.size)
    }

    @Test fun deletingAFolderTrashesEveryNoteInItThenRemovesTheFolders() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F"); val sub = l.newFolder("Sub", parent = f)
        l.newFile(f, "A"); ink(l.newFile(sub, "B"))
        v.autosave(l, null); v.flush()
        l.deleteFolder(f)
        assertFalse(File(dir, "F").exists())
        val back = reload()
        assertTrue(back.root.folders.isEmpty())
        assertEquals(mapOf("A" to listOf("F"), "B" to listOf("F", "Sub")), back.trash.associate { it.file.name to it.from })
        assertEquals(1, back.trash.first { it.file.name == "B" }.file.notebook!!.current.items.size)
    }

    @Test fun deletingForGoodOnlyEverRemovesSomethingInTheTrash() {
        val v = vault(); val l = v.load()
        val keep = l.newFile(l.root, "Keep"); val gone = l.newFile(l.root, "Gone")
        l.delete(l.root, gone)
        l.purge(l.trash.single())
        assertTrue(File(dir, ".trash").list()!!.isEmpty())
        // A file whose place is not in the trash is never deleted, whatever the screen asks.
        l.delete(l.root, keep)
        l.trash.single().file.disk = File(dir, "Elsewhere.ink").also { it.mkdirs() }
        try { l.emptyTrash(); fail("deleting outside the trash should be refused") } catch (_: IOException) {}
        assertTrue(File(dir, "Elsewhere.ink").isDirectory); assertEquals(1, l.trash.size)
    }

    @Test fun inkStillOnItsWayIsNotLostWhenTheNotesFolderIsRenamed() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F"); val n = l.newFile(f, "N")
        val gate = java.util.concurrent.CountDownLatch(1)
        v.beforeWrite = { gate.await() }
        ink(n); v.autosave(l, null) // handed to the writer, which waits
        Thread { Thread.sleep(100); gate.countDown() }.start()
        l.renameFolder(f, "G") // waits for the write, then renames
        v.beforeWrite = null
        v.autosave(l, null); v.flush()
        val back = reload()
        assertEquals("G", back.root.folders.single().name)
        assertEquals(1, back.root.folders.single().files.single().notebook!!.current.items.size)
    }

    @Test fun afterAFolderChangeTheAutosaveHasNothingLeftToDo() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F"); val g = l.newFolder("G")
        assertNull(v.autosave(l, null))
        l.renameFolder(f, "F2"); assertNull(v.autosave(l, null))
        l.moveFolder(g, f); assertNull(v.autosave(l, null))
        assertEquals(listOf("G"), reload().root.folders.single().folders.map { it.name })
    }

    @Test fun aCrashBetweenTheRenameAndItsInfoFileStillLoadsTheNote() {
        val v = vault(); val l = v.load()
        val n = l.newFile(l.root, "N"); ink(n); v.autosave(l, null); v.flush()
        val info = File(dir, "N.ink/file.json").readText()
        l.renameFile(l.root, n, "Renamed")
        File(dir, "Renamed.ink/file.json").writeText(info) // as if the app died before writing the new name
        val back = reload().root.files.single()
        assertEquals("N", back.name) // the old name, in the new place
        assertEquals(1, back.notebook!!.current.items.size)
    }

    @Test fun twoNotesWithOneNameEndUpSideBySide() {
        val v = vault(); val l = v.load()
        val f = l.newFolder("F"); val a = l.newFile(l.root, "Same"); l.newFile(f, "Same")
        l.moveFile(l.root, a, f)
        assertEquals(setOf("Same", "Same 2"), f.files.map { it.name }.toSet())
        assertEquals(2, File(dir, "F").list()!!.count { it.endsWith(".ink") })
        v.autosave(l, null); v.flush()
        assertEquals(setOf("Same", "Same 2"), reload().root.folders.single().files.map { it.name }.toSet())
    }
}
