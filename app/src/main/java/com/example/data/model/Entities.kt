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
    val isOrchestratorVerified: Boolean = true,
    /** Smart Templates: subcategory of the document ("letters.recommendation"); null for other documents. */
    val documentKind: String? = null,
    /** Validated structured content (JSON) the file was rendered from: lets it be rendered again with another template or as PDF without a new AI request. */
    val contentJson: String? = null,
    /** The request the document was generated from (for Regenerate). */
    val sourceRequest: String? = null,
    /** Language the document is written in ("ar", "fr", "en"). */
    val documentLanguage: String? = null
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fileType: String, // "DOCX", "PPTX", "XLSX"
    val originalFilePath: String,
    val profileJson: String,
    val fileSize: Long = 250880L, // in bytes
    val isDefaultWord: Boolean = false,
    val isDefaultPpt: Boolean = false,
    val isDefaultExcel: Boolean = false,
    val isDefaultPdf: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val compatibilityStatus: String = "Compatible", // "Compatible", "Partial", "Review"
    val thumbnailColorHex: String = "#24D6E8",
    /** Smart Templates category and subcategory chosen (or confirmed) by the user; null = not classified yet. */
    val categoryId: String? = null,
    val subcategoryId: String? = null
)

/**
 * Default template of a Smart Templates subcategory ("letters.recommendation") or main category
 * ("letters"). [templateId] is a built-in id ("builtin.letters.classic") or an uploaded template id.
 */
@Entity(tableName = "template_defaults")
data class TemplateDefaultEntity(
    @PrimaryKey val scopeId: String,
    val templateId: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/** A template the user marked as favorite (built-in or uploaded). */
@Entity(tableName = "template_favorites")
data class TemplateFavoriteEntity(
    @PrimaryKey val templateId: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "ai_providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val providerType: String, // "OpenAI", "Google Gemini", "Anthropic Claude", "OpenAI-compatible", "Custom"
    val baseUrl: String,
    val encryptedApiKey: String,
    val modelName: String,
    val isDefault: Boolean = false,
    val status: String = "Active", // "Active", "Connected (24ms)", "Error: ...", "Not Tested"
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

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastMessagePreview: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)
