package com.gari.yahdsell2.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Date
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class AppModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = FirebaseStorage.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFunctions(): FirebaseFunctions =
        FirebaseFunctions.getInstance("us-central1")

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            // Register a custom deserializer to handle dates sent as Long timestamps (milliseconds)
            .registerTypeAdapter(Date::class.java, JsonDeserializer { json, _, _ ->
                try {
                    // The Cloud Function sends dates as timestamps (e.g., 1764221581444)
                    // standard Gson tries to parse this as a date string and fails.
                    // We intercept it here and convert the Long directly to a Date object.
                    Date(json.asJsonPrimitive.asLong)
                } catch (e: Exception) {
                    // If it's not a long, return null or handle alternative formats if needed
                    null
                }
            })
            .create()
    }
}