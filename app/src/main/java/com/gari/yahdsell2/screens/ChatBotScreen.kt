package com.gari.yahdsell2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gari.yahdsell2.model.ChatbotMessage
import com.gari.yahdsell2.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatbotScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    bottomNavPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.chatbotMessages.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Column(modifier = modifier.padding(bottom = bottomNavPadding.calculateBottomPadding())) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(messages) { message ->
                ChatbotMessageBubble(message = message)
            }
        }
        ChatbotInput(onSendMessage = { viewModel.sendChatbotMessage(it) })
    }
}

@Composable
fun ChatbotInput(onSendMessage: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(shadowElevation = 8.dp, tonalElevation = 4.dp, modifier = Modifier.imePadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Ask Gemini...") },
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
fun ChatbotMessageBubble(message: ChatbotMessage) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = RoundedCornerShape(16.dp, 16.dp, if(isUser) 4.dp else 16.dp, if(isUser) 16.dp else 4.dp)

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = alignment) {
        Column(modifier = Modifier.widthIn(max = 280.dp).clip(shape).background(bubbleColor).padding(12.dp)) {
            if (message.isThinking) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
