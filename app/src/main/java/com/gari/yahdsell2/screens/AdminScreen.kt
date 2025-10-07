package com.gari.yahdsell2.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.model.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val verificationRequests by viewModel.verificationRequests.collectAsState()
    val isLoading by viewModel.isLoadingVerificationRequests.collectAsState()
    var processingId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.fetchVerificationRequests()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin - Verification") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (verificationRequests.isEmpty()) {
                Text("No pending verification requests.", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(verificationRequests) { user ->
                        VerificationRequestItem(
                            user = user,
                            isProcessing = processingId == user.uid,
                            onApprove = {
                                processingId = user.uid
                                viewModel.approveVerification(user.uid) { success, message ->
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    processingId = null
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
fun VerificationRequestItem(
    user: UserProfile,
    isProcessing: Boolean,
    onApprove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(user.displayName, fontWeight = FontWeight.Bold)
                Text(user.email, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onApprove,
                enabled = !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Approve")
                }
            }
        }
    }
}
