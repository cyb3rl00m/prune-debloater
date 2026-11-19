package io.github.cyb3rl00m.prune.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.cyb3rl00m.prune.core.model.RemovalMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), DARK("Dark"), LIGHT("Light") }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val method: RemovalMethod = RemovalMethod.DISABLE,
    val fallbackUninstall: Boolean = true,
    val skipUsedWithinDays: Int = 14,
    /** null = not answered yet: every ecosystem counts as used. */
    val usedEcosystems: Set<String>? = null,
    val preferRoot: Boolean = false,
    val setupDone: Boolean = false,
    val progressLevel: Int = 2,
    val showSystemInOptimizer: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val method = stringPreferencesKey("method")
        val fallback = booleanPreferencesKey("fallback_uninstall")
        val skipDays = intPreferencesKey("skip_used_days")
        val ecosystems = stringSetPreferencesKey("used_ecosystems")
        val ecosystemsAnswered = booleanPreferencesKey("ecosystems_answered")
        val preferRoot = booleanPreferencesKey("prefer_root")
        val setupDone = booleanPreferencesKey("setup_done")
        val progressLevel = intPreferencesKey("progress_level")
        val showSystem = booleanPreferencesKey("show_system_optimizer")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p: Preferences ->
        AppSettings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            method = p[K.method]?.let { runCatching { RemovalMethod.valueOf(it) }.getOrNull() } ?: RemovalMethod.DISABLE,
            fallbackUninstall = p[K.fallback] ?: true,
            skipUsedWithinDays = p[K.skipDays] ?: 14,
            usedEcosystems = if (p[K.ecosystemsAnswered] == true) p[K.ecosystems].orEmpty() else null,
            preferRoot = p[K.preferRoot] ?: false,
            setupDone = p[K.setupDone] ?: false,
            progressLevel = p[K.progressLevel] ?: 2,
            showSystemInOptimizer = p[K.showSystem] ?: false,
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setMethod(v: RemovalMethod) = context.dataStore.edit { it[K.method] = v.name }
    suspend fun setFallback(v: Boolean) = context.dataStore.edit { it[K.fallback] = v }
    suspend fun setSkipDays(v: Int) = context.dataStore.edit { it[K.skipDays] = v }
    suspend fun setUsedEcosystems(v: Set<String>) = context.dataStore.edit {
        it[K.ecosystems] = v
        it[K.ecosystemsAnswered] = true
    }
    suspend fun setPreferRoot(v: Boolean) = context.dataStore.edit { it[K.preferRoot] = v }
    suspend fun setSetupDone(v: Boolean) = context.dataStore.edit { it[K.setupDone] = v }
    suspend fun setProgressLevel(v: Int) = context.dataStore.edit { it[K.progressLevel] = v }
    suspend fun setShowSystem(v: Boolean) = context.dataStore.edit { it[K.showSystem] = v }
}
