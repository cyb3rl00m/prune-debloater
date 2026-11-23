package io.github.cyb3rl00m.prune

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.cyb3rl00m.prune.core.shell.Commands
import io.github.cyb3rl00m.prune.core.shell.OutputParsers
import io.github.cyb3rl00m.prune.core.shell.ShizukuShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.util.regex.Pattern

/**
 * Runs Prune's real Shizuku code path against a real Shizuku server (started by CI with adb).
 * The other instrumented tests use a UiAutomation shell instead, so they never touched this path.
 *
 * Skipped unless the runner gets `-e shizuku required`.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuRealTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before fun connect() {
        assumeTrue("Shizuku test not requested", InstrumentationRegistry.getArguments().getString("shizuku") == "required")
        // Shizuku hands its binder to apps when they come to the foreground.
        ActivityScenario.launch(MainActivity::class.java)
        val deadline = System.currentTimeMillis() + 30_000
        while (!Shizuku.pingBinder() && System.currentTimeMillis() < deadline) Thread.sleep(250)
        assertTrue("Shizuku binder never arrived", Shizuku.pingBinder())
        Log.i(TAG, "Shizuku server version ${Shizuku.getVersion()}, uid ${Shizuku.getUid()}")

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Handler(Looper.getMainLooper()).post { Shizuku.requestPermission(REQUEST) }
            val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)^allow all the time$"))), 20_000)
            if (allow == null) fail("Shizuku permission dialog did not appear") else allow.click()
            val until = System.currentTimeMillis() + 10_000
            while (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED && System.currentTimeMillis() < until) Thread.sleep(200)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, Shizuku.checkSelfPermission())
    }

    private fun idThrough(shell: ShizukuShell): Int {
        val start = System.currentTimeMillis()
        val r = runBlocking { shell.run(Commands.id(), 30_000) }
        val ms = System.currentTimeMillis() - start
        Log.i(TAG, "id via ${shell.backend?.label} in $ms ms: exit ${r.exitCode} ${r.output}; user service error: ${shell.userServiceError}")
        assertEquals(r.output, 0, r.exitCode)
        return OutputParsers.uidOf(r.stdout) ?: error("no uid in ${r.stdout}")
    }

    @Test fun userServiceRunsCommands() {
        val shell = ShizukuShell(context.packageName)
        try {
            assertEquals(2000, idThrough(shell))
            assertEquals("user service error: ${shell.userServiceError}", ShizukuShell.Backend.USER_SERVICE, shell.backend)
        } finally { shell.release() }
    }

    @Test fun remoteProcessFallbackRunsCommands() {
        val shell = ShizukuShell(context.packageName, useUserService = false)
        assertEquals(2000, idThrough(shell))
        assertEquals(ShizukuShell.Backend.REMOTE_PROCESS, shell.backend)
    }

    @Test fun disableAndEnableRoundTripThroughShizuku() {
        val pm = context.packageManager
        val user = android.os.Process.myUid() / 100_000
        val target = listOf("com.android.egg", "com.android.dreams.basic", "com.android.traceur", "com.android.bips")
            .first { p -> runCatching { pm.getApplicationInfo(p, 0).enabled }.getOrDefault(false) }
        val shell = ShizukuShell(context.packageName)
        try {
            val off = runBlocking { shell.run(Commands.disable(target, user), 30_000) }
            Log.i(TAG, "disable $target via ${shell.backend?.label}: ${off.exitCode} ${off.output}")
            assertTrue(off.output, OutputParsers.disableSucceeded(off))
            assertTrue("$target still enabled", !pm.getApplicationInfo(target, 0).enabled)
        } finally {
            runBlocking { shell.run(Commands.enable(target, user), 30_000) }
            shell.release()
        }
        assertTrue("$target not re-enabled", pm.getApplicationInfo(target, 0).enabled)
    }

    private companion object {
        const val TAG = "PruneShizukuTest"
        const val REQUEST = 4242
    }
}
