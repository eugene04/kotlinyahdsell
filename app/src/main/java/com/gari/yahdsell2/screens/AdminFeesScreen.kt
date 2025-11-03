package com.gari.yahdsell2.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType // Ensure this import exists
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.gari.yahdsell2.viewmodel.AdminFeesViewModel
import com.gari.yahdsell2.viewmodel.FeesUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminFeesScreen(
    navController: NavController,
    viewModel: AdminFeesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isUpdating by viewModel.isUpdating.collectAsState()
    val context = LocalContext.current

    var listingFeeInput by remember { mutableStateOf("") }
    var bumpFeeInput by remember { mutableStateOf("") }
    var featureFeeInput by remember { mutableStateOf("") }

    // Update input fields when fees are loaded
    LaunchedEffect(uiState) {
        if (uiState is FeesUiState.Success) {
            val fees = (uiState as FeesUiState.Success).fees
            listingFeeInput = String.format("%.2f", fees.listingFeeCents / 100.0)
            bumpFeeInput = String.format("%.2f", fees.bumpFeeCents / 100.0)
            featureFeeInput = String.format("%.2f", fees.featureFeeCents / 100.0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Fees") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.updateFees(listingFeeInput, bumpFeeInput, featureFeeInput) { success, message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                if (success) navController.popBackStack() // Go back on successful update
                            }
                        },
                        enabled = !isUpdating && uiState is FeesUiState.Success // Enable only if fees are loaded and not updating
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save Fees")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is FeesUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is FeesUiState.Error -> {
                    Text(
                        text = "Error: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
                is FeesUiState.Success -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Set the fees charged for listing and promotions. Enter amounts in dollars (e.g., 7.00 for $7, 0.50 for 50 cents, 0 for free).",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        FeeInputRow(
                            label = "Listing Fee",
                            value = listingFeeInput,
                            onValueChange = { listingFeeInput = it },
                            enabled = !isUpdating
                        )
                        FeeInputRow(
                            label = "Bump Fee (per bump)",
                            value = bumpFeeInput,
                            onValueChange = { bumpFeeInput = it },
                            enabled = !isUpdating
                        )
                        FeeInputRow(
                            label = "Feature Fee (per listing)",
                            value = featureFeeInput,
                            onValueChange = { featureFeeInput = it },
                            enabled = !isUpdating
                        )

                        if (isUpdating) {
                            Spacer(Modifier.height(16.dp))
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeeInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = "Dollar Amount") },
        // ✅ Corrected KeyboardType
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true
    )
}

