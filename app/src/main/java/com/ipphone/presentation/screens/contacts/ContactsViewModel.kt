package com.ipphone.presentation.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.domain.model.User
import com.ipphone.domain.repository.MessageRepository
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.messaging.MessagingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactsUiState(
    val isLoading: Boolean = true,
    val contacts: List<User> = emptyList(),
    val lastMessages: Map<String, String> = emptyMap(),
    val typingUsers: Map<String, Boolean> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val messagingEngine: MessagingEngine
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()
    
    init {
        loadContacts()
        observeTypingEvents()
    }

    private fun observeTypingEvents() {
        viewModelScope.launch {
            messagingEngine.messageEvents.collect { event ->
                if (event is MessagingEngine.MessageEvent.TypingIndicator) {
                    _uiState.update { state ->
                        state.copy(
                            typingUsers = state.typingUsers.toMutableMap().apply {
                                if (event.isTyping) {
                                    put(event.peerId, true)
                                } else {
                                    remove(event.peerId)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    
    private fun loadContacts() {
        viewModelScope.launch {
            userRepository.getContacts()
                .collect { users ->
                    val lastMessages = users.associate { user ->
                        val lastMessage = messageRepository.getLastMessage(user.uuid)
                        user.uuid to (lastMessage?.content ?: "")
                    }
                    
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            contacts = users,
                            lastMessages = lastMessages
                        )
                    }
                }
        }
    }
    
    fun deleteUser(userId: String) {
        viewModelScope.launch {
            userRepository.deleteUser(userId)
        }
    }
    
    fun blockUser(userId: String) {
        viewModelScope.launch {
            userRepository.blockUser(userId)
        }
    }
    
    fun clearChatHistory(userId: String) {
        viewModelScope.launch {
            messageRepository.deleteAllForConversation(userId)
        }
    }
}
