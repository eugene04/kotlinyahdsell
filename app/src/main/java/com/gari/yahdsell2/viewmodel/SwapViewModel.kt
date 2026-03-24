package com.gari.yahdsell2.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.ProductSwap
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.functions.FirebaseFunctions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class SwapViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions
) : ViewModel() {

    private val _swaps = MutableStateFlow<List<ProductSwap>>(emptyList())
    val swaps: StateFlow<List<ProductSwap>> = _swaps.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var swapListener: ListenerRegistration? = null

    init {
        listenForUserSwaps()
    }

    private fun listenForUserSwaps() {
        val currentUser = auth.currentUser ?: return
        _isLoading.value = true

        // Listen for swaps where the user is either the proposer or the target
        swapListener = firestore.collection("swaps")
            .whereArrayContains("participants", currentUser.uid)
            .orderBy("proposedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                _isLoading.value = false
                if (e != null) {
                    Log.e("SwapViewModel", "Error fetching swaps", e)
                    _error.value = "Failed to load swaps: ${e.message}"
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    _swaps.value = snapshot.documents.mapNotNull { doc ->
                        doc.toObject<ProductSwap>()?.copy(id = doc.id)
                    }
                }
            }
    }

    // This function will be called from the screen
    fun respondToSwap(
        swapId: String,
        action: String, // "accept" or "reject"
        onResult: (Boolean, String) -> Unit
    ) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            onResult(false, "Authentication required to respond to a swap.")
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Call Cloud Function to handle the swap response transactionally
                val data = mapOf(
                    "swapId" to swapId,
                    "action" to action // "accept" or "reject"
                )

                // Assuming a publicApi function that gates access based on auth status
                functions.getHttpsCallable("publicApi").call(mapOf("action" to "respondToSwap", "data" to data)).await()

                onResult(true, "Swap ${action}ed successfully!")
                // The listener will automatically update the UI after the Firestore change
            } catch (e: Exception) {
                Log.e("SwapViewModel", "Error responding to swap $swapId", e)
                onResult(false, e.message ?: "Failed to process swap response.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        swapListener?.remove()
    }
}