package io.github.cyb3rl00m.prune.core.oem

/** How well Prune supports a device. Shown in the compatibility report; never overstated. */
enum class CompatLevel(val label: String, val meaning: String) {
    TESTED("Fully implemented and tested", "Prune's automated tests run in this environment."),
    PARTIAL("Implemented, partially tested",
        "Dedicated rules exist and the debloat engine is tested on stock Android, but not on this brand's hardware yet."),
    EXPERIMENTAL("Experimental",
        "Dedicated rules and known firmware limitations are built in, tested only with simulated devices. Expect surprises: review every change."),
    GENERIC("Generic compatibility",
        "No dedicated integration. The generic Android rules and the community database are used."),
    UNSUPPORTED("Unsupported", "Prune cannot work here."),
}

data class Firmware(val name: String, val version: String?) {
    override fun toString() = if (version.isNullOrBlank()) name else "$name $version"
}

data class Limitation(val title: String, val text: String)

/** Which Google / Huawei service frameworks are installed and enabled. Detected independently of the brand. */
data class ServicesInfo(val gms: Boolean, val playStore: Boolean, val hms: Boolean, val honorServices: Boolean = false)

/**
 * Everything that differs between manufacturers. Integrations only add knowledge: the debloat engine itself is
 * manufacturer-independent, and [GenericAndroidIntegration] works on every Android device.
 */
abstract class OemIntegration {
    abstract val id: String
    /** Name shown to the user, for example "Xiaomi (HyperOS / MIUI)". */
    abstract val title: String
    abstract val compat: CompatLevel
    open val compatNote: String get() = compat.meaning
    /** Package prefixes that belong to this manufacturer (used to label packages, on any device). */
    open val packagePrefixes: List<String> = emptyList()

    abstract fun matches(d: DeviceIdentity): Boolean
    open fun firmware(d: DeviceIdentity): Firmware? = null
    /** Brand shown on the home screen (for example Redmi on a Xiaomi-made phone). */
    open fun brandName(d: DeviceIdentity): String = d.brand.replaceFirstChar { it.uppercase() }

    open val developerOptionsSteps: List<String> = listOf(
        "Settings → About phone → tap Build number 7 times (enter your PIN). Developer options appear in Settings → System.",
    )
    /** Extra one-time switches some firmwares need before ADB-level commands work. */
    open fun extraSetupSteps(d: DeviceIdentity): List<String> = emptyList()
    open fun limitations(d: DeviceIdentity, services: ServicesInfo): List<Limitation> = emptyList()
    /** Packages this firmware needs, given the installed service frameworks. */
    open fun dynamicProtections(services: ServicesInfo): Map<String, String> = emptyMap()
    /** True where the firmware is known to refuse `pm disable-user` for many system apps. */
    open val disableOftenRefused: Boolean = false
}

/** Picks the integration for a device. Never throws: unknown devices get the generic integration. */
object OemRegistry {
    val integrations: List<OemIntegration> = listOf(
        SamsungIntegration, GooglePixelIntegration, XiaomiIntegration, HuaweiIntegration, HonorIntegration,
        OplusIntegration, MotorolaIntegration, SonyIntegration, NothingIntegration, AsusIntegration, VivoIntegration,
        TranssionIntegration, HmdIntegration, LenovoIntegration, ZteIntegration, LgIntegration,
    )

    fun detect(d: DeviceIdentity): OemIntegration = runCatching {
        if (d.isEmulator) EmulatorIntegration
        else integrations.firstOrNull { runCatching { it.matches(d) }.getOrDefault(false) } ?: GenericAndroidIntegration
    }.getOrDefault(GenericAndroidIntegration)

    private val prefixIndex: List<Pair<String, OemIntegration>> by lazy {
        integrations.flatMap { i -> i.packagePrefixes.map { it to i } }.sortedByDescending { it.first.length }
    }

    /** The manufacturer a package belongs to, from its name, or null if it is not a known manufacturer package. */
    fun manufacturerOf(pkg: String): OemIntegration? = prefixIndex.firstOrNull { pkg.startsWith(it.first) }?.second

    fun firmwareSafe(i: OemIntegration, d: DeviceIdentity): Firmware? = runCatching { i.firmware(d) }.getOrNull()
    fun limitationsSafe(i: OemIntegration, d: DeviceIdentity, s: ServicesInfo): List<Limitation> =
        runCatching { i.limitations(d, s) }.getOrDefault(emptyList())
}
