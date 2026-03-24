package com.gari.yahdsell2.model

import android.os.Parcelable
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import java.util.Date

@Parcelize
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val bio: String = "",
    val profilePicUrl: String? = null,

    @get:PropertyName("verified")
    @set:PropertyName("verified")
    var isVerified: Boolean = false,

    val verificationRequested: Boolean = false,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val averageRating: Double = 0.0,
    val ratingCount: Int = 0,
    @ServerTimestamp val createdAt: Date? = null,
    val followers: List<String> = emptyList(),
    val following: List<String> = emptyList(),

    @get:PropertyName("isAdmin")
    @set:PropertyName("isAdmin")
    var isAdmin: Boolean = false
) : Parcelable

@Parcelize
data class Notification(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val type: String = "",
    var recipientId: String = "",

    @get:PropertyName("isRead")
    @set:PropertyName("isRead")
    var isRead: Boolean = false,

    @ServerTimestamp val createdAt: Date? = null,
    val data: Map<String, String> = emptyMap()
) : Parcelable

@Parcelize
data class AuctionInfo(
    val startingPrice: Double = 0.0,
    val currentBid: Double? = null,
    val leadingBidderId: String? = null,
    @ServerTimestamp val endTime: Date? = null,
    val endTime_timestamp: Long? = null
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

    val sellerLocation: @WriteWith<NullableGeoPointParceler> GeoPoint? = null,
    val itemAddress: String? = null,

    @get:PropertyName("isAuction")
    @set:PropertyName("isAuction")
    var isAuction: Boolean = false,

    val auctionInfo: AuctionInfo? = null,
    val sellerId: String = "",
    val sellerDisplayName: String = "",
    val sellerProfilePicUrl: String? = null,

    @get:PropertyName("sellerIsVerified")
    @set:PropertyName("sellerIsVerified")
    var sellerIsVerified: Boolean = false,

    val sellerAverageRating: Double = 0.0,
    val sellerRatingCount: Int = 0,

    @get:PropertyName("isPaid")
    @set:PropertyName("isPaid")
    var isPaid: Boolean = false,

    @get:PropertyName("isSold")
    @set:PropertyName("isSold")
    var isSold: Boolean = false,

    val viewCount: Int = 0,

    @get:PropertyName("isFeatured")
    @set:PropertyName("isFeatured")
    var isFeatured: Boolean = false,

    @get:PropertyName("isPromoted")
    @set:PropertyName("isPromoted")
    var isPromoted: Boolean = false,

    val startingPrice: Double? = null,

    @get:Exclude
    @set:Exclude
    var isWishlisted: Boolean = false,

    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val expiresAt: Date? = null,
    val expiresAt_timestamp: Long? = null
) : Parcelable

@Parcelize
data class Offer(
    val id: String = "",
    val sellerId: String = "",
    val buyerId: String = "",
    val buyerName: String = "",
    val offerAmount: Double = 0.0,
    val status: String = "pending", // pending, accepted, rejected
    @ServerTimestamp val timestamp: Date? = null
) : Parcelable

@Parcelize
data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val type: String = "text", // text, system, image, location
    val text: String = "",
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val location: @WriteWith<NullableGeoPointParceler> GeoPoint? = null,
    @ServerTimestamp val timestamp: Date? = null
) : Parcelable

@Parcelize
data class PrivateChat(
    val id: String = "",
    @get:PropertyName("participantIds")
    @set:PropertyName("participantIds")
    var participants: List<String> = emptyList(),
    val lastMessage: String = "",
    @ServerTimestamp val lastActivity: Date? = null
) : Parcelable

@Parcelize
data class ChatbotMessage(
    val text: String,
    val role: String, // "user" or "model"
    val isThinking: Boolean = false
) : Parcelable

@Parcelize
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
) : Parcelable

@Parcelize
data class Bid(
    val id: String = "",
    val bidderId: String = "",
    val bidderName: String = "",
    val amount: Double = 0.0,
    @ServerTimestamp val timestamp: Date? = null
) : Parcelable

@Parcelize
data class SavedSearch(
    val id: String = "",
    val userId: String = "",
    val query: String? = null,
    val category: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val condition: String? = null,
    @ServerTimestamp val createdAt: Date? = null
) : Parcelable

@Parcelize
data class ProductAnalytics(
    val viewCount: Int = 0,
    val offerCount: Int = 0,
    val wishlistCount: Int = 0
) : Parcelable

@Parcelize
data class Review(
    val id: String = "",
    val sellerId: String = "",
    val reviewerId: String = "",
    val reviewerName: String = "",
    val rating: Int = 0,
    val comment: String = "",
    @ServerTimestamp val timestamp: Date? = null
) : Parcelable