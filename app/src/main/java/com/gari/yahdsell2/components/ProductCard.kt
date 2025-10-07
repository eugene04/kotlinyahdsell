package com.gari.yahdsell2.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.auth.FirebaseUser

@Composable
fun ProductCard(
    product: Map<String, Any>,
    currentUser: FirebaseUser?,
    onToggleWishlist: (productId: String, sellerId: String) -> Unit,
    onClick: () -> Unit
) {
    val isSold = product["isSold"] as? Boolean ?: false
    val imageUrl = (product["imageUrls"] as? List<*>)?.firstOrNull()?.toString()
        ?: "https://placehold.co/150x120/e0e0e0/7f7f7f?text=No+Image"
    val name = product["name"]?.toString() ?: "Unnamed Product"
    val seller = product["sellerDisplayName"]?.toString() ?: "Seller"
    val price = product["price"] as? Double ?: 0.0
    val distanceKm = product["distanceKm"] as? Double
    val productId = product["id"]?.toString() ?: ""
    val sellerId = product["sellerId"]?.toString() ?: ""
    val isSaved = product["isSaved"] as? Boolean ?: false

    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp)
            .clickable(enabled = !isSold) { onClick() }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = rememberAsyncImagePainter(imageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                if (isSold) {
                    Text(
                        text = "SOLD",
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                if (currentUser != null && !isSold) {
                    IconButton(
                        onClick = { onToggleWishlist(productId, sellerId) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isSaved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Save",
                            tint = if (isSaved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text("By: $seller", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (distanceKm != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${"%.1f".format(distanceKm)} km away", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            PriceDisplay(price = price, size = "large")
        }
    }
}