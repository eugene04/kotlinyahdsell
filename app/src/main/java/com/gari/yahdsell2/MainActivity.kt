package com.gari.yahdsell2

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
// ✅ FIXED: Added the missing import for rememberLauncherForActivityResult
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.gari.yahdsell2.navigation.AppNavigation
import com.gari.yahdsell2.ui.theme.YahdSellTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val requestPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (isGranted) {
                    // Permission is granted. You can now expect notifications.
                } else {
                    // Explain to the user that notifications will be disabled.
                }
            }

            fun askNotificationPermission() {
                // This is only necessary for API level 33+ (TIRAMISU)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            LaunchedEffect(Unit) {
                askNotificationPermission()
            }

            var isDarkMode by remember { mutableStateOf(false) }
            val toggleTheme: () -> Unit = { isDarkMode = !isDarkMode }

            YahdSellTheme(darkTheme = isDarkMode) {
                AppNavigation(toggleTheme = toggleTheme)
            }
        }
    }
}
