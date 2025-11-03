package com.gari.yahdsell2.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class FeeConfig(
    val listingFeeCents: Int = 0,
    val bumpFeeCents: Int = 0,
    val featureFeeCents: Int = 0
)

sealed class FeesUiState {
    object Loading : FeesUiState()
    data class Success(val fees: FeeConfig) : FeesUiState()
    data class Error(val message: String) : FeesUiState()
}

@HiltViewModel
class AdminFeesViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions
) : ViewModel() {

    private val _uiState = MutableStateFlow<FeesUiState>(FeesUiState.Loading)
    val uiState: StateFlow<FeesUiState> = _uiState.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    init {
        fetchCurrentFees()
    }

    private fun callApi(action: String, data: Map<String, Any?>): com.google.android.gms.tasks.Task<com.google.firebase.functions.HttpsCallableResult> {
        val finalData = data.toMutableMap()
        // Add adminId for potential backend checks if needed
        auth.currentUser?.uid?.let {
            finalData["adminId"] = it
        }
        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to finalData))
    }

    fun fetchCurrentFees() {
        viewModelScope.launch {
            _uiState.value = FeesUiState.Loading
            try {
                val result = callApi("getFees", emptyMap()).await()
                @Suppress("UNCHECKED_CAST")
                val feesData = (result.data as? Map<String, Any?>)?.get("fees") as? Map<String, Number>
                if (feesData != null) {
                    val config = FeeConfig(
                        listingFeeCents = feesData["listingFeeCents"]?.toInt() ?: 0,
                        bumpFeeCents = feesData["bumpFeeCents"]?.toInt() ?: 0,
                        featureFeeCents = feesData["featureFeeCents"]?.toInt() ?: 0
                    )
                    _uiState.value = FeesUiState.Success(config)
                } else {
                    _uiState.value = FeesUiState.Error("Could not parse fee data.")
                }
            } catch (e: Exception) {
                Log.e("AdminFeesViewModel", "Error fetching fees", e)
                _uiState.value = FeesUiState.Error(e.message ?: "Failed to fetch fees.")
            }
        }
    }

    fun updateFees(
        listingFee: String,
        bumpFee: String,
        featureFee: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val listingCents = (listingFee.toDoubleOrNull() ?: -1.0) * 100
        val bumpCents = (bumpFee.toDoubleOrNull() ?: -1.0) * 100
        val featureCents = (featureFee.toDoubleOrNull() ?: -1.0) * 100

        if (listingCents < 0 || bumpCents < 0 || featureCents < 0) {
            onResult(false, "Invalid input. Please enter valid dollar amounts (e.g., 7.00, 0.50, 0).")
            return
        }

        viewModelScope.launch {
            _isUpdating.value = true
            try {
                val data = mapOf(
                    "listingFeeCents" to listingCents.toInt(),
                    "bumpFeeCents" to bumpCents.toInt(),
                    "featureFeeCents" to featureCents.toInt()
                )
                callApi("updateFees", data).await()
                _uiState.value = FeesUiState.Success(FeeConfig(listingCents.toInt(), bumpCents.toInt(), featureCents.toInt())) // Update UI state
                onResult(true, "Fees updated successfully!")
            } catch (e: Exception) {
                Log.e("AdminFeesViewModel", "Error updating fees", e)
                val message = if (e is FirebaseFunctionsException) {
                    when (e.code) {
                        FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Authentication required."
                        FirebaseFunctionsException.Code.PERMISSION_DENIED -> "Permission denied. Admin privileges required."
                        else -> e.message
                    }
                } else {
                    e.message
                }
                onResult(false, message ?: "Failed to update fees.")
            } finally {
                _isUpdating.value = false
            }
        }
    }
}
