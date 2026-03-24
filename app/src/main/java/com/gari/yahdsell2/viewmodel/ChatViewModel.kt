package com.gari.yahdsell2.viewmodel

import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gari.yahdsell2.model.ChatMessage
import com.gari.yahdsell2.model.ChatbotMessage
import com.gari.yahdsell2.model.PrivateChat
import com.gari.yahdsell2.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*
import javax.inject.Inject

// Data class to hold the combined chat and user information for the UI
data class ChatViewData(
    val chat: PrivateChat,
    val otherParticipant: UserProfile
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) : ViewModel() {

    // For ChatListScreen - Now exposes the combined data structure
    private val _chatList = MutableStateFlow<List<ChatViewData>>(emptyList())
    val chatList: StateFlow<List<ChatViewData>> = _chatList.asStateFlow()
    private val _isLoadingChatList = MutableStateFlow(false)
    val isLoadingChatList: StateFlow<Boolean> = _isLoadingChatList.asStateFlow()

    // For PrivateChatScreen
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private var messageListener: ListenerRegistration? = null
    private val _isUploadingMedia = MutableStateFlow(false)
    val isUploadingMedia: StateFlow<Boolean> = _isUploadingMedia.asStateFlow()

    // For ChatbotScreen
    private val _chatbotMessages = MutableStateFlow<List<ChatbotMessage>>(emptyList())
    val chatbotMessages: StateFlow<List<ChatbotMessage>> = _chatbotMessages.asStateFlow()

    fun fetchChatList() {
        val userId = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            _isLoadingChatList.value = true
            try {
                // 1. Fetch all chat documents where the current user is a participant
                val chatsSnapshot = firestore.collection("chats")
                    .whereArrayContains("participants", userId)
                    .orderBy("lastActivity", Query.Direction.DESCENDING)
                    .get()
                    .await()
                val chats = chatsSnapshot.documents.mapNotNull { it.toObject<PrivateChat>()?.copy(id = it.id) }

                if (chats.isEmpty()) {
                    _chatList.value = emptyList()
                    return@launch
                }

                // 2. Extract the IDs of the *other* participants
                val otherParticipantIds = chats.mapNotNull { chat ->
                    chat.participants.find { it != userId }
                }.distinct()

                if (otherParticipantIds.isEmpty()){
                    _chatList.value = emptyList()
                    return@launch
                }

                // 3. Fetch the UserProfile for all other participants in a single query
                val usersSnapshot = firestore.collection("users")
                    .whereIn("uid", otherParticipantIds)
                    .get()
                    .await()
                val userProfiles = usersSnapshot.toObjects(UserProfile::class.java).associateBy { it.uid }

                // 4. Combine the chat data with the participant's profile
                val chatViewDataList = chats.mapNotNull { chat ->
                    val otherId = chat.participants.find { it != userId }
                    userProfiles[otherId]?.let { otherUser ->
                        ChatViewData(
                            chat = chat,
                            otherParticipant = otherUser
                        )
                    }
                }
                _chatList.value = chatViewDataList

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error fetching chat list", e)
                _chatList.value = emptyList() // Clear list on error
            } finally {
                _isLoadingChatList.value = false
            }
        }
    }


    fun listenForMessages(chatId: String) {
        messageListener?.remove() // Remove previous listener
        messageListener = firestore.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("ChatViewModel", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    _messages.value = snapshot.toObjects(ChatMessage::class.java)
                }
            }
    }

    fun sendMessage(chatId: String, text: String) {
        val userId = auth.currentUser?.uid
        if (userId == null || text.isBlank()) return

        val message = ChatMessage(
            senderId = userId,
            text = text,
            type = "text",
            timestamp = null // Handled by server
        )
        postMessage(chatId, message, text)
    }

    fun sendImageMessage(chatId: String, uri: Uri, recipientId: String) {
        uploadMedia(uri, "images") { downloadUrl ->
            val message = ChatMessage(
                senderId = auth.currentUser!!.uid,
                imageUrl = downloadUrl,
                type = "image",
                text = "Image"
            )
            postMessage(chatId, message, "📷 Image", recipientId)
        }
    }

    fun sendVideoMessage(chatId: String, uri: Uri, recipientId: String) {
        uploadMedia(uri, "videos") { downloadUrl ->
            val message = ChatMessage(
                senderId = auth.currentUser!!.uid,
                videoUrl = downloadUrl,
                type = "video",
                text = "Video"
            )
            postMessage(chatId, message, "📹 Video", recipientId)
        }
    }

    fun sendLocationMessage(chatId: String, location: Location, recipientId: String) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        val message = ChatMessage(
            senderId = auth.currentUser!!.uid,
            location = geoPoint,
            type = "location",
            text = "Shared their location"
        )
        postMessage(chatId, message, "📍 Location", recipientId)
    }

    fun sendChatbotMessage(text: String) {
        // Implementation for sending a message to the chatbot
    }

    private fun uploadMedia(uri: Uri, folder: String, onComplete: (String) -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            _isUploadingMedia.value = true
            try {
                val ref = storage.reference.child("chat_media/$folder/${UUID.randomUUID()}")
                ref.putFile(uri).await()
                val downloadUrl = ref.downloadUrl.await().toString()
                onComplete(downloadUrl)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error uploading media", e)
            } finally {
                _isUploadingMedia.value = false
            }
        }
    }

    private fun postMessage(chatId: String, message: ChatMessage, lastMessageText: String, recipientId: String? = null) {
        val userId = auth.currentUser?.uid ?: return
        val finalChatId = if (recipientId != null) getChatId(userId, recipientId) else chatId

        val chatDocRef = firestore.collection("chats").document(finalChatId)

        viewModelScope.launch {
            try {
                val doc = chatDocRef.get().await()
                if (!doc.exists() && recipientId != null) {
                    val newChat = PrivateChat(
                        id = finalChatId,
                        participants = listOf(userId, recipientId),
                        lastMessage = lastMessageText,
                        lastActivity = null
                    )
                    chatDocRef.set(newChat).await()
                }

                chatDocRef.collection("messages").add(message).await()

                chatDocRef.update(
                    mapOf(
                        "lastMessage" to lastMessageText,
                        "lastActivity" to FieldValue.serverTimestamp()
                    )
                ).await()

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error posting message", e)
            }
        }
    }

    private fun getChatId(user1: String, user2: String): String {
        return if (user1 > user2) "$user1-$user2" else "$user2-$user1"
    }

    override fun onCleared() {
        super.onCleared()
        messageListener?.remove()
    }
}