package app.sojourn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.sojourn.AppContainer
import app.sojourn.container
import app.sojourn.data.flights.AirportDirectory
import app.sojourn.ui.flights.LocalAirports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.sojourn.ui.calendar.CalendarScreen
import app.sojourn.ui.home.HomeScreen
import app.sojourn.ui.onboarding.OnboardingScreen
import app.sojourn.ui.imports.ImportScreen
import app.sojourn.ui.plan.PlanScreen
import app.sojourn.ui.reports.FeieScreen
import app.sojourn.ui.reports.HistoryScreen
import app.sojourn.ui.rules.RulesScreen
import app.sojourn.ui.settings.SettingsScreen
import app.sojourn.ui.theme.SojournTheme
import app.sojourn.ui.timeline.TimelineScreen
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

@Serializable data object CalendarRoute

@Serializable data object PlanRoute

@Serializable data object TimelineRoute

@Serializable data object SettingsRoute

@Serializable data object RulesRoute

@Serializable data object FeieRoute

@Serializable data object HistoryRoute

@Serializable data object EvidenceRoute

@Serializable data object ImportRoute

private enum class Tab(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector, val selected: ImageVector) {
    HOME(HomeRoute, HomeRoute::class, "Today", Icons.Outlined.Explore, Icons.Rounded.Explore),
    CALENDAR(CalendarRoute, CalendarRoute::class, "Calendar", Icons.Outlined.CalendarMonth, Icons.Rounded.CalendarMonth),
    PLAN(PlanRoute, PlanRoute::class, "Plan", Icons.Outlined.EditCalendar, Icons.Rounded.EditCalendar),
    TIMELINE(TimelineRoute, TimelineRoute::class, "Timeline", Icons.Outlined.Timeline, Icons.Rounded.Timeline),
}

/**
 * Files handed to this activity by "Share" or "Open with". Per activity rather than global, so
 * a second window started by a share doesn't make the first one import too.
 */
val LocalIncomingFiles = staticCompositionLocalOf { MutableStateFlow<List<Uri>>(emptyList()) }

/** Text shared to this activity, with its HTML when the sending app gave it. */
val LocalIncomingText = staticCompositionLocalOf { MutableStateFlow<Pair<String, String?>?>(null) }

/** Builds a screen's view model from the shared container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel { create(container) }
}

@Composable
fun SojournRoot() {
    val container = LocalContext.current.container
    val settings by container.repository.settings.collectAsStateWithLifecycle(initialValue = null)
    // Loaded once off the main thread; flight views show bare airport codes until it's there.
    val airports by produceState<AirportDirectory?>(null) { value = withContext(Dispatchers.IO) { container.airports } }
    val current = settings ?: return
    SojournTheme(current.theme, current.dynamicColor) {
        CompositionLocalProvider(LocalAirports provides airports) {
            if (current.onboarded) MainScaffold() else OnboardingScreen()
        }
    }
}

@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val tab = Tab.entries.firstOrNull { entry?.destination?.hasRoute(it.type) == true }
    val incoming by LocalIncomingFiles.current.collectAsStateWithLifecycle()
    val incomingText by LocalIncomingText.current.collectAsStateWithLifecycle()
    LaunchedEffect(incoming, incomingText) {
        val waiting = incoming.isNotEmpty() || incomingText != null
        if (waiting && nav.currentDestination?.hasRoute(ImportRoute::class) != true) nav.navigate(ImportRoute)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(tab != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                ShortNavigationBar {
                    Tab.entries.forEach { item ->
                        ShortNavigationBarItem(
                            selected = tab == item,
                            onClick = { nav.switchTab(item.route) },
                            icon = { Icon(if (tab == item) item.selected else item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            // Tabs swap with a quick fade-through, as Material's motion guidance has for top-level destinations.
            enterTransition = { fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f) },
            exitTransition = { fadeOut(tween(90)) },
            popEnterTransition = { fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f) },
            popExitTransition = { fadeOut(tween(90)) },
        ) {
            composable<HomeRoute> { HomeScreen(navigate = { nav.navigate(it) }, switchTab = { nav.switchTab(it) }) }
            composable<CalendarRoute> { CalendarScreen() }
            composable<PlanRoute> { PlanScreen(openRules = { nav.navigate(RulesRoute) }) }
            composable<TimelineRoute> { TimelineScreen() }
            pushed<SettingsRoute> { SettingsScreen(back = { nav.popBackStack() }, openRules = { nav.navigate(RulesRoute) }) }
            pushed<RulesRoute> { RulesScreen(back = { nav.popBackStack() }) }
            pushed<FeieRoute> { FeieScreen(back = { nav.popBackStack() }) }
            pushed<HistoryRoute> { HistoryScreen(back = { nav.popBackStack() }) }
            pushed<EvidenceRoute> { app.sojourn.ui.reports.EvidenceScreen(back = { nav.popBackStack() }) }
            pushed<ImportRoute> { ImportScreen(back = { nav.popBackStack() }) }
        }
    }
}

/** Screens stacked on top of the tabs slide in from the side. */
private inline fun <reified T : Any> androidx.navigation.NavGraphBuilder.pushed(noinline content: @Composable () -> Unit) {
    composable<T>(
        enterTransition = { slideInHorizontally(tween(320)) { it / 5 } + fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { slideOutHorizontally(tween(260)) { it / 5 } + fadeOut(tween(260)) },
    ) { content() }
}

private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
