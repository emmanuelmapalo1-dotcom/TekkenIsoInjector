package com.example.tekkeninjector.iso

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Arrays

const val SECTOR_SIZE = 2048
const val PVD_LBA = 16L
private const val U32_MASK = 0xFFFFFFFFL

fun sectorsFor(bytes: Long): Long = (bytes + SECTOR_SIZE - 1) / SECTOR_SIZE

/** Writes [value] as an ISO-9660 "both-endian" 32-bit field (LE then BE, 8 bytes total). */
fun putBothEndian32(buf: ByteArray, offset: Int, value: Long) {
    for (i in 0..3) {
        buf[offset + i] = (value shr (8 * i)).toByte()
        buf[offset + 4 + i] = (value shr (8 * (3 - i))).toByte()
    }
}

class IsoFormatException(message: String) : IOException(message)

/** Positional reader over a seekable channel. Bytes past EOF read as zero. */
class IsoSource(private val channel: FileChannel) {
    val length: Long = channel.size()

    fun read(position: Long, dst: ByteArray, len: Int = dst.size): ByteArray {
        require(len <= dst.size)
        val avail = (length - position).coerceIn(0L, len.toLong()).toInt()
        val buf = ByteBuffer.wrap(dst, 0, avail)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw IOException("Unexpected end of file at byte $pos")
            pos += n
        }
        if (avail < len) Arrays.fill(dst, avail, len, 0.toByte())
        return dst
    }

    fun readSector(lba: Long): ByteArray = read(lba * SECTOR_SIZE, ByteArray(SECTOR_SIZE))
}

data class PrimaryVolume(
    val volumeId: String,
    val volumeSpaceSectors: Long,
    val rootLba: Long,
    val rootSize: Long,
    val hasSupplementary: Boolean,
)

/**
 * One ISO-9660 directory record.
 * [recordPos] is the absolute byte offset of the record inside the image, so it can be patched later.
 */
data class IsoEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val lba: Long,
    val size: Long,
    val recordPos: Long,
    val extAttrSectors: Int,
    val multiExtent: Boolean,
)

class IsoImage private constructor(val source: IsoSource, val pvd: PrimaryVolume) {

    /** Walks the whole primary directory tree. Paths are relative to the root, '/'-separated. */
    fun listEntries(): List<IsoEntry> {
        val out = ArrayList<IsoEntry>(2048)
        walk(pvd.rootLba, pvd.rootSize, "", out, HashSet(), 0)
        return out
    }

    private fun walk(
        dirLba: Long,
        dirSize: Long,
        parent: String,
        out: MutableList<IsoEntry>,
        visited: MutableSet<Long>,
        depth: Int,
    ) {
        if (depth > 32) throw IsoFormatException("Directory nesting too deep (corrupt image?)")
        if (!visited.add(dirLba)) return
        if (dirSize <= 0 || dirSize > MAX_DIR_BYTES) {
            throw IsoFormatException("Implausible directory size $dirSize at LBA $dirLba")
        }
        val total = (sectorsFor(dirSize) * SECTOR_SIZE).toInt()
        val buf = source.read(dirLba * SECTOR_SIZE, ByteArray(total))
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

        var pos = 0
        while (pos < dirSize) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) { // records never straddle sectors; zero length = padding to next sector
                pos = (pos / SECTOR_SIZE + 1) * SECTOR_SIZE
                continue
            }
            if (len < 34 || pos + len > total) {
                throw IsoFormatException("Corrupt directory record at LBA $dirLba + $pos")
            }
            val nameLen = buf[pos + 32].toInt() and 0xFF
            if (33 + nameLen > len) throw IsoFormatException("Corrupt file identifier at LBA $dirLba + $pos")

            val first = buf[pos + 33].toInt()
            val isDotEntry = nameLen == 1 && (first == 0 || first == 1)
            if (!isDotEntry) {
                val raw = String(buf, pos + 33, nameLen, Charsets.ISO_8859_1)
                val name = raw.substringBefore(';').trimEnd('.')
                val flags = buf[pos + 25].toInt() and 0xFF
                val isDir = (flags and 0x02) != 0
                val lba = bb.getInt(pos + 2).toLong() and U32_MASK
                val size = bb.getInt(pos + 10).toLong() and U32_MASK
                val path = if (parent.isEmpty()) name else "$parent/$name"
                out.add(
                    IsoEntry(
                        name = name,
                        path = path,
                        isDirectory = isDir,
                        lba = lba,
                        size = size,
                        recordPos = dirLba * SECTOR_SIZE + pos,
                        extAttrSectors = buf[pos + 1].toInt() and 0xFF,
                        multiExtent = (flags and 0x80) != 0,
                    )
                )
                if (isDir) walk(lba, size, path, out, visited, depth + 1)
            }
            pos += len
        }
    }

    companion object {
        private const val MAX_DIR_BYTES = 16L * 1024 * 1024

        fun open(source: IsoSource): IsoImage {
            if (source.length < (PVD_LBA + 2) * SECTOR_SIZE) {
                throw IsoFormatException("File is too small to be an ISO-9660 image")
            }
            val magic = String(source.read(0, ByteArray(4)), Charsets.ISO_8859_1)
            if (magic == "CISO" || magic == "ZISO") {
                throw IsoFormatException("Compressed CSO/ZSO images are not supported - convert to a plain .iso first")
            }

            var lba = PVD_LBA
            var pvdBytes: ByteArray? = null
            var supplementary = false
            descriptors@ while (lba < PVD_LBA + 64) {
                val s = source.readSector(lba)
                if (String(s, 1, 5, Charsets.ISO_8859_1) != "CD001") break
                when (s[0].toInt() and 0xFF) {
                    1 -> if (pvdBytes == null) pvdBytes = s
                    2 -> supplementary = true
                    255 -> break@descriptors
                }
                lba++
            }
            val pvd = pvdBytes
                ?: throw IsoFormatException("No ISO-9660 primary volume descriptor ('CD001' at sector 16)")

            val bb = ByteBuffer.wrap(pvd).order(ByteOrder.LITTLE_ENDIAN)
            val blockSize = bb.getShort(128).toInt() and 0xFFFF
            if (blockSize != SECTOR_SIZE) throw IsoFormatException("Unsupported logical block size $blockSize")

            return IsoImage(
                source,
                PrimaryVolume(
                    volumeId = String(pvd, 40, 32, Charsets.ISO_8859_1).trim(),
                    volumeSpaceSectors = bb.getInt(80).toLong() and U32_MASK,
                    rootLba = bb.getInt(156 + 2).toLong() and U32_MASK,
                    rootSize = bb.getInt(156 + 10).toLong() and U32_MASK,
                    hasSupplementary = supplementary,
                ),
            )
        }
    }
}
