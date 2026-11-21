package io.github.cyb3rl00m.prune.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cyb3rl00m.prune.core.model.AppPackage
import io.github.cyb3rl00m.prune.core.model.ChangeType
import io.github.cyb3rl00m.prune.core.model.PackageStatus
import io.github.cyb3rl00m.prune.core.model.Risk
import io.github.cyb3rl00m.prune.core.model.Source
import io.github.cyb3rl00m.prune.ui.AppViewModel
import io.github.cyb3rl00m.prune.ui.Nav
import io.github.cyb3rl00m.prune.ui.Routes
import io.github.cyb3rl00m.prune.ui.components.AppIcon
import io.github.cyb3rl00m.prune.ui.components.Banner
import io.github.cyb3rl00m.prune.ui.components.BannerKind
import io.github.cyb3rl00m.prune.ui.components.EmptyState
import io.github.cyb3rl00m.prune.ui.components.KeyValue
import io.github.cyb3rl00m.prune.ui.components.PackageRow
import io.github.cyb3rl00m.prune.ui.components.Pill
import io.github.cyb3rl00m.prune.ui.components.PruneCard
import io.github.cyb3rl00m.prune.ui.components.RiskBadge
import io.github.cyb3rl00m.prune.ui.components.SectionHeader
import io.github.cyb3rl00m.prune.ui.components.StatusBadge
import io.github.cyb3rl00m.prune.ui.components.daysAgo

private enum class StatusFilter(val label: String) { ALL("All"), ENABLED("Enabled"), OFF("Disabled / removed"), DUPLICATES("Duplicates") }

@Composable
fun AppsScreen(vm: AppViewModel, nav: Nav) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<Source?>(null) }
    var status by rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    var risk by rememberSaveable { mutableStateOf<Risk?>(null) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val filtered = remember(scan.apps, query, source, status, risk) {
        scan.apps.filter { a ->
            (query.isBlank() || a.label.contains(query, true) || a.packageName.contains(query, true)) &&
                (source == null || a.classification.source == source) &&
                (risk == null || a.risk == risk) &&
                when (status) {
                    StatusFilter.ALL -> true
                    StatusFilter.ENABLED -> a.status == PackageStatus.ENABLED
                    StatusFilter.OFF -> a.status != PackageStatus.ENABLED
                    StatusFilter.DUPLICATES -> a.duplicateGroup != null
                }
        }
    }
    ScreenScaffold("Packages", null, "apps", subtitle = "${filtered.size} of ${scan.apps.size} packages") {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Search name or package") },
                modifier = Modifier.fillMaxWidth().testTag("search"),
                shape = MaterialTheme.shapes.large,
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(source == null, { source = null }, { Text("All sources") })
                Source.entries.forEach { s ->
                    val n = scan.apps.count { it.classification.source == s }
                    if (n > 0) FilterChip(source == s, { source = if (source == s) null else s }, { Text("${s.label} ($n)") })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.entries.forEach { f -> FilterChip(status == f, { status = f }, { Text(f.label) }) }
                Spacer(Modifier.width(4.dp))
                Risk.entries.forEach { r -> FilterChip(risk == r, { risk = if (risk == r) null else r }, { Text(r.label) }) }
            }
        }
        if (scan.scanning && scan.apps.isEmpty()) item { EmptyState("Scanning", "Reading installed packages…", Icons.Outlined.Search) }
        else if (filtered.isEmpty()) item { EmptyState("No packages", "Nothing matches these filters.", Icons.Outlined.SearchOff) }
        items(filtered, key = { it.packageName }) { a ->
            PackageRow(a, modifier = Modifier.testTag("pkg_${a.packageName}"), onClick = {
                focus.clearFocus()
                nav.go(Routes.app(a.packageName))
            })
        }
    }
}

@Composable
fun AppDetailScreen(vm: AppViewModel, nav: Nav, pkg: String) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app: AppPackage? = scan.apps.firstOrNull { it.packageName == pkg }
    if (app == null) {
        ScreenScaffold(pkg, nav, "app") { item { Banner("This package is not installed.", BannerKind.INFO) } }
        return
    }
    val c = app.classification
    fun review(type: ChangeType) {
        vm.review(vm.planFor(app, type))
        nav.go(Routes.REVIEW)
    }
    ScreenScaffold(app.label, nav, "app", subtitle = app.packageName) {
        item {
            PruneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleLarge)
                        SelectionContainer { Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RiskBadge(app.risk)
                            StatusBadge(app.status)
                            Pill(c.sourceLabel, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        c.lockedReason?.let { r ->
            item {
                PruneCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row {
                        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Protected", style = MaterialTheme.typography.titleSmall)
                            Text(r, style = MaterialTheme.typography.bodyMedium)
                            Text("Prune will not disable or remove this package.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { SectionHeader("What it is") }
        item {
            PruneCard {
                Text(c.description, style = MaterialTheme.typography.bodyMedium)
                c.notes.forEach { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (c.communityTier != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Community rating (UAD-ng): ${c.communityTier}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (c.warnings.isNotEmpty()) {
            item { SectionHeader("Before you change it") }
            c.warnings.forEach { w ->
                item {
                    PruneCard {
                        Row {
                            Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(w.title, style = MaterialTheme.typography.titleSmall)
                                Text(w.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        item { SectionHeader("Details") }
        item {
            PruneCard {
                KeyValue("Category", c.sourceLabel)
                app.facts.installer?.let { KeyValue("Installed by", it) }
                app.duplicateGroup?.let { KeyValue("Same job as", "Other ${it.lowercase()} on this phone") }
                if (c.regional) KeyValue("Variant", "Region-specific")
                KeyValue("Status", app.status.label)
                KeyValue("Risk", "${app.risk.label} — ${app.risk.meaning}")
                KeyValue("Recommended", c.recommendedAction.label)
                KeyValue("Debloat level", c.level?.let { if (it == 4) "Custom only" else "Level $it and above" } ?: "Not part of any level")
                KeyValue("System app", if (app.facts.isSystem) (if (app.facts.isUpdatedSystem) "Yes (updated)" else "Yes") else "No")
                app.facts.versionName?.let { KeyValue("Version", it) }
                app.facts.sharedUserId?.let { KeyValue("Shared user", it) }
                if (scan.usage.isNotEmpty()) KeyValue("Last opened", daysAgo(app.lastUsed))
                if (c.dependencies.isNotEmpty()) KeyValue("Depends on", c.dependencies.joinToString("\n"))
                if (c.neededBy.isNotEmpty()) KeyValue("Needed by", c.neededBy.joinToString("\n"))
            }
        }
        c.consequences?.let { text ->
            item { SectionHeader("Possible consequences") }
            item { PruneCard { Text(text, style = MaterialTheme.typography.bodyMedium) } }
        }
        if (app.facts.isSystem || c.source == Source.PRELOAD) {
            item { SectionHeader("How to bring it back") }
            item { PruneCard { Text(c.recovery, style = MaterialTheme.typography.bodySmall) } }
        }
        item { SectionHeader("Actions") }
        item {
            PruneCard {
                if (!app.isLocked && app.facts.isSystem || (!app.isLocked && c.source == Source.PRELOAD)) {
                    when (app.status) {
                        PackageStatus.ENABLED -> {
                            Button(onClick = { review(ChangeType.DISABLE) }, modifier = Modifier.fillMaxWidth().testTag("action_disable")) { Text("Disable…") }
                            if (app.facts.isSystem) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = { review(ChangeType.UNINSTALL_USER) }, modifier = Modifier.fillMaxWidth()) { Text("Uninstall for this user…") }
                            }
                        }
                        PackageStatus.DISABLED -> {
                            Button(onClick = { review(ChangeType.ENABLE) }, modifier = Modifier.fillMaxWidth().testTag("action_enable")) { Text("Enable…") }
                            if (app.facts.isSystem) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = { review(ChangeType.UNINSTALL_USER) }, modifier = Modifier.fillMaxWidth()) { Text("Uninstall for this user…") }
                            }
                        }
                        PackageStatus.REMOVED -> Button(onClick = { review(ChangeType.ENABLE) }, modifier = Modifier.fillMaxWidth().testTag("action_restore")) { Text("Reinstall and enable…") }
                    }
                    if (!access.privileged) {
                        Spacer(Modifier.height(6.dp))
                        Text("Needs Shizuku or root to apply, or export the change as an ADB script on the next screen.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (!app.facts.isSystem && app.facts.installedForUser) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Uninstall app (system dialog)") }
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open in Android Settings") }
            }
        }
        item { Spacer(Modifier.size(8.dp).padding(4.dp)) }
    }
}
