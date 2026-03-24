package com.gari.yahdsell2.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.stripe.android.paymentsheet.PaymentSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

// Data class to hold all necessary info for the Payment Sheet
data class PaymentSheetData(
    val paymentIntentClientSecret: String,
    val configuration: PaymentSheet.Configuration
)

@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions
) : ViewModel() {

    private val _paymentSheetData = MutableStateFlow<PaymentSheetData?>(null)
    val paymentSheetData: StateFlow<PaymentSheetData?> = _paymentSheetData.asStateFlow()

    private fun callApi(action: String, data: Map<String, Any?>): com.google.android.gms.tasks.Task<com.google.firebase.functions.HttpsCallableResult> {
        val finalData = data.toMutableMap()
        auth.currentUser?.uid?.let {
            finalData["userId"] = it
        }
        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to finalData))
    }

    fun createPaymentIntent(
        productId: String,
        isAuction: Boolean,
        auctionDurationDays: Int,
        onSuccess: (String?) -> Unit, // ✅ CHANGED: Allow nullable String
        onError: (String) -> Unit
    ) {
        if (auth.currentUser == null) return onError("You must be logged in to make a payment.")
        viewModelScope.launch {
            try {
                val data = hashMapOf(
                    "productId" to productId,
                    "isAuction" to isAuction,
                    "auctionDurationDays" to auctionDurationDays
                )
                val result = callApi("createPaymentIntent", data).await()
                val clientSecret = (result.data as? Map<String, Any?>)?.get("clientSecret") as? String
                // ✅ CHANGED: Pass null to onSuccess if fee is zero
                onSuccess(clientSecret)
            } catch (e: Exception) {
                Log.e("PaymentViewModel", "Error creating payment intent", e)
                onError(e.message ?: "An unknown error occurred.")
            }
        }
    }

    fun createPromotionPaymentIntent(productId: String, amount: Int) {
        viewModelScope.launch {
            try {
                val data = mapOf("productId" to productId, "amount" to amount)
                val result = callApi("createPromotionPaymentIntent", data).await()
                val paymentIntentClientSecret = (result.data as? Map<String, Any?>)?.get("paymentIntent") as? String
                val ephemeralKey = (result.data as? Map<String, Any?>)?.get("ephemeralKey") as? String
                val customer = (result.data as? Map<String, Any?>)?.get("customer") as? String

                if (paymentIntentClientSecret != null && ephemeralKey != null && customer != null) {
                    val config = PaymentSheet.Configuration(
                        merchantDisplayName = "YahdSell Promotions",
                        customer = PaymentSheet.CustomerConfiguration(customer, ephemeralKey)
                    )
                    _paymentSheetData.value = PaymentSheetData(paymentIntentClientSecret, config)
                }
            } catch (e: Exception) {
                Log.e("PaymentViewModel", "Error creating promotion payment intent", e)
            }
        }
    }

    fun clearPaymentSheetConfig() {
        _paymentSheetData.value = null
    }

    fun confirmPromotion(
        productId: String,
        promotionType: String,
        onResult: (Boolean, String) -> Unit
    ) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                val data = mapOf("productId" to productId, "promotionType" to promotionType)
                callApi("confirmPromotion", data).await()
                onResult(true, "Promotion successful!")
            } catch (e: Exception) {
                Log.e("PaymentViewModel", "Error confirming promotion", e)
                onResult(false, e.message ?: "Could not confirm promotion.")
            }
        }
    }

    fun markProductAsPaid(productId: String, isAuction: Boolean, auctionDurationDays: Int, onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser == null) return onResult(false, "You must be logged in.")
        viewModelScope.launch {
            try {
                val data = mapOf(
                    "productId" to productId,
                    "isAuction" to isAuction,
                    "auctionDurationDays" to auctionDurationDays
                )
                callApi("markProductAsPaid", data).await()
                onResult(true, "Listing activated!")
            } catch (e: Exception) {
                Log.e("PaymentViewModel", "Error marking product as paid", e)
                onResult(false, e.message ?: "An unknown error occurred.")
            }
        }
    }
}