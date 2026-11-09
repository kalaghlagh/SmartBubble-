package com.example.knowledge

import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.KnowledgeSourceEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class NotebookInfo(
    val id: String,
    val title: String,
    val sourceCount: Int,
    val lastSyncTime: String,
    val status: String = "Active"
)

class NotebookLmManager(private val database: SmartBubbleDatabase) {

    val knowledgeSources: Flow<List<KnowledgeSourceEntity>> =
        database.knowledgeDao().getAllKnowledgeSources()

    fun getSampleNotebooks(): List<NotebookInfo> {
        return listOf(
            NotebookInfo(
                id = "nb-1",
                title = "Enterprise Q3 Strategy & Marketing Notes",
                sourceCount = 18,
                lastSyncTime = "Just now",
                status = "Google Sync Active"
            ),
            NotebookInfo(
                id = "nb-2",
                title = "Legal Compliance & Licensing Vault",
                sourceCount = 9,
                lastSyncTime = "2 hours ago",
                status = "Synced"
            ),
            NotebookInfo(
                id = "nb-3",
                title = "Product Architecture & AI Orchestrator Specs",
                sourceCount = 14,
                lastSyncTime = "Yesterday",
                status = "Synced"
            )
        )
    }

    suspend fun addKnowledgeSource(
        name: String,
        type: String,
        uriOrUrl: String,
        extractedText: String,
        notebookId: String? = null
    ): KnowledgeSourceEntity {
        val entity = KnowledgeSourceEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            sourceType = type,
            uriOrUrl = uriOrUrl,
            extractedText = extractedText,
            notebookReference = notebookId ?: "Enterprise Q3 Strategy",
            processingStatus = "Synced"
        )
        database.knowledgeDao().insertKnowledgeSource(entity)
        return entity
    }

    suspend fun removeKnowledgeSource(entity: KnowledgeSourceEntity) {
        database.knowledgeDao().deleteKnowledgeSource(entity)
    }
}
