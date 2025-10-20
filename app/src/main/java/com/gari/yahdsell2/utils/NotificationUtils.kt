package com.gari.yahdsell2.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gari.yahdsell2.MainActivity
import com.gari.yahdsell2.R

object NotificationUtils {

    // It's best practice to get the channel ID from your strings.xml file
    private fun getChannelId(context: Context): String {
        return context.getString(R.string.default_notification_channel_id)
    }

    private const val CHANNEL_NAME = "YahdSell Notifications"
    private const val CHANNEL_DESCRIPTION = "General notifications for YahdSell app"

    /**
     * Creates the notification channel required for Android 8.0 (API 26) and above.
     * This should be called once when the application starts.
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getChannelId(context),
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESCRIPTION
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds and displays a notification. Includes the necessary permission check to fix the error.
     */
    fun showNotification(context: Context, title: String, message: String, notificationId: Int, data: Map<String, String>) {
        // Create an intent that will open the app when the notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Pass data from the notification payload to the activity
            data.forEach { (key, value) ->
                putExtra(key, value)
            }
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            notificationId, // Use a unique request code for each notification
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


        val builder = NotificationCompat.Builder(context, getChannelId(context))
            .setSmallIcon(R.drawable.ic_stat_name) // Your notification icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Dismiss the notification when tapped

        // ✅ FIX: This permission check resolves the error.
        // It verifies that the app has the required permission before attempting to post the notification.
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // If permission is not granted, we log a warning and exit the function.
            // The notification will not be shown. This is the expected behavior if the user
            // has denied the notification permission.
            Log.w("NotificationUtils", "POST_NOTIFICATIONS permission not granted. Notification will not be shown.")
            return
        }

        with(NotificationManagerCompat.from(context)) {
            // The notificationId is a unique integer that identifies this notification.
            // If you post another notification with the same ID, it will update the existing one.
            notify(notificationId, builder.build())
        }
    }
}

