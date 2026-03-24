package com.gari.yahdsell2.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.model.ProductAnalytics
import com.gari.yahdsell2.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val userProducts by viewModel.userProducts.collectAsState()
    // Using isLoadingProfile since it tracks the overall loading state of user products
    val isLoading by viewModel.isLoadingProfile.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Listings Analytics") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (userProducts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "You don't have any listings yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ✅ FIX: Explicitly name the variable "product ->"
                items(userProducts) { product ->

                    // Note: Since offerCount and wishlistCount require separate queries in Firestore,
                    // we map the basic viewCount natively, and you can later tie this into
                    // a specific ViewModel function to fetch the rest of the analytics if needed.
                    val analytics = ProductAnalytics(
                        viewCount = product.viewCount,
                        offerCount = 0, // Placeholder
                        wishlistCount = 0 // Placeholder
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                // ✅ FIX: Grab the first image from the list, or empty string if null
                                painter = rememberAsyncImagePainter(model = product.imageUrls.firstOrNull()?:"".firstOrNull() ?: ""),
                                contentDescription = product.name,
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    // ✅ FIX: Use product.name instead of product.productName
                                    text = product.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatChip(
                                        icon = Icons.Default.Visibility,
                                        count = analytics.viewCount,
                                        label = "Views"
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    StatChip(
                                        icon = Icons.Default.LocalOffer,
                                        count = analytics.offerCount,
                                        label = "Offers"
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                StatChip(
                                    icon = Icons.Default.Favorite,
                                    count = analytics.wishlistCount,
                                    label = "Saves"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatChip(icon: ImageVector, count: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}