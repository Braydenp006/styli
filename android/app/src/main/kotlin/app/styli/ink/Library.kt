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
) {
    /** Where this file is on disk now (set and moved by [Vault]); null until it is first saved. */
    internal var disk: java.io.File? = null
    /** What is kept for this note beside its pages (see [NoteView]); null if nothing yet. */
    var view: Map<String, Any?>? = null

    /** The page file name of each page of [notebook] (set by [Vault]). */
    internal val pageNames = java.util.IdentityHashMap<PageModel, String>()
}

/** A folder. Folders nest to any depth; [parent] is null only for the root. */
class Folder(var name: String, var color: Int? = null) { // color is ARGB, or null for the default
    var parent: Folder? = null
        internal set
    val folders = mutableListOf<Folder>()
    val files = mutableListOf<NoteFile>()
    var pinned = false

    /** Where this folder is on disk now (set and moved by [Vault]); null until it is first saved. */
    internal var disk: java.io.File? = null

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
 * Where the library is kept ([Vault] on the device). Each call makes its change on disk first and throws
 * [java.io.IOException] if it cannot; the [Library] changes itself only after the call returns, so a refused change leaves
 * memory and disk as they were. [begin] and [settled] wrap each change, so the store can note what is already on disk.
 */
interface Store {
    fun begin(folders: List<Folder>, trash: Boolean)
    fun settled()
    /** Makes [f]'s folder (a new one too) be called [name] inside [to]. */
    fun folderAt(f: Folder, to: Folder, name: String)
    /** Makes [file]'s folder (a new one, or one in the trash, too) be called [name] inside [to]. */
    fun fileAt(file: NoteFile, to: Folder, name: String)
    /** Moves [file] into the trash, noting the folder path it came from and when. */
    fun toTrash(file: NoteFile, from: List<String>, at: Long)
    /** Removes [f]'s now empty folder (its files are in the trash by now). One that holds something else is left. */
    fun removeFolder(f: Folder)
    /** Deletes a trashed file for good. */
    fun purge(file: NoteFile)
}

/**
 * Every folder and file on this device, under one [root] (the Tabularium). In memory only for now (saving to disk
 * comes later), but nothing here touches Android, so it is tested on the JVM. Deletes go to [trash] first.
 */
class Library(private val clock: () -> Long = System::currentTimeMillis) {
    val root = Folder("Tabularium")
    val trash = mutableListOf<Trashed>()

    /** Where changes are made on the spot; null keeps the library in memory only (the tests, and [Vault.save] catching up). */
    var store: Store? = null

    private inline fun <T> change(vararg folders: Folder?, trash: Boolean = false, block: () -> T): T {
        val s = store ?: return block()
        s.begin(folders.filterNotNull(), trash)
        try { return block() } finally { s.settled() }
    }

    /** Folders and files that are gone for good, waiting for [Vault] to remove them from disk (only without a [store]). */
    internal val goneFolders = mutableListOf<Folder>()
    internal val purged = mutableListOf<NoteFile>()

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
        return change(parent) {
            store?.folderAt(f, parent, f.name)
            f.parent = parent
            parent.folders.add(f)
            f
        }
    }

    fun renameFolder(f: Folder, name: String) {
        require(f !== root) { "the root cannot be renamed" }
        val n = unique(clean(name), f.parent!!.folders.filter { it !== f }.map { it.name })
        change(f.parent) { store?.folderAt(f, f.parent!!, n); f.name = n }
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
        return change(folder) { store?.fileAt(file, folder, file.name); folder.files.add(file); file }
    }

    fun renameFile(folder: Folder, file: NoteFile, name: String) {
        val n = unique(clean(name), folder.files.filter { it !== file }.map { it.name })
        change(folder) { store?.fileAt(file, folder, n); file.name = n }
    }

    fun moveFile(from: Folder, file: NoteFile, to: Folder) {
        require(file in from.files && contains(to)) { "not in this library" }
        if (from === to) return
        val n = unique(file.name, to.files.map { it.name })
        change(from, to) {
            store?.fileAt(file, to, n)
            from.files.remove(file)
            file.name = n
            to.files.add(file)
        }
    }

    /** Moves [f] (with everything in it) into [to]. False, and nothing moves, if [to] is [f] or inside it. */
    fun moveFolder(f: Folder, to: Folder): Boolean {
        require(f !== root && contains(f) && contains(to)) { "not in this library" }
        if (to in f.allFolders()) return false
        if (f.parent === to) return true
        val n = unique(f.name, to.folders.map { it.name })
        change(f.parent, to) {
            store?.folderAt(f, to, n)
            f.parent!!.folders.remove(f)
            f.name = n
            f.parent = to
            to.folders.add(f)
        }
        return true
    }

    /** Marks [file] as just opened (so it shows first in recents). */
    fun open(file: NoteFile) { file.opened = clock() }

    /** Sends a file to the trash. */
    fun delete(folder: Folder, file: NoteFile) {
        require(file in folder.files) { "not in this folder" }
        val from = path(folder); val at = clock()
        change(folder, trash = true) {
            store?.toTrash(file, from, at)
            folder.files.remove(file)
            trash.add(Trashed(file, from, at))
        }
    }

    /** Sends every file below [folder] to the trash, then removes the folder and its subfolders. */
    fun deleteFolder(folder: Folder) {
        require(folder !== root && contains(folder)) { "not in this library" }
        val at = clock()
        val parent = folder.parent!!
        change(parent, trash = true) {
            // One file at a time: if one cannot move, the ones before it are in the trash and the rest stay where they are.
            for ((f, file) in folder.allFiles()) {
                val from = path(f)
                store?.toTrash(file, from, at)
                f.files.remove(file)
                trash.add(Trashed(file, from, at))
            }
            val all = folder.allFolders().asReversed() // deepest first, so each is empty when its turn comes
            val s = store
            if (s != null) all.forEach(s::removeFolder) else goneFolders.addAll(all)
            parent.folders.remove(folder)
            folder.parent = null
        }
    }

    /** Brings a trashed file back to the folder path it came from (made again where missing). */
    fun restore(t: Trashed): Folder {
        require(t in trash) { "not in the trash" }
        var folder = root
        for (n in t.from) folder = folder.folders.firstOrNull { it.name == n } ?: newFolder(n, parent = folder)
        val n = unique(t.file.name, folder.files.map { it.name })
        val into = folder
        change(into, trash = true) {
            store?.fileAt(t.file, into, n)
            t.file.name = n
            into.files.add(t.file)
            trash.remove(t)
        }
        return folder
    }

    /** Deletes one trashed file for good. The only way a file leaves the trash without coming back, so the screen asks first. */
    fun purge(t: Trashed) {
        require(t in trash) { "not in the trash" }
        change(trash = true) {
            val s = store
            if (s != null) s.purge(t.file) else purged.add(t.file)
            trash.remove(t)
        }
    }

    /** Deletes everything in the trash for good. If one cannot be deleted, it and the ones after it stay in the trash. */
    fun emptyTrash() { trash.toList().forEach(::purge) }

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
