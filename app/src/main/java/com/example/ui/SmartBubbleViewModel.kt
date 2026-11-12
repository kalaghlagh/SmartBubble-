package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.R
import com.example.ai.orchestrator.AiProgressListener
import com.example.ai.orchestrator.AiProgressStage
import com.example.ai.orchestrator.CentralAiOrchestrator
import com.example.ai.orchestrator.ContentOperation
import com.example.ai.orchestrator.ContextMessage
import com.example.ai.orchestrator.ConversationContext
import com.example.ai.orchestrator.DocumentFormat
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.ai.providers.ProviderInput
import com.example.ai.providers.ProviderManager
import com.example.ai.providers.ProviderValidationException
import com.example.core.security.CredentialSecurityException
import com.example.core.security.CredentialStore
import com.example.core.delivery.DeliveryManager
import com.example.core.storage.DocumentFileNames
import com.example.core.storage.PreferencesManager
import com.example.core.storage.PreferencesSeedFlagStore
import com.example.core.storage.SampleDataSeeder
import com.example.core.voice.VoiceAssistantManager
import com.example.core.voice.VoiceError
import com.example.core.voice.VoiceState
import com.example.core.voice.VoiceText
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity
import com.example.template.analysis.TemplateAnalysisEngine
import com.example.template.analysis.TemplateFileInspector
import com.example.template.domain.TemplateProfile
import com.example.template.preview.TemplatePreviewEngine
import com.example.template.preview.TemplatePreviewResult
import com.example.ocr.DocumentOcrEngine
import com.example.ocr.OcrLanguage
import com.example.ocr.OcrUnavailableException
import com.example.ocr.PageImageLoader
import com.example.ocr.ScannedDocumentStructureReconstructor
import com.example.document.model.ScannedPageData
import com.example.document.model.ScannedDocumentReviewState
import com.example.document.docx.DocxEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

enum class AppNavTab {
    CHAT,
    TEMPLATES,
    LIBRARY,
    SETTINGS
}

class SmartBubbleViewModel(application: Application) : AndroidViewModel(application) {

    /** A template import problem with a localized explanation for the user. */
    private class TemplateImportException(val messageRes: Int) : Exception()

    private companion object {
        const val TAG = "SmartBubbleViewModel"

        /** DocumentEntity.providerUsed value of documents created by on-device OCR. */
        const val LOCAL_OCR_SOURCE = "OCR"
    }

    private val context = application.applicationContext
    val database = SmartBubbleDatabase.getDatabase(context)
    val preferences = PreferencesManager(context)
    val deliveryManager = DeliveryManager(context)
    private val credentialStore = CredentialStore(context)
    /** Provider management + single source of truth for the default provider (Room flag). */
    val providerManager = ProviderManager(database, credentialStore)
    val orchestrator = CentralAiOrchestrator(context, database, deliveryManager, providerManager)
    val templateAnalysisEngine = TemplateAnalysisEngine()
    val templatePreviewEngine = TemplatePreviewEngine()
    private val ocrEngine = DocumentOcrEngine(context)
    private val sampleDataSeeder = SampleDataSeeder(
        filesDir = context.filesDir,
        database = database,
        flagStore = PreferencesSeedFlagStore(preferences)
    )

    // Template Preview State
    private val _selectedPreviewTemplate = MutableStateFlow<TemplateEntity?>(null)
    val selectedPreviewTemplate: StateFlow<TemplateEntity?> = _selectedPreviewTemplate.asStateFlow()

    private val _previewResult = MutableStateFlow<TemplatePreviewResult?>(null)
    val previewResult: StateFlow<TemplatePreviewResult?> = _previewResult.asStateFlow()

    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading.asStateFlow()

    // Document Scanning & OCR Review State
    private val _scannedReviewState = MutableStateFlow<ScannedDocumentReviewState?>(null)
    val scannedReviewState: StateFlow<ScannedDocumentReviewState?> = _scannedReviewState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow<String?>(null)
    val scanProgress: StateFlow<String?> = _scanProgress.asStateFlow()

    // Navigation State
    private val _currentTab = MutableStateFlow(AppNavTab.CHAT)
    val currentTab: StateFlow<AppNavTab> = _currentTab.asStateFlow()

    // Active Chat Session / Conversation ID
    private val _currentSessionId = MutableStateFlow("session-smartbubble")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    // Persistent Conversations List
    val allConversations: StateFlow<List<ConversationEntity>> = database.conversationDao().getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dynamic Chat Messages for the currently active conversation
    @OptIn(ExperimentalCoroutinesApi::class)
    val chatMessages: StateFlow<List<ChatMessageEntity>> = _currentSessionId
        .flatMapLatest { sessionId ->
            database.chatDao().getMessagesForSession(sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDocuments = database.documentDao().getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTemplates = database.templateDao().getAllTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allProviders = database.providerDao().getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected template for chat attachment
    private val _selectedTemplateForChat = MutableStateFlow<TemplateEntity?>(null)
    val selectedTemplateForChat: StateFlow<TemplateEntity?> = _selectedTemplateForChat.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // ---- Active chat operation (one at a time) ------------------------------------------
    /** The running chat AI / document operation; cancelled by [cancelGeneration]. */
    private var chatJob: Job? = null

    /**
     * Identifies the current chat operation. Progress callbacks (which can arrive on background
     * threads) are ignored unless they belong to the operation that is still current, so a
     * cancelled operation can never update the UI later.
     */
    @Volatile
    private var chatOperationId = 0L

    private val _aiProgress = MutableStateFlow<AiProgressStage?>(null)
    /** Real stage of the running chat operation (null when none is running). */
    val aiProgress: StateFlow<AiProgressStage?> = _aiProgress.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    /** Reply text received so far from the provider for the running operation. */
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _generatingSessionId = MutableStateFlow<String?>(null)
    /** Conversation the running operation belongs to; its progress is shown only there. */
    val generatingSessionId: StateFlow<String?> = _generatingSessionId.asStateFlow()

    private val _snackBarMessage = MutableStateFlow<String?>(null)
    val snackBarMessage: StateFlow<String?> = _snackBarMessage.asStateFlow()

    // ---- Voice conversation -------------------------------------------------------------
    // Listener callbacks arrive on the main thread.
    private val voiceAssistant = VoiceAssistantManager(context, object : VoiceAssistantManager.Listener {
        override fun onTranscription(text: String) {
            // What the user said goes through the same orchestrator path as typed messages.
            if (_voiceConversationActive.value) executePrompt(text, fromVoice = true)
        }

        override fun onSpeechCompleted(messageId: String) {
            // The spoken reply finished: listen for the user's next turn.
            if (_voiceConversationActive.value && messageId == pendingVoiceReplyId) {
                pendingVoiceReplyId = null
                listenForVoiceTurn()
            }
        }

        override fun onVoiceError(error: VoiceError) {
            endVoiceConversation()
            _snackBarMessage.value = str(voiceErrorStringRes(error))
        }
    })

    private val _voiceConversationActive = MutableStateFlow(false)
    /** True from the mic tap until the user ends the conversation or an error ends it. */
    val voiceConversationActive: StateFlow<Boolean> = _voiceConversationActive.asStateFlow()

    /** Id of the assistant message being spoken as part of the voice conversation loop. */
    private var pendingVoiceReplyId: String? = null

    /** Raw engine state (used by the per-message player: speaking / paused). */
    val voiceState: StateFlow<VoiceState> = voiceAssistant.state
    val voicePartialText: StateFlow<String> = voiceAssistant.partialText
    val voiceInputLevel: StateFlow<Float> = voiceAssistant.inputLevel
    val speakingMessageId: StateFlow<String?> = voiceAssistant.speakingMessageId

    /** Voice language setting: "auto" (follow the app language), "ar", "en" or "fr". */
    val voiceLanguage: StateFlow<String> =
        preferences.voiceLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, VoiceText.AUTO)

    // Settings preferences
    /** Name of the default provider ("" when none), read from the ai_providers table. */
    val defaultProvider: StateFlow<String> = database.providerDao().getAllProviders()
        .map { list -> list.firstOrNull { it.isDefault }?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Ids of providers that have an API key stored (keys themselves never leave CredentialStore). */
    val providersWithKeys: StateFlow<Set<String>> = database.providerDao().getAllProviders()
        .map { list -> list.filter { providerManager.hasKey(it.id) }.map { it.id }.toSet() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _testingProviderIds = MutableStateFlow<Set<String>>(emptySet())
    val testingProviderIds: StateFlow<Set<String>> = _testingProviderIds.asStateFlow()

    /** Prevents duplicate AI requests from repeated taps or voice input while one is running. */
    private val aiRequestInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    val appLanguage = preferences.appLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, "en")
    val appTheme = preferences.appTheme.stateIn(viewModelScope, SharingStarted.Eagerly, "dark")

    /** What the voice conversation is doing, for the Chat UI. */
    val voicePhase: StateFlow<VoicePhase> =
        combine(voiceAssistant.state, _isGenerating, _voiceConversationActive) { engine, generating, active ->
            when {
                engine == VoiceState.LISTENING -> VoicePhase.LISTENING
                engine == VoiceState.RECOGNIZING -> VoicePhase.RECOGNIZING
                engine == VoiceState.SPEAKING -> VoicePhase.SPEAKING
                engine == VoiceState.PAUSED -> VoicePhase.PAUSED
                active && generating -> VoicePhase.THINKING
                else -> VoicePhase.IDLE
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, VoicePhase.IDLE)

    init {
        viewModelScope.launch {
            try {
                // One-time sample seeding + consistency maintenance, off the main thread.
                withContext(Dispatchers.IO) {
                    sampleDataSeeder.runStartupTasks()
                    PageImageLoader.purgeStaleScans(context)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Start-up data maintenance failed", e)
            }
            try {
                // Move legacy in-database keys to secure storage; drop fake keys and statuses.
                withContext(Dispatchers.IO) { providerManager.migrateLegacyProviderData() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Provider data migration failed", e)
            }
            restoreActiveConversation()
        }
    }

    private suspend fun restoreActiveConversation() {
        try {
            preferences.activeConversationId.firstOrNull()?.let { savedId ->
                if (savedId.isNotBlank()) {
                    val exists = database.conversationDao().getConversationById(savedId)
                    if (exists != null) {
                        _currentSessionId.value = savedId
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore active conversation", e)
        }
    }

    fun setNavTab(tab: AppNavTab) {
        _currentTab.value = tab
    }

    // Conversation Management Actions
    fun startNewConversation() {
        launchSafely("startNewConversation") {
            val newId = UUID.randomUUID().toString()
            val newConv = ConversationEntity(
                id = newId,
                title = str(R.string.new_conversation),
                lastMessagePreview = "",
                updatedAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis()
            )
            database.conversationDao().insertConversation(newConv)
            _currentSessionId.value = newId
            preferences.setActiveConversationId(newId)
        }
    }

    fun selectConversation(id: String) {
        launchSafely("selectConversation") {
            _currentSessionId.value = id
            preferences.setActiveConversationId(id)
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        launchSafely("renameConversation") {
            database.conversationDao().updateTitle(id, newTitle.trim())
            _snackBarMessage.value = str(R.string.msg_conversation_renamed)
        }
    }

    fun deleteConversation(id: String) {
        // An answer still being generated for this conversation has nowhere to go: stop it.
        if (_generatingSessionId.value == id && chatJob != null) {
            chatOperationId++
            chatJob?.cancel(CancellationException("Conversation deleted"))
            finishChatOperation()
            if (_voiceConversationActive.value) stopVoiceConversation()
        }
        launchSafely("deleteConversation") {
            database.conversationDao().deleteConversationById(id)
            database.chatDao().deleteMessagesBySessionId(id)
            _snackBarMessage.value = str(R.string.msg_conversation_deleted)

            if (_currentSessionId.value == id) {
                val remaining = database.conversationDao().getAllConversations().firstOrNull()
                val nextConv = remaining?.firstOrNull()
                if (nextConv != null) {
                    selectConversation(nextConv.id)
                } else {
                    startNewConversation()
                }
            }
        }
    }

    fun selectTemplateForChat(template: TemplateEntity?) {
        _selectedTemplateForChat.value = template
        if (template != null) {
            _snackBarMessage.value = str(R.string.msg_template_selected, template.name)
        }
    }

    /**
     * @param fromVoice the text is a voice transcription: the real reply is read aloud and the
     *   voice conversation continues (or ends, with the error shown, if the request fails).
     */
    fun executePrompt(prompt: String, forceFormat: String? = null, fromVoice: Boolean = false) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        val sessionId = _currentSessionId.value
        // One AI request at a time: ignore repeated taps while one is running.
        val started = launchChatOperation(sessionId) { listener ->
            try {
                // Ensure conversation exists in Room
                var conv = database.conversationDao().getConversationById(sessionId)
                if (conv == null) {
                    conv = ConversationEntity(
                        id = sessionId,
                        title = str(R.string.new_conversation),
                        lastMessagePreview = text.take(50),
                        updatedAt = System.currentTimeMillis()
                    )
                    database.conversationDao().insertConversation(conv)
                }

                // Auto-generate conversation title from the first meaningful request
                val genericTitles = listOf("New Conversation", "محادثة جديدة", "Nouvelle conversation", "New Chat")
                if (genericTitles.any { conv.title.equals(it, ignoreCase = true) }) {
                    val firstLine = text.lineSequence().firstOrNull()?.trim() ?: text
                    val autoTitle = if (firstLine.length > 32) firstLine.take(32) + "…" else firstLine
                    database.conversationDao().updateTitle(sessionId, autoTitle)
                }

                // Conversation context = messages stored BEFORE this one (restored after restart too).
                val history = buildContextHistory(sessionId)

                database.conversationDao().updateLastMessage(sessionId, text.take(60))
                database.chatDao().insertMessage(
                    ChatMessageEntity(id = UUID.randomUUID().toString(), sessionId = sessionId, isUser = true, content = text)
                )

                val result = orchestrator.handleChatTurn(
                    history = history,
                    userMessage = text,
                    uiLanguage = appLanguage.value,
                    selectedTemplate = _selectedTemplateForChat.value,
                    forceFormat = DocumentFormat.parse(forceFormat),
                    listener = listener
                )

                // Only the provider's real answer is stored; failures are never saved as replies.
                // A cancelled operation stops here and stores nothing.
                ensureActive()
                val doc = result.document
                val replyId = UUID.randomUUID().toString()
                database.chatDao().insertMessage(
                    ChatMessageEntity(
                        id = replyId,
                        sessionId = sessionId,
                        isUser = false,
                        content = result.reply,
                        attachedDocId = doc?.id,
                        attachedDocTitle = doc?.title,
                        attachedDocType = doc?.fileType,
                        orchestratorNote = when {
                            doc == null -> result.provider.name
                            result.templateApplied -> "Template Preserved • ${result.provider.name}"
                            else -> "Orchestrator Verified • ${result.provider.name}"
                        }
                    )
                )
                database.conversationDao().updateLastMessage(sessionId, result.reply.take(60))
                if (doc != null) _snackBarMessage.value = str(R.string.msg_generated_copied)
                // Voice turn: read the provider's actual reply aloud, then listen again.
                if (fromVoice && _voiceConversationActive.value) {
                    pendingVoiceReplyId = replyId
                    speakMessage(replyId, result.reply)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                Log.w(TAG, "AI request failed: ${e.kind}")
                // Nothing is spoken for a failed request; the error is shown instead.
                if (fromVoice) endVoiceConversation()
                _snackBarMessage.value = aiErrorMessage(e, defaultProvider.value)
            } catch (e: Exception) {
                if (fromVoice) endVoiceConversation()
                reportError("executePrompt", e)
            }
        }
        if (!started && fromVoice) {
            endVoiceConversation()
            _snackBarMessage.value = str(R.string.voice_busy)
        }
    }

    /**
     * Starts the single chat operation for [sessionId] (returns false if one is already running).
     * The loading state, progress and streamed text are released when it ends, fails or is
     * cancelled; [block] receives the listener that forwards real progress to the UI.
     */
    private fun launchChatOperation(
        sessionId: String,
        block: suspend CoroutineScope.(AiProgressListener) -> Unit
    ): Boolean {
        if (!aiRequestInFlight.compareAndSet(false, true)) return false
        val operationId = ++chatOperationId
        _isGenerating.value = true
        _generatingSessionId.value = sessionId
        _aiProgress.value = null
        _streamingReply.value = ""
        val listener = object : AiProgressListener {
            override fun onStage(stage: AiProgressStage) {
                if (chatOperationId == operationId) _aiProgress.value = stage
            }

            override fun onPartialReply(text: String) {
                if (chatOperationId == operationId) _streamingReply.value = text
            }
        }
        // Started only after chatJob is set: viewModelScope runs on Main.immediate, so an eager
        // start could reach the finally block before the assignment.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(listener)
            } finally {
                // Always release the loading state (also after an error); a cancelled operation
                // was already released by cancelGeneration and must not touch a newer one.
                if (chatOperationId == operationId) finishChatOperation()
            }
        }
        chatJob = job
        job.start()
        return true
    }

    private fun finishChatOperation() {
        chatJob = null
        _isGenerating.value = false
        _generatingSessionId.value = null
        _aiProgress.value = null
        _streamingReply.value = ""
        aiRequestInFlight.set(false)
    }

    /**
     * Stop button: cancels the running chat operation. The in-flight HTTP request is cancelled
     * (closing a streamed answer), no further provider call or document step starts, an
     * unfinished document file is deleted and nothing of the cancelled operation is stored.
     * The chat is usable again immediately.
     */
    fun cancelGeneration() {
        val job = chatJob ?: return
        chatOperationId++ // ignore any late progress of the cancelled operation
        job.cancel(CancellationException("Stopped by the user"))
        finishChatOperation()
        // A voice turn waiting for this answer cannot continue.
        if (_voiceConversationActive.value) stopVoiceConversation()
        _snackBarMessage.value = str(R.string.chat_generation_stopped)
    }

    /**
     * Stored messages of [sessionId] as AI context. The text of an attached document is included
     * once (on its most recent mention) so the user can ask about generated/scanned documents.
     */
    private suspend fun buildContextHistory(sessionId: String): List<ContextMessage> {
        // Only the most recent messages can fit in the context; don't load the whole history.
        val messages = database.chatDao().getRecentMessagesForSession(sessionId, ConversationContext.MAX_MESSAGES)
        val seenDocs = mutableSetOf<String>()
        val out = ArrayList<ContextMessage>(messages.size)
        for (m in messages.asReversed()) {
            val docId = m.attachedDocId
            val docText = if (docId != null && seenDocs.add(docId)) {
                // Scanned documents stay on the device: their text is only sent to the provider
                // when the user explicitly runs an AI action on them.
                database.documentDao().getDocumentById(docId)
                    ?.takeIf { it.providerUsed != LOCAL_OCR_SOURCE }?.textContent
            } else null
            out += ContextMessage(m.isUser, m.content, docText, m.attachedDocTitle)
        }
        out.reverse()
        return out
    }

    private fun mimeTypeFor(doc: DocumentEntity): String = when (doc.fileType) {
        "DOCX" -> DeliveryManager.MIME_DOCX
        "PPTX" -> DeliveryManager.MIME_PPTX
        "XLSX" -> DeliveryManager.MIME_XLSX
        else -> DeliveryManager.MIME_PDF
    }

    fun openDocument(doc: DocumentEntity) {
        val file = File(doc.filePath)
        if (!file.isFile) {
            _snackBarMessage.value = str(R.string.msg_file_not_found)
            return
        }
        deliveryManager.openFile(file, mimeTypeFor(doc))
    }

    fun shareDocument(doc: DocumentEntity) {
        val file = File(doc.filePath)
        if (!file.isFile) {
            _snackBarMessage.value = str(R.string.msg_file_not_found)
            return
        }
        deliveryManager.shareFile(file, mimeTypeFor(doc), doc.title)
    }

    fun exportDocumentAsPdf(doc: DocumentEntity) {
        if (doc.fileType == "PDF") {
            openDocument(doc)
            return
        }
        viewModelScope.launch {
            try {
                val pdfFile = withContext(Dispatchers.IO) {
                    val title = DocumentFileNames.stripDocumentExtension(doc.title)
                        .ifBlank { DocumentFileNames.displayTitle(doc.title) }
                    // Keep paragraph breaks so long content is laid out (and paginated) per paragraph.
                    val paragraphs = doc.textContent
                        .split(Regex("""\n\s*\n"""))
                        .map { it.trim('\n', '\r') }
                        .filter { it.isNotBlank() }
                        .ifEmpty { listOf(doc.textContent) }
                    val wordModel = com.example.document.model.WordDocumentModel(
                        title = title,
                        subtitle = "Exported from SmartBubble Library",
                        sections = listOf(
                            com.example.document.model.WordSection(
                                heading = "Document Content",
                                paragraphs = paragraphs
                            )
                        )
                    )
                    // The export file name is sanitized separately from the visible title (which
                    // may contain "/" or other characters invalid on the filesystem).
                    val exportDir = File(context.cacheDir, "pdf_exports").apply { mkdirs() }
                    val target = File(exportDir, DocumentFileNames.pdfExportFileName(doc.title, doc.id))
                    val partial = File(exportDir, target.name + ".part")
                    try {
                        com.example.document.pdf.PdfEngine()
                            .generatePdf(wordModel, partial, TemplateProfile.defaultProfile())
                        if (!partial.isFile || partial.length() == 0L) {
                            throw IllegalStateException("Generated PDF is empty.")
                        }
                        if (target.exists()) target.delete()
                        if (!partial.renameTo(target)) {
                            partial.copyTo(target, overwrite = true)
                        }
                    } finally {
                        if (partial.exists()) partial.delete()
                    }
                    target
                }
                deliveryManager.openFile(pdfFile, DeliveryManager.MIME_PDF)
                _snackBarMessage.value = "PDF Export ready & opened."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "exportDocumentAsPdf failed", e)
                _snackBarMessage.value = str(
                    R.string.msg_pdf_export_failed,
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    fun renameDocument(doc: DocumentEntity, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            try {
                // Only the visible title changes; the physical file name stays independent.
                val title = DocumentFileNames.displayTitle(newTitle)
                database.documentDao().updateTitle(doc.id, title)
                _snackBarMessage.value = str(R.string.msg_document_renamed, title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("renameDocument", e)
            }
        }
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dao = database.documentDao()
                    dao.deleteDocument(doc)
                    // Older versions could point several Library entries at one shared file;
                    // only delete the file once no other entry references it.
                    if (doc.filePath.isNotBlank() && dao.countDocumentsWithPath(doc.filePath) == 0) {
                        File(doc.filePath).delete()
                    }
                    // Remove this document's cached PDF export too.
                    val exportSuffix = "_" + DocumentFileNames.pdfExportFileName("", doc.id).substringAfterLast('_')
                    File(context.cacheDir, "pdf_exports").listFiles()
                        ?.filter { it.name.endsWith(exportSuffix) }?.forEach { it.delete() }
                }
                _snackBarMessage.value = str(R.string.msg_document_deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("deleteDocument", e)
            }
        }
    }

    fun importTemplateFromUri(uri: Uri, userDefinedName: String) {
        viewModelScope.launch {
            var persistentTemplateFile: File? = null
            var saved = false
            try {
                // Copying and analysing the file is blocking I/O: keep it off the main thread.
                val (file, profile) = withContext(Dispatchers.IO) {
                    val templateDir = File(context.filesDir, "templates").apply { mkdirs() }
                    val staging = File(templateDir, "import_${UUID.randomUUID()}.tmp")
                    persistentTemplateFile = staging

                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw TemplateImportException(R.string.template_import_unreadable)
                    // Bounded copy: stop as soon as the size limit is exceeded.
                    input.use { stream ->
                        FileOutputStream(staging).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > TemplateFileInspector.MAX_FILE_BYTES) {
                                    throw TemplateImportException(R.string.template_import_too_large)
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    // The type comes from the file content, not from its name or MIME type.
                    val type = when (val result = TemplateFileInspector.inspect(staging)) {
                        is TemplateFileInspector.Result.Valid -> result.type
                        TemplateFileInspector.Result.TooLarge ->
                            throw TemplateImportException(R.string.template_import_too_large)
                        TemplateFileInspector.Result.NotOfficeDocument ->
                            throw TemplateImportException(R.string.template_import_unsupported)
                    }
                    val target = File(templateDir, "template_${UUID.randomUUID()}.${type.extension}")
                    if (!staging.renameTo(target)) throw java.io.IOException("Could not store the template file.")
                    persistentTemplateFile = target
                    target to templateAnalysisEngine.analyzeFile(target, userDefinedName)
                }
                val templateEntity = TemplateEntity(
                    id = profile.templateId,
                    name = profile.name,
                    fileType = profile.originalFileType,
                    originalFilePath = file.absolutePath,
                    fileSize = file.length(),
                    profileJson = profile.toJson(),
                    compatibilityStatus = "Compatible",
                    thumbnailColorHex = profile.primaryColorHex
                )
                database.templateDao().insertTemplate(templateEntity)
                saved = true
                _snackBarMessage.value = str(R.string.msg_template_imported, profile.name)
            } catch (e: CancellationException) {
                if (!saved) withContext(NonCancellable + Dispatchers.IO) { persistentTemplateFile?.delete() }
                throw e
            } catch (e: TemplateImportException) {
                if (!saved) withContext(Dispatchers.IO) { persistentTemplateFile?.delete() }
                _snackBarMessage.value = str(e.messageRes)
            } catch (e: Exception) {
                Log.e(TAG, "importTemplateFromUri failed", e)
                // Do not leave an orphaned partial copy behind.
                if (!saved) withContext(Dispatchers.IO) { persistentTemplateFile?.delete() }
                _snackBarMessage.value = "Template import failed: ${e.message}"
            }
        }
    }

    private val isUpdatingDefault = java.util.concurrent.atomic.AtomicBoolean(false)

    fun deleteTemplate(template: TemplateEntity) {
        launchSafely("deleteTemplate") {
            if (_selectedTemplateForChat.value?.id == template.id) {
                _selectedTemplateForChat.value = null
            }
            // Remove the record first, then its file (off the main thread).
            database.templateDao().deleteTemplate(template)
            if (template.originalFilePath.isNotBlank()) {
                withContext(Dispatchers.IO) { File(template.originalFilePath).delete() }
            }
            _snackBarMessage.value = str(R.string.msg_template_deleted)
        }
    }

    fun setDefaultTemplate(template: TemplateEntity) {
        if (!isUpdatingDefault.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val dao = database.templateDao()
                // Clear + set in one transaction so a type can never end up with two defaults.
                // Targeted flag updates avoid overwriting other fields with a stale UI snapshot.
                database.withTransaction {
                    when (template.fileType.uppercase()) {
                        "PPTX" -> {
                            dao.clearDefaultPpt()
                            dao.setDefaultPptFlag(template.id, true)
                        }
                        "XLSX" -> {
                            dao.clearDefaultExcel()
                            dao.setDefaultExcelFlag(template.id, true)
                        }
                        else -> { // "DOCX" and any other type (unchanged behaviour)
                            dao.clearDefaultWord()
                            dao.setDefaultWordFlag(template.id, true)
                        }
                    }
                }
                _snackBarMessage.value = str(R.string.msg_template_set_default, template.name, template.fileType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("setDefaultTemplate", e)
            } finally {
                isUpdatingDefault.set(false)
            }
        }
    }

    fun removeDefaultTemplate(template: TemplateEntity) {
        if (!isUpdatingDefault.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val dao = database.templateDao()
                when (template.fileType.uppercase()) {
                    "PPTX" -> dao.setDefaultPptFlag(template.id, false)
                    "XLSX" -> dao.setDefaultExcelFlag(template.id, false)
                    else -> dao.setDefaultWordFlag(template.id, false)
                }
                _snackBarMessage.value = str(R.string.msg_template_removed_default, template.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("removeDefaultTemplate", e)
            } finally {
                isUpdatingDefault.set(false)
            }
        }
    }

    // Template Preview Actions
    fun openTemplatePreview(template: TemplateEntity) {
        _selectedPreviewTemplate.value = template
        _isPreviewLoading.value = true
        _previewResult.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                templatePreviewEngine.loadPreview(template)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "openTemplatePreview failed", e)
                TemplatePreviewResult.Error(e.message ?: e.javaClass.simpleName)
            }
            // Ignore a stale result if the user closed or switched the preview meanwhile.
            if (_selectedPreviewTemplate.value?.id == template.id) {
                _previewResult.value = result
                _isPreviewLoading.value = false
            }
        }
    }

    fun closeTemplatePreview() {
        _selectedPreviewTemplate.value = null
        _previewResult.value = null
        _isPreviewLoading.value = false
    }

    // Duplicate Template Action
    fun duplicateTemplate(template: TemplateEntity, customName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newId = "tmpl-dup-" + UUID.randomUUID().toString().take(8)
                val resolvedName = if (!customName.isNullOrBlank()) {
                    customName.trim()
                } else {
                    str(R.string.template_duplicate_name_format, template.name)
                }

                val templateDir = File(context.filesDir, "templates").apply { mkdirs() }
                val ext = when (template.fileType.uppercase()) {
                    "DOCX" -> "docx"
                    "PPTX" -> "pptx"
                    "XLSX" -> "xlsx"
                    else -> "docx"
                }
                val newFile = File(templateDir, "template_${newId}.$ext")

                // Copy original file bytes to ensure complete independence
                val origFile = File(template.originalFilePath)
                if (origFile.exists()) {
                    origFile.inputStream().use { input ->
                        FileOutputStream(newFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    newFile.writeBytes(ByteArray(0))
                }

                // Update profile json if valid
                val newProfileJson = try {
                    if (template.profileJson.isNotBlank()) {
                        val profile = TemplateProfile.fromJson(template.profileJson)
                        profile.copy(templateId = newId, name = resolvedName).toJson()
                    } else {
                        TemplateProfile.defaultProfile().copy(
                            templateId = newId,
                            name = resolvedName,
                            originalFileType = template.fileType
                        ).toJson()
                    }
                } catch (e: Exception) {
                    template.profileJson
                }

                val duplicatedEntity = TemplateEntity(
                    id = newId,
                    name = resolvedName,
                    fileType = template.fileType,
                    originalFilePath = newFile.absolutePath,
                    fileSize = if (newFile.exists()) newFile.length() else template.fileSize,
                    profileJson = newProfileJson,
                    isDefaultWord = false,
                    isDefaultPpt = false,
                    isDefaultExcel = false,
                    isDefaultPdf = false,
                    compatibilityStatus = template.compatibilityStatus,
                    thumbnailColorHex = template.thumbnailColorHex,
                    createdAt = System.currentTimeMillis(),
                    lastModified = System.currentTimeMillis(),
                    version = 1
                )

                database.templateDao().insertTemplate(duplicatedEntity)
                _snackBarMessage.value = str(R.string.msg_template_duplicated, resolvedName)
            } catch (e: Exception) {
                _snackBarMessage.value = "Failed to duplicate template: ${e.message}"
            }
        }
    }

    /**
     * Saves the generation settings of a template (stored in its profile; the original template
     * file is never changed).
     */
    fun updateTemplateOptions(template: TemplateEntity, useTemplateLayout: Boolean, keepTemplateContent: Boolean) {
        launchSafely("updateTemplateOptions") {
            val dao = database.templateDao()
            val current = dao.getTemplateById(template.id) ?: return@launchSafely
            val profile = TemplateProfile.fromJson(current.profileJson).copy(
                useTemplateLayout = useTemplateLayout,
                keepTemplateContent = useTemplateLayout && keepTemplateContent
            )
            dao.updateProfileJson(template.id, profile.toJson())
            // Keep the template attached to the chat in sync with the saved settings.
            if (_selectedTemplateForChat.value?.id == template.id) {
                _selectedTemplateForChat.value = dao.getTemplateById(template.id)
            }
            _snackBarMessage.value = str(R.string.msg_template_options_saved)
        }
    }

    // ------------------------------------------------------------------ voice conversation

    /**
     * Starts a two-way voice conversation: listen -> transcribe -> default AI provider ->
     * read the reply aloud -> listen again, until the user ends it or an error occurs.
     * The caller must have been granted RECORD_AUDIO.
     */
    fun startVoiceConversation() {
        if (aiRequestInFlight.get()) {
            _snackBarMessage.value = str(R.string.voice_busy)
            return
        }
        _voiceConversationActive.value = true
        pendingVoiceReplyId = null
        listenForVoiceTurn()
    }

    /** Ends the conversation: closes the microphone (discarding unsent speech) and stops speech. */
    fun stopVoiceConversation() {
        endVoiceConversation()
        voiceAssistant.cancelListening()
        voiceAssistant.stopSpeaking()
    }

    private fun endVoiceConversation() {
        _voiceConversationActive.value = false
        pendingVoiceReplyId = null
    }

    private fun listenForVoiceTurn() {
        val language = VoiceText.effectiveLanguage(voiceLanguage.value, appLanguage.value)
        voiceAssistant.startListening(VoiceText.recognitionTag(language, Locale.getDefault()))
    }

    fun onMicrophonePermissionDenied() {
        endVoiceConversation()
        _snackBarMessage.value = str(R.string.voice_err_permission)
    }

    /** Called when the app leaves the foreground: the mic is closed and speech stops. */
    fun onAppBackgrounded() {
        stopVoiceConversation()
    }

    fun setVoiceLanguage(language: String) {
        launchSafely("setVoiceLanguage") { preferences.setVoiceLanguage(language) }
    }

    /** Reads [text] with a voice matching the language of the text itself. */
    private fun speakMessage(messageId: String, text: String) {
        val language = VoiceText.detectLanguage(text, appLanguage.value)
        voiceAssistant.speak(messageId, text, VoiceText.speechLocales(language, Locale.getDefault()))
    }

    /** Per-message player: play, or pause/resume when this message is the one being read. */
    fun toggleMessageSpeech(messageId: String, text: String) {
        if (speakingMessageId.value == messageId) {
            when (voiceState.value) {
                VoiceState.SPEAKING -> voiceAssistant.pauseSpeaking()
                VoiceState.PAUSED -> voiceAssistant.resumeSpeaking()
                else -> speakMessage(messageId, text)
            }
        } else {
            // Reading another message on request takes over from the conversation loop.
            if (pendingVoiceReplyId != null) endVoiceConversation()
            speakMessage(messageId, text)
        }
    }

    /** Stops reading. Stopping the conversation's spoken reply also ends the conversation. */
    fun stopSpeaking() {
        if (pendingVoiceReplyId != null) endVoiceConversation()
        voiceAssistant.stopSpeaking()
    }

    // AI Providers Management
    fun setDefaultProvider(provider: ProviderEntity) {
        launchSafely("setDefaultProvider") {
            providerManager.setDefault(provider.id)
            _snackBarMessage.value = str(R.string.msg_provider_set_default, provider.name)
        }
    }

    /** Real connection test: an authenticated request that also verifies the configured model. */
    fun testProviderConnection(provider: ProviderEntity) {
        if (provider.id in _testingProviderIds.value) return
        viewModelScope.launch {
            _testingProviderIds.value = _testingProviderIds.value + provider.id
            try {
                val latency = providerManager.testConnection(provider)
                _snackBarMessage.value = str(R.string.provider_test_success, provider.name, latency)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _snackBarMessage.value = str(R.string.provider_test_failed, provider.name, aiErrorReason(e))
            } catch (e: Exception) {
                reportError("testProviderConnection", e)
            } finally {
                _testingProviderIds.value = _testingProviderIds.value - provider.id
            }
        }
    }

    /** Adds a provider; the API key is encrypted before anything is stored. */
    fun addProvider(input: ProviderInput) {
        launchSafely("addProvider") {
            try {
                val created = providerManager.addProvider(input)
                _snackBarMessage.value = if (created.isDefault) {
                    str(R.string.msg_provider_added_default, created.name)
                } else {
                    str(R.string.msg_provider_added, created.name)
                }
            } catch (e: ProviderValidationException) {
                _snackBarMessage.value = str(R.string.provider_error_invalid_fields)
            } catch (e: CredentialSecurityException) {
                _snackBarMessage.value = str(R.string.provider_error_secure_storage)
            }
        }
    }

    /** Updates a provider; a blank API key keeps the stored key. */
    fun updateProvider(providerId: String, input: ProviderInput) {
        launchSafely("updateProvider") {
            try {
                val updated = providerManager.updateProvider(providerId, input)
                _snackBarMessage.value = str(R.string.msg_provider_updated, updated.name)
            } catch (e: ProviderValidationException) {
                _snackBarMessage.value = str(R.string.provider_error_invalid_fields)
            } catch (e: CredentialSecurityException) {
                _snackBarMessage.value = str(R.string.provider_error_secure_storage)
            }
        }
    }

    /** Deletes the provider and its stored credential. The default is never reassigned silently. */
    fun deleteProvider(provider: ProviderEntity) {
        launchSafely("deleteProvider") {
            providerManager.deleteProvider(provider)
            _snackBarMessage.value = if (provider.isDefault) {
                str(R.string.msg_provider_deleted_was_default)
            } else {
                str(R.string.msg_provider_deleted)
            }
        }
    }

    // Settings
    fun setAppLanguage(lang: String) {
        launchSafely("setAppLanguage") {
            preferences.setAppLanguage(lang)
        }
    }

    fun setAppTheme(theme: String) {
        launchSafely("setAppTheme") {
            preferences.setAppTheme(theme)
        }
    }

    // Document Scanning & OCR Workflows

    /** Language used to read scanned pages ("auto", "ar", "en", "fr"). */
    val ocrLanguage: StateFlow<String> =
        preferences.ocrLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, OcrLanguage.AUTO.code)

    private var scanJob: Job? = null

    /**
     * Recognizes the scanned / picked page images ON THE DEVICE (Tesseract). Nothing is sent to
     * any AI provider. When a review is already open, the new pages are appended to it.
     */
    fun processScannedPages(uris: List<Uri>) {
        if (uris.isEmpty() || _isScanning.value) return
        val existing = _scannedReviewState.value
        scanJob = viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = str(R.string.recognizing_text)
            try {
                val result = ocrEngine.processPages(
                    pageUris = uris,
                    language = OcrLanguage.fromCode(ocrLanguage.value),
                    appLanguage = appLanguage.value,
                    firstPageIndex = existing?.pages?.size ?: 0,
                    onProgress = { page, total -> _scanProgress.value = str(R.string.scan_progress_page, page, total) }
                )
                val current = _scannedReviewState.value
                if (current != null) {
                    // "Add page": keep the reviewed pages (and the user's corrections).
                    _scannedReviewState.value = current.copy(
                        pages = current.pages + result.pages,
                        activePageIndex = current.pages.size
                    )
                } else {
                    val autoTitle = result.pages.firstOrNull()?.recognizedTitle
                        ?.takeIf { it.isNotBlank() && !it.startsWith("Page ") }
                        ?: ("Document_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(java.util.Date()))
                    _scannedReviewState.value = ScannedDocumentReviewState(
                        documentTitle = autoTitle,
                        pages = result.pages,
                        activePageIndex = 0,
                        selectedTemplateId = null,
                        isAiEnhanced = false
                    )
                }
                reportScanOutcome(result.pages, result.failedPages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrUnavailableException) {
                Log.e(TAG, "OCR unavailable", e)
                _snackBarMessage.value = str(R.string.scan_ocr_unavailable)
            } catch (e: Exception) {
                reportError("processScannedPages", e)
            } finally {
                _isScanning.value = false
                _scanProgress.value = null
            }
        }
    }

    private fun reportScanOutcome(pages: List<ScannedPageData>, failedPages: Int) {
        val empty = pages.count { it.rawText.isBlank() }
        _snackBarMessage.value = when {
            pages.isNotEmpty() && empty == pages.size -> str(R.string.scan_empty_error)
            failedPages > 0 -> str(R.string.scan_pages_failed, failedPages)
            empty > 0 -> str(R.string.scan_pages_empty, empty)
            else -> return
        }
    }

    /**
     * Changes the OCR language and reads every page again with it. Text corrections made so far
     * are replaced by the new recognition result.
     */
    fun changeOcrLanguage(code: String) {
        val current = _scannedReviewState.value
        if (_isScanning.value || code == ocrLanguage.value) return
        scanJob = viewModelScope.launch {
            preferences.setOcrLanguage(code)
            if (current == null || current.pages.isEmpty()) return@launch
            _isScanning.value = true
            try {
                val result = ocrEngine.processPages(
                    pageUris = current.pages.map { Uri.parse(it.imageUriString) },
                    language = OcrLanguage.fromCode(code),
                    appLanguage = appLanguage.value,
                    onProgress = { page, total -> _scanProgress.value = str(R.string.scan_progress_page, page, total) }
                )
                _scannedReviewState.value?.let { state ->
                    _scannedReviewState.value = state.copy(pages = result.pages, isAiEnhanced = false)
                }
                reportScanOutcome(result.pages, result.failedPages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrUnavailableException) {
                Log.e(TAG, "OCR unavailable", e)
                _snackBarMessage.value = str(R.string.scan_ocr_unavailable)
            } catch (e: Exception) {
                reportError("changeOcrLanguage", e)
            } finally {
                _isScanning.value = false
                _scanProgress.value = null
            }
        }
    }

    /**
     * Optional AI clean-up of the recognized TEXT (never the images) by the default provider.
     * Only called after the user confirmed the consent dialog on the review screen.
     */
    fun enhanceScannedTextWithAi() {
        val state = _scannedReviewState.value ?: return
        if (_isScanning.value || !aiRequestInFlight.compareAndSet(false, true)) return
        scanJob = viewModelScope.launch {
            _isScanning.value = true
            try {
                val pages = state.pages.toMutableList()
                for ((index, page) in state.pages.withIndex()) {
                    if (page.rawText.isBlank()) continue
                    _scanProgress.value = str(R.string.scan_progress_ai, index + 1, state.pages.size)
                    val improved = orchestrator.enhanceScannedPageText(page.rawText)
                    pages[index] = ScannedDocumentStructureReconstructor.reconstructPage(
                        pageIndex = index,
                        imageUriString = page.imageUriString,
                        rawText = improved,
                        confidence = page.confidenceScore
                    )
                }
                // Apply only if the user is still reviewing the same scan.
                _scannedReviewState.value?.let { current ->
                    if (current.pages.size == pages.size) {
                        _scannedReviewState.value = current.copy(pages = pages, isAiEnhanced = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                // The locally recognized text is kept unchanged.
                _snackBarMessage.value = str(R.string.scan_ai_enhancement_failed, aiErrorReason(e))
            } catch (e: Exception) {
                reportError("enhanceScannedTextWithAi", e)
            } finally {
                _isScanning.value = false
                _scanProgress.value = null
                aiRequestInFlight.set(false)
            }
        }
    }

    fun updateScannedPageText(pageIndex: Int, updatedText: String) {
        val current = _scannedReviewState.value ?: return
        val currentPages = current.pages.toMutableList()
        if (pageIndex in currentPages.indices) {
            val oldPage = currentPages[pageIndex]
            val updatedPage = ScannedDocumentStructureReconstructor.reconstructPage(
                pageIndex = pageIndex,
                imageUriString = oldPage.imageUriString,
                rawText = updatedText
            )
            currentPages[pageIndex] = updatedPage
            _scannedReviewState.value = current.copy(pages = currentPages)
        }
    }

    fun updateScannedDocumentTitle(newTitle: String) {
        val current = _scannedReviewState.value ?: return
        _scannedReviewState.value = current.copy(documentTitle = newTitle)
    }

    fun setActiveScanPageIndex(index: Int) {
        val current = _scannedReviewState.value ?: return
        if (index in current.pages.indices) {
            _scannedReviewState.value = current.copy(activePageIndex = index)
        }
    }

    fun removeScannedPage(pageIndex: Int) {
        val current = _scannedReviewState.value ?: return
        if (current.pages.size <= 1) {
            _snackBarMessage.value = str(R.string.scan_empty_error)
            return
        }
        val currentPages = current.pages.toMutableList()
        if (pageIndex in currentPages.indices) {
            deleteTemporaryScanImages(listOf(currentPages.removeAt(pageIndex)))
            val nextIndex = (pageIndex - 1).coerceAtLeast(0)
            _scannedReviewState.value = current.copy(pages = currentPages, activePageIndex = nextIndex)
        }
    }

    fun setSelectedScanTemplate(templateId: String?) {
        val current = _scannedReviewState.value ?: return
        _scannedReviewState.value = current.copy(selectedTemplateId = templateId)
    }

    fun dismissScanReview() {
        scanJob?.cancel()
        val pages = _scannedReviewState.value?.pages.orEmpty()
        _scannedReviewState.value = null
        deleteTemporaryScanImages(pages)
    }

    /** Scanner images are temporary: remove them once the review is finished or discarded. */
    private fun deleteTemporaryScanImages(pages: List<ScannedPageData>) {
        if (pages.isEmpty()) return
        val uris = pages.map { it.imageUriString }
        viewModelScope.launch(Dispatchers.IO) { PageImageLoader.deleteTemporaryScans(context, uris) }
    }

    fun generateDocxFromScannedDocument() {
        val reviewState = _scannedReviewState.value ?: return
        val sessionId = _currentSessionId.value
        if (_isGenerating.value || _isScanning.value) return
        if (reviewState.pages.all { it.rawText.isBlank() }) {
            // Never produce an empty document and call it a scan result.
            _snackBarMessage.value = str(R.string.scan_empty_error)
            return
        }

        launchChatOperation(sessionId) { listener ->
            listener.onStage(AiProgressStage.CREATING_DOCUMENT)
            _scanProgress.value = str(R.string.generate_word_doc)
            var createdFile: File? = null
            var documentSaved = false
            try {
                // Determine template profile
                val templateProfile = if (reviewState.selectedTemplateId != null && reviewState.selectedTemplateId != "default") {
                    val entity = database.templateDao().getTemplateById(reviewState.selectedTemplateId)
                    if (entity != null) TemplateProfile.fromJson(entity.profileJson) else TemplateProfile.defaultProfile()
                } else {
                    TemplateProfile.defaultProfile()
                }

                val templateFile = if (reviewState.selectedTemplateId != null && reviewState.selectedTemplateId != "default") {
                    val entity = database.templateDao().getTemplateById(reviewState.selectedTemplateId)
                    entity?.originalFilePath?.let { File(it) }?.takeIf { it.exists() }
                } else null

                // Convert review state to WordDocumentModel
                val wordModel = ScannedDocumentStructureReconstructor.buildWordDocumentModel(
                    title = reviewState.documentTitle,
                    pages = reviewState.pages
                )

                // Save Word Document: unique physical file, Unicode (e.g. Arabic) visible title.
                val docId = UUID.randomUUID().toString()
                val visibleTitle = DocumentFileNames.displayTitle(reviewState.documentTitle) + ".docx"
                val docxFile = withContext(Dispatchers.IO) {
                    val documentsDir = File(context.filesDir, "generated_docs").apply { mkdirs() }
                    val file = DocumentFileNames.createUniqueFile(documentsDir, reviewState.documentTitle, "docx")
                    createdFile = file
                    DocxEngine().generateDocx(wordModel, file, templateProfile, templateFile)
                    if (!file.exists() || file.length() == 0L) {
                        throw IllegalStateException("Generated DOCX file is missing or empty.")
                    }
                    file
                }

                val allText = reviewState.pages.joinToString("\n\n") { it.rawText }
                val wordCount = allText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size

                // Insert into Room DocumentDao (not after a cancellation: the file is then deleted)
                ensureActive()
                val docEntity = DocumentEntity(
                    id = docId,
                    title = visibleTitle,
                    fileType = "DOCX",
                    filePath = docxFile.absolutePath,
                    fileSize = docxFile.length(),
                    wordCount = wordCount,
                    pageOrSlideCount = reviewState.pages.size,
                    templateId = templateProfile.templateId,
                    templateName = templateProfile.name,
                    providerUsed = LOCAL_OCR_SOURCE,
                    summary = "Scanned Document (${reviewState.pages.size} pages) • ${docxFile.length() / 1024} KB",
                    textContent = allText,
                    isOrchestratorVerified = true
                )
                database.documentDao().insertDocument(docEntity)
                documentSaved = true

                // Deliver real file + clipboard
                deliveryManager.deliverBoth(
                    file = docxFile,
                    textContent = allText,
                    mimeType = DeliveryManager.MIME_DOCX,
                    title = docEntity.title,
                    openImmediately = false
                )

                // Add messages to active chat conversation
                val scanActionLabel = str(R.string.chat_scan_document)
                val userMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    isUser = true,
                    content = "$scanActionLabel (${reviewState.pages.size} pages)"
                )
                database.chatDao().insertMessage(userMsg)

                val isArabic = reviewState.pages.any { it.isArabicDetected }
                val assistantReply = if (isArabic) {
                    "تم مسح ومعالجة المستند ضوئياً بنجاح وتوليد ملف Word (DOCX) أصلي قابل للتحرير بالكامل: **${docEntity.title}** (${reviewState.pages.size} صفحات). تم نسخ المحتوى النصي إلى الحافظة وحفظ الملف في مكتبتك."
                } else {
                    "I've scanned and transformed your document into a genuine, fully editable Word document: **${docEntity.title}** (${reviewState.pages.size} pages). The text has been copied to your clipboard and the DOCX is saved in your Library."
                }

                val assistantMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    isUser = false,
                    content = assistantReply,
                    attachedDocId = docEntity.id,
                    attachedDocTitle = docEntity.title,
                    attachedDocType = "DOCX",
                    orchestratorNote = "Scanned Document • ${reviewState.pages.size} Pages"
                )
                database.chatDao().insertMessage(assistantMsg)
                database.conversationDao().updateLastMessage(sessionId, assistantReply.take(60))

                _snackBarMessage.value = str(R.string.msg_generated_copied)
                _scannedReviewState.value = null
                deleteTemporaryScanImages(reviewState.pages)
            } catch (e: CancellationException) {
                if (!documentSaved) withContext(NonCancellable + Dispatchers.IO) { createdFile?.delete() }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "generateDocxFromScannedDocument failed", e)
                if (!documentSaved) withContext(Dispatchers.IO) { createdFile?.delete() }
                _snackBarMessage.value = "Failed to generate Word document: ${e.message}"
            } finally {
                _scanProgress.value = null
            }
        }
    }

    /** Real AI operation (correct / format / translate / summarize ...) on a Library document. */
    fun executeFollowUpAiAction(actionType: String, docId: String) {
        val operation = ContentOperation.fromActionKey(actionType) ?: return
        val sessionId = _currentSessionId.value
        launchChatOperation(sessionId) { listener ->
            try {
                val doc = database.documentDao().getDocumentById(docId)
                if (doc == null) {
                    _snackBarMessage.value = str(R.string.msg_file_not_found)
                    return@launchChatOperation
                }

                val userActionPrompt = when (operation) {
                    ContentOperation.CORRECT -> str(R.string.scan_ai_spelling)
                    ContentOperation.IMPROVE_FORMAT -> str(R.string.scan_ai_format)
                    ContentOperation.TRANSLATE -> str(R.string.scan_ai_translate)
                    ContentOperation.SUMMARIZE -> str(R.string.scan_ai_summarize)
                    else -> actionType
                }

                if (database.conversationDao().getConversationById(sessionId) == null) {
                    database.conversationDao().insertConversation(
                        ConversationEntity(id = sessionId, title = str(R.string.new_conversation))
                    )
                }
                database.chatDao().insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        isUser = true,
                        content = "$userActionPrompt: ${doc.title}"
                    )
                )

                val (result, provider) = orchestrator.performContentOperation(
                    operation = operation,
                    content = doc.textContent,
                    uiLanguage = appLanguage.value,
                    listener = listener
                )
                ensureActive()

                database.chatDao().insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        isUser = false,
                        content = result,
                        attachedDocId = doc.id,
                        attachedDocTitle = doc.title,
                        attachedDocType = doc.fileType,
                        orchestratorNote = "$userActionPrompt • ${provider.name}"
                    )
                )
                database.conversationDao().updateLastMessage(sessionId, result.take(60))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _snackBarMessage.value = aiErrorMessage(e, defaultProvider.value)
            } catch (e: Exception) {
                reportError("executeFollowUpAiAction", e)
            }
        }
    }

    // ------------------------------------------------------------------ localization helpers

    /** Localized resources for the last language used (creating a configuration context each time is costly). */
    @Volatile
    private var cachedResources: Pair<String, android.content.res.Resources>? = null

    /** Resources in the app's selected language (the application context follows the system locale). */
    private fun localizedResources(): android.content.res.Resources {
        val language = appLanguage.value.ifBlank { "en" }
        cachedResources?.let { (lang, res) -> if (lang == language) return res }
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale(language))
        return context.createConfigurationContext(config).resources.also { cachedResources = language to it }
    }

    private fun str(id: Int, vararg args: Any): String =
        if (args.isEmpty()) localizedResources().getString(id) else localizedResources().getString(id, *args)

    /** Localized reason for an AI failure (provider detail appended when useful; never secrets). */
    private fun aiErrorReason(e: AiException): String {
        val base = str(aiErrorStringRes(e.kind))
        val showDetail = e.kind in setOf(
            AiErrorKind.BAD_REQUEST, AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MALFORMED_RESPONSE,
            AiErrorKind.CONTENT_BLOCKED, AiErrorKind.PERMISSION_DENIED, AiErrorKind.UNKNOWN, AiErrorKind.INVALID_CONFIGURATION
        )
        return if (showDetail && !e.detail.isNullOrBlank()) "$base (${e.detail})" else base
    }

    /** Full message: names the default provider and lets the user decide what to do next. */
    private fun aiErrorMessage(e: AiException, providerName: String): String = when (e.kind) {
        AiErrorKind.NO_DEFAULT_PROVIDER -> str(R.string.ai_err_no_default_provider)
        else -> str(R.string.ai_error_default_provider_failed, providerName.ifBlank { "?" }, aiErrorReason(e))
    }

    /**
     * Launches [block] in viewModelScope; an unexpected exception is reported through the snackbar
     * instead of propagating to the default handler (which would crash the app).
     */
    private fun launchSafely(operation: String, block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(operation, e)
            }
        }

    /** Logs an unexpected failure and shows the existing snackbar feedback instead of crashing. */
    private fun reportError(operation: String, e: Throwable) {
        Log.e(TAG, "$operation failed", e)
        _snackBarMessage.value = str(
            R.string.msg_operation_failed,
            e.message ?: e.javaClass.simpleName
        )
    }

    fun clearSnackBar() {
        _snackBarMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        voiceAssistant.shutdown()
    }
}
