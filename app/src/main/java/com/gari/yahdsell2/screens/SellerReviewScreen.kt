package com.gari.yahdsell2.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.UserState
import com.gari.yahdsell2.model.Review
import com.gari.yahdsell2.navigation.Screen
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellerReviewScreen(
    navController: NavController,
    viewModel: MainViewModel,
    sellerId: String,
    sellerName: String
) {
    val reviews by viewModel.sellerReviews.collectAsState()
    val isLoading by viewModel.isLoadingReviews.collectAsState()
    val userState by viewModel.userState.collectAsState()
    val context = LocalContext.current
    var showReviewDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sellerId) {
        viewModel.fetchSellerReviews(sellerId)
    }

    val canAddReview = (userState as? UserState.Authenticated)?.user?.uid != sellerId

    if (showReviewDialog) {
        ReviewDialog(
            sellerName = sellerName,
            onDismiss = { showReviewDialog = false },
            onSubmit = { rating, comment ->
                viewModel.postReview(sellerId, rating, comment) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    if (success) {
                        showReviewDialog = false
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$sellerName's Reviews") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (canAddReview) {
                FloatingActionButton(onClick = {
                    if (userState is UserState.Authenticated) {
                        showReviewDialog = true
                    } else {
                        navController.navigate(Screen.Login.route)
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Review")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (reviews.isEmpty()) {
                Text("No reviews yet for this seller.", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(reviews) { review ->
                        ReviewItem(review)
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewItem(review: Review) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(review.reviewerName, fontWeight = FontWeight.Bold)
                val date = review.createdAt?.let {
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it)
                } ?: ""
                Text(date, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            StarRating(rating = review.rating)
            Spacer(Modifier.height(8.dp))
            Text(review.comment, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun StarRating(rating: Int) {
    Row {
        for (i in 1..5) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = if (i <= rating) Color(0xFFFFC107) else Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ReviewDialog(
    sellerName: String,
    onDismiss: () -> Unit,
    onSubmit: (Int, String) -> Unit
) {
    var rating by remember { mutableStateOf(0) }
    var comment by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Review $sellerName", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.Center) {
                    (1..5).forEach { star ->
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Star $star",
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { rating = star },
                            tint = if (star <= rating) Color(0xFFFFC107) else Color.LightGray
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Share your experience...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            isSubmitting = true
                            onSubmit(rating, comment)
                        },
                        enabled = !isSubmitting && rating > 0 && comment.isNotBlank()
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Submit")
                        }
                    }
                }
            }
        }
    }
}

