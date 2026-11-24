package io.github.cyb3rl00m.prune.core.oem

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Everything Prune knows about the device's identity: Build fields plus system properties.
 * Pure data, so manufacturer detection can be unit tested with mock devices.
 */
data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val fingerprint: String,
    val sdkInt: Int,
    val release: String,
    val display: String,
    /** System properties, as `getprop` prints them. */
    val props: Map<String, String> = emptyMap(),
) {
    val manufacturerKey: String get() = manufacturer.trim().lowercase()
    val brandKey: String get() = brand.trim().lowercase()

    fun prop(name: String): String? = props[name]?.trim()?.takeIf { it.isNotEmpty() }

    /** Android TV / Google TV device (TV boxes, streamers, TVs). */
    val isTv: Boolean
        get() = prop("ro.build.characteristics")?.split(',')?.any { it.trim() == "tv" } == true

    /** True on the Android emulator (no OEM firmware, used for Prune's automated tests). */
    val isEmulator: Boolean
        get() = prop("ro.kernel.qemu") == "1" || prop("ro.boot.qemu") == "1" ||
            fingerprint.startsWith("generic") || fingerprint.contains("/sdk_g") ||
            model.startsWith("sdk_g") || product.startsWith("sdk_g") || model.contains("Emulator")

    companion object {
        /** Reads Build and all system properties with one `getprop` call (no hidden APIs). */
        fun read(): DeviceIdentity = DeviceIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            display = Build.DISPLAY.orEmpty(),
            props = readProps() + synthetic(),
        )

        /** Values only reachable through manufacturer classes, exposed as properties so detection stays pure. */
        private fun synthetic(): Map<String, String> = buildMap {
            runCatching { Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null) }
                .getOrNull()?.let { put(SamsungIntegration.SEM_PLATFORM_PROP, it.toString()) }
            runCatching {
                Class.forName("com.huawei.system.BuildEx").getMethod("getOsBrand").invoke(null) as? String
            }.getOrNull()?.let { put(HuaweiIntegration.HARMONY_BRAND_PROP, it) }
        }

        private val PROP_LINE = Regex("""^\[([^\]]+)]: \[(.*)]$""")

        fun parseGetprop(output: String): Map<String, String> = output.lineSequence()
            .mapNotNull { PROP_LINE.find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }

        private fun readProps(): Map<String, String> = runCatching {
            val p = ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
            parseGetprop(out)
        }.getOrDefault(emptyMap())
    }
}
