package com.gari.yahdsell2.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.AuthViewModel
import com.gari.yahdsell2.viewmodel.HomeViewModel
import com.gari.yahdsell2.viewmodel.UserState
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun HomeScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
    toggleTheme: () -> Unit,
    bottomNavPadding: PaddingValues
) {
    val products by homeViewModel.products.collectAsStateWithLifecycle()
    val userState by authViewModel.userState.collectAsStateWithLifecycle()
    val isRefreshing by homeViewModel.isRefreshing.collectAsStateWithLifecycle()
    val facetCounts by homeViewModel.facetCounts.collectAsStateWithLifecycle()
    val visualSearchSuggestion by homeViewModel.visualSearchSuggestion.collectAsStateWithLifecycle()
    val isProcessingVisualSearch by homeViewModel.isProcessingVisualSearch.collectAsStateWithLifecycle()
    val conversationalSearchSuggestion by homeViewModel.conversationalSearchSuggestion.collectAsStateWithLifecycle()
    val isParsingSearch by homeViewModel.isParsingSearch.collectAsStateWithLifecycle()
    val authError by homeViewModel.authError.collectAsStateWithLifecycle()
    val userLocation by homeViewModel.userLocation.collectAsStateWithLifecycle()

    val pullToRefreshState = rememberPullToRefreshState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var selectedCondition by rememberSaveable { mutableStateOf("Any Condition") }
    var minPrice by rememberSaveable { mutableStateOf("") }
    var maxPrice by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showImageSourceDialog by remember { mutableStateOf(false) }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var initialLoadDone by rememberSaveable { mutableStateOf(false) }

    var showAuthPrompt by remember { mutableStateOf(false) }
    var isAuthCheckComplete by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val keyboardController = LocalSoftwareKeyboardController.current


    val visualSearchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let { homeViewModel.performVisualSearch(it) } }
    )

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                tempCameraImageUri?.let { homeViewModel.performVisualSearch(it) }
            }
        }
    )

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            val newUri = createImageUri(context)
            tempCameraImageUri = newUri
            cameraLauncher.launch(newUri)
        } else {
            Toast.makeText(context, "Camera permission is required to take a photo.", Toast.LENGTH_SHORT).show()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                homeViewModel.updateUserLocation(location)
                Log.d("HomeScreen", "Location granted, fetching initially. Loc: $location")
                homeViewModel.fetchProducts(location?.latitude, location?.longitude)
            }
        } else {
            homeViewModel.updateUserLocation(null)
            Log.d("HomeScreen", "Location denied, fetching without location.")
            homeViewModel.fetchProducts(null, null) // Fetch without location if denied
            Toast.makeText(context, "Location permission denied. Showing results for all areas.", Toast.LENGTH_LONG).show()
        }
        initialLoadDone = true
    }

    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    val savedSearchResult = savedStateHandle?.getLiveData<Bundle>("criteria_key")

    LaunchedEffect(savedSearchResult) {
        savedSearchResult?.observeForever { criteriaBundle ->
            if (savedStateHandle.contains("criteria_key")) {
                searchQuery = criteriaBundle.getString("query") ?: ""
                selectedCategory = criteriaBundle.getString("category") ?: "All"
                minPrice = criteriaBundle.getDouble("minPrice", -1.0).takeIf { it >= 0 }?.toString() ?: ""
                maxPrice = criteriaBundle.getDouble("maxPrice", -1.0).takeIf { it >= 0 }?.toString() ?: ""
                selectedCondition = criteriaBundle.getString("condition") ?: "Any Condition"

                savedStateHandle.remove<Bundle>("criteria_key")
            }
        }
    }

    LaunchedEffect(userState) {
        if (userState is UserState.Loading) {
            // Keep loading
        } else {
            isAuthCheckComplete = true
            if (userState is UserState.Unauthenticated && !homeViewModel.hasSeenLoginPrompt) {
                showAuthPrompt = true
                homeViewModel.hasSeenLoginPrompt = true
            }
        }
    }

    LaunchedEffect(userState) {
        if (initialLoadDone || savedStateHandle?.contains("criteria_key") == true) {
            return@LaunchedEffect
        }

        if (userState is UserState.Authenticated || userState is UserState.Unauthenticated) {
            Log.d("HomeScreen", "Initial load: Checking location and fetching products.")
            when (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                PackageManager.PERMISSION_GRANTED -> {
                    Log.d("HomeScreen", "Location permission already granted, fetching location.")
                    fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                        homeViewModel.updateUserLocation(location)
                        homeViewModel.fetchProducts(location?.latitude, location?.longitude)
                        initialLoadDone = true
                    }
                }
                else -> {
                    Log.d("HomeScreen", "Location permission not granted, requesting.")
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
        }
    }

    LaunchedEffect(visualSearchSuggestion) {
        visualSearchSuggestion?.let { (query, category) ->
            searchQuery = query
            selectedCategory = category
            selectedCondition = "Any Condition"
            minPrice = ""
            maxPrice = ""
            Toast.makeText(context, "Filters updated based on image!", Toast.LENGTH_SHORT).show()
            homeViewModel.clearVisualSearchSuggestion()
        }
    }

    LaunchedEffect(conversationalSearchSuggestion) {
        conversationalSearchSuggestion?.let { parsedSearch ->
            Log.d("HomeScreen", "Applying conversational search: $parsedSearch")
            searchQuery = parsedSearch.searchQuery
            selectedCategory = parsedSearch.filters.category ?: "All"
            selectedCondition = parsedSearch.filters.condition ?: "Any Condition"
            minPrice = parsedSearch.filters.minPrice?.toString() ?: ""
            maxPrice = parsedSearch.filters.maxPrice?.toString() ?: ""

            homeViewModel.clearConversationalSearchSuggestion()
        }
    }

    LaunchedEffect(searchQuery, selectedCategory, selectedCondition, minPrice, maxPrice) {
        if (!initialLoadDone) {
            return@LaunchedEffect
        }
        delay(300) // Debounce
        Log.d("HomeScreen", "Debounced Search Triggered - Query: $searchQuery, Category: $selectedCategory")
        homeViewModel.fetchProducts(
            latitude = userLocation?.latitude,
            longitude = userLocation?.longitude,
            query = searchQuery,
            filters = mapOf(
                "category" to selectedCategory.takeIf { it != "All" },
                "condition" to selectedCondition.takeIf { it != "Any Condition" },
                "minPrice" to minPrice.toDoubleOrNull(),
                "maxPrice" to maxPrice.toDoubleOrNull()
            ).filterValues { it != null }
        )
    }

    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            Log.d("HomeScreen", "Pull Refresh - Query: $searchQuery, Category: $selectedCategory")
            homeViewModel.fetchProducts(
                latitude = userLocation?.latitude,
                longitude = userLocation?.longitude,
                query = searchQuery,
                filters = mapOf(
                    "category" to selectedCategory.takeIf { it != "All" },
                    "condition" to selectedCondition.takeIf { it != "Any Condition" },
                    "minPrice" to minPrice.toDoubleOrNull(),
                    "maxPrice" to maxPrice.toDoubleOrNull()
                ).filterValues { it != null }
            )
        }
    }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) {
            pullToRefreshState.endRefresh()
        }
    }

    LaunchedEffect(authError) {
        authError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            homeViewModel.clearAuthError()
        }
    }

    if (showImageSourceDialog) {
        ImageSourceSelectionDialog(
            onDismiss = { showImageSourceDialog = false },
            onTakePhoto = {
                showImageSourceDialog = false
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onChooseFromGallery = {
                showImageSourceDialog = false
                visualSearchLauncher.launch("image/*")
            }
        )
    }

    if (showAuthPrompt && isAuthCheckComplete) {
        LoginPromptDialog(
            onDismiss = { showAuthPrompt = false },
            onLogin = {
                navController.navigate(Screen.Login.route)
                showAuthPrompt = false
            },
            onSignup = {
                navController.navigate(Screen.Signup.route)
                showAuthPrompt = false
            }
        )
    }

    if (showFilterSheet) {
        FilterBottomSheet(
            sheetState = sheetState,
            initialCondition = selectedCondition,
            initialMinPrice = minPrice,
            initialMaxPrice = maxPrice,
            conditionCounts = facetCounts["condition"] ?: emptyMap(),
            onDismiss = { showFilterSheet = false },
            onApply = { newCondition, newMin, newMax ->
                selectedCondition = newCondition
                minPrice = newMin
                maxPrice = newMax
                scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) showFilterSheet = false }
            },
            onClear = {
                selectedCondition = "Any Condition"
                minPrice = ""
                maxPrice = ""
                scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) showFilterSheet = false }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YahdSell", color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Notifications.route) }) {
                        BadgedBox(badge = { /* Badge logic */ }) {
                            Icon(Icons.Default.Notifications, "Notifications", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    val authUser = (userState as? UserState.Authenticated)?.user

                    if (authUser != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { navController.navigate(Screen.UserProfile.createRoute(authUser.uid)) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ProfileAvatar(user = authUser)
                            Text("Profile", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        Button(
                            onClick = { navController.navigate(Screen.Login.route) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text("Login", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (userState is UserState.Authenticated) {
                        IconButton(onClick = {
                            authViewModel.signOut()
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
                                onClick = { toggleTheme(); showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Brightness4, "Toggle Dark Mode") }
                            )
                        }
                    }
                }
            )
        },
        // ✅ ADDED: Sell Button (Floating Action Button)
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // ✅ FIXED: Removed double padding. The button will now sit naturally at the bottom right.
                onClick = {
                    if (userState is UserState.Authenticated) {
                        // User is logged in -> Go to Submission Screen
                        navController.navigate(Screen.Submit.createRoute())
                    } else {
                        // User is NOT logged in -> Show Prompt
                        showAuthPrompt = true
                    }
                },
                icon = { Icon(Icons.Default.Add, contentDescription = "Sell Item") },
                text = { Text("Sell") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { localScaffoldPadding ->
        Column(
            modifier = Modifier
                // ✅ FIXED: Removed double padding here as well. The NavHost already handles the bottom spacing.
                .padding(top = localScaffoldPadding.calculateTopPadding())
                .fillMaxSize()
        ) {
            val isFilterActive = searchQuery.isNotBlank() || selectedCategory != "All" || minPrice.isNotBlank() || maxPrice.isNotBlank() || selectedCondition != "Any Condition"

            SearchBarWithFilter(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onFilterClick = { showFilterSheet = true },
                isFilterActive = isFilterActive,
                onSearchSubmit = { keyboardController?.hide(); homeViewModel.performConversationalSearch(searchQuery) },
                onSaveSearch = {
                    homeViewModel.saveSearch(searchQuery, selectedCategory, minPrice, maxPrice, selectedCondition) { _, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                },
                onVisualSearchClick = {
                    if (userState is UserState.Authenticated) showImageSourceDialog = true
                    else {
                        Toast.makeText(context, "Please log in to use Visual Search", Toast.LENGTH_SHORT).show()
                        navController.navigate(Screen.Login.route)
                    }
                },
                isVisualSearchLoading = isProcessingVisualSearch,
                isConversationalSearchLoading = isParsingSearch,
                onClearSearch = {
                    searchQuery = ""
                    selectedCategory = "All"
                    selectedCondition = "Any Condition"
                    minPrice = ""
                    maxPrice = ""
                }
            )

            CategoryBrowser(
                selectedCategory = selectedCategory,
                categoryCounts = facetCounts["category"] ?: emptyMap(),
                onCategoryClick = { newCategory ->
                    selectedCategory = newCategory
                }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(pullToRefreshState.nestedScrollConnection)
            ) {
                when {
                    isRefreshing && !pullToRefreshState.isRefreshing && products.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    products.isEmpty() && !isRefreshing -> {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("No products found. Try adjusting your search or filters.", textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(products, key = { it.id }) { product ->
                                ProductCard(product = product) {
                                    navController.navigate(Screen.ProductDetail.createRoute(product.id))
                                }
                            }
                        }
                    }
                }
                PullToRefreshContainer(
                    state = pullToRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
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
    onSearchSubmit: () -> Unit,
    onSaveSearch: () -> Unit,
    onVisualSearchClick: () -> Unit,
    isVisualSearchLoading: Boolean,
    isConversationalSearchLoading: Boolean,
    onClearSearch: () -> Unit
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
            label = { Text("Search or ask AI...") },
            leadingIcon = {
                IconButton(onClick = onVisualSearchClick, enabled = !isVisualSearchLoading && !isConversationalSearchLoading) {
                    if (isVisualSearchLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    else Icon(Icons.Default.PhotoCamera, contentDescription = "Visual Search")
                }
            },
            trailingIcon = {
                when {
                    isConversationalSearchLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    searchQuery.isNotEmpty() -> IconButton(onClick = onClearSearch) { Icon(Icons.Default.Clear, contentDescription = "Clear Search") }
                    else -> Icon(Icons.Default.Search, contentDescription = "Search")
                }
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboardController?.hide()
                onSearchSubmit() // Call the conversational search
            }),
            shape = RoundedCornerShape(50)
        )
        if (isFilterActive) {
            IconButton(onClick = onSaveSearch, enabled = !isConversationalSearchLoading) {
                Icon(Icons.Default.BookmarkBorder, contentDescription = "Save Search")
            }
        }
        IconButton(onClick = onFilterClick, enabled = !isConversationalSearchLoading) {
            Icon(Icons.Default.Tune, contentDescription = "Advanced Filters")
        }
    }
}

@Composable
fun ImageSourceSelectionDialog(onDismiss: () -> Unit, onTakePhoto: () -> Unit, onChooseFromGallery: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Visual Search") },
        text = {
            Column {
                Text("Search for items using a photo.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Take Photo"); Spacer(Modifier.width(8.dp)); Text("Take Photo")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onChooseFromGallery, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Image, contentDescription = "Choose from Gallery"); Spacer(Modifier.width(8.dp)); Text("Choose from Gallery")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun createImageUri(context: Context): Uri {
    val authority = "${context.packageName}.provider"
    val imageFile = File(context.cacheDir, "visual_search_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, authority, imageFile)
}

data class CategoryItem(val name: String, val icon: ImageVector)

@Composable
fun CategoryBrowser(
    selectedCategory: String,
    categoryCounts: Map<String, Int>,
    onCategoryClick: (String) -> Unit
) {
    val categories = listOf(
        CategoryItem("All", Icons.Default.Category),
        CategoryItem("Electronics", Icons.Default.PhoneAndroid),
        CategoryItem("Clothing & Apparel", Icons.Default.Checkroom),
        CategoryItem("Home & Garden", Icons.Default.Yard),
        CategoryItem("Furniture", Icons.Default.Chair),
        CategoryItem("Vehicles", Icons.Default.DirectionsCar),
        CategoryItem("Books, Movies & Music", Icons.AutoMirrored.Filled.MenuBook),
        CategoryItem("Collectibles & Art", Icons.Default.Palette),
        CategoryItem("Sports & Outdoors", Icons.Default.SportsBasketball),
        CategoryItem("Toys & Hobbies", Icons.Default.Toys),
        CategoryItem("Baby & Kids", Icons.Default.ChildFriendly),
        CategoryItem("Health & Beauty", Icons.Default.Spa),
        CategoryItem("Other", Icons.Default.MoreHoriz)
    )

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(categories) { category ->
            val isSelected = category.name == selectedCategory
            val count = categoryCounts[category.name] ?: 0
            val isEnabled = count > 0 || category.name == "All"
            val categoryText = if (isEnabled && category.name != "All") "${category.name} ($count)" else category.name
            val alpha by animateFloatAsState(targetValue = if (isEnabled) 1f else 0.5f, label = "alpha")

            var buttonState by remember { mutableStateOf(ButtonState.Idle) }
            val scale by animateFloatAsState(if (buttonState == ButtonState.Pressed) 0.95f else 1f, label = "scale")

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer(alpha = alpha)
                    .scale(scale)
                    .pointerInput(isEnabled) {
                        if (!isEnabled) return@pointerInput
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
                        enabled = isEnabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (isEnabled) onCategoryClick(category.name) }
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
                    text = categoryText,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private enum class ButtonState { Pressed, Idle }

@Composable
fun ProfileAvatar(user: FirebaseUser?) {
    val painter = rememberAsyncImagePainter(
        model = user?.photoUrl ?: "https://placehold.co/100x100?text=${user?.displayName?.firstOrNull()?.uppercase() ?: '?'}"
    )
    Image(
        painter = painter,
        contentDescription = "Profile Picture",
        modifier = Modifier.size(32.dp).clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    sheetState: SheetState,
    initialCondition: String,
    initialMinPrice: String,
    initialMaxPrice: String,
    conditionCounts: Map<String, Int>,
    onDismiss: () -> Unit,
    onApply: (condition: String, minPrice: String, maxPrice: String) -> Unit,
    onClear: () -> Unit
) {
    var condition by remember(initialCondition) { mutableStateOf(initialCondition) }
    var minPrice by remember(initialMinPrice) { mutableStateOf(initialMinPrice) }
    var maxPrice by remember(initialMaxPrice) { mutableStateOf(initialMaxPrice) }
    val conditions = listOf("Any Condition", "New", "Used - Like New", "Used - Good", "Used - Fair")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("Condition", style = MaterialTheme.typography.titleMedium)

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                conditions.forEach { item ->
                    val count = conditionCounts[item] ?: 0
                    val isEnabled = count > 0 || item == "Any Condition"
                    val labelText = if (isEnabled && item != "Any Condition") "$item ($count)" else item

                    FilterChip(
                        selected = item == condition,
                        onClick = { condition = item },
                        label = { Text(labelText) },
                        enabled = isEnabled
                    )
                }
            }

            Text("Price Range", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = minPrice, onValueChange = { minPrice = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Min") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = maxPrice, onValueChange = { maxPrice = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Max") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { onClear() }) { Text("Clear All") }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onApply(condition, minPrice, maxPrice) }) { Text("Apply Filters") }
            }
        }
    }
}

@Composable
fun ProductCard(product: Product, onClick: () -> Unit) {
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
                painter = rememberAsyncImagePainter(model = product.imageUrls.firstOrNull()?:"".firstOrNull() ?: "https://placehold.co/300x300?text=No+Image"),
                contentDescription = product.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentScale = ContentScale.Crop
            )
            if (product.isSold) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Text("SOLD", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            if (isAuction) {
                Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Gavel, "Auction", modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("AUCTION", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (product.isFeatured) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, "Featured", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "FEATURED",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(product.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val priceText = if (isAuction) "Starts at ${formatPrice(product.auctionInfo!!.startingPrice)}" else formatPrice(product.price)
            Text(priceText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                Text( "by ${product.sellerDisplayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (product.sellerIsVerified) Icon(Icons.Default.Verified, "Verified Seller", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val displayTime = if (isAuction) product.auctionInfo?.endTime else product.expiresAt
                val timeRemaining = getTimeRemaining(displayTime)
                if (timeRemaining.isNotBlank() && !product.isSold) InfoChip(icon = Icons.Default.Timer, text = timeRemaining) else Spacer(Modifier.weight(1f))

            }
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatPrice(price: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(price)
}

private fun getTimeRemaining(endDate: Date?): String {
    if (endDate == null) return ""
    val diff = endDate.time - System.currentTimeMillis()
    if (diff <= 0) return "Ended"

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60

    return when {
        days > 0 -> "$days days left"
        hours > 0 -> "$hours hours left"
        minutes > 0 -> "$minutes mins left"
        else -> "Ending soon"
    }
}

@Composable
fun LoginPromptDialog(
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
    onSignup: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Store, contentDescription = null) },
        title = { Text("Welcome to YahdSell!") },
        text = {
            Column {
                Text("You can continue browsing as a guest, but you\'ll need to log in or sign up to save products, chat with sellers, or list your own items.")
                Spacer(Modifier.height(12.dp))
                Text("Ready to dive in or just window shopping?", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(onClick = onLogin) {
                Text("Login")
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(onClick = onDismiss) {
                    Text("Continue Browsing (Guest)")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSignup) {
                    Text("Sign Up")
                }
            }
        }
    )
}