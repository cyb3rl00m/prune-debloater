package io.github.cyb3rl00m.prune.core.db

import io.github.cyb3rl00m.prune.core.model.Classification
import io.github.cyb3rl00m.prune.core.model.PackageFacts
import io.github.cyb3rl00m.prune.core.model.RecommendedAction
import io.github.cyb3rl00m.prune.core.model.Risk
import io.github.cyb3rl00m.prune.core.model.Source
import io.github.cyb3rl00m.prune.core.model.Warning

/**
 * Packages that are protected because of how this particular phone is set up right now
 * (default apps, active keyboard, device admins...). Read fresh on every scan.
 */
data class DynamicProtections(
    /** package -> reason it must not be touched */
    val locked: Map<String, String> = emptyMap(),
    /** package -> warning that it is in use, but may be changed */
    val inUse: Map<String, String> = emptyMap(),
)

/**
 * Decides risk, category, level and recommendation for one package.
 *
 * Order of authority (first match wins for locking, later steps only refine):
 *  1. Prune's locked list and locked prefixes.
 *  2. Dynamic protections (this phone's default launcher, dialer, SMS app, keyboard, device admins,
 *     enabled accessibility services).
 *  3. Persistent system apps (Android keeps them running at all times).
 *  4. Prune's curated entry, then curated prefix entries.
 *  5. Community data (UAD-ng tier), mapped conservatively.
 *  6. Unknown packages: system ones are HIGH risk and only offered in Custom mode.
 */
class Classifier(private val db: PackageDatabase) {

    fun classify(facts: PackageFacts, dynamic: DynamicProtections = DynamicProtections()): Classification {
        val pkg = facts.packageName
        val uad = db.uad[pkg]
        val curated = db.curated(pkg)
        val prefix = if (curated == null) db.prefixEntry(pkg) else null
        val source = sourceOf(facts, uad, curated)
        val ecosystems = db.ecosystemsOf(pkg)
        val features = db.featureTagsOf(pkg)
        val groups = (ecosystems + features).map { it.key }.toSet()
        val warnings = (ecosystems + features).map { Warning(it.key, it.name, it.warning) }.toMutableList()
        val notes = mutableListOf<String>()
        val description = uad?.description?.takeIf { it.isNotBlank() } ?: curated?.note ?: defaultDescription(facts, source)
        if (curated?.note != null && uad?.description?.isNotBlank() == true) notes += curated.note
        prefix?.note?.let { if (uad == null) notes += it }

        dynamic.inUse[pkg]?.let { warnings += Warning("IN_USE", "In use on this phone", it) }

        fun locked(reason: String) = Classification(
            source = source, risk = Risk.LOCKED, level = null, description = description, notes = notes,
            groups = groups, warnings = warnings, lockedReason = reason, recommendedAction = RecommendedAction.KEEP,
            inDatabase = uad != null || curated != null, dependencies = uad?.dependencies.orEmpty(),
            neededBy = uad?.neededBy.orEmpty(), communityTier = uad?.removal?.let(::tierName),
        )

        db.lockedReason(pkg)?.let { return locked(it) }
        dynamic.locked[pkg]?.let { return locked(it) }
        if (facts.isSystem && facts.isPersistent) {
            return locked("Persistent system app: Android keeps it running at all times because the system needs it.")
        }

        var risk: Risk
        var level: Int?
        when {
            curated != null -> {
                risk = Risk.valueOf(curated.risk)
                level = curated.level
            }
            prefix != null -> {
                risk = Risk.valueOf(prefix.risk)
                level = prefix.level
            }
            uad != null -> {
                val mapped = mapCommunityTier(uad)
                if (mapped == null) return locked("Marked unsafe to remove by the community database (UAD-ng).")
                risk = mapped.first
                level = mapped.second
            }
            !facts.isSystem -> {
                risk = Risk.SAFE
                level = null
            }
            else -> {
                risk = Risk.HIGH
                level = 4
                notes += "Not in Prune's database. Change it only if you know what it does."
            }
        }

        // Packages sharing the system/phone identity get at least HIGH unless Prune curated them.
        if (curated == null && prefix == null && facts.sharedUserId in PRIVILEGED_SHARED_UIDS) {
            if (risk.ordinal < Risk.HIGH.ordinal) risk = Risk.HIGH
            level = 4
            notes += "Runs with system privileges (${facts.sharedUserId})."
        }
        // Without a curated entry, ecosystem membership raises the risk floor to MODERATE.
        if (curated == null && ecosystems.isNotEmpty() && risk.ordinal < Risk.MODERATE.ordinal) risk = Risk.MODERATE
        if (risk == Risk.HIGH) level = 4

        val recommended = when {
            !facts.isSystem -> RecommendedAction.UNINSTALL_APP.takeIf { source == Source.PRELOAD } ?: RecommendedAction.KEEP
            risk == Risk.HIGH -> RecommendedAction.REVIEW
            level != null && level <= 3 -> RecommendedAction.DISABLE
            else -> RecommendedAction.REVIEW
        }
        if (!facts.isSystem) level = level?.takeIf { source == Source.PRELOAD }

        return Classification(
            source = source, risk = risk, level = level, description = description, notes = notes, groups = groups,
            warnings = warnings, lockedReason = null, recommendedAction = recommended,
            inDatabase = uad != null || curated != null || prefix != null,
            dependencies = uad?.dependencies.orEmpty(), neededBy = uad?.neededBy.orEmpty(),
            communityTier = uad?.removal?.let(::tierName),
        )
    }

    /** Maps the community tier to (risk, level). Returns null for "unsafe". Conservative on purpose. */
    private fun mapCommunityTier(e: UadEntry): Pair<Risk, Int>? {
        val thirdParty = e.list == "c" || e.list == "m"
        return when (e.removal) {
            "r" -> if (thirdParty) Risk.LOW to 1 else Risk.MODERATE to 3
            "a" -> if (thirdParty) Risk.MODERATE to 3 else Risk.MODERATE to 4
            "e" -> Risk.HIGH to 4
            else -> null
        }
    }

    private fun sourceOf(facts: PackageFacts, uad: UadEntry?, curated: CuratedEntry?): Source {
        val pkg = facts.packageName
        if (!facts.isSystem) {
            // Updated or user-installed copies of known preloads still count as preloads.
            return if (uad?.list == "m" || uad?.list == "c" || (curated != null && !curated.google &&
                    !pkg.startsWith("com.samsung") && !pkg.startsWith("com.sec."))) Source.PRELOAD
            else Source.USER
        }
        if (curated?.google == true) return Source.GOOGLE
        when (uad?.list) {
            "o" -> return Source.SAMSUNG
            "g" -> return Source.GOOGLE
            "a" -> return Source.ANDROID
            "c" -> return Source.CARRIER
            "m" -> return Source.PRELOAD
        }
        return when {
            SAMSUNG_PREFIXES.any { pkg.startsWith(it) } -> Source.SAMSUNG
            pkg.startsWith("com.google.") || pkg == "com.android.vending" || pkg == "com.android.chrome" -> Source.GOOGLE
            pkg == "android" || pkg.startsWith("com.android.") -> Source.ANDROID
            CARRIER_PREFIXES.any { pkg.startsWith(it) } -> Source.CARRIER
            curated != null -> Source.PRELOAD
            else -> Source.OTHER_SYSTEM
        }
    }

    private fun defaultDescription(facts: PackageFacts, source: Source): String = when {
        !facts.isSystem -> "An app you installed (or that was installed for you). You can uninstall it like any other app."
        source == Source.SAMSUNG -> "Samsung system component. Prune has no description for it yet."
        source == Source.GOOGLE -> "Google system component. Prune has no description for it yet."
        source == Source.CARRIER -> "Carrier-installed component."
        else -> "System component. Prune has no description for it yet."
    }

    private fun tierName(code: String) = when (code) {
        "r" -> "Recommended"
        "a" -> "Advanced"
        "e" -> "Expert"
        "u" -> "Unsafe"
        else -> code
    }

    companion object {
        val PRIVILEGED_SHARED_UIDS = setOf(
            "android.uid.system", "android.uid.phone", "android.uid.systemui", "android.uid.nfc",
            "android.uid.bluetooth", "android.uid.shell", "android.uid.networkstack", "android.uid.se",
            "android.media", "android.uid.log",
        )
        val SAMSUNG_PREFIXES = listOf("com.samsung", "com.sec.", "com.sem.", "com.osp.", "com.knox", "com.skms",
            "com.wssyncmldm", "com.wsomacp")
        val CARRIER_PREFIXES = listOf(
            "com.vzw", "com.verizon", "com.motricity.verizon", "com.tmobile", "com.tmo", "com.t-mobile", "com.att.",
            "com.sprint", "com.telekom", "de.telekom", "com.vodafone", "uk.co.ee", "com.orange", "com.telefonica",
            "es.movistar", "com.movistar", "com.bouyguestelecom", "fr.free", "com.sfr", "com.telstra", "com.optus",
            "com.rogers", "ca.bell", "com.telus", "com.uscc", "com.cricketwireless", "com.metropcs", "com.boost",
            "com.dish", "com.playphone", "com.dti.", "com.ironsource.appcloud", "com.aura.oobe", "com.lguplus",
            "com.sktelecom", "com.kt.", "com.plus.", "pl.plus", "pl.play", "com.orange.pl", "com.wind", "it.tim",
            "com.three", "com.h3g", "com.drei", "com.a1", "com.mtel", "bg.vivacom", "com.yettel",
        )
    }
}
