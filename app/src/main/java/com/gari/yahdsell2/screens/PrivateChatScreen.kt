package com.gari.yahdsell2.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.model.ChatMessage
import com.gari.yahdsell2.viewmodel.AuthViewModel
import com.gari.yahdsell2.viewmodel.ChatViewModel
import com.gari.yahdsell2.viewmodel.UserState
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
    recipientId: String,
    recipientName: String?
) {
    val messages by viewModel.messages.collectAsState()
    val userState by authViewModel.userState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isUploading by viewModel.isUploadingMedia.collectAsState()

    val chatId = (userState as? UserState.Authenticated)?.user?.uid?.let {
        if (it > recipientId) "$it-$recipientId" else "$recipientId-$it"
    } ?: ""

    LaunchedEffect(chatId) {
        if (chatId.isNotBlank()) {
            viewModel.listenForMessages(chatId)
        }
    }

    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipientName ?: "Chat") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        content = { paddingValues ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(
                        message = message,
                        isCurrentUser = message.senderId == (userState as? UserState.Authenticated)?.user?.uid
                    )
                }
            }
        },
        bottomBar = {
            ChatInputBar(
                isUploading = isUploading,
                onSendMessage = { text -> viewModel.sendMessage(chatId, text) },
                onSendImage = { uri -> viewModel.sendImageMessage(chatId, uri, recipientId) },
                onSendVideo = { uri -> viewModel.sendVideoMessage(chatId, uri, recipientId) },
                onSendLocation = { location -> viewModel.sendLocationMessage(chatId, location, recipientId) }
            )
        }
    )
}

@SuppressLint("MissingPermission")
@Composable
fun ChatInputBar(
    isUploading: Boolean,
    onSendMessage: (String) -> Unit,
    onSendImage: (Uri) -> Unit,
    onSendVideo: (Uri) -> Unit,
    onSendLocation: (Location) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    var showLocationConfirmDialog by remember { mutableStateOf(false) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            showLocationConfirmDialog = true
        } else {
            Toast.makeText(context, "Location permission is required to share your location.", Toast.LENGTH_SHORT).show()
        }
    }

    if (showLocationConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLocationConfirmDialog = false },
            title = { Text("Share Location") },
            text = { Text("Are you sure you want to share your current location with this user?") },
            confirmButton = {
                Button(onClick = {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            onSendLocation(location)
                        } else {
                            Toast.makeText(context, "Could not retrieve location. Please ensure GPS is enabled.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showLocationConfirmDialog = false
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }


    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let { onSendImage(it) } }
    )
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let { onSendVideo(it) } }
    )

    Surface(shadowElevation = 8.dp, tonalElevation = 4.dp, modifier = Modifier.navigationBarsPadding().imePadding()) {
        if (isUploading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Uploading media...")
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(Icons.Default.Image, contentDescription = "Attach Image")
                }
                IconButton(onClick = { videoPickerLauncher.launch("video/*") }) {
                    Icon(Icons.Default.Videocam, contentDescription = "Attach Video")
                }
                IconButton(onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                    Icon(Icons.Default.ShareLocation, contentDescription = "Share Location")
                }

                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Message...") }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSendMessage(text)
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}


@Composable
fun MessageBubble(message: ChatMessage, isCurrentUser: Boolean) {
    if (message.type == "system") {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                text = message.text,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        val bubbleColor = if (isCurrentUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        val alignment = if (isCurrentUser) Alignment.CenterEnd else Alignment.CenterStart
        val shape = RoundedCornerShape(16.dp, 16.dp, if(isCurrentUser) 4.dp else 16.dp, if(isCurrentUser) 16.dp else 4.dp)

        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = alignment) {
            Column(modifier = Modifier.widthIn(max = 280.dp).clip(shape).background(bubbleColor).padding(12.dp)) {
                when (message.type) {
                    "text" -> Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    "image" -> MediaMessage(imageUrl = message.imageUrl ?: "", caption = message.text)
                    "video" -> VideoPlayerBubble(videoUrl = message.videoUrl)
                    "location" -> LocationBubble(location = message.location)
                }
                Text(
                    text = formatTimestamp(message.timestamp),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun MediaMessage(imageUrl: String?, caption: String) {
    if (imageUrl != null) {
        Image(
            painter = rememberAsyncImagePainter(model = imageUrl),
            contentDescription = caption,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    }
    if (caption != "Image") {
        Text(caption, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun VideoPlayerBubble(videoUrl: String?) {
    val context = LocalContext.current
    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            videoUrl?.let {
                val mediaItem = MediaItem.fromUri(it)
                setMediaItem(mediaItem)
                prepare()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { PlayerView(it).apply { player = exoPlayer } },
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
fun LocationBubble(location: GeoPoint?) {
    if (location == null) return
    val context = LocalContext.current
    val gmmIntentUri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(Pinned Location)")
    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
    mapIntent.setPackage("com.google.android.apps.maps")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { context.startActivity(mapIntent) }
    ) {
        Text("Location Shared", fontWeight = FontWeight.Bold)
        Text("Tap to view in Google Maps", style = MaterialTheme.typography.bodySmall)
        // You can optionally add a static map image here for a better visual
    }
}

private fun formatTimestamp(date: Date?): String {
    if (date == null) return ""

    val messageCal = Calendar.getInstance().apply { time = date }
    val nowCal = Calendar.getInstance()
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    return when {
        nowCal.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) == messageCal.get(Calendar.DAY_OF_YEAR) -> timeFormat.format(date)
        nowCal.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) == messageCal.get(Calendar.DAY_OF_YEAR) + 1 -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
    }
}