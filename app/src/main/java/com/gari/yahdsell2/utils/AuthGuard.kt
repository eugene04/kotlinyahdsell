package com.gari.yahdsell2.utils

import com.google.firebase.auth.FirebaseUser

/**
 * Checks if the user is logged in.
 * - If YES: Executes the [onAuthenticated] action immediately.
 * - If NO:  Runs [onNavigateToLogin], which redirects to Home and opens Login.
 */
fun requireAuthentication(
    currentUser: FirebaseUser?,
    onNavigateToLogin: () -> Unit,
    onAuthenticated: () -> Unit
) {
    if (currentUser != null) {
        onAuthenticated()
    } else {
        onNavigateToLogin()
    }
}