package io.github.cyb3rl00m.prune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.cyb3rl00m.prune.core.optimize.OptimizationSection
import io.github.cyb3rl00m.prune.core.plan.DebloatLevel
import io.github.cyb3rl00m.prune.ui.screens.AppDetailScreen
import io.github.cyb3rl00m.prune.ui.screens.AppsScreen
import io.github.cyb3rl00m.prune.ui.screens.BatchScreen
import io.github.cyb3rl00m.prune.ui.screens.CustomizeScreen
import io.github.cyb3rl00m.prune.ui.screens.DebloatScreen
import io.github.cyb3rl00m.prune.ui.screens.DeviceScreen
import io.github.cyb3rl00m.prune.ui.screens.HomeScreen
import io.github.cyb3rl00m.prune.ui.screens.OptimizeScreen
import io.github.cyb3rl00m.prune.ui.screens.OptimizeSectionScreen
import io.github.cyb3rl00m.prune.ui.screens.RestoreScreen
import io.github.cyb3rl00m.prune.ui.screens.ReviewScreen
import io.github.cyb3rl00m.prune.ui.screens.SettingsScreen
import io.github.cyb3rl00m.prune.ui.screens.SetupScreen

object Routes {
    const val HOME = "home"
    const val DEBLOAT = "debloat"
    const val APPS = "apps"
    const val OPTIMIZE = "optimize"
    const val RESTORE = "restore"
    const val CUSTOMIZE = "customize/{level}"
    const val REVIEW = "review"
    const val APP = "app/{pkg}"
    const val SECTION = "section/{name}"
    const val BATCH = "batch/{id}"
    const val SETUP = "setup"
    const val SETTINGS = "settings"
    const val DEVICE = "device"
    const val COMPAT = "compat"
    const val DUPLICATES = "duplicates"

    fun customize(level: DebloatLevel) = "customize/${level.number}"
    fun app(pkg: String) = "app/$pkg"
    fun section(s: OptimizationSection) = "section/${s.name}"
    fun batch(id: String) = "batch/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Dashboard),
    Tab(Routes.DEBLOAT, "Debloat", Icons.Outlined.ContentCut),
    Tab(Routes.APPS, "Packages", Icons.Outlined.Apps),
    Tab(Routes.OPTIMIZE, "Optimize", Icons.Outlined.AutoFixHigh),
    Tab(Routes.RESTORE, "Restore", Icons.Outlined.History),
)

/** Navigation helpers handed to every screen. */
class Nav(private val controller: NavHostController) {
    fun go(route: String) = controller.navigate(route) { launchSingleTop = true }
    fun back() { controller.popBackStack() }
    fun tab(route: String) = controller.navigate(route) {
        popUpTo(controller.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun PruneRoot(vm: AppViewModel) {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    val snackbar = remember { SnackbarHostState() }
    val entry by controller.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val optimizeVm: OptimizeViewModel = viewModel()
    val access by vm.access.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Usage access granted in Settings while Prune was in the background: rescan to load usage data.
    var hadUsageAccess by remember { mutableStateOf(access.usageAccess) }
    LaunchedEffect(access.usageAccess) {
        if (access.usageAccess && !hadUsageAccess) vm.rescan()
        hadUsageAccess = access.usageAccess
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = route == t.route,
                                onClick = { nav.tab(t.route) },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label) },
                                modifier = Modifier.testTag("tab_${t.route}"),
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding: PaddingValues ->
        val bottomPadding = PaddingValues(bottom = padding.calculateBottomPadding())
        // Wide screens (tablets, TVs): keep the phone layout readable instead of stretching it edge to edge.
        Box(Modifier.fillMaxSize().padding(bottomPadding).consumeWindowInsets(bottomPadding), contentAlignment = Alignment.TopCenter) {
            NavHost(controller, startDestination = Routes.HOME, modifier = Modifier.widthIn(max = 840.dp),
                // Short, quiet transitions: a quick cross-fade with a small shift, instead of the default zoom.
                enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180, delayMillis = 40)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 40 } },
                exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
                popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180, delayMillis = 40)) },
                popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(160)) { it / 40 } },
            ) {
                composable(Routes.HOME) { HomeScreen(vm, nav) }
                composable(Routes.DEBLOAT) { DebloatScreen(vm, nav) }
                composable(Routes.APPS) { AppsScreen(vm, nav) }
                composable(Routes.OPTIMIZE) { OptimizeScreen(vm, optimizeVm, nav) }
                composable(Routes.RESTORE) { RestoreScreen(vm, nav) }
                composable(Routes.CUSTOMIZE, arguments = listOf(navArgument("level") { type = NavType.IntType })) {
                    CustomizeScreen(vm, nav, DebloatLevel.of(it.arguments?.getInt("level") ?: 2))
                }
                composable(Routes.REVIEW) { ReviewScreen(vm, nav) }
                composable(Routes.APP, arguments = listOf(navArgument("pkg") { type = NavType.StringType })) {
                    AppDetailScreen(vm, nav, it.arguments?.getString("pkg").orEmpty())
                }
                composable(Routes.SECTION, arguments = listOf(navArgument("name") { type = NavType.StringType })) {
                    val s = runCatching { OptimizationSection.valueOf(it.arguments?.getString("name").orEmpty()) }
                        .getOrDefault(OptimizationSection.ANIMATIONS)
                    OptimizeSectionScreen(vm, optimizeVm, nav, s)
                }
                composable(Routes.BATCH, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    BatchScreen(vm, nav, it.arguments?.getString("id").orEmpty())
                }
                composable(Routes.SETUP) { SetupScreen(vm, nav) }
                composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
                composable(Routes.DEVICE) { DeviceScreen(vm, nav) }
                composable(Routes.COMPAT) { io.github.cyb3rl00m.prune.ui.screens.CompatibilityScreen(vm, nav) }
                composable(Routes.DUPLICATES) { io.github.cyb3rl00m.prune.ui.screens.DuplicatesScreen(vm, nav) }
            }
        }
    }
}
