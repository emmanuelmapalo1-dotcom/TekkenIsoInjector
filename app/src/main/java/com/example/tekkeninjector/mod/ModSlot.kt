package com.example.tekkeninjector.mod

/** What a mod file's name says about the game slot it targets. */
data class ModSlot(
    val fileName: String,
    /** true for placeholder names such as 001_generic_item_Upper_000.ndpp (no Model ID at export time). */
    val generic: Boolean,
    /** Normalized part for generic items: Upper, Lower, HeadFur (or the raw word). */
    val part: String?,
    val isTexture: Boolean,
    /** "s33" / "s12" for explicit names. */
    val prefix: String?,
    val explicitId: String?,
    val hash: String?,
    val variant: String?,
) {
    fun describe(targetId: String): String = when {
        generic -> "generic ${part ?: "?"} ${if (isTexture) "texture package" else "model"}" +
            (variant?.let { " (variant $it)" } ?: "") + " -> target Model ID $targetId"
        explicitId != null -> "explicit $prefix ID $explicitId hash $hash" + if (isTexture) " (texture)" else ""
        else -> "plain name, no slot information"
    }
}

object ModSlotParser {
    private val GENERIC = Regex("001_generic_item_([A-Za-z]+)", RegexOption.IGNORE_CASE)
    private val EXPLICIT = Regex("^(s\\d{2})_([0-9A-Za-z]+)_([0-9A-Za-z]+)_", RegexOption.IGNORE_CASE)
    private val VARIANT = Regex("_(\\d{3})\\.[A-Za-z]+$")

    fun parse(mod: ModFile): ModSlot {
        val name = mod.file.name
        val folders = mod.relativePath.split('/').dropLast(1)
        val isTexture = name.endsWith(".ntpp", ignoreCase = true) ||
            folders.any { it.endsWith("_textures", ignoreCase = true) }
        val variant = VARIANT.find(name)?.groupValues?.get(1)

        val g = GENERIC.find(mod.relativePath)
        if (g != null) {
            return ModSlot(name, true, normalizePart(g.groupValues[1]), isTexture, null, null, null, variant)
        }
        val x = EXPLICIT.find(name)
        if (x != null) {
            return ModSlot(name, false, null, isTexture, x.groupValues[1].lowercase(), x.groupValues[2], x.groupValues[3], variant)
        }
        return ModSlot(name, false, null, isTexture, null, null, null, variant)
    }

    private fun normalizePart(p: String): String = when (p.lowercase()) {
        "upper" -> "Upper"
        "lower" -> "Lower"
        "headfur", "heasfur", "hairfur", "head", "hair" -> "HeadFur"
        else -> p
    }
}
