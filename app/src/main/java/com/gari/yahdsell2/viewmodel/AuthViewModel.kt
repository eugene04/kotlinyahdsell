package com.gari.yahdsell2.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.userProfileChangeRequest
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
    data class Authenticated(val user: FirebaseUser) : UserState()
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
            if (user != null) {
                _userState.value = UserState.Authenticated(user)
                checkAdminStatus()
            } else {
                _userState.value = UserState.Unauthenticated
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _userState.value = UserState.Loading
            try {
                auth.signInWithEmailAndPassword(email, password).await()
                // AuthStateListener will handle success
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Sign in failed", e)
                _userState.value = UserState.Error(e.message ?: "An unknown error occurred.")
            }
        }
    }

    // ✅ ADDED: Missing signUp function
    fun signUp(email: String, password: String, displayName: String) {
        viewModelScope.launch {
            _userState.value = UserState.Loading
            try {
                // 1. Create User in Firebase Auth
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                val user = result.user ?: throw Exception("User creation failed")

                // 2. Update Profile (Display Name)
                val profileUpdates = userProfileChangeRequest {
                    this.displayName = displayName
                }
                user.updateProfile(profileUpdates).await()

                // 3. Create User Document in Firestore
                val newUserProfile = UserProfile(
                    uid = user.uid,
                    displayName = displayName,
                    email = email,
                    // other fields will use default values from data class
                )

                // We use .set() to create the document
                firestore.collection("users").document(user.uid).set(newUserProfile).await()

                // AuthStateListener will automatically pick up the authenticated state
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Sign up failed", e)
                _userState.value = UserState.Error(e.message ?: "Registration failed.")
            }
        }
    }

    fun checkAdminStatus() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            firestore.collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { document ->
                    if (document != null) {
                        val userProfile = document.toObject(UserProfile::class.java)
                        _isAdmin.value = userProfile?.isAdmin ?: false
                    }
                }
        }
    }

    fun signOut() {
        auth.signOut()
    }
}