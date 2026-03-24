package com.gari.yahdsell2.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.components.VideoPlayer
import com.gari.yahdsell2.components.ModalSelector
import com.gari.yahdsell2.viewmodel.SubmissionViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmissionFormScreen(
    navController: NavController,
    productIdToEdit: String? = null,
    productToRelistJson: String? = null,
    viewModel: SubmissionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val productToEdit by viewModel.productToEdit.collectAsState()
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val isGeneratingSuggestions by viewModel.isGeneratingSuggestions.collectAsState()

    // --- State Variables ---
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Electronics") }
    var condition by remember { mutableStateOf("Used - Good") }
    var address by remember { mutableStateOf("") } // ✅ Fixed: Defined here

    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }

    // Location State
    var location by remember { mutableStateOf<Location?>(null) }
    var isFetchingLocation by remember { mutableStateOf(false) }

    // Auction State
    var isAuction by remember { mutableStateOf(false) }
    var auctionDuration by remember { mutableStateOf("7") }

    // Dropdown States
    var showCategoryModal by remember { mutableStateOf(false) }
    var showConditionModal by remember { mutableStateOf(false) }

    // --- Load Data ---
    LaunchedEffect(productIdToEdit) {
        if (productIdToEdit != null) {
            viewModel.getProductForEditing(productIdToEdit)
        }
    }

    LaunchedEffect(productToEdit) {
        productToEdit?.let {
            name = it.name
            description = it.description
            price = it.price.toString()
            category = it.category
            condition = it.condition
            // Note: For editing, handling existing images vs new URIs requires extra logic.
            // Simplified here to assume new uploads for this snippet.
        }
    }

    // AI Auto-Fill Effect
    LaunchedEffect(aiSuggestions) {
        aiSuggestions?.let {
            if (it.description.isNotBlank()) description = it.description
            if (it.category.isNotBlank()) category = it.category
        }
    }

    // --- Media Pickers ---
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        imageUris = uris
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        videoUri = uri
    }

    // --- Location Logic ---
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val getCurrentLocation = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            isFetchingLocation = true
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { loc ->
                    location = loc
                    isFetchingLocation = false
                }
                .addOnFailureListener {
                    isFetchingLocation = false
                    Toast.makeText(context, "Failed to get location", Toast.LENGTH_SHORT).show()
                }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            getCurrentLocation()
        } else {
            Toast.makeText(context, "Location permission is required to sell items.", Toast.LENGTH_LONG).show()
        }
    }

    // Attempt to get location on mount
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocation()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (productIdToEdit != null) "Edit Item" else "Sell Item") })
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // AI Suggestion Button
                Button(
                    onClick = {
                        if (name.isNotBlank()) viewModel.generateSuggestions(name)
                        else Toast.makeText(context, "Enter a title first", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !isGeneratingSuggestions,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    if (isGeneratingSuggestions) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onTertiary)
                    } else {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Auto-Fill Details with AI")
                    }
                }

                // 1. Title
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. Images
                Text("Photos", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(imageUris) { uri ->
                        Box(modifier = Modifier.size(100.dp)) {
                            Image(
                                painter = rememberAsyncImagePainter(uri),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    item {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .background(Color.LightGray, RoundedCornerShape(8.dp))
                                .clickable { imagePickerLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, "Add Photo")
                        }
                    }
                }

                // 3. Price
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price ($)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // 4. Category
                OutlinedTextField(
                    value = category,
                    onValueChange = {},
                    label = { Text("Category") },
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.clickable { showCategoryModal = true }) },
                    modifier = Modifier.fillMaxWidth().clickable { showCategoryModal = true },
                    enabled = false,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // 5. Condition
                OutlinedTextField(
                    value = condition,
                    onValueChange = {},
                    label = { Text("Condition") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().clickable { showConditionModal = true },
                    enabled = false,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // 6. Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                // 7. Address (✅ Fixed: Added Field)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Item Address / Location") },
                    placeholder = { Text("e.g. 123 Main St, New York") },
                    modifier = Modifier.fillMaxWidth()
                )

                // 8. Video
                Text("Video (Optional, max 30s)", style = MaterialTheme.typography.titleSmall)
                if (videoUri != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        VideoPlayer(videoUri = videoUri!!)
                        IconButton(
                            onClick = { videoUri = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        ) {
                            Icon(Icons.Default.Close, "Remove Video", tint = Color.White)
                        }
                    }
                } else {
                    Button(onClick = { videoPickerLauncher.launch("video/*") }) {
                        Icon(Icons.Default.VideoFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Select Video")
                    }
                }

                // 9. Auction Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("List as Auction?", modifier = Modifier.weight(1f))
                    Switch(checked = isAuction, onCheckedChange = { isAuction = it })
                }

                if (isAuction) {
                    OutlinedTextField(
                        value = auctionDuration,
                        onValueChange = { auctionDuration = it },
                        label = { Text("Duration (Days)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Submit Button
                Button(
                    onClick = {
                        if (name.isBlank() || price.isBlank()) {
                            Toast.makeText(context, "Please fill in Title and Price", Toast.LENGTH_SHORT).show()
                        } else if (location == null) {
                            Toast.makeText(context, "Fetching location... try again in a moment.", Toast.LENGTH_SHORT).show()
                            getCurrentLocation()
                        } else {
                            viewModel.submitProduct(
                                name = name,
                                description = description,
                                price = price.toDoubleOrNull() ?: 0.0,
                                category = category,
                                condition = condition,
                                imageUris = imageUris,
                                videoUri = videoUri,
                                sellerLocation = location!!, // Safe assertion due to check above
                                itemAddress = address,       // ✅ Fixed: Passing the address variable
                                isAuction = isAuction,
                                auctionDurationDays = auctionDuration.toIntOrNull() ?: 7,
                                existingProductId = productIdToEdit,
                                onSuccess = { id ->
                                    Toast.makeText(context, "Success!", Toast.LENGTH_SHORT).show()
                                    // Navigate to Payment or Home
                                    navController.popBackStack()
                                },
                                onFailure = { msg ->
                                    Toast.makeText(context, "Error: $msg", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !isFetchingLocation
                ) {
                    if (isFetchingLocation) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Getting Location...")
                    } else {
                        Text(if (productIdToEdit != null) "Update Listing" else "Post Listing")
                    }
                }

                Spacer(Modifier.height(32.dp))
            }

            // Modals
            if (showCategoryModal) {
                ModalSelector(
                    title = "Select Category",
                    options = listOf("Electronics", "Clothing", "Home", "Vehicles", "Sports", "Toys", "Other"),
                    selectedOption = category,
                    onSelect = { category = it },
                    onDismiss = { showCategoryModal = false }
                )
            }

            if (showConditionModal) {
                ModalSelector(
                    title = "Select Condition",
                    options = listOf("New", "Used - Like New", "Used - Good", "Used - Fair"),
                    selectedOption = condition,
                    onSelect = { condition = it },
                    onDismiss = { showConditionModal = false }
                )
            }
        }
    }
}