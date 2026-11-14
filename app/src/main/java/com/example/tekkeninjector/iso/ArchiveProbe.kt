package com.example.tekkeninjector.iso

import java.util.Locale
import kotlin.math.ln

/**
 * Read-only diagnostics for the packed container used by retail Tekken 6 PSP images
 * (DATA401.IDX + DATA400.BIN, or DATA001.IDX + DATA000.BIN). It does NOT interpret the index:
 * it collects raw facts (where the files are, sizes, entropy, strings, unreferenced disc regions)
 * so the on-disc layout can be verified against a real image before any bytes are rewritten.
 */
object ArchiveProbe {
    private val PAIRS = listOf("DATA401.IDX" to "DATA400.BIN", "DATA001.IDX" to "DATA000.BIN")
    private const val USR = "PSP_GAME/USRDIR/"
    private const val GAP_SECTORS = 512L // report unreferenced disc regions >= 1 MiB
    private const val MAX_IDX_READ = 8L * 1024 * 1024
    private const val BULK_SAMPLE = 256 * 1024

    data class Result(
        val index: IsoEntry?,
        val bulk: IsoEntry?,
        val unreferencedRegions: List<LongRange>,
        /** true when the index has near-random bytes (entropy > 7.5 bits/byte): encrypted or compressed. */
        val indexLooksEncrypted: Boolean,
    )

    fun findIndex(files: List<IsoEntry>): IsoEntry? =
        PAIRS.firstNotNullOfOrNull { (idx, _) -> findInUsrdir(files, idx) }

    private fun findInUsrdir(files: List<IsoEntry>, name: String): IsoEntry? =
        files.firstOrNull { it.path.equals(USR + name, ignoreCase = true) }

    fun probe(
        source: IsoSource,
        image: IsoImage,
        allEntries: List<IsoEntry>,
        log: (String) -> Unit,
        checkCancelled: () -> Unit,
    ): Result {
        val files = allEntries.filter { !it.isDirectory }
        log("--- Archive probe ---")

        val usr = files.filter { it.path.startsWith(USR, ignoreCase = true) }.sortedBy { it.lba }
        log("PSP_GAME/USRDIR: ${usr.size} file(s), ${usr.sumOf { it.size }} bytes total")
        usr.take(30).forEach { log("  ${it.path.substring(USR.length)}  LBA ${it.lba}  ${it.size} B") }
        if (usr.size > 30) log("  ... ${usr.size - 30} more")

        var index: IsoEntry? = null
        var bulk: IsoEntry? = null
        var pairName: String? = null
        for ((i, b) in PAIRS) {
            val ie = findInUsrdir(files, i)
            val be = findInUsrdir(files, b)
            log("  $i: " + (ie?.let { "found, ${it.size} B @ LBA ${it.lba}" } ?: "not found"))
            log("  $b: " + (be?.let { "found, ${it.size} B @ LBA ${it.lba}" } ?: "not found"))
            if (index == null && ie != null) {
                index = ie
                bulk = be
                pairName = "$i / $b"
            }
        }

        val others = files.filter {
            !it.path.startsWith(USR, ignoreCase = true) &&
                (it.name.endsWith(".IDX", ignoreCase = true) || it.name.startsWith("DATA", ignoreCase = true))
        }
        others.forEach { log("  other container-like file: ${it.path}  ${it.size} B @ LBA ${it.lba}") }

        // Disc regions that no ISO-9660 record points at (raw data can live there).
        val gaps = unreferencedRegions(source, image, allEntries)
        if (gaps.isEmpty()) {
            log("No large unreferenced disc regions (everything is reachable through ISO-9660 files)")
        } else {
            gaps.take(8).forEach {
                val n = it.last - it.first + 1
                log("Unreferenced disc region: LBA ${it.first}..${it.last} (${n * SECTOR_SIZE / (1024 * 1024)} MB) - may hold raw data outside the file system")
            }
        }

        gaps.take(4).forEach { analyzeRegion(source, it, log) }
        if (index != null) {
            val afterIdx = index.lba + sectorsFor(index.size)
            gaps.firstOrNull { it.first == afterIdx }?.let {
                val mb = (it.last - it.first + 1) * SECTOR_SIZE / (1024 * 1024)
                log("The region starting at LBA ${it.first} (${mb} MB) begins right after the index: most likely the bulk data, addressed by raw sector")
            }
        }

        val indexEncrypted = if (index != null) analyzeIndex(source, index, log, checkCancelled) else false
        if (bulk != null) analyzeBulk(source, bulk, log)
        else if (index != null) {
            log("Bulk file for $pairName is not an ISO file: the data is either elsewhere on the disc (raw LBA reads) or in another file listed above")
        }
        log("IDX entries parsed: n/a - the record layout is not decoded by this build; only the raw statistics above were collected")
        log("--- end of probe ---")
        return Result(index, bulk, gaps, indexEncrypted)
    }

    /** Disc regions (>= 1 MiB) that no ISO-9660 record points at. */
    fun unreferencedRegions(source: IsoSource, image: IsoImage, allEntries: List<IsoEntry>): List<LongRange> {
        val extents = allEntries.filter { it.size > 0 }
            .map { it.lba to it.lba + sectorsFor(it.size) }
            .sortedBy { it.first }
        val gaps = ArrayList<LongRange>()
        var cursor = 18L // system area + volume descriptors
        for ((s, e) in extents) {
            if (s - cursor >= GAP_SECTORS) gaps += cursor until s
            if (e > cursor) cursor = e
        }
        val total = maxOf(image.pvd.volumeSpaceSectors, sectorsFor(source.length))
        if (total - cursor >= GAP_SECTORS) gaps += cursor until total
        return gaps
    }

    private fun analyzeRegion(source: IsoSource, r: LongRange, log: (String) -> Unit) {
        val sectors = r.last - r.first + 1
        val n = minOf(sectors * SECTOR_SIZE, BULK_SAMPLE.toLong()).toInt()
        val head = source.read(r.first * SECTOR_SIZE, ByteArray(n))
        val zeroPct = head.count { it.toInt() == 0 } * 100 / maxOf(n, 1)
        val h = entropy(head, n)
        log("Region LBA ${r.first}: first 32 bytes: ${hex(head, 32)}")
        log("  first ${n / 1024} KB: entropy ${String.format(Locale.US, "%.2f", h)} bits/byte, ${zeroPct}% zero bytes" +
            when {
                h > 7.5 -> " (looks encrypted or compressed)"
                zeroPct > 90 -> " (mostly empty)"
                else -> " (looks like plain structured data)"
            })
        if (sectors * SECTOR_SIZE > 8L * 1024 * 1024) {
            val mid = source.read((r.first + sectors / 2) * SECTOR_SIZE, ByteArray(BULK_SAMPLE))
            log("  middle 256 KB: entropy ${String.format(Locale.US, "%.2f", entropy(mid, mid.size))} bits/byte")
        }
    }

    private fun analyzeIndex(source: IsoSource, e: IsoEntry, log: (String) -> Unit, check: () -> Unit): Boolean {
        check()
        val len = minOf(e.size, MAX_IDX_READ).toInt()
        val data = source.read(e.lba * SECTOR_SIZE, ByteArray(len))
        log("Index ${e.name}: ${e.size} bytes @ LBA ${e.lba}")
        log("  first 48 bytes: ${hex(data, 48)}")
        val words = (0 until 4).filter { (it + 1) * 4 <= len }.joinToString(", ") { "0x%08X".format(u32le(data, it * 4)) }
        log("  first u32 (LE): $words")
        val h = entropy(data, len)
        log("  entropy: ${String.format(Locale.US, "%.2f", h)} bits/byte" +
            if (h > 7.5) " (looks encrypted or compressed)" else " (looks like a structured table)")
        val divisors = (4..128).filter { e.size % it == 0L }
        log("  size is divisible by: ${divisors.joinToString(", ")}")

        val text = String(data, 0, len, Charsets.ISO_8859_1)
        val strings = Regex("[ -~]{6,}").findAll(text).map { it.value }.toList()
        val slotNames = Regex("s\\d{2}_[0-9A-Za-z]+_[0-9A-Za-z]+").findAll(text).count()
        val extNames = Regex("\\.(ndpp|ntpp|bone|bin)", RegexOption.IGNORE_CASE).findAll(text).count()
        log("  ASCII strings (>=6 chars): ${strings.size}; sNN_<id>_<hash> names: $slotNames; mod-style extensions: $extNames")
        strings.take(8).forEach { log("    \"${it.take(64)}\"") }
        if (h > 7.5) log("  => the index is encrypted/compressed: its entries cannot be read or rewritten without the game's cipher")
        return h > 7.5
    }

    private fun analyzeBulk(source: IsoSource, e: IsoEntry, log: (String) -> Unit) {
        val n = minOf(e.size, BULK_SAMPLE.toLong()).toInt()
        val data = source.read(e.lba * SECTOR_SIZE, ByteArray(n))
        log("Bulk ${e.name}: ${e.size} bytes @ LBA ${e.lba}")
        log("  first 32 bytes: ${hex(data, 32)}")
        val h = entropy(data, n)
        log("  entropy of first ${n / 1024} KB: ${String.format(Locale.US, "%.2f", h)} bits/byte" +
            if (h > 7.5) " (looks encrypted or compressed - writing plain bytes into it would corrupt it)" else "")
    }

    private fun hex(d: ByteArray, count: Int): String =
        d.take(count).joinToString(" ") { "%02X".format(it) }

    private fun u32le(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24)

    private fun entropy(d: ByteArray, len: Int): Double {
        if (len == 0) return 0.0
        val counts = IntArray(256)
        for (i in 0 until len) counts[d[i].toInt() and 0xFF]++
        var h = 0.0
        for (c in counts) if (c > 0) {
            val p = c.toDouble() / len
            h -= p * ln(p) / ln(2.0)
        }
        return h
    }
}
