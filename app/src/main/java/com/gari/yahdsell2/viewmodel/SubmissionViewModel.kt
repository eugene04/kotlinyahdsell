package com.gari.yahdsell2.viewmodel

import android.location.Location
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.Product
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*
import javax.inject.Inject

@HiltViewModel
class SubmissionViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage
) : ViewModel() {

    private val _productToEdit = MutableStateFlow<Product?>(null)
    val productToEdit: StateFlow<Product?> = _productToEdit.asStateFlow()

    private val _aiSuggestions = MutableStateFlow<Product?>(null)
    val aiSuggestions: StateFlow<Product?> = _aiSuggestions.asStateFlow()

    private val _isGeneratingSuggestions = MutableStateFlow(false)
    val isGeneratingSuggestions: StateFlow<Boolean> = _isGeneratingSuggestions.asStateFlow()

    fun getProductForEditing(productId: String) {
        viewModelScope.launch {
            try {
                val productDoc = firestore.collection("products").document(productId).get().await()
                _productToEdit.value = productDoc.toObject(Product::class.java)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun clearProductToEdit() {
        _productToEdit.value = null
    }

    fun clearAiSuggestions() {
        _aiSuggestions.value = null
    }

    fun submitProduct(
        name: String,
        description: String,
        price: Double,
        category: String,
        condition: String,
        imageUris: List<Uri>,
        videoUri: Uri?,
        sellerLocation: Location,
        itemAddress: String?,
        isAuction: Boolean,
        auctionDurationDays: Int,
        existingProductId: String?,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        // Implementation details...
    }

    fun generateSuggestions(productName: String) {
        // Implementation details...
    }

    suspend fun verifyAddress(address: String): Location? {
        // Implementation details...
        return null
    }
}
