package com.gari.yahdsell2.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.Product
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class WishlistViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _wishlistItems = MutableStateFlow<List<Product>>(emptyList())
    val wishlistItems: StateFlow<List<Product>> = _wishlistItems.asStateFlow()

    private val _isLoadingWishlist = MutableStateFlow(false)
    val isLoadingWishlist: StateFlow<Boolean> = _isLoadingWishlist.asStateFlow()

    private val _wishlistError = MutableStateFlow<String?>(null)
    val wishlistError: StateFlow<String?> = _wishlistError.asStateFlow()

    init {
        fetchWishlist()
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
                    _isLoadingWishlist.value = false
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
                _wishlistItems.value = allProducts

            } catch (e: Exception) {
                Log.e("WishlistViewModel", "Error fetching wishlist", e)
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
                Log.e("WishlistViewModel", "Error removing from wishlist", e)
            }
        }
    }
}