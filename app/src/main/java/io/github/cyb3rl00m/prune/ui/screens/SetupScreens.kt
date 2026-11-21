package io.github.cyb3rl00m.prune.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cyb3rl00m.prune.BuildConfig
import io.github.cyb3rl00m.prune.core.model.RemovalMethod
import io.github.cyb3rl00m.prune.core.settings.ThemeMode
import io.github.cyb3rl00m.prune.ui.AppViewModel
import io.github.cyb3rl00m.prune.ui.Nav
import io.github.cyb3rl00m.prune.ui.Routes
import io.github.cyb3rl00m.prune.ui.components.Banner
import io.github.cyb3rl00m.prune.ui.components.BannerKind
import io.github.cyb3rl00m.prune.ui.components.KeyValue
import io.github.cyb3rl00m.prune.ui.components.PruneCard
import io.github.cyb3rl00m.prune.ui.components.SectionHeader
import io.github.cyb3rl00m.prune.ui.theme.MonoStyle

@Composable
private fun StatusLine(done: Boolean, title: String, detail: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SetupScreen(vm: AppViewModel, nav: Nav) {
    val access by vm.access.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun open(intent: Intent) = try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {
        vm.say("Could not open that screen on this phone.")
    }

    ScreenScaffold("Access setup", nav, "setup", subtitle = "What Prune can do depends on the access you give it") {
        item {
            PruneCard {
                Text("Three permission levels", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                StatusLine(true, "1 · Standard (always)", "Scan every package, explain it, show usage, open app settings, uninstall apps you installed.")
                StatusLine(access.active.name == "SHIZUKU" || access.rootGranted,
                    "2 · ADB level (Shizuku, no root)",
                    "Disable, uninstall-for-user and restore system packages, background restrictions, permissions, battery exemptions, animations, cache trimming.")
                StatusLine(access.rootGranted, "3 · Root (optional)",
                    "The same operations through su. Prune does not need root for anything; it is only an alternative to Shizuku.")
            }
        }
        item { SectionHeader("Shizuku (recommended)") }
        item {
            PruneCard {
                StatusLine(access.shizukuInstalled, "Shizuku installed", "Free, open-source app by RikkaApps. Lets apps use ADB-level commands without a computer after setup.")
                StatusLine(access.shizukuRunning, "Shizuku running", if (access.shizukuRunning) "Running as ${if (access.shizukuUid == 0) "root" else "ADB (shell)"}." else "Start it in the Shizuku app (see steps below). It must be started again after every reboot.")
                StatusLine(access.shizukuPermission, "Prune authorized", "Shizuku asks you once to allow Prune.")
                if (access.shizukuTooOld) Banner("This Shizuku version is too old. Update Shizuku.", BannerKind.ERROR)
                Spacer(Modifier.height(8.dp))
                when {
                    !access.shizukuInstalled -> Button(onClick = {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")).let {
                            if (it.resolveActivity(context.packageManager) != null) it
                            else Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
                        })
                    }, modifier = Modifier.fillMaxWidth()) { Text("Get Shizuku") }
                    !access.shizukuRunning -> Button(onClick = {
                        context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { open(it) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open Shizuku") }
                    !access.shizukuPermission -> Button(onClick = { vm.requestShizuku() }, modifier = Modifier.fillMaxWidth().testTag("authorize_shizuku")) {
                        Text("Authorize Prune in Shizuku")
                    }
                    else -> OutlinedButton(onClick = { vm.refreshAccess() }, modifier = Modifier.fillMaxWidth()) { Text("Shizuku is ready · Refresh") }
                }
            }
        }
        item {
            PruneCard {
                Text("Start Shizuku on ${device?.brandName ?: "this phone"} (no computer needed)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val integration = vm.repo.integration
                val identity = vm.repo.identity
                (integration.developerOptionsSteps + listOf(
                    "Connect to Wi-Fi. In Developer options turn on Wireless debugging (Android 11+; on older versions use the computer method below).",
                    "Open Shizuku → Start via Wireless debugging → Pairing. Shizuku shows a notification.",
                    "In Wireless debugging, tap Pair device with pairing code. Type the code into Shizuku's notification.",
                    "In Shizuku tap Start. Then come back here and tap Authorize Prune.",
                    "After a reboot, open Shizuku and tap Start again (pairing is remembered).",
                ) + runCatching { integration.extraSetupSteps(identity) }.getOrDefault(emptyList())).forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Developer options")
                }
                Spacer(Modifier.height(8.dp))
                Text("With a computer instead: enable USB debugging, connect the phone and run the command Shizuku shows under \"Start by connecting to a computer\", usually:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text("adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh", style = MonoStyle, color = MaterialTheme.colorScheme.primary) }
            }
        }
        item { SectionHeader("Extra permissions") }
        item {
            PruneCard {
                StatusLine(access.usageAccess, "Usage access", "Finds apps you don't use, shows screen time and per-app storage. Nothing leaves the phone.")
                StatusLine(access.writeSecureSettings, "Write secure settings", "Lets Prune change animation scales directly.")
                Spacer(Modifier.height(8.dp))
                if (access.privileged && (!access.usageAccess || !access.writeSecureSettings)) {
                    Button(onClick = { vm.grantSelfPermissions() }, modifier = Modifier.fillMaxWidth().testTag("grant_self")) { Text("Grant both with ${access.active.label}") }
                } else if (!access.usageAccess) {
                    Button(onClick = {
                        open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")).let {
                            if (it.resolveActivity(context.packageManager) != null) it else Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        })
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open usage access settings") }
                }
                if (!access.writeSecureSettings && !access.privileged) {
                    Spacer(Modifier.height(8.dp))
                    Text("Or from a computer:", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text("adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS", style = MonoStyle, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        item { SectionHeader("Root (optional)") }
        item {
            PruneCard {
                StatusLine(access.rootGranted, "Root access", "Only if the phone is rooted (Magisk, KernelSU, APatch). Rooting usually needs an unlocked bootloader, can break banking and payment apps, and on Samsung trips Knox permanently. Prune never asks you to root.")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.requestRoot() }, modifier = Modifier.fillMaxWidth()) { Text("Request root access") }
            }
        }
        item { SectionHeader("Without Shizuku or root") }
        item {
            Banner("Every plan can be exported as an ADB script on the review screen and run from a computer. Prune records it and checks the results afterwards.", BannerKind.INFO)
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, nav: Nav) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenScaffold("Settings", nav, "settings") {
        item { SectionHeader("Appearance") }
        item {
            PruneCard {
                ThemeMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.theme == m, { vm.update { setTheme(m) } })
                        Text(m.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item { SectionHeader("Debloating") }
        item {
            PruneCard {
                Text("Default method", style = MaterialTheme.typography.titleSmall)
                RemovalMethod.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(s.method == m, { vm.update { setMethod(m) } })
                        Column {
                            Text(m.label, style = MaterialTheme.typography.bodyLarge)
                            Text(m.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OptionSwitch("Fallback to uninstall-for-user", "Some firmwares (One UI, EMUI, MagicOS) refuse to disable some packages. Uninstall them for this user instead (also reversible).",
                    s.fallbackUninstall) { vm.update { setFallback(it) } }
                OptionSwitch("Skip recently used apps", "Don't pre-select apps opened in the last ${s.skipUsedWithinDays} days.",
                    s.skipUsedWithinDays > 0) { vm.update { setSkipDays(if (it) 14 else 0) } }
                OptionSwitch("Prefer root over Shizuku", "Only matters when both are available.", s.preferRoot) { vm.update { setPreferRoot(it) } }
            }
        }
        item { SectionHeader("About") }
        item {
            PruneCard {
                KeyValue("Version", BuildConfig.VERSION_NAME)
                KeyValue("Package database", "${vm.repo.database.updated} · ${vm.repo.database.uad.size} community entries · ${vm.repo.database.curatedCount} curated in ${vm.repo.database.files.size} rule files")
                KeyValue("License", "GPL-3.0-or-later")
                Spacer(Modifier.height(8.dp))
                Text("Package descriptions come from the Universal Android Debloater Next Generation community list (GPL-3.0). Prune's own safety rules always override it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cyb3rl00m/prune-debloater"))) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Source code on GitHub") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { nav.go(Routes.SETUP) }, modifier = Modifier.fillMaxWidth()) { Text("Access setup") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { nav.go(Routes.DEVICE) }, modifier = Modifier.fillMaxWidth()) { Text("Device information") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { nav.go(Routes.COMPAT) }, modifier = Modifier.fillMaxWidth().testTag("open_compat")) { Text("Compatibility report") }
            }
        }
        item {
            Text("Prune is not affiliated with any phone manufacturer or with Google. Brand and product names are trademarks of their owners.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
fun DeviceScreen(vm: AppViewModel, nav: Nav) {
    val d by vm.device.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    ScreenScaffold("Device information", nav, "device") {
        val dev = d
        if (dev == null) {
            item { Text("Reading…") }
            return@ScreenScaffold
        }
        item {
            PruneCard {
                KeyValue("Model", dev.fullName)
                KeyValue("Model number", dev.model)
                KeyValue("Device code", dev.device)
                KeyValue("Manufacturer", dev.manufacturer)
                KeyValue("Brand", dev.brand)
                KeyValue("Android", "${dev.androidVersion} (API ${dev.sdkInt})")
                KeyValue("Firmware", dev.firmware?.toString() ?: "Not detected")
                KeyValue("Integration", "${dev.integration.title} · ${dev.compat.label}")
                KeyValue("Google services", if (dev.services.gms) "Installed" else "Not available")
                KeyValue("Build", dev.buildNumber)
                KeyValue("Security patch", dev.securityPatch)
                KeyValue("CSC (region/carrier code)", listOfNotNull(dev.cscCode, dev.cscCountry).joinToString(" · ").ifEmpty { "Unknown" })
                KeyValue("Carrier", dev.carrier ?: "No SIM / unknown")
                KeyValue("RAM", io.github.cyb3rl00m.prune.core.plan.Executor.formatBytes(dev.totalRamBytes))
                KeyValue("Storage", "${io.github.cyb3rl00m.prune.core.plan.Executor.formatBytes(dev.storageFreeBytes)} free of ${io.github.cyb3rl00m.prune.core.plan.Executor.formatBytes(dev.storageTotalBytes)}")
                KeyValue("Android user", dev.userId.toString())
            }
        }
        item {
            PruneCard {
                KeyValue("Packages", scan.apps.size.toString())
                KeyValue("Access", "${access.active.label} (${access.active.tier})")
                KeyValue("Shizuku", if (access.shizukuRunning) "Running" else if (access.shizukuInstalled) "Installed, not running" else "Not installed")
            }
        }
    }
}
