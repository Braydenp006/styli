package app.styli.ink

import app.styli.ink.proto.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private var now = 1000L
    private val lib = Library { now++ }

    @Test fun newFileUsesTheChosenLook() {
        val f = lib.newFolder("Maths")
        val file = lib.newFile(f, "Week 1", NewFileLook(letter = true, landscape = true, infinite = false, template = Template.TEMPLATE_GRID))
        val p = file.notebook!!.current.page
        assertEquals(27940, p.width); assertEquals(21590, p.height)
        assertEquals(Template.TEMPLATE_GRID, p.template)
        assertEquals(FileKind.INK, file.kind)
    }

    @Test fun namesAreTrimmedAndUniqueInTheirFolder() {
        val f = lib.newFolder(" Maths ")
        assertEquals("Maths", f.name)
        assertEquals("Maths 2", lib.newFolder("Maths").name)
        assertEquals("Maths", lib.newFolder("Maths", parent = f).name) // another parent, no clash
        lib.newFile(f, "Notes")
        assertEquals("Notes 2", lib.newFile(f, "Notes").name)
        assertThrows(IllegalArgumentException::class.java) { lib.newFile(f, "   ") }
    }

    @Test fun renameKeepsOwnNameFree() {
        val f = lib.newFolder("A")
        val a = lib.newFile(f, "One")
        lib.newFile(f, "Two")
        lib.renameFile(f, a, "One") // its own name is not a clash
        assertEquals("One", a.name)
        lib.renameFile(f, a, "Two")
        assertEquals("Two 2", a.name)
    }

    @Test fun foldersNestAndHavePaths() {
        val a = lib.newFolder("Physics")
        val b = lib.newFolder("Labs", parent = a)
        val c = lib.newFolder("Week 3", parent = b)
        assertEquals(listOf("Physics", "Labs", "Week 3"), lib.path(c))
        assertTrue(lib.path(lib.root).isEmpty())
        assertEquals(listOf(a, b, c), a.allFolders())
    }

    @Test fun moveFolderRefusesItsOwnSubtree() {
        val a = lib.newFolder("A"); val b = lib.newFolder("B", parent = a); val c = lib.newFolder("C", parent = b)
        assertFalse(lib.moveFolder(a, a))
        assertFalse(lib.moveFolder(a, c))
        assertEquals(listOf("A"), lib.path(a))
        assertTrue(lib.moveFolder(c, lib.root)) // out to the top
        assertEquals(listOf("C"), lib.path(c)); assertTrue(b.folders.isEmpty())
        assertTrue(lib.moveFolder(b, c))
        assertEquals(listOf("C", "B"), lib.path(b))
    }

    @Test fun moveFolderRenamesOnClash() {
        val a = lib.newFolder("A"); val x = lib.newFolder("X"); val inner = lib.newFolder("X", parent = a)
        assertTrue(lib.moveFolder(x, a))
        assertEquals(listOf("X", "X 2"), a.folders.map { it.name })
        assertSame(a, x.parent); assertSame(a, inner.parent)
    }

    @Test fun deleteGoesToTrashAndRestoreBringsItBack() {
        val f = lib.newFolder("A")
        val file = lib.newFile(f, "One")
        lib.delete(f, file)
        assertTrue(f.files.isEmpty()); assertEquals(1, lib.trash.size)
        lib.deleteFolder(f) // the folder is gone too
        val back = lib.restore(lib.trash.single())
        assertEquals("A", back.name)
        assertSame(file, back.files.single())
        assertTrue(lib.trash.isEmpty())
    }

    @Test fun deletingAFolderTrashesTheWholeSubtreeAndRestoreRebuildsThePath() {
        val a = lib.newFolder("A"); val b = lib.newFolder("B", parent = a)
        val x = lib.newFile(a, "One"); val y = lib.newFile(b, "Deep")
        lib.deleteFolder(a)
        assertTrue(lib.folders.isEmpty()); assertFalse(lib.contains(b))
        assertEquals(2, lib.trash.size)
        val back = lib.restore(lib.trash.first { it.file === y })
        assertEquals(listOf("A", "B"), lib.path(back))
        assertSame(y, back.files.single())
        lib.restore(lib.trash.single())
        assertSame(x, lib.folders.single().files.single()) // joins the folder just remade
    }

    @Test fun purgeAndEmptyTrashRemoveOnlyWhatIsAsked() {
        val f = lib.newFolder("A")
        val one = lib.newFile(f, "One"); val two = lib.newFile(f, "Two"); val three = lib.newFile(f, "Three")
        lib.delete(f, one); lib.delete(f, two); lib.delete(f, three)
        lib.purge(lib.trash.first { it.file === two })
        assertEquals(setOf(one, three), lib.trash.map { it.file }.toSet())
        assertTrue(runCatching { lib.purge(Trashed(two, emptyList(), 0)) }.isFailure) // not in the trash any more
        lib.emptyTrash()
        assertTrue(lib.trash.isEmpty())
    }

    @Test fun moveFileRenamesOnClash() {
        val a = lib.newFolder("A"); val b = lib.newFolder("B")
        val x = lib.newFile(a, "Note"); lib.newFile(b, "Note")
        lib.moveFile(a, x, b)
        assertEquals(listOf("Note", "Note 2"), b.files.map { it.name })
        assertTrue(a.files.isEmpty())
    }

    @Test fun recentSearchAndKindFilter() {
        val f = lib.newFolder("A"); val g = lib.newFolder("G", parent = f)
        val one = lib.newFile(f, "Alpha"); val two = lib.newFile(g, "Beta")
        val md = lib.addFile(f, "Betamd", FileKind.MARKDOWN)
        assertEquals(listOf(md, two, one), lib.recent().map { it.second })
        lib.open(one)
        assertEquals(one, lib.recent().first().second)
        assertEquals(setOf(two, md), lib.search(" bet ").map { it.second }.toSet())
        assertEquals(listOf(md), lib.search("bet", FileKind.MARKDOWN).map { it.second })
        assertEquals(setOf(one, two), lib.recent(kind = FileKind.INK).map { it.second }.toSet())
        assertTrue(lib.search("").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { lib.addFile(f, "x", FileKind.INK) }
    }

    @Test fun pinsReachDeepItems() {
        val a = lib.newFolder("A"); val b = lib.newFolder("B", parent = a); val c = lib.newFolder("C", parent = b)
        val file = lib.newFile(c, "Deep"); lib.newFile(c, "Other")
        lib.setPinned(c, true); lib.setPinned(file, true)
        assertEquals(listOf(c), lib.pinnedFolders())
        assertEquals(listOf(file), lib.pinnedFiles().map { it.second })
        assertSame(c, lib.pinnedFiles().single().first)
        lib.setPinned(c, false)
        assertTrue(lib.pinnedFolders().isEmpty())
    }

    @Test fun sorting() {
        val f = lib.newFolder("A")
        val b = lib.newFile(f, "banana"); val a = lib.newFile(f, "Apple"); val c = lib.newFile(f, "cherry")
        lib.open(b)
        assertEquals(listOf(a, b, c), lib.sorted(f.files, Sort.NAME))
        assertEquals(listOf(b, c, a), lib.sorted(f.files, Sort.EDITED))
        assertEquals(listOf(c, a, b), lib.sorted(f.files, Sort.CREATED))
    }

    @Test fun folderColour() {
        val f = lib.newFolder("A", 0xFF4A78C2.toInt())
        assertEquals(0xFF4A78C2.toInt(), f.color)
        assertNull(lib.newFolder("B").color)
    }

    @Test fun agoText() {
        assertEquals("Edited just now", agoText(100_000, 70_000))
        assertEquals("Edited 5 min ago", agoText(600_000, 300_000))
        assertEquals("Edited 3 h ago", agoText(3 * 3_600_000L, 0))
        assertEquals("Edited 2 days ago", agoText(2 * 86_400_000L, 0))
    }
}
