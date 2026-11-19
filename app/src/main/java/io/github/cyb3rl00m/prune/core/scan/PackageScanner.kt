package io.github.cyb3rl00m.prune.core.scan

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import io.github.cyb3rl00m.prune.core.db.DynamicProtections
import io.github.cyb3rl00m.prune.core.model.PackageFacts

/** Reads installed packages and their state for the current user through public PackageManager APIs. */
class PackageScanner(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun scan(): List<PackageFacts> {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val infos: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledPackages(flags)
        }
        val launchable = launchablePackages()
        return infos.mapNotNull { info -> toFacts(info, launchable) }.sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    fun read(packageName: String): PackageFacts? = runCatching {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else pm.getPackageInfo(packageName, flags)
        toFacts(info, null)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun toFacts(info: PackageInfo, launchable: Set<String>?): PackageFacts? {
        val ai: ApplicationInfo = info.applicationInfo ?: return null
        val installed = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
        val enabledSetting = runCatching { pm.getApplicationEnabledSetting(info.packageName) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val enabled = when (enabledSetting) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> ai.enabled
        }
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: info.packageName
        return PackageFacts(
            packageName = info.packageName,
            label = label,
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            isPersistent = (ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0,
            sharedUserId = info.sharedUserId,
            installedForUser = installed,
            enabled = enabled,
            versionName = info.versionName,
            uid = ai.uid,
            hasLauncherActivity = launchable?.contains(info.packageName) ?: false,
        )
    }

    @Suppress("DEPRECATION")
    private fun launchablePackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        list.map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** Packages this phone currently depends on: default apps, the keyboard, device admins and more. */
    @Suppress("DEPRECATION")
    fun dynamicProtections(): DynamicProtections {
        val locked = linkedMapOf<String, String>()
        val inUse = linkedMapOf<String, String>()
        fun resolve(intent: Intent): String? = runCatching {
            val ri = if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ri?.activityInfo?.packageName?.takeIf { it != "android" }
        }.getOrNull()

        resolve(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            ?.let { locked[it] = "Your current home screen (launcher)." }
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
            ?.let { locked[it] = "Your default phone app: needed for calls and emergency calls." }
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            ?.let { locked[it] = "Your default SMS app." }
        val cr = context.contentResolver
        runCatching { Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD) }.getOrNull()
            ?.substringBefore('/')?.takeIf { it.isNotBlank() }
            ?.let { locked[it] = "Your active keyboard. Without it you cannot type (or enter your PIN)." }
        runCatching { Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull()
            ?.split(':')?.map { it.substringBefore('/') }?.filter { it.isNotBlank() }
            ?.forEach { locked[it] = "Has an accessibility service you turned on." }
        runCatching { context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins }.getOrNull()
            ?.forEach { locked[it.packageName] = "Device administrator (for example work profile, Find My Mobile or MDM)." }

        resolve(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE))
            ?.let { inUse[it] = "Your default browser. Links open in it." }
        runCatching { Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS) }.getOrNull()
            ?.split(':')?.map { it.substringBefore('/') }?.filter { it.isNotBlank() && it !in locked }
            ?.forEach { inUse[it] = "An enabled keyboard on this phone." }
        runCatching { Settings.Secure.getString(cr, "enabled_notification_listeners") }.getOrNull()
            ?.split(':')?.map { it.substringBefore('/') }?.filter { it.isNotBlank() }
            ?.forEach { inUse.putIfAbsent(it, "Has notification access (for example a watch or wearable app).") }
        runCatching { android.app.WallpaperManager.getInstance(context).wallpaperInfo?.packageName }.getOrNull()
            ?.let { inUse.putIfAbsent(it, "Provides your current live wallpaper.") }

        locked[context.packageName] = "Prune itself."
        return DynamicProtections(locked = locked, inUse = inUse)
    }
}
