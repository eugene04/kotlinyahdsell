package com.gari.yahdsell2.viewmodel

import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// Import GeoPoint if needed for reconstructing from API response
import com.google.firebase.firestore.GeoPoint
import com.gari.yahdsell2.model.Product
import com.google.firebase.Timestamp // Import Timestamp if parsing dates
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException // Added for better error handling
import com.google.gson.Gson // Import Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken // Import TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.* // Import Date if parsing dates
import javax.inject.Inject

// --- Type Alias for Facets ---
typealias FacetCounts = Map<String, Map<String, Int>>

// --- State and Enum Definitions --- (Keep these)
enum class NearMeSortOption(val displayName: String) {
    DISTANCE_ASC("Nearest"),
    PRICE_ASC("Price: Low to High"),
    PRICE_DESC("Price: High to Low"),
    DATE_DESC("Newest")
}

data class NearMeFilterState(
    val category: String? = null,
    val condition: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null
)

@HiltViewModel
class NearMeViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val gson: Gson // Inject Gson
) : ViewModel() {

    // --- StateFlows for UI ---
    private val _nearbyProductsRaw = MutableStateFlow<List<Product>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _radiusKm = MutableStateFlow(10.0) // Default 10km
    val radiusKm: StateFlow<Double> = _radiusKm.asStateFlow()
    private val _filters = MutableStateFlow(NearMeFilterState())
    val filters: StateFlow<NearMeFilterState> = _filters.asStateFlow()
    private val _sortOption = MutableStateFlow(NearMeSortOption.DISTANCE_ASC)
    val sortOption: StateFlow<NearMeSortOption> = _sortOption.asStateFlow()

    // --- ADDED: StateFlow for facet counts ---
    private val _facetCounts = MutableStateFlow<FacetCounts>(emptyMap())
    val facetCounts: StateFlow<FacetCounts> = _facetCounts.asStateFlow()
    // ------------------------------------------

    // --- Derived StateFlow applies client-side SORTING ---
    // Filtering is now primarily done server-side via Cloud Function + Algolia
    val filteredAndSortedProducts: StateFlow<List<Product>> = combine(
        _nearbyProductsRaw, // This list comes filtered from the backend
        _sortOption
    ) { products: List<Product>, currentSort: NearMeSortOption ->
        // DEBUG: Log before sorting
        Log.d("NearMeViewModel", "Applying sort: $currentSort to ${products.size} raw products.")
        // Apply Sorting
        when (currentSort) {
            NearMeSortOption.DISTANCE_ASC -> products // Temporarily return the list as-isistanceKm comes from CF
            NearMeSortOption.PRICE_ASC -> products.sortedBy { it.price }
            NearMeSortOption.PRICE_DESC -> products.sortedByDescending { it.price }
            NearMeSortOption.DATE_DESC -> products.sortedByDescending { it.createdAt ?: Date(0) }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // --- Helper Functions ---
    private suspend fun callApi(action: String, data: Map<String, Any?>): com.google.firebase.functions.HttpsCallableResult {
        val finalData = data.toMutableMap()
        // Add user ID if needed by backend rules, though maybe not for public nearby search
        // auth.currentUser?.uid?.let { finalData["userId"] = it }

        // DEBUG: Log the data being sent TO the cloud function
        Log.d("NearMeViewModel", "Calling Cloud Function '$action' with data: $finalData")

        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to finalData)).await()
    }

    // --- REMOVED parseProductsFromMap - replaced with Gson parsing in fetch ---

    // --- Public Functions called by UI ---
    fun fetchNearbyProducts(location: Location, radius: Double) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _facetCounts.value = emptyMap() // Clear old facets
            try {
                // Prepare filters to send to the Cloud Function
                val currentFilters = _filters.value
                val filterMap = mapOf(
                    "category" to currentFilters.category?.takeIf { it != "All" }, // Send null if "All"
                    "condition" to currentFilters.condition?.takeIf { it != "Any Condition" }, // Send null if "Any Condition"
                    "minPrice" to currentFilters.minPrice,
                    "maxPrice" to currentFilters.maxPrice
                ).filterValues { it != null } // Remove null values

                val data = hashMapOf(
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "radiusKm" to radius,
                    "filters" to filterMap // Send the non-null filters
                )

                // DEBUG: Log before calling the cloud function
                Log.d("NearMeViewModel", "fetchNearbyProducts called. Filters: $filterMap, Radius: $radius, Loc: (${location.latitude}, ${location.longitude})")


                // --- UPDATED: Call getNearbyProducts action ---
                val result = callApi("getNearbyProducts", data)
                // --------------------------------------------

                // Result.data should be Map<String, Any?> containing "nearbyProducts" list and "facets" map
                @Suppress("UNCHECKED_CAST")
                val resultData = result.data as? Map<String, Any?>
                val nearbyProductData = resultData?.get("nearbyProducts") // This should be List<Map<String, Any?>>

                // --- ADDED: Parse Facet Counts ---
                val rawFacets = resultData?.get("facets") as? Map<String, Any?>
                val parsedFacets = mutableMapOf<String, Map<String, Int>>()
                rawFacets?.forEach { (facetName, facetValues) ->
                    val valueMap = facetValues as? Map<String, Number> // Algolia returns numbers
                    if (valueMap != null) {
                        parsedFacets[facetName] = valueMap.mapValues { it.value.toInt() }
                    }
                }
                _facetCounts.value = parsedFacets
                // DEBUG: Log the parsed facet counts
                Log.d("NearMeViewModel", "Parsed Facet Counts: $parsedFacets")
                // ---------------------------------


                // DEBUG: Log the raw data received FROM the cloud function
                Log.d("NearMeViewModel", "Received raw nearbyProductData from function: $nearbyProductData")


                if (nearbyProductData != null) {
                    // Use Gson to convert the generic list of maps into List<Product>
                    try {
                        val jsonString = gson.toJson(nearbyProductData)
                        val listType = object : TypeToken<List<Product>>() {}.type
                        val products: List<Product> = gson.fromJson(jsonString, listType)

                        _nearbyProductsRaw.value = products
                        // DEBUG: Log successful parsing and final list size
                        Log.d("NearMeViewModel", "Successfully parsed ${products.size} products from function result.")


                    } catch (e: JsonSyntaxException) {
                        Log.e("NearMeViewModel", "Gson parsing error", e)
                        _nearbyProductsRaw.value = emptyList()
                        _error.value = "Error parsing nearby products. Data format might be incorrect."
                        _facetCounts.value = emptyMap() // Clear facets on error
                    } catch (e: Exception) {
                        Log.e("NearMeViewModel", "Error processing function result", e)
                        _nearbyProductsRaw.value = emptyList()
                        _error.value = "Error processing nearby products."
                        _facetCounts.value = emptyMap() // Clear facets on error
                    }
                } else {
                    _nearbyProductsRaw.value = emptyList()
                    Log.w("NearMeViewModel", "Cloud function returned null or no 'nearbyProducts' field.")
                    // Facets might still be present even with zero hits, keep parsedFacets
                }
            } catch (e: FirebaseFunctionsException) { // Catch specific Firebase Functions errors
                Log.e("NearMeViewModel", "Firebase Functions Exception fetching nearby products: ${e.code} - ${e.message}", e)
                _error.value = "Error communicating with server (${e.code}). Please try again."
                _nearbyProductsRaw.value = emptyList() // Clear list on error
                _facetCounts.value = emptyMap() // Clear facets on error
            } catch (e: Exception) { // Catch generic errors
                Log.e("NearMeViewModel", "Error fetching nearby products", e)
                _error.value = e.message ?: "Failed to load nearby products."
                _nearbyProductsRaw.value = emptyList() // Clear list on error
                _facetCounts.value = emptyMap() // Clear facets on error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setRadius(newRadius: Double) {
        if (newRadius != _radiusKm.value) {
            Log.d("NearMeViewModel", "setRadius called. New radius: $newRadius") // DEBUG Log
            _radiusKm.value = newRadius
            // Let UI trigger refetch onValueChangeFinished or pull-to-refresh
        }
    }

    // This function updates the filter state. Refetching happens separately.
    fun applyFilters(category: String?, condition: String?, minPrice: Double?, maxPrice: Double?) {
        val newFilters = NearMeFilterState(
            category = category, // Store the raw selection ("All" or specific)
            condition = condition, // Store the raw selection ("Any Condition" or specific)
            minPrice = minPrice,
            maxPrice = maxPrice
        )
        // DEBUG: Log the attempt to apply filters
        Log.d("NearMeViewModel", "applyFilters called. New filters: $newFilters")
        // Update state only if changed
        if (_filters.value != newFilters) {
            _filters.value = newFilters
            // We don't automatically refetch here. Let pull-to-refresh or explicit
            // "search this area" button handle the refetch with the new filters.
            Log.d("NearMeViewModel", "Filters state updated.") // DEBUG Log
        } else {
            Log.d("NearMeViewModel", "Filters state unchanged, not updating.") // DEBUG Log
        }
    }

    // This function updates the sort state. Sorting happens client-side.
    fun setSortOption(sortOption: NearMeSortOption) {
        if (_sortOption.value != sortOption) {
            Log.d("NearMeViewModel", "setSortOption called. New sort: $sortOption") // DEBUG Log
            _sortOption.value = sortOption
            // No refetch needed, combine flow handles resorting client-side
        }
    }
}
