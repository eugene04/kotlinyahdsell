package com.gari.yahdsell2.screens

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.UserState
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.navigation.Screen
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    toggleTheme: () -> Unit,
    bottomNavPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val products by viewModel.products.collectAsState()
    val userState by viewModel.userState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val unreadNotificationCount by viewModel.unreadNotificationCount.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()


    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedCondition by remember { mutableStateOf("Any Condition") }
    var minPrice by remember { mutableStateOf("") }
    var maxPrice by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                viewModel.updateUserLocation(location)
                location?.let {
                    viewModel.fetchProducts(it.latitude, it.longitude)
                }
            }
        } else {
            viewModel.updateUserLocation(null)
            viewModel.fetchProducts(null, null)
        }
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    if (showFilterSheet) {
        FilterBottomSheet(
            sheetState = sheetState,
            initialCondition = selectedCondition,
            initialMinPrice = minPrice,
            initialMaxPrice = maxPrice,
            onDismiss = { showFilterSheet = false },
            onApply = { newCondition, newMin, newMax ->
                selectedCondition = newCondition
                minPrice = newMin
                maxPrice = newMax
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    if (!sheetState.isVisible) {
                        showFilterSheet = false
                    }
                }
            },
            onClear = {
                selectedCondition = "Any Condition"
                minPrice = ""
                maxPrice = ""
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("YahdSell", color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Notifications.route) }) {
                        BadgedBox(
                            badge = {
                                val count = unreadNotificationCount
                                if (count > 0) {
                                    Badge { Text(if (count > 9) "9+" else "$count") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, "Notifications", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    val authUser = (userState as? UserState.Authenticated)?.user
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                if (authUser != null) navController.navigate(
                                    Screen.UserProfile.createRoute(
                                        authUser.uid
                                    )
                                )
                                else navController.navigate(Screen.Login.route)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ProfileAvatar(user = authUser)
                        Text("Profile", color = MaterialTheme.colorScheme.onPrimary)
                    }

                    if (userState is UserState.Authenticated) {
                        IconButton(onClick = {
                            viewModel.signOut()
                            navController.navigate(Screen.Login.route) { popUpTo(navController.graph.startDestinationId) { inclusive = true } }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Logout, "Logout", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Settings", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Toggle Dark Mode") },
                                onClick = {
                                    toggleTheme()
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Brightness4, "Toggle Dark Mode") }
                            )
                        }
                    }
                }
            )
        }
    ) { localScaffoldPadding ->
        val filteredProducts = remember(products, searchQuery, selectedCategory, selectedCondition, minPrice, maxPrice) {
            products.filter { product ->
                val appliedMinPrice = minPrice.toDoubleOrNull()
                val appliedMaxPrice = maxPrice.toDoubleOrNull()
                val matchesSearch = product.name.contains(searchQuery, ignoreCase = true) || product.description.contains(searchQuery, ignoreCase = true)
                val matchesCategory = selectedCategory == "All" || product.category == selectedCategory
                val matchesCondition = selectedCondition == "Any Condition" || product.condition == selectedCondition
                val matchesMinPrice = appliedMinPrice == null || product.price >= appliedMinPrice
                val matchesMaxPrice = appliedMaxPrice == null || product.price <= appliedMaxPrice
                matchesSearch && matchesCategory && matchesCondition && matchesMinPrice && matchesMaxPrice
            }
        }

        Column(modifier = Modifier
            .padding(bottom = bottomNavPadding.calculateBottomPadding())
            .padding(top = localScaffoldPadding.calculateTopPadding())
            .fillMaxSize()
        ) {
            val isFilterActive = searchQuery.isNotBlank() || selectedCategory != "All" || minPrice.isNotBlank() || maxPrice.isNotBlank() || selectedCondition != "Any Condition"
            SearchBarWithFilter(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onFilterClick = { showFilterSheet = true },
                isFilterActive = isFilterActive,
                onSaveSearch = {
                    viewModel.saveSearch(
                        query = searchQuery,
                        category = selectedCategory,
                        minPrice = minPrice,
                        maxPrice = maxPrice,
                        condition = selectedCondition,
                    ) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            )

            CategoryBrowser(
                selectedCategory = selectedCategory,
                onCategoryClick = { selectedCategory = it }
            )

            SwipeRefresh(
                state = rememberSwipeRefreshState(isRefreshing),
                onRefresh = {
                    userLocation?.let {
                        viewModel.fetchProducts(it.latitude, it.longitude)
                    } ?: viewModel.fetchProducts(null, null)
                },
                modifier = Modifier.weight(1f)
            ) {
                if (filteredProducts.isEmpty() && !isRefreshing) {
                    Box(Modifier
                        .fillMaxSize()
                        .padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("No products found. Try adjusting your filters.")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredProducts, key = { it.id }) { product ->
                            ProductCard(product = product) {
                                navController.navigate(Screen.ProductDetail.createRoute(product.id))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchBarWithFilter(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
    isFilterActive: Boolean,
    onSaveSearch: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("Search products...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            shape = RoundedCornerShape(50)
        )
        if (isFilterActive) {
            IconButton(onClick = onSaveSearch) {
                Icon(Icons.Default.BookmarkBorder, contentDescription = "Save Search")
            }
        }
        IconButton(onClick = onFilterClick) {
            Icon(Icons.Default.Tune, contentDescription = "Advanced Filters")
        }
    }
}


data class CategoryItem(val name: String, val icon: ImageVector)

@Composable
fun CategoryBrowser(
    selectedCategory: String,
    onCategoryClick: (String) -> Unit
) {
    val categories = listOf(
        CategoryItem("All", Icons.Default.Category),
        CategoryItem("Electronics", Icons.Default.PhoneAndroid),
        CategoryItem("Clothing & Apparel", Icons.Default.Checkroom),
        CategoryItem("Home & Garden", Icons.Default.Yard),
        CategoryItem("Furniture", Icons.Default.Chair),
        CategoryItem("Vehicles", Icons.Default.DirectionsCar),
        CategoryItem("Other", Icons.Default.MoreHoriz)
    )

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(categories) { category ->
            val isSelected = category.name == selectedCategory
            var buttonState by remember { mutableStateOf(ButtonState.Idle) }
            val scale by animateFloatAsState(if (buttonState == ButtonState.Pressed) 0.95f else 1f, label = "scale")

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .scale(scale)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitFirstDown(requireUnconsumed = false)
                                buttonState = ButtonState.Pressed
                                waitForUpOrCancellation()
                                buttonState = ButtonState.Idle
                            }
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onCategoryClick(category.name) }
                    )
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = category.name,
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = category.name,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private enum class ButtonState { Pressed, Idle }


@Composable
fun ProfileAvatar(user: FirebaseUser?) {
    if (user?.photoUrl != null) {
        Image(
            painter = rememberAsyncImagePainter(model = user.photoUrl),
            contentDescription = "Profile Picture",
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user?.displayName?.firstOrNull()?.toString()?.uppercase() ?: "Y",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBottomSheet(
    sheetState: SheetState,
    initialCondition: String,
    initialMinPrice: String,
    initialMaxPrice: String,
    onDismiss: () -> Unit,
    onApply: (condition: String, minPrice: String, maxPrice: String) -> Unit,
    onClear: () -> Unit
) {
    var condition by remember { mutableStateOf(initialCondition) }
    var minPrice by remember { mutableStateOf(initialMinPrice) }
    var maxPrice by remember { mutableStateOf(initialMaxPrice) }

    val conditions = listOf("Any Condition", "New", "Used - Like New", "Used - Good", "Used - Fair")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))

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
                    onClear()
                    onApply("Any Condition", "", "")
                }) {
                    Text("Clear All")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onApply(condition, minPrice, maxPrice) }) {
                    Text("Apply Filters")
                }
            }
        }
    }
}


@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit
) {
    val isAuction = product.auctionInfo != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (isAuction) Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(12.dp)) else Modifier),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box {
            Image(
                painter = rememberAsyncImagePainter(
                    model = product.imageUrls.firstOrNull() ?: "https://placehold.co/300x300?text=No+Image"
                ),
                contentDescription = product.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentScale = ContentScale.Crop
            )

            if (product.isSold) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SOLD",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            if (isAuction) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Gavel, contentDescription = "Auction", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("AUCTION", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val priceText = if (product.auctionInfo != null) {
                "Starts at ${formatPrice(product.auctionInfo.startingPrice)}"
            } else {
                formatPrice(product.price)
            }
            Text(
                text = priceText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = "by ${product.sellerDisplayName ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (product.sellerIsVerified) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = "Verified Seller",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeRemaining = getTimeRemaining(product.expiresAt)
                if (timeRemaining.isNotBlank()) {
                    InfoChip(icon = Icons.Default.Timer, text = timeRemaining)
                } else {
                    Spacer(Modifier.weight(1f))
                }

                product.distanceKm?.let { distance ->
                    InfoChip(icon = Icons.Default.LocationOn, text = "${"%.1f".format(distance)} km")
                }
            }
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatPrice(price: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(price)
}

private fun getTimeRemaining(expiryDate: Date?): String {
    if (expiryDate == null) return ""
    val now = Date()
    if (now.after(expiryDate)) return "Expired"

    val diff = expiryDate.time - now.time
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60

    return when {
        days > 1 -> "Ends in ${days}d"
        days > 0 -> "Ends in ${days}d ${hours}h"
        hours > 0 -> "Ends in ${hours}h ${minutes}m"
        minutes > 0 -> "Ends in ${minutes}m"
        else -> "Ending soon"
    }
}

