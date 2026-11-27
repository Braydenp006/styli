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

    /** Makes the disk match [lib]. Anything that fails is listed in [problems] and tried again next time. */
    fun save(lib: Library): List<String> {
        val found = ArrayList<String>()
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
    private fun syncFile(file: NoteFile, dir: File, found: MutableList<String>) {
        File(dir, "trash.json").delete()
        writeMeta(File(dir, "file.json"), mapOf("name" to file.name, "kind" to "ink", "created" to file.created, "opened" to file.opened, "pinned" to file.pinned))
        file.view?.let { writeMeta(File(dir, "view.json"), it) }
        val nb = file.notebook ?: return
        val live = nb.pages
        val gone = File(dir, ".deleted")
        for (p in live) {
            val name = file.pageNames.getOrPut(p) { nextPageName(dir, file) }
            val inGone = File(gone, name)
            if (inGone.exists() && !File(dir, name).exists()) { // a deleted page was brought back
                if (!inGone.renameTo(File(dir, name))) throw IOException("could not bring back ${inGone.name}")
            }
            try { writePictures(nb, p, dir); writePage(file, p, File(dir, name)) } catch (e: Exception) { found.add("${file.name}: ${e.message}") }
        }
        for ((p, name) in file.pageNames.entries.toList()) {
            if (live.any { it === p }) continue
            val from = File(dir, name)
            if (from.exists()) { gone.mkdirs(); if (!from.renameTo(File(gone, name))) throw IOException("could not move $name aside") }
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

    /** A number that changes whenever anything saved on disk would. */
    private fun fingerprint(lib: Library): Int {
        var h = 17
        fun mix(x: Any?) { h = h * 31 + (x?.hashCode() ?: 0) }
        fun folder(f: Folder) {
            mix(f.name); mix(f.color); mix(f.pinned)
            f.folders.forEach(::folder)
            for (file in f.files) {
                mix(file.name); mix(file.opened); mix(file.pinned); mix(file.view); mix(System.identityHashCode(file))
                file.notebook?.pages?.forEach { mix(System.identityHashCode(it)); mix(it.log.size) }
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
