package com.gari.yahdsell2.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.UserState
import com.gari.yahdsell2.screens.*

@Composable
fun AppNavigation(toggleTheme: () -> Unit) {
    val navController = rememberNavController()
    val viewModel: MainViewModel = hiltViewModel()
    val userState by viewModel.userState.collectAsState()

    // The NavHost's start destination will now be determined by the user's auth state.
    // However, we will use a "blank" loading screen as the technical start destination
    // to avoid screen flicker while the auth state is being resolved.
    val startDestination = "auth_handler"

    NavHost(navController = navController, startDestination = startDestination) {

        // This composable acts as a gatekeeper. It checks the auth state and navigates
        // to the correct screen (Login or Main) immediately.
        composable("auth_handler") {
            LaunchedEffect(userState) {
                when (userState) {
                    is UserState.Authenticated -> {
                        navController.navigate(Screen.Main.route) {
                            popUpTo("auth_handler") { inclusive = true }
                        }
                    }
                    is UserState.Unauthenticated -> {
                        navController.navigate(Screen.Login.route) {
                            popUpTo("auth_handler") { inclusive = true }
                        }
                    }
                    is UserState.Loading -> {
                        // While loading, it shows a blank screen, which is fine
                        // as it's a very brief state on app open.
                    }
                    is UserState.Error -> {
                        navController.navigate(Screen.Login.route) {
                            popUpTo("auth_handler") { inclusive = true }
                        }
                    }
                }
            }
        }

        composable(Screen.Login.route) {
            LoginScreen(navController = navController, viewModel = viewModel)
        }
        composable(Screen.Signup.route) {
            SignupScreen(navController = navController, viewModel = viewModel)
        }
        // This is the main entry point for logged-in users. It contains the bottom nav.
        composable(Screen.Main.route) {
            MainScreen(mainNavController = navController, viewModel = viewModel, toggleTheme = toggleTheme)
        }
        composable(Screen.ProductDetail.route) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            if (productId != null) {
                ProductDetailScreen(navController, viewModel, productId)
            }
        }
        composable(Screen.Submit.route) { backStackEntry ->
            val productJson = backStackEntry.arguments?.getString("productToRelistJson")
            SubmissionFormScreen(navController, viewModel, productToRelistJson = productJson, productIdToEdit = null)
        }
        composable(Screen.EditProduct.route) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            SubmissionFormScreen(navController, viewModel, productToRelistJson = null, productIdToEdit = productId)
        }
        composable(Screen.Payment.route) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            val productJson = backStackEntry.arguments?.getString("productJson")
            if (productJson != null && productId != null) {
                PaymentScreen(navController, viewModel, productId, productJson)
            }
        }
        composable(Screen.UserProfile.route) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId")
            if (userId != null) {
                UserProfileScreen(navController, viewModel, userId)
            }
        }
        composable(Screen.SellerReviews.route) { backStackEntry ->
            val sellerId = backStackEntry.arguments?.getString("sellerId")
            val sellerName = backStackEntry.arguments?.getString("sellerName")?.let { Uri.decode(it) }
            if (sellerId != null && sellerName != null) {
                SellerReviewScreen(navController, viewModel, sellerId, sellerName)
            }
        }
        composable(Screen.Offers.route) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            val productName = backStackEntry.arguments?.getString("productName")?.let { Uri.decode(it) }
            if (productId != null && productName != null) {
                OffersScreen(navController, viewModel, productId, productName)
            }
        }
        composable(Screen.EditProfile.route) {
            EditProfileScreen(navController, viewModel)
        }
        composable(Screen.Admin.route) {
            AdminScreen(navController, viewModel)
        }
        composable(Screen.Notifications.route) {
            NotificationsScreen(navController, viewModel)
        }
        composable(Screen.Swaps.route) {
            SwapsScreen(navController = navController, viewModel = viewModel)
        }
        composable(Screen.PrivateChat.route) { backStackEntry ->
            val recipientId = backStackEntry.arguments?.getString("recipientId")
            val recipientName = backStackEntry.arguments?.getString("recipientName")?.let { Uri.decode(it) }
            if (recipientId != null && recipientName != null) {
                PrivateChatScreen(navController, viewModel, recipientId, recipientName)
            }
        }
        composable(Screen.Wishlist.route) {
            WishlistScreen(navController = navController, viewModel = viewModel)
        }
        composable(Screen.Analytics.route) {
            AnalyticsScreen(navController = navController, viewModel = viewModel)
        }
        composable(Screen.SavedSearches.route) {
            SavedSearchesScreen(navController = navController, viewModel = viewModel)
        }
    }
}
