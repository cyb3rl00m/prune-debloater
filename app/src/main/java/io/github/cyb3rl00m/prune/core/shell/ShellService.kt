package io.github.cyb3rl00m.prune.core.shell

import android.os.Bundle
import io.github.cyb3rl00m.prune.IShellService
import kotlin.system.exitProcess

class ShellService : IShellService.Stub() {
    override fun destroy() { exitProcess(0) }
    override fun exec(argv: Array<out String>, timeoutMillis: Long): Bundle = Bundle()
    override fun uid(): Int = android.os.Process.myUid()
}
