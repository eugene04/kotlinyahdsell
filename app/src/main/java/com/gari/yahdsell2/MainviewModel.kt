package com.gari.yahdsell2

import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.*
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
import java.util.*
import javax.inject.Inject
import kotlin.math.*

data class AiSuggestions(val description: String, val category: String)
data class SwapsData(val incoming: List<ProductSwap> = emptyList(), val outgoing: List<ProductSwap> = emptyList())


@HiltViewModel
class MainViewModel @Inject constructor(
    val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val storage: FirebaseStorage
) : ViewModel() {

    // --- State ---
    private val _userState = MutableStateFlow<UserState>(UserState.Loading)
    val userState: StateFlow<UserState> = _userState.asStateFlow()

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _selectedProduct = MutableStateFlow<Product?>(null)
    val selectedProduct: StateFlow<Product?> = _selectedProduct.asStateFlow()

    private val _sellerProfile = MutableStateFlow<UserProfile?>(null)
    val sellerProfile: StateFlow<UserProfile?> = _sellerProfile.asStateFlow()

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _userHasPendingOffer = MutableStateFlow(false)
    val userHasPendingOffer: StateFlow<Boolean> = _userHasPendingOffer.asStateFlow()

    private val _isProductInWishlist = MutableStateFlow(false)
    val isProductInWishlist: StateFlow<Boolean> = _isProductInWishlist.asStateFlow()

    private val _profileUser = MutableStateFlow<UserProfile?>(null)
    val profileUser: StateFlow<UserProfile?> = _profileUser.asStateFlow()

    private val _userProducts = MutableStateFlow<List<Product>>(emptyList())
    val userProducts: StateFlow<List<Product>> = _userProducts.asStateFlow()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _isLoadingFollow = MutableStateFlow(false)
    val isLoadingFollow: StateFlow<Boolean> = _isLoadingFollow.asStateFlow()

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isUploadingMedia = MutableStateFlow(false)
    val isUploadingMedia: StateFlow<Boolean> = _isUploadingMedia.asStateFlow()

    private val _chatList = MutableStateFlow<List<PrivateChat>>(emptyList())
    val chatList: StateFlow<List<PrivateChat>> = _chatList.asStateFlow()

    private val _isLoadingChatList = MutableStateFlow(false)
    val isLoadingChatList: StateFlow<Boolean> = _isLoadingChatList.asStateFlow()

    private val _chatbotMessages = MutableStateFlow<List<ChatbotMessage>>(listOf(
        ChatbotMessage(text = "Hello! How can I assist you with YahdSell today?", role = "model")
    ))
    val chatbotMessages: StateFlow<List<ChatbotMessage>> = _chatbotMessages.asStateFlow()

    private val _wishlistItems = MutableStateFlow<List<Product>>(emptyList())
    val wishlistItems: StateFlow<List<Product>> = _wishlistItems.asStateFlow()

    private val _isLoadingWishlist = MutableStateFlow(false)
    val isLoadingWishlist: StateFlow<Boolean> = _isLoadingWishlist.asStateFlow()

    private val _wishlistError = MutableStateFlow<String?>(null)
    val wishlistError: StateFlow<String?> = _wishlistError.asStateFlow()

    private val _aiSuggestions = MutableStateFlow<AiSuggestions?>(null)
    val aiSuggestions: StateFlow<AiSuggestions?> = _aiSuggestions.asStateFlow()

    private val _isGeneratingSuggestions = MutableStateFlow(false)
    val isGeneratingSuggestions: StateFlow<Boolean> = _isGeneratingSuggestions.asStateFlow()

    private val _offersForProduct = MutableStateFlow<List<Offer>>(emptyList())
    val offersForProduct: StateFlow<List<Offer>> = _offersForProduct.asStateFlow()

    private val _isLoadingOffers = MutableStateFlow(false)
    val isLoadingOffers: StateFlow<Boolean> = _isLoadingOffers.asStateFlow()

    private val _sellerReviews = MutableStateFlow<List<Review>>(emptyList())
    val sellerReviews: StateFlow<List<Review>> = _sellerReviews.asStateFlow()

    private val _isLoadingReviews = MutableStateFlow(false)
    val isLoadingReviews: StateFlow<Boolean> = _isLoadingReviews.asStateFlow()

    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()

    private val _unreadNotificationCount = MutableStateFlow(0)
    val unreadNotificationCount: StateFlow<Int> = _unreadNotificationCount.asStateFlow()

    private val _isLoadingNotifications = MutableStateFlow(false)
    val isLoadingNotifications: StateFlow<Boolean> = _isLoadingNotifications.asStateFlow()

    private val _isUpdatingProfile = MutableStateFlow(false)
    val isUpdatingProfile: StateFlow<Boolean> = _isUpdatingProfile.asStateFlow()

    private val _verificationRequests = MutableStateFlow<List<UserProfile>>(emptyList())
    val verificationRequests: StateFlow<List<UserProfile>> = _verificationRequests.asStateFlow()

    private val _isLoadingVerificationRequests = MutableStateFlow(false)
    val isLoadingVerificationRequests: StateFlow<Boolean> = _isLoadingVerificationRequests.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _productAnalytics = MutableStateFlow<List<ProductAnalytics>>(emptyList())
    val productAnalytics: StateFlow<List<ProductAnalytics>> = _productAnalytics.asStateFlow()

    private val _isLoadingAnalytics = MutableStateFlow(false)
    val isLoadingAnalytics: StateFlow<Boolean> = _isLoadingAnalytics.asStateFlow()

    private val _swaps = MutableStateFlow(SwapsData())
    val swaps: StateFlow<SwapsData> = _swaps.asStateFlow()

    private val _isLoadingSwaps = MutableStateFlow(false)
    val isLoadingSwaps: StateFlow<Boolean> = _isLoadingSwaps.asStateFlow()

    private val _bids = MutableStateFlow<List<Bid>>(emptyList())
    val bids: StateFlow<List<Bid>> = _bids.asStateFlow()

    private val _isLoadingBids = MutableStateFlow(false)
    val isLoadingBids: StateFlow<Boolean> = _isLoadingBids.asStateFlow()

    private val _savedSearches = MutableStateFlow<List<SavedSearch>>(emptyList())
    val savedSearches: StateFlow<List<SavedSearch>> = _savedSearches.asStateFlow()

    private val _isLoadingSavedSearches = MutableStateFlow(false)
    val isLoadingSavedSearches: StateFlow<Boolean> = _isLoadingSavedSearches.asStateFlow()

    private val _productToEdit = MutableStateFlow<Product?>(null)
    val productToEdit: StateFlow<Product?> = _productToEdit.asStateFlow()

    private val _visualSearchSuggestion = MutableStateFlow<Pair<String, String>?>(null)
    val visualSearchSuggestion: StateFlow<Pair<String, String>?> = _visualSearchSuggestion.asStateFlow()

    private val _isProcessingVisualSearch = MutableStateFlow(false)
    val isProcessingVisualSearch: StateFlow<Boolean> = _isProcessingVisualSearch.asStateFlow()


    // --- Listeners ---
    private var commentsListener: ListenerRegistration? = null
    private var offersListener: ListenerRegistration? = null
    private var wishlistListener: ListenerRegistration? = null
    private var followListener: ListenerRegistration? = null
    private var userProfileListener: ListenerRegistration? = null
    private var chatListener: ListenerRegistration? = null
    private var offersForProductListener: ListenerRegistration? = null
    private var sellerReviewsListener: ListenerRegistration? = null
    private var notificationsListener: ListenerRegistration? = null
    private var bidsListener: ListenerRegistration? = null
    private var savedSearchesListener: ListenerRegistration? = null


    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _userState.value = if (user != null) {
                UserState.Authenticated(user)
            } else {
                UserState.Unauthenticated
            }
        }

        viewModelScope.launch {
            userState.collect { state ->
                if (state is UserState.Authenticated) {
                    listenForNotifications()
                    checkAdminStatus()
                } else {
                    stopNotificationsListener()
                    _isAdmin.value = false
                }
            }
        }
    }

    fun updateUserLocation(location: Location?) {
        _userLocation.value = location
    }

    // --- Authentication & Push Token ---
    private fun checkCurrentUser() {
        val user = auth.currentUser
        _userState.value = if (user != null) UserState.Authenticated(user) else UserState.Unauthenticated
    }

    fun savePushToken(token: String) {
        val currentUser = auth.currentUser ?: return
        val tokenRef = firestore.collection("users").document(currentUser.uid)
            .collection("pushTokens").document(token)
        tokenRef.set(mapOf("token" to token, "timestamp" to FieldValue.serverTimestamp()))
            .addOnSuccessListener { Log.d("MainViewModel", "Push token saved successfully.") }
            .addOnFailureListener { e -> Log.e("MainViewModel", "Error saving push token", e) }
    }


    fun signOut() {
        auth.signOut()
        _userState.value = UserState.Unauthenticated
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _userState.value = UserState.Loading
            try {
                val result = auth.signInWithEmailAndPassword(email, password).await()
                _userState.value = result.user?.let { UserState.Authenticated(it) }
                    ?: UserState.Error("Authentication failed.")
            } catch (e: Exception) {
                _userState.value = UserState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun signUp(email: String, password: String, displayName: String) {
        viewModelScope.launch {
            _userState.value = UserState.Loading
            try {
                val authResult = auth.createUserWithEmailAndPassword(email, password).await()
                val firebaseUser = authResult.user
                    ?: throw IllegalStateException("Firebase user not found")
                val profileUpdates = userProfileChangeRequest { this.displayName = displayName }
                firebaseUser.updateProfile(profileUpdates).await()
                val userProfile = UserProfile(uid = firebaseUser.uid, displayName = displayName, email = email)
                firestore.collection("users").document(firebaseUser.uid).set(userProfile).await()
                _userState.value = UserState.Authenticated(firebaseUser)
            } catch (e: Exception) {
                _userState.value = UserState.Error(e.message ?: "Sign up failed")
            }
        }
    }

    fun checkAdminStatus() {
        viewModelScope.launch {
            try {
                val user = auth.currentUser
                user?.getIdToken(true)?.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val isAdminClaim = task.result.claims["admin"] as? Boolean
                        _isAdmin.value = isAdminClaim == true
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error checking admin status", e)
                _isAdmin.value = false
            }
        }
    }

    // --- API Call Helper ---
    private fun callApi(action: String, data: Map<String, Any?>): com.google.android.gms.tasks.Task<com.google.firebase.functions.HttpsCallableResult> {
        val finalData = data.toMutableMap()
        val actionsRequiringAuth = listOf(
            "submitProduct", "updateProduct", "proposeProductSwap", "respondToSwap", "placeBid",
            "markListingAsSold", "relistProduct", "clearAllNotifications", "requestVerification",
            "approveVerificationRequest", "saveSearch", "confirmPromotion"
        )
        if (actionsRequiringAuth.contains(action)) {
            auth.currentUser?.uid?.let {
                when (action) {
                    "submitProduct", "updateProduct", "markListingAsSold", "relistProduct" -> finalData["sellerId"] = it
                    "proposeProductSwap" -> finalData["proposingUserId"] = it
                    "respondToSwap" -> finalData["currentUserId"] = it
                    "placeBid" -> finalData["bidderId"] = it
                    "clearAllNotifications", "requestVerification", "saveSearch", "confirmPromotion" -> finalData["userId"] = it
                    "approveVerificationRequest" -> finalData["adminId"] = it
                }
            }
        }
        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to finalData))
    }

    // --- Product Management ---
    fun fetchProducts(latitude: Double?, longitude: Double?) {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val data = hashMapOf("latitude" to latitude, "longitude" to longitude)
                val result = callApi("getRankedProducts", data).await()

                @Suppress("UNCHECKED_CAST")
                val rankedProductData = (result.data as? Map<String, Any>)?.get("rankedProducts") as? List<Map<String, Any>>

                if (rankedProductData.isNullOrEmpty()) {
                    _products.value = emptyList()
                    return@launch
                }

                val productIds = rankedProductData.mapNotNull { it["id"] as? String }
                val distanceMap = rankedProductData.associate {
                    (it["id"] as? String) to (it["distanceKm"] as? Number)?.toDouble()
                }

                if (productIds.isEmpty()) {
                    _products.value = emptyList()
                    return@launch
                }

                val productChunks = productIds.chunked(30)
                val allProducts = mutableListOf<Product>()

                for (chunk in productChunks) {
                    val snapshot = firestore.collection("products")
                        .whereIn(FieldPath.documentId(), chunk)
                        .get().await()
                    allProducts.addAll(snapshot.documents.mapNotNull { it.toObject<Product>()?.copy(id = it.id) })
                }

                val productMap = allProducts.associateBy { it.id }
                _products.value = productIds.mapNotNull { id ->
                    productMap[id]?.copy(distanceKm = distanceMap[id])
                }

            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching ranked products", e)
                try {
                    val snapshot = firestore.collection("products")
                        .whereEqualTo("isSold", false)
                        .whereEqualTo("isPaid", true)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(50)
                        .get().await()
                    _products.value = snapshot.documents.mapNotNull { it.toObject<Product>()?.copy(id = it.id) }
                } catch (dbError: Exception) {
                    Log.e("MainViewModel", "Fallback DB query failed", dbError)
                    _products.value = emptyList()
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun submitProduct(
        name: String, description: String, price: Double, category: String, condition: String,
        imageUris: List<Uri>, videoUri: Uri?,
        isAuction: Boolean, auctionDurationDays: Int,
        sellerLocation: Location, itemAddress: String?,
        onSuccess: (String, Map<String, Any?>) -> Unit, onError: (String) -> Unit,
    ) {
        if (auth.currentUser == null) return onError("User not authenticated")

        viewModelScope.launch {
            try {
                val imageUploadUrls = mutableListOf<String>()
                imageUris.forEach { uri ->
                    if (uri.scheme?.startsWith("http") == true) {
                        imageUploadUrls.add(uri.toString())
                    } else {
                        val path = "product_images/${auth.currentUser!!.uid}_${System.currentTimeMillis()}_${imageUploadUrls.size}"
                        val ref = storage.reference.child(path)
                        ref.putFile(uri).await()
                        imageUploadUrls.add(ref.downloadUrl.await().toString())
                    }
                }

                var videoUploadUrl: String? = null
                videoUri?.let { uri ->
                    if (uri.scheme?.startsWith("http") == true) {
                        videoUploadUrl = uri.toString()
                    } else {
                        val path = "product_videos/${auth.currentUser!!.uid}_${System.currentTimeMillis()}"
                        val ref = storage.reference.child(path)
                        ref.putFile(uri).await()
                        videoUploadUrl = ref.downloadUrl.await().toString()
                    }
                }

                val locationData = mapOf("latitude" to sellerLocation.latitude, "longitude" to sellerLocation.longitude)

                val data = hashMapOf(
                    "name" to name, "description" to description, "price" to price, "category" to category, "condition" to condition,
                    "imageUris" to imageUploadUrls, "videoUri" to videoUploadUrl,
                    "isAuction" to isAuction, "auctionDurationDays" to auctionDurationDays,
                    "location" to locationData, "itemAddress" to itemAddress
                )

                val result = callApi("submitProduct", data).await()
                @Suppress("UNCHECKED_CAST")
                val resultData = result.data as? Map<String, Any?>
                val newProductId = resultData?.get("productId") as? String
                @Suppress("UNCHECKED_CAST")
                val submittedData = resultData?.get("submittedData") as? Map<String, Any?>

                if (newProductId != null && submittedData != null) {
                    onSuccess(newProductId, submittedData)
                } else {
                    onError("Failed to create product listing.")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Submission failed", e)
                onError(e.message ?: "Submission failed")
            }
        }
    }

    fun updateProduct(
        productId: String, name: String, description: String, price: Double, category: String, condition: String,
        imageUris: List<Uri>, videoUri: Uri?, itemAddress: String?, location: Location,
        onResult: (Boolean, String) -> Unit
    ) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in to update a product.")
        viewModelScope.launch {
            try {
                val imageUploadUrls = mutableListOf<String>()
                imageUris.forEach { uri ->
                    if (uri.scheme?.startsWith("http") == true) {
                        imageUploadUrls.add(uri.toString())
                    } else {
                        val path = "product_images/${auth.currentUser!!.uid}_${System.currentTimeMillis()}_${imageUploadUrls.size}"
                        val ref = storage.reference.child(path)
                        ref.putFile(uri).await()
                        imageUploadUrls.add(ref.downloadUrl.await().toString())
                    }
                }

                var videoUploadUrl: String? = null
                videoUri?.let { uri ->
                    if (uri.scheme?.startsWith("http") == true) {
                        videoUploadUrl = uri.toString()
                    } else {
                        val path = "product_videos/${auth.currentUser!!.uid}_${System.currentTimeMillis()}"
                        val ref = storage.reference.child(path)
                        ref.putFile(uri).await()
                        videoUploadUrl = ref.downloadUrl.await().toString()
                    }
                }

                val locationData = mapOf("latitude" to location.latitude, "longitude" to location.longitude)

                val data = hashMapOf(
                    "productId" to productId,
                    "name" to name, "description" to description, "price" to price, "category" to category, "condition" to condition,
                    "imageUris" to imageUploadUrls, "videoUri" to videoUploadUrl,
                    "itemAddress" to itemAddress,
                    "location" to locationData
                )

                callApi("updateProduct", data).await()
                onResult(true, "Product updated successfully!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error updating product", e)
                onResult(false, e.message ?: "Update failed.")
            }
        }
    }


    fun fetchProductDetails(productId: String) {
        viewModelScope.launch {
            resetDetailScreenState()
            try {
                val productDocRef = firestore.collection("products").document(productId)

                val productDoc = productDocRef.get().await()
                val initialProduct = productDoc.toObject<Product>()?.copy(id = productDoc.id)
                if (initialProduct == null) {
                    Log.e("MainViewModel", "Product with ID $productId not found.")
                    return@launch
                }

                productDocRef.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("MainViewModel", "Error listening to product details", error)
                        return@addSnapshotListener
                    }
                    val updatedProduct = snapshot?.toObject<Product>()?.copy(id = snapshot.id)
                    updatedProduct?.let {
                        if (it.distanceKm == null) {
                            val userLoc = _userLocation.value
                            val productLoc = it.sellerLocation
                            if (userLoc != null && productLoc != null) {
                                _selectedProduct.value = it.copy(distanceKm = getDistanceFromLatLonInKm(
                                    userLoc.latitude, userLoc.longitude,
                                    productLoc.latitude, productLoc.longitude
                                ))
                                return@addSnapshotListener
                            }
                        }
                        _selectedProduct.value = it
                    }
                }

                listenForComments(productId)
                listenForOffers(productId)
                listenForWishlistStatus(productId)
                if (initialProduct.sellerId.isNotBlank()) fetchSellerProfile(initialProduct.sellerId)
                if (initialProduct.auctionInfo != null) listenForBids(productId)
                incrementProductViewCount(productId)

            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching product details", e)
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
                onResult(true, "Listing marked as sold!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error marking product as sold", e)
                if (e is FirebaseFunctionsException && e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                    signOut()
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
                onResult(true, "Listing has been relisted!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error relisting product", e)
                onResult(false, e.message ?: "An error occurred.")
            }
        }
    }

    // --- User Profile, Follow, Edit ---
    fun fetchUserProfileAndProducts(userId: String) {
        userProfileListener?.remove()
        userProfileListener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("MainViewModel", "Error listening to user profile", error)
                    return@addSnapshotListener
                }
                _profileUser.value = snapshot?.toObject<UserProfile>()?.copy(uid = snapshot.id)
            }
        viewModelScope.launch {
            try {
                val productsSnapshot = firestore.collection("products")
                    .whereEqualTo("sellerId", userId)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .get().await()
                _userProducts.value = productsSnapshot.documents.mapNotNull { it.toObject<Product>()?.copy(id = it.id) }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching user products", e)
            }
        }
        listenForFollowStatus(userId)
    }

    private fun fetchSellerProfile(sellerId: String) {
        viewModelScope.launch {
            try {
                val sellerDoc = firestore.collection("users").document(sellerId).get().await()
                _sellerProfile.value = sellerDoc.toObject<UserProfile>()?.copy(uid = sellerDoc.id)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching seller profile", e)
            }
        }
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
                Log.e("MainViewModel", "Error toggling follow", e)
            } finally {
                _isLoadingFollow.value = false
            }
        }
    }

    fun updateUserProfile(displayName: String, bio: String, imageUri: Uri?, onResult: (Boolean, String) -> Unit) {
        val currentUser = auth.currentUser ?: return onResult(false, "You must be logged in.")
        _isUpdatingProfile.value = true
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
                Log.e("MainViewModel", "Error updating profile", e)
                onResult(false, e.message ?: "An error occurred.")
            } finally {
                _isUpdatingProfile.value = false
            }
        }
    }

    fun requestVerification(onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                callApi("requestVerification", emptyMap()).await()
                onResult(true, "Verification request sent!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error requesting verification", e)
                onResult(false, e.message ?: "An error occurred.")
            }
        }
    }

    // --- Admin ---
    fun fetchVerificationRequests() {
        if (auth.currentUser == null) return
        _isLoadingVerificationRequests.value = true
        viewModelScope.launch {
            try {
                val snapshot = firestore.collection("users")
                    .whereEqualTo("verificationRequested", true)
                    .get().await()
                _verificationRequests.value = snapshot.documents.mapNotNull { it.toObject<UserProfile>()?.copy(uid = it.id) }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching verification requests", e)
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
                _verificationRequests.value = _verificationRequests.value.filterNot { it.uid == userId }
                onResult(true, "User verified successfully.")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error approving verification", e)
                onResult(false, e.message ?: "An error occurred.")
            }
        }
    }

    // --- Comments and Offers ---
    private fun listenForComments(productId: String) {
        commentsListener?.remove()
        commentsListener = firestore.collection("products").document(productId)
            .collection("comments").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    _comments.value = snapshot.documents.mapNotNull { it.toObject<Comment>()?.copy(id = it.id) }
                }
            }
    }

    private fun listenForOffers(productId: String) {
        val currentUser = auth.currentUser ?: return
        offersListener?.remove()
        offersListener = firestore.collection("products").document(productId).collection("offers")
            .whereEqualTo("buyerId", currentUser.uid)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, _ -> _userHasPendingOffer.value = snapshot != null && !snapshot.isEmpty }
    }

    fun postComment(productId: String, text: String) {
        val currentUser = auth.currentUser ?: return
        val comment = Comment(
            text = text, userId = currentUser.uid,
            userName = currentUser.displayName ?: "Anonymous",
            userPhotoURL = currentUser.photoUrl?.toString(),
        )
        firestore.collection("products").document(productId).collection("comments").add(comment)
    }

    fun submitOffer(productId: String, amount: Double) {
        val currentUser = auth.currentUser ?: return
        val product = _selectedProduct.value ?: return
        val offer = Offer(
            buyerId = currentUser.uid, buyerName = currentUser.displayName ?: "Anonymous",
            sellerId = product.sellerId, offerAmount = amount, status = "pending"
        )
        firestore.collection("products").document(productId).collection("offers").add(offer)
    }

    fun listenForProductOffers(productId: String) {
        if (auth.currentUser == null) return
        _isLoadingOffers.value = true
        offersForProductListener?.remove()
        offersForProductListener = firestore.collection("products").document(productId).collection("offers")
            .orderBy("offerTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("MainViewModel", "Error listening for offers", error)
                } else if (snapshot != null) {
                    _offersForProduct.value = snapshot.documents.mapNotNull { it.toObject<Offer>()?.copy(id = it.id) }
                }
                _isLoadingOffers.value = false
            }
    }

    fun updateOfferStatus(productId: String, offerId: String, status: String) {
        if (auth.currentUser == null) return
        viewModelScope.launch {
            try {
                firestore.collection("products").document(productId).collection("offers").document(offerId)
                    .update("status", status).await()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error updating offer status", e)
            }
        }
    }

    // --- Wishlist ---
    private fun listenForWishlistStatus(productId: String) {
        val currentUser = auth.currentUser ?: return
        wishlistListener?.remove()
        wishlistListener = firestore.collection("users").document(currentUser.uid)
            .collection("wishlist").document(productId)
            .addSnapshotListener { snapshot, _ -> _isProductInWishlist.value = snapshot != null && snapshot.exists() }
    }

    fun toggleWishlistForItem(product: Product) {
        val currentUser = auth.currentUser ?: return
        if (currentUser.uid == product.sellerId) return
        viewModelScope.launch {
            try {
                val wishlistItemRef = firestore.collection("users").document(currentUser.uid)
                    .collection("wishlist").document(product.id)
                if (_isProductInWishlist.value) {
                    wishlistItemRef.delete().await()
                } else {
                    wishlistItemRef.set(mapOf("savedAt" to FieldValue.serverTimestamp(), "productId" to product.id)).await()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error toggling wishlist item", e)
            }
        }
    }

    fun fetchWishlist() {
        val currentUser = auth.currentUser ?: return
        _isLoadingWishlist.value = true
        _wishlistError.value = null
        viewModelScope.launch {
            try {
                val wishlistSnapshot = firestore.collection("users").document(currentUser.uid)
                    .collection("wishlist").get().await()
                val productIds = wishlistSnapshot.documents.map { it.id }
                if (productIds.isEmpty()) {
                    _wishlistItems.value = emptyList()
                    return@launch
                }
                _wishlistItems.value = firestore.collection("products")
                    .whereIn(FieldPath.documentId(), productIds)
                    .get().await().documents.mapNotNull { doc ->
                        doc.toObject<Product>()?.copy(id = doc.id)
                    }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching wishlist", e)
                _wishlistError.value = "Failed to load wishlist."
            } finally {
                _isLoadingWishlist.value = false
            }
        }
    }

    fun removeFromWishlist(productId: String) {
        val currentUser = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                firestore.collection("users").document(currentUser.uid)
                    .collection("wishlist").document(productId).delete().await()
                _wishlistItems.value = _wishlistItems.value.filterNot { it.id == productId }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error removing from wishlist", e)
            }
        }
    }

    // --- Analytics & View Count ---
    fun incrementProductViewCount(productId: String) {
        viewModelScope.launch {
            try {
                callApi("incrementProductViewCount", mapOf("productId" to productId)).await()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Failed to increment view count for $productId", e)
            }
        }
    }

    fun fetchProductAnalytics() {
        val currentUser = auth.currentUser ?: return
        _isLoadingAnalytics.value = true
        viewModelScope.launch {
            try {
                val productsSnapshot = firestore.collection("products")
                    .whereEqualTo("sellerId", currentUser.uid)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .get().await()

                val analytics = productsSnapshot.documents.mapNotNull { doc ->
                    val product = doc.toObject<Product>()?.copy(id = doc.id)
                    if (product == null) return@mapNotNull null

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
                Log.e("MainViewModel", "Error fetching product analytics", e)
            } finally {
                _isLoadingAnalytics.value = false
            }
        }
    }

    // --- Payment & Promotions ---
    fun createPaymentIntent(
        productId: String,
        isAuction: Boolean,
        auctionDurationDays: Int,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (auth.currentUser == null) return onError("You must be logged in to make a payment.")
        viewModelScope.launch {
            try {
                val data = hashMapOf(
                    "productId" to productId,
                    "isAuction" to isAuction,
                    "auctionDurationDays" to auctionDurationDays
                )
                val result = callApi("createPaymentIntent", data).await()
                val clientSecret = (result.data as? Map<String, Any?>)?.get("clientSecret") as? String
                if (clientSecret != null) onSuccess(clientSecret) else onError("Could not retrieve payment secret.")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error creating payment intent", e)
                onError(e.message ?: "An unknown error occurred.")
            }
        }
    }

    fun createPromotionPaymentIntent(
        promotionType: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (auth.currentUser == null) return onError("You must be logged in.")
        viewModelScope.launch {
            try {
                val data = mapOf("promotionType" to promotionType)
                val result = callApi("createPromotionPaymentIntent", data).await()
                val clientSecret = (result.data as? Map<String, Any?>)?.get("clientSecret") as? String
                if (clientSecret != null) {
                    onSuccess(clientSecret)
                } else {
                    onError("Could not initialize promotion payment.")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error creating promotion payment intent", e)
                onError(e.message ?: "An unknown error occurred.")
            }
        }
    }

    fun confirmPromotion(
        productId: String,
        promotionType: String,
        onResult: (Boolean, String) -> Unit
    ) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                val data = mapOf("productId" to productId, "promotionType" to promotionType)
                callApi("confirmPromotion", data).await()
                onResult(true, "Promotion successful!")
                fetchUserProfileAndProducts(auth.currentUser!!.uid)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error confirming promotion", e)
                onResult(false, e.message ?: "Could not confirm promotion.")
            }
        }
    }

    fun markProductAsPaid(productId: String, isAuction: Boolean, auctionDurationDays: Int, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                val data = mapOf(
                    "productId" to productId,
                    "isAuction" to isAuction,
                    "auctionDurationDays" to auctionDurationDays
                )
                callApi("markProductAsPaid", data).await()
                onResult(true, "Listing activated!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error marking product as paid", e)
                onResult(false, e.message ?: "An unknown error occurred.")
            }
        }
    }

    // --- AI Suggestions & Geocoding ---
    fun getAiSuggestions(title: String) {
        viewModelScope.launch {
            _isGeneratingSuggestions.value = true
            try {
                val result = callApi("getListingDetailsFromTitle", mapOf("title" to title)).await()
                @Suppress("UNCHECKED_CAST")
                val suggestionsData = (result.data as? Map<String, Any?>)?.get("suggestions") as? Map<String, String>
                if (suggestionsData != null) {
                    _aiSuggestions.value = AiSuggestions(
                        description = suggestionsData["description"] ?: "",
                        category = suggestionsData["category"] ?: ""
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error getting AI suggestions", e)
            } finally {
                _isGeneratingSuggestions.value = false
            }
        }
    }

    suspend fun geocodeAddress(address: String): Location? {
        return try {
            val result = callApi("geocodeAddress", mapOf("address" to address)).await()
            @Suppress("UNCHECKED_CAST")
            val data = result.data as? Map<String, Double>
            data?.let {
                Location("").apply {
                    latitude = it["latitude"]!!
                    longitude = it["longitude"]!!
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error geocoding address", e)
            null
        }
    }

    fun clearAiSuggestions() {
        _aiSuggestions.value = null
    }

    // --- Visual Search ---
    fun performVisualSearch(imageUri: Uri) {
        val currentUser = auth.currentUser ?: return
        viewModelScope.launch {
            _isProcessingVisualSearch.value = true
            try {
                val path = "visual_search_uploads/${currentUser.uid}_${System.currentTimeMillis()}.jpg"
                val ref = storage.reference.child(path)
                ref.putFile(imageUri).await()
                val downloadUrl = ref.downloadUrl.await().toString()

                val data = mapOf("imageUrl" to downloadUrl)
                val result = callApi("visualSearch", data).await()

                @Suppress("UNCHECKED_CAST")
                val suggestions = (result.data as? Map<String, Any?>)?.get("suggestions") as? Map<String, String>
                val query = suggestions?.get("searchQuery")
                val category = suggestions?.get("category")

                if (query != null && category != null) {
                    _visualSearchSuggestion.value = Pair(query, category)
                }

                ref.delete().await()

            } catch (e: Exception) {
                Log.e("MainViewModel", "Error performing visual search", e)
            } finally {
                _isProcessingVisualSearch.value = false
            }
        }
    }

    fun clearVisualSearchSuggestion() {
        _visualSearchSuggestion.value = null
    }


    // --- Seller Reviews ---
    fun fetchSellerReviews(sellerId: String) {
        _isLoadingReviews.value = true
        sellerReviewsListener?.remove()
        sellerReviewsListener = firestore.collection("reviews")
            .whereEqualTo("sellerId", sellerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("MainViewModel", "Error fetching seller reviews", error)
                } else if (snapshot != null) {
                    _sellerReviews.value = snapshot.documents.mapNotNull { it.toObject<Review>()?.copy(id = it.id) }
                }
                _isLoadingReviews.value = false
            }
    }

    fun postReview(sellerId: String, rating: Int, comment: String, onResult: (Boolean, String) -> Unit) {
        val currentUser = auth.currentUser ?: return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                val review = Review(
                    sellerId = sellerId,
                    reviewerId = currentUser.uid,
                    reviewerName = currentUser.displayName ?: "Anonymous",
                    rating = rating,
                    comment = comment
                )
                firestore.collection("reviews").add(review).await()
                onResult(true, "Review submitted successfully!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error posting review", e)
                onResult(false, e.message ?: "Failed to submit review.")
            }
        }
    }

    // --- Chat ---
    fun fetchChatList() {
        val currentUser = auth.currentUser ?: return
        _isLoadingChatList.value = true
        viewModelScope.launch {
            try {
                val chatsSnapshot = firestore.collection("privateChats")
                    .whereArrayContains("participantIds", currentUser.uid)
                    .orderBy("lastActivity", Query.Direction.DESCENDING)
                    .get().await()
                val otherParticipantIds = chatsSnapshot.documents.mapNotNull { doc ->
                    doc.toObject<PrivateChat>()?.participantIds?.find { it != currentUser.uid }
                }.distinct()
                if (otherParticipantIds.isEmpty()) {
                    _chatList.value = emptyList()
                    _isLoadingChatList.value = false
                    return@launch
                }
                val usersSnapshot = firestore.collection("users")
                    .whereIn(FieldPath.documentId(), otherParticipantIds)
                    .get().await()
                val userProfiles = usersSnapshot.documents.mapNotNull { it.toObject<UserProfile>()?.copy(uid = it.id) }
                    .associateBy { it.uid }
                val chats = chatsSnapshot.documents.mapNotNull { doc ->
                    val chat = doc.toObject<PrivateChat>()?.copy(id = doc.id)
                    val otherId = chat?.participantIds?.find { it != currentUser.uid }
                    val otherProfile = userProfiles[otherId]
                    if (chat != null && otherProfile != null) {
                        chat.copy(participants = mapOf(otherId!! to otherProfile))
                    } else { null }
                }
                _chatList.value = chats
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching chat list.", e)
                _chatList.value = emptyList()
            } finally {
                _isLoadingChatList.value = false
            }
        }
    }

    fun listenForMessages(recipientId: String) {
        val currentUser = auth.currentUser ?: return
        val chatId = listOf(currentUser.uid, recipientId).sorted().joinToString("_")
        chatListener?.remove()
        chatListener = firestore.collection("privateChats").document(chatId)
            .collection("messages").orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    _messages.value = snapshot.documents.mapNotNull { it.toObject<ChatMessage>()?.copy(id = it.id) }
                }
            }
    }

    fun sendMessage(recipientId: String, text: String) {
        val currentUser = auth.currentUser ?: return
        val message = ChatMessage(type = "text", text = text, senderId = currentUser.uid)
        sendMessageObject(recipientId, message, text)
    }

    fun sendImageMessage(recipientId: String, imageUri: Uri) {
        uploadMediaAndSendMessage(recipientId, imageUri, "chat_images", "image", "[Image]")
    }

    fun sendVideoMessage(recipientId: String, videoUri: Uri) {
        uploadMediaAndSendMessage(recipientId, videoUri, "chat_videos", "video", "[Video]")
    }

    fun sendLocationMessage(recipientId: String, location: Location) {
        val currentUser = auth.currentUser ?: return
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        val message = ChatMessage(
            type = "location",
            location = geoPoint,
            senderId = currentUser.uid
        )
        sendMessageObject(recipientId, message, "📍 Shared a location")
    }

    private fun uploadMediaAndSendMessage(recipientId: String, uri: Uri, folder: String, type: String, lastMessage: String) {
        val currentUser = auth.currentUser ?: return
        viewModelScope.launch {
            _isUploadingMedia.value = true
            try {
                val ref = storage.reference.child("$folder/${currentUser.uid}_${System.currentTimeMillis()}")
                ref.putFile(uri).await()
                val url = ref.downloadUrl.await().toString()
                val message = when(type) {
                    "image" -> ChatMessage(type = "image", imageUrl = url, senderId = currentUser.uid)
                    "video" -> ChatMessage(type = "video", videoUrl = url, senderId = currentUser.uid)
                    else -> throw IllegalArgumentException("Unsupported media type")
                }
                sendMessageObject(recipientId, message, lastMessage)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error uploading media", e)
            } finally {
                _isUploadingMedia.value = false
            }
        }
    }

    private fun sendMessageObject(recipientId: String, message: ChatMessage, lastMessageText: String) {
        val currentUser = auth.currentUser ?: return
        val chatId = listOf(currentUser.uid, recipientId).sorted().joinToString("_")
        viewModelScope.launch {
            try {
                firestore.collection("privateChats").document(chatId).collection("messages").add(message).await()
                firestore.collection("privateChats").document(chatId).set(mapOf(
                    "participantIds" to listOf(currentUser.uid, recipientId),
                    "lastMessage" to lastMessageText,
                    "lastActivity" to FieldValue.serverTimestamp()
                ), SetOptions.merge()).await()

            } catch (e: Exception) {
                Log.e("MainViewModel", "Error sending message object", e)
            }
        }
    }

    // --- Chatbot ---
    fun sendChatbotMessage(prompt: String) {
        viewModelScope.launch {
            _chatbotMessages.value += ChatbotMessage(text = prompt, role = "user")
            _chatbotMessages.value += ChatbotMessage(text = "", role = "model", isThinking = true)
            try {
                val result = callApi("askGemini", mapOf("prompt" to prompt)).await()
                val reply = (result.data as? Map<String, Any?>)?.get("reply") as? String ?: "Sorry, I couldn't process that."
                val modelMessage = ChatbotMessage(text = reply, role = "model")
                _chatbotMessages.value = _chatbotMessages.value.dropLast(1) + modelMessage
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error in sendChatbotMessage", e)
                val errorMessage = ChatbotMessage(text = "Error: ${e.message}", role = "model")
                _chatbotMessages.value = _chatbotMessages.value.dropLast(1) + errorMessage
            }
        }
    }

    // --- Notifications ---
    private fun listenForNotifications() {
        val currentUser = auth.currentUser ?: return
        if (notificationsListener != null) return
        _isLoadingNotifications.value = true
        notificationsListener = firestore.collection("users").document(currentUser.uid)
            .collection("notifications").orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("MainViewModel", "Error listening for notifications", error)
                } else if (snapshot != null) {
                    val notificationsList = snapshot.documents.mapNotNull { it.toObject<Notification>()?.copy(id = it.id) }
                    _notifications.value = notificationsList
                    _unreadNotificationCount.value = notificationsList.count { !it.isRead }
                }
                _isLoadingNotifications.value = false
            }
    }

    private fun stopNotificationsListener() {
        notificationsListener?.remove()
        notificationsListener = null
        _notifications.value = emptyList()
        _unreadNotificationCount.value = 0
    }

    fun markNotificationAsRead(notificationId: String) {
        val currentUser = auth.currentUser ?: return
        val currentNotifications = _notifications.value.toMutableList()
        val index = currentNotifications.indexOfFirst { it.id == notificationId }
        if (index != -1) {
            val notification = currentNotifications[index]
            if (!notification.isRead) {
                currentNotifications[index] = notification.copy(isRead = true)
                _notifications.value = currentNotifications
                if (_unreadNotificationCount.value > 0) {
                    _unreadNotificationCount.value = _unreadNotificationCount.value - 1
                }
            }
        }
        viewModelScope.launch {
            try {
                firestore.collection("users").document(currentUser.uid)
                    .collection("notifications").document(notificationId)
                    .update("isRead", true).await()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error marking notification as read", e)
            }
        }
    }

    fun clearAllNotifications(onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                val result = callApi("clearAllNotifications", emptyMap()).await()
                val message = (result.data as? Map<String, Any?>)?.get("message") as? String ?: "Notifications cleared."
                onResult(true, message)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error clearing all notifications", e)
                onResult(false, e.message ?: "An unknown error occurred.")
            }
        }
    }

    // --- Product Swaps ---
    fun fetchSwaps() {
        val currentUser = auth.currentUser ?: return
        _isLoadingSwaps.value = true
        viewModelScope.launch {
            try {
                val incomingSnapshot = firestore.collection("swaps")
                    .whereEqualTo("targetUserId", currentUser.uid)
                    .orderBy("proposedAt", Query.Direction.DESCENDING)
                    .get().await()
                val incoming = incomingSnapshot.documents.mapNotNull { it.toObject<ProductSwap>()?.copy(id = it.id) }

                val outgoingSnapshot = firestore.collection("swaps")
                    .whereEqualTo("proposingUserId", currentUser.uid)
                    .orderBy("proposedAt", Query.Direction.DESCENDING)
                    .get().await()
                val outgoing = outgoingSnapshot.documents.mapNotNull { it.toObject<ProductSwap>()?.copy(id = it.id) }

                _swaps.value = SwapsData(incoming = incoming, outgoing = outgoing)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching swaps", e)
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
                    "cashTopUp" to cashTopUp
                )
                callApi("proposeProductSwap", data).await()
                onResult(true, "Swap proposal sent successfully!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error proposing swap", e)
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
                fetchSwaps()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error responding to swap", e)
                val message = if (e is FirebaseFunctionsException) e.message else "An unknown error occurred."
                onResult(false, message ?: "Failed to send response.")
            }
        }
    }

    // --- Auctions ---
    private fun listenForBids(productId: String) {
        _isLoadingBids.value = true
        bidsListener?.remove()
        bidsListener = firestore.collection("products").document(productId)
            .collection("bids").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("MainViewModel", "Error listening for bids", error)
                } else if (snapshot != null) {
                    _bids.value = snapshot.documents.mapNotNull { it.toObject<Bid>()?.copy(id = it.id) }
                }
                _isLoadingBids.value = false
            }
    }

    fun placeBid(productId: String, amount: Double, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) {
            return onResult(false, "You must be logged in to place a bid.")
        }
        viewModelScope.launch {
            try {
                val data = hashMapOf("productId" to productId, "amount" to amount)
                callApi("placeBid", data).await()
                onResult(true, "Bid placed successfully!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error placing bid", e)
                val message = if (e is FirebaseFunctionsException) e.message else "An unknown error occurred."
                onResult(false, message ?: "Failed to place bid.")
            }
        }
    }

    // --- Saved Searches ---
    fun saveSearch(query: String, category: String, minPrice: String, maxPrice: String, condition: String, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) {
            return onResult(false, "You must be logged in to save searches.")
        }
        viewModelScope.launch {
            try {
                val data = hashMapOf(
                    "query" to query.ifBlank { null },
                    "category" to category.ifBlank { null },
                    "minPrice" to minPrice.toDoubleOrNull(),
                    "maxPrice" to maxPrice.toDoubleOrNull(),
                    "condition" to condition.ifBlank { null }
                )
                callApi("saveSearch", data).await()
                onResult(true, "Search saved!")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error saving search", e)
                onResult(false, e.message ?: "Could not save search.")
            }
        }
    }

    fun fetchSavedSearches() {
        val currentUser = auth.currentUser ?: return
        _isLoadingSavedSearches.value = true
        savedSearchesListener?.remove()
        savedSearchesListener = firestore.collection("users").document(currentUser.uid)
            .collection("savedSearches")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("MainViewModel", "Error fetching saved searches", error)
                } else if (snapshot != null) {
                    _savedSearches.value = snapshot.documents.mapNotNull { it.toObject<SavedSearch>()?.copy(id = it.id) }
                }
                _isLoadingSavedSearches.value = false
            }
    }

    fun deleteSavedSearch(searchId: String) {
        val currentUser = auth.currentUser ?: return
        viewModelScope.launch {
            try {
                firestore.collection("users").document(currentUser.uid)
                    .collection("savedSearches").document(searchId).delete().await()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error deleting saved search", e)
            }
        }
    }

    // --- Product Editing ---
    fun getProductForEditing(productId: String) {
        viewModelScope.launch {
            try {
                val doc = firestore.collection("products").document(productId).get().await()
                _productToEdit.value = doc.toObject<Product>()?.copy(id = doc.id)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching product for edit", e)
            }
        }
    }

    fun clearProductToEdit() {
        _productToEdit.value = null
    }

    private fun getDistanceFromLatLonInKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371
        val dLat = deg2rad(lat2 - lat1)
        val dLon = deg2rad(lon2 - lon1)
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                    cos(deg2rad(lat1)) * cos(deg2rad(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun deg2rad(deg: Double): Double {
        return deg * (PI / 180)
    }

    // --- Cleanup ---
    private fun resetDetailScreenState() {
        _selectedProduct.value = null
        _sellerProfile.value = null
        _comments.value = emptyList()
        _userHasPendingOffer.value = false
        _isProductInWishlist.value = false
        _bids.value = emptyList()
        commentsListener?.remove()
        offersListener?.remove()
        wishlistListener?.remove()
        bidsListener?.remove()
    }

    override fun onCleared() {
        super.onCleared()
        commentsListener?.remove()
        offersListener?.remove()
        wishlistListener?.remove()
        followListener?.remove()
        userProfileListener?.remove()
        chatListener?.remove()
        offersForProductListener?.remove()
        sellerReviewsListener?.remove()
        notificationsListener?.remove()
        bidsListener?.remove()
        savedSearchesListener?.remove()
    }
}

sealed class UserState {
    object Loading : UserState()
    data class Authenticated(val user: com.google.firebase.auth.FirebaseUser) : UserState()
    object Unauthenticated : UserState()
    data class Error(val message: String) : UserState()
}

