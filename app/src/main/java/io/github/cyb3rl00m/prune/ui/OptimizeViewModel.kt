package io.github.cyb3rl00m.prune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cyb3rl00m.prune.core.model.AppPackage
import io.github.cyb3rl00m.prune.core.optimize.AnimationState
import io.github.cyb3rl00m.prune.core.optimize.AppPermissions
import io.github.cyb3rl00m.prune.core.optimize.OptimizationSection
import io.github.cyb3rl00m.prune.core.optimize.Optimizer
import io.github.cyb3rl00m.prune.core.optimize.ResourceItem
import io.github.cyb3rl00m.prune.core.optimize.StorageSummary
import io.github.cyb3rl00m.prune.repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OptimizeState(
    val loading: Set<OptimizationSection> = emptySet(),
    val animations: AnimationState? = null,
    val background: List<AppPackage>? = null,
    val exemptions: Optimizer.Exemptions? = null,
    val permissions: List<AppPermissions>? = null,
    val notifications: List<AppPackage>? = null,
    val storage: StorageSummary? = null,
    val resources: List<ResourceItem>? = null,
)

class OptimizeViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.repository
    private val optimizer = repo.optimizer
    private val _state = MutableStateFlow(OptimizeState())
    val state: StateFlow<OptimizeState> = _state.asStateFlow()

    fun load(section: OptimizationSection, includeSystem: Boolean = false) {
        val apps = repo.scan.value.apps
        val usage = repo.scan.value.usage
        val shell = repo.access.shell()
        _state.update { it.copy(loading = it.loading + section) }
        viewModelScope.launch {
            try {
                when (section) {
                    OptimizationSection.ANIMATIONS -> _state.update { it.copy(animations = optimizer.animationState()) }
                    OptimizationSection.BACKGROUND -> {
                        val list = optimizer.backgroundCandidates(apps, usage, shell)
                        _state.update { it.copy(background = list) }
                    }
                    OptimizationSection.BATTERY_EXEMPTIONS -> {
                        val e = optimizer.batteryExemptions(apps, shell)
                        _state.update { it.copy(exemptions = e) }
                    }
                    OptimizationSection.PERMISSIONS -> {
                        val p = optimizer.permissions(apps, includeSystem)
                        _state.update { it.copy(permissions = p) }
                    }
                    OptimizationSection.NOTIFICATIONS -> {
                        val n = optimizer.notificationApps(apps, includeSystem)
                        _state.update { it.copy(notifications = n) }
                    }
                    OptimizationSection.STORAGE -> {
                        val s = optimizer.storage(apps)
                        _state.update { it.copy(storage = s) }
                    }
                    OptimizationSection.RESOURCES -> {
                        val r = optimizer.resources(apps, usage, shell)
                        _state.update { it.copy(resources = r) }
                    }
                    OptimizationSection.COMPILATION -> Unit
                }
            } finally {
                _state.update { it.copy(loading = it.loading - section) }
            }
        }
    }
}
