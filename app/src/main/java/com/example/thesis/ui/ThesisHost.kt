package com.example.thesis.ui

import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.thesis.ThesisController
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Thesis Builder workspace needs from the app (implemented by the main ViewModel).
 * Keeping it small lets the workspace be tested with the real [ThesisController] and repository.
 */
interface ThesisHost {
    val thesis: ThesisController
    /** Templates from My Templates (DOCX and PDF ones can be bound to a thesis). */
    val templateList: StateFlow<List<TemplateEntity>>
    /** App language, used as the default thesis content language. */
    val defaultContentLanguage: String
    /**
     * Result of the last operation (created, applied, exported, failed...). The workspace shows
     * it inside its own window: the app's main snackbar is hidden behind the full-screen dialog.
     */
    val feedback: StateFlow<String?>
    fun consumeFeedback()
    /** Closes the workspace and sends the following Chat messages to [projectId] ([sectionId] = target). */
    fun continueThesisInChat(projectId: String, sectionId: String?)
    fun openExportedDocument(document: DocumentEntity)
    fun shareExportedDocument(document: DocumentEntity)
    /** Default AI provider and model from Settings (the ones the AI tools use), or null when none is set. */
    suspend fun aiProviderInfo(): Pair<String, String>?
    /** Recent Chat messages (newest first) for "Insert from Chat". */
    suspend fun recentChatMessages(limit: Int = 30): List<ChatSnippet>
}

/** A Chat message offered by "Insert from Chat". */
data class ChatSnippet(val text: String, val fromUser: Boolean, val time: Long)
