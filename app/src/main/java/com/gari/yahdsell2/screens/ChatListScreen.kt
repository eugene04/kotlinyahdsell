package com.gari.yahdsell2.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.model.PrivateChat
import com.gari.yahdsell2.navigation.Screen
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    bottomNavPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val chatList by viewModel.chatList.collectAsState()
    val isLoading by viewModel.isLoadingChatList.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.fetchChatList()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("My Chats") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { localScaffoldPadding ->
        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing = isLoading),
            onRefresh = { viewModel.fetchChatList() },
            modifier = Modifier
                .padding(bottom = bottomNavPadding.calculateBottomPadding())
                .padding(top = localScaffoldPadding.calculateTopPadding())
                .fillMaxSize()
        ) {
            if (isLoading && chatList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (chatList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("You have no active chats yet.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(chatList) { chat ->
                        ChatListItem(
                            chat = chat,
                            onClick = { otherParticipant ->
                                navController.navigate(
                                    Screen.PrivateChat.createRoute(
                                        recipientId = otherParticipant.uid,
                                        recipientName = otherParticipant.displayName
                                    )
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun ChatListItem(
    chat: PrivateChat,
    onClick: (otherParticipant: com.gari.yahdsell2.model.UserProfile) -> Unit
) {
    if (chat.participants.values.isNotEmpty()) {
        val otherParticipant = chat.participants.values.first()
        val lastMessageTimestamp = chat.lastActivity?.let {
            formatChatListTimestamp(it)
        } ?: ""

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(otherParticipant) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = otherParticipant.profilePicUrl ?: "https://placehold.co/100x100?text=${otherParticipant.displayName.firstOrNull()}"
                ),
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = otherParticipant.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = chat.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = lastMessageTimestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatChatListTimestamp(date: Date): String {
    val now = Calendar.getInstance()
    val messageTime = Calendar.getInstance().apply { time = date }

    return if (now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == messageTime.get(Calendar.DAY_OF_YEAR)) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
    }
}

