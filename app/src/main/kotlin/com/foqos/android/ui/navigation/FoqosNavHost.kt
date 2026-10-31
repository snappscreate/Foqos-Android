package com.foqos.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.ui.home.HomeScreen
import com.foqos.android.ui.insights.InsightsScreen
import com.foqos.android.ui.onboarding.OnboardingScreen
import com.foqos.android.ui.profile.AppPickerScreen
import com.foqos.android.ui.profile.ProfileEditScreen
import com.foqos.android.ui.profile.ProfileEditViewModel
import com.foqos.android.ui.session.ActiveSessionScreen
import com.foqos.android.ui.settings.ExportScreen
import com.foqos.android.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single-activity NavHost for the whole app. Chooses [Routes.ONBOARDING] vs [Routes.HOME] as the
 * start destination based on [SettingsDataStore.introSeen], and auto-navigates to
 * [Routes.ACTIVE_SESSION] whenever a session starts while the user is on the Home screen (mirrors
 * iOS auto-presenting the active session sheet).
 */
@Composable
fun FoqosNavHost(
    sessionManager: SessionManager,
    settingsDataStore: SettingsDataStore,
    modifier: Modifier = Modifier,
) {
    val introSeen by produceState<Boolean?>(initialValue = null) {
        value = settingsDataStore.introSeen.first()
    }

    val resolvedIntroSeen = introSeen
    if (resolvedIntroSeen == null) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize())
        }
        return
    }

    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // The home screen must remain accessible while blocking is active.
    // Session starts no longer auto-navigate to a second full-screen blocker.

    NavHost(
        navController = navController,
        startDestination = if (resolvedIntroSeen) Routes.HOME else Routes.ONBOARDING,
        modifier = modifier,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    scope.launch {
                        settingsDataStore.setIntroSeen(true)
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onCreateProfile = { navController.navigate(Routes.profileEditNew()) },
                onEditProfile = { profileId -> navController.navigate(Routes.profileEdit(profileId)) },
                onOpenInsights = { profileId -> navController.navigate(Routes.insights(profileId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenActiveSession = { navController.navigate(Routes.ACTIVE_SESSION) },
            )
        }

        composable(
            route = Routes.PROFILE_EDIT_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_PROFILE_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val viewModel = hiltViewModel<ProfileEditViewModel>(backStackEntry)
            ProfileEditScreen(
                viewModel = viewModel,
                onDone = { navController.popBackStack() },
                onOpenAppPicker = { navController.navigate(Routes.APP_PICKER) },
            )
        }

        composable(Routes.APP_PICKER) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Routes.PROFILE_EDIT_PATTERN)
            }
            val viewModel = hiltViewModel<ProfileEditViewModel>(parentEntry)
            AppPickerScreen(
                viewModel = viewModel,
                onDone = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.INSIGHTS_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_PROFILE_ID) { type = NavType.StringType }),
        ) {
            InsightsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ACTIVE_SESSION) {
            ActiveSessionScreen(onClosed = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenExport = { navController.navigate(Routes.EXPORT) },
            )
        }

        composable(Routes.EXPORT) {
            ExportScreen(onBack = { navController.popBackStack() })
        }
    }
}
