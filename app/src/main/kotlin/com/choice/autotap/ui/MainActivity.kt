package com.choice.autotap.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.choice.autotap.appGraph
import com.choice.autotap.license.LicenseGate
import com.choice.autotap.ui.theme.ChoiceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChoiceTheme {
                LicenseGate(appGraph.license) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onEdit = { id -> nav.navigate("editor/$id") },
                                onLogs = { nav.navigate("logs") },
                                onSnippets = { nav.navigate("snippets") },
                                onSetup = { nav.navigate("setup") },
                            )
                        }
                        composable(
                            "editor/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.LongType }),
                        ) { entry ->
                            EditorScreen(
                                macroId = entry.arguments?.getLong("id") ?: 0L,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("logs") { LogsScreen(onBack = { nav.popBackStack() }) }
                        composable("snippets") { SnippetsScreen(onBack = { nav.popBackStack() }) }
                        composable("setup") { SetupScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
