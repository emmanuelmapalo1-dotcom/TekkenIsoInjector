package com.example.tekkeninjector.mod

import com.example.tekkeninjector.iso.IsoEntry

/** Maps extracted mod files onto files in the ISO's PSP_GAME/USRDIR tree by name (and path suffix). */
class ModMatcher(usrdirFiles: List<IsoEntry>) {

    sealed interface Result {
        data class Hit(val entry: IsoEntry) : Result
        data object Miss : Result
        data class Ambiguous(val candidates: List<IsoEntry>) : Result
    }

    private val byName: Map<String, List<IsoEntry>> = usrdirFiles.groupBy { it.name.lowercase() }

    fun match(mod: ModFile): Result {
        val candidates = byName[mod.file.name.lowercase()] ?: return Result.Miss
        if (candidates.size == 1) return Result.Hit(candidates[0])

        // Several ISO files share the name: pick the one whose path shares the longest suffix
        // with the mod's relative path. Require at least name + one folder to agree.
        val modParts = mod.relativePath.lowercase().split('/')
        fun score(e: IsoEntry): Int {
            val p = e.path.lowercase().split('/')
            var n = 0
            while (n < modParts.size && n < p.size && modParts[modParts.size - 1 - n] == p[p.size - 1 - n]) n++
            return n
        }
        val scored = candidates.map { it to score(it) }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        return if (top.size == 1 && best >= 2) Result.Hit(top[0].first) else Result.Ambiguous(candidates)
    }
}
