package io.github.cyb3rl00m.prune.core.optimize

import android.Manifest
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.os.storage.StorageManager
import android.provider.Settings
import io.github.cyb3rl00m.prune.core.model.AppPackage
import io.github.cyb3rl00m.prune.core.model.ChangeType
import io.github.cyb3rl00m.prune.core.model.Source
import io.github.cyb3rl00m.prune.core.plan.PlannedChange
import io.github.cyb3rl00m.prune.core.scan.AppUsage
import io.github.cyb3rl00m.prune.core.shell.Commands
import io.github.cyb3rl00m.prune.core.shell.OutputParsers
import io.github.cyb3rl00m.prune.core.shell.PrivilegedShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The optimization sections, each with the technical reason it works. */
enum class OptimizationSection(val title: String, val why: String, val requirement: String) {
    ANIMATIONS(
        "Animation speed",
        "Android plays a short animation for every window, transition and UI change. The three animation scales " +
            "(Developer options) multiply their duration. 0.5× halves the time you wait for each transition. It makes " +
            "the phone feel faster; it does not make the processor faster.",
        "Shizuku, root, or ADB (WRITE_SECURE_SETTINGS)"),
    BACKGROUND(
        "Background activity",
        "Apps can run jobs, alarms and services in the background even when you never open them. Setting the " +
            "RUN_ANY_IN_BACKGROUND app-op to \"ignore\" is the same switch as Android's \"Restricted\" battery setting: " +
            "the app can no longer run in the background, but works normally when you open it. Only apps you haven't " +
            "opened for 30 days are suggested.",
        "Shizuku or root, plus usage access"),
    BATTERY_EXEMPTIONS(
        "Battery optimization exemptions",
        "Apps on the battery-optimization exemption list (\"Unrestricted\") ignore Doze and App Standby, so they can " +
            "wake the phone at any time. Removing an exemption you don't need lets Android batch their work while the " +
            "phone is idle. Messaging, alarm and fitness apps may need the exemption: keep those.",
        "Shizuku or root (or change each app in Settings)"),
    PERMISSIONS(
        "Sensitive permissions",
        "Location, microphone, camera, contacts, SMS and call log access are the permissions with the biggest privacy " +
            "impact, and background location also costs battery. Revoking a permission is instant and reversible: the " +
            "app asks again when it needs it.",
        "Shizuku or root (or change each app in Settings)"),
    NOTIFICATIONS(
        "Notifications",
        "Since Android 13 posting notifications is a runtime permission (POST_NOTIFICATIONS). Revoking it for apps that " +
            "only send promotions silences them completely. Every notification also wakes the screen and the app, so fewer " +
            "notifications means fewer wake-ups.",
        "Android 13+. Shizuku or root (or change each app in Settings)"),
    STORAGE(
        "Storage and cache",
        "Apps keep cache files to load faster. The cache is rebuilt when needed, so clearing it is safe, but the apps " +
            "will be a bit slower the first time and re-download thumbnails. Prune asks Android to trim caches the same " +
            "way it does when storage runs low (pm trim-caches) and measures the space actually freed.",
        "Usage access to see sizes; Shizuku or root to clear all caches at once"),
    RESOURCES(
        "Resource usage",
        "Shows which apps you use most (screen time, last 7 days) and the battery estimate Android itself calculates " +
            "(dumpsys batterystats, since the last full charge). Use it to find apps that cost more than they are worth.",
        "Usage access; battery estimates need Shizuku or root"),
    COMPILATION(
        "App compilation",
        "After app updates Android runs apps partly interpreted until its idle maintenance compiles them during the " +
            "night (while charging). Running the same background compilation job now (cmd package bg-dexopt-job) " +
            "compiles the hot code paths of every app with its usage profile. Takes several minutes and uses battery.",
        "Shizuku or root"),
}

data class AnimationState(val window: Float, val transition: Float, val animator: Float) {
    val uniform: Float? get() = if (window == transition && transition == animator) window else null
}

data class PermissionInfoItem(val permission: String, val label: String, val group: String)

data class AppPermissions(val app: AppPackage, val granted: List<PermissionInfoItem>)

data class AppStorage(val app: AppPackage, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
    val total get() = appBytes + dataBytes + cacheBytes
}

data class StorageSummary(val totalBytes: Long, val freeBytes: Long, val apps: List<AppStorage>) {
    val cacheBytes get() = apps.sumOf { it.cacheBytes }
}

data class ResourceItem(val app: AppPackage, val foregroundMs7d: Long, val batteryMah: Double?)

data class Recommendation(val section: OptimizationSection, val title: String, val detail: String)

class Optimizer(private val context: Context) {

    private val pm = context.packageManager
    private val user = Process.myUid() / 100_000

    fun animationState(): AnimationState {
        val cr = context.contentResolver
        fun f(key: String) = runCatching { Settings.Global.getFloat(cr, key, 1f) }.getOrDefault(1f)
        return AnimationState(f(KEY_WINDOW), f(KEY_TRANSITION), f(KEY_ANIMATOR))
    }

    fun animationChanges(scale: Float): List<PlannedChange> {
        val value = if (scale == 0f) "0" else scale.toString()
        return listOf(KEY_WINDOW to "Window animation scale", KEY_TRANSITION to "Transition animation scale",
            KEY_ANIMATOR to "Animator duration scale").map { (key, label) ->
            PlannedChange(
                type = ChangeType.SETTING_PUT, label = "$label → ${if (scale == 0f) "off" else "${value}×"}",
                params = mapOf("key" to key, "value" to value),
                explanation = OptimizationSection.ANIMATIONS.why + if (scale == 0f)
                    "\n\nOff removes animations completely. Some apps rely on animations to show progress; 0.5× is the usual choice." else "",
            )
        }
    }

    /** Apps not opened for [days] days that may still run in the background. Needs usage stats and a shell. */
    suspend fun backgroundCandidates(apps: List<AppPackage>, usage: Map<String, AppUsage>, shell: PrivilegedShell?,
                                     days: Int = 30): List<AppPackage> = withContext(Dispatchers.IO) {
        if (usage.isEmpty()) return@withContext emptyList()
        val restricted = shell?.let {
            runCatching { OutputParsers.appOpQueryPackages(it.run(Commands.appOpQuery("RUN_ANY_IN_BACKGROUND", "ignore", user)).stdout) }
                .getOrNull()
        } ?: emptySet()
        val cutoff = System.currentTimeMillis() - days * DAY
        apps.filter { a ->
            !a.facts.isSystem && !a.isLocked && a.facts.installedForUser && a.facts.enabled &&
                a.classification.warnings.none { it.groupKey == "IN_USE" } &&
                a.packageName !in restricted &&
                (usage[a.packageName]?.lastUsed ?: 0L) < cutoff &&
                installedBefore(a.packageName, cutoff)
        }.sortedBy { it.label.lowercase() }
    }

    fun backgroundChange(app: AppPackage, restrict: Boolean) = PlannedChange(
        type = ChangeType.APPOP_SET, packageName = app.packageName,
        label = (if (restrict) "Restrict background: " else "Allow background: ") + app.label,
        params = mapOf("op" to "RUN_ANY_IN_BACKGROUND", "mode" to if (restrict) "ignore" else "default"),
        explanation = OptimizationSection.BACKGROUND.why, isSystemPackage = app.facts.isSystem,
    )

    data class Exemptions(val removable: List<AppPackage>, val systemManaged: List<AppPackage>)

    suspend fun batteryExemptions(apps: List<AppPackage>, shell: PrivilegedShell?): Exemptions = withContext(Dispatchers.IO) {
        val power = context.getSystemService(PowerManager::class.java)
        val exempt = apps.filter { it.facts.installedForUser && runCatching { power.isIgnoringBatteryOptimizations(it.packageName) }.getOrDefault(false) }
        val list = shell?.let { runCatching { OutputParsers.deviceIdleWhitelist(it.run(Commands.deviceIdleList()).stdout) }.getOrNull() }
        if (list == null) {
            Exemptions(exempt.filter { !it.facts.isSystem }, exempt.filter { it.facts.isSystem })
        } else {
            Exemptions(exempt.filter { it.packageName in list.user && it.packageName != context.packageName },
                exempt.filter { it.packageName !in list.user })
        }
    }

    fun exemptionChange(app: AppPackage) = PlannedChange(
        type = ChangeType.DEVICEIDLE_REMOVE, packageName = app.packageName, label = "Remove battery exemption: ${app.label}",
        explanation = OptimizationSection.BATTERY_EXEMPTIONS.why, isSystemPackage = app.facts.isSystem,
    )

    @Suppress("DEPRECATION")
    suspend fun permissions(apps: List<AppPackage>, includeSystem: Boolean): List<AppPermissions> = withContext(Dispatchers.IO) {
        apps.filter { (includeSystem || !it.facts.isSystem) && it.facts.installedForUser && it.packageName != context.packageName }
            .mapNotNull { app ->
                val info: PackageInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(app.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                    else pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                }.getOrNull() ?: return@mapNotNull null
                val req = info.requestedPermissions ?: return@mapNotNull null
                val flags = info.requestedPermissionsFlags ?: return@mapNotNull null
                val granted = req.indices.filter { (flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
                    .mapNotNull { SENSITIVE[req[it]] }
                    .filter { it.permission != Manifest.permission.POST_NOTIFICATIONS }
                if (granted.isEmpty()) null else AppPermissions(app, granted)
            }.sortedByDescending { it.granted.size }
    }

    @Suppress("DEPRECATION")
    suspend fun notificationApps(apps: List<AppPackage>, includeSystem: Boolean): List<AppPackage> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 33) return@withContext emptyList()
        apps.filter { (includeSystem || !it.facts.isSystem) && it.facts.installedForUser && it.facts.enabled &&
            it.packageName != context.packageName &&
            pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS, it.packageName) == PackageManager.PERMISSION_GRANTED
        }.sortedBy { it.label.lowercase() }
    }

    fun revokeChange(app: AppPackage, perm: PermissionInfoItem) = PlannedChange(
        type = ChangeType.PERMISSION_REVOKE, packageName = app.packageName,
        label = "Revoke ${perm.label}: ${app.label}", params = mapOf("permission" to perm.permission),
        explanation = OptimizationSection.PERMISSIONS.why, isSystemPackage = app.facts.isSystem,
    )

    fun notificationChange(app: AppPackage) = revokeChange(app, PermissionInfoItem(Manifest.permission.POST_NOTIFICATIONS, "notifications", "Notifications"))
        .copy(explanation = OptimizationSection.NOTIFICATIONS.why)

    suspend fun storage(apps: List<AppPackage>): StorageSummary? = withContext(Dispatchers.IO) {
        val ssm = context.getSystemService(StorageStatsManager::class.java) ?: return@withContext null
        val uuid = StorageManager.UUID_DEFAULT
        val total = runCatching { ssm.getTotalBytes(uuid) }.getOrDefault(0L)
        val free = runCatching { ssm.getFreeBytes(uuid) }.getOrDefault(0L)
        val handle: UserHandle = Process.myUserHandle()
        val list = apps.filter { it.facts.installedForUser }.mapNotNull { app ->
            runCatching {
                val s = ssm.queryStatsForPackage(uuid, app.packageName, handle)
                AppStorage(app, s.appBytes, s.dataBytes - s.cacheBytes, s.cacheBytes)
            }.getOrNull()
        }
        StorageSummary(total, free, list.sortedByDescending { it.total })
    }

    fun trimCachesChange() = PlannedChange(type = ChangeType.TRIM_CACHES, label = "Clear cached files of all apps",
        explanation = OptimizationSection.STORAGE.why)

    fun dexoptChange() = PlannedChange(type = ChangeType.DEXOPT, label = "Compile apps now (background dexopt)",
        explanation = OptimizationSection.COMPILATION.why)

    suspend fun resources(apps: List<AppPackage>, usage: Map<String, AppUsage>, shell: PrivilegedShell?): List<ResourceItem> =
        withContext(Dispatchers.IO) {
            val battery = shell?.let {
                runCatching { OutputParsers.batteryUsePerUid(it.run(Commands.batteryStats(), 120_000).stdout) }.getOrNull()
            }.orEmpty()
            val byUid = apps.groupBy { it.facts.uid }
            val items = apps.filter { it.facts.installedForUser }.map { app ->
                val shared = byUid[app.facts.uid]?.size ?: 1
                // Apps that share a uid share one battery figure: show it only when the uid has a single package.
                ResourceItem(app, usage[app.packageName]?.foregroundMs7d ?: 0L, battery[app.facts.uid]?.takeIf { shared == 1 })
            }
            items.filter { it.foregroundMs7d > 0 || (it.batteryMah ?: 0.0) > 0.0 }
                .sortedWith(compareByDescending<ResourceItem> { it.batteryMah ?: 0.0 }.thenByDescending { it.foregroundMs7d })
        }

    fun recommendations(apps: List<AppPackage>, usage: Map<String, AppUsage>, privileged: Boolean): List<Recommendation> {
        val out = mutableListOf<Recommendation>()
        val anim = animationState()
        if (anim.window >= 1f && anim.transition >= 1f && anim.animator >= 1f) {
            out += Recommendation(OptimizationSection.ANIMATIONS, "Animations at 1×",
                "Setting them to 0.5× halves transition time. ${if (privileged) "One tap." else "Needs Shizuku or ADB."}")
        }
        if (usage.isEmpty()) {
            out += Recommendation(OptimizationSection.RESOURCES, "Grant usage access",
                "Lets Prune find apps you don't use and show storage per app.")
        } else {
            val cutoff = System.currentTimeMillis() - 30 * DAY
            val unused = apps.count { !it.facts.isSystem && it.facts.enabled && (usage[it.packageName]?.lastUsed ?: 0L) < cutoff &&
                installedBefore(it.packageName, cutoff) }
            if (unused > 0) out += Recommendation(OptimizationSection.BACKGROUND, "$unused apps unused for 30+ days",
                "Restrict their background activity or uninstall them.")
        }
        val power = context.getSystemService(PowerManager::class.java)
        val exempt = apps.count { !it.facts.isSystem && it.packageName != context.packageName &&
            runCatching { power.isIgnoringBatteryOptimizations(it.packageName) }.getOrDefault(false) }
        if (exempt > 0) out += Recommendation(OptimizationSection.BATTERY_EXEMPTIONS, "$exempt apps ignore battery optimization",
            "Review which of them really need to run unrestricted.")
        return out
    }

    @Suppress("DEPRECATION")
    private fun installedBefore(pkg: String, time: Long): Boolean = runCatching {
        pm.getPackageInfo(pkg, 0).firstInstallTime < time
    }.getOrDefault(true)

    fun sourceIsUser(app: AppPackage) = app.classification.source == Source.USER

    companion object {
        const val KEY_WINDOW = "window_animation_scale"
        const val KEY_TRANSITION = "transition_animation_scale"
        const val KEY_ANIMATOR = "animator_duration_scale"
        private const val DAY = 24L * 60 * 60 * 1000

        val SENSITIVE: Map<String, PermissionInfoItem> = listOf(
            PermissionInfoItem(Manifest.permission.ACCESS_FINE_LOCATION, "precise location", "Location"),
            PermissionInfoItem(Manifest.permission.ACCESS_COARSE_LOCATION, "approximate location", "Location"),
            PermissionInfoItem(Manifest.permission.ACCESS_BACKGROUND_LOCATION, "background location", "Location"),
            PermissionInfoItem(Manifest.permission.CAMERA, "camera", "Camera"),
            PermissionInfoItem(Manifest.permission.RECORD_AUDIO, "microphone", "Microphone"),
            PermissionInfoItem(Manifest.permission.READ_CONTACTS, "contacts", "Contacts"),
            PermissionInfoItem(Manifest.permission.WRITE_CONTACTS, "edit contacts", "Contacts"),
            PermissionInfoItem(Manifest.permission.READ_CALENDAR, "calendar", "Calendar"),
            PermissionInfoItem(Manifest.permission.READ_SMS, "read SMS", "SMS"),
            PermissionInfoItem(Manifest.permission.SEND_SMS, "send SMS", "SMS"),
            PermissionInfoItem(Manifest.permission.RECEIVE_SMS, "receive SMS", "SMS"),
            PermissionInfoItem(Manifest.permission.READ_CALL_LOG, "call log", "Call log"),
            PermissionInfoItem(Manifest.permission.CALL_PHONE, "make calls", "Phone"),
            PermissionInfoItem(Manifest.permission.READ_PHONE_STATE, "phone state", "Phone"),
            PermissionInfoItem(Manifest.permission.BODY_SENSORS, "body sensors", "Body sensors"),
            PermissionInfoItem(Manifest.permission.ACTIVITY_RECOGNITION, "physical activity", "Physical activity"),
            PermissionInfoItem("android.permission.READ_MEDIA_IMAGES", "photos", "Photos and videos"),
            PermissionInfoItem("android.permission.READ_MEDIA_VIDEO", "videos", "Photos and videos"),
            PermissionInfoItem("android.permission.READ_MEDIA_AUDIO", "music and audio", "Music and audio"),
            PermissionInfoItem(Manifest.permission.READ_EXTERNAL_STORAGE, "files and media", "Files"),
            PermissionInfoItem("android.permission.BLUETOOTH_SCAN", "nearby devices", "Nearby devices"),
            PermissionInfoItem("android.permission.NEARBY_WIFI_DEVICES", "nearby Wi-Fi devices", "Nearby devices"),
            PermissionInfoItem(Manifest.permission.POST_NOTIFICATIONS, "notifications", "Notifications"),
        ).associateBy { it.permission }
    }
}
