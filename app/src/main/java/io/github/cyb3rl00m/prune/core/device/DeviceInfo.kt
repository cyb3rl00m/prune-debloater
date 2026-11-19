package io.github.cyb3rl00m.prune.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val marketName: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int,
    val oneUiVersion: String?,
    val buildNumber: String,
    val securityPatch: String,
    val cscCode: String?,
    val cscCountry: String?,
    val carrier: String?,
    val isSamsung: Boolean,
    val totalRamBytes: Long,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long,
    val userId: Int,
) {
    val displayName: String get() = if (marketName != model) "$marketName ($model)" else model
}

object DeviceInfoReader {

    fun read(context: Context): DeviceInfo {
        val props = SystemProps.getAll(
            listOf(
                "ro.build.version.oneui", "ro.csc.sales_code", "ro.boot.sales_code", "ro.csc.country_code",
                "ro.product.marketname", "ro.product.vendor.marketname", "ro.product.system.marketname",
            )
        )
        val oneUi = OneUi.fromProperty(props["ro.build.version.oneui"]) ?: OneUi.fromSemPlatformInt(semPlatformInt())
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val tm = context.getSystemService(TelephonyManager::class.java)
        val carrier = runCatching { tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: tm?.networkOperatorName }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val marketProp = listOf("ro.product.marketname", "ro.product.vendor.marketname", "ro.product.system.marketname")
            .firstNotNullOfOrNull { props[it]?.takeIf { v -> v.isNotBlank() } }
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            marketName = marketProp ?: SamsungModels.marketName(Build.MODEL) ?: Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            oneUiVersion = if (isSamsung) oneUi else null,
            buildNumber = Build.DISPLAY,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            cscCode = (props["ro.csc.sales_code"] ?: props["ro.boot.sales_code"])?.takeIf { it.isNotBlank() },
            cscCountry = props["ro.csc.country_code"]?.takeIf { it.isNotBlank() },
            carrier = carrier,
            isSamsung = isSamsung,
            totalRamBytes = mem.totalMem,
            storageTotalBytes = stat?.totalBytes ?: 0,
            storageFreeBytes = stat?.availableBytes ?: 0,
            userId = android.os.Process.myUid() / 100_000,
        )
    }

    /** Samsung adds Build.VERSION.SEM_PLATFORM_INT to its firmware. Not present on other phones. */
    private fun semPlatformInt(): Int? = runCatching {
        Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
    }.getOrNull()
}

/** One UI version decoding. Pure functions, unit tested. */
object OneUi {
    /** ro.build.version.oneui = MMmmpp, e.g. 60101 = One UI 6.1.1, 80000 = One UI 8.0. */
    fun fromProperty(raw: String?): String? {
        val v = raw?.trim()?.toIntOrNull() ?: return null
        if (v < 10000) return null
        val major = v / 10000
        val minor = (v / 100) % 100
        val patch = v % 100
        return if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
    }

    /** SEM_PLATFORM_INT: 100000 = One UI 1.0, 110500 = 2.5, 130000 = 4.0 (One UI = (x - 90000) / 10000). */
    fun fromSemPlatformInt(sem: Int?): String? {
        if (sem == null || sem < 100000) return null
        val v = sem - 90000
        val major = v / 10000
        val minor = (v % 10000) / 100
        return "$major.$minor"
    }
}

/** Reads system properties with the public `getprop` tool (no hidden APIs). */
object SystemProps {
    fun getAll(names: List<String>): Map<String, String> = names.associateWith { get(it) ?: "" }
        .filterValues { it.isNotEmpty() }

    fun get(name: String): String? = runCatching {
        val p = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
        out.ifEmpty { null }
    }.getOrNull()
}

/** Marketing names for model numbers whose firmware does not report one. */
object SamsungModels {
    private val names = linkedMapOf(
        "SM-S731" to "Galaxy S25 FE", "SM-S937" to "Galaxy S25 Edge", "SM-S938" to "Galaxy S25 Ultra",
        "SM-S936" to "Galaxy S25+", "SM-S931" to "Galaxy S25", "SM-S721" to "Galaxy S24 FE",
        "SM-S928" to "Galaxy S24 Ultra", "SM-S926" to "Galaxy S24+", "SM-S921" to "Galaxy S24",
        "SM-S711" to "Galaxy S23 FE", "SM-S918" to "Galaxy S23 Ultra", "SM-S916" to "Galaxy S23+",
        "SM-S911" to "Galaxy S23", "SM-S908" to "Galaxy S22 Ultra", "SM-S906" to "Galaxy S22+",
        "SM-S901" to "Galaxy S22", "SM-G990" to "Galaxy S21 FE", "SM-F966" to "Galaxy Z Fold7",
        "SM-F766" to "Galaxy Z Flip7", "SM-F761" to "Galaxy Z Flip7 FE", "SM-F956" to "Galaxy Z Fold6",
        "SM-F741" to "Galaxy Z Flip6", "SM-F946" to "Galaxy Z Fold5", "SM-F731" to "Galaxy Z Flip5",
        "SM-A576" to "Galaxy A57", "SM-A566" to "Galaxy A56", "SM-A556" to "Galaxy A55", "SM-A546" to "Galaxy A54",
        "SM-A376" to "Galaxy A37", "SM-A366" to "Galaxy A36", "SM-A356" to "Galaxy A35", "SM-A346" to "Galaxy A34",
        "SM-A266" to "Galaxy A26", "SM-A256" to "Galaxy A25", "SM-A176" to "Galaxy A17", "SM-A166" to "Galaxy A16",
        "SM-A165" to "Galaxy A16", "SM-M366" to "Galaxy M36", "SM-M566" to "Galaxy M56", "SM-X" to "Galaxy Tab",
    )

    fun marketName(model: String): String? =
        names.entries.firstOrNull { model.uppercase().startsWith(it.key) }?.value
}
