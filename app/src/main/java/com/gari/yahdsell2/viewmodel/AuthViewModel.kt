package com.gari.yahdsell2.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject


sealed class UserState {
    object Loading : UserState()
    data class Authenticated(val user: com.google.firebase.auth.FirebaseUser) : UserState()
    object Unauthenticated : UserState()
    data class Error(val message: String) : UserState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _userState = MutableStateFlow<UserState>(UserState.Loading)
    val userState: StateFlow<UserState> = _userState.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()


    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _userState.value = if (user != null) {
                UserState.Authenticated(user)
            } else {
                UserState.Unauthenticated
            }
        }
    }

    fun savePushToken(token: String) {
        val currentUser = auth.currentUser ?: return
        val tokenRef = firestore.collection("users").document(currentUser.uid)
            .collection("pushTokens").document(token)
        tokenRef.set(mapOf("token" to token, "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
            .addOnSuccessListener { Log.d("AuthViewModel", "Push token saved successfully.") }
            .addOnFailureListener { e -> Log.e("AuthViewModel", "Error saving push token", e) }
    }

    fun signOut() {
        auth.signOut()
        _userState.value = UserState.Unauthenticated
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _userState.value = UserState.Loading
            try {
                val result = auth.signInWithEmailAndPassword(email, password).await()
                _userState.value = result.user?.let { UserState.Authenticated(it) }
                    ?: UserState.Error("Authentication failed.")
            } catch (e: Exception) {
                _userState.value = UserState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun signUp(email: String, password: String, displayName: String) {
        viewModelScope.launch {
            _userState.value = UserState.Loading
            try {
                val authResult = auth.createUserWithEmailAndPassword(email, password).await()
                val firebaseUser = authResult.user
                    ?: throw IllegalStateException("Firebase user not found")
                val profileUpdates = userProfileChangeRequest { this.displayName = displayName }
                firebaseUser.updateProfile(profileUpdates).await()
                val userProfile = UserProfile(uid = firebaseUser.uid, displayName = displayName, email = email)
                firestore.collection("users").document(firebaseUser.uid).set(userProfile).await()
                _userState.value = UserState.Authenticated(firebaseUser)
            } catch (e: Exception) {
                _userState.value = UserState.Error(e.message ?: "Sign up failed")
            }
        }
    }

    fun checkAdminStatus() {
        viewModelScope.launch {
            try {
                val user = auth.currentUser
                user?.getIdToken(true)?.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val isAdminClaim = task.result.claims["admin"] as? Boolean
                        _isAdmin.value = isAdminClaim == true
                    }
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Error checking admin status", e)
                _isAdmin.value = false
            }
        }
    }
}

