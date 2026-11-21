package io.github.cyb3rl00m.prune.core.db

import io.github.cyb3rl00m.prune.core.model.Classification
import io.github.cyb3rl00m.prune.core.model.PackageFacts
import io.github.cyb3rl00m.prune.core.model.RecommendedAction
import io.github.cyb3rl00m.prune.core.model.Risk
import io.github.cyb3rl00m.prune.core.model.Source
import io.github.cyb3rl00m.prune.core.model.Warning
import io.github.cyb3rl00m.prune.core.oem.OemRegistry

/**
 * Packages that are protected because of how this particular phone is set up right now
 * (default apps, active keyboard, device admins, missing Google services...). Read fresh on every scan.
 */
data class DynamicProtections(
    /** package -> reason it must not be touched */
    val locked: Map<String, String> = emptyMap(),
    /** package -> warning that it is in use, but may be changed */
    val inUse: Map<String, String> = emptyMap(),
)

/**
 * Decides risk, category, level and recommendation for one package. Manufacturer-independent: manufacturer
 * knowledge comes from the rule files and [OemRegistry].
 *
 * Order of authority (first match wins for locking, later steps only refine):
 *  1. Prune's locked lists (core and every manufacturer file).
 *  2. Dynamic protections (this phone's launcher, dialer, SMS app, keyboard, device admins, accessibility
 *     services, and service frameworks a phone without Google services depends on).
 *  3. Persistent system apps (Android keeps them running at all times).
 *  4. Prune's curated entry (if it applies to this Android version and firmware), then curated prefix entries.
 *  5. Community data (UAD-ng tier), mapped conservatively.
 *  6. Unknown system packages: risk UNKNOWN, only offered in Custom mode, never pre-selected.
 */
class Classifier(private val db: PackageDatabase, private val device: DeviceContext = DeviceContext()) {

    /** Never throws: a package Prune cannot analyse is treated as unknown. */
    fun classify(facts: PackageFacts, dynamic: DynamicProtections = DynamicProtections()): Classification =
        runCatching { classifyUnsafe(facts, dynamic) }.getOrElse {
            Classification(
                source = if (facts.isSystem) Source.OTHER_SYSTEM else Source.USER, risk = Risk.UNKNOWN,
                level = if (facts.isSystem) 4 else null, description = "Prune could not analyse this package.",
                notes = emptyList(), groups = emptySet(), warnings = emptyList(), lockedReason = null,
                recommendedAction = RecommendedAction.REVIEW, inDatabase = false, recovery = recoveryFor(facts),
            )
        }

    private fun classifyUnsafe(facts: PackageFacts, dynamic: DynamicProtections): Classification {
        val pkg = facts.packageName
        val uad = db.uad[pkg]
        val sourced = db.curated(pkg, device)
        val curated = sourced?.entry
        val prefix = if (curated == null) db.prefixEntry(pkg) else null
        val oem = OemRegistry.manufacturerOf(pkg)
        val preloadInstaller = facts.installer?.let { inst -> PRELOAD_INSTALLERS.any { inst == it || inst.startsWith("$it.") } } == true
        val source = sourceOf(facts, uad, curated, oem != null, preloadInstaller)
        val manufacturer = when (source) {
            Source.OEM -> oem?.let { manufacturerName(it.id) } ?: sourced?.manufacturer?.takeIf { it != "Generic Android" }
            else -> null
        }
        val ecosystems = db.ecosystemsOf(pkg)
        val features = db.featureTagsOf(pkg)
        val groups = (ecosystems + features).map { it.key }.toSet()
        val warnings = (ecosystems + features).map { Warning(it.key, it.name, it.warning) }.toMutableList()
        val notes = mutableListOf<String>()
        val description = uad?.description?.takeIf { it.isNotBlank() } ?: curated?.note ?: defaultDescription(facts, source, manufacturer)
        if (curated?.note != null && uad?.description?.isNotBlank() == true) notes += curated.note
        prefix?.note?.let { if (uad == null) notes += it }
        if (preloadInstaller) notes += "Installed by ${facts.installer}, a preload/partner installer: the manufacturer or carrier put it there."
        val regional = REGIONAL.containsMatchIn(pkg)
        if (regional) notes += "Region-specific variant of an app."

        dynamic.inUse[pkg]?.let { warnings += Warning("IN_USE", "In use on this phone", it) }
        val recovery = curated?.recovery ?: recoveryFor(facts)

        fun locked(reason: String) = Classification(
            source = source, risk = Risk.LOCKED, level = null, description = description, notes = notes,
            groups = groups, warnings = warnings, lockedReason = reason, recommendedAction = RecommendedAction.KEEP,
            inDatabase = uad != null || curated != null, dependencies = uad?.dependencies.orEmpty(),
            neededBy = uad?.neededBy.orEmpty(), communityTier = uad?.removal?.let(::tierName),
            manufacturer = manufacturer, recovery = recovery, regional = regional,
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
                val mapped = mapCommunityTier(uad) ?: return locked("Marked unsafe to remove by the community database (UAD-ng).")
                risk = mapped.first
                level = mapped.second
            }
            !facts.isSystem -> {
                risk = Risk.SAFE
                level = if (preloadInstaller) 1 else null
            }
            else -> {
                risk = Risk.UNKNOWN
                level = 4
                notes += "Not in Prune's database: its purpose is unknown. Change it only if you know what it does."
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
        if (risk == Risk.HIGH || risk == Risk.UNKNOWN) level = 4

        val recommended = when {
            !facts.isSystem -> RecommendedAction.UNINSTALL_APP.takeIf { source == Source.PRELOAD } ?: RecommendedAction.KEEP
            risk == Risk.HIGH || risk == Risk.UNKNOWN -> RecommendedAction.REVIEW
            level != null && level <= 3 -> RecommendedAction.DISABLE
            else -> RecommendedAction.REVIEW
        }
        if (!facts.isSystem) level = level?.takeIf { source == Source.PRELOAD }

        return Classification(
            source = source, risk = risk, level = level, description = description, notes = notes, groups = groups,
            warnings = warnings, lockedReason = null, recommendedAction = recommended,
            inDatabase = uad != null || curated != null || prefix != null,
            dependencies = uad?.dependencies.orEmpty(), neededBy = uad?.neededBy.orEmpty(),
            communityTier = uad?.removal?.let(::tierName), manufacturer = manufacturer,
            consequences = curated?.consequences, recovery = recovery, regional = regional,
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

    private fun sourceOf(facts: PackageFacts, uad: UadEntry?, curated: CuratedEntry?, oemPrefix: Boolean,
                         preloadInstaller: Boolean): Source {
        val pkg = facts.packageName
        if (!facts.isSystem) {
            // Updated or user-installed copies of known preloads still count as preloads.
            return if (preloadInstaller || uad?.list == "m" || uad?.list == "c" ||
                (curated != null && !curated.google && !oemPrefix)) Source.PRELOAD
            else Source.USER
        }
        if (curated?.google == true) return Source.GOOGLE
        when (uad?.list) {
            "o" -> return if (pkg.startsWith("com.google.")) Source.GOOGLE else Source.OEM
            "g" -> return Source.GOOGLE
            "a" -> return Source.ANDROID
            "c" -> return Source.CARRIER
            "m" -> return Source.PRELOAD
        }
        return when {
            oemPrefix && !pkg.startsWith("com.google.") -> Source.OEM
            pkg.startsWith("com.google.") || pkg == "com.android.vending" || pkg == "com.android.chrome" -> Source.GOOGLE
            pkg == "android" || pkg.startsWith("com.android.") -> Source.ANDROID
            CARRIER_PREFIXES.any { pkg.startsWith(it) } -> Source.CARRIER
            curated != null -> Source.PRELOAD
            else -> Source.OTHER_SYSTEM
        }
    }

    private fun defaultDescription(facts: PackageFacts, source: Source, manufacturer: String?): String = when {
        !facts.isSystem && source == Source.PRELOAD -> "App preinstalled by the manufacturer, carrier or a partner. You can remove it like any other app."
        !facts.isSystem -> "An app you installed. You can uninstall it like any other app."
        source == Source.OEM -> "${manufacturer ?: "Manufacturer"} system component. Prune has no description for it yet."
        source == Source.GOOGLE -> "Google system component. Prune has no description for it yet."
        source == Source.CARRIER -> "Carrier-installed component."
        else -> "System component. Prune has no description for it yet."
    }

    private fun recoveryFor(facts: PackageFacts): String = if (facts.isSystem) {
        "Restore it in Prune (Restore tab), or from a computer: adb shell pm install-existing ${facts.packageName} " +
            "and adb shell pm enable ${facts.packageName}. A factory reset also brings it back."
    } else {
        "If disabled: enable it in Prune or Settings → Apps. If uninstalled: install it again from its app store."
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

        /** Installers that place partner apps during setup (not app stores the user chose). */
        val PRELOAD_INSTALLERS = listOf(
            "com.facebook.system", "com.aura.oobe.samsung", "com.aura.oobe.samsung.gl", "com.ironsource.appcloud.oobe",
            "com.digitalturbine.ignite", "com.dti", "com.miui.msa.global", "com.android.preinstall",
        )

        private val REGIONAL = Regex("""(\.|_)(global|india|in|cn|china|eu|eea|indonesia|jp|kr|br|latam|mx|ru|tr|vn|th)$""",
            RegexOption.IGNORE_CASE)

        val CARRIER_PREFIXES = listOf(
            "com.vzw", "com.verizon", "com.motricity.verizon", "com.tmobile", "com.tmo", "com.t-mobile", "com.att.",
            "com.sprint", "com.telekom", "de.telekom", "com.vodafone", "uk.co.ee", "com.orange", "com.telefonica",
            "es.movistar", "com.movistar", "com.bouyguestelecom", "fr.free", "com.sfr", "com.telstra", "com.optus",
            "com.rogers", "ca.bell", "com.telus", "com.uscc", "com.cricketwireless", "com.metropcs", "com.boost",
            "com.dish", "com.playphone", "com.dti.", "com.ironsource.appcloud", "com.aura.oobe", "com.lguplus",
            "com.sktelecom", "com.kt.", "com.plus.", "pl.plus", "pl.play", "com.orange.pl", "com.wind", "it.tim",
            "com.three", "com.h3g", "com.drei", "com.a1", "com.mtel", "bg.vivacom", "com.yettel",
        )

        /** Display names for the manufacturers in [OemRegistry]. */
        fun manufacturerName(id: String): String = when (id) {
            "samsung" -> "Samsung"
            "pixel" -> "Google Pixel"
            "xiaomi" -> "Xiaomi"
            "huawei" -> "Huawei"
            "honor" -> "Honor"
            "oplus" -> "OPPO / OnePlus / realme"
            "motorola" -> "Motorola"
            "sony" -> "Sony"
            "nothing" -> "Nothing"
            "asus" -> "ASUS"
            "vivo" -> "vivo"
            "transsion" -> "Tecno / Infinix / itel"
            "hmd" -> "Nokia / HMD"
            "lenovo" -> "Lenovo"
            "zte" -> "ZTE / nubia"
            "lg" -> "LG"
            else -> id
        }
    }
}
