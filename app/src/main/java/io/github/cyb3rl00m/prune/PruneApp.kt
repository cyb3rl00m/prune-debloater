package io.github.cyb3rl00m.prune

import android.app.Application
import android.content.Context
import io.github.cyb3rl00m.prune.core.db.Classifier
import io.github.cyb3rl00m.prune.core.db.PackageDatabase
import io.github.cyb3rl00m.prune.core.device.DeviceInfo
import io.github.cyb3rl00m.prune.core.device.DeviceInfoReader
import io.github.cyb3rl00m.prune.core.history.HistoryStore
import io.github.cyb3rl00m.prune.core.model.AppPackage
import io.github.cyb3rl00m.prune.core.optimize.Optimizer
import io.github.cyb3rl00m.prune.core.plan.Executor
import io.github.cyb3rl00m.prune.core.scan.AppUsage
import io.github.cyb3rl00m.prune.core.scan.PackageScanner
import io.github.cyb3rl00m.prune.core.scan.UsageReader
import io.github.cyb3rl00m.prune.core.settings.SettingsStore
import io.github.cyb3rl00m.prune.core.shell.AccessManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PruneApp : Application() {
    lateinit var repository: PruneRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = PruneRepository(this)
    }
}

val Context.repository: PruneRepository get() = (applicationContext as PruneApp).repository

data class ScanState(
    val apps: List<AppPackage> = emptyList(),
    val usage: Map<String, AppUsage> = emptyMap(),
    val scanning: Boolean = false,
    val lastScan: Long = 0,
    val error: String? = null,
)

/** Single owner of device state shared by all screens. */
class PruneRepository(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: PackageDatabase by lazy {
        val assets = context.assets
        PackageDatabase.parse(
            assets.open("prune_rules.json").bufferedReader().use { it.readText() },
            assets.open("packages_uad.json").bufferedReader().use { it.readText() },
        )
    }
    val classifier by lazy { Classifier(database) }
    val scanner = PackageScanner(context)
    val usageReader = UsageReader(context)
    val access = AccessManager(context)
    val history = HistoryStore(context)
    val executor = Executor(context, scanner, history)
    val optimizer = Optimizer(context)
    val settings = SettingsStore(context)

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private val scanLock = Mutex()

    init {
        scope.launch { rescan() }
    }

    suspend fun rescan() = scanLock.withLock {
        _scan.update { it.copy(scanning = true, error = null) }
        try {
            withContext(Dispatchers.IO) {
                _device.value = DeviceInfoReader.read(context)
                access.refresh()
                val facts = scanner.scan()
                val dynamic = scanner.dynamicProtections()
                val usage = usageReader.read()
                val apps = facts.map { f ->
                    AppPackage(f, classifier.classify(f, dynamic), usage[f.packageName]?.lastUsed)
                }
                _scan.value = ScanState(apps, usage, scanning = false, lastScan = System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            _scan.update { it.copy(scanning = false, error = t.message ?: t.toString()) }
        }
    }

    fun app(pkg: String): AppPackage? = _scan.value.apps.firstOrNull { it.packageName == pkg }
}
