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
    /** Only applies on these Android versions (API levels), when set. */
    val minSdk: Int? = null,
    val maxSdk: Int? = null,
    /** Only applies on these firmwares (for example "HyperOS"), when set. */
    val firmware: List<String> = emptyList(),
    val consequences: String? = null,
    val recovery: String? = null,
)

@Serializable
data class PrefixEntry(
    val prefix: String,
    val level: Int,
    val risk: String,
    val note: String? = null,
)

/** One rule file: core.json or a manufacturer file. */
@Serializable
data class PruneRules(
    val schema: Int,
    val manufacturer: String = "Generic Android",
    val brands: List<String> = emptyList(),
    val updated: String,
    val comment: String = "",
    val locked: List<LockedEntry> = emptyList(),
    val lockedPrefixes: List<LockedPrefix> = emptyList(),
    val ecosystems: List<Group> = emptyList(),
    val featureTags: List<Group> = emptyList(),
    val entries: Map<String, CuratedEntry> = emptyMap(),
    val prefixEntries: List<PrefixEntry> = emptyList(),
)

/** A curated entry together with the rule file it came from. */
data class SourcedEntry(val entry: CuratedEntry, val manufacturer: String)

/** What the classifier needs to know about the device to apply firmware- or version-specific entries. */
data class DeviceContext(val sdkInt: Int = Int.MAX_VALUE, val firmware: String? = null)

/**
 * Prune's package knowledge: the core rules, every manufacturer's rules, and the community descriptions.
 * All manufacturer files are always loaded: a package name means the same on every device, and rebranded
 * firmware (Redmi, POCO, OnePlus, realme...) still finds its rules.
 */
class PackageDatabase(val files: List<PruneRules>, val uad: Map<String, UadEntry>) {

    private val lockedById: Map<String, String> = files.flatMap { it.locked }.associate { it.id to it.reason }
    private val lockedPrefixes: List<LockedPrefix> = files.flatMap { it.lockedPrefixes }
    private val entries: Map<String, SourcedEntry> =
        files.flatMap { f -> f.entries.map { (k, v) -> k to SourcedEntry(v, f.manufacturer) } }.toMap()
    private val prefixEntries: List<PrefixEntry> = files.flatMap { it.prefixEntries }

    /** Ecosystems ("features you use"), merged by key across files. */
    val ecosystems: List<Group> = merge(files.flatMap { it.ecosystems })
    val featureTags: List<Group> = merge(files.flatMap { it.featureTags })
    val ecosystemsByKey: Map<String, Group> = ecosystems.associateBy { it.key }
    val updated: String = files.maxOfOrNull { it.updated } ?: ""
    val curatedCount: Int = entries.size

    fun lockedReason(pkg: String): String? =
        lockedById[pkg] ?: lockedPrefixes.firstOrNull { pkg.startsWith(it.prefix) }?.reason

    /** The curated entry for a package, if it applies to this Android version and firmware. */
    fun curated(pkg: String, device: DeviceContext = DeviceContext()): SourcedEntry? {
        val s = entries[pkg] ?: return null
        val e = s.entry
        if (e.minSdk != null && device.sdkInt < e.minSdk) return null
        if (e.maxSdk != null && device.sdkInt > e.maxSdk) return null
        if (e.firmware.isNotEmpty() && device.firmware != null &&
            e.firmware.none { device.firmware.startsWith(it, ignoreCase = true) }) return null
        return s
    }

    fun prefixEntry(pkg: String): PrefixEntry? = prefixEntries.firstOrNull { pkg.startsWith(it.prefix) }

    fun ecosystemsOf(pkg: String): List<Group> = ecosystems.filter { g -> matches(g, pkg) }

    fun featureTagsOf(pkg: String): List<Group> = featureTags.filter { g -> matches(g, pkg) }

    private fun matches(g: Group, pkg: String): Boolean =
        pkg in g.packages || g.prefixes.any { pkg.startsWith(it) } ||
            g.patterns.any { pkg.contains(it, ignoreCase = true) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Groups with the same key in several files become one group (first name and warning win). */
        private fun merge(groups: List<Group>): List<Group> = groups.groupBy { it.key }.map { (_, gs) ->
            gs.first().copy(
                packages = gs.flatMap { it.packages }.distinct(),
                prefixes = gs.flatMap { it.prefixes }.distinct(),
                patterns = gs.flatMap { it.patterns }.distinct(),
            )
        }

        fun parseRules(text: String): PruneRules = json.decodeFromString(PruneRules.serializer(), text)

        fun parse(rulesJson: List<String>, uadJson: String): PackageDatabase =
            PackageDatabase(
                rulesJson.mapNotNull { runCatching { parseRules(it) }.getOrNull() },
                json.decodeFromString(MapSerializer(String.serializer(), UadEntry.serializer()), uadJson),
            )
    }
}
