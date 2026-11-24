package io.github.cyb3rl00m.prune.core.shell

/** Result of running one command. */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val output: String get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    val ok: Boolean get() = exitCode == 0 && !OutputParsers.looksLikeFailure(output)
}

/** Ways Prune can run privileged commands. */
enum class AccessKind(val label: String, val tier: String) {
    SHIZUKU("Shizuku", "ADB level"),
    ROOT("Root", "Root"),
    ADB_SCRIPT("ADB from a computer", "ADB level (manual)"),
    NONE("No privileged access", "Standard");
}

/** Something that can run commands as the shell or root user. */
interface PrivilegedShell {
    val kind: AccessKind
    /** uid the commands run as: 2000 = shell, 0 = root. */
    val uid: Int
    suspend fun run(argv: List<String>, timeoutMs: Long = 60_000): ShellResult
}

/**
 * Builds every command Prune runs. All inputs are validated here, and commands are passed as argv lists
 * (never through a shell string), so a package name can never inject extra commands.
 */
object Commands {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")
    private val PERMISSION = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
    val APP_OPS = setOf("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND", "GET_USAGE_STATS")
    val APP_OP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")
    /** Settings Prune may change, per namespace (global, secure, system). Values are always plain numbers. */
    val SETTINGS: Map<String, Set<String>> = mapOf(
        "global" to setOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale", "disable_window_blurs"),
        "secure" to setOf("high_text_contrast_enabled"),
        "system" to setOf("peak_refresh_rate", "min_refresh_rate"),
    )
    val GLOBAL_SETTINGS = SETTINGS.getValue("global")

    fun isValidPackage(pkg: String) = pkg.length in 1..255 && PKG.matches(pkg)

    private fun pkg(p: String): String = p.also { require(isValidPackage(it)) { "Invalid package name: $it" } }
    private fun user(u: Int): String = u.also { require(it in 0..9999) { "Invalid user: $it" } }.toString()

    fun disable(p: String, u: Int) = listOf("pm", "disable-user", "--user", user(u), pkg(p))
    fun enable(p: String, u: Int) = listOf("pm", "enable", "--user", user(u), pkg(p))
    fun uninstallForUser(p: String, u: Int) = listOf("pm", "uninstall", "-k", "--user", user(u), pkg(p))
    fun installExisting(p: String, u: Int) = listOf("pm", "install-existing", "--user", user(u), pkg(p))

    fun appOpSet(p: String, op: String, mode: String, u: Int): List<String> {
        require(op in APP_OPS && mode in APP_OP_MODES)
        return listOf("cmd", "appops", "set", "--user", user(u), pkg(p), op, mode)
    }

    fun appOpGet(p: String, op: String, u: Int): List<String> {
        require(op in APP_OPS)
        return listOf("cmd", "appops", "get", "--user", user(u), pkg(p), op)
    }

    fun appOpQuery(op: String, mode: String, u: Int): List<String> {
        require(op in APP_OPS && mode in APP_OP_MODES)
        return listOf("cmd", "appops", "query-op", "--user", user(u), op, mode)
    }

    fun revoke(p: String, perm: String, u: Int): List<String> {
        require(PERMISSION.matches(perm))
        return listOf("pm", "revoke", "--user", user(u), pkg(p), perm)
    }

    fun grant(p: String, perm: String, u: Int): List<String> {
        require(PERMISSION.matches(perm))
        return listOf("pm", "grant", "--user", user(u), pkg(p), perm)
    }

    fun deviceIdleRemove(p: String) = listOf("cmd", "deviceidle", "whitelist", "-" + pkg(p))
    fun deviceIdleAdd(p: String) = listOf("cmd", "deviceidle", "whitelist", "+" + pkg(p))
    fun deviceIdleList() = listOf("cmd", "deviceidle", "whitelist")

    fun settingsPut(key: String, value: String, namespace: String = "global"): List<String> {
        require(key in SETTINGS[namespace].orEmpty() && value.matches(Regex("^[0-9.]{1,6}$")))
        return listOf("settings", "put", namespace, key, value)
    }

    fun settingsDelete(key: String, namespace: String = "global"): List<String> {
        require(key in SETTINGS[namespace].orEmpty())
        return listOf("settings", "delete", namespace, key)
    }

    /** Runs Android's idle storage maintenance now: fstrim on the data partition plus database upkeep. */
    fun idleMaintenance() = listOf("sm", "idle-maint", "run")

    /** Asks the system to free cache space from all apps (same mechanism Android uses when storage runs low). */
    fun trimCaches() = listOf("pm", "trim-caches", "9999G")

    /** Runs the idle-maintenance app compilation job now (ART dexopt with app profiles). */
    fun backgroundDexopt() = listOf("cmd", "package", "bg-dexopt-job")

    fun batteryStats() = listOf("dumpsys", "batterystats")

    fun id() = listOf("id")

    /** Quotes argv for display, ADB scripts and `su -c`. */
    fun toShell(argv: List<String>): String = argv.joinToString(" ") { quote(it) }

    fun quote(arg: String): String =
        if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "._-+/=:@%," }) arg
        else "'" + arg.replace("'", "'\\''") + "'"
}

/** Parses command output. Pure functions so they can be unit tested against real outputs. */
object OutputParsers {

    private val failureMarkers = listOf("Exception", "Failure", "Error:", "Unknown package", "not found",
        "Shell cannot", "Permission Denial", "Bad component", "IllegalArgument", "SecurityException")

    fun looksLikeFailure(output: String): Boolean = failureMarkers.any { output.contains(it, ignoreCase = false) }

    /** `pm disable-user` prints "Package X new state: disabled-user". */
    fun disableSucceeded(r: ShellResult) = r.exitCode == 0 && r.stdout.contains("new state: disabled")

    /** `pm enable` prints "Package X new state: enabled". */
    fun enableSucceeded(r: ShellResult) = r.exitCode == 0 &&
        (r.stdout.contains("new state: enabled") || r.stdout.contains("new state: default"))

    fun uninstallSucceeded(r: ShellResult) = r.exitCode == 0 && r.stdout.trim().startsWith("Success")

    /** `pm install-existing` prints "Package X installed for user: 0". */
    fun installExistingSucceeded(r: ShellResult) = r.exitCode == 0 && r.stdout.contains("installed for user")

    /** Reads the mode from `cmd appops get <pkg> <op>`: "RUN_ANY_IN_BACKGROUND: ignore; time=..." */
    fun appOpMode(output: String, op: String): String {
        val m = Regex("""\b$op: ([a-z]+)""").find(output) ?: return "default"
        return m.groupValues[1]
    }

    /** `cmd appops query-op` prints one package per line (or "No operations."). */
    fun appOpQueryPackages(output: String): Set<String> =
        output.lines().map { it.trim() }.filter { Commands.isValidPackage(it) }.toSet()

    data class IdleWhitelist(val user: Set<String>, val system: Set<String>)

    /** `cmd deviceidle whitelist` prints "system-excidle,pkg,uid", "system,pkg,uid" and "user,pkg,uid" lines. */
    fun deviceIdleWhitelist(output: String): IdleWhitelist {
        val user = mutableSetOf<String>()
        val system = mutableSetOf<String>()
        for (line in output.lines()) {
            val parts = line.trim().split(',')
            if (parts.size < 2 || !Commands.isValidPackage(parts[1])) continue
            when (parts[0]) {
                "user" -> user += parts[1]
                "system", "system-excidle" -> system += parts[1]
            }
        }
        return IdleWhitelist(user, system - user)
    }

    /**
     * Estimated battery use per uid from `dumpsys batterystats`, section "Estimated power use (mAh)".
     * Lines look like "UID u0a234: 12.3 ( cpu=... )" or "UID 1000: 45.6 fg: ...".
     */
    fun batteryUsePerUid(output: String): Map<Int, Double> {
        val lines = output.lines()
        val headerIndex = lines.indexOfFirst { it.contains("Estimated power use") }
        if (headerIndex < 0) return emptyMap()
        fun indent(s: String) = s.length - s.trimStart().length
        val headerIndent = indent(lines[headerIndex])
        // The section ends at the first line that is not indented deeper than its header.
        val section = lines.drop(headerIndex + 1).takeWhile { it.isBlank() || indent(it) > headerIndent }
        val re = Regex("""^\s*UID (u(\d+)a(\d+)|u(\d+)s(\d+)|(\d+)): ([0-9]+(?:\.[0-9]+)?)""")
        val out = HashMap<Int, Double>()
        for (line in section) {
            val m = re.find(line) ?: continue
            val g = m.groupValues
            val uid = when {
                g[2].isNotEmpty() -> g[2].toInt() * 100_000 + 10_000 + g[3].toInt()
                g[4].isNotEmpty() -> g[4].toInt() * 100_000 + 99_000 + g[5].toInt()
                else -> g[6].toInt()
            }
            out[uid] = (out[uid] ?: 0.0) + g[7].toDouble()
        }
        return out
    }

    /** uid line from `id`: "uid=2000(shell) gid=..." */
    fun uidOf(idOutput: String): Int? = Regex("""uid=(\d+)""").find(idOutput)?.groupValues?.get(1)?.toIntOrNull()
}
