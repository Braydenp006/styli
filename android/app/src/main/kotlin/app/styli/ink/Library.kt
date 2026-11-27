package app.styli.ink

import app.styli.ink.proto.PageSet
import app.styli.ink.proto.Template

/** What a file is. Only ink files can be made so far; Markdown and PDF are in the model for the filter and later screens. */
enum class FileKind { INK, MARKDOWN, PDF }

/** One file: a name, a kind, and for ink its pages ([notebook]). [opened] is when it was last opened. */
class NoteFile(
    var name: String,
    val kind: FileKind,
    val notebook: Notebook?, // null unless kind is INK
    val created: Long,
    var opened: Long,
    var pinned: Boolean = false,
)

/** A folder. Folders nest to any depth; [parent] is null only for the root. */
class Folder(var name: String, var color: Int? = null) { // color is ARGB, or null for the default
    var parent: Folder? = null
        internal set
    val folders = mutableListOf<Folder>()
    val files = mutableListOf<NoteFile>()
    var pinned = false

    /** This folder and every folder below it, with every file below. */
    fun allFolders(): List<Folder> = listOf(this) + folders.flatMap { it.allFolders() }
    fun allFiles(): List<Pair<Folder, NoteFile>> = allFolders().flatMap { f -> f.files.map { f to it } }
}

/** A deleted file, kept until it is brought back, with the folder path it came from (outermost first). */
class Trashed(val file: NoteFile, val from: List<String>, val at: Long)

/** The starting look of a new ink file. Each page can change it later; this is only the first page. */
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

enum class Sort { NAME, EDITED, CREATED }

/**
 * Every folder and file on this device, under one [root] (the Tabularium). In memory only for now (saving to disk
 * comes later), but nothing here touches Android, so it is tested on the JVM. Deletes go to [trash] first.
 */
class Library(private val clock: () -> Long = System::currentTimeMillis) {
    val root = Folder("Tabularium")
    val trash = mutableListOf<Trashed>()

    /** The top-level folders. */
    val folders: MutableList<Folder> get() = root.folders

    private fun clean(name: String) = name.trim().also { require(it.isNotEmpty()) { "a name cannot be empty" } }

    /** [wanted], or "wanted 2", "wanted 3"... if [taken] already holds it. */
    private fun unique(wanted: String, taken: Collection<String>): String {
        if (wanted !in taken) return wanted
        var n = 2
        while ("$wanted $n" in taken) n++
        return "$wanted $n"
    }

    /** True if [f] is in this library's tree. */
    fun contains(f: Folder): Boolean {
        var q: Folder? = f
        while (q != null) { if (q === root) return true; q = q.parent }
        return false
    }

    /** The names from the top down, such as ["Physics", "Labs"]; empty for the root. */
    fun path(f: Folder): List<String> {
        val out = ArrayDeque<String>()
        var q: Folder? = f
        while (q != null && q !== root) { out.addFirst(q.name); q = q.parent }
        return out.toList()
    }

    fun newFolder(name: String, color: Int? = null, parent: Folder = root): Folder {
        require(contains(parent)) { "not a folder of this library" }
        val f = Folder(unique(clean(name), parent.folders.map { it.name }), color)
        f.parent = parent
        parent.folders.add(f)
        return f
    }

    fun renameFolder(f: Folder, name: String) {
        require(f !== root) { "the root cannot be renamed" }
        f.name = unique(clean(name), f.parent!!.folders.filter { it !== f }.map { it.name })
    }

    /** New ink file in [folder]. */
    fun newFile(folder: Folder, name: String, look: NewFileLook = NewFileLook()): NoteFile =
        add(folder, name, FileKind.INK, Notebook(PageModel(look.pageSet())))

    /** A file of another kind (Markdown or PDF), for the filter and later screens. */
    fun addFile(folder: Folder, name: String, kind: FileKind): NoteFile {
        require(kind != FileKind.INK) { "use newFile for ink" }
        return add(folder, name, kind, null)
    }

    private fun add(folder: Folder, name: String, kind: FileKind, nb: Notebook?): NoteFile {
        require(contains(folder)) { "not a folder of this library" }
        val now = clock()
        val file = NoteFile(unique(clean(name), folder.files.map { it.name }), kind, nb, now, now)
        folder.files.add(file)
        return file
    }

    fun renameFile(folder: Folder, file: NoteFile, name: String) {
        file.name = unique(clean(name), folder.files.filter { it !== file }.map { it.name })
    }

    fun moveFile(from: Folder, file: NoteFile, to: Folder) {
        require(file in from.files && contains(to)) { "not in this library" }
        if (from === to) return
        from.files.remove(file)
        file.name = unique(file.name, to.files.map { it.name })
        to.files.add(file)
    }

    /** Moves [f] (with everything in it) into [to]. False, and nothing moves, if [to] is [f] or inside it. */
    fun moveFolder(f: Folder, to: Folder): Boolean {
        require(f !== root && contains(f) && contains(to)) { "not in this library" }
        if (to in f.allFolders()) return false
        if (f.parent === to) return true
        f.parent!!.folders.remove(f)
        f.name = unique(f.name, to.folders.map { it.name })
        f.parent = to
        to.folders.add(f)
        return true
    }

    /** Marks [file] as just opened (so it shows first in recents). */
    fun open(file: NoteFile) { file.opened = clock() }

    /** Sends a file to the trash. */
    fun delete(folder: Folder, file: NoteFile) {
        require(file in folder.files) { "not in this folder" }
        folder.files.remove(file)
        trash.add(Trashed(file, path(folder), clock()))
    }

    /** Sends every file below [folder] to the trash, then removes the folder and its subfolders. */
    fun deleteFolder(folder: Folder) {
        require(folder !== root && contains(folder)) { "not in this library" }
        val at = clock()
        for ((f, file) in folder.allFiles()) trash.add(Trashed(file, path(f), at))
        folder.parent!!.folders.remove(folder)
        folder.parent = null
    }

    /** Brings a trashed file back to the folder path it came from (made again where missing). */
    fun restore(t: Trashed): Folder {
        require(t in trash) { "not in the trash" }
        var folder = root
        for (n in t.from) folder = folder.folders.firstOrNull { it.name == n } ?: newFolder(n, parent = folder)
        t.file.name = unique(t.file.name, folder.files.map { it.name })
        folder.files.add(t.file)
        trash.remove(t)
        return folder
    }

    fun setPinned(f: Folder, pinned: Boolean) { f.pinned = pinned }
    fun setPinned(file: NoteFile, pinned: Boolean) { file.pinned = pinned }

    /** Every pinned folder, wherever it is in the tree. */
    fun pinnedFolders(): List<Folder> = root.allFolders().filter { it.pinned && it !== root }

    /** Every pinned file with its folder, wherever it is. */
    fun pinnedFiles(kind: FileKind? = null): List<Pair<Folder, NoteFile>> =
        root.allFiles().filter { it.second.pinned && (kind == null || it.second.kind == kind) }

    /** Every file with its folder, most recently opened first, at most [limit]. */
    fun recent(limit: Int = 10, kind: FileKind? = null): List<Pair<Folder, NoteFile>> =
        root.allFiles().filter { kind == null || it.second.kind == kind }.sortedByDescending { it.second.opened }.take(limit)

    /** Files whose name contains [query] (ignoring case), with their folders. */
    fun search(query: String, kind: FileKind? = null): List<Pair<Folder, NoteFile>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return root.allFiles().filter { it.second.name.contains(q, ignoreCase = true) && (kind == null || it.second.kind == kind) }
    }

    /** [files] in the order asked for: by name, most recently opened first, or newest created first. */
    fun sorted(files: List<NoteFile>, sort: Sort): List<NoteFile> = when (sort) {
        Sort.NAME -> files.sortedBy { it.name.lowercase() }
        Sort.EDITED -> files.sortedByDescending { it.opened }
        Sort.CREATED -> files.sortedByDescending { it.created }
    }
}
