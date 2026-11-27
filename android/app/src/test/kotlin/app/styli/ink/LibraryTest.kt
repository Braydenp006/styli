package app.styli.ink

import app.styli.ink.proto.Template
import org.junit.Assert.assertEquals
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
        val p = file.notebook.current.page
        assertEquals(27940, p.width); assertEquals(21590, p.height)
        assertEquals(Template.TEMPLATE_GRID, p.template)
    }

    @Test fun namesAreTrimmedAndUniqueInTheirFolder() {
        val f = lib.newFolder(" Maths ")
        assertEquals("Maths", f.name)
        assertEquals("Maths 2", lib.newFolder("Maths").name)
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

    @Test fun deletingAFolderTrashesItsFiles() {
        val f = lib.newFolder("A")
        lib.newFile(f, "One"); lib.newFile(f, "Two")
        lib.deleteFolder(f)
        assertTrue(lib.folders.isEmpty()); assertEquals(2, lib.trash.size)
    }

    @Test fun moveRenamesOnClash() {
        val a = lib.newFolder("A"); val b = lib.newFolder("B")
        val x = lib.newFile(a, "Note"); lib.newFile(b, "Note")
        lib.moveFile(a, x, b)
        assertEquals(listOf("Note", "Note 2"), b.files.map { it.name })
        assertTrue(a.files.isEmpty())
    }

    @Test fun recentAndSearch() {
        val f = lib.newFolder("A")
        val one = lib.newFile(f, "Alpha"); val two = lib.newFile(f, "Beta")
        assertEquals(listOf(two, one), lib.recent().map { it.second })
        lib.open(one)
        assertEquals(listOf(one, two), lib.recent().map { it.second })
        assertEquals(listOf(two), lib.search(" bet ").map { it.second })
        assertTrue(lib.search("").isEmpty())
    }

    @Test fun agoText() {
        assertEquals("Edited just now", agoText(100_000, 70_000))
        assertEquals("Edited 5 min ago", agoText(600_000, 300_000))
        assertEquals("Edited 3 h ago", agoText(3 * 3_600_000L, 0))
        assertEquals("Edited 2 days ago", agoText(2 * 86_400_000L, 0))
    }

    @Test fun folderColour() {
        val f = lib.newFolder("A", 0xFF4A78C2.toInt())
        assertEquals(0xFF4A78C2.toInt(), f.color)
        assertEquals(null, lib.newFolder("B").color)
    }
}
