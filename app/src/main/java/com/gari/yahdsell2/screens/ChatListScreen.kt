package com.gari.yahdsell2.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.gari.yahdsell2.navigation.Screen
import com.gari.yahdsell2.viewmodel.ChatViewModel
import com.gari.yahdsell2.viewmodel.ChatViewData
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel(),
    bottomNavPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val chatList by viewModel.chatList.collectAsState()
    val isLoading by viewModel.isLoadingChatList.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.fetchChatList()
    }

    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.fetchChatList()
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            pullToRefreshState.endRefresh()
        }
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
        Box(
            modifier = Modifier
                .nestedScroll(pullToRefreshState.nestedScrollConnection)
                .padding(bottom = bottomNavPadding.calculateBottomPadding())
                .padding(top = localScaffoldPadding.calculateTopPadding())
                .fillMaxSize()
        ) {
            if (chatList.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("You have no active chats yet. Pull down to refresh.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {                    items(chatList, key = { it.chat.id }) { chatViewData ->
                        ChatListItem(
                            chatData = chatViewData,
                            onClick = { 
                                navController.navigate(
                                    Screen.PrivateChat.createRoute(
                                        recipientId = chatViewData.otherParticipant.uid,
                                        recipientName = chatViewData.otherParticipant.displayName
                                    )
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }

            PullToRefreshContainer(
                state = pullToRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
fun ChatListItem(
    chatData: ChatViewData,
    onClick: () -> Unit
) {
    val lastMessageTimestamp = chatData.chat.lastActivity?.let {
        formatChatListTimestamp(it)
    } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = chatData.otherParticipant.profilePicUrl ?: "https://placehold.co/100x100?text=${chatData.otherParticipant.displayName.firstOrNull()}"
            ),
            contentDescription = "Avatar",
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chatData.otherParticipant.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = chatData.chat.lastMessage,
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
