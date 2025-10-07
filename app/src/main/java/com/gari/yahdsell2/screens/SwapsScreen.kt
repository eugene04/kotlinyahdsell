package com.gari.yahdsell2.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.model.ProductSwap
import java.text.NumberFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwapsScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val swapsData by viewModel.swaps.collectAsState()
    val isLoading by viewModel.isLoadingSwaps.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.fetchSwaps()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Swaps") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Incoming") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Outgoing") }
                )
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val listToShow = if (selectedTab == 0) swapsData.incoming else swapsData.outgoing
                if (listToShow.isEmpty()) {
                    EmptyState(
                        message = if (selectedTab == 0) "You have no incoming swap proposals."
                        else "You have not proposed any swaps."
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(listToShow) { swap ->
                            SwapItemCard(
                                swap = swap,
                                isIncoming = selectedTab == 0,
                                onAccept = {
                                    viewModel.respondToSwap(swap.id, "accepted") { s, m ->
                                        Toast.makeText(context, m, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onReject = {
                                    viewModel.respondToSwap(swap.id, "rejected") { s, m ->
                                        Toast.makeText(context, m, Toast.LENGTH_SHORT).show()
                                    }
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
fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SwapItemCard(
    swap: ProductSwap,
    isIncoming: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                ProductThumbnail(
                    name = swap.proposingProductName,
                    imageUrl = swap.proposingProductImageUrl
                )
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = "Swap",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                ProductThumbnail(
                    name = swap.targetProductName,
                    imageUrl = swap.targetProductImageUrl
                )
            }

            swap.cashTopUp?.let {
                if (it > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "+ ${formatPrice(it)} cash offer",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            when (swap.status) {
                "pending" -> {
                    if (isIncoming) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(onClick = onReject) {
                                Text("Reject")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = onAccept) {
                                Text("Accept")
                            }
                        }
                    } else {
                        StatusChip("Pending", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "accepted" -> {
                    StatusChip("Accepted", Color(0xFF388E3C))
                }
                "rejected" -> {
                    StatusChip("Rejected", MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ProductThumbnail(name: String?, imageUrl: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(120.dp)
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

