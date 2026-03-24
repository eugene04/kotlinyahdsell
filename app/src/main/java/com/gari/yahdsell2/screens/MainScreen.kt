package com.gari.yahdsell2.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gari.yahdsell2.navigation.Screen

// Define Bottom Navigation Items
sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector) {
    object Home : BottomNavItem(Screen.Home.route, "Home", Icons.Default.Home)
    object Map : BottomNavItem(Screen.Map.route, "Map", Icons.Default.Map)
    object NearMe : BottomNavItem(Screen.NearMe.route, "Near Me", Icons.Default.NearMe)
    object ChatList : BottomNavItem(Screen.ChatList.route, "Chats", Icons.AutoMirrored.Filled.Chat)
    object Chatbot : BottomNavItem(Screen.Chatbot.route, "Ask AI", Icons.Default.SmartToy)
}

@Composable
fun MainScreen(
    mainNavController: NavHostController, // Standardized name used in AppNavigation
    toggleTheme: () -> Unit,
    showLoginOnStart: Boolean = false // Added to handle the login prompt requirement
) {
    // This internal navController handles switching TABS (Home, Map, etc.)
    val bottomNavController = rememberNavController()

    // State to control the login dialog
    var showLoginDialog by remember { mutableStateOf(showLoginOnStart) }

    Scaffold(
        bottomBar = { BottomNavigationBar(bottomNavController) }
    ) { innerPadding ->

        // Show the prompt if requested (e.g. from RedirectToLogin)
        if (showLoginDialog) {
            LoginPromptDialog(
                onDismiss = { showLoginDialog = false },
                onLogin = {
                    showLoginDialog = false
                    // Navigate to the full login screen
                    mainNavController.navigate(Screen.Login.route)
                },
                onSignup = {
                    showLoginDialog = false
                    mainNavController.navigate(Screen.Signup.route)
                }
            )
        }

        // Host for Bottom Tabs
        NavHost(
            navController = bottomNavController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    navController = mainNavController, // Pass main nav for global navigation (details, profile)
                    toggleTheme = toggleTheme,
                    bottomNavPadding = innerPadding
                )
            }
            composable(Screen.Map.route) {
                MapScreen(
                    navController = mainNavController,
                    bottomNavPadding = innerPadding
                )
            }
            composable(Screen.NearMe.route) {
                NearMeScreen(
                    navController = mainNavController,
                    bottomNavPadding = innerPadding
                )
            }
            composable(Screen.ChatList.route) {
                ChatListScreen(
                    navController = mainNavController,
                    bottomNavPadding = innerPadding
                )
            }
            composable(Screen.Chatbot.route) {
                ChatbotScreen(
                    bottomNavPadding = innerPadding
                )
            }
        }
    }
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    val items = listOf(
        BottomNavItem.Home,
        BottomNavItem.Map,
        BottomNavItem.NearMe,
        BottomNavItem.ChatList,
        BottomNavItem.Chatbot,
    )

    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        items.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.label) },
                label = { Text(screen.label) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}