package com.gari.yahdsell2.screens

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.UserState
import com.gari.yahdsell2.model.Bid
import com.gari.yahdsell2.model.Comment
import com.gari.yahdsell2.model.Product
import com.gari.yahdsell2.model.UserProfile
import com.gari.yahdsell2.navigation.Screen
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    productId: String?
) {
    val product by viewModel.selectedProduct.collectAsState()
    val sellerProfile by viewModel.sellerProfile.collectAsState()
    val userState by viewModel.userState.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val bids by viewModel.bids.collectAsState()
    val isProductInWishlist by viewModel.isProductInWishlist.collectAsState()
    val hasPendingOffer by viewModel.userHasPendingOffer.collectAsState()
    val myProducts by viewModel.userProducts.collectAsState()

    val context = LocalContext.current
    var showOfferDialog by remember { mutableStateOf(false) }
    var showSwapDialog by remember { mutableStateOf(false) }
    var showBidDialog by remember { mutableStateOf(false) }
    var showPromotionDialog by remember { mutableStateOf(false) }


    val currentUser = (userState as? UserState.Authenticated)?.user

    LaunchedEffect(productId) {
        if (productId != null) {
            viewModel.fetchProductDetails(productId)
        }
    }

    LaunchedEffect(userState) {
        if (userState is UserState.Authenticated) {
            val uid = (userState as UserState.Authenticated).user.uid
            viewModel.fetchUserProfileAndProducts(uid)
        }
    }

    if (showOfferDialog) {
        MakeOfferDialog(
            productPrice = product?.price ?: 0.0,
            onDismiss = { showOfferDialog = false },
            onSubmit = { offerAmount ->
                if (productId != null) {
                    viewModel.submitOffer(productId, offerAmount)
                    Toast.makeText(context, "Offer sent!", Toast.LENGTH_SHORT).show()
                }
                showOfferDialog = false
            }
        )
    }

    if (showSwapDialog) {
        ProposeSwapDialog(
            myProducts = myProducts.filter { !it.isSold && it.id != productId },
            onDismiss = { showSwapDialog = false },
            onConfirm = { myProductId, cashTopUp ->
                if (productId != null) {
                    viewModel.proposeSwap(myProductId, productId, cashTopUp) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
                showSwapDialog = false
            }
        )
    }

    if (showBidDialog) {
        PlaceBidDialog(
            currentBid = product?.auctionInfo?.currentBid ?: 0.0,
            startingPrice = product?.auctionInfo?.startingPrice ?: 0.0,
            onDismiss = { showBidDialog = false },
            onConfirm = { bidAmount ->
                if (productId != null) {
                    viewModel.placeBid(productId, bidAmount) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
                showBidDialog = false
            }
        )
    }

    if (showPromotionDialog) {
        product?.let {
            PromotionDialog(
                product = it,
                viewModel = viewModel,
                onDismiss = { showPromotionDialog = false }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(product?.name ?: "Details", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            product?.let { p ->
                ProductActionBar(
                    product = p,
                    isOwnListing = p.sellerId == currentUser?.uid,
                    hasPendingOffer = hasPendingOffer,
                    onMakeOffer = { if (currentUser != null) showOfferDialog = true else navController.navigate(Screen.Login.route) },
                    onProposeSwap = { if (currentUser != null) showSwapDialog = true else navController.navigate(Screen.Login.route) },
                    onPlaceBid = { if (currentUser != null) showBidDialog = true else navController.navigate(Screen.Login.route) },
                    onChat = {
                        if (currentUser != null && sellerProfile != null) {
                            navController.navigate(Screen.PrivateChat.createRoute(sellerProfile!!.uid, sellerProfile!!.displayName))
                        } else {
                            navController.navigate(Screen.Login.route)
                        }
                    },
                    onManageOffers = { navController.navigate(Screen.Offers.createRoute(p.id, p.name)) },
                    onEdit = { navController.navigate(Screen.EditProduct.createRoute(p.id)) },
                    onPromote = { showPromotionDialog = true }
                )
            }
        }
    ) { paddingValues ->
        if (productId == null) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) { Text("Product not found.") }
            return@Scaffold
        }

        if (product == null) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            product?.let { p ->
                LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding())) {
                    item { Spacer(modifier = Modifier.height(paddingValues.calculateTopPadding())) }

                    p.auctionInfo?.let { auction ->
                        if (p.isSold) {
                            val isWinner = auction.leadingBidderId == currentUser?.uid
                            val isSeller = p.sellerId == currentUser?.uid
                            item { AuctionEndedBanner(isWinner = isWinner, isSeller = isSeller) }
                        }
                    }

                    item {
                        MediaCarousel(
                            product = p,
                            isWishlisted = isProductInWishlist,
                            onWishlistToggle = {
                                val user = (userState as? UserState.Authenticated)?.user
                                when {
                                    user == null -> navController.navigate(Screen.Login.route)
                                    p.sellerId == user.uid -> Toast.makeText(context, "You cannot wishlist your own item.", Toast.LENGTH_SHORT).show()
                                    else -> viewModel.toggleWishlistForItem(p)
                                }
                            }
                        )
                    }

                    if (p.auctionInfo != null) {
                        item { AuctionInfoSection(product = p) }
                    } else {
                        item { ProductPrimaryInfo(product = p) }
                    }

                    item {
                        sellerProfile?.let {
                            SellerInfoCard(
                                seller = it,
                                product = p,
                                onClick = { navController.navigate(Screen.UserProfile.createRoute(it.uid)) },
                                onReviewsClick = { navController.navigate(Screen.SellerReviews.createRoute(it.uid, it.displayName)) }
                            )
                        } ?: Box(modifier = Modifier.padding(16.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }

                    if (p.auctionInfo != null) {
                        item { BidHistorySection(bids = bids) }
                    }

                    item {
                        CommentsSection(
                            comments = comments,
                            currentUser = currentUser,
                            onPostComment = { commentText -> viewModel.postComment(productId, commentText) },
                            onLoginRequest = { navController.navigate(Screen.Login.route) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AuctionEndedBanner(isWinner: Boolean, isSeller: Boolean) {
    val message = when {
        isWinner -> "Congratulations, you won! Check your chat with the seller to arrange payment and collection."
        isSeller -> "Your auction has ended! Check your chat with the winner to arrange collection."
        else -> "This auction has ended."
    }
    val backgroundColor = if (isWinner || isSeller) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isWinner || isSeller) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, color = textColor, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCarousel(
    product: Product,
    isWishlisted: Boolean,
    onWishlistToggle: () -> Unit
) {
    val mediaItems = remember(product) {
        val items = mutableListOf<Pair<String, String>>()
        if (!product.videoUrl.isNullOrBlank()) {
            items.add("video" to product.videoUrl)
        }
        items.addAll(product.imageUrls.map { "image" to it })
        if (items.isEmpty()) {
            items.add("image" to "https://placehold.co/600x600?text=No+Image")
        }
        items
    }

    val pagerState = rememberPagerState(pageCount = { mediaItems.size })

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        HorizontalPager(state = pagerState) { page ->
            val (type, uri) = mediaItems[page]
            if (type == "image") {
                Image(
                    painter = rememberAsyncImagePainter(uri),
                    contentDescription = "Product Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                VideoPlayer(videoUri = Uri.parse(uri))
            }
        }

        IconButton(
            onClick = onWishlistToggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(
                imageVector = if (isWishlisted) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Add to Wishlist",
                tint = if (isWishlisted) Color.Red else Color.White
            )
        }

        if (product.isSold) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("SOLD", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
            }
        }

        if (mediaItems.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(mediaItems.size) { iteration ->
                    val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else Color.LightGray
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductPrimaryInfo(product: Product) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(product.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatPrice(product.price),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InfoChip(
                icon = Icons.Default.Visibility,
                text = "${product.viewCount} Views"
            )
            InfoChip(
                icon = Icons.Default.Shield,
                text = product.condition
            )
            val timeRemaining = getTimeRemaining(product.expiresAt)
            if (timeRemaining.isNotBlank()) {
                InfoChip(
                    icon = Icons.Default.Timer,
                    text = timeRemaining
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Text("Description", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(product.description, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AuctionInfoSection(product: Product) {
    val auction = product.auctionInfo ?: return

    var timeRemaining by remember { mutableStateOf(getTimeRemaining(auction.endTime)) }

    LaunchedEffect(auction.endTime) {
        while (true) {
            timeRemaining = getTimeRemaining(auction.endTime)
            delay(1000)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(product.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("CURRENT BID", style = MaterialTheme.typography.labelMedium)
                Text(
                    formatPrice(auction.currentBid ?: auction.startingPrice),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("AUCTION ENDS IN", style = MaterialTheme.typography.labelMedium)
                Text(
                    timeRemaining,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (timeRemaining.contains("Ended")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InfoChip(
                icon = Icons.Default.Visibility,
                text = "${product.viewCount} Views"
            )
            InfoChip(
                icon = Icons.Default.Shield,
                text = product.condition
            )
        }


        Spacer(Modifier.height(16.dp))
        Text("Description", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(product.description, style = MaterialTheme.typography.bodyLarge)
    }
}


@Composable
private fun InfoChip(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SellerInfoCard(
    seller: UserProfile,
    product: Product,
    onClick: () -> Unit,
    onReviewsClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = rememberAsyncImagePainter(seller.profilePicUrl ?: "https://placehold.co/100x100"),
                    contentDescription = "Seller Avatar",
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(seller.displayName, fontWeight = FontWeight.Bold)
                        if (seller.isVerified) {
                            Icon(
                                Icons.Default.Verified,
                                contentDescription = "Verified Seller",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(start = 4.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.clickable(onClick = onReviewsClick),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, contentDescription = "Rating", tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${seller.averageRating} (${seller.ratingCount} reviews)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onReviewsClick) {
                    Text("See all")
                }
            }
            HorizontalDivider()

            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            product.sellerLocation?.let { geoPoint ->
                                val gmmIntentUri =
                                    Uri.parse("google.navigation:q=${geoPoint.latitude},${geoPoint.longitude}")
                                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                                mapIntent.setPackage("com.google.android.apps.maps")
                                if (mapIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(mapIntent)
                                } else {
                                    Toast
                                        .makeText(
                                            context,
                                            "Google Maps is not installed.",
                                            Toast.LENGTH_SHORT
                                        )
                                        .show()
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = "Location",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Get Directions",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Location provided by seller — verify before meeting",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


@Composable
private fun CommentsSection(
    comments: List<Comment>,
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onPostComment: (String) -> Unit,
    onLoginRequest: () -> Unit
) {
    var newComment by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        if (currentUser != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newComment,
                    onValueChange = { newComment = it },
                    label = { Text("Add a comment...") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (newComment.isNotBlank()) {
                            onPostComment(newComment)
                            newComment = ""
                            keyboardController?.hide()
                        }
                    })
                )
                IconButton(
                    onClick = {
                        if (newComment.isNotBlank()) {
                            onPostComment(newComment)
                            newComment = ""
                            keyboardController?.hide()
                        }
                    },
                    enabled = newComment.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post Comment")
                }
            }
        } else {
            OutlinedButton(onClick = onLoginRequest, modifier = Modifier.fillMaxWidth()) {
                Text("Log In to Post a Comment")
            }
        }

        Spacer(Modifier.height(16.dp))

        if (comments.isEmpty()) {
            Text("Be the first to comment!", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            comments.forEach { comment ->
                CommentItem(comment = comment)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun BidHistorySection(bids: List<Bid>) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Bid History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        if (bids.isEmpty()) {
            Text("No bids placed yet. Be the first!", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(bids) { bid ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(bid.bidderName, fontWeight = FontWeight.SemiBold)
                        Text(formatPrice(bid.amount), fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}


@Composable
private fun CommentItem(comment: Comment) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Image(
            painter = rememberAsyncImagePainter(comment.userPhotoURL ?: "https://placehold.co/100x100"),
            contentDescription = "Commenter Avatar",
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.userName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                val date = comment.timestamp?.let {
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it)
                } ?: ""
                Text(date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(comment.text, style = MaterialTheme.typography.bodyMedium)
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
            playWhenReady = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = exoPlayer
                useController = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    )
}

@Composable
fun ProductActionBar(
    product: Product,
    isOwnListing: Boolean,
    hasPendingOffer: Boolean,
    onMakeOffer: () -> Unit,
    onProposeSwap: () -> Unit,
    onPlaceBid: () -> Unit,
    onChat: () -> Unit,
    onManageOffers: () -> Unit,
    onEdit: () -> Unit,
    onPromote: () -> Unit
) {
    val isAuction = product.auctionInfo != null
    val isAuctionActive = product.auctionInfo?.endTime?.after(Date()) == true

    Surface(
        shadowElevation = 8.dp,
        tonalElevation = 4.dp,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (isOwnListing) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), enabled = !product.isSold) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Edit")
                    }
                    Button(onClick = onManageOffers, modifier = Modifier.weight(1f)) {
                        Text("Manage")
                    }
                }
                if (!product.isSold && product.expiresAt?.after(Date()) == true) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onPromote, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.RocketLaunch, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Promote Listing")
                    }
                }
            } else if (isAuction) {
                // Auction View for bidders
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onChat, modifier = Modifier.weight(1f), enabled = !product.isSold) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Chat")
                    }
                    Button(onClick = onPlaceBid, modifier = Modifier.weight(1f), enabled = !product.isSold && isAuctionActive) {
                        Text(if (!isAuctionActive) "Auction Ended" else "Place Bid")
                    }
                }
            }
            else {
                // Fixed Price View
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onMakeOffer,
                        modifier = Modifier.weight(1f),
                        enabled = !product.isSold && !hasPendingOffer,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(if (product.isSold) "Sold" else if (hasPendingOffer) "Offer Pending" else "Make Offer")
                    }
                    OutlinedButton(onClick = onChat, modifier = Modifier.weight(1f), enabled = !product.isSold) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Chat")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onProposeSwap, modifier = Modifier.fillMaxWidth(), enabled = !product.isSold) {
                    Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Propose Swap")
                }
            }
        }
    }
}

@Composable
fun MakeOfferDialog(
    productPrice: Double,
    onDismiss: () -> Unit,
    onSubmit: (Double) -> Unit
) {
    var offerAmount by remember { mutableStateOf("") }
    val isAmountValid = offerAmount.toDoubleOrNull()?.let { it > 0 } ?: false

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Make an Offer", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Listing Price: ${formatPrice(productPrice)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = offerAmount,
                    onValueChange = { offerAmount = it },
                    label = { Text("Your offer amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Text("$") }
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSubmit(offerAmount.toDouble()) }, enabled = isAmountValid) { Text("Submit Offer") }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposeSwapDialog(
    myProducts: List<Product>,
    onDismiss: () -> Unit,
    onConfirm: (String, Double?) -> Unit
) {
    var selectedProductId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var cashTopUp by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Propose a Swap", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp))

                if (myProducts.isEmpty()) {
                    Text("You have no active listings to propose for a swap.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            value = myProducts.find { it.id == selectedProductId }?.name ?: "Select your item to trade",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Your Item") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            myProducts.forEach { product ->
                                DropdownMenuItem(
                                    text = { Text(product.name) },
                                    onClick = {
                                        selectedProductId = product.id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = cashTopUp,
                    onValueChange = { cashTopUp = it.filter { char -> char.isDigit() || char == '.' } },
                    label = { Text("Add Cash (Optional)") },
                    leadingIcon = { Icon(Icons.Default.MonetizationOn, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            selectedProductId?.let {
                                val cashAmount = cashTopUp.toDoubleOrNull()
                                onConfirm(it, cashAmount)
                            }
                        },
                        enabled = selectedProductId != null
                    ) { Text("Send Proposal") }
                }
            }
        }
    }
}

@Composable
fun PlaceBidDialog(
    currentBid: Double,
    startingPrice: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var bidAmount by remember { mutableStateOf("") }
    val minBid = currentBid.takeIf { it > 0 && it > startingPrice } ?: startingPrice
    val isBidValid = bidAmount.toDoubleOrNull()?.let { it > minBid } ?: false

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Place a Bid", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Enter an amount higher than ${formatPrice(minBid)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = bidAmount,
                    onValueChange = { bidAmount = it.filter { char -> char.isDigit() || char == '.' } },
                    label = { Text("Your Bid") },
                    leadingIcon = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = bidAmount.isNotBlank() && !isBidValid
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { onConfirm(bidAmount.toDouble()) },
                        enabled = isBidValid
                    ) { Text("Confirm Bid") }
                }
            }
        }
    }
}


private fun formatPrice(price: Double): String {
    val numberFormat = NumberFormat.getCurrencyInstance(Locale.US)
    return numberFormat.format(price)
}

private fun getTimeRemaining(expiryDate: Date?): String {
    if (expiryDate == null) return "Expired"
    val now = Date()
    if (now.after(expiryDate)) return "Auction Ended"

    val diff = expiryDate.time - now.time
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff) % 60

    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        seconds > 0 -> "${seconds}s"
        else -> "Ending soon"
    }
}

