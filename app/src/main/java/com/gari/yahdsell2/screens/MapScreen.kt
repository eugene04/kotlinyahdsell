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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
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
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.navigation.Screen
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    bottomNavPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val products by viewModel.products.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSearchThisAreaButton by remember { mutableStateOf(false) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }

    // Local state for filters
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedCondition by remember { mutableStateOf("Any Condition") }
    var minPrice by remember { mutableStateOf("") }
    var maxPrice by remember { mutableStateOf("") }


    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(-19.0154, 29.1549), 6f) // Centered on Zimbabwe
    }
    val coroutineScope = rememberCoroutineScope()

    // Apply client-side filters
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
            onDismiss = { showFilterSheet = false },
            onApply = { condition, min, max, category ->
                // Update local state, which will trigger recomposition and filtering
                selectedCondition = condition
                minPrice = min
                maxPrice = max
                selectedCategory = category
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
                items = filteredProducts.mapNotNull { product -> // Use filtered products
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
                    viewModel.fetchProducts(center?.latitude, center?.longitude)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapFilterBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onApply: (condition: String, minPrice: String, maxPrice: String, category: String) -> Unit,
) {
    var condition by remember { mutableStateOf("Any Condition") }
    var minPrice by remember { mutableStateOf("") }
    var maxPrice by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }

    val conditions = listOf("Any Condition", "New", "Used - Like New", "Used - Good", "Used - Fair")
    val categories = listOf("All", "Electronics", "Clothing & Apparel", "Home & Garden", "Furniture", "Vehicles", "Other")


    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))

            Text("Category", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { item ->
                    FilterChip(
                        selected = item == category,
                        onClick = { category = item },
                        label = { Text(item) }
                    )
                }
            }

            Text("Condition", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conditions) { item ->
                    FilterChip(
                        selected = item == condition,
                        onClick = { condition = item },
                        label = { Text(item) }
                    )
                }
            }

            Text("Price Range", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minPrice,
                    onValueChange = { minPrice = it },
                    label = { Text("Min") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = maxPrice,
                    onValueChange = { maxPrice = it },
                    label = { Text("Max") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            HorizontalDivider()

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    condition = "Any Condition"
                    minPrice = ""
                    maxPrice = ""
                    category = "All"
                    onApply(condition, minPrice, maxPrice, category)
                }) {
                    Text("Clear All")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onApply(condition, minPrice, maxPrice, category) }) {
                    Text("Apply Filters")
                }
            }
        }
    }
}


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

