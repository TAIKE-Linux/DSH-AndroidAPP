package com.dsh.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dsh.android.ui.screens.ChatScreen
import com.dsh.android.ui.screens.ServerListScreen
import com.dsh.android.ui.screens.SessionListScreen
import com.dsh.android.ui.theme.DshTheme
import com.dsh.android.ui.viewmodel.ServersViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DshTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val serversViewModel: ServersViewModel = viewModel()

    // Re-connect to the last active server once at startup.
    LaunchedEffect(Unit) {
        serversViewModel.restoreActive()
    }

    NavHost(navController = navController, startDestination = "servers") {
        composable("servers") {
            ServerListScreen(
                onOpenSessions = { navController.navigate("sessions") },
                viewModel = serversViewModel,
            )
        }
        composable("sessions") {
            SessionListScreen(
                onOpenSession = { sessionId ->
                    navController.navigate("chat/$sessionId")
                },
                onManageServers = { navController.popBackStack() },
            )
        }
        composable(
            route = "chat/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            ChatScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
