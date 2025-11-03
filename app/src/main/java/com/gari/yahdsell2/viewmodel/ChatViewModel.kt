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
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val storage: FirebaseStorage
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isUploadingMedia = MutableStateFlow(false)
    val isUploadingMedia: StateFlow<Boolean> = _isUploadingMedia.asStateFlow()

    private val _chatList = MutableStateFlow<List<PrivateChat>>(emptyList())
    val chatList: StateFlow<List<PrivateChat>> = _chatList.asStateFlow()

    private val _isLoadingChatList = MutableStateFlow(false)
    val isLoadingChatList: StateFlow<Boolean> = _isLoadingChatList.asStateFlow()

    private val _chatbotMessages = MutableStateFlow<List<ChatbotMessage>>(listOf(
        ChatbotMessage(text = "Hello! How can I assist you with YahdSell today?", role = "model")
    ))
    val chatbotMessages: StateFlow<List<ChatbotMessage>> = _chatbotMessages.asStateFlow()

    private var chatListener: ListenerRegistration? = null

    private fun callApi(action: String, data: Map<String, Any?>): com.google.android.gms.tasks.Task<com.google.firebase.functions.HttpsCallableResult> {
        return functions.getHttpsCallable("publicApi").call(mapOf("action" to action, "data" to data))
    }

    fun fetchChatList() {
        val currentUser = auth.currentUser ?: return
        _isLoadingChatList.value = true
        viewModelScope.launch {
            try {
                val chatsSnapshot = firestore.collection("privateChats")
                    .whereArrayContains("participantIds", currentUser.uid)
                    .orderBy("lastActivity", Query.Direction.DESCENDING)
                    .get().await()

                val otherParticipantIds = chatsSnapshot.documents.mapNotNull { doc ->
                    doc.toObject<PrivateChat>()?.participantIds?.find { it != currentUser.uid }
                }.distinct()

                if (otherParticipantIds.isEmpty()) {
                    _chatList.value = emptyList()
                    _isLoadingChatList.value = false
                    return@launch
                }

                val usersSnapshot = firestore.collection("users")
                    .whereIn(FieldPath.documentId(), otherParticipantIds)
                    .get().await()

                val userProfiles = usersSnapshot.documents.mapNotNull { it.toObject<UserProfile>()?.copy(uid = it.id) }
                    .associateBy { it.uid }

                val chats = chatsSnapshot.documents.mapNotNull { doc ->
                    val chat = doc.toObject<PrivateChat>()?.copy(id = doc.id)
                    val otherId = chat?.participantIds?.find { it != currentUser.uid }
                    val otherProfile = userProfiles[otherId]
                    if (chat != null && otherProfile != null) {
                        chat.copy(participants = mapOf(otherId!! to otherProfile))
                    } else {
                        null
                    }
                }
                _chatList.value = chats
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error fetching chat list.", e)
                _chatList.value = emptyList()
            } finally {
                _isLoadingChatList.value = false
            }
        }
    }

    fun listenForMessages(recipientId: String) {
        val currentUser = auth.currentUser ?: return
        val chatId = listOf(currentUser.uid, recipientId).sorted().joinToString("_")
        chatListener?.remove()
        chatListener = firestore.collection("privateChats").document(chatId)
            .collection("messages").orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    _messages.value = snapshot.documents.mapNotNull { it.toObject<ChatMessage>()?.copy(id = it.id) }
                }
            }
    }

    fun sendMessage(recipientId: String, text: String) {
        val currentUser = auth.currentUser ?: return
        val message = ChatMessage(type = "text", text = text, senderId = currentUser.uid)
        sendMessageObject(recipientId, message, text)
    }

    fun sendImageMessage(recipientId: String, imageUri: Uri) {
        uploadMediaAndSendMessage(recipientId, imageUri, "chat_images", "image", "[Image]")
    }

    fun sendVideoMessage(recipientId: String, videoUri: Uri) {
        uploadMediaAndSendMessage(recipientId, videoUri, "chat_videos", "video", "[Video]")
    }

    fun sendLocationMessage(recipientId: String, location: Location) {
        val currentUser = auth.currentUser ?: return
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        val message = ChatMessage(
            type = "location",
            location = geoPoint,
            senderId = currentUser.uid
        )
        sendMessageObject(recipientId, message, "📍 Shared a location")
    }

    private fun uploadMediaAndSendMessage(recipientId: String, uri: Uri, folder: String, type: String, lastMessage: String) {
        val currentUser = auth.currentUser ?: return
        viewModelScope.launch {
            _isUploadingMedia.value = true
            try {
                val ref = storage.reference.child("$folder/${currentUser.uid}_${System.currentTimeMillis()}")
                ref.putFile(uri).await()
                val url = ref.downloadUrl.await().toString()
                val message = when (type) {
                    "image" -> ChatMessage(type = "image", imageUrl = url, senderId = currentUser.uid)
                    "video" -> ChatMessage(type = "video", videoUrl = url, senderId = currentUser.uid)
                    else -> throw IllegalArgumentException("Unsupported media type")
                }
                sendMessageObject(recipientId, message, lastMessage)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error uploading media", e)
            } finally {
                _isUploadingMedia.value = false
            }
        }
    }

    private fun sendMessageObject(recipientId: String, message: ChatMessage, lastMessageText: String) {
        val currentUser = auth.currentUser ?: return
        val chatId = listOf(currentUser.uid, recipientId).sorted().joinToString("_")
        viewModelScope.launch {
            try {
                firestore.collection("privateChats").document(chatId).collection("messages").add(message).await()
                firestore.collection("privateChats").document(chatId).set(
                    mapOf(
                        "participantIds" to listOf(currentUser.uid, recipientId),
                        "lastMessage" to lastMessageText,
                        "lastActivity" to FieldValue.serverTimestamp()
                    ), SetOptions.merge()
                ).await()

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message object", e)
            }
        }
    }

    fun sendChatbotMessage(prompt: String) {
        viewModelScope.launch {
            _chatbotMessages.value += ChatbotMessage(text = prompt, role = "user")
            _chatbotMessages.value += ChatbotMessage(text = "", role = "model", isThinking = true)
            try {
                val result = callApi("askGemini", mapOf("prompt" to prompt)).await()
                val reply = (result.data as? Map<String, Any?>)?.get("reply") as? String ?: "Sorry, I couldn't process that."
                val modelMessage = ChatbotMessage(text = reply, role = "model")
                _chatbotMessages.value = _chatbotMessages.value.dropLast(1) + modelMessage
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error in sendChatbotMessage", e)
                val errorMessage = ChatbotMessage(text = "Error: ${e.message}", role = "model")
                _chatbotMessages.value = _chatbotMessages.value.dropLast(1) + errorMessage
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatListener?.remove()
    }
}
