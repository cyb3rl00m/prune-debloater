package io.github.cyb3rl00m.prune.core.plan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import io.github.cyb3rl00m.prune.core.history.Batch
import io.github.cyb3rl00m.prune.core.history.HistoryEntry
import io.github.cyb3rl00m.prune.core.history.HistoryStore
import io.github.cyb3rl00m.prune.core.history.OriginalState
import io.github.cyb3rl00m.prune.core.model.ChangeType
import io.github.cyb3rl00m.prune.core.model.PackageStatus
import io.github.cyb3rl00m.prune.core.scan.PackageScanner
import io.github.cyb3rl00m.prune.core.shell.Commands
import io.github.cyb3rl00m.prune.core.shell.OutputParsers
import io.github.cyb3rl00m.prune.core.shell.PrivilegedShell
import io.github.cyb3rl00m.prune.core.shell.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class ExecProgress(val done: Int, val total: Int, val current: String, val log: List<String>)

/**
 * Applies plans. For every change it records the state before, runs the commands, then reads the real state
 * back from Android to decide whether it worked: a command's own output is never trusted on its own.
 */
class Executor(
    private val context: Context,
    private val scanner: PackageScanner,
    private val history: HistoryStore,
) {
    private val user = android.os.Process.myUid() / 100_000
    private val pm = context.packageManager

    suspend fun apply(
        plan: Plan,
        shell: PrivilegedShell,
        undoOf: Map<String, String> = emptyMap(),
        onProgress: (ExecProgress) -> Unit = {},
    ): Batch = withContext(Dispatchers.IO) {
        val log = mutableListOf<String>()
        val entries = mutableListOf<HistoryEntry>()
        val originals = mutableMapOf<String, OriginalState>()

        // Check the connection once before changing anything: if Shizuku/root cannot run a command,
        // every change would fail anyway, after a timeout each.
        onProgress(ExecProgress(0, plan.changes.size, "Connecting to ${shell.kind.label}…", log.toList()))
        val probe = runCatching { shell.run(Commands.id(), 30_000) }.getOrElse { ShellResult(-4, "", it.message ?: it.toString()) }
        log += "$ id"
        probe.output.lineSequence().take(3).forEach { if (it.isNotBlank()) log += "  $it" }
        if (probe.exitCode != 0 || OutputParsers.uidOf(probe.stdout) == null) {
            val why = probe.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "exit code ${probe.exitCode}"
            val message = "Not done: Prune could not run commands through ${shell.kind.label} ($why). Nothing was changed."
            log += "  ✗ $message"
            plan.changes.forEach { c ->
                entries += HistoryEntry(UUID.randomUUID().toString(), c, before = null, after = null, success = false, message = message)
            }
            onProgress(ExecProgress(plan.changes.size, plan.changes.size, "", log.toList()))
            val batch = Batch(UUID.randomUUID().toString(), plan.title, plan.origin, System.currentTimeMillis(), shell.kind.label, entries)
            history.addBatch(batch, emptyMap())
            return@withContext batch
        }
        log += "  connected as uid ${OutputParsers.uidOf(probe.stdout)}" + ((shell as? io.github.cyb3rl00m.prune.core.shell.ShizukuShell)?.backend?.let { " (${it.label})" } ?: "")

        plan.changes.forEachIndexed { i, change ->
            onProgress(ExecProgress(i, plan.changes.size, change.label, log.toList()))
            val before = readState(change, shell)
            if (change.packageName != null && before in PACKAGE_STATES) {
                originals[change.packageName] = OriginalState(change.packageName, change.label, before!!, System.currentTimeMillis())
            }
            val commands = mutableListOf<String>()
            suspend fun run(argv: List<String>, timeout: Long = 60_000): ShellResult {
                commands += Commands.toShell(argv)
                log += "$ " + Commands.toShell(argv)
                val r = runCatching { shell.run(argv, timeout) }
                    .getOrElse { ShellResult(-4, "", it.message ?: it.toString()) }
                r.output.lineSequence().take(6).forEach { if (it.isNotBlank()) log += "  $it" }
                return r
            }
            var message = ""
            var refusedBy: String? = null
            val result: ShellResult? = when (change.type) {
                ChangeType.DISABLE -> {
                    var r = run(Commands.disable(change.packageName!!, user))
                    // Fall back only when the firmware ran the command and refused it, never on connection errors.
                    if (!OutputParsers.disableSucceeded(r) && change.fallbackUninstall && r.exitCode >= 0 &&
                        packageState(change.packageName) == "enabled") {
                        refusedBy = firstLine(r)
                        r = run(Commands.uninstallForUser(change.packageName, user))
                    }
                    r
                }
                ChangeType.SETTING_PUT -> putSetting(change) { argv, timeout -> run(argv, timeout) }
                ChangeType.TRIM_CACHES -> {
                    val free = freeBytes()
                    val r = run(Commands.trimCaches(), 300_000)
                    val gained = (freeBytes() - free).coerceAtLeast(0)
                    message = "Free space grew by ${formatBytes(gained)} (measured)."
                    r
                }
                ChangeType.DEXOPT -> run(Commands.backgroundDexopt(), 30 * 60_000L)
                ChangeType.IDLE_MAINT -> run(Commands.idleMaintenance(), 30 * 60_000L)
                ChangeType.UNINSTALL_APP -> null
                else -> {
                    var last: ShellResult? = null
                    for (argv in ChangeCommands.forChange(change, user)) last = run(argv)
                    last
                }
            }
            val after = readState(change, shell)
            val success = when (change.type) {
                ChangeType.DISABLE -> after == "disabled" || (change.fallbackUninstall && after == "removed")
                ChangeType.ENABLE -> after == "enabled"
                ChangeType.UNINSTALL_USER -> after == "removed"
                ChangeType.REINSTALL_USER -> after == "enabled" || after == "disabled"
                ChangeType.APPOP_SET -> after == change.params["mode"] || (change.params["mode"] == "default" && after in setOf("default", "allow"))
                ChangeType.PERMISSION_REVOKE -> after == "revoked"
                ChangeType.PERMISSION_GRANT -> after == "granted"
                ChangeType.DEVICEIDLE_REMOVE -> after == "not-exempt"
                ChangeType.DEVICEIDLE_ADD -> after == "exempt"
                ChangeType.SETTING_PUT -> after == (change.params["value"] ?: "null")
                ChangeType.TRIM_CACHES, ChangeType.DEXOPT, ChangeType.IDLE_MAINT -> result?.exitCode == 0
                ChangeType.UNINSTALL_APP -> false
            }
            if (refusedBy != null) {
                message = if (after == "removed") "Disabling was refused ($refusedBy). Uninstalled for this user instead."
                else "Disabling was refused ($refusedBy). Uninstalling for this user also failed: ${result?.let(::firstLine).orEmpty()}"
            }
            if (message.isEmpty()) {
                message = if (success) "Done." else "Not done: " + (result?.let(::firstLine)?.ifBlank { null }
                    ?: "state is still \"$after\"")
            }
            log += if (success) "  ✓ ${change.label}" else "  ✗ ${change.label}: $message"
            entries += HistoryEntry(
                id = UUID.randomUUID().toString(), change = change, before = before, after = after,
                success = success, message = message, commands = commands, undoOf = undoOf[change.id],
            )
        }
        onProgress(ExecProgress(plan.changes.size, plan.changes.size, "", log.toList()))
        val batch = Batch(UUID.randomUUID().toString(), plan.title, plan.origin, System.currentTimeMillis(),
            shell.kind.label, entries)
        history.addBatch(batch, originals)
        val undone = entries.filter { it.success == true }.mapNotNull { it.undoOf }.toSet()
        if (undone.isNotEmpty()) history.markUndone(undone)
        batch
    }

    /** Records a plan that was exported as an ADB script. Results stay unverified until [verifyScriptBatch]. */
    suspend fun recordScript(plan: Plan): Batch {
        val entries = plan.changes.map { c ->
            HistoryEntry(UUID.randomUUID().toString(), c, before = readState(c, null), success = null,
                message = "Waiting: run the script, then tap Check results.",
                commands = ChangeCommands.forChange(c, user).map(Commands::toShell))
        }
        val originals = entries.filter { it.change.packageName != null && it.before in PACKAGE_STATES }
            .associate { it.change.packageName!! to OriginalState(it.change.packageName, it.change.label, it.before!!, System.currentTimeMillis()) }
        val batch = Batch(UUID.randomUUID().toString(), plan.title, plan.origin, System.currentTimeMillis(),
            "ADB script", entries, script = true)
        history.addBatch(batch, originals)
        return batch
    }

    suspend fun verifyScriptBatch(batch: Batch): Batch {
        val checked = batch.copy(entries = batch.entries.map { e ->
            val after = readState(e.change, null)
            val ok = when (e.change.type) {
                ChangeType.DISABLE -> after == "disabled"
                ChangeType.ENABLE -> after == "enabled"
                ChangeType.UNINSTALL_USER -> after == "removed"
                ChangeType.REINSTALL_USER -> after != "removed"
                ChangeType.SETTING_PUT -> after == (e.change.params["value"] ?: "null")
                ChangeType.PERMISSION_REVOKE -> after == "revoked"
                ChangeType.PERMISSION_GRANT -> after == "granted"
                ChangeType.DEVICEIDLE_REMOVE -> after == "not-exempt"
                ChangeType.DEVICEIDLE_ADD -> after == "exempt"
                else -> null
            }
            e.copy(after = after, success = ok, message = when (ok) {
                true -> "Verified."
                false -> "Not applied (state is \"$after\")."
                null -> "Cannot be verified without Shizuku or root."
            })
        })
        history.replaceBatch(checked)
        return checked
    }

    /** Reads the current state relevant to a change. [shell] is needed for app-ops only. */
    suspend fun readState(change: PlannedChange, shell: PrivilegedShell?): String? = withContext(Dispatchers.IO) {
        val p = change.packageName
        when (change.type) {
            ChangeType.DISABLE, ChangeType.ENABLE, ChangeType.UNINSTALL_USER, ChangeType.REINSTALL_USER,
            ChangeType.UNINSTALL_APP -> p?.let { packageState(it) }
            ChangeType.APPOP_SET -> shell?.let {
                val r = runCatching { it.run(Commands.appOpGet(p!!, change.params.getValue("op"), user)) }.getOrNull()
                r?.let { res -> OutputParsers.appOpMode(res.stdout, change.params.getValue("op")) }
            }
            ChangeType.PERMISSION_REVOKE, ChangeType.PERMISSION_GRANT ->
                if (pm.checkPermission(change.params.getValue("permission"), p!!) == PackageManager.PERMISSION_GRANTED) "granted" else "revoked"
            ChangeType.DEVICEIDLE_REMOVE, ChangeType.DEVICEIDLE_ADD ->
                if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(p!!)) "exempt" else "not-exempt"
            ChangeType.SETTING_PUT -> readSetting(change.params["ns"] ?: "global", change.params.getValue("key")) ?: "null"
            ChangeType.TRIM_CACHES, ChangeType.IDLE_MAINT -> freeBytes().toString()
            ChangeType.DEXOPT -> null
        }
    }

    fun packageState(pkg: String): String = when (scanner.read(pkg)?.status) {
        PackageStatus.ENABLED -> "enabled"
        PackageStatus.DISABLED -> "disabled"
        PackageStatus.REMOVED -> "removed"
        null -> "missing"
    }

    private fun readSetting(namespace: String, key: String): String? = runCatching {
        val cr = context.contentResolver
        when (namespace) {
            "secure" -> Settings.Secure.getString(cr, key)
            "system" -> Settings.System.getString(cr, key)
            else -> Settings.Global.getString(cr, key)
        }
    }.getOrNull()

    private suspend fun putSetting(change: PlannedChange, run: suspend (List<String>, Long) -> ShellResult): ShellResult {
        val key = change.params.getValue("key")
        val value = change.params["value"]
        val namespace = change.params["ns"] ?: "global"
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        // WRITE_SECURE_SETTINGS covers global and secure settings; system settings always go through the shell.
        if (hasPermission && namespace != "system") {
            val ok = runCatching {
                if (namespace == "secure") Settings.Secure.putString(context.contentResolver, key, value)
                else Settings.Global.putString(context.contentResolver, key, value)
            }.getOrDefault(false)
            if (ok) return ShellResult(0, "", "")
        }
        return run(if (value == null) Commands.settingsDelete(key, namespace) else Commands.settingsPut(key, value, namespace), 60_000)
    }

    private fun firstLine(r: ShellResult) = r.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: ""

    private fun freeBytes(): Long = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(0)

    companion object {
        val PACKAGE_STATES = setOf("enabled", "disabled", "removed")

        fun formatBytes(b: Long): String {
            if (b < 1000) return "$b B"
            val units = listOf("kB", "MB", "GB", "TB")
            var v = b.toDouble()
            var i = -1
            while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
            return String.format(java.util.Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
        }
    }
}
