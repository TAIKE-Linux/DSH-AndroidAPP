package com.dsh.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dsh.android.ui.screens.ChatScreen
import com.dsh.android.ui.screens.ServerListScreen
import com.dsh.android.ui.screens.SessionListScreen
import com.dsh.android.ui.theme.AppBackground
import com.dsh.android.ui.theme.DshTheme
import com.dsh.android.ui.viewmodel.ServersViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)
        setContent {
            DshTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Handle `dsh-gateway://connect?u=...&t=...` scanned from the PC panel QR.
     * Parsing/connecting is done by AppRoot so navigation can jump into chat.
     */
    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "dsh-gateway" || data.host != "connect") return
        (application as DshApp).container.setPendingDeepLink(data.toString())
    }
}

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val serversViewModel: ServersViewModel = viewModel()
    val appContext = LocalContext.current.applicationContext
    val container = (appContext as DshApp).container
    val background = container.backgroundStore.current
        .collectAsStateWithLifecycle().value
    val pendingDeepLink = container.pendingDeepLink.collectAsStateWithLifecycle().value

    // Re-connect to the last active server once at startup.
    LaunchedEffect(Unit) {
        serversViewModel.restoreActive()
    }

    // QR scan result (in-app camera or system camera deep link) → connect and
    // jump straight into the chat box.
    LaunchedEffect(pendingDeepLink) {
        val raw = pendingDeepLink ?: return@LaunchedEffect
        container.setPendingDeepLink(null)
        serversViewModel.connectDeepLink(raw) { sessionId ->
            navController.navigate("chat/$sessionId")
        }
    }

    val openChat: (String) -> Unit = { sessionId ->
        navController.navigate("chat/$sessionId")
    }

    AppBackground(background) {
        NavHost(navController = navController, startDestination = "servers") {
        composable("servers") {
            ServerListScreen(
                onOpenSessions = { navController.navigate("sessions") },
                onOpenChat = openChat,
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
}
