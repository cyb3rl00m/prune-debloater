package io.github.cyb3rl00m.prune.core.plan

import io.github.cyb3rl00m.prune.core.model.AppPackage
import io.github.cyb3rl00m.prune.core.model.ChangeType
import io.github.cyb3rl00m.prune.core.model.PackageStatus
import io.github.cyb3rl00m.prune.core.model.RemovalMethod
import io.github.cyb3rl00m.prune.core.model.Risk
import io.github.cyb3rl00m.prune.core.model.Source
import io.github.cyb3rl00m.prune.core.shell.Commands
import kotlinx.serialization.Serializable
import java.util.UUID

/** One change Prune intends to make. Also stored in the history, so it is serializable. */
@Serializable
data class PlannedChange(
    val id: String = UUID.randomUUID().toString(),
    val type: ChangeType,
    val packageName: String? = null,
    val label: String,
    val params: Map<String, String> = emptyMap(),
    val explanation: String,
    val warnings: List<String> = emptyList(),
    val risk: Risk? = null,
    /** If disabling is refused by the firmware, uninstall for the user instead (also reversible). */
    val fallbackUninstall: Boolean = false,
    val isSystemPackage: Boolean = true,
) {
    val needsConfirmation: Boolean get() = risk == Risk.HIGH || risk == Risk.UNKNOWN
}

@Serializable
data class Plan(
    val title: String,
    val origin: String,
    val changes: List<PlannedChange>,
    val createdAt: Long = System.currentTimeMillis(),
)

/** The four debloat levels. */
enum class DebloatLevel(val number: Int, val title: String, val summary: String, val details: List<String>) {
    MINIMAL(1, "Minimal", "A cleaner phone without real changes.", listOf(
        "Removes promoted apps, carrier apps and third-party preloads (Facebook, Netflix, ...)",
        "Turns off optional manufacturer extras: tips, promotion feeds, ad services, AR stickers",
        "Keeps every manufacturer ecosystem feature and all Google services",
    )),
    BALANCED(2, "Balanced", "Noticeably lighter, ecosystem kept.", listOf(
        "Everything in Minimal",
        "Removes diagnostics and data-collection services that run in the background",
        "Removes optional manufacturer apps (game launchers, email, data-transfer helpers...)",
        "Optional: removes Google apps you don't need",
        "Optional: restricts background activity of apps you haven't used for 30 days",
    )),
    AGGRESSIVE(3, "Aggressive", "As light as reasonably possible.", listOf(
        "Everything in Balanced, Google apps included by default",
        "Removes more manufacturer components and optional system parts",
        "Removes the parts of ecosystem features you say you don't use",
        "Expect to lose features: read every warning in the review",
    )),
    CUSTOM(4, "Extreme / Custom", "Every package, your choice.", listOf(
        "Lists every changeable package with its risk",
        "Starts from the Aggressive selection: you add or remove items one by one",
        "High-risk and unknown packages need an extra confirmation",
        "Protected packages stay locked, even here",
    ));

    companion object {
        fun of(n: Int) = entries.first { it.number == n }
    }
}

data class ProfileOptions(
    val level: DebloatLevel,
    val includeGoogle: Boolean,
    /** Ecosystem keys the user still uses. Their packages are never selected. */
    val usedEcosystems: Set<String>,
    val method: RemovalMethod = RemovalMethod.DISABLE,
    val skipUsedWithinDays: Int = 14,
    val restrictUnusedBackground: Boolean,
    val fallbackUninstall: Boolean = true,
) {
    companion object {
        fun defaults(level: DebloatLevel, allEcosystems: Set<String>) = ProfileOptions(
            level = level,
            includeGoogle = level.number >= 3,
            usedEcosystems = allEcosystems,
            restrictUnusedBackground = level.number >= 2,
        )
    }
}

data class Candidate(
    val app: AppPackage,
    val selected: Boolean,
    /** Why it is not pre-selected, shown to the user. */
    val note: String? = null,
    /** Why it is excluded entirely (kept), shown in the "kept" section. */
    val keptBecause: String? = null,
)

/**
 * Turns a debloat level and the user's answers into a list of candidates. Pure logic, unit tested.
 * Safety rules enforced here:
 *  - LOCKED packages are never candidates.
 *  - Packages of an ecosystem the user still uses are never candidates.
 *  - HIGH risk packages are only listed in Custom mode and are never pre-selected.
 *  - Recently used apps are never pre-selected.
 */
object PlanBuilder {

    fun candidates(apps: List<AppPackage>, options: ProfileOptions, now: Long = System.currentTimeMillis()): List<Candidate> {
        val maxLevel = if (options.level == DebloatLevel.CUSTOM) 4 else options.level.number
        val defaultLevel = if (options.level == DebloatLevel.CUSTOM) 3 else maxLevel
        val out = mutableListOf<Candidate>()
        for (app in apps) {
            val c = app.classification
            if (app.isLocked) continue
            if (app.status != PackageStatus.ENABLED) continue
            val level = c.level ?: continue
            if (level > maxLevel) continue
            val usedEco = c.groups.firstOrNull { it in options.usedEcosystems }
            if (usedEco != null) {
                out += Candidate(app, selected = false, keptBecause = "You use ${ecosystemLabel(c, usedEco)}")
                continue
            }
            if (c.source == Source.GOOGLE && !options.includeGoogle) {
                out += Candidate(app, selected = false, keptBecause = "Google apps not included")
                continue
            }
            val recentlyUsed = app.lastUsed?.let { now - it < options.skipUsedWithinDays * DAY } ?: false
            val inUse = c.warnings.any { it.groupKey == "IN_USE" }
            val note = when {
                recentlyUsed -> "You opened it in the last ${options.skipUsedWithinDays} days"
                inUse -> c.warnings.first { it.groupKey == "IN_USE" }.text
                c.risk == Risk.HIGH -> "High risk: select only if you are sure"
                c.risk == Risk.UNKNOWN -> "Unknown purpose: select only if you know what it does"
                level > defaultLevel -> "Not in the default selection"
                else -> null
            }
            out += Candidate(app, selected = note == null, note = note)
        }
        return out.sortedWith(compareBy({ !it.selected }, { it.keptBecause != null }, { it.app.risk.ordinal },
            { it.app.label.lowercase() }))
    }

    fun changeFor(app: AppPackage, method: RemovalMethod, fallbackUninstall: Boolean): PlannedChange {
        val system = app.facts.isSystem
        // User-installed copies are always disabled: uninstalling them for the only user deletes them for good.
        val type = if (method == RemovalMethod.UNINSTALL_USER && system) ChangeType.UNINSTALL_USER else ChangeType.DISABLE
        val c = app.classification
        val explanation = buildString {
            append(if (type == ChangeType.DISABLE) RemovalMethod.DISABLE.explanation else RemovalMethod.UNINSTALL_USER.explanation)
            c.notes.firstOrNull()?.let { append("\n\n").append(it) }
        }
        return PlannedChange(
            type = type, packageName = app.packageName, label = app.label, explanation = explanation,
            warnings = c.warnings.map { "${it.title}: ${it.text}" }, risk = c.risk,
            fallbackUninstall = fallbackUninstall && system && type == ChangeType.DISABLE,
            isSystemPackage = system,
        )
    }

    fun plan(title: String, origin: String, selected: List<AppPackage>, options: ProfileOptions,
             extra: List<PlannedChange> = emptyList()): Plan =
        Plan(title, origin, selected.map { changeFor(it, options.method, options.fallbackUninstall) } + extra)

    /** Share of the selected level's packages that are already disabled or removed (0..1). */
    fun progress(apps: List<AppPackage>, level: Int): Pair<Int, Int> {
        val relevant = apps.filter { !it.isLocked && (it.classification.level ?: 99) <= level && it.facts.isSystem }
        val done = relevant.count { it.status != PackageStatus.ENABLED }
        return done to relevant.size
    }

    private fun ecosystemLabel(c: io.github.cyb3rl00m.prune.core.model.Classification, key: String) =
        c.warnings.firstOrNull { it.groupKey == key }?.title ?: key

    private const val DAY = 24L * 60 * 60 * 1000
}

/** The exact commands behind each change. Used for execution, previews and ADB scripts. */
object ChangeCommands {

    fun forChange(change: PlannedChange, user: Int): List<List<String>> {
        val p = change.packageName
        return when (change.type) {
            ChangeType.DISABLE -> listOf(Commands.disable(p!!, user))
            ChangeType.ENABLE -> listOf(Commands.installExisting(p!!, user), Commands.enable(p, user))
            ChangeType.UNINSTALL_USER -> listOf(Commands.uninstallForUser(p!!, user))
            ChangeType.REINSTALL_USER -> listOf(Commands.installExisting(p!!, user))
            ChangeType.APPOP_SET -> listOf(Commands.appOpSet(p!!, change.params.getValue("op"), change.params.getValue("mode"), user))
            ChangeType.PERMISSION_REVOKE -> listOf(Commands.revoke(p!!, change.params.getValue("permission"), user))
            ChangeType.PERMISSION_GRANT -> listOf(Commands.grant(p!!, change.params.getValue("permission"), user))
            ChangeType.DEVICEIDLE_REMOVE -> listOf(Commands.deviceIdleRemove(p!!))
            ChangeType.DEVICEIDLE_ADD -> listOf(Commands.deviceIdleAdd(p!!))
            ChangeType.SETTING_PUT -> {
                val key = change.params.getValue("key")
                val value = change.params["value"]
                listOf(if (value == null) Commands.settingsDelete(key) else Commands.settingsPut(key, value))
            }
            ChangeType.TRIM_CACHES -> listOf(Commands.trimCaches())
            ChangeType.DEXOPT -> listOf(Commands.backgroundDexopt())
            ChangeType.UNINSTALL_APP -> emptyList()
        }
    }

    fun preview(change: PlannedChange, user: Int): List<String> =
        forChange(change, user).map { "adb shell " + Commands.toShell(it) }

    /** A script the user can run from a computer with `adb` when Shizuku and root are not available. */
    fun adbScript(plan: Plan, user: Int, header: String): String = buildString {
        appendLine("# $header")
        appendLine("# ${plan.title} - ${plan.changes.size} change(s)")
        appendLine("# Run on a computer with ADB connected to the phone (USB debugging or wireless debugging).")
        appendLine("# Linux/macOS: sh this-file.sh    Windows: copy the lines into a Command Prompt.")
        appendLine("# Every line is independent. Undo: use the restore script from Prune's Restore screen.")
        appendLine()
        for (c in plan.changes) {
            appendLine("# ${c.type.label}: ${c.label}${c.packageName?.let { " ($it)" } ?: ""}")
            preview(c, user).forEach { appendLine(it) }
        }
    }
}

/** Computes the change that undoes a recorded change, from the state recorded before it. */
object Inverse {
    fun of(change: PlannedChange, before: String?): PlannedChange? {
        val base = change.copy(id = UUID.randomUUID().toString(), warnings = emptyList(), fallbackUninstall = false, risk = null)
        return when (change.type) {
            ChangeType.DISABLE -> when (before) {
                "enabled" -> base.copy(type = ChangeType.ENABLE, explanation = "Turns the app back on.")
                else -> null
            }
            ChangeType.UNINSTALL_USER -> when (before) {
                "enabled" -> base.copy(type = ChangeType.ENABLE, explanation = "Reinstalls the app for this user and turns it on.")
                "disabled" -> base.copy(type = ChangeType.REINSTALL_USER, explanation = "Reinstalls the app for this user (it stays disabled, as before).")
                else -> null
            }
            ChangeType.ENABLE -> when (before) {
                "disabled" -> base.copy(type = ChangeType.DISABLE, explanation = "Disables the app again.")
                "removed" -> base.copy(type = ChangeType.UNINSTALL_USER, explanation = "Uninstalls the app for this user again.")
                else -> null
            }
            ChangeType.REINSTALL_USER -> if (before == "removed") base.copy(type = ChangeType.UNINSTALL_USER, explanation = "Uninstalls the app for this user again.") else null
            ChangeType.APPOP_SET -> before?.let { base.copy(params = change.params + ("mode" to it), explanation = "Restores the previous background setting ($it).") }
            ChangeType.PERMISSION_REVOKE -> if (before == "granted") base.copy(type = ChangeType.PERMISSION_GRANT, explanation = "Grants the permission again.") else null
            ChangeType.PERMISSION_GRANT -> if (before == "revoked") base.copy(type = ChangeType.PERMISSION_REVOKE, explanation = "Revokes the permission again.") else null
            ChangeType.DEVICEIDLE_REMOVE -> if (before == "exempt") base.copy(type = ChangeType.DEVICEIDLE_ADD, explanation = "Exempts the app from battery optimization again.") else null
            ChangeType.DEVICEIDLE_ADD -> if (before == "not-exempt") base.copy(type = ChangeType.DEVICEIDLE_REMOVE, explanation = "Removes the battery exemption again.") else null
            ChangeType.SETTING_PUT -> base.copy(
                params = if (before == null || before == "null") change.params - "value" else change.params + ("value" to before),
                explanation = "Restores the previous value (${before ?: "system default"}).")
            ChangeType.TRIM_CACHES, ChangeType.DEXOPT, ChangeType.UNINSTALL_APP -> null
        }
    }
}
