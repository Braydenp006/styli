package app.styli.ink

import java.io.File

/**
 * Where the notes folder is. The choice is kept in the app's own preferences (it cannot live inside the notes folder
 * it points to), and defaults to Documents/Tabularium. What is in the folder is [Vault]'s business.
 */
object Storage {
    private const val PREFS = "styli"
    private const val KEY = "root"

    /** The folder the person chose (or the default, once they said to use it); null before they have said. */
    fun saved(context: android.content.Context): File? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY, null)?.let(::File)

    fun save(context: android.content.Context, dir: File) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(KEY, dir.absolutePath).commit()
    }

    fun default(): File = Vault.rootDir()

    /** A folder picked with the system's folder picker, as an ordinary path (only phone and SD card storage can be one). */
    fun fromTree(uri: android.net.Uri): File? = try {
        treeIdToFile(android.provider.DocumentsContract.getTreeDocumentId(uri), android.os.Environment.getExternalStorageDirectory())
    } catch (_: Exception) { null }

    /** "primary:Documents/Notes" is Documents/Notes on the phone's storage; "1A2B-3C4D:Notes" is Notes on that card. Anything else (a cloud drive) is null. */
    fun treeIdToFile(docId: String, primary: File, volumes: File = File("/storage")): File? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon); val path = docId.substring(colon + 1)
        if (path.split('/').any { it == ".." }) return null
        val base = if (volume == "primary") primary else File(volumes, volume).takeIf { volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) } ?: return null
        return if (path.isEmpty()) base else File(base, path)
    }

    /** True if one folder is the other or inside it (moving notes into themselves would never end). */
    fun overlaps(a: File, b: File): Boolean {
        val x = a.canonicalFile.path + File.separator; val y = b.canonicalFile.path + File.separator
        return x.startsWith(y) || y.startsWith(x)
    }

    /** A path for people to read: Documents/Tabularium rather than /storage/emulated/0/Documents/Tabularium. */
    fun shortName(f: File): String = f.path.removePrefix(android.os.Environment.getExternalStorageDirectory().path + "/")
}
