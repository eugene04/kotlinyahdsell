package com.gari.yahdsell2.viewmodel

import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.Product
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

// Data class for the AI-parsed search
data class ParsedSearch(
    val searchQuery: String = "",
    val filters: ParsedFilters = ParsedFilters()
)

data class ParsedFilters(
    val category: String? = null,
    val condition: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val storage: FirebaseStorage,
    private val gson: Gson
) : ViewModel() {

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    private val _facetCounts = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val facetCounts: StateFlow<Map<String, Map<String, Int>>> = _facetCounts.asStateFlow()

    private val _visualSearchSuggestion = MutableStateFlow<Pair<String, String>?>(null)
    val visualSearchSuggestion: StateFlow<Pair<String, String>?> = _visualSearchSuggestion.asStateFlow()

    private val _isProcessingVisualSearch = MutableStateFlow(false)
    val isProcessingVisualSearch: StateFlow<Boolean> = _isProcessingVisualSearch.asStateFlow()

    private val _conversationalSearchSuggestion = MutableStateFlow<ParsedSearch?>(null)
    val conversationalSearchSuggestion: StateFlow<ParsedSearch?> = _conversationalSearchSuggestion.asStateFlow()

    private val _isParsingSearch = MutableStateFlow(false)
    val isParsingSearch: StateFlow<Boolean> = _isParsingSearch.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    fun updateUserLocation(location: Location?) {
        _userLocation.value = location
    }

    private suspend fun callApi(action: String, data: Map<String, Any?>): com.google.firebase.functions.HttpsCallableResult {
        if (auth.currentUser == null) {
            Log.w("HomeViewModel", "User not authenticated when trying to call API action: $action")
            _authError.value = "You need to be logged in to perform this action. Please log in again."
            throw FirebaseAuthInvalidUserException("Authentication required.", "User is not logged in.")
        }
        val finalData = data.toMutableMap()
        Log.d("HomeViewModel", "Calling Cloud Function '$action' with data: $finalData")
        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to finalData)).await()
    }

    fun fetchProducts(
        latitude: Double?,
        longitude: Double?,
        query: String = "",
        filters: Map<String, Any?> = emptyMap()
    ) {
        viewModelScope.launch {
            _isRefreshing.value = true
            _authError.value = null
            try {
                val functionData = hashMapOf(
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "query" to query,
                    "filters" to filters
                )

                Log.d("HomeViewModel", "fetchProducts called. Query: '$query', Filters: $filters, Lat: $latitude, Lon: $longitude")

                val result = callApi("getRankedProducts", functionData)

                @Suppress("UNCHECKED_CAST")
                val resultData = result.data as? Map<String, Any?>
                val rankedProductData = resultData?.get("rankedProducts") as? List<Map<String, Any?>>

                val facetsData = resultData?.get("facets") as? Map<String, Any?>
                if (facetsData != null) {
                    try {
                        val jsonString = gson.toJson(facetsData)
                        val type = object : TypeToken<Map<String, Map<String, Int>>>() {}.type
                        _facetCounts.value = gson.fromJson(jsonString, type)
                        Log.d("HomeViewModel", "Parsed Facet Counts: ${_facetCounts.value}")
                    } catch (e: Exception) {
                        Log.e("HomeViewModel", "Error parsing facet data", e)
                        _facetCounts.value = emptyMap()
                    }
                } else {
                    _facetCounts.value = emptyMap()
                    Log.d("HomeViewModel", "No facet data received from function.")
                }

                Log.d("HomeViewModel", "Received raw rankedProductData from function: $rankedProductData")

                if (rankedProductData.isNullOrEmpty()) {
                    Log.d("HomeViewModel", "Ranked product data is null or empty.")
                    _products.value = emptyList()
                    _isRefreshing.value = false
                    return@launch
                }

                val productIds = rankedProductData.mapNotNull { it["id"] as? String }
                val distanceMap = rankedProductData.associate {
                    (it["id"] as? String) to (it["distanceKm"] as? Number)?.toDouble()
                }.filterKeys { it != null } as Map<String, Double?>

                Log.d("HomeViewModel", "Extracted Product IDs: $productIds")
                Log.d("HomeViewModel", "Extracted Distance Map: $distanceMap")

                if (productIds.isEmpty()) {
                    _products.value = emptyList()
                    _isRefreshing.value = false
                    return@launch
                }

                val productChunks = productIds.chunked(30)
                val allProducts = mutableListOf<Product>()

                for (chunk in productChunks) {
                    if (chunk.isEmpty()) continue
                    Log.d("HomeViewModel", "Fetching details from Firestore for chunk: ${chunk.size} IDs")
                    try {
                        val snapshot = firestore.collection("products")
                            .whereIn(FieldPath.documentId(), chunk)
                            .get().await()
                        allProducts.addAll(snapshot.documents.mapNotNull {
                            try { it.toObject<Product>()?.copy(id = it.id) }
                            catch (e: Exception) { Log.e("HomeViewModel", "Error converting Firestore doc ${it.id}", e); null }
                        })
                    } catch (e: Exception) { Log.e("HomeViewModel", "Error fetching Firestore chunk", e) }
                }
                Log.d("HomeViewModel", "Fetched details for ${allProducts.size} products from Firestore.")

                val productMap = allProducts.associateBy { it.id }
                val finalProductList = productIds.mapNotNull { id ->
                    productMap[id]?.copy(distanceKm = distanceMap[id])
                }

                _products.value = finalProductList
                Log.d("HomeViewModel", "Final product list size set to UI: ${finalProductList.size}")

            } catch (e: FirebaseAuthInvalidUserException) {
                Log.e("HomeViewModel", "Authentication error fetching ranked products: ${e.message}", e)
                _authError.value = "Your session seems invalid. Please log out and log back in."
                _products.value = emptyList()
                _facetCounts.value = emptyMap()
            } catch (e: FirebaseFunctionsException) {
                Log.e("HomeViewModel", "Firebase Functions Exception fetching ranked products: ${e.code} - ${e.message}", e)
                _authError.value = "Error communicating with server (${e.code}). Please try again."
                _products.value = emptyList()
                _facetCounts.value = emptyMap()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Generic error fetching ranked products", e)
                _authError.value = "An unexpected error occurred while fetching products."
                _products.value = emptyList()
                _facetCounts.value = emptyMap()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun saveSearch(query: String, category: String, minPrice: String, maxPrice: String, condition: String, onResult: (Boolean, String) -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            _authError.value = "You need to be logged in to save searches."
            return onResult(false, "You must be logged in to save searches.")
        }
        viewModelScope.launch {
            _authError.value = null
            try {
                val minPriceDouble = minPrice.toDoubleOrNull()
                val maxPriceDouble = maxPrice.toDoubleOrNull()
                val categoryToSend = category.takeIf { it != "All" }
                val conditionToSend = condition.takeIf { it != "Any Condition" }
                val queryToSend = query.takeIf { it.isNotBlank() }

                val data = hashMapOf(
                    "query" to queryToSend, "category" to categoryToSend, "minPrice" to minPriceDouble,
                    "maxPrice" to maxPriceDouble, "condition" to conditionToSend
                )
                Log.d("HomeViewModel", "Saving Search with data: $data")
                callApi("saveSearch", data)
                onResult(true, "Search saved!")
            } catch (e: FirebaseAuthInvalidUserException){
                Log.e("HomeViewModel", "Auth error saving search", e)
                _authError.value = "Authentication error saving search. Please log in again."
                onResult(false, "Authentication error.")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error saving search", e)
                _authError.value = "Could not save search: ${e.message}"
                onResult(false, e.message ?: "Could not save search.")
            }
        }
    }

    fun performVisualSearch(imageUri: Uri) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            _authError.value = "You need to be logged in to use visual search."
            return
        }
        viewModelScope.launch {
            _isProcessingVisualSearch.value = true
            _visualSearchSuggestion.value = null
            _authError.value = null
            try {
                val path = "visual_search_uploads/${currentUser.uid}_${System.currentTimeMillis()}.jpg"
                val ref = storage.reference.child(path)
                ref.putFile(imageUri).await()
                val downloadUrl = ref.downloadUrl.await().toString()
                Log.d("HomeViewModel", "Visual search image uploaded to: $downloadUrl")

                val data = mapOf("imageUrl" to downloadUrl)
                Log.d("HomeViewModel", "Calling visualSearch Cloud Function with image URL.")
                val result = callApi("visualSearch", data)

                @Suppress("UNCHECKED_CAST")
                val suggestions = (result.data as? Map<String, Any?>)?.get("suggestions") as? Map<String, String>
                val query = suggestions?.get("searchQuery")
                val category = suggestions?.get("category")

                Log.d("HomeViewModel", "Visual search suggestions received: query=$query, category=$category")

                if (!query.isNullOrBlank() && !category.isNullOrBlank()) {
                    _visualSearchSuggestion.value = Pair(query, category)
                } else {
                    Log.w("HomeViewModel", "Visual search returned invalid or missing query/category.")
                }

                try { ref.delete().await(); Log.d("HomeViewModel", "Temp visual search image deleted.") }
                catch (deleteError: Exception) { Log.e("HomeViewModel", "Failed to delete temp visual search image", deleteError) }

            } catch (e: FirebaseAuthInvalidUserException){
                Log.e("HomeViewModel", "Auth error during visual search", e)
                _authError.value = "Authentication error during visual search. Please log in again."
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error performing visual search", e)
                _authError.value = "Visual search failed: ${e.message}"
            } finally {
                _isProcessingVisualSearch.value = false
            }
        }
    }

    fun clearVisualSearchSuggestion() {
        _visualSearchSuggestion.value = null
    }

    fun performConversationalSearch(naturalQuery: String) {
        if (naturalQuery.isBlank()) return
        viewModelScope.launch {
            _isParsingSearch.value = true
            _authError.value = null
            try {
                val data = mapOf("query" to naturalQuery)
                val result = callApi("parseSearchQuery", data)

                @Suppress("UNCHECKED_CAST")
                val parsedSearchData = (result.data as? Map<String, Any?>)?.get("parsedSearch")

                val jsonString = gson.toJson(parsedSearchData)
                val parsedSearch = gson.fromJson(jsonString, ParsedSearch::class.java)

                if (parsedSearch != null) {
                    _conversationalSearchSuggestion.value = parsedSearch
                    Log.d("HomeViewModel", "Parsed search result: $parsedSearch")
                } else {
                    Log.w("HomeViewModel", "Conversational search returned invalid or missing data.")
                    _authError.value = "AI couldn't understand that. Try rephrasing."
                }

            } catch (e: FirebaseAuthInvalidUserException) {
                Log.e("HomeViewModel", "Auth error during conversational search", e)
                _authError.value = "Authentication error. Please log in again."
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error performing conversational search", e)
                _authError.value = "Conversational search failed: ${e.message}"
            } finally {
                _isParsingSearch.value = false
            }
        }
    }

    fun clearConversationalSearchSuggestion() {
        _conversationalSearchSuggestion.value = null
    }

    fun clearAuthError() {
        _authError.value = null
    }
}