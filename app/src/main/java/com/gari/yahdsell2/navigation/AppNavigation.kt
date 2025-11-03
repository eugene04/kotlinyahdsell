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
import com.gari.yahdsell2.screens.*
import com.gari.yahdsell2.viewmodel.AuthViewModel
import com.gari.yahdsell2.viewmodel.UserState

@Composable
fun AppNavigation(toggleTheme: () -> Unit) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val userState by authViewModel.userState.collectAsState()

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
                        // On Error, we default to the Login screen.
                        navController.navigate(Screen.Login.route) {
                            popUpTo("auth_handler") { inclusive = true }
                        }
                    }
                }
            }
            // Display nothing while handling auth state
        }

        composable(Screen.Login.route) {
            LoginScreen(navController = navController)
        }
        composable(Screen.Signup.route) {
            SignupScreen(navController = navController)
        }
        // This is the main entry point for logged-in users. It contains the bottom nav.
        composable(Screen.Main.route) {
            MainScreen(mainNavController = navController, toggleTheme = toggleTheme)
        }
        composable(Screen.ProductDetail.route) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            if (productId != null) {
                ProductDetailScreen(navController, productId = productId)
            }
        }
        composable(Screen.Submit.route) { backStackEntry ->
            val productJson = backStackEntry.arguments?.getString("productToRelistJson")
            SubmissionFormScreen(navController, productToRelistJson = productJson, productIdToEdit = null)
        }
        composable(Screen.EditProduct.route) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            SubmissionFormScreen(navController, productToRelistJson = null, productIdToEdit = productId)
        }
        composable(Screen.Payment.route) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            val productJson = backStackEntry.arguments?.getString("productJson")
            if (productJson != null && productId != null) {
                PaymentScreen(navController, viewModel = hiltViewModel(), productId = productId, productJson = productJson)
            }
        }
        composable(Screen.UserProfile.route) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId")
            if (userId != null) {
                UserProfileScreen(navController, userId = userId)
            }
        }
        composable(Screen.SellerReviews.route) { backStackEntry ->
            val sellerId = backStackEntry.arguments?.getString("sellerId")
            val sellerName = backStackEntry.arguments?.getString("sellerName")?.let { Uri.decode(it) }
            if (sellerId != null && sellerName != null) {
                SellerReviewScreen(navController, sellerId = sellerId, sellerName = sellerName)
            }
        }
        composable(Screen.Offers.route) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            val productName = backStackEntry.arguments?.getString("productName")?.let { Uri.decode(it) }
            if (productId != null && productName != null) {
                OffersScreen(navController, viewModel = hiltViewModel(), productId = productId, productName = productName)
            }
        }
        composable(Screen.EditProfile.route) {
            EditProfileScreen(navController)
        }
        composable(Screen.Admin.route) {
            AdminScreen(navController)
        }
        composable(Screen.AdminFees.route) { // Added Admin Fees route
            AdminFeesScreen(navController)
        }
        composable(Screen.Notifications.route) {
            NotificationsScreen(navController)
        }
        composable(Screen.Swaps.route) {
            SwapsScreen(navController = navController)
        }
        composable(Screen.PrivateChat.route) { backStackEntry ->
            val recipientId = backStackEntry.arguments?.getString("recipientId")
            val recipientName = backStackEntry.arguments?.getString("recipientName")?.let { Uri.decode(it) }
            if (recipientId != null && recipientName != null) {
                PrivateChatScreen(navController, recipientId = recipientId, recipientName = recipientName)
            }
        }
        composable(Screen.Wishlist.route) {
            WishlistScreen(navController = navController)
        }
        composable(Screen.Analytics.route) {
            AnalyticsScreen(navController = navController)
        }
        composable(Screen.SavedSearches.route) {
            SavedSearchesScreen(navController = navController)
        }
        // Note: NearMeScreen composable is defined within MainScreen's NavHost
    }
}

