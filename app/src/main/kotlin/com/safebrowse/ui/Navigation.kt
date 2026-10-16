package com.safebrowse.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.safebrowse.gate.GateIntent
import com.safebrowse.gate.Romans6GateScreen
import com.safebrowse.ui.screens.BlocklistScreen
import com.safebrowse.ui.screens.DashboardScreen
import com.safebrowse.ui.screens.SettingsScreen
import com.safebrowse.viewmodel.BlocklistViewModel
import com.safebrowse.viewmodel.DashboardViewModel

object NavRoutes {
    const val DASHBOARD = "dashboard"
    const val BLOCKLIST = "blocklist"
    const val SETTINGS  = "settings"

    /** Gate in front of removing one domain. */
    const val GATE_UNBLOCK = "gate/unblock/{domainId}/{domain}"
    fun gateUnblock(id: Long, domain: String) = "gate/unblock/$id/${Uri.encode(domain)}"

    /** Gate in front of turning filtering off entirely. */
    const val GATE_STOP = "gate/stop"
}

@Composable
fun SafeBrowseNavGraph(navController: NavHostController) {
    // Outside the NavHost this is the Activity. The dashboard and the stop gate
    // share the Activity's DashboardViewModel: MainActivity collects its
    // VPN-consent Intent, and a per-screen instance would emit it to nobody.
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current)

    NavHost(
        navController = navController,
        startDestination = NavRoutes.DASHBOARD
    ) {
        composable(NavRoutes.DASHBOARD) {
            DashboardScreen(
                onNavigateToBlocklist = { navController.navigate(NavRoutes.BLOCKLIST) },
                onNavigateToSettings  = { navController.navigate(NavRoutes.SETTINGS) },
                onRequestStopProtection = { navController.navigate(NavRoutes.GATE_STOP) },
                viewModel = hiltViewModel(activityOwner)
            )
        }

        composable(NavRoutes.BLOCKLIST) {
            BlocklistScreen(
                onNavigateUp = { navController.popBackStack() },
                onRequestUnblock = { domain ->
                    navController.navigate(NavRoutes.gateUnblock(domain.id, domain.domain))
                }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(onNavigateUp = { navController.popBackStack() })
        }

        // ── The gate ─────────────────────────────────────────────────────────
        // Both routes reach the same screen. Only the closing button differs.

        composable(
            route = NavRoutes.GATE_UNBLOCK,
            arguments = listOf(
                navArgument("domainId") { type = NavType.LongType },
                navArgument("domain")   { type = NavType.StringType }
            )
        ) { entry ->
            val id = entry.arguments?.getLong("domainId") ?: 0L
            // Navigation has already decoded the argument; decoding again would
            // mangle any literal "%" sequence.
            val domain = entry.arguments?.getString("domain").orEmpty()

            // Scoped to the blocklist entry so the removal lands in the same
            // ViewModel that owns the list. The gate's own entry is popped right
            // after onPassed, which would cancel the delete mid-flight.
            val blocklistEntry = remember(entry) { navController.getBackStackEntry(NavRoutes.BLOCKLIST) }
            val vm: BlocklistViewModel = hiltViewModel(blocklistEntry)

            Romans6GateScreen(
                intent = GateIntent.UnblockDomain(id, domain),
                onPassed = {
                    vm.confirmUnblock(id, domain)
                    navController.popBackStack()
                },
                onAbandoned = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.GATE_STOP) {
            val vm: DashboardViewModel = hiltViewModel(activityOwner)
            Romans6GateScreen(
                intent = GateIntent.StopProtection,
                onPassed = {
                    vm.confirmStopProtection()
                    navController.popBackStack()
                },
                onAbandoned = { navController.popBackStack() }
            )
        }
    }
}
