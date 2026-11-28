package app.styli.ink

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The Tabularium on disk: plain folders and files that any app or computer can read and copy.
 *
 *   <root>/<Folder>/.folder.json                  name, colour, pinned
 *   <root>/<Folder>/<File>.ink/file.json          name, created, opened, pinned
 *   <root>/<Folder>/<File>.ink/page-001.stylog    one ink log per page (see InkLog.kt)
 *   <root>/<Folder>/<File>.ink/pictures/          the picked file of each picture, named <sha256>.<ext>
 *   <root>/<Folder>/<File>.ink/.deleted/          page logs of deleted pages (never erased)
 *   <root>/.trash/<time>-<File>.ink/              a deleted file, with trash.json (where it came from, when)
 *   <root>/.styli/seq                             the next event number for this device
 *
 * Folders and notes are made, renamed, moved, trashed and restored on disk the moment the [Library] asks (see [Store]);
 * [autosave] writes the rest as it changes: new ink, and the small info files. Nothing is ever deleted except what the
 * user chose "delete for good" on. No Android in here, so it is tested on the JVM.
 */
class Vault(val root: File, private val device: String = DEVICE, private val clock: () -> Long = System::currentTimeMillis) {
    private val meta = File(root, ".styli")
    private val trashDir = File(root, ".trash")
    private val written = HashMap<File, String>() // the last text written to each small file, so unchanged ones are not rewritten
    private var seq = readSeq()
    // Ink is appended to the logs by one background thread, so the fsync never stops the screen. [sending] (UI thread only) holds,
    // per page, the events handed to it but not yet confirmed; they stay in the page's log until the write succeeds, and a failed
    // write simply sends them again. Anything else that touches the files (a full [save], clips, copying) calls [flush] first.
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vault-writer").apply { isDaemon = true } }
    private val done = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>() // run on the UI thread by [drain]
    private val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val sending = java.util.IdentityHashMap<PageModel, MutableSet<Ev>>()
    private val creating = HashSet<File>() // logs handed over to be created, so a second batch appends instead of creating again
    private val aside = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PageModel, Boolean>()) // pages whose log was moved to .deleted
    internal var beforeWrite: (() -> Unit)? = null // for tests: runs on the writer thread before each write

    /** Waits for the background writes to finish, then settles them. Returns what went wrong, in plain words. */
    fun flush(): List<String> {
        writer.submit {}.get()
        return drain()
    }

    private fun drain(): List<String> {
        while (true) done.poll()?.invoke() ?: break
        return generateSequence { failures.poll() }.toList().also { if (it.isNotEmpty()) retryAll = true } // a failed write: look at every note again
    }

    // [autosave] saves only what changed: each folder (its list of children) and each note has its own fingerprint, remembered here.
    private val savedFolder = java.util.IdentityHashMap<Folder, Int>()
    private val savedNote = java.util.IdentityHashMap<NoteFile, Int>()
    private var retryAll = false // after a failed write: look at every note again

    /** Things that went wrong on the last [load] or [save], in plain words. */
    var problems: List<String> = emptyList()
        private set

    // ---- Loading ----

    /**
     * Reads the vault into a library. With [pages] every note's ink is read too, and a note whose ink cannot be read is
     * left out (alone on disk) and reported in [problems]. Without, only the folders and the small info files are read,
     * which is quick; the app then reads each note's pages in the background ([readPages], [attachPages]).
     */
    fun load(pages: Boolean = true): Library {
        val lib = Library(clock)
        val found = ArrayList<String>()
        loadFolder(lib.root, root, found, pages)
        trashDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val m = readMeta(File(d, "trash.json")) ?: return@forEach
            loadFile(d, found, pages)?.let { f -> lib.trash.add(Trashed(f, (m["from"] as? List<*>)?.map { it as String } ?: emptyList(), (m["at"] as? Long) ?: 0L)) }
        }
        problems = found
        attach(lib)
        return lib
    }

    private fun loadFolder(f: Folder, dir: File, found: MutableList<String>, pages: Boolean) {
        for (d in dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()) {
            if (!d.isDirectory || d.name.startsWith(".")) continue
            if (d.name.endsWith(".ink")) { loadFile(d, found, pages)?.let { f.files.add(it) }; continue }
            // Remember what the info file says, so a save after launch does not rewrite it when nothing changed.
            val m = readInfo(File(d, ".folder.json"))
            val child = Folder((m?.get("name") as? String) ?: d.name, (m?.get("color") as? Long)?.toInt())
            child.pinned = m?.get("pinned") == true
            child.parent = f; child.disk = d
            f.folders.add(child)
            loadFolder(child, d, found, pages)
        }
    }

    private fun loadFile(d: File, found: MutableList<String>, pages: Boolean): NoteFile? {
        if (d.listFiles { x -> x.isFile && PAGE.matches(x.name) }.isNullOrEmpty()) return null
        val read = if (pages) try { readPages(d) } catch (e: IOException) { found.add(e.message ?: d.name); return null } else null
        val m = readInfo(File(d, "file.json"))
        val file = NoteFile(
            (m?.get("name") as? String) ?: d.name.removeSuffix(".ink"), FileKind.INK, null,
            (m?.get("created") as? Long) ?: clock(), (m?.get("opened") as? Long) ?: clock(), m?.get("pinned") == true,
        )
        file.disk = d
        file.view = readInfo(File(d, "view.json"))
        read?.let { hand(file, it) }
        return file
    }

    /** A note's pages as read from disk, not yet handed to its [NoteFile]. */
    class Pages internal constructor(internal val notebook: Notebook, internal val names: List<Pair<String, PageModel>>)

    /**
     * Reads the pages (and pictures) of the note in [dir]. It makes only new objects and touches nothing shared, so it may
     * run on a background thread. Throws, in plain words, if a page cannot be read.
     */
    fun readPages(dir: File): Pages {
        val names = dir.listFiles { x -> x.isFile && PAGE.matches(x.name) }?.map { it.name }?.sorted().orEmpty()
        if (names.isEmpty()) throw IOException("${dir.name} has no pages")
        val pages = names.map { n ->
            try { n to InkLogReader.read(File(dir, n).readBytes()).page }
            catch (e: Exception) { throw IOException("${dir.name}/$n could not be read (${e.message}); it is left as it is") }
        }
        val nb = Notebook(pages[0].second)
        pages.drop(1).forEach { nb.adopt(it.second) }
        File(dir, PICTURES.trimEnd('/')).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { nb.pictureFiles.put(it.readBytes()) }
        return Pages(nb, pages)
    }

    /**
     * Hands pages read by [readPages] from [dir] to [file] (on the UI thread). False if they no longer fit, because the
     * note moved while they were read or already has pages: read it again.
     */
    fun attachPages(file: NoteFile, dir: File, pages: Pages): Boolean {
        if (file.disk != dir || file.notebook != null) return false
        // A note that was saved stays saved: its pages arriving is not a change to write.
        val clean = savedNote[file]?.let { it == noteFingerprint(file, view = true) || it == noteFingerprint(file, view = false) } == true
        val withView = savedNote[file] == noteFingerprint(file, view = true)
        hand(file, pages)
        if (clean) savedNote[file] = noteFingerprint(file, view = withView)
        return true
    }

    /** The pages of [file], read from [dir], could not be read: it is marked so it is never written. Ignored if it moved meanwhile. */
    fun pagesFailed(file: NoteFile, dir: File, why: String?) {
        if (file.disk == dir && file.notebook == null) file.loadError = why ?: "its pages could not be read"
    }

    private fun hand(file: NoteFile, pages: Pages) {
        file.notebook = pages.notebook
        pages.names.forEach { (n, p) -> file.pageNames[p] = n }
    }

    /** Reads a small info file, remembering its text so a save that would write the same text leaves it alone. */
    private fun readInfo(f: File): Map<String, Any?>? {
        val text = try { if (f.isFile) f.readText() else null } catch (_: Exception) { null } ?: return null
        written[f] = text
        return try { MiniJson.parse(text) } catch (_: Exception) { null }
    }

    private fun readMeta(f: File): Map<String, Any?>? =
        try { if (f.isFile) MiniJson.parse(f.readText()) else null } catch (_: Exception) { null }

    // ---- Saving ----

    /** True if something has changed that is not saved yet. Cheap: it only looks at memory. */
    fun dirty(lib: Library, open: NoteFile? = null): Boolean = retryAll || lib.root.allFolders().any { f ->
        savedFolder[f] != folderFingerprint(f) || f.files.any { savedNote[it] != noteFingerprint(it, view = it !== open) }
    }

    /**
     * The one-second autosave: writes only what changed since the last save. A folder whose list of children changed (a new,
     * renamed, moved or deleted note or folder, a folder's colour or pin) is synced on its own, a note whose own content changed
     * (ink, its name, pin, opened time) is saved on its own. (The trash changes on the spot, see [Store].) Where [open] is
     * scrolled to is left for when it is left. Nothing here walks the disk. Null if there was nothing to save, otherwise
     * what went wrong (empty if nothing did).
     */
    fun autosave(lib: Library, open: NoteFile?): List<String>? {
        val failed = drain()
        if (failed.isNotEmpty()) { problems = failed; return failed } // the events are still in the log: the next call sends them again
        working = lib
        val dirtyFolders = ArrayList<Folder>()
        val dirtyNotes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NoteFile, Boolean>())
        for (f in lib.root.allFolders()) { // a folder before the folders inside it
            if (retryAll || savedFolder[f] != folderFingerprint(f)) dirtyFolders.add(f)
            for (n in f.files) if (retryAll || savedNote[n] != noteFingerprint(n, view = n !== open)) dirtyNotes.add(n)
        }
        if (dirtyFolders.isEmpty() && dirtyNotes.isEmpty()) return null
        val found = ArrayList<String>()
        try {
            if (dirtyFolders.isNotEmpty()) { root.mkdirs(); meta.mkdirs(); File(root, ".nomedia").let { if (!it.exists()) it.createNewFile() } }
            val done = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NoteFile, Boolean>())
            for (f in dirtyFolders) syncChildren(f, if (f === lib.root) root else f.disk ?: continue, dirtyNotes, done, found)
            for (n in dirtyNotes) if (n !in done) n.disk?.let { syncFile(n, it, found, later = true) }
        } catch (e: IOException) { found.add("could not save: ${e.message}") }
        problems = found
        if (found.isEmpty()) { remember(lib, open); retryAll = false }
        return found
    }

    /** One folder's own children: each in its place, with its info file; the folders inside are synced if they changed. */
    private fun syncChildren(f: Folder, dir: File, dirtyNotes: Set<NoteFile>, done: MutableSet<NoteFile>, found: MutableList<String>) {
        val used = HashSet<String>()
        for (c in f.folders) {
            val want = pick(dir, safe(c.name), "", c.disk, used)
            c.disk = place(c.disk, want) { old, new -> shift(c, old, new) }
            writeMeta(File(want, ".folder.json"), mapOf("name" to c.name, "color" to c.color?.toLong(), "pinned" to c.pinned))
        }
        for (file in f.files) {
            val want = pick(dir, safe(file.name), ".ink", file.disk, used)
            file.disk = place(file.disk, want) { old, new -> shiftFile(file, old, new) }
            if (file in dirtyNotes) { syncFile(file, want, found, later = true); done.add(file) }
        }
    }

    /** What was saved, as of now: each folder's and note's fingerprint ([open]'s scroll position left out). */
    private fun remember(lib: Library, open: NoteFile?) {
        savedFolder.clear(); savedNote.clear()
        for (f in lib.root.allFolders()) {
            savedFolder[f] = folderFingerprint(f)
            for (n in f.files) savedNote[n] = noteFingerprint(n, view = n !== open)
        }
    }

    /**
     * Saves everything not saved yet, scroll positions included, and waits until it is on disk: for leaving the app, and
     * before anything that copies the files. Anything that fails is listed in [problems] and tried again next time.
     */
    fun save(lib: Library): List<String> {
        flush() // a write that failed earlier is sent again by the autosave below
        val found = ArrayList(autosave(lib, null) ?: emptyList())
        found.addAll(flush())
        problems = found
        if (found.isEmpty()) remember(lib, null) // the writes are confirmed: their pages' logs are empty now
        return found
    }

    /** One file's folder: its small info file, its pages, and no trace of having been in the trash. */
    private fun syncFile(file: NoteFile, dir: File, found: MutableList<String>, later: Boolean = false) {
        if (dir.parentFile != trashDir) File(dir, "trash.json").delete() // in the trash it must stay: without it the file is not listed there
        writeMeta(File(dir, "file.json"), fileMeta(file, file.name))
        file.view?.let { writeMeta(File(dir, "view.json"), it) }
        val nb = file.notebook ?: return
        val live = nb.pages
        val gone = File(dir, ".deleted")
        for (p in live) {
            // In the background pass, a page with nothing to send is already on disk: skip its file checks (slow on shared storage).
            // A page whose log was moved aside (deleted, then brought back by an undo) is never skipped: its file has to come back.
            if (later && p in file.pageNames && p !in aside && p.log.all { it in (sending[p] ?: emptySet<Ev>()) }) continue
            val name = file.pageNames.getOrPut(p) { nextPageName(dir, file) }
            val inGone = File(gone, name)
            if (inGone.exists() && !File(dir, name).exists()) { // a deleted page was brought back
                if (!inGone.renameTo(File(dir, name))) throw IOException("could not bring back ${inGone.name}")
            }
            aside.remove(p)
            try { writePictures(nb, p, dir); if (later) writePageLater(file, p, File(dir, name)) else writePage(file, p, File(dir, name)) } catch (e: Exception) { found.add("${file.name}: ${e.message}") }
        }
        for ((p, name) in file.pageNames.entries.toList()) {
            if (live.any { it === p }) continue
            val from = File(dir, name)
            if (from.exists()) { flush(); gone.mkdirs(); if (!from.renameTo(File(gone, name))) throw IOException("could not move $name aside"); aside.add(p) }
            // a deleted page keeps its name, so undoing the delete finds its file again
        }
    }

    /** Writes the files of the pictures on [p] (and any in its unsaved events) that are not on disk yet, before the log that names them. */
    private fun writePictures(nb: Notebook, p: PageModel, dir: File) {
        val live = p.pictures.map { it.id }.toSet()
        val pics = p.pictures + p.log.filterIsInstance<Ev.PictureSet>().map { it.picture }
        for (pic in pics) {
            val target = File(File(dir, PICTURES.trimEnd('/')), pic.file)
            if (target.exists()) continue
            val bytes = nb.pictureFiles.get(pic.sha)
            if (bytes == null) { if (pic.id in live) throw IOException("the picture ${pic.file} has lost its file"); continue }
            target.parentFile.mkdirs()
            atomicWrite(target, bytes)
        }
    }

    private fun nextPageName(dir: File, file: NoteFile): String {
        val taken = HashSet<String>(file.pageNames.values)
        File(dir, ".deleted").list()?.let { taken.addAll(it) }
        dir.list()?.let { taken.addAll(it) }
        var n = 1
        while ("page-%03d.stylog".format(n) in taken) n++
        return "page-%03d.stylog".format(n)
    }

    /** Appends the page's new events to its log (the whole log, with a header, if it is new). */
    private fun writePage(file: NoteFile, p: PageModel, target: File) {
        val events = p.log.toList()
        val exists = target.exists()
        if (exists && events.isEmpty()) return
        val w = InkLogWriter(device, seq, clock)
        val bytes = if (exists) w.encode(events) else w.header() + w.encode(listOf(Ev.PageChanged(p.page)) + events)
        // The numbers are reserved first: a crash can skip some but never use one twice.
        writeSeq(w.nextSeq)
        if (exists) FileOutputStream(target, true).use { it.write(bytes); it.fd.sync() } else atomicWrite(target, bytes)
        seq = w.nextSeq
        p.log.removeAll(events.toSet())
    }

    /** Like [writePage], but the encoding is done here and the writing is left to the background thread (see [writer]). */
    private fun writePageLater(file: NoteFile, p: PageModel, target: File) {
        val mine = sending.getOrPut(p) { java.util.Collections.newSetFromMap(java.util.IdentityHashMap()) }
        val events = p.log.filter { it !in mine }
        val exists = target.exists() || target in creating
        if (exists && events.isEmpty()) return
        val w = InkLogWriter(device, seq, clock)
        val bytes = if (exists) w.encode(events) else w.header() + w.encode(listOf(Ev.PageChanged(p.page)) + events)
        val next = w.nextSeq
        seq = next // the numbers are reserved now; the file on disk catches up in the job, before the log
        mine.addAll(events)
        val sent = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Ev, Boolean>()).apply { addAll(events) }
        if (!exists) creating.add(target)
        val created = !exists
        writer.execute {
            try {
                beforeWrite?.invoke()
                if (exists && !target.exists()) throw IOException("${target.name} is missing") // its creation failed: the events go round again
                writeSeq(next)
                if (exists) FileOutputStream(target, true).use { it.write(bytes); it.fd.sync() } else atomicWrite(target, bytes)
                done.add { mine.removeAll(sent); p.log.removeAll { it in sent }; if (created) creating.remove(target) }
            } catch (e: Exception) {
                failures.add("${file.name}: ${e.message}")
                done.add { mine.removeAll(sent); if (created) creating.remove(target) }
            }
        }
    }

    /** Deletes a file the user chose to delete for good. Only ever inside the trash folder. */
    private fun removeFromTrash(f: NoteFile, found: MutableList<String>): Boolean {
        val d = f.disk ?: return true
        if (d.parentFile != trashDir) { found.add("will not delete ${d.name}: it is not in the trash"); return false }
        if (!d.deleteRecursively()) { found.add("could not delete ${d.name}"); return false }
        written.keys.removeAll { it.path.startsWith(d.path) }
        f.disk = null
        return true
    }

    /** Removes the (empty) directory of a folder that was deleted; its files are in the trash by now. */
    private fun removeFolder(f: Folder): Boolean {
        val d = f.disk ?: return true
        File(d, ".folder.json").delete(); written.remove(File(d, ".folder.json"))
        return !d.exists() || d.delete() || (d.list()?.isNotEmpty() == true) // not empty: leave it, it holds something not ours
    }

    private fun fileMeta(file: NoteFile, name: String) = mapOf("name" to name, "kind" to "ink", "created" to file.created, "opened" to file.opened, "pinned" to file.pinned)

    // ---- Changes made on the spot ----
    // Renaming, moving, making, deleting and restoring happen on disk the moment they are asked for (see [Store]); the
    // [Library] changes only once the disk has. Each step that can fail comes before the one that cannot be undone, so a
    // refusal leaves things as they were and a crash in between leaves something that still loads. [autosave] then only
    // writes the small info files and ink, and finds the folders already in place.

    /** Makes [lib]'s changes happen here on the spot. [load] does this; a library made in memory can be attached later. */
    fun attach(lib: Library) { working = lib; lib.store = store; remember(lib, null) }

    private val settleFolders = ArrayList<Folder>()
    private var settleOk = true

    private fun dirOf(f: Folder): File =
        if (f === working?.root) root else f.disk ?: throw IOException("the folder \"${f.name}\" is not saved yet")

    private val store = object : Store {
        override fun begin(folders: List<Folder>, trash: Boolean) {
            settleFolders.clear(); settleOk = true
            // Only what was saved before the change can be marked saved after it: anything else still waits for [autosave].
            folders.filterTo(settleFolders) { savedFolder[it] == folderFingerprint(it) }
        }

        override fun settled() {
            if (settleOk) settleFolders.forEach { savedFolder[it] = folderFingerprint(it) }
            settleFolders.clear()
        }

        override fun folderAt(f: Folder, to: Folder, name: String) {
            val dir = dirOf(to)
            flush() // nothing may still be on its way into the old place
            val fresh = f.disk == null
            f.disk = place(f.disk, pick(dir, safe(name), "", f.disk, HashSet())) { old, new -> shift(f, old, new) }
            if (fresh) settleFolders.add(f) // new and empty: nothing in it to write
            try { writeMeta(File(f.disk, ".folder.json"), mapOf("name" to name, "color" to f.color?.toLong(), "pinned" to f.pinned)) }
            catch (_: IOException) { settleOk = false } // the move happened; [autosave] writes the info file again
        }

        override fun fileAt(file: NoteFile, to: Folder, name: String) {
            val dir = dirOf(to)
            flush()
            val was = file.disk
            file.disk = place(was, pick(dir, safe(name), ".ink", was, HashSet())) { old, new -> shiftFile(file, old, new) }
            File(file.disk, "trash.json").delete() // out of the trash (if it was in it)
            try { writeMeta(File(file.disk, "file.json"), fileMeta(file, name)) } catch (_: IOException) { settleOk = false }
        }

        override fun toTrash(file: NoteFile, from: List<String>, at: Long) {
            flush()
            val d = file.disk ?: throw IOException("\"${file.name}\" is not saved yet; try again in a moment") // autosave makes it within a second
            val found = ArrayList<String>()
            syncFile(file, d, found) // its ink so far goes with it
            if (found.isNotEmpty()) throw IOException(found.first())
            // The note in its folder with a trash.json still loads as a note; in the trash without one it would not be listed.
            val mark = File(d, "trash.json")
            writeMeta(mark, mapOf("from" to from, "at" to at))
            trashDir.mkdirs()
            try { file.disk = place(d, pick(trashDir, "$at-${safe(file.name)}", ".ink", d, HashSet())) { old, new -> shiftFile(file, old, new) } }
            catch (e: IOException) { mark.delete(); written.remove(mark); throw e }
        }

        override fun removeFolder(f: Folder) { this@Vault.removeFolder(f) }

        override fun purge(file: NoteFile) {
            flush()
            val found = ArrayList<String>()
            if (!removeFromTrash(file, found)) throw IOException(found.firstOrNull() ?: "could not delete ${file.name}")
        }
    }

    // ---- The clip shelf ----

    private val clipDir get() = File(meta, "clips")

    /** The saved clips (a name and size beside each clip's log, and their pictures' files). Ones that cannot be read are skipped. */
    fun loadClips(): ClipShelf {
        val out = ArrayList<Clip>()
        val pics = ClipShelf()
        File(clipDir, PICTURES.trimEnd('/')).listFiles()?.filter { it.isFile }?.forEach { pics.pictures.put(it.readBytes()) }
        for (log in clipDir.listFiles { f -> f.isFile && f.name.endsWith(".stylog") }?.sortedBy { it.name } ?: emptyList()) {
            val m = readMeta(File(clipDir, log.name.removeSuffix(".stylog") + ".json")) ?: continue
            try { out.add(ClipFile.decode(m["name"] as? String ?: "Clip", (m["width"] as? Long ?: 0L).toFloat(), (m["height"] as? Long ?: 0L).toFloat(), log.readBytes())) }
            catch (_: Exception) { /* a clip that will not read is left on disk and left out */ }
        }
        return ClipShelf(out).also { shelf -> pics.pictures.let { p -> out.flatMap { it.pictures }.forEach { pic -> p.get(pic.sha)?.let { shelf.pictures.put(it) } } } }
    }

    /** Writes the shelf: one log and one small info file per clip, numbered in order; files left over from deleted clips are removed. */
    fun saveClips(shelf: ClipShelf) {
        flush()
        clipDir.mkdirs()
        val w = InkLogWriter(device, seq, clock)
        shelf.clips.forEachIndexed { i, clip ->
            val base = "clip-%03d".format(i + 1)
            atomicWrite(File(clipDir, "$base.stylog"), ClipFile.encode(clip, w))
            writeMeta(File(clipDir, "$base.json"), mapOf("name" to clip.name, "width" to clip.width.toLong(), "height" to clip.height.toLong()))
            for (pic in clip.pictures) {
                val f = File(File(clipDir, PICTURES.trimEnd('/')), pic.file)
                if (!f.exists()) shelf.pictures.get(pic.sha)?.let { f.parentFile.mkdirs(); atomicWrite(f, it) }
            }
        }
        writeSeq(w.nextSeq); seq = w.nextSeq
        for (i in shelf.clips.size + 1..shelf.clips.size + 200) { // anything numbered past the last clip is from a clip since deleted
            val base = "clip-%03d".format(i)
            if (!File(clipDir, "$base.stylog").delete() && !File(clipDir, "$base.json").exists()) break
            File(clipDir, "$base.json").delete(); written.remove(File(clipDir, "$base.json"))
        }
    }

    // ---- Moving to another folder ----

    /**
     * Copies the whole vault (notes, trash, clips, settings) into [dest], which must be empty or not exist yet. The old folder
     * is not touched: nothing is deleted. Null if it worked (every file the same size), otherwise what went wrong.
     */
    fun copyTo(dest: File): String? {
        flush()
        if (dest.exists() && (dest.list()?.isNotEmpty() == true)) return "That folder is not empty, so nothing was moved"
        return try {
            if (!root.copyRecursively(dest, overwrite = false)) return "Some files could not be copied"
            fun tally(d: File) = d.walkTopDown().filter { it.isFile }.let { fs -> fs.count() to fs.sumOf { it.length() } }
            if (tally(root) != tally(dest)) "The copy did not match the original, so nothing was moved" else null
        } catch (e: Exception) { "Could not copy the notes: ${e.message}" }
    }

    // ---- Settings ----

    /** The app's saved settings, or null if there are none (or the file cannot be read). */
    fun readSettings(): Map<String, Any?>? = readMeta(File(meta, "settings.json"))

    fun writeSettings(values: Map<String, Any?>) { meta.mkdirs(); writeMeta(File(meta, "settings.json"), values) }

    // ---- Helpers ----

    /** Where [current] should be: [name] in [dir], with " (2)" and so on if another thing there already has the name. */
    private fun pick(dir: File, name: String, suffix: String, current: File?, used: MutableSet<String>): File {
        var n = 1
        while (true) {
            val candidate = if (n == 1) name else "$name ($n)"
            val f = File(dir, candidate + suffix)
            if (used.add(f.name.lowercase().let { if (f.name.lowercase() in used) return@let "$it#" else it })) {
                if (!f.exists() || f == current) return f
            }
            n++
        }
    }

    /** Creates [want], or renames [current] to it. [moved] hears about a rename so what is inside can follow. */
    private fun place(current: File?, want: File, moved: (File, File) -> Unit): File {
        if (current == null) { if (!want.mkdirs() && !want.isDirectory) throw IOException("could not make ${want.name}"); return want }
        if (current == want) return want
        want.parentFile?.mkdirs()
        if (!current.renameTo(want)) throw IOException("could not move ${current.name} to ${want.parentFile?.name}/${want.name}")
        written.keys.filter { it.path.startsWith(current.path + File.separator) }.forEach { written.remove(it) }
        moved(current, want)
        return want
    }

    private fun relocate(f: File?, old: File, new: File) = f?.let { File(new, it.relativeTo(old).path) }
    private fun shift(folder: Folder, old: File, new: File) {
        folder.folders.forEach { it.disk = relocate(it.disk, old, new); shift(it, old, new) }
        folder.files.forEach { it.disk = relocate(it.disk, old, new) }
    }
    private var working: Library? = null // the attached library, so [dirOf] knows its root
    private fun shiftFile(file: NoteFile, old: File, new: File) { file.disk = new } // its pages are named relative to it

    private fun writeMeta(f: File, map: Map<String, Any?>) {
        val text = MiniJson.write(map)
        if (written[f] == text && f.exists()) return
        atomicWrite(f, text.toByteArray())
        written[f] = text
    }

    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(f)) throw IOException("could not write ${f.name}")
    }

    private fun readSeq(): Long = try { File(meta, "seq").readText().trim().toLong() } catch (_: Exception) { 0L }
    private fun writeSeq(n: Long) { meta.mkdirs(); atomicWrite(File(meta, "seq"), n.toString().toByteArray()) }

    /** A number that changes whenever what is saved for [file] alone would: its name, pin, opened time, pages and (with [view]) where it is scrolled to. */
    private fun noteFingerprint(file: NoteFile, view: Boolean = true): Int {
        var h = 17
        fun mix(x: Any?) { h = h * 31 + (x?.hashCode() ?: 0) }
        mix(file.name); mix(file.pinned); mix(file.opened); if (view) mix(file.view)
        // the last event too: a write finishing while a new stroke is added would otherwise leave the size the same
        file.notebook?.pages?.forEach { mix(System.identityHashCode(it)); mix(it.log.size); mix(it.log.lastOrNull()?.let(System::identityHashCode)) }
        return h
    }

    /** A number that changes whenever [f]'s list of children, or what is saved about its folders (name, colour, pin), would. */
    private fun folderFingerprint(f: Folder): Int {
        var h = 17
        fun mix(x: Any?) { h = h * 31 + (x?.hashCode() ?: 0) }
        for (c in f.folders) { mix(System.identityHashCode(c)); mix(c.name); mix(c.color); mix(c.pinned) }
        for (n in f.files) { mix(System.identityHashCode(n)); mix(n.name) }
        return h
    }

    companion object {
        /** The device name on every ink event until pairing gives each device a real one. */
        const val DEVICE = "george"

        /** True if the app may use shared storage freely (the "all files" access), which the vault needs. */
        fun hasAccess() = android.os.Environment.isExternalStorageManager()

        /** Where the notes go: Documents/Tabularium. */
        fun rootDir() = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "Tabularium")

        /** Opens the system screen where the person allows it. */
        fun openAccessSettings(context: android.content.Context) = context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
        )

        private val PAGE = Regex("page-\\d+\\.stylog")

        /** A name safe to use as a file or folder name: no slashes or other characters some systems refuse, and not hidden. */
        fun safe(name: String): String {
            val s = name.map { if (it in "/\\:*?\"<>|" || it < ' ') '_' else it }.joinToString("").trim().trimEnd('.').take(80)
            return if (s.isEmpty() || s.startsWith('.')) "_$s" else s
        }
    }
}
