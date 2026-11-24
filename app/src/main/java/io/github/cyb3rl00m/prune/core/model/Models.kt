package io.github.cyb3rl00m.prune.core.model

import kotlinx.serialization.Serializable

/** How risky it is to disable or remove a package. LOCKED packages are never touched by Prune. */
enum class Risk(val label: String, val meaning: String) {
    SAFE("Safe", "Optional app. Nothing else depends on it."),
    LOW("Low risk", "Optional feature. You lose only that feature."),
    MODERATE("Moderate", "Part of a manufacturer or Google feature. Read the warning before removing."),
    UNKNOWN("Unknown", "Prune does not know what this package does. Only for experienced users."),
    HIGH("High risk", "Core feature or used by other apps. Only for experienced users."),
    LOCKED("Protected", "System-critical. Prune never changes it.");
}

/** Where a package comes from. */
enum class Source(val label: String) {
    /** Made by a phone manufacturer; [Classification.manufacturer] names which one. */
    OEM("Manufacturer"),
    GOOGLE("Google"),
    CARRIER("Carrier"),
    PRELOAD("Third-party preload"),
    ANDROID("Android core"),
    OTHER_SYSTEM("Other system"),
    USER("Installed by you");
}

/** Current state of a package for the current Android user. */
enum class PackageStatus(val label: String) {
    ENABLED("Enabled"),
    DISABLED("Disabled"),
    REMOVED("Removed for this user");
}

/** What Prune recommends doing with a package. */
enum class RecommendedAction(val label: String) {
    KEEP("Keep"),
    DISABLE("Disable"),
    REVIEW("Review before changing"),
    UNINSTALL_APP("Uninstall normally");
}

/** The ways Prune can take a system package out of use. Both are reversible. */
enum class RemovalMethod(val label: String, val explanation: String) {
    DISABLE(
        "Disable",
        "Turns the app off for this user. It stops running, disappears from the app drawer and keeps its data. " +
            "Reversible at any time with Prune, ADB or Settings."
    ),
    UNINSTALL_USER(
        "Uninstall for this user",
        "Removes the app and its data for this user. The original copy stays in the system partition, so it " +
            "can be reinstalled at any time (Prune restore or `pm install-existing`) and comes back after a factory reset."
    );
}

/** Facts about an installed package that are read from PackageManager. Pure data so it can be unit tested. */
data class PackageFacts(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val isPersistent: Boolean,
    val sharedUserId: String?,
    val installedForUser: Boolean,
    val enabled: Boolean,
    val versionName: String?,
    val uid: Int,
    val hasLauncherActivity: Boolean = false,
    /** Package that installed this app (Play Store, a manufacturer store, a preload installer), if Android reports one. */
    val installer: String? = null,
) {
    val status: PackageStatus
        get() = when {
            !installedForUser -> PackageStatus.REMOVED
            !enabled -> PackageStatus.DISABLED
            else -> PackageStatus.ENABLED
        }
}

/** A warning attached to a package, for example "Samsung Wallet stops working". */
data class Warning(val groupKey: String, val title: String, val text: String)

/** Prune's full verdict about one package. */
data class Classification(
    val source: Source,
    val risk: Risk,
    /** Lowest debloat level (1..4) that may select this package; null if no profile ever selects it. */
    val level: Int?,
    val description: String,
    val notes: List<String>,
    val groups: Set<String>,
    val warnings: List<Warning>,
    val lockedReason: String?,
    val recommendedAction: RecommendedAction,
    val inDatabase: Boolean,
    val dependencies: List<String> = emptyList(),
    val neededBy: List<String> = emptyList(),
    val communityTier: String? = null,
    /** Manufacturer name for [Source.OEM] packages (for example "Xiaomi"). */
    val manufacturer: String? = null,
    /** What can stop working, if Prune's database says more than the warnings. */
    val consequences: String? = null,
    /** How to bring the package back. */
    val recovery: String = "",
    /** Region-specific variant (for example a .global or .india build of an app). */
    val regional: Boolean = false,
) {
    /** Label for the package's origin: the manufacturer's name for manufacturer packages. */
    val sourceLabel: String get() = if (source == Source.OEM) manufacturer ?: source.label else source.label
    /** HIGH and UNKNOWN are never pre-selected and need an extra confirmation. */
    val needsCare: Boolean get() = risk == Risk.HIGH || risk == Risk.UNKNOWN
}

data class AppPackage(
    val facts: PackageFacts,
    val classification: Classification,
    /** Last time the user opened it (from usage stats), or null if unknown. */
    val lastUsed: Long? = null,
    /** Set when another installed app does the same job (two browsers, two galleries...). */
    val duplicateGroup: String? = null,
) {
    val packageName get() = facts.packageName
    val label get() = facts.label
    val status get() = facts.status
    val risk get() = classification.risk
    val isLocked get() = classification.risk == Risk.LOCKED
}

/** One device-level change Prune can apply and undo. */
@Serializable
enum class ChangeType(val label: String, val reversible: Boolean = true) {
    DISABLE("Disable"),
    ENABLE("Enable"),
    UNINSTALL_USER("Uninstall for user"),
    REINSTALL_USER("Reinstall"),
    UNINSTALL_APP("Uninstall app", reversible = false),
    APPOP_SET("Change background access"),
    PERMISSION_REVOKE("Revoke permission"),
    PERMISSION_GRANT("Grant permission"),
    DEVICEIDLE_REMOVE("Remove battery exemption"),
    DEVICEIDLE_ADD("Restore battery exemption"),
    SETTING_PUT("Change setting"),
    TRIM_CACHES("Clear app caches", reversible = false),
    DEXOPT("Optimize apps", reversible = false),
    IDLE_MAINT("Storage maintenance", reversible = false);
}
