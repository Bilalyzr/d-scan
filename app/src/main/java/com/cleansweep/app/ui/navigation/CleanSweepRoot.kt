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
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cleansweep.app.R
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.group.GroupScreen
import com.cleansweep.app.ui.home.HomeScreen
import com.cleansweep.app.ui.onboarding.OnboardingScreen
import com.cleansweep.app.ui.preview.PreviewScreen
import com.cleansweep.app.ui.scan.ScanScreen
import com.cleansweep.app.ui.suggestions.SuggestionsScreen

/** A screen with a tab of its own in the bar at the bottom. */
private data class TopLevel(
    /** What the navigation library reports for the screen, with its arguments as patterns. */
    val pattern: String,
    /** Where to go to open the screen. */
    val open: String,
    val label: Int,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevel(Routes.HOME, Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    TopLevel(Routes.SUGGESTIONS, Routes.suggestions(), R.string.nav_suggestions, AppIcons.Suggestions),
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
            if (currentRoute.isTopLevel()) {
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
                    onOpenSuggestions = { navController.navigate(Routes.suggestions(type = it)) },
                    onOpenScan = { navController.navigate(Routes.SCAN) },
                    onOpenTrash = { },
                    onAllowAccess = { navController.navigate(Routes.ONBOARDING) },
                )
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    onBack = { navController.popBackStack() },
                    onFinished = { navController.openSuggestionsAfterScan() },
                    onAllowAccess = { navController.navigate(Routes.ONBOARDING) },
                )
            }
            composable(
                route = Routes.SUGGESTIONS,
                arguments = listOf(
                    navArgument(Routes.ARG_CATEGORY) { type = NavType.StringType; defaultValue = "" },
                    navArgument(Routes.ARG_TYPE) { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                SuggestionsScreen(
                    onBack = { navController.leaveTopLevel() },
                    onScan = { navController.navigate(Routes.SCAN) },
                    onAllowAccess = { navController.navigate(Routes.ONBOARDING) },
                    onOpenGroup = { navController.navigate(Routes.group(it)) },
                    onOpenFile = { navController.navigate(Routes.preview(it)) },
                )
            }
            composable(
                route = Routes.GROUP,
                arguments = listOf(navArgument(Routes.ARG_HASH) { type = NavType.StringType }),
            ) {
                GroupScreen(
                    onBack = { navController.popBackStack() },
                    onOpenFile = { navController.navigate(Routes.preview(it)) },
                    onAllowAccess = { navController.navigate(Routes.ONBOARDING) },
                )
            }
            composable(
                route = Routes.PREVIEW,
                arguments = listOf(navArgument(Routes.ARG_FILE_ID) { type = NavType.StringType }),
            ) {
                PreviewScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}

private fun String?.isTopLevel(): Boolean = this != null && topLevelDestinations.any { it.pattern == this }

/** The way off the onboarding screen, whether access was granted or put off: on to the dashboard. */
private fun NavHostController.leaveOnboarding() {
    navigate(Routes.HOME) {
        popUpTo(Routes.ONBOARDING) { inclusive = true }
        launchSingleTop = true
    }
}

/** Leaves a screen with a tab for the one beneath it, or for the dashboard when there is none. */
private fun NavHostController.leaveTopLevel() {
    if (!popBackStack()) openTopLevel(Routes.HOME)
}

/** After a scan, the app lands on the Suggestions Hub (PRD 5, step 3). The scan screen is left behind. */
private fun NavHostController.openSuggestionsAfterScan() {
    navigate(Routes.suggestions()) {
        popUpTo(Routes.SCAN) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Opens one of the screens with a tab. The other tabs keep their place in the stack, so that going back and forth
 * between them loses nothing and does not pile up copies.
 */
private fun NavHostController.openTopLevel(
    pattern: String,
) {
    val destination = topLevelDestinations.first { it.pattern == pattern || it.open == pattern }
    navigate(destination.open) {
        popUpTo(Routes.HOME) { saveState = true }
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
                selected = currentRoute == destination.pattern,
                onClick = { onOpen(destination.pattern) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}
