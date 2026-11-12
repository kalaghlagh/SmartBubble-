package com.example.data.db

import androidx.room.withTransaction

/**
 * The two deletions of the "/clear" command. Each runs in one Room transaction: either
 * everything is deleted or nothing is. Only the chat tables (conversations, chat_messages) are
 * touched; documents, templates, providers and API keys are never deleted here.
 */
class ConversationCleaner(private val database: SmartBubbleDatabase) {

    /**
     * Deletes every message of conversation [id] and leaves the conversation empty (title reset to
     * [emptyTitle], no preview). Other conversations are not touched.
     * @return the number of messages that were deleted
     */
    suspend fun clearConversation(id: String, emptyTitle: String): Int = database.withTransaction {
        val chatDao = database.chatDao()
        val conversationDao = database.conversationDao()
        val deleted = chatDao.getMessagesForSessionOnce(id).size
        chatDao.deleteMessagesBySessionId(id)
        val existing = conversationDao.getConversationById(id)
        if (existing != null) {
            conversationDao.updateConversation(
                existing.copy(title = emptyTitle, lastMessagePreview = "", updatedAt = System.currentTimeMillis())
            )
        }
        deleted
    }

    /**
     * Deletes all conversations and all chat messages.
     * @return the number of conversations that were deleted
     */
    suspend fun deleteAllConversations(): Int = database.withTransaction {
        val count = database.conversationDao().countConversations()
        database.chatDao().deleteAllMessages()
        database.conversationDao().deleteAllConversations()
        count
    }
}
