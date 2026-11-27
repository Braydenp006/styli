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
 * [save] makes the disk match the [Library] (creating, renaming and moving folders and files, appending new ink
 * events, moving deleted files into the trash). Nothing is ever deleted except what the user chose "delete for
 * good" on. No Android in here, so it is tested on the JVM.
 */
class Vault(val root: File, private val device: String = DEVICE, private val clock: () -> Long = System::currentTimeMillis) {
    private val meta = File(root, ".styli")
    private val trashDir = File(root, ".trash")
    private val written = HashMap<File, String>() // the last text written to each small file, so unchanged ones are not rewritten
    private var seq = readSeq()
    private var saved = 0 // the fingerprint of the library as last saved
    // Ink is appended to the logs by one background thread, so the fsync never stops the screen. [sending] (UI thread only) holds,
    // per page, the events handed to it but not yet confirmed; they stay in the page's log until the write succeeds, and a failed
    // write simply sends them again. Anything else that touches the files (a full [save], clips, copying) calls [flush] first.
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vault-writer").apply { isDaemon = true } }
    private val done = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>() // run on the UI thread by [drain]
    private val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val sending = java.util.IdentityHashMap<PageModel, MutableSet<Ev>>()
    private val creating = HashSet<File>() // logs handed over to be created, so a second batch appends instead of creating again
    internal var beforeWrite: (() -> Unit)? = null // for tests: runs on the writer thread before each write

    /** Waits for the background writes to finish, then settles them. Returns what went wrong, in plain words. */
    fun flush(): List<String> {
        writer.submit {}.get()
        return drain()
    }

    private fun drain(): List<String> {
        while (true) done.poll()?.invoke() ?: break
        return generateSequence { failures.poll() }.toList().also { if (it.isNotEmpty()) savedNote = -1 } // a failed write: look at the note again
    }

    // While a note is open [autosave] saves only that note. These say what was saved, and for which note.
    private var savedFor: NoteFile? = null
    private var savedOutside = 0 // the fingerprint of everything but that note
    private var savedNote = 0 // the fingerprint of that note alone

    /** Things that went wrong on the last [load] or [save], in plain words. */
    var problems: List<String> = emptyList()
        private set

    // ---- Loading ----

    /** Reads the vault into a library. A file that cannot be read is left alone on disk and reported in [problems]. */
    fun load(): Library {
        val lib = Library(clock)
        val found = ArrayList<String>()
        loadFolder(lib.root, root, found)
        trashDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val m = readMeta(File(d, "trash.json")) ?: return@forEach
            loadFile(d, found)?.let { f -> lib.trash.add(Trashed(f, (m["from"] as? List<*>)?.map { it as String } ?: emptyList(), (m["at"] as? Long) ?: 0L)) }
        }
        problems = found
        saved = fingerprint(lib)
        return lib
    }

    private fun loadFolder(f: Folder, dir: File, found: MutableList<String>) {
        for (d in dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()) {
            if (!d.isDirectory || d.name.startsWith(".")) continue
            if (d.name.endsWith(".ink")) { loadFile(d, found)?.let { f.files.add(it) }; continue }
            val m = readMeta(File(d, ".folder.json"))
            val child = Folder((m?.get("name") as? String) ?: d.name, (m?.get("color") as? Long)?.toInt())
            child.pinned = m?.get("pinned") == true
            child.parent = f; child.disk = d
            f.folders.add(child)
            loadFolder(child, d, found)
        }
    }

    private fun loadFile(d: File, found: MutableList<String>): NoteFile? {
        val m = readMeta(File(d, "file.json"))
        val names = d.listFiles { x -> x.isFile && PAGE.matches(x.name) }?.map { it.name }?.sorted() ?: emptyList()
        if (names.isEmpty()) return null
        val pages = ArrayList<Pair<String, PageModel>>()
        for (n in names) {
            try { pages.add(n to InkLogReader.read(File(d, n).readBytes()).page) }
            catch (e: Exception) { found.add("${d.name}/$n could not be read (${e.message}); it is left as it is"); return null }
        }
        val nb = Notebook(pages[0].second)
        pages.drop(1).forEach { nb.adopt(it.second) }
        File(d, PICTURES.trimEnd('/')).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { nb.pictureFiles.put(it.readBytes()) }
        val file = NoteFile(
            (m?.get("name") as? String) ?: d.name.removeSuffix(".ink"), FileKind.INK, nb,
            (m?.get("created") as? Long) ?: clock(), (m?.get("opened") as? Long) ?: clock(), m?.get("pinned") == true,
        )
        file.disk = d
        file.view = readMeta(File(d, "view.json"))
        pages.forEach { (n, p) -> file.pageNames[p] = n }
        return file
    }

    private fun readMeta(f: File): Map<String, Any?>? =
        try { if (f.isFile) MiniJson.parse(f.readText()) else null } catch (_: Exception) { null }

    // ---- Saving ----

    /** True if the library changed since it was last saved or loaded. Cheap: it only looks at memory. */
    fun dirty(lib: Library) = fingerprint(lib) != saved

    /**
     * The one-second autosave. With a note [open], only that note's own pages are written, so drawing never walks the whole
     * library; anything outside it (a rename, a move, a delete) or no open note takes the full [save], as does leaving the note.
     * Null if there was nothing to save, otherwise what [save] would return.
     */
    fun autosave(lib: Library, open: NoteFile?): List<String>? {
        val failed = drain()
        if (failed.isNotEmpty()) { problems = failed; return failed } // the events are still in the log: the next call sends them again
        val disk = open?.disk
        if (open == null || disk == null || open.notebook == null || savedFor !== open) {
            if (!dirty(lib)) { remember(lib, open); return null }
            return save(lib).also { if (it.isEmpty()) remember(lib, open) }
        }
        if (fingerprint(lib, open) != savedOutside) return save(lib).also { if (it.isEmpty()) remember(lib, open) }
        if (noteFingerprint(open, false) == savedNote) return null // scrolling alone is saved when the note is left
        val found = ArrayList<String>()
        try { syncFile(open, disk, found, later = true) } catch (e: IOException) { found.add("could not save: ${e.message}") }
        problems = found
        if (found.isEmpty()) savedNote = noteFingerprint(open, false) // after: saving drains each page's pending events
        return found
    }

    private fun remember(lib: Library, open: NoteFile?) {
        savedFor = open; savedOutside = fingerprint(lib, open); savedNote = open?.let { noteFingerprint(it, false) } ?: 0
    }

    /** Makes the disk match [lib]. Anything that fails is listed in [problems] and tried again next time. */
    fun save(lib: Library): List<String> {
        val found = ArrayList<String>(flush())
        try {
            root.mkdirs(); meta.mkdirs()
            File(root, ".nomedia").let { if (!it.exists()) it.createNewFile() } // keep gallery apps from scanning the notes
            syncFolder(lib.root, root, found)
            syncTrash(lib, found)
            lib.purged.toList().forEach { f -> if (removeFromTrash(f, found)) lib.purged.remove(f) }
            lib.goneFolders.toList().forEach { f -> if (removeFolder(f)) lib.goneFolders.remove(f) }
        } catch (e: IOException) { found.add("could not save: ${e.message}") }
        problems = found
        if (found.isEmpty()) saved = fingerprint(lib) // after: saving drains each page's pending events
        return found
    }

    private fun syncFolder(f: Folder, dir: File, found: MutableList<String>) {
        val used = HashSet<String>()
        for (c in f.folders) {
            val want = pick(dir, safe(c.name), "", c.disk, used)
            c.disk = place(c.disk, want) { old, new -> shift(c, old, new) }
            writeMeta(File(want, ".folder.json"), mapOf("name" to c.name, "color" to c.color?.toLong(), "pinned" to c.pinned))
            syncFolder(c, want, found)
        }
        for (file in f.files) {
            val want = pick(dir, safe(file.name), ".ink", file.disk, used)
            file.disk = place(file.disk, want) { old, new -> shiftFile(file, old, new) }
            syncFile(file, want, found)
        }
    }

    /** One file's folder: its small info file, its pages, and no trace of having been in the trash. */
    private fun syncFile(file: NoteFile, dir: File, found: MutableList<String>, later: Boolean = false) {
        File(dir, "trash.json").delete()
        writeMeta(File(dir, "file.json"), mapOf("name" to file.name, "kind" to "ink", "created" to file.created, "opened" to file.opened, "pinned" to file.pinned))
        file.view?.let { writeMeta(File(dir, "view.json"), it) }
        val nb = file.notebook ?: return
        val live = nb.pages
        val gone = File(dir, ".deleted")
        for (p in live) {
            // In the background pass, a page with nothing to send is already on disk: skip its file checks (slow on shared storage).
            if (later && p in file.pageNames && p.log.all { it in (sending[p] ?: emptySet<Ev>()) }) continue
            val name = file.pageNames.getOrPut(p) { nextPageName(dir, file) }
            val inGone = File(gone, name)
            if (inGone.exists() && !File(dir, name).exists()) { // a deleted page was brought back
                if (!inGone.renameTo(File(dir, name))) throw IOException("could not bring back ${inGone.name}")
            }
            try { writePictures(nb, p, dir); if (later) writePageLater(file, p, File(dir, name)) else writePage(file, p, File(dir, name)) } catch (e: Exception) { found.add("${file.name}: ${e.message}") }
        }
        for ((p, name) in file.pageNames.entries.toList()) {
            if (live.any { it === p }) continue
            val from = File(dir, name)
            if (from.exists()) { flush(); gone.mkdirs(); if (!from.renameTo(File(gone, name))) throw IOException("could not move $name aside") }
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

    private fun syncTrash(lib: Library, found: MutableList<String>) {
        val used = HashSet<String>()
        for (t in lib.trash) {
            val file = t.file
            val want = pick(trashDir, "${t.at}-${safe(file.name)}", ".ink", file.disk, used)
            file.disk = place(file.disk, want) { old, new -> shiftFile(file, old, new) }
            syncFile(file, want, found)
            writeMeta(File(want, "trash.json"), mapOf("from" to t.from, "at" to t.at))
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
        if (current == null) { want.mkdirs(); return want }
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

    /** A number that changes whenever what is saved for [file] alone would: its opened time, pages and (with [view]) where it is scrolled to. */
    private fun noteFingerprint(file: NoteFile, view: Boolean = true): Int {
        var h = 17
        fun mix(x: Any?) { h = h * 31 + (x?.hashCode() ?: 0) }
        mix(file.opened); if (view) mix(file.view)
        // the last event too: a write finishing while a new stroke is added would otherwise leave the size the same
        file.notebook?.pages?.forEach { mix(System.identityHashCode(it)); mix(it.log.size); mix(it.log.lastOrNull()?.let(System::identityHashCode)) }
        return h
    }

    /** A number that changes whenever anything saved on disk would. With [skip], that note's own part ([noteFingerprint]) is left out. */
    private fun fingerprint(lib: Library, skip: NoteFile? = null): Int {
        var h = 17
        fun mix(x: Any?) { h = h * 31 + (x?.hashCode() ?: 0) }
        fun folder(f: Folder) {
            mix(f.name); mix(f.color); mix(f.pinned)
            f.folders.forEach(::folder)
            for (file in f.files) {
                mix(file.name); mix(file.pinned); mix(System.identityHashCode(file))
                if (file !== skip) mix(noteFingerprint(file))
            }
        }
        folder(lib.root)
        for (t in lib.trash) { mix(t.file.name); mix(t.at); mix(t.from); t.file.notebook?.pages?.forEach { mix(it.log.size) } }
        mix(lib.purged.size); mix(lib.goneFolders.size)
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
