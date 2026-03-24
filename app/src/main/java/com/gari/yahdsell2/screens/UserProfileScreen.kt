package com.gari.yahdsell2.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.model.UserProfile
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.ProfileViewModel
import com.google.gson.Gson
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    navController: NavController,
    userId: String,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val profileUser by viewModel.profileUser.collectAsState()
    val userProducts by viewModel.userProducts.collectAsState()
    val isLoading by viewModel.isLoadingProfile.collectAsState()
    val currentUser = viewModel.currentUser
    val isOwnProfile = currentUser?.uid == userId
    val context = LocalContext.current

    LaunchedEffect(userId) {
        viewModel.fetchUserProfile(userId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isOwnProfile) "My Profile" else profileUser?.displayName ?: "Profile") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isOwnProfile) {
                        IconButton(onClick = { navController.navigate(Screen.EditProfile.route) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Profile")
                        }
                        IconButton(onClick = { navController.navigate(Screen.SavedSearches.route) }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "Saved Searches")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            profileUser?.let { user ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 16.dp,
                        bottom = 16.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header Section (Spans full width)
                    item(span = { GridItemSpan(2) }) {
                        UserProfileHeader(
                            user = user,
                            isOwnProfile = isOwnProfile,
                            onChatClick = {
                                navController.navigate(Screen.PrivateChat.createRoute(user.uid, user.displayName))
                            },
                            onReviewClick = {
                                navController.navigate(Screen.SellerReviews.createRoute(user.uid, user.displayName))
                            }
                        )
                    }

                    // Listings Section Title
                    item(span = { GridItemSpan(2) }) {
                        Text(
                            text = if (isOwnProfile) "My Listings" else "${user.displayName}'s Listings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    // Products Grid
                    items(userProducts) { product ->
                        UserProductGridItem(
                            product = product,
                            isOwnProfile = isOwnProfile,
                            onProductClick = {
                                navController.navigate(Screen.ProductDetail.createRoute(product.id))
                            },
                            onMarkAsSold = {
                                viewModel.markAsSold(product.id)
                            },
                            onRelist = {
                                val productJson = Uri.encode(Gson().toJson(product))
                                navController.navigate(Screen.Submit.createRoute(productToRelistJson = productJson))
                            },
                            onPromote = {
                                // Navigate to Payment Screen instead of trying to present payment sheet here
                                val productJson = Uri.encode(Gson().toJson(product))
                                navController.navigate(Screen.Payment.createRoute(product.id, productJson))
                            }
                        )
                    }

                    if (userProducts.isEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No listings found.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } ?: run {
                // User not found state
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("User not found.")
                }
            }
        }
    }
}

@Composable
fun UserProfileHeader(
    user: UserProfile,
    isOwnProfile: Boolean,
    onChatClick: () -> Unit,
    onReviewClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Profile Image
        Image(
            painter = rememberAsyncImagePainter(model = user.profilePicUrl ?: "https://placehold.co/100x100"),
            contentDescription = "Profile Picture",
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(16.dp))

        // Name & Verification
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (user.isVerified) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Verified",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Bio
        if (user.bio.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = user.bio,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        // Stats Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ProfileStat(count = user.followerCount.toString(), label = "Followers")
            ProfileStat(count = user.followingCount.toString(), label = "Following")
            ProfileStat(
                count = if (user.ratingCount > 0) String.format("%.1f", user.averageRating) else "-",
                label = "Rating (${user.ratingCount})"
            )
        }

        Spacer(Modifier.height(24.dp))

        // Action Buttons (Chat, Reviews) - Only if not own profile
        if (!isOwnProfile) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onChatClick) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Chat")
                }
                OutlinedButton(onClick = onReviewClick) {
                    Icon(Icons.Default.Star, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reviews")
                }
            }
        }
    }
}

@Composable
fun ProfileStat(count: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun UserProductGridItem(
    product: Product,
    isOwnProfile: Boolean,
    onProductClick: () -> Unit,
    onMarkAsSold: () -> Unit,
    onRelist: () -> Unit,
    onPromote: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onProductClick),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box {
            // Product Image
            Image(
                painter = rememberAsyncImagePainter(model = product.imageUrls.firstOrNull()?:"".firstOrNull() ?: ""),
                contentDescription = product.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(Color.Gray),
                contentScale = ContentScale.Crop
            )

            // Status Badges (Sold, Promoted)
            Column(modifier = Modifier.padding(8.dp)) {
                if (product.isSold) {
                    Badge(containerColor = Color.Red, contentColor = Color.White) {
                        Text("SOLD")
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (product.isPromoted) {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = Color.White) {
                        Text("PROMOTED")
                    }
                }
            }

            // More Options Menu (Only for owner)
            if (isOwnProfile) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color.White,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                .padding(4.dp)
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (!product.isSold) {
                            DropdownMenuItem(
                                text = { Text("Mark as Sold") },
                                onClick = {
                                    onMarkAsSold()
                                    showMenu = false
                                }
                            )
                        }
                        // Allow relisting if sold OR if expired
                        if (product.isSold || (product.expiresAt != null && product.expiresAt.before(Date()))) {
                            DropdownMenuItem(
                                text = { Text("Relist") },
                                onClick = {
                                    onRelist()
                                    showMenu = false
                                }
                            )
                        }
                        if (!product.isPromoted && !product.isSold) {
                            DropdownMenuItem(
                                text = { Text("Promote") },
                                onClick = {
                                    onPromote()
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Product Details
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = product.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$${product.price}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = product.condition,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}