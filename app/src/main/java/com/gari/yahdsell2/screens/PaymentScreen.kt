package com.gari.yahdsell2.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.navigation.Screen
import com.google.gson.Gson
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    productId: String?,
    productJson: String?
) {
    val context = LocalContext.current
    val productData = remember(productJson) {
        try {
            Gson().fromJson(Uri.decode(productJson), Map::class.java) as? Map<String, Any?>
        } catch (e: Exception) { null }
    }

    var clientSecret by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isActivating by remember { mutableStateOf(false) }

    val isAuction = productData?.get("auctionInfo") != null
    val auctionDurationDays = (productData?.get("auctionDurationDays") as? Double)?.toInt() ?: 7


    val onPaymentResultCallback: (PaymentSheetResult) -> Unit = { paymentResult ->
        when (paymentResult) {
            is PaymentSheetResult.Completed -> {
                if (productId == null) {
                    errorMessage = "Product ID missing. Cannot activate listing."
                } else {
                    isActivating = true
                    viewModel.markProductAsPaid(productId, isAuction, auctionDurationDays) { success, message ->
                        isActivating = false
                        if (success) {
                            Toast.makeText(context, "Payment Successful! Your item is now listed.", Toast.LENGTH_LONG).show()
                            navController.navigate(Screen.Main.route) {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                launchSingleTop = true
                            }
                        } else {
                            errorMessage = message
                        }
                    }
                }
            }
            is PaymentSheetResult.Canceled -> errorMessage = "Payment was canceled."
            is PaymentSheetResult.Failed -> errorMessage = paymentResult.error.localizedMessage ?: "An unknown payment error occurred."
        }
    }

    val paymentSheet = rememberPaymentSheet(paymentResultCallback = onPaymentResultCallback)

    LaunchedEffect(productId) {
        if (productId == null) {
            errorMessage = "Product ID not found."
            isLoading = false
            return@LaunchedEffect
        }
        viewModel.createPaymentIntent(
            productId = productId,
            isAuction = isAuction,
            auctionDurationDays = auctionDurationDays,
            onSuccess = { secret ->
                clientSecret = secret
                isLoading = false
            },
            onError = { error ->
                errorMessage = error
                isLoading = false
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Complete Listing") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
                Text("Preparing payment...", modifier = Modifier.padding(top = 16.dp))
            } else if (isActivating) {
                CircularProgressIndicator()
                Text("Activating your listing...", modifier = Modifier.padding(top = 16.dp))
            } else if (errorMessage != null) {
                Text("Error", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text(productData?.get("name")?.toString() ?: "Your Product", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Text("Listing Fee: $7.00", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                val durationText = if (isAuction) "$auctionDurationDays days" else "7 days"
                Text("Your item will be listed for $durationText.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top=4.dp))
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { clientSecret?.let { paymentSheet.presentWithPaymentIntent(it) } },
                    enabled = clientSecret != null
                ) { Text("Pay and List Item") }
            }
        }
    }
}
