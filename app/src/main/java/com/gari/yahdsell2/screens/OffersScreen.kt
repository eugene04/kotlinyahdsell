package com.gari.yahdsell2.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.gari.yahdsell2.model.Offer
import com.gari.yahdsell2.viewmodel.ProductDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersScreen(
    navController: NavController,
    productId: String,
    productName: String,
    viewModel: ProductDetailViewModel = hiltViewModel()
) {
    val offers by viewModel.offersForProduct.collectAsState()
    val isLoading by viewModel.isLoadingOffers.collectAsState()

    LaunchedEffect(productId) {
        viewModel.listenForProductOffers(productId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offers for $productName") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (offers.isEmpty()) {
                Text("You have not received any offers for this item yet.", modifier = Modifier.align(Alignment.Center).padding(16.dp))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = offers, key = { offer -> offer.id }) { offer ->
                        OfferItem(
                            offer = offer,
                            onAccept = {
                                viewModel.respondToOffer(offer.id, true)
                            },
                            onReject = {
                                viewModel.respondToOffer(offer.id, false)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OfferItem(
    offer: Offer,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Offer from: ${offer.buyerName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Amount: $${String.format("%.2f", offer.offerAmount)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))

            when (offer.status) {
                "pending" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = onReject) {
                            Text("Reject")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onAccept) {
                            Text("Accept")
                        }
                    }
                }
                "accepted" -> {
                    Text(
                        "Offer Accepted",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
                "rejected" -> {
                    Text(
                        "Offer Rejected",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}
