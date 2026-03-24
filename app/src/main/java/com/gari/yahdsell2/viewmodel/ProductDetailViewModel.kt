package com.gari.yahdsell2.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.Offer
import com.gari.yahdsell2.model.Product
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _product = MutableStateFlow<Product?>(null)
    val product: StateFlow<Product?> = _product.asStateFlow()

    private val _offersForProduct = MutableStateFlow<List<Offer>>(emptyList())
    val offersForProduct: StateFlow<List<Offer>> = _offersForProduct.asStateFlow()

    private val _isLoadingOffers = MutableStateFlow(false)
    val isLoadingOffers: StateFlow<Boolean> = _isLoadingOffers.asStateFlow()

    private var offersListener: ListenerRegistration? = null

    fun loadProduct(productId: String) {
        viewModelScope.launch {
            try {
                // 1. Fetch Product Data
                val document = firestore.collection("products").document(productId).get().await()
                val fetchedProduct = document.toObject(Product::class.java)

                if (fetchedProduct != null) {
                    var finalProduct = fetchedProduct

                    // 2. Increment View Count (atomic operation)
                    firestore.collection("products").document(productId)
                        .update("viewCount", FieldValue.increment(1))

                    // 3. Check if Wishlisted by current user
                    val currentUser = auth.currentUser
                    if (currentUser != null) {
                        val wishlistDoc = firestore.collection("users")
                            .document(currentUser.uid)
                            .collection("wishlist")
                            .document(productId)
                            .get()
                            .await()

                        // Update the local excluded field 'isWishlisted'
                        finalProduct = finalProduct.copy(isWishlisted = wishlistDoc.exists())
                    }

                    _product.value = finalProduct
                }
            } catch (e: Exception) {
                Log.e("ProductDetailViewModel", "Error loading product", e)
            }
        }
    }

    fun toggleWishlist(productId: String) {
        val user = auth.currentUser ?: return
        val currentProduct = _product.value ?: return

        viewModelScope.launch {
            val wishlistRef = firestore.collection("users")
                .document(user.uid)
                .collection("wishlist")
                .document(productId)

            val productRef = firestore.collection("products").document(productId)

            try {
                if (currentProduct.isWishlisted) {
                    // Remove from wishlist
                    wishlistRef.delete().await()
                    // Decrement global save count
                    productRef.update("saveCount", FieldValue.increment(-1))

                    _product.value = currentProduct.copy(isWishlisted = false)
                } else {
                    // Add to wishlist
                    // We save a small snapshot of the product to the wishlist for easy querying later
                    wishlistRef.set(currentProduct).await()
                    // Increment global save count
                    productRef.update("saveCount", FieldValue.increment(1))

                    _product.value = currentProduct.copy(isWishlisted = false)
                }
            } catch (e: Exception) {
                Log.e("ProductDetailViewModel", "Error toggling wishlist", e)
            }
        }
    }

    fun placeBid(productId: String, amount: Double, onResult: (Boolean, String) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onResult(false, "You must be logged in to bid.")
            return
        }

        viewModelScope.launch {
            try {
                // In a real app, utilize a Transaction here to prevent race conditions
                val productRef = firestore.collection("products").document(productId)

                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(productRef)
                    val currentBid = snapshot.getDouble("auctionInfo.currentBid") ?: snapshot.getDouble("startingPrice") ?: 0.0

                    if (amount <= currentBid) {
                        throw Exception("Bid must be higher than current price.")
                    }

                    // Update Auction Info
                    transaction.update(productRef, mapOf(
                        "auctionInfo.currentBid" to amount,
                        "auctionInfo.leadingBidderId" to user.uid
                    ))

                    // Record the bid in a subcollection
                    val bidData = mapOf(
                        "bidderId" to user.uid,
                        "bidderName" to (user.displayName ?: "Anonymous"),
                        "amount" to amount,
                        "timestamp" to FieldValue.serverTimestamp()
                    )
                    transaction.set(productRef.collection("bids").document(), bidData)
                }.await()

                // Refresh product data
                loadProduct(productId)
                onResult(true, "Bid placed successfully!")

            } catch (e: Exception) {
                Log.e("ProductDetailViewModel", "Bid failed", e)
                onResult(false, e.message ?: "Failed to place bid")
            }
        }
    }

    // --- Offers Management (for Sellers) ---

    fun listenForProductOffers(productId: String) {
        _isLoadingOffers.value = true
        offersListener?.remove()

        offersListener = firestore.collection("products").document(productId)
            .collection("offers")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                _isLoadingOffers.value = false
                if (e != null) {
                    Log.e("ProductDetailViewModel", "Listen failed", e)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    _offersForProduct.value = snapshot.toObjects(Offer::class.java).map { it.copy(id = it.id) }
                }
            }
    }

    fun respondToOffer(offerId: String, accept: Boolean) {
        val productId = _product.value?.id ?: return
        val status = if (accept) "accepted" else "rejected"

        viewModelScope.launch {
            try {
                firestore.collection("products").document(productId)
                    .collection("offers").document(offerId)
                    .update("status", status)
                    .await()
            } catch (e: Exception) {
                Log.e("ProductDetailViewModel", "Error responding to offer", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        offersListener?.remove()
    }
}