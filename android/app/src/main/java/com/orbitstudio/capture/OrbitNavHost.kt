package com.orbitstudio.capture

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.orbitstudio.capture.ui.screens.BridgeScreen
import com.orbitstudio.capture.ui.screens.BundleScreen
import com.orbitstudio.capture.ui.screens.CaptureScreen
import com.orbitstudio.capture.ui.screens.DoneScreen
import com.orbitstudio.capture.ui.screens.FloorPlanScreen
import com.orbitstudio.capture.ui.screens.HomeScreen
import com.orbitstudio.capture.ui.screens.KuulaScreen
import com.orbitstudio.capture.ui.screens.ReviewScreen

@Composable
fun OrbitNavHost(nav: NavHostController = rememberNavController()) {
    NavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable("bridge") { BridgeScreen(nav) }
        composable("kuula") { KuulaScreen(nav) }
        composable("plan/{planId}") { backStackEntry ->
            FloorPlanScreen(nav, backStackEntry.arguments?.getString("planId").orEmpty())
        }
        composable("capture/{scanId}") { backStackEntry ->
            CaptureScreen(nav, backStackEntry.arguments?.getString("scanId").orEmpty())
        }
        composable("review/{scanId}") { backStackEntry ->
            ReviewScreen(nav, backStackEntry.arguments?.getString("scanId").orEmpty())
        }
        composable("bundle/{scanId}") { backStackEntry ->
            BundleScreen(nav, backStackEntry.arguments?.getString("scanId").orEmpty())
        }
        composable("done/{scanId}") { backStackEntry ->
            DoneScreen(nav, backStackEntry.arguments?.getString("scanId").orEmpty())
        }
    }
}
