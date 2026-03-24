package com.gari.yahdsell2.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.components.VideoPlayer
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.utils.requireAuthentication // ✅ This will work now
import com.gari.yahdsell2.viewmodel.ProductDetailViewModel
import com.google.firebase.auth.FirebaseAuth
import java.util.*

// ✅ Fixes "Experimental" API error for HorizontalPager
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProductDetailScreen(
    navController: NavController,
    productId: String,
    viewModel: ProductDetailViewModel = hiltViewModel()
) {
    val product by viewModel.product.collectAsState()
    val currentUser = FirebaseAuth.getInstance().currentUser

    var showSwapDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showBidDialog by remember { mutableStateOf(false) }
    var showVideoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(productId) {
        viewModel.loadProduct(productId)
    }

    // Helper to redirect guests to Home -> Login
    val navigateToHomeLogin = {
        navController.navigate("${Screen.Main.route}?showLogin=true") {
            popUpTo(Screen.Main.route) { inclusive = true }
        }
    }

    if (product == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val p = product!!
    val isOwner = currentUser?.uid == p.sellerId
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "Check out ${p.name} on YahdSell! Price: $${p.price}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                    }) {
                        Icon(Icons.Default.Share, "Share")
                    }
                    if (!isOwner) {
                        IconButton(onClick = {
                            requireAuthentication(
                                currentUser = currentUser,
                                onNavigateToLogin = navigateToHomeLogin
                            ) {
                                showReportDialog = true
                            }
                        }) {
                            Icon(Icons.Default.Flag, "Report")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!isOwner) {
                Surface(tonalElevation = 8.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                requireAuthentication(
                                    currentUser = currentUser,
                                    onNavigateToLogin = navigateToHomeLogin
                                ) {
                                    navController.navigate(
                                        Screen.PrivateChat.createRoute(p.sellerId, p.sellerDisplayName)
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Chat")
                        }

                        OutlinedButton(
                            onClick = {
                                requireAuthentication(
                                    currentUser = currentUser,
                                    onNavigateToLogin = navigateToHomeLogin
                                ) {
                                    showSwapDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Swap")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // --- Image/Video Carousel ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color.Black)
            ) {
                val pagerState = rememberPagerState(pageCount = { p.imageUrls.size + (if (p.videoUrl != null) 1 else 0) })
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    if (p.videoUrl != null && page == 0) {
                        // Video Thumbnail / Player Placeholder
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Show a thumbnail or icon. Clicking opens full video dialog
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = "Play Video",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clickable { showVideoDialog = true }
                                )
                                Text("Watch Video", color = Color.White)
                            }
                        }
                    } else {
                        val imageIndex = if (p.videoUrl != null) page - 1 else page
                        Image(
                            painter = rememberAsyncImagePainter(p.imageUrls.firstOrNull()?:"".getOrNull(imageIndex)),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                // Page Indicator
                if (pagerState.pageCount > 1) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        repeat(pagerState.pageCount) { iteration ->
                            val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                        }
                    }
                }
            }

            // --- Product Info ---
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$${p.price}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${p.viewCount} views", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = {
                            requireAuthentication(currentUser, navigateToHomeLogin) {
                                viewModel.toggleWishlist(p.id)
                            }
                        }) {
                            Icon(
                                if (p.isWishlisted) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Wishlist",
                                tint = if (p.isWishlisted) Color.Red else Color.Gray
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(p.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(onClick = {}, label = { Text(p.category) })
                    SuggestionChip(onClick = {}, label = { Text(p.condition) })
                    if (p.isAuction) SuggestionChip(onClick = {}, label = { Text("Auction") }, colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer))
                }

                Spacer(Modifier.height(16.dp))
                Text("Description", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(p.description, style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                // --- Seller Info ---
                Text("Seller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.UserProfile.createRoute(p.sellerId)) }
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(p.sellerProfilePicUrl ?: "https://placehold.co/100x100"),
                        contentDescription = "Seller",
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color.LightGray),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.sellerDisplayName, style = MaterialTheme.typography.titleMedium)
                            if (p.sellerIsVerified) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.Verified, contentDescription = "Verified", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Favorite, contentDescription = "Rating", tint = Color.Yellow, modifier = Modifier.size(14.dp))
                            // ✅ Now matches the updated Models.kt
                            Text(" ${p.sellerAverageRating} (${p.sellerRatingCount})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // --- Auction Section (If applicable) ---
                if (p.isAuction) {
                    Spacer(Modifier.height(24.dp))
                    Text("Auction Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val timeLeft = calculateTimeLeft(p.auctionInfo?.endTime)
                            Text("Time Left: $timeLeft", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))

                            // ✅ Fixes 'startingPrice' error by checking p.startingPrice
                            val currentPrice = p.auctionInfo?.currentBid ?: p.startingPrice
                            Text("Current Bid: $${currentPrice}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)

                            if (!isOwner) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        requireAuthentication(currentUser, navigateToHomeLogin) {
                                            showBidDialog = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Place Bid")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Dialogs ---

    // 1. Video Player Dialog
    if (showVideoDialog && p.videoUrl != null) {
        Dialog(onDismissRequest = { showVideoDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(8.dp)
            ) {
                // Using the shared VideoPlayer component here
                VideoPlayer(videoUri = Uri.parse(p.videoUrl))
            }
        }
    }

    // 2. Bid Dialog
    if (showBidDialog) {
        // ✅ Fixes 'startingPrice' error
        BidDialog(
            currentBid = p.auctionInfo?.currentBid ?: p.startingPrice ?: 0.0,
            startingPrice = p.startingPrice ?: 0.0,
            onDismiss = { showBidDialog = false },
            onConfirm = { amount ->
                viewModel.placeBid(p.id, amount) { success, msg ->
                    // Handle result toast or snackbar
                }
                showBidDialog = false
            }
        )
    }

    // 3. Swap Dialog (Placeholder)
    if (showSwapDialog) {
        AlertDialog(
            onDismissRequest = { showSwapDialog = false },
            title = { Text("Propose Swap") },
            text = { Text("Swap functionality to be implemented in SwapsScreen context.") },
            confirmButton = { TextButton(onClick = { showSwapDialog = false }) { Text("OK") } }
        )
    }
}

// Helper to calculate time left
fun calculateTimeLeft(endTime: Date?): String {
    if (endTime == null) return "Unknown"
    val diff = endTime.time - System.currentTimeMillis()
    if (diff <= 0) return "Ended"
    val days = diff / (1000 * 60 * 60 * 24)
    val hours = (diff / (1000 * 60 * 60)) % 24
    val minutes = (diff / (1000 * 60)) % 60
    return "${days}d ${hours}h ${minutes}m"
}

@Composable
fun BidDialog(
    currentBid: Double,
    startingPrice: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var bidAmount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val minimumBid = if (currentBid > 0) currentBid else startingPrice

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place a Bid") },
        text = {
            Column {
                Text("Minimum bid: $${"%.2f".format(minimumBid)}")
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = bidAmount,
                    onValueChange = { bidAmount = it; error = null },
                    label = { Text("Your Bid") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = error != null,
                    supportingText = { if (error != null) Text(error!!) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = bidAmount.toDoubleOrNull()
                if (amount == null || amount <= minimumBid) {
                    error = "Bid must be higher than current minimum."
                } else {
                    onConfirm(amount)
                }
            }) {
                Text("Place Bid")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}