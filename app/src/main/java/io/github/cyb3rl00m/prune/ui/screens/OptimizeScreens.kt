package io.github.cyb3rl00m.prune.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cyb3rl00m.prune.core.model.AppPackage
import io.github.cyb3rl00m.prune.core.optimize.OptimizationSection
import io.github.cyb3rl00m.prune.core.plan.Executor
import io.github.cyb3rl00m.prune.core.plan.Plan
import io.github.cyb3rl00m.prune.core.plan.PlannedChange
import io.github.cyb3rl00m.prune.ui.AppViewModel
import io.github.cyb3rl00m.prune.ui.Nav
import io.github.cyb3rl00m.prune.ui.OptimizeViewModel
import io.github.cyb3rl00m.prune.ui.Routes
import io.github.cyb3rl00m.prune.ui.components.Banner
import io.github.cyb3rl00m.prune.ui.components.BannerKind
import io.github.cyb3rl00m.prune.ui.components.PackageRow
import io.github.cyb3rl00m.prune.ui.components.Pill
import io.github.cyb3rl00m.prune.ui.components.PruneCard
import io.github.cyb3rl00m.prune.ui.components.SectionHeader
import io.github.cyb3rl00m.prune.ui.components.daysAgo
import io.github.cyb3rl00m.prune.ui.components.formatDuration

private fun iconOf(s: OptimizationSection): ImageVector = when (s) {
    OptimizationSection.ANIMATIONS -> Icons.Outlined.Animation
    OptimizationSection.BACKGROUND -> Icons.Outlined.Bolt
    OptimizationSection.BATTERY_EXEMPTIONS -> Icons.Outlined.BatteryChargingFull
    OptimizationSection.PERMISSIONS -> Icons.Outlined.PrivacyTip
    OptimizationSection.NOTIFICATIONS -> Icons.Outlined.Notifications
    OptimizationSection.STORAGE -> Icons.Outlined.Storage
    OptimizationSection.RESOURCES -> Icons.Outlined.Speed
    OptimizationSection.COMPILATION -> Icons.Outlined.Build
}

@Composable
fun OptimizeScreen(vm: AppViewModel, ovm: OptimizeViewModel, nav: Nav) {
    ScreenScaffold("Optimize", null, "optimize", subtitle = "Only changes with a real technical effect. No placebo.") {
        item {
            Banner("Prune does not kill processes, change CPU/GPU governors or touch thermal management. Those make phones less stable and often use more battery, not less.",
                BannerKind.INFO)
        }
        OptimizationSection.entries.forEach { s ->
            item {
                PruneCard(onClick = { nav.go(Routes.section(s)) }, modifier = Modifier.testTag("section_${s.name}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconOf(s), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall)
                            Text(s.requirement, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun OptimizeSectionScreen(vm: AppViewModel, ovm: OptimizeViewModel, nav: Nav, section: OptimizationSection) {
    val state by ovm.state.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val includeSystem = settings.showSystemInOptimizer
    LaunchedEffect(section, scan.lastScan, access.privileged, includeSystem) { ovm.load(section, includeSystem) }
    val loading = section in state.loading
    val selection = remember(section) { mutableStateOf(setOf<String>()) }

    fun review(title: String, changes: List<PlannedChange>) {
        if (changes.isEmpty()) return
        vm.review(Plan(title, section.title, changes))
        nav.go(Routes.REVIEW)
    }

    fun openSettings(pkg: String, notifications: Boolean = false) = runCatching {
        val intent = if (notifications && Build.VERSION.SDK_INT >= 26)
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        context.startActivity(intent)
    }

    ScreenScaffold(section.title, nav, "section_${section.name}") {
        item {
            PruneCard {
                Text("Why this works", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(section.why, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Pill("Requires: ${section.requirement}", MaterialTheme.colorScheme.secondary)
            }
        }
        if (loading) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
        }
        when (section) {
            OptimizationSection.ANIMATIONS -> {
                val a = state.animations
                item {
                    PruneCard {
                        Text("Current scales", style = MaterialTheme.typography.titleSmall)
                        if (a != null) Text("Window ${a.window}× · Transition ${a.transition}× · Animator ${a.animator}×", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1f to "1× (default)", 0.5f to "0.5×", 0f to "Off").forEach { (v, label) ->
                                FilterChip(selected = a?.uniform == v, onClick = {
                                    review("Animation speed $label", vm.repo.optimizer.animationChanges(v))
                                }, label = { Text(label) }, modifier = Modifier.testTag("anim_$v"))
                            }
                        }
                        if (!access.privileged && !access.writeSecureSettings) {
                            Spacer(Modifier.height(8.dp))
                            Text("Without Shizuku you can still export the change as an ADB script on the review screen.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OptimizationSection.BACKGROUND -> {
                val list = state.background
                if (scan.usage.isEmpty()) item {
                    Banner("Usage access is needed to know which apps you don't use.", BannerKind.WARNING) {
                        TextButton(onClick = { nav.go(Routes.SETUP) }) { Text("Grant usage access") }
                    }
                } else if (!access.privileged) item {
                    Banner("Changing background access needs Shizuku or root. You can also set each app to \"Restricted\" in Settings → Apps → app → Battery.", BannerKind.INFO)
                }
                if (list != null) {
                    if (list.isEmpty() && !loading && scan.usage.isNotEmpty()) item { PruneCard { Text("No unused apps that still run in the background.") } }
                    selectableList(list, selection.value, { selection.value = it }, subtitle = { "Last opened ${daysAgo(it.lastUsed)}" }) { ids ->
                        review("Restrict background activity", list.filter { it.packageName in ids }.map { vm.repo.optimizer.backgroundChange(it, true) })
                    }
                }
            }
            OptimizationSection.BATTERY_EXEMPTIONS -> {
                val e = state.exemptions
                if (e != null) {
                    item { SectionHeader("Exempt apps you can change (${e.removable.size})") }
                    selectableList(e.removable, selection.value, { selection.value = it }, onOpen = { openSettings(it.packageName) }) { ids ->
                        review("Remove battery exemptions", e.removable.filter { it.packageName in ids }.map { vm.repo.optimizer.exemptionChange(it) })
                    }
                    if (e.systemManaged.isNotEmpty()) {
                        item { SectionHeader("Exempted by the system (${e.systemManaged.size})") }
                        item {
                            Text("Android or the manufacturer exempt these themselves. They cannot be changed from here.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(e.systemManaged, key = { "sys" + it.packageName }) { PackageRow(it) }
                    }
                }
            }
            OptimizationSection.PERMISSIONS -> {
                item { SystemToggle(includeSystem) { vm.update { setShowSystem(it) } } }
                val list = state.permissions
                if (list != null) {
                    if (list.isEmpty() && !loading) item { PruneCard { Text("No apps hold sensitive permissions.") } }
                    items(list, key = { it.app.packageName }) { ap ->
                        PruneCard(padding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                            PackageRow(ap.app, subtitle = ap.granted.joinToString { it.label }, showStatus = false,
                                onClick = { openSettings(ap.app.packageName) })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { openSettings(ap.app.packageName) }) { Text("Settings") }
                                if (access.privileged) TextButton(onClick = {
                                    review("Revoke permissions: ${ap.app.label}", ap.granted.map { vm.repo.optimizer.revokeChange(ap.app, it) })
                                }) { Text("Revoke all…") }
                            }
                        }
                    }
                }
            }
            OptimizationSection.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT < 33) item {
                    Banner("Notification permission exists from Android 13. Use Settings → Notifications on this version.", BannerKind.INFO)
                }
                item { SystemToggle(includeSystem) { vm.update { setShowSystem(it) } } }
                val list = state.notifications
                if (list != null) {
                    selectableList(list, selection.value, { selection.value = it },
                        subtitle = { if (scan.usage.isEmpty()) it.packageName else "Last opened ${daysAgo(it.lastUsed)}" },
                        onOpen = { openSettings(it.packageName, notifications = true) }) { ids ->
                        review("Turn off notifications", list.filter { it.packageName in ids }.map { vm.repo.optimizer.notificationChange(it) })
                    }
                }
            }
            OptimizationSection.STORAGE -> {
                val s = state.storage
                if (scan.usage.isEmpty()) item {
                    Banner("Usage access is needed to read app sizes.", BannerKind.WARNING) {
                        TextButton(onClick = { nav.go(Routes.SETUP) }) { Text("Grant usage access") }
                    }
                }
                if (s != null) {
                    item {
                        PruneCard {
                            val used = s.totalBytes - s.freeBytes
                            Text("${Executor.formatBytes(used)} used of ${Executor.formatBytes(s.totalBytes)}", style = MaterialTheme.typography.titleMedium)
                            Text("${Executor.formatBytes(s.freeBytes)} free · app caches ${Executor.formatBytes(s.cacheBytes)}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { review("Clear app caches", listOf(vm.repo.optimizer.trimCachesChange())) },
                                enabled = true, modifier = Modifier.fillMaxWidth().testTag("trim_caches")) { Text("Clear all app caches…") }
                        }
                    }
                    item { SectionHeader("Largest apps") }
                    items(s.apps.take(25), key = { it.app.packageName }) { a ->
                        PackageRow(a.app, subtitle = "${Executor.formatBytes(a.total)} · cache ${Executor.formatBytes(a.cacheBytes)}",
                            showStatus = false, onClick = { openSettings(a.app.packageName) })
                    }
                }
            }
            OptimizationSection.RESOURCES -> {
                if (scan.usage.isEmpty()) item {
                    Banner("Usage access is needed for screen time.", BannerKind.WARNING) {
                        TextButton(onClick = { nav.go(Routes.SETUP) }) { Text("Grant usage access") }
                    }
                }
                if (!access.privileged) item {
                    Banner("Battery estimates need Shizuku or root (they come from dumpsys batterystats).", BannerKind.INFO)
                }
                val list = state.resources
                if (list != null) {
                    item {
                        Text("Battery figures are Android's own estimate since the last full charge. They are approximate and not available on every firmware.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(list.take(30), key = { it.app.packageName }) { r ->
                        PackageRow(r.app, showStatus = false, onClick = { nav.go(Routes.app(r.app.packageName)) },
                            subtitle = listOfNotNull(
                                r.batteryMah?.let { String.format(java.util.Locale.US, "%.1f mAh", it) },
                                if (r.foregroundMs7d > 0) "${formatDuration(r.foregroundMs7d)} on screen (7 days)" else null,
                            ).joinToString(" · ").ifEmpty { r.app.packageName })
                    }
                }
            }
            OptimizationSection.COMPILATION -> item {
                PruneCard {
                    Text("Runs the same job Android runs overnight. It can take 5–20 minutes and the phone may get warm. Best done while charging.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { review("Compile apps now", listOf(vm.repo.optimizer.dexoptChange())) },
                        modifier = Modifier.fillMaxWidth()) { Text("Compile apps now…") }
                }
            }
        }
    }
}

@Composable
private fun SystemToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    OptionSwitch("Show system apps", "Off: only apps you installed and third-party preloads.", on, onChange)
}

private fun androidx.compose.foundation.lazy.LazyListScope.selectableList(
    apps: List<AppPackage>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    subtitle: (AppPackage) -> String = { it.packageName },
    onOpen: ((AppPackage) -> Unit)? = null,
    onReview: (Set<String>) -> Unit,
) {
    if (apps.isEmpty()) return
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size} selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSelect(if (selected.size == apps.size) emptySet() else apps.map { it.packageName }.toSet()) }) {
                Text(if (selected.size == apps.size) "None" else "All")
            }
            OutlinedButton(onClick = { onReview(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.testTag("review_selected")) {
                Text("Review…")
            }
        }
    }
    items(apps, key = { "sel" + it.packageName }) { a ->
        val on = a.packageName in selected
        PackageRow(a, subtitle = subtitle(a), showStatus = false,
            onClick = { onSelect(if (on) selected - a.packageName else selected + a.packageName) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onOpen != null) TextButton(onClick = { onOpen(a) }) { Text("Settings") }
                    Checkbox(on, { onSelect(if (on) selected - a.packageName else selected + a.packageName) })
                }
            })
    }
}

