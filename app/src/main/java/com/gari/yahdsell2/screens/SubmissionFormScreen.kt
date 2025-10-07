package com.gari.yahdsell2.screens

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.navigation.Screen
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import kotlinx.coroutines.launch

private enum class VerifyAddressState { IDLE, VERIFYING, SUCCESS, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun SubmissionFormScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    productToRelistJson: String?,
    productIdToEdit: String?
) {
    val context = LocalContext.current
    val isEditMode = productIdToEdit != null
    val isRelistMode = productToRelistJson != null

    var productName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Select Category...") }
    var condition by remember { mutableStateOf("Select Condition...") }
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showConditionDialog by remember { mutableStateOf(false) }
    var userLocation by remember { mutableStateOf<Location?>(null) }

    var listingType by remember { mutableStateOf("Fixed Price") }
    var auctionDurationDays by remember { mutableStateOf(3) }
    var showAuctionDurationDialog by remember { mutableStateOf(false) }

    var locationOption by remember { mutableStateOf("current") } // "current" or "address"
    var itemAddress by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var verifiedAddressLocation by remember { mutableStateOf<Location?>(null) }
    var verifyAddressState by remember { mutableStateOf(VerifyAddressState.IDLE) }


    val productToEdit by viewModel.productToEdit.collectAsState()
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val isGeneratingSuggestions by viewModel.isGeneratingSuggestions.collectAsState()

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                userLocation = location
                if (location == null) {
                    Toast.makeText(context, "Could not get location. Please enable GPS.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(context, "Location permission is required to list an item.", Toast.LENGTH_LONG).show()
        }
    }

    // A helper function to populate form fields from a Product object
    val populateFields: (Product) -> Unit = { product ->
        productName = product.name
        description = product.description
        price = product.price.toString()
        category = product.category
        condition = product.condition
        imageUris = product.imageUrls.map { Uri.parse(it) }
        videoUri = product.videoUrl?.let { Uri.parse(it) }
        listingType = if (product.auctionInfo != null) "Auction" else "Fixed Price"
        itemAddress = product.itemAddress ?: ""
        if (product.itemAddress?.isNotBlank() == true) {
            locationOption = "address"
            verifyAddressState = VerifyAddressState.SUCCESS // Assume it's valid if pre-filled
        }
    }

    // Handle different entry modes
    LaunchedEffect(key1 = productIdToEdit, key2 = productToRelistJson) {
        if (isEditMode) {
            viewModel.getProductForEditing(productIdToEdit!!)
        } else if (isRelistMode) {
            try {
                val product = Gson().fromJson(Uri.decode(productToRelistJson), Product::class.java)
                populateFields(product)
                Toast.makeText(context, "Details filled from previous listing.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not load item details for re-listing.", Toast.LENGTH_LONG).show()
            }
        }
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // Observe productToEdit for populating the form
    LaunchedEffect(productToEdit) {
        if (isEditMode && productToEdit != null) {
            populateFields(productToEdit!!)
        }
    }

    // When the address text changes, reset the verification status
    LaunchedEffect(itemAddress) {
        verifyAddressState = VerifyAddressState.IDLE
        verifiedAddressLocation = null
    }


    // Clean up when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearProductToEdit()
        }
    }


    LaunchedEffect(aiSuggestions) {
        aiSuggestions?.let { suggestions ->
            description = suggestions.description
            if (suggestions.category.isNotBlank() && suggestions.category != "Other") {
                category = suggestions.category
            }
            viewModel.clearAiSuggestions()
        }
    }

    val categoryOptions = listOf("Electronics", "Clothing & Apparel", "Home & Garden", "Furniture", "Vehicles", "Books, Movies & Music", "Collectibles & Art", "Sports & Outdoors", "Toys & Hobbies", "Baby & Kids", "Health & Beauty", "Other")
    val conditionOptions = listOf("New", "Used - Like New", "Used - Good", "Used - Fair")
    val auctionDurationOptions = listOf(1, 3, 5, 7)

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        imageUris = (imageUris + uris).take(5)
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Item" else "List an Item") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Listing Type Selection (Disabled in edit mode)
            Card(elevation = CardDefaults.cardElevation(4.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Listing Type", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    TabRow(
                        selectedTabIndex = if (listingType == "Fixed Price") 0 else 1,
                    ) {
                        Tab(
                            selected = listingType == "Fixed Price",
                            onClick = { if (!isEditMode) listingType = "Fixed Price" },
                            text = { Text("Fixed Price") },
                            enabled = !isEditMode
                        )
                        Tab(
                            selected = listingType == "Auction",
                            onClick = { if (!isEditMode) listingType = "Auction" },
                            text = { Text("Auction") },
                            enabled = !isEditMode
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Product Details
            Card(elevation = CardDefaults.cardElevation(4.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Product Details", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = productName, onValueChange = { productName = it }, label = { Text("Product Name") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) }, modifier = Modifier.fillMaxWidth())

                    Button(
                        onClick = { viewModel.getAiSuggestions(productName) },
                        enabled = productName.isNotBlank() && !isGeneratingSuggestions,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        if (isGeneratingSuggestions) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "AI Suggestion")
                            Spacer(Modifier.width(8.dp))
                            Text("Generate Description with AI")
                        }
                    }

                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, leadingIcon = { Icon(Icons.Default.Description, null) }, modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 4)
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text(if (listingType == "Auction") "Starting Bid" else "Price") },
                        leadingIcon = { Icon(Icons.Default.MonetizationOn, null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (listingType == "Auction") {
                        Spacer(Modifier.height(8.dp))
                        Text("Auction Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Button(onClick = { showAuctionDurationDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = !isEditMode) {
                            Icon(Icons.Default.Timer, null); Spacer(Modifier.width(8.dp)); Text("Duration: $auctionDurationDays days")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Categorization
            Card(elevation = CardDefaults.cardElevation(4.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Categorization", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { showCategoryDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Category, null); Spacer(Modifier.width(8.dp)); Text(category)
                    }
                    Button(onClick = { showConditionDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text(condition)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Location
            Card(elevation = CardDefaults.cardElevation(4.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Item Location", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = locationOption == "current", onClick = { locationOption = "current" })
                        Text("Use my current location")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = locationOption == "address", onClick = { locationOption = "address" })
                        Text("Enter a specific address")
                    }
                    if (locationOption == "address") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = itemAddress,
                                onValueChange = { itemAddress = it },
                                label = { Text("Item Address") },
                                modifier = Modifier.weight(1f),
                                leadingIcon = { Icon(Icons.Default.LocationOn, null) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        verifyAddressState = VerifyAddressState.VERIFYING
                                        val locationResult = viewModel.geocodeAddress(itemAddress)
                                        if (locationResult != null) {
                                            verifiedAddressLocation = locationResult
                                            verifyAddressState = VerifyAddressState.SUCCESS
                                        } else {
                                            verifyAddressState = VerifyAddressState.ERROR
                                        }
                                    }
                                },
                                enabled = itemAddress.isNotBlank() && verifyAddressState != VerifyAddressState.VERIFYING
                            ) {
                                Text("Verify")
                            }
                        }
                        // Address verification status message
                        when (verifyAddressState) {
                            VerifyAddressState.VERIFYING -> Text("Verifying...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            VerifyAddressState.SUCCESS -> Text("Address Verified!", color = Color(0xFF008080))
                            VerifyAddressState.ERROR -> Text("Could not find address. Please be more specific or check network.", color = MaterialTheme.colorScheme.error)
                            else -> {}
                        }
                    }
                }
            }


            Spacer(Modifier.height(16.dp))

            // Media
            Card(elevation = CardDefaults.cardElevation(4.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Media", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
                    Button(onClick = { imagePicker.launch("image/*") }) {
                        Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text("Choose Images (${imageUris.size}/5)")
                    }
                    if (imageUris.isNotEmpty()) {
                        LazyRow(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(imageUris) { index, uri ->
                                Box {
                                    Image(painter = rememberAsyncImagePainter(uri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)))
                                    IconButton(onClick = { imageUris = imageUris.toMutableList().also { it.removeAt(index) } }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                                        Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    Button(onClick = { videoPicker.launch("video/*") }) {
                        Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(8.dp)); Text(if (videoUri != null) "Change Video" else "Choose Video (Optional)")
                    }

                    videoUri?.let { uri ->
                        VideoPlayer(videoUri = uri)
                        TextButton(onClick = { videoUri = null }) { Text("Remove Video") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Location Status and Submit Button
            if (locationOption == "current" && userLocation == null && !isEditMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Acquiring location...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry Location")
                    }
                }
            }

            if (isSubmitting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            val isLocationReady = (locationOption == "current" && userLocation != null) || (locationOption == "address" && verifyAddressState == VerifyAddressState.SUCCESS)
            Button(
                onClick = {
                    val finalLocation = when {
                        locationOption == "address" && verifiedAddressLocation != null -> verifiedAddressLocation
                        locationOption == "current" && userLocation != null -> userLocation
                        else -> null
                    }
                    if (finalLocation == null) {
                        Toast.makeText(context, "Please ensure a valid location is set before submitting.", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    isSubmitting = true
                    if (isEditMode) {
                        viewModel.updateProduct(
                            productId = productIdToEdit!!,
                            name = productName.trim(),
                            description = description.trim(),
                            price = price.toDoubleOrNull() ?: 0.0,
                            category = category,
                            condition = condition,
                            imageUris = imageUris,
                            videoUri = videoUri,
                            itemAddress = itemAddress.takeIf { locationOption == "address" },
                            location = finalLocation
                        ) { success, message ->
                            isSubmitting = false
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            if (success) {
                                navController.popBackStack()
                            }
                        }
                    } else {
                        // Create new product
                        viewModel.submitProduct(
                            name = productName.trim(),
                            description = description.trim(),
                            price = price.toDoubleOrNull() ?: 0.0,
                            category = category,
                            condition = condition,
                            imageUris = imageUris,
                            videoUri = videoUri,
                            isAuction = listingType == "Auction",
                            auctionDurationDays = auctionDurationDays,
                            sellerLocation = finalLocation,
                            itemAddress = itemAddress.takeIf { locationOption == "address" },
                            onSuccess = { newProductId, submittedData ->
                                isSubmitting = false
                                val productJson = Uri.encode(Gson().toJson(submittedData))
                                navController.navigate(Screen.Payment.createRoute(newProductId, productJson)) {
                                    popUpTo(Screen.Main.route)
                                }
                            },
                            onError = { errorMsg ->
                                isSubmitting = false
                                Toast.makeText(context, "Error: $errorMsg", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                },
                enabled = !isSubmitting && productName.isNotBlank() && price.isNotBlank() && imageUris.isNotEmpty() && category != "Select Category..." && condition != "Select Condition..." && (isEditMode || isLocationReady),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (isEditMode) {
                    Text("Save Changes")
                } else {
                    Text("Proceed to Payment")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, null)
                }
            }
        }

        if (showCategoryDialog) {
            SelectionDialog(title = "Select Category", options = categoryOptions, onDismiss = { showCategoryDialog = false }, onSelect = { category = it; showCategoryDialog = false })
        }
        if (showConditionDialog) {
            SelectionDialog(title = "Select Condition", options = conditionOptions, onDismiss = { showConditionDialog = false }, onSelect = { condition = it; showConditionDialog = false })
        }
        if (showAuctionDurationDialog) {
            SelectionDialog(title = "Select Auction Duration", options = auctionDurationOptions.map { "$it days" }, onDismiss = { showAuctionDurationDialog = false }, onSelect = {
                auctionDurationDays = it.split(" ")[0].toInt()
                showAuctionDurationDialog = false
            })
        }
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun VideoPlayer(videoUri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = exoPlayer
                useController = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        },
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun SelectionDialog(title: String, options: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    TextButton(onClick = { onSelect(option) }, modifier = Modifier.fillMaxWidth()) {
                        Text(option)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

