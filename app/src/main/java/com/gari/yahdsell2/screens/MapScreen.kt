package com.gari.yahdsell2.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState // Import for scrolling bottom sheet
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll // Import for scrolling bottom sheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Import all filled icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle // Use lifecycle-aware collection
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.gari.yahdsell2.viewmodel.HomeViewModel // Use HomeViewModel for products & facets
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.FacetCounts // Import type alias
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.compose.*
import com.google.maps.android.compose.clustering.Clustering
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.*
import androidx.compose.runtime.saveable.rememberSaveable

// Data class for cluster items
data class ProductClusterItem(
    val product: Product,
    val latLng: LatLng
) : ClusterItem {
    override fun getPosition(): LatLng = latLng
    override fun getTitle(): String = product.name
    override fun getSnippet(): String = formatPrice(product.price)
    override fun getZIndex(): Float? = 0f
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class) // Added ExperimentalLayoutApi
@Composable
fun MapScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(), // Use HomeViewModel
    bottomNavPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    // Collect state using lifecycle-aware collector
    val products by viewModel.products.collectAsStateWithLifecycle()
    val userLocation by viewModel.userLocation.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val facetCounts by viewModel.facetCounts.collectAsStateWithLifecycle() // Collect facets

    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSearchThisAreaButton by remember { mutableStateOf(false) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }

    // Local state for filters - these will now primarily control client-side filtering
    // and potentially be passed to the sheet for initialization
    var selectedCategory by rememberSaveable { mutableStateOf("All") } // Use rememberSaveable
    var selectedCondition by rememberSaveable { mutableStateOf("Any Condition") } // Use rememberSaveable
    var minPrice by rememberSaveable { mutableStateOf("") } // Use rememberSaveable
    var maxPrice by rememberSaveable { mutableStateOf("") } // Use rememberSaveable


    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(-19.0154, 29.1549), 6f) // Centered on Zimbabwe
    }
    val coroutineScope = rememberCoroutineScope()

    // Apply client-side filters (mainly for map markers if needed, or rely on ViewModel fetch)
    // You might simplify this if `viewModel.products` already reflects the filters applied during fetch
    val filteredProducts = remember(products, selectedCategory, selectedCondition, minPrice, maxPrice) {
        products.filter { product ->
            val appliedMinPrice = minPrice.toDoubleOrNull()
            val appliedMaxPrice = maxPrice.toDoubleOrNull()
            val matchesCategory = selectedCategory == "All" || product.category == selectedCategory
            val matchesCondition = selectedCondition == "Any Condition" || product.condition == selectedCondition
            val matchesMinPrice = appliedMinPrice == null || product.price >= appliedMinPrice
            val matchesMaxPrice = appliedMaxPrice == null || product.price <= appliedMaxPrice
            matchesCategory && matchesCondition && matchesMinPrice && matchesMaxPrice
        }
    }


    // Effect to recenter on user location when it first becomes available
    LaunchedEffect(userLocation) {
        userLocation?.let {
            coroutineScope.launch {
                cameraPositionState.animate(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition(LatLng(it.latitude, it.longitude), 12f, 0f, 0f)
                    )
                )
            }
        }
    }

    // Effect to show the "Search this area" button when the user stops moving the map
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.isMoving }
            .map { isMoving -> !isMoving && cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                showSearchThisAreaButton = true
            }
    }

    val sheetState = rememberModalBottomSheetState()
    if (showFilterSheet) {
        MapFilterBottomSheet(
            sheetState = sheetState,
            initialCategory = selectedCategory, // Pass current filter selections
            initialCondition = selectedCondition,
            initialMinPrice = minPrice,
            initialMaxPrice = maxPrice,
            categoryCounts = facetCounts["category"] ?: emptyMap(), // Pass counts
            conditionCounts = facetCounts["condition"] ?: emptyMap(), // Pass counts
            onDismiss = { showFilterSheet = false },
            onApply = { condition, min, max, category ->
                // Update local state, which will trigger recomposition and client-side filtering
                selectedCondition = condition
                minPrice = min
                maxPrice = max
                selectedCategory = category
                showFilterSheet = false
                // --- ADDED: Trigger fetchProducts with new filters ---
                val center = cameraPositionState.projection?.visibleRegion?.latLngBounds?.center
                viewModel.fetchProducts(
                    latitude = center?.latitude,
                    longitude = center?.longitude,
                    query = "", // Assuming map screen doesn't use text query directly
                    filters = mapOf(
                        "category" to category.takeIf { it != "All" },
                        "condition" to condition.takeIf { it != "Any Condition" },
                        "minPrice" to min.toDoubleOrNull(),
                        "maxPrice" to max.toDoubleOrNull()
                    ).filterValues { it != null }
                )
                // ----------------------------------------------------
            },
            onClear = {
                // Clear local state
                selectedCondition = "Any Condition"
                minPrice = ""
                maxPrice = ""
                selectedCategory = "All"
                // --- ADDED: Trigger fetchProducts with cleared filters ---
                val center = cameraPositionState.projection?.visibleRegion?.latLngBounds?.center
                viewModel.fetchProducts(
                    latitude = center?.latitude,
                    longitude = center?.longitude,
                    query = "",
                    filters = emptyMap() // Send empty map for cleared filters
                )
                // ------------------------------------------------------
                showFilterSheet = false // Hide sheet after clearing
            }
        )
    }

    Box(modifier = modifier
        .fillMaxSize()
        .padding(bottom = bottomNavPadding.calculateBottomPadding())) {
        GoogleMap(
            modifier = Modifier.matchParentSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = mapType),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
            onMapClick = { selectedProduct = null }
        ) {
            Clustering(
                items = filteredProducts.mapNotNull { product -> // Use client-side filtered products for markers
                    product.sellerLocation?.let { geoPoint ->
                        ProductClusterItem(product, LatLng(geoPoint.latitude, geoPoint.longitude))
                    }
                },
                clusterContent = { cluster ->
                    ClusterContent(size = cluster.size)
                },
                clusterItemContent = {
                    ProductMapPin()
                },
                onClusterItemClick = { item ->
                    selectedProduct = item.product
                    true // Consume the click
                }
            )
        }

        // UI Controls on top of the map
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(onClick = { showFilterSheet = true }, shape = CircleShape) {
                Icon(Icons.Default.Tune, "Filters")
            }
            FloatingActionButton(onClick = {
                mapType = if (mapType == MapType.NORMAL) MapType.SATELLITE else MapType.NORMAL
            }, shape = CircleShape) {
                Icon(Icons.Default.Layers, "Toggle Map Type")
            }
            FloatingActionButton(onClick = {
                userLocation?.let {
                    coroutineScope.launch {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 12f))
                    }
                }
            }, shape = CircleShape) {
                Icon(Icons.Default.MyLocation, "My Location")
            }
        }

        AnimatedVisibility(
            visible = showSearchThisAreaButton || isRefreshing,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Button(
                onClick = {
                    val center = cameraPositionState.projection?.visibleRegion?.latLngBounds?.center
                    // --- UPDATED: Pass current filters when searching area ---
                    viewModel.fetchProducts(
                        latitude = center?.latitude,
                        longitude = center?.longitude,
                        query = "", // Still assume no text query here
                        filters = mapOf(
                            "category" to selectedCategory.takeIf { it != "All" },
                            "condition" to selectedCondition.takeIf { it != "Any Condition" },
                            "minPrice" to minPrice.toDoubleOrNull(),
                            "maxPrice" to maxPrice.toDoubleOrNull()
                        ).filterValues { it != null }
                    )
                    // --------------------------------------------------------
                    showSearchThisAreaButton = false
                },
                enabled = !isRefreshing,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text("Search this area")
                }
            }
        }

        AnimatedVisibility(
            visible = selectedProduct != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            selectedProduct?.let { product ->
                MapInfoCard(
                    product = product,
                    onClick = {
                        navController.navigate(Screen.ProductDetail.createRoute(product.id))
                    }
                )
            }
        }
    }
}

// --- MODIFIED: MapFilterBottomSheet now accepts counts ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapFilterBottomSheet(
    sheetState: SheetState,
    initialCategory: String, // Added initial values
    initialCondition: String,
    initialMinPrice: String,
    initialMaxPrice: String,
    categoryCounts: Map<String, Int>, // Accept category counts
    conditionCounts: Map<String, Int>, // Accept condition counts
    onDismiss: () -> Unit,
    onApply: (condition: String, minPrice: String, maxPrice: String, category: String) -> Unit,
    onClear: () -> Unit
) {
    // Initialize local state from passed initial values
    var condition by remember { mutableStateOf(initialCondition) }
    var minPrice by remember { mutableStateOf(initialMinPrice) }
    var maxPrice by remember { mutableStateOf(initialMaxPrice) }
    var category by remember { mutableStateOf(initialCategory) }

    val conditions = listOf("Any Condition", "New", "Used - Like New", "Used - Good", "Used - Fair")
    val categories = listOf("All", "Electronics", "Clothing & Apparel", "Home & Garden", "Furniture", "Vehicles", "Books, Movies & Music", "Collectibles & Art", "Sports & Outdoors", "Toys & Hobbies", "Baby & Kids", "Health & Beauty", "Other")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .navigationBarsPadding() // Use navigationBarsPadding
                .verticalScroll(rememberScrollState()), // Make sheet scrollable
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))

            // Category Filter Chips with Counts
            Text("Category", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { item ->
                    val count = categoryCounts[item] ?: 0
                    val labelText = if (count > 0 && item != "All") "$item ($count)" else item
                    FilterChip(
                        selected = item == category,
                        onClick = { category = item },
                        label = { Text(labelText) },
                        enabled = count > 0 || item == "All"
                    )
                }
            }

            // Condition Filter Chips with Counts
            Text("Condition", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                conditions.forEach { item ->
                    val count = conditionCounts[item] ?: 0
                    val labelText = if (count > 0 && item != "Any Condition") "$item ($count)" else item
                    FilterChip(
                        selected = item == condition,
                        onClick = { condition = item },
                        label = { Text(labelText) },
                        enabled = count > 0 || item == "Any Condition"
                    )
                }
            }

            // Price Range Input
            Text("Price Range", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minPrice,
                    onValueChange = { minPrice = it.filter { char -> char.isDigit() || char == '.' } },
                    label = { Text("Min") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal) // Use Decimal
                )
                OutlinedTextField(
                    value = maxPrice,
                    onValueChange = { maxPrice = it.filter { char -> char.isDigit() || char == '.' } },
                    label = { Text("Max") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal) // Use Decimal
                )
            }

            HorizontalDivider()

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    // Reset local state first before calling onClear
                    condition = "Any Condition"
                    minPrice = ""
                    maxPrice = ""
                    category = "All"
                    onClear() // Call clear callback (which also triggers fetch)
                }) {
                    Text("Clear All")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onApply(condition, minPrice, maxPrice, category) }) { // Pass all values
                    Text("Apply Filters")
                }
            }
        }
    }
}
// ---------------------------------------------------


/**
 * A custom composable for a visually distinct map pin.
 */
@Composable
fun ProductMapPin() {
    val Maroon = Color(0xFF800000) // Define the maroon color

    Surface(
        shape = CircleShape,
        color = Maroon, // Use the new maroon color
        modifier = Modifier
            .size(28.dp)
            .border(2.dp, Color.White, CircleShape),
        shadowElevation = 4.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = "Product Location",
                tint = Color.White, // Use white for the icon for good contrast
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun ClusterContent(size: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(40.dp)
            .border(2.dp, Color.White, CircleShape),
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = size.toString(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MapInfoCard(product: Product, onClick: () -> Unit) {
    val imageRequest = ImageRequest.Builder(LocalContext.current)
        .data(product.imageUrls.firstOrNull() ?: "https://placehold.co/200x200?text=No+Image")
        .crossfade(true)
        .build()

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = rememberAsyncImagePainter(model = imageRequest),
                contentDescription = product.name,
                modifier = Modifier
                    .size(110.dp),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier.padding(12.dp).height(110.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = product.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatPrice(product.price),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

private fun formatPrice(price: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(price)
}
