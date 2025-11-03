package com.gari.yahdsell2.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions
) : ViewModel() {

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

    private val _bids = MutableStateFlow<List<Bid>>(emptyList())
    val bids: StateFlow<List<Bid>> = _bids.asStateFlow()

    private val _isLoadingBids = MutableStateFlow(false)
    val isLoadingBids: StateFlow<Boolean> = _isLoadingBids.asStateFlow()

    private var productListener: ListenerRegistration? = null
    private var commentsListener: ListenerRegistration? = null
    private var offersListener: ListenerRegistration? = null
    private var wishlistListener: ListenerRegistration? = null
    private var bidsListener: ListenerRegistration? = null

    private fun callApi(action: String, data: Map<String, Any?>): com.google.android.gms.tasks.Task<com.google.firebase.functions.HttpsCallableResult> {
        val finalData = data.toMutableMap()
        auth.currentUser?.uid?.let {
            finalData["bidderId"] = it
        }
        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to finalData))
    }

    fun fetchProductDetails(productId: String) {
        viewModelScope.launch {
            resetDetailScreenState()
            try {
                productListener = firestore.collection("products").document(productId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e("ProductDetailViewModel", "Error listening to product details", error)
                            return@addSnapshotListener
                        }
                        val updatedProduct = snapshot?.toObject<Product>()?.copy(id = snapshot.id)
                        _selectedProduct.value = updatedProduct

                        if (_sellerProfile.value == null && updatedProduct?.sellerId?.isNotBlank() == true) {
                            fetchSellerProfile(updatedProduct.sellerId)
                        }
                    }

                listenForComments(productId)
                listenForOffers(productId)
                listenForWishlistStatus(productId)
                listenForBids(productId)
                incrementProductViewCount(productId)

            } catch (e: Exception) {
                Log.e("ProductDetailViewModel", "Error fetching product details", e)
            }
        }
    }

    private fun fetchSellerProfile(sellerId: String) {
        viewModelScope.launch {
            try {
                val sellerDoc = firestore.collection("users").document(sellerId).get().await()
                _sellerProfile.value = sellerDoc.toObject<UserProfile>()?.copy(uid = sellerDoc.id)
            } catch (e: Exception) {
                Log.e("ProductDetailViewModel", "Error fetching seller profile", e)
            }
        }
    }

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
                    wishlistItemRef.set(mapOf("savedAt" to com.google.firebase.Timestamp.now(), "productId" to product.id)).await()
                }
            } catch (e: Exception) {
                Log.e("ProductDetailViewModel", "Error toggling wishlist item", e)
            }
        }
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

    private fun listenForBids(productId: String) {
        _isLoadingBids.value = true
        bidsListener?.remove()
        bidsListener = firestore.collection("products").document(productId)
            .collection("bids").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ProductDetailViewModel", "Error listening for bids", error)
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
                Log.e("ProductDetailViewModel", "Error placing bid", e)
                val message = if (e is FirebaseFunctionsException) e.message else "An unknown error occurred."
                onResult(false, message ?: "Failed to place bid.")
            }
        }
    }

    private fun incrementProductViewCount(productId: String) {
        viewModelScope.launch {
            try {
                callApi("incrementProductViewCount", mapOf("productId" to productId)).await()
            } catch (e: Exception) {
                Log.w("ProductDetailViewModel", "Failed to increment view count for $productId", e)
            }
        }
    }

    private fun resetDetailScreenState() {
        _selectedProduct.value = null
        _sellerProfile.value = null
        _comments.value = emptyList()
        _userHasPendingOffer.value = false
        _isProductInWishlist.value = false
        _bids.value = emptyList()
        productListener?.remove()
        commentsListener?.remove()
        offersListener?.remove()
        wishlistListener?.remove()
        bidsListener?.remove()
    }

    override fun onCleared() {
        super.onCleared()
        resetDetailScreenState()
    }
}
