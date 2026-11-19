package io.github.cyb3rl00m.prune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cyb3rl00m.prune.core.history.Batch
import io.github.cyb3rl00m.prune.core.model.PackageStatus
import io.github.cyb3rl00m.prune.ui.AppViewModel
import io.github.cyb3rl00m.prune.ui.Nav
import io.github.cyb3rl00m.prune.ui.Routes
import io.github.cyb3rl00m.prune.ui.components.Banner
import io.github.cyb3rl00m.prune.ui.components.BannerKind
import io.github.cyb3rl00m.prune.ui.components.EmptyState
import io.github.cyb3rl00m.prune.ui.components.PackageRow
import io.github.cyb3rl00m.prune.ui.components.Pill
import io.github.cyb3rl00m.prune.ui.components.PruneCard
import io.github.cyb3rl00m.prune.ui.components.SectionHeader
import io.github.cyb3rl00m.prune.ui.components.rememberTextExporter
import io.github.cyb3rl00m.prune.ui.theme.MonoStyle
import java.text.DateFormat
import java.util.Date

private fun formatTime(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

@Composable
fun RestoreScreen(vm: AppViewModel, nav: Nav) {
    val history by vm.history.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val exporter = rememberTextExporter { ok -> vm.say(if (ok) "Saved." else "Could not save the file.") }
    val off = scan.apps.filter { it.facts.isSystem && it.status != PackageStatus.ENABLED }

    ScreenScaffold("Restore", null, "restore", subtitle = "History, undo and recovery") {
        item {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                listOf("History", "On this device (${off.size})", "Tools").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }, modifier = Modifier.testTag("restore_tab_$i"))
                }
            }
        }
        when (tab) {
            0 -> {
                if (history.batches.isEmpty()) item {
                    EmptyState("No changes yet", "Everything Prune changes is recorded here with the state before the change, so it can be undone.",
                        Icons.Outlined.History)
                }
                items(history.batches, key = { it.id }) { b -> BatchCard(b) { nav.go(Routes.batch(b.id)) } }
            }
            1 -> {
                item {
                    Text("System packages that are currently disabled or removed for this user — by Prune or by any other tool.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (off.isEmpty()) item { EmptyState("All system packages are on", "Nothing to restore.", Icons.Outlined.CheckCircle) }
                else item {
                    Button(onClick = { if (vm.restorePackages(off)) nav.go(Routes.REVIEW) }, modifier = Modifier.fillMaxWidth().testTag("restore_all_off")) {
                        Text("Restore all ${off.size}…")
                    }
                }
                items(off, key = { it.packageName }) { a ->
                    PackageRow(a, onClick = { nav.go(Routes.app(a.packageName)) }, trailing = {
                        TextButton(onClick = { if (vm.restorePackages(listOf(a))) nav.go(Routes.REVIEW) }) { Text("Restore") }
                    })
                }
            }
            else -> {
                item {
                    PruneCard {
                        Text("Restore original state", style = MaterialTheme.typography.titleSmall)
                        Text("Puts every package Prune ever changed back to the state it had the first time Prune touched it (${history.originals.size} packages recorded).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { if (vm.restoreAllOriginals()) nav.go(Routes.REVIEW) },
                            modifier = Modifier.fillMaxWidth().testTag("restore_originals")) { Text("Review restore…") }
                    }
                }
                item {
                    PruneCard {
                        Text("Emergency restore script", style = MaterialTheme.typography.titleSmall)
                        Text("An ADB script that re-enables every disabled or removed system package. Keep a copy on your computer: it works even if Prune or Shizuku are not available.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { exporter.save("prune-restore.sh", vm.emergencyRestoreScript()) }, modifier = Modifier.weight(1f)) { Text("Save") }
                            OutlinedButton(onClick = { exporter.share("prune-restore.sh", vm.emergencyRestoreScript()) }, modifier = Modifier.weight(1f)) { Text("Share") }
                        }
                    }
                }
                item {
                    PruneCard {
                        Text("Export history", style = MaterialTheme.typography.titleSmall)
                        Text("Every change with its original state, as JSON.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { exporter.save("prune-history.json", vm.repo.history.exportJson()) }, modifier = Modifier.fillMaxWidth()) { Text("Save history") }
                    }
                }
                item {
                    Banner("If the phone misbehaves after a change: open Restore → History → Undo. If the phone cannot be used normally, connect it to a computer and run the emergency restore script, or run: adb shell pm install-existing <package>",
                        BannerKind.INFO)
                }
            }
        }
    }
}

@Composable
private fun BatchCard(b: Batch, onClick: () -> Unit) {
    val ok = b.entries.count { it.success == true }
    val failed = b.entries.count { it.success == false }
    val pending = b.entries.count { it.success == null }
    val undone = b.entries.count { it.undone }
    PruneCard(onClick = onClick, modifier = Modifier.testTag("batch_${b.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall)
                Text("${formatTime(b.timestamp)} · ${b.access}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ok > 0) Pill("$ok applied", MaterialTheme.colorScheme.primary)
                    if (failed > 0) Pill("$failed failed", MaterialTheme.colorScheme.error)
                    if (pending > 0) Pill("$pending to check", MaterialTheme.colorScheme.tertiary)
                    if (undone > 0) Pill("$undone undone", MaterialTheme.colorScheme.secondary)
                }
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BatchScreen(vm: AppViewModel, nav: Nav, id: String) {
    val history by vm.history.collectAsStateWithLifecycle()
    val batch = history.batches.firstOrNull { it.id == id }
    if (batch == null) {
        ScreenScaffold("Batch", nav, "batch") { item { Banner("Not found.", BannerKind.INFO) } }
        return
    }
    val undoable = batch.entries.any { it.success == true && !it.undone && it.change.type.reversible }
    ScreenScaffold(batch.title, nav, "batch", subtitle = formatTime(batch.timestamp),
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (batch.script) OutlinedButton(onClick = { vm.verifyScript(batch) }, modifier = Modifier.weight(1f)) { Text("Check results") }
                Button(onClick = { if (vm.undoBatch(batch)) nav.go(Routes.REVIEW) }, enabled = undoable, modifier = Modifier.weight(1f).testTag("undo_batch")) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Undo")
                }
            }
        }) {
        item {
            Text("Origin: ${batch.origin} · via ${batch.access}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(batch.entries, key = { it.id }) { e ->
            PruneCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (e.success) { true -> Icons.Outlined.CheckCircle; false -> Icons.Outlined.ErrorOutline; null -> Icons.Outlined.HourglassEmpty },
                        null,
                        tint = when (e.success) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.tertiary },
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.change.label, style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(e.change.type.label, e.change.packageName).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (e.undone) Pill("Undone", MaterialTheme.colorScheme.secondary)
                    else if (e.success == true && e.change.type.reversible) TextButton(onClick = {
                        if (vm.undoEntries(listOf(e), "Undo: ${e.change.label}")) nav.go(Routes.REVIEW)
                    }) { Text("Undo") }
                }
                Spacer(Modifier.height(6.dp))
                Text("Before: ${e.before ?: "—"}   After: ${e.after ?: "—"}", style = MaterialTheme.typography.bodySmall)
                Text(e.message, style = MaterialTheme.typography.bodySmall, color = if (e.success == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.commands.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer { Text(e.commands.joinToString("\n"), style = MonoStyle, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        item { SectionHeader("") }
    }
}
