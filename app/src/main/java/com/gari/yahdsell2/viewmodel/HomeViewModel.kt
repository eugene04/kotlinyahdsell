package com.gari.yahdsell2.viewmodel

import android.location.Location
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.model.SavedSearch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

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

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    var hasSeenLoginPrompt = false

    fun fetchProducts(latitude: Double?, longitude: Double?, query: String? = null, filters: Map<String, Any?> = emptyMap()) {
        // Implementation details...
    }

    fun performVisualSearch(uri: Uri) {
        // Implementation details...
    }

    fun clearVisualSearchSuggestion() {
        _visualSearchSuggestion.value = null
    }

    fun performConversationalSearch(query: String) {
        // Implementation details...
    }

    fun clearConversationalSearchSuggestion() {
        _conversationalSearchSuggestion.value = null
    }

    fun saveSearch(searchQuery: String, selectedCategory: String, minPrice: String, maxPrice: String, selectedCondition: String, onResult: (Boolean, String) -> Unit) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            onResult(false, "You must be logged in to save searches.")
            return
        }

        val savedSearch = SavedSearch(
            userId = userId,
            query = searchQuery.takeIf { it.isNotBlank() },
            category = selectedCategory.takeIf { it != "All" },
            minPrice = minPrice.toDoubleOrNull(),
            maxPrice = maxPrice.toDoubleOrNull(),
            condition = selectedCondition.takeIf { it != "Any Condition" }
        )

        firestore.collection("users").document(userId).collection("savedSearches").add(savedSearch)
            .addOnSuccessListener {
                onResult(true, "Search saved!")
            }
            .addOnFailureListener {
                onResult(false, "Failed to save search.")
            }
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun updateUserLocation(location: Location?) {
        _userLocation.value = location
    }
}

data class ParsedSearch(
    val searchQuery: String,
    val filters: ParsedFilters
)

data class ParsedFilters(
    val category: String?,
    val condition: String?,
    val minPrice: Double?,
    val maxPrice: Double?
)
