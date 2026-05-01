package com.fitform.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.fitform.app.model.ExerciseMode
import com.fitform.app.ui.benchmark.BenchmarkScreen
import com.fitform.app.ui.history.HistoryScreen
import com.fitform.app.ui.home.HomeScreen
import com.fitform.app.ui.live.LiveCoachScreen
import com.fitform.app.ui.replay.ReplayScreen
import com.fitform.app.ui.setup.SetupScreen
import com.fitform.app.ui.summary.SetSummaryScreen

object Routes {
    const val HOME = "home"
    const val SETUP = "setup/{mode}"
    const val LIVE = "live/{mode}"
    const val SUMMARY = "summary/{sessionId}"
    const val HISTORY = "history"
    const val REPLAY = "replay/{sessionId}"
    const val BENCHMARK = "benchmark"

    fun setup(mode: ExerciseMode) = "setup/${mode.routeKey}"
    fun live(mode: ExerciseMode) = "live/${mode.routeKey}"
    fun summary(sessionId: String) = "summary/$sessionId"
    fun replay(sessionId: String) = "replay/$sessionId"
}

@Composable
fun FitFormNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onGymCoach = { navController.navigate(Routes.setup(ExerciseMode.GYM)) },
                onShotCoach = { navController.navigate(Routes.setup(ExerciseMode.SHOT)) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onBenchmark = { navController.navigate(Routes.BENCHMARK) },
            )
        }
        composable(
            Routes.SETUP,
            arguments = listOf(navArgument("mode") { type = NavType.StringType }),
        ) { entry ->
            val mode = ExerciseMode.fromRouteKey(entry.arguments?.getString("mode") ?: "gym")
            SetupScreen(
                mode = mode,
                onBack = { navController.popBackStack() },
                onStart = { navController.navigate(Routes.live(mode)) },
            )
        }
        composable(
            Routes.LIVE,
            arguments = listOf(navArgument("mode") { type = NavType.StringType }),
        ) { entry ->
            val mode = ExerciseMode.fromRouteKey(entry.arguments?.getString("mode") ?: "gym")
            LiveCoachScreen(
                mode = mode,
                onExit = { navController.popBackStack() },
                onSetComplete = { sessionId ->
                    navController.navigate(Routes.summary(sessionId)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(
            Routes.SUMMARY,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            SetSummaryScreen(
                sessionId = sessionId,
                onWatchReplay = { navController.navigate(Routes.replay(sessionId)) },
                onHome = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate(Routes.replay(id)) },
            )
        }
        composable(
            Routes.REPLAY,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            ReplayScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BENCHMARK) {
            BenchmarkScreen(onBack = { navController.popBackStack() })
        }
    }
}
