package io.github.cyb3rl00m.prune.core.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import io.github.cyb3rl00m.prune.core.scan.UsageReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku

data class AccessState(
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuPermission: Boolean = false,
    val shizukuTooOld: Boolean = false,
    /** 0 if Shizuku was started with root, 2000 if started through ADB / wireless debugging. */
    val shizukuUid: Int? = null,
    val rootGranted: Boolean = false,
    val usageAccess: Boolean = false,
    val writeSecureSettings: Boolean = false,
    /** User preference: use root even when Shizuku is available. */
    val preferRoot: Boolean = false,
) {
    val active: AccessKind
        get() = when {
            preferRoot && rootGranted -> AccessKind.ROOT
            shizukuRunning && shizukuPermission -> AccessKind.SHIZUKU
            rootGranted -> AccessKind.ROOT
            else -> AccessKind.NONE
        }
    val privileged: Boolean get() = active == AccessKind.SHIZUKU || active == AccessKind.ROOT
}

/** Tracks which permission tier is available right now and hands out the matching shell. */
class AccessManager(private val context: Context) {

    private val _state = MutableStateFlow(AccessState())
    val state: StateFlow<AccessState> = _state.asStateFlow()

    private val shizukuShell = ShizukuShell(context.packageName)

    /** Instrumented tests inject a shell backed by UiAutomation (uid 2000, like Shizuku). Never set in the app. */
    @Volatile
    @androidx.annotation.VisibleForTesting
    var testShell: PrivilegedShell? = null
    private val rootShell = RootShell()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        shizukuShell.release()
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        val installed = isInstalled(SHIZUKU_PACKAGE)
        val test = testShell != null
        val running = test || runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val tooOld = !test && running && runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        val permission = test || (running && !tooOld &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false))
        val uid = if (test) 2000 else if (running) runCatching { Shizuku.getUid() }.getOrNull() else null
        _state.update {
            it.copy(
                shizukuInstalled = installed || running,
                shizukuRunning = running,
                shizukuPermission = permission,
                shizukuTooOld = tooOld,
                shizukuUid = uid,
                usageAccess = UsageReader(context).hasAccess(),
                writeSecureSettings = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    fun requestShizukuPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
    }

    suspend fun requestRoot(): Boolean {
        val ok = RootShell.request()
        _state.update { it.copy(rootGranted = ok) }
        return ok
    }

    fun setPreferRoot(prefer: Boolean) = _state.update { it.copy(preferRoot = prefer) }

    /** The shell for the active tier, or null when only standard Android permissions are available. */
    fun shell(): PrivilegedShell? = when (_state.value.active) {
        AccessKind.SHIZUKU -> testShell ?: shizukuShell
        AccessKind.ROOT -> rootShell
        else -> null
    }

    /**
     * With a privileged shell available, Prune grants itself the two extra permissions it uses:
     * usage access (app usage and storage stats) and WRITE_SECURE_SETTINGS (animation scales).
     * Both are visible and revocable in Android Settings.
     */
    suspend fun grantSelfPermissions(): List<String> {
        val shell = shell() ?: return emptyList()
        val done = mutableListOf<String>()
        val user = android.os.Process.myUid() / 100_000
        if (!_state.value.usageAccess &&
            shell.run(Commands.appOpSet(context.packageName, "GET_USAGE_STATS", "allow", user)).exitCode == 0) {
            done += "Usage access"
        }
        if (!_state.value.writeSecureSettings &&
            shell.run(Commands.grant(context.packageName, Manifest.permission.WRITE_SECURE_SETTINGS, user)).exitCode == 0) {
            done += "Write secure settings"
        }
        refresh()
        return done
    }

    private fun isInstalled(pkg: String) = runCatching {
        context.packageManager.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES); true
    }.getOrDefault(false)

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4711
    }
}
