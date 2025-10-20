package com.gari.yahdsell2.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke // ✅ ADDED
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.UserState
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.model.UserProfile
import com.gari.yahdsell2.navigation.Screen
import com.google.gson.Gson
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    userId: String?
) {
    val profileUser by viewModel.profileUser.collectAsState()
    val userProducts by viewModel.userProducts.collectAsState()
    val userState by viewModel.userState.collectAsState()
    val isFollowing by viewModel.isFollowing.collectAsState()
    val isLoadingFollow by viewModel.isLoadingFollow.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    val context = LocalContext.current

    val isOwnProfile = (userState as? UserState.Authenticated)?.user?.uid == userId
    var activeTab by remember(isOwnProfile) {
        mutableStateOf("Active")
    }

    var productToPromote by remember { mutableStateOf<Product?>(null) }


    LaunchedEffect(userId, isOwnProfile) {
        if (userId != null) {
            viewModel.fetchUserProfileAndProducts(userId)
            if (isOwnProfile) {
                viewModel.checkAdminStatus()
            }
        }
    }

    productToPromote?.let { product ->
        PromotionDialog(
            product = product,
            viewModel = viewModel,
            onDismiss = { productToPromote = null }
        )
    }

    val filteredProducts = remember(userProducts, activeTab) {
        when (activeTab) {
            "Sold" -> userProducts.filter { it.isSold }
            "Expired" -> userProducts.filter { !it.isSold && (it.expiresAt?.before(Date()) == true) }
            else -> userProducts.filter { !it.isSold && (it.expiresAt?.after(Date()) != false) }
        }
    }

    if (userId == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("User ID not provided.") }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profileUser?.displayName ?: "Profile") },
                navigationIcon = {
                    val currentRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentRoute != Screen.UserProfile.route) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (profileUser == null) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            profileUser?.let { user ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.padding(paddingValues),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        UserProfileHeader(user = user, isOwnProfile = isOwnProfile)
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ProfileActions(
                            isOwnProfile = isOwnProfile,
                            isFollowing = isFollowing,
                            isLoadingFollow = isLoadingFollow,
                            onEdit = { navController.navigate(Screen.EditProfile.route) },
                            onFollowToggle = { viewModel.toggleFollow(user.uid) },
                            onMessage = {
                                navController.navigate(
                                    Screen.PrivateChat.createRoute(user.uid, user.displayName)
                                )
                            }
                        )
                    }

                    if (isOwnProfile) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            DashboardSection(navController = navController, isAdmin = isAdmin)
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ProductTabs(
                            activeTab = activeTab,
                            onTabSelected = { activeTab = it },
                            isOwnProfile = isOwnProfile
                        )
                    }

                    if (filteredProducts.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
                                Text("No ${activeTab.lowercase()} listings yet.")
                            }
                        }
                    } else {
                        items(filteredProducts) { product ->
                            UserProfileProductCard(
                                product = product,
                                isOwnListing = isOwnProfile,
                                onCardClick = {
                                    navController.navigate(Screen.ProductDetail.createRoute(product.id))
                                },
                                onMarkAsSold = {
                                    viewModel.markProductAsSold(product.id) { _, message ->
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRelist = {
                                    if (activeTab == "Sold") { // Relisting from "Sold" tab
                                        viewModel.relistProduct(product.id) { _, message ->
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    } else { // Relisting from "Expired" tab
                                        val productJson = Uri.encode(Gson().toJson(product))
                                        navController.navigate(Screen.Submit.createRoute(productJson))
                                    }
                                },
                                onPromote = {
                                    productToPromote = product
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserProfileHeader(user: UserProfile, isOwnProfile: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = rememberAsyncImagePainter(model = user.profilePicUrl ?: "https://placehold.co/200x200"),
            contentDescription = "Profile Picture",
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(user.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (user.isVerified) {
                Icon(
                    Icons.Default.Verified,
                    contentDescription = "Verified",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp).padding(start = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Joined: ${user.createdAt?.let { SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(it) } ?: "N/A"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (user.bio.isNotBlank()) {
            Text(
                text = user.bio,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else if (isOwnProfile) {
            Text(
                text = "You haven't added a bio yet. Tap 'Edit Profile' to introduce yourself.",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = user.followerCount.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = "Followers", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = user.followingCount.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = "Following", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ProfileActions(
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    isLoadingFollow: Boolean,
    onEdit: () -> Unit,
    onFollowToggle: () -> Unit,
    onMessage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isOwnProfile) {
            Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Profile")
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Edit Profile")
            }
        } else {
            val followButtonColors = if (isFollowing) ButtonDefaults.outlinedButtonColors() else ButtonDefaults.buttonColors()

            Button(
                onClick = onFollowToggle,
                modifier = Modifier.weight(1f),
                enabled = !isLoadingFollow,
                colors = followButtonColors
            ) {
                if (isLoadingFollow) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(if (isFollowing) Icons.Default.Check else Icons.Default.PersonAdd, contentDescription = "Follow")
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (isFollowing) "Following" else "Follow")
                }
            }
            OutlinedButton(onClick = onMessage, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Message")
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Message")
            }
        }
    }
}

@Composable
fun DashboardSection(navController: NavController, isAdmin: Boolean) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("My Dashboard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

        DashboardItem(
            icon = Icons.Default.SwapHoriz,
            title = "Manage Swaps",
            onClick = { navController.navigate(Screen.Swaps.route) }
        )
        HorizontalDivider()

        DashboardItem(
            icon = Icons.Default.Favorite,
            title = "My Wishlist",
            onClick = { navController.navigate(Screen.Wishlist.route) }
        )
        HorizontalDivider()

        DashboardItem(
            icon = Icons.Default.Bookmark,
            title = "My Saved Searches",
            onClick = { navController.navigate(Screen.SavedSearches.route) }
        )
        HorizontalDivider()

        DashboardItem(
            icon = Icons.Default.Analytics,
            title = "Listing Analytics",
            onClick = { navController.navigate(Screen.Analytics.route) }
        )

        if (isAdmin) {
            HorizontalDivider()
            DashboardItem(
                icon = Icons.Default.AdminPanelSettings,
                title = "Admin Panel",
                onClick = { navController.navigate(Screen.Admin.route) }
            )
        }
    }
}

@Composable
fun DashboardItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
fun ProductTabs(
    activeTab: String,
    onTabSelected: (String) -> Unit,
    isOwnProfile: Boolean
) {
    val tabs = if (isOwnProfile) listOf("Active", "Sold", "Expired") else listOf("Listings")
    if (tabs.size > 1) {
        TabRow(
            selectedTabIndex = tabs.indexOf(activeTab)
        ) {
            tabs.forEach { title ->
                Tab(
                    selected = activeTab == title,
                    onClick = { onTabSelected(title) },
                    text = { Text(title) }
                )
            }
        }
    }
}

@Composable
fun UserProfileProductCard(
    product: Product,
    isOwnListing: Boolean,
    onCardClick: () -> Unit,
    onMarkAsSold: () -> Unit,
    onRelist: () -> Unit,
    onPromote: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onCardClick),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box {
            Column {
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
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$${"%.2f".format(product.price)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (isOwnListing) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (product.isSold) {
                        Button(
                            onClick = onRelist,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Relist", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (product.expiresAt?.after(Date()) == true) { // Active
                        if (product.auctionInfo == null) {
                            OutlinedButton(
                                onClick = onMarkAsSold,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text("Mark Sold", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Button(
                            onClick = onPromote,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Icon(Icons.Default.RocketLaunch, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(4.dp))
                            Text("Promote", style = MaterialTheme.typography.labelSmall)
                        }
                    } else { // Expired
                        Button(
                            onClick = onRelist,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Re-list", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PromotionDialog(
    product: Product,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var promotionType by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val onPaymentResultCallback: (PaymentSheetResult) -> Unit = { paymentResult ->
        isLoading = false
        when (paymentResult) {
            is PaymentSheetResult.Completed -> {
                promotionType?.let {
                    viewModel.confirmPromotion(product.id, it) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        if (success) onDismiss()
                    }
                }
            }
            is PaymentSheetResult.Canceled -> Toast.makeText(context, "Payment canceled.", Toast.LENGTH_SHORT).show()
            is PaymentSheetResult.Failed -> Toast.makeText(context, paymentResult.error.localizedMessage, Toast.LENGTH_LONG).show()
        }
        promotionType = null // Reset for next time
    }

    val paymentSheet = rememberPaymentSheet(paymentResultCallback = onPaymentResultCallback)

    val launchPayment: (String) -> Unit = { type ->
        isLoading = true
        promotionType = type
        viewModel.createPromotionPaymentIntent(
            promotionType = type,
            onSuccess = { clientSecret ->
                paymentSheet.presentWithPaymentIntent(clientSecret)
            },
            onError = { error ->
                isLoading = false
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp)) {
            if (isLoading) {
                Box(modifier = Modifier.padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.RocketLaunch, "Promote", modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Promote Your Listing", style = MaterialTheme.typography.headlineSmall)
                    Text("Get more views and sell faster!", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))

                    PromotionOption(
                        title = "Bump to Top",
                        description = "Move your listing to the top of search results for 24 hours.",
                        price = "$1.00",
                        onClick = { launchPayment("bump") },
                        isFeatured = false
                    )

                    Spacer(Modifier.height(16.dp))

                    PromotionOption(
                        title = "Feature Listing",
                        description = "Mark your listing with a 'Featured' badge for the entire listing duration.",
                        price = "$5.00",
                        onClick = { launchPayment("feature") },
                        isFeatured = true
                    )
                }
            }
        }
    }
}

@Composable
fun PromotionOption(
    title: String,
    description: String,
    price: String,
    onClick: () -> Unit,
    isFeatured: Boolean
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = BorderStroke(1.dp, if (isFeatured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Text(price, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

