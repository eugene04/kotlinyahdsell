package com.gari.yahdsell2.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gari.yahdsell2.MainActivity
import com.gari.yahdsell2.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "FCM_Service"

    /**
     * Called when a new push notification message is received.
     * This is the entry point for handling incoming messages from FCM.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // Check if the message contains a notification payload and handle it.
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            sendNotification(it.title, it.body)
        }
    }

    /**
     * Called when a new FCM registration token is generated.
     * This token is the unique identifier for this app instance and is needed to send notifications to this specific device.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed token: $token")
        sendTokenToServer(token)
    }

    /**
     * Saves the new FCM token to the currently logged-in user's document in Firestore.
     */
    private fun sendTokenToServer(token: String) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val tokenInfo = mapOf("token" to token, "timestamp" to FieldValue.serverTimestamp())
            FirebaseFirestore.getInstance()
                .collection("users").document(currentUser.uid)
                .collection("pushTokens").document(token)
                .set(tokenInfo)
                .addOnSuccessListener { Log.d(TAG, "Token successfully saved to Firestore.") }
                .addOnFailureListener { e -> Log.e(TAG, "Error saving token to Firestore", e) }
        } else {
            Log.w(TAG, "Cannot save token, no user is currently logged in.")
        }
    }

    /**
     * Creates and displays a standard Android notification on the device.
     */
    private fun sendNotification(title: String?, messageBody: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)

        val channelId = getString(R.string.default_notification_channel_id)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_name) // Make sure you have this drawable
            .setContentTitle(title ?: "New Message")
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Since Android 8.0 (Oreo), notification channels are required.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId,
                "YahdSell Notifications",
                NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        // Display the notification. The ID can be any unique integer.
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}

