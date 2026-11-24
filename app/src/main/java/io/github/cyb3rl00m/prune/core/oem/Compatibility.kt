package io.github.cyb3rl00m.prune.core.oem

import io.github.cyb3rl00m.prune.core.history.HistoryFile
import io.github.cyb3rl00m.prune.core.model.ChangeType

/** What an operation needs. */
enum class Requirement(val label: String) {
    STANDARD("Standard (no extra access)"),
    USAGE_ACCESS("Usage access"),
    SHELL("Shizuku or root"),
    SHELL_OR_SECURE_SETTINGS("Shizuku, root, or the WRITE_SECURE_SETTINGS grant"),
}

/** Everything Prune can do, for the compatibility report. */
enum class Operation(val label: String, val requirement: Requirement, val minSdk: Int = 28, val changeTypes: Set<ChangeType> = emptySet()) {
    SCAN("Scan and classify packages", Requirement.STANDARD),
    UNINSTALL_OWN("Uninstall apps you installed (system dialog)", Requirement.STANDARD),
    SCRIPT("Export changes as an ADB script", Requirement.STANDARD),
    USAGE("App usage, unused apps and storage per app", Requirement.USAGE_ACCESS),
    DISABLE("Disable system apps", Requirement.SHELL, changeTypes = setOf(ChangeType.DISABLE)),
    UNINSTALL_USER("Uninstall system apps for this user", Requirement.SHELL, changeTypes = setOf(ChangeType.UNINSTALL_USER)),
    RESTORE("Restore and re-enable apps", Requirement.SHELL, changeTypes = setOf(ChangeType.ENABLE, ChangeType.REINSTALL_USER)),
    BACKGROUND("Restrict background activity", Requirement.SHELL, changeTypes = setOf(ChangeType.APPOP_SET)),
    PERMISSIONS("Revoke and grant permissions", Requirement.SHELL, changeTypes = setOf(ChangeType.PERMISSION_REVOKE, ChangeType.PERMISSION_GRANT)),
    NOTIFICATIONS("Turn off app notifications", Requirement.SHELL, minSdk = 33),
    BATTERY_EXEMPTIONS("Remove battery-optimization exemptions", Requirement.SHELL, changeTypes = setOf(ChangeType.DEVICEIDLE_REMOVE, ChangeType.DEVICEIDLE_ADD)),
    ANIMATIONS("Change animation speed", Requirement.SHELL_OR_SECURE_SETTINGS, changeTypes = setOf(ChangeType.SETTING_PUT)),
    CACHES("Clear all app caches", Requirement.SHELL, changeTypes = setOf(ChangeType.TRIM_CACHES)),
    DEXOPT("Compile apps now", Requirement.SHELL, changeTypes = setOf(ChangeType.DEXOPT)),
    IDLE_MAINT("Storage maintenance (fstrim)", Requirement.SHELL, changeTypes = setOf(ChangeType.IDLE_MAINT)),
    BATTERY_STATS("Battery estimates per app", Requirement.SHELL),
}

enum class OpStatus(val label: String) {
    AVAILABLE("Available"),
    NEEDS_ACCESS("Needs Shizuku or root"),
    NEEDS_USAGE("Needs usage access"),
    UNSUPPORTED("Not supported on this Android version"),
}

data class OperationReport(
    val operation: Operation,
    val status: OpStatus,
    /** Firmware-specific caveat, if any. */
    val caveat: String?,
    /** Results of this operation on this device, from Prune's history. */
    val succeeded: Int,
    val failed: Int,
    val lastError: String?,
)

data class AccessSnapshot(
    val tier: String,
    val privileged: Boolean,
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean,
    val shizukuAuthorized: Boolean,
    val root: Boolean,
    val usageAccess: Boolean,
    val writeSecureSettings: Boolean,
)

data class CompatibilityReport(
    val device: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val firmware: Firmware?,
    val integration: OemIntegration,
    val services: ServicesInfo,
    val access: AccessSnapshot,
    val operations: List<OperationReport>,
    val limitations: List<Limitation>,
    val setupSteps: List<String>,
) {
    val supported get() = operations.filter { it.status == OpStatus.AVAILABLE }
    val unsupported get() = operations.filter { it.status != OpStatus.AVAILABLE }

    /** Plain text, for bug reports. */
    fun asText(appVersion: String): String = buildString {
        appendLine("Prune $appVersion compatibility report")
        appendLine("Device: $device")
        appendLine("Manufacturer: $manufacturer")
        appendLine("Android: $androidVersion (API $sdkInt)")
        appendLine("Firmware: ${firmware ?: "unknown"}")
        appendLine("Integration: ${integration.title} — ${integration.compat.label}")
        appendLine("Google services: ${if (services.gms) "yes" else "no"} · Play Store: ${if (services.playStore) "yes" else "no"} · Huawei HMS: ${if (services.hms) "yes" else "no"}")
        appendLine("Access: ${access.tier} · Shizuku ${if (access.shizukuRunning) "running" else if (access.shizukuInstalled) "installed" else "not installed"}" +
            " · root ${if (access.root) "yes" else "no"} · usage access ${if (access.usageAccess) "yes" else "no"}")
        appendLine()
        appendLine("Operations:")
        for (o in operations) {
            append("- ${o.operation.label}: ${o.status.label}")
            if (o.succeeded + o.failed > 0) append(" (on this device: ${o.succeeded} ok, ${o.failed} failed)")
            appendLine()
            o.caveat?.let { appendLine("    note: $it") }
            o.lastError?.let { appendLine("    last error: $it") }
        }
        if (limitations.isNotEmpty()) {
            appendLine()
            appendLine("Known limitations:")
            limitations.forEach { appendLine("- ${it.title}: ${it.text}") }
        }
    }

    companion object {
        fun build(
            device: String,
            identity: DeviceIdentity,
            integration: OemIntegration,
            firmware: Firmware?,
            services: ServicesInfo,
            access: AccessSnapshot,
            history: HistoryFile,
        ): CompatibilityReport {
            val entries = history.batches.flatMap { it.entries }
            val ops = Operation.entries.map { op ->
                val status = when {
                    identity.sdkInt < op.minSdk -> OpStatus.UNSUPPORTED
                    op.requirement == Requirement.STANDARD -> OpStatus.AVAILABLE
                    op.requirement == Requirement.USAGE_ACCESS -> if (access.usageAccess) OpStatus.AVAILABLE else OpStatus.NEEDS_USAGE
                    op.requirement == Requirement.SHELL_OR_SECURE_SETTINGS ->
                        if (access.privileged || access.writeSecureSettings) OpStatus.AVAILABLE else OpStatus.NEEDS_ACCESS
                    else -> if (access.privileged) OpStatus.AVAILABLE else OpStatus.NEEDS_ACCESS
                }
                val mine = entries.filter { it.change.type in op.changeTypes && it.success != null }
                OperationReport(
                    operation = op, status = status, caveat = runCatching { caveat(integration, op) }.getOrNull(),
                    succeeded = mine.count { it.success == true }, failed = mine.count { it.success == false },
                    lastError = mine.lastOrNull { it.success == false }?.message?.take(200),
                )
            }
            return CompatibilityReport(
                device = device, manufacturer = identity.manufacturer, androidVersion = identity.release,
                sdkInt = identity.sdkInt, firmware = firmware, integration = integration, services = services,
                access = access, operations = ops,
                limitations = OemRegistry.limitationsSafe(integration, identity, services),
                setupSteps = runCatching { integration.developerOptionsSteps + integration.extraSetupSteps(identity) }.getOrDefault(emptyList()),
            )
        }

        /** Firmware-specific notes per operation. Only for behaviour that is documented for that firmware. */
        fun caveat(i: OemIntegration, op: Operation): String? = when {
            i == XiaomiIntegration && op in setOf(Operation.PERMISSIONS, Operation.BACKGROUND, Operation.NOTIFICATIONS, Operation.ANIMATIONS) ->
                "Needs \"USB debugging (Security settings)\" in Developer options on Xiaomi firmware."
            i == OplusIntegration && op in setOf(Operation.PERMISSIONS, Operation.BACKGROUND, Operation.NOTIFICATIONS) ->
                "Needs \"Disable permission monitoring\" in Developer options on ColorOS-based firmware."
            i.disableOftenRefused && op == Operation.DISABLE ->
                "Often refused by this firmware; Prune then uninstalls for this user instead (reversible)."
            i == SamsungIntegration && op == Operation.DISABLE ->
                "Refused for some Samsung packages; Prune then uninstalls for this user instead (reversible)."
            else -> null
        }
    }
}
