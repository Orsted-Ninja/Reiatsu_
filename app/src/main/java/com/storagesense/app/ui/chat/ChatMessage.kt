package com.storagesense.app.ui.chat

import com.storagesense.app.domain.model.ActionProposal
import com.storagesense.app.domain.model.DuplicateGroup
import com.storagesense.app.domain.model.SearchResult
import java.util.UUID

enum class MessageSender {
    USER,
    ASSISTANT
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val searchResults: List<SearchResult> = emptyList(),
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val pendingAction: ActionProposal? = null,
    val isStreaming: Boolean = false,
    val timestampEpochMs: Long = System.currentTimeMillis()
)
