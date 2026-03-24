package com.gari.yahdsell2.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) : ViewModel() {

    val currentUser = auth.currentUser

    // Profile Data
    private val _profileUser = MutableStateFlow<UserProfile?>(null)
    val profileUser: StateFlow<UserProfile?> = _profileUser.asStateFlow()

    private val _userProducts = MutableStateFlow<List<Product>>(emptyList())
    val userProducts: StateFlow<List<Product>> = _userProducts.asStateFlow()

    private val _isLoadingProfile = MutableStateFlow(false)
    val isLoadingProfile: StateFlow<Boolean> = _isLoadingProfile.asStateFlow()

    // Reviews
    private val _sellerReviews = MutableStateFlow<List<Review>>(emptyList())
    val sellerReviews: StateFlow<List<Review>> = _sellerReviews.asStateFlow()
    private val _isLoadingReviews = MutableStateFlow(false)
    val isLoadingReviews: StateFlow<Boolean> = _isLoadingReviews.asStateFlow()

    // Notifications
    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()
    private val _isLoadingNotifications = MutableStateFlow(false)
    val isLoadingNotifications: StateFlow<Boolean> = _isLoadingNotifications.asStateFlow()

    // Saved Searches
    private val _savedSearches = MutableStateFlow<List<SavedSearch>>(emptyList())
    val savedSearches: StateFlow<List<SavedSearch>> = _savedSearches.asStateFlow()
    private val _isLoadingSavedSearches = MutableStateFlow(false)
    val isLoadingSavedSearches: StateFlow<Boolean> = _isLoadingSavedSearches.asStateFlow()

    // Analytics
    private val _userListingsAnalytics = MutableStateFlow<List<ProductAnalytics>>(emptyList())
    val userListingsAnalytics: StateFlow<List<ProductAnalytics>> = _userListingsAnalytics.asStateFlow()
    private val _isLoadingAnalytics = MutableStateFlow(false)
    val isLoadingAnalytics: StateFlow<Boolean> = _isLoadingAnalytics.asStateFlow()

    // Admin
    private val _verificationRequests = MutableStateFlow<List<UserProfile>>(emptyList())
    val verificationRequests: StateFlow<List<UserProfile>> = _verificationRequests.asStateFlow()
    private val _isLoadingVerificationRequests = MutableStateFlow(false)
    val isLoadingVerificationRequests: StateFlow<Boolean> = _isLoadingVerificationRequests.asStateFlow()

    init {
        // Automatically fetch notifications if user is logged in
        if (currentUser != null) {
            fetchNotifications()
        }
    }

    // --- Profile & Products ---

    fun fetchUserProfile(userId: String) {
        viewModelScope.launch {
            _isLoadingProfile.value = true
            try {
                val userDoc = firestore.collection("users").document(userId).get().await()
                _profileUser.value = userDoc.toObject(UserProfile::class.java)

                // Fetch products for this user
                val productsQuery = firestore.collection("products")
                    .whereEqualTo("sellerId", userId)
                    .whereEqualTo("isPaid", true)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .get()
                    .await()

                _userProducts.value = productsQuery.toObjects(Product::class.java)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching profile", e)
            } finally {
                _isLoadingProfile.value = false
            }
        }
    }

    fun updateUserProfile(displayName: String, bio: String, imageUri: Uri?, onResult: (Boolean, String) -> Unit) {
        val userId = currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                var profilePicUrl = _profileUser.value?.profilePicUrl

                if (imageUri != null) {
                    val ref = storage.reference.child("profile_images/$userId/${UUID.randomUUID()}")
                    ref.putFile(imageUri).await()
                    profilePicUrl = ref.downloadUrl.await().toString()
                }

                val updates = mapOf(
                    "displayName" to displayName,
                    "bio" to bio,
                    "profilePicUrl" to profilePicUrl
                )

                firestore.collection("users").document(userId).update(updates).await()

                // Update local state
                _profileUser.value = _profileUser.value?.copy(
                    displayName = displayName,
                    bio = bio,
                    profilePicUrl = profilePicUrl
                )

                onResult(true, "Profile updated")
            } catch (e: Exception) {
                onResult(false, e.message ?: "Update failed")
            }
        }
    }

    fun markAsSold(productId: String) {
        viewModelScope.launch {
            try {
                firestore.collection("products").document(productId)
                    .update("isSold", true)
                    .await()

                // Update local list
                _userProducts.value = _userProducts.value.map {
                    if (it.id == productId) it.copy(isSold = true) else it
                }
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error marking as sold", e)
            }
        }
    }

    fun requestVerification(onResult: (Boolean, String) -> Unit) {
        val userId = currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                firestore.collection("users").document(userId)
                    .update("verificationRequested", true)
                    .await()

                _profileUser.value = _profileUser.value?.copy(verificationRequested = true)
                onResult(true, "Verification requested")
            } catch (e: Exception) {
                onResult(false, "Failed to request verification")
            }
        }
    }

    // --- Reviews ---

    fun fetchSellerReviews(sellerId: String) {
        viewModelScope.launch {
            _isLoadingReviews.value = true
            try {
                val snapshot = firestore.collection("users").document(sellerId)
                    .collection("reviews")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .get()
                    .await()
                _sellerReviews.value = snapshot.toObjects(Review::class.java)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching reviews", e)
                _sellerReviews.value = emptyList()
            } finally {
                _isLoadingReviews.value = false
            }
        }
    }

    fun postReview(sellerId: String, rating: Int, comment: String, onResult: (Boolean) -> Unit) {
        val user = currentUser ?: return
        viewModelScope.launch {
            try {
                val review = Review(
                    reviewerId = user.uid,
                    reviewerName = user.displayName ?: "Anonymous",
                    sellerId = sellerId, // ✅ FIXED: Changed from targetUserId to sellerId
                    rating = rating,
                    comment = comment,
                    timestamp = null // Server timestamp
                )

                // 1. Add review to subcollection
                firestore.collection("users").document(sellerId)
                    .collection("reviews").add(review).await()

                // 2. Recalculate average rating (Cloud Function usually does this, but for simplicity we simulate or rely on backend)
                // For now, assume backend triggers handle the aggregation update on the User document.

                onResult(true)
                // Refresh reviews
                fetchSellerReviews(sellerId)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error posting review", e)
                onResult(false)
            }
        }
    }

    // --- Notifications ---

    private fun fetchNotifications() {
        val userId = currentUser?.uid ?: return
        viewModelScope.launch {
            _isLoadingNotifications.value = true
            try {
                firestore.collection("users").document(userId)
                    .collection("notifications")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) return@addSnapshotListener
                        if (snapshot != null) {
                            _notifications.value = snapshot.toObjects(Notification::class.java).map { it.copy(id = it.id) } // Map ID if needed
                        }
                    }
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching notifications", e)
            } finally {
                _isLoadingNotifications.value = false
            }
        }
    }

    fun markNotificationRead(notificationId: String) {
        val userId = currentUser?.uid ?: return
        firestore.collection("users").document(userId)
            .collection("notifications").document(notificationId)
            .update("isRead", true)
    }

    fun clearAllNotifications(onResult: (Boolean, String) -> Unit) {
        val userId = currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                val batch = firestore.batch()
                val snapshot = firestore.collection("users").document(userId)
                    .collection("notifications").get().await()

                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.commit().await()
                onResult(true, "All notifications cleared")
            } catch (e: Exception) {
                onResult(false, "Failed to clear notifications")
            }
        }
    }

    // --- Saved Searches ---

    fun fetchSavedSearches() {
        val userId = currentUser?.uid ?: return
        viewModelScope.launch {
            _isLoadingSavedSearches.value = true
            try {
                val snapshot = firestore.collection("users").document(userId)
                    .collection("savedSearches")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .get().await()
                _savedSearches.value = snapshot.documents.mapNotNull {
                    it.toObject(SavedSearch::class.java)?.copy(id = it.id)
                }
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching saved searches", e)
            } finally {
                _isLoadingSavedSearches.value = false
            }
        }
    }

    fun deleteSavedSearch(searchId: String) {
        val userId = currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                firestore.collection("users").document(userId)
                    .collection("savedSearches").document(searchId)
                    .delete().await()

                // Update local list
                _savedSearches.value = _savedSearches.value.filter { it.id != searchId }
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error deleting search", e)
            }
        }
    }

    // --- Analytics ---

    fun fetchUserListingsAnalytics() {
        val userId = currentUser?.uid ?: return
        viewModelScope.launch {
            _isLoadingAnalytics.value = true
            try {
                // Fetch products first
                val productsSnapshot = firestore.collection("products")
                    .whereEqualTo("sellerId", userId)
                    .get().await()

                val analyticsData = productsSnapshot.toObjects(Product::class.java).map { product ->
                    ProductAnalytics(
                        viewCount = product.viewCount,
                        offerCount = 0,
                        wishlistCount = 0
                    )
                }
                _userListingsAnalytics.value = analyticsData
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching analytics", e)
            } finally {
                _isLoadingAnalytics.value = false
            }
        }
    }

    // --- Admin ---

    fun fetchVerificationRequests() {
        viewModelScope.launch {
            _isLoadingVerificationRequests.value = true
            try {
                val snapshot = firestore.collection("users")
                    .whereEqualTo("verificationRequested", true)
                    .whereEqualTo("isVerified", false)
                    .get().await()
                _verificationRequests.value = snapshot.toObjects(UserProfile::class.java)
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error fetching verification requests", e)
            } finally {
                _isLoadingVerificationRequests.value = false
            }
        }
    }

    fun approveVerification(userId: String) {
        viewModelScope.launch {
            try {
                firestore.collection("users").document(userId)
                    .update(
                        mapOf(
                            "isVerified" to true,
                            "verificationRequested" to false
                        )
                    ).await()

                // Remove from local list
                _verificationRequests.value = _verificationRequests.value.filter { it.uid != userId }
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error approving verification", e)
            }
        }
    }
}