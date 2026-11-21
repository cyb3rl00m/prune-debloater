package io.github.cyb3rl00m.prune.core.oem

import io.github.cyb3rl00m.prune.core.device.OneUi

// Manufacturer integrations. Firmware properties that are missing simply give no version:
// detection never fails a device, it only describes it less precisely.

object SamsungIntegration : OemIntegration() {
    override val id = "samsung"
    override val title = "Samsung Galaxy (One UI)"
    override val compat = CompatLevel.PARTIAL
    override val packagePrefixes = listOf("com.samsung.", "com.sec.", "com.sem.", "com.osp.", "com.knox", "com.skms.",
        "com.wssyncmldm", "com.wsomacp")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "samsung"
    override fun firmware(d: DeviceIdentity): Firmware {
        val v = OneUi.fromProperty(d.prop("ro.build.version.oneui"))
            ?: OneUi.fromSemPlatformInt(d.prop(SEM_PLATFORM_PROP)?.toIntOrNull())
        return Firmware("One UI", v)
    }
    override fun brandName(d: DeviceIdentity) = "Samsung Galaxy"
    override val developerOptionsSteps = listOf(
        "Settings → About phone → Software information → tap Build number 7 times (enter your PIN).",
        "Developer options are at the bottom of Settings.",
    )
    override fun limitations(d: DeviceIdentity, services: ServicesInfo) = listOf(
        Limitation("Some packages refuse disabling",
            "Recent One UI versions block `pm disable-user` for some Samsung packages (\"Shell cannot change component state\"). " +
                "Prune can uninstall them for the current user instead; that is reversible too."),
        Limitation("Knox", "Rooting a Galaxy phone trips Knox permanently: Samsung Wallet, Secure Folder and Samsung Health stop working. Prune never needs root."),
    )

    /** Synthetic property: Build.VERSION.SEM_PLATFORM_INT read by reflection on Samsung firmware. */
    const val SEM_PLATFORM_PROP = "prune.sem_platform_int"
}

object GooglePixelIntegration : OemIntegration() {
    override val id = "pixel"
    override val title = "Google Pixel (stock Android)"
    override val compat = CompatLevel.PARTIAL
    override val packagePrefixes = listOf("com.google.android.apps.pixel", "com.google.pixel.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "google" && d.model.startsWith("Pixel", ignoreCase = true)
    override fun firmware(d: DeviceIdentity) = Firmware("Stock Android", d.display.takeIf { it.isNotBlank() })
    override fun brandName(d: DeviceIdentity) = "Google Pixel"
    override fun limitations(d: DeviceIdentity, services: ServicesInfo) = listOf(
        Limitation("Mostly Google apps", "Pixels have little manufacturer bloat. Most removable packages are Google apps: enable \"Include Google apps\" in a profile to see them."),
    )
}

object XiaomiIntegration : OemIntegration() {
    override val id = "xiaomi"
    override val title = "Xiaomi, Redmi, POCO (HyperOS / MIUI)"
    override val compat = CompatLevel.EXPERIMENTAL
    override val packagePrefixes = listOf("com.miui.", "com.xiaomi.", "com.mi.", "com.mipay.", "com.duokan.", "com.milink.",
        "com.lbe.security.miui", "com.facemoji.lite.xiaomi")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "xiaomi" || d.brandKey in setOf("xiaomi", "redmi", "poco")
    override fun firmware(d: DeviceIdentity): Firmware? {
        d.prop("ro.mi.os.version.name")?.let { return Firmware("HyperOS", it.removePrefix("OS")) }
        d.prop("ro.miui.ui.version.name")?.let { return Firmware("MIUI", it.removePrefix("V")) }
        return null
    }
    override fun brandName(d: DeviceIdentity) = when (d.brandKey) {
        "redmi" -> "Redmi"
        "poco" -> "POCO"
        else -> "Xiaomi"
    }
    override val developerOptionsSteps = listOf(
        "Settings → About phone → tap OS version (HyperOS) or MIUI version 7 times.",
        "Developer options are in Settings → Additional settings.",
    )
    override fun extraSetupSteps(d: DeviceIdentity) = listOf(
        "In Developer options also turn on \"USB debugging (Security settings)\". Without it Xiaomi blocks permission, background and animation changes over ADB/Shizuku. Xiaomi requires a SIM card and a Xiaomi account to turn it on.",
    )
    override fun limitations(d: DeviceIdentity, services: ServicesInfo) = listOf(
        Limitation("USB debugging (Security settings)", "Needed for `pm grant/revoke`, app-ops and settings changes. Disabling and removing apps works without it."),
        Limitation("Updates can bring apps back", "HyperOS/MIUI updates sometimes re-enable or reinstall removed system apps. Run your profile again after an update."),
        Limitation("Core MIUI packages", "Removing the Security app, permission manager or core MIUI packages can bootloop: Prune keeps them locked."),
    )
}

object HuaweiIntegration : OemIntegration() {
    override val id = "huawei"
    override val title = "Huawei (EMUI / HarmonyOS with Android support)"
    override val compat = CompatLevel.EXPERIMENTAL
    override val packagePrefixes = listOf("com.huawei.", "com.hicloud.", "com.vmall.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "huawei" || d.brandKey == "huawei"
    override fun firmware(d: DeviceIdentity): Firmware? {
        if (isHarmony(d)) return Firmware("HarmonyOS", d.prop("hw_sc.build.platform.version"))
        d.prop("ro.build.version.emui")?.let { return Firmware("EMUI", it.substringAfter('_')) }
        return null
    }
    override val disableOftenRefused = true
    override fun limitations(d: DeviceIdentity, services: ServicesInfo) = buildList {
        add(Limitation("Disabling is often refused", "EMUI and HarmonyOS refuse `pm disable-user` for many system apps. Prune falls back to uninstalling for the current user, which is reversible."))
        if (!services.gms) add(Limitation("No Google services",
            "This phone runs without Google Mobile Services. Huawei Mobile Services (HMS Core) and AppGallery replace them, so Prune keeps both locked."))
        if (isHarmony(d)) add(Limitation("HarmonyOS", "Prune works on HarmonyOS versions that still run Android apps (2.x to 4.x). HarmonyOS NEXT cannot run Android apps at all."))
    }
    override fun dynamicProtections(services: ServicesInfo): Map<String, String> =
        if (services.gms) emptyMap() else mapOf(
            "com.huawei.hwid" to "Huawei Mobile Services: this phone has no Google services, so apps depend on HMS Core.",
            "com.huawei.appmarket" to "AppGallery: the only app store on this phone (no Google Play).",
        )

    fun isHarmony(d: DeviceIdentity) = d.prop("hw_sc.build.platform.version") != null ||
        d.prop(HARMONY_BRAND_PROP).equals("harmony", ignoreCase = true)

    /** Synthetic property: com.huawei.system.BuildEx.getOsBrand() read by reflection. */
    const val HARMONY_BRAND_PROP = "prune.huawei.os_brand"
}

object HonorIntegration : OemIntegration() {
    override val id = "honor"
    override val title = "Honor (MagicOS)"
    override val compat = CompatLevel.EXPERIMENTAL
    override val packagePrefixes = listOf("com.hihonor.", "com.honor.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "honor" || d.brandKey == "honor"
    override fun firmware(d: DeviceIdentity): Firmware? {
        d.prop("ro.build.version.magic")?.let { return Firmware("MagicOS", it.substringAfter('_')) }
        d.prop("ro.build.version.emui")?.let { return Firmware("EMUI", it.substringAfter('_')) }
        return null
    }
    override val disableOftenRefused = true
    override fun limitations(d: DeviceIdentity, services: ServicesInfo) = buildList {
        add(Limitation("Disabling is often refused", "MagicOS refuses `pm disable-user` for many system apps. Prune falls back to uninstalling for the current user, which is reversible."))
        if (!services.gms) add(Limitation("No Google services", "Honor Mobile Services replace Google services on this phone, so Prune keeps them locked."))
    }
    override fun dynamicProtections(services: ServicesInfo): Map<String, String> =
        if (services.gms) emptyMap() else mapOf("com.hihonor.id" to "Honor Mobile Services: this phone has no Google services, so apps depend on it.")
}

object OplusIntegration : OemIntegration() {
    override val id = "oplus"
    override val title = "OPPO, OnePlus, realme (ColorOS / OxygenOS / realme UI)"
    override val compat = CompatLevel.EXPERIMENTAL
    override val packagePrefixes = listOf("com.oplus.", "com.coloros.", "com.heytap.", "com.oppo.", "com.nearme.", "com.oneplus.",
        "net.oneplus.", "com.realme.", "com.redteamobile.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey in setOf("oppo", "oneplus", "realme") || d.brandKey in setOf("oppo", "oneplus", "realme")
    override fun firmware(d: DeviceIdentity): Firmware? {
        val oplus = d.prop("ro.build.version.oplusrom")?.removePrefix("V")
        return when {
            d.brandKey == "realme" || d.manufacturerKey == "realme" ->
                Firmware("realme UI", d.prop("ro.build.version.realmeui")?.removePrefix("V") ?: oplus)
            d.brandKey == "oneplus" || d.manufacturerKey == "oneplus" ->
                Firmware("OxygenOS", d.prop("ro.oxygen.version") ?: oplus)
            else -> Firmware("ColorOS", oplus)
        }
    }
    override fun brandName(d: DeviceIdentity) = when {
        d.brandKey == "oneplus" -> "OnePlus"
        d.brandKey == "realme" -> "realme"
        else -> "OPPO"
    }
    override val developerOptionsSteps = listOf(
        "Settings → About device → Version → tap Build number 7 times.",
        "Developer options are in Settings → Additional settings (System settings on newer versions).",
    )
    override fun extraSetupSteps(d: DeviceIdentity) = listOf(
        "In Developer options turn on \"Disable permission monitoring\". Without it permission and background changes over ADB/Shizuku are refused.",
    )
    override fun limitations(d: DeviceIdentity, services: ServicesInfo) = listOf(
        Limitation("Disable permission monitoring", "ColorOS-based firmware blocks `pm grant/revoke` and app-op changes until this developer option is on."),
        Limitation("Security apps", "The Security Center and permission manager are locked: removing them breaks permission dialogs."),
    )
}

object MotorolaIntegration : OemIntegration() {
    override val id = "motorola"
    override val title = "Motorola (My UX)"
    override val compat = CompatLevel.EXPERIMENTAL
    override val packagePrefixes = listOf("com.motorola.", "com.motocare.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "motorola" || d.brandKey == "motorola"
    override fun firmware(d: DeviceIdentity) = Firmware("My UX", null)
    override fun limitations(d: DeviceIdentity, services: ServicesInfo) = listOf(
        Limitation("Overlays", "Motorola ships many configuration overlays (Wi-Fi, SIM, audio). They are locked or high risk: some cause bootloops when removed."),
    )
}

object SonyIntegration : OemIntegration() {
    override val id = "sony"
    override val title = "Sony Xperia"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.sonymobile.", "com.sony.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "sony"
    override fun firmware(d: DeviceIdentity) = Firmware("Xperia (Android)", null)
    override fun brandName(d: DeviceIdentity) = "Sony Xperia"
}

object NothingIntegration : OemIntegration() {
    override val id = "nothing"
    override val title = "Nothing (Nothing OS)"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.nothing.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "nothing" || d.brandKey == "nothing"
    override fun firmware(d: DeviceIdentity) = Firmware("Nothing OS", d.prop("ro.build.nothing.version") ?: d.prop("ro.nothing.version"))
}

object AsusIntegration : OemIntegration() {
    override val id = "asus"
    override val title = "ASUS (ZenUI / ROG UI)"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.asus.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "asus"
    override fun brandName(d: DeviceIdentity) = "ASUS"
}

object VivoIntegration : OemIntegration() {
    override val id = "vivo"
    override val title = "vivo, iQOO (Funtouch OS / OriginOS)"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.vivo.", "com.bbk.", "com.iqoo.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "vivo" || d.brandKey in setOf("vivo", "iqoo")
    override fun firmware(d: DeviceIdentity): Firmware? {
        val name = d.prop("ro.vivo.os.name") ?: return null
        val pretty = when {
            name.contains("origin", true) -> "OriginOS"
            name.contains("funtouch", true) -> "Funtouch OS"
            else -> name
        }
        return Firmware(pretty, d.prop("ro.vivo.os.version"))
    }
}

object TranssionIntegration : OemIntegration() {
    override val id = "transsion"
    override val title = "Tecno, Infinix, itel (HiOS / XOS / itel OS)"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.transsion.", "com.itel.", "com.tecno.", "com.infinix.", "com.afmobi.")
    override fun matches(d: DeviceIdentity) = d.brandKey in setOf("tecno", "infinix", "itel") ||
        d.manufacturerKey.contains("tecno") || d.manufacturerKey.contains("infinix") || d.manufacturerKey == "itel"
    override fun firmware(d: DeviceIdentity): Firmware {
        val name = when {
            d.brandKey == "infinix" || d.manufacturerKey.contains("infinix") -> "XOS"
            d.brandKey == "itel" -> "itel OS"
            else -> "HiOS"
        }
        return Firmware(name, d.prop("ro.tranos.version"))
    }
}

object HmdIntegration : OemIntegration() {
    override val id = "hmd"
    override val title = "Nokia / HMD"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.hmdglobal.", "com.evenwell.", "com.nokia.", "com.fih.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey.contains("hmd") || d.brandKey == "nokia" || d.brandKey == "hmd"
}

object LenovoIntegration : OemIntegration() {
    override val id = "lenovo"
    override val title = "Lenovo"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.lenovo.", "com.zui.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "lenovo"
}

object ZteIntegration : OemIntegration() {
    override val id = "zte"
    override val title = "ZTE, nubia"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.zte.", "cn.nubia.", "com.nubia.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey in setOf("zte", "nubia")
}

object LgIntegration : OemIntegration() {
    override val id = "lg"
    override val title = "LG"
    override val compat = CompatLevel.GENERIC
    override val packagePrefixes = listOf("com.lge.")
    override fun matches(d: DeviceIdentity) = d.manufacturerKey == "lge" || d.manufacturerKey == "lg"
    override fun brandName(d: DeviceIdentity) = "LG"
}

/** Any other Android device: generic rules and community data only. */
object GenericAndroidIntegration : OemIntegration() {
    override val id = "generic"
    override val title = "Android (generic)"
    override val compat = CompatLevel.GENERIC
    override fun matches(d: DeviceIdentity) = true
    override fun firmware(d: DeviceIdentity) = Firmware("Android", d.release.takeIf { it.isNotBlank() })
    override val developerOptionsSteps = listOf(
        "Settings → About phone → tap Build number 7 times (the exact place varies by manufacturer).",
        "Developer options appear in Settings → System, or in the main Settings list.",
    )
}

/** The Android emulator: where Prune's automated tests run. */
object EmulatorIntegration : OemIntegration() {
    override val id = "emulator"
    override val title = "Android emulator"
    override val compat = CompatLevel.TESTED
    override fun matches(d: DeviceIdentity) = d.isEmulator
    override fun firmware(d: DeviceIdentity) = Firmware("Android emulator", d.release)
    override fun brandName(d: DeviceIdentity) = "Android emulator"
}
