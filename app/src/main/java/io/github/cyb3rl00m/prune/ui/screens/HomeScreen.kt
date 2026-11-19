package io.github.cyb3rl00m.prune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cyb3rl00m.prune.core.model.PackageStatus
import io.github.cyb3rl00m.prune.core.plan.DebloatLevel
import io.github.cyb3rl00m.prune.core.plan.PlanBuilder
import io.github.cyb3rl00m.prune.core.shell.AccessKind
import io.github.cyb3rl00m.prune.ui.AppViewModel
import io.github.cyb3rl00m.prune.ui.Nav
import io.github.cyb3rl00m.prune.ui.Routes
import io.github.cyb3rl00m.prune.ui.components.Banner
import io.github.cyb3rl00m.prune.ui.components.BannerKind
import io.github.cyb3rl00m.prune.ui.components.Pill
import io.github.cyb3rl00m.prune.ui.components.PruneCard
import io.github.cyb3rl00m.prune.ui.components.SectionHeader
import io.github.cyb3rl00m.prune.ui.components.StatTile

@Composable
fun HomeScreen(vm: AppViewModel, nav: Nav) {
    val device by vm.device.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val apps = scan.apps
    val installed = apps.count { it.facts.installedForUser }
    val system = apps.count { it.facts.isSystem }
    val removable = apps.count { !it.isLocked && it.status == PackageStatus.ENABLED && (it.classification.level ?: 9) <= 3 && it.facts.isSystem }
    val changed = apps.count { it.facts.isSystem && it.status != PackageStatus.ENABLED }
    val (done, total) = remember(apps, settings.progressLevel) { PlanBuilder.progress(apps, settings.progressLevel) }
    val recommendations by produceState(emptyList<io.github.cyb3rl00m.prune.core.optimize.Recommendation>(), apps, scan.usage, access.privileged) {
        value = if (apps.isEmpty()) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            vm.repo.optimizer.recommendations(apps, scan.usage, access.privileged)
        }
    }

    ScreenScaffold(
        title = "Prune", nav = null, tag = "home",
        subtitle = "Debloater & optimizer for Samsung Galaxy",
        actions = {
            IconButton(onClick = { vm.rescan() }, modifier = Modifier.testTag("rescan")) { Icon(Icons.Outlined.Refresh, "Scan again") }
            IconButton(onClick = { nav.go(Routes.SETTINGS) }, modifier = Modifier.testTag("open_settings")) { Icon(Icons.Outlined.Settings, "Settings") }
        },
    ) {
        item {
            val brush = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f)))
            PruneCard(onClick = { nav.go(Routes.DEVICE) }, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Box(Modifier.fillMaxWidth().background(brush).padding(20.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Smartphone, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(if (device?.isSamsung == false) "Not a Samsung device" else "Samsung Galaxy",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(device?.marketName ?: "Reading device…", style = MaterialTheme.typography.headlineMedium)
                        Text(device?.model ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            device?.let { d ->
                                Pill("Android ${d.androidVersion}", MaterialTheme.colorScheme.onSurface)
                                d.oneUiVersion?.let { Pill("One UI $it", MaterialTheme.colorScheme.onSurface) }
                                d.cscCode?.let { Pill("CSC $it", MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                    }
                }
            }
        }

        if (device?.isSamsung == false) item {
            Banner("Prune is built for Samsung Galaxy phones. Android packages and the safety rules still apply, but Samsung-specific recommendations will not.", BannerKind.WARNING)
        }

        item {
            AccessCard(access.active, access.privileged) { nav.go(Routes.SETUP) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(if (scan.scanning && apps.isEmpty()) "…" else installed.toString(), "Installed packages", Icons.Outlined.Apps, Modifier.weight(1f))
                StatTile(system.toString(), "System packages", Icons.Outlined.Inventory2, Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(removable.toString(), "Removable (Levels 1–3)", Icons.Outlined.ContentCut, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                StatTile(changed.toString(), "Disabled or removed", Icons.Outlined.CheckCircle, Modifier.weight(1f))
            }
        }

        item {
            PruneCard {
                Text("Debloat progress", style = MaterialTheme.typography.titleMedium)
                Text("Share of the optional packages at this level that are already off.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DebloatLevel.MINIMAL, DebloatLevel.BALANCED, DebloatLevel.AGGRESSIVE).forEach { l ->
                        FilterChip(selected = settings.progressLevel == l.number, onClick = { vm.update { setProgressLevel(l.number) } },
                            label = { Text(l.title) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                val fraction = if (total == 0) 0f else done.toFloat() / total
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("$done of $total packages (${(fraction * 100).toInt()} %)", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { nav.tab(Routes.DEBLOAT) }, modifier = Modifier.fillMaxWidth().testTag("home_debloat")) {
                    Icon(Icons.Outlined.ContentCut, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose a debloat level")
                }
            }
        }

        item { SectionHeader("Optimization recommendations") }
        if (recommendations.isEmpty()) item {
            PruneCard { Text(if (scan.scanning) "Scanning…" else "No recommendations right now.", style = MaterialTheme.typography.bodyMedium) }
        }
        recommendations.forEach { r ->
            item {
                PruneCard(onClick = { nav.go(Routes.section(r.section)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.TipsAndUpdates, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall)
                            Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        scan.error?.let { e -> item { Banner("Scan failed: $e", BannerKind.ERROR) } }
        item {
            Text(
                if (scan.lastScan == 0L) "Scanning packages…" else "Last scan ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(scan.lastScan))} · database ${vm.repo.database.rules.updated}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }
}

@Composable
fun AccessCard(active: AccessKind, privileged: Boolean, onSetup: () -> Unit) {
    PruneCard(onClick = onSetup, color = if (privileged) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, tint = if (privileged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (privileged) "Access: ${active.label}" else "Limited access", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (privileged) "Prune can disable, remove and restore system packages."
                    else "Prune can scan and explain everything. To change system packages, set up Shizuku (no root needed).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!privileged) OutlinedButton(onClick = onSetup, modifier = Modifier.testTag("setup_access")) { Text("Set up") }
        }
    }
}
