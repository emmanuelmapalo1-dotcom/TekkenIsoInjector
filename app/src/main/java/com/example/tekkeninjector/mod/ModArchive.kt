package com.example.tekkeninjector.mod

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

data class ModFile(val file: File, val relativePath: String)

object ModArchive {
    val EXTENSIONS = setOf("ndpp", "ntpp", "bone", "bin")
    val IGNORED_DIRS = setOf("single_textures", "unassigned_textures")
    private const val MAX_TOTAL_BYTES = 3L * 1024 * 1024 * 1024

    /**
     * Extracts the relevant files of the ZIP into [dest]. Entries inside ignored folders, with other
     * extensions, with traversal paths ("..") or macOS metadata are not written to disk.
     * Returns the number of files extracted.
     */
    fun extract(
        context: Context,
        zipUri: Uri,
        dest: File,
        log: (String) -> Unit,
        checkCancelled: () -> Unit,
    ): Int {
        dest.deleteRecursively()
        if (!dest.mkdirs()) throw IOException("Cannot create cache directory ${dest.path}")
        val destCanon = dest.canonicalFile
        val input = context.contentResolver.openInputStream(zipUri) ?: throw IOException("Cannot open the mod ZIP")

        var count = 0
        var total = 0L
        var ignoredDirFiles = 0
        ZipInputStream(BufferedInputStream(input, 1 shl 16)).use { zis ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                checkCancelled()
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.isEmpty()) continue
                if (parts.any { it == ".." }) {
                    log("WARN: skipped unsafe ZIP path '${entry.name}'")
                    continue
                }
                if (parts.any { it.equals("__MACOSX", ignoreCase = true) } || parts.last().startsWith("._")) continue
                if (parts.dropLast(1).any { it.lowercase() in IGNORED_DIRS }) {
                    ignoredDirFiles++
                    continue
                }
                val ext = parts.last().substringAfterLast('.', "").lowercase()
                if (ext !in EXTENSIONS) continue

                val target = File(destCanon, parts.joinToString(File.separator)).canonicalFile
                if (!target.path.startsWith(destCanon.path + File.separator)) {
                    log("WARN: skipped path escaping the cache dir '${entry.name}'")
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { fos ->
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL_BYTES) throw IOException("ZIP expands to more than 3 GiB - refusing to continue")
                        fos.write(buf, 0, n)
                    }
                }
                count++
            }
        }
        if (ignoredDirFiles > 0) log("Ignored $ignoredDirFiles file(s) inside single_textures / unassigned_textures")
        return count
    }

    /** Recursively lists candidate files, never descending into the ignored folders. */
    fun scan(root: File): List<ModFile> =
        root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name.lowercase() !in IGNORED_DIRS }
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .map { ModFile(it, it.relativeTo(root).invariantSeparatorsPath) }
            .sortedBy { it.relativePath.lowercase() }
            .toList()
}
