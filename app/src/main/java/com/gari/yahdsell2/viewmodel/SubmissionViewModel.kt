package com.gari.yahdsell2.viewmodel

import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.Product
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class AiSuggestions(val description: String, val category: String)

@HiltViewModel
class SubmissionViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val storage: FirebaseStorage
) : ViewModel() {

    private val _productToEdit = MutableStateFlow<Product?>(null)
    val productToEdit: StateFlow<Product?> = _productToEdit.asStateFlow()

    private val _aiSuggestions = MutableStateFlow<AiSuggestions?>(null)
    val aiSuggestions: StateFlow<AiSuggestions?> = _aiSuggestions.asStateFlow()

    private val _isGeneratingSuggestions = MutableStateFlow(false)
    val isGeneratingSuggestions: StateFlow<Boolean> = _isGeneratingSuggestions.asStateFlow()

    private fun callApi(action: String, data: Map<String, Any?>): com.google.android.gms.tasks.Task<com.google.firebase.functions.HttpsCallableResult> {
        val finalData = data.toMutableMap()
        auth.currentUser?.uid?.let {
            finalData["sellerId"] = it
        }
        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to finalData))
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
                Log.e("SubmissionViewModel", "Submission failed", e)
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
                Log.e("SubmissionViewModel", "Error updating product", e)
                onResult(false, e.message ?: "Update failed.")
            }
        }
    }

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
                Log.e("SubmissionViewModel", "Error getting AI suggestions", e)
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
            Log.e("SubmissionViewModel", "Error geocoding address", e)
            null
        }
    }

    fun clearAiSuggestions() {
        _aiSuggestions.value = null
    }

    fun getProductForEditing(productId: String) {
        viewModelScope.launch {
            try {
                val doc = firestore.collection("products").document(productId).get().await()
                _productToEdit.value = doc.toObject<Product>()?.copy(id = doc.id)
            } catch (e: Exception) {
                Log.e("SubmissionViewModel", "Error fetching product for edit", e)
            }
        }
    }

    fun clearProductToEdit() {
        _productToEdit.value = null
    }
}
