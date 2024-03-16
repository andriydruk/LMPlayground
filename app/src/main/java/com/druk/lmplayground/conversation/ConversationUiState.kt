package com.druk.lmplayground.conversation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.toMutableStateList
import com.druk.lmplayground.R

class ConversationUiState(
    val channelName: String,
    val channelMembers: Int,
    initialMessages: List<Message>
) {
    private val _messages: MutableList<Message> = initialMessages.toMutableStateList()
    val messages: List<Message> = _messages

    fun addMessage(msg: Message) {
        _messages.add(msg) // Add to the end of the list
    }

    fun updateLastMessage(msg: String) {
        val message = _messages.last()
        _messages[_messages.size - 1] = message.copy(content = msg)
    }

    fun resetMessages() {
        _messages.clear()
    }
}

@Immutable
data class Message(
    val author: String,
    val content: String,
    val timestamp: String,
    val image: Int? = null,
    val authorImage: Int = if (author == "User")
        R.drawable.ic_baseline_person
    else
        R.drawable.penrose_triangle_monochrome
)
