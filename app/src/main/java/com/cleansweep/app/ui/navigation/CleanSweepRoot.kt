package com.cleansweep.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cleansweep.app.R
import com.cleansweep.app.ui.home.HomeScreen
import com.cleansweep.app.ui.onboarding.OnboardingScreen

/** A screen with a tab of its own in the bar at the bottom. */
private data class TopLevel(
    val route: String,
    val label: Int,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevel(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
)

/**
 * Everything the user can see, and the way from one screen to the next.
 *
 * [startOnOnboarding] is true when the app may not read storage yet. The launch route sends the user on to
 * onboarding or to the dashboard, so that the dashboard is always the root of the back stack.
 */
@Composable
fun CleanSweepRoot(
    startOnOnboarding: Boolean,
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route

    Scaffold(
        bottomBar = {
            if (topLevelDestinations.size > 1 && currentRoute.isTopLevel()) {
                BottomBar(currentRoute = currentRoute.orEmpty(), onOpen = navController::openTopLevel)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LAUNCH,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.LAUNCH) {
                LaunchedEffect(Unit) {
                    navController.navigate(if (startOnOnboarding) Routes.ONBOARDING else Routes.HOME) {
                        popUpTo(Routes.LAUNCH) { inclusive = true }
                    }
                }
            }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinished = { navController.leaveOnboarding() })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSuggestions = { },
                    onOpenScan = { },
                    onOpenTrash = { },
                    onAllowAccess = { navController.navigate(Routes.ONBOARDING) },
                )
            }
        }
    }
}

private fun String?.isTopLevel(): Boolean = this != null && topLevelDestinations.any { it.route == this }

/** The way off the onboarding screen, whether access was granted or put off: on to the dashboard. */
private fun NavHostController.leaveOnboarding() {
    navigate(Routes.HOME) {
        popUpTo(Routes.ONBOARDING) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Opens one of the screens with a tab. The other tabs keep their place in the stack, so that going back and forth
 * between them loses nothing and does not pile up copies.
 */
private fun NavHostController.openTopLevel(
    route: String,
) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomBar(
    currentRoute: String,
    onOpen: (String) -> Unit,
) {
    NavigationBar {
        for (destination in topLevelDestinations) {
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onOpen(destination.route) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}
