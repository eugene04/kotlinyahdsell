package com.gari.yahdsell2.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
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
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.UserState
import com.gari.yahdsell2.navigation.Screen

sealed class BottomNavItem(val route: String, val icon: ImageVector, val label: String) {
    object Home : BottomNavItem(Screen.Home.route, Icons.Default.Home, "Home")
    object Map : BottomNavItem(Screen.Map.route, Icons.Default.Map, "Map") // ✅ ADDED: Map item
    object ChatList : BottomNavItem(Screen.ChatList.route, Icons.AutoMirrored.Filled.Chat, "Chats")
    object Chatbot : BottomNavItem(Screen.Chatbot.route, Icons.Default.SmartToy, "AI Assistant")
}

@Composable
fun MainScreen(
    mainNavController: NavHostController,
    viewModel: MainViewModel = hiltViewModel(),
    toggleTheme: () -> Unit
) {
    val bottomNavController = rememberNavController()
    val userState by viewModel.userState.collectAsState()

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
            startDestination = BottomNavItem.Home.route
        ) {
            composable(BottomNavItem.Home.route) {
                HomeScreen(
                    navController = mainNavController,
                    viewModel = viewModel,
                    toggleTheme = toggleTheme,
                    bottomNavPadding = innerPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // ✅ ADDED: Composable for the new MapScreen
            composable(BottomNavItem.Map.route) {
                MapScreen(
                    navController = mainNavController,
                    viewModel = viewModel,
                    bottomNavPadding = innerPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
            composable(BottomNavItem.ChatList.route) {
                ChatListScreen(
                    navController = mainNavController,
                    viewModel = viewModel,
                    bottomNavPadding = innerPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
            composable(BottomNavItem.Chatbot.route) {
                ChatbotScreen(
                    viewModel = viewModel,
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
        BottomNavItem.Map, // ✅ ADDED: Map item
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

