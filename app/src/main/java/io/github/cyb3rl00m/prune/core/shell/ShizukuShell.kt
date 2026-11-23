package io.github.cyb3rl00m.prune.core.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.cyb3rl00m.prune.BuildConfig
import io.github.cyb3rl00m.prune.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs commands through Shizuku, as shell (or as root if Shizuku was started with root).
 *
 * Two backends:
 *  1. A Shizuku user service (preferred): a small process Shizuku starts from Prune's APK.
 *  2. Shizuku's remote process API: Shizuku's own server runs the command. Used when the user service
 *     cannot be started, which has been reported on Xiaomi HyperOS.
 * If the user service does not connect once, Prune uses the second backend until Shizuku restarts.
 */
class ShizukuShell(
    private val packageName: String,
    /** Tests set this to false to exercise the Shizuku process fallback on its own. */
    private val useUserService: Boolean = true,
) : PrivilegedShell {

    enum class Backend(val label: String) { USER_SERVICE("user service"), REMOTE_PROCESS("Shizuku process") }

    override val kind = AccessKind.SHIZUKU
    override var uid: Int = 2000
        private set

    /** Backend that last worked, or null before the first command. */
    @Volatile var backend: Backend? = null
        private set
    /** Why the user service could not be used, if it failed. */
    @Volatile var userServiceError: String? = null
        private set

    @Volatile private var service: IShellService? = null
    @Volatile private var userServiceFailed = false
    private val bindLock = Mutex()

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private var connection: ServiceConnection? = null

    private suspend fun ensureService(): IShellService {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        return bindLock.withLock {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            withTimeout(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val s = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
                            service = s
                            if (s != null) {
                                uid = runCatching { s.uid() }.getOrDefault(2000)
                                if (cont.isActive) cont.resume(s)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                        }
                    }
                    connection = conn
                    // Shizuku delivers the connection callbacks on the main thread; bind from there too.
                    Handler(Looper.getMainLooper()).post {
                        runCatching { Shizuku.bindUserService(args, conn) }.onFailure { e ->
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            }
        }
    }

    override suspend fun run(argv: List<String>, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (useUserService && !userServiceFailed) {
            val s = try {
                ensureService()
            } catch (e: Exception) {
                userServiceFailed = true
                userServiceError = e.message ?: e.toString()
                releaseConnection()
                null
            }
            if (s != null) {
                val bundle = s.exec(argv.toTypedArray(), timeoutMs)
                backend = Backend.USER_SERVICE
                return@withContext ShellResult(
                    exitCode = bundle.getInt(ShellService.KEY_EXIT, -1),
                    stdout = bundle.getString(ShellService.KEY_OUT).orEmpty(),
                    stderr = bundle.getString(ShellService.KEY_ERR).orEmpty(),
                )
            }
        }
        val r = runRemoteProcess(argv, timeoutMs)
        if (r.exitCode != ERROR_NO_BACKEND) backend = Backend.REMOTE_PROCESS
        r
    }

    /** Runs the command in Shizuku's server process (Shizuku.newProcess, private since API 13, so reflection). */
    private fun runRemoteProcess(argv: List<String>, timeoutMs: Long): ShellResult {
        val method = newProcessMethod ?: return ShellResult(ERROR_NO_BACKEND, "",
            "Shizuku's process API is not available. User service error: $userServiceError")
        val cmd = argv.toMutableList()
        if (!cmd[0].startsWith("/")) {
            listOf("/system/bin/", "/system/xbin/").map { File(it + cmd[0]) }.firstOrNull { it.exists() }?.let { cmd[0] = it.path }
        }
        return try {
            val p = method.invoke(null, cmd.toTypedArray(), null, null) as Process
            runCatching { p.outputStream.close() }
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
            val t2 = thread { runCatching { err.append(p.errorStream.bufferedReader().readText()) } }
            // Shizuku's timed waitFor() can return before the process exits (then exitValue() throws
            // "process hasn't exited"), so wait with the plain blocking call on a watcher thread.
            var exit: Int? = null
            val waiter = thread { exit = runCatching { p.waitFor() }.getOrNull() }
            waiter.join(timeoutMs.coerceIn(1_000, 30 * 60_000))
            val done = exit != null
            if (!done) runCatching { p.destroy() }
            t1.join(2_000)
            t2.join(2_000)
            ShellResult(exit ?: -2, out.toString().take(ShellService.MAX_OUTPUT),
                if (done) err.toString().take(ShellService.MAX_OUTPUT) else "Timed out after ${timeoutMs / 1000} s")
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            ShellResult(ERROR_NO_BACKEND, "", "Shizuku could not run the command: ${cause.message ?: cause}")
        }
    }

    private fun releaseConnection() {
        connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        connection = null
        service = null
    }

    /** Forgets everything, for example after Shizuku restarted: the user service is tried again. */
    fun release() {
        releaseConnection()
        userServiceFailed = false
        userServiceError = null
        backend = null
    }

    companion object {
        const val BIND_TIMEOUT_MS = 10_000L
        /** Exit code used when no Shizuku backend could run the command at all. */
        const val ERROR_NO_BACKEND = -5

        private val newProcessMethod by lazy {
            runCatching {
                Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                    .apply { isAccessible = true }
            }.getOrNull()
        }
    }
}
