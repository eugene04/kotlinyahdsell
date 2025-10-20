package com.gari.yahdsell2.model

import android.os.Parcelable
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import java.util.Date

@Parcelize
data class AuctionInfo(
    val startingPrice: Double = 0.0,
    val currentBid: Double? = null,
    val leadingBidderId: String? = null,
    @ServerTimestamp val endTime: Date? = null
) : Parcelable

@Parcelize
data class Product(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val price: Double = 0.0,
    val category: String = "",
    val condition: String = "",
    val imageUrls: List<String> = emptyList(),
    val videoUrl: String? = null,
    val imageStoragePaths: List<String> = emptyList(),
    val videoStoragePath: String? = null,
    val sellerId: String = "",
    val sellerDisplayName: String = "",
    val sellerProfilePicUrl: String? = null,
    val sellerIsVerified: Boolean = false,
    val sellerAverageRating: Double = 0.0,
    val sellerLocation: @RawValue GeoPoint? = null,
    val geohash: String? = null,
    val itemAddress: String? = null,
    val distanceKm: Double? = null,
    @get:PropertyName("isSold") val isSold: Boolean = false,
    @get:PropertyName("isPaid") val isPaid: Boolean = false,
    val auctionInfo: @RawValue AuctionInfo? = null,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val paidAt: Date? = null,
    @ServerTimestamp val soldAt: Date? = null,
    @ServerTimestamp val expiresAt: Date? = null,
    val viewCount: Int = 0,
    @get:PropertyName("isFeatured") val isFeatured: Boolean = false,
    @ServerTimestamp val lastBumpedAt: Date? = null
) : Parcelable

@Parcelize
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val bio: String = "",
    val profilePicUrl: String? = null,
    val averageRating: Double = 0.0,
    val ratingCount: Int = 0,
    val isVerified: Boolean = false,
    val verificationRequested: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null,
    val followerCount: Int = 0,
    val followingCount: Int = 0
) : Parcelable

data class Comment(
    val id: String = "",
    val text: String = "",
    val userId: String = "",
    val userName: String = "",
    val userPhotoURL: String? = null,
    @ServerTimestamp val timestamp: Date? = null
)

data class Offer(
    val id: String = "",
    val buyerId: String = "",
    val buyerName: String = "",
    val sellerId: String = "",
    val offerAmount: Double = 0.0,
    val status: String = "pending", // pending, accepted, rejected
    @ServerTimestamp val offerTimestamp: Date? = null
)

data class Review(
    val id: String = "",
    val sellerId: String = "",
    val reviewerId: String = "",
    val reviewerName: String = "",
    val rating: Int = 0,
    val comment: String = "",
    @ServerTimestamp val createdAt: Date? = null
)

data class Notification(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val type: String = "",
    val data: Map<String, String> = emptyMap(),
    @get:PropertyName("isRead") val isRead: Boolean = false,
    val recipientId: String = "",
    @ServerTimestamp val createdAt: Date? = null
)

data class ChatMessage(
    val id: String = "",
    val type: String = "text", // "text", "image", "video", "system", "location"
    val text: String = "",
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val location: @RawValue GeoPoint? = null,
    val senderId: String = "",
    @ServerTimestamp val timestamp: Date? = null
)

data class PrivateChat(
    val id: String = "",
    val participantIds: List<String> = emptyList(),
    val lastMessage: String = "",
    @ServerTimestamp val lastActivity: Date? = null,
    val participants: Map<String, UserProfile> = emptyMap()
)

data class ChatbotMessage(
    val text: String,
    val role: String, // "user" or "model"
    val isThinking: Boolean = false
)

data class ProductAnalytics(
    val product: Product,
    val offerCount: Int = 0,
    val wishlistCount: Int = 0
)

data class ProductSwap(
    val id: String = "",
    val proposingUserId: String = "",
    val proposingProductId: String = "",
    val proposingProductName: String? = null,
    val proposingProductImageUrl: String? = null,
    val targetUserId: String = "",
    val targetProductId: String = "",
    val targetProductName: String? = null,
    val targetProductImageUrl: String? = null,
    val status: String = "pending", // pending, accepted, rejected
    val cashTopUp: Double? = null,
    @ServerTimestamp val proposedAt: Date? = null
)

data class Bid(
    val id: String = "",
    val bidderId: String = "",
    val bidderName: String = "",
    val amount: Double = 0.0,
    @ServerTimestamp val timestamp: Date? = null
)

data class SavedSearch(
    val id: String = "",
    val userId: String = "",
    val query: String? = null,
    val category: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val condition: String? = null,
    @ServerTimestamp val createdAt: Date? = null
)

