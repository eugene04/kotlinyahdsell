package com.gari.yahdsell2.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.components.EmptyState
import com.gari.yahdsell2.model.ProductSwap
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.SwapViewModel
import com.google.firebase.auth.FirebaseAuth
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapsScreen(
    navController: NavController,
    viewModel: SwapViewModel = hiltViewModel()
) {
    val auth = FirebaseAuth.getInstance()

    // ✅ AUTH GUARD: Redirect to Home with Login Prompt if not authenticated
    if (auth.currentUser == null) {
        LaunchedEffect(Unit) {
            navController.navigate("${Screen.Main.route}?showLogin=true") {
                popUpTo(Screen.Main.route) { inclusive = true }
            }
        }
        return // Stop rendering
    }

    // ✅ FIX: Unresolved reference 'swaps' -> Use viewModel.swaps
    val swaps by viewModel.swaps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val currentUserId = auth.currentUser!!.uid

    val responseToSwap: (String, String) -> Unit = { swapId, action ->
        // ✅ FIX: Unresolved reference: respondToSwap -> Use viewModel.respondToSwap
        viewModel.respondToSwap(swapId, action) { success, message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            if (success) {
                viewModel.clearError()
            }
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Product Swaps") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (error != null) {
                EmptyState(message = error ?: "Failed to load swaps.") { viewModel.clearError() }
            } else if (swaps.isEmpty()) {
                EmptyState(message = "You have no active or past swap proposals.") {}
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(swaps) { swap ->
                        SwapCard(
                            swap = swap,
                            currentUserId = currentUserId,
                            onAccept = { responseToSwap(swap.id, "accepted") },
                            onReject = { responseToSwap(swap.id, "rejected") },
                            onProposingProductClick = {
                                swap.proposingProductId.let {
                                    navController.navigate(Screen.ProductDetail.createRoute(it))
                                }
                            },
                            onTargetProductClick = {
                                swap.targetProductId.let {
                                    navController.navigate(Screen.ProductDetail.createRoute(it))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun SwapCard(
    swap: ProductSwap,
    currentUserId: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onProposingProductClick: () -> Unit,
    onTargetProductClick: () -> Unit
) {
    val isIncoming = swap.targetUserId == currentUserId
    val isPending = swap.status == "pending"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title and Status
            Text(
                text = if (isIncoming) "Incoming Swap Proposal" else "Your Swap Proposal",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            // Swap Details (Product Thumbnails)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Product Proposing the Swap
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isIncoming) "Proposing Item" else "Your Item",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    ProductThumbnail(
                        name = swap.proposingProductName,
                        imageUrl = swap.proposingProductImageUrl,
                        onClick = onProposingProductClick
                    )
                }

                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = "Swap",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Product Targeted for Swap
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isIncoming) "Your Item" else "Target Item",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    ProductThumbnail(
                        name = swap.targetProductName,
                        imageUrl = swap.targetProductImageUrl,
                        onClick = onTargetProductClick
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Cash Top-up
            swap.cashTopUp?.let { cash ->
                if (cash > 0) {
                    Text(
                        text = "Cash Top-up: ${formatPrice(cash)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Status and Actions
            when {
                !isPending -> {
                    StatusChip(
                        text = "Status: ${swap.status.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }}",
                        color = if (swap.status == "accepted") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                isIncoming && isPending -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = onReject) { Text("Reject") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onAccept) { Text("Accept") }
                    }
                }
                !isIncoming && isPending -> {
                    StatusChip("Status: Pending Your Review", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ProductThumbnail(name: String?, imageUrl: String?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(120.dp).clickable(onClick = onClick)
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = imageUrl ?: "https://placehold.co/200x200?text=No+Image"
            ),
            contentDescription = name,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = name ?: "Unknown Item",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun formatPrice(price: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(price)
}