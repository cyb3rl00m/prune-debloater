package io.github.cyb3rl00m.prune.tiles

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.cyb3rl00m.prune.MainActivity
import io.github.cyb3rl00m.prune.core.history.Batch
import io.github.cyb3rl00m.prune.core.plan.Executor
import io.github.cyb3rl00m.prune.core.plan.Plan
import io.github.cyb3rl00m.prune.core.plan.PlannedChange
import io.github.cyb3rl00m.prune.repository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick Settings tiles. They run the same reviewed changes as the app (recorded in History, undoable there)
 * and need the same access: Shizuku or root.
 */
abstract class PruneTile : TileService() {
    private val repo get() = applicationContext.repository
    @Volatile private var busy = false

    override fun onStartListening() = render()

    protected abstract fun render()

    protected fun show(state: Int, label: String, subtitle: String?) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle
        tile.updateTile()
    }

    /** Applies [changes] in the background and shows [result] (or the failure) in the tile. */
    protected fun runChanges(title: String, label: String, changes: List<PlannedChange>, result: (Batch) -> String) {
        if (busy) return
        busy = true
        show(Tile.STATE_UNAVAILABLE, label, "Working…")
        repo.scope.launch {
            try {
                // A tile can start Prune's process; Shizuku hands over its binder a moment later.
                var shell = repo.access.shell()
                var tries = 0
                while (shell == null && tries++ < 10) {
                    delay(300); repo.access.refresh(); shell = repo.access.shell()
                }
                val s = shell
                if (s == null) {
                    show(Tile.STATE_INACTIVE, label, "Set up Shizuku in Prune")
                    openApp()
                    return@launch
                }
                val batch = repo.executor.apply(Plan(title, "Quick Settings tile", changes), s)
                val failed = batch.entries.firstOrNull { it.success != true }
                render()
                if (failed != null) show(Tile.STATE_INACTIVE, label, "Failed: " + failed.message.take(40)) else qsTile?.let {
                    if (Build.VERSION.SDK_INT >= 29) { it.subtitle = result(batch); it.updateTile() }
                }
            } finally {
                busy = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else startActivityAndCollapse(intent)
        }
    }
}

/** Switches animations between normal speed (1×) and fast (0.5×). */
class AnimationTile : PruneTile() {
    private val optimizer get() = applicationContext.repository.optimizer

    override fun render() {
        val scale = optimizer.animationState().uniform
        show(if (scale != null && scale < 1f) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, "Fast animations",
            when (scale) { null -> "Mixed"; 0f -> "Off"; else -> "${scale}×" })
    }

    override fun onClick() {
        val fast = optimizer.animationState().uniform.let { it == null || it >= 1f }
        val target = if (fast) 0.5f else 1f
        runChanges("Animation speed ${target}×", "Fast animations", optimizer.animationChanges(target)) { "${target}×" }
    }
}

/** Clears the cache of every app (pm trim-caches) and shows how much space was freed. */
class CacheTile : PruneTile() {
    override fun render() = show(Tile.STATE_INACTIVE, "Clear caches", null)

    override fun onClick() {
        val change = applicationContext.repository.optimizer.trimCachesChange()
        runChanges("Clear app caches", "Clear caches", listOf(change)) { batch ->
            val e = batch.entries.firstOrNull()
            val freed = (e?.after?.toLongOrNull() ?: 0L) - (e?.before?.toLongOrNull() ?: 0L)
            if (freed > 0) "Freed ${Executor.formatBytes(freed)}" else "Done"
        }
    }
}
