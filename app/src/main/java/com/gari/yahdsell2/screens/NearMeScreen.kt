package com.gari.yahdsell2.screens

// Android & System Imports
import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.util.Log // Import Log for debugging
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

// Compose Foundational Imports
import androidx.compose.foundation.Image // For Product Card Image
import androidx.compose.foundation.clickable
// ✅ ADDED specific import for ExperimentalLayoutApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.* // Includes Column, Row, Spacer, PaddingValues, Arrangement, FlowRow, etc.
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items // Keep this for LazyColumn items
import androidx.compose.foundation.rememberScrollState // For scrollable BottomSheet
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll // For scrollable BottomSheet
import androidx.compose.runtime.* // Includes remember, mutableStateOf, collectAsState, LaunchedEffect, etc.
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale // For Image ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Material 3 Imports
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Imports common M3 icons
import androidx.compose.material3.* // Imports Scaffold, TopAppBar, Button, Icon, Text, Slider, Card, etc.
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer // M3 PullRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState // M3 PullRefresh

// Hilt & Navigation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle // Use lifecycle-aware state collection

// ViewModel & Model
import com.gari.yahdsell2.model.Product // Ensure correct Product import
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.NearMeFilterState
import com.gari.yahdsell2.viewmodel.NearMeSortOption
import com.gari.yahdsell2.viewmodel.NearMeViewModel
import com.gari.yahdsell2.viewmodel.FacetCounts // Import type alias

// Other Libraries
import coil.compose.rememberAsyncImagePainter
// Correct FlowRow import is now part of androidx.compose.foundation.layout.* above
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun NearMeScreen(
    navController: NavController,
    viewModel: NearMeViewModel = hiltViewModel(),
    bottomNavPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    // Use collectAsStateWithLifecycle
    val nearbyProducts by viewModel.filteredAndSortedProducts.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val currentRadius by viewModel.radiusKm.collectAsStateWithLifecycle()
    val currentSort by viewModel.sortOption.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val facetCounts by viewModel.facetCounts.collectAsStateWithLifecycle() // Collect facet counts

    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var hasLocationPermission by remember { mutableStateOf(false) } // Track permission status
    var userLocation by remember { mutableStateOf<Location?>(null) } // Track location itself

    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(currentRadius.toFloat()) } // Local slider state

    // Initialize sliderValue based on ViewModel state
    LaunchedEffect(currentRadius) {
        sliderValue = currentRadius.toFloat()
    }

    val pullToRefreshState = rememberPullToRefreshState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasLocationPermission = isGranted
        if (isGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                userLocation = location // Update local location state
                if (location != null) {
                    // DEBUG: Log initial fetch after permission grant
                    Log.d("NearMeScreen", "Location granted, fetching initially. Radius: $currentRadius")
                    viewModel.fetchNearbyProducts(location, currentRadius)
                } else {
                    Toast.makeText(context, "Could not get location. Enable GPS and pull to refresh.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(context, "Location permission is required for the 'Near Me' feature.", Toast.LENGTH_LONG).show()
        }
    }

    // Request location on initial composition if permission not already granted
    // We might need a better way to check existing permission state here
    LaunchedEffect(Unit) {
        // TODO: Check if permission is already granted before launching
        // For now, always request on launch. Consider using Accompanist Permissions library for better checks.
        Log.d("NearMeScreen", "Requesting location permission on launch.")
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // Handle Pull to Refresh
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            if (hasLocationPermission) {
                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    userLocation = location // Update local location state
                    if (location != null) {
                        // DEBUG: Log fetch on pull to refresh
                        Log.d("NearMeScreen", "Pull refresh, fetching. Radius: $currentRadius, Filters: $filters")
                        viewModel.fetchNearbyProducts(location, currentRadius) // Use current radius and existing filters
                    } else {
                        Toast.makeText(context, "Could not get location. Enable GPS.", Toast.LENGTH_LONG).show()
                        pullToRefreshState.endRefresh()
                    }
                }.addOnFailureListener {
                    Toast.makeText(context, "Failed to get location.", Toast.LENGTH_SHORT).show()
                    pullToRefreshState.endRefresh()
                }
            } else {
                pullToRefreshState.endRefresh()
                Toast.makeText(context, "Location permission needed.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // End refresh animation when isLoading becomes false
    LaunchedEffect(isLoading) {
        if (!isLoading) {
            pullToRefreshState.endRefresh()
        }
    }

    // Filter Bottom Sheet State
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope() // Needed for hiding sheet
    if (showFilterSheet) {
        NearMeFilterBottomSheet(
            sheetState = sheetState,
            initialFilters = filters, // Pass the current filter state
            // --- MODIFIED: Pass facet counts ---
            categoryCounts = facetCounts["category"] ?: emptyMap(),
            conditionCounts = facetCounts["condition"] ?: emptyMap(),
            // ---------------------------------
            onDismiss = { showFilterSheet = false },
            onApply = { newFilters ->
                viewModel.applyFilters( // Update the ViewModel's filter state
                    category = newFilters.category,
                    condition = newFilters.condition,
                    minPrice = newFilters.minPrice,
                    maxPrice = newFilters.maxPrice
                )
                // --- ADDED: Trigger fetch after applying filters ---
                userLocation?.let { loc ->
                    // DEBUG: Log fetch after applying filters
                    Log.d("NearMeScreen", "Applying filters, fetching. Radius: $currentRadius, New Filters: $newFilters")
                    viewModel.fetchNearbyProducts(loc, currentRadius) // Use current radius and new filters
                } ?: Toast.makeText(context, "Location needed to apply filters.", Toast.LENGTH_SHORT).show()
                // --------------------------------------------------
                scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) showFilterSheet = false }
            },
            onClear = {
                viewModel.applyFilters(null, null, null, null) // Clear filters in ViewModel
                // --- ADDED: Trigger fetch after clearing filters ---
                userLocation?.let { loc ->
                    // DEBUG: Log fetch after clearing filters
                    Log.d("NearMeScreen", "Clearing filters, fetching. Radius: $currentRadius")
                    viewModel.fetchNearbyProducts(loc, currentRadius) // Use current radius and cleared filters
                }
                // ----------------------------------------------------
                scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) showFilterSheet = false } // Also hide sheet on clear
            }
        )
    }

    Scaffold(
        modifier = modifier.padding(bottom = bottomNavPadding.calculateBottomPadding()),
        topBar = {
            TopAppBar(
                title = { Text("Near Me (${currentRadius.toInt()} km)") },
                actions = {
                    // Sort Button & Menu
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort Products")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            // Use NearMeSortOption.entries for enum values
                            NearMeSortOption.entries.forEach { sortOption ->
                                DropdownMenuItem(
                                    text = { Text(sortOption.displayName) },
                                    onClick = {
                                        Log.d("NearMeScreen", "Setting sort option: ${sortOption.displayName}") // DEBUG: Log sort change
                                        viewModel.setSortOption(sortOption)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (currentSort == sortOption) {
                                            Icon(Icons.Default.Check, contentDescription = "Selected")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    // Filter Button
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter Products")
                    }
                    // Recenter Button (Refreshes data for current location)
                    IconButton(onClick = {
                        if (hasLocationPermission) {
                            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                                userLocation = location // Update local location state
                                if (location != null) {
                                    // DEBUG: Log fetch on recenter click
                                    Log.d("NearMeScreen", "Recenter click, fetching. Radius: $currentRadius, Filters: $filters")
                                    viewModel.fetchNearbyProducts(location, currentRadius) // Re-fetch with current radius and filters
                                } else {
                                    Toast.makeText(context, "Could not get current location.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Log.d("NearMeScreen", "Recenter click, requesting permission first.") // DEBUG: Log permission request
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Refresh Location")
                    }
                }
            )
        }
    ) { localScaffoldPadding ->
        Column(modifier = Modifier.padding(localScaffoldPadding)) {
            // Radius Slider
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 1f..50f,
                steps = 48, // Creates steps of 1km
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), // Added vertical padding
                onValueChangeFinished = {
                    val newRadius = sliderValue.toDouble()
                    viewModel.setRadius(newRadius) // Update ViewModel state
                    userLocation?.let { loc -> // Re-fetch data with new radius
                        // DEBUG: Log fetch after slider change finished
                        Log.d("NearMeScreen", "Slider finished, fetching. New Radius: $newRadius, Filters: $filters")
                        viewModel.fetchNearbyProducts(loc, newRadius) // Fetch with new radius and current filters
                    }
                }
            )

            // Content Area with Pull-to-Refresh
            Box(
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(pullToRefreshState.nestedScrollConnection)
            ) {
                when {
                    !hasLocationPermission -> {
                        Column(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.LocationOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("Location permission is required to find items near you.", textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                                Text("Grant Permission")
                            }
                        }
                    }
                    isLoading && nearbyProducts.isEmpty() -> { // Show loading only on initial load or radius/filter change when list is empty
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Column(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            Text("Error: $error", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { // Offer a retry mechanism
                                userLocation?.let { loc -> viewModel.fetchNearbyProducts(loc, currentRadius) }
                                    ?: locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }) {
                                Text("Retry")
                            }
                        }
                    }
                    userLocation == null && !isLoading && hasLocationPermission-> { // After permission granted, but location still null
                        Column(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.GpsNotFixed, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("Could not retrieve your location. Please ensure GPS is enabled and pull down to refresh.", textAlign = TextAlign.Center)
                        }
                    }
                    nearbyProducts.isEmpty() -> {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("No products found within ${currentRadius.toInt()} km matching your filters. Try increasing the radius or clearing filters.", textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(nearbyProducts, key = { it.id }) { product ->
                                NearMeProductCard(
                                    product = product,
                                    onClick = {
                                        navController.navigate(Screen.ProductDetail.createRoute(product.id))
                                    }
                                )
                            }
                        }
                    }
                }

                // Pull to refresh indicator
                PullToRefreshContainer(
                    state = pullToRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

// Simple Card for Near Me items - Adapt as needed (e.g., from HomeScreen's ProductCard)
@Composable
fun NearMeProductCard(
    product: Product,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = product.imageUrls.firstOrNull()?:"".firstOrNull() ?: "https://placehold.co/200x200?text=No+Image"
                ),
                contentDescription = product.name,
                modifier = Modifier
                    .size(80.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(formatPrice(product.price), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))

            }
        }
    }
}

// --- MODIFIED: Accept and display facet counts ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NearMeFilterBottomSheet(
    sheetState: SheetState,
    initialFilters: NearMeFilterState, // Use the state object
    categoryCounts: Map<String, Int>, // Accept category counts
    conditionCounts: Map<String, Int>, // Accept condition counts
    onDismiss: () -> Unit,
    onApply: (NearMeFilterState) -> Unit, // Pass back the full state
    onClear: () -> Unit
) {
    var condition by remember { mutableStateOf(initialFilters.condition ?: "Any Condition") }
    var minPrice by remember { mutableStateOf(initialFilters.minPrice?.toString() ?: "") }
    var maxPrice by remember { mutableStateOf(initialFilters.maxPrice?.toString() ?: "") }
    var category by remember { mutableStateOf(initialFilters.category ?: "All") }

    val conditions = listOf("Any Condition", "New", "Used - Like New", "Used - Good", "Used - Fair")
    // Consider fetching categories dynamically if they change often
    val categories = listOf("All", "Electronics", "Clothing & Apparel", "Home & Garden", "Furniture", "Vehicles", "Books, Movies & Music", "Collectibles & Art", "Sports & Outdoors", "Toys & Hobbies", "Baby & Kids", "Health & Beauty", "Other")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .navigationBarsPadding() // Use navigationBarsPadding for inset handling
                .verticalScroll(rememberScrollState()), // Make sheet scrollable
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))

            // Category Filter Chips
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

            // Condition Filter Chips
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
                    onValueChange = { minPrice = it.filter { char -> char.isDigit() || char == '.' } }, // Allow digits and decimal
                    label = { Text("Min") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal) // Use correct KeyboardType
                )
                OutlinedTextField(
                    value = maxPrice,
                    onValueChange = { maxPrice = it.filter { char -> char.isDigit() || char == '.' } }, // Allow digits and decimal
                    label = { Text("Max") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal) // Use correct KeyboardType
                )
            }

            HorizontalDivider()

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    // Reset local state first
                    condition = "Any Condition"
                    minPrice = ""
                    maxPrice = ""
                    category = "All"
                    onClear() // Call the ViewModel clear function AND trigger fetch
                }) {
                    Text("Clear All")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = {
                    onApply( // Call ViewModel apply AND trigger fetch
                        NearMeFilterState(
                            category = category.takeIf { it != "All" },
                            condition = condition.takeIf { it != "Any Condition" },
                            minPrice = minPrice.toDoubleOrNull(),
                            maxPrice = maxPrice.toDoubleOrNull()
                        )
                    )
                }) {
                    Text("Apply Filters")
                }
            }
        }
    }
}
// ---------------------------------------------------

// Helper function (if not already defined globally)
private fun formatPrice(price: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(price)
}
