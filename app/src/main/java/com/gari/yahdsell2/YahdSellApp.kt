package com.gari.yahdsell2

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.stripe.android.PaymentConfiguration
import dagger.hilt.android.HiltAndroidApp
import com.gari.yahdsell2.BuildConfig

@HiltAndroidApp
class YahdSellApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase
        FirebaseApp.initializeApp(this)

        // Initialize Firebase App Check to protect backend resources
        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        firebaseAppCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )

        // Stripe initialization
        PaymentConfiguration.init(
            applicationContext,
            BuildConfig.STRIPE_PUBLISHABLE_KEY
        )

        Log.d("AppSetup", "YahdSellApp Hilt application initialized with App Check.")
    }
}

