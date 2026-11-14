package com.example.tekkeninjector.iso

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.TreeMap
import java.util.zip.CRC32

data class Replacement(val entry: IsoEntry, val modFile: File)

/** Where one replaced file ends up in the output image. */
class PlannedWrite(
    val entry: IsoEntry,
    val file: File,
    val newSize: Long,
    val newLba: Long,
    /** Sectors reserved for the data (original allocation for in-place writes). */
    val sectors: Long,
    /** true = written over the original extent, false = appended past the old end of the image. */
    val inPlace: Boolean,
)

class PatchPlan(
    val writes: List<PlannedWrite>,
    /** Sector number -> replacement bytes for directory-record sectors (and the PVD, if resized). */
    val patchedSectors: TreeMap<Long, ByteArray>,
    val baseSectors: Long,
    val totalSectors: Long,
)

/**
 * Builds and executes a patch of a base ISO.
 *
 * Every sector of the base image keeps its LBA. A replaced file that still fits in its original
 * sector allocation is written over its old extent (zero-padded). A larger file is appended after
 * the end of the image and its directory record (extent LBA + data length) is rewritten to point
 * there; the PVD volume-space size is updated. Nothing else moves, so the system area, boot
 * sectors, SYSDIR/BOOT.BIN, EBOOT.BIN and every untouched file keep their exact LBAs.
 */
class IsoPatcher(private val image: IsoImage) {

    fun plan(allFiles: List<IsoEntry>, replacements: List<Replacement>): PatchPlan {
        val source = image.source
        val baseSectors = maxOf(image.pvd.volumeSpaceSectors, sectorsFor(source.length))
        val extentUsers = allFiles.filter { it.size > 0 }.groupingBy { it.lba }.eachCount()

        var nextFree = baseSectors
        val writes = ArrayList<PlannedWrite>(replacements.size)
        for (r in replacements) {
            val e = r.entry
            val newSize = r.modFile.length()
            if (newSize > 0xFFFFFFFFL) throw IsoFormatException("${r.modFile.name} exceeds the 4 GiB ISO-9660 file limit")
            val origSectors = sectorsFor(e.size)
            val newSectors = sectorsFor(newSize)
            // A shared extent (two records -> same LBA) must never be overwritten in place.
            val fits = e.size > 0 && newSectors <= origSectors && extentUsers[e.lba] == 1
            if (fits) {
                writes += PlannedWrite(e, r.modFile, newSize, e.lba, origSectors, inPlace = true)
            } else {
                writes += PlannedWrite(e, r.modFile, newSize, nextFree, newSectors, inPlace = false)
                nextFree += newSectors
            }
        }
        if (nextFree > 0xFFFFFFFFL) throw IsoFormatException("Result would exceed the ISO-9660 32-bit sector range")

        val inPlace = writes.filter { it.inPlace }.sortedBy { it.newLba }
        for (i in 1 until inPlace.size) {
            val prev = inPlace[i - 1]
            if (inPlace[i].newLba < prev.newLba + prev.sectors) {
                throw IsoFormatException("Base ISO has overlapping file extents (${prev.entry.path} / ${inPlace[i].entry.path})")
            }
        }

        // Patch directory records (extent LBA at +2, data length at +10; both-endian).
        val patched = TreeMap<Long, ByteArray>()
        for (w in writes) {
            val sectorNo = w.entry.recordPos / SECTOR_SIZE
            val off = (w.entry.recordPos % SECTOR_SIZE).toInt()
            val sector = patched.getOrPut(sectorNo) { source.readSector(sectorNo) }
            putBothEndian32(sector, off + 2, w.newLba)
            putBothEndian32(sector, off + 10, w.newSize)
        }

        val grew = nextFree > baseSectors
        if (grew) {
            val pvd = patched.getOrPut(PVD_LBA) { source.readSector(PVD_LBA) }
            putBothEndian32(pvd, 80, nextFree) // Volume Space Size
        }
        return PatchPlan(writes, patched, baseSectors, if (grew) nextFree else baseSectors)
    }

    /** Streams the patched image sequentially to [out] (no seeking required on the output). */
    fun write(
        plan: PatchPlan,
        out: OutputStream,
        onProgress: (done: Long, total: Long) -> Unit,
        checkCancelled: () -> Unit,
    ) {
        val source = image.source
        val total = plan.totalSectors
        var done = 0L
        val buf = ByteArray(CHUNK_SECTORS * SECTOR_SIZE)
        val zeros = ByteArray(64 * 1024)

        fun copyBase(from: Long, to: Long) {
            var lba = from
            while (lba < to) {
                checkCancelled()
                val patch = plan.patchedSectors[lba]
                if (patch != null) {
                    out.write(patch)
                    lba++
                    done++
                    continue
                }
                val nextPatch = plan.patchedSectors.higherKey(lba) ?: Long.MAX_VALUE
                val end = minOf(to, lba + CHUNK_SECTORS, nextPatch)
                val count = (end - lba).toInt()
                source.read(lba * SECTOR_SIZE, buf, count * SECTOR_SIZE)
                out.write(buf, 0, count * SECTOR_SIZE)
                lba = end
                done += count
                onProgress(done, total)
            }
        }

        fun writeFile(w: PlannedWrite) {
            var remaining = w.newSize
            FileInputStream(w.file).use { input ->
                while (remaining > 0) {
                    checkCancelled()
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("${w.file.name} changed size while being read")
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            var pad = w.sectors * SECTOR_SIZE - w.newSize
            while (pad > 0) {
                val n = minOf(pad, zeros.size.toLong()).toInt()
                out.write(zeros, 0, n)
                pad -= n
            }
            done += w.sectors
            onProgress(done, total)
        }

        var cursor = 0L
        for (w in plan.writes.filter { it.inPlace }.sortedBy { it.newLba }) {
            copyBase(cursor, w.newLba)
            writeFile(w)
            cursor = w.newLba + w.sectors
        }
        copyBase(cursor, plan.baseSectors)
        for (w in plan.writes.filter { !it.inPlace }.sortedBy { it.newLba }) writeFile(w)

        if (done != total) throw IOException("Internal error: wrote $done sectors, expected $total")
        out.flush()
    }

    /**
     * Re-parses the finished image and checks: system area (sectors 0-15) identical, every
     * untouched file has the same LBA/size, every replaced file has the planned LBA/size and a
     * matching CRC32. Returns a list of problems (empty = OK).
     */
    fun verify(
        base: IsoSource,
        output: IsoSource,
        plan: PatchPlan,
        baseFiles: List<IsoEntry>,
        checkCancelled: () -> Unit,
    ): List<String> {
        val problems = ArrayList<String>()
        val outImage = try {
            IsoImage.open(output)
        } catch (e: IsoFormatException) {
            return listOf("Output is not a valid ISO-9660 image: ${e.message}")
        }
        if (output.length < plan.totalSectors * SECTOR_SIZE) problems += "Output is shorter than expected"

        val sysBytes = PVD_LBA.toInt() * SECTOR_SIZE
        val a = base.read(0, ByteArray(sysBytes))
        val b = output.read(0, ByteArray(sysBytes))
        if (!a.contentEquals(b)) problems += "System area (sectors 0-15) differs from the base image"

        val outFiles = outImage.listEntries().filter { !it.isDirectory }.associateBy { it.path.lowercase() }
        val replaced = plan.writes.associateBy { it.entry.path.lowercase() }
        for (f in baseFiles) {
            val key = f.path.lowercase()
            val o = outFiles[key]
            if (o == null) {
                problems += "Missing in output: ${f.path}"
                continue
            }
            val w = replaced[key]
            if (w == null) {
                if (o.lba != f.lba || o.size != f.size) problems += "Untouched file moved/resized: ${f.path}"
            } else if (o.lba != w.newLba || o.size != w.newSize) {
                problems += "Wrong directory record for ${f.path}"
            } else if (crcExtent(output, o.lba, o.size, checkCancelled) != crcFile(w.file, checkCancelled)) {
                problems += "Data mismatch in ${f.path}"
            }
        }
        return problems
    }

    private fun crcFile(f: File, checkCancelled: () -> Unit): Long {
        val crc = CRC32()
        val buf = ByteArray(1 shl 20)
        FileInputStream(f).use { input ->
            while (true) {
                checkCancelled()
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    private fun crcExtent(src: IsoSource, lba: Long, size: Long, checkCancelled: () -> Unit): Long {
        val crc = CRC32()
        val buf = ByteArray(1 shl 20)
        var pos = lba * SECTOR_SIZE
        var left = size
        while (left > 0) {
            checkCancelled()
            val n = minOf(left, buf.size.toLong()).toInt()
            src.read(pos, buf, n)
            crc.update(buf, 0, n)
            pos += n
            left -= n
        }
        return crc.value
    }

    private companion object {
        const val CHUNK_SECTORS = 512 // 1 MiB
    }
}
