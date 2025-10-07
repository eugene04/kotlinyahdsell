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
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.UserState
import com.gari.yahdsell2.model.ChatMessage
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    recipientId: String?,
    recipientName: String?
) {
    val messages by viewModel.messages.collectAsState()
    val userState by viewModel.userState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isUploading by viewModel.isUploadingMedia.collectAsState()

    LaunchedEffect(recipientId) {
        if (recipientId != null) {
            viewModel.listenForMessages(recipientId)
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
            if (recipientId == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Recipient not found.") }
                return@Scaffold
            }

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
            if (recipientId != null) {
                ChatInputBar(
                    isUploading = isUploading,
                    onSendMessage = { text -> viewModel.sendMessage(recipientId, text) },
                    onSendImage = { uri -> viewModel.sendImageMessage(recipientId, uri) },
                    onSendVideo = { uri -> viewModel.sendVideoMessage(recipientId, uri) },
                    onSendLocation = { location -> viewModel.sendLocationMessage(recipientId, location) }
                )
            }
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
                    "image" -> MediaMessage(imageUrl = message.imageUrl, caption = message.text)
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

private fun formatTimestamp(date: Date?): String {
    if (date == null) return ""

    val messageCal = Calendar.getInstance().apply { time = date }
    val nowCal = Calendar.getInstance()
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    return when {
        nowCal.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) == messageCal.get(Calendar.DAY_OF_YEAR) -> "Today, ${timeFormat.format(date)}"
        nowCal.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) - 1 == messageCal.get(Calendar.DAY_OF_YEAR) -> "Yesterday, ${timeFormat.format(date)}"
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
    }
}


@Composable
fun MediaMessage(imageUrl: String?, caption: String) {
    Image(
        painter = rememberAsyncImagePainter(model = imageUrl ?: "https://placehold.co/400x300?text=Image"),
        contentDescription = "Sent image",
        modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp).clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Crop
    )
    if (caption.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(caption, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LocationBubble(location: GeoPoint?) {
    if (location == null) return
    val context = LocalContext.current
    val staticMapUrl = "https://maps.googleapis.com/maps/api/staticmap?center=${location.latitude},${location.longitude}&zoom=15&size=600x400&markers=color:red%7C${location.latitude},${location.longitude}&key=AIzaSyCB8UI6puDzs7NHrGN2eCHd2kdrqN8ieZc"

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = rememberAsyncImagePainter(model = staticMapUrl),
            contentDescription = "Shared location map",
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val gmmIntentUri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(Pinned Location)")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            context.startActivity(mapIntent)
        }) {
            Text("Open in Maps")
        }
    }
}


@Composable
fun VideoPlayerBubble(videoUrl: String?) {
    if (videoUrl == null) return
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp))
    )
}

