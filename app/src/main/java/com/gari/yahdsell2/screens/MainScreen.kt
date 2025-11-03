package com.gari.yahdsell2.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NearMe // ✅ ADDED Icon import
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.AuthViewModel
import com.gari.yahdsell2.viewmodel.UserState

sealed class BottomNavItem(val route: String, val icon: ImageVector, val label: String) {
    object Home : BottomNavItem(Screen.Home.route, Icons.Default.Home, "Home")
    object Map : BottomNavItem(Screen.Map.route, Icons.Default.Map, "Map")
    // ✅ ADDED Near Me Item Definition
    object NearMe : BottomNavItem(Screen.NearMe.route, Icons.Default.NearMe, "Near Me")
    object ChatList : BottomNavItem(Screen.ChatList.route, Icons.AutoMirrored.Filled.Chat, "Chats")
    object Chatbot : BottomNavItem(Screen.Chatbot.route, Icons.Default.SmartToy, "AI Assistant")
}

@Composable
fun MainScreen(
    mainNavController: NavHostController,
    authViewModel: AuthViewModel = hiltViewModel(),
    toggleTheme: () -> Unit
) {
    val bottomNavController = rememberNavController()
    val userState by authViewModel.userState.collectAsState()

    Scaffold(
        bottomBar = {
            BottomNavigationBar(navController = bottomNavController)
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (userState is UserState.Authenticated) {
                        mainNavController.navigate(Screen.Submit.createRoute())
                    } else {
                        // Consider user feedback about mandatory login [2025-01-07]
                        mainNavController.navigate(Screen.Login.route)
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(Icons.Filled.Add, "Add Item", tint = MaterialTheme.colorScheme.onSecondary)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = bottomNavController,
            startDestination = BottomNavItem.Home.route // Keep Home as the default start
        ) {
            composable(BottomNavItem.Home.route) {
                HomeScreen(
                    navController = mainNavController,
                    toggleTheme = toggleTheme,
                    bottomNavPadding = innerPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
            composable(BottomNavItem.Map.route) {
                MapScreen(
                    navController = mainNavController,
                    bottomNavPadding = innerPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // ✅ ADDED Composable for the NearMeScreen
            composable(BottomNavItem.NearMe.route) {
                NearMeScreen(
                    navController = mainNavController,
                    bottomNavPadding = innerPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
            composable(BottomNavItem.ChatList.route) {
                ChatListScreen(
                    navController = mainNavController,
                    bottomNavPadding = innerPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
            composable(BottomNavItem.Chatbot.route) {
                ChatbotScreen(
                    bottomNavPadding = innerPadding,
                    modifier = Modifier.fillMaxSize()
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
        BottomNavItem.NearMe, // ✅ ADDED Near Me item to the list
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
