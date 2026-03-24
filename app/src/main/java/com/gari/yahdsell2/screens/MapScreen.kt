package com.gari.yahdsell2.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.HomeViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

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

    // Local state for filters
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var selectedCondition by rememberSaveable { mutableStateOf("Any Condition") }
    var minPrice by rememberSaveable { mutableStateOf("") }
    var maxPrice by rememberSaveable { mutableStateOf("") }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(-19.0154, 29.1549), 6f) // Centered on Zimbabwe
    }
    val coroutineScope = rememberCoroutineScope()

    // Apply client-side filters
    val filteredProducts by remember {
        derivedStateOf {
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
    }

    // Effect to recenter on user location
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

    // Effect to show "Search this area" button
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.isMoving }
            .map { isMoving -> !isMoving && cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE }
            .distinctUntilChanged()
            .filter { it }
            .collect { showSearchThisAreaButton = true }
    }

    val sheetState = rememberModalBottomSheetState()
    if (showFilterSheet) {
        MapFilterBottomSheet(
            sheetState = sheetState,
            initialCategory = selectedCategory,
            initialCondition = selectedCondition,
            initialMinPrice = minPrice,
            initialMaxPrice = maxPrice,
            categoryCounts = facetCounts["category"] ?: emptyMap(),
            conditionCounts = facetCounts["condition"] ?: emptyMap(),
            onDismiss = { showFilterSheet = false },
            onApply = { condition, min, max, category ->
                selectedCondition = condition
                minPrice = min
                maxPrice = max
                selectedCategory = category
                showFilterSheet = false
                val center = cameraPositionState.projection?.visibleRegion?.latLngBounds?.center
                viewModel.fetchProducts(
                    latitude = center?.latitude,
                    longitude = center?.longitude,
                    filters = mapOf(
                        "category" to category.takeIf { it != "All" },
                        "condition" to condition.takeIf { it != "Any Condition" },
                        "minPrice" to min.toDoubleOrNull(),
                        "maxPrice" to max.toDoubleOrNull()
                    ).filterValues { it != null }
                )
            },
            onClear = {
                selectedCondition = "Any Condition"
                minPrice = ""
                maxPrice = ""
                selectedCategory = "All"
                val center = cameraPositionState.projection?.visibleRegion?.latLngBounds?.center
                viewModel.fetchProducts(latitude = center?.latitude, longitude = center?.longitude, filters = emptyMap())
                showFilterSheet = false
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
                items = filteredProducts.mapNotNull { product ->
                    product.sellerLocation?.let { geoPoint ->
                        ProductClusterItem(product, LatLng(geoPoint.latitude, geoPoint.longitude))
                    }
                },
                onClusterItemClick = { item ->
                    selectedProduct = item.product
                    true
                },
                clusterContent = { cluster -> ClusterContent(size = cluster.size) },
                clusterItemContent = { ProductMapPin() }
            )
        }

        // UI Controls
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(onClick = { showFilterSheet = true }, shape = CircleShape) { Icon(Icons.Default.Tune, "Filters") }
            FloatingActionButton(onClick = { mapType = if (mapType == MapType.NORMAL) MapType.SATELLITE else MapType.NORMAL }, shape = CircleShape) { Icon(Icons.Default.Layers, "Toggle Map Type") }
            FloatingActionButton(onClick = { userLocation?.let { coroutineScope.launch { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 12f)) } } }, shape = CircleShape) { Icon(Icons.Default.MyLocation, "My Location") }
        }

        AnimatedVisibility(
            visible = showSearchThisAreaButton || isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Button(
                onClick = {
                    val center = cameraPositionState.projection?.visibleRegion?.latLngBounds?.center
                    viewModel.fetchProducts(
                        latitude = center?.latitude,
                        longitude = center?.longitude,
                        filters = mapOf(
                            "category" to selectedCategory.takeIf { it != "All" },
                            "condition" to selectedCondition.takeIf { it != "Any Condition" },
                            "minPrice" to minPrice.toDoubleOrNull(),
                            "maxPrice" to maxPrice.toDoubleOrNull()
                        ).filterValues { it != null }
                    )
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            selectedProduct?.let { product ->
                MapInfoCard(product = product) { navController.navigate(Screen.ProductDetail.createRoute(product.id)) }
            }
        }
    }
}

@Composable
fun ClusterContent(size: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(40.dp).border(2.dp, Color.White, CircleShape)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("$size", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
fun ProductMapPin() {
    Icon(
        imageVector = Icons.Default.Gavel,
        contentDescription = "Product",
        modifier = Modifier.size(32.dp),
        tint = MaterialTheme.colorScheme.secondary
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapFilterBottomSheet(
    sheetState: SheetState,
    initialCategory: String,
    initialCondition: String,
    initialMinPrice: String,
    initialMaxPrice: String,
    categoryCounts: Map<String, Int>,
    conditionCounts: Map<String, Int>,
    onDismiss: () -> Unit,
    onApply: (condition: String, minPrice: String, maxPrice: String, category: String) -> Unit,
    onClear: () -> Unit
) {
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            
            Text("Category", style = MaterialTheme.typography.titleMedium)
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { item ->
                    val count = categoryCounts[item] ?: 0
                    val isEnabled = count > 0 || item == "All"
                    FilterChip(selected = item == category, onClick = { category = item }, label = { Text("$item ($count)") }, enabled = isEnabled)
                }
            }

            Text("Condition", style = MaterialTheme.typography.titleMedium)
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                conditions.forEach { item ->
                    val count = conditionCounts[item] ?: 0
                    val isEnabled = count > 0 || item == "Any Condition"
                    FilterChip(selected = item == condition, onClick = { condition = item }, label = { Text("$item ($count)") }, enabled = isEnabled)
                }
            }

            Text("Price Range", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = minPrice, onValueChange = { minPrice = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Min") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = maxPrice, onValueChange = { maxPrice = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Max") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            
            HorizontalDivider()
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onClear) { Text("Clear All") }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onApply(condition, minPrice, maxPrice, category) }) { Text("Apply Filters") }
            }
        }
    }
}

@Composable
fun MapInfoCard(product: Product, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = rememberAsyncImagePainter(model = product.imageUrls.firstOrNull()?:"".firstOrNull() ?: ""),
                contentDescription = product.name,
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(product.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatPrice(product.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(product.condition, style = MaterialTheme.typography.bodySmall)
            }
            Icon(imageVector = Icons.Default.Star, contentDescription = "View Details", modifier = Modifier.size(24.dp))
        }
    }
}

private fun formatPrice(price: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(price)
}
