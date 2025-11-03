package com.gari.yahdsell2.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.AuthViewModel
import com.gari.yahdsell2.viewmodel.UserState

@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: AuthViewModel = hiltViewModel()
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var attemptedLogin by remember { mutableStateOf(false) }

    val userState by viewModel.userState.collectAsState()

    // This navigation is now primarily handled by the gatekeeper in AppNavigation,
    // but this serves as a reliable fallback.
    LaunchedEffect(userState) {
        if (userState is UserState.Authenticated) {
            Log.d("LoginScreen", "User authenticated, navigating to Main screen")
            navController.navigate(Screen.Main.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    val errorMessage = when (val state = userState) {
        is UserState.Error -> state.message
        else -> null
    }

    val isLoading = userState is UserState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Login", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    attemptedLogin = true
                    if (email.isNotBlank() && password.isNotBlank()) {
                        viewModel.signIn(email.trim(), password.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Login")
            }
        }

        if (attemptedLogin && errorMessage != null) {
            Text(text = errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        TextButton(onClick = { navController.navigate(Screen.Signup.route) }) {
            Text("Don't have an account? Sign up")
        }
    }
}
