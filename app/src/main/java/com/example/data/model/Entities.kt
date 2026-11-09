package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val fileType: String, // "DOCX", "PPTX", "XLSX", "PDF"
    val filePath: String,
    val fileSize: Long,
    val wordCount: Int,
    val pageOrSlideCount: Int,
    val templateId: String? = null,
    val templateName: String? = null,
    val providerUsed: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val projectId: String? = null,
    val summary: String = "",
    val textContent: String = "",
    val isOrchestratorVerified: Boolean = true
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fileType: String, // "DOCX", "PPTX", "XLSX", "PDF"
    val originalFilePath: String,
    val profileJson: String,
    val isDefaultWord: Boolean = false,
    val isDefaultPpt: Boolean = false,
    val isDefaultExcel: Boolean = false,
    val isDefaultPdf: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val compatibilityStatus: String = "Compatible", // "Compatible", "Partial", "Review"
    val thumbnailColorHex: String = "#24D6E8"
)

@Entity(tableName = "knowledge_sources")
data class KnowledgeSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceType: String, // "PDF", "DOCX", "PPTX", "XLSX", "TXT", "WEB", "NOTEBOOKLM"
    val uriOrUrl: String,
    val extractedText: String,
    val notebookReference: String? = null,
    val processingStatus: String = "Synced", // "Processing", "Synced", "Failed"
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val isUser: Boolean,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachedDocId: String? = null,
    val attachedDocTitle: String? = null,
    val attachedDocType: String? = null,
    val orchestratorNote: String? = null
)
