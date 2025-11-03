package com.gari.yahdsell2.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.viewmodel.ProfileViewModel
import com.gari.yahdsell2.model.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val profileUser by viewModel.profileUser.collectAsState()
    val context = LocalContext.current

    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var newImageUri by remember { mutableStateOf<Uri?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(profileUser) {
        profileUser?.let {
            displayName = it.displayName
            bio = it.bio
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (profileUser == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            ScrollView(padding, profileUser!!, displayName, bio, newImageUri, isSubmitting,
                onDisplayNameChange = { displayName = it },
                onBioChange = { bio = it },
                onImageUriChange = { newImageUri = it },
                onSubmit = {
                    isSubmitting = true
                    viewModel.updateUserProfile(
                        displayName = displayName,
                        bio = bio,
                        imageUri = newImageUri
                    ) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        isSubmitting = false
                        if (success) {
                            navController.popBackStack()
                        }
                    }
                },
                onVerify = {
                    viewModel.requestVerification { _, message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }
}

@Composable
private fun ScrollView(
    padding: PaddingValues,
    profileUser: UserProfile,
    displayName: String,
    bio: String,
    newImageUri: Uri?,
    isSubmitting: Boolean,
    onDisplayNameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onImageUriChange: (Uri?) -> Unit,
    onSubmit: () -> Unit,
    onVerify: () -> Unit
) {
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> onImageUriChange(uri) }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = newImageUri ?: profileUser.profilePicUrl ?: "https://placehold.co/200x200"
            ),
            contentDescription = "Profile Picture",
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { imagePickerLauncher.launch("image/*") }) {
            Text("Change Photo")
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Display Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = bio,
            onValueChange = onBioChange,
            label = { Text("Bio (optional)") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            maxLines = 4
        )
        Spacer(Modifier.height(24.dp))

        VerificationSection(profileUser, onVerify)

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSubmit,
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Save Changes")
            }
        }
    }
}

@Composable
private fun VerificationSection(
    profileUser: UserProfile,
    onVerify: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Verification", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            when {
                profileUser.isVerified -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, "Verified", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("You are a verified seller.", fontWeight = FontWeight.Bold)
                    }
                }
                profileUser.verificationRequested -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HourglassTop, "Pending")
                        Spacer(Modifier.width(8.dp))
                        Text("Your verification request is pending review.")
                    }
                }
                else -> {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, "Not Verified")
                            Spacer(Modifier.width(8.dp))
                            Text("You are not verified yet.")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onVerify) {
                            Text("Request Verification")
                        }
                    }
                }
            }
        }
    }
}
