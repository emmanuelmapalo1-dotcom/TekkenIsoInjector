package com.example.tekkeninjector.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.example.tekkeninjector.iso.ArchiveProbe
import com.example.tekkeninjector.iso.IsoEntry
import com.example.tekkeninjector.iso.IsoImage
import com.example.tekkeninjector.iso.IsoPatcher
import com.example.tekkeninjector.iso.IsoSource
import com.example.tekkeninjector.iso.Replacement
import com.example.tekkeninjector.iso.SECTOR_SIZE
import com.example.tekkeninjector.mod.ModArchive
import com.example.tekkeninjector.mod.ModMatcher
import com.example.tekkeninjector.mod.ModSlotParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext

class InjectorEngine(private val context: Context) {

    data class Summary(
        val inPlace: Int,
        val relocated: Int,
        val unmatched: Int,
        val outputBytes: Long,
        val verified: Boolean,
    )

    // ---------------------------------------------------------------------------------------
    // Build
    // ---------------------------------------------------------------------------------------

    suspend fun run(
        isoUri: Uri,
        zipUri: Uri,
        outUri: Uri,
        targetId: String,
        log: (String) -> Unit,
        progress: (Float, String) -> Unit,
    ): Summary = withContext(Dispatchers.IO) {
        require(outUri != isoUri) { "The output file must be different from the base ISO." }
        val job = coroutineContext.job
        val cancelCheck: () -> Unit = { job.ensureActive() }
        val workDir = File(context.cacheDir, "mod_extract")
        var success = false
        try {
            val summary = execute(isoUri, zipUri, outUri, targetId, workDir, log, progress, cancelCheck)
            success = true
            summary
        } finally {
            workDir.deleteRecursively()
            if (!success) deleteQuietly(outUri)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Analyze only (no ZIP, no output)
    // ---------------------------------------------------------------------------------------

    suspend fun analyze(isoUri: Uri, log: (String) -> Unit, progress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            val job = coroutineContext.job
            val cancelCheck: () -> Unit = { job.ensureActive() }
            progress(0.1f, "Analyzing ISO")
            log("Opening base ISO...")
            val pfd = context.contentResolver.openFileDescriptor(isoUri, "r")
                ?: throw IOException("Cannot open the base ISO")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val source = IsoSource(input.channel)
                val image = IsoImage.open(source)
                val entries = describeImage(image, log)
                ArchiveProbe.probe(source, image, entries, log, cancelCheck)
            }
            progress(1f, "Analysis done")
        }

    // ---------------------------------------------------------------------------------------
    // Export DATA401.IDX (so the index layout can be inspected off-device)
    // ---------------------------------------------------------------------------------------

    suspend fun exportIndex(isoUri: Uri, outUri: Uri, log: (String) -> Unit, progress: (Float, String) -> Unit) =
        withContext(Dispatchers.IO) {
            require(outUri != isoUri) { "The output file must be different from the base ISO." }
            val job = coroutineContext.job
            var ok = false
            try {
                progress(0.1f, "Exporting index")
                val pfd = context.contentResolver.openFileDescriptor(isoUri, "r")
                    ?: throw IOException("Cannot open the base ISO")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    val source = IsoSource(input.channel)
                    val image = IsoImage.open(source)
                    val files = image.listEntries().filter { !it.isDirectory }
                    val idx = ArchiveProbe.findIndex(files)
                        ?: throw IOException("No DATA401.IDX / DATA001.IDX found in PSP_GAME/USRDIR of this ISO")
                    if (idx.size > 64L * 1024 * 1024) throw IOException("${idx.name} is unexpectedly large (${idx.size} bytes)")
                    log("Exporting ${idx.path} (${fmt(idx.size)}) from LBA ${idx.lba}...")
                    val out = context.contentResolver.openOutputStream(outUri, "wt")
                        ?: throw IOException("Cannot open the output file")
                    out.use {
                        val buf = ByteArray(1 shl 20)
                        var pos = idx.lba * SECTOR_SIZE
                        var left = idx.size
                        while (left > 0) {
                            job.ensureActive()
                            val n = minOf(left, buf.size.toLong()).toInt()
                            source.read(pos, buf, n)
                            it.write(buf, 0, n)
                            pos += n
                            left -= n
                        }
                    }
                    log("Exported ${idx.size} bytes. Keep this file as-is; it is a byte-exact copy.")
                }
                ok = true
                progress(1f, "Export done")
            } finally {
                if (!ok) deleteQuietly(outUri)
            }
        }

    // ---------------------------------------------------------------------------------------

    private fun describeImage(image: IsoImage, log: (String) -> Unit): List<IsoEntry> {
        log("Volume '${image.pvd.volumeId}': ${image.pvd.volumeSpaceSectors} sectors (${fmt(image.pvd.volumeSpaceSectors * SECTOR_SIZE)})")
        if (image.pvd.hasSupplementary) {
            log("WARN: image has a supplementary (Joliet) descriptor; only the primary tree is used (PSP/PPSSPP read the primary tree)")
        }
        val entries = image.listEntries()
        val files = entries.filter { !it.isDirectory }
        log("Parsed ${entries.size} directory records (${files.size} files)")
        val byPath = files.associateBy { it.path.uppercase() }
        for (p in listOf("PSP_GAME/SYSDIR/BOOT.BIN", "PSP_GAME/SYSDIR/EBOOT.BIN")) {
            val e = byPath[p]
            if (e == null) log("WARN: $p not found - is this really a PSP UMD image?")
            else log("Boot file $p at LBA ${e.lba} (${fmt(e.size)}) - never modified")
        }
        return entries
    }

    private fun execute(
        isoUri: Uri,
        zipUri: Uri,
        outUri: Uri,
        targetId: String,
        workDir: File,
        log: (String) -> Unit,
        progress: (Float, String) -> Unit,
        cancelCheck: () -> Unit,
    ): Summary {
        val resolver = context.contentResolver

        progress(0f, "Reading ISO")
        log("Opening base ISO...")
        val isoPfd = resolver.openFileDescriptor(isoUri, "r") ?: throw IOException("Cannot open the base ISO")

        return ParcelFileDescriptor.AutoCloseInputStream(isoPfd).use { isoIn ->
            val source = IsoSource(isoIn.channel)
            val image = IsoImage.open(source)
            val entries = describeImage(image, log)
            val files = entries.filter { !it.isDirectory }

            val usrPrefix = "PSP_GAME/USRDIR/"
            val usrFiles = files.filter { it.path.startsWith(usrPrefix, ignoreCase = true) }
            if (usrFiles.isEmpty()) throw IOException("No files found under PSP_GAME/USRDIR - not a PSP game image")

            // Archive probe: tells us whether this is a retail-style packed image.
            val probe = ArchiveProbe.probe(source, image, entries, log, cancelCheck)

            // --- Mod ZIP -------------------------------------------------------------------
            progress(0.03f, "Extracting mod ZIP")
            log("Extracting mod ZIP to cache...")
            val extracted = ModArchive.extract(context, zipUri, workDir, log, cancelCheck)
            val mods = ModArchive.scan(workDir)
            log("Extracted $extracted file(s); ${mods.size} candidate .ndpp/.ntpp/.bone/.bin file(s)")
            if (mods.isEmpty()) throw IOException("The ZIP contains no .ndpp/.ntpp/.bone/.bin files (outside single_textures / unassigned_textures)")

            // --- Matching ------------------------------------------------------------------
            progress(0.12f, "Matching files")
            log("Target Model ID: $targetId")
            val matcher = ModMatcher(usrFiles)
            val chosen = LinkedHashMap<Long, Replacement>()
            var unmatched = 0
            var needArchive = 0
            for (m in mods) {
                cancelCheck()
                val slot = ModSlotParser.parse(m)
                if (slot.generic) {
                    needArchive++
                    log("SLOT   ${m.relativePath}: ${slot.describe(targetId)}")
                    log("       not placed: the DATA401 archive cannot be written by this build")
                    continue
                }
                when (val r = matcher.match(m)) {
                    is ModMatcher.Result.Miss -> {
                        if (slot.explicitId != null) {
                            needArchive++
                            log("SLOT   ${m.relativePath}: ${slot.describe(targetId)} - not a plain file in USRDIR; needs archive support")
                        } else {
                            unmatched++
                            log("SKIP   ${m.relativePath} (no file with that name in USRDIR)")
                        }
                    }
                    is ModMatcher.Result.Ambiguous -> {
                        unmatched++
                        log("WARN: ${m.relativePath} matches ${r.candidates.size} ISO files; keep the folder structure in the ZIP to disambiguate - skipped")
                    }
                    is ModMatcher.Result.Hit -> {
                        val e: IsoEntry = r.entry
                        when {
                            e.multiExtent || e.extAttrSectors > 0 -> {
                                unmatched++
                                log("WARN: ${e.path} uses multi-extent/extended attributes - skipped")
                            }
                            m.file.length() == 0L -> {
                                unmatched++
                                log("WARN: ${m.relativePath} is empty - skipped")
                            }
                            chosen.containsKey(e.recordPos) -> {
                                unmatched++
                                log("WARN: ${m.relativePath} duplicates an earlier file for ${e.path} - skipped")
                            }
                            else -> chosen[e.recordPos] = Replacement(e, m.file)
                        }
                    }
                }
            }
            if (needArchive > 0) log("$needArchive mod file(s) target the packed archive and were NOT injected")
            if (chosen.isEmpty()) {
                if (probe.indexLooksEncrypted) {
                    throw IOException(
                        "Nothing could be injected. This ISO stores its models/textures in an encrypted packed archive " +
                            "(${probe.index?.name ?: "DATA401.IDX"} has near-random bytes), so mod files cannot be written " +
                            "into it from this app. Texture-only mods can instead be loaded through PPSSPP's texture replacement."
                    )
                }
                if (probe.index != null || needArchive > 0) {
                    throw IOException(
                        "Nothing could be injected. This ISO keeps its models/textures inside a packed container " +
                            "(${probe.index?.name ?: "DATA401.IDX"}), which this build can analyze but not patch."
                    )
                }
                throw IOException("No mod file matched anything inside PSP_GAME/USRDIR - nothing to inject")
            }

            // --- Plan ----------------------------------------------------------------------
            val patcher = IsoPatcher(image)
            val plan = patcher.plan(files, chosen.values.toList())
            for (w in plan.writes) {
                val kind = if (w.inPlace) "IN-PLACE " else "RELOCATE "
                log("$kind${w.entry.path}  ${fmt(w.entry.size)} -> ${fmt(w.newSize)}  LBA ${w.entry.lba} -> ${w.newLba}")
            }
            val inPlace = plan.writes.count { it.inPlace }
            val relocated = plan.writes.size - inPlace
            log("Plan: $inPlace in place, $relocated appended; output ${plan.totalSectors} sectors (${fmt(plan.totalSectors * SECTOR_SIZE)})")

            // --- Write ---------------------------------------------------------------------
            progress(0.15f, "Writing ISO")
            val outStream = resolver.openOutputStream(outUri, "wt") ?: throw IOException("Cannot open the output file")
            var lastPermille = -1
            BufferedOutputStream(outStream, 1 shl 20).use { out ->
                patcher.write(
                    plan = plan,
                    out = out,
                    onProgress = { done, total ->
                        val pm = (done * 1000 / total).toInt()
                        if (pm != lastPermille) {
                            lastPermille = pm
                            progress(0.15f + 0.75f * done.toFloat() / total, "Writing ISO")
                        }
                    },
                    checkCancelled = cancelCheck,
                )
            }
            log("Wrote ${fmt(plan.totalSectors * SECTOR_SIZE)}")

            // --- Verify --------------------------------------------------------------------
            progress(0.9f, "Verifying output")
            log("Verifying output image...")
            val verified: Boolean = try {
                val outPfd = resolver.openFileDescriptor(outUri, "r")
                if (outPfd == null) {
                    log("WARN: could not reopen the output for verification (provider does not support it)")
                    false
                } else {
                    ParcelFileDescriptor.AutoCloseInputStream(outPfd).use { outIn ->
                        val problems = patcher.verify(source, IsoSource(outIn.channel), plan, files, cancelCheck)
                        problems.take(20).forEach { log("VERIFY FAIL: $it") }
                        if (problems.isNotEmpty()) throw IOException("Verification failed (${problems.size} problem(s)); output discarded")
                        true
                    }
                }
            } catch (e: SecurityException) {
                log("WARN: could not reopen the output for verification (${e.message})")
                false
            } catch (e: java.io.FileNotFoundException) {
                log("WARN: could not reopen the output for verification (${e.message})")
                false
            }
            if (verified) log("Verification OK: system area, boot files and all untouched LBAs unchanged; replaced files match their CRC32")

            progress(1f, "Done")
            log("DONE: $inPlace replaced in place, $relocated appended, $unmatched skipped, $needArchive need archive support")
            Summary(inPlace, relocated, unmatched + needArchive, plan.totalSectors * SECTOR_SIZE, verified)
        }
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // Best effort: the provider may not support deletion.
        }
    }

    private fun fmt(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }
}
