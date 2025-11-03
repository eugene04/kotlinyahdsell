package com.gari.yahdsell2.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date // Import Date
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val functions: FirebaseFunctions
) : ViewModel() {

    private val _profileUser = MutableStateFlow<UserProfile?>(null)
    val profileUser: StateFlow<UserProfile?> = _profileUser.asStateFlow()

    private val _userProducts = MutableStateFlow<List<Product>>(emptyList())
    val userProducts: StateFlow<List<Product>> = _userProducts.asStateFlow()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _isLoadingFollow = MutableStateFlow(false)
    val isLoadingFollow: StateFlow<Boolean> = _isLoadingFollow.asStateFlow()

    private val _verificationRequests = MutableStateFlow<List<UserProfile>>(emptyList())
    val verificationRequests: StateFlow<List<UserProfile>> = _verificationRequests.asStateFlow()

    private val _isLoadingVerificationRequests = MutableStateFlow(false)
    val isLoadingVerificationRequests: StateFlow<Boolean> = _isLoadingVerificationRequests.asStateFlow()

    private val _productAnalytics = MutableStateFlow<List<ProductAnalytics>>(emptyList())
    val productAnalytics: StateFlow<List<ProductAnalytics>> = _productAnalytics.asStateFlow()

    private val _isLoadingAnalytics = MutableStateFlow(false)
    val isLoadingAnalytics: StateFlow<Boolean> = _isLoadingAnalytics.asStateFlow()

    private val _swaps = MutableStateFlow(SwapsData())
    val swaps: StateFlow<SwapsData> = _swaps.asStateFlow()

    private val _isLoadingSwaps = MutableStateFlow(false)
    val isLoadingSwaps: StateFlow<Boolean> = _isLoadingSwaps.asStateFlow()

    private val _sellerReviews = MutableStateFlow<List<Review>>(emptyList())
    val sellerReviews: StateFlow<List<Review>> = _sellerReviews.asStateFlow()

    private val _isLoadingReviews = MutableStateFlow(false)
    val isLoadingReviews: StateFlow<Boolean> = _isLoadingReviews.asStateFlow()

    private val _offersForProduct = MutableStateFlow<List<Offer>>(emptyList())
    val offersForProduct: StateFlow<List<Offer>> = _offersForProduct.asStateFlow()

    private val _isLoadingOffers = MutableStateFlow(false)
    val isLoadingOffers: StateFlow<Boolean> = _isLoadingOffers.asStateFlow()

    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()

    private val _unreadNotificationCount = MutableStateFlow(0)
    val unreadNotificationCount: StateFlow<Int> = _unreadNotificationCount.asStateFlow()

    private val _isLoadingNotifications = MutableStateFlow(false)
    val isLoadingNotifications: StateFlow<Boolean> = _isLoadingNotifications.asStateFlow()

    private val _savedSearches = MutableStateFlow<List<SavedSearch>>(emptyList())
    val savedSearches: StateFlow<List<SavedSearch>> = _savedSearches.asStateFlow()

    private val _isLoadingSavedSearches = MutableStateFlow(false)
    val isLoadingSavedSearches: StateFlow<Boolean> = _isLoadingSavedSearches.asStateFlow()

    private var userProfileListener: ListenerRegistration? = null
    private var followListener: ListenerRegistration? = null
    private var sellerReviewsListener: ListenerRegistration? = null
    private var offersForProductListener: ListenerRegistration? = null
    private var notificationsListener: ListenerRegistration? = null
    private var savedSearchesListener: ListenerRegistration? = null

    // --- Init block to listen for notifications ---
    init {
        listenForNotifications() // Start listening when ViewModel is created
    }

    private fun callApi(action: String, data: Map<String, Any?>): com.google.android.gms.tasks.Task<com.google.firebase.functions.HttpsCallableResult> {
        val finalData = data.toMutableMap()
        auth.currentUser?.uid?.let {
            finalData["userId"] = it
            finalData["proposingUserId"] = it
            finalData["currentUserId"] = it
            finalData["sellerId"] = it
            finalData["adminId"] = it
        }
        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to finalData))
    }

    fun fetchUserProfileAndProducts(userId: String) {
        userProfileListener?.remove()
        userProfileListener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ProfileViewModel", "Error listening to user profile", error)
                    return@addSnapshotListener
                }
                _profileUser.value = snapshot?.toObject<UserProfile>()?.copy(uid = snapshot.id)
            }
        viewModelScope.launch {
            try {
                // Fetch ALL products initially for the profile screen tabs
                val productsSnapshot = firestore.collection("products")
                    .whereEqualTo("sellerId", userId)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .get().await()
                _userProducts.value = productsSnapshot.documents.mapNotNull { it.toObject<Product>()?.copy(id = it.id) }
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching user products", e)
                _userProducts.value = emptyList() // Ensure list is cleared on error
            }
        }
        listenForFollowStatus(userId)
    }

    private fun listenForFollowStatus(profileUserId: String) {
        val currentUser = auth.currentUser ?: return
        followListener?.remove()
        followListener = firestore.collection("users").document(currentUser.uid)
            .collection("following").document(profileUserId)
            .addSnapshotListener { snapshot, _ -> _isFollowing.value = snapshot != null && snapshot.exists() }
    }

    fun toggleFollow(profileUserId: String) {
        val currentUser = auth.currentUser ?: return
        if (currentUser.uid == profileUserId) return
        viewModelScope.launch {
            _isLoadingFollow.value = true
            try {
                val currentUserDocRef = firestore.collection("users").document(currentUser.uid)
                val targetUserDocRef = firestore.collection("users").document(profileUserId)
                val currentUserFollowingRef = currentUserDocRef.collection("following").document(profileUserId)
                val targetUserFollowersRef = targetUserDocRef.collection("followers").document(currentUser.uid)
                firestore.runTransaction { transaction ->
                    val increment = if (_isFollowing.value) -1L else 1L
                    transaction.update(currentUserDocRef, "followingCount", FieldValue.increment(increment))
                    transaction.update(targetUserDocRef, "followerCount", FieldValue.increment(increment))
                    if (_isFollowing.value) {
                        transaction.delete(currentUserFollowingRef)
                        transaction.delete(targetUserFollowersRef)
                    } else {
                        transaction.set(currentUserFollowingRef, mapOf("followedAt" to FieldValue.serverTimestamp()))
                        transaction.set(targetUserFollowersRef, mapOf("followedAt" to FieldValue.serverTimestamp()))
                    }
                    null
                }.await()
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error toggling follow", e)
            } finally {
                _isLoadingFollow.value = false
            }
        }
    }

    fun updateUserProfile(displayName: String, bio: String, imageUri: Uri?, onResult: (Boolean, String) -> Unit) {
        val currentUser = auth.currentUser ?: return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                var finalPhotoUrl = _profileUser.value?.profilePicUrl
                if (imageUri != null) {
                    val storagePath = "profile_pictures/${currentUser.uid}.jpg"
                    val storageRef = storage.reference.child(storagePath)
                    storageRef.putFile(imageUri).await()
                    finalPhotoUrl = storageRef.downloadUrl.await().toString()
                }
                val profileUpdates = userProfileChangeRequest {
                    this.displayName = displayName
                    this.photoUri = finalPhotoUrl?.let { Uri.parse(it) }
                }
                currentUser.updateProfile(profileUpdates).await()
                val userDocRef = firestore.collection("users").document(currentUser.uid)
                userDocRef.update(mapOf("displayName" to displayName, "bio" to bio, "profilePicUrl" to finalPhotoUrl)).await()
                onResult(true, "Profile updated successfully!")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error updating profile", e)
                onResult(false, e.message ?: "An error occurred.")
            }
        }
    }

    fun requestVerification(onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                callApi("requestVerification", emptyMap()).await()
                // Update local state optimistically or re-fetch profile
                _profileUser.value = _profileUser.value?.copy(verificationRequested = true)
                onResult(true, "Verification request sent!")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error requesting verification", e)
                onResult(false, e.message ?: "An error occurred.")
            }
        }
    }

    fun fetchVerificationRequests() {
        if (auth.currentUser == null) return
        _isLoadingVerificationRequests.value = true
        viewModelScope.launch {
            try {
                val snapshot = firestore.collection("users")
                    .whereEqualTo("verificationRequested", true)
                    .whereEqualTo("isVerified", false) // Ensure we only get pending ones
                    .get().await()
                _verificationRequests.value = snapshot.documents.mapNotNull { it.toObject<UserProfile>()?.copy(uid = it.id) }
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching verification requests", e)
            } finally {
                _isLoadingVerificationRequests.value = false
            }
        }
    }

    fun approveVerification(userId: String, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) return
        viewModelScope.launch {
            try {
                callApi("approveVerificationRequest", mapOf("userId" to userId)).await()
                // Remove from local list after successful approval
                _verificationRequests.value = _verificationRequests.value.filterNot { it.uid == userId }
                onResult(true, "User verified successfully.")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error approving verification", e)
                onResult(false, e.message ?: "An error occurred.")
            }
        }
    }

    fun markProductAsSold(productId: String, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) {
            onResult(false, "You must be logged in to perform this action.")
            return
        }
        viewModelScope.launch {
            try {
                callApi("markListingAsSold", mapOf("productId" to productId)).await()
                // Update local product list state
                _userProducts.value = _userProducts.value.map {
                    if (it.id == productId) it.copy(isSold = true) else it
                }
                onResult(true, "Listing marked as sold!")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error marking product as sold", e)
                if (e is FirebaseFunctionsException && e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                    onResult(false, "Your session has expired. Please log in again.")
                } else {
                    onResult(false, e.message ?: "An error occurred.")
                }
            }
        }
    }

    fun relistProduct(productId: String, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in to relist.")
        viewModelScope.launch {
            try {
                callApi("relistProduct", mapOf("productId" to productId)).await()
                // Update local product list state
                _userProducts.value = _userProducts.value.map {
                    if (it.id == productId) it.copy(isSold = false, soldAt = null) // Reset sold status
                    else it
                }
                onResult(true, "Listing has been relisted!")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error relisting product", e)
                onResult(false, e.message ?: "An error occurred.")
            }
        }
    }

    fun fetchProductAnalytics() {
        val currentUser = auth.currentUser ?: return
        _isLoadingAnalytics.value = true
        viewModelScope.launch {
            try {
                val now = Date()
                // ✅ FIX: Query only for active products for analytics
                val productsSnapshot = firestore.collection("products")
                    .whereEqualTo("sellerId", currentUser.uid)
                    .whereEqualTo("isSold", false) // Only unsold
                    .whereGreaterThan("expiresAt", now) // Only not expired
                    .orderBy("expiresAt", Query.Direction.DESCENDING) // Example ordering
                    .get().await()

                val analytics = productsSnapshot.documents.mapNotNull { doc ->
                    val product = doc.toObject<Product>()?.copy(id = doc.id)
                    if (product == null) return@mapNotNull null

                    // Fetch offer and wishlist counts (consider optimizing if this becomes slow)
                    val offersSnapshot = firestore.collection("products").document(product.id)
                        .collection("offers").get().await()

                    val wishlistSnapshot = firestore.collectionGroup("wishlist")
                        .whereEqualTo("productId", product.id).get().await()

                    ProductAnalytics(
                        product = product,
                        offerCount = offersSnapshot.size(),
                        wishlistCount = wishlistSnapshot.size()
                    )
                }
                _productAnalytics.value = analytics
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching product analytics", e)
                _productAnalytics.value = emptyList() // Clear on error
            } finally {
                _isLoadingAnalytics.value = false
            }
        }
    }


    fun fetchSwaps() {
        val currentUser = auth.currentUser ?: return
        _isLoadingSwaps.value = true
        viewModelScope.launch {
            try {
                // Fetch incoming swaps
                val incomingSnapshot = firestore.collection("swaps")
                    .whereEqualTo("targetUserId", currentUser.uid)
                    .orderBy("proposedAt", Query.Direction.DESCENDING)
                    .get().await()
                val incoming = incomingSnapshot.documents.mapNotNull { it.toObject<ProductSwap>()?.copy(id = it.id) }

                // Fetch outgoing swaps
                val outgoingSnapshot = firestore.collection("swaps")
                    .whereEqualTo("proposingUserId", currentUser.uid)
                    .orderBy("proposedAt", Query.Direction.DESCENDING)
                    .get().await()
                val outgoing = outgoingSnapshot.documents.mapNotNull { it.toObject<ProductSwap>()?.copy(id = it.id) }

                _swaps.value = SwapsData(incoming = incoming, outgoing = outgoing)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching swaps", e)
                _swaps.value = SwapsData() // Clear on error
            } finally {
                _isLoadingSwaps.value = false
            }
        }
    }

    fun proposeSwap(
        proposingProductId: String,
        targetProductId: String,
        cashTopUp: Double?,
        onResult: (Boolean, String) -> Unit
    ) {
        if (auth.currentUser == null) {
            return onResult(false, "You must be logged in.")
        }
        viewModelScope.launch {
            try {
                val data = hashMapOf(
                    "proposingProductId" to proposingProductId,
                    "targetProductId" to targetProductId,
                    "cashTopUp" to cashTopUp?.takeIf { it > 0 } // Send null if zero or less
                )
                callApi("proposeProductSwap", data).await()
                onResult(true, "Swap proposal sent successfully!")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error proposing swap", e)
                val message = if (e is FirebaseFunctionsException) e.message else "An unknown error occurred."
                onResult(false, message ?: "Failed to send proposal.")
            }
        }
    }

    fun respondToSwap(swapId: String, response: String, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) {
            return onResult(false, "You must be logged in.")
        }
        viewModelScope.launch {
            try {
                val data = hashMapOf("swapId" to swapId, "response" to response)
                callApi("respondToSwap", data).await()
                onResult(true, "Response sent successfully.")
                fetchSwaps() // Refresh the swaps list after responding
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error responding to swap", e)
                val message = if (e is FirebaseFunctionsException) e.message else "An unknown error occurred."
                onResult(false, message ?: "Failed to send response.")
            }
        }
    }


    fun fetchSellerReviews(sellerId: String) {
        _isLoadingReviews.value = true
        sellerReviewsListener?.remove() // Remove previous listener if any
        sellerReviewsListener = firestore.collection("reviews")
            .whereEqualTo("sellerId", sellerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ProfileViewModel", "Error fetching seller reviews", error)
                } else if (snapshot != null) {
                    _sellerReviews.value = snapshot.documents.mapNotNull { it.toObject<Review>()?.copy(id = it.id) }
                }
                _isLoadingReviews.value = false // Ensure loading stops even on error
            }
    }


    fun postReview(sellerId: String, rating: Int, comment: String, onResult: (Boolean, String) -> Unit) {
        val currentUser = auth.currentUser ?: return onResult(false, "You must be logged in.")
        if (currentUser.uid == sellerId) return onResult(false, "You cannot review yourself.")
        if (rating <= 0 || rating > 5) return onResult(false, "Rating must be between 1 and 5.")
        if (comment.isBlank()) return onResult(false, "Comment cannot be empty.")

        viewModelScope.launch {
            try {
                val review = Review(
                    sellerId = sellerId,
                    reviewerId = currentUser.uid,
                    reviewerName = currentUser.displayName ?: "Anonymous",
                    rating = rating,
                    comment = comment.trim() // Trim whitespace
                )
                // Use cloud function to handle rating update atomically
                val data = mapOf(
                    "sellerId" to sellerId,
                    "rating" to rating,
                    "comment" to comment.trim()
                )
                callApi("postReview", data).await()
                // Listener will update the UI, no need to manually add here
                onResult(true, "Review submitted successfully!")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error posting review", e)
                onResult(false, e.message ?: "Failed to submit review.")
            }
        }
    }


    fun listenForProductOffers(productId: String) {
        if (auth.currentUser == null) return
        _isLoadingOffers.value = true
        offersForProductListener?.remove() // Remove previous listener if any
        offersForProductListener = firestore.collection("products").document(productId).collection("offers")
            .orderBy("offerTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ProfileViewModel", "Error listening for offers", error)
                } else if (snapshot != null) {
                    _offersForProduct.value = snapshot.documents.mapNotNull { it.toObject<Offer>()?.copy(id = it.id) }
                }
                _isLoadingOffers.value = false // Ensure loading stops even on error
            }
    }


    fun updateOfferStatus(productId: String, offerId: String, status: String) {
        if (auth.currentUser == null) return
        if (!listOf("accepted", "rejected").contains(status)) {
            Log.w("ProfileViewModel", "Invalid status update requested: $status")
            return
        }
        viewModelScope.launch {
            try {
                // Use cloud function to handle side effects (like rejecting others)
                val data = mapOf(
                    "productId" to productId,
                    "offerId" to offerId,
                    "status" to status
                )
                callApi("respondToOffer", data).await()
                // Listener will update the UI
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error updating offer status via function", e)
                // Optionally show error to user
            }
        }
    }


    fun listenForNotifications() {
        val currentUser = auth.currentUser ?: return
        if (notificationsListener != null) return // Avoid attaching multiple listeners
        _isLoadingNotifications.value = true
        notificationsListener = firestore.collection("users").document(currentUser.uid)
            .collection("notifications").orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50) // Limit to avoid loading too many initially
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ProfileViewModel", "Error listening for notifications", error)
                } else if (snapshot != null) {
                    val notificationsList = snapshot.documents.mapNotNull { it.toObject<Notification>()?.copy(id = it.id) }
                    _notifications.value = notificationsList
                    _unreadNotificationCount.value = notificationsList.count { !it.isRead }
                }
                _isLoadingNotifications.value = false // Ensure loading stops
            }
    }

    fun markNotificationAsRead(notificationId: String) {
        val currentUser = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                firestore.collection("users").document(currentUser.uid)
                    .collection("notifications").document(notificationId)
                    .update("isRead", true).await()
                // Optimistic UI update (optional, listener will catch up)
                _notifications.value = _notifications.value.map {
                    if (it.id == notificationId) it.copy(isRead = true) else it
                }
                _unreadNotificationCount.value = _notifications.value.count { !it.isRead }
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error marking notification as read", e)
            }
        }
    }

    fun clearAllNotifications(onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                val result = callApi("clearAllNotifications", emptyMap()).await()
                val message = (result.data as? Map<String, Any?>)?.get("message") as? String ?: "Notifications cleared."
                // Listener will update the list to empty
                onResult(true, message)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error clearing all notifications", e)
                onResult(false, e.message ?: "An unknown error occurred.")
            }
        }
    }


    fun saveSearch(query: String, category: String, minPrice: String, maxPrice: String, condition: String, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) {
            return onResult(false, "You must be logged in to save searches.")
        }
        viewModelScope.launch {
            try {
                val data = hashMapOf(
                    "query" to query.takeIf { it.isNotBlank() },
                    "category" to category.takeIf { it != "All" },
                    "minPrice" to minPrice.toDoubleOrNull(),
                    "maxPrice" to maxPrice.toDoubleOrNull(),
                    "condition" to condition.takeIf { it != "Any Condition" }
                )
                callApi("saveSearch", data).await()
                onResult(true, "Search saved!")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error saving search", e)
                onResult(false, e.message ?: "Could not save search.")
            }
        }
    }

    fun fetchSavedSearches() {
        val currentUser = auth.currentUser ?: return
        _isLoadingSavedSearches.value = true
        savedSearchesListener?.remove() // Remove previous listener if any
        savedSearchesListener = firestore.collection("users").document(currentUser.uid)
            .collection("savedSearches")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ProfileViewModel", "Error fetching saved searches", error)
                } else if (snapshot != null) {
                    _savedSearches.value = snapshot.documents.mapNotNull { it.toObject<SavedSearch>()?.copy(id = it.id) }
                }
                _isLoadingSavedSearches.value = false // Ensure loading stops
            }
    }

    fun deleteSavedSearch(searchId: String) {
        val currentUser = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                firestore.collection("users").document(currentUser.uid)
                    .collection("savedSearches").document(searchId).delete().await()
                // Listener will update the list
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error deleting saved search", e)
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        // Remove all Firestore listeners to prevent memory leaks
        userProfileListener?.remove()
        followListener?.remove()
        sellerReviewsListener?.remove()
        offersForProductListener?.remove()
        notificationsListener?.remove()
        savedSearchesListener?.remove()
        Log.d("ProfileViewModel", "Listeners removed.")
    }
}
