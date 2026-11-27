package app.styli.ink

import app.styli.ink.proto.PageSet
import app.styli.ink.proto.Template

/** One handwritten file: its pages ([notebook]) plus a name and when it was made and last opened. */
class NoteFile(var name: String, val notebook: Notebook, val created: Long, var opened: Long)

/** A folder of files (a "notebook" to the user). */
class Folder(var name: String, var color: Int? = null) { // color is ARGB, or null for the default
    val files = mutableListOf<NoteFile>()
}

/** A deleted file, kept until it is brought back, with the folder it came from. */
class Trashed(val file: NoteFile, val from: String, val at: Long)

/** The starting look of a new file. Each page can change it later; this is only the first page. */
data class NewFileLook(
    val letter: Boolean = false,
    val landscape: Boolean = false,
    val infinite: Boolean = false,
    val template: Template = Template.TEMPLATE_LINED,
) {
    fun pageSet(): PageSet {
        val (w, h) = PageSizes.of(letter, landscape)
        return PageSet.newBuilder().setWidth(w).setHeight(h).setTemplate(template).setInfinite(infinite).build()
    }
}

/**
 * Every folder and file on this device. In memory only for now (saving to disk comes later), but nothing here
 * touches Android, so it is tested on the JVM. Deletes go to [trash] first and never lose a file.
 */
class Library(private val clock: () -> Long = System::currentTimeMillis) {
    val folders = mutableListOf<Folder>()
    val trash = mutableListOf<Trashed>()

    private fun clean(name: String) = name.trim().also { require(it.isNotEmpty()) { "a name cannot be empty" } }

    /** [wanted], or "wanted 2", "wanted 3"... if [taken] already holds it. */
    private fun unique(wanted: String, taken: Collection<String>): String {
        if (wanted !in taken) return wanted
        var n = 2
        while ("$wanted $n" in taken) n++
        return "$wanted $n"
    }

    fun newFolder(name: String, color: Int? = null): Folder =
        Folder(unique(clean(name), folders.map { it.name }), color).also { folders.add(it) }

    fun renameFolder(f: Folder, name: String) {
        f.name = unique(clean(name), folders.filter { it !== f }.map { it.name })
    }

    fun newFile(folder: Folder, name: String, look: NewFileLook = NewFileLook()): NoteFile {
        require(folder in folders) { "not a folder of this library" }
        val now = clock()
        val file = NoteFile(unique(clean(name), folder.files.map { it.name }), Notebook(PageModel(look.pageSet())), now, now)
        folder.files.add(file)
        return file
    }

    fun renameFile(folder: Folder, file: NoteFile, name: String) {
        file.name = unique(clean(name), folder.files.filter { it !== file }.map { it.name })
    }

    fun moveFile(from: Folder, file: NoteFile, to: Folder) {
        require(file in from.files && to in folders) { "not in this library" }
        if (from === to) return
        from.files.remove(file)
        file.name = unique(file.name, to.files.map { it.name })
        to.files.add(file)
    }

    /** Marks [file] as just opened (so it shows first in recents). */
    fun open(file: NoteFile) { file.opened = clock() }

    /** Sends a file to the trash. */
    fun delete(folder: Folder, file: NoteFile) {
        require(file in folder.files) { "not in this folder" }
        folder.files.remove(file)
        trash.add(Trashed(file, folder.name, clock()))
    }

    /** Sends a folder's files to the trash and removes the folder. */
    fun deleteFolder(folder: Folder) {
        folders.remove(folder)
        folder.files.forEach { trash.add(Trashed(it, folder.name, clock())) }
        folder.files.clear()
    }

    /** Brings a trashed file back to the folder it came from (made again if that folder is gone). */
    fun restore(t: Trashed): Folder {
        require(t in trash) { "not in the trash" }
        val folder = folders.firstOrNull { it.name == t.from } ?: newFolder(t.from)
        t.file.name = unique(t.file.name, folder.files.map { it.name })
        folder.files.add(t.file)
        trash.remove(t)
        return folder
    }

    /** Every file with its folder, most recently opened first, at most [limit]. */
    fun recent(limit: Int = 10): List<Pair<Folder, NoteFile>> =
        folders.flatMap { f -> f.files.map { f to it } }.sortedByDescending { it.second.opened }.take(limit)

    /** Files whose name contains [query] (ignoring case), with their folders. */
    fun search(query: String): List<Pair<Folder, NoteFile>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return folders.flatMap { f -> f.files.map { f to it } }.filter { it.second.name.contains(q, ignoreCase = true) }
    }
}
