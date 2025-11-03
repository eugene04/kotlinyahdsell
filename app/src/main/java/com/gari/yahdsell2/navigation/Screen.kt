package com.gari.yahdsell2.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    // Top-level destinations that manage their own UI without the main bottom navigation bar
    object Login : Screen("login")
    object Signup : Screen("signup")
    object Main : Screen("main") // This is the container for screens that DO have the bottom nav

    // Screens that are part of the Bottom Navigation within the Main screen
    object Home : Screen("home")
    object Map : Screen("map")
    object NearMe : Screen("near_me") // Added Near Me screen
    object ChatList : Screen("chat_list")
    object Chatbot : Screen("chatbot")


    // Other screens reachable from various points in the app
    object Wishlist : Screen("wishlist") // Moved from bottom nav section
    object Analytics : Screen("analytics")
    object Notifications : Screen("notifications")
    object Admin : Screen("admin")
    object AdminFees : Screen("admin_fees") // Added Admin Fees screen
    object EditProfile : Screen("edit_profile")
    object Swaps : Screen("swaps")
    object SavedSearches : Screen("saved_searches")

    // Route for editing a product
    object EditProduct : Screen("edit_product/{productId}") {
        fun createRoute(productId: String) = "edit_product/$productId"
    }

    object Submit : Screen("submit?productToRelistJson={productToRelistJson}") {
        fun createRoute(productJson: String? = null): String {
            return if (productJson != null) "submit?productToRelistJson=$productJson" else "submit"
        }
    }

    object Payment : Screen("payment/{productId}/{productJson}") {
        fun createRoute(productId: String, productJson: String) = "payment/$productId/$productJson"
    }

    object ProductDetail : Screen("product_detail/{productId}") {
        fun createRoute(productId: String) = "product_detail/$productId"
    }

    object UserProfile : Screen("user_profile/{userId}") {
        fun createRoute(userId: String) = "user_profile/$userId"
    }

    object SellerReviews : Screen("seller_reviews/{sellerId}/{sellerName}") {
        fun createRoute(sellerId: String, sellerName: String): String {
            val encodedName = Uri.encode(sellerName)
            return "seller_reviews/$sellerId/$encodedName"
        }
    }

    object Offers : Screen("offers/{productId}/{productName}") {
        fun createRoute(productId: String, productName: String): String {
            val encodedName = Uri.encode(productName)
            return "offers/$productId/$encodedName"
        }
    }

    object PrivateChat: Screen("private_chat/{recipientId}/{recipientName}") {
        fun createRoute(recipientId: String, recipientName: String): String {
            val encodedName = Uri.encode(recipientName)
            return "private_chat/$recipientId/$encodedName"
        }
    }
}

