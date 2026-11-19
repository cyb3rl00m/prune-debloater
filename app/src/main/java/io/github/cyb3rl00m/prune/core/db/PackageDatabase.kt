package io.github.cyb3rl00m.prune.core.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Community data (UAD-ng) for one package, compact keys as written by tools/build_db.py. */
@Serializable
data class UadEntry(
    @SerialName("l") val list: String,
    @SerialName("r") val removal: String,
    @SerialName("d") val description: String = "",
    @SerialName("dep") val dependencies: List<String> = emptyList(),
    @SerialName("nb") val neededBy: List<String> = emptyList(),
)

@Serializable
data class LockedEntry(val id: String, val reason: String)

@Serializable
data class LockedPrefix(val prefix: String, val reason: String)

@Serializable
data class Group(
    val key: String,
    val name: String,
    val warning: String,
    val packages: List<String> = emptyList(),
    val prefixes: List<String> = emptyList(),
    /** Substrings of the package name (feature tags only). */
    val patterns: List<String> = emptyList(),
)

@Serializable
data class CuratedEntry(
    val level: Int,
    val risk: String,
    val note: String? = null,
    val google: Boolean = false,
)

@Serializable
data class PrefixEntry(
    val prefix: String,
    val level: Int,
    val risk: String,
    val note: String? = null,
)

@Serializable
data class PruneRules(
    val schema: Int,
    val updated: String,
    val comment: String = "",
    val locked: List<LockedEntry>,
    val lockedPrefixes: List<LockedPrefix>,
    val ecosystems: List<Group>,
    val featureTags: List<Group>,
    val entries: Map<String, CuratedEntry>,
    val prefixEntries: List<PrefixEntry>,
)

/** Prune's package knowledge: its own safety rules plus the community descriptions. */
class PackageDatabase(val rules: PruneRules, val uad: Map<String, UadEntry>) {

    private val lockedById = rules.locked.associateBy { it.id }
    val ecosystemsByKey: Map<String, Group> = rules.ecosystems.associateBy { it.key }
    val featureTagsByKey: Map<String, Group> = rules.featureTags.associateBy { it.key }

    fun lockedReason(pkg: String): String? =
        lockedById[pkg]?.reason ?: rules.lockedPrefixes.firstOrNull { pkg.startsWith(it.prefix) }?.reason

    fun curated(pkg: String): CuratedEntry? = rules.entries[pkg]

    fun prefixEntry(pkg: String): PrefixEntry? = rules.prefixEntries.firstOrNull { pkg.startsWith(it.prefix) }

    fun ecosystemsOf(pkg: String): List<Group> = rules.ecosystems.filter { g -> matches(g, pkg) }

    fun featureTagsOf(pkg: String): List<Group> = rules.featureTags.filter { g -> matches(g, pkg) }

    private fun matches(g: Group, pkg: String): Boolean =
        pkg in g.packages || g.prefixes.any { pkg.startsWith(it) } ||
            g.patterns.any { pkg.contains(it, ignoreCase = true) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(rulesJson: String, uadJson: String): PackageDatabase =
            PackageDatabase(
                json.decodeFromString(PruneRules.serializer(), rulesJson),
                json.decodeFromString(MapSerializer(String.serializer(), UadEntry.serializer()), uadJson),
            )
    }
}
