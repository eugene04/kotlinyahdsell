package com.gari.yahdsell2.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gari.yahdsell2.screens.*

@Composable
fun AppNavigation(toggleTheme: () -> Unit) {
    val navController = rememberNavController()
    val startDestination = Screen.Main.route

    NavHost(navController = navController, startDestination = startDestination) {

        // --- Auth ---
        composable(Screen.Login.route) { LoginScreen(navController) }
        composable(Screen.Signup.route) { SignupScreen(navController) }

        // --- Main Screen (Home, Map, etc.) ---
        composable(
            route = "${Screen.Main.route}?showLogin={showLogin}",
            arguments = listOf(navArgument("showLogin") {
                type = NavType.BoolType
                defaultValue = false
            })
        ) { backStackEntry ->
            val showLogin = backStackEntry.arguments?.getBoolean("showLogin") ?: false
            MainScreen(
                mainNavController = navController,
                toggleTheme = toggleTheme,
                showLoginOnStart = showLogin
            )
        }

        // --- Simple Screens ---
        composable(Screen.Wishlist.route) { WishListScreen(navController) }
        composable(Screen.Analytics.route) { AnalyticsScreen(navController) }
        composable(Screen.Notifications.route) { NotificationsScreen(navController) }
        composable(Screen.Admin.route) { AdminScreen(navController) }
        composable(Screen.AdminFees.route) { AdminFeesScreen(navController) }
        composable(Screen.EditProfile.route) { EditProfileScreen(navController) }
        composable(Screen.Swaps.route) { SwapsScreen(navController) }
        composable(Screen.SavedSearches.route) { SavedSearchesScreen(navController) }

        // --- Product Detail ---
        composable(
            Screen.ProductDetail.route,
            arguments = listOf(navArgument("productId") { type = NavType.StringType })
        ) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId") ?: return@composable
            ProductDetailScreen(navController = navController, productId = productId)
        }

        // --- User Profile ---
        composable(
            Screen.UserProfile.route,
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: return@composable
            UserProfileScreen(navController = navController, userId = userId)
        }

        // --- Submission Form (Arguments are optional/nullable in the screen, so direct pass is fine) ---
        composable(
            Screen.Submit.route,
            arguments = listOf(
                navArgument("productIdToEdit") { nullable = true },
                navArgument("productToRelistJson") { nullable = true }
            )
        ) { backStackEntry ->
            val productIdToEdit = backStackEntry.arguments?.getString("productIdToEdit")
            val productToRelistJson = backStackEntry.arguments?.getString("productToRelistJson")
            SubmissionFormScreen(
                navController = navController,
                productIdToEdit = productIdToEdit,
                productToRelistJson = productToRelistJson
            )
        }

        // --- Payment (Arguments are optional/nullable in the screen) ---
        composable(
            Screen.Payment.route,
            arguments = listOf(
                navArgument("productId") { nullable = true },
                navArgument("productJson") { nullable = true }
            )
        ) { backStackEntry ->
            val productId = backStackEntry.arguments?.getString("productId")
            val productJson = backStackEntry.arguments?.getString("productJson")
            PaymentScreen(navController, productId = productId, productJson = productJson)
        }

        // --- Private Chat (Requires non-null strings) ---
        composable(
            Screen.PrivateChat.route,
            arguments = listOf(
                navArgument("recipientId") { type = NavType.StringType },
                navArgument("recipientName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            // ✅ FIX: Use Elvis operator (?: "") to ensure non-null String
            val recipientId = backStackEntry.arguments?.getString("recipientId") ?: ""
            val recipientName = backStackEntry.arguments?.getString("recipientName") ?: ""
            PrivateChatScreen(navController, recipientId = recipientId, recipientName = recipientName)
        }

        // --- Seller Reviews (Requires non-null strings) ---
        composable(
            Screen.SellerReviews.route,
            arguments = listOf(
                navArgument("sellerId") { type = NavType.StringType },
                navArgument("sellerName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            // ✅ FIX: Use Elvis operator
            val sellerId = backStackEntry.arguments?.getString("sellerId") ?: ""
            val sellerName = backStackEntry.arguments?.getString("sellerName") ?: ""
            SellerReviewScreen(navController, sellerId = sellerId, sellerName = sellerName)
        }

        // --- Offers (Requires non-null strings) ---
        composable(
            Screen.Offers.route,
            arguments = listOf(
                navArgument("productId") { type = NavType.StringType },
                navArgument("productName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            // ✅ FIX: Use Elvis operator
            val productId = backStackEntry.arguments?.getString("productId") ?: ""
            val productName = backStackEntry.arguments?.getString("productName") ?: ""
            OffersScreen(navController, productId = productId, productName = productName)
        }
    }
}